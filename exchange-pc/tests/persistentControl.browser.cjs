const {chromium} = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const out = path.join(require('node:os').tmpdir(), '705-control-browser')
fs.mkdirSync(out,{recursive:true})
;(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe'})
 try {
  for(const app of ['pc','mobile']) {
   const page=await browser.newPage({viewport:app==='pc'?{width:1500,height:950}:{width:390,height:844}})
   const errors=[];page.on('pageerror',e=>errors.push(e.message))
   const minute=Math.floor(Date.now()/60000)*60000
   let phase=0,socket,historyCalls=0
   await page.addInitScript(()=>{localStorage.setItem('token','control-fixture');localStorage.setItem('locale','en')})
   await page.routeWebSocket('**/api/ws/market',ws=>{socket=ws;ws.onMessage(raw=>{const m=JSON.parse(raw);if(m.action==='ping')ws.send(JSON.stringify({type:'pong'}))})})
   await page.route('**/api/**',async route=>{
    const url=new URL(route.request().url());let body={ret:200,data:[],list:[],success:true}
    if(/\/market\/(all|symbols)$/.test(url.pathname))body={list:[{symbol:'XAUUSD',alltickSymbol:'XAUUSD',category:'Metal',pricePrecision:2}]}
    else if(url.pathname.endsWith('/timezone'))body={timezone:'UTC'}
    else if(url.pathname.includes('/kline/')&&!url.pathname.endsWith('/batch')){
     const interval=url.searchParams.get('interval'),width={'1m':60000,'5m':300000,'1h':3600000}[interval]||300000
     const limit=Number(url.searchParams.get('limit'))||200,cursor=Number(url.searchParams.get('endTime'))||Infinity
     if(Number.isFinite(cursor))historyCalls++
     const end=Math.min(Math.floor(minute/width)*width,Math.floor(cursor/width)*width)
     const rows=Array.from({length:limit},(_,i)=>({timestamp:end-(limit-i-1)*width,open_price:90,high_price:95,low_price:85,close_price:90,volume:10}))
     if(cursor>=minute && phase>0){rows.at(-1).high_price=110;rows.at(-1).close_price=phase===1?105:110}
     if(cursor>=minute && phase>=2 && width===60000)rows.push({timestamp:minute+60000,open_price:110,high_price:120,low_price:110,close_price:120,volume:0})
     body={ret:200,data:{status:'available',merged:true,kline_list:rows.slice(-limit)}}
    }
    await route.fulfill({json:body})
   })
   const read=()=>page.locator('.chart-workspace').evaluate(el=>el.__vueParentComponent.setupState.chart.getDataList().map(b=>({...b})))
   const quote=state=>socket.send(JSON.stringify({type:'price',data:{XAUUSD:{price:120,timestamp:Date.now(),fetchedAt:Date.now()-90000,expiresAt:Date.now()-80000,available:false,status:'unavailable',sourceAvailable:false,sourceTimestamp:Date.now()-90000,controlHistory:true,controlTaskId:'control-1',controlState:state,marketRevision:1}}}))
   await page.goto(app==='pc'?'http://127.0.0.1:5187/':'http://127.0.0.1:5188/#/trade?symbol=XAUUSD&category=Metal')
   await page.waitForFunction(()=>document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.count===200)
   // Exercise minute boundary explicitly.
   await page.getByRole('button',{name:/^1m$/i}).click()
   await page.waitForFunction(()=>document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.interval==='1m')
   await page.waitForFunction(()=>document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.count===200)
   const before=await read();phase=1;quote('RUNNING')
   await page.waitForFunction(()=>document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.chart.getDataList().at(-1).close===105)
   phase=2;quote('WAITING_SOURCE')
   await page.waitForFunction(()=>document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.chart.getDataList().at(-1).close===120)
   let bars=await read();assert.equal(bars.at(-2).close,110);assert.equal(bars.at(-1).timestamp-bars.at(-2).timestamp,60000)
   assert.deepEqual(bars.slice(0,198),before.slice(0,198));assert.equal(new Set(bars.map(b=>b.timestamp)).size,bars.length)
   const state=await page.locator('.chart-workspace').evaluate(el=>{const s=el.__vueParentComponent.setupState;return s.market.getQuoteStatus(s.props.symbol)})
   assert.notEqual(state,'available','chart controls must never open trading')
   await page.getByText('Static price · Waiting for source',{exact:true}).waitFor()
   await page.screenshot({path:path.join(out,app+'-waiting.png'),fullPage:false})
   await page.reload();await page.waitForFunction(()=>document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.count===200)
   quote('WAITING_SOURCE');await page.getByRole('button',{name:/^1m$/i}).click()
   await page.waitForFunction(()=>document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.chart.getDataList().at(-1).close===120)
   bars=await read();assert.equal(bars.at(-2).close,110)
   await page.getByRole('button',{name:/^1h$/i}).click()
   await page.waitForFunction(()=>{const s=document.querySelector('.chart-workspace')?.__vueParentComponent.setupState;return s?.interval==='1h'&&!s.loading&&s.count===200})
   assert.equal((await read()).at(-1).close,110)
   await page.locator('.chart-workspace').evaluate(el=>el.__vueParentComponent.setupState.chart.scrollToDataIndex(0,0))
   await page.waitForFunction(()=>document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.count>200)
   assert.ok(historyCalls>0);assert.deepEqual(errors,[])
   quote('HOLDING')
   await page.getByText('Holding offset · Manual restore required', {exact:false}).waitFor()
   const heldStatus=await page.locator('.chart-workspace').evaluate(el=>{const s=el.__vueParentComponent.setupState;return s.market.getQuoteStatus(s.props.symbol)})
   assert.notEqual(heldStatus,'available')
   await page.screenshot({path:path.join(out,app+'-holding.png'),fullPage:false})
   console.log(app+': PASS outage display/trade separation, final minute update, cross-minute append, reload, period switch, history paging, console')
   await page.close()
  }
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1})
