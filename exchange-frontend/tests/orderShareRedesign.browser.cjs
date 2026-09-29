const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const out = path.resolve('reports/share-redesign-20260928')
fs.mkdirSync(out,{recursive:true})
const all = ['light','dark','chart','gold','globe','architecture','city','referenceGold','referenceWhite','referenceTerminal','launch','aurora','racing','receipt','journal','voyage']
const raw = { id: 3391, symbol: 'USDJPY', status: 'CLOSED', side: 'SELL', profit: -50, openPrice: 161.24, closePrice: 161.305, margin: 1000, quantity: 78, fee: 3, openTime: '2026-09-27T12:00:00+08:00', closeTime: '2026-09-27T12:53:00+08:00' }
;(async()=>{
const browser = await chromium.launch({executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true})
const results=[]
try {
for (const port of [5193,5195]) {
 const page=await browser.newPage({viewport:{width:port===5193?390:1440,height:900}}), errors=[]
 page.on('pageerror', e=>errors.push(e.message))
 await page.route('**/__qa',r=>r.fulfill({contentType:'text/html',body:'<!doctype html><html><body style="margin:0"><div id="app"></div></body></html>'}))
 const requested=[]
 await page.route('**/api/**',async r=>{
  const u=new URL(r.request().url()); let data={}
  if(u.pathname.endsWith('/share-templates')) {const lang=u.searchParams.get('locale');requested.push(lang);assert.equal(u.searchParams.get('details'),'true');data={templates:lang==='ja'?['referenceWhite','light']:lang==='zh-TW'?['gold','light']:all,focus:'rate'}}
  if(u.pathname.endsWith('/orders'))data={list:[raw]}
  if(u.pathname.endsWith('/timezone'))data={timezone:'UTC'}
  if(u.pathname.includes('/market/kline/'))data={ret:200,data:{symbol:'USDJPY',source:'QA',kline_list:Array.from({length:60},(_,i)=>({timestamp:1790481600+i*60,open_price:161.2,close_price:161.3,high_price:161.4,low_price:161.1}))}}
  await r.fulfill({json:data})
 })
 await page.goto(`http://127.0.0.1:${port}/__qa`)
 await page.evaluate(()=>{
   window.draws=[]; const original=CanvasRenderingContext2D.prototype.fillText;
   CanvasRenderingContext2D.prototype.fillText=function(text,x,y,...rest){window.draws.push({text,x,y,font:this.font,color:this.fillStyle});return original.call(this,text,x,y,...rest)};
 })
 await page.evaluate(async ({desktop})=>{
  const source = await (await fetch('/src/store/locale.ts')).text()
  const dependency = name => source.match(new RegExp('from [\"\']([^\"\']*'+name+'\\.js[^\"\']*)[\"\']'))[1]
  const {createApp,h}=await import(dependency('vue')),{createPinia,setActivePinia}=await import(dependency('pinia'))
  const {useLocaleStore}=await import('/src/store/locale.ts'),{default:Modal}=await import('/src/components/OrderShareModal.vue')
  const pinia=createPinia();setActivePinia(pinia);window.locale=useLocaleStore();window.locale.setLocale('zh-TW')
  createApp({render:()=>h(Modal,{orderId:3391,kind:'contract',brand:'DEMO',desktop})}).use(pinia).mount('#app')
 },{desktop:port===5195})
 await page.locator('.poster-preview').waitFor();await page.waitForFunction(()=>!document.querySelector('.pnl-save').disabled)
 assert.equal(await page.locator('.pnl-template').count(),2)
 assert.ok(await page.evaluate(()=>window.draws.some(d=>d.text==='-5.00%'&&d.y===327)), 'Admin focus reaches actual modal')
 assert.equal(await page.locator('.pnl-template[aria-pressed="true"]').innerText(),'黑金山巒')
 await page.screenshot({path:path.join(out,`modal-${port}-zh.png`)})
 await page.evaluate(()=>window.locale.setLocale('ja'))
 await page.waitForFunction(()=>document.querySelector('.pnl-template[aria-pressed="true"]')?.textContent.includes('ホワイトチャート')&&!document.querySelector('.pnl-save').disabled)
 assert.equal(await page.locator('.pnl-template').count(),2)
 await page.screenshot({path:path.join(out,`modal-${port}-ja.png`)})
 await page.evaluate(()=>{window.locale.setLocale('en');window.locale.setLocale('zh-TW')})
 await page.waitForFunction(()=>document.querySelector('.pnl-template[aria-pressed="true"]')?.textContent.includes('黑金山巒')&&!document.querySelector('.pnl-save').disabled)
 assert.equal(await page.locator('.pnl-template').count(),2)
 assert.ok(requested.includes('ja')&&requested.includes('zh-TW'))
 await page.evaluate(()=>{window.locale.setLocale('en')})
 await page.waitForFunction(()=>document.querySelectorAll('.pnl-template').length===16&&!document.querySelector('.pnl-save').disabled)
 for(let i=0;i<16;i++){await page.locator('.pnl-template').nth(i).click();await page.waitForFunction(()=>!!document.querySelector('.poster-preview')&&!document.querySelector('.pnl-save').disabled)}
 const download=page.waitForEvent('download');await page.locator('.pnl-save').click();assert.ok((await download).suggestedFilename().endsWith('.png'))
 // Render every locale/template in real Canvas, independent of enabled-language configuration.
 const count=await page.evaluate(async ({raw})=>{
   const {drawSharePoster,shareCopy,shareTemplates,shareBackgrounds,settledShareOrder,recentShareChart}=await import('/src/utils/orderShare.ts')
   const {posterLocales}=await import('/src/utils/orderShareLocales.ts'), images={}
   for(const [key,file]of Object.entries(shareBackgrounds)){const image=new Image();image.src='/share-templates/'+file;await image.decode();images[key]=image}
   const chart=recentShareChart(Array.from({length:60},(_,i)=>({timestamp:1790481600+i*60,open_price:161.2,close_price:161.3,high_price:161.4,low_price:161.1})),'QA')
   window.gallery={};let count=0
   for(const locale of Object.keys(posterLocales))for(const template of shareTemplates)for(const focus of ['amount','rate']){const canvas=document.createElement('canvas');drawSharePoster(canvas,settledShareOrder(raw,'contract'),{template,mode:'both',focus},shareCopy(locale),'DEMO','UTC',undefined,chart,images[template]);count++; if(['zh-TW','ja','en','ar','de','my'].includes(locale))window.gallery[locale+'-'+template+'-'+focus]=canvas.toDataURL()}
   return count
 },{raw})
 assert.equal(count,608)
 if(port===5193){
  await page.setViewportSize({width:1200,height:1600})
  for(const locale of ['zh-TW','ja','en','ar','de','my'])for(const focus of ['amount','rate']){
   await page.evaluate(({locale,focus})=>{document.querySelector('dialog')?.close();document.body.style.overflow='auto';document.body.innerHTML='<main style="display:grid;grid-template-columns:repeat(4,1fr);gap:12px;background:#d6dce0;padding:12px"></main>';const main=document.querySelector('main');for(const [key,src]of Object.entries(window.gallery).filter(([k])=>k.startsWith(locale+'-')&&k.endsWith('-'+focus))){const div=document.createElement('div');div.innerHTML=`<div style="font:15px Arial;padding:8px">${key}</div><img style="width:100%;display:block" src="${src}">`;main.append(div)}},{locale,focus})
   await page.screenshot({path:path.join(out,`gallery-${locale}-${focus}.png`),fullPage:true})
  }
  for(const key of ['zh-TW-referenceWhite-rate','ja-referenceWhite-rate','en-referenceWhite-rate','ar-referenceWhite-rate']){const data=await page.evaluate(key=>window.gallery[key],key);fs.writeFileSync(path.join(out,key+'.png'),Buffer.from(data.split(',')[1],'base64'))}
 }
 assert.deepEqual(errors,[]);results.push({port,canvasExports:count,languageSwitch:true,allTemplates:true,pngDownload:true,errors});await page.close()
}
fs.writeFileSync(path.join(out,'browser-results.json'),JSON.stringify(results,null,2));console.log(JSON.stringify(results))
} finally {await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)})
