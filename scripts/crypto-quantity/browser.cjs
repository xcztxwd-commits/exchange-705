// Browser plugin not available. Playwright UI fixtures only; ALL API requests intercepted.
// This is not deployed-service or real-account acceptance.
const {chromium}=require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const out=path.resolve(process.env.CQ_SCREENSHOTS || 'C:/Users/徐乾妖/AppData/Local/Temp/705-crypto-quantity');fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe'});
 const results=[];
 try {
  for(const desktop of [true,false]) {
   const page=await browser.newPage({viewport:{width:desktop?1440:390,height:960}}),errors=[],writes=[];
   page.on('pageerror',e=>errors.push(e.message));
   await page.addInitScript(()=>{localStorage.setItem('locale','zh-CN');localStorage.setItem('token','cq-fixture-only');localStorage.setItem('user',JSON.stringify({id:1,email:'cq-fixture@example.invalid'}));});
   await page.route('**/api/**',async route=>{
    const p=new URL(route.request().url()).pathname;let data={success:true,list:[],data:[],symbols:[],orders:[],balances:[],announcements:[]};
    if(p==='/api/kyc/status')data={kycStatus:'VERIFIED',latestRecord:{status:'APPROVED'}};
    if(p.endsWith('/balance'))data={success:true,available:8.0003,balance:8.0003};
    if(p==='/api/user/assets')data={success:true,contractBalance:8.0003,optionBalance:0,fundBalance:0};
    if(p.endsWith('/timezone'))data={timezone:'UTC'};
    if(p==='/api/trade/option/durations')data=[];
    if(route.request().method()==='POST' && p==='/api/trade/contract/order'){writes.push(route.request().postDataJSON());data={success:true,orderId:999};}
    await route.fulfill({json:data});
   });
   await page.goto(desktop?'http://127.0.0.1:5391/':'http://127.0.0.1:5392/trade');await page.locator('.trade-page').waitFor();
   await page.evaluate(()=>{let c=document.querySelector('.trade-page').__vueParentComponent;while(c&&!c.setupState.ensureKyc)c=c.parent;if(!c)throw Error('No trade component');window.trade=c.setupState;});
   for(const asset of ['BTC','ETH','SOL']) {
    await page.evaluate(async({desktop,asset})=>{
     const s=window.trade,m=desktop?s.marketStore:s.market,price=asset==='BTC'?80000:asset==='ETH'?2500:100;
     const item={symbol:asset+'USDT',baseCurrency:asset,quoteCurrency:'USDT',category:'Crypto',sourceCategory:'Crypto',marketSource:'binance',lotSize:1,feeMultiplier:.03,maxLeverage:100,pricePrecision:2,isEnabled:true,quantityUnitType:'BASE_ASSET',specVersion:1,minOrderQuantity:asset==='SOL'?.01:.001,quantityStep:asset==='SOL'?.01:.001,minOrderNotional:10};
     s.currentSymbol=item.symbol;
     if(desktop){s.currentSymbolInfo=item;m.symbols=[item];s.tradeMode='contract';}else{s.catalog=[item];s.activeTab='contract';s.contractTab='entry';}
     m.priceMap[item.symbol]={price,timestamp:Date.now()};m.quoteStatusMap[item.symbol]={status:'available',timestamp:Date.now(),fetchedAt:Date.now(),expiresAt:Date.now()+300000};
     s.quantity=asset==='SOL'?.1:.01;await s.refreshAccount();
    },{desktop,asset});
    await page.getByText(`数量（${asset}）`,{exact:false}).first().waitFor();
    const input=desktop?page.locator('.custom-input-number input').filter({visible:true}):page.locator('#contract-quantity');
    if(!desktop){assert.equal(await input.getAttribute('step'),asset==='SOL'?'0.01':'0.001');await input.fill(asset==='BTC'?'0.001':asset==='ETH'?'0.004':'0.10');}
    await page.screenshot({path:path.join(out,`${desktop?'pc':'mobile'}-${asset}.png`),animations:'disabled'});
    results.push(`${desktop?'PC':'mobile'} ${asset}: unit and quantity control rendered`);
   }
   // Fixture submission proves the client carries the unit and independent version.
   await page.evaluate(async(desktop)=>{window.trade.quantity=.1; if(desktop)await window.trade.submitContractOrder('BUY');else{await window.trade.openContract('BUY');}},desktop);
   if(!desktop){await page.locator('dialog[open] .sheet-primary').click();await page.waitForFunction(()=>!window.trade.busy);}
   assert.equal(writes.length,1);assert.equal(writes[0].specVersion,1);assert.equal(writes[0].quantityUnitType,'BASE_ASSET');assert.equal(writes[0].quantity,'0.1');
   assert.deepEqual(errors,[]);await page.close();
  }
  const page=await browser.newPage({viewport:{width:1360,height:1000}}),errors=[];let saved=null;
  let item={id:72,symbol:'BTCUSDT',name:'Bitcoin',baseCurrency:'BTC',quoteCurrency:'USDT',category:'Crypto',sourceCategory:'Crypto',marketSource:'binance',alltickSymbol:'BTCUSDT',lotSize:1,feeMultiplier:.03,maxLeverage:100,quantityUnitType:'BASE_ASSET',specVersion:1,minOrderQuantity:.001,quantityStep:.001,minOrderNotional:10,isEnabled:true};
  page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{localStorage.setItem('admin_token','cq-fixture-only');localStorage.setItem('admin_user',JSON.stringify({id:1,role:'super_admin',isSuperAdmin:true}));});
  await page.route('**/api/**',async route=>{const p=new URL(route.request().url()).pathname;let data={success:true,list:[],total:0};
   if(p==='/api/admin/menus/current')data={success:true,superAdmin:true,menus:[{menuCode:'symbols',path:'/symbols',menuName:'品种'}],groups:[],actions:{symbols:['*']}};
   if(p.endsWith('/symbols/categories'))data=[{key:'Crypto',label:'加密资产',leverageEnabled:true}];
   if(p.endsWith('/symbols/catalog/sources'))data=[];
   if(p.endsWith('/symbols/query'))data={list:[item],total:1};
   if(p.endsWith('/symbols/update')){saved=route.request().postDataJSON();item={...saved,specVersion:2};}
   await route.fulfill({json:data});});
  await page.goto('http://127.0.0.1:5393/symbols');await page.getByText('BTCUSDT',{exact:true}).first().waitFor();
  await page.getByRole('button',{name:'编辑',exact:true}).first().click();
  await page.getByText('数量规格（仅影响新订单，历史快照不变）',{exact:true}).waitFor();
  const min=page.getByRole('textbox',{name:'最小数量',exact:true});await min.fill('0.002');
  await page.getByText('最低名义金额 USD',{exact:true}).scrollIntoViewIfNeeded();
  await page.screenshot({path:path.join(out,'admin-spec.png'),animations:'disabled'});
  await page.getByRole('button',{name:'保存',exact:true}).click();await page.getByText('更新成功',{exact:true}).waitFor();assert.equal(saved.minOrderQuantity,'0.002');assert.equal(saved.specVersion,1);
  await page.reload();await page.getByRole('button',{name:'编辑',exact:true}).first().click();assert.equal(await min.inputValue(),'0.002');assert.deepEqual(errors,[]);
  results.push('Admin fixture: edit/save/reload specification; fixed fee label and read-only multiplier');
  fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({status:'PASS',mode:'intercepted API fixtures; not deployment acceptance',results,screenshots:out},null,2));console.log(JSON.stringify({status:'PASS',results,screenshots:out}));
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
