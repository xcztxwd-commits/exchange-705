// Real HTTP / MySQL audit. Only uniquely named disposable fixtures are mutated.
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const {execFileSync} = require('node:child_process');
const {randomBytes} = require('node:crypto');
const base = 'http://127.0.0.1:17051';
const root = path.resolve(__dirname, '../..');
const statePath = path.join(os.tmpdir(), '705-permission-audit-state.json');
const reportPath = path.join(root, 'reports/permission-live-audit-20260929.json');
const sql = query => execFileSync('docker', ['exec','-i','exchange-705-mysql-1','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -D1090 -N -B'], {input: query, encoding:'utf8'}).trim();
const quote = s => "'" + String(s).replaceAll("'", "''") + "'";
let state, results = [];
function cleanup() {
  const p = state.prefix;
  if (!/^qa_perm_[a-f0-9]{8}$/.test(p)) throw Error('Unsafe cleanup prefix');
  sql(`START TRANSACTION;
    DELETE a FROM user_action a JOIN user_account u ON a.user_id=u.id WHERE u.email LIKE '${p}%@example.invalid';
    DELETE m FROM user_menu m JOIN user_account u ON m.user_id=u.id WHERE u.email LIKE '${p}%@example.invalid';
    DELETE FROM user_account WHERE email LIKE '${p}%@example.invalid';
    DELETE FROM admin_user WHERE account LIKE '${p}%';
    DELETE m FROM admin_role_menu m JOIN admin_role r ON m.role_id=r.id WHERE r.role_code LIKE '${p}%';
    DELETE FROM admin_role WHERE role_code LIKE '${p}%';
    DELETE FROM option_duration WHERE label LIKE '${p}%';
    DELETE FROM system_config WHERE config_key='${p}';
    COMMIT;`);
  const remaining = sql(`SELECT (SELECT COUNT(*) FROM admin_user WHERE account LIKE '${p}%')+(SELECT COUNT(*) FROM admin_role WHERE role_code LIKE '${p}%')+(SELECT COUNT(*) FROM user_account WHERE email LIKE '${p}%@example.invalid')+(SELECT COUNT(*) FROM option_duration WHERE label LIKE '${p}%');`);
  if (remaining !== '0') throw Error('Fixture cleanup incomplete: '+remaining);
  fs.rmSync(statePath, {force:true});
  return {remaining:0, auditLogsRetained:true};
}
async function req(actor, method, url, body) {
  const token = state.actors[actor]?.token;
  const r = await fetch(base+url, {method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)});
  const text=await r.text(); let data; try {data=JSON.parse(text)} catch {data={raw:text.slice(0,100)}}
  return {status:r.status,data};
}
function check(name, actual, expected, detail={}) { results.push({name,pass:actual===expected,actual,expected,...detail}); }
async function test(name, actor, method, url, body, expected=200) {
  const r=await req(actor,method,url,body); check(name,r.status,expected,{actor,method,path:url,businessSuccess:r.data.success});
  if(expected===200 && r.data.success===false) check(name+' business success',false,true,{message:r.data.message});
  return r.data;
}
async function login(actor) {
  const a=state.actors[actor], r=await req('', 'POST','/api/admin/auth/login',{account:a.account,password:state.password});
  check(actor+' password login',r.status,200); if(!r.data.token) throw Error('Login failed: '+actor+' '+JSON.stringify(r.data));
  a.token=r.data.token; a.user=r.data.user; return a.token;
}
async function main() {
  if(process.argv.includes('--cleanup')) {state=JSON.parse(fs.readFileSync(statePath)); const c=cleanup(); const report=JSON.parse(fs.readFileSync(reportPath)); report.cleanup=c; fs.writeFileSync(reportPath,JSON.stringify(report,null,2)); console.log(JSON.stringify(c)); return;}
  if(fs.existsSync(statePath)) throw Error('Previous fixtures exist. Run --cleanup first.');
  const prefix='qa_perm_'+randomBytes(4).toString('hex');
  state={prefix,password:randomBytes(20).toString('base64url'),actors:{},roles:{},startedAt:new Date().toISOString()};
  fs.writeFileSync(statePath,JSON.stringify(state));
  let cleaned;
  try {
    const hash=execFileSync('python',['-c','import bcrypt,sys;print(bcrypt.hashpw(sys.stdin.read().encode(),bcrypt.gensalt()).decode())'],{input:state.password,encoding:'utf8'}).trim();
    state.actors.super={account:prefix+'_super'};
    sql(`INSERT INTO admin_user(account,email,password_hash,role,enabled,row_version,created_at,updated_at) VALUES (${quote(state.actors.super.account)},'${prefix}_super@example.invalid',${quote(hash)},'super_admin',1,0,NOW(),NOW());`);
    await login('super');
    const catalog=await test('super reads catalog','super','GET','/api/admin/menus');
    const menus=catalog.list; if(!Array.isArray(menus)) throw Error('Catalog shape unavailable');
    const mid=code=>{const m=menus.find(m=>m.menuCode===code);return m?.id;};
    check('button permission catalog exists',menus.some(m=>m.menuCode==='durations:create'),true);
    const profiles={empty:[],reader:['durations'],creator:['durations','durations:create'],editor:['durations','durations:edit'],deleter:['durations','durations:delete'],delegator:['roles','roles:create','roles:edit','roles:delete','roles:assign_permission','admin_list','admin_list:create','admin_list:edit','admin_list:delete','admin_list:status','admin_list:detail']};
    for(const [name,codes] of Object.entries(profiles)) {
      const code=prefix+'_'+name;
      const r=await test(name+' create role','super','POST','/api/admin/roles',{roleName:code,roleCode:code,status:'active',isSuper:false});
      if(!r.data?.id) throw Error('Role creation failed');
      state.roles[name]={id:r.data.id,code,codes};
      await test(name+' grant permissions','super','POST',`/api/admin/roles/${r.data.id}/menus`,{menuIds:codes.map(mid).filter(Boolean)});
      const a=await test(name+' create account','super','POST','/api/admin/admins',{account:code,email:code+'@example.invalid',password:state.password,role:code,enabled:true});
      if(!a.data?.id) {
        sql(`INSERT INTO admin_user(account,email,password_hash,role,enabled,row_version,created_at,updated_at) VALUES ('${code}','${code}@example.invalid',${quote(hash)},'${code}',1,0,NOW(),NOW());`);
        a.data={id:Number(sql(`SELECT id FROM admin_user WHERE account='${code}';`))};
      }
      state.actors[name]={account:code,id:a.data.id}; await login(name);
    }
    for(const name of Object.keys(profiles)) {
      const snapshot=await test(name+' snapshot',''+name,'GET','/api/admin/menus/current');
      check(name+' snapshot menu count',snapshot.menus?.length,profiles[name].filter(c=>!c.includes(':')).length);
      await test(name+' read durations',name,'GET','/api/admin/durations',undefined,profiles[name].includes('durations')?200:403);
      await test(name+' unrelated statistics denied',name,'GET','/api/admin/statistics',undefined,403);
    }
    await test('anonymous denied','','GET','/api/admin/durations',undefined,401);
    const duration={duration:1700000000+Math.floor(Math.random()*10000000),label:prefix,enabled:false,profitRate:0.2,lossRate:1,sortOrder:99999};
    const unauthorizedCreate=await req('reader','POST','/api/admin/durations',duration);
    check('reader create denied',unauthorizedCreate.status,403);
    if(unauthorizedCreate.data.data?.id) sql(`DELETE FROM option_duration WHERE id=${Number(unauthorizedCreate.data.data.id)} AND label='${prefix}';`);
    const created=await test('creator create allowed','creator','POST','/api/admin/durations',duration);
    const id=created.data?.id; if(!id) throw Error('No duration fixture');
    for(const name of ['empty','reader','creator','editor','deleter']) {
      await test(name+' edit duration',name,'PUT',`/api/admin/durations/${id}`,{label:prefix},name==='editor'?200:403);
      if(name!=='deleter') await test(name+' delete duration denied',name,'DELETE','/api/admin/durations/9223372036854775000',undefined,403);
    }
    await test('deleter delete allowed','deleter','DELETE',`/api/admin/durations/${id}`);
    await test('revoke creator button','super','POST',`/api/admin/roles/${state.roles.creator.id}/menus`,{menuIds:[mid('durations')]});
    await test('same token revoked button denied','creator','POST','/api/admin/durations',duration,403);
    await test('restore creator button','super','POST',`/api/admin/roles/${state.roles.creator.id}/menus`,{menuIds:profiles.creator.map(mid).filter(Boolean)});
    await test('role escalation blocked','delegator','POST',`/api/admin/roles/${state.roles.empty.id}/menus`,{menuIds:[mid('settings'),mid('settings:save')].filter(Boolean)},403);
    await test('super creation blocked','delegator','POST','/api/admin/admins',{account:prefix+'_evil',email:prefix+'_evil@example.invalid',password:state.password,role:'super_admin'},403);
    await test('super edit blocked','delegator','PUT',`/api/admin/admins/${state.actors.super.user.id}`,{account:prefix+'_super'},403);
    const orphan=await req('super','POST',`/api/admin/roles/${state.roles.empty.id}/menus`,{menuIds:[mid('durations:create')||-987654322]});
    check('orphan button rejected',orphan.data.success,false);
    const missing=await req('super','POST',`/api/admin/roles/${state.roles.empty.id}/menus`,{menuIds:[-987654321]}); check('unknown menu rejected',missing.data.success,false);
    const bound=await req('super','DELETE',`/api/admin/roles/${state.roles.empty.id}`); check('bound role delete rejected',bound.data.success,false);
    const old=state.actors.reader.token; await login('reader');
    state.actors.old={token:old}; await test('second login invalidates old token','old','GET','/api/admin/menus/current',undefined,401); delete state.actors.old;
    await test('disable account','super','PUT',`/api/admin/admins/${state.actors.reader.id}/status`,{enabled:false});
    await test('disabled session rejected','reader','GET','/api/admin/menus/current',undefined,401);
    const disabled=await req('','POST','/api/admin/auth/login',{account:state.actors.reader.account,password:state.password}); check('disabled password login rejected',Boolean(disabled.data.token),false);
    await test('enable account','super','PUT',`/api/admin/admins/${state.actors.reader.id}/status`,{enabled:true}); await login('reader');
    await test('disable role','super','PUT',`/api/admin/roles/${state.roles.reader.id}`,{roleName:state.roles.reader.code,status:'inactive'});
    await test('disabled role denied same token','reader','GET','/api/admin/durations',undefined,403);
    await test('restore role','super','PUT',`/api/admin/roles/${state.roles.reader.id}`,{roleName:state.roles.reader.code,status:'active'});
    const uid=Date.now(); state.userIds=[uid,uid+1,uid+2,uid+3];
    for(const [i,name] of ['agentA','agentB','childA','childB'].entries()) {
      sql(`INSERT INTO user_account(id,email,password_hash,user_type,status,parent_user_id,row_version) VALUES (${uid+i},'${prefix}_${name}@example.invalid',${quote(hash)},'${i<2?'agent':'normal'}','normal',${i<2?'NULL':uid+i-2},0);`);
      if(i<2) { state.actors[name]={id:uid+i,account:prefix+'_'+name+'@example.invalid'}; await login(name);
        await test(name+' grant user scope','super','POST',`/api/admin/users/${uid+i}/menus`,{menuIds:[mid('users')],actions:{[mid('users')]:['detail','modify_remark']}});
      }
    }
    for(const [name,own,other] of [['agentA',uid+2,uid+3],['agentB',uid+3,uid+2]]) {
      await test(name+' own child detail',name,'GET',`/api/admin/users/${own}`);
      await test(name+' cross child detail denied',name,'GET',`/api/admin/users/${other}`,undefined,403);
      await test(name+' own remark',name,'POST',`/api/admin/users/${own}/update-remark`,{remark:prefix});
      await test(name+' cross remark denied',name,'POST',`/api/admin/users/${other}/update-remark`,{remark:'blocked'},403);
      await test(name+' cross scope filter denied',name,'GET',`/api/admin/users?filterAgentId=${name==='agentA'?uid+1:uid}`,undefined,403);
      await test(name+' roles forbidden',name,'GET','/api/admin/roles',undefined,403);
      await test(name+' super profile forbidden',name,'PUT','/api/admin/auth/profile/account',{account:prefix+'_bad'},403);
      const list=await test(name+' scoped user list',name,'GET','/api/admin/users?page=1&size=100');
      const rows=list.list || list.data?.list || list.content;
      check(name+' list only own children',Array.isArray(rows)&&rows.every(u=>Number(u.parentUserId)===state.actors[name].id),true,{rowCount:rows?.length});
    }
    check('cross writes left owned data intact',sql(`SELECT COUNT(*) FROM user_account WHERE id IN (${uid+2},${uid+3}) AND remark='${prefix}';`),'2');
    await test('agent revoke omitted actions','super','POST',`/api/admin/users/${uid}/menus`,{menuIds:[mid('users')],actions:{}});
    await test('agent same token revoked action','agentA','POST',`/api/admin/users/${uid+2}/update-remark`,{remark:'blocked'},403);
    await test('empty account write balance denied','empty','POST','/api/admin/users/updateBalance',{userId:uid+2,fundBalance:1},403);
    await test('empty account batch config denied','empty','POST','/api/admin/config/saveBatch',[{key:prefix,value:'1'}],403);
    await test('reader change password','reader','PUT','/api/admin/auth/profile/password',{oldPassword:state.password,newPassword:state.password+'x'});
    await test('old token after password change','reader','GET','/api/admin/menus/current',undefined,401);
    sql(`UPDATE admin_user SET password_hash=${quote(hash)},current_token=NULL WHERE id=${state.actors.reader.id};`); await login('reader');
    const tampered=state.actors.reader.token.split('.'); const claims=JSON.parse(Buffer.from(tampered[1],'base64url')); claims.role='super_admin'; tampered[1]=Buffer.from(JSON.stringify(claims)).toString('base64url');
    state.actors.tampered={token:tampered.join('.')}; await test('tampered JWT denied','tampered','GET','/api/admin/roles',undefined,401); delete state.actors.tampered;
  } catch(e) {results.push({name:'execution',pass:false,error:e.message});}
  finally {
    fs.writeFileSync(statePath,JSON.stringify(state));
    if(!process.argv.includes('--keep-fixtures')) {try {cleaned=cleanup();}catch(e){cleaned={error:e.message};}}
    const report={base,startedAt:state.startedAt,finishedAt:new Date().toISOString(),prefix:state.prefix,accounts:Object.keys(state.actors),total:results.length,passed:results.filter(r=>r.pass).length,failed:results.filter(r=>!r.pass).length,cleanup:cleaned||'pending browser verification',results};
    fs.writeFileSync(reportPath,JSON.stringify(report,null,2));
    console.log(JSON.stringify({report:reportPath,total:report.total,passed:report.passed,failed:report.failed,failures:results.filter(r=>!r.pass),cleanup:report.cleanup},null,2));
    if(report.failed || cleaned?.error) process.exitCode=1;
  }
}
main().catch(e=>{console.error(e.message);process.exitCode=1});
