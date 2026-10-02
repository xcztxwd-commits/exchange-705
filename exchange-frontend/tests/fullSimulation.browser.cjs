// Browser plugin not available. Installed Playwright; mocked HTTP tests, not production financial execution.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const out = path.join(require('node:os').tmpdir(), 'full-simulation-qa');fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try {
  for(const desktop of [false,true]) {
   const page=await browser.newPage({viewport:{width:desktop?1440:390,height:960}});
   const errors=[],calls=[];let unavailable=false, releasePending;
   page.on('pageerror',e=>errors.push(e.message));
   await page.addInitScript(()=>{localStorage.setItem('locale','en');localStorage.setItem('token','fixture');localStorage.setItem('user',JSON.stringify({id:700001,email:'fixture@example.invalid'}));});
   await page.route(/\/(?:demo-api|api)\//,async route=>{
    const req=route.request(), u=new URL(req.url()), demo=u.pathname.startsWith('/demo-api/'), endpoint=u.pathname.replace(/^\/(demo-api|api)/,'');
    calls.push({demo,endpoint,method:req.method(),mode:req.headers()['x-account-mode']});
    if(endpoint==='/transfer/submit' && demo) { await new Promise(resolve=>releasePending=resolve); try {await route.fulfill({json:{success:true}});}catch{} return; }
    let body={success:true,list:[],data:[],balances:[],announcements:[],items:[]};
    if(endpoint==='/simulation/session') {await route.fulfill({status:unavailable?503:200,json:{environment:'DEMO',userId:700001}});return;}
    if(endpoint==='/user/assets')body={success:true,fundBalance:demo?100000:42,contractBalance:demo?100000:0,optionBalance:demo?100000:0};
    if(endpoint==='/kyc/status')body={success:true,kycStatus:'NOT_VERIFIED',latestRecord:null,simulationExempt:demo,canTrade:demo};
    if(endpoint==='/loan/personal-info/status')body={success:true,verified:false,exempt:demo,status:demo?'SIMULATION_EXEMPT':'NOT_SUBMITTED',data:{}};
    if(endpoint.includes('/support/config'))body={mode:'off',inboxEnabled:false};
    if(endpoint.includes('timezone'))body={success:true,timezone:'UTC'};
    if(endpoint.includes('/price/batch'))body={ret:200,data:{}};
    if(endpoint.includes('categories'))body={success:true,data:[]};
    await route.fulfill({json:body});
   });
   const base=desktop?'http://127.0.0.1:5415':'http://127.0.0.1:5413';
   await page.goto(base+(desktop?'/':'/assets'));
   const bar=page.locator('.account-mode-bar');await bar.waitFor();
   assert.match(await page.title(),/.+/);assert.equal(await page.locator('vite-error-overlay').count(),0);
   unavailable=true;await bar.getByRole('button',{name:/Switch to demo/}).click();await bar.getByRole('alert').waitFor();
   assert.equal(await page.evaluate(()=>sessionStorage.getItem('account-mode:700001')),null);
   unavailable=false;await bar.getByRole('button',{name:/Switch to demo/}).click();
   await page.waitForFunction(()=>document.querySelector('.account-mode-bar')?.textContent.includes('Independent demo account'));
   assert.equal(await page.evaluate(()=>sessionStorage.getItem('account-mode:700001')),'DEMO');
   await page.reload();await bar.getByText('Independent demo account',{exact:true}).waitFor();
   for(let tries=0;tries<100&&!calls.some(c=>c.demo&&c.endpoint==='/user/assets');tries++) await new Promise(resolve=>setTimeout(resolve,50));
   assert(calls.some(c=>c.demo && c.endpoint==='/user/assets' && c.mode==='DEMO'),'demo assets use separate API');
   assert(calls.filter(c=>c.endpoint==='/auth/heartbeat').every(c=>!c.demo&&c.mode==='REAL'),'login identity stays real');
   const watermark = await page.evaluate(async()=>{const {markSimulationExport}=await import('/src/utils/accountMode.ts');const canvas=document.createElement('canvas');canvas.width=1080;canvas.height=1440;markSimulationExport(canvas);return Array.from(canvas.getContext('2d').getImageData(0,0,1,1).data);});
   assert.deepEqual(watermark,[55,48,163,255],'demo export gets mandatory watermark');
   if(!desktop){await page.goto(base+'/withdraw');await bar.waitFor();assert.match(page.url(),/\/withdraw$/);}
   await page.locator('.forex-transition').waitFor({state:'hidden',timeout:15000});
   await page.screenshot({path:path.join(out,desktop?'pc-demo.png':'mobile-demo.png'),fullPage:false});
   await page.evaluate(async()=>{const {default:request}=await import('/src/utils/request.ts');void request.post('/transfer/submit',{fromAccount:'FUND',toAccount:'CONTRACT',amount:'1'}).catch(()=>{});});
   while(!releasePending) await new Promise(resolve=>setTimeout(resolve,25));
   assert.equal(await bar.getByRole('button',{name:/Switch to real/}).isEnabled(),true,'in-flight write does not prevent switching');
   await bar.getByRole('button',{name:/Switch to real/}).click();
   releasePending();
   await page.waitForFunction(()=>document.querySelector('.account-mode-bar')?.textContent.includes('Real account'));
   assert.equal(await page.evaluate(()=>sessionStorage.getItem('account-mode:700001')),'REAL');
   assert(calls.filter(c=>!c.demo&&!c.endpoint.startsWith('/auth/')).every(c=>!c.mode||c.mode==='REAL'),'no DEMO financial calls to real API');
   assert.deepEqual(errors,[]);
   console.log(JSON.stringify({surface:desktop?'pc':'mobile',result:'PASS',requests:calls.length,screenshot:path.join(out,desktop?'pc-demo.png':'mobile-demo.png')}));await page.close();
  }
 } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1});
