import assert from 'node:assert/strict'
import { orderChartCandles, orderChartRange, orderMinuteTimestamp, orderChartPriceExtent } from '../src/utils/manualOrderChart.ts'
const minute = (timestamp, price = '100.1234567890123456') => ({ timestamp, price, low: '99', high: '102', close: '100', local: '1970-01-01T00:01', offset: 'Z' })
const rows = orderChartCandles([minute(180000), minute(60000), minute(120000), minute(240000), { ...minute(120001) }, { ...minute(0), high: '98' }, { ...minute(0), close: null }], 240000)
assert.deepEqual(rows.map(r => r.timestamp), [60000, 120000, 180000])
assert.equal(rows[0].price, '100.1234567890123456')
assert.equal(orderChartRange(rows, 2, 0).open.timestamp, 60000)
assert.equal(orderChartRange(rows, 2, 0).close.timestamp, 180000)
assert.equal(orderChartRange(rows, -5, 999).close.price, '100.1234567890123456')
assert.throws(() => orderChartRange(rows, 1, 1), /早于/)
assert.throws(() => orderChartRange(rows, NaN, 1))
assert.equal(orderMinuteTimestamp('2026-11-01T01:30', '-04:00') + 3600000, orderMinuteTimestamp('2026-11-01T01:30', '-05:00'))
assert.equal(orderMinuteTimestamp('', ''), undefined)
assert.equal(orderMinuteTimestamp('invalid', 'Z'), undefined)
console.log('order chart selection, precision, completed candles and DST checks passed')

// Exercise the real simple-form script: a chart selection pins time as well as price,
// and Axios resolves the route once (a duplicated /api prefix must not regress).
const { readFileSync } = await import('node:fs')
const { createRequire } = await import('node:module')
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript'), vue = require('vue'), axios = require('axios')
const source = readFileSync(new URL('../src/components/SimpleManualContractOrder.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
const { simpleConditions, simpleFields, simplePayload } = await import('../src/utils/simpleManualOrder.ts')
const { manualOrderEstimate } = await import('../src/utils/manualOrderEstimate.ts')
const gets = [], posts = []
const request = {
  get: async url => { gets.push(url); return { users: [], symbols: [{ symbol: 'FIXTURE', max_leverage: '100' }], timezone: 'UTC' } },
  post: async (url, payload) => {
    posts.push({ url, payload })
    if (url.endsWith('/bind/preview')) return { net: '1', walletBefore: '100', walletAfter: '100', previewToken: 'binding' }
    if (url.endsWith('/bind')) return { orderId: 7 }
    return { request: { leverage: '100', side: 'BUY' }, quotes: { openPrice: payload.openPrice, closePrice: payload.closePrice }, calculation: { quantity: '1', net: '1' }, previewToken: 'chart' }
  },
}
const scope = vue.effectScope()
const names = ['computed','nextTick','reactive','ref','watch','ElMessage','request','simpleConditions','simpleFields','simplePayload','manualOrderEstimate','defineEmits','defineExpose']
const component = scope.run(() => new Function(...names, js + ';return {open,form,selectChart,clearChart,edit,generate,result,canCreate,openBinding,binding,previewBinding,confirmBinding};')(...[vue.computed,vue.nextTick,vue.reactive,vue.ref,vue.watch,{success(){}},request,simpleConditions,simpleFields,simplePayload,manualOrderEstimate,()=>()=>{},()=>{}]))
try {
  await component.open(); component.form.symbol = 'FIXTURE'; await vue.nextTick()
  component.selectChart(orderChartRange(rows, 2, 0)); await vue.nextTick(); assert.equal(posts.length, 0)
  await component.generate()
  const selected = posts.at(-1).payload
  assert.equal(selected.openTime, rows[0].timestamp); assert.equal(selected.closeTime, rows[2].timestamp)
  assert.equal(selected.openPrice, rows[0].price); assert.equal(selected.closePrice, rows[2].price)
  assert.equal(component.canCreate.value, true)
  component.selectChart(orderChartRange(rows, 1, 2)); await vue.nextTick(); assert.equal(Boolean(component.canCreate.value), false)
  component.edit('openPrice', '100.125'); await vue.nextTick(); assert.equal(component.form.openTime, null); assert.equal(component.form.closeTime, null)
  await component.generate(); assert.equal(posts.at(-1).payload.openTime, null); assert.equal(posts.at(-1).payload.closeTime, null)
  await component.openBinding({ id: 7 }); component.binding.userId = 1; await component.previewBinding(); await component.confirmBinding()
  const client = axios.create({ baseURL: '/api' })
  for (const url of [...gets, ...posts.map(r => r.url)]) assert.ok(client.getUri({url}).startsWith('/api/admin/orders/contract/manual'), url)
  console.log('real simple form chart-time pinning, preview invalidation, binding and API-prefix checks passed')
} finally { scope.stop() }

// The shared chart prompts for a symbol first, then waits for parent symbol reset before loading.
const chartSource = readFileSync(new URL('../src/components/ManualOrderChart.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const chartJs = ts.transpileModule(chartSource, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
const chartProps = vue.reactive({ symbol: '', symbols: [{ symbol: 'FIXTURE', name: 'fixture' }], timezone: 'UTC', active: false, disabled: false })
const chartGets = [], chartEvents = [], chartScope = vue.effectScope()
let closeDuringChoice = false
const chartNames = ['computed','nextTick','onBeforeUnmount','ref','watch','echarts','request','orderChartCandles','orderChartRange','orderChartPriceExtent','defineProps','defineEmits']
const chart = chartScope.run(() => new Function(...chartNames, chartJs + ';return {toggle,selectSymbol,symbolPicker,symbolChoice,expanded,stop,dispose};')(...[vue.computed,vue.nextTick,()=>{},vue.ref,vue.watch,{}, { get: async (url, options) => { chartGets.push({url,params:options.params}); return {candles:[],hasMore:false,from:0} } },orderChartCandles,orderChartRange,orderChartPriceExtent,()=>chartProps,()=> (event, value) => { chartEvents.push({event,value}); chartProps.symbol=value; if (closeDuringChoice) chartProps.active=false }]))
try {
  chart.toggle(); assert.equal(chart.symbolPicker.value, false); assert.equal(chartGets.length, 0)
  chartProps.active=true; await vue.nextTick(); chartProps.disabled=true; chart.toggle(); assert.equal(chart.symbolPicker.value, false)
  chartProps.disabled=false; chart.toggle(); assert.equal(chart.symbolPicker.value, true); assert.equal(chart.expanded.value, false); assert.equal(chartGets.length, 0)
  chartProps.timezone='Asia/Tokyo'; await vue.nextTick(); assert.equal(chart.symbolPicker.value,true,'Late-loaded context must not dismiss a blank-symbol picker'); assert.equal(chartGets.length,0)
  chart.symbolChoice.value='UNKNOWN'; await chart.selectSymbol(); assert.equal(chartEvents.length, 0); assert.equal(chartGets.length, 0)
  chart.symbolChoice.value='FIXTURE'; await chart.selectSymbol()
  assert.deepEqual(chartEvents, [{event:'change-symbol',value:'FIXTURE'}]); assert.equal(chartProps.symbol,'FIXTURE')
  assert.equal(chart.symbolPicker.value,false); assert.equal(chart.expanded.value,true)
  assert.equal(chartGets.length,1); assert.equal(chartGets[0].params.symbol,'FIXTURE'); assert.equal(chartGets[0].params.limit,200)
  chart.toggle(); assert.equal(chart.expanded.value,false); chart.toggle(); assert.equal(chart.symbolPicker.value,false); assert.equal(chart.expanded.value,true)
  assert.equal(chartGets.length,2, 'An existing symbol opens the chart directly, without an extra symbol prompt')
  chartProps.symbol=''; await vue.nextTick(); chart.toggle(); chart.symbolChoice.value='FIXTURE'; closeDuringChoice=true; await chart.selectSymbol()
  assert.equal(chartGets.length,2, 'Closing the parent during selection must not open or fetch a stale chart'); assert.equal(chart.symbolPicker.value,false); assert.equal(chart.expanded.value,false)
  console.log('shared chart symbol-first picker, disabled/closed/invalid guards, parent reset ordering, bounded chart reads and direct existing-symbol opening checks passed')
} finally { chart.stop(); chart.dispose(); chartScope.stop() }

// Compact layout keeps all original inputs; business controls share the existing order capability.
const { parse: parseSfc } = require('@vue/compiler-sfc'), { parse: parseTemplate } = require('@vue/compiler-dom')
for (const file of ['SimpleManualContractOrder.vue', 'ManualOrderChart.vue']) {
  const { descriptor } = parseSfc(readFileSync(new URL('../src/components/' + file, import.meta.url), 'utf8'))
  let controls = 0
  function check(node) {
    if (node.type === 1 && ['el-button', 'el-switch'].includes(node.tag)) {
      controls++
      const permission = node.props.find(p => p.type === 7 && p.name === 'permission')
      assert.equal(permission?.exp?.content, "'orders:manual_order'", file + ': unguarded control')
    }
    for (const child of node.children || []) check(child)
  }
  check(parseTemplate(descriptor.template.content)); assert.ok(controls > 0)
}
console.log('simple order and shared chart business-control permission checks passed')

// A distant historical high must not flatten the current viewport's candlesticks.
const prices = [minute(60000), minute(120000), minute(180000)]
prices[0].high = '10000'; prices[0].low = '1'
assert.ok(orderChartPriceExtent(prices, 50, 100).max < 103)
assert.ok(orderChartPriceExtent(prices, 50, 100).min > 98)
assert.ok(orderChartPriceExtent(prices, 0, 100).max > 10000)
assert.deepEqual(orderChartPriceExtent(prices, 100, 50), orderChartPriceExtent(prices, 50, 100))
assert.equal(orderChartPriceExtent([]), undefined)
const flat = [{ ...minute(60000), low: '100', high: '100' }]
assert.ok(orderChartPriceExtent(flat).min < 100 && orderChartPriceExtent(flat).max > 100)
assert.equal(prices[0].high, '10000')
console.log('visible-window chart scale, flat candles and immutable historical prices checks passed')
