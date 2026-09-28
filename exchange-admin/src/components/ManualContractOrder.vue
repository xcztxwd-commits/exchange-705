<script setup lang="ts">
import { computed, reactive, ref, watch, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'

const emit = defineEmits<{ created: [] }>()
const visible = ref(false), busy = ref(false), saving = ref(false), error = ref('')
const users = ref<any[]>([]), symbols = ref<any[]>([]), account = ref<any>(null), result = ref<any>(null)
const selectedZone = ref('UTC')
const initial = () => ({ userId: null as number | null, symbol: '', timezone: 'UTC', openLocal: '', closeLocal: '', openOffset: '', closeOffset: '', side: 'BUY', leverage: '10', driver: 'QUANTITY', input: '1', walletEnabled: false, historyEnabled: false })
const form = reactive(initial())
let timer: ReturnType<typeof setTimeout> | undefined, revision = 0, lastToken = '', key = '', ready = false
const path = '/admin/orders/contract/manual'
async function search(search = '') {
  try { const data: any = await request.get(`${path}/context`, { params: { search } }); users.value = data.users; symbols.value = data.symbols; return data }
  catch (e: any) { error.value = e.message }
}
async function open() {
  ready = false; revision++; clearTimeout(timer); Object.assign(form, initial()); result.value = null; account.value = null; error.value = ''; lastToken = ''; key = crypto.randomUUID(); visible.value = true
  const data = await search()
  if (data) { form.timezone = data.timezone; selectedZone.value = data.timezone }
  ready = true
}
async function chooseUser() {
  account.value = null
  try { const data: any = await request.get(`${path}/context`, { params: { userId: form.userId } }); account.value = data.account }
  catch (e: any) { error.value = e.message }
}
function drive(driver: string, value: string | number) { form.driver = driver; form.input = String(value) }
const valueOf = (driver: string, field: string) => form.driver === driver ? form.input : (result.value?.calculation?.[field] ?? '')
const percent = computed(() => Number(valueOf('PERCENT', 'percent') || 0))
const sliderMax = computed(() => Math.max(200, Math.ceil(Math.max(0, percent.value) / 100) * 100))
const percentDisabled = computed(() => !account.value || Number(account.value.available) <= 0)
async function preview() {
  clearTimeout(timer)
  const id = ++revision; busy.value = true; result.value = null; error.value = ''
  try {
    const response: any = await request.post(`${path}/preview`, { ...form, previewToken: lastToken || undefined })
    if (id !== revision || !visible.value) return
    result.value = response; lastToken = response.previewToken
  } catch (e: any) { if (id === revision) error.value = e.message }
  finally { if (id === revision) busy.value = false }
}
watch(form, () => {
  if (!ready || saving.value) return
  revision++; result.value = null; error.value = ''; busy.value = false; clearTimeout(timer)
  if (form.userId && form.symbol && form.openLocal && form.closeLocal) timer = setTimeout(preview, 400)
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
}
async function submit() {
  if (!result.value || busy.value || saving.value) return
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
  <el-dialog v-model="visible" title="新建已平仓模拟合约单" width="760px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <el-alert title="直接创建 MANUAL_TEST / CLOSED；不真实开仓、不冻结或返还保证金。最终净收益由后端计算。" type="info" :closable="false" />
    <el-form label-width="140px" :disabled="saving" style="margin-top:16px">
      <el-form-item label="用户 ID / 邮箱"><el-select v-model="form.userId" filterable remote :remote-method="search" placeholder="输入 ID 或邮箱" style="width:100%" @change="chooseUser"><el-option v-for="u in users" :key="u.id" :value="u.id" :label="`${u.id} · ${u.email}`" /></el-select></el-form-item>
      <el-form-item label="CONTRACT 可用"><span>{{ account?.available ?? '请选择用户' }}</span></el-form-item>
      <el-form-item label="实际配置品种"><el-select v-model="form.symbol" filterable><el-option v-for="s in symbols" :key="s.symbol" :value="s.symbol" :label="`${s.symbol} · ${s.name}`" /></el-select></el-form-item>
      <el-form-item label="输入 / 显示时区"><el-select v-model="selectedZone" filterable @change="changeZone"><el-option v-for="z in [...new Set([form.timezone, 'Asia/Shanghai','Asia/Singapore','UTC','America/New_York','Europe/London'])]" :key="z" :value="z" :label="z" /></el-select></el-form-item>
      <el-form-item label="开仓分钟"><input v-model="form.openLocal" aria-label="开仓分钟" type="datetime-local" step="60" /><el-input v-model="form.openOffset" placeholder="歧义时必填偏移，如 -04:00" aria-label="开仓 UTC 偏移" /></el-form-item>
      <el-form-item label="平仓分钟"><input v-model="form.closeLocal" aria-label="平仓分钟" type="datetime-local" step="60" /><el-input v-model="form.closeOffset" placeholder="歧义时必填偏移，如 -05:00" aria-label="平仓 UTC 偏移" /></el-form-item>
      <el-form-item label="方向"><el-radio-group v-model="form.side"><el-radio value="BUY" label="BUY">做多</el-radio><el-radio value="SELL" label="SELL">做空</el-radio></el-radio-group></el-form-item>
      <el-form-item label="杠杆"><el-input v-model="form.leverage" aria-label="杠杆" /></el-form-item>
      <el-form-item label="手数（0.01 步长）"><el-input :model-value="String(valueOf('QUANTITY','quantity'))" aria-label="手数" @update:model-value="(v: string | number) => drive('QUANTITY',v)" /></el-form-item>
      <el-form-item label="仓位比例 %"><el-input :model-value="String(valueOf('PERCENT','percent'))" :disabled="percentDisabled" aria-label="仓位比例" @update:model-value="(v: string | number) => drive('PERCENT',v)" /><el-slider :model-value="percent" :min="0" :max="sliderMax" :disabled="percentDisabled" aria-label="仓位滑块" @update:model-value="(v: number | number[]) => drive('PERCENT',Number(v))" /><small>{{ percentDisabled ? '余额不为正，比例不可计算；仍可输入手数或净收益' : '允许 120% 及更高比例，数字框可扩展滑块范围' }}</small></el-form-item>
      <el-form-item label="目标净收益"><el-input :model-value="String(valueOf('NET','net'))" aria-label="目标净收益" @update:model-value="(v: string | number) => drive('NET',v)" /><small>当前驱动：{{ form.driver }}；净收益已扣一次手续费。</small></el-form-item>
      <el-form-item label="净收益入钱包"><el-switch v-model="form.walletEnabled" :disabled="form.historyEnabled" aria-label="净收益入钱包" /></el-form-item>
      <el-form-item label="回填历史权益"><el-switch v-model="form.historyEnabled" aria-label="回填历史权益" @change="historyChanged" /><small>开启历史同时开启钱包，仅调整四张权益表；未来采样直接读取钱包。</small></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="result" class="manual-preview">
      <p>取价：所选分钟 K 线 open；开 {{ result.quotes.openPrice }} / 平 {{ result.quotes.closePrice }}（{{ result.quotes.source }}）</p>
      <p>UTC：{{ result.openUtc }} 至 {{ result.closeUtc }}；偏移 {{ result.openOffset }} / {{ result.closeOffset }}</p>
      <p>每手 {{ result.lotSize }}；每手手续费 {{ result.feePerLot }}；保证金 {{ result.calculation.margin }}；实际仓位 {{ result.calculation.percent == null ? '不可计算' : result.calculation.percent + '%' }}</p>
      <p>毛盈亏 {{ result.calculation.profit }} − 手续费 {{ result.calculation.fee }} = 净收益 {{ result.calculation.net }}；目标差额 {{ result.calculation.difference }}</p>
      <p>钱包 {{ form.walletEnabled ? '开启' : '关闭' }}：{{ result.walletBefore }} → {{ result.walletAfter }}；历史 {{ form.historyEnabled ? '开启' : '关闭' }}</p>
      <p v-if="result.history">历史基础 {{ result.history.base }}（{{ result.history.baseSource }}），已有有效点 {{ result.history.existingValidCount }}，补点/修复 {{ result.history.insertOrRepair }}；范围 {{ new Date(result.history.from).toISOString() }} 至 {{ new Date(result.history.through).toISOString() }}</p>
      <p v-for="period in result.history?.closePeriods || []" :key="period.table">{{ period.table }}：{{ new Date(period.start).toISOString() }} 至 {{ new Date(period.end).toISOString() }}；{{ period.eligible ? '到期修复' : '等待周期封闭、正常延迟及下级归集' }}</p>
    </div>
    <template #footer><el-button :loading="busy" :disabled="saving" @click="preview">重新预览</el-button><el-button type="primary" :disabled="!result || busy" :loading="saving" @click="submit">确认创建已平仓单</el-button></template>
  </el-dialog>
</template>
