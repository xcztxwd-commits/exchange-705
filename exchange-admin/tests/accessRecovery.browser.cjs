// Local Vite + isolated API fixtures; never use live credentials or production writes.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const base = process.env.ADMIN_QA_URL || 'http://127.0.0.1:18059'
const output = process.env.ADMIN_QA_OUTPUT || path.resolve(__dirname, '../../reports/admin-access-recovery')
;(async () => {
 fs.mkdirSync(output, {recursive:true})
 const browser = await chromium.launch({headless:true, executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'})
 try {
  const page=await browser.newPage(), errors=[]
  page.on('pageerror',e=>errors.push(e.message))
  let mode='ok', calls=0, writes=0
  await page.addInitScript(()=>{
   if(!sessionStorage.getItem('qa-initialized')) {
    sessionStorage.setItem('qa-initialized','1')
    sessionStorage.setItem('exchange.admin.session.v2',JSON.stringify({token:'qa-token',mode:'admin',user:{id:1,tenantId:2,userType:'admin'}}))
   }
   const interval=window.setInterval.bind(window)
   window.setInterval=(handler,delay,...args)=>interval(handler,delay===30000?300:delay,...args)
  })
  await page.route('**/api/**',route=>{
   if(new URL(route.request().url()).pathname.startsWith('/api/admin/table-preferences/')) return route.fulfill({json:route.request().method()==='GET'?[]:{success:true}})
   if(new URL(route.request().url()).pathname==='/api/admin/menus/current') {
    calls++
    if(mode==='network') return route.abort('failed')
    if(['fail','expired','denied'].includes(mode)) return route.fulfill({status:mode==='fail'?503:mode==='denied'?403:401,json:mode==='fail'?{code:'RELEASE_MAINTENANCE'}:{success:false,message:'fixture failure'}})
    return route.fulfill({json:{success:true,menus:mode==='empty'?[]:[{id:1,menuCode:'durations',menuName:'周期设置',path:'/durations'}],groups:[],actions:mode==='empty'?{}:{durations:['create']}}})
   }
   if(!['GET','HEAD','OPTIONS'].includes(route.request().method())) writes++
   return route.fulfill({json:{success:true,list:[],data:{},count:0}})
  })
  await page.goto(base+'/durations'); await page.locator('.layout-container').waitFor()
  assert.ok(await page.title()); assert.equal(await page.locator('vite-error-overlay').count(),0)
  await page.getByRole('button',{name:'新增期限'}).click()
  const draft=page.getByPlaceholder('如：30s, 60s')
  await draft.fill('QA 未提交内容')
  mode='fail'; await page.getByText('系统正在发布维护（HTTP 503）',{exact:true}).waitFor()
  assert.equal(new URL(page.url()).pathname,'/durations')
  assert.equal(await page.locator('.layout-container').count(),1)
  assert.equal(await draft.inputValue(),'QA 未提交内容')
  assert.equal(await page.getByRole('button',{name:'保存',exact:true}).count(),0)
  const paused=await page.evaluate(async()=>{try{await(await import('/src/utils/request.ts')).default.post('/admin/durations',{});return ''}catch(error){return error.message}})
  assert.match(paused,/敏感操作已暂停/);assert.equal(writes,0)
  await page.evaluate(async()=>{await(await import('/src/router/index.ts')).default.push('/orders')})
  assert.equal(new URL(page.url()).pathname,'/durations');assert.equal(await draft.inputValue(),'QA 未提交内容')
  assert.ok(await page.evaluate(()=>sessionStorage.getItem('exchange.admin.session.v2')))
  await page.screenshot({path:path.join(output,'maintenance-keeps-draft.png')})
  mode='ok'; await page.locator('.permission-warning').waitFor({state:'detached',timeout:25000})
  assert.equal(await draft.inputValue(),'QA 未提交内容')
  await page.getByRole('button',{name:'保存',exact:true}).waitFor()
  console.log('PASS maintenance keeps page/draft; cancels navigation; blocks programmatic writes; automatic recovery without login')
  await page.getByRole('button',{name:'取消',exact:true}).click()
  mode='network'; await page.getByText('网络连接异常或权限请求超时',{exact:true}).waitFor()
  mode='ok'; await page.getByRole('button',{name:'重新加载权限'}).click()
  await page.locator('.permission-warning').waitFor({state:'detached'})
  console.log('PASS network failure and manual recovery stay on the same page')
  for(const denial of ['empty','denied']) {
   mode=denial; await page.getByText('暂无访问权限',{exact:true}).waitFor()
   assert.equal(new URL(page.url()).pathname,'/forbidden')
   const before=calls; await page.waitForTimeout(600); assert.equal(calls,before)
   mode='ok'; await page.reload(); await page.waitForURL('**/durations')
  }
  console.log('PASS confirmed empty grants and JSON 403 deny; no retry loop or cached writes')
  mode='fail'; await page.goto(base+'/durations?tab=draft')
  await page.getByText('后台服务暂时不可用',{exact:true}).waitFor()
  assert.equal(new URL(page.url()).pathname,'/access-unavailable')
  assert.equal(await page.locator('.layout-container').count(),0)
  mode='ok'; await page.getByRole('button',{name:'重新加载权限'}).click()
  await page.waitForURL('**/durations?tab=draft')
  console.log('PASS cold-start outage is not forbidden; recovery restores the requested route/query')
  mode='expired'; await page.waitForURL('**/login')
  assert.equal(await page.evaluate(()=>sessionStorage.getItem('exchange.admin.session.v2')),null)
  console.log('PASS expired token returns to login instead of forbidden')
  for (const serverRejects of [false, true]) {
   mode='ok'
   await page.evaluate(serverRejects=>sessionStorage.setItem('exchange.admin.session.v2',JSON.stringify({token:'qa-control',mode:'control',user:{id:-1,tenantId:2,userType:'control'},accessSession:{id:'access-1',tenantId:2,tenantName:'QA',expiresAt:Date.now()+(serverRejects?60000:2500)}})),serverRejects)
   await page.goto(base+'/durations'); await page.locator('.layout-container').waitFor()
   if(serverRejects) mode='expired'
   await page.waitForURL('**/access-ended')
   assert.equal(await page.evaluate(()=>sessionStorage.getItem('exchange.admin.session.v2')),null)
   console.log('PASS control access ends correctly: '+(serverRejects?'server 401':'local deadline'))
  }
  assert.deepEqual(errors,[])
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1})
