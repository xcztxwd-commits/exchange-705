<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import ManualOrderChart from './ManualOrderChart.vue'
import type { OrderChartRange } from '@/utils/manualOrderChart'
import { simpleConditions, simpleFields, simplePayload, type SimpleField } from '@/utils/simpleManualOrder'

const emit = defineEmits(['created'])
const path = '/admin/orders/contract/manual'
const initial = () => ({ userId: null as number | null, symbol: '', openTime: null as number | null, closeTime: null as number | null, openPrice: '', closePrice: '', leverage: '100', quantity: '', side: '', targetNet: '', allowNetAdjustment: true, netTolerance: '5', walletEnabled: false, historyEnabled: false })
const form = reactive(initial()), conditions = reactive(simpleConditions())
const visible = ref(false), busy = ref(false), saving = ref(false), error = ref(''), result = ref<any>(null)
const dialogContent = ref<HTMLElement | null>(null)
function resetScroll() { dialogContent.value?.closest('.el-dialog__body')?.scrollTo(0, 0); dialogContent.value?.closest('.el-overlay-dialog')?.scrollTo(0, 0) }
const users = ref<any[]>([]), symbols = ref<any[]>([]), timezone = ref('UTC')
const symbol = computed(() => symbols.value.find(s => s.symbol === form.symbol))
const unit = computed(() => symbol.value?.quantity_unit_type === 'BASE_ASSET' ? symbol.value.base_currency : symbol.value?.quantity_unit_type === 'SHARE' ? '股' : '手')
const step = computed(() => Number(symbol.value?.quantity_step || 0.01))
const minimum = computed(() => Number(symbol.value?.min_order_quantity || step.value))
const sliderValue = computed(() => Math.max(Math.min(minimum.value, 100), Math.min(100, Number(form.quantity) || minimum.value)))
const signature = computed(() => JSON.stringify({ userId: form.userId, symbol: form.symbol, openTime: form.openTime, closeTime: form.closeTime, conditions, allow: form.allowNetAdjustment, tolerance: form.netTolerance, wallet: form.walletEnabled, history: form.historyEnabled }))
let revision = 0, session = 0, searchRevision = 0, key = '', verified = ''
watch(signature, () => { revision++; result.value = null; error.value = ''; verified = ''; busy.value = false })
watch(visible, value => { if (!value) { revision++; busy.value = false } })
watch(() => form.historyEnabled, value => { if (value) form.walletEnabled = true })
watch(() => form.userId, value => { if (!value) { form.walletEnabled = false; form.historyEnabled = false } })
watch(() => form.symbol, () => {
  clearChart()
  for (const field of simpleFields) { conditions[field] = ''; form[field] = '' }
  form.leverage = String(Math.min(100, Number(symbol.value?.max_leverage || 100)))
})
function edit(field: SimpleField, value: string | number | number[] | null | undefined) {
  const text = value == null ? '' : String(value)
  form[field] = text; conditions[field] = text
  if (field === 'openPrice' || field === 'closePrice') clearChart()
}
function clearChart() { form.openTime = null; form.closeTime = null }
function selectChart(range: OrderChartRange) {
  edit('openPrice', range.open.price); edit('closePrice', range.close.price)
  form.openTime = range.open.timestamp; form.closeTime = range.close.timestamp
  ElMessage.success('已固定所选开平仓分钟和开盘价，点击一键生成补齐其他条件')
}
async function search(text = '') {
  const id = ++searchRevision, active = session
  try {
    const data: any = await request.get(`${path}/context`, { params: { search: text } })
    if (id !== searchRevision || active !== session) return
    users.value = data.users || []; symbols.value = data.symbols || []; timezone.value = data.timezone || 'UTC'
  } catch (e: any) { if (active === session) error.value = e.message }
}
function clear() {
  clearChart()
  Object.assign(conditions, simpleConditions())
  for (const field of simpleFields) form[field] = field === 'leverage' ? String(Math.min(100, Number(symbol.value?.max_leverage || 100))) : ''
  result.value = null; verified = ''; error.value = ''; revision++
}
async function open() {
  session++; Object.assign(form, initial()); clear(); visible.value = true; bindVisible.value = false
  await search()
}
async function generate() {
  if (busy.value || saving.value) return
  let payload: Record<string, unknown>
  try { payload = simplePayload(form, conditions, symbol.value, timezone.value); Object.assign(payload, { openTime: form.openTime, closeTime: form.closeTime }) } catch (e: any) { error.value = e.message; return }
  await nextTick()
  const id = ++revision, expected = signature.value; busy.value = true; result.value = null; error.value = ''
  try {
    let response: any
    for (let attempt = 0; attempt < 20; attempt++) {
      if (id !== revision || !visible.value) return
      try { response = await request.post(`${path}/simple/generate`, payload, { timeout: 120000 }); break }
      catch (e: any) {
        if (e.response?.data?.code !== 425 || attempt === 19) throw e
        error.value = '历史行情正在加载，完成后自动生成…'
        await new Promise(resolve => setTimeout(resolve, 1500))
      }
    }
    if (id !== revision || !visible.value || expected !== signature.value) return
    const values = { openPrice: response.quotes.openPrice, closePrice: response.quotes.closePrice, leverage: response.request.leverage, quantity: response.calculation.quantity, side: response.request.side, targetNet: response.calculation.net }
    for (const field of simpleFields) form[field] = String(values[field])
    result.value = response; error.value = ''; key = crypto.randomUUID(); verified = signature.value
    ElMessage.success('已生成预览，尚未创建订单或修改资金')
  } catch (e: any) { if (id === revision) error.value = e.message }
  finally { if (id === revision) busy.value = false }
}
const canCreate = computed(() => result.value && verified === signature.value && !busy.value && !saving.value)
async function create() {
  if (!canCreate.value) return
  saving.value = true; error.value = ''
  try {
    await request.post(`${path}/simple`, { ...result.value.request, previewToken: result.value.previewToken, idempotencyKey: key })
    ElMessage.success(form.userId ? '模拟已平仓订单已创建' : '未绑定模拟订单已创建，可在列表绑定用户')
    visible.value = false; emit('created')
  } catch (e: any) { error.value = e.message }
  finally { saving.value = false }
}
function time(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: timezone.value, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(value))
}

const bindVisible = ref(false), bindBusy = ref(false), bindSaving = ref(false), bindError = ref(''), bindResult = ref<any>(null)
const binding = reactive({ orderId: 0, userId: null as number | null, walletEnabled: false, historyEnabled: false })
let bindRevision = 0, bindKey = '', bindVerified = ''
const bindSignature = computed(() => JSON.stringify(binding))
watch(bindSignature, () => { bindRevision++; bindResult.value = null; bindVerified = ''; bindError.value = ''; bindBusy.value = false })
watch(bindVisible, value => { if (!value) { bindRevision++; bindBusy.value = false } })
watch(() => binding.historyEnabled, value => { if (value) binding.walletEnabled = true })
async function openBinding(row: any) {
  session++; visible.value = false; Object.assign(binding, { orderId: row.id, userId: null, walletEnabled: false, historyEnabled: false })
  bindResult.value = null; bindVerified = ''; bindError.value = ''; bindVisible.value = true; await search()
}
async function previewBinding() {
  await nextTick(); const id = ++bindRevision, expected = bindSignature.value
  bindBusy.value = true; bindError.value = ''; bindResult.value = null
  try {
    const response: any = await request.post(`${path}/${binding.orderId}/bind/preview`, { userId: binding.userId, walletEnabled: binding.walletEnabled, historyEnabled: binding.historyEnabled })
    if (id !== bindRevision || !bindVisible.value || expected !== bindSignature.value) return
    bindResult.value = response; bindVerified = expected; bindKey = crypto.randomUUID()
  } catch (e: any) { if (id === bindRevision) bindError.value = e.message }
  finally { if (id === bindRevision) bindBusy.value = false }
}
async function confirmBinding() {
  if (!bindResult.value || bindVerified !== bindSignature.value || bindSaving.value) return
  bindSaving.value = true; bindError.value = ''
  try {
    await request.post(`${path}/${binding.orderId}/bind`, { userId: binding.userId, walletEnabled: binding.walletEnabled, historyEnabled: binding.historyEnabled, previewToken: bindResult.value.previewToken, idempotencyKey: bindKey })
    bindVisible.value = false; emit('created'); ElMessage.success('用户已绑定，资金按确认的开关处理')
  } catch (e: any) { bindError.value = e.message }
  finally { bindSaving.value = false }
}
defineExpose({ open, openBinding })
</script>

<template>
  <el-dialog v-model="visible" title="生成模拟订单 · 简版" width="min(1200px, calc(100vw - 24px))" top="4vh" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" class="simple-order-dialog" @opened="resetScroll">
    <ManualOrderChart class="simple-order-chart" :symbol="form.symbol" :timezone="timezone" :active="visible" :disabled="busy || saving" :open-time="form.openTime ?? undefined" :close-time="form.closeTime ?? undefined" @select="selectChart" @clear="clearChart">
      <template #intro><p ref="dialogContent" class="hint">仅品种必填；手填条件保留，空项一键补齐。</p></template>
    </ManualOrderChart>
    <el-form label-position="top" class="simple-order-form" :disabled="busy || saving">
      <el-form-item label="用户（可选）"><el-select v-model="form.userId" clearable filterable remote :remote-method="search" placeholder="留空不绑定用户" aria-label="用户（可选）"><el-option v-for="u in users" :key="u.id" :value="u.id" :label="`${u.id} · ${u.email}`" /></el-select></el-form-item>
      <el-form-item label="品种" required><el-select v-model="form.symbol" filterable placeholder="请选择品种" aria-label="品种"><el-option v-for="s in symbols" :key="s.symbol" :value="s.symbol" :label="`${s.symbol} · ${s.name}`" /></el-select></el-form-item>
      <el-form-item label="开仓价格"><el-input :model-value="form.openPrice" clearable placeholder="留空自动匹配" aria-label="开仓价格" @update:model-value="(v: string | number | number[]) => edit('openPrice', v)" /></el-form-item>
      <el-form-item label="平仓价格"><el-input :model-value="form.closePrice" clearable placeholder="留空取最近分钟" aria-label="平仓价格" @update:model-value="(v: string | number | number[]) => edit('closePrice', v)" /></el-form-item>
      <el-form-item label="杠杆"><el-input :model-value="form.leverage" clearable placeholder="默认100×" aria-label="杠杆" @update:model-value="(v: string | number | number[]) => edit('leverage', v)" /></el-form-item>
      <el-form-item label="方向"><el-select :model-value="form.side" clearable placeholder="自动决定" aria-label="方向" @update:model-value="(v: string | number | number[]) => edit('side', v)"><el-option value="BUY" label="做多" /><el-option value="SELL" label="做空" /></el-select></el-form-item>
      <el-form-item :label="`手数 / 数量（${unit}）`"><el-input :model-value="form.quantity" clearable placeholder="自动，可超过100" aria-label="手数" @update:model-value="(v: string | number | number[]) => edit('quantity', v)" /><el-slider :model-value="sliderValue" :min="Math.min(minimum, 100)" :max="100" :step="step" :disabled="minimum > 100 || step > 100" aria-label="手数滑块" @update:model-value="(v: string | number | number[]) => edit('quantity', v)" /><small>{{ Number(form.quantity) > 100 ? '超过滑块范围，实际值保留。' : '滑块≤100，输入不限。' }}</small></el-form-item>
      <el-form-item label="净收益（USD）"><el-input :model-value="form.targetNet" clearable placeholder="自动，可为负或零" aria-label="净收益" @update:model-value="(v: string | number | number[]) => edit('targetNet', v)" /><div class="tolerance"><el-checkbox v-model="form.allowNetAdjustment" aria-label="允许净收益调整">允许调整</el-checkbox><el-input v-model="form.netTolerance" :disabled="!form.allowNetAdjustment" aria-label="净收益容差"><template #append>%</template></el-input></div></el-form-item>
      <div class="funding-options" role="group" aria-label="资金处理">
        <div class="funding-option"><span>净收益入钱包</span><el-switch v-permission="'orders:manual_order'" v-model="form.walletEnabled" :disabled="!form.userId || form.historyEnabled" aria-label="净收益入钱包" /></div>
        <div class="funding-option"><span>回填历史权益</span><el-switch v-permission="'orders:manual_order'" v-model="form.historyEnabled" :disabled="!form.userId" aria-label="回填历史权益" /></div>
        <p class="funding-hint">{{ form.userId ? '历史权益开启时，钱包入账同步开启。' : '未绑定用户：不修改资金，后续可绑定。' }}</p>
      </div>
    </el-form>
    <details class="order-rules"><summary>生成规则与资金说明</summary><p>匹配最近七天已结束分钟，开仓严格早于平仓。平仓价留空取最近已结束分钟开盘价；自动开仓价幅度0.3%～0.8%，手填价格不受此限制。图表拖选固定时间，手动改价解除时间固定。净收益支持正数、负数和零；勾选“允许调整”时按容差匹配，取消后严格计算。手数滑块上限100，输入不限。无用户不动资金；开启历史权益同时开启钱包入账。</p></details>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section v-if="result" class="simple-result" aria-live="polite">
      <div><strong>开仓 {{ result.quotes.openPrice }}</strong><span>{{ time(result.openUtc) }}</span></div>
      <div><strong>平仓 {{ result.quotes.closePrice }}</strong><span>{{ time(result.closeUtc) }}</span></div>
      <p>{{ form.side === 'BUY' ? '做多' : '做空' }} · {{ result.calculation.quantity }} {{ unit }} · {{ result.request.leverage }}× · 净收益 {{ result.calculation.net }} USD</p>
      <p v-if="conditions.targetNet">目标 {{ conditions.targetNet }}，实际 {{ result.calculation.net }}，差额 {{ result.generation.difference }}（{{ Number(result.generation.errorPercent).toFixed(4) }}%）</p>
      <p>{{ form.userId ? `钱包 ${result.walletBefore} / 确认后 ${result.walletAfter}` : '未绑定订单：不影响任何账户资金' }}；历史权益{{ form.historyEnabled ? '开启' : '关闭' }}</p>
      <details><summary>详情（{{ timezone }}）</summary><p>行情 {{ result.quotes.source }}；仅匹配分钟价格区间，不代表实际成交。手续费 {{ result.calculation.fee }}；保证金 {{ result.calculation.margin }}。</p><p>开仓K线范围 {{ result.quotes.openLow }}～{{ result.quotes.openHigh }}；平仓K线范围 {{ result.quotes.closeLow }}～{{ result.quotes.closeHigh }}。</p></details>
    </section>
    <template #footer><div class="simple-footer"><el-button v-permission="'orders:manual_order'" :disabled="busy || saving" @click="clear">清空条件</el-button><el-button v-permission="'orders:manual_order'" type="primary" :loading="busy" :disabled="saving || !form.symbol" @click="generate">一键生成</el-button><el-button v-permission="'orders:manual_order'" type="primary" :loading="saving" :disabled="!canCreate" @click="create">确认创建</el-button></div></template>
  </el-dialog>

  <el-dialog v-model="bindVisible" :title="`绑定用户 · 模拟订单 ${binding.orderId}`" width="min(560px, calc(100vw - 24px))" :close-on-click-modal="false" :close-on-press-escape="!bindSaving" :show-close="!bindSaving">
    <p class="hint">保留原订单ID、价格、时间和收益。确认后仅按以下开关处理资金。</p>
    <el-form label-position="top" :disabled="bindBusy || bindSaving">
      <el-form-item label="绑定用户" required><el-select v-model="binding.userId" filterable remote :remote-method="search" aria-label="绑定用户"><el-option v-for="u in users" :key="u.id" :value="u.id" :label="`${u.id} · ${u.email}`" /></el-select></el-form-item>
      <el-form-item label="净收益入钱包"><el-switch v-permission="'orders:manual_order'" v-model="binding.walletEnabled" :disabled="binding.historyEnabled" aria-label="绑定净收益入钱包" /></el-form-item>
      <el-form-item label="回填历史权益"><el-switch v-permission="'orders:manual_order'" v-model="binding.historyEnabled" aria-label="绑定回填历史权益" /></el-form-item>
    </el-form>
    <el-alert v-if="bindError" :title="bindError" type="error" :closable="false" />
    <section v-if="bindResult" class="simple-result"><p>订单净收益 {{ bindResult.net }} USD</p><p>钱包 {{ bindResult.walletBefore }}，确认后 {{ bindResult.walletAfter }}</p><p>钱包{{ binding.walletEnabled ? '开启' : '关闭' }}；历史权益{{ binding.historyEnabled ? '开启，按原平仓时间回填' : '关闭' }}</p></section>
    <template #footer><el-button v-permission="'orders:manual_order'" :disabled="!binding.userId || bindSaving" :loading="bindBusy" @click="previewBinding">预览资金变化</el-button><el-button v-permission="'orders:manual_order'" type="primary" :loading="bindSaving" :disabled="!bindResult || bindVerified !== bindSignature || bindBusy" @click="confirmBinding">确认绑定</el-button></template>
  </el-dialog>
</template>

<style scoped>
.hint { color: #737980; font-size: 13px; line-height: 1.5; margin: 0 0 8px; }
.simple-order-chart { margin: 4px 0 12px; }
.simple-order-chart .hint { margin: 0; }
.simple-order-chart :deep(.chart-panel) { margin-top: 8px; }
.simple-order-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 16px; }
.simple-order-form :deep(.el-form-item) { min-width: 0; margin-bottom: 10px; }
.simple-order-form :deep(.el-form-item__label) { height: auto; font-size: 13px; line-height: 20px; margin-bottom: 4px; }
.simple-order-form :deep(.el-form-item__content) { min-width: 0; display: block; line-height: 22px; }
:deep(.el-select) { width: 100%; }
.simple-order-form :deep(.el-input), .simple-order-form :deep(.el-select__wrapper) { font-size: 13px; }
.simple-order-form :deep(.el-slider) { height: 28px; padding: 0 6px; }
.simple-order-form :deep(.el-slider__button) { width: 14px; height: 14px; }
.simple-order-form small { display: block; color: #858b94; font-size: 12px; line-height: 18px; overflow-wrap: anywhere; }
.tolerance { display: flex; gap: 6px; align-items: center; margin-top: 4px; width: 100%; flex-wrap: wrap; }
.tolerance :deep(.el-checkbox) { margin-right: 0; height: 28px; }
.tolerance :deep(.el-checkbox__label) { font-size: 12px; padding-left: 5px; }
.tolerance .el-input { width: 76px; }
.tolerance :deep(.el-input-group__append) { padding: 0 8px; }
.funding-options { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; padding: 4px 10px 8px; border-radius: 6px; background: #f5f7fa; }
.funding-option { min-width: 0; display: flex; justify-content: space-between; align-items: center; gap: 6px; font-size: 13px; }
.funding-hint { grid-column: 1 / -1; color: #737980; font-size: 12px; line-height: 18px; }
.order-rules { color: #737980; font-size: 12px; line-height: 1.6; margin: 8px 0 0; }
.order-rules summary { cursor: pointer; width: fit-content; }
.order-rules p { margin-top: 4px; }
.simple-footer { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.simple-footer :deep(.el-button + .el-button) { margin-left: 0; }
:global(.simple-order-dialog) { display: flex; flex-direction: column; max-height: 92vh; }
:global(.simple-order-dialog .el-dialog__body) { min-height: 0; overflow-y: auto; }
:global(.simple-order-dialog .el-dialog__header), :global(.simple-order-dialog .el-dialog__footer) { flex-shrink: 0; }
:global(.simple-order-dialog .el-dialog__footer) { padding-top: 12px; }
.simple-result { background: #f5f7fa; border-radius: 6px; padding: 10px 12px; margin-top: 10px; font-size: 13px; line-height: 1.6; }
.simple-result > div { display: inline-flex; flex-direction: column; width: 50%; margin-bottom: 6px; overflow-wrap: anywhere; }
.simple-result span { color: #737980; }
.simple-result p { margin: 4px 0; overflow-wrap: anywhere; }
.simple-result summary { cursor: pointer; color: #737980; }
@media (min-width: 960px) {
  .simple-order-form { grid-template-columns: repeat(4, minmax(0, 1fr)); }
  .funding-options { grid-template-columns: max-content max-content minmax(0, 1fr); column-gap: 24px; padding: 4px 12px; }
  .funding-option { gap: 12px; }
  .funding-hint { grid-column: auto; align-self: center; }
}
@media (max-width: 600px) {
  .simple-order-form { column-gap: 12px; }
  .simple-order-form :deep(.el-input__wrapper), .simple-order-form :deep(.el-select__wrapper) { min-height: 36px; }
  .tolerance .el-input { width: 68px; }
  .tolerance :deep(.el-input__wrapper) { padding: 1px 6px; }
  .funding-options { gap: 0 12px; padding-left: 8px; padding-right: 8px; }
  .funding-option { font-size: 12px; }
  .simple-result > div { width: 100%; }
}
@media (max-width: 359px) {
  .simple-order-form { grid-template-columns: 1fr; }
  .funding-options { grid-template-columns: 1fr; }
}
</style>
