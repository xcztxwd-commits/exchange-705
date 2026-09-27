import { ref, computed, onMounted, onUnmounted } from 'vue'
import request from '@/utils/request'
import { useLocaleStore } from '@/store/locale'

export function useFiatCurrency() {
  const locale = useLocaleStore()
  const currency = ref('USD')
  const rates = ref<Record<string, { quoteToUsdRate: number; conversionAvailable: boolean; conversionExpiresAt: number }>>({})
  const now = ref(Date.now())
  const rate = computed(() => {
    if (currency.value === 'USD') return 1
    const quote = rates.value[currency.value]
    const value = Number(quote?.quoteToUsdRate)
    return quote?.conversionAvailable && quote.conversionExpiresAt > now.value && Number.isFinite(value) && value > 0 ? value : null
  })
  let nextRefresh = 0
  async function refreshRates() {
    nextRefresh = Date.now() + 30000
    try {
      const response: any = await request.get('/market/currencies')
      rates.value = response.rates || {}
    } catch {
      rates.value = {}
    }
    now.value = Date.now()
  }
  const formatAsset = (amount: number | string | undefined | null) => rate.value === null ? '—' :
    new Intl.NumberFormat(locale.locale, { style: 'currency', currency: currency.value, currencyDisplay: 'code', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(amount || 0) / rate.value)
  const usdPreview = (amount: number | string | null) => rate.value === null ? locale.text('匯率暫不可用，請稍後重試', 'Exchange rate unavailable; please retry later') :
    locale.text('≈ {amount} USD（以提交時匯率為準）', '≈ {amount} USD (rate at submission applies)', { amount: (Number(amount || 0) * rate.value).toLocaleString(locale.locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) })
  let timer: ReturnType<typeof setInterval>
  onMounted(() => {
    void refreshRates()
    timer = setInterval(() => { now.value = Date.now(); if (now.value >= nextRefresh) void refreshRates() }, 1000)
  })
  onUnmounted(() => clearInterval(timer))
  return { currency, rate, refreshRates, formatAsset, usdPreview }
}
