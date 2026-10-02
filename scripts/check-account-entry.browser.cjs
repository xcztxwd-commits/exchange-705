// Browser plugin not available; local Playwright with mocked HTTP, no financial writes.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const output = path.join(require('node:os').tmpdir(), 'account-entry-qa'); fs.mkdirSync(output, {recursive:true});
(async () => {
 const browser = await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try {
  for (const desktop of [false,true]) {
   const page = await browser.newPage({viewport:{width:desktop?1920:390,height:960}});
   const errors=[]; let unavailable=false, enabled=true;
   page.on('pageerror',e=>errors.push(e.message));
   page.on('console',msg=> {if(msg.type()==='warning' && /Teleport|Vue warn/.test(msg.text())) errors.push(msg.text());});
   await page.addInitScript(()=>{
    localStorage.setItem('locale','en');localStorage.setItem('token','fixture');
    localStorage.setItem('user',JSON.stringify({id:700001,tenantId:1,email:'fixture@example.invalid'}));
   });
   await page.route(/\/(?:demo-api|api)\//,async route=>{
    const endpoint=new URL(route.request().url()).pathname.replace(/^\/(demo-api|api)/,'');
    let body={success:true,list:[],data:[],balances:[],announcements:[],items:[],content:[],totalPages:0};
    if(endpoint==='/tenant/features')body={tenantId:1,tenantName:'QA',status:'ACTIVE',acceptNewBusiness:true,features:{simulation:enabled}};
    if(endpoint==='/simulation/session'){await route.fulfill({status:unavailable?503:200,json:{environment:'DEMO',tenantId:1,userId:700001}});return;}
    if(endpoint.includes('/support/notifications'))body={mode:'off',inboxEnabled:true,inboxUnread:2};
    if(endpoint.includes('/support/config'))body={mode:'off',inboxEnabled:true};
    if(endpoint.includes('/support/inbox'))body=[];
    if(endpoint==='/user/asset-history')body={points:[],from:Date.now()-86400000,asOf:Date.now(),intervalMs:3600000,total:"42",income:"0",timezone:"UTC"};
    if(endpoint.includes('timezone'))body={success:true,timezone:'UTC'};
    if(endpoint.includes('/price/batch'))body={ret:200,data:{}};
    if(endpoint.includes('/kyc/status'))body={kycStatus:'VERIFIED',latestRecord:{status:'APPROVED'},canTrade:true};
    await route.fulfill({json:body});
   });
   const base=desktop?'http://127.0.0.1:5415':'http://127.0.0.1:5413';
   await page.goto(base+'/');
   const inbox=page.locator('#header-inbox .header-inbox-button'); await inbox.waitFor();
   assert.ok(await page.title());assert.equal(await page.locator('vite-error-overlay').count(),0);
   assert.equal(await page.locator('.account-mode-bar').count(),0);
   assert.equal(await page.locator('.activity-inbox-trigger').count(),0);
   assert.equal(await page.locator('.account-mode-menu:visible').count(),0);
   await page.locator('.forex-transition').waitFor({state:'hidden'});
   await page.screenshot({path:path.join(output,`${desktop?'pc':'mobile'}-header.png`)});
   await inbox.click();await page.waitForURL('**/inbox');
   await page.getByRole('button',{name:/Activity rewards/}).click();await page.locator('.activity-dialog[open]').waitFor();
   await page.locator('.activity-x').click();
   await page.goBack();await inbox.waitFor();
   async function openProfile(){
    if(desktop) await page.getByRole('button',{name:'fixture@example.invalid',exact:true}).click();
    else await page.goto(base+'/profile');
    await page.locator('.account-mode-menu').waitFor();
   }
   await openProfile();
   if(!desktop){const previous=await page.locator('.account-mode-entry').evaluate(e=>e.previousElementSibling.textContent);assert.match(previous,/Transfer/);}
   else {assert.equal(await page.locator('.user-demo-entry').evaluate(e=>e.parentElement.firstElementChild===e),true);}
   await page.locator('.account-mode-menu').scrollIntoViewIfNeeded();
   await page.locator('.forex-transition').waitFor({state:'hidden'});
   await page.screenshot({path:path.join(output,`${desktop?'pc':'mobile'}-profile.png`)});
   unavailable=true;await page.locator('.account-mode-menu').click();await page.locator('.account-mode-entry [role="alert"]').waitFor();
   assert.equal(await page.evaluate(()=>sessionStorage.getItem('account-mode:1:700001')),null);
   unavailable=false;await page.locator('.account-mode-menu').click();await page.locator('.account-mode-bar.practice').waitFor();
   assert.equal(await page.evaluate(()=>sessionStorage.getItem('account-mode:1:700001')),'DEMO');
   assert.equal(await page.locator('.account-mode-menu').count(),0);
   // Exiting remains available even when starting simulation is disabled.
   enabled=false;await page.reload();await page.locator('.account-mode-bar.practice').waitFor();
   await page.locator('.account-mode-bar').getByRole('button',{name:/Switch to real/}).click();
   await inbox.waitFor();assert.equal(await page.locator('.account-mode-bar').count(),0);
   assert.equal(await page.evaluate(()=>sessionStorage.getItem('account-mode:1:700001')),'REAL');
   if(desktop) await page.getByRole('button',{name:'fixture@example.invalid',exact:true}).click();
   else await page.goto(base+'/profile');
   assert.equal(await page.locator('.account-mode-menu').count(),0);
   await page.evaluate(()=>{localStorage.removeItem('token');localStorage.removeItem('user');});
   await page.goto(base+'/');
   assert.equal(await page.locator('.header-inbox-button').count(),0);
   assert.equal(await page.locator('.account-mode-bar').count(),0);
   assert.deepEqual(errors,[]);
   console.log(`${desktop?'PC':'Mobile'} PASS: placement, inbox, activity dialog, failure guard, demo/real switch, capability-independent return; no runtime errors`);
   await page.close();
  }
 }finally {await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
