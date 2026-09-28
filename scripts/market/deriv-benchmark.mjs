import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { setTimeout as sleep } from 'node:timers/promises';

const stats = values => {
  const a = values.filter(Number.isFinite).sort((a,b)=>a-b);
  const q = p => a.length ? a[Math.ceil(p*a.length)-1] : null;
  return {n:a.length,min:a[0]??null,p50:q(.5),p95:q(.95),p99:q(.99),max:a.at(-1)??null};
};
if(process.argv.includes('--self-test')) {
  assert.deepEqual(stats([3,1,2]).p50,2);
  assert.equal(stats([]).p95,null);
  assert.equal(stats([NaN,4]).n,1);
  console.log('self-test passed');
  process.exit(0);
}
const endpoint = process.env.DERIV_WS_URL || 'wss://api.derivws.com/trading/v1/options/ws/public';
const out = path.resolve('reports',`deriv-${new Date().toISOString().replaceAll(':','-')}`);
fs.mkdirSync(out,{recursive:true});
const log = fs.createWriteStream(path.join(out,'events.ndjson'));
const record = x => log.write(JSON.stringify({local_ms:Date.now(),...x})+'\n');
const save = (name,data) => { const p=path.join(out,name); fs.writeFileSync(p+'.tmp',JSON.stringify(data,null,2)); fs.renameSync(p+'.tmp',p); };
let seq=0, nextSend=0, halted=false;
const clients = new Set();
// Global pacing, including across connections: <=18 non-ping requests/minute; conservative after observed tick-specific RateLimit.
async function pace() { const at=Math.max(Date.now(),nextSend); nextSend=at+3500; await sleep(at-Date.now()); }
class Client {
  constructor(label) {
    this.label=label;this.pending=new Map();this.errors=[];this.ticks=[];this.pings=[];this.closes=[];this.expectedClose=false;
    const start=performance.now();this.ws=new WebSocket(endpoint);clients.add(this);
    this.ready=new Promise((resolve,reject)=>{
      const timeout=setTimeout(()=>{record({label,event:'connection-timeout'});reject(new Error('connection timeout'));this.ws.close();},30000);
      this.ws.onopen=()=>{clearTimeout(timeout);this.open_ms=performance.now()-start;record({label,event:'open',open_ms:this.open_ms});resolve();};
      this.ws.onerror=e=>{clearTimeout(timeout);const message=e.message||'WebSocket error';this.errors.push({message});reject(new Error(message));};
    });
    this.ws.onclose=e=>{const c={code:e.code,reason:e.reason,expected:this.expectedClose};this.closes.push(c);record({label,event:'close',...c});for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(new Error('connection closed'));}this.pending.clear();};
    this.ws.onmessage=e=>{
      const mono=performance.now(),wall=Date.now();let data;
      try {data=JSON.parse(e.data);} catch {this.errors.push({message:'invalid JSON'});return;}
      record({label,event:'receive',mono_ms:mono,data});
      if(data.error){this.errors.push(data.error);if(/rate|limit|too.many/i.test(data.error.code+' '+data.error.message))halted=true;}
      if(data.tick)this.ticks.push({mono,wall,bytes:Buffer.byteLength(e.data),...data.tick});
      const p=this.pending.get(data.req_id);
      if(p){clearTimeout(p.timer);this.pending.delete(data.req_id);const result={data,rtt:mono-p.mono,start_wall:p.wall,end_wall:wall};if(data.msg_type==='ping')this.pings.push({mono,rtt:result.rtt});p.resolve(result);}
    };
  }
  async request(body) {
    if(!body.ping)await pace();
    if(halted&&!body.ping)throw new Error('Stopped on service limit response');
    if(this.ws.readyState!==WebSocket.OPEN)throw new Error('socket not open');
    const req_id=++seq;
    return new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>{this.pending.delete(req_id);reject(new Error(`response timeout ${JSON.stringify(body)}`));},15000);
      this.pending.set(req_id,{resolve,reject,timer,mono:performance.now(),wall:Date.now()});
      record({label:this.label,event:'send',data:{...body,req_id}});
      this.ws.send(JSON.stringify({...body,req_id}));
    });
  }
  async close(){this.expectedClose=true;if(this.ws.readyState<2){const done=new Promise(r=>this.ws.addEventListener('close',r,{once:true}));this.ws.close(1000,'benchmark complete');await Promise.race([done,sleep(2000)]);}clients.delete(this);}
}
function summarize(cs,start,end,requested) {
  const seconds=(end-start)/1000;
  const all=cs.flatMap(c=>c.ticks.filter(t=>t.mono>=start&&t.mono<end));
  const perSymbol={};
  for(const symbol of new Set(requested)){
    const ticks=all.filter(t=>t.symbol===symbol);
    const gaps=[],epochGaps=[];
    for(const c of cs){const ts=c.ticks.filter(t=>t.symbol===symbol&&t.mono>=start&&t.mono<end);for(let i=1;i<ts.length;i++){gaps.push(ts[i].mono-ts[i-1].mono);epochGaps.push((ts[i].epoch-ts[i-1].epoch)*1000);}}
    perSymbol[symbol]={ticks:ticks.length,aggregate_hz:ticks.length/seconds,receive_interval_ms:stats(gaps),source_interval_ms:stats(epochGaps),apparent_age_ms:stats(ticks.map(t=>t.wall-t.epoch*1000))};
  }
  return {seconds,connections:cs.length,requested_symbols:[...new Set(requested)],received_symbols:Object.values(perSymbol).filter(x=>x.ticks).length,ticks:all.length,ticks_per_second:all.length/seconds,payload_bytes_per_second:all.reduce((s,t)=>s+t.bytes,0)/seconds,unique_symbol_epoch_quote:new Set(all.map(t=>`${t.symbol}/${t.epoch}/${t.quote}`)).size,ping_rtt_ms:stats(cs.flatMap(c=>c.pings.filter(p=>p.mono>=start&&p.mono<end).map(p=>p.rtt))),apparent_age_ms:stats(all.map(t=>t.wall-t.epoch*1000)),per_connection:cs.map(c=>({label:c.label,open_ms:c.open_ms,ticks:c.ticks.filter(t=>t.mono>=start&&t.mono<end).length,errors:c.errors,closes:c.closes})),per_symbol:perSymbol};
}
async function measure(cs,seconds,requested) {
  await sleep(3000); // Exclude subscription snapshots and setup from the common window.
  const start=performance.now();
  const ping=()=>Promise.all(cs.map(c=>c.request({ping:1}).catch(e=>c.errors.push({message:e.message}))));
  await ping();
  const timer=setInterval(()=>void ping(),5000);
  try {await sleep(Math.max(0,seconds*1000-(performance.now()-start)));}finally{clearInterval(timer);}
  return summarize(cs,start,performance.now(),requested);
}
const result={started_utc:new Date().toISOString(),endpoint,node:process.version,platform:process.platform,request_spacing_ms:3500,connection_ceiling:16,stages:[]};
const connectionsOnly=process.argv.includes('--connections-only');
result.connections_only=connectionsOnly;
try {
  let names=[];
  if(!connectionsOnly) {
  const c=new Client('inventory');await c.ready;
  const inventory=await c.request({active_symbols:'brief'});
  if(inventory.data.error)throw new Error(JSON.stringify(inventory.data.error));
  const symbols=inventory.data.active_symbols;
  assert(Array.isArray(symbols)&&symbols.length>0);
  save('active-symbols.json',symbols);
  result.inventory={count:symbols.length,rtt_ms:inventory.rtt,markets:{}};
  for(const s of symbols)result.inventory.markets[s.market]=(result.inventory.markets[s.market]||0)+1;
  result.clock_samples=[];
  for(let i=0;i<5;i++){const r=await c.request({time:1});result.clock_samples.push({...r,offset_lower_ms:r.data.time*1000-r.end_wall,offset_upper_ms:(r.data.time+1)*1000-r.start_wall});}
  names=symbols.map(s=>s.underlying_symbol||s.symbol);
  result.subscriptions=[];
  for(const symbol of names){const r=await c.request({ticks:symbol,subscribe:1});result.subscriptions.push({symbol,rtt_ms:r.rtt,success:!!r.data.tick,error:r.data.error});if(halted)break;}
  const full=await measure([c],60,names);full.name='all-symbols-one-connection';result.stages.push(full);
  await c.close();save('summary.json',result);
  console.log(JSON.stringify({inventory:result.inventory,stage:full.name,hz:full.ticks_per_second,received:full.received_symbols}));
  }
  const testSymbols=['1HZ100V','R_100','frxEURUSD'].filter(s=>names.includes(s));
  if(!connectionsOnly&&!testSymbols.length)throw new Error('No benchmark symbols available');
  for(const n of [1,4,8,16]){
    if(halted)break;
    const cs=[];
    try {
      for(let i=0;i<n;i++){
        const conn=new Client(`concurrency-${n}-${i}`);cs.push(conn);await conn.ready;
        for(const symbol of testSymbols){const r=await conn.request({ticks:symbol,subscribe:1});if(r.data.error)throw new Error(JSON.stringify(r.data.error));}
      }
      const stage=await measure(cs,45,testSymbols);stage.name=`concurrency-${n}`;
      result.stages.push(stage);
      console.log(JSON.stringify({stage:stage.name,hz:stage.ticks_per_second,rtt:stage.ping_rtt_ms,errors:cs.flatMap(c=>c.errors)}));
    } finally {await Promise.all(cs.map(c=>c.close()));save('summary.json',result);}
  }
} catch(e){result.fatal_error=e.stack;console.error(e.stack);process.exitCode=1;}
finally {
  await Promise.all([...clients].map(c=>c.close()));
  result.finished_utc=new Date().toISOString();result.stopped_on_limit=halted;
  save('summary.json',result);
  await new Promise(resolve=>log.end(resolve));
  console.log('OUTPUT '+out);
}
