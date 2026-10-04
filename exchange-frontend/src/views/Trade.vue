<script setup lang="ts">
import { canStartBusiness } from '@/utils/tenantFeatures'
import { formatQuoteTime } from '@/utils/visitorRegion'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTradeKyc } from '@/utils/useTradeKyc'
import Tabbar from '@/components/Tabbar.vue'
import KlineChart from '@/components/KlineChart.vue'
import LeverageControl from '@/components/LeverageControl.vue'
import TradeSheet from '@/components/TradeSheet.vue'
import TradeOrders from '@/components/TradeOrders.vue'
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
import { DEFAULT_LEVERAGE, contractMargin, leverageLimit, quantityUnit, validQuantity, displayFee, decimalProduct } from '@/utils/contract'
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
const currentInterval = ref('5m'), intervals = ['1m', '5m', '15m', '30m', '1h', '1d', '1w', '1M']
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
const chartVisible = computed(() => activeTab.value === 'term' || contractTab.value === 'entry' || showChart.value)
const showRules = ref(false), showRisk = ref(false), showEstimates = ref(false), showConfirm = ref(false)
const side = ref<'BUY' | 'SELL'>('BUY'), busy = ref(false), message = ref(''), now = ref(Date.now()), keyboardOpen = ref(false)
const { verified: tradeVerified, checking: kycChecking, promptOpen: kycPromptOpen, promptMessage: kycPromptMessage, ensure: ensureKyc, handleError: handleKycError } = useTradeKyc(
  () => { void router.push('/login') }, value => { message.value = value })
function goToVerification() { kycPromptOpen.value = false; showConfirm.value = false; void router.push('/verification') }
let timer: ReturnType<typeof setInterval>, disposed = false, selectionVersion = 0
const currentPrice = computed(() => market.getPrice(currentSymbol.value))
const change = computed(() => market.getChange24h(currentSymbol.value)?.changePct)
const quoteStatus = computed(() => market.getQuoteStatus(currentSymbol.value, now.value))
const quoteReason = computed(() => !symbolInfo.value ? text('品種資料載入中', 'Loading instrument') : symbolInfo.value.isEnabled === false ? text('品種已停用', 'Instrument disabled') : quoteStatus.value === 'available' ? '' : quoteStatus.value === 'closed' ? locale.t('marketClosed') : quoteStatus.value === 'stale' ? text('報價已過期，等待更新', 'Quote expired; waiting for update') : text('行情暫不可用', 'Quote unavailable'))
const quoteTime = computed(() => { const timestamp = market.quoteStatusMap[currentSymbol.value]?.timestamp; return timestamp ? formatQuoteTime(timestamp) : '—' })
function price(value: any) { return value != null && Number.isFinite(Number(value)) && Number(value) > 0 ? Number(value).toFixed(precision.value) : '—' }
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
function adjustQuantity(delta: number) { quantity.value = Math.max(Number(symbolInfo.value?.minOrderQuantity ?? 0.01), Number((Number(quantity.value) + delta * quantityStep.value).toFixed(16))) }
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
    if (!Array.isArray(response)) throw new Error(text('期限設定載入失敗', 'Duration configuration failed'))
    durations.value = response.filter(d => d.enabled !== false && Number(d.duration) > 0 && d.profitRate != null && d.lossRate != null && [Number(d.profitRate), Number(d.lossRate)].every(v => Number.isFinite(v) && v >= 0)).map(d => ({ value: Number(d.duration), label: new Intl.NumberFormat(locale.locale, { style: 'unit', unit: 'second', unitDisplay: 'short' }).format(Number(d.duration)), profitRate: Number(d.profitRate), lossRate: Number(d.lossRate), minAmount: d.minAmount == null ? undefined : Number(d.minAmount), maxAmount: d.maxAmount == null ? undefined : Number(d.maxAmount) }))
    if (!config.value) duration.value = durations.value[0]?.value ?? 0
    durationError.value = durations.value.length ? '' : text('暫無可交易期限', 'No durations available')
  } catch (e: any) { durations.value = []; durationError.value = e.message }
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
watch([fundingSource, () => wallet.state.eligible, () => auth.token], () => {
  confirmationGeneration++; showConfirm.value = false
  if (fundingSource.value !== 'TRIAL' && !wallet.state.eligible) message.value = text('体验金资格不可用，已使用现金账户；存量订单仍可退出。', 'Trial credit unavailable. Cash account selected; existing orders can still close.')
}, { flush: 'sync' })
async function openContract(next: 'BUY' | 'SELL') { const run=confirmationGeneration; if (!await ensureKyc() || run!==confirmationGeneration) return; side.value = next; message.value = ''; showConfirm.value = true }
async function openTerm(next: 'UP' | 'DOWN') { const run=confirmationGeneration; if (!await ensureKyc() || run!==confirmationGeneration) return; direction.value = next; message.value = ''; showConfirm.value = true; void refreshOption() }
async function submit() {
  if (busy.value || kycChecking.value) return
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
  const version = ++selectionVersion
  showSymbols.value = false; currentSymbol.value = item.symbol; currentCategory.value = item.category || 'Crypto'
  // Price drafts belong to an instrument, never carry protection to another market.
  limitPrice.value = 0; takeProfit.value = ''; stopLoss.value = ''; showConfirm.value = false
  try {
    await market.initMarketService(currentCategory.value, 'trade-category')
    if (version !== selectionVersion || disposed) return
    await market.subscribeSymbol(item.symbol, currentCategory.value)
    if (version !== selectionVersion || disposed) return
    if (orderType.value === 'limit') limitPrice.value = Number(currentPrice.value.toFixed(precision.value))
  } catch (e: any) { message.value = e.message }
}
watch(() => route.query.tab, tab => { activeTab.value = tab === 'term' ? 'term' : 'contract' })
watch(activeTab, tab => { confirmationGeneration++; showConfirm.value = false; openCount.value = 0; pendingCount.value = 0; message.value = ''; if (tab === 'term') void refreshOption() })
function keyboardResize() { keyboardOpen.value = !!window.visualViewport && window.innerHeight - window.visualViewport.height > 140 }
onMounted(async () => {
  timer = setInterval(() => { now.value = Date.now(); if (activeTab.value === 'term' && document.visibilityState === 'visible' && performance.now() - optionAttempt > 15000) void refreshOption() }, 1000)
  window.visualViewport?.addEventListener('resize', keyboardResize)
  void loadDurations(); void refreshOption()
  try {
    const response: any = await request.get('/market/all')
    if (disposed) return
    catalog.value = response.list || []
    void market.subscribeSymbols(catalog.value.map(s => ({ symbol: s.symbol, category: s.category || 'Crypto', alltickSymbol: s.alltickSymbol || s.symbol })), 'trade')
    const initial = catalog.value.find(s => s.symbol === route.query.symbol) || catalog.value[0]
    if (initial) await selectSymbol(initial)
  } catch (e: any) { message.value = e.message }
})
onUnmounted(() => {
  disposed = true; selectionVersion++; clearInterval(timer)
  window.visualViewport?.removeEventListener('resize', keyboardResize)
  marketWebSocket.release('trade'); marketWebSocket.release('trade-category'); marketWebSocket.release('active')
})
</script>
<template>
  <TradeSheet :open="kycPromptOpen" :title="locale.t('verification')" @close="kycPromptOpen = false">
    <p role="alert">{{ kycPromptMessage }}</p>
    <template #footer><div class="primary-actions">
      <button @click="kycPromptOpen = false">{{ locale.t('cancel') }}</button>
      <button class="sheet-primary buy" @click="goToVerification">{{ text('確認', 'Confirm') }}</button>
    </div></template>
  </TradeSheet>
  <main class="trade-page" :class="[activeTab, { 'keyboard-open': keyboardOpen }]">
    <header class="product-header"><div class="segments"><button :class="{ active: activeTab === 'contract' }" @click="activeTab = 'contract'">{{ locale.t('contract') }}</button><button :class="{ active: activeTab === 'term' }" @click="activeTab = 'term'">{{ locale.t('term') }}</button></div><button class="help" :aria-label="text('交易規則', 'Trading rules')" @click="showRules = true">?</button></header>
    <section class="funding-selector" aria-label="Funding source" data-testid="funding-selector">
      <span>{{ text('资金来源', 'Funding source') }}</span>
      <button v-if="wallet.state.eligible && wallet.state.available > 0" type="button" data-source="TRIAL" :aria-pressed="fundingSource === 'TRIAL'" @click="chooseFunding('TRIAL')">{{ text('体验金', 'Trial credit') }}</button>
      <button type="button" :data-source="activeTab === 'contract' ? 'CONTRACT' : 'OPTION'" :aria-pressed="fundingSource !== 'TRIAL'" @click="chooseFunding(activeTab === 'contract' ? 'CONTRACT' : 'OPTION')">{{ activeTab === 'contract' ? locale.t('contractAccountTitle') : locale.t('optionAccountTitle') }}</button>
      <time v-if="fundingSource === 'TRIAL' && wallet.state.expiresAt != null" data-testid="trade-trial-countdown">{{ wallet.remaining }}</time>
    </section>
    <section class="market-card">
      <div class="quote-header"><div><button class="symbol-select" @click="showSymbols = true">{{ displayName }}<span class="ui-chevron ui-chevron--down" aria-hidden="true"></span></button><p class="symbol-caption">{{ currentCategory === 'Forex' ? displayName : (locale.locale.startsWith('zh') ? symbolInfo?.nameCn : symbolInfo?.nameEn) }} <span>{{ locale.categoryLabel(currentCategory) }}</span></p></div><div class="quote-price" :class="Number(change) < 0 ? 'down' : 'up'"><strong>{{ price(currentPrice) }}</strong><div class="quote-details"><time class="quote-time">{{ quoteTime }}</time><span>{{ change == null ? '—' : `${change >= 0 ? '+' : ''}${change.toFixed(2)}%` }} <small>24h</small></span></div></div></div>
      <button v-if="!chartVisible" class="chart-collapse" @click="showChart = true">{{ text('展開行情圖表', 'Expand chart') }}<span class="ui-chevron ui-chevron--down" aria-hidden="true"></span></button>
      <template v-if="chartVisible"><div class="intervals"><button v-for="interval in intervals" :key="interval" :class="{ active: currentInterval === interval }" :aria-pressed="currentInterval === interval" @click="currentInterval = interval">{{ interval === '1w' ? text('1週', '1W') : interval === '1M' ? text('1月', '1MO') : text(interval, interval) }}</button></div><div class="mobile-chart"><KlineChart :symbol="currentSymbol" :category="currentCategory" :interval="currentInterval" :height="activeTab === 'contract' ? 240 : 300" compact :price-precision="precision" /></div></template>
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
        <dl class="fees"><div><dt>{{ text('可用資金', 'Available') }} (USD)</dt><dd>{{ money(available) }}</dd></div><div><dt>{{ locale.t('margin') }} (USD)</dt><dd>{{ money(margin) }}</dd></div><div><dt>{{ locale.t('fee') }} (USD)</dt><dd>{{ displayFee(fee) }}</dd></div><div><dt>{{ text('買入 / 賣出預計強平價', 'Buy / Sell liquidation') }} ({{ symbolInfo?.quoteCurrency }})</dt><dd>{{ price(liquidation.buy) }} / {{ price(liquidation.sell) }}</dd></div></dl>
        <button class="disclosure" @click="showRisk = true">{{ text('止盈 / 止損', 'Take profit / Stop loss') }}<span>{{ protection.takeProfit != null || protection.stopLoss != null ? text('已設定', 'Configured') : text('未設定', 'Not set') }}<span class="ui-chevron" aria-hidden="true"></span></span></button>
        <button class="disclosure" @click="showEstimates = !showEstimates">{{ text('強平估算與計算說明', 'Liquidation estimates') }}<span :class="['ui-chevron', showEstimates ? 'ui-chevron--up' : 'ui-chevron--down']" aria-hidden="true"></span></button>
        <div v-if="showEstimates" class="estimates"><p>{{ text('按帳戶整體權益估算，假設其他品種價格與匯率不變。— 表示資料不足、數量無效或無正值強平價。倉位比例包含保證金及手續費。', 'Account equity estimate with other prices and FX held constant. — means missing data, invalid size or no positive liquidation price. Allocation includes margin and fee.') }}</p></div>
      </div>
      <TradeOrders v-show="activeTab === 'term' || contractTab !== 'entry'" :mode="activeTab" :symbol="currentSymbol" :catalog="catalog" :tab="activeTab === 'contract' ? contractTab : termTab" :revision="revision" @counts="counts" @changed="afterOrderChange" />
    </section>
    <p v-if="message && !showConfirm" class="page-message" role="status">{{ message }}</p>
    <section class="action-dock">
      <template v-if="activeTab === 'term'"><div class="dock-meta"><button @click="openTerm(direction)">{{ text('期限', 'Duration') }} <strong>{{ config?.label || '—' }}<span class="ui-chevron ui-chevron--down" aria-hidden="true"></span></strong></button><button @click="showRules = true">{{ text('交易規則', 'Rules') }}<span class="ui-chevron" aria-hidden="true"></span></button></div><div class="primary-actions"><button v-if="canStartBusiness('option')" class="buy" :disabled="kycChecking || (tradeVerified && (!!quoteReason || !config))" @click="openTerm('UP')">{{ directionLabel('買漲 ↑', 'Buy up ↑') }}<span class="ui-inline-arrow" aria-hidden="true">↑</span></button><button v-if="canStartBusiness('option')" class="sell" :disabled="kycChecking || (tradeVerified && (!!quoteReason || !config))" @click="openTerm('DOWN')">{{ directionLabel('買跌 ↓', 'Buy down ↓') }}<span class="ui-inline-arrow" aria-hidden="true">↓</span></button></div><p v-if="!quoteReason">{{ durationError || text('選擇方向後，確認金額與到期時間', 'Choose a direction, then confirm amount and expiry') }}</p></template>
      <template v-else-if="contractTab === 'entry'"><div class="primary-actions"><button v-if="canStartBusiness('contract')" class="buy" :disabled="kycChecking || (tradeVerified && !!contractReason)" @click="openContract('BUY')">{{ text('買入 / 做多', 'Buy / Long') }}</button><button v-if="canStartBusiness('contract')" class="sell" :disabled="kycChecking || (tradeVerified && !!contractReason)" @click="openContract('SELL')">{{ text('賣出 / 做空', 'Sell / Short') }}</button></div><p v-if="!quoteReason && contractReason" role="status">{{ contractReason }}</p></template>
      <button v-else class="return-order" @click="contractTab = 'entry'">{{ text('返回下單', 'Back to order') }}</button>
    </section>
    <Tabbar />
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
      <template #footer><p class="error" role="status">{{ activeTab === 'contract' ? contractReason || protectionReason : termReason }}</p><button v-if="canStartBusiness(activeTab === 'contract' ? 'contract' : 'option')" class="sheet-primary" :class="(activeTab === 'contract' ? side === 'BUY' : direction === 'UP') ? 'buy' : 'sell'" :disabled="busy || kycChecking || (tradeVerified && !!(activeTab === 'contract' ? contractReason || protectionReason : termReason))" @click="submit">{{ busy ? text('提交中…', 'Submitting…') : text('確認', 'Confirm') }} {{ activeTab === 'contract' ? `${side === 'BUY' ? text('買入', 'Buy') : text('賣出', 'Sell')} · ${quantity} ${unitLabel}` : `${direction === 'UP' ? text('買漲', 'Buy up') : text('買跌', 'Buy down')} · ${money(amount)} USD` }}</button><p class="footnote">{{ text('價格與費用以實際成交為準', 'Execution price and final costs may differ') }}</p></template>
    </TradeSheet>
    <TradeSheet :open="showRules" :title="text('交易規則', 'Trading rules')" @close="showRules = false"><p v-if="activeTab === 'contract'">{{ text('市價單按服務端新鮮報價成交。掛單為限價委託：買單在現價不高於委託價、賣單在現價不低於委託價時成交。委託先凍結保證金及手續費，成交時按實際價格調整；撤單解凍。槓桿上限與合約規格依品種設定。', 'Market orders execute at fresh server quotes. Buy limits fill at or below the limit; sell limits at or above it. Margin and fee are reserved, adjusted on execution and released on cancellation. Instrument leverage limits and lot sizes apply.') }}</p><p v-else>{{ text('買漲：到期價高於成交價獲利；買跌：到期價低於成交價獲利。平價按配置的虧損比例結算，無另收交易手續費。期限、金額上下限與盈虧比例由服務端設定；到期由服務端結算，報價失效時可能延後。', 'Up wins above entry; down wins below entry. A tie uses the configured loss rate; no separate trading fee. Durations, amount limits and rates come from the server. Expiry settlement may be deferred while quotes are unavailable.') }}</p><template #footer><button class="sheet-primary buy" @click="showRules = false">{{ text('知道了', 'Got it') }}</button></template></TradeSheet>
  </main>
</template>

<style scoped>
.funding-selector{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin:4px 0 12px;font-size:12px}.funding-selector button{border:1px solid #dce0e7;border-radius:8px;background:white;padding:4px 10px}.funding-selector button[aria-pressed=true]{border-color:#639700;background:#edf5df;color:#476e00}.funding-selector time{width:100%;font-variant-numeric:tabular-nums;color:#638c3d}
.trade-page { --green: #85bd00; --red: #ff4d4f; --line: #e6e8ed; min-height: 100dvh; background: #f5f7fb; color: #111; padding: 0 12px calc(174px + env(safe-area-inset-bottom)); font-family: 'PingFang SC','Microsoft YaHei',system-ui,sans-serif; font-variant-numeric: tabular-nums; }
button, input { font: inherit; box-sizing: border-box; }button { cursor: pointer; border: 0; min-height: 44px; background: transparent; color: inherit; }button:disabled { opacity: .45; cursor: not-allowed; }button:focus-visible, input:focus-visible { outline: 2px solid #639700; outline-offset: 2px; }input { min-width: 0; }input[type=number]::-webkit-inner-spin-button { appearance: none; }
.product-header { display: flex; align-items: center; gap: 6px; padding: 12px 0 8px; }.segments { display: flex; flex: 1; background: #f0f2f6; border-radius: 9px; padding: 3px; gap: 3px; }.segments button { flex: 1; border-radius: 7px; padding: 0 8px; font-weight: 600; }.active { background: #85bd00 !important; color: #fff !important; }.help { width: 44px; font-size: 19px; }.market-card,.trading-card { background: #fff; border-radius: 16px; padding: 12px; margin-bottom: 8px; }.quote-header { display: flex; justify-content: space-between; align-items: center; gap: 8px; }.symbol-select { display: inline-flex; align-items: center; font-size: 19px; font-weight: 700; padding: 0; text-align: left; }.symbol-select .ui-chevron { width: 44px; height: 44px; } .symbol-caption { margin: -2px 0 0; color: #777; font-size: 12px; }.symbol-caption span { padding-left: 6px; color: #999; }.quote-price { display: flex; flex-direction: column; text-align: right; }.quote-price strong { font-size: 27px; line-height: 1.2; }.quote-details { display: flex; align-items: baseline; justify-content: flex-end; gap: 6px; margin-top: 4px; white-space: nowrap; }.quote-details span, .quote-time { font-size: 11px; }.quote-time { color: #777; font-variant-numeric: tabular-nums; }.quote-price small { color: #777; }.up { color: #639700; }.down,.error { color: #e63a3e; }.quote-state { font-size: 12px; color: #777; margin: 8px 0; }.warning { color: #a26600; }.intervals { display: flex; flex-wrap: nowrap; gap: 2px; overflow-x: auto; overscroll-behavior-x: contain; scrollbar-width: none; background: #f5f7fb; border-radius: 7px; margin: 6px 0; padding: 2px; }.intervals::-webkit-scrollbar { display: none; }.intervals button { flex: 0 0 auto; min-width: 44px; min-height: 36px; padding: 0 8px; border-radius: 5px; font-size: 12px; white-space: nowrap; }.intervals button:focus-visible { outline-offset: -2px; }.chart-collapse { display: inline-flex; align-items: center; justify-content: center; gap: 4px; width: 100%; background: #f5f7fb; border-radius: 8px; color: #666; }
.mobile-chart :deep(.chart-workspace:not(.chart-fullscreen)) { min-height: 0; border-radius: 8px; }
.mobile-chart :deep(.chart-workspace:not(.chart-fullscreen) .drawing-rail), .mobile-chart :deep(.chart-workspace:not(.chart-fullscreen) .study-strip), .mobile-chart :deep(.chart-workspace:not(.chart-fullscreen) .chart-select), .mobile-chart :deep(.chart-workspace:not(.chart-fullscreen) .toolbar-divider), .mobile-chart :deep(.chart-workspace:not(.chart-fullscreen) .chart-toolbar > button:nth-of-type(2)), .mobile-chart :deep(.chart-workspace:not(.chart-fullscreen) .chart-toolbar > button:nth-of-type(4)), .mobile-chart :deep(.chart-workspace:not(.chart-fullscreen) .chart-toolbar > button:nth-of-type(5)) { display: none; }
.mobile-chart :deep(.chart-workspace:not(.chart-fullscreen) .chart-toolbar) { order: 2; min-height: 44px; border-top: 1px solid #e6e8ed; border-bottom: 0; padding: 0; justify-content: space-between; }.mobile-chart :deep(.chart-workspace:not(.chart-fullscreen) .chart-footer:not(.chart-warning)) { display: none; }.mobile-chart :deep(.chart-workspace:not(.chart-fullscreen) .chart-toolbar button) { min-height: 44px; min-width: 44px; font-size: 12px; }.mobile-chart :deep(.chart-dialog button), .mobile-chart :deep(.chart-warning button), .mobile-chart :deep(.chart-fullscreen button) { min-height: 44px; min-width: 44px; }.mobile-chart :deep(.chart-fullscreen .drawing-rail) { width: 48px; }
.section-tabs { display: flex; gap: 4px; padding-bottom: 8px; border-bottom: 1px solid var(--line); margin-bottom: 10px; }.section-tabs button { border-radius: 7px; font-size: 14px; font-weight: 600; padding: 0 12px; }.order-toolbar { display: flex; gap: 8px; }.order-toolbar :deep(.leverage-trigger) { min-height: 44px; background: #fff; padding-inline: 8px; }.order-toolbar .segments { min-width: 0; }.hint { font-size: 12px; color: #777; line-height: 1.5; margin: 6px 0; }.field { display: block; margin: 10px 0 6px; font-size: 14px; font-weight: 600; }.field input,.price-input,.search-input { width: 100%; height: 52px; border: 1px solid #dce0e7; border-radius: 8px; padding: 10px 12px; font-size: 16px; background: #fff; color: #111; }.field input { display: block; margin-top: 6px; }.step-input { display: flex; height: 48px; border: 1px solid #dce0e7; border-radius: 8px; overflow: hidden; }.step-input button { width: 48px; font-size: 22px; flex-shrink: 0; }.step-input input { flex: 1; width: 0; border: 0; border-inline: 1px solid var(--line); text-align: center; font-size: 18px; font-weight: 600; }.allocation input { width: 100%; height: 44px; accent-color: #85bd00; margin: 0; }.allocation > div { display: flex; justify-content: space-between; }.allocation button { min-width: 44px; padding: 0; font-size: 12px; color: #777; }.allocation button[aria-pressed=true] { color: #639700; font-weight: 700; }.fees { padding: 10px 12px; margin: 8px 0; border-radius: 10px; background: #f5f7fb; font-size: 13px; }.fees > div { display: flex; justify-content: space-between; gap: 12px; padding: 3px 0; }.fees dt { color: #777; }.fees dd { margin: 0; font-weight: 600; text-align: right; }.disclosure { display: flex; align-items: center; justify-content: space-between; width: 100%; border-bottom: 1px solid var(--line); text-align: left; font-size: 13px; font-weight: 600; }.disclosure span { color: #888; font-weight: 400; }.estimates { color: #777; font-size: 12px; line-height: 1.6; }
.action-dock { position: fixed; left: 0; right: 0; bottom: 64px; background: #fff; padding: 8px 16px; border-top: 1px solid #e6e8ed; z-index: 45; }.primary-actions { display: flex; gap: 12px; }.primary-actions button,.sheet-primary,.return-order { height: 52px; border: 0; border-radius: 9px; font-size: 16px; font-weight: 700; }.primary-actions button { display: inline-flex; align-items: center; justify-content: center; gap: 4px; flex: 1; } .segments.direction button { display: inline-flex; align-items: center; justify-content: center; gap: 4px; }.buy { background: #85bd00; color: #fff; }.sell,.segments .sell { background: #ff4d4f !important; color: #fff; }.action-dock p { text-align: center; margin: 6px 0 0; color: #777; font-size: 11px; line-height: 1.4; }.dock-meta { display: flex; justify-content: space-between; font-size: 12px; }.dock-meta button { display: inline-flex; align-items: center; padding: 0; }.dock-meta strong { display: inline-flex; align-items: center; color: #111; margin-left: 6px; } .dock-meta strong .ui-chevron { height: 44px; }.return-order { width: 100%; border: 1px solid #85bd00; color: #639700; background: #fff; }.keyboard-open .action-dock,.keyboard-open :deep(.tabbar) { display: none; }.page-message { padding: 12px; background: #fff; font-size: 13px; }
.symbol-item { width: 100%; display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid #e6e8ed; text-align: left; }.symbol-item small { display: block; font-size: 12px; color: #777; margin-top: 4px; }.risk-toggle { display: flex; align-items: center; gap: 8px; min-height: 44px; font-size: 14px; }.risk-toggle input { width: 20px; height: 20px; accent-color: #85bd00; }.sheet-primary { width: 100%; }.risk-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }.risk-grid > div { min-width: 0; }.confirm-symbol { display: flex; justify-content: space-between; font-size: 21px; margin-bottom: 12px; }.confirm-order-type { display: flex; justify-content: space-between; align-items: center; gap: 8px; font-size: 14px; }.duration-grid { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 8px; margin: 10px 0; }.duration-grid button { background: #f5f7fb; border: 1px solid #e6e8ed; border-radius: 7px; font-size: 14px; padding: 0 4px; }.notice { padding: 10px; border-radius: 8px; background: #fff5de; color: #9b6900; font-size: 12px; line-height: 1.6; }.footnote { font-size: 12px; text-align: center; color: #888; margin: 8px 0 0; }.error { font-size: 12px; margin: 4px 0 8px; }
@media (max-width: 375px) { .funding-selector{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin:4px 0 12px;font-size:12px}.funding-selector button{border:1px solid #dce0e7;border-radius:8px;background:white;padding:4px 10px}.funding-selector button[aria-pressed=true]{border-color:#639700;background:#edf5df;color:#476e00}.funding-selector time{width:100%;font-variant-numeric:tabular-nums;color:#638c3d}
.trade-page { padding-inline: 8px; }.market-card,.trading-card { padding: 10px; }.quote-price strong { font-size: 24px; }.section-tabs button { padding-inline: 10px; }.order-toolbar { gap: 6px; } }
@media (min-width: 700px) { .funding-selector{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin:4px 0 12px;font-size:12px}.funding-selector button{border:1px solid #dce0e7;border-radius:8px;background:white;padding:4px 10px}.funding-selector button[aria-pressed=true]{border-color:#639700;background:#edf5df;color:#476e00}.funding-selector time{width:100%;font-variant-numeric:tabular-nums;color:#638c3d}
.trade-page { max-width: 560px; margin: auto; }.action-dock { max-width: 560px; margin: auto; box-sizing: border-box; } }
.section-tabs a { min-width: 44px; justify-content: flex-end; margin-left: auto; display: flex; align-items: center; min-height: 44px; color: #777; text-decoration: none; font-size: 13px; }
.term .mobile-chart :deep(.chart-workspace:not(.chart-fullscreen)) { height: clamp(240px, calc(100dvh - 540px), 300px) !important; }
.allocation input[type=range] { appearance: none; background: transparent; }
.allocation input[type=range]::-webkit-slider-runnable-track { height: 5px; border-radius: 5px; background: linear-gradient(to right, #85bd00 var(--allocation), #e5e8ec var(--allocation)); }
.allocation input[type=range]::-webkit-slider-thumb { appearance: none; width: 20px; height: 20px; margin-top: -7.5px; border: 3px solid #85bd00; border-radius: 50%; background: #fff; }
.allocation input[type=range]::-moz-range-track { height: 5px; border-radius: 5px; background: #e5e8ec; }
.allocation input[type=range]::-moz-range-progress { height: 5px; background: #85bd00; }
.allocation input[type=range]::-moz-range-thumb { width: 15px; height: 15px; border: 3px solid #85bd00; border-radius: 50%; background: #fff; }
.symbol-picker-controls { position: sticky; top: 0; z-index: 1; background: #fff; padding-bottom: 4px; }
.symbol-categories { display: flex; gap: 8px; overflow-x: auto; overscroll-behavior-x: contain; scrollbar-width: none; padding: 12px 0 8px; }
.symbol-categories::-webkit-scrollbar { display: none; }
.symbol-categories button { flex: 0 0 auto; min-height: 44px; padding: 0 15px; border-radius: 9px; background: #fff; color: #737b6b; font-size: 14px; white-space: nowrap; }
.symbol-categories button[aria-pressed=true] { background: #edf5df; color: #64832b; font-weight: 500; }
.symbol-categories button:focus-visible { outline-offset: -2px; }
.symbol-empty { padding: 28px 0; text-align: center; color: #777; font-size: 14px; }
</style>
