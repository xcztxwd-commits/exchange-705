<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import request from '@/utils/request'
import { orderChartCandles, orderChartAggregate, orderChartIntervals, orderChartRange, orderChartPriceExtent, type OrderCandle, type OrderChartRange, type OrderChartInterval } from '@/utils/manualOrderChart'

const props = defineProps<{ symbol: string; symbols?: Array<{ symbol: string; name?: string }>; timezone: string; active: boolean; disabled?: boolean; openTime?: number; closeTime?: number }>()
const emit = defineEmits<{ select: [range: OrderChartRange]; clear: []; 'change-symbol': [symbol: string] }>()
const expanded = ref(false), loading = ref(false), message = ref(''), source = ref(''), host = ref<HTMLDivElement>()
const symbolPicker = ref(false), symbolChoice = ref('')
const canChooseSymbol = computed(() => props.symbols?.some(s => s.symbol === symbolChoice.value))
const rows = ref<OrderCandle[]>([]), draft = ref<[number, number]>()
const interval = ref<OrderChartInterval>('1m')
const candles = computed(() => orderChartAggregate(rows.value, interval.value))
const hasMore = ref(false), nextEndTime = ref<number>()
let chart: echarts.ECharts | undefined, resize: ResizeObserver | undefined, revision = 0
let timer: ReturnType<typeof setTimeout> | undefined, dragging: { pointer: number; index: number } | undefined
let abort: AbortController | undefined, historyComplete = false, failed = false
const selected = computed(() => {
  if (draft.value) { try { return orderChartRange(candles.value, ...draft.value) } catch { return null } }
  const open = rows.value.find(r => r.timestamp === props.openTime), close = rows.value.find(r => r.timestamp === props.closeTime)
  return open && close && open.timestamp < close.timestamp ? { open, close } : null
})
function stop() { revision++; clearTimeout(timer); abort?.abort(); abort = undefined; loading.value = false; dragging = undefined }
function dispose() { resize?.disconnect(); resize = undefined; chart?.dispose(); chart = undefined }
function reset() { stop(); if (!props.active || props.symbol) { symbolPicker.value = false; symbolChoice.value = '' }; expanded.value = false; rows.value = []; draft.value = undefined; hasMore.value = false; nextEndTime.value = undefined; historyComplete = false; failed = false; message.value = ''; dispose() }
function date(time: number, short = false) {
  if (short && interval.value === '1d') return new Intl.DateTimeFormat('zh-CN', { timeZone: props.timezone, month: '2-digit', day: '2-digit' }).format(new Date(time))
  return new Intl.DateTimeFormat('zh-CN', { timeZone: props.timezone, ...(short ? {} : { month: '2-digit', day: '2-digit' } as const), hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(time))
}
function candleIndex(time: number) {
  const duration = orderChartIntervals[interval.value]
  return candles.value.findIndex(r => Math.floor(r.timestamp / duration) === Math.floor(time / duration))
}
function markers() {
  if (!chart) return
  const range = selected.value
  const time = (row: OrderCandle) => String(candles.value[candleIndex(row.timestamp)]?.timestamp ?? row.timestamp)
  chart.setOption({ series: [{ id: 'minutes', markArea: { silent: true, itemStyle: { color: 'rgba(64,158,255,0.10)' }, data: range ? [[{ xAxis: time(range.open) }, { xAxis: time(range.close) }]] : [] }, markLine: { silent: true, symbol: 'none', label: { formatter: '{b}', position: 'insideEndTop', rotate: 0 }, data: range ? [{ name: '开仓', xAxis: time(range.open), lineStyle: { color: '#19a47b' } }, { name: '平仓', xAxis: time(range.close), lineStyle: { color: '#e69b38' } }] : [] } }] })
}
function rescale() {
  if (!chart) return
  const zoom = (chart.getOption()?.dataZoom as any[] | undefined)?.[0]
  chart.setOption({ yAxis: orderChartPriceExtent(candles.value, Number(zoom?.start ?? 0), Number(zoom?.end ?? 100)) })
}
async function render(view?: { start: number; end: number }) {
  await nextTick()
  if (!expanded.value || !props.active || !host.value || !rows.value.length) { dispose(); return }
  if (!chart) {
    chart = echarts.init(host.value)
    chart.on('datazoom', () => {
      rescale()
      const zoom = (chart?.getOption()?.dataZoom as any[] | undefined)?.[0]
      if (!failed && Number(zoom?.start) <= 5) loadMore()
    })
    resize = new ResizeObserver(() => chart?.resize()); resize.observe(host.value)
  }
  const zoom = view || (chart.getOption()?.dataZoom as any[] | undefined)?.[0]
  // ponytail: at most 43,200 minute candles; keep a small visible window with native ECharts dataZoom.
  chart.setOption({ animation: false, grid: { left: 62, right: 16, top: 25, bottom: 70 },
    tooltip: { trigger: 'axis', renderMode: 'richText', confine: true, axisPointer: { type: 'cross' }, formatter: (items: any) => { const row = candles.value[items?.[0]?.dataIndex]; return row ? `${interval.value} · ${date(row.timestamp)} UTC${row.offset === 'Z' ? '+00:00' : row.offset}\n开 ${row.price}  收 ${row.close}\n低 ${row.low}  高 ${row.high}` : '' } },
    xAxis: { type: 'category', data: candles.value.map(r => String(r.timestamp)), boundaryGap: true, axisLabel: { formatter: (v: string) => date(Math.floor(Number(v) / orderChartIntervals[interval.value]) * orderChartIntervals[interval.value], true), hideOverlap: true } },
    yAxis: { type: 'value', scale: true, splitNumber: 4, axisLabel: { width: 54, overflow: 'truncate' }, splitLine: { lineStyle: { color: '#edf0f5' } } },
    dataZoom: [{ type: 'slider', xAxisIndex: 0, filterMode: 'empty', bottom: 8, height: 20, showDetail: false, start: zoom?.start ?? Math.max(0, (1 - 120 / candles.value.length) * 100), end: zoom?.end ?? 100 }, { type: 'inside', xAxisIndex: 0, filterMode: 'empty', moveOnMouseMove: false, zoomOnMouseWheel: true, moveOnMouseWheel: false }],
    series: [{ id: 'minutes', type: 'candlestick', data: candles.value.map(r => [Number(r.price), Number(r.close), Number(r.low), Number(r.high)]), itemStyle: { color: '#19a47b', color0: '#e45e65', borderColor: '#19a47b', borderColor0: '#e45e65' } }],
  }, true)
  rescale(); markers()
}
async function load(id: number, endTime?: number, attempt = 0, fill = false) {
  if (id !== revision || !expanded.value || !props.active || props.disabled) return
  loading.value = true
  abort = new AbortController()
  try {
    const response: any = await request.get('/admin/orders/contract/manual/chart', { params: { symbol: props.symbol, timezone: props.timezone, limit: 200, ...(endTime === undefined ? {} : { endTime }) }, signal: abort.signal, timeout: 30000 })
    if (id !== revision || !expanded.value || !props.active) return
    const next = orderChartCandles([...rows.value, ...(Array.isArray(response.candles) ? response.candles : [])]).filter(row => !Number.isFinite(response.from) || row.timestamp >= response.from)
    const nextCandles = orderChartAggregate(next, interval.value), duration = orderChartIntervals[interval.value]
    const zoom = (chart?.getOption()?.dataZoom as any[] | undefined)?.[0]
    let view: { start: number; end: number } | undefined
    if (fill && interval.value !== '1m') view = { start: Math.max(0, (1 - 120 / nextCandles.length) * 100), end: 100 }
    else if (zoom && candles.value.length > 1 && nextCandles.length > 1) {
      const first = candles.value[Math.round(Number(zoom.start) / 100 * (candles.value.length - 1))]?.timestamp
      const last = candles.value[Math.round(Number(zoom.end) / 100 * (candles.value.length - 1))]?.timestamp
      const a = nextCandles.findIndex(r => Math.floor(r.timestamp / duration) >= Math.floor((first ?? Infinity) / duration)), b = nextCandles.findIndex(r => Math.floor(r.timestamp / duration) >= Math.floor((last ?? Infinity) / duration))
      if (a >= 0 && b > a) view = { start: a / (nextCandles.length - 1) * 100, end: b / (nextCandles.length - 1) * 100 }
    }
    // Append each bounded page immediately; prepending history must not move the visible timestamps or selection.
    dragging = undefined; draft.value = undefined; rows.value = next; source.value = response.source || '历史分钟行情'
    if (response.hasMore === false) historyComplete = true
    const cursor = Math.min(Number(response.nextEndTime), next[0] ? next[0].timestamp - 1 : Infinity)
    nextEndTime.value = Number.isSafeInteger(cursor) ? cursor : undefined
    hasMore.value = !historyComplete && response.hasMore === true && nextEndTime.value !== undefined && cursor >= response.from
    failed = false
    message.value = response.pending ? '缺失行情正在后台加载，已有完整分钟可直接选择；更早行情按需加载。' : response.status === 'unavailable' ? '行情接口暂不可用，可重试；不是已确认休市。' : response.status === 'stale' ? '当前为历史缓存；最终生成会重新校验。' : rows.value.length < 2 ? '这一段不足两根完整的已结束分钟K线，可加载更早行情或更换品种。' : ''
    await render(view)
    if (id !== revision) return
    // Retry only this page, with a stable cursor; never re-read all thirty days every 1.5 seconds.
    if (response.pending && attempt < 19) timer = setTimeout(() => load(id, response.to - 1, attempt + 1, fill), 1500)
    else if (response.pending) message.value = '历史行情仍在加载，可刷新重试或加载更早行情；缺失分钟不可选。'
    // Coarser periods need more minute pages, but keep every read bounded and render progress immediately.
    else if (fill && interval.value !== '1m' && response.status !== 'unavailable' && hasMore.value && candles.value.length < 120 && nextEndTime.value! < (endTime ?? Infinity)) timer = setTimeout(() => load(id, nextEndTime.value, 0, true), 0)
  } catch (e: any) { if (id === revision) { failed = true; message.value = e.message || '行情获取失败，请重试' } }
  finally { if (id === revision) { loading.value = false; abort = undefined } }
}
function reload() { stop(); failed = false; draft.value = undefined; message.value = rows.value.length ? '' : '正在读取已存分钟行情…'; void load(revision, undefined, 0, interval.value !== '1m') }
async function changeInterval(value: OrderChartInterval) {
  if (props.disabled || !props.active || !expanded.value || interval.value === value) return
  const interrupted = loading.value
  stop(); interval.value = value; draft.value = undefined
  const id = revision
  await render({ start: Math.max(0, (1 - 120 / candles.value.length) * 100), end: 100 })
  if (id === revision && (interrupted || (value !== '1m' && hasMore.value && candles.value.length < 120))) reload()
}
function loadMore() {
  if (!hasMore.value || loading.value || props.disabled || nextEndTime.value === undefined) return
  const cursor = nextEndTime.value
  stop(); failed = false; draft.value = undefined; void load(revision, cursor)
}
async function selectSymbol() {
  if (!canChooseSymbol.value || !props.active || props.disabled) return
  const symbol = symbolChoice.value
  emit('change-symbol', symbol)
  await nextTick()
  if (!props.active || props.disabled || props.symbol !== symbol) return
  symbolPicker.value = false; expanded.value = true; reload()
}
function toggle() {
  if (!props.active || props.disabled) return
  if (!props.symbol) { symbolChoice.value = ''; symbolPicker.value = true; return }
  expanded.value = !expanded.value
  if (expanded.value) reload()
  else { stop(); dispose() }
}
function pixel(event: PointerEvent) {
  const rect = host.value!.getBoundingClientRect()
  return [event.clientX - rect.left, event.clientY - rect.top] as [number, number]
}
function indexAt(event: PointerEvent) {
  const x = Math.max(62, Math.min(host.value!.clientWidth - 16, pixel(event)[0]))
  return chart!.convertFromPixel({ xAxisIndex: 0 }, x)
}
function start(event: PointerEvent) {
  if (props.disabled || event.button !== 0 || !chart || candles.value.length < 2 || !chart.containPixel({ gridIndex: 0 }, pixel(event))) return
  const index = indexAt(event)
  if (!Number.isFinite(index)) return
  event.preventDefault(); dragging = { pointer: event.pointerId, index }; draft.value = [index, index]
  host.value!.setPointerCapture(event.pointerId); host.value!.focus({ preventScroll: true }); markers()
}
function move(event: PointerEvent) {
  if (!dragging || event.pointerId !== dragging.pointer || !chart) return
  draft.value = [dragging.index, indexAt(event)]; markers()
}
function apply() {
  if (props.disabled || !draft.value) return
  try { const range = orderChartRange(candles.value, ...draft.value); message.value = ''; emit('select', range); void nextTick(() => { draft.value = undefined; markers() }) }
  catch (e: any) { message.value = e.message; draft.value = undefined; markers() }
}
function finish(event: PointerEvent) {
  if (!dragging || event.pointerId !== dragging.pointer) return
  move(event); dragging = undefined
  if (host.value?.hasPointerCapture(event.pointerId)) host.value.releasePointerCapture(event.pointerId)
  apply()
}
function cancel() { dragging = undefined; draft.value = undefined; markers() }
function keyboard(event: KeyboardEvent) {
  if (props.disabled || candles.value.length < 2) return
  if (event.key === 'Escape') { event.preventDefault(); cancel(); return }
  if (event.key === 'Enter') { event.preventDefault(); apply(); return }
  if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return
  event.preventDefault()
  const last = candles.value.length - 1, range = selected.value
  const a = range ? Math.max(0, Math.min(last - 1, candleIndex(range.open.timestamp))) : Math.max(0, last - 1), b = range ? Math.max(a + 1, candleIndex(range.close.timestamp)) : last
  const change = event.key === 'ArrowLeft' ? -1 : 1
  draft.value = event.shiftKey ? [Math.max(0, Math.min(b - 1, a + change)), b] : [a, Math.max(a + 1, Math.min(last, b + change))]
  markers(); apply()
}
watch(() => [props.openTime, props.closeTime], () => { draft.value = undefined; markers() })
watch(() => [props.symbol, props.timezone, props.active], reset)
onBeforeUnmount(() => { stop(); dispose() })
</script>

<template>
  <section class="order-chart" aria-label="图表选择开平仓">
    <div class="chart-toggle"><slot name="intro" /><el-button v-permission="'orders:manual_order'" :disabled="!active || disabled" :aria-expanded="expanded" @click="toggle">{{ expanded ? '收起图表' : '图表选择开平仓' }}</el-button></div>
    <div v-if="expanded" class="chart-panel">
      <div class="chart-toolbar"><span>{{ symbol }} · {{ interval }} · 最近30天 · {{ timezone }}</span><div class="chart-toolbar-actions"><div class="chart-periods" role="group" aria-label="时间周期"><el-button v-for="(_, period) in orderChartIntervals" :key="period" v-permission="'orders:manual_order'" size="small" :type="interval === period ? 'primary' : 'default'" :aria-pressed="interval === period" :disabled="disabled" @click="changeInterval(period)">{{ period }}</el-button></div><el-button v-permission="'orders:manual_order'" text :disabled="disabled" :loading="loading" @click="reload">刷新行情</el-button></div></div>
      <p class="chart-help">在K线区域按住鼠标拖选一段：较早K线为开仓，较晚为平仓，均取该K线首个有效分钟开盘价。下方滑条浏览/缩放；滚轮缩放。<template v-if="interval !== '1m'">周期按UTC对齐，仅聚合已加载分钟，未满周期或缺失分钟不补造；切换周期保留已选时间和价格。</template></p>
      <div class="chart-progress" aria-live="polite"><span>已显示 {{ candles.length }} 根{{ interval === '1m' ? '完整分钟K线' : `${interval} K线（${rows.length} 根完整分钟）` }} · {{ hasMore ? '向左浏览按需加载更早行情' : '最近30天范围' }}</span><el-button v-if="hasMore" v-permission="'orders:manual_order'" size="small" :disabled="disabled || loading" @click="loadMore">加载更早行情</el-button></div>
      <div ref="host" class="candles" role="group" tabindex="0" aria-label="K线拖选区；左右键调整平仓，Shift加左右键调整开仓，自动应用" @pointerdown="start" @pointermove="move" @pointerup="finish" @pointercancel="cancel" @keydown="keyboard" />
      <p v-if="message" role="status" class="chart-message">{{ message }}</p>
      <div v-if="selected" class="selected-minutes" aria-live="polite"><span>开仓 {{ date(selected.open.timestamp) }} · {{ selected.open.price }}</span><span>平仓 {{ date(selected.close.timestamp) }} · {{ selected.close.price }}</span><el-button v-permission="'orders:manual_order'" text :disabled="disabled" @click="draft = undefined; emit('clear'); markers()">清除时间选择</el-button></div>
      <small>{{ source }} · 只选择时间与开盘价，不创建订单、不修改行情或资金。键盘：左右键调平仓，Shift+左右键调开仓，自动应用。</small>
    </div>
    <el-dialog v-model="symbolPicker" title="选择图表品种" width="min(460px, calc(100vw - 24px))" append-to-body :close-on-click-modal="false" class="order-chart-symbol-dialog">
      <p class="chart-help">请选择品种，确认后自动打开图表。支持搜索代码或名称。</p>
      <el-select v-model="symbolChoice" filterable clearable placeholder="搜索品种代码或名称" aria-label="图表品种" style="width:100%"><el-option v-for="s in symbols" :key="s.symbol" :value="s.symbol" :label="s.name ? `${s.symbol} · ${s.name}` : s.symbol" /></el-select>
      <p v-if="!symbols?.length" class="chart-message" role="status">暂无可用品种，请稍后重试。</p>
      <template #footer><el-button v-permission="'orders:manual_order'" @click="symbolPicker = false">取消</el-button><el-button v-permission="'orders:manual_order'" type="primary" :disabled="!canChooseSymbol || !active || disabled" @click="selectSymbol">打开图表</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.order-chart { margin: 14px 0; min-width: 0; }
.chart-toggle { display: flex; align-items: center; justify-content: space-between; gap: 8px 12px; flex-wrap: wrap; }
.chart-panel { margin-top: 12px; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.chart-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.chart-toolbar-actions, .chart-periods { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.chart-periods .el-button + .el-button { margin-left: 0; }
.chart-help, .chart-message, small { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.chart-help { margin: 4px 0 8px; }
.chart-progress { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; font-size: 12px; color: var(--el-text-color-secondary); }
.chart-message { color: var(--el-color-warning); margin: 8px 0; }
.candles { width: 100%; height: 290px; touch-action: none; cursor: crosshair; }
.candles:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.selected-minutes { display: flex; gap: 8px 16px; flex-wrap: wrap; align-items: center; font-size: 12px; }
.selected-minutes span:first-child { color: #168a69; }.selected-minutes span:nth-child(2) { color: #a36d23; }
small { display: block; overflow-wrap: anywhere; }
@media (max-width: 600px) { .chart-panel { padding: 8px; }.candles { height: 260px; } }
</style>
