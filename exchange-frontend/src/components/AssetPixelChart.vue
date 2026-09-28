<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import request from '@/utils/request'
import { useLocaleStore } from '@/store/locale'
import { assetDisplayIndex, assetDisplayLine, assetColumnTime, assetPriceTicks, assetDisplayValue, assetScrubTime, assetHighlight, assetHistoryPoints } from '@/utils/assetPixelWindow'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ total: [value: number | null] }>()
const locale = useLocaleStore()
const en = computed(() => locale.locale !== 'zh-TW')
type Point = import('@/utils/assetPixelWindow').AssetPoint
type History = import('@/utils/assetPixelWindow').AssetHistory
const equityBasis = ref(false), valuationStale = ref(false)
const canvas = ref<HTMLCanvasElement>()
const period = ref('1M'), points = ref<Point[]>([]), loading = ref(false), failed = ref(false)
const selected = ref(-1), asOf = ref(0), refreshTurns = ref(0)
const from = ref(0), intervalMs = ref(60000), selectedTime = ref<number | null>(null)
const periods = ['1D', '1W', '1M', '1Y']
const labels = computed(() => locale.locale === 'ja' ? ['1日', '1週間', '1か月', '1年'] : en.value ? ['1D', '1W', '1M', '1Y'] : ['1日', '1週', '1月', '1年'])
const change = ref<number | null>(null)
// Compare the selected point (or latest value) with the fixed range opening.
const chartRgb = computed(() => {
  const opening = assetDisplayValue(points.value, from.value, intervalMs.value + 120000)
  const current = assetDisplayValue(points.value, selectedTime.value ?? asOf.value, intervalMs.value + 120000)
  if (opening === null || current === null) return '137,150,135'
  return current < opening ? '214,74,83' : '96,177,43'
})
const incomePercent = ref<number | null>(null)
const percentLabel = computed(() => incomePercent.value === null ? '—' : `${incomePercent.value > 0 ? '+' : incomePercent.value < 0 ? '−' : ''}${money(Math.abs(incomePercent.value))}%`)
const timezone = ref('UTC')
const incomeLabel = computed(() => [['今日收益', 'Today’s income'], ['本週收益', 'This week’s income'], ['本月收益', 'This month’s income'], ['本年收益', 'This year’s income']].map(([zh, en]) => locale.text(zh!, en!))[periods.indexOf(period.value)])
const money = (n: number) => n.toLocaleString(locale.locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const selectedPoint = computed(() => points.value[selected.value])
const selectedCarried = computed(() => !!selectedPoint.value && (selectedPoint.value.filled || (selectedTime.value ?? asOf.value) > selectedPoint.value.time))
const selectedSourceTime = computed(() => selectedPoint.value ? new Intl.DateTimeFormat(locale.locale, { timeZone: timezone.value, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(selectedPoint.value.closeAt ?? selectedPoint.value.time) : '')
const selectedDate = computed(() => {
  const point = selectedPoint.value
  const dailyBucket = period.value === '1Y' && point?.bucketStart != null
  const time = dailyBucket ? point.bucketStart! : point?.closeAt ?? point?.time ?? selectedTime.value ?? asOf.value
  const parts = new Intl.DateTimeFormat('en-GB', { timeZone: dailyBucket ? 'UTC' : timezone.value, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(time)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(p => p.type === type)!.value
  const day = `${part('month')}/${part('day')}`, hour = part('hour')
  if (period.value === '1D') return `${hour}:${part('minute')}`
  if (period.value === '1Y') return day
  // Month labels identify the four-hour slot; the observed amount is unchanged.
  return `${day}-${period.value === '1M' ? String(Math.floor(Number(hour) / 4) * 4).padStart(2, '0') : hour}:00`
})
const selectedAmount = computed(() => {
  const value = assetDisplayValue(points.value, selectedTime.value ?? asOf.value, intervalMs.value + 120000)
  return value === null ? '—' : '$' + money(value)
})
const displayLine = computed(() => assetDisplayLine(points.value, from.value, asOf.value))
const extrema = computed(() => {
  const data = displayLine.value.filter((p): p is Point & { value: number } => p.value !== null)
  if (!data.length) return null
  return { high: data.reduce((a, b) => b.value > a.value ? b : a), low: data.reduce((a, b) => b.value < a.value ? b : a) }
})
const ticks = computed(() => assetPriceTicks(displayLine.value.map(p => p.value).filter((v): v is number => v !== null)))
let width = 0, animation = 0, observer: ResizeObserver | undefined, timer: ReturnType<typeof setInterval> | undefined
let plotRight = 0
let generation = 0, disposed = false, activeRequest = false
let pointerStart: { x: number; y: number; id: number } | undefined
let holdTimer: ReturnType<typeof setTimeout> | undefined
let scrubbing = false
let reduced: MediaQueryList | undefined
const height = 180, plotInset = 8

async function load(replay = false) {
  const version = ++generation
  loading.value = true; activeRequest = true
  try {
    const data = await request.get('/user/asset-history', { params: { range: period.value } }) as unknown as History
    if (disposed || version !== generation) return
    if (pointerStart) return // Keep the time window stable under the user's finger.
    const next = assetHistoryPoints(data)
    equityBasis.value = data.schemaVersion === 2
    valuationStale.value = equityBasis.value && data.total === null
    const changed = points.value[points.value.length - 1]?.value !== Number(data.total)
    points.value = next; asOf.value = data.asOf; from.value = data.from; intervalMs.value = data.intervalMs; failed.value = false
    if (selectedTime.value !== null) {
      selectedTime.value = Math.max(data.from, Math.min(data.asOf, selectedTime.value))
      selected.value = assetDisplayIndex(next, selectedTime.value)
    }
    change.value = Number(data.income); timezone.value = data.timezone || 'UTC'
    incomePercent.value = data.incomePercent != null && Number.isFinite(Number(data.incomePercent)) ? Number(data.incomePercent) : null
    emit('total', data.total === null ? null : Number(data.total))
    if (replay || changed) play(); else draw(1)
  } catch {
    if (!disposed && version === generation) failed.value = true
  } finally {
    if (version === generation) { loading.value = false; activeRequest = false }
  }
}

function draw(progress: number, origin = -1) {
  const context = canvas.value?.getContext('2d')
  if (!context || !width) return
  context.clearRect(0, 0, width, height)
  if (!props.visible || !points.value.length) return
  const data = points.value
  const tint = (alpha: number) => `rgba(${chartRgb.value},${alpha})`
  const prices = ticks.value, low = prices[0]!, high = prices[3]!, base = height - 25
  context.font = '10px -apple-system, sans-serif'
  plotRight = width - plotInset
  const xAt = (time: number) => plotInset + (time - from.value) / (asOf.value - from.value) * (plotRight - plotInset)
  const yAt = (value: number) => base - (value - low) / (high - low) * (base - 27)
  // Reference line is not a fabricated historical observation.
  context.textAlign = 'left'; context.textBaseline = 'middle'
  for (const price of prices) {
    const y = yAt(price)
    context.setLineDash(price === 0 ? [4, 4] : [2, 5]); context.strokeStyle = tint(price === 0 ? .4 : .16)
    context.beginPath(); context.moveTo(plotInset, y); context.lineTo(plotRight, y); context.stroke()
  }
  context.setLineDash([])
  const count = 68, step = (plotRight - plotInset) / (count - 1), size = Math.min(3.4, step * .68)
  const appearance = (column: number) => {
    const distance = origin < 0 ? column / (count - 1) : Math.abs(column - origin) / Math.max(origin, count - 1 - origin, 1)
    const phase = Math.max(0, Math.min(1, (progress - distance * .61) / .39))
    return 1 - (1 - phase) ** 3
  }
  for (let column = 0; column < count; column++) {
    const time = assetColumnTime(from.value, asOf.value, column, count)
    // Display only: hold the nearest earlier value, without changing stored observations.
    const value = assetDisplayValue(data, time, intervalMs.value + 120000)
    if (value === null) continue // No earlier usable observation exists.
    const appear = appearance(column)
    if (!appear) continue
    context.globalAlpha = assetHighlight(time, selectedTime.value)
    const x = plotInset + column * step, top = Math.min(base, yAt(value) + (1 - appear) * 13)
    for (let y = top; y < base; y += 5.1) {
      const depth = (y - top) / Math.max(base - top, 1)
      context.fillStyle = tint((.53 * (1 - depth) ** 1.65 + .015) * appear)
      context.fillRect(x - size / 2, y, size, size)
    }
    context.fillStyle = tint(.84 * appear); context.shadowColor = tint(.4); context.shadowBlur = 6 * appear
    context.fillRect(x - size / 2, top - size / 2, size + .5, size + .5); context.shadowBlur = 0
    if (value !== 0 && column % 8 === 0) { context.fillStyle = tint(.18 * appear); context.fillRect(x, top - 10, 2, 2) }
  }
  // Fixed time columns can miss a jump entirely. Cover its real vertical edge too.
  const vertices = displayLine.value
  for (let i = 1; i < vertices.length; i++) {
    const previous = vertices[i - 1]!, point = vertices[i]!
    if (point.time !== previous.time || point.value === previous.value) continue
    const x = xAt(point.time), appear = appearance((x - plotInset) / step)
    if (!appear) continue
    const top = Math.min(yAt(previous.value!), yAt(point.value!)) + (1 - appear) * 13
    const bottom = Math.max(yAt(previous.value!), yAt(point.value!)) + (1 - appear) * 13
    const segments = Math.max(1, Math.ceil((bottom - top) / 5.1))
    context.globalAlpha = assetHighlight(point.time, selectedTime.value)
    context.fillStyle = tint(.84 * appear)
    for (let row = 0; row <= segments; row++) {
      const y = top + (bottom - top) * row / segments
      context.fillRect(x - size / 2, y - size / 2, size, size)
    }
  }
  context.globalAlpha = 1
  if (progress < 1) return
  // No artificial zero drops or backward extension before the first observation.
  context.strokeStyle = tint(1); context.lineWidth = 1.1
  const line = new Path2D()
  vertices.forEach((point, index) => {
    const x = xAt(point.time), y = yAt(point.value!)
    if (index === 0) line.moveTo(x, y)
    else line.lineTo(x, y)
  })
  context.globalAlpha = selectedTime.value === null ? 1 : .16; context.stroke(line); context.globalAlpha = 1
  if (selectedTime.value !== null) {
    context.save(); context.beginPath(); context.rect(0, 0, xAt(selectedTime.value), height); context.clip(); context.stroke(line); context.restore()
  }
  const annotate = (p: Point, label: string, above: boolean) => {
    if (p.value === null) return
    const x = xAt(p.time), y = yAt(p.value), text = label + ' $' + money(p.value)
    context.globalAlpha = assetHighlight(p.time, selectedTime.value)
    const labelWidth = context.measureText(text).width + 8
    const left = Math.max(10, Math.min(plotRight - labelWidth, x - labelWidth / 2))
    const labelY = above ? Math.max(10, y - 16) : Math.min(height - 7, y + 16)
    context.fillStyle = '#fff'; context.fillRect(left - 3, labelY - 8, labelWidth + 6, 16)
    context.fillStyle = tint(1); context.fillText(text,left+4,labelY)
    context.globalAlpha = 1
  }
  if (extrema.value) {
    annotate(extrema.value.high, locale.text('最高', 'High'), true)
    annotate(extrema.value.low, locale.text('最低', 'Low'), false)
  }
  if (selectedTime.value !== null) {
    const selectedValue = assetDisplayValue(data, selectedTime.value, intervalMs.value + 120000)
    if (selectedValue === null) return
    const x = xAt(selectedTime.value), y = yAt(selectedValue)
    context.strokeStyle = tint(1); context.lineWidth = 1; context.setLineDash([3,3])
    context.beginPath(); context.moveTo(x, 25); context.lineTo(x, base + 3); context.stroke(); context.setLineDash([])
    context.fillStyle = '#fff'; context.strokeStyle = tint(1); context.lineWidth = 2
    context.beginPath(); context.arc(Math.max(4,Math.min(width-4,x)), y, 3, 0, Math.PI*2); context.fill(); context.stroke()
  }
}
function play(origin = -1) {
  cancelAnimationFrame(animation)
  if (selectedTime.value !== null) { draw(1); return }
  if (reduced?.matches) { draw(1, origin); return }
  const start = performance.now()
  const frame = (now: number) => { const progress = Math.min(1, (now - start) / 1050); draw(progress, origin); if (progress < 1 && !disposed) animation = requestAnimationFrame(frame) }
  animation = requestAnimationFrame(frame)
}
function choose(next: string) { stopPointer(); period.value = next; points.value = []; from.value = 0; change.value = null; selected.value = -1; selectedTime.value = null; draw(1); void load(true) }
function selectAt(clientX: number) {
  if (!props.visible || !points.value.length) return
  cancelAnimationFrame(animation)
  const rect = canvas.value!.getBoundingClientRect()
  const time = assetScrubTime(clientX, rect.left + plotInset, rect.width - plotInset * 2, from.value, asOf.value)
  selectedTime.value = time
  selected.value = assetDisplayIndex(points.value, time)
  draw(1)
}
function startPointer(event: PointerEvent) {
  if (!event.isPrimary || event.button !== 0 || !props.visible || !points.value.length) return
  stopPointer(); pointerStart = { x: event.clientX, y: event.clientY, id: event.pointerId }
  if (event.pointerType === 'mouse') { scrubbing = true; canvas.value?.setPointerCapture(event.pointerId); selectAt(event.clientX); return }
  holdTimer = setTimeout(() => {
    if (!pointerStart) return
    scrubbing = true; canvas.value?.setPointerCapture(pointerStart.id); selectAt(pointerStart.x)
  }, 250)
}
function movePointer(event: PointerEvent) {
  if (!pointerStart || pointerStart.id !== event.pointerId) return
  if (!scrubbing) {
    const dx = Math.abs(event.clientX - pointerStart.x), dy = Math.abs(event.clientY - pointerStart.y)
    if (dy > 10 && dy > dx) { stopPointer(); return }
    if (dx < 6) return
    clearTimeout(holdTimer); scrubbing = true; canvas.value?.setPointerCapture(event.pointerId)
  }
  selectAt(event.clientX)
}
function endPointer(event: PointerEvent) {
  if (pointerStart?.id !== event.pointerId) return
  resetSelection()
}
function stopPointer() {
  clearTimeout(holdTimer)
  const id = pointerStart?.id; pointerStart = undefined; scrubbing = false
  if (id !== undefined && canvas.value?.hasPointerCapture(id)) canvas.value.releasePointerCapture(id)
}
function preventScrubScroll(event: TouchEvent) { if (scrubbing && event.cancelable) event.preventDefault() }
function resetSelection() { stopPointer(); selected.value = -1; selectedTime.value = null; draw(1) }
function refreshChart() { if (loading.value) return; refreshTurns.value++; resetSelection(); void load(true) }
function scrubKey(event: KeyboardEvent) {
  if (event.key === 'Escape') { resetSelection(); return }
  if (!['ArrowLeft','ArrowRight','Home','End'].includes(event.key) || !from.value) return
  event.preventDefault()
  const time = event.key === 'Home' ? from.value : event.key === 'End' ? asOf.value : (selectedTime.value ?? asOf.value) + (event.key === 'ArrowLeft' ? -intervalMs.value : intervalMs.value)
  const rect = canvas.value!.getBoundingClientRect()
  selectAt(rect.left + plotInset + (time - from.value) / (asOf.value - from.value) * (rect.width - plotInset * 2))
}
function resume() { if (!document.hidden && !activeRequest && !pointerStart) void load() }
watch(() => locale.locale, () => draw(1))
watch(() => props.visible, () => { resetSelection(); play() })
onMounted(() => {
  reduced = matchMedia('(prefers-reduced-motion: reduce)')
  observer = new ResizeObserver(() => {
    const element = canvas.value; if (!element) return
    width = element.getBoundingClientRect().width
    const scale = Math.min(devicePixelRatio || 1, 2)
    element.width = Math.round(width * scale); element.height = height * scale
    element.getContext('2d')?.setTransform(scale, 0, 0, scale, 0, 0); draw(1)
  })
  observer.observe(canvas.value!); void load(true)
  canvas.value!.addEventListener('touchmove', preventScrubScroll, { passive: false })
  timer = setInterval(resume, 10000); document.addEventListener('visibilitychange', resume); window.addEventListener('focus', resume)
})
onBeforeUnmount(() => { disposed = true; generation++; stopPointer(); canvas.value?.removeEventListener('touchmove', preventScrubScroll); observer?.disconnect(); clearInterval(timer); cancelAnimationFrame(animation); document.removeEventListener('visibilitychange', resume); window.removeEventListener('focus', resume) })
</script>

<template>
  <section class="pixel-history" @click.stop>
    <div class="chart-toolbar">
      <div class="delta" v-if="visible" aria-live="polite"><span>{{ incomeLabel }}</span><b v-if="change !== null" :class="{ negative: change < 0 }">{{ change >= 0 ? '+' : '−' }}${{ money(Math.abs(change)) }} ({{ percentLabel }})</b><b v-else>—</b></div>
      <button class="refresh-button" type="button" :disabled="loading" :aria-label="locale.t('update')" @click="refreshChart">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" :style="{ transform: `rotate(${refreshTurns * 360}deg)` }"><path d="M20 7v5h-5M20 12a8 8 0 1 0-2.34 5.66" /></svg>
      </button>
    </div>
    <div class="chart-wrap" :style="{ '--chart-rgb': chartRgb }">
      <canvas ref="canvas" role="img" tabindex="0" :aria-label="locale.text('長按拖動查看金額；方向鍵選擇時間，Esc恢復。', 'Hold and drag to inspect. Arrow keys select time; Escape resets.')" @pointerdown="startPointer" @pointermove="movePointer" @pointercancel="resetSelection" @lostpointercapture="resetSelection" @pointerup="endPointer" @contextmenu.prevent @keydown="scrubKey" @keydown.enter="refreshChart" />
      <div v-if="!visible || !points.length" class="empty">{{ !visible ? (locale.text('資產已隱藏', 'Assets hidden')) : loading ? (locale.text('載入中…', 'Loading…')) : failed ? (locale.text('載入失敗，請點刷新', 'Unable to load. Tap refresh.')) : (locale.text('正在累積真實資產記錄…', 'Recording real history…')) }}</div>
      <div v-if="visible && selectedTime !== null" class="point-detail" role="status">{{ selectedDate }} · {{ selectedAmount }}<span v-if="selectedCarried"> · {{ locale.text('沿用歷史值，來源：', 'Carried forward, observed: ') }}{{ selectedSourceTime }}</span></div>
    </div>
    <div class="periods"><button v-for="(item, i) in periods" :key="item" type="button" :aria-pressed="period === item" @click="choose(item)">{{ labels[i] }}</button></div>
    <div v-if="visible && valuationStale" class="status" role="status">{{ locale.text('估值不可用', 'Valuation unavailable') }}</div>
    <div v-if="failed && points.length" class="status" role="status">{{ locale.text('同步失敗 · 顯示上次資料', 'Sync failed · showing last update') }}</div>
  </section>
</template>

<style scoped>
canvas{user-select:none;-webkit-user-select:none;-webkit-touch-callout:none}
.point-detail{max-width:95%;white-space:normal!important;text-align:center}
.pixel-history{margin-top:2px;min-width:0}.chart-toolbar{display:flex;align-items:center;gap:8px}.delta{flex:1;min-width:0}.delta{display:flex;flex-wrap:wrap;gap:7px;font-size:11px;color:#8d988b}.delta b{color:#5c9936;font-weight:600;font-variant-numeric:tabular-nums}.delta b.negative{color:#b65072}
.refresh-button{display:grid;place-items:center;flex:0 0 32px;width:32px;height:32px;margin-left:auto;padding:6px;border:0;border-radius:8px;background:transparent;color:#6e9e54;cursor:pointer}.refresh-button:disabled{opacity:.5;cursor:default}.refresh-button svg{display:block;width:20px;height:20px;transition:transform .6s ease-in-out}.refresh-button:active{background:#f3f5f1}@media(prefers-reduced-motion:reduce){.refresh-button svg{transition:none}}
.chart-wrap{position:relative;height:180px}canvas{display:block;width:100%;height:180px;touch-action:pan-y}.empty{position:absolute;inset:0;display:grid;place-items:center;background:#fff;color:#899687;font-size:12px}.point-detail{position:absolute;top:3px;left:50%;transform:translateX(-50%);white-space:nowrap;background:#fff;border:1px solid rgba(var(--chart-rgb),.25);border-radius:8px;padding:6px 9px;font-size:11px;box-shadow:0 5px 15px rgba(var(--chart-rgb),.1);pointer-events:none}
.periods{display:grid;grid-template-columns:repeat(4,1fr);gap:3px;margin-top:2px}.periods button{min-height:36px;border:0;border-radius:9px;background:transparent;color:#7d8a7a;font-size:11px;cursor:pointer}.periods button[aria-pressed=true]{background:#e6f0de;color:#4f852d;font-weight:700}.status{margin-top:10px;color:#929d8e;font-size:10px;line-height:1.5}button:focus-visible,canvas:focus-visible{outline:2px solid #73b100;outline-offset:2px}
</style>
