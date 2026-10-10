import { computed, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useTrialWallet } from './useTrialWallet'
import { accountMode } from './accountMode'
import { selectedAvailable, fundingPositions, type FundingSource } from './trialLifecycle'
import { useMarketStore } from '@/store/market'
import request from '@/utils/request'
import marketWebSocket from '@/utils/marketWebSocket'
import { contractMargin, estimateLiquidationPrice, quantityFromAllocation, validQuantity, decimalProduct, decimalSum } from './contract'

export function useOrderSizing(input: {
  fundingSource?: Ref<FundingSource>; catalog: Ref<any[]>; quantity: Ref<number>; leverage: Ref<number>; available: Ref<number>; active: Ref<boolean>;
  symbol: Ref<string>; price: Ref<number>; lotSize: Ref<number>; feePerLot: Ref<number>; currency: Ref<string>;
}) {
  const auth = useAuthStore()
  const market = useMarketStore(), wallet = useTrialWallet()
  const source = input.fundingSource || ref<FundingSource>('CONTRACT')
  const positions = ref<any[]>([])
  const loadedAt = ref(0)
  const now = ref(Date.now())
  const requestedPercent = ref<number | null>(null)
  let writingQuantity = false
  let lastAttempt = 0, disposed = false
  let requestVersion = 0
  let pending: Promise<void> | null = null
  let timer: ReturnType<typeof setInterval> | undefined
  const accountReady = computed(() => !!auth.token && wallet.ready && loadedAt.value > 0 && performance.now() - loadedAt.value < 30000 && (source.value !== 'TRIAL' || wallet.state.eligible))
  const conversionRate = computed(() => {
    void now.value
    return market.getConversionRate(input.symbol.value, input.currency.value)
  })
  const quoteReady = computed(() => market.getQuoteStatus(input.symbol.value, now.value) === 'available')
  const marginPerLot = computed(() => contractMargin(1, input.lotSize.value, input.price.value, input.leverage.value, conversionRate.value, market.getMarginBaseRate(input.catalog.value.find(item => item.symbol === input.symbol.value), input.price.value)))
  const specification = computed(() => input.catalog.value.find(item => item.symbol === input.symbol.value))
  const notionalPerUnit = computed(() => decimalProduct(input.lotSize.value, input.price.value, conversionRate.value))
  const costPerLot = computed(() => decimalSum(marginPerLot.value, input.feePerLot.value))
  const canAllocate = computed(() => accountReady.value && quoteReady.value && input.available.value > 0
    && Number.isFinite(costPerLot.value) && marginPerLot.value > 0 && input.feePerLot.value >= 0)
  const cost = computed(() => decimalSum(contractMargin(input.quantity.value, input.lotSize.value, input.price.value, input.leverage.value, conversionRate.value, market.getMarginBaseRate(specification.value, input.price.value)), decimalProduct(input.quantity.value, input.feePerLot.value)))
  const orderReady = computed(() => canAllocate.value && Number.isFinite(input.quantity.value)
    && validQuantity(input.quantity.value, specification.value) && decimalProduct(input.quantity.value, notionalPerUnit.value) >= Number(specification.value?.minOrderNotional ?? 0) && Number.isFinite(cost.value) && cost.value <= input.available.value)
  const actualPercent = computed(() => input.available.value > 0 && Number.isFinite(cost.value)
    ? Math.max(0, Math.min(100, cost.value / input.available.value * 100)) : 0)
  const allocationPercent = computed(() => requestedPercent.value ?? Math.round(actualPercent.value * 10) / 10)

  function applyAllocation() {
    if (requestedPercent.value == null || requestedPercent.value !== 0 && !canAllocate.value) return
    writingQuantity = true
    input.quantity.value = requestedPercent.value === 0 ? 0 : quantityFromAllocation(input.available.value, requestedPercent.value, marginPerLot.value, input.feePerLot.value, Number(specification.value?.quantityStep ?? 0.01), Number(specification.value?.minOrderQuantity ?? 0.01), notionalPerUnit.value, Number(specification.value?.minOrderNotional ?? 0))
    writingQuantity = false
  }
  function setAllocation(value: number) {
    if (!Number.isFinite(value) || !canAllocate.value) return
    requestedPercent.value = Math.max(0, Math.min(100, value))
    applyAllocation()
  }
  watch(input.quantity, () => { if (!writingQuantity) requestedPercent.value = input.quantity.value === 0 ? 0 : null }, { flush: 'sync' })
  watch([costPerLot, input.available, canAllocate], applyAllocation)
  watch(input.symbol, () => { requestedPercent.value = null }, { flush: 'sync' })

  let accountAbort: AbortController | null = null
  async function refreshAccount() {
    if (disposed || !auth.token || !input.active.value || document.visibilityState !== 'visible') return
    if (pending) return pending
    lastAttempt = performance.now()
    const token = auth.token, funding = source.value, mode = accountMode(), version = ++requestVersion
    const controller = new AbortController(); accountAbort = controller
    pending = (async () => {
      try {
        const [, orders]: any[] = await Promise.all([
          wallet.refresh(),
          request.get('/trade/contract/orders', { params: { status: 'OPEN' }, signal: controller.signal }),
        ])
        if (version !== requestVersion || auth.token !== token || source.value !== funding || accountMode() !== mode) return
        const available = selectedAvailable(wallet.snapshot, funding, wallet.state)
        if (!wallet.ready || !Number.isFinite(available) || !Array.isArray(orders?.list) || orders?.success === false) throw new Error('Invalid account snapshot')
        positions.value = fundingPositions(orders.list, funding)
        input.available.value = available
        loadedAt.value = performance.now()
        now.value = Date.now()
      } catch {
        if (version === requestVersion) { loadedAt.value = 0; input.available.value = NaN }
      } finally { if (version === requestVersion) { pending = null; accountAbort = null } }
    })()
    return pending
  }
  watch([() => wallet.snapshot, () => wallet.state.available, () => wallet.state.eligible], () => {
    input.available.value = selectedAvailable(wallet.snapshot, source.value, wallet.state)
  }, { flush: 'sync' })

  watch([positions, input.catalog], () => {
    const names = new Set(positions.value.map(order => order.symbol))
    void market.subscribeSymbols(input.catalog.value.filter(item => names.has(item.symbol)), 'order-sizing')
  })

  const liquidation = computed(() => {
    const unavailable = { buy: null, sell: null }
    if (!orderReady.value || positions.value.some(order => order.fundingSource == null && Number(order.trialReserved) > 0)) return unavailable
    const livePositions = positions.value.map(order => ({
      ...order, quantity: Number(order.quantity), openPrice: Number(order.openPrice),
      currentPrice: market.getQuoteStatus(order.symbol, now.value) === 'available' ? Number(market.priceMap[order.symbol]?.price) : NaN,
      margin: Number(order.margin), fee: Number(order.fee || 0),
      lotSize: order.lotSize == null ? null : Number(order.lotSize), leverage: Number(order.leverage ?? 1),
      conversionRate: market.getConversionRate(order.symbol, order.quoteCurrency),
    }))
    const order = { symbol: input.symbol.value, quantity: Number(input.quantity.value), price: input.price.value,
      lotSize: input.lotSize.value, fee: decimalProduct(input.quantity.value, input.feePerLot.value), conversionRate: conversionRate.value }
    return {
      buy: estimateLiquidationPrice(input.available.value, livePositions, { ...order, side: 'BUY' }),
      sell: estimateLiquidationPrice(input.available.value, livePositions, { ...order, side: 'SELL' }),
    }
  })

  watch([() => auth.token, () => auth.user?.id, () => auth.user?.tenantId, input.active, source], () => {
    accountAbort?.abort()
    requestedPercent.value = null
    input.available.value = NaN
    requestVersion++
    pending = null
    loadedAt.value = 0
    positions.value = []
    if (!auth.token) input.available.value = 0
    if (!auth.token || !input.active.value) marketWebSocket.release('order-sizing')
    void refreshAccount()
  }, { immediate: true })
  function onFocus() { void refreshAccount() }
  onMounted(() => {
    timer = setInterval(() => {
      now.value = Date.now()
      if (input.active.value && document.visibilityState === 'visible' && (performance.now() - lastAttempt >= 15000)) void refreshAccount()
    }, 1000)
    window.addEventListener('focus', onFocus)
    document.addEventListener('visibilitychange', onFocus)
  })
  onUnmounted(() => {
    disposed = true
    accountAbort?.abort()
    requestVersion++
    if (timer) clearInterval(timer)
    window.removeEventListener('focus', onFocus)
    document.removeEventListener('visibilitychange', onFocus)
    marketWebSocket.release('order-sizing')
  })
  return { allocationPercent, setAllocation, canAllocate, orderReady, liquidation, refreshAccount }
}
