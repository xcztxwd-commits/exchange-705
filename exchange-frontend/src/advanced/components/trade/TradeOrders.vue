<script setup lang="ts">
import { getSystemTimezone } from '@/utils/dateTime'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import TradeSheet from './TradeSheet.vue'
import request from '@/utils/request'
import { useMarketStore } from '@/store/market'
import { useLocaleStore } from '@/store/locale'
import { quantityUnit, displayFee, calculateContractProfit, estimateLiquidationPrice } from '@/utils/contract'
import { orderTimestamp, protectionError, validIncrement } from '@/utils/tradeValidation'
import { displaySymbol } from '@/utils/displaySymbol'
import marketWebSocket from '@/utils/marketWebSocket'
import OrderShareModal from '@/components/OrderShareModal.vue'
import { accountMode } from '@/utils/accountMode'
import type { ShareKind } from '@/utils/orderShare'
import { withinDays } from '@/utils/orderView'
import { contractEquity } from '@/utils/contract'

const props = defineProps<{ mode: 'contract' | 'term'; symbol: string; catalog: any[]; tab: string; revision: number; fullPage?: boolean }>()
const emit = defineEmits<{ counts: [open: number, pending: number]; changed: [] }>()
const market = useMarketStore()
const locale = useLocaleStore()
const text = locale.text
const orders = ref<any[]>([])
const loading = ref(false)
const error = ref('')
const onlyCurrent = ref(!props.fullPage)
const symbolFilter = ref('all'), directionFilter = ref('all'), periodFilter = ref(30)
const selected = ref<any>(null)
const shareOrder = ref<{id:string|number;kind:ShareKind}|null>(null)
const action = ref<'detail' | 'protection' | 'close' | 'cancel'>('detail')
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
let refreshController: AbortController | null = null
const timezoneController = new AbortController()
const currentOrders = computed(() => orders.value.filter(o => !onlyCurrent.value || o.symbol === props.symbol))
const isHistory = computed(() => props.tab === 'history')
const visibleOrders = computed(() => currentOrders.value.filter(o =>
  o.status === (props.mode === 'term' ? isHistory.value ? 'CLOSED' : 'TRADING' : isHistory.value ? 'CLOSED' : props.tab === 'pending' ? 'PENDING' : 'OPEN')
  && (symbolFilter.value === 'all' || o.symbol === symbolFilter.value)
  && (directionFilter.value === 'all' || (o.side || o.direction) === directionFilter.value)
  && (!isHistory.value || withinDays(o.manualCloseTimeUtc || o.closeTime, periodFilter.value, now.value))
).sort((a,b) => orderTimestamp(b.manualCloseTimeUtc || b.closeTime || b.createdAt || b.openTime) - orderTimestamp(a.manualCloseTimeUtc || a.closeTime || a.createdAt || a.openTime)))
const symbolOptions = computed(() => [...new Set(orders.value.map(o => String(o.symbol)))])
const historyProfit = computed(() => visibleOrders.value.reduce((sum,o) => sum + Number(o.profit || 0),0))
const historyWon = computed(() => visibleOrders.value.reduce((sum,o) => sum + Math.max(0,Number(o.profit || 0)),0))
const historyLost = computed(() => visibleOrders.value.reduce((sum,o) => sum + Math.min(0,Number(o.profit || 0)),0))
const openPositions = computed(() => orders.value.filter(o => o.status === 'OPEN'))
const allOpenProfit = computed(() => openPositions.value.reduce((sum,o) => sum + profit(o),0))
const accountMargin = computed(() => openPositions.value.reduce((sum,o) => sum + Number(o.margin || 0),0))
const riskRate = computed(() => available.value != null && accountMargin.value > 0 ? contractEquity(available.value, openPositions.value.map(o => ({...o,profit:profit(o)}))) / accountMargin.value * 100 : NaN)
watch(() => [props.mode,props.tab], () => { symbolFilter.value='all'; directionFilter.value='all'; selected.value=null })
watch([orders, () => props.catalog], () => {
  const required = new Set(orders.value.filter(o => ['OPEN','PENDING','TRADING'].includes(o.status)).map(o => o.symbol))
  void market.subscribeSymbols(props.catalog.filter(s => required.has(s.symbol)), 'advanced-order-list')
})
watch(currentOrders, items => emit('counts', items.filter(o => ['OPEN', 'TRADING'].includes(o.status)).length, items.filter(o => o.status === 'PENDING').length))
function info(order: any) { return props.catalog.find(s => s.symbol === order.symbol) }
function name(order: any) {
  const label = displaySymbol(order)
  if (label !== order.symbol) return label
  const s = info(order)
  return s?.category === 'Forex' || s?.sourceCategory === 'Forex' ? displaySymbol(s) : s?.baseCurrency && s?.quoteCurrency ? `${s.baseCurrency}/${s.quoteCurrency}` : label
}
function sideLabel(order: any) { return props.mode==='term' ? order.direction==='UP'?text('看涨','Up'):text('看跌','Down') : order.side==='BUY'?text('多','Long'):text('空','Short') }
function money(value: any) { return value != null && Number.isFinite(Number(value)) ? Number(value).toLocaleString(locale.locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—' }
function price(value: any, order: any) { return Number(value) > 0 ? Number(value).toLocaleString(locale.locale, { minimumFractionDigits: info(order)?.pricePrecision ?? 2, maximumFractionDigits: info(order)?.pricePrecision ?? 2 }) : '—' }
function livePrice(order: any) { return market.getQuoteStatus(order.symbol, now.value) === 'available' ? market.getPrice(order.symbol) : NaN }
function profit(order: any) { return order.status === 'OPEN' ? (Number.isFinite(livePrice(order)) ? calculateContractProfit(order, livePrice(order), market.getConversionRate(order.symbol, order.quoteCurrency)) : NaN) : Number(order.profit) }
function liquidation(order: any) {
  if (available.value == null || order.status !== 'OPEN' || !Number.isFinite(livePrice(order))) return null
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
function date(value: number) { return Number.isFinite(value) ? new Date(value).toLocaleString(locale.locale, { timeZone: getSystemTimezone() }) : '—' }
async function refresh() {
  const version = ++generation
  refreshController?.abort()
  const controller = new AbortController(); refreshController = controller
  const mode = props.mode
  loading.value = true
  lastRefresh = Date.now()
  try {
    const [result, account]: any[] = await Promise.all([
      request.get(`/trade/${mode === 'term' ? 'option' : 'contract'}/orders`, { signal: controller.signal }),
      mode === 'contract' ? request.get('/trade/contract/balance', { signal: controller.signal }) : Promise.resolve(null),
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
  } catch (e: any) { if (!disposed && version === generation) { error.value = e.message; available.value = null } }
  finally { if (version === generation) loading.value = false }
}
function open(order: any, next: 'detail' | 'protection' | 'close' | 'cancel' = 'detail') {
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
  if (busy.value || disposed || !selected.value || selected.value.status === 'CLOSED' || (operation === 'update-tp-sl' && protectionInvalid.value)) return
  if (operation === 'close' && !Number.isFinite(livePrice(selected.value))) { error.value=text('报价已过期或行情不可用，等待更新后平仓', 'Quote expired or unavailable; wait for a fresh quote before closing'); return }
  busy.value = true
  error.value = ''
  const order = selected.value
  try {
    const result: any = await request.post(`/trade/contract/order/${order.id}/${operation}`, operation === 'update-tp-sl' ? {
      takeProfit: takeProfit.value === '' ? null : Number(takeProfit.value), stopLoss: stopLoss.value === '' ? null : Number(stopLoss.value),
    } : operation === 'close' ? { closePrice: market.getPrice(order.symbol) } : undefined)
    if (disposed) return
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
    const response = await fetch('/api/user/system/timezone', { cache: 'no-store', signal: timezoneController.signal })
    if (disposed) return
    const time = Date.parse(response.headers.get('date') || '')
    if (Number.isFinite(time)) serverOffset = time - (start + Date.now()) / 2
  } catch { /* Countdown remains indicative; settlement is server-authoritative. */ }
})
onUnmounted(() => { disposed = true; generation++; refreshController?.abort(); timezoneController.abort(); clearInterval(timer); marketWebSocket.release('advanced-order-list') })
function remainingText(order: any) {
  const seconds=remaining(order)
  return seconds == null ? '—' : seconds === 0 ? text('等待结算','Awaiting settlement') : `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2,'0')}`
}
function orderTime(order: any) { return orderTimestamp(isHistory.value ? order.manualCloseTimeUtc || order.closeTime : order.status === 'PENDING' ? order.createdAt : order.manualOpenTimeUtc || order.openTime) }
function signed(value: any) { return Number.isFinite(Number(value)) ? `${Number(value)>0?'+':''}${money(value)}` : '—' }
function protectionMetrics(order: any) { return metrics(order).filter((_,index) => (order.status==='PENDING'?[0,1,2]:[0,1,3]).includes(index)) }
function metrics(order: any) {
  const unit = quantityUnit(order,locale.t('lots')), currency = order.quoteCurrency || info(order)?.quoteCurrency || ''
  const label = (value: string) => currency ? `${value} ${currency}` : value
  if (props.mode === 'term') return [
    {label:text('投入 USD','Invested USD'),value:money(order.amount)},
    {label:label(text('开仓价格','Entry price')),value:price(order.openPrice,order)},
    {label:label(isHistory.value?text('结算价格','Settlement price'):text('当前价格','Current price')),value:price(isHistory.value?order.closePrice:livePrice(order),order)},
  ]
  if (order.status === 'PENDING') return [
    {label:label(text('委托价','Limit price')),value:price(order.price,order)},
    {label:label(text('标记价格','Mark price')),value:price(livePrice(order),order)},
    {label:text('委托量','Order size'),value:`${order.quantity} ${unit}`},
    {label:text('冻结保证金 USD','Frozen margin USD'),value:money(order.margin)},
    {label:text('委托类型','Order type'),value:order.type === 'LIMIT'?text('限价','Limit'):text('市价','Market')},
    {label:text('订单编号','Order ID'),value:`#${order.id}`},
  ]
  if (order.status === 'CLOSED') return [
    {label:label(text('开仓均价','Entry price')),value:price(order.openPrice,order)},
    {label:label(text('平仓均价','Exit price')),value:price(order.closePrice,order)},
    {label:text('平仓数量','Closed size'),value:`${order.quantity} ${unit}`},
    {label:text('保证金 USD','Margin USD'),value:money(order.margin)},
    {label:text('手续费 USD','Fee USD'),value:displayFee(order.fee)},
    {label:text('订单编号','Order ID'),value:`#${order.id}`},
  ]
  return [
    {label:label(text('开仓均价','Entry price')),value:price(order.openPrice,order)},
    {label:label(text('标记价格','Mark price')),value:price(livePrice(order),order)},
    {label:text('保证金 USD','Margin USD'),value:money(order.margin)},
    {label:text('持仓量','Position size'),value:`${order.quantity} ${unit}`},
    {label:label(text('止盈价','Take profit')),value:price(order.takeProfit,order)},
    {label:label(text('止损价','Stop loss')),value:price(order.stopLoss,order)},
  ]
}
</script>

<template>
  <section class="trade-orders" :data-design-state="mode === 'term' ? '37:1804' : tab === 'history' ? '37:1610' : tab === 'pending' ? '37:1732' : '34:703'">
    <section v-if="fullPage && mode === 'contract' && tab === 'positions'" class="account-overview">
      <h2>{{ text('账户概览','Account overview') }}</h2>
      <dl class="metrics"><div><dt>{{ text('浮动收益 USD','Unrealized USD') }}</dt><dd :class="allOpenProfit<0?'down':'up'">{{ signed(allOpenProfit) }}</dd></div><div><dt>{{ text('持仓保证金','Margin') }}</dt><dd>{{ money(accountMargin) }}</dd></div><div><dt>{{ text('风险率','Risk rate') }}</dt><dd>{{ Number.isFinite(riskRate)?`${riskRate.toFixed(1)}%`:'—' }}</dd></div></dl>
    </section>
    <slot name="tabs" />
    <dl v-if="fullPage && isHistory" class="metrics history-summary"><div><dt>{{ text('已实现 USD','Realized USD') }}</dt><dd :class="historyProfit<0?'down':'up'">{{ signed(historyProfit) }}</dd></div><div><dt>{{ text('盈利','Profit') }}</dt><dd class="up">{{ money(historyWon) }}</dd></div><div><dt>{{ text('亏损','Loss') }}</dt><dd class="down">{{ money(historyLost) }}</dd></div></dl>
    <div v-if="fullPage" class="filters">
      <label><span class="sr-only">{{ text('品种','Instrument') }}</span><select v-model="symbolFilter"><option value="all">{{ text('全部品种','All instruments') }}</option><option v-for="symbol in symbolOptions" :key="symbol" :value="symbol">{{ name({symbol}) }}</option></select></label>
      <div class="filter-right"><label><span class="sr-only">{{ text('方向','Side') }}</span><select v-model="directionFilter"><option value="all">{{ text('全部方向','All sides') }}</option><option :value="mode === 'term'?'UP':'BUY'">{{ text('多 / 看涨','Long / Up') }}</option><option :value="mode === 'term'?'DOWN':'SELL'">{{ text('空 / 看跌','Short / Down') }}</option></select></label><label v-if="isHistory"><span class="sr-only">{{ text('时间','Period') }}</span><select v-model.number="periodFilter"><option :value="30">{{ text('近30天','30 days') }}</option><option :value="7">{{ text('近7天','7 days') }}</option><option :value="90">{{ text('近90天','90 days') }}</option><option :value="0">{{ text('全部时间','All time') }}</option></select></label></div>
    </div>
    <div v-else-if="mode === 'contract'" class="list-toolbar"><label><input type="checkbox" v-model="onlyCurrent" />{{ text('仅看当前品种','Current instrument') }}</label><router-link to="/orders">{{ text('全部订单','All orders') }} ›</router-link></div>
    <p v-if="error && !selected" class="error" role="alert">{{ error }} <button @click="refresh">{{ text('重试','Retry') }}</button></p>
    <p v-if="loading && !orders.length" class="empty" role="status">{{ text('载入订单…','Loading orders…') }}</p>
    <p v-else-if="!visibleOrders.length && !error" class="empty">{{ text('暂无订单','No orders') }}</p>
    <article v-for="order in (fullPage ? visibleOrders : visibleOrders.slice(0,20))" :key="order.id" class="order-card">
      <header><h2>{{ name(order) }}</h2><span v-if="order.status === 'CLOSED'" class="status">{{ mode === 'term'?text('已结算','Settled'):text('已全部平仓','Closed') }}</span><button v-else class="detail-arrow" :aria-label="text('订单详情','Order details')" @click="open(order)">↗</button></header>
      <p class="position-tags" :class="['BUY','UP'].includes(order.side || order.direction)?'up':'down'">{{ sideLabel(order) }} · {{ mode==='term'?`${order.duration}s`:`${order.leverage || 1}×` }}<span v-if="order.status==='PENDING'"> · {{ text('等待成交','Pending') }}</span></p>
      <div v-if="mode==='contract' && order.status!=='PENDING' || mode==='term' && order.status==='CLOSED'" class="profit-row"><span>{{ isHistory?text('已实现收益 / USD','Realized P/L / USD'):text('未实现收益 / USD','Unrealized P/L / USD') }}</span><strong :class="profit(order)<0?'down':'up'">{{ signed(profit(order)) }}</strong></div>
      <dl class="metrics"><div v-for="metric in metrics(order)" :key="metric.label"><dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd></div></dl>
      <div v-if="mode==='term' && order.status==='TRADING'" class="time-row"><span>{{ text('剩余时间','Remaining') }}</span><strong>{{ remainingText(order) }}</strong></div>
      <div v-else class="time-row"><span>{{ order.status==='CLOSED'?text('平仓时间','Close time'):order.status==='PENDING'?text('创建时间','Created'):text('开仓时间','Open time') }}</span><time>{{ date(orderTime(order)) }}</time></div>
      <div class="card-actions"><template v-if="mode==='contract' && order.status==='OPEN'"><button @click="open(order,'protection')">{{ text('止盈止损','TP / SL') }}</button><button @click="open(order,'close')">{{ text('平仓','Close position') }}</button><button v-if="fullPage" @click="open(order)">{{ text('详情','Details') }}</button></template><button v-else-if="mode==='contract' && order.status==='PENDING'" @click="open(order,'cancel')">{{ text('撤销委托','Cancel order') }}</button><template v-else><button @click="open(order)">{{ text('订单详情','Order details') }}</button><button v-if="order.status==='CLOSED'" @click="shareOrder={id:order.id,kind:mode==='term'?'option':'contract'}">{{ text('分享','Share') }}</button></template></div>
    </article>
    <router-link v-if="!fullPage && visibleOrders.length>20" to="/orders">{{ text('查看其余订单','View more orders') }} ›</router-link>
    <p v-if="mode==='term'" class="notice">{{ text('结算结果由服务端确定；倒计时与报价状态持续显示。','Server-authoritative settlement; countdown and quote status remain visible.') }}</p>
    <OrderShareModal v-if="shareOrder" :order-id="shareOrder.id" :kind="shareOrder.kind" :brand="accountMode() === 'DEMO' ? 'DEMO' : 'FOREX'" @close="shareOrder=null" />
    <TradeSheet :open="!!selected" :busy="busy" :page="action==='protection'" :title="action==='protection'?text('止盈止损','TP / SL'):action==='close'?text('确认平仓','Confirm close'):action==='cancel'?text('确认撤单','Confirm cancellation'):text('订单详情','Order details')" @close="selected=null">
      <template v-if="selected">
        <section class="detail-card" :class="{'protection-card':action==='protection'}" :data-design-node="action==='protection'?'37:2090':undefined"><h2>{{ name(selected) }} · {{ sideLabel(selected) }} · {{ selected.leverage?`${selected.leverage}×`:`#${selected.id}` }}</h2><strong v-if="selected.status!=='PENDING'" class="detail-profit" :class="profit(selected)<0?'down':'up'">{{ signed(profit(selected)) }} USD</strong><dl class="metrics"><div v-for="metric in (action==='protection'?protectionMetrics(selected):metrics(selected))" :key="metric.label"><dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd></div></dl>
        <template v-if="action==='protection' && mode==='contract' && selected.status!=='CLOSED'"><label class="protection-label">{{ text('止盈','Take profit') }} / {{ selected.quoteCurrency || info(selected)?.quoteCurrency }}<input v-model="takeProfit" type="number" inputmode="decimal" :step="10 ** -(info(selected)?.pricePrecision ?? 2)" :aria-label="text('止盈价格','Take profit price')" /></label><label class="protection-label">{{ text('止损','Stop loss') }} / {{ selected.quoteCurrency || info(selected)?.quoteCurrency }}<input v-model="stopLoss" type="number" inputmode="decimal" :step="10 ** -(info(selected)?.pricePrecision ?? 2)" :aria-label="text('止损价格','Stop loss price')" /></label></template></section>
        <p v-if="mode==='contract' && selected?.status==='OPEN' && action==='detail'" class="notice">{{ text('预计强平价','Est. liquidation') }}: {{ price(liquidation(selected),selected) }} · {{ text('按整体账户权益估算，其他价格与汇率不变','Account equity estimate with other prices and FX held constant') }}</p><p v-if="action==='protection'" class="notice">{{ text('方向、报价与价格关系使用原有风险校验；留空移除保护价格。','Existing direction, quote and precision validation; leave blank to remove protection.') }}</p>
        <p v-if="action==='protection' && protectionInvalid" class="error" role="alert">{{ text('请检查价格方向、精度与行情状态','Check price direction, precision and quote availability') }}</p>
        <p v-if="action==='close' || action==='cancel'" class="notice">{{ text('请核对品种、方向及数量。成交与结算以服务端为准。','Check instrument, side and size. Execution is server-authoritative.') }}</p>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
      </template>
      <template #footer><button v-if="mode==='contract' && selected?.status!=='CLOSED' && action!=='detail'" class="confirm" :disabled="busy || action==='protection' && protectionInvalid" @click="submit(action==='protection'?'update-tp-sl':action as 'close'|'cancel')">{{ busy?text('提交中…','Submitting…'):action==='protection'?text('保存止盈止损','Save TP / SL'):action==='close'?text('确认平仓','Confirm close'):text('确认撤单','Confirm cancellation') }}</button><button v-if="mode==='contract' && selected?.status!=='CLOSED' && action==='detail'" class="confirm" @click="action='protection'">{{ text('修改止盈止损','Edit TP / SL') }}</button><button class="cancel" :disabled="busy" @click="selected=null">{{ text('取消','Cancel') }}</button></template>
    </TradeSheet>
  </section>
</template>

<style scoped>
.trade-orders{font-size:14px;color:#252a30;line-height:1.45;font-variant-numeric:tabular-nums}.account-overview,.order-card,.detail-card{padding:12px;border:1px solid #e9edef;border-radius:10px;background:white}.account-overview{margin-bottom:20px}h2{font-size:17px;font-weight:500;margin:0;overflow-wrap:anywhere}.metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:12px 0}.metrics>div{min-width:0}dt{font-size:12px;color:#707780;overflow-wrap:anywhere}dd{font-size:14px;font-weight:500;margin:5px 0 0;overflow-wrap:anywhere}.history-summary{margin:20px 0}.filters,.filter-right,.list-toolbar{display:flex;justify-content:space-between;align-items:center;gap:6px;min-height:44px}.filters{margin:12px 0 20px}.filter-right{justify-content:flex-end;flex-wrap:wrap}.filters label{min-width:0}.filters select{border:0;min-height:44px;max-width:100%;color:#252a30;background:white;font:inherit;font-size:13px}.filters>label{max-width:46%}.list-toolbar{margin-bottom:12px;font-size:12px}.list-toolbar label{display:flex;align-items:center;gap:6px}.list-toolbar input{width:18px;height:18px;accent-color:#736582}a{color:#736582;text-decoration:none;min-height:44px;display:inline-flex;align-items:center}.order-card{margin-bottom:20px}.order-card header{display:flex;justify-content:space-between;gap:8px;align-items:center}.status{color:#736582;font-size:12px;white-space:nowrap}.detail-arrow{color:#736582;background:transparent;border:0;width:44px;height:44px;margin:-10px -6px;font-size:20px}.position-tags{font-size:12px;margin:12px 0}.up{color:#56723e}.down,.error{color:#aa5363}.profit-row{display:flex;justify-content:space-between;align-items:center;gap:8px;margin:12px 0}.profit-row>span{font-size:12px;color:#707780}.profit-row strong,.detail-profit{font-size:20px;font-weight:500;overflow-wrap:anywhere}.time-row{display:flex;justify-content:space-between;align-items:center;gap:12px;margin:20px 0;font-size:14px}.time-row time{font-size:13px;text-align:right;overflow-wrap:anywhere}.card-actions{display:flex;gap:8px}.card-actions button{flex:1;min-width:0;min-height:48px;border:0;border-radius:10px;background:#f5f6f7;color:#252a30;font:inherit;font-size:14px;cursor:pointer;overflow-wrap:anywhere}.empty{text-align:center;padding:40px 0;color:#707780}.notice{background:#f5f2f7;color:#707780;padding:10px;border-radius:7px;font-size:12px;line-height:1.6;margin:20px 0}.error{font-size:12px;overflow-wrap:anywhere}.error button{min-height:44px;color:#736582;background:#f5f2f7;border:0;border-radius:7px;padding:8px}.detail-profit{display:block;margin:12px 0}.protection-label{display:block;margin-top:12px;color:#707780;font-size:12px}.protection-label input{box-sizing:border-box;display:block;width:100%;height:50px;padding:12px 14px;border:1px solid #e9edef;border-radius:10px;background:#f5f6f7;color:#252a30;font:inherit;font-size:16px;margin-top:8px}.confirm,.cancel{display:block;width:100%;min-height:48px;font:inherit;border:0;border-radius:10px;padding:12px;margin-bottom:12px;cursor:pointer}.confirm{background:#d9e6c8;color:#2f4129}.cancel{background:#f5f6f7;color:#252a30}button:disabled{opacity:.45;cursor:not-allowed}button:focus-visible,input:focus-visible,select:focus-visible{outline:2px solid #736582;outline-offset:2px}.sr-only{position:absolute;width:1px;height:1px;padding:0;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}@media(max-width:360px){.metrics{gap:10px 7px}.metrics dd{font-size:13px}.filters{font-size:12px}.filters select{font-size:12px}}
.protection-card .metrics{margin-bottom:0}
</style>
