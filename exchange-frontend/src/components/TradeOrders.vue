<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import TradeSheet from './TradeSheet.vue'
import request from '@/utils/request'
import { useMarketStore } from '@/store/market'
import { useLocaleStore } from '@/store/locale'
import { calculateContractProfit, estimateLiquidationPrice } from '@/utils/contract'
import { orderTimestamp, protectionError, validIncrement } from '@/utils/tradeValidation'
import { displaySymbol } from '@/utils/displaySymbol'

const props = defineProps<{ mode: 'contract' | 'term'; symbol: string; catalog: any[]; tab: string; revision: number }>()
const emit = defineEmits<{ counts: [open: number, pending: number]; changed: [] }>()
const market = useMarketStore()
const locale = useLocaleStore()
const text = (zh: string, en: string) => locale.locale.startsWith('zh') ? zh : en
const orders = ref<any[]>([])
const loading = ref(false)
const error = ref('')
const onlyCurrent = ref(true)
const selected = ref<any>(null)
const action = ref<'detail' | 'close' | 'cancel'>('detail')
const busy = ref(false)
const takeProfit = ref<number | string>('')
const stopLoss = ref<number | string>('')
const now = ref(Date.now())
const available = ref<number | null>(null)
let serverOffset = 0
let lastRefresh = 0
let generation = 0
let timer: ReturnType<typeof setInterval>
let disposed = false
const currentOrders = computed(() => orders.value.filter(o => !onlyCurrent.value || o.symbol === props.symbol))
const visibleOrders = computed(() => currentOrders.value.filter(o => o.status === (props.mode === 'term' ? props.tab === 'history' ? 'CLOSED' : 'TRADING' : props.tab === 'pending' ? 'PENDING' : 'OPEN')))
watch(currentOrders, items => emit('counts', items.filter(o => ['OPEN', 'TRADING'].includes(o.status)).length, items.filter(o => o.status === 'PENDING').length))
function info(order: any) { return props.catalog.find(s => s.symbol === order.symbol) }
function name(order: any) {
  const label = displaySymbol(order)
  if (label !== order.symbol) return label
  const s = info(order)
  return s?.category === 'Forex' || s?.sourceCategory === 'Forex' ? displaySymbol(s) : s?.baseCurrency && s?.quoteCurrency ? `${s.baseCurrency}/${s.quoteCurrency}` : label
}
function money(value: any) { return value != null && Number.isFinite(Number(value)) ? Number(value).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—' }
function price(value: any, order: any) { return Number(value) > 0 ? Number(value).toFixed(info(order)?.pricePrecision ?? 2) : '—' }
function livePrice(order: any) { return market.getQuoteStatus(order.symbol, now.value) === 'available' ? market.getPrice(order.symbol) : NaN }
function profit(order: any) { return order.status === 'OPEN' ? (Number.isFinite(livePrice(order)) ? calculateContractProfit(order, livePrice(order), market.getConversionRate(order.symbol, order.quoteCurrency)) : NaN) : Number(order.profit) }
const totalProfit = computed(() => visibleOrders.value.reduce((sum, order) => sum + profit(order), 0))
const margin = computed(() => visibleOrders.value.reduce((sum, order) => sum + Number(order.margin || 0), 0))
function liquidation(order: any) {
  if (available.value == null || order.status !== 'OPEN') return null
  const quote = livePrice(order), pnl = profit(order)
  // Re-express this existing position as the helper's proposed position; retain its
  // margin and unrealized P/L in equity, and do not charge the opening fee twice.
  const equity = available.value + Number(order.margin) + pnl + (order.lotSize == null ? Number(order.fee || 0) : 0)
  const positions = orders.value.filter(o => o.status === 'OPEN' && o.id !== order.id).map(o => ({
    ...o, quantity: Number(o.quantity), openPrice: Number(o.openPrice), currentPrice: livePrice(o), margin: Number(o.margin),
    fee: Number(o.fee || 0), lotSize: o.lotSize == null ? null : Number(o.lotSize), leverage: Number(o.leverage ?? 1),
    conversionRate: market.getConversionRate(o.symbol, o.quoteCurrency),
  }))
  return estimateLiquidationPrice(equity, positions, { symbol: order.symbol, side: order.side, quantity: Number(order.quantity),
    lotSize: Number(order.lotSize ?? order.leverage ?? 1), price: quote, fee: 0, conversionRate: market.getConversionRate(order.symbol, order.quoteCurrency) })
}
function expiry(order: any) { return orderTimestamp(order.openTime) + Number(order.duration) * 1000 }
function remaining(order: any) { const end = expiry(order); return Number.isFinite(end) ? Math.max(0, Math.ceil((end - now.value - serverOffset) / 1000)) : null }
function date(value: number) { return Number.isFinite(value) ? new Date(value).toLocaleString() : '—' }
async function refresh() {
  const version = ++generation
  const mode = props.mode
  loading.value = true
  lastRefresh = Date.now()
  try {
    const [result, account]: any[] = await Promise.all([
      request.get(`/trade/${mode === 'term' ? 'option' : 'contract'}/orders`),
      mode === 'contract' ? request.get('/trade/contract/balance') : Promise.resolve(null),
    ])
    if (version !== generation || disposed) return
    if (!Array.isArray(result.list) || result.success === false) throw new Error(result.message || 'Invalid order response')
    orders.value = result.list
    available.value = account && account.success !== false && Number.isFinite(Number(account.available ?? account.balance)) ? Number(account.available ?? account.balance) : null
    error.value = ''
    if (selected.value && !busy.value) {
      const updated = result.list.find((o: any) => o.id === selected.value.id)
      if (updated) selected.value = updated
    }
  } catch (e: any) { if (version === generation) { error.value = e.message; available.value = null } }
  finally { if (version === generation) loading.value = false }
}
function open(order: any, next: 'detail' | 'close' | 'cancel' = 'detail') {
  selected.value = order
  action.value = next
  takeProfit.value = order.takeProfit ?? ''
  stopLoss.value = order.stopLoss ?? ''
  error.value = ''
}
const protectionInvalid = computed(() => {
  if (!selected.value) return false
  const step = 10 ** -(info(selected.value)?.pricePrecision ?? 2)
  const tp = takeProfit.value === '' ? null : Number(takeProfit.value)
  const sl = stopLoss.value === '' ? null : Number(stopLoss.value)
  const reference = selected.value.status === 'PENDING' ? Number(selected.value.price) : livePrice(selected.value)
  return protectionError(selected.value.side, reference, tp, sl) || (tp != null && !validIncrement(tp, step)) || (sl != null && !validIncrement(sl, step))
})
async function submit(operation: 'close' | 'cancel' | 'update-tp-sl') {
  if (busy.value || !selected.value || (operation === 'update-tp-sl' && protectionInvalid.value)) return
  busy.value = true
  error.value = ''
  const order = selected.value
  try {
    const result: any = await request.post(`/trade/contract/order/${order.id}/${operation}`, operation === 'update-tp-sl' ? {
      takeProfit: takeProfit.value === '' ? null : Number(takeProfit.value), stopLoss: stopLoss.value === '' ? null : Number(stopLoss.value),
    } : operation === 'close' ? { closePrice: market.getPrice(order.symbol) } : undefined)
    if (result.success === false) throw new Error(result.message)
    selected.value = null
    await refresh()
    emit('changed')
  } catch (e: any) { error.value = e.message }
  finally { busy.value = false }
}
watch(() => props.mode, () => { orders.value = []; selected.value = null; void refresh() })
watch(() => props.revision, () => void refresh())
onMounted(async () => {
  void refresh()
  timer = setInterval(() => { now.value = Date.now(); if (now.value - lastRefresh >= 5000 && document.visibilityState === 'visible' && !loading.value && !busy.value) void refresh() }, 1000)
  const start = Date.now()
  try {
    const response = await fetch('/api/user/system/timezone', { cache: 'no-store' })
    const time = Date.parse(response.headers.get('date') || '')
    if (Number.isFinite(time)) serverOffset = time - (start + Date.now()) / 2
  } catch { /* Countdown remains indicative; settlement is server-authoritative. */ }
})
onUnmounted(() => { disposed = true; generation++; clearInterval(timer) })
</script>

<template>
  <section class="trade-orders">
    <div v-if="mode === 'contract'" class="list-toolbar"><label><input type="checkbox" v-model="onlyCurrent" />{{ text('僅看當前品種', 'Current instrument') }}</label><router-link to="/orders">{{ text('全部訂單', 'All orders') }} ›</router-link></div>
    <p v-if="error && !selected" class="error" role="alert">{{ error }} <button @click="refresh">{{ text('重試', 'Retry') }}</button></p>
    <div v-if="mode === 'contract' && tab === 'positions' && visibleOrders.length" class="summary"><div>{{ text('未實現盈虧', 'Unrealized P/L') }} (USD)<strong :class="totalProfit < 0 ? 'down' : 'up'">{{ money(totalProfit) }}</strong></div><div>{{ text('佔用保證金', 'Margin') }} (USD)<strong>{{ money(margin) }}</strong></div></div>
    <p v-if="loading && !orders.length" class="empty" role="status">{{ text('載入訂單…', 'Loading orders…') }}</p>
    <div v-else-if="!visibleOrders.length && !error" class="empty">{{ text('暫無', 'No ') }}{{ mode === 'term' ? (tab === 'history' ? text('歷史交易', 'past trades') : text('進行中的交易', 'active trades')) : (tab === 'pending' ? text('委託', 'pending orders') : text('持倉', 'positions')) }}<small>{{ text('成交後可在此查看訂單狀態與詳情', 'Orders and their status will appear here') }}</small></div>
    <article v-for="order in visibleOrders.slice(0, 20)" :key="order.id" class="order-card" :class="{ 'contract-order': mode === 'contract' }">
      <header><strong>{{ name(order) }}</strong><span :class="['badge', ['BUY', 'UP'].includes(order.side || order.direction) ? 'up' : 'down']">{{ ['BUY', 'UP'].includes(order.side || order.direction) ? text('多 ↑', 'Long ↑') : text('空 ↓', 'Short ↓') }}</span><span v-if="mode === 'contract'">{{ order.leverage }}×</span><span class="order-status">{{ order.status === 'PENDING' ? text('待成交', 'Pending') : order.status === 'CLOSED' ? text('已結算', 'Settled') : text('進行中', 'Active') }}</span></header>
      <dl><div><dt>{{ text('數量 / 金額', 'Size / Amount') }}</dt><dd>{{ mode === 'contract' ? `${order.quantity} ${locale.t('lots')}` : `${money(order.amount)} USD` }}</dd></div><div><dt>{{ order.status === 'PENDING' ? text('委託價格', 'Limit price') : text('成交價格', 'Entry price') }}</dt><dd>{{ price(order.status === 'PENDING' ? order.price : order.openPrice, order) }}</dd></div>
        <template v-if="mode === 'contract'"><div><dt>{{ text('參考現價', 'Current price') }}</dt><dd>{{ price(livePrice(order), order) }}</dd></div><div><dt>{{ text('盈虧', 'P/L') }} (USD)</dt><dd :class="profit(order) < 0 ? 'down' : 'up'">{{ money(profit(order)) }}</dd></div><div><dt>{{ text('止盈', 'Take profit') }}</dt><dd>{{ price(order.takeProfit, order) }}</dd></div><div><dt>{{ text('止損', 'Stop loss') }}</dt><dd>{{ price(order.stopLoss, order) }}</dd></div></template>
        <template v-else><div><dt>{{ text('到期時間', 'Expiry') }}</dt><dd>{{ date(expiry(order)) }}</dd></div><div><dt>{{ order.status === 'CLOSED' ? text('結算盈虧', 'Settled P/L') : text('剩餘時間', 'Remaining') }}</dt><dd>{{ order.status === 'CLOSED' ? money(order.profit) + ' USD' : remaining(order) === null ? '—' : remaining(order) === 0 ? text('等待結算', 'Awaiting settlement') : remaining(order) + 's' }}</dd></div></template>
      </dl>
      <p v-if="mode === 'contract' && order.status === 'OPEN'" class="liquidation-note">{{ text('預計強平價', 'Est. liquidation') }}: {{ price(liquidation(order), order) }}<small>{{ text('依帳戶整體權益估算，其他價格與匯率不變；— 表示資料不足或無正值強平價。', 'Account equity estimate, other prices and FX held constant; — means missing data or no positive liquidation price.') }}</small></p><div class="card-actions"><button @click="open(order)">{{ text('查看詳情', 'Details') }}</button><button v-if="mode === 'contract'" @click="open(order, order.status === 'PENDING' ? 'cancel' : 'close')">{{ order.status === 'PENDING' ? text('撤單', 'Cancel order') : text('平倉', 'Close position') }}</button></div>
    </article>
    <router-link v-if="visibleOrders.length > 20" to="/orders">{{ text('查看其餘訂單', 'View more orders') }} ›</router-link>
    <TradeSheet :open="!!selected" :busy="busy" :title="action === 'close' ? text('確認平倉', 'Confirm close') : action === 'cancel' ? text('確認撤單', 'Confirm cancellation') : text('訂單詳情', 'Order details')" @close="selected = null">
      <template v-if="selected"><h3>{{ name(selected) }} · {{ selected.side || selected.direction }} · #{{ selected.id }}</h3>
        <dl class="detail-list"><div><dt>{{ text('數量 / 金額', 'Size / Amount') }}</dt><dd>{{ selected.quantity ?? selected.amount }} {{ mode === 'contract' ? locale.t('lots') : 'USD' }}</dd></div><div><dt>{{ text('委託 / 成交價', 'Limit / Entry') }}</dt><dd>{{ price(selected.status === 'PENDING' ? selected.price : selected.openPrice, selected) }}</dd></div><div><dt>{{ text('狀態', 'Status') }}</dt><dd>{{ selected.status }}</dd></div><div><dt>{{ text('參考現價', 'Current price') }}</dt><dd>{{ price(livePrice(selected), selected) }}</dd></div><div v-if="mode === 'contract'"><dt>{{ text('保證金 / 手續費', 'Margin / Fee') }}</dt><dd>{{ money(selected.margin) }} / {{ money(selected.fee) }} USD</dd></div><div v-else><dt>{{ text('到期時間', 'Expiry') }}</dt><dd>{{ date(expiry(selected)) }}</dd></div></dl>
        <template v-if="action === 'detail' && mode === 'contract'"><p>{{ text('止盈止損 · 留空移除；依當前方向校驗', 'Protection · Leave blank to remove; validated for this direction') }}</p><label class="protection-label">{{ text('止盈價格', 'Take profit') }}<input v-model="takeProfit" type="number" inputmode="decimal" :step="10 ** -(info(selected)?.pricePrecision ?? 2)" /></label><label class="protection-label">{{ text('止損價格', 'Stop loss') }}<input v-model="stopLoss" type="number" inputmode="decimal" :step="10 ** -(info(selected)?.pricePrecision ?? 2)" /></label><p v-if="protectionInvalid" class="error">{{ text('請檢查保護價格的方向、精度及行情狀態', 'Check protection direction, precision and quote availability') }}</p></template>
        <p v-if="action !== 'detail'">{{ text('請核對品種、方向及數量，確認後提交。成交與結算以服務端為準。', 'Check instrument, side and size before confirming. Execution is server-authoritative.') }}</p><p v-if="error" role="alert" class="error">{{ error }}</p>
      </template>
      <template #footer><button v-if="mode === 'contract'" class="confirm" :disabled="busy || (action === 'detail' && protectionInvalid)" @click="submit(action === 'detail' ? 'update-tp-sl' : action)">{{ busy ? text('提交中…', 'Submitting…') : action === 'detail' ? text('儲存止盈止損', 'Save protection') : action === 'close' ? text('確認平倉', 'Confirm close') : text('確認撤單', 'Confirm cancellation') }}</button><button v-else class="confirm" @click="selected = null">{{ text('完成', 'Done') }}</button></template>
    </TradeSheet>
  </section>
</template>

<style scoped>
.trade-orders { font-size: 13px; }.liquidation-note { color: #777; font-size: 12px; line-height: 1.6; }.liquidation-note small { display: block; }
.list-toolbar, header, .card-actions { display: flex; align-items: center; gap: 8px; }
.list-toolbar { justify-content: space-between; min-height: 44px; }
.list-toolbar label { display: flex; align-items: center; gap: 6px; min-height: 44px; }
input[type=checkbox] { width: 20px; height: 20px; accent-color: #85bd00; }
a { color: #666; text-decoration: none; min-height: 44px; display: inline-flex; align-items: center; }
.summary { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; padding: 12px; background: #f5f7fb; border-radius: 10px; color: #666; }
.summary strong { display: block; font-size: 23px; margin-top: 6px; }
.up { color: #639700; }.down, .error { color: #df383d; }
.empty { color: #777; padding: 24px 0; text-align: center; line-height: 1.8; }.empty small { display: block; font-size: 12px; }
.order-card { padding: 16px 0; border-top: 1px solid #e6e8ed; }header strong { font-size: 17px; }.badge { padding: 4px 6px; border-radius: 5px; background: #f5f7fb; }.order-status { margin-left: auto; color: #666; }
.contract-order dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 12px; }
.contract-order dl > div { flex-wrap: wrap; gap: 4px; }
dl { margin: 12px 0; }dl > div { display: flex; justify-content: space-between; gap: 12px; padding: 5px 0; }dt { color: #777; }dd { margin: 0; text-align: right; font-variant-numeric: tabular-nums; }
button { min-height: 44px; border: 1px solid #dce0e7; background: #fff; border-radius: 8px; padding: 8px 12px; color: #333; cursor: pointer; font: inherit; }
.card-actions button { flex: 1; }.protection-label { display: block; margin: 12px 0; }.protection-label input { box-sizing: border-box; width: 100%; height: 52px; font-size: 16px; border: 1px solid #dce0e7; border-radius: 8px; padding: 12px; margin-top: 6px; }
.confirm { width: 100%; height: 52px; color: #fff; background: #85bd00; border: 0; font-size: 16px; font-weight: 600; }button:disabled { opacity: .45; cursor: not-allowed; }
</style>
