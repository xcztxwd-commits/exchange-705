// Start an isolated admin Vite server, then set ADMIN_URL and EVIDENCE_DIR before running this file.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const base = process.env.ADMIN_URL, out = process.env.EVIDENCE_DIR
if (!base || !out) throw Error('ADMIN_URL and EVIDENCE_DIR are required')
const endpoint = new URL(base)
if (!['127.0.0.1','localhost'].includes(endpoint.hostname) || ['17050','17051','17052'].includes(endpoint.port)) throw Error('Use an isolated local admin server')
fs.mkdirSync(out,{recursive:true})
;(async () => {
 const browser = await chromium.launch({headless:true,...(process.env.CHROME_PATH ? {executablePath:process.env.CHROME_PATH} : {})})
 const page = await browser.newPage({viewport:{width:1500,height:1100}}), errors = [], requests = [], cases = []
 page.on('pageerror',error=>errors.push(error.message))
 try {
  await page.addInitScript(()=>sessionStorage.setItem('exchange.admin.session.v2',JSON.stringify({mode:'admin',token:'QA',user:{id:1,tenantId:1,userType:'admin',role:'admin'}})))
  await page.route('**/__qa',route=>route.fulfill({contentType:'text/html',body:'<!doctype html><html><head><meta charset="utf-8"></head><body style="margin:0"><div id="app"></div></body></html>'}))
  await page.route('**/api/**',route=>{
   const url=new URL(route.request().url());requests.push({method:route.request().method(),path:url.pathname})
   assert.equal(route.request().method(),'GET','layout changes must not write any data')
   if(url.pathname==='/api/admin/menus/current')return route.fulfill({json:{success:true,menus:[],groups:[],actions:{share_templates:['save'],users:[]}}})
   assert.equal(url.pathname,'/api/admin/share-materials');return route.fulfill({json:[]})
  })
  await page.goto(base+'/__qa')
  await page.evaluate(async()=>{
   const source=await(await fetch('/src/components/ShareTemplateEditor.vue')).text(),auth=await(await fetch('/src/store/auth.ts')).text()
   const dep=(s,n)=>s.match(new RegExp('from ["\']([^"\']*'+n+'\\.js[^"\']*)["\']'))[1]
   const {createApp,h}=await import(dep(source,'vue')),element=await import(dep(source,'element-plus')),{createPinia,setActivePinia}=await import(dep(auth,'pinia'))
   const pinia=createPinia();setActivePinia(pinia)
   const {loadAccess,permissionDirective}=await import('/src/utils/access.ts');await loadAccess()
   const {default:Editor}=await import('/src/components/ShareTemplateEditor.vue');await import('/node_modules/element-plus/dist/index.css')
   const app=createApp({render:()=>h(element.ElDialog,{modelValue:true,width:'min(1500px,98vw)',top:'2vh',showClose:false,closeOnClickModal:false,class:'share-editor-dialog'},()=>h(Editor,{rule:{id:'light',name:'极简方刊',base:'light',focus:'amount',languages:['*']},editable:true,language:'zh-TW'}))}).use(pinia).use(element.default)
   app.directive('permission',permissionDirective);app.mount('#app')
  })
  await page.locator('.designer-stage>img').waitFor();await page.waitForTimeout(400)
  const measure=()=>page.locator('.designer-columns').evaluate(el=>Array.from(el.children).map(node=>{const box=node.getBoundingClientRect(),css=getComputedStyle(node);return {top:box.top,bottom:box.bottom,height:box.height,width:box.width,clientHeight:node.clientHeight,scrollHeight:node.scrollHeight,overflow:css.overflowY}}))
  async function aligned(label){
   await page.waitForTimeout(100);const [left,center,right]=await measure();cases.push({label,left,center,right});fs.writeFileSync(path.join(out,'panel-layout.json'),JSON.stringify(cases,null,2))
   assert.ok(Math.abs(left.top-center.top)<1&&Math.abs(right.top-center.top)<1,label+': panel tops align')
   assert.ok(Math.abs(left.bottom-center.bottom)<1&&Math.abs(right.bottom-center.bottom)<1,label+': panel bottoms align')
   assert.equal(left.overflow,'auto');assert.equal(right.overflow,'auto');return {left,center,right}
  }
  async function canvasSize(name){await page.locator('.el-select').filter({has:page.getByRole('combobox',{name:'画布尺寸',exact:true})}).click();await page.getByRole('option',{name,exact:true}).click();await page.locator('.designer-stage>img').waitFor()}
  await aligned('portrait 1500x1100')
  for(const [name,label] of [['方图 1080 × 1080','square'],['横图 1440 × 900','landscape']]){await canvasSize(name);const data=await aligned(label);if(label==='landscape'){
   for(const [index,expected] of [[0,data.left],[1,data.right]]){assert.ok(expected.scrollHeight>expected.clientHeight,'long side panel remains internally scrollable');const panel=page.locator('.designer-panel').nth(index);await panel.evaluate(el=>{el.scrollTop=el.scrollHeight});assert.ok(await panel.evaluate(el=>el.scrollTop>0&&Math.abs(el.scrollTop+el.clientHeight-el.scrollHeight)<=1),'panel can reach its last control')}
  }}
  const right=page.locator('.designer-panel').last();await right.getByRole('button',{name:'生成预览图',exact:true}).scrollIntoViewIfNeeded();await right.getByRole('button',{name:'生成预览图',exact:true}).click();await page.locator('.designer-stage>img').waitFor()
  await page.screenshot({path:path.join(out,'landscape-aligned.png')})
  await page.getByRole('tab',{name:'图层',exact:true}).click();await aligned('layers tab')
  await page.getByRole('tab',{name:'素材库',exact:true}).click();await page.getByText('暂无素材，上传图片或将形状存入素材库。',{exact:true}).waitFor();await aligned('empty materials tab')
  await page.getByRole('tab',{name:'模板',exact:true}).click();await canvasSize('竖图 1080 × 1440')
  await page.setViewportSize({width:1280,height:900});await aligned('portrait 1280x900')
  await right.getByRole('button',{name:'生成预览图',exact:true}).scrollIntoViewIfNeeded();await page.screenshot({path:path.join(out,'portrait-aligned.png')})
  for(const viewport of [{width:1024,height:900},{width:390,height:844}]){
   await page.setViewportSize(viewport);await page.waitForTimeout(150);const panels=await measure();cases.push({label:'responsive '+viewport.width,panels});assert.ok(panels.every(panel=>panel.height>100),'responsive panels must not collapse');assert.ok(await page.locator('.share-designer').evaluate(el=>el.scrollWidth<=el.clientWidth+1),'no horizontal overflow');assert.ok(await right.getByRole('button',{name:'生成预览图',exact:true}).isVisible())
  }
  fs.writeFileSync(path.join(out,'panel-layout.json'),JSON.stringify({cases,errors,requests},null,2));assert.deepEqual(errors,[])
  console.log('PASS panel top/bottom alignment, portrait/square/landscape, independent scrolling to last controls, layer/material tabs, narrow/mobile layout; no API writes or browser errors')
 } catch(error){await page.screenshot({path:path.join(out,'failure.png')}).catch(()=>{});throw error}
 finally {await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})
