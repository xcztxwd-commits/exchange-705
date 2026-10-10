<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { can } from '@/utils/access'
import { createRequestKey } from '@/utils/requestKey'
import { commandScope } from '@/utils/aiControlCommand'
import { readSession } from '@/utils/adminSession'
import { aggregateHistory, localMinute, minuteCandidates, restoreRange, type HistoryCandle } from '@/utils/historyRestore'

const props = defineProps<{ symbolId: number; label: string; precision: number; initialRange?: { from: number; to: number } }>()
const tab = ref('restore'), timezone = ref('Asia/Singapore'), period = ref('1m'), interaction = ref('select'), after = ref(false)
const width = computed(() => ({ '1m': 60000, '5m': 300000, '15m': 900000, '1h': 3600000 }[period.value] || 60000))
const closed = () => Math.floor(Date.now() / 60000) * 60000 - 60000
const from = ref(closed() - 4 * 60000), to = ref(closed())
const host = ref<HTMLDivElement>(), error = ref(''), busy = ref(false), loading = ref(false), sourceIdentity = ref('')
const before = ref<HistoryCandle[]>([]), originals = ref<HistoryCandle[]>([]), records = ref<any[]>([]), preview = ref<any>()
const gapPeriod = ref('1m'), gapReport = ref<any>(), gapBusy = ref(false)
let gapTimer: ReturnType<typeof setTimeout> | undefined, gapPolls = 0
const gapScope = () => JSON.stringify([generation, props.symbolId, from.value, to.value, gapPeriod.value, timezone.value, commandScope(readSession(sessionStorage))])
const gapState = (s: string) => ({ queued: '待补', running: '执行中', recoverable: '可安全补齐', complete: '已完整', blocked: '已停止', queue_full: '队列已满，请重试' }[s] || s)
const gapReason = (s: string) => ({ missing_source: '缺少源记录', protected_control_history: '控盘历史保护', missing_control_samples: '控盘采样缺失', control_samples_unverified: '控盘采样未验证', protected_control_hold: 'hold 保护', protected_publication: '发布保护', protected_mixed_minute: '混合分钟保护', frozen_snapshot: '封存快照', sealed_history: '已封存历史', protected_history_restore: '恢复或撤销快照保护', simulation_history: '模拟会话及冻结前缀', projection_before_initial: '不满足 SOURCE 投影资格', existing_partial_or_invalid: '已有源记录不完整或无效', source_conflict: '来源冲突', source_route_conflict: '源路由冲突', upstream_no_data: '上游无数据', source_fetch_or_write_failed: '补采或写入失败，重试已耗尽' }[s] || s)
const ambiguous = ref<{ side: 'from' | 'to'; options: { timestamp: number; offset: string }[] }>()
const active = computed(() => records.value.some(row => ['ACCEPTED', 'RUNNING'].includes(row.state)))
const count = computed(() => Math.floor((to.value - from.value) / 60000) + 1)
const api = () => `/admin/ai-control/${props.symbolId}/history-restore`
const stamp = (n: number) => localMinute(n, timezone.value).replace('T', ' ')
const price = (n: number) => Number(n).toFixed(props.precision)
const stateLabel = (s: string) => ({ ACCEPTED: '已受理', RUNNING: '执行中', COMPLETED: '已完成', FAILED: '失败，保留已提交部分', REJECTED: '预览失效，未执行' }[s] || s)
const storageKey = () => `history-source-restore:${commandScope(readSession(sessionStorage))}:${props.symbolId}`
type Pending = { requestKey: string; previewToken: string; undoOf?: string }
const pending = ref<Pending>()
const details = ref<any>()
async function showDetails(id: string) {
  if (!can('ai_control:view') || disposed) return
  const version = generation
  try { const result = await request.get(api() + `/jobs/${id}`); if (version === generation) details.value = result }
  catch (e: any) { if (version === generation) error.value = e.message || '详情加载失败' }
}
let chart: echarts.ECharts | undefined, resize: ResizeObserver | undefined, generation = 0, disposed = false, timer: ReturnType<typeof setTimeout> | undefined
let windowFrom = closed() - 239 * 60000, windowTo = closed(), drag: { pointer: number; index: number; boundary?: 'from' | 'to' } | undefined
const bars = computed(() => aggregateHistory(after.value && preview.value?.state === 'READY'
  ? before.value.map(row => row.timestamp >= from.value && row.timestamp <= to.value ? originals.value.find(source => source.timestamp === row.timestamp) || row : row)
  : before.value, width.value))
const lastSelected = () => bars.value.reduce((found, row, index) => row.timestamp <= to.value ? index : found, -1)
function invalidate() { preview.value = undefined; after.value = false; error.value = ''; draw() }
function choose(side: 'from' | 'to', text: string) {
  invalidate()
  const options = minuteCandidates(text, timezone.value)
  if (!options.length) { error.value = '该时间不存在，请检查日期或夏令时'; return }
  if (options.length > 1) { ambiguous.value = { side, options }; return }
  setBoundary(side, options[0]!.timestamp)
}
function setBoundary(side: 'from' | 'to', timestamp: number) { (side === 'from' ? from : to).value = timestamp; ambiguous.value = undefined; invalidate() }
function draw() {
  if (!chart || disposed) return
  const source = new Map(aggregateHistory(originals.value, width.value).map(row => [row.timestamp, row.close_price]))
  const labels = bars.value.map(row => stamp(row.timestamp))
  chart.setOption({ animation: false, tooltip: { trigger: 'axis' }, legend: { data: ['当前历史', '原始源收盘'], top: 0 },
    grid: { left: 65, right: 30, top: 40, bottom: 82 }, xAxis: { type: 'category', data: labels, boundaryGap: true, axisLabel: { hideOverlap: true } }, yAxis: { scale: true },
    dataZoom: [{ type: 'inside', disabled: interaction.value === 'select', filterMode: 'none' }, { type: 'slider', bottom: 15, height: 28, filterMode: 'none' }],
    series: [{ name: '当前历史', type: 'candlestick', data: bars.value.map(r => [r.open_price, r.close_price, r.low_price, r.high_price]),
      itemStyle: { color: '#22a596', color0: '#ee5c62', borderColor: '#22a596', borderColor0: '#ee5c62' } },
    { name: '原始源收盘', type: 'line', showSymbol: false, connectNulls: false, lineStyle: { color: '#4a78d0', type: 'dashed', width: 1.5 }, data: bars.value.map(row => source.get(row.timestamp) ?? null) }] })
  selectionGraphic()
}
function selectionGraphic() {
  if (!chart || disposed || !bars.value.length) return
  const first = bars.value.findIndex(row => row.timestamp + width.value > from.value), last = lastSelected()
  const left = Math.max(65, pixelAt(first - .45)), right = Math.min(chart.getWidth() - 30, pixelAt(last + .45))
  const visible = first >= 0 && last >= first && right >= left
  chart.setOption({ graphic: [
    { id: 'restore-area', type: 'rect', silent: true, invisible: !visible, shape: { x: left, y: 40, width: right - left, height: chart.getHeight() - 122 }, style: { fill: 'rgba(133,189,0,.12)' }, z: 1 },
    ...[left, right].map((x, i) => ({ id: `restore-boundary-${i}`, type: 'rect', silent: true, invisible: !visible, shape: { x: x - 2, y: 40, width: 4, height: chart!.getHeight() - 122 }, style: { fill: '#85bd00' }, z: 5 })),
  ] })
}
function pixelAt(index: number) {
  const first = Number(chart!.convertToPixel({ xAxisIndex: 0 }, 0))
  const spacing = bars.value.length > 1 ? Number(chart!.convertToPixel({ xAxisIndex: 0 }, 1)) - first : chart!.getWidth() - 95
  return first + index * spacing
}
function indexAt(event: PointerEvent) {
  if (!chart || !host.value) return NaN
  return Number(chart.convertFromPixel({ xAxisIndex: 0 }, Math.max(65, Math.min(host.value.clientWidth - 30, event.clientX - host.value.getBoundingClientRect().left))))
}
function start(event: PointerEvent) {
  if (busy.value || interaction.value !== 'select' || !chart || !host.value || event.button !== 0 || !bars.value.length) return
  const rect = host.value.getBoundingClientRect(), point = [event.clientX - rect.left, event.clientY - rect.top]
  if (!chart.containPixel({ gridIndex: 0 }, point)) return
  const index = indexAt(event), a = bars.value.findIndex(row => row.timestamp + width.value > from.value), b = lastSelected()
  const boundary = a >= 0 && Math.abs(point[0]! - pixelAt(a - .45)) < 10 ? 'from' : b >= 0 && Math.abs(point[0]! - pixelAt(b + .45)) < 10 ? 'to' : undefined
  drag = { pointer: event.pointerId, index, boundary }; host.value.setPointerCapture(event.pointerId); event.preventDefault(); move(event)
}
function move(event: PointerEvent) {
  if (!drag || event.pointerId !== drag.pointer) return
  const index = indexAt(event)
  if (!Number.isFinite(index)) return
  const selection = restoreRange(bars.value, drag.index, index, width.value)
  if (drag.boundary) {
    const selected = restoreRange(bars.value, index, index, width.value)
    ;(drag.boundary === 'from' ? from : to).value = drag.boundary === 'from' ? selected.from : selected.to
  } else { from.value = selection.from; to.value = selection.to }
  invalidate()
}
function finish(event: PointerEvent) { if (drag?.pointer === event.pointerId) { move(event); drag = undefined; if (host.value?.hasPointerCapture(event.pointerId)) host.value.releasePointerCapture(event.pointerId) } }
function keyboard(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight'].includes(event.key) || busy.value) return
  event.preventDefault(); const shift = event.key === 'ArrowLeft' ? -60000 : 60000
  if (event.shiftKey) to.value += shift; else { from.value += shift; to.value += shift } invalidate()
}
async function loadChart(earlier = false) {
  if (!can('ai_control:view') || disposed) return
  const version = generation, endpoint = api(); loading.value = true
  try {
    if (earlier) { windowTo = windowFrom - 60000; windowFrom = windowTo - 239 * 60000 }
    const result = await request.get(endpoint + '/chart', { params: { from: windowFrom, to: windowTo, timezone: timezone.value } }) as any
    if (version !== generation || disposed) return
    before.value = earlier ? [...result.before, ...before.value] : result.before
    originals.value = earlier ? [...result.source, ...originals.value] : result.source
    sourceIdentity.value = result.sourceIdentity; await nextTick(); draw()
  } catch (e: any) { if (version === generation) error.value = e.message || '历史加载失败' }
  finally { if (version === generation) loading.value = false }
}
async function locateLatest() {
  if (busy.value || loading.value) return
  to.value = closed(); from.value = to.value - 4 * 60000
  windowTo = to.value; windowFrom = windowTo - 239 * 60000
  ambiguous.value = undefined; drag = undefined; invalidate()
  await loadChart()
  chart?.dispatchAction({ type: 'dataZoom', start: 0, end: 100 })
}
async function loadRecords() {
  if (!can('ai_control:view') || disposed) return
  const version = generation
  try { const result = await request.get(api() + '/jobs') as any[]; if (version === generation && !disposed) records.value = result }
  catch (e: any) { if (version === generation) error.value = e.message || '记录加载失败' }
}
function validRange() {
  if (ambiguous.value) throw new Error('请选择明确的 UTC 偏移')
  if (from.value > to.value || count.value < 1 || count.value > 1440 || to.value > closed()) throw new Error('请选择已结束的分钟，单次 1～1440 分钟；结束时间包含该分钟')
  return { from: from.value, to: to.value, timezone: timezone.value }
}
async function checkGaps(repair = false, polling = false) {
  if (gapBusy.value || disposed || !can(repair ? 'ai_control:restore_history' : 'ai_control:view')) return
  const scope = gapScope(), endpoint = api(); gapBusy.value = true; error.value = ''
  if (!polling) gapPolls = 0
  clearTimeout(gapTimer)
  try {
    const range = { ...validRange(), period: gapPeriod.value }
    const result = await (repair ? request.post(endpoint + '/gaps/repair', range) : request.get(endpoint + '/gaps', { params: range })) as any
    if (scope !== gapScope() || disposed) return
    gapReport.value = result
    if (result.pending && ++gapPolls <= 12) {
      const retryAt = Math.max(0, ...result.windows.map((row: any) => Number(row.retryAt) || 0))
      gapTimer = setTimeout(() => { if (scope === gapScope()) void checkGaps(false, true) }, Math.max(1500, retryAt - Date.now()))
    }
    if (repair && !result.pending) await loadChart()
    if (polling && !result.pending && result.inserted > 0) await loadChart()
  } catch (e: any) { if (scope === gapScope()) error.value = e.message || '缺口检查失败' }
  finally { gapBusy.value = false }
}
async function check(backfill = false) {
  if (busy.value || disposed || !can('ai_control:restore_history')) return
  const version = generation, endpoint = api(); busy.value = true; error.value = ''
  try {
    const range = validRange()
    if (backfill) await request.post(endpoint + '/source', range, { timeout: 60000 })
    if (version !== generation || disposed || !can('ai_control:restore_history')) return
    const result = await request.post(endpoint + '/preview', range) as any
    if (version !== generation || disposed) return
    preview.value = result
    windowFrom = Math.max(946684800000, from.value - 5 * 60000); windowTo = Math.min(closed(), to.value + 5 * 60000)
    // A maximum-sized range uses its exact 1440 slots; no extra context beyond the API bound.
    if ((windowTo - windowFrom) / 60000 >= 1440) { windowFrom = from.value; windowTo = to.value }
    await loadChart(); draw()
  } catch (e: any) { if (version === generation) error.value = e.message || '预览失败' }
  finally { if (version === generation) busy.value = false }
}
async function queryPending() {
  if (!can('ai_control:view') || disposed) return
  const item = pending.value, version = generation
  if (!item) return
  const result = await request.get(api() + '/jobs', { params: { requestKey: item.requestKey } }) as any
  if (version !== generation || disposed) return
  if (result.state === 'NOT_FOUND') { error.value = '尚未查询到受理结果，可用原请求重试'; return }
  if (!['ACCEPTED', 'RUNNING'].includes(result.state)) {
    sessionStorage.removeItem(storageKey()); pending.value = undefined; preview.value = undefined
    if (result.state === 'COMPLETED') { ElMessage.success('历史源恢复任务已完成'); await loadChart() }
    else error.value = result.error || '任务失败，请查询已提交部分'
  }
  await loadRecords()
}
async function sendOriginal() {
  const item = pending.value, version = generation
  if (!item || disposed || !can(item.undoOf ? 'ai_control:undo_history_restore' : 'ai_control:restore_history')) return
  busy.value = true
  try { await request.post(api() + (item.undoOf ? `/jobs/${item.undoOf}/undo` : '/jobs'), item); if (version === generation) await queryPending() }
  catch (e: any) { if (version === generation) error.value = `${e.message || '提交未确认'}；原请求已保留，请查询原请求` }
  finally { if (version === generation) busy.value = false }
}
async function confirm(undoOf?: string) {
  const version = generation
  const permission = undoOf ? 'ai_control:undo_history_restore' : 'ai_control:restore_history'
  if (pending.value || active.value || disposed || !can(permission)) return
  try {
    let token: string, total: number, start = from.value, end = to.value
    if (undoOf) { const result = await request.post(api() + `/jobs/${undoOf}/undo-preview`) as any; token = result.previewToken; total = result.total; start = result.from; end = result.to }
    else { if (preview.value?.state !== 'READY') return; token = preview.value.previewToken; total = preview.value.changed }
    if (version !== generation || disposed || !can(permission)) return
    await ElMessageBox.confirm(`${props.label} · ${stamp(start)} ～ ${stamp(end)}（${timezone.value}）。${undoOf ? '撤销' : '恢复'} ${total} 根历史分钟？`, undoOf ? '确认撤销历史源恢复' : '确认恢复历史源数据', { confirmButtonText: `确认${undoOf ? '撤销' : '恢复'} ${total} 根`, cancelButtonText: '返回检查', type: 'warning' })
    if (version !== generation || disposed || !can(permission)) return
    const item = { requestKey: createRequestKey(), previewToken: token, undoOf }
    sessionStorage.setItem(storageKey(), JSON.stringify(item)); pending.value = item; await sendOriginal()
  } catch (e: any) { if (version === generation && e !== 'cancel' && e !== 'close') error.value = e.message || '确认失败' }
}
async function retry(row: any) {
  if (disposed || !can(row.kind === 'UNDO' ? 'ai_control:undo_history_restore' : 'ai_control:restore_history')) return
  try { await request.post(api() + `/jobs/${row.id}/${row.kind === 'UNDO' ? 'undo-retry' : 'retry'}`); await loadRecords() }
  catch (e: any) { error.value = e.message || '重试失败' }
}
function reset() {
  gapReport.value = undefined; clearTimeout(gapTimer); gapPolls = 0
  ++generation; preview.value = undefined; pending.value = undefined; details.value = undefined; records.value = []; before.value = []; originals.value = []; error.value = ''; busy.value = false; loading.value = false; ambiguous.value = undefined; drag = undefined
  from.value = props.initialRange?.from ?? closed() - 4 * 60000; to.value = props.initialRange?.to ?? closed()
  windowFrom = from.value - Math.max(0, 240 - count.value) * 60000; windowTo = to.value
  try { const saved = sessionStorage.getItem(storageKey()); if (saved) pending.value = JSON.parse(saved) } catch { error.value = '原请求读取失败，请先查询恢复记录' }
  void loadChart(); void loadRecords(); if (pending.value) void queryPending().catch(e => { error.value = e.message })
}
watch(() => props.symbolId, reset)
watch(() => props.initialRange, reset)
watch([period, interaction, after], draw)
watch(timezone, invalidate)
watch([from, to, gapPeriod, timezone], () => { gapReport.value = undefined; clearTimeout(gapTimer) })
async function poll() {
  if (disposed) return
  try { if (pending.value) await queryPending(); else if (active.value) { await loadRecords(); if (!active.value) await loadChart() } } catch (e: any) { error.value = e.message || '任务查询失败' }
  if (!disposed) timer = setTimeout(poll, 1500)
}
onMounted(() => { chart = echarts.init(host.value!); chart.on('datazoom', selectionGraphic); resize = new ResizeObserver(() => { chart?.resize(); selectionGraphic() }); resize.observe(host.value!); reset(); void poll() })
onUnmounted(() => { disposed = true; ++generation; clearTimeout(timer); clearTimeout(gapTimer); resize?.disconnect(); chart?.dispose() })
</script>

<template>
  <section class="history-restore">
    <el-tabs v-model="tab"><el-tab-pane label="历史源恢复" name="restore" /><el-tab-pane label="恢复记录" name="records" /></el-tabs>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="pending" title="原请求已保留。刷新或超时后查询同一请求。" type="info" :closable="false" />
    <div v-if="pending" class="toolbar"><el-button v-permission="'ai_control:view'" :disabled="busy" @click="queryPending">查询原请求</el-button><el-button v-permission="pending?.undoOf ? 'ai_control:undo_history_restore' : 'ai_control:restore_history'" :disabled="busy" @click="sendOriginal">重试原请求</el-button><span>{{ pending.requestKey }}</span></div>
    <div v-show="tab === 'restore'">
      <div class="title"><div><h3>{{ label }} · 历史源恢复</h3><p>{{ sourceIdentity || '读取原始源…' }}</p></div><span class="scope">历史分钟 · 结束时间包含该分钟</span></div>
      <div class="range-inputs">
        <label>开始时间<input aria-label="恢复开始时间" type="datetime-local" :value="localMinute(from, timezone)" :disabled="busy" @change="choose('from', ($event.target as HTMLInputElement).value)"></label>
        <div class="range-field"><span>结束时间</span><div class="range-end-controls"><input aria-label="恢复结束时间" type="datetime-local" :value="localMinute(to, timezone)" :disabled="busy" @change="choose('to', ($event.target as HTMLInputElement).value)"><el-button v-permission="'ai_control:view'" :disabled="busy || loading" @click="locateLatest">定位最新</el-button></div></div>
        <label>显示时区<el-select v-model="timezone" aria-label="显示时区"><el-option v-for="zone in ['Asia/Singapore', 'Asia/Shanghai', 'UTC', 'America/New_York', 'Europe/London']" :key="zone" :label="zone" :value="zone" /></el-select></label>
      </div>
      <div v-if="ambiguous" class="toolbar"><span>该时间重复，请选择 UTC 偏移</span><el-button v-permission="'ai_control:view'" v-for="option in ambiguous.options" :key="option.timestamp" @click="setBoundary(ambiguous.side, option.timestamp)">{{ option.offset }}</el-button></div>
      <div class="toolbar"><el-radio-group v-model="period" size="small"><el-radio-button v-for="p in ['1m','5m','15m','1h']" :key="p" :value="p">{{ p }}</el-radio-button></el-radio-group><el-radio-group v-model="interaction" size="small"><el-radio-button value="select">框选</el-radio-button><el-radio-button value="pan">平移</el-radio-button></el-radio-group><el-button v-permission="'ai_control:view'" :loading="loading" @click="loadChart(true)">加载更早</el-button><el-button v-permission="'ai_control:view'" :loading="loading" @click="loadChart()">刷新图表</el-button><el-switch v-permission="'ai_control:view'" v-model="after" :disabled="preview?.state !== 'READY'" active-text="恢复后预览" /></div>
      <div ref="host" class="chart" :class="{ selecting: interaction === 'select' }" tabindex="0" aria-label="历史 K 线选区，方向键移动一分钟，Shift 加方向键调整结束时间" @pointerdown="start" @pointermove="move" @pointerup="finish" @pointercancel="drag = undefined" @keydown="keyboard" />
      <div class="selection"><span>{{ stamp(from) }} ～ {{ stamp(to) }} · {{ count }} 分钟</span><el-button v-permission="'ai_control:restore_history'" type="primary" :loading="busy" :disabled="!symbolId || active || !!pending || !!ambiguous" @click="check()">预览恢复</el-button></div>
      <p class="hint">点选单根，拖动选择多根；拖动绿色边界调整范围。大周期选择会展开为完整分钟。单次最多 1440 分钟。</p>
      <div class="gap-check">
        <div class="toolbar"><strong>历史缺口</strong><el-select v-model="gapPeriod" aria-label="缺口检查周期" style="width:100px"><el-option v-for="p in ['1m','5m','15m','30m','1h']" :key="p" :value="p" :label="p" /></el-select><el-button v-permission="'ai_control:view'" :loading="gapBusy" :disabled="busy || loading || !!ambiguous" @click="checkGaps()">缺口检查</el-button><el-button v-permission="'ai_control:restore_history'" :loading="gapBusy" :disabled="busy || !gapReport?.retryable" @click="checkGaps(true)">安全补齐</el-button></div>
        <p class="hint">按所选区间独立检查原生周期。安全补齐只新增允许补采的源槽位；受保护缺口停止，不执行恢复或撤销。</p>
        <template v-if="gapReport">
          <p>{{ gapReport.sourceIdentity }} · {{ gapReport.period }} · {{ stamp(gapReport.from) }} ～ {{ stamp(gapReport.to) }}</p>
          <p class="gap-summary">源缺失 {{ gapReport.sourceMissing }} · 展示缺失 {{ gapReport.displayMissing }} · 可补 {{ gapReport.recoverable }} · 受保护 {{ gapReport.protected }} · 最近新增 {{ gapReport.inserted }} · 源修订 {{ gapReport.sourceInputRevision }}</p>
          <el-alert v-if="gapReport.protected" title="受保护缺口保留，自动和手动均不能绕过保护" type="warning" :closable="false" />
          <admin-table table-key="HistorySourceRestore.gaps" :data="gapReport.windows" max-height="300"><el-table-column column-key="window" label="检查窗口" min-width="185"><template #default="{ row }">{{ stamp(row.from) }}<br>{{ stamp(row.to) }}</template></el-table-column><el-table-column column-key="state" label="状态" min-width="130"><template #default="{ row }">{{ gapState(row.queueState) }}<br>最近新增 {{ row.inserted }}</template></el-table-column><el-table-column column-key="missing" label="源 / 展示缺失" min-width="120"><template #default="{ row }">{{ row.sourceMissing }} / {{ row.displayMissing }}</template></el-table-column><el-table-column column-key="reason" label="原因" min-width="210"><template #default="{ row }">{{ [...new Set(row.gaps.map((gap: any) => gapReason(gap.reason)))].join('、') || '完整' }}</template></el-table-column></admin-table>
        </template>
      </div>
      <el-alert v-if="preview?.state === 'NO_CHANGE'" title="所选区间已经是原始源，无需恢复" type="success" :closable="false" />
      <el-alert v-if="preview?.state === 'MISSING_SOURCE'" :title="`缺少 ${preview.missing.length} 根完整原始源，暂不可恢复`" type="warning" :closable="false" />
      <div v-if="preview?.state === 'MISSING_SOURCE'" class="toolbar"><span>{{ preview.missing.slice(0, 8).map(stamp).join('、') }}{{ preview.missing.length > 8 ? '…' : '' }}</span><el-button v-permission="'ai_control:restore_history'" :loading="busy" @click="check(true)">补采原始源并重新预览</el-button></div>
      <div v-if="preview?.state === 'READY'" class="preview">
        <h4>原始源齐全 · {{ preview.changed }} 根需要恢复</h4><p>{{ preview.sourceIdentity }} · 预览有效 5 分钟</p>
        <admin-table table-key="HistorySourceRestore.differences" :data="preview.differences" max-height="280"><el-table-column column-key="timestamp" label="分钟" min-width="180"><template #default="{ row }">{{ stamp(row.timestamp) }}</template></el-table-column><el-table-column v-for="[key, title] in [['open_price','开'],['high_price','高'],['low_price','低'],['close_price','收']]" :key="key" :column-key="key" :label="`${title}：当前 / 原始源`" min-width="150"><template #default="{ row }">{{ price(row.before[key]) }} / <strong>{{ price(row.source[key]) }}</strong></template></el-table-column></admin-table>
        <p v-if="preview.changed > 200" class="hint">显示前 200 根对比，执行涵盖全部 {{ preview.changed }} 根。</p>
        <el-button v-permission="'ai_control:restore_history'" type="primary" :loading="busy" :disabled="active || !!pending" @click="confirm()">确认恢复 {{ preview.changed }} 根</el-button>
      </div>
    </div>
    <div v-if="tab === 'records'"><div class="toolbar"><h3>恢复记录</h3><el-button v-permission="'ai_control:view'" @click="loadRecords">刷新</el-button></div><admin-table table-key="HistorySourceRestore.records" :data="records" empty-text="暂无历史源恢复记录"><el-table-column column-key="range" label="区间" min-width="230"><template #default="{ row }">{{ localMinute(row.from, row.timezone).replace('T', ' ') }}<br>{{ localMinute(row.to, row.timezone).replace('T', ' ') }}<br>{{ row.timezone }}</template></el-table-column><el-table-column column-key="progress" label="进度" min-width="120"><template #default="{ row }">{{ row.completed }} / {{ row.total }}<br>{{ stateLabel(row.state) }}<p v-if="row.error">{{ row.error }}</p></template></el-table-column><el-table-column prop="actorId" label="操作人" width="90" /><el-table-column column-key="createdAt" label="操作时间" min-width="170"><template #default="{ row }">{{ stamp(row.createdAt) }}</template></el-table-column><el-table-column column-key="actions" label="操作" min-width="160"><template #default="{ row }"><el-button v-permission="'ai_control:view'" @click="showDetails(row.id)">详情</el-button><el-button v-permission="'ai_control:undo_history_restore'" v-if="row.kind === 'RESTORE' && row.completed > 0 && !['ACCEPTED','RUNNING'].includes(row.state)" :disabled="busy || active || !!pending" @click="confirm(row.id)">撤销</el-button><el-button v-permission="row.kind === 'UNDO' ? 'ai_control:undo_history_restore' : 'ai_control:restore_history'" v-if="row.state === 'FAILED'" @click="retry(row)">继续原任务</el-button><span v-if="row.kind === 'UNDO'">撤销记录</span></template></el-table-column><el-table-column column-key="sourceIdentity" label="源与快照编号" min-width="250"><template #default="{ row }">{{ row.sourceIdentity }}<br>{{ row.id }}</template></el-table-column></admin-table></div>
  </section>
  <el-dialog :model-value="!!details" title="恢复快照与校验记录" width="90%" @close="details = undefined"><p>任务 {{ details?.id }} · 已提交 {{ details?.completed }} / {{ details?.total }}；最多展示前 200 根快照。</p><admin-table table-key="HistorySourceRestore.snapshots" :data="details?.snapshots" max-height="500"><el-table-column column-key="timestamp" label="分钟" min-width="170"><template #default="{ row }">{{ stamp(row.timestamp) }}</template></el-table-column><el-table-column prop="version" label="提交版本" width="110" /><el-table-column column-key="lowPrice" label="恢复前 / 目标低价" min-width="160"><template #default="{ row }">{{ price(row.before.low_price) }} / {{ price(row.source.low_price) }}</template></el-table-column><el-table-column prop="checksum" label="快照 SHA-256" min-width="320" /></admin-table></el-dialog>
</template>
<style scoped>
.range-field{display:flex;flex-direction:column;gap:8px;font-size:13px;color:#6b7280;min-width:0}.range-end-controls{display:flex;align-items:center;gap:8px}.range-end-controls input{flex:1;min-width:0}.range-end-controls .el-button{flex-shrink:0;height:38px}
.history-restore{padding-top:12px}.title,.selection,.toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin:16px 0}h3,h4,p{margin:8px 0}.title p,.hint,.scope{color:#6b7280;font-size:13px}.range-inputs{display:grid;grid-template-columns:1fr 1fr 1fr;gap:16px}.range-inputs label{display:flex;flex-direction:column;gap:8px;font-size:13px;color:#6b7280}.range-inputs input{height:38px;width:100%;box-sizing:border-box;padding:8px 12px;border:1px solid #dcdfe6;border-radius:6px;font:inherit;color:#1f1f1f;background:white}.chart{height:410px;border:1px solid #e5e7eb;border-radius:10px;touch-action:pan-y}.chart.selecting{cursor:crosshair;touch-action:none}.chart:focus-visible{outline:2px solid #85bd00}.preview{padding:16px;background:#f8fbf3;border:1px solid #dcebc7;border-radius:10px}.preview strong{color:#519400}.preview .el-button{margin-top:16px}@media(max-width:700px){.range-inputs{grid-template-columns:1fr}.chart{height:340px}.title .scope{display:none}.selection{align-items:flex-start}.toolbar{justify-content:flex-start}}
</style>
