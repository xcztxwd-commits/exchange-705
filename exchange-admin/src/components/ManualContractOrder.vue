<script setup lang="ts">
import { computed, reactive, ref, watch, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import MarketMinutePicker from './MarketMinutePicker.vue'
import { manualOrderEstimate } from '@/utils/manualOrderEstimate'
import { formatPrice } from '@/utils/formatPrice'
import { generationConstraints, generationRequest, generationConstraintError } from '@/utils/manualOrderGeneration'

const emit = defineEmits<{ created: [] }>()
const visible = ref(false), busy = ref(false), saving = ref(false), error = ref('')
const users = ref<any[]>([]), symbols = ref<any[]>([]), account = ref<any>(null), result = ref<any>(null)
const selectedZone = ref('UTC')
const details = ref<string[]>([]), basis = ref<any>(null), basisKey = ref(''), verifiedKey = ref('')
const initial = () => ({ specVersion: null as number | null, quantityUnitType: null as string | null, userId: null as number | null, symbol: '', timezone: 'UTC', openLocal: '', closeLocal: '', openOffset: '', closeOffset: '', side: '', leverage: '', driver: 'QUANTITY', input: '', targetNet: null as string | null, walletEnabled: false, historyEnabled: false })
const form = reactive(initial())
const conditions = reactive(generationConstraints())
const generating = ref(false), generation = ref<any>(null)
let timer: ReturnType<typeof setTimeout> | undefined, revision = 0, userRevision = 0, lastToken = '', key = '', ready = false
const path = '/admin/orders/contract/manual'
watch(visible, (value) => {
  if (!value) { revision++; userRevision++; clearTimeout(timer); ready = false; busy.value = false; generating.value = false }
})
const quoteKey = () => JSON.stringify([form.userId, form.symbol, form.timezone, form.openLocal, form.closeLocal, form.openOffset, form.closeOffset])
const previewCurrent = computed(() => result.value && verifiedKey.value === JSON.stringify(form))
const constraintError = computed(() => previewCurrent.value ? generationConstraintError(result.value.calculation, conditions, !!generation.value, result.value.quotes?.closePrice, result.value.request?.leverage) : '')
const verified = computed(() => previewCurrent.value && !constraintError.value)
const calculation = computed(() => verified.value ? result.value.calculation : basisKey.value === quoteKey() ? manualOrderEstimate(form, basis.value) : null)
const quantityInput = computed(() => conditions.quantity || (calculation.value?.quantity == null ? '' : String(calculation.value.quantity)))
const percentInput = computed(() => conditions.percent || (calculation.value?.percent == null ? '' : String(calculation.value.percent)))
const netInput = computed(() => conditions.net || (calculation.value?.net == null ? '' : String(calculation.value.net)))
const generatedAdjustments = computed(() => {
  if (!generation.value || !result.value) return ''
  const g = generation.value, c = result.value.calculation, items: string[] = []
  if (g.leverageTarget != null) items.push(`目标杠杆 ${g.leverageTarget}，实际 ${form.leverage}，偏差 ${Number(g.leverageErrorPercent).toFixed(4)}%`)
  if (g.quantityTarget != null) items.push(`目标数量 ${g.quantityTarget}，实际 ${c.quantity}，偏差 ${Number(g.quantityErrorPercent).toFixed(4)}%`)
  if (g.percentTarget != null) items.push(`目标仓位 ${g.percentTarget}%，实际 ${c.percent}%，偏差 ${Number(g.percentErrorPercent).toFixed(4)}%`)
  if (g.closePriceTarget != null) items.push(`目标平仓价 ${g.closePriceTarget}，实际 ${result.value.quotes.closePrice}，偏差 ${Number(g.closePriceErrorPercent).toFixed(4)}%`)
  return items.join('；')
})
const balanceBefore = computed(() => basisKey.value === quoteKey() && basis.value ? basis.value.walletBefore : account.value?.available)
const walletAfter = computed(() => calculation.value && balanceBefore.value != null ? Number((Number(balanceBefore.value) + (form.walletEnabled ? Number(calculation.value.net) : 0)).toFixed(8)) : null)
const marketCategory = computed(() => { const s = symbols.value.find(s => s.symbol === form.symbol); return s?.source_category || s?.category || '' })
const unitLabel = computed(() => { const s=symbols.value.find(s => s.symbol===form.symbol); return s?.quantity_unit_type === 'BASE_ASSET' ? s.base_currency : s?.quantity_unit_type === 'SHARE' ? '股' : '手' })
watch(() => form.symbol, () => { const s=symbols.value.find(s => s.symbol===form.symbol); form.specVersion=s?.spec_version ?? null; form.quantityUnitType=s?.quantity_unit_type ?? null; form.openLocal = ''; form.closeLocal = ''; form.openOffset = ''; form.closeOffset = ''; conditions.openLocal = ''; conditions.closeLocal = ''; conditions.openOffset = ''; conditions.closeOffset = ''; lastToken = '' })
watch(conditions, () => { generation.value = null })
watch(() => conditions.closePrice, () => { result.value = null; verifiedKey.value = '' })
async function search(search = '') {
  try { const data: any = await request.get(`${path}/context`, { params: { search } }); users.value = data.users; symbols.value = data.symbols; return data }
  catch (e: any) { error.value = e.message }
}
async function open() {
  details.value = []; basis.value = null; basisKey.value = ''; verifiedKey.value = ''
  Object.assign(conditions, generationConstraints()); generation.value = null
  ready = false; revision++; clearTimeout(timer); busy.value = false; generating.value = false; Object.assign(form, initial()); result.value = null; account.value = null; error.value = ''; lastToken = ''; key = crypto.randomUUID(); visible.value = true
  const data = await search()
  if (data) { form.timezone = data.timezone; selectedZone.value = data.timezone }
  ready = true
}
async function chooseUser() {
  const id = ++userRevision, userId = form.userId
  account.value = null
  try { const data: any = await request.get(`${path}/context`, { params: { userId } }); if (id === userRevision && visible.value && userId === form.userId) account.value = data.account }
  catch (e: any) { if (id === userRevision && visible.value) error.value = e.message }
}
function drive(driver: 'QUANTITY' | 'PERCENT' | 'NET', value: string | number) {
  form.driver = driver; form.input = String(value)
  const field = ({ QUANTITY: 'quantity', PERCENT: 'percent', NET: 'net' } as const)[driver]
  conditions[field] = String(value)
  form.targetNet = conditions.net.trim() ? conditions.net : null
}
function setSide(value: string) { conditions.side = value || ''; form.side = conditions.side }
function setLeverage(value: string | number) { conditions.leverage = String(value); form.leverage = conditions.leverage }
function clearFields() {
  Object.assign(conditions, generationConstraints())
  Object.assign(form, { openLocal: '', closeLocal: '', openOffset: '', closeOffset: '', side: '', leverage: '', driver: 'QUANTITY', input: '', targetNet: null })
  result.value = null; basis.value = null; generation.value = null; lastToken = ''
}
function selectMinute(field: 'open' | 'close', local: string, offset: string) {
  conditions[`${field}Local`] = local; conditions[`${field}Offset`] = offset
  form[`${field}Local`] = local; form[`${field}Offset`] = offset
}
async function generate() {
  if (generating.value || saving.value) return
  let payload: Record<string, unknown>
  try { payload = { ...generationRequest(form, conditions), specVersion: form.specVersion, quantityUnitType: form.quantityUnitType } } catch (e: any) { error.value = e.message; return }
  clearTimeout(timer); const id = ++revision; generating.value = true; busy.value = false; result.value = null; error.value = ''; generation.value = null
  try {
    const response: any = await request.post(`${path}/generate`, payload, { timeout: 120000 })
    if (id !== revision || !visible.value) return
    ready = false
    for (const field of Object.keys(initial()) as Array<keyof ReturnType<typeof initial>>) (form as any)[field] = response.request[field]
    form.input = String(form.input); form.leverage = String(form.leverage); form.targetNet = form.targetNet == null ? null : String(form.targetNet)
    await nextTick()
    result.value = response; lastToken = response.previewToken; verifiedKey.value = JSON.stringify(form)
    basis.value = response; basisKey.value = quoteKey(); generation.value = response.generation
    key = crypto.randomUUID(); ElMessage.success('已生成并完成预览，确认前不会创建订单或调整资金')
  } catch (e: any) { if (id === revision) error.value = e.message }
  finally { if (id === revision) { ready = true; generating.value = false } }
}
const percent = computed(() => Number(percentInput.value) || 0)
const sliderMax = computed(() => Math.max(200, Math.ceil(Math.max(0, percent.value) / 100) * 100))
const percentDisabled = computed(() => !account.value || Number(account.value.available) <= 0)
async function preview() {
  clearTimeout(timer)
  const id = ++revision; busy.value = true; result.value = null; error.value = ''
  const requestKey = JSON.stringify(form), priceKey = quoteKey()
  try {
    const response: any = await request.post(`${path}/preview`, { ...form, previewToken: lastToken || undefined }, { timeout: 30000 })
    if (id !== revision || !visible.value) return
    result.value = response; lastToken = response.previewToken; verifiedKey.value = requestKey
    basis.value = response; basisKey.value = priceKey
  } catch (e: any) { if (id === revision) error.value = e.code === 'ECONNABORTED' ? '预览请求超时，请重试' : e.message }
  finally { if (id === revision) busy.value = false }
}
watch(form, () => {
  if (!ready || saving.value || generating.value) return
  generation.value = null
  revision++; result.value = null; error.value = ''; busy.value = false; clearTimeout(timer)
  if (form.userId && form.symbol && form.openLocal && form.closeLocal && form.side && form.leverage.trim() && form.input.trim()) timer = setTimeout(preview, 400)
})
function historyChanged(value: unknown) { if (value) form.walletEnabled = true }
function localAt(utc: string, zone: string) {
  const parts = new Intl.DateTimeFormat('sv-SE', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(utc))
  const get = (type: string) => parts.find(p => p.type === type)?.value
  return `${get('year')}-${get('month')}-${get('day')}T${get('hour')}:${get('minute')}`
}
function changeZone(zone: string) {
  if (!form.openLocal && !form.closeLocal) { form.timezone = zone; return }
  if (!result.value) { selectedZone.value = form.timezone; ElMessage.warning('先完成有效预览，再切换显示时区；绝对时刻保持不变'); return }
  const p = result.value
  form.openLocal = localAt(p.openUtc, zone); form.closeLocal = localAt(p.closeUtc, zone)
  const offset = (utc: string) => new Intl.DateTimeFormat('en', { timeZone: zone, timeZoneName: 'longOffset' }).formatToParts(new Date(utc)).find(x => x.type === 'timeZoneName')!.value.replace('GMT', '') || '+00:00'
  form.openOffset = offset(p.openUtc); form.closeOffset = offset(p.closeUtc); form.timezone = zone
  if (conditions.openLocal) { conditions.openLocal = form.openLocal; conditions.openOffset = form.openOffset }
  if (conditions.closeLocal) { conditions.closeLocal = form.closeLocal; conditions.closeOffset = form.closeOffset }
}
async function submit() {
  if (!verified.value || busy.value || saving.value) return
  saving.value = true; error.value = ''
  try {
    const response: any = await request.post(path, { ...form, previewToken: result.value.previewToken, idempotencyKey: key }, { timeout: 60000 })
    ElMessage.success(`已创建手动订单 #${response.orderId}`); visible.value = false; emit('created')
  } catch (e: any) { error.value = `${e.message}；网络失败可用本表单原幂等键重试。` }
  finally { saving.value = false }
}
onBeforeUnmount(() => clearTimeout(timer))
defineExpose({ open })
</script>

<template>
  <el-dialog v-model="visible" title="生成订单" width="min(820px, calc(100vw - 24px))" top="5vh" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <el-alert class="generation-hint" title="平仓分钟留空时取最近有行情的分钟，并在此前最多 7 天找开仓时间。杠杆、数量、仓位、净收益、目标平仓价可同时设置 ±5% 目标；已填时间、方向及开关保持不变；真实行情价不会被修改。" type="info" :closable="false" />
    <el-form class="manual-form" label-position="top" :disabled="saving || generating">
      <el-form-item label="用户 ID / 邮箱"><el-select v-model="form.userId" filterable remote :remote-method="search" placeholder="输入 ID 或邮箱" style="width:100%" @change="chooseUser"><el-option v-for="u in users" :key="u.id" :value="u.id" :label="`${u.id} · ${u.email}`" /></el-select></el-form-item>
      <el-form-item class="balance" label="合约可用余额"><span>{{ balanceBefore ?? '请选择用户' }}<template v-if="walletAfter !== null"> {{ form.walletEnabled && Number(calculation.net) < 0 ? '−' : '+' }} {{ form.walletEnabled ? Math.abs(Number(calculation.net)) : 0 }} = <strong>{{ verified ? result.walletAfter : walletAfter }}</strong></template></span><small v-if="calculation">{{ form.walletEnabled ? '预计入账后余额' : '未开启入钱包，余额不变' }}{{ verified ? '' : ' · 实时估算' }}</small></el-form-item>
      <el-form-item label="实际配置品种"><el-select v-model="form.symbol" filterable><el-option v-for="s in symbols" :key="s.symbol" :value="s.symbol" :label="`${s.symbol} · ${s.name}`" /></el-select></el-form-item>
      <el-form-item label="输入 / 显示时区"><el-select v-model="selectedZone" filterable @change="changeZone"><el-option v-for="z in [...new Set([form.timezone, 'Asia/Shanghai','Asia/Singapore','Asia/Tokyo','UTC','America/New_York','Europe/London'])]" :key="z" :value="z" :label="z" /></el-select></el-form-item>
      <el-form-item label="开仓分钟"><MarketMinutePicker :model-value="conditions.openLocal" :offset="conditions.openOffset" :symbol="form.symbol" :category="marketCategory" :timezone="form.timezone" :active="visible && !generating" :price="conditions.openLocal ? result?.quotes.openPrice : undefined" label="开仓分钟" @confirm="(local, offset) => selectMinute('open', local, offset)" /><el-button v-permission="'orders:manual_order'" v-if="conditions.openLocal" text @click="selectMinute('open', '', '')">清空时间</el-button><small v-if="result && !conditions.openLocal">自动结果：{{ form.openLocal.replace('T', ' ') }}</small></el-form-item>
      <el-form-item label="平仓分钟"><MarketMinutePicker :model-value="conditions.closeLocal" :offset="conditions.closeOffset" :symbol="form.symbol" :category="marketCategory" :timezone="form.timezone" :active="visible && !generating" :price="conditions.closeLocal ? result?.quotes.closePrice : undefined" label="平仓分钟" @confirm="(local, offset) => selectMinute('close', local, offset)" /><el-button v-permission="'orders:manual_order'" v-if="conditions.closeLocal" text @click="selectMinute('close', '', '')">清空时间</el-button><small v-if="result && !conditions.closeLocal">自动结果：{{ form.closeLocal.replace('T', ' ') }}</small></el-form-item>
      <el-form-item label="目标平仓价（允许 ±5%）"><el-input v-model="conditions.closePrice" clearable placeholder="留空不限；价格取平仓分钟 K 线 open" aria-label="目标平仓价" /><small v-if="result && !conditions.closePrice">实际价格：{{ formatPrice(result.quotes.closePrice) }}</small></el-form-item>
      <el-form-item class="third" label="方向"><el-select :model-value="conditions.side" clearable placeholder="自动决定" aria-label="方向" @update:model-value="(v: string) => setSide(v)"><el-option value="BUY" label="做多" /><el-option value="SELL" label="做空" /></el-select><small v-if="result && !conditions.side">自动结果：{{ form.side === 'BUY' ? '做多' : '做空' }}</small></el-form-item>
      <el-form-item class="third" label="目标杠杆（允许 ±5%）"><el-input :model-value="conditions.leverage" clearable placeholder="自动决定" aria-label="杠杆" @update:model-value="(v: string) => setLeverage(v)" /><small v-if="result && !conditions.leverage">自动结果：{{ form.leverage }}</small></el-form-item>
      <el-form-item class="third" :label="'数量（' + unitLabel + '）'"><el-input :model-value="quantityInput" clearable placeholder="自动决定" :aria-label="'数量（' + unitLabel + '）'" @update:model-value="(v: string | number) => drive('QUANTITY',v)" /></el-form-item>
      <el-form-item label="仓位比例 %"><el-input :model-value="percentInput" clearable placeholder="自动决定" :disabled="saving || generating || percentDisabled" aria-label="仓位比例" @update:model-value="(v: string | number) => drive('PERCENT',v)" /><el-slider :model-value="percent" :min="0" :max="sliderMax" :disabled="saving || generating || percentDisabled" aria-label="仓位滑块" @update:model-value="(v: number | number[]) => drive('PERCENT',Number(v))" /><small>{{ percentDisabled ? '余额不为正，比例不可计算；仍可输入数量或净收益' : '允许 120% 及更高比例；拖动滑块会填写比例，清空数字则交给生成器' }}</small></el-form-item>
      <el-form-item label="目标净收益"><el-input :model-value="netInput" clearable placeholder="留空表示不限" aria-label="目标净收益" @update:model-value="(v: string | number) => drive('NET',v)" /><small v-if="calculation">毛盈亏 {{ calculation.profit }} − 手续费 {{ calculation.fee }} = 净收益 <strong>{{ calculation.net }}</strong>{{ verified ? '' : '（实时估算，最终以后端为准）' }}</small><small v-else>可先填写目标净收益，点击一键生成；也可手动选时间预览。</small></el-form-item>
      <el-form-item class="toggle" label="净收益入钱包"><el-switch v-permission="'orders:view'" v-model="form.walletEnabled" :disabled="saving || generating || form.historyEnabled" aria-label="净收益入钱包" /></el-form-item>
      <el-form-item class="toggle" label="回填历史权益"><el-switch v-permission="'orders:manual_order'" v-model="form.historyEnabled" aria-label="回填历史权益" @change="historyChanged" /></el-form-item>
      <small class="history-hint">开启历史同时开启钱包，仅调整四张权益表；未来采样直接读取钱包。</small>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-else-if="constraintError" :title="constraintError" type="warning" :closable="false" />
    <el-alert v-if="generation" class="generation-result" :title="`已生成 · 持仓 ${generation.durationMinutes} 分钟 · ${generation.targetNet == null ? '实际净收益 ' + result.calculation.net : '目标净收益 ' + generation.targetNet + '，实际 ' + result.calculation.net + '，误差 ' + Number(generation.errorPercent).toFixed(4) + '%'}`" :description="[generation.closeAutomaticallySelected ? '平仓取最近有效分钟 ' + form.closeLocal.replace('T',' ') : '', generatedAdjustments, generation.warning, '搜索范围 ' + localAt(generation.from, form.timezone).replace('T',' ') + ' 至 ' + localAt(generation.to, form.timezone).replace('T',' ')].filter(Boolean).join('；')" type="success" :closable="false" />
    <el-collapse v-if="result" v-model="details" class="manual-preview"><el-collapse-item title="详情" name="details">
      <p>取价：所选分钟 K 线 open；开 {{ formatPrice(result.quotes.openPrice) }} / 平 {{ formatPrice(result.quotes.closePrice) }}（{{ result.quotes.source }}）</p>
      <p>时间（{{ form.timezone }}）：{{ localAt(result.openUtc, form.timezone).replace('T', ' ') }} 至 {{ localAt(result.closeUtc, form.timezone).replace('T', ' ') }}；偏移 {{ result.openOffset }} / {{ result.closeOffset }}</p>
      <p>每1 {{ unitLabel }} 资产数量 {{ result.lotSize }}；每1 {{ unitLabel }} 固定往返佣金 {{ result.feePerLot }}；保证金 {{ result.calculation.margin }}；实际仓位 {{ result.calculation.percent == null ? '不可计算' : result.calculation.percent + '%' }}</p>
      <p>毛盈亏 {{ result.calculation.profit }} − 手续费 {{ result.calculation.fee }} = 净收益 {{ result.calculation.net }}；目标差额 {{ result.calculation.difference }}</p>
      <p>钱包 {{ form.walletEnabled ? '开启' : '关闭' }}：{{ result.walletBefore }} → {{ result.walletAfter }}；历史 {{ form.historyEnabled ? '开启' : '关闭' }}</p>
      <p v-if="result.history">历史基础 {{ result.history.base }}（{{ result.history.baseSource }}），已有有效点 {{ result.history.existingValidCount }}，补点/修复 {{ result.history.insertOrRepair }}；范围 {{ localAt(result.history.from, form.timezone).replace('T', ' ') }} 至 {{ localAt(result.history.through, form.timezone).replace('T', ' ') }}</p>
      <p v-for="period in result.history?.closePeriods || []" :key="period.table">{{ period.table }}：{{ localAt(period.start, form.timezone).replace('T', ' ') }} 至 {{ localAt(period.end, form.timezone).replace('T', ' ') }}；{{ period.eligible ? '到期修复' : '等待周期封闭、正常延迟及下级归集' }}</p>
    </el-collapse-item></el-collapse>
    <template #footer><el-button v-permission="'orders:manual_order'" :disabled="saving || generating" @click="clearFields">清空填写条件</el-button><el-button v-permission="'orders:manual_order'" type="primary" :loading="generating" :disabled="saving || !form.userId || !form.symbol" @click="generate">一键生成</el-button><el-button v-permission="'orders:manual_order'" :loading="busy" :disabled="saving || generating || !form.userId || !form.symbol || !form.openLocal || !form.closeLocal || !form.side || !form.leverage.trim() || !form.input.trim()" @click="preview">重新预览</el-button><el-button v-permission="'orders:manual_order'" type="primary" :disabled="!verified || busy || generating" :loading="saving" @click="submit">确认创建已平仓单</el-button></template>
  </el-dialog>
</template>

<style scoped>
.manual-form {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px 20px;
  margin-top: 16px;
}
.generation-hint, .generation-result { margin-top: 12px; }
.manual-form :deep(.el-form-item) {
  grid-column: span 3;
  min-width: 0;
  margin-bottom: 0;
}
.manual-form :deep(.el-form-item.third) { grid-column: span 2; }
.manual-form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 5px;
  line-height: 20px;
}
.manual-form :deep(.el-form-item__content) { min-width: 0; gap: 5px; }
.manual-form :deep(.el-select) { width: 100%; }
.manual-form small { color: var(--el-text-color-secondary); line-height: 1.5; }
.manual-form :deep(.el-slider) { width: calc(100% - 16px); margin: 0 8px; }
.manual-form input[type="datetime-local"] {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-regular);
  font: inherit;
}
.manual-form input[type="datetime-local"]:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 1px;
}
.balance :deep(.el-form-item__content) {
  min-height: 32px;
  padding: 0 12px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  font-variant-numeric: tabular-nums;
}
.manual-form :deep(.toggle) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
}
.manual-form :deep(.toggle .el-form-item__label) { margin: 0; }
.manual-form :deep(.toggle .el-form-item__content) { flex: none; }
.history-hint { grid-column: 1 / -1; margin-top: -6px; }
.manual-form + .el-alert { margin-top: 12px; }
.manual-preview {
  margin-top: 12px;
  padding: 8px 12px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  overflow-wrap: anywhere;
  font-size: 12px;
  line-height: 1.6;
}
.manual-preview p { margin: 4px 0; }
@media (max-width: 600px) {
  .manual-form { grid-template-columns: minmax(0, 1fr); gap: 12px; }
  .manual-form :deep(.el-form-item),
  .manual-form :deep(.el-form-item.third) { grid-column: 1; }
}
</style>
