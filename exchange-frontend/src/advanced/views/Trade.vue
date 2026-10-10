<script setup lang="ts">
import { canStartBusiness } from '@/utils/tenantFeatures'
import { formatQuoteTime } from '@/utils/visitorRegion'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTradeKyc } from '@/utils/useTradeKyc'
import AdvancedLayout from '@/advanced/components/AdvancedLayout.vue'
import AppSelect from '@/components/AppSelect.vue'
import KlineChart from '@/components/KlineChart.vue'
import LeverageControl from '@/advanced/components/trade/LeverageControl.vue'
import TradeSheet from '@/advanced/components/trade/TradeSheet.vue'
import TradeOrders from '@/advanced/components/trade/TradeOrders.vue'
import { useOrderSizing } from '@/utils/useOrderSizing'
import { useTrialWallet } from '@/utils/useTrialWallet'
import { accountMode } from '@/utils/accountMode'
import { reconcileFunding, selectedAvailable, type FundingSource, type FundingChoice } from '@/utils/trialLifecycle'
import { useAuthStore } from '@/store/auth'
import { useMarketStore } from '@/store/market'
import { displaySymbol } from '@/utils/displaySymbol'
import { useLocaleStore } from '@/store/locale'
import request from '@/utils/request'
import marketWebSocket from '@/utils/marketWebSocket'
import { DEFAULT_LEVERAGE, contractMargin, leverageLimit, quantityUnit, validQuantity, stepQuantity, displayFee, decimalProduct } from '@/utils/contract'
import { optionalPrice, protectionError, validIncrement } from '@/utils/tradeValidation'

const router = useRouter()
const route = useRoute(), market = useMarketStore(), locale = useLocaleStore()
locale.loadLocale()
const text = locale.text
const directionLabel = (zh: string, en: string) => text(zh, en).replace(/\s*[↑↓]$/, '')
const wallet = useTrialWallet(), auth = useAuthStore()
const contractChoice = ref<FundingChoice>({ source: 'CONTRACT', manual: false }), optionChoice = ref<FundingChoice>({ source: 'OPTION', manual: false })
const contractFunding = computed(() => contractChoice.value.source), optionFunding = computed(() => optionChoice.value.source)
const fundingSource = computed(() => activeTab.value === 'contract' ? contractFunding.value : optionFunding.value)
const fundingOptions = computed(() => [
  { value: activeTab.value === 'contract' ? 'CONTRACT' : 'OPTION', label: locale.t(activeTab.value === 'contract' ? 'contractAccountTitle' : 'optionAccountTitle') },
  ...(wallet.state.eligible && wallet.state.available > 0 ? [{ value: 'TRIAL', label: text('體驗金', 'Trial credit') }] : []),
])
function chooseFunding(source: FundingSource) {
  const choice = activeTab.value === 'contract' ? contractChoice : optionChoice
  choice.value = { source, manual: true }
}
watch([() => wallet.state.eligible, () => wallet.state.available], () => {
  contractChoice.value = reconcileFunding(contractChoice.value, 'CONTRACT', wallet.state, accountMode())
  optionChoice.value = reconcileFunding(optionChoice.value, 'OPTION', wallet.state, accountMode())
}, { immediate: true })
const activeTab = ref<'contract' | 'term'>(route.query.tab === 'term' ? 'term' : 'contract')
const contractTab = ref('entry'), termTab = ref('active')
const currentSymbol = ref(''), currentCategory = ref('Crypto'), catalog = ref<any[]>([])
const symbolInfo = computed(() => catalog.value.find(s => s.symbol === currentSymbol.value))
function symbolName(s: any) { return s?.category === 'Forex' || s?.sourceCategory === 'Forex' || /=X$/i.test(s?.symbol || '') ? displaySymbol(s) : s?.baseCurrency && s?.quoteCurrency ? `${s.baseCurrency}/${s.quoteCurrency}` : s?.symbol || '—' }
const displayName = computed(() => symbolName(symbolInfo.value))
const baseCurrencyName = computed(() => {
  if (symbolInfo.value?.sourceCategory === 'Forex' || symbolInfo.value?.category === 'Forex' || /=X$/i.test(currentSymbol.value)) return displaySymbol(symbolInfo.value || currentSymbol.value).split('/')[0]
  return symbolInfo.value?.baseCurrency || ''
})
const precision = computed(() => symbolInfo.value?.pricePrecision ?? 2)
const priceStep = computed(() => 10 ** -precision.value)
// Existing contract sizing uses 0.01 lots; market volumePrecision is not a lot step.
const quantityStep = computed(() => Number(symbolInfo.value?.quantityStep ?? 0.01))
const unitLabel = computed(() => quantityUnit(symbolInfo.value, locale.t('lots')))
const currentInterval = ref('15m'), intervals = ['1m', '5m', '15m', '30m', '1h', '1d', '1w', '1M']
const showSymbols = ref(false), symbolSearch = ref(''), showChart = ref(false)
const symbolCategory = ref('')
function pickerCategoryLabel(category: string) {
  const labels: Record<string, [string, string]> = { Forex: ['外匯', 'Forex'], US: ['美股', 'US stocks'], Metal: ['貴金屬', 'Precious metals'], CFD: ['指數', 'Indices'], Oil: ['原油', 'Oil'], Crypto: ['加密貨幣', 'Crypto'], CryptoPerpetual: ['永續合約', 'Perpetual contracts'] }
  const label = labels[category]
  return label ? text(label[0], label[1]) : locale.categoryLabel(category)
}
const pickerCategory = (s: any) => s.category === 'USStock' ? 'US' : s.category || 'Crypto'
const symbolCategories = computed(() => {
  const present = new Set<string>(catalog.value.filter(s => s.isEnabled !== false).map(pickerCategory))
  return [...new Set(['Forex', 'US', 'Metal', 'CFD', 'Oil', 'Crypto', 'CryptoPerpetual', ...present])].filter(key => present.has(key))
})
watch(symbolCategories, categories => { if (!categories.includes(symbolCategory.value)) symbolCategory.value = '' })
const filteredSymbols = computed(() => catalog.value.filter(s => s.isEnabled !== false && (!symbolCategory.value || pickerCategory(s) === symbolCategory.value) && `${s.symbol} ${displaySymbol(s)} ${s.baseCurrency} ${s.quoteCurrency} ${s.nameCn} ${s.nameEn}`.toLowerCase().includes(symbolSearch.value.trim().toLowerCase())))
const chartExpanded = ref(true)
const chartVisible = computed(() => chartExpanded.value && (activeTab.value === 'term' || contractTab.value === 'entry' || showChart.value))
const showRules = ref(false), showRisk = ref(false), showEstimates = ref(false), showConfirm = ref(false), showMarketClosed = ref(false)
const side = ref<'BUY' | 'SELL'>('BUY'), busy = ref(false), message = ref(''), now = ref(Date.now())
const { verified: tradeVerified, checking: kycChecking, promptOpen: kycPromptOpen, promptMessage: kycPromptMessage, ensure: ensureKyc, handleError: handleKycError } = useTradeKyc(
  () => { void router.push('/login') }, value => { message.value = value })
function goToVerification() { kycPromptOpen.value = false; showConfirm.value = false; void router.push('/verification') }
let timer: ReturnType<typeof setInterval>, disposed = false, selectionVersion = 0
const currentPrice = computed(() => market.getPrice(currentSymbol.value))
const change = computed(() => { const value = market.getChange24h(currentSymbol.value).changePct; return Number.isFinite(value) ? value : null })
const quoteStatus = computed(() => market.getQuoteStatus(currentSymbol.value, now.value))
const quoteReason = computed(() => !symbolInfo.value ? text('品種資料載入中', 'Loading instrument') : symbolInfo.value.isEnabled === false ? text('品種已停用', 'Instrument disabled') : quoteStatus.value === 'available' ? '' : quoteStatus.value === 'closed' ? locale.t('marketClosed') : quoteStatus.value === 'stale' ? text('報價已過期，等待更新', 'Quote expired; waiting for update') : text('行情暫不可用', 'Quote unavailable'))
const quoteTime = computed(() => { const timestamp = market.quoteStatusMap[currentSymbol.value]?.timestamp; return timestamp ? formatQuoteTime(timestamp) : '—' })
function price(value: any) { return value != null && Number.isFinite(Number(value)) && Number(value) > 0 ? Number(value).toLocaleString(locale.locale, { minimumFractionDigits: precision.value, maximumFractionDigits: precision.value }) : '—' }
function money(value: any) { return value != null && Number.isFinite(Number(value)) ? Number(value).toLocaleString(locale.locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) : '—' }
const orderType = ref<'market' | 'limit'>('market'), quantity = ref(0.01), leverage = ref(DEFAULT_LEVERAGE), limitPrice = ref(0)
const takeProfit = ref<number | string>(''), stopLoss = ref<number | string>(''), available = ref(NaN)
const protection = computed(() => ({ takeProfit: optionalPrice(takeProfit.value), stopLoss: optionalPrice(stopLoss.value) }))
const lotSize = computed(() => Number(symbolInfo.value?.lotSize ?? 1000)), feePerLot = computed(() => Number(symbolInfo.value?.feeMultiplier ?? 30))
const maxLeverage = computed(() => leverageLimit(symbolInfo.value?.maxLeverage, symbolInfo.value?.leverageEnabled !== false))
const orderPrice = computed(() => orderType.value === 'limit' ? Number(limitPrice.value) : currentPrice.value)
const { allocationPercent, setAllocation, canAllocate, orderReady, liquidation, refreshAccount } = useOrderSizing({
  fundingSource: contractFunding, catalog, quantity, leverage, available, active: computed(() => activeTab.value === 'contract'), symbol: currentSymbol,
  price: orderPrice, lotSize, feePerLot, currency: computed(() => symbolInfo.value?.quoteCurrency || 'USD'),
})
const margin = computed(() => contractMargin(quantity.value, lotSize.value, orderPrice.value, leverage.value, market.getConversionRate(currentSymbol.value, symbolInfo.value?.quoteCurrency), market.getMarginBaseRate(symbolInfo.value, orderPrice.value)))
const fee = computed(() => decimalProduct(quantity.value, feePerLot.value)), total = computed(() => margin.value + fee.value)
const contractReason = computed(() => {
  if (quoteReason.value) return quoteReason.value
  if (!validQuantity(quantity.value, symbolInfo.value)) return text('数量不符合品种最小数量或步长', 'Quantity must satisfy instrument minimum and step')
  if (orderType.value === 'limit' && !validIncrement(Number(limitPrice.value), priceStep.value)) return text('請檢查委託價格與精度', 'Check limit price and precision')
  if (!Number.isInteger(leverage.value) || leverage.value < 1 || leverage.value > maxLeverage.value) return text('槓桿超出品種限制', 'Leverage outside instrument limits')
  if (!Number.isFinite(margin.value)) return text('結算匯率暫不可用', 'Settlement rate unavailable')
  if (total.value > available.value) return text('可用資金不足（含保證金及手續費）', 'Insufficient funds including margin and fee')
  if (!orderReady.value) return text('帳戶資料未就緒或可用資金不足', 'Account not ready or funds unavailable')
  return ''
})
const protectionReason = computed(() => {
  const { takeProfit: tp, stopLoss: sl } = protection.value
  return protectionError(side.value, orderPrice.value, tp, sl) || (tp != null && !validIncrement(tp, priceStep.value)) || (sl != null && !validIncrement(sl, priceStep.value)) ? text('請檢查止盈止損方向及價格精度', 'Check protection direction and precision') : ''
})
function adjustQuantity(delta: number) { quantity.value = stepQuantity(quantity.value, delta, symbolInfo.value) }
watch(maxLeverage, max => { leverage.value = Math.min(leverage.value, max) })
watch(orderType, type => { if (type === 'limit' && !limitPrice.value) limitPrice.value = Number(currentPrice.value.toFixed(precision.value)) })
interface Duration { value: number; label: string; profitRate: number; lossRate: number; minAmount?: number; maxAmount?: number }
const durations = ref<Duration[]>([]), duration = ref(0), amount = ref<number | string>(''), direction = ref<'UP' | 'DOWN'>('UP')
const optionAvailable = computed(() => selectedAvailable(wallet.snapshot, optionFunding.value, wallet.state)), durationError = ref('')
const config = computed(() => durations.value.find(d => d.value === duration.value))
const expectedProfit = computed(() => config.value ? Number(amount.value) * config.value.profitRate : NaN), expectedLoss = computed(() => config.value ? Number(amount.value) * config.value.lossRate : NaN)
const termReason = computed(() => {
  if (quoteReason.value) return quoteReason.value
  if (!config.value) return durationError.value || text('期限設定尚未就緒', 'Duration configuration unavailable')
  if (!wallet.ready || !Number.isFinite(optionAvailable.value) || (optionFunding.value === 'TRIAL' && !wallet.state.eligible)) return text('帳戶資料尚未就緒', 'Account data unavailable')
  const value = Number(amount.value)
  if (!Number.isFinite(value) || value <= 0) return text('請輸入有效交易金額', 'Enter a valid amount')
  if (config.value.minAmount != null && value < config.value.minAmount) return `${text('最低金額', 'Minimum')} ${config.value.minAmount} USD`
  if (config.value.maxAmount != null && value > config.value.maxAmount) return `${text('最高金額', 'Maximum')} ${config.value.maxAmount} USD`
  if (value > Number(optionAvailable.value)) return text('期限可用餘額不足', 'Insufficient option balance')
  return ''
})
async function loadDurations() {
  try {
    const response: any = await request.get('/trade/option/durations')
    if (disposed) return
    if (!Array.isArray(response)) throw new Error(text('期限設定載入失敗', 'Duration configuration failed'))
    durations.value = response.filter(d => d.enabled !== false && Number(d.duration) > 0 && d.profitRate != null && d.lossRate != null && [Number(d.profitRate), Number(d.lossRate)].every(v => Number.isFinite(v) && v >= 0)).map(d => ({ value: Number(d.duration), label: new Intl.NumberFormat(locale.locale, { style: 'unit', unit: 'second', unitDisplay: 'short' }).format(Number(d.duration)), profitRate: Number(d.profitRate), lossRate: Number(d.lossRate), minAmount: d.minAmount == null ? undefined : Number(d.minAmount), maxAmount: d.maxAmount == null ? undefined : Number(d.maxAmount) }))
    if (!config.value) duration.value = durations.value[0]?.value ?? 0
    durationError.value = durations.value.length ? '' : text('暫無可交易期限', 'No durations available')
  } catch (e: any) { if (!disposed) { durations.value = []; durationError.value = e.message } }
}
let optionAttempt = 0
async function refreshOption() {
  if (disposed || !auth.token || activeTab.value !== 'term' || document.visibilityState !== 'visible') return
  optionAttempt = performance.now(); await wallet.refresh()
}
const openCount = ref(0), pendingCount = ref(0), revision = ref(0)
function counts(open: number, pending: number) { openCount.value = open; pendingCount.value = pending }
function afterOrderChange() { void refreshAccount(); void refreshOption() }
let confirmationGeneration = 0
function warnIfMarketClosed() {
  if (quoteStatus.value !== 'closed') return false
  message.value = locale.t('marketClosed'); showMarketClosed.value = true; return true
}
watch([fundingSource, () => wallet.state.eligible, () => auth.token], () => {
  confirmationGeneration++; showConfirm.value = false
  if (fundingSource.value !== 'TRIAL' && !wallet.state.eligible) message.value = text('体验金资格不可用，已使用现金账户；存量订单仍可退出。', 'Trial credit unavailable. Cash account selected; existing orders can still close.')
}, { flush: 'sync' })
async function openContract(next: 'BUY' | 'SELL') {
  if (warnIfMarketClosed()) return
  const run = confirmationGeneration
  if (!await ensureKyc() || run !== confirmationGeneration || warnIfMarketClosed()) return
  side.value = next; message.value = ''; showConfirm.value = true
}
async function openTerm(next: 'UP' | 'DOWN') {
  if (warnIfMarketClosed()) return
  const run = confirmationGeneration
  if (!await ensureKyc() || run !== confirmationGeneration || warnIfMarketClosed()) return
  direction.value = next; message.value = ''; showConfirm.value = true; void refreshOption()
}
async function submit() {
  if (busy.value || kycChecking.value || warnIfMarketClosed()) return
  const run = confirmationGeneration, funding = fundingSource.value, mode = accountMode()
  if (!await ensureKyc()) { if (kycPromptOpen.value) showConfirm.value = false; return }
  if (run !== confirmationGeneration || funding !== fundingSource.value || mode !== accountMode() || !showConfirm.value) return
  wallet.tick = performance.now()
  if (funding === 'TRIAL' && (!wallet.state.eligible || wallet.state.available <= 0)) { showConfirm.value=false; return }
  const reason = activeTab.value === 'contract' ? contractReason.value || protectionReason.value : termReason.value
  if (reason) { message.value = reason; return }
  busy.value = true; message.value = ''
  try {
    const contract = activeTab.value === 'contract'
    const response: any = await request.post(`/trade/${contract ? 'contract' : 'option'}/order`, contract ? {
      fundingSource: funding, symbol: currentSymbol.value, side: side.value, type: orderType.value === 'limit' ? 'LIMIT' : 'MARKET', quantity: String(quantity.value), specVersion: symbolInfo.value?.specVersion, quantityUnitType: symbolInfo.value?.quantityUnitType, leverage: leverage.value,
      price: orderType.value === 'limit' ? Number(limitPrice.value) : null, currentPrice: currentPrice.value,
      ...protection.value,
    } : { fundingSource: funding, symbol: currentSymbol.value, direction: direction.value, duration: duration.value, amount: Number(amount.value), currentPrice: currentPrice.value })
    if (run !== confirmationGeneration || funding !== fundingSource.value || mode !== accountMode() || disposed) return
    if (response.success === false) throw new Error(response.message || text('下單失敗', 'Order failed'))
    showConfirm.value = false; message.value = text('訂單已提交，可在下方查看實際成交與狀態', 'Order submitted. View execution and status below.')
    if (contract) contractTab.value = orderType.value === 'limit' ? 'pending' : 'positions'
    else termTab.value = 'active'
    revision.value++; afterOrderChange()
  } catch (e: any) {
    if (run !== confirmationGeneration || disposed) return
    if (handleKycError(e)) showConfirm.value = false
    else message.value = e.message
  }
  finally { busy.value = false }
}
async function selectSymbol(item: any) {
  const version = ++selectionVersion; confirmationGeneration++
  showSymbols.value = false; currentSymbol.value = item.symbol; currentCategory.value = item.category || 'Crypto'
  // Price drafts belong to an instrument, never carry protection to another market.
  limitPrice.value = 0; takeProfit.value = ''; stopLoss.value = ''; showConfirm.value = false
  try {
    // The catalog is already loaded. Use a private synchronous owner instead of a second category fetch that may finish after unmount.
    await market.subscribeSymbols([item], 'advanced-trade-selected')
    if (version !== selectionVersion || disposed) return
    if (orderType.value === 'limit') limitPrice.value = Number(currentPrice.value.toFixed(precision.value))
  } catch (e: any) { if (version === selectionVersion && !disposed) message.value = e.message }
}
function syncRouteSymbol() {
  if (disposed || route.path !== '/trade') return
  const item = catalog.value.find(s => s.symbol === route.query.symbol) || catalog.value[0]
  if (item && item.symbol !== currentSymbol.value) return selectSymbol(item)
}
watch(() => route.query.symbol, syncRouteSymbol, { flush: 'sync' })
watch(() => route.query.tab, tab => { activeTab.value = tab === 'term' ? 'term' : 'contract' })
watch(activeTab, tab => { confirmationGeneration++; showConfirm.value = false; openCount.value = 0; pendingCount.value = 0; message.value = ''; if (tab === 'term') void refreshOption() })
onMounted(async () => {
  timer = setInterval(() => { now.value = Date.now(); if (activeTab.value === 'term' && document.visibilityState === 'visible' && performance.now() - optionAttempt > 15000) void refreshOption() }, 1000)
  void loadDurations(); void refreshOption()
  try {
    const response: any = await request.get('/market/all')
    if (disposed) return
    if (!Array.isArray(response.list) || response.success === false) throw new Error(response.message || text('品种资料载入失败', 'Instrument data unavailable'))
    catalog.value = response.list
    if (!catalog.value.length) message.value = text('暂无可交易品种', 'No instruments available')
    void market.subscribeSymbols(catalog.value.map(s => ({ symbol: s.symbol, category: s.category || 'Crypto', alltickSymbol: s.alltickSymbol || s.symbol })), 'advanced-trade')
    await syncRouteSymbol()
  } catch (e: any) { message.value = e.message }
})
onUnmounted(() => {
  disposed = true; selectionVersion++; confirmationGeneration++; clearInterval(timer)
  marketWebSocket.release('advanced-trade'); marketWebSocket.release('advanced-trade-selected')
})
</script>
<template>
  <TradeSheet :open="showMarketClosed" :title="locale.t('marketClosed')" @close="showMarketClosed = false">
    <p role="alert">{{ locale.t('marketClosed') }}</p>
    <template #footer><button class="sheet-primary buy" @click="showMarketClosed = false">{{ text('確認', 'Confirm') }}</button></template>
  </TradeSheet>
  <TradeSheet :open="kycPromptOpen" :title="locale.t('verification')" @close="kycPromptOpen = false">
    <p role="alert">{{ kycPromptMessage }}</p>
    <template #footer><div class="primary-actions">
      <button @click="kycPromptOpen = false">{{ locale.t('cancel') }}</button>
      <button class="sheet-primary buy" @click="goToVerification">{{ text('確認', 'Confirm') }}</button>
    </div></template>
  </TradeSheet>
  <AdvancedLayout :nav="true" :title="text('交易', 'Trade')">
  <section class="trade-page" :class="activeTab">
    <header class="product-header"><div class="segments"><button :class="{ active: activeTab === 'contract' }" @click="activeTab = 'contract'">{{ locale.t('contract') }}</button><button :class="{ active: activeTab === 'term' }" @click="activeTab = 'term'">{{ locale.t('term') }}</button></div><button class="help" :aria-label="text('交易規則', 'Trading rules')" @click="showRules = true">?</button></header>
    <section class="market-card">
      <div class="quote-header"><div><button class="symbol-select" @click="showSymbols = true">{{ displayName }}<span class="ui-chevron ui-chevron--down" aria-hidden="true"></span></button><p class="symbol-caption">{{ currentCategory === 'Forex' ? displayName : (locale.locale.startsWith('zh') ? symbolInfo?.nameCn : symbolInfo?.nameEn) }} <span>{{ locale.categoryLabel(currentCategory) }}</span></p></div><div class="quote-price" :class="change == null ? '' : change < 0 ? 'down' : 'up'"><strong>{{ price(currentPrice) }}</strong><div class="quote-details"><time class="quote-time">{{ quoteTime }}</time><span>{{ change == null ? '—' : `${change >= 0 ? '+' : ''}${change.toFixed(2)}%` }} <small>24h</small></span></div></div></div>
      <button class="chart-collapse" :aria-expanded="chartVisible" @click="chartExpanded = !chartVisible; showChart = chartExpanded">{{ chartVisible ? text('行情 / 收起', 'Chart / collapse') : text('行情 / 展开', 'Chart / expand') }}<span :class="['ui-chevron', chartVisible ? 'ui-chevron--up' : 'ui-chevron--down']" aria-hidden="true"></span></button>
      <p v-if="quoteReason" class="quote-state" role="status">{{ quoteReason }}</p>
      <template v-if="chartVisible"><div class="intervals"><button v-for="interval in intervals" :key="interval" :class="{ active: currentInterval === interval }" :aria-pressed="currentInterval === interval" @click="currentInterval = interval">{{ interval === '1w' ? text('1週', '1W') : interval === '1M' ? text('1月', '1MO') : text(interval, interval) }}</button></div><div class="mobile-chart"><KlineChart :symbol="currentSymbol" :category="currentCategory" :interval="currentInterval" :height="190" compact :price-precision="precision" /></div></template>
    </section>
    <section class="trading-card">
      <div v-if="activeTab === 'contract'" class="section-tabs"><button :class="{ active: contractTab === 'entry' }" @click="contractTab = 'entry'">{{ text('下單', 'Order') }}</button><button :class="{ active: contractTab === 'positions' }" @click="contractTab = 'positions'; showChart = false">{{ text('持倉', 'Positions') }} ({{ openCount }})</button><button :class="{ active: contractTab === 'pending' }" @click="contractTab = 'pending'; showChart = false">{{ text('委託', 'Pending') }} ({{ pendingCount }})</button></div>
      <div v-else class="section-tabs"><button :class="{ active: termTab === 'active' }" @click="termTab = 'active'">{{ text('進行中', 'Active') }} ({{ openCount }})</button><button :class="{ active: termTab === 'history' }" @click="termTab = 'history'">{{ text('歷史', 'History') }}</button><router-link to="/orders">{{ text('全部', 'All') }}<span class="ui-chevron" aria-hidden="true"></span></router-link></div>
      <div v-if="activeTab === 'contract' && contractTab === 'entry'" class="entry-form">
        <div class="order-toolbar"><div class="segments"><button :class="{ active: orderType === 'market' }" @click="orderType = 'market'">{{ locale.t('marketPrice') }}</button><button :class="{ active: orderType === 'limit' }" @click="orderType = 'limit'">{{ text('掛單', 'Limit') }}</button></div><LeverageControl v-model="leverage" :max="maxLeverage" :disabled="!symbolInfo" :symbol="displayName" /></div>
        <p v-if="orderType === 'limit'" class="hint">{{ text('限價委託 · 買入≤委託價，賣出≥委託價', 'Limit order · Buy ≤ limit, Sell ≥ limit') }}</p>
        <label v-if="orderType === 'limit'" class="field">{{ text('委託價格', 'Limit price') }} ({{ symbolInfo?.quoteCurrency }})<input v-model.number="limitPrice" type="number" inputmode="decimal" :step="priceStep" min="0" /></label>
        <label class="field" for="contract-quantity">{{ `${text('数量', 'Quantity')}（${unitLabel}）` }}</label><div class="step-input"><button :aria-label="text('減少數量', 'Decrease quantity')" @click="adjustQuantity(-1)">−</button><input id="contract-quantity" v-model.number="quantity" type="number" inputmode="decimal" :step="quantityStep" :min="quantityStep" /><button :aria-label="text('增加數量', 'Increase quantity')" @click="adjustQuantity(1)">+</button></div>
        <p v-if="symbolInfo?.quantityUnitType === 'BASE_ASSET'" class="hint">{{ locale.locale === 'ja' ? `1 ${unitLabel}あたりの往復手数料：${feePerLot} USD（固定）` : `每1 ${unitLabel} 固定往返佣金 ${feePerLot} USD` }}</p>
        <p class="hint">{{ text('最小變動', 'Step') }} {{ quantityStep }} {{ unitLabel }} · 1 {{ unitLabel }} = {{ lotSize }} {{ baseCurrencyName }}</p>
        <div class="allocation"><input type="range" min="0" max="100" step="1" :value="allocationPercent" :style="{ '--allocation': `${allocationPercent}%` }" :disabled="!canAllocate" :aria-label="text('倉位比例', 'Position allocation')" @input="setAllocation(Number(($event.target as HTMLInputElement).value))" /><div><button v-for="value in [0,25,50,75,100]" :key="value" :disabled="!canAllocate" :aria-pressed="allocationPercent === value" @click="setAllocation(value)">{{ value }}%</button></div></div>
        <dl class="fees">
          <div class="funding-selector" data-testid="funding-selector">
            <dt><span>{{ text('資金來源', 'Funding source') }}</span><time v-if="fundingSource === 'TRIAL' && wallet.state.expiresAt != null" data-testid="trade-trial-countdown">{{ wallet.remaining }}</time></dt>
            <dd><AppSelect class="funding-select" compact :model-value="fundingSource" :options="fundingOptions" :label="text('資金來源', 'Funding source')" :disabled="busy || kycChecking" @update:model-value="chooseFunding($event as FundingSource)" /></dd>
          </div>
          <div><dt>{{ text('可用資金', 'Available') }} (USD)</dt><dd>{{ money(available) }}</dd></div><div><dt>{{ locale.t('margin') }} (USD)</dt><dd>{{ money(margin) }}</dd></div><div><dt>{{ locale.t('fee') }} (USD)</dt><dd>{{ displayFee(fee) }}</dd></div><div><dt>{{ text('買入 / 賣出預計強平價', 'Buy / Sell liquidation') }} ({{ symbolInfo?.quoteCurrency }})</dt><dd>{{ price(liquidation.buy) }} / {{ price(liquidation.sell) }}</dd></div></dl>
        <button class="disclosure" @click="showRisk = true">{{ text('止盈 / 止損', 'Take profit / Stop loss') }}<span>{{ protection.takeProfit != null || protection.stopLoss != null ? text('已設定', 'Configured') : text('未設定', 'Not set') }}<span class="ui-chevron" aria-hidden="true"></span></span></button>
        <button class="disclosure" @click="showEstimates = !showEstimates">{{ text('強平估算與計算說明', 'Liquidation estimates') }}<span :class="['ui-chevron', showEstimates ? 'ui-chevron--up' : 'ui-chevron--down']" aria-hidden="true"></span></button>
        <div v-if="showEstimates" class="estimates"><p>{{ text('按帳戶整體權益估算，假設其他品種價格與匯率不變。— 表示資料不足、數量無效或無正值強平價。倉位比例包含保證金及手續費。', 'Account equity estimate with other prices and FX held constant. — means missing data, invalid size or no positive liquidation price. Allocation includes margin and fee.') }}</p></div>
      </div>
      <dl v-if="activeTab === 'term'" class="fees">
          <div class="funding-selector" data-testid="funding-selector">
            <dt><span>{{ text('資金來源', 'Funding source') }}</span><time v-if="fundingSource === 'TRIAL' && wallet.state.expiresAt != null" data-testid="trade-trial-countdown">{{ wallet.remaining }}</time></dt>
            <dd><AppSelect class="funding-select" compact :model-value="fundingSource" :options="fundingOptions" :label="text('資金來源', 'Funding source')" :disabled="busy || kycChecking" @update:model-value="chooseFunding($event as FundingSource)" /></dd>
          </div>
          <div><dt>{{ text('可用資金', 'Available') }} (USD)</dt><dd>{{ money(optionAvailable) }}</dd></div>
      </dl>
      <TradeOrders v-show="activeTab === 'term' || contractTab !== 'entry'" :mode="activeTab" :symbol="currentSymbol" :catalog="catalog" :tab="activeTab === 'contract' ? contractTab : termTab" :revision="revision" @counts="counts" @changed="afterOrderChange" />
    </section>
    <p v-if="message && !showConfirm" class="page-message" role="status">{{ message }}</p>
    <section class="action-dock">
      <template v-if="activeTab === 'term'"><div class="dock-meta"><button @click="openTerm(direction)">{{ text('期限', 'Duration') }} <strong>{{ config?.label || '—' }}<span class="ui-chevron ui-chevron--down" aria-hidden="true"></span></strong></button><button @click="showRules = true">{{ text('交易規則', 'Rules') }}<span class="ui-chevron" aria-hidden="true"></span></button></div><div class="primary-actions"><button class="buy" :disabled="!canStartBusiness('option') || kycChecking || (tradeVerified && quoteStatus !== 'closed' && (!!quoteReason || !config))" @click="openTerm('UP')">{{ directionLabel('買漲 ↑', 'Buy up ↑') }}<span class="ui-inline-arrow" aria-hidden="true">↑</span></button><button class="sell" :disabled="!canStartBusiness('option') || kycChecking || (tradeVerified && quoteStatus !== 'closed' && (!!quoteReason || !config))" @click="openTerm('DOWN')">{{ directionLabel('買跌 ↓', 'Buy down ↓') }}<span class="ui-inline-arrow" aria-hidden="true">↓</span></button></div><p v-if="!quoteReason">{{ durationError || text('選擇方向後，確認金額與到期時間', 'Choose a direction, then confirm amount and expiry') }}</p></template>
      <template v-else-if="contractTab === 'entry'"><div class="primary-actions"><button class="buy" :disabled="!canStartBusiness('contract') || kycChecking || (tradeVerified && quoteStatus !== 'closed' && !!contractReason)" @click="openContract('BUY')">{{ text('買入 / 做多', 'Buy / Long') }}</button><button class="sell" :disabled="!canStartBusiness('contract') || kycChecking || (tradeVerified && quoteStatus !== 'closed' && !!contractReason)" @click="openContract('SELL')">{{ text('賣出 / 做空', 'Sell / Short') }}</button></div><p v-if="!quoteReason && contractReason" role="status">{{ contractReason }}</p></template>
      <button v-else class="return-order" @click="contractTab = 'entry'">{{ text('返回下單', 'Back to order') }}</button>
    </section>

    <TradeSheet :open="showSymbols" :title="text('選擇交易品種', 'Select instrument')" @close="showSymbols = false">
      <div class="symbol-picker-controls">
        <input class="search-input" v-model="symbolSearch" type="search" :placeholder="text('搜尋品種', 'Search instruments')" :aria-label="text('搜尋品種', 'Search instruments')" />
        <div class="symbol-categories" role="group" :aria-label="text('選擇交易品種', 'Select instrument')">
          <button type="button" :aria-pressed="symbolCategory === ''" @click="symbolCategory = ''">{{ locale.t('all') }}</button>
          <button v-for="category in symbolCategories" :key="category" type="button" :aria-pressed="symbolCategory === category" @click="symbolCategory = category">{{ pickerCategoryLabel(category) }}</button>
        </div>
      </div>
      <div aria-live="polite">
        <button v-for="item in filteredSymbols" :key="item.symbol" class="symbol-item" @click="selectSymbol(item)"><span>{{ symbolName(item) }}<small>{{ item.category === 'Forex' ? symbolName(item) : (locale.locale === 'zh-TW' ? item.nameCn : item.nameEn) || item.symbol }}</small></span><span>{{ market.getPrice(item.symbol) > 0 ? market.getPrice(item.symbol).toFixed(item.pricePrecision ?? 2) : '—' }}</span></button>
        <p v-if="!filteredSymbols.length" class="symbol-empty">{{ locale.t('noData') }}</p>
      </div>
    </TradeSheet>
    <TradeSheet :open="showRisk" :title="text('止盈 / 止損', 'Take profit / Stop loss')" @close="showRisk = false">
      <p>{{ displayName }} · {{ symbolInfo?.quoteCurrency }}</p>
      <label class="field">{{ locale.t('takeProfit') }} ({{ symbolInfo?.quoteCurrency }})<input class="price-input" v-model="takeProfit" type="number" inputmode="decimal" :step="priceStep" :aria-label="locale.t('takeProfit')" /></label>
      <label class="field">{{ locale.t('stopLoss') }} ({{ symbolInfo?.quoteCurrency }})<input class="price-input" v-model="stopLoss" type="number" inputmode="decimal" :step="priceStep" :aria-label="locale.t('stopLoss')" /></label>
      <p class="hint">{{ text('買入：止盈高於參考價、止損低於參考價；賣出反之。選方向後再次校驗。', 'Long: take profit above reference, stop below. Short: the reverse. Revalidated after choosing side.') }}</p>
      <template #footer><button class="sheet-primary buy" @click="showRisk = false">{{ text('保留設定', 'Keep settings') }}</button></template>
    </TradeSheet>
    <TradeSheet :open="showConfirm" :busy="busy" :title="activeTab === 'contract' ? text('確認合約交易', 'Confirm contract order') : text('確認期限交易', 'Confirm option order')" @close="showConfirm = false">
      <p data-testid="confirm-funding">{{ text('资金来源', 'Funding source') }}: {{ fundingSource }}</p>
      <div class="confirm-symbol"><strong>{{ displayName }}</strong><strong class="up">{{ price(currentPrice) }}</strong></div>
      <template v-if="activeTab === 'term'">
        <div class="segments direction"><button :class="{ active: direction === 'UP' }" @click="direction = 'UP'">{{ directionLabel('買漲 ↑', 'Buy up ↑') }}<span class="ui-inline-arrow" aria-hidden="true">↑</span></button><button :class="{ 'active sell': direction === 'DOWN' }" @click="direction = 'DOWN'">{{ directionLabel('買跌 ↓', 'Buy down ↓') }}<span class="ui-inline-arrow" aria-hidden="true">↓</span></button></div>
        <p class="hint">{{ direction === 'UP' ? text('到期價格高於成交價格時獲利', 'Profit when expiry price exceeds entry') : text('到期價格低於成交價格時獲利', 'Profit when expiry price is below entry') }}</p>
        <label class="field">{{ text('到期時間', 'Expiry') }}</label><div class="duration-grid"><button v-for="item in durations" :key="item.value" :class="{ active: duration === item.value }" @click="duration = item.value">{{ item.label }}</button></div>
        <label class="field">{{ text('交易金額', 'Amount') }} (USD)<input v-model="amount" type="number" inputmode="decimal" step="any" :min="config?.minAmount" :max="config?.maxAmount" /></label><p class="hint">{{ text('可用餘額', 'Available') }} {{ money(optionAvailable) }} USD · {{ config?.minAmount ?? '—' }} – {{ config?.maxAmount ?? '—' }}</p>
        <dl class="fees"><div><dt>{{ text('獲利時淨收益', 'Net profit if won') }}</dt><dd class="up">+{{ money(expectedProfit) }} USD</dd></div><div><dt>{{ text('虧損時損失', 'Loss if lost') }}</dt><dd class="down">−{{ money(expectedLoss) }} USD</dd></div><div><dt>{{ text('收益率', 'Profit rate') }}</dt><dd>{{ config ? (config.profitRate * 100).toFixed(2) + '%' : '—' }}</dd></div><div><dt>{{ text('到期時間', 'Expiry') }}</dt><dd>{{ text('成交後', 'After entry') }} {{ config?.label }}</dd></div></dl>
        <p class="hint">{{ text('平價按虧損比例結算；不另收交易手續費。收益與損失按期限設定，以服務端結算為準。', 'A tie settles at the configured loss rate. No separate trading fee. Server settlement and duration settings apply.') }}</p>
      </template>
      <template v-else>
        <div class="confirm-order-type"><strong :class="side === 'BUY' ? 'up' : 'down'">{{ side === 'BUY' ? text('買入 / 做多', 'Buy / Long') : text('賣出 / 做空', 'Sell / Short') }} · {{ orderType === 'limit' ? text('掛單（限價）', 'Limit') : text('市價', 'Market') }}</strong><LeverageControl v-model="leverage" :max="maxLeverage" :symbol="displayName" /></div>
        <label v-if="orderType === 'limit'" class="field">{{ text('委託價格', 'Limit price') }} ({{ symbolInfo?.quoteCurrency }})<input v-model.number="limitPrice" type="number" inputmode="decimal" :step="priceStep" /></label><label class="field">{{ `${text('数量', 'Quantity')}（${unitLabel}）` }}<input v-model.number="quantity" type="number" inputmode="decimal" :step="quantityStep" /></label>
        <dl class="fees"><div><dt>{{ locale.t('margin') }} (USD)</dt><dd>{{ money(margin) }}</dd></div><div><dt>{{ locale.t('fee') }} (USD)</dt><dd>{{ displayFee(fee) }}</dd></div><div><dt>{{ text('預估佔用', 'Est. total') }} (USD)</dt><dd>{{ money(total) }}</dd></div><div><dt>{{ text('預計強平價', 'Est. liquidation') }}</dt><dd>{{ price(side === 'BUY' ? liquidation.buy : liquidation.sell) }}</dd></div></dl>
        <p class="hint">{{ text('依帳戶整體權益估算，假設其他價格與匯率不變；— 表示資料不足或無正值強平價。', 'Account equity estimate with other prices and FX held constant; — means missing data or no positive liquidation price.') }}</p>
        <p v-if="orderType === 'limit'" class="notice">{{ text('委託未成交前不代表已建立持倉；買入≤委託價、賣出≥委託價時按服務端新鮮報價成交，資金不足時繼續等待。', 'Pending is not a position. Buy fills at or below the limit, sell at or above, using fresh server quotes; insufficient funds may defer execution.') }}</p>
      </template>
      <p v-if="message" class="error" role="alert">{{ message }}</p>
      <template #footer><p class="error" role="status">{{ activeTab === 'contract' ? contractReason || protectionReason : termReason }}</p><button class="sheet-primary" :class="(activeTab === 'contract' ? side === 'BUY' : direction === 'UP') ? 'buy' : 'sell'" :disabled="!canStartBusiness(activeTab === 'contract' ? 'contract' : 'option') || busy || kycChecking || (tradeVerified && !!(activeTab === 'contract' ? contractReason || protectionReason : termReason))" @click="submit">{{ busy ? text('提交中…', 'Submitting…') : text('確認', 'Confirm') }} {{ activeTab === 'contract' ? `${side === 'BUY' ? text('買入', 'Buy') : text('賣出', 'Sell')} · ${quantity} ${unitLabel}` : `${direction === 'UP' ? text('買漲', 'Buy up') : text('買跌', 'Buy down')} · ${money(amount)} USD` }}</button><p class="footnote">{{ text('價格與費用以實際成交為準', 'Execution price and final costs may differ') }}</p></template>
    </TradeSheet>
    <TradeSheet :open="showRules" :title="text('交易規則', 'Trading rules')" @close="showRules = false"><p v-if="activeTab === 'contract'">{{ text('市價單按服務端新鮮報價成交。掛單為限價委託：買單在現價不高於委託價、賣單在現價不低於委託價時成交。委託先凍結保證金及手續費，成交時按實際價格調整；撤單解凍。槓桿上限與合約規格依品種設定。', 'Market orders execute at fresh server quotes. Buy limits fill at or below the limit; sell limits at or above it. Margin and fee are reserved, adjusted on execution and released on cancellation. Instrument leverage limits and lot sizes apply.') }}</p><p v-else>{{ text('買漲：到期價高於成交價獲利；買跌：到期價低於成交價獲利。平價按配置的虧損比例結算，無另收交易手續費。期限、金額上下限與盈虧比例由服務端設定；到期由服務端結算，報價失效時可能延後。', 'Up wins above entry; down wins below entry. A tie uses the configured loss rate; no separate trading fee. Durations, amount limits and rates come from the server. Expiry settlement may be deferred while quotes are unavailable.') }}</p><template #footer><button class="sheet-primary buy" @click="showRules = false">{{ text('知道了', 'Got it') }}</button></template></TradeSheet>
  </section>
  </AdvancedLayout>
</template>

<style scoped>
.trade-page{--line:#e9edef;color:#252a30;background:#fff;min-width:0;font-variant-numeric:tabular-nums;padding:0 0 20px;font-size:14px;line-height:1.45}
button,input{font:inherit;box-sizing:border-box}button{cursor:pointer;border:0;min-height:44px;background:transparent;color:inherit}button:disabled{opacity:.45;cursor:not-allowed}button:focus-visible,input:focus-visible{outline:2px solid #736582;outline-offset:2px}input{min-width:0}input[type=number]::-webkit-inner-spin-button{appearance:none}
.product-header{display:flex;align-items:center;gap:8px;margin-bottom:20px}.help{width:44px;font-size:18px}.segments{display:flex;flex:1;gap:6px;min-width:0}.segments button{flex:1;border-radius:7px;background:#f5f6f7;padding:8px;font-size:12px;font-weight:400;min-height:36px}.active{background:#f5f2f7!important;color:#736582!important}
.fees>.funding-selector{grid-column:1/-1;display:flex;align-items:center;justify-content:space-between;gap:8px;padding:0 0 6px}.funding-selector dt{display:flex;align-items:center;flex-wrap:wrap;gap:4px 6px;flex:1;min-width:0;overflow-wrap:anywhere}.funding-selector dd{display:flex;align-items:center;justify-content:flex-end;flex-wrap:wrap;gap:4px 8px;max-width:65%;margin:0}.funding-selector .funding-select{width:132px;max-width:100%}.funding-selector time{font-size:11px;font-weight:400;font-variant-numeric:tabular-nums;color:#736582}
.market-card{background:white;border:1px solid var(--line);border-radius:10px;padding:12px;margin-bottom:20px}.quote-header{display:flex;flex-direction:column;align-items:flex-start;gap:8px}.quote-header>div:first-child{width:100%;display:flex;justify-content:space-between;align-items:center;gap:8px}.symbol-select{display:flex;align-items:center;min-height:24px;padding:0;font-size:17px;font-weight:500;overflow-wrap:anywhere;text-align:left}.symbol-select .ui-chevron{width:24px;height:24px}.symbol-caption{font-size:12px;color:#736582;margin:0;text-align:right}.symbol-caption span{display:block}.quote-price{text-align:left;max-width:100%}.quote-price strong{font-size:30px;font-weight:500;overflow-wrap:anywhere}.quote-details{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px;color:#707780;font-size:12px}.quote-price small{font-size:12px}.up{color:#56723e}.down,.error{color:#aa5363}.quote-state,.hint{color:#707780;font-size:12px;line-height:1.6;margin:8px 0}
.intervals{display:flex;gap:6px;overflow-x:auto;scrollbar-width:none;margin:12px 0}.intervals button{flex:1 0 44px;min-height:36px;background:#f5f6f7;border-radius:7px;font-size:12px;padding:4px}.chart-collapse{display:flex;align-items:center;justify-content:space-between;width:100%;color:#707780;min-height:32px;font-size:12px;text-align:left;padding:0}.mobile-chart :deep(.chart-workspace:not(.chart-fullscreen)){min-height:0;border-radius:7px}.mobile-chart :deep(.drawing-rail),.mobile-chart :deep(.study-strip),.mobile-chart :deep(.chart-select),.mobile-chart :deep(.toolbar-divider),.mobile-chart :deep(.chart-toolbar>button:nth-of-type(2)),.mobile-chart :deep(.chart-toolbar>button:nth-of-type(4)),.mobile-chart :deep(.chart-toolbar>button:nth-of-type(5)),.mobile-chart :deep(.chart-footer:not(.chart-warning)){display:none}.mobile-chart :deep(.chart-toolbar){order:2;min-height:32px;padding:0;justify-content:space-between}.mobile-chart :deep(button){min-height:44px;min-width:44px}
.section-tabs{display:flex;gap:6px;margin-bottom:20px}.section-tabs button,.section-tabs a{flex:1;min-width:0;padding:8px 4px;border-radius:7px;min-height:36px;background:#f5f6f7;font-size:12px;text-align:center;color:#707780;text-decoration:none;overflow-wrap:anywhere}.section-tabs a{display:flex;align-items:center;justify-content:center}.entry-form{border:1px solid var(--line);border-radius:10px;padding:12px}.order-toolbar{display:flex;flex-wrap:wrap;gap:12px}.order-toolbar .segments{flex-basis:100%}.order-toolbar :deep(.leverage-trigger){width:100%;justify-content:space-between;background:white;border:0;padding:0;font-size:14px}
.field{display:block;font-size:12px;color:#707780;margin:12px 0 8px}.field input,.price-input,.search-input{display:block;width:100%;height:50px;margin-top:8px;border:1px solid var(--line);border-radius:10px;padding:12px 14px;background:#f5f6f7;color:#252a30;font-size:16px}.step-input{display:flex;height:50px;border:1px solid var(--line);border-radius:10px;overflow:hidden;background:#f5f6f7}.step-input button{width:48px;flex:none;font-size:20px}.step-input input{flex:1;width:0;border:0;background:transparent;text-align:center;font-size:16px;color:inherit}.allocation input{width:100%;height:32px;accent-color:#736582}.allocation>div{display:flex;justify-content:space-between}.allocation button{font-size:12px;color:#707780;min-width:40px;padding:0}.allocation button[aria-pressed=true]{color:#736582}.fees{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:12px 0}.fees>div{min-width:0}.fees>div:last-child{grid-column:1/-1}.fees dt{font-size:12px;color:#707780}.fees dd{margin:4px 0 0;font-size:14px;overflow-wrap:anywhere}.disclosure{display:flex;justify-content:space-between;align-items:center;gap:8px;width:100%;text-align:left;padding:12px 0}.disclosure>span{color:#707780;font-size:12px}.estimates,.notice,.footnote{color:#707780;font-size:12px;line-height:1.6}.notice{background:#f5f2f7;border-radius:7px;padding:10px}
.action-dock{padding:20px 0 0}.primary-actions{display:flex;gap:8px}.primary-actions button,.sheet-primary,.return-order{min-height:48px;border:0;border-radius:10px;font-size:14px;font-weight:500;padding:12px;overflow-wrap:anywhere}.primary-actions button{flex:1;display:flex;justify-content:center;align-items:center;gap:4px}.buy,.return-order{background:#d9e6c8;color:#2f4129}.sell,.segments .sell{background:#aa5363!important;color:white!important}.sheet-primary{width:100%;background:#d9e6c8;color:#2f4129}.action-dock p{text-align:left;font-size:12px;color:#707780;margin-top:10px}.dock-meta{display:flex;justify-content:space-between;gap:8px;font-size:12px}.dock-meta strong{margin-left:6px}.return-order{width:100%}.page-message{background:#f5f2f7;padding:10px;border-radius:7px;font-size:12px;overflow-wrap:anywhere}
.symbol-item{width:100%;display:flex;justify-content:space-between;align-items:center;gap:12px;padding:12px 0;border-bottom:1px solid var(--line);text-align:left}.symbol-item span{min-width:0;overflow-wrap:anywhere}.symbol-item small{display:block;font-size:12px;color:#707780;margin-top:4px}.symbol-categories{display:flex;overflow-x:auto;gap:6px;margin:12px 0}.symbol-categories button{flex:none;font-size:12px;background:#f5f6f7;border-radius:7px;padding:8px}.symbol-categories button[aria-pressed=true]{background:#f5f2f7;color:#736582}.symbol-empty{color:#707780;text-align:center}.confirm-symbol,.confirm-order-type{display:flex;justify-content:space-between;align-items:center;gap:8px;overflow-wrap:anywhere}.duration-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:6px;margin:12px 0}.duration-grid button{background:#f5f6f7;border-radius:7px;font-size:12px}.error{font-size:12px;line-height:1.6;overflow-wrap:anywhere}.footnote{text-align:center}
@media(max-width:360px){.fees{gap:8px}.fees dd{font-size:13px}.quote-price strong{font-size:28px}}
</style>
