<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { dispose, init, type CandleType, type Chart, type Coordinate, type DataLoaderGetBarsParams, type KLineData, type OverlayCreate } from 'klinecharts'
import { useMarketStore } from '@/store/market'
import marketWebSocket from '@/utils/marketWebSocket'
import { useLocaleStore } from '@/store/locale'
import request from '@/utils/request'
import { candleFromQuote, chartPeriod, normalizeCandles } from '@/utils/chartData'
import { registerTradingDrawingOverlays } from '@/utils/chartOverlays'

const props = defineProps<{ symbol: string; category?: string; interval?: string; height?: number }>()
const emit = defineEmits<{ (e: 'ready'): void }>()
const market = useMarketStore()
const locale = useLocaleStore()
const container = ref<HTMLDivElement>()
const chinese = computed(() => locale.getCurrentLocale().startsWith('zh'))
const text = (zh: string, en: string) => chinese.value ? zh : en
const interval = computed(() => props.interval || '1m')
const dark = ref(false)
const fullscreen = ref(false)
const loading = ref(false)
const historyLoading = ref(false)
const error = ref(false)
const empty = ref(false)
const exhausted = ref(false)
const dataWarning = ref(false)
const syncError = ref(false)
const staleCandles = ref(false)
const saved = ref(true)
const timezone = ref('Europe/London')
const count = ref(0)
const drawingCount = ref(0)
const activeTool = ref('')
const selected = ref('')
const hidden = ref(false)
const locked = ref(false)
const magnet = ref(false)
const color = ref('#8cc63f')
const mainIndicator = ref('MA')
const subIndicator = ref('VOL')
const candleType = ref<CandleType>('candle_solid')
let chart: Chart | null = null
let controller = new AbortController()
let revision = 0
let drawingKey = ''
let pendingDrawing: string | null = null
let retry: (() => void) | null = null
let realtime: ((bar: KLineData) => void) | null = null
let resizeObserver: ResizeObserver | undefined
let themeObserver: MutationObserver | undefined
let lastQuoteTime = 0
let replaceBars: DataLoaderGetBarsParams['callback'] | null = null
let syncing = false
let lastSyncAttempt = 0
let syncTimer: ReturnType<typeof setInterval> | undefined
let stopConnected: (() => void) | undefined
const groupId = 'trading-drawings'

const tools = [
  { name: 'segment', zh: '趨勢線', en: 'Trend line', path: 'M4 19 20 5M3 18h3v3H3zM18 3h3v3h-3z' },
  { name: 'horizontalStraightLine', zh: '水平線', en: 'Horizontal line', path: 'M3 12h18M10 10h4v4h-4z' },
  { name: 'rayLine', zh: '射線', en: 'Ray', path: 'm4 19 16-14M3 18h3v3H3z' },
  { name: 'priceChannelLine', zh: '價格通道', en: 'Price channel', path: 'm3 16 15-12M6 21 15-12M4 19 20 6' },
  { name: 'fibonacciLine', zh: '斐波那契回撤', en: 'Fibonacci retracement', path: 'M3 4h18M3 10h18M3 14h18M3 20h18m-16 0L19 4' },
  { name: 'tradingRectangle', zh: '矩形', en: 'Rectangle', path: 'M4 5h16v14H4z' },
  { name: 'tradingArrowLine', zh: '箭頭', en: 'Arrow', path: 'm4 20 16-16M11 4h9v9' },
  { name: 'brush', zh: '畫筆', en: 'Brush', path: 'M3 18C5 3 9 3 10 12s5 9 11-7' },
]

function applyTheme() {
  dark.value = document.documentElement.classList.contains('dark')
  if (!chart) return
  chart.setStyles(dark.value ? 'dark' : 'light')
  const grid = dark.value ? '#242c39' : '#edf0f3'
  const ink = dark.value ? '#8994a5' : '#7b8492'
  const compact = (container.value?.clientWidth || 0) < 500
  chart.setStyles({
    grid: { horizontal: { color: grid, style: 'solid' }, vertical: { color: grid, style: 'solid' } },
    candle: {
      type: candleType.value,
      bar: { upColor: '#26a69a', downColor: '#ef5350', upBorderColor: '#26a69a', downBorderColor: '#ef5350', upWickColor: '#26a69a', downWickColor: '#ef5350' },
      area: { lineColor: '#8cc63f', backgroundColor: [{ offset: 0, color: 'rgba(140,198,63,.24)' }, { offset: 1, color: 'rgba(140,198,63,0)' }] },
      tooltip: { showRule: compact ? 'follow_cross' : 'always', showType: compact ? 'rect' : 'standard', title: { show: false }, legend: { size: 11 } },
      priceMark: { last: { upColor: '#26a69a', downColor: '#ef5350' } },
    },
    xAxis: { axisLine: { color: grid }, tickText: { color: ink, size: 11 }, tickLine: { show: false } },
    yAxis: { axisLine: { show: false }, tickText: { color: ink, size: 11 }, tickLine: { show: false } },
    separator: { color: grid, activeBackgroundColor: 'rgba(140,198,63,.12)' },
    indicator: { tooltip: { showRule: compact ? 'follow_cross' : 'always', legend: { size: 10 }, title: { size: 10 } } },
  })
  chart.setLocale(chinese.value ? 'zh-CN' : 'en-US')
}

function applyIndicators() {
  if (!chart) return
  chart.removeIndicator()
  if (mainIndicator.value) chart.createIndicator({ name: mainIndicator.value, ...(mainIndicator.value === 'MA' ? { calcParams: [5, 10, 30] } : {}) }, { pane: { id: 'candle_pane' } })
  if (subIndicator.value) chart.createIndicator(subIndicator.value, { pane: { id: 'study_pane', height: container.value && container.value.clientHeight < 360 ? 65 : 95, minHeight: 55 } })
}

function persistDrawings() {
  if (!chart || !drawingKey) return
  const drawings = chart.getOverlays({ groupId }).filter(o => o.currentStep === -1)
  // Persist stable time/price coordinates, never chart-relative indexes.
  const records = drawings.map(o => ({ name: o.name, points: o.points.map(p => ({ timestamp: p.timestamp, value: p.value })), styles: o.styles, lock: o.lock }))
  drawingCount.value = records.length
  try { localStorage.setItem(drawingKey, JSON.stringify(records)); saved.value = true } catch { saved.value = false }
}

function overlayOptions(value: OverlayCreate): OverlayCreate {
  return {
    ...value, groupId, visible: !hidden.value,
    onDrawEnd: () => { pendingDrawing = null; activeTool.value = ''; persistDrawings() },
    onPressedMoveEnd: persistDrawings,
    onSelected: ({ overlay }) => { selected.value = overlay.id },
    onDeselected: () => { selected.value = '' },
    onRemoved: () => { selected.value = ''; queueMicrotask(persistDrawings) },
  }
}

function cancelDrawing() {
  if (pendingDrawing) chart?.removeOverlay({ id: pendingDrawing })
  pendingDrawing = null
  activeTool.value = ''
}

function chooseTool(name: string) {
  cancelDrawing()
  if (!name || !chart || !count.value) return
  activeTool.value = name
  hidden.value = false
  chart.overrideOverlay({ groupId, visible: true })
  const id = chart.createOverlay(overlayOptions({ name, mode: magnet.value ? 'weak_magnet' : 'normal', styles: { line: { color: color.value, size: 1.5 }, polygon: { color: color.value + '22', borderColor: color.value }, rect: { color: color.value + '18', borderColor: color.value } } }))
  pendingDrawing = typeof id === 'string' ? id : null
}

function restoreDrawings() {
  if (!chart) return
  cancelDrawing()
  selected.value = ''
  chart.removeOverlay({ groupId })
  drawingKey = 'exchange:chart-drawings:v1:' + props.symbol
  drawingCount.value = 0
  try {
    const records: unknown = JSON.parse(localStorage.getItem(drawingKey) || '[]')
    if (Array.isArray(records)) {
      const names = new Set(tools.map(t => t.name))
      const valid = records.filter(o => o && names.has(o.name) && Array.isArray(o.points) && o.points.length && o.points.every((p: { timestamp?: number; value?: number }) => Number.isFinite(p.timestamp) && Number.isFinite(p.value)))
      chart.createOverlay(valid.map(o => overlayOptions({ name: o.name, points: o.points, styles: o.styles, lock: o.lock === true })))
      drawingCount.value = valid.length
      locked.value = valid.length > 0 && valid.every(o => o.lock === true)
    }
  } catch { saved.value = false }
}

function removeDrawing(all = false) {
  cancelDrawing()
  if (all) chart?.removeOverlay({ groupId })
  else {
    const overlays = chart?.getOverlays({ groupId }) || []
    const id = selected.value || overlays[overlays.length - 1]?.id
    if (id) chart?.removeOverlay({ id })
  }
  selected.value = ''
  persistDrawings()
}
function toggleHidden() { hidden.value = !hidden.value; chart?.overrideOverlay({ groupId, visible: !hidden.value }) }
function scrollLatest() { chart?.scrollToRealTime(150) }
function retryLoad() { retry?.() }
function toggleLock() { locked.value = !locked.value; chart?.overrideOverlay({ groupId, lock: locked.value }); persistDrawings() }
function changeColor() {
  if (selected.value) { chart?.overrideOverlay({ id: selected.value, styles: { line: { color: color.value }, rect: { borderColor: color.value, color: color.value + '18' }, polygon: { borderColor: color.value, color: color.value + '22' } } }); persistDrawings() }
}
function keydown(event: KeyboardEvent) {
  if ((event.target as HTMLElement).matches('input, select, textarea')) return
  if (event.key === 'Escape') { cancelDrawing(); fullscreen.value = false }
  if (event.key === 'Delete' && selected.value) { event.preventDefault(); removeDrawing() }
}

async function fetchBars(before: number, signal: AbortSignal) {
  const history = Number.isFinite(before)
  const url = '/market/kline/' + (history ? 'history/' : '') + encodeURIComponent(props.symbol)
  const query = { interval: interval.value, limit: 200, ...(history ? { endTime: before - 1 } : { category: props.category || 'Crypto' }) }
  let response: any
  for (let attempt = 0; attempt < 8; attempt++) {
    try {
      response = await request.get(url, { params: query, signal })
    } catch (failure) {
      if (history || signal.aborted) throw failure
      response = await request.get('/market/redis/kline/' + encodeURIComponent(props.symbol), { params: query, signal })
    }
    if (signal.aborted) throw new Error('Aborted')
    if (!response?.data?.pending) break
    await new Promise<void>((resolve, reject) => {
      const abort = () => { clearTimeout(timer); reject(new Error('Aborted')) }
      const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve() }, 1000)
      signal.addEventListener('abort', abort, { once: true })
    })
  }
  if (response?.data?.status === 'unavailable' || response?.ret !== 200) throw new Error('Chart data unavailable')
  const rows = response.data?.kline_list ?? response.data
  if (!Array.isArray(rows)) throw new Error('Invalid candle response')
  const validated = normalizeCandles(rows)
  if (rows.length && !validated.length) throw new Error('Invalid candle data')
  const aligned = normalizeCandles(rows, Infinity, interval.value)
  const candles = aligned.filter(bar => bar.timestamp < before)
  return { candles, damaged: aligned.length < validated.length || rows.some(row => normalizeCandles([row]).length === 0),
    stale: response.data?.status === 'stale' || !!response.data?.pending }
}

function cacheBars() {
  const bars = chart?.getDataList() || []
  count.value = bars.length
  market.klineDataMap[props.symbol + '_' + interval.value] = bars.slice(-200).map(bar => ({ ...bar, symbol: props.symbol, interval: interval.value, volume: bar.volume || 0 }))
}

async function loadBars(params: DataLoaderGetBarsParams, version: number, signal: AbortSignal) {
  if (!chart || version !== revision || signal.aborted) return
  if (params.type === 'backward') { params.callback([], { backward: false }); return }
  const history = params.type === 'forward'
  if (history && !['1m', '5m', '15m', '30m', '1h', '1d'].includes(interval.value)) {
    params.callback([], { forward: false, backward: false }); exhausted.value = true; return
  }
  loading.value = !history
  historyLoading.value = history
  error.value = false
  const before = history && params.timestamp !== null ? params.timestamp : Infinity
  try {
    const result = await fetchBars(before, signal)
    const { candles } = result
    if (version !== revision || signal.aborted) return
    dataWarning.value ||= result.damaged
    staleCandles.value ||= result.stale
    if (!history) replaceBars = params.callback
    params.callback(candles, { forward: candles.length > 0, backward: false })
    cacheBars()
    exhausted.value = history && candles.length === 0
    if (!history) {
      empty.value = candles.length === 0; restoreDrawings(); emit('ready')
    }
    retry = null
  } catch {
    if (signal.aborted || version !== revision) return
    // Retry resumes the failed page without resetting the user's viewport.
    error.value = true
    retry = () => { void loadBars(params, version, signal) }
  } finally {
    if (version === revision) { loading.value = false; historyLoading.value = false; replayQuote() }
  }
}

async function syncLatest() {
  if (!chart || !replaceBars || loading.value || historyLoading.value || syncing) return
  const version = revision, signal = controller.signal
  syncing = true
  lastSyncAttempt = Date.now()
  try {
    const result = await fetchBars(Infinity, signal)
    if (signal.aborted || version !== revision || !chart) return
    const existing = chart.getDataList()
    const previousLast = existing[existing.length - 1]?.timestamp || 0
    let candles = result.candles
    while (previousLast && candles[0] && candles[0].timestamp > previousLast) {
      const older = await fetchBars(candles[0].timestamp, signal)
      if (signal.aborted || version !== revision) return
      result.damaged ||= older.damaged
      if (!older.candles.length) throw new Error('Candle gap unavailable')
      candles = [...older.candles, ...candles]
    }
    if (signal.aborted || version !== revision || !chart || historyLoading.value) return
    if (!candles.length) throw new Error('No latest candles')
    const current = chart.getDataList()
    const range = chart.getVisibleRange()
    const anchor = current[range.from]?.timestamp
    const x = anchor ? (chart.convertToPixel({ timestamp: anchor }) as Partial<Coordinate>).x : undefined
    const atLatest = range.to >= current.length
    const merged = normalizeCandles([...current, ...candles])
    replaceBars?.(merged, { forward: !exhausted.value, backward: false })
    if (!atLatest && anchor && x !== undefined) {
      const nextX = (chart.convertToPixel({ timestamp: anchor }) as Partial<Coordinate>).x
      if (nextX !== undefined) chart.scrollByDistance(x - nextX, 0)
    }
    dataWarning.value ||= result.damaged
    staleCandles.value = result.stale
    syncError.value = false
    cacheBars()
    replayQuote()
  } catch {
    if (!signal.aborted && version === revision) syncError.value = true
  } finally { if (version === revision) syncing = false }
}

function resetMarket() {
  if (!chart || !props.symbol) return
  controller.abort()
  controller = new AbortController()
  const version = ++revision
  realtime = null
  cancelDrawing()
  chart.removeOverlay({ groupId })
  drawingKey = ''
  drawingCount.value = 0
  selected.value = ''
  count.value = 0
  lastQuoteTime = 0
  replaceBars = null
  syncing = false
  lastSyncAttempt = 0
  dataWarning.value = false
  syncError.value = false
  staleCandles.value = false
  error.value = false
  empty.value = false
  exhausted.value = false
  locked.value = false
  retry = null
  // Setters reload automatically; detach the previous loader before switching.
  chart.setDataLoader({ getBars: () => {} })
  const instruments = 'symbols' in market && Array.isArray(market.symbols) ? market.symbols : []
  const instrument = instruments.find(item => item.symbol === props.symbol)
  const precision = instrument?.pricePrecision
  chart.setSymbol({ ticker: props.symbol, pricePrecision: Number.isInteger(precision) && precision >= 0 && precision <= 12 ? precision : /JPY/.test(props.symbol) ? 3 : /^(BTC|ETH|XAU|XAG)/.test(props.symbol) ? 2 : 5, volumePrecision: 2 })
  chart.setPeriod(chartPeriod(interval.value))
  const signal = controller.signal
  chart.setDataLoader({
    getBars: params => loadBars(params, version, signal),
    subscribeBar: ({ callback }) => { if (version === revision) realtime = callback },
    unsubscribeBar: () => { realtime = null },
  })
}

watch(() => [props.symbol, props.category, interval.value], resetMarket)
function replayQuote() {
  if (!chart || !realtime || !count.value || market.getQuoteStatus(props.symbol) !== 'available') return
  const time = market.quoteStatusMap[props.symbol]?.timestamp || 0
  const milliseconds = time < 10_000_000_000 ? time * 1000 : time
  if (milliseconds < lastQuoteTime || milliseconds > Date.now() + 5000) return
  const bars = chart.getDataList()
  const bar = candleFromQuote(bars[bars.length - 1], market.getPrice(props.symbol), milliseconds, interval.value)
  if (!bar && milliseconds > (bars[bars.length - 1]?.timestamp || Infinity)) {
    syncError.value = true
    if (Date.now() - lastSyncAttempt >= 15_000) void syncLatest()
  }
  if (bar) {
    lastQuoteTime = milliseconds; realtime(bar); count.value = chart.getDataList().length
    cacheBars()
  }
}
watch(() => market.quoteStatusMap[props.symbol], replayQuote)
watch([mainIndicator, subIndicator], applyIndicators)
watch([candleType, chinese], applyTheme)
watch(magnet, () => chart?.overrideOverlay({ groupId, mode: magnet.value ? 'weak_magnet' : 'normal' }))
watch(fullscreen, async () => { await nextTick(); chart?.resize() })

onMounted(() => {
  if (!container.value) return
  registerTradingDrawingOverlays()
  chart = init(container.value)
  if (!chart) return
  chart.setTimezone(timezone.value)
  chart.setBarSpace(container.value.clientWidth < 500 ? 5 : 7)
  chart.setOffsetRightDistance(55)
  applyTheme()
  applyIndicators()
  resetMarket()
  stopConnected = marketWebSocket.onConnected(() => { void syncLatest() })
  syncTimer = setInterval(() => { void syncLatest() }, 15_000)
  resizeObserver = new ResizeObserver(() => { chart?.resize(); applyTheme() })
  resizeObserver.observe(container.value)
  themeObserver = new MutationObserver(applyTheme)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  void request.get('/user/system/timezone').then((response: any) => {
    if (chart && response?.timezone) {
      try { new Intl.DateTimeFormat('en', { timeZone: response.timezone }); chart.setTimezone(response.timezone); timezone.value = response.timezone } catch { /* Keep the existing system fallback. */ }
    }
  }).catch(() => {})
})
onUnmounted(() => {
  persistDrawings()
  revision++
  controller.abort()
  clearInterval(syncTimer)
  stopConnected?.()
  resizeObserver?.disconnect()
  themeObserver?.disconnect()
  if (container.value) dispose(container.value)
  chart = null
})
</script>

<template>
  <div class="chart-workspace" :class="{ 'chart-dark': dark, 'chart-fullscreen': fullscreen }" :style="height && !fullscreen ? { height: height + 'px' } : undefined" tabindex="0" :aria-label="text('K 線圖表', 'Candlestick chart')" @keydown="keydown">
    <div class="chart-toolbar">
      <label class="chart-select"><span class="sr-only">{{ text('圖表類型', 'Chart type') }}</span><select v-model="candleType" :aria-label="text('圖表類型', 'Chart type')"><option value="candle_solid">{{ text('蠟燭圖', 'Candles') }}</option><option value="candle_stroke">{{ text('空心蠟燭', 'Hollow') }}</option><option value="ohlc">OHLC</option><option value="area">{{ text('面積圖', 'Area') }}</option></select></label>
      <span class="toolbar-divider"></span>
      <label class="chart-select"><span class="sr-only">{{ text('主圖指標', 'Main indicator') }}</span><select v-model="mainIndicator" :aria-label="text('主圖指標', 'Main indicator')"><option value="">{{ text('無主圖指標', 'No main indicator') }}</option><option>MA</option><option>EMA</option><option>BOLL</option></select></label>
      <label class="chart-select"><span class="sr-only">{{ text('副圖指標', 'Secondary indicator') }}</span><select v-model="subIndicator" :aria-label="text('副圖指標', 'Secondary indicator')"><option value="">{{ text('無副圖指標', 'No sub indicator') }}</option><option>VOL</option><option>MACD</option><option>RSI</option><option>KDJ</option></select></label>
      <div class="toolbar-spacer"></div>
      <button @click="scrollLatest">{{ text('回到最新', 'Latest') }}</button>
      <button :title="text('全螢幕', 'Fullscreen')" :aria-label="text('全螢幕', 'Fullscreen')" :aria-pressed="fullscreen" @click="fullscreen = !fullscreen"><svg viewBox="0 0 24 24"><path d="M9 4H4v5m11-5h5v5M4 15v5h5m11-5v5h-5" /></svg></button>
    </div>
    <div class="chart-body">
      <div class="drawing-rail" role="toolbar" :aria-label="text('繪圖工具', 'Drawing tools')">
        <button :class="{ active: !activeTool }" :title="text('游標 / 取消繪圖 (Esc)', 'Cursor / Cancel drawing (Esc)')" :aria-label="text('游標', 'Cursor')" @click="cancelDrawing"><svg viewBox="0 0 24 24"><path d="m5 3 14 10-7 1-3 7z" /></svg></button>
        <button v-for="tool in tools" :key="tool.name" :disabled="!count" :class="{ active: activeTool === tool.name }" :title="chinese ? tool.zh : tool.en" :aria-label="chinese ? tool.zh : tool.en" :aria-pressed="activeTool === tool.name" @click="chooseTool(tool.name)"><svg viewBox="0 0 24 24"><path :d="tool.path" /></svg></button>
        <span class="rail-divider"></span>
        <label class="color-control" :title="text('線條顏色', 'Drawing color')"><input v-model="color" type="color" :aria-label="text('線條顏色', 'Drawing color')" @input="changeColor"></label>
        <button :class="{ active: magnet }" :aria-pressed="magnet" :title="text('磁吸', 'Magnet')" :aria-label="text('磁吸', 'Magnet')" @click="magnet = !magnet"><svg viewBox="0 0 24 24"><path d="M5 4v9a7 7 0 0 0 14 0V4h-4v9a3 3 0 0 1-6 0V4zM5 8h4m6 0h4" /></svg></button>
        <button :class="{ active: locked }" :aria-pressed="locked" :title="text('鎖定繪圖', 'Lock drawings')" :aria-label="text('鎖定繪圖', 'Lock drawings')" @click="toggleLock"><svg viewBox="0 0 24 24"><path d="M5 10h14v11H5zM8 10V7a4 4 0 0 1 8 0v3m-4 5v2" /></svg></button>
        <button :class="{ active: hidden }" :aria-pressed="hidden" :title="text('隱藏繪圖', 'Hide drawings')" :aria-label="text('隱藏繪圖', 'Hide drawings')" @click="toggleHidden"><svg viewBox="0 0 24 24"><path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12m7 0a3 3 0 1 0 6 0 3 3 0 1 0-6 0" /><path v-if="hidden" d="m3 3 18 18" /></svg></button>
        <button :disabled="!drawingCount" :title="text('刪除選中或最後一筆繪圖', 'Delete selected or last drawing')" :aria-label="text('刪除繪圖', 'Delete drawing')" @click="removeDrawing()"><svg viewBox="0 0 24 24"><path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7" /></svg></button>
      </div>
      <div class="chart-stage">
        <div ref="container" class="kline-chart"></div>
        <div v-if="loading || empty || (error && !count)" class="chart-message" role="status">
          <span v-if="loading" class="loading-dot"></span>
          <span>{{ loading ? text('正在載入行情…', 'Loading candles…') : error ? text('行情載入失敗', 'Could not load candles') : text('暫無 K 線數據', 'No candle data') }}</span>
          <button v-if="error" @click="retryLoad">{{ text('重試', 'Retry') }}</button>
        </div>
        <div v-if="activeTool" class="drawing-hint">{{ activeTool === 'brush' ? text('按住拖動繪製 · Esc 取消', 'Drag to draw · Esc to cancel') : text('點擊圖表設定錨點 · Esc 取消', 'Click to place points · Esc to cancel') }}</div>
      </div>
    </div>
    <div class="chart-footer" role="status" aria-live="polite">
      <span v-if="historyLoading">{{ text('正在載入更早行情…', 'Loading older candles…') }}</span>
      <button v-else-if="error && count" class="retry-history" @click="retryLoad">{{ text('歷史載入失敗 · 點擊重試', 'History failed · Retry') }}</button>
      <button v-else-if="syncError" class="retry-history" @click="syncLatest">{{ text('最新 K 線未完整同步 · 重試', 'Latest candles incomplete · Retry') }}</button>
      <span v-else-if="dataWarning">{{ text('非週期或異常數據已略過；K 線有缺損', 'Irregular source candles omitted; data incomplete') }}</span>
      <span v-else-if="staleCandles">{{ text('顯示快取 K 線，等待更新', 'Cached candles; awaiting refresh') }}</span>
      <span v-else-if="!saved">{{ text('繪圖無法儲存至此瀏覽器', 'Drawing storage unavailable') }}</span>
      <span v-else>{{ exhausted ? text('已到最早可用行情', 'Earliest available candles') : text('向右拖動查看更早行情', 'Drag right for older candles') }}</span>
      <span class="footer-meta">{{ count }} {{ text('根', 'bars') }} · {{ timezone }}</span>
      <button v-if="drawingCount" :title="text('清除全部繪圖', 'Clear all drawings')" @click="removeDrawing(true)">{{ text('清除', 'Clear') }} ({{ drawingCount }})</button>
    </div>
  </div>
</template>

<style scoped>
.chart-workspace { --chart-bg: #fff; --chart-border: #e9edf1; --chart-ink: #657084; --chart-hover: #f4f7f0; --chart-accent: #78ad30; width: 100%; height: 100%; min-height: 240px; display: flex; flex-direction: column; color: var(--chart-ink); background: var(--chart-bg); font: 11px/1.4 -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; overflow: hidden; outline: none; }
.chart-dark { --chart-bg: #131722; --chart-border: #2b3139; --chart-ink: #a0aabb; --chart-hover: #232d25; --chart-accent: #a0d157; }
.chart-toolbar { display: flex; align-items: center; gap: 5px; min-height: 37px; padding: 0 8px; border-bottom: 1px solid var(--chart-border); flex-shrink: 0; overflow-x: auto; }
button, select { color: inherit; font: inherit; }
button { display: inline-flex; justify-content: center; align-items: center; flex-shrink: 0; background: transparent; border: 0; border-radius: 4px; min-height: 28px; padding: 4px 6px; cursor: pointer; white-space: nowrap; }
button:hover, button.active { background: var(--chart-hover); color: var(--chart-accent); }
button:disabled { opacity: .35; cursor: default; }
button:focus-visible, select:focus-visible, input:focus-visible { outline: 2px solid var(--chart-accent); outline-offset: -2px; }
svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.4; stroke-linecap: round; stroke-linejoin: round; }
.chart-select select { background: var(--chart-bg); border: 0; border-radius: 4px; height: 28px; max-width: 120px; padding: 0 3px; cursor: pointer; }
.toolbar-divider { width: 1px; height: 15px; background: var(--chart-border); flex-shrink: 0; }
.toolbar-spacer { flex: 1; }
.chart-body { flex: 1; min-height: 0; display: flex; }
.drawing-rail { width: 39px; flex-shrink: 0; padding: 5px 3px; display: flex; flex-direction: column; gap: 3px; align-items: center; border-right: 1px solid var(--chart-border); overflow-y: auto; scrollbar-width: thin; scrollbar-color: var(--chart-border) transparent; }
.drawing-rail button { width: 31px; min-height: 30px; }
.rail-divider { height: 1px; width: 22px; background: var(--chart-border); margin: 3px 0; flex-shrink: 0; }
.color-control { width: 30px; height: 28px; display: flex; justify-content: center; align-items: center; flex-shrink: 0; }
.color-control input { width: 22px; height: 22px; padding: 0; border: 0; cursor: pointer; background: transparent; }
.chart-stage { flex: 1; position: relative; min-width: 0; min-height: 0; }
.kline-chart { position: absolute; inset: 0; overflow: hidden; z-index: 0; }
.chart-message { position: absolute; inset: 0; z-index: 1; display: flex; gap: 10px; align-items: center; justify-content: center; background: var(--chart-bg); }
.chart-message button, .retry-history { color: var(--chart-accent); }
.loading-dot { width: 12px; height: 12px; border: 2px solid var(--chart-border); border-top-color: var(--chart-accent); border-radius: 50%; animation: spin .8s linear infinite; }
.drawing-hint { position: absolute; z-index: 1; bottom: 30px; left: 50%; transform: translateX(-50%); padding: 5px 10px; border: 1px solid var(--chart-border); border-radius: 4px; background: var(--chart-bg); pointer-events: none; white-space: nowrap; }
.chart-footer { display: flex; align-items: center; gap: 8px; min-height: 25px; padding: 0 8px; border-top: 1px solid var(--chart-border); font-size: 10px; flex-shrink: 0; }
.chart-footer > span:first-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.footer-meta { margin-left: auto; white-space: nowrap; font-variant-numeric: tabular-nums; }
.chart-footer button { min-height: 22px; font-size: 10px; padding: 1px 4px; }
.chart-fullscreen { position: fixed !important; inset: 0 !important; width: 100vw !important; height: 100dvh !important; z-index: 10000; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 600px) { .chart-toolbar { gap: 2px; padding: 0 3px; } .chart-select select { max-width: 87px; } .drawing-rail { width: 35px; padding-inline: 1px; } .chart-footer { padding: 0 5px; gap: 3px; } .footer-meta { max-width: 110px; overflow: hidden; text-overflow: ellipsis; } }
@media (prefers-reduced-motion: reduce) { .loading-dot { animation: none; } }
</style>
