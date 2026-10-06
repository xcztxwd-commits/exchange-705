// Run against an isolated local admin Vite server. All API calls are stubbed; writes fail the test.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const base = process.env.ADMIN_URL, out = process.env.EVIDENCE_DIR
if (!base || !out || !['127.0.0.1','localhost'].includes(new URL(base).hostname) || ['17050','17051','17052'].includes(new URL(base).port)) throw Error('Use an isolated local admin server and evidence directory')
fs.mkdirSync(out,{recursive:true})
;(async () => {
 const browser = await chromium.launch({headless:true,...(process.env.CHROME_PATH ? {executablePath:process.env.CHROME_PATH} : {})})
 const page = await browser.newPage({viewport:{width:1500,height:950}}), errors=[], requests=[], checks=[]
 let notification={mode:'internal',waiting:4,queueUnread:0,chatUnread:0,latest:0,queueLatest:0,inboxLatest:0,inboxUnread:0,inboxEnabled:false,sound:'/api/user/support/tones/arrival.wav'}, supportAllowed=true, claimAllowed=true, failure=false, slow=false, release
 page.on('pageerror',error=>errors.push(error.message))
 const notify=async state=>{notification={...notification,...state};await page.evaluate(()=>window.dispatchEvent(new Event('unified-inbox-changed')))}
 const expectCount=async count=>{await page.getByRole('button',{name:`客服(${count})`,exact:true}).waitFor({timeout:7500})}
 try {
  await page.addInitScript(()=>sessionStorage.setItem('exchange.admin.session.v2',JSON.stringify({mode:'admin',token:'QA1',user:{id:1,tenantId:1,userType:'admin',role:'admin',account:'QA'}})))
  await page.route('**/__qa',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"></head><body style="margin:0"><div id="app"></div></body></html>'}))
  await page.route('**/api/**',async route=>{
   const url=new URL(route.request().url()), method=route.request().method(), token=route.request().headers().authorization
   requests.push({method,path:url.pathname,query:url.search,tenant:token==='Bearer QA2'?2:1});assert.equal(method,'GET','pending message navigation must not accept, acknowledge reading or change presence')
   if(url.pathname==='/api/admin/menus/current') {
    const menus=[{menuCode:'dashboard',menuName:'运营看板',path:'/dashboard'},{menuCode:'users',menuName:'用户管理',path:'/users'}], actions={dashboard:[],users:[]}
    if(supportAllowed){menus.push({menuCode:'support',menuName:'客服工作台',path:'/support'});actions.support=['detail','reply',...(claimAllowed?['claim']:[])]}
    return route.fulfill({json:{success:true,menus,groups:[],actions,superAdmin:false}})
   }
   if(url.pathname==='/api/admin/notification/pending-counts')return route.fulfill({json:{success:true,data:{deposit:0,withdraw:0,kyc:0,order:0}}})
   if(url.pathname==='/api/admin/notification/sounds')return route.fulfill({json:[]})
   if(url.pathname==='/api/admin/users/online-count')return route.fulfill({json:{success:true,count:0}})
   if(url.pathname==='/api/admin/support/notifications') {
    if(token==='Bearer QA2')return route.fulfill({json:{...notification,mode:'internal',queueUnread:1,chatUnread:0}})
    if(failure)return route.fulfill({status:503,json:{message:'QA temporary outage'}})
    const response={...notification};if(slow)await new Promise(resolve=>{release=resolve})
    return route.fulfill({json:response})
   }
   if(url.pathname==='/api/admin/support/sessions')return route.fulfill({json:url.searchParams.get('scope')==='queue'?[{id:800,userId:80,status:'WAITING',adminReadId:0,userReadId:0,createdAt:'2026-10-06T00:00:00Z',updatedAt:'2026-10-06T00:00:00Z'}]:[]})
   if(url.pathname==='/api/user/support/config')return route.fulfill({json:{mode:notification.mode,inboxEnabled:false}})
   throw Error('Unexpected API request: '+url.pathname)
  })
  await page.goto(base+'/__qa')
  await page.evaluate(async()=>{
   const source=await(await fetch('/src/views/Layout.vue')).text(),authSource=await(await fetch('/src/store/auth.ts')).text(),mainSource=await(await fetch('/src/main.ts')).text()
   const dep=(s,n)=>s.match(new RegExp('from ["\']([^"\']*'+n+'\\.js[^"\']*)["\']'))[1]
   const {createApp,h}=await import(dep(source,'vue')),element=await import(dep(mainSource,'element-plus')),{createPinia,setActivePinia}=await import(dep(authSource,'pinia'))
   const {createRouter,createMemoryHistory,RouterView}=await import(dep(source,'vue-router')),pinia=createPinia();setActivePinia(pinia)
   const {loadAccess,permissionDirective}=await import('/src/utils/access.ts');await loadAccess()
   const {useAuthStore}=await import('/src/store/auth.ts'),{default:Layout}=await import('/src/views/Layout.vue'),{default:SupportDesk}=await import('/src/views/SupportDesk.vue')
   const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',component:Layout,children:[{path:'dashboard',component:{render:()=>h('h1','运营看板')}},{path:'inbox',component:{render:()=>h('h1','站内信')}},{path:'support',component:SupportDesk}]}]})
   await router.push('/dashboard');await router.isReady();await import('/node_modules/element-plus/dist/index.css');await import('/src/styles/global.scss')
   createApp({render:()=>h(RouterView)}).use(pinia).use(element.default).use(router).directive('permission',permissionDirective).mount('#app')
   window.__qa={auth:useAuthStore(),router,loadAccess}
  })
  await expectCount(0)
  assert.equal(await page.locator('.notification-area .support-pending').count(),1);assert.equal(await page.locator('.support-pending.has-pending').count(),0)
  assert.equal(await page.locator('.header-right').getByRole('button',{name:/^客服/}).count(),1,'no duplicated separate support pill')
  checks.push('Support is inside pending messages; welcome-only waiting sessions do not create message alerts')
  notification={...notification,queueUnread:2}
  await expectCount(2);assert.equal(await page.locator('.support-pending.has-pending').count(),1)
  await page.screenshot({path:path.join(out,'pending-desktop.png')})
  await page.evaluate(()=>window.__qa.router.push('/inbox'));await expectCount(2)
  await page.evaluate(()=>window.__qa.router.push('/dashboard'));await expectCount(2)
  checks.push('Existing five-second poll updates unassigned customer messages without online staff or new polling; header entry remains visible on the inbox page')
  await notify({queueUnread:2,chatUnread:3});await expectCount(5)
  await page.getByRole('button',{name:'客服(5)',exact:true}).focus();await page.keyboard.press('Enter')
  await page.waitForFunction(()=>window.__qa.router.currentRoute.value.query.scope==='queue')
  await page.locator('.scope-tabs button.chosen').filter({hasText:'等待接待'}).waitFor()
  assert.equal(await page.locator('.presence').innerText(),'离线')
  await page.getByRole('button',{name:'我的会话',exact:true}).click();await page.waitForFunction(()=>window.__qa.router.currentRoute.value.query.scope==='mine')
  await page.getByRole('button',{name:'客服(5)',exact:true}).click();await page.locator('.scope-tabs button.chosen').filter({hasText:'等待接待'}).waitFor()
  checks.push('Mouse and keyboard open waiting reception, even in an already open workbench; no automatic reception or read acknowledgement')
  await notify({queueUnread:0,chatUnread:3});await expectCount(3);await page.getByRole('button',{name:'客服(3)',exact:true}).click()
  await page.locator('.scope-tabs button.chosen').filter({hasText:'我的会话'}).waitFor()
  await notify({chatUnread:0});await expectCount(0);assert.equal(await page.locator('.support-pending.has-pending').count(),0)
  checks.push('Queue and own unread messages combine without double counting; own unread opens my sessions; reading clears highlight')
  claimAllowed=false;await page.evaluate(()=>window.__qa.loadAccess(true));await notify({queueUnread:0,chatUnread:1});await expectCount(1)
  await page.getByRole('button',{name:'客服(1)',exact:true}).click();await page.locator('.scope-tabs button.chosen').filter({hasText:'我的会话'}).waitFor();assert.equal(await page.getByRole('button',{name:'等待接待',exact:true}).count(),0)
  claimAllowed=true;await page.evaluate(()=>window.__qa.loadAccess(true))
  await notify({mode:'off',queueUnread:0,chatUnread:0});await page.locator('.support-pending').waitFor({state:'hidden'})
  await notify({mode:'external'});await page.locator('.support-pending').waitFor({state:'hidden'})
  checks.push('Reception permission is respected; disabled/external service has no internal support pending entry')
  await notify({mode:'internal',queueUnread:2,chatUnread:0});await expectCount(2)
  failure=true;await notify({});await page.waitForTimeout(200);await expectCount(2)
  failure=false;await notify({queueUnread:0});await expectCount(0)
  checks.push('Temporary API failures preserve unread counts; successful retries update them')
  await notify({queueUnread:7});await expectCount(7);slow=true;await notify({})
  while(!release)await page.waitForTimeout(20)
  await page.evaluate(async()=>{window.__qa.auth.setAuth('QA2',{id:2,tenantId:2,userType:'admin',role:'admin',account:'QA2'});await window.__qa.loadAccess()})
  await expectCount(1);release();slow=false;await page.waitForTimeout(200);await expectCount(1)
  checks.push('Tenant/session changes clear old counts; delayed old-tenant responses cannot overwrite current counts')
  await page.setViewportSize({width:390,height:844});await page.waitForTimeout(150)
  assert.ok(await page.locator('.layout-header').evaluate(el=>el.scrollWidth<=el.clientWidth+1),'phone header fits viewport')
  const box=await page.locator('.support-pending').boundingBox();assert.ok(box.height>=44&&box.x>=0&&box.x+box.width<=390,'phone entry remains a visible 44-pixel tap target')
  await page.screenshot({path:path.join(out,'pending-mobile.png')})
  checks.push('Mobile top bar wraps without horizontal overflow and retains accessible support tap target')
  supportAllowed=false;await page.evaluate(()=>window.__qa.loadAccess(true));await page.locator('.support-pending').waitFor({state:'hidden'})
  const calls=requests.filter(r=>r.path==='/api/admin/support/notifications').length;await page.waitForTimeout(5300)
  assert.equal(requests.filter(r=>r.path==='/api/admin/support/notifications').length,calls,'support permission revocation stops notification polling')
  checks.push('Revoked support view hides entry and stops polling; every API request is read-only')
  assert.deepEqual(errors,[]);fs.writeFileSync(path.join(out,'result.json'),JSON.stringify({checks,errors,requests},null,2));console.log('PASS '+checks.join('; '))
 } catch(error){await page.screenshot({path:path.join(out,'failure.png')}).catch(()=>{});throw error}
 finally {if(release)release();await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})