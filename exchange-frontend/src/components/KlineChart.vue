<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { dispose, init, type CandleType, type Chart, type Coordinate, type DataLoaderGetBarsParams, type KLineData, type OverlayCreate } from 'klinecharts'
import { useMarketStore } from '@/store/market'
import marketWebSocket from '@/utils/marketWebSocket'
import { useLocaleStore } from '@/store/locale'
import request from '@/utils/request'
import { candleFromQuote, chartPeriod, normalizeCandles } from '@/utils/chartData'
import { registerTradingDrawingOverlays } from '@/utils/chartOverlays'
import { indicatorCatalog, normalizePreferences, validParameters, validTimezone } from '@/utils/chartPreferences'

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
const preferenceKey = 'exchange:chart-preferences:v1'
let initialPreferences = normalizePreferences(null)
try { initialPreferences = normalizePreferences(JSON.parse(localStorage.getItem(preferenceKey) || 'null')) } catch { /* Use defaults when storage is unavailable. */ }
const preferences = ref(initialPreferences)
const timezone = ref(initialPreferences.timezone || 'Europe/London')
let systemTimezone = 'Europe/London'
const count = ref(0)
const drawingCount = ref(0)
const activeTool = ref('')
const selected = ref('')
const hidden = ref(false)
const locked = ref(false)
const magnet = ref(false)
const color = ref('#8cc63f')
const candleType = computed({ get: () => preferences.value.candleType as CandleType, set: value => { preferences.value.candleType = value } })
const dialog = ref<HTMLDialogElement>()
const panel = ref<'indicators' | 'settings' | 'timezone' | 'snapshot'>('indicators')
const search = ref('')
const snapshot = ref('')
const snapshotName = ref('')
const snapshotError = ref(false)
const preferenceSaved = ref(true)
const parameterError = ref('')
const selectedStudies = computed(() => indicatorCatalog.filter(item => preferences.value.indicators.includes(item.name)))
const visibleStudies = computed(() => indicatorCatalog.filter(item => `${item.name} ${item.zh} ${item.en}`.toLowerCase().includes(search.value.toLowerCase())))
const zones = (() => {
  const api = Intl as typeof Intl & { supportedValuesOf?: (key: string) => string[] }
  return [...new Set(['UTC', 'Asia/Shanghai', 'Asia/Hong_Kong', 'Asia/Singapore', 'Asia/Tokyo', 'Europe/London', 'America/New_York', Intl.DateTimeFormat().resolvedOptions().timeZone, ...(api.supportedValuesOf?.('timeZone') || [])])]
})()
const visibleZones = computed(() => [...new Set([timezone.value, ...zones])].filter(zone => zone.toLowerCase().includes(search.value.toLowerCase())))
const stageHeight = computed(() => {
  const panes = selectedStudies.value.filter(item => !item.main).length
  return panes > 1 ? `${260 + panes * 100}px` : '0px'
})
const historyLimited = ref(false)
let historyWindow = 200
let historyRetryAt = 0
let historyRetryTimer: ReturnType<typeof setTimeout> | undefined
let historyFailures = 0
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
let manuallyScrolled = false
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
    grid: { show: preferences.value.grid, horizontal: { color: grid, style: 'solid' }, vertical: { color: grid, style: 'solid' } },
    crosshair: { show: preferences.value.crosshair },
    candle: {
      type: candleType.value,
      bar: { upColor: preferences.value.upColor, downColor: preferences.value.downColor, upBorderColor: preferences.value.upColor, downBorderColor: preferences.value.downColor, upWickColor: preferences.value.upColor, downWickColor: preferences.value.downColor },
      area: { lineColor: '#8cc63f', backgroundColor: [{ offset: 0, color: 'rgba(140,198,63,.24)' }, { offset: 1, color: 'rgba(140,198,63,0)' }] },
      tooltip: { showRule: compact ? 'follow_cross' : 'always', showType: compact ? 'rect' : 'standard', title: { show: false }, legend: { size: 11 } },
      priceMark: { last: { show: preferences.value.lastPrice, upColor: preferences.value.upColor, downColor: preferences.value.downColor } },
    },
    xAxis: { axisLine: { color: grid }, tickText: { color: ink, size: 11 }, tickLine: { show: false } },
    yAxis: { axisLine: { show: false }, tickText: { color: ink, size: 11 }, tickLine: { show: false } },
    separator: { color: grid, activeBackgroundColor: 'rgba(140,198,63,.12)' },
    indicator: { tooltip: { showRule: compact ? 'follow_cross' : 'always', legend: { size: 10 }, title: { size: 10 } } },
  })
  chart.setLocale(chinese.value ? 'zh-CN' : 'en-US')
  const axis = { name: preferences.value.scale, paneId: 'candle_pane' }
  chart.overrideYAxis(axis)
}

function applyIndicators() {
  if (!chart) return
  for (const existing of chart.getIndicators()) {
    if (!preferences.value.indicators.includes(existing.name)) chart.removeIndicator({ name: existing.name, paneId: existing.paneId })
  }
  for (const item of selectedStudies.value) {
    const value = { name: item.name, calcParams: preferences.value.parameters[item.name] || item.params }
    if (chart.getIndicators({ name: item.name }).length) chart.overrideIndicator(value)
    else chart.createIndicator(value, { isStack: true, pane: item.main ? { id: 'candle_pane' } : { id: 'study_' + item.name, height: 95, minHeight: 65, dragEnabled: true } })
  }
}

async function openPanel(name: typeof panel.value) {
  panel.value = name; search.value = ''; parameterError.value = ''
  await nextTick()
  if (!dialog.value?.open) dialog.value?.showModal()
}
function toggleStudy(name: string) {
  const names = preferences.value.indicators
  preferences.value.indicators = names.includes(name) ? names.filter(item => item !== name) : [...names, name]
}
function updateParameter(name: string, index: number, event: Event) {
  const item = indicatorCatalog.find(item => item.name === name)!
  const values = [...(preferences.value.parameters[name] || item.params)]
  const input = event.target as HTMLInputElement
  values[index] = Number(input.value)
  if (!validParameters(name, values)) {
    parameterError.value = text('參數需為 1–500 的有效週期；短週期小於長週期。', 'Use valid periods from 1–500; short periods must be below long periods.')
    input.value = String((preferences.value.parameters[name] || item.params)[index]); return
  }
  parameterError.value = ''; preferences.value.parameters[name] = values
}
function parameterLabel(name: string, index: number) {
  const labels: Record<string, string[]> = {
    BOLL: [text('週期', 'Period'), text('倍數', 'Deviation')],
    MACD: [text('快線', 'Fast'), text('慢線', 'Slow'), text('訊號', 'Signal')],
    SAR: [text('起始 %', 'Start %'), text('步進 %', 'Step %'), text('上限 %', 'Max %')],
    SMA: [text('週期', 'Period'), text('權重', 'Weight')],
  }
  return labels[name]?.[index] || text('週期 ', 'Period ') + (index + 1)
}
function chooseTimezone(zone: string) {
  if (!validTimezone(zone)) return
  preferences.value.timezone = zone; timezone.value = zone
}
async function takeSnapshot() {
  if (!chart || !count.value) return
  snapshotError.value = false
  const symbol = props.symbol, period = interval.value, zone = timezone.value
  try {
    const source = new Image()
    source.src = chart.getConvertPictureUrl(true, 'png', dark.value ? '#131722' : '#ffffff')
    await source.decode()
    const canvas = document.createElement('canvas')
    canvas.width = source.width; canvas.height = source.height + 64
    const context = canvas.getContext('2d')!
    context.fillStyle = dark.value ? '#131722' : '#ffffff'; context.fillRect(0, 0, canvas.width, canvas.height)
    context.fillStyle = dark.value ? '#e2e8f0' : '#243247'; context.font = 'bold 18px sans-serif'
    context.fillText(`${symbol} · ${period}`, 16, 26)
    context.font = '12px sans-serif'
    context.fillText(`${zone} · ${new Intl.DateTimeFormat('sv-SE', { timeZone: zone, dateStyle: 'short', timeStyle: 'medium' }).format(new Date())}`, 16, 48)
    context.drawImage(source, 0, 64)
    snapshot.value = canvas.toDataURL('image/png')
    snapshotName.value = `${symbol.replace(/[^a-z0-9_-]/gi, '_')}-${period}-${Date.now()}.png`
    await openPanel('snapshot')
  } catch { snapshotError.value = true }
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
function scrollLatest() { chart?.scrollToRealTime(0); manuallyScrolled = false }
function retryLoad() { retry?.() }
function toggleLock() { locked.value = !locked.value; chart?.overrideOverlay({ groupId, lock: locked.value }); persistDrawings() }
function changeColor() {
  if (selected.value) { chart?.overrideOverlay({ id: selected.value, styles: { line: { color: color.value }, rect: { borderColor: color.value, color: color.value + '18' }, polygon: { borderColor: color.value, color: color.value + '22' } } }); persistDrawings() }
}
function keydown(event: KeyboardEvent) {
  if (dialog.value?.open) return
  if ((event.target as HTMLElement).matches('input, select, textarea')) return
  if (event.key === 'Escape') { cancelDrawing(); fullscreen.value = false }
  if (event.key === 'Delete' && selected.value) { event.preventDefault(); removeDrawing() }
}

async function fetchBars(before: number, signal: AbortSignal, limit = 200) {
  const history = Number.isFinite(before)
  const url = '/market/kline/' + (history ? 'history/' : '') + encodeURIComponent(props.symbol)
  const query = { interval: interval.value, limit, ...(history ? { endTime: before - 1 } : { category: props.category || 'Crypto' }) }
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
  if (validated.length && !aligned.length) throw new Error('No aligned candles')
  const candles = aligned.filter(bar => bar.timestamp < before)
  if (history && aligned.length && !candles.length) throw new Error('History cursor was not honored')
  return { candles, damaged: !!response.data?.missingData || rows.some(row => row.partial === true) || aligned.length < validated.length || rows.some(row => normalizeCandles([row]).length === 0),
    stale: response.data?.status === 'stale' || !!response.data?.pending }
}

async function fetchHistory(before: number, signal: AbortSignal) {
  try { return { ...await fetchBars(before, signal), limited: false } } catch (failure) {
    if (signal.aborted) throw failure
    // The existing latest endpoint accepts up to 1000 bars. Use that window when
    // the cursor endpoint is missing or temporarily unavailable; never fabricate history.
    while (true) {
      const limit = Math.min(1000, Math.max(historyWindow + 200, (chart?.getDataList().length || 0) + 200))
      const result = await fetchBars(Infinity, signal, limit)
      if (signal.aborted) throw new Error('Aborted')
      historyWindow = limit
      const candles = result.candles.filter(bar => bar.timestamp < before)
      if (candles.length) return { ...result, candles, limited: false }
      if (limit === 1000) return { ...result, candles, limited: true }
    }
  }
}

function retryHistoryOnScroll() {
  if (!chart || loading.value || historyLoading.value || chart.getVisibleRange().from > 20 || !error.value || !retry || Date.now() < historyRetryAt) return
  historyFailures = 0
  retry()
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
    const result = history ? await fetchHistory(before, signal) : { ...await fetchBars(before, signal), limited: false }
    const { candles } = result
    if (version !== revision || signal.aborted) return
    dataWarning.value ||= result.damaged
    staleCandles.value ||= result.stale
    historyLimited.value = result.limited
    params.callback(candles, { forward: candles.length > 0 && !result.limited, backward: false })
    cacheBars()
    exhausted.value = history && candles.length === 0 && !result.limited
    if (!history) {
      empty.value = candles.length === 0; restoreDrawings(); emit('ready')
    }
    retry = null
    historyFailures = 0
  } catch {
    if (signal.aborted || version !== revision) return
    // Retry resumes the failed page without resetting the user's viewport.
    error.value = true
    retry = () => { void loadBars(params, version, signal) }
    if (history) {
      // Always release the library's loading lock. Retrying must not require a reload.
      params.callback([], { forward: false, backward: false })
      historyRetryAt = Date.now() + 3000
      if (++historyFailures <= 3) {
        clearTimeout(historyRetryTimer)
        historyRetryTimer = setTimeout(() => {
          if (!signal.aborted && version === revision && error.value && !historyLoading.value && chart && chart.getVisibleRange().from <= 20) retry?.()
        }, 3000)
      }
    }
  } finally {
    if (version === revision) { loading.value = false; historyLoading.value = false; replayQuote() }
  }
}

async function syncLatest() {
  if (!chart || !realtime || loading.value || historyLoading.value || syncing) return
  const version = revision, signal = controller.signal
  const session = market.quoteStatusMap[props.symbol]?.simulationSession
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
    if (signal.aborted || version !== revision || !chart || historyLoading.value || session !== market.quoteStatusMap[props.symbol]?.simulationSession) return
    if (!candles.length) throw new Error('No latest candles')
    const current = chart.getDataList()
    const range = chart.getVisibleRange()
    const anchor = current[range.from]?.timestamp
    const x = anchor ? (chart.convertToPixel({ timestamp: anchor }) as Partial<Coordinate>).x : undefined
    // The subscription callback updates the last candle or appends a new one
    // without clearing history, drawings, or the current viewport.
    let last = current[current.length - 1]
    // A delayed source minute can complete a previously partial aggregate. Update the
    // retained objects before triggering recalculation; history and drawings stay in place.
    if (market.quoteStatusMap[props.symbol]?.controlHistory) {
      const incoming = new Map(candles.map(bar => [bar.timestamp, bar]))
      let corrected = false
      for (const previous of current) {
        const next = incoming.get(previous.timestamp)
        if (next && (next.open !== previous.open || next.high !== previous.high || next.low !== previous.low || next.close !== previous.close || next.volume !== previous.volume)) {
          Object.assign(previous, next); corrected = true
        }
      }
      if (corrected && last) realtime?.(last)
    }
    for (const bar of candles) {
      if (last && bar.timestamp < last.timestamp) continue
      if (!last || bar.timestamp > last.timestamp ||
        bar.open !== last.open || bar.high !== last.high || bar.low !== last.low ||
        bar.close !== last.close || bar.volume !== last.volume) realtime?.(bar)
      last = bar
    }
    empty.value = chart.getDataList().length === 0
    if (manuallyScrolled && anchor && x !== undefined) {
      const nextX = (chart.convertToPixel({ timestamp: anchor }) as Partial<Coordinate>).x
      if (nextX !== undefined && nextX !== x) chart.scrollByDistance(x - nextX, 0)
    }
    dataWarning.value ||= result.damaged
    staleCandles.value = result.stale
    syncError.value = false
    cacheBars()
    replayQuote()
  } catch {
    if (!signal.aborted && version === revision) syncError.value = true
  } finally {
    if (version === revision) {
      syncing = false
      if (session !== market.quoteStatusMap[props.symbol]?.simulationSession) void syncLatest()
    }
  }
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
  manuallyScrolled = false
  syncing = false
  lastSyncAttempt = 0
  historyWindow = 200
  historyFailures = 0
  historyLimited.value = false
  clearTimeout(historyRetryTimer)
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
  // Persisted mixed candles are authoritative; never rebuild their OHLC from client ticks.
  if (market.quoteStatusMap[props.symbol]?.controlHistory) {
    if (Date.now() - lastSyncAttempt >= 900) void syncLatest()
    return
  }
  if (!chart || !realtime || !count.value || market.getQuoteStatus(props.symbol) !== 'available') return
  const time = market.quoteStatusMap[props.symbol]?.timestamp || 0
  const milliseconds = time < 10_000_000_000 ? time * 1000 : time
  if (milliseconds < lastQuoteTime || milliseconds > Date.now() + 5000) return
  const bars = chart.getDataList()
  const bar = candleFromQuote(bars[bars.length - 1], market.getPrice(props.symbol), milliseconds, interval.value)
  if (!bar && milliseconds > (bars[bars.length - 1]?.timestamp || Infinity)) {
    syncError.value = true
    if (Date.now() - lastSyncAttempt >= (market.quoteStatusMap[props.symbol]?.simulated ? 1000 : 15_000)) void syncLatest()
  }
  if (bar) {
    lastQuoteTime = milliseconds; realtime(bar); count.value = chart.getDataList().length
    cacheBars()
  }
}
watch(() => market.quoteStatusMap[props.symbol]?.simulationSession, (value, previous) => {
  if (value === previous) return
  if (value && count.value) { lastSyncAttempt = 0; void syncLatest() }
  else resetMarket()
})
watch(() => market.quoteStatusMap[props.symbol], replayQuote)
watch(() => [preferences.value.indicators, preferences.value.parameters], applyIndicators, { deep: true })
watch(preferences, () => {
  applyTheme()
  try { localStorage.setItem(preferenceKey, JSON.stringify(preferences.value)); preferenceSaved.value = true } catch { preferenceSaved.value = false }
}, { deep: true })
watch(chinese, applyTheme)
watch(timezone, value => chart?.setTimezone(value))
watch(stageHeight, async () => { await nextTick(); chart?.resize() })
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
  chart.subscribeAction('onScroll', () => { manuallyScrolled = true; retryHistoryOnScroll() })
  stopConnected = marketWebSocket.onConnected(() => { void syncLatest() })
  syncTimer = setInterval(() => {
    if (document.visibilityState === 'hidden') return
    if (Date.now() - lastSyncAttempt >= (market.quoteStatusMap[props.symbol]?.controlHistory ? 900 : 14_000)) void syncLatest()
  }, 1000)
  resizeObserver = new ResizeObserver(() => { chart?.resize(); applyTheme() })
  resizeObserver.observe(container.value)
  themeObserver = new MutationObserver(applyTheme)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  void request.get('/user/system/timezone').then((response: any) => {
    if (chart && validTimezone(response?.timezone)) {
      systemTimezone = response.timezone
      if (!preferences.value.timezone) timezone.value = systemTimezone
    }
  }).catch(() => {})
})
onUnmounted(() => {
  persistDrawings()
  revision++
  controller.abort()
  clearInterval(syncTimer)
  clearTimeout(historyRetryTimer)
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
      <button class="toolbar-indicators" aria-haspopup="dialog" @click="openPanel('indicators')"><svg viewBox="0 0 24 24"><path d="m3 17 5-7 5 4 8-10M3 21h18" /></svg>{{ text('指標', 'Indicators') }} <span class="tool-count">{{ selectedStudies.length }}</span></button>
      <button aria-haspopup="dialog" :aria-label="text('時區設定', 'Timezone settings')" @click="openPanel('timezone')"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8"/><path d="M12 7v5l3 2" /></svg><span class="toolbar-label">{{ text('時區', 'Timezone') }}</span></button>
      <button aria-haspopup="dialog" :aria-label="text('圖表設定', 'Chart settings')" @click="openPanel('settings')"><svg viewBox="0 0 24 24"><path d="M5 4v16M12 4v16M19 4v16M2 8h6m1 8h6m1-7h6" /></svg><span class="toolbar-label">{{ text('設定', 'Settings') }}</span></button>
      <button :disabled="!count" :aria-label="text('圖表快照', 'Chart snapshot')" @click="takeSnapshot"><svg viewBox="0 0 24 24"><path d="M3 7h4l2-3h6l2 3h4v13H3z"/><circle cx="12" cy="13" r="4" /></svg><span class="toolbar-label">{{ text('快照', 'Snapshot') }}</span></button>
      <div class="toolbar-spacer"></div>
      <button @click="scrollLatest">{{ text('回到最新', 'Latest') }}</button>
      <button :title="text('全螢幕', 'Fullscreen')" :aria-label="text('全螢幕', 'Fullscreen')" :aria-pressed="fullscreen" @click="fullscreen = !fullscreen"><svg viewBox="0 0 24 24"><path d="M9 4H4v5m11-5h5v5M4 15v5h5m11-5v5h-5" /></svg></button>
    </div>
    <div v-if="selectedStudies.length" class="study-strip" :aria-label="text('已啟用指標', 'Active indicators')">
      <button v-for="item in selectedStudies" :key="item.name" :aria-label="text('移除 ', 'Remove ') + item.name" @click="toggleStudy(item.name)">{{ item.name }} <span>×</span></button>
      <span>{{ text('點擊移除 · 副圖可拖動調整高度', 'Click to remove · Drag pane dividers to resize') }}</span>
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
      <div class="chart-stage" :style="{ minHeight: stageHeight }">
        <div ref="container" class="kline-chart" :style="{ touchAction: activeTool ? 'none' : 'pan-y' }"></div>
        <div v-if="loading || empty || (error && !count)" class="chart-message" role="status">
          <span v-if="loading" class="loading-dot"></span>
          <span>{{ loading ? text('正在載入行情…', 'Loading candles…') : error ? text('行情載入失敗', 'Could not load candles') : text('暫無 K 線數據', 'No candle data') }}</span>
          <button v-if="error" @click="retryLoad">{{ text('重試', 'Retry') }}</button>
        </div>
        <div v-if="activeTool" class="drawing-hint">{{ activeTool === 'brush' ? text('按住拖動繪製 · Esc 取消', 'Drag to draw · Esc to cancel') : text('點擊圖表設定錨點 · Esc 取消', 'Click to place points · Esc to cancel') }}</div>
      </div>
    </div>
    <div class="chart-footer" role="status" aria-live="polite">
      <span v-if="market.quoteStatusMap[props.symbol]?.controlState === 'WAITING_SOURCE'">{{ text('靜態價格 · 等待原始行情恢復', 'Static price · Waiting for source') }}</span>
      <span v-else-if="market.quoteStatusMap[props.symbol]?.controlState === 'HOLDING'">{{ text('保持控盤偏移 · 等待手動恢復', 'Holding offset · Manual restore required') }}{{ market.quoteStatusMap[props.symbol]?.sourceAvailable ? '' : text(' · 源異常，靜態等待', ' · Source unavailable, static price') }}</span>
      <span v-else-if="market.quoteStatusMap[props.symbol]?.controlState === 'RUNNING'">{{ text('目標控盤運行中', 'Target control running') }}{{ market.quoteStatusMap[props.symbol]?.sourceAvailable ? '' : text(' · 原始行情異常，按控盤價交易', ' · Source unavailable, trading at controlled price') }}</span>
      <span v-else-if="market.quoteStatusMap[props.symbol]?.simulated">{{ text('虛擬行情', 'Simulated market') }}</span>
      <span v-if="historyLoading">{{ text('正在載入更早行情…', 'Loading older candles…') }}</span>
      <button v-else-if="error" class="retry-history" @click="retryLoad">{{ count ? text('歷史載入失敗 · 點擊重試', 'History failed · Retry') : text('行情載入失敗 · 重試', 'Candles unavailable · Retry') }}</button>
      <span v-else-if="historyLimited">{{ text('已到目前接口可回溯範圍', 'History limit reached for the current data source') }}</span>
      <span v-else-if="snapshotError">{{ text('快照失敗，請重新嘗試', 'Snapshot failed; please retry') }}</span>
      <button v-else-if="syncError" class="retry-history" @click="syncLatest">{{ text('最新 K 線未完整同步 · 重試', 'Latest candles incomplete · Retry') }}</button>
      <span v-else-if="dataWarning">{{ text('K 線含缺失或部分源數據，未補造走勢', 'Partial or missing source data; no fabricated history') }}</span>
      <span v-else-if="staleCandles">{{ text('顯示快取 K 線，等待更新', 'Cached candles; awaiting refresh') }}</span>
      <span v-else-if="!saved">{{ text('繪圖無法儲存至此瀏覽器', 'Drawing storage unavailable') }}</span>
      <span v-else>{{ exhausted ? text('已到最早可用行情', 'Earliest available candles') : text('向右拖動查看更早行情', 'Drag right for older candles') }}</span>
      <button class="footer-meta" :title="timezone" :aria-label="text('時區設定', 'Timezone settings')" @click="openPanel('timezone')">{{ count }} {{ text('根', 'bars') }} · {{ timezone }}</button>
      <button v-if="drawingCount" :title="text('清除全部繪圖', 'Clear all drawings')" @click="removeDrawing(true)">{{ text('清除', 'Clear') }} ({{ drawingCount }})</button>
    </div>
    <dialog ref="dialog" class="chart-dialog" :class="{ 'snapshot-dialog': panel === 'snapshot' }" aria-labelledby="chart-panel-title" @click="($event.target === dialog) && dialog?.close()">
      <header class="panel-header">
        <div><span class="panel-eyebrow">{{ props.symbol }} · {{ interval }}</span><h2 id="chart-panel-title">{{ panel === 'indicators' ? text('技術指標', 'Technical indicators') : panel === 'timezone' ? text('圖表時區', 'Chart timezone') : panel === 'settings' ? text('圖表設定', 'Chart settings') : text('圖表快照', 'Chart snapshot') }}</h2></div>
        <button autofocus :aria-label="text('關閉', 'Close')" @click="dialog?.close()"><svg viewBox="0 0 24 24"><path d="m6 6 12 12M6 18 18 6" /></svg></button>
      </header>
      <div class="panel-content">
        <template v-if="panel === 'indicators'">
          <div class="panel-summary"><span>{{ indicatorCatalog.length }} {{ text('種指標 · 支援多選疊加', 'studies · select multiple') }}</span><button @click="preferences.indicators = []">{{ text('清空', 'Clear all') }}</button></div>
          <input v-model="search" class="panel-search" type="search" :placeholder="text('搜尋名稱，例如 MACD、布林', 'Search indicators, e.g. MACD, Bollinger')" :aria-label="text('搜尋指標', 'Search indicators')">
          <p class="panel-note">{{ text('主圖指標疊加顯示，副圖指標各自獨立。多個副圖可上下捲動查看。', 'Overlay studies share the price chart. Oscillators use separate panes; scroll vertically to view them.') }}</p>
          <p class="panel-note">{{ text('成交量類指標依賴數據源提供的成交量；外匯等品種可能不提供。', 'Volume studies depend on source volume data, which may be unavailable for forex and some other instruments.') }}</p>
          <p v-if="parameterError" class="panel-error" role="alert">{{ parameterError }}</p>
          <section v-for="main in [true, false]" :key="String(main)" class="indicator-group">
            <h3>{{ main ? text('主圖疊加', 'Price overlays') : text('副圖分析', 'Oscillators & volume') }}</h3>
            <div class="indicator-grid">
              <div v-for="item in visibleStudies.filter(item => item.main === main)" :key="item.name" class="indicator-card" :class="{ chosen: preferences.indicators.includes(item.name) }">
                <label><input type="checkbox" :checked="preferences.indicators.includes(item.name)" :aria-label="item.name" @change="toggleStudy(item.name)"><span><strong>{{ item.name }}</strong><small>{{ chinese ? item.zh : item.en }}</small></span></label>
                <div v-if="preferences.indicators.includes(item.name) && item.params.length" class="indicator-params">
                  <label v-for="(value, index) in preferences.parameters[item.name] || item.params" :key="index"><span>{{ parameterLabel(item.name, index) }}</span><input type="number" min="1" max="500" :step="item.name === 'SAR' || (item.name === 'BOLL' && index === 1) ? 0.1 : 1" :value="value" :aria-label="item.name + ' parameter ' + (index + 1)" @change="updateParameter(item.name, index, $event)"></label>
                </div>
              </div>
            </div>
          </section>
          <p v-if="!visibleStudies.length" class="panel-note">{{ text('沒有符合的指標', 'No matching indicators') }}</p>
        </template>
        <template v-else-if="panel === 'timezone'">
          <p class="panel-note">{{ text('僅改變圖表時間顯示，不改變行情及 K 線週期。', 'Changes displayed chart times; candle data and periods stay the same.') }}</p>
          <button class="local-timezone" @click="chooseTimezone(Intl.DateTimeFormat().resolvedOptions().timeZone)">{{ text('使用裝置時區', 'Use device timezone') }}</button>
          <input v-model="search" class="panel-search" type="search" :placeholder="text('搜尋城市 / 時區，例如 Asia/Shanghai', 'Search city / timezone, e.g. America/New_York')" :aria-label="text('搜尋時區', 'Search timezones')">
          <div class="timezone-list"><button v-for="zone in visibleZones" :key="zone" :class="{ active: timezone === zone }" :aria-pressed="timezone === zone" @click="chooseTimezone(zone)"><span>{{ zone.replace(/_/g, ' ') }}</span><span>{{ timezone === zone ? '✓' : '' }}</span></button></div>
          <p v-if="!visibleZones.length" class="panel-note">{{ text('沒有符合的時區', 'No matching timezones') }}</p>
        </template>
        <template v-else-if="panel === 'settings'">
          <h3>{{ text('價格與外觀', 'Price & appearance') }}</h3>
          <label class="setting-row"><span>{{ text('價格刻度', 'Price scale') }}</span><select v-model="preferences.scale" :aria-label="text('價格刻度', 'Price scale')"><option value="normal">{{ text('線性', 'Linear') }}</option><option value="logarithm">{{ text('對數', 'Logarithmic') }}</option><option value="percentage">{{ text('百分比', 'Percentage') }}</option></select></label>
          <label class="setting-row"><span>{{ text('上漲顏色', 'Up color') }}</span><input v-model="preferences.upColor" type="color" :aria-label="text('上漲顏色', 'Up color')"></label>
          <label class="setting-row"><span>{{ text('下跌顏色', 'Down color') }}</span><input v-model="preferences.downColor" type="color" :aria-label="text('下跌顏色', 'Down color')"></label>
          <h3>{{ text('輔助顯示', 'Display options') }}</h3>
          <label class="setting-row"><span>{{ text('網格線', 'Grid lines') }}</span><input v-model="preferences.grid" type="checkbox"></label>
          <label class="setting-row"><span>{{ text('十字游標', 'Crosshair') }}</span><input v-model="preferences.crosshair" type="checkbox"></label>
          <label class="setting-row"><span>{{ text('最新價格線', 'Last price line') }}</span><input v-model="preferences.lastPrice" type="checkbox"></label>
          <button class="reset-preferences" @click="preferences = normalizePreferences(null); timezone = systemTimezone">{{ text('恢復預設設定與指標', 'Reset settings and indicators') }}</button>
        </template>
        <template v-else><img class="snapshot-preview" :src="snapshot" :alt="text('包含指標及繪圖的 K 線快照', 'Chart snapshot with indicators and drawings')"><a class="snapshot-download" :href="snapshot" :download="snapshotName">{{ text('下載 PNG 圖片', 'Download PNG') }}</a></template>
      </div>
      <footer class="panel-footer"><span>{{ preferenceSaved ? text('設定自動儲存於此瀏覽器', 'Preferences saved in this browser') : text('瀏覽器無法儲存設定', 'Browser storage unavailable') }}</span><button @click="dialog?.close()">{{ text('完成', 'Done') }}</button></footer>
    </dialog>
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
.chart-body { flex: 1; min-height: 0; display: flex; overflow-y: auto; overscroll-behavior-y: contain; }
.drawing-rail { width: 39px; flex-shrink: 0; padding: 5px 3px; display: flex; flex-direction: column; gap: 3px; align-items: center; border-right: 1px solid var(--chart-border); overflow-y: auto; scrollbar-width: thin; scrollbar-color: var(--chart-border) transparent; }
.drawing-rail button { width: 31px; min-height: 30px; }
.rail-divider { height: 1px; width: 22px; background: var(--chart-border); margin: 3px 0; flex-shrink: 0; }
.color-control { width: 30px; height: 28px; display: flex; justify-content: center; align-items: center; flex-shrink: 0; }
.color-control input { width: 22px; height: 22px; padding: 0; border: 0; cursor: pointer; background: transparent; }
.chart-stage { flex: 1; position: relative; min-width: 0; min-height: 0; }
.kline-chart { position: absolute; inset: 0; overflow: hidden; z-index: 0; touch-action: pan-y; overscroll-behavior-x: contain; }
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
.tool-count { display: inline-grid; place-items: center; min-width: 18px; height: 18px; padding: 0 3px; border-radius: 4px; background: var(--chart-hover); color: var(--chart-accent); font-size: 10px; }
.toolbar-indicators { gap: 5px; }
.toolbar-label { margin-left: 3px; }
.study-strip { display: flex; gap: 5px; align-items: center; min-height: 29px; padding: 2px 8px; overflow-x: auto; border-bottom: 1px solid var(--chart-border); flex-shrink: 0; }
.study-strip button { font-size: 10px; min-height: 21px; gap: 8px; background: var(--chart-hover); }
.study-strip button span { opacity: .6; }
.study-strip > span { font-size: 10px; white-space: nowrap; opacity: .7; }
.chart-dialog { --dialog-width: 640px; padding: 0; width: min(var(--dialog-width), calc(100vw - 28px)); max-width: none; max-height: min(740px, calc(100dvh - 40px)); margin: auto; border: 1px solid var(--chart-border); border-radius: 14px; color: var(--chart-ink); background: var(--chart-bg); box-shadow: 0 24px 80px #0004; font: 13px/1.5 -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; overflow: hidden; }
.chart-dialog[open] { display: flex; flex-direction: column; }
.chart-dialog::backdrop { background: #10182866; backdrop-filter: blur(3px); }
.panel-header { display: flex; justify-content: space-between; align-items: center; padding: 18px 22px; border-bottom: 1px solid var(--chart-border); flex-shrink: 0; }
.panel-eyebrow { font-size: 10px; letter-spacing: .08em; color: var(--chart-accent); }
.panel-header h2 { margin: 3px 0 0; font-size: 19px; font-weight: 600; color: var(--chart-ink); }
.panel-header > button { width: 32px; height: 32px; border: 1px solid var(--chart-border); border-radius: 8px; }
.panel-content { padding: 16px 22px 22px; overflow-y: auto; min-height: 0; overscroll-behavior: contain; }
.panel-content h3 { margin: 18px 0 10px; font-size: 11px; font-weight: 600; letter-spacing: .03em; }
.panel-summary { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 10px; font-size: 12px; }
.panel-summary button { color: var(--chart-accent); }
.panel-search { width: 100%; box-sizing: border-box; height: 40px; border: 1px solid var(--chart-border); border-radius: 7px; background: var(--chart-hover); color: inherit; padding: 0 12px; font: inherit; }
.panel-note { font-size: 11px; line-height: 1.7; margin: 10px 0; opacity: .8; }
.panel-error { color: #e45a5a; font-size: 12px; }
.indicator-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; align-items: start; }
.indicator-card { border: 1px solid var(--chart-border); border-radius: 8px; overflow: hidden; }
.indicator-card.chosen { border-color: var(--chart-accent); background: var(--chart-hover); }
.indicator-card > label { display: flex; gap: 10px; align-items: center; padding: 12px; cursor: pointer; }
.indicator-card strong { display: block; font-size: 12px; font-weight: 600; }
.indicator-card small { display: block; margin-top: 2px; font-size: 10px; opacity: .75; }
.chart-dialog input[type=checkbox] { width: 15px; height: 15px; accent-color: var(--chart-accent); flex-shrink: 0; cursor: pointer; }
.indicator-params { display: flex; flex-wrap: wrap; gap: 6px; padding: 0 12px 10px; }
.indicator-params label { display: flex; flex-direction: column; gap: 2px; font-size: 9px; }
.indicator-params input { width: 48px; height: 26px; box-sizing: border-box; border: 1px solid var(--chart-border); border-radius: 4px; background: var(--chart-bg); color: inherit; font: inherit; font-size: 11px; padding-left: 4px; }
.timezone-list { display: grid; gap: 3px; margin-top: 12px; }
.timezone-list button { justify-content: space-between; padding: 10px; border-radius: 6px; text-align: left; }
.local-timezone { color: var(--chart-accent); margin: 0 0 10px; }
.setting-row { display: flex; align-items: center; justify-content: space-between; min-height: 46px; gap: 12px; border-bottom: 1px solid var(--chart-border); font-size: 12px; cursor: pointer; }
.setting-row select { border: 1px solid var(--chart-border); border-radius: 6px; background: var(--chart-bg); padding: 6px; }
.setting-row input[type=color] { background: transparent; border: 1px solid var(--chart-border); border-radius: 4px; width: 36px; height: 28px; padding: 2px; cursor: pointer; }
.reset-preferences { margin-top: 20px; color: var(--chart-accent); }
.panel-footer { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 12px 22px; border-top: 1px solid var(--chart-border); font-size: 10px; flex-shrink: 0; }
.panel-footer > button, .snapshot-download { background: var(--chart-accent); color: #fff; border-radius: 6px; padding: 7px 22px; font-size: 12px; text-decoration: none; }
.snapshot-dialog { --dialog-width: 960px; }
.snapshot-preview { width: 100%; height: auto; border: 1px solid var(--chart-border); border-radius: 6px; display: block; }
.snapshot-download { display: inline-flex; margin-top: 16px; }
@media (max-width: 600px) { .toolbar-label { display: none; } .study-strip > span { display: none; } .chart-dialog { width: calc(100vw - 16px); max-height: calc(100dvh - 20px); border-radius: 10px; } .panel-header, .panel-footer { padding: 12px 14px; } .panel-content { padding: 12px 14px 16px; } .indicator-grid { grid-template-columns: 1fr; } .panel-header h2 { font-size: 17px; } }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 600px) { .chart-toolbar { gap: 2px; padding: 0 3px; } .chart-select select { max-width: 87px; } .drawing-rail { width: 35px; padding-inline: 1px; } .chart-footer { padding: 0 5px; gap: 3px; } .footer-meta { max-width: 110px; overflow: hidden; text-overflow: ellipsis; } }
@media (prefers-reduced-motion: reduce) { .loading-dot { animation: none; } }
</style>
