<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import request from '@/utils/request'
import { formatPrice } from '@/utils/formatPrice'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { isCryptoMarket, marketMinuteHours } from '@/utils/marketMinuteHours'

const props = defineProps<{ modelValue: string; offset: string; symbol: string; category?: string; timezone: string; label: string; active: boolean; price?: string | number }>()
const emit = defineEmits<{ confirm: [value: string, offset: string] }>()
type Minute = { timestamp: number; local: string; offset: string; price: string }
const visible = ref(false), date = ref(''), hour = ref(''), selected = ref<number>()
const rows = ref<Minute[]>([]), confirmed = ref<Minute>(), loading = ref(false), message = ref('')
const month = ref(''), calendarRows = ref<Minute[]>([])
const dates = computed(() => [...new Set(calendarRows.value.map(row => row.local.slice(0,10)))].sort().reverse())
let revision = 0, timer: ReturnType<typeof setTimeout> | undefined
const cryptoMarket = computed(() => isCryptoMarket(props.category || ''))
const hours = computed(() => marketMinuteHours(props.category || '', rows.value.map(row => row.local)))
const minutes = computed(() => rows.value.filter(row => row.local.slice(11, 13) === hour.value))
const draft = computed(() => rows.value.find(row => row.timestamp === selected.value))
function today() { return new Intl.DateTimeFormat('sv-SE', { timeZone: props.timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()) }
function disabledDate(value: Date) {
  const local = `${value.getFullYear()}-${String(value.getMonth()+1).padStart(2,'0')}-${String(value.getDate()).padStart(2,'0')}`
  return local > today() || local < '1970-01-01'
}
function stop() { revision++; clearTimeout(timer); loading.value = false }
async function loadCalendar() {
  stop(); const id = revision
  calendarRows.value = []; rows.value = []; date.value = ''; hour.value = ''; selected.value = undefined
  if (!month.value) return
  loading.value = true; let failed = 0
  try {
    // Five local days per request stay below Yahoo's single-request minute span limit.
    for (let page = 0; page < 7; page++) {
      if (id !== revision || !visible.value) return
      message.value = `正在验证 ${month.value} 的历史日期（${page + 1}/7）；未验证日期不可进入。`
      try {
        const data: any = await request.get('/admin/orders/contract/manual/calendar', { params: { symbol: props.symbol, month: month.value, timezone: props.timezone, page } })
        if (id !== revision || !visible.value) return
        calendarRows.value.push(...data.minutes)
      } catch { if (id !== revision) return; failed++ }
    }
    message.value = failed ? `${failed} 段历史查询失败或超出 Yahoo 分钟保留范围；未验证日期保持禁用，可重新获取。` : !dates.value.length ? '该月未返回有效历史分钟；所有日期均不可进入。' : '历史日期验证完成，请依次选择日期、小时、分钟。'
  } finally { if (id === revision) loading.value = false }
}
function chooseDate() {
  rows.value = calendarRows.value.filter(row => row.local.startsWith(`${date.value}T`))
  hour.value = ''; selected.value = undefined
}
async function fetchMinutes(id: number, attempt = 0) {
  if (!date.value || !props.symbol) return
  loading.value = true
  try {
    const data: any = await request.get('/admin/orders/contract/manual/minutes', { params: { symbol: props.symbol, date: date.value, timezone: props.timezone } })
    if (id !== revision || !visible.value) return
    rows.value = data.minutes
    message.value = data.status === 'loading' ? '正在获取历史分钟行情…' : data.status === 'unavailable' ? '行情接口暂不可用，请重试；并非已确认休市。' : data.status === 'stale' ? '显示历史缓存，接口刷新暂不可用。' : !rows.value.length ? (cryptoMarket.value ? '加密货币全天交易；该日行情暂未返回或超出数据源覆盖范围，并非休市。' : '该日未返回有效分钟行情，可能休市或数据源未覆盖。') : ''
    if (!hours.value.includes(hour.value)) hour.value = hours.value[0] || ''
    if (!rows.value.some(row => row.timestamp === selected.value)) selected.value = undefined
    if (data.pending && attempt < 15) { timer = setTimeout(() => fetchMinutes(id, attempt + 1), 2000); return }
    if (data.pending) message.value = '历史行情仍在排队，点击重新获取；未返回的分钟不可选。'
  } catch (e: any) { if (id === revision) message.value = e.message || '行情获取失败，请重试' }
  finally { if (id === revision) loading.value = false }
}
function reload() {
  if (!cryptoMarket.value) { void loadCalendar(); return }
  stop(); rows.value = []; selected.value = undefined; message.value = ''; void fetchMinutes(revision)
}
function open() {
  stop(); rows.value = []; selected.value = undefined; hour.value = props.modelValue.slice(11,13)
  date.value = props.modelValue.slice(0,10) || today(); month.value = date.value.slice(0,7); visible.value = true; reload()
}
function confirm() {
  if (!draft.value) return
  confirmed.value = { ...draft.value }; emit('confirm', draft.value.local, draft.value.offset); visible.value = false
}
watch(visible, value => { if (!value) stop() })
watch(() => [props.symbol, props.category, props.timezone, props.active], () => { visible.value = false; stop(); confirmed.value = undefined })
watch(() => props.modelValue, value => { if (!value) confirmed.value = undefined })
onBeforeUnmount(stop)
</script>

<template>
  <div class="minute-field">
    <el-button v-permission="'orders:manual_order'" class="minute-trigger" :disabled="!symbol || !active" @click="open">{{ modelValue ? modelValue.replace('T', ' ') : '选择日期与交易分钟' }} <span>▦</span></el-button>
    <div class="minute-price"><small>该分钟开盘价</small><strong>{{ formatPrice(price ?? confirmed?.price) }}</strong></div>
  </div>
  <el-dialog v-model="visible" :title="`选择${label}`" width="560px" append-to-body :close-on-click-modal="false">
    <div class="minute-meta">{{ symbol }} · {{ timezone }}</div>
    <p class="minute-help">{{ cryptoMarket ? '加密货币 · 7×24 小时，不存日历，按所选日期及时区查询行情。' : 'Yahoo 历史实时日历：只有已验证存在分钟开盘价的日期才能进入，不生成未来日历。' }}</p>
    <el-config-provider :locale="zhCn">
      <el-date-picker v-if="cryptoMarket" v-model="date" type="date" value-format="YYYY-MM-DD" format="YYYY 年 MM 月 DD 日" :clearable="false" :editable="false" :disabled-date="disabledDate" @change="reload" />
      <template v-else>
        <el-date-picker v-model="month" type="month" value-format="YYYY-MM" format="YYYY 年 MM 月" :clearable="false" :editable="false" :disabled-date="disabledDate" @change="loadCalendar" />
        <el-select v-model="date" aria-label="已验证历史日期" placeholder="选择已验证有行情的日期" :disabled="!dates.length" style="margin-top:12px" @change="chooseDate"><el-option v-for="day in dates" :key="day" :value="day" :label="day" /></el-select>
      </template>
    </el-config-provider>
    <div class="minute-selects">
      <el-select v-model="hour" placeholder="小时" :disabled="!hours.length" @change="selected = undefined"><el-option v-for="h in hours" :key="h" :value="h" :label="`${h} 时`" /></el-select>
      <el-select v-model="selected" placeholder="选择分钟查看开盘价" :disabled="!minutes.length" filterable><el-option v-for="m in minutes" :key="m.timestamp" :value="m.timestamp" :label="`${m.local.slice(11)} · UTC${m.offset === 'Z' ? '+00:00' : m.offset}`" /></el-select>
    </div>
    <p v-if="cryptoMarket && hour && !minutes.length" class="minute-help">该小时暂无可用历史分钟价格（或时间尚未发生），不是休市；可切换小时或重新获取。</p>
    <div class="minute-quote"><small>所选分钟 K 线开盘价</small><strong>{{ formatPrice(draft?.price) }}</strong><span>{{ draft ? `${draft.local.replace('T',' ')} · UTC${draft.offset === 'Z' ? '+00:00' : draft.offset}` : '选择分钟后展示价格，点击确定才应用' }}</span></div>
    <p v-if="message" role="status" class="minute-help">{{ message }}</p>
    <el-button v-permission="'orders:manual_order'" text :loading="loading" @click="reload">重新获取行情</el-button>
    <template #footer><el-button v-permission="'session:close'" @click="visible = false">取消</el-button><el-button v-permission="'orders:manual_order'" type="primary" :disabled="!draft" @click="confirm">确定时间</el-button></template>
  </el-dialog>
</template>

<style scoped>
.minute-field{display:flex;gap:12px;width:100%;align-items:stretch;flex-wrap:wrap}
.minute-trigger{flex:1;min-width:220px;justify-content:space-between;height:48px;border-radius:8px}
.minute-trigger span{margin-left:16px;color:var(--el-color-primary)}
.minute-price{min-width:140px;padding:4px 14px;background:var(--el-fill-color-light);border-radius:8px;display:flex;flex-direction:column;line-height:20px}
.minute-price small,.minute-help,.minute-meta{color:var(--el-text-color-secondary)}
.minute-price strong{color:var(--el-color-primary);font-variant-numeric:tabular-nums}
.minute-meta{font-size:13px}.minute-help{font-size:13px;line-height:1.7}
.minute-selects{display:flex;gap:12px;margin:16px 0}.minute-selects>:first-child{width:120px}.minute-selects>:last-child{flex:1}
.minute-quote{display:flex;flex-direction:column;gap:8px;padding:20px;border:1px solid var(--el-border-color-lighter);border-radius:12px;background:var(--el-fill-color-light)}
.minute-quote strong{font-size:28px;color:var(--el-color-primary);font-variant-numeric:tabular-nums}.minute-quote span{font-size:12px;color:var(--el-text-color-secondary)}
</style>
