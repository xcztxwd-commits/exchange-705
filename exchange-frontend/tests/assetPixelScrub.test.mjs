// Execute the actual component handlers with a deterministic pointer/Canvas fixture.
import fs from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import ts from 'typescript'
import * as helpers from '../src/utils/assetPixelWindow.ts'
const source=fs.readFileSync(new URL('../src/components/AssetPixelChart.vue',import.meta.url),'utf8')
const script=source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'')
const timers=new Map(),captured=new Set(),texts=[],alphas=[], totals=[], rectangles=[], colors=[]
const props={visible:true}
let sequence=0
const drawing=new Proxy({globalAlpha:1,measureText:t=>({width:t.length*6}),fillText:t=>texts.push(t),fillRect:(...args)=>rectangles.push(args)}, {
  get:(o,k)=>k in o?o[k]:()=>{}, set:(o,k,v)=>{o[k]=v;if(k==='globalAlpha')alphas.push(v);if(['fillStyle','strokeStyle','shadowColor'].includes(k))colors.push(v);return true}
})
const surface={getContext:()=>drawing,getBoundingClientRect:()=>({left:20,width:360}),setPointerCapture:id=>captured.add(id),hasPointerCapture:id=>captured.has(id),releasePointerCapture:id=>captured.delete(id)}
const context={...helpers,console,Intl,performance,defineProps:()=>props,defineEmits:()=>((name,value)=>totals.push(value)),useLocaleStore:()=>({locale:'zh-TW',text:(zh,en)=>zh}),ref:value=>({value}),computed:get=>({get value(){return get()}}),watch:()=>{},onMounted:()=>{},onBeforeUnmount:()=>{},setTimeout:fn=>{timers.set(++sequence,fn);return sequence},clearTimeout:id=>timers.delete(id),cancelAnimationFrame:()=>{},requestAnimationFrame:()=>1,Path2D:class{moveTo(){}lineTo(){}},request:{},window:{},document:{}}
context.globalThis=context
vm.runInNewContext(ts.transpileModule(script+`
globalThis.fixture={startPointer,movePointer,endPointer,stopPointer,resetSelection,draw,load,choose,scrubKey,refreshChart,
  get turns(){return refreshTurns.value},
  color(range,opening,current){period.value=range;from.value=1000;asOf.value=25000;points.value=[{time:1000,value:opening},{time:25000,value:current}];selected.value=-1;selectedTime.value=null;draw(1);return chartRgb.value},
  tooltip(range,point,zone='UTC'){period.value=range;timezone.value=zone;points.value=point?[point]:[];selected.value=point?0:-1;selectedTime.value=point?.time??asOf.value;return [selectedDate.value,selectedAmount.value]},
  get points(){return points.value},get failed(){return failed.value},get high(){return extrema.value?.high.value},
  get time(){return selectedTime.value}, get dragging(){return scrubbing},
  initialize(surface){canvas.value=surface;width=360;from.value=1000;asOf.value=25000;points.value=[{time:1000,value:0},{time:13000,value:100},{time:25000,value:200}]}}
`,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.None}}).outputText,context)
const f=context.fixture;f.initialize(surface)
const event=(x,y=50,type='touch')=>({clientX:x,clientY:y,pointerId:1,isPrimary:true,button:0,pointerType:type})
f.startPointer(event(200))
assert.equal(f.time,null)
for(const [id,fn] of [...timers]){timers.delete(id);fn()}
assert.equal(f.dragging,true);assert.equal(f.time,13000)
f.movePointer(event(380));assert.equal(f.time,25000)
f.movePointer(event(20));assert.equal(f.time,1000)
f.endPointer(event(200));assert.equal(f.time,null);assert.equal(f.dragging,false);assert.equal(captured.size,0)
assert.ok(alphas.includes(.16),'later curve must be dimmed')
assert.ok(!texts.some(t=>t.startsWith('$')),'no vertical price labels')
f.resetSelection();f.startPointer(event(200));f.movePointer(event(200,80))
assert.equal(timers.size,0);assert.equal(f.time,null,'vertical scrolling before hold must not select')
f.startPointer(event(200,50,'mouse'));assert.equal(f.time,13000)
f.stopPointer();assert.equal(captured.size,0)
f.resetSelection();assert.equal(f.time,null)
console.log('PASS: actual component long-press, drag, edge clamping, dimming, scroll cancellation and reset')

const response=(value)=>({schemaVersion:2,basisVersion:'net_equity_v1',points:[{time:13000,value,closeAt:13000}],live:{time:25000,value},total:value,from:1000,asOf:25000,intervalMs:60000,income:'0',incomePercent:null,timezone:'UTC',extrema:{high:{time:12000,value:'400'},low:{time:11000,value:'-500'}}})
context.request.get=async()=>response('-10')
await f.load();assert.equal(totals.at(-1),-10);assert.equal(f.high,400,'server OHLC extrema, not close values or fill')
context.request.get=async()=>response(null)
await f.load();assert.equal(totals.at(-1),null);assert.equal(f.points[0].value,null);assert.equal(f.failed,false)
const before=texts.length;props.visible=false;f.draw(1);assert.equal(texts.length,before,'hidden chart must not paint money')
props.visible=true
let resolveOld
context.request.get=()=>new Promise(resolve=>{resolveOld=resolve})
const stale=f.load()
context.request.get=async()=>response('22')
await f.load();resolveOld(response('99'));await stale;assert.equal(totals.at(-1),22,'late response cannot overwrite new range')
f.startPointer(event(200,50,'mouse'))
context.request.get=async()=>response('33')
await f.load();assert.equal(totals.at(-1),22,'hold freezes history window')
f.stopPointer();f.resetSelection()
f.scrubKey({key:'Home',preventDefault(){}});assert.equal(f.time,1000)
f.scrubKey({key:'Escape'});assert.equal(f.time,null)
console.log('PASS: negative/NULL top amount, server extrema, privacy, stale-request rejection, stable hold and keyboard')

rectangles.length=0;f.draw(1)
assert.ok(rectangles.length>0)
assert.ok(rectangles.every(([x,,w])=>x>=0 && x+w<=360),'pixel endpoints and labels stay inside canvas')
f.startPointer(event(28,50,'mouse'));assert.equal(f.time,1000);f.resetSelection()
f.startPointer(event(372,50,'mouse'));assert.equal(f.time,25000);f.resetSelection()
let resolveRefresh
context.request.get=()=>new Promise(resolve=>{resolveRefresh=resolve})
f.refreshChart();assert.equal(f.turns,1)
f.refreshChart();assert.equal(f.turns,1,'loading blocks duplicate refresh')
resolveRefresh(response('33'));await new Promise(setImmediate)
f.refreshChart();assert.equal(f.turns,2,'each accepted click adds exactly one full turn')
resolveRefresh(response('33'));await new Promise(setImmediate)
assert.ok(!source.includes('class="axis"') && !source.includes('class="chart-head"'))
assert.ok(!source.includes('margin:0 -24px') && !source.includes('radial-gradient'))
assert.match(source, /refreshTurns \* 360/)
assert.match(source, /prefers-reduced-motion:reduce/)
console.log('PASS: inset rendering/scrubbing, compact layout, repeatable refresh rotation and loading guard')

const stamp=Date.parse('2026-09-27T15:37:00Z')
const point={time:stamp,value:578.126,quality:'PARTIAL'}
for(const [range,label] of [['1D','15:37'],['1W','09/27-15:00'],['1M','09/27-12:00'],['1Y','09/27']]) {
  const [date,amount]=f.tooltip(range,point)
  assert.equal(date,label);assert.equal(amount,'$578.13')
}
assert.equal(f.tooltip('1M',{...point,time:Date.parse('2026-09-27T04:00:00Z')})[0],'09/27-04:00')
assert.equal(f.tooltip('1D',{...point,time:Date.parse('2026-09-27T16:05:00Z')},'Asia/Singapore')[0],'00:05')
assert.equal(f.tooltip('1Y',{...point,bucketStart:Date.parse('2026-09-26T00:00:00Z')},'America/Los_Angeles')[0],'09/26')
assert.equal(f.tooltip('1D',{...point,value:-1.2})[1],'$-1.20')
assert.equal(f.tooltip('1D',{...point,value:0})[1],'$0.00')
assert.equal(f.tooltip('1D',{...point,value:null})[1],'—')
assert.equal(f.tooltip('1D',null)[1],'$0.00')
assert.match(source,/\{\{ selectedDate \}\} · \{\{ selectedAmount \}\}/)
console.log('PASS: compact period labels, four-hour boundaries, timezones, daily buckets and two-decimal amounts')

for (const range of ['1D','1W','1M','1Y']) {
  for (const [opening,current,rgb] of [[100,90,'214,74,83'],[100,110,'96,177,43'],[100,100,'96,177,43'],[-100,-90,'96,177,43'],[-100,-110,'214,74,83'],[0,0,'96,177,43'],[null,100,'137,150,135'],[100,null,'137,150,135']]) {
    colors.length=0
    assert.equal(f.color(range,opening,current),rgb)
    assert.ok(colors.every(color=>color==='#fff'||color.startsWith('rgba('+rgb+',')),'all canvas layers use the range color')
  }
}
f.color('1M',100,90);colors.length=0;f.draw(.75)
assert.ok(colors.every(color=>color==='#fff'||color.startsWith('rgba(214,74,83,')),'animation stays red')
console.log('PASS: range-relative loss/red, gain-or-flat/green, unavailable/neutral across all periods and draw layers')

for (const range of ['1D','1W','1M','1Y']) {
  f.color(range,5,8)
  f.points.splice(1,0,{time:7000,value:3},{time:13000,value:8},{time:19000,value:5})
  f.startPointer(event(28))
  for(const [id,fn] of [...timers]){timers.delete(id);fn()}
  for (const [x,rgb] of [[114,'214,74,83'],[200,'96,177,43'],[286,'96,177,43'],[114,'214,74,83']]) {
    colors.length=0;f.movePointer(event(x))
    assert.ok(colors.length>0)
    assert.ok(colors.every(color=>color==='#fff'||color.startsWith('rgba('+rgb+',')),'held point is compared with opening 5')
  }
  colors.length=0;f.endPointer(event(114))
  assert.equal(f.time,null)
  assert.ok(colors.every(color=>color==='#fff'||color.startsWith('rgba(96,177,43,')),'release restores latest value 8 / green')
}
console.log('PASS: long-press 5-to-3 red, 5-to-8 green, flat green, drag recoloring and release restoration')
