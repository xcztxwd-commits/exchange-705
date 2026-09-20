<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

type SymbolItem = { id: number; symbol: string; name?: string; quoteCurrency?: string; pricePrecision: number; isEnabled: boolean }
type ControlStatus = {
  id: number; enabled: boolean; running: boolean; restoring: boolean; available: boolean; randomOscillation: boolean
  rawPrice: number | null; currentPrice: number | null; offset: number
  startPrice: number | null; targetPrice: number | null; durationSeconds: number | null
  intensity: number | null; startedAt: number | null; completedAt: number | null; remainingSeconds: number
}
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
  if (state.running) return `${state.restoring ? '正在恢复原始行情' : '正在前往目标价'} · 剩余 ${state.remainingSeconds} 秒`
  if (!state.enabled) return '跟随原始行情'
  return state.completedAt ? '已到达目标 · 保留最终偏移' : '固定偏移已开启'
})
const progress = computed(() => {
  const state = status.value
  return state?.durationSeconds ? Math.min(100, Math.max(0, (1 - state.remainingSeconds / state.durationSeconds) * 100)) : 0
})
const formatPrice = (value: number | null | undefined) => value == null ? '—' : Number(value).toFixed(precision.value)
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false, requestVersion = 0, statusRequests = 0

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
    if (!disposed && id === selectedId.value && version === requestVersion) applyStatus(value, reset)
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

async function selectSymbol() {
  status.value = null
  await fetchStatus(true)
}

async function poll() {
  if (disposed) return
  if (!saving.value && !loading.value && !statusRequests) await fetchStatus()
  if (!disposed) timer = setTimeout(poll, 1000)
}

async function submit(action: 'start' | 'restore' | 'manual' | 'stop', payload?: object) {
  const id = selectedId.value
  if (id == null || saving.value) return
  saving.value = true
  ++requestVersion
  try {
    const value = await request.post(`/admin/ai-control/${id}/${action}`, payload) as unknown as ControlStatus
    if (!disposed && id === selectedId.value) {
      applyStatus(value)
      manual.value = { enabled: value.enabled, offset: Number(value.offset || 0) }
      ElMessage.success(action === 'start' ? '自动控盘已开始' : action === 'restore' ? '正在逐步恢复原始行情' : action === 'stop' ? '任务已停止，保留当前偏移' : value.enabled ? '偏移已保存' : '已恢复原始行情')
    }
  } catch (error: any) { ElMessage.error(error?.message || '操作失败') }
  finally { saving.value = false }
}

function runTimed() {
  const { durationSeconds, randomOscillation } = timing.value
  const intensity = timing.value.intensity ?? (randomOscillation ? undefined : 1)
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
        <el-alert v-if="statusError" :title="statusError" type="error" :closable="false" show-icon />
        <el-alert v-else-if="status && !status.available" title="行情暂不可用或已过期，等待有效报价；仍可一键恢复原始行情。" type="warning" :closable="false" show-icon />
        <div v-if="status" class="quotes">
          <div><span>原始行情</span><strong>{{ formatPrice(status.rawPrice) }}</strong></div>
          <div><span>当前控盘价</span><strong>{{ formatPrice(status.currentPrice) }}</strong></div>
          <div><span>当前偏移</span><strong>{{ formatPrice(status.offset) }}</strong></div>
        </div>
        <p v-if="currentSymbol" class="hint">价格单位：{{ currentSymbol.quoteCurrency || 'USD' }}。当前控盘价用于用户行情与交易取价。</p>
        <p role="status">{{ statusText }}</p>
        <el-progress v-if="status?.running" :percentage="Math.round(progress)" />
        <div class="actions">
          <el-button v-if="status?.running" :disabled="busy || !status.available" :loading="saving" @click="submit('stop')">停止任务并保留当前偏移</el-button>
          <el-button type="danger" plain :disabled="busy || !status?.enabled" :loading="saving" @click="submit('manual', { enabled: false, offset: 0 })">一键恢复原始行情</el-button>
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
            <el-input-number id="control-intensity" :key="String(busy || !timing.randomOscillation)" v-model="timing.intensity" :min="1" :max="10" :precision="0" :disabled="busy || !timing.randomOscillation" />
          </el-form-item>
          <p class="hint">关闭时匀速变化；开启后加入随机涨跌，波动强度为 1–10，等级越高波动越大。到时仍收敛到目标。</p>
          <p v-if="mode === 'target'" class="hint">到达目标后保留最终偏移，继续随真实行情涨跌。</p>
          <p v-else class="hint">从当前偏移逐步回到 0，跟随实时原始行情，结束后自动关闭控盘。可接替正在运行的目标任务。</p>
          <el-form-item>
            <el-button type="primary" :loading="saving" :disabled="busy || !status?.available || (mode === 'target' ? status?.running || !currentSymbol?.isEnabled : !status?.enabled)" @click="runTimed">
              {{ mode === 'restore' ? '按设定恢复原始行情' : '开始自动控盘' }}
            </el-button>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item label="启用控盘"><el-switch v-model="manual.enabled" :disabled="saving || status?.running" /></el-form-item>
          <el-form-item label="控盘偏移" for="control-offset">
            <el-input-number id="control-offset" v-model="manual.offset" :precision="8" :step="0.0001" :disabled="saving || status?.running" />
          </el-form-item>
          <p class="hint">当前控盘价 = 原始行情 + 偏移。修改前先停止正在运行的自动任务。</p>
          <el-form-item><el-button type="primary" :loading="saving" :disabled="busy || status?.running || (manual.enabled && !status?.available)" @click="saveManual">保存手动偏移</el-button></el-form-item>
        </template>
        <p class="hint">任务在服务端运行，刷新页面不会中断。K 线沿用当前偏移规则，不保存逐秒控盘走势。</p>
      </el-form>
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
