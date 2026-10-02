// Actual four-entry preflight. Browser/NSS use a normally trusted, owned fixture CA; no TLS-error flags or mocks.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),net=require('node:net');
const {chromium}=require(process.env.PLAYWRIGHT_PATH);
const run=path.resolve(process.env.STAGE1_RUN),proof=JSON.parse(fs.readFileSync(process.env.STAGE1_LINUX_OWNER_PROOF));
assert.equal(process.env.STAGE1_LINUX_FIXTURE,'1');assert.equal(process.platform,'linux');assert.equal(path.basename(run),proof.run);
assert.equal(process.env.STAGE1_HOST_IP,proof.hostIp);assert.ok(net.isIP(proof.hostIp));
const stamp=new Date().toISOString().replace(/[:.]/g,'-'),evidence=path.join(run,'browser','linux-preflight-'+stamp),results=[];
fs.mkdirSync(evidence,{recursive:true});
const args=['--host-resolver-rules='+['control','admin','default','a','a2','b'].map(host=>`MAP ${host}.localhost ${proof.hostIp}`).join(', ')];
(async()=>{
 const browser=await chromium.launch({headless:true,chromiumSandbox:true,args,executablePath:process.env.CHROME_PATH});
 try{
  const context=await browser.newContext(),page=await context.newPage();
  try{await assert.rejects(page.goto('https://host.docker.internal/'),/ERR_CERT_COMMON_NAME_INVALID/);results.push({name:'Strict TLS rejects a hostname outside certificate SAN',status:'PASS'});console.log('PASS strict TLS hostname rejection');}
  catch(e){results.push({name:'Strict TLS hostname rejection',status:'FAIL',error:e.message});process.exitCode=1;}
  finally{await context.close();}
  for(const [name,url,selector] of [['control','https://control.localhost/','.control-login'],['admin','https://admin.localhost/login','.login-box'],['pc','https://default.localhost/?login=1','#pc-login-form'],['mobile','https://default.localhost/mobile/login','.auth-card']]){
   const own=await browser.newContext({viewport:{width:name==='mobile'?390:1440,height:name==='mobile'?844:1000}}),p=await own.newPage(),errors=[];
   p.on('pageerror',e=>errors.push(e.message));
   try{const response=await p.goto(url);assert.equal(response.status(),200);await p.locator(selector).waitFor();assert.ok((await p.locator('body').innerText()).length>20);assert.equal(await p.locator('vite-error-overlay').count(),0);assert.deepEqual(errors,[]);await p.screenshot({path:path.join(evidence,name+'.png')});results.push({name,status:'PASS'});console.log('PASS actual strict TLS '+name+' entry');}
   catch(e){results.push({name,status:'FAIL',error:e.message});process.exitCode=1;console.error('FAIL '+name+' '+e.message);await p.screenshot({path:path.join(evidence,name+'-fail.png')}).catch(()=>{});}
   finally{await own.close();}
  }
 }finally{await browser.close();fs.writeFileSync(path.join(evidence,'result.json'),JSON.stringify({browserTool:'Node.js Playwright / actual Chromium',tls:'Normal OS and user NSS fixture CA trust; no error suppression',mocks:false,passed:results.filter(r=>r.status==='PASS').length,failed:results.filter(r=>r.status==='FAIL').length,skipped:0,results,evidence},null,2));console.log('Evidence '+evidence);}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
