// Repeatable Phase 2 supplier INPUTS only. No application/funds responses are mocked.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
function reply(url){
 const u=new URL(url,'http://127.0.0.1:33632');
 if(!u.pathname.startsWith('/_stage2_inputs/'))return null;
 const now=Date.now(),seconds=Math.floor(now/1000);
 const known=s=>s==='BTCUSDT'||/^(EUR|GBP|AUD|CAD|CHF|NZD|JPY|SGD|HKD|CNY|KRW|INR|THB|MYR|IDR|VND|PHP|RUB|BRL|MXN|ZAR|TRY|AED|SAR|TWD|NOK|SEK|DKK|PLN|HUF|CZK)(USD)?=X$/.test(s);
 const price=s=>s==='BTCUSDT'?110:1;
 if(u.pathname.endsWith('/exchangeInfo'))return {timezone:'UTC',serverTime:now,symbols:[{symbol:'BTCUSDT',status:'TRADING',baseAsset:'BTC',quoteAsset:'USDT',isSpotTradingAllowed:true,filters:[{filterType:'PRICE_FILTER',tickSize:'0.01'},{filterType:'LOT_SIZE',minQty:'0.01',stepSize:'0.01'}]}]};
 if(u.pathname.endsWith('/ticker/24hr')){
  const symbols=u.searchParams.has('symbols')?JSON.parse(u.searchParams.get('symbols')):[u.searchParams.get('symbol')];
  if(!Array.isArray(symbols)||!symbols.length||symbols.some(s=>!known(s)))return null;
  const rows=symbols.map(s=>({symbol:s,lastPrice:String(price(s)),openPrice:String(price(s)),closeTime:now}));
  return u.searchParams.has('symbols')?rows:rows[0];
 }
 if(u.pathname.endsWith('/klines')){
  const s=u.searchParams.get('symbol');if(!known(s))return null;
  const limit=Math.min(1000,Math.max(1,Number(u.searchParams.get('limit'))||100)),p=price(s);
  const spans={'1m':60000,'3m':180000,'5m':300000,'15m':900000,'30m':1800000,'1h':3600000,'2h':7200000,'4h':14400000,'6h':21600000,'8h':28800000,'12h':43200000,'1d':86400000,'3d':259200000,'1w':604800000};
  const interval=u.searchParams.get('interval')||'1m',span=spans[interval];
  const end=u.searchParams.has('endTime')?Number(u.searchParams.get('endTime')):now;
  if(!Number.isSafeInteger(end)||end<946684800000||(!span&&interval!=='1M'))return null;
  const monthly=interval==='1M',date=new Date(end),anchor=monthly?Date.UTC(date.getUTCFullYear(),date.getUTCMonth(),1):Math.floor((end-(interval==='1w'?345600000:0))/span)*span+(interval==='1w'?345600000:0);
  return Array.from({length:limit},(_,i)=>{const offset=limit-i-1,start=monthly?Date.UTC(date.getUTCFullYear(),date.getUTCMonth()-offset,1):anchor-offset*span,close=monthly?Date.UTC(date.getUTCFullYear(),date.getUTCMonth()-offset+1,1)-1:start+span-1;return[start,String(p),String(p),String(p),String(p),'10',close,'1100'];});
 }
 if(u.pathname.endsWith('/spark')){
  const symbols=(u.searchParams.get('symbols')||'').split(',');if(symbols.some(s=>!known(s)))return null;
  return Object.fromEntries(symbols.map(s=>[s,{symbol:s,timestamp:[seconds],close:[price(s)],chartPreviousClose:price(s)}]));
 }
 if(u.pathname.includes('/chart/')){
  const s=decodeURIComponent(u.pathname.split('/chart/')[1]);if(!known(s))return null;
  const p=price(s),n=200,t=Array.from({length:n},(_,i)=>seconds-(n-i)*60),v=Array(n).fill(p);
  return {chart:{error:null,result:[{meta:{symbol:s,currency:'USD',regularMarketPrice:p,exchangeTimezoneName:'UTC'},timestamp:t,indicators:{quote:[{open:v,high:v,low:v,close:v,volume:Array(n).fill(10)}]}}]}};
 }
 return null;
}
function handler(home,spec){
 const parent=path.dirname(path.dirname(home));
 if(path.basename(parent)!=='stage2-'+spec.run.slice(7)||spec.stage2QuoteInput!==spec.run)throw Error('Exact owned Stage 2 quote fixture opt-in required');
 const log=path.join(parent,'logs','quote-input-requests.jsonl');
 return (req,res)=>{try{const result=reply(req.url);fs.appendFileSync(log,JSON.stringify({time:new Date().toISOString(),source:'OWNED_SYNTHETIC_SUPPLIER_INPUT_NOT_LIVE_MARKET',path:new URL(req.url,'http://localhost').pathname,accepted:result!==null})+'\n');res.writeHead(result===null?(req.url.split('?')[0].endsWith('/ticker/24hr')?400:503):200,{'Content-Type':'application/json','Cache-Control':'no-store','X-Phase2-Input':'synthetic-no-live-provider'});res.end(JSON.stringify(result===null?{error:'No owned quote input for route'}:result));}catch(e){res.writeHead(400);res.end('Invalid owned quote request');}};
}
if(require.main===module){assert.equal(reply('/unavailable'),null);assert.equal(reply('/_stage2_inputs/api/v3/exchangeInfo').symbols[0].symbol,'BTCUSDT');assert.equal(reply('/_stage2_inputs/api/v3/ticker/24hr?symbol=BTCUSDT').lastPrice,'110');assert.equal(reply('/_stage2_inputs/api/v3/ticker/24hr?symbol=UNKNOWN'),null);assert.equal(reply('/_stage2_inputs/spark?symbols=EURUSD%3DX')['EURUSD=X'].close[0],1);const history=reply('/_stage2_inputs/api/v3/klines?symbol=BTCUSDT&interval=5m&limit=3&endTime=1700000000000');assert.equal(history.length,3);assert.equal(history[2][0],Math.floor(1700000000000/300000)*300000);assert.equal(history[1][0]-history[0][0],300000);assert.equal(history[2][6]-history[2][0],299999);assert.equal(history[0][4],'110');const prior=reply('/_stage2_inputs/api/v3/klines?symbol=BTCUSDT&interval=5m&limit=3&endTime='+(history[0][0]-1));assert.ok(prior[2][0]<history[0][0]);assert.equal(reply('/_stage2_inputs/api/v3/klines?symbol=BTCUSDT&interval=INVALID'),null);const month=reply('/_stage2_inputs/api/v3/klines?symbol=BTCUSDT&interval=1M&limit=2&endTime=1700000000000');assert.equal(new Date(month[0][0]).getUTCDate(),1);assert.equal(month[0][6]+1,month[1][0]);console.log('Owned synthetic quote protocol self-check PASS: exact historical cursor and interval, same deterministic 110 input');}
module.exports={handler};
