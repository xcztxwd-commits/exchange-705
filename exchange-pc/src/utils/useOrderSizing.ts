import { computed, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useMarketStore } from '@/store/market'
import request from '@/utils/request'
import marketWebSocket from '@/utils/marketWebSocket'
import { contractMargin, estimateLiquidationPrice, quantityFromAllocation } from './contract'

export function useOrderSizing(input: {
  catalog: Ref<any[]>; quantity: Ref<number>; leverage: Ref<number>; available: Ref<number>; active: Ref<boolean>;
  symbol: Ref<string>; price: Ref<number>; lotSize: Ref<number>; feePerLot: Ref<number>; currency: Ref<string>;
}) {
  const auth = useAuthStore()
  const market = useMarketStore()
  const positions = ref<any[]>([])
  const loadedAt = ref(0)
  const now = ref(Date.now())
  const requestedPercent = ref<number | null>(null)
  let writingQuantity = false
  let lastAttempt = 0
  let requestVersion = 0
  let pending: Promise<void> | null = null
  let timer: ReturnType<typeof setInterval> | undefined
  const accountReady = computed(() => !!auth.token && loadedAt.value > 0 && now.value - loadedAt.value < 30000)
  const conversionRate = computed(() => {
    void now.value
    return market.getConversionRate(input.symbol.value, input.currency.value)
  })
  const quoteReady = computed(() => market.getQuoteStatus(input.symbol.value, now.value) === 'available')
  const marginPerLot = computed(() => contractMargin(1, input.lotSize.value, input.price.value, input.leverage.value, conversionRate.value))
  const costPerLot = computed(() => marginPerLot.value + input.feePerLot.value)
  const canAllocate = computed(() => accountReady.value && quoteReady.value && input.available.value > 0
    && Number.isFinite(costPerLot.value) && marginPerLot.value > 0 && input.feePerLot.value >= 0)
  const cost = computed(() => input.quantity.value * marginPerLot.value + input.quantity.value * input.feePerLot.value)
  const orderReady = computed(() => canAllocate.value && Number.isFinite(input.quantity.value)
    && input.quantity.value >= 0.01 && Number.isFinite(cost.value) && cost.value <= input.available.value)
  const actualPercent = computed(() => input.available.value > 0 && Number.isFinite(cost.value)
    ? Math.max(0, Math.min(100, cost.value / input.available.value * 100)) : 0)
  const allocationPercent = computed(() => requestedPercent.value ?? Math.round(actualPercent.value * 10) / 10)

  function applyAllocation() {
    if (requestedPercent.value == null || !canAllocate.value) return
    writingQuantity = true
    input.quantity.value = quantityFromAllocation(input.available.value, requestedPercent.value, marginPerLot.value, input.feePerLot.value)
    writingQuantity = false
  }
  function setAllocation(value: number) {
    if (!Number.isFinite(value) || !canAllocate.value) return
    requestedPercent.value = Math.max(0, Math.min(100, value))
    applyAllocation()
  }
  watch(input.quantity, () => { if (!writingQuantity) requestedPercent.value = null }, { flush: 'sync' })
  watch([costPerLot, input.available, canAllocate], applyAllocation)
  watch(input.symbol, () => { requestedPercent.value = null }, { flush: 'sync' })

  async function refreshAccount() {
    if (!auth.token || !input.active.value) return
    if (pending) return pending
    lastAttempt = Date.now()
    const token = auth.token
    const version = ++requestVersion
    pending = (async () => {
      try {
        const [account, orders]: any[] = await Promise.all([
          request.get('/trade/contract/balance'),
          request.get('/trade/contract/orders', { params: { status: 'OPEN' } }),
        ])
        if (version !== requestVersion || auth.token !== token) return
        const available = Number(account?.available ?? account?.balance)
        if (account?.success === false || !Number.isFinite(available) || available < 0 || !Array.isArray(orders?.list) || orders?.success === false) throw new Error('Invalid account snapshot')
        positions.value = orders.list.filter((order: any) => order.status === 'OPEN')
        input.available.value = available
        loadedAt.value = Date.now()
        now.value = Date.now()

      } catch {
        if (version === requestVersion) loadedAt.value = 0
      } finally {
        if (version === requestVersion) pending = null
      }
    })()
    return pending
  }

  watch([positions, input.catalog], () => {
    const names = new Set(positions.value.map(order => order.symbol))
    void market.subscribeSymbols(input.catalog.value.filter(item => names.has(item.symbol)), 'order-sizing')
  })

  const liquidation = computed(() => {
    const unavailable = { buy: null, sell: null }
    if (!orderReady.value) return unavailable
    const livePositions = positions.value.map(order => ({
      ...order, quantity: Number(order.quantity), openPrice: Number(order.openPrice),
      currentPrice: market.getQuoteStatus(order.symbol, now.value) === 'available' ? Number(market.priceMap[order.symbol]?.price) : NaN,
      margin: Number(order.margin), fee: Number(order.fee || 0),
      lotSize: order.lotSize == null ? null : Number(order.lotSize), leverage: Number(order.leverage ?? 1),
      conversionRate: market.getConversionRate(order.symbol, order.quoteCurrency),
    }))
    const order = { symbol: input.symbol.value, quantity: Number(input.quantity.value), price: input.price.value,
      lotSize: input.lotSize.value, fee: input.quantity.value * input.feePerLot.value, conversionRate: conversionRate.value }
    return {
      buy: estimateLiquidationPrice(input.available.value, livePositions, { ...order, side: 'BUY' }),
      sell: estimateLiquidationPrice(input.available.value, livePositions, { ...order, side: 'SELL' }),
    }
  })

  watch([() => auth.token, input.active], () => {
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
      if (input.active.value && document.visibilityState === 'visible' && (now.value - lastAttempt >= 15000)) void refreshAccount()
    }, 1000)
    window.addEventListener('focus', onFocus)
  })
  onUnmounted(() => {
    requestVersion++
    if (timer) clearInterval(timer)
    window.removeEventListener('focus', onFocus)
    marketWebSocket.release('order-sizing')
  })
  return { allocationPercent, setAllocation, canAllocate, orderReady, liquidation, refreshAccount }
}
