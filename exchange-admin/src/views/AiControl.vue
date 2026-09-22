<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { createRequestKey } from '@/utils/requestKey'

type SymbolItem = { id: number; symbol: string; name?: string; quoteCurrency?: string; pricePrecision: number; isEnabled: boolean }
type ControlStatus = {
  canStart?: boolean; sourceAvailable?: boolean; controlState?: string; startSource?: string; sourceTime?: number; holding?: boolean
  startBasis?: { source: string; timestamp: number; price: number }
  virtualTrading: boolean; randomMarketEnabled: boolean; randomMarketBasePrice: number | null
  id: number; enabled: boolean; running: boolean; restoring: boolean; available: boolean; randomOscillation: boolean
  rawPrice: number | null; currentPrice: number | null; offset: number
  startPrice: number | null; targetPrice: number | null; durationSeconds: number | null
  intensity: number | null; startedAt: number | null; completedAt: number | null; remainingSeconds: number
}
type ControlTask = { id: string; kind: string; status: string; startSource: string; sourceTime: number; startedAt: number; endedAt: number | null; startPrice: number; targetPrice: number; holding?: boolean; historyReplacedAt?: number | null }
const history = ref<ControlTask[]>([])
const sourceName = (source: string) => ({ LIVE_DISPLAY: '实时展示价', COMPLETED_CANDLE: '已完成 K 线收盘价', LAST_VALID_QUOTE: '最后有效报价', CONTROL_DISPLAY: '控盘展示价', LEGACY_PARAMETERS: '旧任务参数' }[source] || source)
const timeText = (value: number | null | undefined) => value ? new Date(value).toLocaleString() : '—'
const loading = ref(false), saving = ref(false), statusError = ref('')
const symbols = ref<SymbolItem[]>([]), selectedId = ref<number>()
const status = ref<ControlStatus | null>(null)
const mode = ref<'target' | 'restore' | 'manual'>('target')
const manual = ref({ enabled: false, offset: 0 as number | undefined })
const target = ref({ durationSeconds: 10 as number | undefined, intensity: 1 as number | undefined, randomOscillation: false, targetPrice: undefined as number | undefined })
const restore = ref({ durationSeconds: 10 as number | undefined, intensity: 1 as number | undefined, randomOscillation: false })
const timing = computed(() => mode.value === 'restore' ? restore.value : target.value)
const currentSymbol = computed(() => symbols.value.find(item => item.id === selectedId.value))
const precision = computed(() => currentSymbol.value?.pricePrecision ?? 2)
const busy = computed(() => loading.value || saving.value || !status.value || !!statusError.value)
const statusText = computed(() => {
  const state = status.value
  if (!state) return '等待行情'
  if (state.holding) return state.sourceAvailable ? '已到目标 · 保持偏移，等待手动恢复' : '保持偏移 · 源异常，静态等待恢复'
  if (state.running) return `${state.restoring ? '正在恢复原始行情' : '正在前往目标价'} · 剩余 ${state.remainingSeconds} 秒`
  if (state.randomMarketEnabled) return state.enabled ? '随机行情 + 指定偏移 · 虚拟资金结算' : '随机行情已开启 · 每秒更新 · 虚拟资金结算'
  if (state.controlState === 'WAITING_SOURCE') return '静态价格 · 等待原始行情恢复'
  if (!state.enabled) return state.sourceAvailable === false ? '原始行情异常 · 可使用有效历史起点' : '跟随原始行情'
  return state.completedAt ? '任务已结束 · 历史已保存' : '固定偏移已开启'
})
const progress = computed(() => {
  const state = status.value
  return state?.durationSeconds ? Math.min(100, Math.max(0, (1 - state.remainingSeconds / state.durationSeconds) * 100)) : 0
})
const formatPrice = (value: number | null | undefined) => value == null ? '—' : Number(value).toFixed(precision.value)
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false, requestVersion = 0, statusRequests = 0
let timedRequest: { signature: string; key: string } | undefined

function applyStatus(value: ControlStatus, reset = false) {
  status.value = value
  statusError.value = ''
  if (reset) {
    manual.value = { enabled: value.enabled, offset: Number(value.offset || 0) }
    const savedTiming = { durationSeconds: value.durationSeconds ?? 10, intensity: value.intensity ?? 1, randomOscillation: value.randomOscillation ?? false }
    const defaultTiming = { durationSeconds: 10, intensity: 1, randomOscillation: false }
    target.value = { ...(value.restoring ? defaultTiming : savedTiming),
      targetPrice: (!value.restoring ? value.targetPrice : null) ?? value.currentPrice ?? undefined }
    restore.value = { ...(value.restoring ? savedTiming : defaultTiming) }
    if (value.running) mode.value = value.restoring ? 'restore' : 'target'
  }
}

async function fetchStatus(reset = false) {
  const id = selectedId.value, version = ++requestVersion
  if (id == null) return
  ++statusRequests
  try {
    const value = await request.get(`/admin/ai-control/${id}`) as unknown as ControlStatus
    if (!disposed && id === selectedId.value && version === requestVersion) {
      applyStatus(value, reset)
      const tasks = await request.get(`/admin/ai-control/${id}/history`) as unknown as ControlTask[]
      if (!disposed && id === selectedId.value && version === requestVersion) history.value = [...tasks, ...history.value.filter(task => !tasks.some(latest => latest.id === task.id) && task.startedAt < (tasks[tasks.length - 1]?.startedAt ?? Infinity))]
    }
  } catch (error: any) {
    if (!disposed && id === selectedId.value && version === requestVersion) statusError.value = error?.message || '控盘状态加载失败'
  } finally { --statusRequests }
}

async function loadSymbols() {
  loading.value = true
  try {
    symbols.value = await request.get('/admin/ai-control/symbols') as unknown as SymbolItem[]
    if (!symbols.value.some(item => item.id === selectedId.value)) selectedId.value = symbols.value.find(item => item.isEnabled)?.id ?? symbols.value[0]?.id
    await fetchStatus(true)
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
  if (id == null || saving.value || !task.endedAt || task.historyReplacedAt) return
  saving.value = true
  try {
    const saved = await request.post(`/admin/ai-control/${id}/history/${task.id}/replace`) as unknown as ControlTask
    if (selectedId.value === id) history.value = history.value.map(item => item.id === saved.id ? saved : item)
    ElMessage.success('该控盘段已替代原时间段历史行情，刷新与重启后持续保留')
  } catch (error: any) { ElMessage.error(error?.message || '替代历史行情失败') }
  finally { saving.value = false }
}

async function selectSymbol() {
  status.value = null
  history.value = []
  await fetchStatus(true)
}

async function poll() {
  if (disposed) return
  if (!saving.value && !loading.value && !statusRequests) await fetchStatus()
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
      ElMessage.success(action === 'random-market' ? (value.randomMarketEnabled ? '随机行情已开启' : '随机行情已关闭') : action === 'start' ? '自动控盘已开始' : action === 'restore' ? '正在逐步恢复原始行情' : action === 'stop' ? '任务已停止，历史已保存' : value.enabled ? '偏移已保存' : '已恢复原始行情')
    }
  } catch (error: any) { ElMessage.error(error?.message || '操作失败') }
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
    void submit('start', { durationSeconds, intensity, randomOscillation, targetPrice: target.value.targetPrice })
  }
}

function saveManual() {
  if (!Number.isFinite(manual.value.offset)) { ElMessage.warning('请输入有效的偏移值'); return }
  void submit('manual', manual.value)
}

watch(mode, value => {
  if (value === 'manual' && status.value) manual.value = { enabled: status.value.enabled, offset: Number(status.value.offset || 0) }
})
onMounted(async () => { await loadSymbols(); void poll() })
onUnmounted(() => { disposed = true; ++requestVersion; clearTimeout(timer) })
</script>

<template>
  <div class="ai-control-page">
    <el-card shadow="never">
      <template #header>
        <div class="header">
          <div><strong>AI 控盘</strong><p class="hint">按时间到达目标价，或逐步恢复原始行情</p></div>
          <el-button :loading="loading" :disabled="saving" @click="loadSymbols">刷新列表</el-button>
        </div>
      </template>
      <el-form label-width="120px" class="control-form">
        <el-form-item label="选择币种">
          <el-select v-model="selectedId" filterable placeholder="请选择币种" :disabled="loading || saving" style="width: 100%" @change="selectSymbol">
            <el-option v-for="item in symbols" :key="item.id" :label="`${item.symbol} (${item.name || ''})`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="随机行情">
          <el-switch :model-value="!!status?.randomMarketEnabled" aria-label="随机行情" :disabled="busy || (!status?.virtualTrading && !status?.randomMarketEnabled)"
            @change="(value: boolean | string | number) => submit('random-market', { enabled: value })" />
          <span class="hint" style="margin-left: 12px">每秒更新价格，按当前周期生成 K 线</span>
        </el-form-item>
        <p class="hint">随机行情用于虚拟资金测试；开启后保留已有历史，从最后有效价格、当前时刻接续。可叠加目标价格、渐进恢复或手动偏移；指定任务结束或取消后继续随机走势。关闭随机开关后恢复外部基础行情。</p>
        <p v-if="status && !status.virtualTrading" class="hint">当前环境未启用虚拟交易配置，随机行情不可开启。</p>
        <el-alert v-if="statusError" :title="statusError" type="error" :closable="false" show-icon />
        <el-alert v-else-if="status && !status.available" title="原始行情异常；控盘或保持偏移期间仍可按当前展示价交易，存在有效历史起点时可启动目标控盘。" type="warning" :closable="false" show-icon />
        <div v-if="status" class="quotes">
          <div><span>{{ status.randomMarketEnabled ? '随机基础价' : '原始行情' }}</span><strong>{{ formatPrice(status.rawPrice) }}</strong></div>
          <div><span>当前控盘价</span><strong>{{ formatPrice(status.currentPrice) }}</strong></div>
          <div><span>配置偏移</span><strong>{{ formatPrice(status.offset) }}</strong></div>
        </div>
        <p v-if="currentSymbol" class="hint">价格单位：{{ currentSymbol.quoteCurrency || 'USD' }}。控盘和保持偏移期间按后台当前展示价交易；停止任务保留偏移，明确恢复后才跟随原始行情。</p>
        <p role="status">{{ statusText }}</p>
        <p v-if="status?.startBasis?.source" class="hint">可用起点：{{ sourceName(status.startBasis.source) }} · {{ formatPrice(status.startBasis.price) }} · 原始时间 {{ timeText(status.startBasis.timestamp) }}</p>
        <el-progress v-if="status?.running" :percentage="Math.round(progress)" />
        <div class="actions">
          <el-button v-if="status?.running" :disabled="busy" :loading="saving" @click="submit('stop')">停止任务并保存历史</el-button>
          <el-button type="danger" plain :disabled="busy || !status?.enabled" :loading="saving" @click="submit('manual', { enabled: false, offset: 0 })">{{ status?.randomMarketEnabled ? '取消指定并继续随机' : '一键恢复原始行情' }}</el-button>
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
          <el-form-item v-if="mode === 'target'" label="目标价格" for="control-target">
            <el-input-number id="control-target" :key="String(busy)" v-model="target.targetPrice" :precision="precision" :min="10 ** -precision" :step="10 ** -precision" :disabled="busy" />
          </el-form-item>
          <el-form-item :label="mode === 'restore' ? '恢复时长（秒）' : '执行时长（秒）'" for="control-duration">
            <el-input-number id="control-duration" :key="String(busy)" v-model="timing.durationSeconds" :min="1" :max="86400" :precision="0" :disabled="busy" />
          </el-form-item>
          <el-form-item label="开启随机震荡" for="control-random-oscillation">
            <el-switch id="control-random-oscillation" v-model="timing.randomOscillation" aria-label="开启随机震荡" :disabled="busy" />
          </el-form-item>
          <el-form-item label="波动强度" for="control-intensity">
            <el-input-number id="control-intensity" :key="String(busy)" v-model="timing.intensity" :min="1" :max="10" :precision="0" :disabled="busy" />
          </el-form-item>
          <p class="hint">波动强度独立生效：普通控盘按规则上下波动，开启随机震荡后改为随机涨跌。强度 1–10，越高每次波动越大；两种模式均按时到达目标。</p>
          <p v-if="mode === 'target'" class="hint">到达目标后继续保持固定偏移，跟随原始行情变化，直到手动恢复或开始新的控盘。断源时静态保留最后控盘价，不生成额外横盘。</p>
          <p v-else class="hint">从当前展示价启动独立恢复段，目标固定为启动时的原始报价；结束后接回原始行情。随机开关保持不变。</p>
          <el-form-item>
            <el-button type="primary" :loading="saving" :disabled="busy || (mode === 'target' ? !(status?.canStart ?? status?.available) || status?.running || !currentSymbol?.isEnabled : !status?.available)" @click="runTimed">
              {{ mode === 'restore' ? '按设定恢复原始行情' : '开始自动控盘' }}
            </el-button>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="启用控盘"><el-switch aria-label="启用控盘" v-model="manual.enabled" :disabled="saving || status?.running" /></el-form-item>
          <el-form-item label="控盘偏移" for="control-offset">
            <el-input-number id="control-offset" v-model="manual.offset" :precision="8" :step="0.0001" :disabled="saving || status?.running" />
          </el-form-item>
          <p class="hint">当前控盘价 = 原始行情 + 偏移。修改前先停止正在运行的自动任务。</p>
          <el-form-item><el-button type="primary" :loading="saving" :disabled="busy || status?.running || (manual.enabled && !status?.available)" @click="saveManual">保存手动偏移</el-button></el-form-item>
        </template>
        <p class="hint">轨迹结束后可点击“替代历史行情”，将该段应用到原时间段，刷新、切换周期和重启后持续显示。原始行情独立保留；保持偏移期间的新行情继续保存。缺失源数据会明确标记。</p>
      </el-form>
      <h3>控盘任务历史</h3>
      <el-table :data="history" empty-text="暂无持久化控盘任务">
        <el-table-column label="开始时间" min-width="180"><template #default="{ row }">{{ timeText(row.startedAt) }}</template></el-table-column>
        <el-table-column label="起点依据" min-width="180"><template #default="{ row }">{{ sourceName(row.startSource) }}<br>{{ timeText(row.sourceTime) }}</template></el-table-column>
        <el-table-column prop="startPrice" label="起点价格" />
        <el-table-column prop="targetPrice" label="目标价格" />
        <el-table-column label="状态" min-width="120"><template #default="{ row }">{{ row.holding ? '保持偏移' : row.status }}</template></el-table-column>
        <el-table-column label="轨迹结束时间" min-width="180"><template #default="{ row }">{{ timeText(row.endedAt) }}</template></el-table-column>
        <el-table-column label="历史行情" min-width="155" fixed="right"><template #default="{ row }">
          <el-button size="small" :disabled="saving || !row.endedAt || !!row.historyReplacedAt" @click="replaceHistory(row)">{{ row.historyReplacedAt ? '已替代历史行情' : '替代历史行情' }}</el-button>
          <div v-if="row.historyReplacedAt" class="hint">{{ timeText(row.historyReplacedAt) }}</div>
        </template></el-table-column>
      </el-table>
      <el-button v-if="history.length >= 100" @click="olderTasks">加载更早任务</el-button>
    </el-card>
  </div>
</template>

<style scoped>
.ai-control-page { padding: 16px; }
.header, .actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.actions { justify-content: flex-start; margin-top: 16px; }
.control-form { max-width: 760px; }
.hint { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
.quotes { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin: 20px 0 8px; }
.quotes div { padding: 14px; border: 1px solid var(--el-border-color-light); border-radius: 6px; }
.quotes span { display: block; color: var(--el-text-color-secondary); font-size: 12px; margin-bottom: 8px; }
.quotes strong { font-size: 20px; overflow-wrap: anywhere; }
.el-alert { margin-bottom: 16px; }
@media (max-width: 600px) { .quotes { grid-template-columns: 1fr; gap: 8px; } .ai-control-page { padding: 8px; } }
</style>
