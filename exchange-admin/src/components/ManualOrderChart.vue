<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import request from '@/utils/request'
import { orderChartCandles, orderChartRange, orderChartPriceExtent, type OrderCandle, type OrderChartRange } from '@/utils/manualOrderChart'

const props = defineProps<{ symbol: string; timezone: string; active: boolean; disabled?: boolean; openTime?: number; closeTime?: number }>()
const emit = defineEmits<{ select: [range: OrderChartRange]; clear: [] }>()
const expanded = ref(false), loading = ref(false), message = ref(''), source = ref(''), host = ref<HTMLDivElement>()
const rows = ref<OrderCandle[]>([]), draft = ref<[number, number]>()
let chart: echarts.ECharts | undefined, resize: ResizeObserver | undefined, revision = 0
let timer: ReturnType<typeof setTimeout> | undefined, dragging: { pointer: number; index: number } | undefined
const selected = computed(() => {
  if (draft.value) { try { return orderChartRange(rows.value, ...draft.value) } catch { return null } }
  const open = rows.value.find(r => r.timestamp === props.openTime), close = rows.value.find(r => r.timestamp === props.closeTime)
  return open && close && open.timestamp < close.timestamp ? { open, close } : null
})
function stop() { revision++; clearTimeout(timer); loading.value = false; dragging = undefined }
function dispose() { resize?.disconnect(); resize = undefined; chart?.dispose(); chart = undefined }
function reset() { stop(); expanded.value = false; rows.value = []; draft.value = undefined; message.value = ''; dispose() }
function date(time: number, short = false) {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: props.timezone, ...(short ? {} : { month: '2-digit', day: '2-digit' } as const), hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(time))
}
function markers() {
  if (!chart) return
  const range = selected.value
  chart.setOption({ series: [{ id: 'minutes', markArea: { silent: true, itemStyle: { color: 'rgba(64,158,255,0.10)' }, data: range ? [[{ xAxis: String(range.open.timestamp) }, { xAxis: String(range.close.timestamp) }]] : [] }, markLine: { silent: true, symbol: 'none', label: { formatter: '{b}', position: 'insideEndTop', rotate: 0 }, data: range ? [{ name: '开仓', xAxis: String(range.open.timestamp), lineStyle: { color: '#19a47b' } }, { name: '平仓', xAxis: String(range.close.timestamp), lineStyle: { color: '#e69b38' } }] : [] } }] })
}
function rescale() {
  if (!chart) return
  const zoom = (chart.getOption()?.dataZoom as any[] | undefined)?.[0]
  chart.setOption({ yAxis: orderChartPriceExtent(rows.value, Number(zoom?.start ?? 0), Number(zoom?.end ?? 100)) })
}
async function render(view?: { start: number; end: number }) {
  await nextTick()
  if (!expanded.value || !props.active || !host.value || !rows.value.length) { dispose(); return }
  if (!chart) {
    chart = echarts.init(host.value)
    chart.on('datazoom', rescale)
    resize = new ResizeObserver(() => chart?.resize()); resize.observe(host.value)
  }
  const zoom = view || (chart.getOption()?.dataZoom as any[] | undefined)?.[0]
  // ponytail: at most 10,080 minute candles; keep a small visible window with native ECharts dataZoom.
  chart.setOption({ animation: false, grid: { left: 62, right: 16, top: 25, bottom: 70 },
    tooltip: { trigger: 'axis', renderMode: 'richText', confine: true, axisPointer: { type: 'cross' }, formatter: (items: any) => { const row = rows.value[items?.[0]?.dataIndex]; return row ? `${date(row.timestamp)} UTC${row.offset === 'Z' ? '+00:00' : row.offset}\n开 ${row.price}  收 ${row.close}\n低 ${row.low}  高 ${row.high}` : '' } },
    xAxis: { type: 'category', data: rows.value.map(r => String(r.timestamp)), boundaryGap: true, axisLabel: { formatter: (v: string) => date(Number(v), true), hideOverlap: true } },
    yAxis: { type: 'value', scale: true, splitNumber: 4, axisLabel: { width: 54, overflow: 'truncate' }, splitLine: { lineStyle: { color: '#edf0f5' } } },
    dataZoom: [{ type: 'slider', xAxisIndex: 0, filterMode: 'empty', bottom: 8, height: 20, showDetail: false, start: zoom?.start ?? Math.max(0, (1 - 120 / rows.value.length) * 100), end: zoom?.end ?? 100 }, { type: 'inside', xAxisIndex: 0, filterMode: 'empty', moveOnMouseMove: false, zoomOnMouseWheel: true, moveOnMouseWheel: false }],
    series: [{ id: 'minutes', type: 'candlestick', data: rows.value.map(r => [Number(r.price), Number(r.close), Number(r.low), Number(r.high)]), itemStyle: { color: '#19a47b', color0: '#e45e65', borderColor: '#19a47b', borderColor0: '#e45e65' } }],
  }, true)
  rescale(); markers()
}
async function load(id: number, attempt = 0) {
  loading.value = true
  try {
    const response: any = await request.get('/admin/orders/contract/manual/chart', { params: { symbol: props.symbol, timezone: props.timezone }, timeout: 30000 })
    if (id !== revision || !expanded.value || !props.active) return
    const next = orderChartCandles(response.candles), zoom = (chart?.getOption()?.dataZoom as any[] | undefined)?.[0]
    let view: { start: number; end: number } | undefined
    if (zoom && rows.value.length > 1 && next.length > 1) {
      const first = rows.value[Math.round(Number(zoom.start) / 100 * (rows.value.length - 1))]?.timestamp
      const last = rows.value[Math.round(Number(zoom.end) / 100 * (rows.value.length - 1))]?.timestamp
      const a = next.findIndex(r => r.timestamp >= (first ?? Infinity)), b = next.findIndex(r => r.timestamp >= (last ?? Infinity))
      if (a >= 0 && b > a) view = { start: a / (next.length - 1) * 100, end: b / (next.length - 1) * 100 }
    }
    // History may prepend older candles; preserve visible timestamps, never reuse a draft's old indices.
    dragging = undefined; draft.value = undefined; rows.value = next; source.value = response.source || '历史分钟行情'
    message.value = response.pending ? '历史行情正在加载，已返回的完整分钟可选…' : response.status === 'unavailable' ? '行情接口暂不可用，可重试；不是已确认休市。' : response.status === 'stale' ? '当前为历史缓存；最终生成会重新校验。' : rows.value.length < 2 ? '最近七天不足两根完整的已结束分钟K线，请重试或更换品种。' : ''
    await render(view)
    if (id !== revision) return
    if (response.pending && attempt < 19) timer = setTimeout(() => load(id, attempt + 1), 1500)
    else if (response.pending) message.value = '历史行情仍在加载，点击刷新行情重试；缺失分钟不可选。'
  } catch (e: any) { if (id === revision) message.value = e.message || '行情获取失败，请重试' }
  finally { if (id === revision) loading.value = false }
}
function reload() { stop(); draft.value = undefined; message.value = ''; void load(revision) }
function toggle() {
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
  if (props.disabled || event.button !== 0 || !chart || rows.value.length < 2 || !chart.containPixel({ gridIndex: 0 }, pixel(event))) return
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
  try { const range = orderChartRange(rows.value, ...draft.value); message.value = ''; emit('select', range); void nextTick(() => { draft.value = undefined; markers() }) }
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
  if (props.disabled || rows.value.length < 2) return
  if (event.key === 'Escape') { event.preventDefault(); cancel(); return }
  if (event.key === 'Enter') { event.preventDefault(); apply(); return }
  if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return
  event.preventDefault()
  const last = rows.value.length - 1, range = selected.value
  const a = range ? rows.value.indexOf(range.open) : Math.max(0, last - 1), b = range ? rows.value.indexOf(range.close) : last
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
    <div class="chart-toggle"><slot name="intro" /><el-button v-permission="'orders:manual_order'" :disabled="!symbol || !active || disabled" :aria-expanded="expanded" @click="toggle">{{ expanded ? '收起图表' : '图表选择开平仓' }}</el-button></div>
    <div v-if="expanded" class="chart-panel">
      <div class="chart-toolbar"><span>{{ symbol }} · 1分钟 · 最近七天 · {{ timezone }}</span><el-button v-permission="'orders:manual_order'" text :disabled="disabled" :loading="loading" @click="reload">刷新行情</el-button></div>
      <p class="chart-help">在K线区域按住鼠标拖选一段：较早分钟为开仓，较晚为平仓，均取该分钟开盘价。下方滑条浏览/缩放；滚轮缩放。</p>
      <div ref="host" class="candles" role="group" tabindex="0" aria-label="K线拖选区；左右键调整平仓，Shift加左右键调整开仓，自动应用" @pointerdown="start" @pointermove="move" @pointerup="finish" @pointercancel="cancel" @keydown="keyboard" />
      <p v-if="message" role="status" class="chart-message">{{ message }}</p>
      <div v-if="selected" class="selected-minutes" aria-live="polite"><span>开仓 {{ date(selected.open.timestamp) }} · {{ selected.open.price }}</span><span>平仓 {{ date(selected.close.timestamp) }} · {{ selected.close.price }}</span><el-button v-permission="'orders:manual_order'" text :disabled="disabled" @click="draft = undefined; emit('clear'); markers()">清除时间选择</el-button></div>
      <small>{{ source }} · 只选择时间与开盘价，不创建订单、不修改行情或资金。键盘：左右键调平仓，Shift+左右键调开仓，自动应用。</small>
    </div>
  </section>
</template>

<style scoped>
.order-chart { margin: 14px 0; min-width: 0; }
.chart-toggle { display: flex; align-items: center; justify-content: space-between; gap: 8px 12px; flex-wrap: wrap; }
.chart-panel { margin-top: 12px; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.chart-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; font-size: 13px; }
.chart-help, .chart-message, small { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.chart-help { margin: 4px 0 8px; }
.chart-message { color: var(--el-color-warning); margin: 8px 0; }
.candles { width: 100%; height: 290px; touch-action: none; cursor: crosshair; }
.candles:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.selected-minutes { display: flex; gap: 8px 16px; flex-wrap: wrap; align-items: center; font-size: 12px; }
.selected-minutes span:first-child { color: #168a69; }.selected-minutes span:nth-child(2) { color: #a36d23; }
small { display: block; overflow-wrap: anywhere; }
@media (max-width: 600px) { .chart-panel { padding: 8px; }.candles { height: 260px; } }
</style>
