<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AdvancedLayout from '@/advanced/components/AdvancedLayout.vue'
import MessageHeaderActions from '@/components/MessageHeaderActions.vue'
import TrialAccountCard from '@/components/TrialAccountCard.vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'
import { useMarketStore } from '@/store/market'
import { useTrialWallet } from '@/utils/useTrialWallet'
import { assetDisplayLine, assetHistoryPoints, type AssetHistory, type AssetPoint } from '@/utils/assetPixelWindow'
import { calculateContractProfit, decimalSum } from '@/utils/contract'
import { displaySymbol } from '@/utils/displaySymbol'
import { getSystemTimezone } from '@/utils/dateTime'
import marketWebSocket from '@/utils/marketWebSocket'
import depositIcon from '@/advanced/assets/home-explore/deposit.svg'
import withdrawIcon from '@/advanced/assets/home-explore/withdraw.svg'
import transferIcon from '@/advanced/assets/home-explore/transfer.svg'
import walletIcon from '@/advanced/assets/home-explore/wallet.svg'
import searchIcon from '@/advanced/assets/home-explore/search.svg'

const auth = useAuthStore(), locale = useLocaleStore(), market = useMarketStore(), trialWallet = useTrialWallet()
const brandLogo = `${import.meta.env.BASE_URL}img/logo.svg`
const text = (zh: string, en: string) => locale.text(zh, en)
const history = ref<AssetHistory | null>(null), points = ref<AssetPoint[]>([]), expanded = ref(false), range = ref('1W'), selected = ref(100)
const assets = ref<Record<string, unknown> | null>(null), positions = ref<any[]>([])
const historyError = ref(''), assetsError = ref(''), positionsError = ref('')
const historyLoading = ref(false), assetsLoading = ref(false), positionsLoading = ref(false), now = ref(Date.now())
const controller = new AbortController(), owner = auth.token
let disposed = false, historyGeneration = 0, lastRefresh = 0, refreshTimer: ReturnType<typeof setInterval> | undefined
const active = () => !disposed && auth.token === owner
const money = (value: unknown, digits = 2) => value === null || value === undefined || !Number.isFinite(Number(value)) ? '—' : Number(value).toLocaleString(locale.locale, { minimumFractionDigits: digits, maximumFractionDigits: digits })
const signed = (value: unknown) => value === null || value === undefined || !Number.isFinite(Number(value)) ? '—' : `${Number(value) > 0 ? '+' : ''}${money(value)}`
const dates = (value: number, time = false) => new Intl.DateTimeFormat(locale.locale, { timeZone: history.value?.timezone || getSystemTimezone(), month: '2-digit', day: '2-digit', ...(time ? { hour: '2-digit', minute: '2-digit' } : {}) }).format(value)
const periods = computed(() => [{ key: '1D', label: text('1日', '1D') }, { key: '1W', label: text('7日', '7D') }, { key: '1M', label: text('1月', '1M') }, { key: '1Y', label: text('1年', '1Y') }])
const shortcuts = computed(() => [
  { path: '/deposit', label: locale.t('deposit'), icon: depositIcon }, { path: '/withdraw', label: locale.t('withdraw'), icon: withdrawIcon },
  { path: '/transfer', label: locale.t('transfer'), icon: transferIcon }, { path: '/wallet', label: locale.t('wallet'), icon: walletIcon },
])
const accounts = computed(() => ['fund', 'contract', 'option'].map((key, index) => {
  const available = assets.value?.[`${key}Balance`], frozen = assets.value?.[`${key}Frozen`]
  const valid = available != null && frozen != null && Number.isFinite(Number(available)) && Number.isFinite(Number(frozen))
  return { key, label: [text('资金账户', 'Funding account'), text('合约账户', 'Contract account'), text('期权账户', 'Option account')][index], available, frozen, total: valid ? decimalSum(available, frozen) : null }
}))
const accountTotal = computed(() => accounts.value.every(a => a.total !== null) ? decimalSum(...accounts.value.map(a => a.total)) : null)
const line = computed(() => history.value ? assetDisplayLine(points.value, history.value.from, history.value.asOf) : [])
const chartPath = computed(() => {
  const h = history.value, data = line.value
  if (!h || !data.length) return ''
  const values = data.map(p => p.value!).filter(Number.isFinite), low = Math.min(...values), high = Math.max(...values)
  return data.map((point, i) => `${i ? 'L' : 'M'}${((point.time - h.from) / (h.asOf - h.from) * 330 + 2).toFixed(2)},${(142 - (point.value! - low) / Math.max(high - low, 1) * 126).toFixed(2)}`).join(' ')
})
const selectedPoint = computed(() => {
  const data = points.value.filter(p => p.value !== null)
  return data[Math.min(data.length - 1, Math.round(selected.value / 100 * (data.length - 1)))]
})
const stale = computed(() => !!history.value && (history.value.total === null || now.value - history.value.asOf > 90000))
const firstPosition = computed(() => positions.value[0])
const mark = (position: any) => market.getQuoteStatus(position.symbol, now.value) === 'available' ? market.getPrice(position.symbol) : null
const positionProfit = computed(() => {
  const position = firstPosition.value, price = position && mark(position)
  return position && price ? calculateContractProfit(position, price, market.getConversionRate(position.symbol, position.quoteCurrency)) : null
})
async function loadHistory() {
  if (!auth.token) return
  const generation = ++historyGeneration
  historyLoading.value = true
  try {
    const data = await request.get('/user/asset-history', { params: { range: range.value }, signal: controller.signal }) as unknown as AssetHistory
    if (!active() || generation !== historyGeneration) return
    const parsed = assetHistoryPoints(data)
    history.value = data; points.value = parsed; selected.value = 100; historyError.value = ''
  } catch (error: any) { if (active() && generation === historyGeneration) historyError.value = error.message || text('资金曲线加载失败', 'Asset history unavailable') }
  finally { if (active() && generation === historyGeneration) historyLoading.value = false }
}
async function loadAssets() {
  if (!auth.token) return
  assetsLoading.value = true
  const started = performance.now()
  try {
    const data: any = await request.get('/user/assets', { signal: controller.signal })
    if (!active()) return
    if (data?.success === false || !data) throw new Error(data?.message || 'Invalid assets response')
    assets.value = data; assetsError.value = ''
    if (data.serverNow != null) trialWallet.accept(data, started)
  } catch (error: any) { if (active()) assetsError.value = error.message || text('资产加载失败', 'Assets unavailable') }
  finally { if (active()) assetsLoading.value = false }
}
async function loadPositions() {
  if (!auth.token) return
  positionsLoading.value = true
  try {
    const data: any = await request.get('/trade/contract/orders', { params: { status: 'OPEN' }, signal: controller.signal })
    if (!active()) return
    if (!Array.isArray(data?.list)) throw new Error('Invalid order response')
    positions.value = data.list; positionsError.value = ''
    await market.subscribeSymbols(data.list.map((p: any) => ({ symbol: p.symbol, category: p.category || p.sourceCategory || 'Crypto' })), 'advanced-home')
    if (!active()) marketWebSocket.release('advanced-home')
  } catch (error: any) { if (active()) positionsError.value = error.message || text('持仓加载失败', 'Positions unavailable') }
  finally { if (active()) positionsLoading.value = false }
}
function selectRange(value: string) { range.value = value; void loadHistory() }
function refresh() { lastRefresh = Date.now(); void loadHistory(); void loadAssets(); void loadPositions() }
function visible() { if (document.visibilityState === 'visible') refresh() }
onMounted(() => {
  refresh()
  refreshTimer = setInterval(() => { now.value = Date.now(); if (document.visibilityState === 'visible' && now.value - lastRefresh >= 30000) refresh() }, 1000)
  document.addEventListener('visibilitychange', visible)
})
onBeforeUnmount(() => { disposed = true; historyGeneration++; controller.abort(); clearInterval(refreshTimer); marketWebSocket.release('advanced-home'); document.removeEventListener('visibilitychange', visible) })
</script>

<template>
  <AdvancedLayout nav>
    <header class="brand-header" data-design-node="34:432">
      <img :src="brandLogo" alt="FOREX EXCHANGE" class="brand-logo" />
      <div class="brand-actions"><router-link to="/search" class="header-search" :aria-label="text('搜索行情', 'Search markets')"><img :src="searchIcon" alt="" /></router-link><MessageHeaderActions /></div>
    </header>
    <div class="home-content" :data-design-node="expanded ? '37:2151' : '34:428'">
      <div v-if="!auth.token" class="notice"><router-link to="/login">{{ locale.t('pleaseLoginFirst') }}</router-link></div>
      <section class="equity" :class="{ expanded }">
        <div class="equity-value"><p class="muted">{{ text('我的净资产 / USD', 'My net equity / USD') }}</p><strong>{{ money(history?.total) }}</strong><p class="income" :class="{ negative: Number(history?.income) < 0 }">{{ text('期间收益', 'Period income') }} {{ signed(history?.income) }} <span v-if="history?.incomePercent != null">({{ signed(history.incomePercent) }}%)</span></p></div>
        <button v-if="!expanded" class="mini-chart" type="button" :aria-expanded="expanded" @click="expanded = true">
          <span>{{ text('资金曲线', 'Equity curve') }} ⤢</span>
          <svg v-if="chartPath" viewBox="0 0 334 156" role="img" :aria-label="text('真实净资产曲线', 'Actual net equity history')"><path :d="chartPath" fill="none" stroke="currentColor" stroke-width="2" /></svg><p v-else class="chart-empty">{{ historyLoading ? text('加载中', 'Loading') : '—' }}</p>
          <div v-if="history" class="axis"><span>{{ dates(history.from) }}</span><span>{{ dates(history.asOf) }}</span></div>
        </button>
      </section>
      <p v-if="historyError" class="error" role="alert">{{ historyError }} <button type="button" @click="loadHistory">{{ text('重试', 'Retry') }}</button></p>
      <p v-if="stale" class="notice">{{ text('净资产估值不可用或快照已过期，请刷新', 'Equity valuation unavailable or snapshot stale. Refresh to update.') }}</p>
      <nav class="shortcuts" :aria-label="text('资金快捷操作', 'Funding actions')"><router-link v-for="action in shortcuts" :key="action.path" :to="action.path"><span class="shortcut-icon"><img :src="action.icon" alt="" /></span><span>{{ action.label }}</span></router-link></nav>
      <section v-if="expanded" class="panel curve-panel">
        <h2>{{ text('资金曲线', 'Equity curve') }}</h2><div class="tabs"><button v-for="period in periods" :key="period.key" :class="{ active: range === period.key }" :aria-pressed="range === period.key" type="button" @click="selectRange(period.key)">{{ period.label }}</button></div>
        <p class="muted">{{ text('快照时间 / 净资产', 'Snapshot time / Net equity') }}</p>
        <svg v-if="chartPath" class="large-chart" viewBox="0 0 334 156" role="img" :aria-label="text('真实净资产曲线', 'Actual net equity history')"><path :d="chartPath" fill="none" stroke="currentColor" stroke-width="1.5" /></svg><p v-else class="chart-empty">{{ historyLoading ? text('加载中', 'Loading') : text('暂无快照数据', 'No snapshot data') }}</p>
        <div v-if="history" class="axis"><span>{{ dates(history.from) }}</span><span>{{ dates(history.asOf) }}</span></div>
        <label v-if="selectedPoint" class="scrubber">{{ text('选择快照', 'Select snapshot') }}<input v-model.number="selected" type="range" min="0" max="100" :aria-label="text('选择快照', 'Select snapshot')" /></label>
        <dl><div><dt>{{ text('选中快照', 'Selected snapshot') }}</dt><dd>{{ money(selectedPoint?.value) }} USD</dd></div><div><dt>{{ text('时间', 'Time') }}</dt><dd>{{ selectedPoint ? dates(selectedPoint.time, true) : '—' }}</dd></div></dl>
        <button class="secondary" type="button" @click="expanded = false">━ {{ text('收起', 'Collapse') }}</button>
      </section>
      <section><div class="section-heading"><h2>{{ text('资产组合', 'Account portfolio') }}</h2><span class="accent muted">{{ text('可用 + 冻结', 'Available + Frozen') }}</span></div>
        <p v-if="assetsError" class="error" role="alert">{{ assetsError }} <button type="button" @click="loadAssets">{{ text('重试', 'Retry') }}</button></p>
        <div class="account-strip" :aria-busy="assetsLoading"><router-link v-for="account in accounts" :key="account.key" to="/assets" class="account-card" :aria-label="`${account.label} · ${text('可用', 'Available')} ${money(account.available)} · ${text('冻结', 'Frozen')} ${money(account.frozen)}`"><p class="muted">{{ account.label }}</p><strong>{{ money(account.total) }}</strong><p class="muted">{{ account.total !== null && accountTotal ? money(account.total / accountTotal * 100) + '%' : '—' }} · USD</p></router-link></div>
      </section>
      <TrialAccountCard />
      <section><div class="section-heading"><h2>{{ text('持仓摘要', 'Position summary') }}</h2><router-link to="/orders">{{ text('全部', 'All') }} ›</router-link></div>
        <p v-if="positionsError" class="error" role="alert">{{ positionsError }} <button type="button" @click="loadPositions">{{ text('重试', 'Retry') }}</button></p>
        <router-link v-else-if="firstPosition" to="/orders" class="panel position-card"><div class="position-top"><div><strong>{{ displaySymbol(firstPosition) }}</strong><p class="muted">{{ firstPosition.side === 'BUY' ? text('多', 'Long') : text('空', 'Short') }} · {{ firstPosition.leverage }}×</p></div><strong :class="Number(positionProfit) < 0 ? 'negative' : 'positive'">{{ signed(positionProfit) }} USD</strong></div><div class="metrics"><div><span>{{ text('开仓均价', 'Entry price') }}</span><b>{{ money(firstPosition.openPrice, firstPosition.pricePrecision ?? 2) }}</b></div><div><span>{{ text('当前价格', 'Mark price') }}</span><b>{{ money(mark(firstPosition), firstPosition.pricePrecision ?? 2) }}</b></div><div><span>{{ text('保证金 USD', 'Margin USD') }}</span><b>{{ money(firstPosition.margin) }}</b></div></div><p v-if="mark(firstPosition) === null" class="muted">{{ text('报价不可用 / 已过期；未显示估算收益', 'Quote unavailable / stale; estimated profit unavailable') }}</p></router-link>
        <p v-else class="empty">{{ positionsLoading ? text('加载中', 'Loading') : text('暂无持仓', 'No open positions') }}</p>
      </section>
      <p v-if="history" class="muted as-of">{{ text('真实净资产快照', 'Actual net equity snapshot') }} · {{ dates(history.asOf, true) }}</p>
    </div>
  </AdvancedLayout>
</template>

<style scoped>
.brand-actions{display:flex;align-items:center;gap:8px}.header-search{display:grid;place-items:center;width:40px;height:40px}.header-search img{width:20px;height:20px}.brand-header :deep(.message-header-actions button){background:transparent;border:0;border-radius:50%;width:40px;height:40px;color:#707780;padding:9px}.brand-header :deep(.message-header-actions [data-action="support"]),.brand-header :deep(.message-header-actions [data-action="language"]){display:none}
.brand-header{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:16px 0 8px}.brand-logo{width:106px;height:auto}.home-content{display:flex;flex-direction:column;gap:16px;padding:16px 0}.home-content p,.home-content h2{margin:0}.home-content h2{font-size:17px;font-weight:500}.muted{color:#707780;font-size:12px;line-height:1.45}.accent,.mini-chart,.section-heading a{color:#736582}.positive{color:#56723e}.negative{color:#aa5363!important}.equity{display:grid;grid-template-columns:minmax(0,1fr) 120px;gap:12px}.equity-value{display:flex;flex-direction:column;gap:8px;min-width:0}.equity-value>strong{font-size:30px;line-height:1.45;font-weight:500;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.income{font-size:12px;color:#56723e;overflow-wrap:anywhere}.mini-chart{background:transparent;border:0;text-align:start;padding:0;display:flex;flex-direction:column;gap:8px;min-height:112px;font:inherit;font-size:12px;cursor:pointer}.mini-chart svg{width:120px;height:72px}.axis{display:flex;justify-content:space-between;gap:8px;font-size:12px;color:#707780;width:100%;font-variant-numeric:tabular-nums}.shortcuts{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}.shortcuts a{display:flex;flex-direction:column;align-items:center;gap:8px;color:#252a30;font-size:12px;text-align:center;overflow-wrap:anywhere}.shortcut-icon{display:flex;justify-content:center;align-items:center;border-radius:50%;width:44px;height:44px;background:#f5f6f7}.shortcut-icon img{display:block}.section-heading{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:12px}.section-heading a{font-size:12px}.account-strip{display:flex;gap:8px;overflow-x:auto;scroll-snap-type:x proximity;padding-bottom:4px}.account-card{flex:0 0 142px;background:#f5f6f7;border-radius:10px;padding:12px;color:#252a30;display:flex;flex-direction:column;gap:8px;scroll-snap-align:start;min-width:0}.account-card>strong{font-size:17px;font-weight:500;overflow-wrap:anywhere}.account-detail{font-size:10px}.panel{border:1px solid #e9edef;border-radius:10px;padding:12px;display:flex;flex-direction:column;gap:12px;min-width:0}.position-card{color:#252a30;gap:8px}.position-top{display:flex;justify-content:space-between;gap:8px;font-size:14px}.position-top>div{min-width:0;overflow-wrap:anywhere}.position-top>strong{overflow-wrap:anywhere;text-align:end}.position-top p{margin-top:4px}.metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.metrics span{font-size:12px;color:#707780;display:block}.metrics b{font-size:14px;font-weight:500;display:block;margin-top:5px;overflow-wrap:anywhere}.empty{padding:20px 0;color:#707780;font-size:12px;text-align:center}.error{font-size:12px;color:#aa5363}.error button{font:inherit;color:inherit;background:none;border:0;text-decoration:underline;padding:8px}.notice{background:#f5f2f7;padding:10px;border-radius:7px;color:#707780;font-size:12px}.expanded{display:block}.expanded .equity-value{gap:16px}.curve-panel .large-chart{width:100%;height:auto;color:#56723e}.chart-empty{min-height:72px;display:grid;place-items:center;color:#707780}.tabs{display:flex;gap:6px}.tabs button{flex:1;min-width:0;border:0;border-radius:7px;background:#f5f6f7;color:#707780;font:inherit;font-size:12px;padding:10px 4px;min-height:40px}.tabs button.active{background:#f5f2f7;color:#736582}.scrubber{display:flex;flex-direction:column;gap:8px;font-size:12px;color:#707780}.scrubber input{width:100%;accent-color:#736582}.curve-panel dl{margin:0;font-size:14px}.curve-panel dl>div{display:flex;justify-content:space-between;gap:12px;padding:12px 0}.curve-panel dd{margin:0;text-align:end;overflow-wrap:anywhere}.secondary{border:0;background:#f5f6f7;color:#252a30;border-radius:10px;min-height:48px;font:inherit;cursor:pointer}.as-of{padding-bottom:4px}@media(max-width:350px){.equity{grid-template-columns:minmax(0,1fr) 96px}.mini-chart svg{width:96px}.equity-value>strong{font-size:26px}.position-top{flex-wrap:wrap}}button:focus-visible,a:focus-visible,input:focus-visible{outline:2px solid #7557b7;outline-offset:3px}
</style>
