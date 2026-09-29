// Run after live-audit.cjs --keep-fixtures, before its --cleanup.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {execFileSync}=require('node:child_process');
const file=path.join(os.tmpdir(),'705-permission-audit-state.json'), s=JSON.parse(fs.readFileSync(file));
const base='http://127.0.0.1:17051', checks=[];
const sql=q=>execFileSync('docker',['exec','-i','exchange-705-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -D1090 -N -B'],{input:q,encoding:'utf8'}).trim();
async function req(actor,method,url,body){const r=await fetch(base+url,{method,headers:{'Content-Type':'application/json',...(actor?{Authorization:'Bearer '+s.actors[actor].token}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});let data;try{data=await r.json()}catch{data={}}return {status:r.status,data};}
function record(name,r,expected){checks.push({name,status:r.status,expected,pass:r.status===expected});}
(async()=>{
  // Refresh dedicated sessions after browser logins; never use pre-existing user accounts.
  for(const actor of ['reader','super','empty','agentA','agentB']) {const r=await req('','POST','/api/admin/auth/login',{account:s.actors[actor].account,password:s.password});if(!r.data.token)throw Error('Cannot refresh '+actor);s.actors[actor].token=r.data.token;}
  fs.writeFileSync(file,JSON.stringify(s));
  const paths=['dashboard/stats','dashboard/chart-data','users','symbols/categories','ai-control/symbols','durations','deposit/settings','deposit/review/list','deposit/orders/list','withdraw/list','loan/settings','loan/review/list','loan/personal-info/list','kyc/list','financial/products','financial/orders','announcement/list','roles','admins','website-security','config/list','operation-logs','statistics'];
  for(const actor of ['empty','reader','agentA','agentB']) for(const p of paths) {
    const expected=actor==='reader'&&p==='durations'||actor.startsWith('agent')&&p==='users'?200:403;
    record(actor+' GET '+p,await req(actor,'GET','/api/admin/'+p),expected);
  }
  const own=Number(s.userIds[2]),other=Number(s.userIds[3]);
  for(const actor of ['agentA','agentB']) {
    const self=actor==='agentA'?own:other, cross=actor==='agentA'?other:own;
    for(const suffix of ['fund-details']) {
      record(actor+' cross '+suffix,await req(actor,'GET',`/api/admin/users/${cross}/${suffix}`),403);
    }
    for(const suffix of ['bank-cards','digital-addresses']) record(actor+' cross wallet '+suffix,await req(actor,'GET',`/api/admin/wallet/${cross}/${suffix}`),403);
    record(actor+' other permissions',await req(actor,'GET',`/api/admin/users/${actor==='agentA'?s.userIds[1]:s.userIds[0]}/menus`),403);
  }
  const made=await req('super','POST','/api/admin/durations',{duration:1800000000+Math.floor(Math.random()*1000000),label:s.prefix,enabled:false});
  if(!made.data.data?.id)throw Error('Missing disabled fixture');
  const id=made.data.data.id;
  const del=await req('reader','DELETE','/api/admin/durations/'+id);
  record('read-only deletes existing disabled test record',del,403);
  checks.push({name:'read-only deletion must leave test record',expected:'1',actual:sql(`SELECT COUNT(*) FROM option_duration WHERE id=${Number(id)};`)});
  checks.at(-1).pass=checks.at(-1).actual===checks.at(-1).expected;
  const normal=await req('','POST','/api/admin/auth/login',{account:s.prefix+'_childA@example.invalid',password:s.password});
  checks.push({name:'ordinary user cannot use admin login',pass:!normal.data.token,status:normal.status});
  const counts=sql('SELECT COUNT(*) FROM menu_action; SELECT COUNT(*) FROM admin_menu WHERE menu_type=\'button\';');
  const out={base,checks,total:checks.length,passed:checks.filter(c=>c.pass).length,failed:checks.filter(c=>!c.pass).length,catalogCounts:counts};
  fs.writeFileSync(path.resolve(__dirname,'../../reports/permission-live-supplement-20260929.json'),JSON.stringify(out,null,2));
  console.log(JSON.stringify({...out,checks:checks.filter(c=>!c.pass)},null,2));
  if(out.failed) process.exitCode=1;
})().catch(e=>{console.error(e.message);process.exitCode=1});
