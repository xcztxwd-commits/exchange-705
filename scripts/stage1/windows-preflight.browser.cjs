// Actual anonymous Windows browser preflight; normal OS root trust, no TLS error flags, warning continuation, or mocks.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_PATH||'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root=path.resolve(__dirname,'../..'),run=path.resolve(process.env.STAGE1_RUN||process.argv[2]||'');
assert.equal(process.platform,'win32');assert.ok(run.startsWith(path.join(root,'reports')+path.sep));assert.equal(JSON.parse(fs.readFileSync(path.join(run,'environment.json'))).run,path.basename(run));
const stamp=new Date().toISOString().replace(/[:.]/g,'-'),evidence=path.join(run,'browser','windows-strict-preflight-'+stamp),results=[];
fs.mkdirSync(evidence,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,chromiumSandbox:true,executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 try{
  for(const [name,url,selector] of [['control','https://control.localhost/','.control-login'],['admin','https://admin.localhost/login','.login-box'],['pc','https://default.localhost/?login=1','#pc-login-form'],['mobile','https://default.localhost/mobile/login','.auth-card']]){
   const context=await browser.newContext({viewport:{width:name==='mobile'?390:1440,height:name==='mobile'?844:1000}}),page=await context.newPage(),errors=[],consoleEvents=[];
   page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(['error','warning'].includes(m.type()))consoleEvents.push(m.text());});
   try{const response=await page.goto(url);assert.equal(response.status(),200);await page.locator(selector).waitFor();assert.ok(await page.title());assert.ok((await page.locator('body').innerText()).trim().length>20);assert.equal(await page.locator('vite-error-overlay,nextjs-portal').count(),0);assert.deepEqual(errors,[]);await page.screenshot({path:path.join(evidence,name+'.png')});results.push({name,status:'PASS',url:page.url(),title:await page.title(),consoleEvents});console.log('PASS actual Windows strict TLS '+name+' entry');}
   catch(error){results.push({name,status:'FAIL',error:error.message,consoleEvents});process.exitCode=1;console.error('FAIL '+name+' '+error.message);await page.screenshot({path:path.join(evidence,name+'-fail.png')}).catch(()=>{});}
   finally{await context.close();}
  }
 }finally{await browser.close();const result={browserTool:'Node.js Playwright / actual Windows Chrome',tls:'Normal explicitly approved OS CA trust; no error suppression',mocks:false,passed:results.filter(r=>r.status==='PASS').length,failed:results.filter(r=>r.status==='FAIL').length,skipped:0,results,evidence};fs.writeFileSync(path.join(evidence,'result.json'),JSON.stringify(result,null,2));console.log('Evidence '+evidence);}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
