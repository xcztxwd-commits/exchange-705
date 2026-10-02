// Browser plugin not available. Local Vite + isolated API fixtures; no live credentials.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')
const assert = require('node:assert/strict')
const base = process.env.ADMIN_QA_URL || 'http://127.0.0.1:18059'
;(async () => {
 const browser = await chromium.launch({headless:true, executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'})
 try {
  const page=await browser.newPage(), errors=[]
  page.on('pageerror',e=>errors.push(e.message))
  let mode='ok', calls=0
  await page.addInitScript(()=>{
   if(!sessionStorage.getItem('qa-initialized')) {
    sessionStorage.setItem('qa-initialized','1')
    sessionStorage.setItem('exchange.admin.session.v2',JSON.stringify({token:'qa-token',mode:'admin',user:{id:1,tenantId:2,userType:'admin'}}))
   }
   const interval=window.setInterval.bind(window)
   window.setInterval=(handler,delay,...args)=>interval(handler,delay===30000?300:delay,...args)
  })
  await page.route('**/api/**',route=>{
   if(new URL(route.request().url()).pathname==='/api/admin/menus/current') {
    calls++
    if(mode==='network') return route.abort('failed')
    if(['fail','expired'].includes(mode)) return route.fulfill({status:mode==='fail'?503:401,json:{success:false,message:'fixture failure'}})
    return route.fulfill({json:{success:true,menus:mode==='empty'?[]:[{id:1,menuCode:'durations',menuName:'周期设置',path:'/durations'}],groups:[],actions:mode==='empty'?{}:{durations:[]}}})
   }
   return route.fulfill({json:{success:true,list:[],data:{},count:0}})
  })
  await page.goto(base+'/durations'); await page.locator('.layout-container').waitFor()
  assert.ok(await page.title()); assert.equal(await page.locator('vite-error-overlay').count(),0)
  mode='fail'; await page.getByText('权限加载暂时失败',{exact:true}).waitFor()
  assert.equal(await page.locator('.layout-container').count(),0)
  assert.ok(await page.evaluate(()=>sessionStorage.getItem('exchange.admin.session.v2')))
  await page.screenshot({path:'../reports/admin-access-recovery.png'})
  mode='ok'; await page.waitForURL('**/durations',{timeout:25000}); await page.locator('.layout-container').waitFor()
  console.log('PASS idle refresh failure closes access; automatic recovery without login')
  mode='network'; await page.getByText('权限加载暂时失败',{exact:true}).waitFor()
  mode='ok'; await page.getByRole('button',{name:'重新加载权限'}).click(); await page.waitForURL('**/durations')
  console.log('PASS network failure and manual recovery')
  mode='empty'; await page.getByText('暂无访问权限',{exact:true}).waitFor()
  const before=calls; await page.waitForTimeout(600); assert.equal(calls,before)
  mode='ok'; await page.reload(); await page.waitForURL('**/durations')
  console.log('PASS actual revocation stays denied; grant + reload recovers')
  mode='expired'; await page.waitForURL('**/login')
  assert.equal(await page.evaluate(()=>sessionStorage.getItem('exchange.admin.session.v2')),null)
  assert.equal(await page.getByText('暂无访问权限',{exact:true}).count(),0)
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
