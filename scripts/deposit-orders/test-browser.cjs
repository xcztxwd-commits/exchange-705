// Isolated SFC browser acceptance: virtual fixture APIs, no production backend/proxy.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{pathToFileURL}=require('node:url')
const {chromium}=require(process.env.PLAYWRIGHT_PATH || 'playwright')
const root=path.resolve(__dirname,'../../exchange-admin'),report=path.resolve(__dirname,'../../reports/deposit-orders/browser')
fs.mkdirSync(report,{recursive:true})
;(async()=>{
 const {createServer}=await import(pathToFileURL(path.join(root,'node_modules/vite/dist/node/index.js')))
 const vue=(await import(pathToFileURL(path.join(root,'node_modules/@vitejs/plugin-vue/dist/index.mjs')))).default
 const virtual=root.replaceAll('\\','/')+'/__deposit-', entry=`import {createApp} from 'vue';import {createPinia} from 'pinia';import ElementPlus from 'element-plus';import 'element-plus/dist/index.css';import App from '@/views/DepositOrders.vue';import {useAuthStore} from '@/store/auth';const app=createApp(App);app.use(createPinia());useAuthStore().setAuth('fixture-only',{id:999,userType:'admin'});app.use(ElementPlus).mount('#app')`
 const server=await createServer({root,configFile:false,envDir:report,cacheDir:path.join(report,'vite-cache'),define:{'import.meta.env.VITE_API_BASE_URL':JSON.stringify('/api')},plugins:[{name:'deposit-fixture',resolveId(id){if(id==='deposit-entry')return virtual+'entry.js'},load(id){if(id===virtual+'entry.js')return entry},configureServer(s){s.middlewares.use('/__deposit_test',(_q,r)=>{r.setHeader('Content-Type','text/html');r.end('<html><body><div id="app"></div><script type="module" src="/@id/deposit-entry"></script></body></html>')})}},vue()],resolve:{alias:{'@':path.join(root,'src')}},server:{host:'127.0.0.1',port:0}})
 let browser
 try{
  await server.listen();const port=server.httpServer.address().port
  browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'})
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[],requests=[];let writes=0,loseResponse=true,order=null
  page.on('pageerror',e=>errors.push(e.message))
  await page.route('**/*',route=>{
   const u=new URL(route.request().url());if(u.hostname!=='127.0.0.1'||u.port!==String(port))return route.abort('blockedbyclient')
   if(!u.pathname.startsWith('/api/'))return route.continue()
   if(u.pathname.endsWith('/permissions'))return route.fulfill({json:{view_deposit_orders:true,manual_deposit:true,export_deposit_orders:true,approve_deposit:true,reject_deposit:true}})
   if(u.pathname==='/api/market/currencies')return route.fulfill({json:{rates:{}}})
   if(u.pathname.includes('/recipient/'))return route.fulfill({json:{userId:424242,name:'fixture@invalid',remark:'隔离客户',balances:{FUND:'25'}}})
   if(u.pathname.endsWith('/manual')){
    const r=route.request().postDataJSON();requests.push(r)
    if(!order){writes++;order={...r,id:1,orderNo:'DEP-ISOLATED',source:'ADMIN_MANUAL',status:'COMPLETED',amount:'100.0000000000000000',originalAmount:r.amount,accountType:r.account,feeRate:'0',feeAmount:'0',createdAt:'2026-09-28T00:00:00',creditedAt:'2026-09-28T00:00:00'}}
    if(loseResponse){loseResponse=false;return route.abort('failed')}
    return route.fulfill({json:order})
   }
   if(u.pathname.endsWith('/list'))return route.fulfill({json:{list:order?[order]:[],total:order?1:0,page:1,size:20}})
   if(u.pathname.endsWith('/summary'))return route.fulfill({json:{count:order?1:0,pending:0,creditedUsd:order?'100':'0',manualUsd:order?'100':'0',userUsd:'0',legacyUsd:'0',groups:order?{ADMIN_MANUAL_ADJUSTMENT:'100'}:{}}})
   if(u.pathname.endsWith('/1'))return route.fulfill({json:{...order,credit:{balanceBefore:'25',balanceAfter:'125',operatorType:'ADMIN',operatorId:999,operatorName:'fixture',creditedAt:'2026-09-28T00:00:00'}}})
   return route.fulfill({status:404,json:{message:'unimplemented fixture endpoint'}})
  })
  await page.goto(`http://127.0.0.1:${port}/__deposit_test`)
  await page.getByRole('button',{name:'新增手动充值',exact:true}).click()
  const dialog=page.locator('.el-dialog').filter({has:page.getByText('新增手动充值',{exact:true})})
  await dialog.getByRole('button',{name:'确认充值',exact:true}).click();assert.equal(requests.length,0)
  const item=label=>dialog.locator('.el-form-item').filter({has:page.locator('.el-form-item__label',{hasText:label})})
  await item('客户 UID').locator('input').fill('424242');await dialog.getByRole('button',{name:'查询客户'}).click();await dialog.getByText(/fixture@invalid/).waitFor()
  await item('原币数量').locator('input').fill('100');await item('订单备注').locator('textarea').fill('隔离浏览器验收')
  await dialog.getByRole('button',{name:'确认充值',exact:true}).click();await page.locator('.el-message-box__btns .el-button--primary').click()
  await dialog.getByRole('button',{name:'重试原请求'}).waitFor();assert.equal(writes,1)
  await dialog.getByRole('button',{name:'关闭',exact:true}).click();await page.getByRole('button',{name:'新增手动充值',exact:true}).click()
  await dialog.getByRole('button',{name:'重试原请求'}).waitFor();assert.ok(await item('原币数量').locator('input').isDisabled())
  await dialog.getByRole('button',{name:'重试原请求'}).click();await page.getByText('DEP-ISOLATED',{exact:true}).first().waitFor()
  assert.equal(writes,1);assert.equal(requests.length,2);assert.deepEqual(requests[0],requests[1]);assert.equal(errors.length,0,errors.join('\n'))
  await dialog.waitFor({state:'hidden'});await page.waitForTimeout(3500)
  await page.screenshot({path:path.join(report,'ledger.png'),fullPage:true})
  await page.getByRole('button',{name:'详情',exact:true}).click();await page.getByText('125',{exact:true}).waitFor();await page.screenshot({path:path.join(report,'detail.png'),fullPage:true})
  fs.writeFileSync(path.join(report,'result.json'),JSON.stringify({passed:true,fixtureOnly:true,checks:['required input rejects without request','decimal-string amount','response lost after fixture credit','close/reopen retains immutable key','retry sends identical body','one fixture credit only','detail balances visible'],requests:requests.length,writes,errors},null,2))
  console.log('PASS: isolated browser, timeout/reopen/retry identical key; one fixture credit. Real transaction verification is separate MySQL suite.')
 }finally{if(browser)await browser.close();await server.close()}
})().catch(e=>{console.error(e);process.exitCode=1})
