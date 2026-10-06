// Run against an isolated local admin Vite server. All APIs are mocked; any non-read request fails.
const {chromium}=require(process.env.PLAYWRIGHT_PATH||'playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path')
const base=process.env.ADMIN_URL,out=process.env.EVIDENCE_DIR
if(!base||!out||!['127.0.0.1','localhost'].includes(new URL(base).hostname))throw Error('Use an isolated local server and evidence directory')
fs.mkdirSync(out,{recursive:true})
;(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROME_PATH?{executablePath:process.env.CHROME_PATH}:{})}),page=await browser.newPage({viewport:{width:1300,height:800}})
 const requests=[],errors=[],checks=[],finished=Math.floor(Date.now()/60000)*60000,from=finished-7*86400000
 let failOlder=false,blockOlder=false,release,blocked=false,pendingPages=0,emptyPage=false
 page.on('pageerror',e=>errors.push(e.message))
 const count=async n=>page.getByText(new RegExp('已显示 '+n+' 根')).waitFor({timeout:7500})
 const visible=()=>page.evaluate(()=>{const c=window.__qa.chart(),o=c.getOption(),data=o.xAxis[0].data,z=o.dataZoom[0];return [data[Math.round(z.start/100*(data.length-1))],data[Math.round(z.end/100*(data.length-1))]]})
 try{
  await page.addInitScript(()=>sessionStorage.setItem('exchange.admin.session.v2',JSON.stringify({mode:'admin',token:'QA',user:{id:1,tenantId:1,userType:'admin',role:'super_admin',account:'QA'}})))
  await page.route('**/__qa',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"></head><body><div id="app"></div></body></html>'}))
  await page.route('**/api/**',async route=>{
   const request=route.request(),url=new URL(request.url());assert.equal(request.method(),'GET','chart navigation cannot change orders, funds or market state')
   if(url.pathname==='/api/admin/menus/current')return route.fulfill({json:{success:true,superAdmin:true,menus:[{menuCode:'orders',path:'/orders'}],groups:[],actions:{orders:['manual_order']}}})
   if(url.pathname!=='/api/admin/orders/contract/manual/chart')throw Error('Unexpected API: '+url.pathname)
   const symbol=url.searchParams.get('symbol'),cursor=url.searchParams.get('endTime');assert.equal(url.searchParams.get('limit'),'200')
   requests.push({symbol,endTime:cursor===null?null:Number(cursor),limit:200})
   const isOlder=cursor!==null&&Number(cursor)<finished-1
   if(isOlder&&failOlder){failOlder=false;return route.fulfill({status:503,json:{message:'隔离测试：行情暂不可用'}})}
   if(isOlder&&blockOlder){blocked=true;await new Promise(resolve=>release=resolve);blocked=false;release=undefined}
   else if(isOlder)await new Promise(resolve=>setTimeout(resolve,650))
   const to=Math.floor((cursor===null?finished-1:Number(cursor))/60000)*60000+60000,eur=symbol==='EURUSD=X'
   const candles=Array.from({length:200},(_,i)=>{const t=to-(200-i)*60000;return {timestamp:t,local:new Date(t-4*3600000).toISOString().slice(0,16),offset:'-04:00',price:eur?'1.0812345678901234':'157.5751234567890123',low:eur?'1':'157',high:eur?'1.1':'158',close:eur?'1.09':'157.6'}}).filter(c=>c.timestamp>=from)
   const pending=pendingPages>0;if(pending)pendingPages--;const shown=emptyPage?[]:pending?candles.slice(-100):candles;emptyPage=false
   const oldest=shown[0]?.timestamp??Math.max(from,to-200*60000)
   await route.fulfill({json:{candles:shown,pending,status:pending?'loading':shown.length?'available':'empty',source:'yahoo',from,to,nextEndTime:oldest-1,hasMore:oldest>from}})
  })
  await page.goto(base+'/__qa')
  await page.evaluate(async()=>{
   const source=await(await fetch('/src/components/ManualOrderChart.vue')).text(),authSource=await(await fetch('/src/store/auth.ts')).text(),dep=(s,n)=>s.match(new RegExp('from ["\']([^"\']*'+n+'\\.js[^"\']*)["\']'))[1]
   const {createApp,h,reactive}=await import(dep(source,'vue')),{createPinia,setActivePinia}=await import(dep(authSource,'pinia')),element=await import((await(await fetch('/src/main.ts')).text()).match(/from ["']([^"']*element-plus\.js[^"']*)["']/)[1]),echarts=await import(dep(source,'echarts'))
   const pinia=createPinia();setActivePinia(pinia);const {permissionDirective,loadAccess}=await import('/src/utils/access.ts');await loadAccess()
   const {default:Chart}=await import('/src/components/ManualOrderChart.vue');await import('/node_modules/element-plus/dist/index.css');await import('/src/styles/global.scss')
   const model=reactive({symbol:'JPY=X',timezone:'America/New_York',active:true,disabled:false,openTime:undefined,closeTime:undefined}),selections=[]
   createApp({render:()=>h(Chart,{...model,onSelect:range=>{selections.push(range);model.openTime=range.open.timestamp;model.closeTime=range.close.timestamp}})}).use(pinia).use(element.default).directive('permission',permissionDirective).mount('#app')
   window.__qa={model,selections,chart:()=>echarts.getInstanceByDom(document.querySelector('.candles'))}
  })
  const firstStarted=Date.now();await page.getByRole('button',{name:'图表选择开平仓',exact:true}).click();await count(200)
  const firstBatchMs=Date.now()-firstStarted;assert.equal(requests.length,1);assert.equal(await page.locator('.candles canvas').count(),1)
  checks.push('First 200 completed candles render from one request, without loading seven days eagerly')
  await page.locator('.candles').focus();await page.keyboard.press('ArrowLeft')
  const selection=await page.evaluate(()=>window.__qa.selections.at(-1));assert.equal(selection.open.price,'157.5751234567890123');assert.ok(selection.open.timestamp<selection.close.timestamp)
  const originalView=await visible(),firstCursor=finished-200*60000-1
  const olderResponse=page.waitForResponse(r=>r.url().includes('/manual/chart')&&new URL(r.url()).searchParams.get('endTime')===String(firstCursor))
  await page.getByRole('button',{name:'加载更早行情',exact:true}).click();await page.waitForTimeout(100);await count(200)
  assert.equal(await page.locator('.candles canvas').count(),1,'existing chart stays visible during slow older-page fetch')
  await olderResponse;await count(400);assert.deepEqual(await visible(),originalView)
  assert.deepEqual(await page.evaluate(()=>[window.__qa.model.openTime,window.__qa.model.closeTime]),[selection.open.timestamp,selection.close.timestamp])
  checks.push('Delayed earlier page prepends without blanking the chart, moving the visible time window or changing selected prices/times')
  const refreshed=page.waitForResponse(r=>r.url().includes('/manual/chart')&&!new URL(r.url()).searchParams.has('endTime'))
  await page.getByRole('button',{name:'刷新行情',exact:true}).click();await refreshed;await count(400);assert.deepEqual(await visible(),originalView)
  checks.push('Refreshing the latest page deduplicates candles and retains loaded older history')
  failOlder=true;await page.getByRole('button',{name:'加载更早行情',exact:true}).click();await page.getByRole('status').filter({hasText:'行情暂不可用'}).waitFor();await count(400)
  await page.getByRole('button',{name:'加载更早行情',exact:true}).click();await count(600)
  checks.push('A failed older-page request retains existing candles and can be retried')
  await page.evaluate(()=>window.__qa.chart().dispatchAction({type:'dataZoom',start:0,end:25}));const leftView=await visible();await count(800);assert.deepEqual(await visible(),leftView)
  checks.push('Browsing the left edge fetches one bounded earlier page automatically, preserving the visible timestamps')
  await page.screenshot({path:path.join(out,'chart-paged-desktop.png')})
  blockOlder=true;await page.getByRole('button',{name:'加载更早行情',exact:true}).click();while(!blocked)await page.waitForTimeout(20)
  await page.evaluate(()=>window.__qa.model.symbol='EURUSD=X');await page.getByRole('button',{name:'图表选择开平仓',exact:true}).click();await count(200)
  release();blockOlder=false;await page.waitForTimeout(200);await count(200)
  assert.ok(await page.evaluate(()=>window.__qa.chart().getOption().series[0].data.every(row=>row[0]<2)))
  checks.push('Changing symbol cancels and rejects stale old-symbol responses')
  await page.setViewportSize({width:390,height:844});await page.waitForTimeout(150);await page.screenshot({path:path.join(out,'chart-paged-mobile.png')})
  assert.ok(await page.locator('.chart-panel').evaluate(el=>el.scrollWidth<=el.clientWidth+1));checks.push('Mobile progress and earlier-page button fit the viewport')
  await page.evaluate(()=>window.__qa.model.active=false);const stopped=requests.length;await page.waitForTimeout(1700);assert.equal(requests.length,stopped)
  checks.push('Deactivating the form stops any further chart requests')
  pendingPages=1;await page.evaluate(()=>{window.__qa.model.active=true;window.__qa.model.symbol='JPY=X'})
  const pendingStart=requests.length;await page.getByRole('button',{name:'图表选择开平仓',exact:true}).click();await count(100)
  await page.getByRole('status').filter({hasText:'已有完整分钟可直接选择'}).waitFor();assert.equal(await page.locator('.candles canvas').count(),1)
  await count(200);assert.equal(requests.length,pendingStart+2);assert.equal(requests.at(-1).endTime,finished-1)
  checks.push('Pending pages render their existing complete candles immediately and retry only the same stable 200-minute cursor')
  pendingPages=3;await page.getByRole('button',{name:'刷新行情',exact:true}).click();await page.getByRole('status').filter({hasText:'已有完整分钟可直接选择'}).waitFor();await count(200)
  await page.getByRole('button',{name:'收起图表',exact:true}).click();const closed=requests.length;await page.waitForTimeout(1700);assert.equal(requests.length,closed)
  checks.push('Collapsing a pending chart cancels its timer without clearing existing loaded history')
  pendingPages=0;emptyPage=true;await page.evaluate(()=>window.__qa.model.symbol='EURUSD=X');await page.getByRole('button',{name:'图表选择开平仓',exact:true}).click();await count(0)
  await page.getByRole('status').filter({hasText:'这一段不足两根'}).waitFor();assert.equal(await page.locator('.candles canvas').count(),0)
  await page.getByRole('button',{name:'加载更早行情',exact:true}).click();await count(200)
  checks.push('An empty current page stays explicit and allows an older-page request without inventing candles')
  assert.deepEqual(errors,[]);fs.writeFileSync(path.join(out,'browser-result.json'),JSON.stringify({checks,firstBatchMs,requests,errors,mockedApis:true},null,2));console.log('PASS '+checks.join('; '))
 }catch(e){await page.screenshot({path:path.join(out,'browser-failure.png')}).catch(()=>{});throw e}finally{if(release)release();await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1})
