import { ref, computed, onMounted, onUnmounted } from 'vue'
import request from '@/utils/request'

export const currencies = ['USD', 'EUR', 'JPY', 'GBP', 'AUD', 'CAD', 'SGD', 'CNY']
const names = ['美元', '欧元', '日元', '英镑', '澳元', '加元', '新加坡元', '人民币']
export const currencyOptions = currencies.map((code, i) => ({ code, label: `${code} · ${names[i]}` }))

export function useFiatCurrency() {
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
    new Intl.NumberFormat('en-US', { style: 'currency', currency: currency.value, currencyDisplay: 'code', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(amount || 0) / rate.value)
  const usdPreview = (amount: number | string | null) => rate.value === null ? '汇率暂不可用，请稍后重试' :
    `≈ ${(Number(amount || 0) * rate.value).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} USD（以提交时汇率为准）`
  let timer: ReturnType<typeof setInterval>
  onMounted(() => {
    void refreshRates()
    timer = setInterval(() => { now.value = Date.now(); if (now.value >= nextRefresh) void refreshRates() }, 1000)
  })
  onUnmounted(() => clearInterval(timer))
  return { currency, rate, refreshRates, formatAsset, usdPreview }
}
