const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const out = path.resolve('reports/trade-kyc-switch-20260929');
fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const passed=[];
 try {
  for(const desktop of [false,true]) {
   const page=await browser.newPage({viewport:{width:desktop?1440:390,height:960}});
   let enabled=true, state='NONE', statusError=false, denyOrder=false, balance=0, holdNextKyc=false, releaseKyc=null;
   const writes=[],errors=[];
   page.on('pageerror',e=>errors.push(e.message));
   await page.addInitScript(()=>{localStorage.setItem('locale','en');localStorage.setItem('token','kyc-test');localStorage.setItem('user',JSON.stringify({id:1,email:'fixture@example.invalid'}));});
   await page.route('**/api/**',async r=>{
    const p=new URL(r.request().url()).pathname;
    let data={success:true,list:[],data:[],symbols:[],orders:[],balances:[],announcements:[]},status=200;
    if(p==='/api/tenant/features') data={tenantId:1,status:'ACTIVE',acceptNewBusiness:true,configReady:true,domainVerified:true,features:{contract:true,option:true,financial:true}};
    if(p==='/api/kyc/status') {status=statusError?503:200;data={canTrade:!enabled||state==='APPROVED',kycStatus:state==='APPROVED'?'VERIFIED':'NOT_VERIFIED',latestRecord:state==='NONE'?null:{status:state,realName:'Test',idNumber:'TEST-ONLY'}};}
    if(p.endsWith('/balance')) data={success:true,available:balance,balance};
    if(p==='/api/user/assets') data={success:true,contractBalance:balance,optionBalance:balance,fundBalance:0};
    if(p==='/api/trade/option/durations') data=[{duration:60,label:'60s',enabled:true,profitRate:0.8,lossRate:1,minAmount:1,maxAmount:1000}];
    if(p.endsWith('/timezone')) data={timezone:'UTC'};
    if(r.request().method()==='POST' && /\/trade\/(contract|option)\/order$/.test(p)) {
     writes.push({path:p,body:JSON.parse(r.request().postData())});
     status=denyOrder?403:200;data=denyOrder?{success:false,errorCode:'KYC_REQUIRED',kycStatus:'PENDING',message:'Identity required'}:{success:true,orderId:1};
    }
    if(p==='/api/kyc/status' && holdNextKyc) {holdNextKyc=false;await new Promise(resolve=>{releaseKyc=resolve;});}
    await r.fulfill({status,json:data});
   });
   const base=desktop?(process.env.PC_URL||'http://127.0.0.1:5295'):(process.env.MOBILE_URL||'http://127.0.0.1:5293');
   const attach=async()=>{
    await page.locator('.trade-page').waitFor();
    await page.evaluate(()=>{let c=document.querySelector('.trade-page').__vueParentComponent;while(c&&!c.setupState.ensureKyc)c=c.parent;if(!c)throw Error('Trade component missing');window.trade=c.setupState;});
    await page.waitForFunction(()=>!window.trade.kycChecking);
   };
   await page.goto(base+(desktop?'/':'/trade'));await attach();
   const dialog=page.getByRole('dialog',{name:'Identity Verification',exact:true});
   const setMode=async(mode)=>page.evaluate(({mode,desktop})=>{if(desktop)window.trade.tradeMode=mode==='option'?'options':'contract';else {window.trade.activeTab=mode==='option'?'term':'contract';window.trade.contractTab='entry';}}, {mode,desktop});
   const click=async(sell=false)=>{
    if(desktop) {
     const mode=await page.evaluate(()=>window.trade.tradeMode);
     const name=await page.evaluate(({mode,sell})=>window.trade.localeStore.t(mode==='contract'?(sell?'sell':'buy'):(sell?'buyDown':'buyUp')),{mode,sell});
     // The submit buttons are the only colored trading buttons with these handlers.
     // Match full visible label; funds are zero but unverified users must still be able to click.
     await page.getByRole('button',{name,exact:true}).first().click();
    } else await page.locator('.action-dock .primary-actions '+(sell?'.sell':'.buy')).click();
   };

   // Browser plugin not available; reuse repository Playwright harness.
   let alerts=0;
   page.on('dialog',async dialog=>{assert.match(dialog.message(),/complete identity verification/i);alerts++;await dialog.accept();});
   const reset=async()=>{await page.goto(base+(desktop?'/':'/trade'));await attach();};
   for(const mode of ['contract','option']) {
    for(const kyc of ['NONE','PENDING','REJECTED']) {
     state=kyc;await reset();await setMode(mode);const before=alerts;
     await click(kyc==='REJECTED');
     await page.waitForFunction(desktop=>desktop?window.trade.showUserCenter&&window.trade.activeUserMenu==='kyc':location.pathname.endsWith('/verification'),desktop);
     await page.waitForTimeout(400);assert.equal(alerts,before+1);assert.equal(writes.length,0);
    }
   }
   await page.waitForTimeout(2000);
   if(desktop) await page.getByRole('dialog').filter({hasText:'Account'}).first().screenshot({path:path.join(out,'pc-verification.png')});
   else await page.screenshot({path:path.join(out,'mobile-verification.png')});
   enabled=false;state='NONE';await reset();
   assert.equal(await page.evaluate(()=>window.trade.ensureKyc()),true,'Disabled gate allows unverified users');
   enabled=true;state='APPROVED';
   assert.equal(await page.evaluate(()=>window.trade.ensureKyc()),true,'Approved user passes');
   // Server denial must navigate even outside trade components and preserve login.
   denyOrder=true;const before=alerts;
   await page.evaluate(async()=>{const {default:request}=await import('/src/utils/request.ts');try{await request.post('/trade/contract/order',{symbol:'TESTUSD'});}catch(error){if(error.errorCode!=='KYC_REQUIRED')throw error;}});
   await page.waitForFunction(desktop=>desktop?window.trade.showUserCenter&&window.trade.activeUserMenu==='kyc':location.pathname.endsWith('/verification'),desktop);
   await page.waitForTimeout(400);assert.equal(alerts,before+1);assert.equal(writes.length,1);
   assert.equal(await page.evaluate(()=>localStorage.getItem('token')),'kyc-test');
   assert.deepEqual(errors,[]);passed.push(desktop?'PC':'mobile');await page.close();
  }
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({passed},null,2));console.log('PASS',passed);
 } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
