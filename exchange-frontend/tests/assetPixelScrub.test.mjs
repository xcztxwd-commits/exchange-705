// Execute the actual component handlers with a deterministic pointer/Canvas fixture.
import fs from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import ts from 'typescript'
import * as helpers from '../src/utils/assetPixelWindow.ts'
const source=fs.readFileSync(new URL('../src/components/AssetPixelChart.vue',import.meta.url),'utf8')
const script=source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'')
const timers=new Map(),captured=new Set(),texts=[],alphas=[], totals=[], inspections=[], rectangles=[], colors=[], strokes=[]
const labels=[]
const props={visible:true}
let sequence=0
const drawing=new Proxy({globalAlpha:1,measureText:t=>({width:t.length*6}),fillText:(t,x,y)=>{texts.push(t);labels.push({text:t,x,y})},fillRect:(...args)=>rectangles.push(args),beginPath(){this.path=[]},moveTo(x,y){this.path.push([x,y])},lineTo(x,y){this.path.push([x,y])},stroke(path){strokes.push({points:path?.points??this.path,width:this.lineWidth})}}, {
  get:(o,k)=>k in o?o[k]:()=>{}, set:(o,k,v)=>{o[k]=v;if(k==='globalAlpha')alphas.push(v);if(['fillStyle','strokeStyle','shadowColor'].includes(k))colors.push(v);return true}
})
const surface={getContext:()=>drawing,getBoundingClientRect:()=>({left:20,width:360}),setPointerCapture:id=>captured.add(id),hasPointerCapture:id=>captured.has(id),releasePointerCapture:id=>captured.delete(id)}
const context={visitorTimezone:'UTC',getSystemTimezone:()=>context.visitorTimezone,...helpers,inspections,console,Intl,performance,defineProps:()=>props,defineEmits:()=>((name,value)=>{if(name==='total')totals.push(value);else if(name==='inspect')inspections.push(value)}),useLocaleStore:()=>({locale:'zh-TW',text:(zh,en)=>zh}),ref:value=>({value}),computed:get=>({get value(){return get()}}),watch:()=>{},onMounted:()=>{},onBeforeUnmount:()=>{},setTimeout:fn=>{timers.set(++sequence,fn);return sequence},clearTimeout:id=>timers.delete(id),cancelAnimationFrame:()=>{},requestAnimationFrame:()=>1,Path2D:class{points=[];moveTo(x,y){this.points.push([x,y])}lineTo(x,y){this.points.push([x,y])}},request:{},window:{},document:{}}
context.globalThis=context
vm.runInNewContext(ts.transpileModule(script+`
globalThis.fixture={startPointer,movePointer,endPointer,stopPointer,resetSelection,draw,load,choose,scrubKey,refreshChart,
  get turns(){return refreshTurns.value},
  color(range,opening,current){period.value=range;from.value=1000;asOf.value=25000;points.value=[{time:1000,value:opening},{time:25000,value:current}];selectedTime.value=null;draw(1);return chartRgb.value},
  tooltip(range,point,zone='UTC',time=point?.time??asOf.value){period.value=range;globalThis.visitorTimezone=zone;points.value=point?[point]:[];selectedTime.value=time;return [selectedDate.value,selectedAmount.value]},
  get points(){return points.value},get failed(){return failed.value},get high(){return extrema.value?.high.value},
  get time(){return selectedTime.value}, get dragging(){return scrubbing}, get inspection(){return inspections.at(-1)},
  setWindow(start,end,data){from.value=start;asOf.value=end;points.value=data},
  initialize(surface){canvas.value=surface;width=360;from.value=1000;asOf.value=25000;points.value=[{time:1000,value:0},{time:13000,value:100},{time:25000,value:200}]}}
`,{compilerOptions:{target:ts.ScriptTarget.ES2020,module:ts.ModuleKind.None}}).outputText,context)
const f=context.fixture;f.initialize(surface)
const event=(x,y=50,type='touch')=>({clientX:x,clientY:y,pointerId:1,isPrimary:true,button:0,pointerType:type})
f.startPointer(event(200))
assert.equal(f.time,null)
for(const [id,fn] of [...timers]){timers.delete(id);fn()}
assert.equal(f.dragging,true);assert.equal(f.time,13000)
assert.equal(f.inspection.amount,100)
f.movePointer(event(380));assert.equal(f.time,25000);assert.equal(f.inspection.amount,200)
f.movePointer(event(20));assert.equal(f.time,1000);assert.equal(f.inspection.amount,0)
f.endPointer(event(200));assert.equal(f.time,null);assert.equal(f.inspection,null);assert.equal(f.dragging,false);assert.equal(captured.size,0)
assert.ok(alphas.includes(.16),'later curve must be dimmed')
assert.ok(!texts.some(t=>t.startsWith('$')),'no vertical price labels')
f.resetSelection();f.startPointer(event(200));f.movePointer(event(200,80))
assert.equal(timers.size,0);assert.equal(f.time,null,'vertical scrolling before hold must not select')
f.startPointer(event(200));f.movePointer(event(207));assert.equal(f.time,null,'horizontal drag before hold must not select')
for(const [id,fn] of [...timers]){timers.delete(id);fn()}
assert.equal(f.time,13000);f.resetSelection()
f.startPointer(event(200));f.movePointer(event(220));assert.equal(timers.size,0,'large movement cancels the pending hold')
assert.equal(f.time,null)
f.startPointer(event(200,50,'mouse'));assert.equal(f.time,13000)
f.stopPointer();assert.equal(captured.size,0)
f.resetSelection();assert.equal(f.time,null)
console.log('PASS: actual component long-press, drag, edge clamping, dimming, scroll cancellation and reset')

const response=(value)=>({schemaVersion:2,basisVersion:'net_equity_v1',points:[{time:13000,value,closeAt:13000}],live:{time:25000,value},total:value,from:1000,asOf:25000,intervalMs:60000,income:'0',incomePercent:null,timezone:'UTC',extrema:{high:{time:12000,value:'400'},low:{time:11000,value:'-500'}}})
context.request.get=async()=>response('-10')
await f.load();assert.equal(totals.at(-1),-10);assert.equal(f.high,0,'no earlier observation starts the displayed range at zero')
f.scrubKey({key:'Home',preventDefault(){}});assert.equal(f.inspection.amount,0);f.scrubKey({key:'Escape'});assert.equal(f.inspection,null)
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
console.log('PASS: negative/NULL top amount, plotted extrema, privacy, stale-request rejection, stable hold and keyboard')

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
for(const [range,label] of [['1D','15:37'],['1W','09/27 15:00'],['1M','09/27 15:00'],['1Y','09/27']]) {
  const [date,amount]=f.tooltip(range,point)
  assert.equal(date,label);assert.equal(amount,578.126)
}
assert.equal(f.tooltip('1D',point,'UTC',stamp+15*60000)[0],'15:52','time follows finger, not previous observation')
assert.equal(f.tooltip('1M',point,'UTC',stamp+23*60000)[0],'09/27 16:00')
assert.equal(f.tooltip('1M',{...point,time:Date.parse('2026-09-27T04:00:00Z')})[0],'09/27 04:00')
assert.equal(f.tooltip('1D',{...point,time:Date.parse('2026-09-27T16:05:00Z')},'Asia/Singapore')[0],'00:05')
assert.equal(f.tooltip('1Y',{...point,bucketStart:Date.parse('2026-09-26T00:00:00Z')},'America/Los_Angeles')[0],'09/27')
assert.equal(f.tooltip('1D',{...point,value:-1.2})[1],-1.2)
assert.equal(f.tooltip('1D',{...point,value:0})[1],0)
assert.equal(f.tooltip('1D',{...point,value:null})[1],null)
assert.equal(f.tooltip('1D',null)[1],null)
assert.doesNotMatch(source,/point-detail|selectedCarried|selectedSourceTime/,'chart tooltip and source text are gone')
assert.match(source,/v-if="visible && selectedTime === null"/,'income is hidden during inspection')
const profile=fs.readFileSync(new URL('../src/views/Profile.vue',import.meta.url),'utf8')
assert.match(profile,/@inspect="inspectedAsset = \$event"/)
assert.doesNotMatch(profile,/class="inspection-time"/,'time is no longer fixed in the header')
assert.match(source,/class="sr-only" role="status">\{\{ selectedDate \}\}/,'canvas time stays accessible')
assert.match(profile,/displayedAssets === null \? '—' : '\$' \+ formatMoney\(displayedAssets\)/)
f.resetSelection();f.setWindow(stamp,stamp+3600000,[{time:stamp,value:578.126}])
f.startPointer(event(28))
for(const [id,fn] of [...timers]){timers.delete(id);fn()}
assert.equal(f.inspection.time,'15:37');assert.equal(f.inspection.amount,578.126)
f.movePointer(event(200));assert.equal(f.inspection.time,'16:07');assert.equal(f.inspection.amount,578.126)
f.endPointer(event(200));assert.equal(f.inspection,null)
labels.length=0;strokes.length=0
f.startPointer(event(114))
for(const [id,fn] of [...timers]){timers.delete(id);fn()}
const firstTime=labels.at(-1),firstLine=strokes.findLast(s=>s.points?.[0]?.[1]===22)
assert.equal(firstTime.x,94);assert.equal(firstTime.y,11);assert.equal(firstLine.points[0][0],firstTime.x)
labels.length=0;strokes.length=0;f.movePointer(event(286))
const nextTime=labels.at(-1),nextLine=strokes.findLast(s=>s.points?.[0]?.[1]===22)
assert.equal(nextTime.x,266);assert.notEqual(nextTime.text,firstTime.text);assert.equal(nextLine.points[0][0],nextTime.x)
f.endPointer(event(286));assert.equal(f.time,null)
f.setWindow(stamp,stamp+3600000,[{time:stamp,value:null}]);labels.length=0;strokes.length=0
f.startPointer(event(200,50,'mouse'))
assert.equal(f.inspection.amount,null)
assert.equal(labels.at(-1).x,strokes.findLast(s=>s.points?.[0]?.[1]===22).points[0][0],'time and vertical line remain visible without a value')
f.resetSelection()
console.log('PASS: period time follows finger at the top of its own vertical line, not the header')

for (const range of ['1D','1W','1M','1Y']) {
  for (const [opening,current,rgb] of [[100,90,'214,74,83'],[100,110,'96,177,43'],[100,100,'96,177,43'],[-100,-90,'96,177,43'],[-100,-110,'214,74,83'],[0,0,'96,177,43'],[null,100,'137,150,135'],[100,null,'96,177,43']]) {
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

// A short spike falls between the 68 time columns; both vertical edges must survive.
for (const range of ['1D','1W','1M','1Y']) {
  f.color(range,0,0)
  f.points.splice(1,0,{time:13000,value:100000},{time:13100,value:0})
  const original=JSON.stringify(f.points)
  const prices=helpers.assetPriceTicks(f.points.map(p=>p.value))
  const yAt=value=>155-(value-prices[0])/(prices[3]-prices[0])*128
  for (const progress of [.75,1]) {
    rectangles.length=0;f.draw(progress)
    for (const time of [13000,13100]) {
      const fraction=(time-1000)/24000, x=8+fraction*344
      const phase=Math.max(0,Math.min(1,(progress-fraction*.61)/.39))
      const offset=(1-phase)**3*13
      const ys=rectangles.filter(([left,,w,h])=>Math.abs(left+1.7-x)<1e-6&&w===3.4&&h===3.4).map(([,top])=>top+1.7).sort((a,b)=>a-b)
      assert.ok(ys.length>10,'jump must have a solid particle column, not only a line')
      assert.ok(Math.abs(ys[0]-yAt(100000)-offset)<1e-6,'particles start at the high endpoint')
      assert.ok(Math.abs(ys.at(-1)-yAt(0)-offset)<1e-6,'particles reach the low endpoint')
      assert.ok(ys.slice(1).every((y,i)=>y-ys[i]<=5.1+1e-6),'no vertical gaps over one particle step')
    }
  }
  assert.equal(JSON.stringify(f.points),original,'rendering must not fabricate observations')
}
f.color('1M',0,100000);rectangles.length=0;f.draw(1)
assert.ok(rectangles.filter(([x,,w,h])=>Math.abs(x+1.7-352)<1e-6&&w===3.4&&h===3.4).length>10,'live endpoint jump is filled')
f.color('1M',null,100000);rectangles.length=0;f.draw(1)
const firstTicks=helpers.assetPriceTicks([100000])
const fillRows=Math.ceil((100000-firstTicks[0])/(firstTicks[3]-firstTicks[0])*128/5.1)
assert.equal(rectangles.filter(([x,,w,h])=>Math.abs(x+1.7-352)<1e-6&&w===3.4&&h===3.4).length,fillRows,'first observation has only its normal area fill, not a fabricated vertical edge')
console.log('PASS: vertical particles cover off-grid spikes, drops and live jumps during animation in all ranges without inventing history')

for (const range of ['1D','1W','1M','1Y']) {
  f.color(range,100,900)
  context.request.get=async()=>({...response('900'),carryIn:{time:500,value:'900'},points:[{time:7000,value:'1000'},{time:19000,value:'100'}],extrema:{high:{time:6000,value:'1100'},low:{time:18000,value:'50'}}})
  await f.load();labels.length=0;f.draw(1)
  const high=labels.find(p=>p.text==='最高 $1,000.00'),low=labels.find(p=>p.text==='最低 $100.00')
  assert.ok(high&&low,'labels use period final values instead of intraperiod extremes')
  assert.equal(labels.length,2)
  const ticks=helpers.assetPriceTicks([1000,100,900])
  const yAt=value=>155-(value-ticks[0])/(ticks[3]-ticks[0])*128
  assert.equal(high.y,yAt(1000)-16)
  assert.equal(low.y,yAt(100)+16)
  assert.equal(high.x+high.text.length*3,94,'high label centered over its point')
  assert.equal(low.x+low.text.length*3,266,'low label centered under its point')
}
// The carried opening is visible at the left edge, never at its off-screen source time.
context.request.get=async()=>({...response('100'),carryIn:{time:500,value:'2000'},points:[{time:13000,value:'100'}]})
await f.load();labels.length=0;f.draw(1)
assert.equal(f.high,2000)
assert.ok(labels.some(p=>p.text==='最高 $2,000.00'&&p.x>=0))
f.color('1M',100,100);labels.length=0;f.draw(1)
assert.equal(labels.length,2);assert.ok(labels[0].y<labels[1].y,'flat curve has high above and low below')
f.color('1M',null,null);labels.length=0;f.draw(1)
assert.equal(labels.length,0,'unavailable history has no fabricated extrema')
context.request.get=async()=>({...response('100'),points:[{time:13000,value:'100'}]})
await f.load();labels.length=0;f.draw(1)
assert.ok(labels.some(p=>p.text==='最低 $0.00'),'no predecessor shows a zero opening in the plotted range')
strokes.length=0;f.draw(1)
const zeroRise=strokes.find(s=>s.width===2.2&&s.points?.length>=3)
assert.ok(zeroRise,'inferred zero is drawn as a visible continuous step')
assert.equal(zeroRise.points[0][1],zeroRise.points[1][1])
assert.equal(zeroRise.points[1][0],zeroRise.points[2][0])
assert.ok(zeroRise.points[2][1]<zeroRise.points[1][1],'first real value rises vertically from zero')
assert.doesNotMatch(source,/selectedInferredZero|無更早記錄，按 \$0 顯示/)
const annotation=source.slice(source.indexOf('const annotate ='),source.indexOf('if (extrema.value)'))
assert.doesNotMatch(annotation,/\.(?:moveTo|lineTo|stroke)\(/,'annotations must not draw connector lines')
console.log('PASS: period-final extrema, label positions, no connector lines, carried opening, flat and missing history')
