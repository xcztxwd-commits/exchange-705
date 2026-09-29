const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const out = path.resolve('reports/trade-kyc-20260929');
fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 const passed=[];
 try {
  for(const desktop of [false,true]) {
   const page=await browser.newPage({viewport:{width:desktop?1440:390,height:960}});
   let state='NONE', statusError=false, denyOrder=false, balance=0, holdNextKyc=false, releaseKyc=null;
   const writes=[],errors=[];
   page.on('pageerror',e=>errors.push(e.message));
   await page.addInitScript(()=>{localStorage.setItem('locale','en');localStorage.setItem('token','kyc-test');localStorage.setItem('user',JSON.stringify({id:1,email:'fixture@example.invalid'}));});
   await page.route('**/api/**',async r=>{
    const p=new URL(r.request().url()).pathname;
    let data={success:true,list:[],data:[],symbols:[],orders:[],balances:[],announcements:[]},status=200;
    if(p==='/api/kyc/status') {status=statusError?503:200;data={kycStatus:state==='APPROVED'?'VERIFIED':'NOT_VERIFIED',latestRecord:state==='NONE'?null:{status:state,realName:'Test',idNumber:'TEST-ONLY'}};}
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
   for(const mode of ['contract','option']) {
    await setMode(mode);
    for(const kyc of ['NONE','PENDING','REJECTED']) {
     state=kyc;await click(kyc==='REJECTED');await dialog.waitFor();
     assert.match(await dialog.innerText(),kyc==='NONE'?/Please complete identity verification/:kyc==='PENDING'?/under review/:/was rejected/);
     assert.equal(writes.length,0);
     if(mode==='contract'&&kyc==='NONE') await page.screenshot({animations:'disabled',path:path.join(out,desktop?'pc-required.png':'mobile-required.png')});
     await dialog.getByRole('button',{name:'Cancel',exact:true}).click();await dialog.waitFor({state:'hidden'});
    }
   }
   state='NONE';await click();await dialog.getByRole('button',{name:'Confirm',exact:true}).click();
   if(desktop) {
    await page.waitForFunction(()=>window.trade.showUserCenter && window.trade.activeUserMenu==='kyc');
    await page.evaluate(()=>{window.trade.showUserCenter=false;});
   } else {await page.waitForURL('**/verification');await page.goto(base+'/trade');await attach();}
   assert.equal(writes.length,0,'Confirmation must navigate, not replay order');
   statusError=true;await setMode('contract');await click();
   await page.getByText('Unable to check identity verification. Please try again.',{exact:true}).first().waitFor();
   assert.equal(await dialog.isVisible(),false);assert.equal(writes.length,0);
   assert.equal(await page.evaluate(()=>localStorage.getItem('token')),'kyc-test');statusError=false;
   // Synthetic quote/account fixtures, keeping real validation, preflight and HTTP interceptor.
   const ready=async()=>{
    balance=10000;
    await page.evaluate(async(desktop)=>{
     const s=window.trade,m=desktop?s.marketStore:s.market;
     const item={symbol:'TESTUSD',baseCurrency:'TEST',quoteCurrency:'USD',category:'Crypto',lotSize:1000,feeMultiplier:30,maxLeverage:100,pricePrecision:2,isEnabled:true};
     s.currentSymbol='TESTUSD';
     if(desktop){s.currentSymbolInfo=item;m.symbols=[item];s.optionAmount='50';s.optionTime=60;}
     else{s.catalog=[item];s.amount=50;s.duration=60;}
     m.priceMap.TESTUSD={price:100,timestamp:Date.now()};
     m.quoteStatusMap.TESTUSD={status:'available',timestamp:Date.now(),fetchedAt:Date.now(),expiresAt:Date.now()+300000};
     await s.refreshAccount();if(!desktop)await s.refreshOption();
    },desktop);
   };
   state='APPROVED';await ready();
   await page.evaluate(()=>window.dispatchEvent(new Event('focus')));
   await page.waitForFunction(()=>window.trade.tradeVerified && !window.trade.kycChecking);
   assert.equal(writes.length,0,'Approval must never auto-submit');
   for(const mode of ['contract','option']) {
    await setMode(mode);await ready();
    await page.waitForFunction(desktop=>!window.trade.kycChecking && (desktop?window.trade.tradeMode!=='contract'||window.trade.orderReady:window.trade.activeTab==='term'?!window.trade.termReason:!window.trade.contractReason),desktop);
    const submitted = page.waitForResponse(response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/trade/' + mode + '/order');
    await click();
    if(!desktop){await page.waitForFunction(()=>window.trade.showConfirm);await page.locator('dialog[open] .sheet-primary').click();}
    await submitted;
    await page.waitForFunction(desktop=>desktop?!window.trade.tradeSubmitting:!window.trade.busy,desktop);
    assert.equal(writes.length,mode==='contract'?1:2,'Approved trade submits once');
   }
   denyOrder=true;await setMode('contract');await ready();await click();
   if(!desktop){await page.waitForFunction(()=>window.trade.showConfirm);await page.locator('dialog[open] .sheet-primary').click();}
   await dialog.waitFor();assert.match(await dialog.innerText(),/under review/);
   assert.equal(writes.length,3);assert.equal(await page.evaluate(()=>localStorage.getItem('token')),'kyc-test','KYC 403 must not log out');
   await dialog.getByRole('button',{name:'Cancel',exact:true}).click();
   assert.equal(writes.length,3);
   // Even after opening a mobile confirmation, a new pending status must block final submission.
   if(!desktop){denyOrder=false;await ready();await click();await page.waitForFunction(()=>window.trade.showConfirm);state='PENDING';await page.locator('dialog[open] .sheet-primary').click();await dialog.waitFor();assert.equal(writes.length,3);assert.equal(await page.evaluate(()=>window.trade.showConfirm),false);}
   // A late approved response from the previous account must never authorize the next one.
   await page.evaluate(()=>{window.trade.kycPromptOpen=false;});
   state='APPROVED';holdNextKyc=true;
   const oldCheck=page.evaluate(()=>window.trade.ensureKyc());
   await page.waitForRequest('**/api/kyc/status');
   while(!releaseKyc) await new Promise(resolve=>setTimeout(resolve,10));
   state='NONE';
   await page.evaluate(async()=>{const {useAuthStore}=await import('/src/store/auth.ts');useAuthStore().token='second-test-user';});
   releaseKyc();assert.equal(await oldCheck,false);
   await page.waitForFunction(()=>!window.trade.kycChecking);
   assert.equal(await page.evaluate(()=>window.trade.tradeVerified),false);
   await click();await dialog.waitFor();assert.match(await dialog.innerText(),/Please complete identity verification/);
   await dialog.getByRole('button',{name:'Cancel',exact:true}).click();
   await page.evaluate(async()=>{const {useAuthStore}=await import('/src/store/auth.ts');useAuthStore().logout();});
   if(desktop) await attach(); // Logout changes the router-view user key and remounts the trade component.
   await click();
   if(desktop) await page.waitForFunction(()=>window.trade.showLoginModal);
   else await page.waitForURL('**/login');
   assert.equal(writes.length,3,'Account changes/login redirects cannot submit');
   assert.deepEqual(errors,[]);
   passed.push(`${desktop?'PC':'Mobile'}: missing/pending/rejected, both directions/products, zero balance prompt, cancel/confirm navigation, lookup failure closed, approval refresh, real submit, 403 preserved, no auto-replay, token-switch race, login redirect${desktop?'':', final-confirmation recheck'}`);
   await page.close();
  }
  fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({passed},null,2));console.log(passed.join('\n'));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
