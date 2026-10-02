<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { createRequestKey } from '@/utils/requestKey'
import { displaySymbol } from '@/utils/displaySymbol'

type SymbolItem = { id: number; symbol: string; name?: string; displayName?: string; category?: string; sourceCategory?: string; quoteCurrency?: string; pricePrecision: number; isEnabled: boolean }
const symbolLabel = (item: SymbolItem) => {
  const label = displaySymbol(item)
  return /=X$/i.test(item.symbol) || item.category === 'Forex' || item.sourceCategory === 'Forex' ? label : item.name && item.name !== label ? `${label} (${item.name})` : label
}
type RecoveryOptions = { autoRestore: boolean; restoreMode: 'GRADUAL' | 'QUICK'; restoreDurationSeconds: number; restoreIntensity: number; restoreRandomOscillation: boolean; autoReplaceHistory: boolean }
const defaultRecovery = (): RecoveryOptions => ({ autoRestore: false, restoreMode: 'GRADUAL', restoreDurationSeconds: 10, restoreIntensity: 5, restoreRandomOscillation: true, autoReplaceHistory: true })
const recovery = ref<RecoveryOptions>(defaultRecovery())
type ControlStatus = Partial<RecoveryOptions> & {
  canStart?: boolean; sourceAvailable?: boolean; controlState?: string; startSource?: string; sourceTime?: number; holding?: boolean
  startBasis?: { source: string; timestamp: number; price: number }
  virtualTrading: boolean; randomMarketEnabled: boolean; randomMarketBasePrice: number | null
  id: number; enabled: boolean; running: boolean; restoring: boolean; available: boolean; randomOscillation: boolean
  rawPrice: number | null; currentPrice: number | null; offset: number
  startPrice: number | null; targetPrice: number | null; durationSeconds: number | null
  intensity: number | null; startedAt: number | null; completedAt: number | null; remainingSeconds: number
  algorithmVersion?: number; v3Enabled?: boolean; v4Enabled?: boolean; stepFormula?: string; deviationBandMode?: string; deviationBandPercent?: string; corridorAmount?: string; minStepAmount?: string; maxStepAmount?: string
  planSummary?: { points: number; minPrice: string; maxPrice: string; maxStep: string; averageStep: string;
    maxDeviation?: string; corridorAmount?: string; segmentAverages: string[]; rolling10Min?: string; rolling10Max?: string; rolling30Min?: string; rolling30Max?: string }
}
type ControlTask = { id: string; kind: string; status: string; startSource: string; sourceTime: number; startedAt: number; endedAt: number | null; startPrice: number; targetPrice: number; algorithmVersion?: number; holding?: boolean; historyReplacedAt?: number | null }
type PreviewTier = { intensity: number; minAmount?: string; maxAmount?: string; feasible: boolean; errorCode?: string; message?: string }
type Preview = { algorithmVersion?: number; deviationBandPercent?: string; corridorAmount?: string; typicalAmount?: string; feasible?: boolean; errorCode?: string; message?: string; startPrice?: string; minAmount?: string; maxAmount?: string; amountRandom?: boolean; summary?: ControlStatus['planSummary']; tiers?: PreviewTier[] }
const history = ref<ControlTask[]>([])
const sourceName = (source: string) => ({ LIVE_DISPLAY: '实时展示价', COMPLETED_CANDLE: '已完成 K 线收盘价', LAST_VALID_QUOTE: '最后有效报价', CONTROL_DISPLAY: '控盘展示价', LEGACY_PARAMETERS: '旧任务参数' }[source] || source)
const timeText = (value: number | null | undefined) => value ? new Date(value).toLocaleString() : '—'
const loading = ref(false), saving = ref(false), statusError = ref(''), historyError = ref('')
const symbols = ref<SymbolItem[]>([]), selectedId = ref<number>()
const status = ref<ControlStatus | null>(null)
const preview = ref<Preview | null>(null), previewError = ref(''), previewBusy = ref(false)
const mode = ref<'target' | 'restore' | 'manual'>('target')
const manual = ref({ enabled: false, offset: 0 as number | undefined })
const target = ref({ durationSeconds: 10 as number | undefined, intensity: 1 as number | undefined, randomOscillation: true, targetPrice: undefined as number | undefined })
const restore = ref({ durationSeconds: 10 as number | undefined, intensity: 1 as number | undefined, randomOscillation: false })
const timing = computed(() => mode.value === 'restore' ? restore.value : target.value)
const currentSymbol = computed(() => symbols.value.find(item => item.id === selectedId.value))
const precision = computed(() => currentSymbol.value?.pricePrecision ?? 2)
const precisionReady = computed(() => Number.isInteger(currentSymbol.value?.pricePrecision) && precision.value >= 0 && precision.value <= 8)
const formulaLoading = ref(false), formulaLoadError = ref('')
const busy = computed(() => loading.value || saving.value || formulaLoading.value || !status.value || !!statusError.value || !precisionReady.value)
function formatPrice(value: number | string | null | undefined): string {
  if (value == null || value === '' || !Number.isFinite(Number(value))) return '—'
  return new Intl.NumberFormat('en-US', { minimumFractionDigits: precision.value, maximumFractionDigits: precision.value }).format(Number(value))
}
const defaultFormula = 'start * 0.00001 * intensity'
const stepFormula = ref(defaultFormula), formulaDraft = ref(defaultFormula), formulaError = ref('')
const formulaOpen = ref(false), formulaBusy = ref(false), formulaPreview = ref<Preview | null>(null)
const bandManual = ref(false), bandPercent = ref('0.004'), advanced = ref<string[]>([])
const automaticBand = () => ((target.value.intensity ?? 1) * 4 / 1000).toFixed(3).replace(/0+$/, '').replace(/\.$/, '')
function restoreAutomaticBand() { bandManual.value = false; bandPercent.value = automaticBand() }
function changeBand(value: string) { bandPercent.value = value; bandManual.value = true }
function targetPayload(formula = stepFormula.value) {
  return { ...target.value, ...(status.value?.v4Enabled ? {
    stepFormula: formula, deviationBandMode: bandManual.value ? 'MANUAL' : 'AUTO',
    ...(bandManual.value ? { deviationBandPercent: bandPercent.value } : {})
  } : {}) }
}
function validBand() {
  return !bandManual.value || (/^(?:0|[1-9][0-9]*)(?:\.[0-9]{1,8})?$/.test(bandPercent.value) && Number(bandPercent.value) > 0 && Number(bandPercent.value) <= 100)
}
let formulaVersion = 0, formulaLoadVersion = 0
async function fetchFormula() {
  if (!status.value?.v4Enabled) return
  const id = selectedId.value, version = ++formulaLoadVersion
  formulaLoading.value = true; formulaLoadError.value = ''
  try {
    const value = await request.get(`/admin/ai-control/${id}/formula`) as unknown as { stepFormula: string }
    if (!disposed && selectedId.value === id && version === formulaLoadVersion) stepFormula.value = value.stepFormula
  } catch (error: any) {
    if (!disposed && selectedId.value === id && version === formulaLoadVersion) formulaLoadError.value = error?.message || '公式加载失败；请刷新后再启动'
  } finally { if (version === formulaLoadVersion) formulaLoading.value = false }
}
function openFormula() { formulaDraft.value = stepFormula.value; formulaPreview.value = null; formulaError.value = ''; formulaOpen.value = true }
async function closeFormula(done: () => void) {
  if (formulaBusy.value) return
  if (formulaDraft.value !== stepFormula.value) {
    try { await ElMessageBox.confirm('丢弃未保存的公式修改？当前任务和已保存公式不变。', '未保存修改', { confirmButtonText: '丢弃', cancelButtonText: '继续编辑', type: 'warning' }) }
    catch { return }
  }
  ++formulaVersion; done()
}
async function tryFormula() {
  const id = selectedId.value, version = ++formulaVersion
  if (id == null || !validBand()) { formulaError.value = '偏差带百分比须大于0且不超过100，最多8位小数'; return }
  formulaBusy.value = true; formulaError.value = ''; formulaPreview.value = null
  try {
    const value = await request.post(`/admin/ai-control/${id}/preview`, targetPayload(formulaDraft.value)) as unknown as Preview
    if (!disposed && id === selectedId.value && version === formulaVersion) formulaPreview.value = value
  } catch (error: any) {
    if (!disposed && id === selectedId.value && version === formulaVersion) formulaError.value = error?.message || '公式试算失败'
  } finally { formulaBusy.value = false }
}
async function saveFormula() {
  const id = selectedId.value, formula = formulaDraft.value.trim()
  if (id == null || formulaBusy.value || formulaPreview.value?.feasible !== true) return
  formulaBusy.value = true
  try {
    const value = await request.put(`/admin/ai-control/${id}/formula`, targetPayload(formula)) as unknown as { stepFormula: string }
    if (!disposed && id === selectedId.value) {
      stepFormula.value = value.stepFormula; formulaDraft.value = value.stepFormula; formulaOpen.value = false; formulaError.value = ''; formulaLoadError.value = ''
      ElMessage.success('公式已保存，后续新任务使用；当前运行轨迹不变')
    }
  } catch (error: any) { if (!disposed && id === selectedId.value) formulaError.value = error?.message || '公式保存失败' }
  finally { formulaBusy.value = false }
}
const progress = computed(() => {
  const state = status.value
  return state?.durationSeconds ? Math.min(100, Math.max(0, (1 - state.remainingSeconds / state.durationSeconds) * 100)) : 0
})
let timer: ReturnType<typeof setTimeout> | undefined
let previewTimer: ReturnType<typeof setTimeout> | undefined, previewVersion = 0
let disposed = false, requestVersion = 0, statusRequests = 0
let historyVersion = 0, historyRequests = 0, lastHistoryRequest = 0, resetPending = false
const readError = (error: any, label: string) => error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT'
  ? `${label}请求超时，正在自动重试` : `${label}加载失败：${error?.message || '网络异常'}`
let timedRequest: { signature: string; key: string } | undefined
function applyStatus(value: ControlStatus, reset = false) {
  status.value = value
  statusError.value = ''
  if (reset) {
    manual.value = { enabled: value.enabled, offset: Number(value.offset || 0) }
    recovery.value = Object.fromEntries(Object.entries(defaultRecovery()).map(([key, fallback]) => [key, value[key as keyof RecoveryOptions] ?? fallback])) as RecoveryOptions
    const savedTiming = { durationSeconds: value.durationSeconds ?? 10, intensity: value.intensity ?? 1, randomOscillation: value.randomOscillation ?? true }
    const defaultTiming = { durationSeconds: 10, intensity: 1, randomOscillation: true }
    target.value = { ...(value.restoring && value.autoRestore === undefined ? defaultTiming : savedTiming),
      targetPrice: (!value.restoring || value.autoRestore !== undefined ? value.targetPrice : null) ?? value.currentPrice ?? undefined }
    bandManual.value = value.algorithmVersion === 4 && value.deviationBandMode === 'MANUAL'
    bandPercent.value = bandManual.value ? String(value.deviationBandPercent) : automaticBand()
    restore.value = { ...(value.restoring && value.autoRestore === undefined ? savedTiming : defaultTiming) }
    if (value.running) mode.value = value.restoring && value.autoRestore === undefined ? 'restore' : 'target'
  }
}
async function fetchStatus(reset = false) {
  const id = selectedId.value, version = ++requestVersion
  if (id == null) return
  resetPending ||= reset
  ++statusRequests
  try {
    const value = await request.get(`/admin/ai-control/${id}`) as unknown as ControlStatus
    if (!disposed && id === selectedId.value && version === requestVersion) {
      applyStatus(value, resetPending)
      resetPending = false
    }
  } catch (error: any) {
    if (!disposed && id === selectedId.value && version === requestVersion) statusError.value = readError(error, '控盘状态')
  } finally { --statusRequests }
}
async function fetchPreview() {
  const id = selectedId.value, version = ++previewVersion
  if (id == null || !(status.value?.v3Enabled || status.value?.v4Enabled) || mode.value !== 'target') return
  const { durationSeconds, intensity, targetPrice, randomOscillation } = target.value
  if (!Number.isInteger(durationSeconds) || !Number.isInteger(intensity) || !Number.isFinite(targetPrice) || targetPrice! <= 0) return
  if (!validBand()) { previewError.value = '偏差带百分比须大于0且不超过100，最多8位小数'; return }
  previewBusy.value = true
  try {
    const value = await request.post(`/admin/ai-control/${id}/preview`, targetPayload()) as unknown as Preview
    if (!disposed && version === previewVersion && id === selectedId.value) { preview.value = value; previewError.value = '' }
  } catch (error: any) {
    if (!disposed && version === previewVersion && id === selectedId.value) previewError.value = error?.message || '预览失败'
  } finally { if (version === previewVersion) previewBusy.value = false }
}
async function fetchHistory(force = false) {
  const id = selectedId.value
  if (id == null || (!force && (historyRequests || Date.now() - lastHistoryRequest < 5000))) return
  const version = ++historyVersion
  lastHistoryRequest = Date.now()
  ++historyRequests
  try {
    const tasks = await request.get(`/admin/ai-control/${id}/history`) as unknown as ControlTask[]
    if (!disposed && id === selectedId.value && version === historyVersion) {
      history.value = [...tasks, ...history.value.filter(task => !tasks.some(latest => latest.id === task.id) && task.startedAt < (tasks[tasks.length - 1]?.startedAt ?? Infinity))]
      historyError.value = ''
    }
  } catch (error: any) {
    if (!disposed && id === selectedId.value && version === historyVersion) historyError.value = readError(error, '任务历史')
  } finally {
    --historyRequests
    if (version === historyVersion) lastHistoryRequest = Date.now()
  }
}
async function loadSymbols() {
  loading.value = true
  try {
    symbols.value = await request.get('/admin/ai-control/symbols') as unknown as SymbolItem[]
    if (!symbols.value.some(item => item.id === selectedId.value)) selectedId.value = symbols.value.find(item => item.isEnabled)?.id ?? symbols.value[0]?.id
    void fetchHistory(true)
    await fetchStatus(true)
    await fetchFormula()
  } catch (error: any) { ElMessage.error(error?.message || '加载币种失败') }
  finally { loading.value = false }
}
async function olderTasks() {
  if (!history.value.length || selectedId.value == null) return
  const older = await request.get(`/admin/ai-control/${selectedId.value}/history`, { params: { before: history.value[history.value.length - 1]!.startedAt } }) as unknown as ControlTask[]
  history.value.push(...older.filter(task => !history.value.some(existing => existing.id === task.id)))
}
async function replaceHistory(task: ControlTask) {
  const id = selectedId.value
  if (id == null || saving.value || !task.endedAt) return
  saving.value = true
  try {
    const saved = await request.post(`/admin/ai-control/${id}/history/${task.id}/replace`) as unknown as ControlTask
    if (selectedId.value === id) history.value = history.value.map(item => item.id === saved.id ? saved : item)
    ElMessage.success('该控盘段已替代原时间段历史行情，刷新与重启后持续保留')
  } catch (error: any) { ElMessage.error(error?.message || '替代历史行情失败') }
  finally { saving.value = false }
}
async function selectSymbol() {
  ++formulaLoadVersion; ++formulaVersion; formulaLoading.value = false; formulaOpen.value = false; stepFormula.value = defaultFormula; formulaError.value = ''; formulaLoadError.value = ''; restoreAutomaticBand()
  status.value = null
  statusError.value = ''
  historyError.value = ''
  history.value = []
  void fetchHistory(true)
  await fetchStatus(true)
  await fetchFormula()
}
async function poll() {
  if (disposed) return
  if (!saving.value && !loading.value) {
    void fetchHistory()
    if (!statusRequests) await fetchStatus()
  }
  if (!disposed) timer = setTimeout(poll, 1000)
}
async function submit(action: 'start' | 'restore' | 'manual' | 'stop' | 'random-market', payload?: object) {
  const id = selectedId.value
  if (id == null || saving.value) return
  saving.value = true
  ++requestVersion
  try {
    if (action === 'start' || action === 'restore') {
      const signature = JSON.stringify([id, action, payload])
      if (timedRequest?.signature !== signature) timedRequest = { signature, key: createRequestKey() }
      payload = { ...payload, requestKey: timedRequest.key }
    }
    const value = await request.post(`/admin/ai-control/${id}/${action}`, payload) as unknown as ControlStatus
    if (!disposed && id === selectedId.value) {
      applyStatus(value)
      if (action === 'start' || action === 'restore') timedRequest = undefined
      manual.value = { enabled: value.enabled, offset: Number(value.offset || 0) }
      void fetchHistory(true)
      ElMessage.success(action === 'random-market' ? (value.randomMarketEnabled ? '随机行情已开启' : '随机行情已关闭') : action === 'start' ? '自动控盘已开始' : action === 'restore' ? '正在逐步恢复原始行情' : action === 'stop' ? '任务已停止，历史已保存' : value.enabled ? '偏移已保存' : '已恢复原始行情')
    }
  } catch (error: any) { ElMessage.error([error?.response?.data?.errorCode, error?.message || '操作失败'].filter(Boolean).join('：')) }
  finally { saving.value = false }
}
function runTimed() {
  const { durationSeconds, randomOscillation } = timing.value
  const intensity = timing.value.intensity
  if (!Number.isInteger(durationSeconds) || durationSeconds! < 1 || durationSeconds! > 86400 || !Number.isInteger(intensity) || intensity! < 1 || intensity! > 10) {
    ElMessage.warning('时长需为 1–86400 秒，波动强度需为 1–10'); return
  }
  if (mode.value === 'restore') void submit('restore', { durationSeconds, intensity, randomOscillation })
  else {
    if (!Number.isFinite(target.value.targetPrice) || target.value.targetPrice! <= 0) { ElMessage.warning('请输入大于 0 的目标价格'); return }
    if (status.value?.v4Enabled && (!validBand() || formulaLoadError.value)) { ElMessage.warning(formulaLoadError.value || '请填写有效偏差带百分比'); return }
    void submit('start', { ...targetPayload(), ...recovery.value })
  }
}
function saveManual() {
  if (!Number.isFinite(manual.value.offset)) { ElMessage.warning('请输入有效的偏移值'); return }
  void submit('manual', manual.value)
}
watch(mode, value => {
  if (value === 'manual' && status.value) manual.value = { enabled: status.value.enabled, offset: Number(status.value.offset || 0) }
})
watch(() => target.value.intensity, () => { if (!bandManual.value) bandPercent.value = automaticBand() })
watch(() => [formulaDraft.value, bandPercent.value, bandManual.value, target.value.randomOscillation, target.value.targetPrice, target.value.durationSeconds, target.value.intensity], () => { ++formulaVersion; formulaPreview.value = null })
watch(() => [selectedId.value, mode.value, status.value?.v3Enabled, target.value.durationSeconds,
  target.value.intensity, target.value.targetPrice, target.value.randomOscillation, bandPercent.value, bandManual.value, stepFormula.value], () => {
  preview.value = null; previewError.value = ''; previewBusy.value = false; ++previewVersion
  clearTimeout(previewTimer)
  if (status.value?.v3Enabled && mode.value === 'target') previewTimer = setTimeout(() => void fetchPreview(), 350)
})
onMounted(async () => { await loadSymbols(); void poll() })
onUnmounted(() => { disposed = true; ++requestVersion; ++previewVersion; ++formulaVersion; ++formulaLoadVersion; clearTimeout(timer); clearTimeout(previewTimer) })
</script>
<template>
  <div class="ai-control-page">
    <el-card shadow="never">
      <template #header>
        <div class="header">
          <div><strong>AI 控盘</strong></div>
          <el-button v-permission="'ai_control:view'" :loading="loading" :disabled="saving" @click="loadSymbols">刷新列表</el-button>
        </div>
      </template>
      <el-form label-width="140px" class="control-form">
        <el-form-item label="选择币种">
          <el-select v-model="selectedId" filterable placeholder="请选择币种" :disabled="loading || saving" style="width: 100%" @change="selectSymbol">
            <el-option v-for="item in symbols" :key="item.id" :label="symbolLabel(item)" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="随机行情">
          <el-switch v-permission="'ai_control:random'" :model-value="!!status?.randomMarketEnabled" aria-label="随机行情" :disabled="busy || (!status?.virtualTrading && !status?.randomMarketEnabled)"
            @change="(value: boolean | string | number) => submit('random-market', { enabled: value })" />
        </el-form-item>
        <el-alert v-if="statusError" :title="statusError" type="error" :closable="false" show-icon />
        <el-alert v-else-if="status && !status.available" title="无可用行情源" type="warning" :closable="false" show-icon />
        <div v-if="status" class="quotes">
          <div><span>{{ status.randomMarketEnabled ? '随机基础价' : '原始行情' }}</span><strong>{{ formatPrice(status.rawPrice) }}</strong></div>
          <div><span>当前控盘价</span><strong>{{ formatPrice(status.currentPrice) }}</strong></div>
          <div><span>配置偏移</span><strong>{{ formatPrice(status.offset) }}</strong></div>
        </div>
        <el-alert v-if="status?.algorithmVersion === 3" type="success" :closable="false"
          :title="`均衡随机 V3 · TARGET 固定单秒幅度 ${formatPrice(status.minStepAmount)}～${formatPrice(status.maxStepAmount)}；HOLDING/恢复不受此范围约束`" />
        <el-alert v-if="status?.algorithmVersion === 4" type="success" :closable="false"
          :title="`稳定轨迹 V4 · TARGET 单秒幅度 ${formatPrice(status.minStepAmount)}～${formatPrice(status.maxStepAmount)}；固定偏差带 ±${formatPrice(status.corridorAmount)}（${status.deviationBandPercent}%）`" />
        <el-progress v-if="status?.running" :percentage="Math.round(progress)" />
        <div class="actions">
          <el-button v-permission="'ai_control:stop'" v-if="status?.running" :disabled="busy" :loading="saving" @click="submit('stop')">停止任务并保存历史</el-button>
          <el-button v-permission="'ai_control:manual'" type="danger" plain :disabled="busy || !status?.enabled" :loading="saving" @click="submit('manual', { enabled: false, offset: 0 })">{{ status?.randomMarketEnabled ? '取消指定并继续随机' : '一键恢复原始行情' }}</el-button>
        </div>
        <el-divider />
        <el-form-item label="控盘方式">
          <el-radio-group v-model="mode">
            <el-radio-button value="target">目标价格</el-radio-button>
            <el-radio-button value="restore">渐进恢复</el-radio-button>
            <el-radio-button value="manual">手动偏移</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <template v-if="mode !== 'manual'">
          <!-- Element Plus caches aria-disabled on mount; remount numeric inputs when that state changes. -->
          <el-form-item v-if="mode === 'target'" label="起点价格">
            <span>{{ formatPrice(status?.startBasis?.price ?? status?.currentPrice) }}（启动时读取，不手填）</span>
          </el-form-item>
          <el-form-item v-if="mode === 'target'" label="目标价格" for="control-target">
            <el-input-number id="control-target" :key="String(busy)" v-model="target.targetPrice" :precision="precision" :min="10 ** -precision" :step="10 ** -precision" :disabled="busy" />
          </el-form-item>
          <el-form-item :label="mode === 'restore' ? '恢复时长（秒）' : '执行时长（秒）'" for="control-duration">
            <el-input-number id="control-duration" :key="String(busy)" v-model="timing.durationSeconds" :min="1" :max="86400" :precision="0" :disabled="busy" />
          </el-form-item>
          <el-form-item v-if="mode === 'restore'" label="开启随机震荡" for="control-random-oscillation">
            <el-switch v-permission="'ai_control:start'" id="control-random-oscillation" v-model="timing.randomOscillation" aria-label="开启随机震荡" :disabled="busy" />
          </el-form-item>
          <p v-if="mode === 'target'" class="hint">{{ status?.v4Enabled ? '单步幅度和偏差带初始化后固定，不随剩余时间放大。偏差带约束相对参考路径的偏离，仅用于TARGET阶段。' : status?.v3Enabled ? '均衡随机 V3：强度决定每秒绝对涨跌额的固定范围。关闭随机后仍为可复现的均衡排列，不是直线。' : '新建目标轨迹已暂停。' }}</p>
          <el-form-item label="波动强度" for="control-intensity">
            <el-input-number id="control-intensity" :key="String(busy)" v-model="timing.intensity" :min="1" :max="10" :precision="0" :disabled="busy" />
          </el-form-item>
          <template v-if="mode === 'target' && status?.v4Enabled">
            <el-form-item label="偏差带半宽（%）" for="control-band-percent">
              <div class="band-field">
                <el-input id="control-band-percent" :model-value="bandPercent" inputmode="decimal" :disabled="busy" aria-label="偏差带半宽百分比" @input="changeBand"><template #append>%</template></el-input>
                <el-tag :type="bandManual ? 'warning' : 'info'">{{ bandManual ? '手动' : '自动联动' }}</el-tag>
                <el-button v-permission="'ai_control:start'" :disabled="busy || !bandManual" @click="restoreAutomaticBand">恢复自动</el-button>
              </div>
              <p class="hint">以任务起点价为固定基准；手动修改后不随强度覆盖。按品种最小跳动向内取整，不扩大范围。</p>
            </el-form-item>
            <el-alert v-if="formulaLoadError" :title="formulaLoadError" type="error" :closable="false" />
          </template>
        <template v-if="mode === 'target'">
          <el-alert v-if="previewError" :title="previewError" type="error" :closable="false" />
          <el-alert v-else-if="preview?.feasible === false" :title="`${preview.errorCode}: ${preview.message}`" type="warning" :closable="false" />
          <div v-if="preview" class="preview">
            <p>预览起点 {{ formatPrice(preview.startPrice) }}；当前档单秒幅度 {{ formatPrice(preview.minAmount) }}～{{ formatPrice(preview.maxAmount) }}。{{ preview.amountRandom === false ? '当前精度不支持金额随机。' : '' }}</p>
            <p v-if="preview.algorithmVersion === 4">偏差带 {{ preview.deviationBandPercent }}%，固定半宽 ±{{ formatPrice(preview.corridorAmount) }}；典型幅度 {{ formatPrice(preview.typicalAmount) }}。</p>
            <p v-if="preview.summary?.maxDeviation != null">候选最大偏离 {{ formatPrice(preview.summary.maxDeviation) }}，未超过固定偏差带。</p>
            <p v-if="preview.summary">候选 {{ preview.summary.points }} 点；最高 {{ formatPrice(preview.summary.maxPrice) }}，最低 {{ formatPrice(preview.summary.minPrice) }}；最大单秒涨跌 {{ formatPrice(preview.summary.maxStep) }}。候选仅供预览，启动时重验起点。</p>
            <p v-if="preview.summary">前／中／后三段均幅 {{ preview.summary.segmentAverages.map(formatPrice).join(' / ') }}；10 秒窗口 {{ formatPrice(preview.summary.rolling10Min) }}～{{ formatPrice(preview.summary.rolling10Max) }}；30 秒窗口 {{ formatPrice(preview.summary.rolling30Min) }}～{{ formatPrice(preview.summary.rolling30Max) }}。</p>
            <admin-table table-key="AiControl.1" v-if="preview.tiers" :data="preview.tiers" size="small" max-height="250">
              <el-table-column prop="intensity" label="强度" width="70" />
              <el-table-column label="固定单秒幅度"><template #default="{ row }">{{ formatPrice(row.minAmount) }}～{{ formatPrice(row.maxAmount) }}</template></el-table-column>
              <el-table-column label="可行性"><template #default="{ row }">{{ row.feasible ? '可行' : row.errorCode || '不可行' }}</template></el-table-column>
            </admin-table>
          </div>
          <el-collapse v-model="advanced" class="advanced-settings"><el-collapse-item title="高级设置" name="target">
            <el-form-item v-if="status?.v4Enabled" label="单步典型幅度公式">
              <div><code class="formula-text">{{ stepFormula }}</code><el-button v-permission="'ai_control:start'" :disabled="loading || saving || formulaBusy || formulaLoading" @click="openFormula">编辑公式</el-button></div>
            </el-form-item>
            <el-form-item label="随机均衡排列" for="control-random-oscillation">
              <el-switch v-permission="'ai_control:start'" id="control-random-oscillation" v-model="target.randomOscillation" aria-label="开启随机震荡" :disabled="busy" />
            </el-form-item>
          <el-form-item label="自动恢复"><el-checkbox v-model="recovery.autoRestore" aria-label="自动恢复">启用</el-checkbox></el-form-item>
          <el-form-item v-if="recovery.autoRestore" label="恢复方式">
            <el-radio-group v-model="recovery.restoreMode"><el-radio value="GRADUAL" label="GRADUAL">渐进恢复</el-radio><el-radio value="QUICK" label="QUICK">快速恢复</el-radio></el-radio-group>
          </el-form-item>
          <template v-if="recovery.autoRestore && recovery.restoreMode === 'GRADUAL'">
            <el-form-item label="恢复时长（秒）"><el-input-number v-model="recovery.restoreDurationSeconds" :min="1" :max="86400" :precision="0" aria-label="恢复时长" /></el-form-item>
            <el-form-item label="恢复波动强度"><el-input-number v-model="recovery.restoreIntensity" :min="1" :max="10" :precision="0" aria-label="恢复波动强度" /></el-form-item>
            <el-form-item label="恢复随机震荡"><el-checkbox v-model="recovery.restoreRandomOscillation" aria-label="恢复随机震荡">启用</el-checkbox></el-form-item>
          </template>
          <el-form-item label="自动替代历史行情"><el-checkbox v-model="recovery.autoReplaceHistory" aria-label="自动替代历史行情">启用</el-checkbox></el-form-item>
          </el-collapse-item></el-collapse>
        </template>
          <el-form-item>
            <el-button v-permission="mode === 'restore' ? 'ai_control:restore' : 'ai_control:start'" type="primary" :loading="saving" :disabled="busy || (mode === 'target' ? !(status?.canStart ?? status?.available) || status?.running || !currentSymbol?.isEnabled || status?.v4Enabled === false || (!!status?.v3Enabled && (previewBusy || preview?.feasible !== true || !!formulaLoadError)) : !status?.available)" @click="runTimed">
              {{ mode === 'restore' ? '按设定恢复原始行情' : '开始自动控盘' }}
            </el-button>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="启用控盘"><el-switch v-permission="'ai_control:manual'" aria-label="启用控盘" v-model="manual.enabled" :disabled="saving || status?.running" /></el-form-item>
          <el-form-item label="控盘偏移" for="control-offset">
            <el-input-number id="control-offset" v-model="manual.offset" :precision="8" :step="0.0001" :disabled="saving || status?.running" />
          </el-form-item>
          <el-form-item><el-button v-permission="'ai_control:manual'" type="primary" :loading="saving" :disabled="busy || status?.running || (manual.enabled && !status?.available)" @click="saveManual">保存手动偏移</el-button></el-form-item>
        </template>
      </el-form>
      <el-dialog v-model="formulaOpen" title="编辑单步典型幅度公式" width="min(640px, calc(100vw - 32px))" :before-close="closeFormula" :close-on-click-modal="false">
        <el-input v-model="formulaDraft" type="textarea" :rows="3" maxlength="256" :disabled="formulaBusy" aria-label="单步典型幅度公式" />
        <p class="hint">结果单位为价格/秒；实际单秒幅度为典型值的90%～110%，按品种精度校验。只在任务初始化时计算一次。</p>
        <p class="hint">变量：start 起点价、target 目标价、gap 绝对价差、duration 秒数、intensity 强度、tick 最小跳动。支持 + − * /、括号、abs/min/max。</p>
        <p v-if="! /\bintensity\b/.test(formulaDraft)" class="hint">此公式没有引用intensity，波动强度不会影响单步幅度；偏差带自动联动仍独立生效。</p>
        <el-alert v-if="formulaError" :title="formulaError" type="error" :closable="false" />
        <el-alert v-else-if="formulaPreview?.feasible === false" :title="`${formulaPreview.errorCode}: ${formulaPreview.message}`" type="warning" :closable="false" />
        <p v-else-if="formulaPreview?.feasible">试算通过：典型幅度 {{ formatPrice(formulaPreview.typicalAmount) }}；单秒 {{ formatPrice(formulaPreview.minAmount) }}～{{ formatPrice(formulaPreview.maxAmount) }}；偏差带 ±{{ formatPrice(formulaPreview.corridorAmount) }}。保存不会修改运行中的任务。</p>
        <template #footer>
          <el-button v-permission="'ai_control:start'" :disabled="formulaBusy" @click="formulaDraft = defaultFormula">恢复默认公式</el-button>
          <el-button v-permission="'ai_control:preview'" :loading="formulaBusy" :disabled="!formulaDraft.trim()" @click="tryFormula">试算公式</el-button>
          <el-button v-permission="'ai_control:start'" type="primary" :loading="formulaBusy" :disabled="formulaPreview?.feasible !== true || formulaBusy" @click="saveFormula">保存并应用</el-button>
        </template>
      </el-dialog>
      <h3>控盘任务历史</h3>
      <el-alert v-if="historyError" :title="historyError" type="warning" :closable="false" show-icon />
      <admin-table table-key="AiControl.2" :data="history" empty-text="暂无持久化控盘任务">
        <el-table-column label="开始时间" min-width="180"><template #default="{ row }">{{ timeText(row.startedAt) }}</template></el-table-column>
        <el-table-column label="起点依据" min-width="180"><template #default="{ row }">{{ sourceName(row.startSource) }}<br>{{ timeText(row.sourceTime) }}</template></el-table-column>
        <el-table-column prop="startPrice" label="起点价格"><template #default="{ row }">{{ formatPrice(row.startPrice) }}</template></el-table-column>
        <el-table-column prop="targetPrice" label="目标价格"><template #default="{ row }">{{ formatPrice(row.targetPrice) }}</template></el-table-column>
        <el-table-column label="算法" width="85"><template #default="{ row }">V{{ row.algorithmVersion || 1 }}</template></el-table-column>
        <el-table-column label="状态" min-width="120"><template #default="{ row }">{{ row.holding ? '保持偏移' : row.status }}</template></el-table-column>
        <el-table-column label="轨迹结束时间" min-width="180"><template #default="{ row }">{{ timeText(row.endedAt) }}</template></el-table-column>
        <el-table-column label="历史行情" min-width="155" fixed="right"><template #default="{ row }">
          <el-button v-permission="'ai_control:replace_history'" size="small" :disabled="saving || !row.endedAt" @click="replaceHistory(row)">{{ row.historyReplacedAt ? '更新已发布区间' : '替代历史行情' }}</el-button>
          <div v-if="row.historyReplacedAt" class="hint">{{ timeText(row.historyReplacedAt) }}</div>
        </template></el-table-column>
      </admin-table>
      <el-button v-permission="'ai_control:view'" v-if="history.length >= 100" @click="olderTasks">加载更早任务</el-button>
    </el-card>
  </div>
</template>
<style scoped>
.ai-control-page { padding: 16px; }
.header, .actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.actions { justify-content: flex-start; margin-top: 16px; }
.control-form { max-width: 760px; }
/* Fixed for the longest label across all three modes; switching modes must not shift fields. */
.control-form :deep(.el-form-item) { margin-bottom: 18px; }
.control-form :deep(.el-form-item__label) { white-space: nowrap; padding-right: 12px; }
.hint { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
.band-field { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; width: 100%; }
.band-field .el-input { max-width: 220px; }
.band-field + .hint { margin: 6px 0 0; }
.advanced-settings { margin: 16px 0; }
.formula-text { display: block; overflow-wrap: anywhere; padding-bottom: 8px; }
.preview { margin: 0 0 16px 140px; color: var(--el-text-color-regular); font-size: 13px; }
.preview p { margin: 6px 0; }
.quotes { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin: 20px 0 8px; }
.quotes div { padding: 14px; border: 1px solid var(--el-border-color-light); border-radius: 6px; }
.quotes span { display: block; color: var(--el-text-color-secondary); font-size: 12px; margin-bottom: 8px; }
.quotes strong { font-size: 20px; overflow-wrap: anywhere; }
.el-alert { margin-bottom: 16px; }
@media (max-width: 600px) {
  .quotes { grid-template-columns: 1fr; gap: 8px; }
  .ai-control-page { padding: 8px; }
  .preview { margin-left: 0; }
  .control-form :deep(.el-form-item) { flex-direction: column; align-items: stretch; }
  .control-form :deep(.el-form-item__label) { width: auto !important; justify-content: flex-start; height: auto; line-height: 24px; margin-bottom: 6px; }
  .control-form :deep(.el-form-item__content) { margin-left: 0 !important; min-width: 0; }
  .control-form :deep(.el-radio-group) { flex-wrap: wrap; gap: 4px; }
  .band-field .el-input { max-width: 100%; }
  :global(.layout-container:has(.ai-control-page) .layout-main) { padding: 8px; min-width: 0; }
  :global(.layout-container:has(.ai-control-page) .notification-area),
  :global(.layout-container:has(.ai-control-page) .online-count-area) { display: none; }
}
</style>
