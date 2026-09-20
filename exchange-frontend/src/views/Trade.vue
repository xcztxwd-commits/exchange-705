<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import KlineChart from '@/components/KlineChart.vue'
import SuccessModal from '@/components/SuccessModal.vue'
import { useMarketStore } from '@/store/market'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'
import request from '@/utils/request'
import { DEFAULT_LEVERAGE, leverageLimit, leverageChoices, contractMargin } from '@/utils/contract'
// 市场休市时间判断已移除，改用阿里云市场API返回的数据来判断市场状态
import { formatDateTime, formatTime } from '@/utils/dateTime'

const route = useRoute()
const marketStore = useMarketStore()
const auth = useAuthStore()
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 顶部标签页（合约/期限）
// 从路由参数获取标签页，如果没有则默认为期限（term）
const activeTab = ref<'contract' | 'term'>((route.query.tab as 'contract' | 'term') || 'term')

// 当前选中的交易对（从路由参数获取，如果没有则使用默认值）
const currentSymbol = ref((route.query.symbol as string) || 'BTCUSD')
const currentCategory = ref((route.query.category as string) || 'Crypto')
// 标记是否已经完成了首次初始化
const isInitialized = ref(false)
const currentInterval = ref('5m')

// 订单类型（市价/挂单）
const orderType = ref<'market' | 'limit'>('market')

// 止损/止盈
const stopLossEnabled = ref(false)
const takeProfitEnabled = ref(false)
const stopLossValue = ref(0)
const takeProfitValue = ref(0)

// 买入数量
const buyQuantity = ref(0.01)
const selectedLeverage = ref(DEFAULT_LEVERAGE)

// 挂单价格
const limitPrice = ref(0)

// 期限交易相关
const termDirection = ref<'UP' | 'DOWN'>('UP') // 方向：買漲/買跌
const termDuration = ref(30) // 到期时间（秒）
const termAmount = ref(0) // 交易数量（金额）
const optionBalance = ref(0) // 期权资产余额
const showTermOrderModal = ref(false) // 显示期限订单弹窗

// 订单详情弹窗（购买成功后显示）
const showTermOrderDetailModal = ref(false) // 显示订单详情弹窗
const termOrderDetail = ref<any>(null) // 订单详情
const countdown = ref(0) // 倒计时（秒）
const countdownInterval = ref<number | null>(null) // 倒计时定时器
const countdownProgress = ref(0) // 倒计时进度（0-100）

// 计算箭头旋转角度（响应式，确保实时跟随）
// 使用 watch 监听进度变化，更新箭头角度
const arrowRotationAngle = ref(-90) // 箭头初始角度：-90度（顶部12点方向）

watch(() => countdownProgress.value, (newProgress) => {
  // 当进度更新时，同步更新箭头角度
  // SVG 坐标系旋转说明：
  // - 0度 = 右侧（3点钟方向）
  // - 90度 = 底部（6点钟方向）
  // - 180度 = 左侧（9点钟方向）
  // - 270度/-90度 = 顶部（12点钟方向）
  // 
  // 进度条从顶部（-90度）开始，顺时针旋转
  // 箭头需要跟随进度条的末端位置，所以角度计算为：
  // 从-90度（顶部）开始，根据进度顺时针旋转
  // 进度 0%：-90度（顶部12点）
  // 进度 25%：0度（右侧3点）
  // 进度 50%：90度（底部6点）
  // 进度 75%：180度（左侧9点）
  // 进度 100%：270度（回到顶部，即270度或-90度+360度）
  const angle = -90 + (newProgress / 100) * 360
  arrowRotationAngle.value = Number(angle.toFixed(2))
}, { immediate: true })

// 到期时间选项（秒）- 从后端获取
const durationOptions = ref<Array<{ value: number; label: string; profitRate: number; lossRate: number; minAmount?: number; maxAmount?: number }>>([])

// 加载期限选项
async function loadDurationOptions() {
  try {
    const res: any = await request.get('/trade/option/durations')
    if (res && Array.isArray(res)) {
      durationOptions.value = res.map((item: any) => ({
        value: item.duration,
        label: item.label,
        profitRate: Number(item.profitRate || 0.8), // 盈亏比例，默认0.8（80%）
        lossRate: Number(item.lossRate || 1.0), // 亏损比例，默认1.0（100%，全部亏损）
        minAmount: item.minAmount ? Number(item.minAmount) : undefined,
        maxAmount: item.maxAmount ? Number(item.maxAmount) : undefined,
      }))
      // 如果加载成功且有数据，设置默认选中第一个
      if (durationOptions.value.length > 0 && durationOptions.value[0]) {
        termDuration.value = durationOptions.value[0].value
      }
    } else {
      // 如果后端没有数据，使用默认值
      durationOptions.value = [
        { value: 30, label: '30s', profitRate: 0.8, lossRate: 1.0 },
        { value: 60, label: '60s', profitRate: 0.8, lossRate: 1.0 },
        { value: 120, label: '120s', profitRate: 0.8, lossRate: 1.0 },
        { value: 180, label: '180s', profitRate: 0.8, lossRate: 1.0 },
        { value: 300, label: '300s', profitRate: 0.8, lossRate: 1.0 },
        { value: 600, label: '600s', profitRate: 0.8, lossRate: 1.0 },
        { value: 1200, label: '1200s', profitRate: 0.8, lossRate: 1.0 },
        { value: 1800, label: '1800s', profitRate: 0.8, lossRate: 1.0 },
      ]
    }
  } catch (e) {
    console.error(localeStore.t('loadDurationOptionsFailed'), e)
    // 如果加载失败，使用默认值
    durationOptions.value = [
      { value: 30, label: '30s', profitRate: 0.8, lossRate: 1.0 },
      { value: 60, label: '60s', profitRate: 0.8, lossRate: 1.0 },
      { value: 120, label: '120s', profitRate: 0.8, lossRate: 1.0 },
      { value: 180, label: '180s', profitRate: 0.8, lossRate: 1.0 },
      { value: 300, label: '300s', profitRate: 0.8, lossRate: 1.0 },
      { value: 600, label: '600s', profitRate: 0.8, lossRate: 1.0 },
      { value: 1200, label: '1200s', profitRate: 0.8, lossRate: 1.0 },
      { value: 1800, label: '1800s', profitRate: 0.8, lossRate: 1.0 },
    ]
  }
}

// 获取当前选中期限的配置
const currentDurationConfig = computed(() => {
  return durationOptions.value.find(opt => opt.value === termDuration.value) || null
})

// 计算预计盈亏（实时）
const estimatedProfit = computed(() => {
  if (!termOrderDetail.value) {
    console.log('计算预计盈亏：订单详情为空')
    return 0
  }
  
  // 如果订单已平仓，使用已计算的盈亏
  if (termOrderDetail.value.status === 'CLOSED') {
    return Number(termOrderDetail.value.profit || 0)
  }
  
  // 确保有必要的值
  const amount = Number(termOrderDetail.value.amount || 0)
  const openPrice = Number(termOrderDetail.value.openPrice || 0)
  const currentPriceValue = Number(currentPrice.value || 0)
  
  console.log('计算预计盈亏 - 金额:', amount, '开仓价:', openPrice, '当前价:', currentPriceValue)
  
  if (amount <= 0) {
    console.warn('计算预计盈亏：金额无效', amount)
    return 0
  }
  
  if (openPrice <= 0) {
    console.warn('计算预计盈亏：开仓价无效', openPrice)
    return 0
  }
  
  const priceDiff = currentPriceValue - openPrice
  const direction = termOrderDetail.value.direction
  
  // 获取订单对应的期限设置的盈亏比例和亏损比例
  const orderDuration = Number(termOrderDetail.value.duration || 0)
  const durationOption = durationOptions.value.find(opt => opt.value === orderDuration)
  const profitRate = durationOption?.profitRate || 0.8 // 盈利比例，默认80%
  const lossRate = durationOption?.lossRate || 1.0 // 亏损比例，默认100%（全部亏损）
  
  console.log('计算预计盈亏 - 价格差:', priceDiff, '方向:', direction, '盈利比例:', profitRate, '亏损比例:', lossRate)
  
  // 买涨：价格上涨盈利，价格下跌亏损
  // 买跌：价格下跌盈利，价格上涨亏损
  let profit = 0
  if (direction === 'UP') {
    if (priceDiff > 0) {
      // 价格上涨，盈利：使用盈利比例
      profit = amount * profitRate
    } else {
      // 价格下跌，亏损：使用亏损比例
      profit = -(amount * lossRate)
    }
  } else if (direction === 'DOWN') {
    if (priceDiff < 0) {
      // 价格下跌，盈利：使用盈利比例
      profit = amount * profitRate
    } else {
      // 价格上涨，亏损：使用亏损比例
      profit = -(amount * lossRate)
    }
  }
  
  console.log('计算预计盈亏 - 最终结果:', profit)
  return profit
})

// 打开期限订单弹窗
function openTermOrderModal(direction: 'UP' | 'DOWN') {
  termDirection.value = direction
  showTermOrderModal.value = true
}

// 关闭期限订单弹窗
function closeTermOrderModal() {
  showTermOrderModal.value = false
  termAmount.value = 0
}

// 开始倒计时
function startCountdown() {
  if (countdownInterval.value) {
    clearInterval(countdownInterval.value)
  }
  
  if (!termOrderDetail.value) {
    console.warn('开始倒计时失败：订单详情为空')
    return
  }
  
  // 如果订单已经关闭，不启动倒计时
  if (termOrderDetail.value.status === 'CLOSED') {
    console.warn('订单已关闭，不启动倒计时')
    return
  }
  
  const totalDuration = termOrderDetail.value.duration || termDuration.value
  if (!totalDuration || totalDuration <= 0) {
    console.error('倒计时时长无效:', totalDuration)
    return
  }
  
  const startTime = Date.now()
  const endTime = startTime + totalDuration * 1000
  
  console.log('开始倒计时，总时长:', totalDuration, '秒，结束时间:', formatTime(new Date(endTime)))
  
  // 使用 requestAnimationFrame 实现平滑的动画效果，约60fps
  let animationFrameId: number | null = null
  
  function updateCountdown() {
    // 检查订单状态，如果已关闭则停止倒计时
    if (!termOrderDetail.value || termOrderDetail.value.status === 'CLOSED') {
      console.log('订单已关闭，停止倒计时')
      stopCountdown()
      return
    }
    
    const now = Date.now()
    
    // 计算剩余时间（秒）
    const remaining = Math.max(0, Math.floor((endTime - now) / 1000))
    countdown.value = remaining
    
    // 计算进度（0-100），使用更精确的计算，不取整，保持小数
    const elapsed = (now - startTime) / 1000 // 使用实际经过的时间，更精确
    const progress = totalDuration > 0 ? Math.min(100, Math.max(0, (elapsed / totalDuration) * 100)) : 0
    
    // 保留更多小数位，实现平滑的动画效果
    const newProgress = Number(progress.toFixed(3))
    countdownProgress.value = newProgress
    
    // 继续动画循环
    if (now < endTime) {
      animationFrameId = requestAnimationFrame(updateCountdown)
      // 将 animationFrameId 存储到 countdownInterval 中，以便清理
      countdownInterval.value = animationFrameId as any
    } else {
      // 倒计时结束，确保进度为100%
      countdownProgress.value = 100
      countdown.value = 0
      stopCountdown()
      // 立即触发自动平仓
      autoCloseOrder()
    }
  }
  
  // 启动动画循环
  animationFrameId = requestAnimationFrame(updateCountdown)
  countdownInterval.value = animationFrameId as any
  
  // 在倒计时结束时触发自动平仓（通过 updateCountdown 函数处理）
  // 不再需要 setInterval 的倒计时结束检查，因为已经在 requestAnimationFrame 中处理
}

// 停止倒计时
function stopCountdown() {
  if (countdownInterval.value) {
    // 如果是 requestAnimationFrame，使用 cancelAnimationFrame
    cancelAnimationFrame(countdownInterval.value as number)
    countdownInterval.value = null
  }
}

// 自动平仓
async function autoCloseOrder() {
  if (!termOrderDetail.value || !termOrderDetail.value.id) {
    console.error('自动平仓失败：订单详情或订单ID为空')
    return
  }
  
  // 检查订单状态，如果已经关闭，不再执行
  if (termOrderDetail.value.status === 'CLOSED') {
    console.log('订单已关闭，跳过自动平仓')
    return
  }
  
  console.log('开始自动平仓，订单ID:', termOrderDetail.value.id)
  
  try {
    const currentPriceValue = currentPrice.value
    if (!currentPriceValue || currentPriceValue <= 0) {
      console.error('当前价格无效:', currentPriceValue)
      showToast(localeStore.t('currentPriceInvalid'), 'error')
      return
    }
    
    console.log('调用平仓API，订单ID:', termOrderDetail.value.id, '平仓价格:', currentPriceValue)
    
    const res: any = await request.post(`/trade/option/order/${termOrderDetail.value.id}/close`, {
      closePrice: currentPriceValue
    })
    
    console.log('平仓API响应:', res)
    
    if (res.success || res.success === undefined) {
      // 更新订单详情
      termOrderDetail.value.status = 'CLOSED'
      termOrderDetail.value.closePrice = currentPriceValue
      termOrderDetail.value.closeTime = new Date().toISOString()
      termOrderDetail.value.profit = res.data?.profit || res.profit || res.data?.profit || 0
      
      console.log('自动平仓成功，盈亏:', termOrderDetail.value.profit)
      
      // 显示成功提示
      showToast(localeStore.t('orderAutoSettled'), 'success')
      
      // 刷新余额
      await loadOptionBalance()
      
      // 3秒后关闭弹窗
      setTimeout(() => {
        closeTermOrderDetailModal()
      }, 3000)
    } else {
      console.error('自动平仓失败，响应:', res)
      showToast(res.message || localeStore.t('orderAutoSettled'), 'error')
    }
  } catch (e: any) {
    console.error('自动平仓异常:', e)
    const errorMessage = e?.response?.data?.message || e?.message || localeStore.t('orderAutoSettled')
    console.error('错误详情:', errorMessage)
    showToast(errorMessage, 'error')
  }
}

// 关闭订单详情弹窗
function closeTermOrderDetailModal() {
  stopCountdown()
  showTermOrderDetailModal.value = false
  termOrderDetail.value = null
  countdown.value = 0
  countdownProgress.value = 0
}

// 获取价格颜色类（根据方向和价格变化）
function getPriceColorClass(order: any) {
  if (!order) return ''
  
  const isClosed = order.status === 'CLOSED'
  const openPrice = Number(order.openPrice || 0)
  const comparePrice = isClosed ? Number(order.closePrice || 0) : Number(currentPrice.value || 0)
  
  if (openPrice <= 0 || comparePrice <= 0) return ''
  
  const direction = order.direction
  
  // 买涨：价格上涨为绿色（盈利），价格下跌为红色（亏损）
  // 买跌：价格下跌为绿色（盈利），价格上涨为红色（亏损）
  if (direction === 'UP') {
    return comparePrice > openPrice ? 'positive' : comparePrice < openPrice ? 'negative' : ''
  } else if (direction === 'DOWN') {
    return comparePrice < openPrice ? 'positive' : comparePrice > openPrice ? 'negative' : ''
  }
  
  return ''
}

// 用户余额
const balance = ref(0)
// const fundBalance = ref(0) // 未使用，保留以备后用
const contractBalance = ref(0)

// 成功弹窗
const showSuccessModal = ref(false)
const successMessage = ref(localeStore.t('orderConfirmed'))

// 页面提示消息
const toastMessage = ref('')
const toastType = ref<'success' | 'error' | ''>('')

function showToast(msg: string, type: 'success' | 'error' = 'error') {
  toastMessage.value = msg
  toastType.value = type
  setTimeout(() => {
    toastMessage.value = ''
    toastType.value = ''
  }, 3000)
}

// 当前K线数据（用于显示High, Open, Low）
const currentKlineData = ref<{
  high: number
  open: number
  low: number
  close: number
} | null>(null)

// 交易对列表（从后端获取）
const symbols = ref<any[]>([])
// 所有币种列表（用于下拉选择器，包含所有分类的币种）
const allSymbols = ref<any[]>([])
// 下拉选择器显示状态
const showSymbolDropdown = ref(false)
// 当前交易对的合约设置
const currentSymbolInfo = ref<any>(null)
// Symbol 到 alltickSymbol 的映射（刷新页面后需要重新建立）
const symbolToAlltickMap = ref<Map<string, string>>(new Map())

// 时间周期选项
const intervals = [
  { value: '1m', label: '1M' },
  { value: '5m', label: '5M' },
  { value: '15m', label: '15M' },
  { value: '30m', label: '30M' },
  { value: '1h', label: '1H' },
  { value: '1d', label: '1D' },
]

// 获取当前价格
// 判断是否休市
// 休市判断已由后端处理（基于美股交易时间），前端不再基于K线数据判断
const isMarketClosed = computed(() => {
  // 后端已处理美股交易时间的休市判断，前端不再判断
  return false
})

const currentPrice = computed(() => {
  const price = marketStore.getPrice(currentSymbol.value)
  return price > 0 ? price : 0
})

// 获取24h涨跌幅
const change24h = computed(() => {
  return marketStore.getChange24h(currentSymbol.value)
})

// 格式化价格
function formatPrice(price: number | null | undefined, precision: number = 2) {
  if (price === null || price === undefined || isNaN(Number(price))) {
    const defaultValue = '0'
    if (precision > 0) {
      return defaultValue + '.' + '0'.repeat(precision)
    }
    return defaultValue
  }
  const numPrice = Number(price)
  if (isNaN(numPrice)) {
    const defaultValue = '0'
    if (precision > 0) {
      return defaultValue + '.' + '0'.repeat(precision)
    }
    return defaultValue
  }
  return numPrice.toLocaleString('en-US', { minimumFractionDigits: precision, maximumFractionDigits: precision })
}

function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 格式化日期时间

// 选择交易对
async function selectSymbol(symbol: string, category: string) {
  currentSymbol.value = symbol
  currentCategory.value = category
  currentSymbolInfo.value = allSymbols.value.find((item: any) => item.symbol === symbol) || null
  
  // 确保该分类的市场服务已初始化（HTTP轮询）
  await marketStore.initMarketService(category)
  
  // 订阅实时价格（HTTP轮询）
  console.log('[Trade] Selecting symbol:', symbol, 'category:', category)
  await marketStore.subscribeSymbol(symbol, category)
  console.log('[Trade] ✅ Subscribed to symbol via HTTP polling:', symbol)
  
  // 加载交易对详细信息（包含合约设置）
  await loadSymbolInfo(symbol)
  
  // 加载K线数据（选择交易对时，强制从HTTP接口获取，使用较大的limit确保获取足够的数据）
  await marketStore.fetchKlines(symbol, category, currentInterval.value, 200)
}

// 加载交易对详细信息
async function loadSymbolInfo(symbol: string) {
  try {
    const res: any = await request.get('/market/symbols', {
      params: { category: currentCategory.value },
    })
    if (res && res.list) {
      const symbolInfo = res.list.find((s: any) => s.symbol === symbol)
      if (symbolInfo && currentSymbol.value === symbol) {
        currentSymbolInfo.value = symbolInfo
      }
    }
  } catch (e) {
    console.error('加载交易对信息失败:', e)
  }
}

// 切换K线周期
async function changeInterval(interval: string) {
  currentInterval.value = interval
  // 切换周期时，强制从HTTP接口获取新的K线数据，使用较大的limit确保获取足够的数据
  await marketStore.fetchKlines(currentSymbol.value, currentCategory.value, interval, 200)
}

// 监听订单类型变化，切换到挂单时设置默认价格为当前价格
watch(
  () => orderType.value,
  (newType) => {
    if (newType === 'limit' && limitPrice.value === 0) {
      limitPrice.value = currentPrice.value
    }
  }
)

// 监听当前价格变化，如果挂单价格为0，自动设置为当前价格
watch(
  () => currentPrice.value,
  (newPrice) => {
    if (orderType.value === 'limit' && newPrice > 0 && limitPrice.value === 0) {
      limitPrice.value = newPrice
    }
  }
)

// 获取K线数据中的最高价
function getKlineHigh(): number {
  const klines = marketStore.getKlines(currentSymbol.value, 100)
  if (!klines || klines.length === 0) return 0
  return Math.max(...klines.map(k => k.high))
}

// 获取K线数据中的开盘价（最新一根）
function getKlineOpen(): number {
  const klines = marketStore.getKlines(currentSymbol.value, 100)
  if (!klines || klines.length === 0) return 0
  return klines[klines.length - 1]?.open || 0
}

// 获取K线数据中的最低价
function getKlineLow(): number {
  const klines = marketStore.getKlines(currentSymbol.value, 100)
  if (!klines || klines.length === 0) return 0
  return Math.min(...klines.map(k => k.low))
}

// 调整数量
function adjustQuantity(delta: number) {
  const step = 0.01
  const newValue = buyQuantity.value + delta * step
  buyQuantity.value = Math.max(step, Math.round(newValue / step) * step)
}

// 调整止损（合约交易：按步长1调整）
function adjustStopLoss(delta: number) {
  if (!stopLossEnabled.value) return
  
  // 如果是合约交易且当前值为0，先设置为当前价格
  if (activeTab.value === 'contract' && stopLossValue.value === 0 && currentPrice.value > 0) {
    stopLossValue.value = currentPrice.value
  }
  
  // 按步长1调整
  const step = 1
  stopLossValue.value = Math.max(0, stopLossValue.value + delta * step)
}

// 调整止盈（合约交易：按步长1调整）
function adjustTakeProfit(delta: number) {
  if (!takeProfitEnabled.value) return
  
  // 如果是合约交易且当前值为0，先设置为当前价格
  if (activeTab.value === 'contract' && takeProfitValue.value === 0 && currentPrice.value > 0) {
    takeProfitValue.value = currentPrice.value
  }
  
  // 按步长1调整
  const step = 1
  takeProfitValue.value = Math.max(0, takeProfitValue.value + delta * step)
}

// 监听止损开关（合约交易：打开时自动设置为当前价格）
watch(
  () => stopLossEnabled.value,
  (enabled) => {
    if (enabled && activeTab.value === 'contract' && currentPrice.value > 0) {
      stopLossValue.value = currentPrice.value
    } else if (!enabled) {
      stopLossValue.value = 0
    }
  }
)

// 监听止盈开关（合约交易：打开时自动设置为当前价格）
watch(
  () => takeProfitEnabled.value,
  (enabled) => {
    if (enabled && activeTab.value === 'contract' && currentPrice.value > 0) {
      takeProfitValue.value = currentPrice.value
    } else if (!enabled) {
      takeProfitValue.value = 0
    }
  }
)

// 买入
async function handleBuy() {
  try {
    // 检查是否休市（已移除硬编码的休市时间判断）
    if (isMarketClosed.value) {
      showToast(localeStore.t('marketClosed') || 'Market Closed', 'error')
      return
    }
    
    // 挂单模式下需要验证价格
    if (orderType.value === 'limit' && (!limitPrice.value || limitPrice.value <= 0)) {
      showToast(localeStore.t('enterLimitPrice'), 'error')
      return
    }
    
    // 验证数量
    if (buyQuantity.value <= 0) {
      showToast(localeStore.t('enterValidQuantity'), 'error')
      return
    }
    
    // 验证余额是否足够（保证金 + 手续费）
    if (totalCost.value > balance.value) {
      showToast(localeStore.t('insufficientBalanceForTrade'), 'error')
      return
    }
    
    // 调用买入接口
    const orderData = {
      symbol: currentSymbol.value,
      side: 'BUY',
      type: orderType.value === 'limit' ? 'LIMIT' : 'MARKET',
      quantity: buyQuantity.value,
      leverage: selectedLeverage.value,
      price: orderType.value === 'limit' ? limitPrice.value : null,
      currentPrice: currentPrice.value,
      stopLoss: stopLossEnabled.value ? stopLossValue.value : null,
      takeProfit: takeProfitEnabled.value ? takeProfitValue.value : null,
    }
    
    const res: any = await request.post('/trade/contract/order', orderData)
    
    if (res.success) {
      successMessage.value = localeStore.t('orderConfirmed')
      showSuccessModal.value = true
      // 刷新余额
      await loadContractBalance()
    } else {
      showToast(res.message || localeStore.t('buyFailed'), 'error')
    }
  } catch (error: any) {
    console.error('买入失败:', error)
    const message = error.response?.data?.message || error.message || localeStore.t('buyFailed')
    showToast(message, 'error')
  }
}

// 卖出
async function handleSell() {
  try {
    // 检查是否休市（已移除硬编码的休市时间判断）
    if (isMarketClosed.value) {
      showToast(localeStore.t('marketClosed') || 'Market Closed', 'error')
      return
    }
    
    // 挂单模式下需要验证价格
    if (orderType.value === 'limit' && (!limitPrice.value || limitPrice.value <= 0)) {
      showToast(localeStore.t('enterLimitPrice'), 'error')
      return
    }
    
    // 验证数量
    if (buyQuantity.value <= 0) {
      showToast(localeStore.t('enterValidQuantity'), 'error')
      return
    }
    
    // 验证余额是否足够（保证金 + 手续费）
    if (totalCost.value > balance.value) {
      showToast(localeStore.t('insufficientBalanceForTrade'), 'error')
      return
    }
    
    // 调用卖出接口
    const orderData = {
      symbol: currentSymbol.value,
      side: 'SELL',
      type: orderType.value === 'limit' ? 'LIMIT' : 'MARKET',
      quantity: buyQuantity.value,
      leverage: selectedLeverage.value,
      price: orderType.value === 'limit' ? limitPrice.value : null,
      currentPrice: currentPrice.value,
      stopLoss: stopLossEnabled.value ? stopLossValue.value : null,
      takeProfit: takeProfitEnabled.value ? takeProfitValue.value : null,
    }
    
    const res: any = await request.post('/trade/contract/order', orderData)
    
    if (res.success) {
      successMessage.value = localeStore.t('orderConfirmed')
      showSuccessModal.value = true
      // 刷新余额
      await loadContractBalance()
    } else {
      showToast(res.message || localeStore.t('sellFailed'), 'error')
    }
  } catch (error: any) {
    console.error('卖出失败:', error)
    const message = error.response?.data?.message || error.message || localeStore.t('sellFailed')
    showToast(message, 'error')
  }
}

// 期限交易：買漲
async function handleTermBuy() {
  try {
    // 检查是否休市（已移除硬编码的休市时间判断）
    if (isMarketClosed.value) {
      showToast(localeStore.t('marketClosed') || 'Market Closed', 'error')
      return
    }
    
    if (termAmount.value <= 0) {
      showToast(localeStore.t('enterTradingAmount'), 'error')
      return
    }
    
    // 验证最小和最大交易金额
    const config = currentDurationConfig.value
    if (config) {
      if (config.minAmount && termAmount.value < config.minAmount) {
        showToast(`${localeStore.t('tradingAmountCannotBeLessThan')} ${config.minAmount}`, 'error')
        return
      }
      if (config.maxAmount && termAmount.value > config.maxAmount) {
        showToast(`${localeStore.t('tradingAmountCannotBeGreaterThan')} ${config.maxAmount}`, 'error')
        return
      }
    }
    
    if (termAmount.value > optionBalance.value) {
      showToast(localeStore.t('insufficientOptionBalance'), 'error')
      return
    }
    
    // 保存提交前的值（在重置前保存）
    const submitAmount = Number(termAmount.value)
    const submitOpenPrice = Number(currentPrice.value)
    const submitDuration = Number(termDuration.value)
    
    const requestData = {
      symbol: currentSymbol.value,
      direction: 'UP',
      amount: submitAmount,
      currentPrice: submitOpenPrice,
      duration: submitDuration,
    }
    
    console.log('提交订单数据:', requestData)
    
    const res: any = await request.post('/trade/option/order', requestData)
    
    console.log('后端响应:', res)
    
    if (res.success) {
      // 关闭下单弹窗
      closeTermOrderModal()
      
      // 优先使用后端返回的完整订单数据
      const responseOrder = res.data || res.order || {}
      const orderId = responseOrder.id || res.orderId || res.id
      
      if (!orderId) {
        showToast(localeStore.t('orderCreatedFailedNoId'), 'error')
        return
      }
      
      // 从后端返回的数据中获取金额，如果没有则使用提交时的值
      // 注意：后端返回的 amount 可能是 BigDecimal，需要转换为数字
      const backendAmount = responseOrder.amount != null ? Number(responseOrder.amount) : null
      const backendOpenPrice = responseOrder.openPrice != null ? Number(responseOrder.openPrice) : null
      const backendDuration = responseOrder.duration != null ? Number(responseOrder.duration) : null
      
      const savedAmount = backendAmount !== null && backendAmount > 0 ? backendAmount : submitAmount
      const savedOpenPrice = backendOpenPrice !== null && backendOpenPrice > 0 ? backendOpenPrice : submitOpenPrice
      const savedDuration = backendDuration !== null && backendDuration > 0 ? backendDuration : submitDuration
      
      console.log('后端返回的订单数据:', responseOrder)
      console.log('后端金额:', backendAmount, '提交金额:', submitAmount, '最终金额:', savedAmount)
      console.log('保存的值 - 金额:', savedAmount, '开仓价:', savedOpenPrice, '时长:', savedDuration)
      
      // 显示订单详情弹窗，优先使用后端返回的数据
      termOrderDetail.value = {
        id: orderId,
        symbol: responseOrder.symbol || currentSymbol.value,
        direction: responseOrder.direction || 'UP',
        amount: savedAmount,
        openPrice: savedOpenPrice,
        duration: savedDuration,
        openTime: responseOrder.openTime || responseOrder.createdAt || new Date().toISOString(),
        status: responseOrder.status || 'TRADING',
        profit: Number(responseOrder.profit || 0),
        baseCurrency: currentSymbolInfo.value?.baseCurrency || '',
        quoteCurrency: currentSymbolInfo.value?.quoteCurrency || '',
      }
      
      console.log('订单详情设置:', termOrderDetail.value)
      console.log('金额值:', termOrderDetail.value.amount, '类型:', typeof termOrderDetail.value.amount)
      
      countdown.value = savedDuration
      countdownProgress.value = 0
      showTermOrderDetailModal.value = true
      
      // 刷新余额
      await loadOptionBalance()
      
      // 重置输入（在显示弹窗后再重置）
      termAmount.value = 0
      
      // 使用 setTimeout 确保 DOM 更新后再启动倒计时
      setTimeout(() => {
        startCountdown()
      }, 100)
    } else {
      showToast(res.message || localeStore.t('buyUpFailed'), 'error')
    }
  } catch (error: any) {
    console.error('買漲失败:', error)
    const message = error.response?.data?.message || error.message || localeStore.t('buyUpFailed')
    showToast(message, 'error')
  }
}

// 期限交易：買跌
async function handleTermSell() {
  try {
    // 检查是否休市（已移除硬编码的休市时间判断）
    if (isMarketClosed.value) {
      showToast(localeStore.t('marketClosed') || 'Market Closed', 'error')
      return
    }
    
    if (termAmount.value <= 0) {
      showToast(localeStore.t('enterTradingAmount'), 'error')
      return
    }
    
    // 验证最小和最大交易金额
    const config = currentDurationConfig.value
    if (config) {
      if (config.minAmount && termAmount.value < config.minAmount) {
        showToast(`${localeStore.t('tradingAmountCannotBeLessThan')} ${config.minAmount}`, 'error')
        return
      }
      if (config.maxAmount && termAmount.value > config.maxAmount) {
        showToast(`${localeStore.t('tradingAmountCannotBeGreaterThan')} ${config.maxAmount}`, 'error')
        return
      }
    }
    
    if (termAmount.value > optionBalance.value) {
      showToast(localeStore.t('insufficientOptionBalance'), 'error')
      return
    }
    
    // 保存提交前的值（在重置前保存）
    const submitAmount = Number(termAmount.value)
    const submitOpenPrice = Number(currentPrice.value)
    const submitDuration = Number(termDuration.value)
    
    const requestData = {
      symbol: currentSymbol.value,
      direction: 'DOWN',
      amount: submitAmount,
      currentPrice: submitOpenPrice,
      duration: submitDuration,
    }
    
    console.log('提交订单数据:', requestData)
    
    const res: any = await request.post('/trade/option/order', requestData)
    
    console.log('后端响应:', res)
    
    if (res.success) {
      // 关闭下单弹窗
      closeTermOrderModal()
      
      // 优先使用后端返回的完整订单数据
      const responseOrder = res.data || res.order || {}
      const orderId = responseOrder.id || res.orderId || res.id
      
      if (!orderId) {
        showToast(localeStore.t('orderCreatedFailedNoId'), 'error')
        return
      }
      
      // 从后端返回的数据中获取金额，如果没有则使用提交时的值
      // 注意：后端返回的 amount 可能是 BigDecimal，需要转换为数字
      const backendAmount = responseOrder.amount != null ? Number(responseOrder.amount) : null
      const backendOpenPrice = responseOrder.openPrice != null ? Number(responseOrder.openPrice) : null
      const backendDuration = responseOrder.duration != null ? Number(responseOrder.duration) : null
      
      const savedAmount = backendAmount !== null && backendAmount > 0 ? backendAmount : submitAmount
      const savedOpenPrice = backendOpenPrice !== null && backendOpenPrice > 0 ? backendOpenPrice : submitOpenPrice
      const savedDuration = backendDuration !== null && backendDuration > 0 ? backendDuration : submitDuration
      
      console.log('后端返回的订单数据:', responseOrder)
      console.log('后端金额:', backendAmount, '提交金额:', submitAmount, '最终金额:', savedAmount)
      console.log('保存的值 - 金额:', savedAmount, '开仓价:', savedOpenPrice, '时长:', savedDuration)
      
      termOrderDetail.value = {
        id: orderId,
        symbol: responseOrder.symbol || currentSymbol.value,
        direction: responseOrder.direction || 'DOWN',
        amount: savedAmount,
        openPrice: savedOpenPrice,
        duration: savedDuration,
        openTime: responseOrder.openTime || responseOrder.createdAt || new Date().toISOString(),
        status: responseOrder.status || 'TRADING',
        profit: Number(responseOrder.profit || 0),
        baseCurrency: currentSymbolInfo.value?.baseCurrency || '',
        quoteCurrency: currentSymbolInfo.value?.quoteCurrency || '',
      }
      
      console.log('订单详情设置:', termOrderDetail.value)
      console.log('金额值:', termOrderDetail.value.amount, '类型:', typeof termOrderDetail.value.amount)
      
      countdown.value = savedDuration
      countdownProgress.value = 0
      showTermOrderDetailModal.value = true
      
      // 刷新余额
      await loadOptionBalance()
      
      // 重置输入（在显示弹窗后再重置）
      termAmount.value = 0
      
      // 使用 setTimeout 确保 DOM 更新后再启动倒计时
      setTimeout(() => {
        startCountdown()
      }, 100)
    } else {
      showToast(res.message || localeStore.t('buyDownFailed'), 'error')
    }
  } catch (error: any) {
    console.error('買跌失败:', error)
    const message = error.response?.data?.message || error.message || localeStore.t('buyDownFailed')
    showToast(message, 'error')
  }
}

// 计算预计收益（使用后端设置的盈利比例）
const expectedReturn = computed(() => {
  if (termAmount.value <= 0) return 0
  // 使用当前选中期限的盈利比例（从后端加载的 durationOptions 中获取）
  const profitRate = currentDurationConfig.value?.profitRate || 0.8
  return termAmount.value * profitRate
})


// 获取每手数量
const lotSize = computed(() => {
  const value = currentSymbolInfo.value?.lotSize
  if (value != null && !isNaN(Number(value))) {
    return Number(value)
  }
  return 1000
})


// 获取手续费倍数
const feeMultiplier = computed(() => {
  const value = currentSymbolInfo.value?.feeMultiplier
  if (value != null && !isNaN(Number(value))) {
    return Number(value)
  }
  return 30
})

const maxLeverage = computed(() => leverageLimit(currentSymbolInfo.value?.maxLeverage))
const leverageOptions = computed(() => leverageChoices(maxLeverage.value))
watch(maxLeverage, max => { selectedLeverage.value = Math.min(selectedLeverage.value, max) })

// 市价按当前行情预估，挂单按限价预留。
const estimatedMargin = computed(() => contractMargin(
  Number(buyQuantity.value), lotSize.value,
  orderType.value === 'limit' ? Number(limitPrice.value) : currentPrice.value, selectedLeverage.value,
))

// 计算预估手续费 = 买入数量 × 手续费倍数
const estimatedFee = computed(() => {
  const qty = Number(buyQuantity.value) || 0
  if (qty <= 0) return 0
  const multiplier = feeMultiplier.value
  return qty * multiplier
})

// 计算总费用（预估保证金 + 预估手续费）
const totalCost = computed(() => {
  const margin = Number(estimatedMargin.value) || 0
  const fee = Number(estimatedFee.value) || 0
  return margin + fee
})

// 监听标签页切换，更新余额显示
watch(
  () => activeTab.value,
  async (newTab) => {
    if (newTab === 'contract') {
      await loadContractBalance()
    } else {
      await loadOptionBalance()
    }
  }
)

// 加载所有币种列表（类似首页，包含所有分类）
async function loadAllSymbols() {
  try {
    console.log('[Trade] Loading all symbols from API...')
    const res: any = await request.get('/market/all')
    const symbolList = res.list || []
    
    console.log('[Trade] Loaded', symbolList.length, 'symbols from API')
    
    // 存储所有币种
    allSymbols.value = symbolList
    currentSymbolInfo.value = symbolList.find((item: any) => item.symbol === currentSymbol.value) || null
    
    // 建立 symbol 到 alltickSymbol 的映射
    symbolToAlltickMap.value.clear()
    symbolList.forEach((s: any) => {
      const symbol = s.symbol
      const alltickSymbol = s.alltickSymbol || s.symbol
      symbolToAlltickMap.value.set(symbol, alltickSymbol)
      // 同时建立反向映射（alltickSymbol -> symbol）
      if (alltickSymbol !== symbol) {
        symbolToAlltickMap.value.set(alltickSymbol, symbol)
      }
    })
    
    console.log('[Trade] Symbol to alltickSymbol mapping established:', Array.from(symbolToAlltickMap.value.entries()).slice(0, 10))
    
    // 按分类过滤当前分类的币种
    symbols.value = symbolList.filter((s: any) => (s.category || 'Crypto') === currentCategory.value)
    console.log('[Trade] Filtered', symbols.value.length, 'symbols for category', currentCategory.value)
    
    // 订阅所有币种的行情（按分类分组，使用 alltickSymbol）
    const symbolListForSubscription = symbolList.map((s: any) => {
      const category = s.category || 'Crypto'
      const alltickSymbol = s.alltickSymbol || s.symbol
      return {
        symbol: s.symbol,
        category: category,
        alltickSymbol: alltickSymbol,
      }
    })
    
    console.log('[Trade] Starting batch subscription for all symbols...')
    // 异步订阅，在后台执行（类似首页）
    marketStore.subscribeSymbols(symbolListForSubscription).then(() => {
      console.log('[Trade] ✅ Batch subscription completed for all symbols')
    }).catch((error) => {
      console.error('[Trade] Batch subscription failed:', error)
    })
    
    return symbolList
  } catch (e) {
    console.error('[Trade] Load all symbols error:', e)
    return []
  }
}

// 加载当前分类的交易对列表（用于下拉选择器）
async function loadSymbols() {
  try {
    // 如果已经加载了所有币种，直接过滤
    if (allSymbols.value.length > 0) {
      symbols.value = allSymbols.value.filter((s: any) => (s.category || 'Crypto') === currentCategory.value)
      return
    }
    
    // 否则从 API 加载当前分类的币种
    const res: any = await request.get('/market/symbols', {
      params: { category: currentCategory.value },
    })
    symbols.value = res.list || []
    
    // 如果当前symbol不在列表中，使用第一个
    if (symbols.value.length > 0 && !symbols.value.find((s: any) => s.symbol === currentSymbol.value)) {
      // 只有当且仅当目前的 currentSymbol 不在最新获取的当前分类列表中时，才去改变它
      // 【修复】：并且必须在初始化完成之后（用户手动切换分类时）才允许跳转，防止首次加载时跳变。
      if (isInitialized.value) {
        const firstSymbol = symbols.value[0]
        await selectSymbol(firstSymbol.symbol, firstSymbol.category || currentCategory.value)
      }
    }
  } catch (e) {
    console.error('加载交易对失败:', e)
  }
}

// 切换下拉选择器显示状态
function toggleSymbolDropdown() {
  showSymbolDropdown.value = !showSymbolDropdown.value
  if (showSymbolDropdown.value && allSymbols.value.length === 0) {
    // 如果还没有加载所有币种，加载它们
    loadAllSymbols().catch(e => console.error('[Trade] Failed to load all symbols for dropdown:', e))
  }
}

// 点击外部关闭侧边栏（遮罩层已处理，此函数保留作为备用）
function handleClickOutside(event: MouseEvent) {
  const target = event.target as HTMLElement
  // 如果点击的不是侧边栏相关元素，关闭侧边栏
  if (!target.closest('.symbol-sidebar') && !target.closest('.symbol-selector-dropdown')) {
    showSymbolDropdown.value = false
  }
}

// 选择币种并关闭下拉列表
async function selectSymbolFromDropdown(symbol: any) {
  // 立即关闭弹窗，提供更好的用户体验
  showSymbolDropdown.value = false
  // 然后执行选择交易对的操作
  await selectSymbol(symbol.symbol, symbol.category || 'Crypto')
}

// 获取币种的实时价格
function getSymbolPrice(symbol: string): number {
  return marketStore.getPrice(symbol) || 0
}

// 获取币种的涨跌幅
function getSymbolChange(symbol: string) {
  return marketStore.getChange24h(symbol)
}

// 加载合约余额
async function loadContractBalance() {
  try {
    const user = auth.user
    if (!user?.id) {
      console.warn('用户未登录，无法加载合约余额')
      return
    }
    
    const res: any = await request.get('/trade/contract/balance')
    if (res && res.success !== false) {
      contractBalance.value = Number(res.balance || res.available || 0)
      if (activeTab.value === 'contract') {
        balance.value = contractBalance.value // 合约交易使用合约余额
      }
    } else {
      console.error('加载合约余额失败:', res?.message || '未知错误')
      showToast(res?.message || localeStore.t('loadContractBalanceFailed'), 'error')
      contractBalance.value = 0
      if (activeTab.value === 'contract') {
        balance.value = 0
      }
    }
  } catch (e: any) {
    console.error('加载合约余额失败:', e)
    const errorMsg = e.response?.data?.message || e.message || localeStore.t('loadContractBalanceFailed')
    showToast(errorMsg, 'error')
    contractBalance.value = 0
    if (activeTab.value === 'contract') {
      balance.value = 0
    }
  }
}

// 加载期权余额
async function loadOptionBalance() {
  try {
    const user = auth.user
    const userId = user?.id
    if (!userId) return
    
    const res: any = await request.get(`/user/${userId}/info`)
    optionBalance.value = Number(res.optionBalance || 0)
    if (activeTab.value === 'term') {
      balance.value = optionBalance.value // 期限交易使用期权余额
    }
  } catch (e) {
    console.error('加载期权余额失败:', e)
  }
}

// async function loadBalance() {
//   try {
//     const user = auth.user
//     const userId = user?.id
//     if (!userId) return
//     
//     const res: any = await request.get(`/user/${userId}/info`)
//     balance.value = Number(res.fundBalance || 0)
//     fundBalance.value = Number(res.fundBalance || 0)
//   } catch (e) {
//     console.error('加载余额失败:', e)
//   }
// }

// 监听K线数据变化，更新High, Open, Low
watch(
  () => marketStore.getKlines(currentSymbol.value),
  (klines) => {
    if (klines && klines.length > 0) {
      const latest = klines[klines.length - 1]
      if (latest) {
        // 如果已有数据，保留当前价格作为收盘价
        const currentClose = currentKlineData.value?.close || latest.close
        const currentHigh = currentKlineData.value?.high || latest.high
        const currentLow = currentKlineData.value?.low || latest.low
        
        currentKlineData.value = {
          high: Math.max(currentHigh, latest.high, currentPrice.value || 0),
          open: latest.open,
          low: Math.min(currentLow, latest.low, currentPrice.value || Infinity),
          close: currentPrice.value > 0 ? currentPrice.value : currentClose,
        }
      }
    }
  },
  { immediate: true, deep: true }
)

// 监听当前价格变化，实时更新收盘价、最高价、最低价
watch(
  () => currentPrice.value,
  (newPrice) => {
    if (newPrice > 0) {
      if (!currentKlineData.value) {
        // 如果还没有K线数据，先初始化
        const klines = marketStore.getKlines(currentSymbol.value)
        if (klines && klines.length > 0) {
          const latest = klines[klines.length - 1]
          if (latest) {
            currentKlineData.value = {
              high: Math.max(latest.high, newPrice),
              open: latest.open,
              low: Math.min(latest.low, newPrice),
              close: newPrice,
            }
          }
        } else {
          // 如果没有K线数据，使用当前价格初始化
          currentKlineData.value = {
            high: newPrice,
            open: newPrice,
            low: newPrice,
            close: newPrice,
          }
        }
      } else {
        // 实时更新收盘价为当前价格
        currentKlineData.value.close = newPrice
        // 如果当前价格高于最高价，更新最高价
        if (newPrice > currentKlineData.value.high) {
          currentKlineData.value.high = newPrice
        }
        // 如果当前价格低于最低价，更新最低价
        if (newPrice < currentKlineData.value.low) {
          currentKlineData.value.low = newPrice
        }
      }
    }
  },
  { immediate: false }
)

// 监听路由参数变化，更新标签页
watch(
  () => route.query.tab,
  (newTab) => {
    if (newTab === 'contract' || newTab === 'term') {
      activeTab.value = newTab
    }
  },
  { immediate: true }
)

onMounted(async () => {
  // 加载期限选项
  await loadDurationOptions()
  
  // 根据当前标签页加载对应的余额（不加载资金余额）
  if (activeTab.value === 'contract') {
    await loadContractBalance()
  } else {
    await loadOptionBalance()
  }
  
  // 先加载所有币种（建立映射关系，类似首页）
  console.log('[Trade] Loading all symbols first...')
  await loadAllSymbols()
  
  // 初始化市场服务（HTTP轮询）
  console.log('[Trade] Initializing market service for category:', currentCategory.value)
  await marketStore.initMarketService(currentCategory.value)
  
  // 等待服务初始化完成
  await new Promise(resolve => setTimeout(resolve, 1000))
  
  // 加载当前分类的交易对列表（从已加载的 allSymbols 中过滤）
  await loadSymbols()
  
  // 如果路由中有币种参数，使用路由参数
  if (route.query.symbol && route.query.category) {
    const symbol = route.query.symbol as string
    const category = route.query.category as string
    // 获取对应的 alltickSymbol
    const alltickSymbol = symbolToAlltickMap.value.get(symbol) || symbol
    console.log('[Trade] Route params - symbol:', symbol, 'alltickSymbol:', alltickSymbol, 'category:', category)
    await selectSymbol(symbol, category)
  } else if (symbols.value.length > 0) {
    // 【彻底修复跳变问题】：不管是否在列表中，如果是初次加载（且没有带路由参数），直接强制选中并订阅当前的默认值 currentSymbol.value（BTCUSD）。
    console.log('[Trade] No route params, enforcing default symbol:', currentSymbol.value)
    await selectSymbol(currentSymbol.value, currentCategory.value)
  }
  
  // 添加点击外部关闭下拉菜单的事件监听
  document.addEventListener('click', handleClickOutside)
  
  // 确保当前交易对已订阅（刷新页面后需要重新订阅，使用HTTP轮询）
  if (currentSymbol.value) {
    console.log('[Trade] Ensuring current symbol is subscribed:', currentSymbol.value)
    // 使用HTTP轮询订阅实时价格
    await marketStore.subscribeSymbol(currentSymbol.value, currentCategory.value)
    console.log('[Trade] ✅ Subscribed to symbol via HTTP polling:', currentSymbol.value)
  }
  
  // 标记初始化完成，允许后续手动切换分类时的自动跳变
  isInitialized.value = true
})

onUnmounted(() => {
  // 移除事件监听
  document.removeEventListener('click', handleClickOutside)
  // 清理倒计时定时器
  stopCountdown()
})
</script>

<template>
  <div class="trade-page">
    <!-- 顶部标签页 -->
    <div class="top-tabs-container">
      <div class="top-tabs">
        <button
          class="tab-btn"
          :class="{ active: activeTab === 'contract' }"
          @click="activeTab = 'contract'"
        >
          {{ localeStore.t('contract') }}
        </button>
        <button
          class="tab-btn"
          :class="{ active: activeTab === 'term' }"
          @click="activeTab = 'term'"
        >
          {{ localeStore.t('term') }}
        </button>
      </div>
    </div>

    <!-- 交易对和价格 -->
    <div class="symbol-header">
      <div class="symbol-selector-dropdown" @click.stop="toggleSymbolDropdown">
        <span class="symbol-name">{{ currentSymbol }}</span>
        <span class="dropdown-icon" :class="{ active: showSymbolDropdown }">▼</span>
      </div>
      <div class="price-display">
        <span v-if="!isMarketClosed" class="price-value" :style="{ color: (change24h?.changePct || 0) >= 0 ? '#85bd00' : '#ef5350' }">
          {{ formatPrice(currentPrice, 2) }}
        </span>
        <span v-else class="price-value market-closed" style="color: #999;">{{ localeStore.t('marketClosed') }}</span>
        <span
          v-if="!isMarketClosed"
          class="price-change"
          :style="{ color: (change24h?.changePct || 0) >= 0 ? '#85bd00' : '#ef5350' }"
        >
          <span class="change-icon">{{ (change24h?.changePct || 0) >= 0 ? '▲' : '▼' }}</span>
          {{ (change24h?.changePct || 0) >= 0 ? '+' : '' }}{{ (change24h?.changePct || 0).toFixed(2) }}%
        </span>
        <span v-else class="price-change" style="color: #999;">-</span>
      </div>
    </div>

    <!-- 时间周期选择 -->
    <div class="interval-selector">
      <button
        v-for="interval in intervals"
        :key="interval.value"
        class="interval-btn"
        :class="{ active: currentInterval === interval.value }"
        @click="changeInterval(interval.value)"
      >
        {{ interval.label }}
      </button>
    </div>

    <!-- K线图 -->
    <div class="chart-section">
      <!-- 价格信息（High, Open, Low） -->
      <div class="kline-info">
        <span class="kline-info-item">High: {{ formatPrice(getKlineHigh(), 2) }}</span>
        <span class="kline-info-item">Open: {{ formatPrice(getKlineOpen(), 2) }}</span>
        <span class="kline-info-item">Low: {{ formatPrice(getKlineLow(), 2) }}</span>
      </div>

      <!-- TradingView K线图 -->
      <div class="chart-container">
        <KlineChart
          :symbol="currentSymbol"
          :category="currentCategory"
          :interval="currentInterval"
          :height="420"
        />
      </div>
      
      <!-- 最低价格显示（底部左侧） -->
      <div class="low-price-indicator" v-if="getKlineLow() > 0">
        <span class="low-price-arrow">↑</span>
        <span class="low-price-value">{{ formatPrice(getKlineLow(), 2) }}</span>
      </div>
    </div>

    <!-- 币种选择侧边栏遮罩 -->
    <div 
      class="symbol-sidebar-overlay" 
      v-if="showSymbolDropdown"
      @click="showSymbolDropdown = false"
    ></div>

    <!-- 币种选择侧边栏（从左侧弹出） -->
    <div 
      class="symbol-sidebar" 
      :class="{ active: showSymbolDropdown }"
      @click.stop
    >
      <div class="symbol-sidebar-header">
        <span class="symbol-sidebar-title">{{ localeStore.t('selectTradingPair') }}</span>
        <button class="symbol-sidebar-close" @click="showSymbolDropdown = false">×</button>
      </div>
      <div class="symbol-list">
        <div
          v-for="symbol in allSymbols"
          :key="symbol.symbol"
          class="symbol-item"
          :class="{ active: symbol.symbol === currentSymbol }"
          @click="selectSymbolFromDropdown(symbol)"
        >
          <span class="symbol-item-name">{{ symbol.symbol }}</span>
          <span
            class="symbol-item-price"
            :style="{ color: (getSymbolChange(symbol.symbol)?.changePct || 0) >= 0 ? '#26a69a' : '#ef5350' }"
          >
            {{ formatPrice(getSymbolPrice(symbol.symbol), symbol.pricePrecision || 2) }}
          </span>
        </div>
      </div>
    </div>

    <!-- 合约订单区域 -->
    <div class="order-section" v-if="activeTab === 'contract'">
      <!-- 订单类型标签页 -->
      <div class="order-type-tabs">
        <button
          class="order-tab-btn"
          :class="{ active: orderType === 'market' }"
          @click="orderType = 'market'"
        >
          {{ localeStore.t('marketPrice') }}
        </button>
        <button
          class="order-tab-btn"
          :class="{ active: orderType === 'limit' }"
          @click="orderType = 'limit'"
        >
          {{ localeStore.t('limitOrder') }}
        </button>
      </div>

      <!-- 挂单价格输入（仅在挂单模式下显示） -->
      <div class="order-item" v-if="orderType === 'limit'">
        <div class="order-label">{{ localeStore.t('price') }}</div>
        <input 
          type="number" 
          v-model.number="limitPrice" 
          class="order-price-input" 
          :placeholder="localeStore.t('enterPrice')"
          step="0.01"
          min="0"
        />
      </div>

      <!-- 止损 -->
      <div class="order-item">
        <div class="order-item-header">
          <span class="order-label">{{ localeStore.t('stopLoss') }}</span>
          <label class="toggle-switch">
            <input type="checkbox" v-model="stopLossEnabled" />
            <span class="toggle-slider"></span>
          </label>
        </div>
        <div class="order-input-group" v-if="stopLossEnabled">
          <button class="input-btn" @click="adjustStopLoss(-1)">-</button>
          <input type="number" v-model.number="stopLossValue" class="order-input" />
          <button class="input-btn" @click="adjustStopLoss(1)">+</button>
        </div>
      </div>

      <!-- 止盈 -->
      <div class="order-item">
        <div class="order-item-header">
          <span class="order-label">{{ localeStore.t('takeProfit') }}</span>
          <label class="toggle-switch">
            <input type="checkbox" v-model="takeProfitEnabled" />
            <span class="toggle-slider"></span>
          </label>
        </div>
        <div class="order-input-group" v-if="takeProfitEnabled">
          <button class="input-btn" @click="adjustTakeProfit(-1)">-</button>
          <input type="number" v-model.number="takeProfitValue" class="order-input" />
          <button class="input-btn" @click="adjustTakeProfit(1)">+</button>
        </div>
      </div>

      <div class="order-item leverage-selector">
        <div class="order-item-header">
          <label class="order-label" for="contract-leverage">{{ localeStore.t('leverage') }}</label>
          <output for="contract-leverage" class="leverage-value">{{ selectedLeverage }}×</output>
        </div>
        <input id="contract-leverage" v-model.number="selectedLeverage" type="range" min="1" :max="maxLeverage" step="1"
          :aria-valuetext="`${selectedLeverage}×`" :disabled="!currentSymbolInfo || maxLeverage === 1" class="leverage-slider" />
        <div class="leverage-presets">
          <button v-for="value in leverageOptions" :key="value" type="button" :aria-pressed="selectedLeverage === value"
            :disabled="!currentSymbolInfo" @click="selectedLeverage = value">{{ value }}×</button>
        </div>
      </div>

      <!-- 买入数量 -->
      <div class="order-item">
        <div class="order-label">{{ localeStore.t('buyQuantityLabel') }}</div>
        <div class="order-input-group">
          <button class="input-btn" @click="adjustQuantity(-1)">-</button>
          <input type="number" v-model.number="buyQuantity" step="0.01" min="0.01" class="order-input" />
          <button class="input-btn" @click="adjustQuantity(1)">+</button>
        </div>
      </div>

      <!-- 交易详情 -->
      <div class="trade-details">
        <div class="detail-row">
          <span class="detail-label">{{ localeStore.t('perLot') }}</span>
          <span class="detail-value">1{{ localeStore.t('lots') }} = {{ Math.round(lotSize || 0) }} {{ currentSymbol }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">{{ localeStore.t('estimatedFee') }}</span>
          <span class="detail-value">{{ formatMoney(estimatedFee) }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">{{ localeStore.t('estimatedMargin') }}</span>
          <span class="detail-value">{{ formatMoney(estimatedMargin) }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">{{ localeStore.t('balance') }}</span>
          <span class="detail-value">{{ formatMoney(balance) }}</span>
        </div>
      </div>

      <!-- 买入/卖出按钮 -->
      <div class="trade-buttons">
        <button class="buy-btn" :disabled="isMarketClosed || !currentSymbolInfo" @click="handleBuy">{{ localeStore.t('buy') }}</button>
        <button class="sell-btn" :disabled="isMarketClosed || !currentSymbolInfo" @click="handleSell">{{ localeStore.t('sell') }}</button>
      </div>
    </div>

    <!-- 期限订单区域 - 显示買漲買跌按钮 -->
    <div class="term-trade-section" v-if="activeTab === 'term'">
      <!-- 统计信息 -->
      <div class="term-statistics-wrapper" v-if="currentKlineData">
        <div class="term-statistics-container">
          <div class="term-statistics-box">
            <div class="statistics-grid">
              <div class="stat-item">
                <div class="stat-label">{{ localeStore.t('open') }}</div>
                <div class="stat-value">{{ formatPrice(currentKlineData.open, 2) }}</div>
              </div>
              <div class="stat-item">
                <div class="stat-label">{{ localeStore.t('close') }}</div>
                <div class="stat-value close-value">{{ formatPrice(currentKlineData.close, 2) }}</div>
              </div>
              <div class="stat-item">
                <div class="stat-label">{{ localeStore.t('low') }}</div>
                <div class="stat-value">{{ formatPrice(currentKlineData.low, 2) }}</div>
              </div>
              <div class="stat-item">
                <div class="stat-label">{{ localeStore.t('high') }}</div>
                <div class="stat-value">{{ formatPrice(currentKlineData.high, 2) }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="term-action-buttons">
        <button 
          class="term-action-btn buy-action" 
          :disabled="isMarketClosed"
          @click="openTermOrderModal('UP')"
        >
          <div class="term-action-btn-content">
            <div class="term-action-btn-text">{{ localeStore.t('lookUp') }}:{{ currentSymbolInfo?.baseCurrency || '' }}</div>
            <div class="term-action-btn-multiplier" v-if="currentDurationConfig && currentDurationConfig.profitRate">
              {{ Number(currentDurationConfig.profitRate).toFixed(1) }}{{ localeStore.t('multiplier') }}
            </div>
          </div>
        </button>
        <button 
          class="term-action-btn sell-action" 
          :disabled="isMarketClosed"
          @click="openTermOrderModal('DOWN')"
        >
          <div class="term-action-btn-content">
            <div class="term-action-btn-text">{{ localeStore.t('lookUp') }}:{{ currentSymbolInfo?.quoteCurrency || '' }}</div>
            <div class="term-action-btn-multiplier" v-if="currentDurationConfig && currentDurationConfig.profitRate">
              {{ Number(currentDurationConfig.profitRate).toFixed(1) }}{{ localeStore.t('multiplier') }}
            </div>
          </div>
        </button>
      </div>
    </div>

    <!-- 期限订单弹窗 -->
    <div 
      v-if="showTermOrderModal" 
      class="term-order-modal-overlay"
      @click.self="closeTermOrderModal"
    >
      <div class="term-order-modal">
        <!-- 关闭按钮 -->
        <button class="term-modal-close" @click="closeTermOrderModal">×</button>
        
        <!-- 方向选择 -->
        <div class="term-modal-item">
          <div class="term-modal-label">{{ localeStore.t('direction') }}</div>
          <div class="direction-buttons">
            <button 
              class="direction-btn buy-direction" 
              :class="{ active: termDirection === 'UP' }"
              @click="termDirection = 'UP'"
            >
              {{ localeStore.t('lookUp') }}:{{ currentSymbolInfo?.baseCurrency || '' }}
            </button>
            <button 
              class="direction-btn sell-direction" 
              :class="{ active: termDirection === 'DOWN' }"
              @click="termDirection = 'DOWN'"
            >
              {{ localeStore.t('lookUp') }}:{{ currentSymbolInfo?.quoteCurrency || '' }}
            </button>
          </div>
        </div>

        <!-- 交易对和价格（移动到方向选择下方） -->
        <div class="term-modal-header">
          <span class="term-modal-symbol">{{ currentSymbol }}</span>
          <span class="term-modal-price" :style="{ color: (change24h?.changePct || 0) >= 0 ? '#85bd00' : '#ef5350' }">
            {{ formatPrice(currentPrice) }}
          </span>
        </div>

        <!-- 选择到期时间 -->
        <div class="term-modal-item">
          <div class="term-modal-label">{{ localeStore.t('selectExpiryTime') }}</div>
          <div class="duration-grid">
            <button
              v-for="option in durationOptions"
              :key="option.value"
              class="duration-btn"
              :class="{ active: termDuration === option.value }"
              @click="termDuration = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </div>

        <!-- 交易数量 -->
        <div class="term-modal-item">
          <div class="term-modal-label">
            {{ localeStore.t('tradingAmount') }}
            <span v-if="currentDurationConfig && (currentDurationConfig.minAmount || currentDurationConfig.maxAmount)" class="amount-limit-hint">
              ({{ currentDurationConfig.minAmount ? `>=${currentDurationConfig.minAmount}` : '' }}{{ currentDurationConfig.minAmount && currentDurationConfig.maxAmount ? ' ' : '' }}{{ currentDurationConfig.maxAmount ? `<=${currentDurationConfig.maxAmount}` : '' }})
            </span>
          </div>
          <div class="term-amount-input-wrapper">
            <input 
              type="number" 
              v-model.number="termAmount" 
              class="term-amount-input" 
              :placeholder="currentDurationConfig && currentDurationConfig.minAmount ? `${localeStore.t('enterTradingAmount')} (>=${currentDurationConfig.minAmount})` : localeStore.t('enterTradingAmount')"
              :min="currentDurationConfig?.minAmount || 0"
              :max="currentDurationConfig?.maxAmount || undefined"
              step="0.01"
            />
            <span class="amount-arrow">›</span>
          </div>
          <div class="expected-return">
            <span class="expected-label">{{ localeStore.t('expectedReturnRate') }}</span>
            <span class="expected-value">{{ formatMoney(expectedReturn) }} USD</span>
          </div>
        </div>

        <!-- 余额信息 -->
        <div class="term-balance-info">
          {{ localeStore.t('balance') }}: {{ formatMoney(optionBalance) }} USD
        </div>

        <!-- 确认按钮 -->
        <div class="term-modal-action">
          <button 
            v-if="termDirection === 'UP'"
            class="term-confirm-btn buy-direction" 
            :disabled="isMarketClosed"
            @click="handleTermBuy"
          >
            {{ localeStore.t('lookUp') }}:{{ currentSymbolInfo?.baseCurrency || '' }}
          </button>
          <button 
            v-else
            class="term-confirm-btn sell-direction" 
            :disabled="isMarketClosed"
            @click="handleTermSell"
          >
            {{ localeStore.t('lookUp') }}:{{ currentSymbolInfo?.quoteCurrency || '' }}
          </button>
        </div>

        <!-- 盈利率 -->
        <div class="term-profit-rate" v-if="currentDurationConfig && currentDurationConfig.profitRate">
          <span class="profit-rate-label">{{ localeStore.t('profitRate') }}:</span>
          <span class="profit-rate-value">{{ (Number(currentDurationConfig.profitRate) * 100).toFixed(2) }}%</span>
        </div>
      </div>
    </div>

    <!-- 订单详情弹窗（购买成功后显示） -->
    <div 
      v-if="showTermOrderDetailModal && termOrderDetail" 
      class="term-order-detail-overlay"
      @click.self="closeTermOrderDetailModal"
    >
      <div class="term-order-detail-modal">
        <!-- 关闭按钮 -->
        <button class="term-detail-close" @click="closeTermOrderDetailModal">×</button>
        
        <!-- 交易对和价格 -->
        <div class="term-detail-header">
          <div class="term-detail-symbol">{{ termOrderDetail.symbol }}</div>
          <div class="term-detail-price">
            <span>{{ formatPrice(termOrderDetail?.openPrice || 0) }}</span>
            <span class="arrow">→</span>
            <span :class="getPriceColorClass(termOrderDetail)">
              {{ formatPrice(termOrderDetail?.status === 'CLOSED' ? (termOrderDetail?.closePrice || 0) : currentPrice) }}
            </span>
          </div>
        </div>

        <!-- 倒计时（仅交易中显示） -->
        <div v-if="termOrderDetail?.status === 'TRADING'" class="term-countdown-section">
          <div class="countdown-circle">
            <svg class="countdown-svg" viewBox="0 0 120 120">
              <!-- 外层虚线圆环 -->
              <circle
                class="countdown-outer-dashed"
                cx="60"
                cy="60"
                r="52"
                fill="none"
                stroke="#060d47"
                stroke-width="2"
                stroke-dasharray="4 4"
                opacity="0.6"
              />
              <!-- 内层实心进度圆环 -->
              <circle
                class="countdown-progress-bg"
                cx="60"
                cy="60"
                r="48"
                fill="none"
                stroke="rgba(6, 13, 71, 0.1)"
                stroke-width="6"
              />
              <circle
                class="countdown-progress"
                cx="60"
                cy="60"
                r="48"
                fill="none"
                stroke="#060d47"
                stroke-width="6"
                stroke-linecap="round"
                :stroke-dasharray="301.6"
                :stroke-dashoffset="301.6 - (countdownProgress / 100) * 301.6"
                transform="rotate(-90 60 60)"
              />
              <!-- 箭头指针（跟随进度条末端位置旋转，指向进度条末端） -->
              <!-- 进度条从顶部（-90度）开始，箭头也要从顶部开始 -->
              <!-- 
                问题分析：如果箭头从9点钟方向开始，说明角度偏移了90度
                SVG 坐标系：
                - 0度 = 右侧（3点）
                - 90度 = 底部（6点）  
                - 180度 = 左侧（9点）
                - 270度/-90度 = 顶部（12点）
                
                如果箭头显示在9点（180度），而应该从12点（-90度）开始，
                说明角度计算需要减去90度，或者箭头路径需要调整。
                
                解决方案：调整箭头路径，使其在旋转0度时指向顶部（12点方向）
                或者调整角度计算，加上90度来修正偏移
              -->
              <g
                class="countdown-arrow-group"
                :transform="`rotate(${arrowRotationAngle + 90} 60 60)`"
              >
                <!-- 箭头位于进度条圆环的外边缘（半径48的位置），指向圆心（向下） -->
                <!-- 路径：从顶部 (60, 12) 开始，形成向下指向圆心的三角形 -->
                <path
                  class="countdown-arrow"
                  d="M 60 12 L 57 20 L 63 20 Z"
                  fill="#060d47"
                  opacity="1"
                  stroke="#060d47"
                  stroke-width="0.5"
                />
              </g>
            </svg>
            <!-- 内层半透明圆圈 -->
            <div class="countdown-inner-circle"></div>
            <!-- 倒计时数字 -->
            <div class="countdown-number">{{ countdown }}</div>
          </div>
        </div>

        <!-- 订单详情 -->
        <div class="term-detail-info">
          <div class="term-detail-row">
            <span class="term-detail-label">{{ localeStore.t('direction') }}</span>
            <span class="term-detail-value" :class="termOrderDetail?.direction === 'UP' ? 'positive' : 'negative'">
              {{ localeStore.t('lookUp') }}{{ termOrderDetail?.direction === 'UP' ? (termOrderDetail?.baseCurrency || currentSymbolInfo?.baseCurrency || '') : (termOrderDetail?.quoteCurrency || currentSymbolInfo?.quoteCurrency || '') }}
            </span>
          </div>
          <div class="term-detail-row">
            <span class="term-detail-label">{{ localeStore.t('amount') }}</span>
            <span class="term-detail-value">{{ termOrderDetail && termOrderDetail.amount != null ? formatMoney(termOrderDetail.amount) : '0.00' }}</span>
          </div>
          <div class="term-detail-row">
            <span class="term-detail-label">{{ termOrderDetail && termOrderDetail.status === 'CLOSED' ? localeStore.t('profitLoss') : localeStore.t('estimatedProfitLoss') }}</span>
            <span class="term-detail-value" :class="{ 
              positive: estimatedProfit > 0, 
              negative: estimatedProfit < 0 
            }">
              {{ formatMoney(estimatedProfit) }}
            </span>
          </div>
          <div class="term-detail-row">
            <span class="term-detail-label">{{ localeStore.t('duration') }}</span>
            <span class="term-detail-value">{{ termOrderDetail?.duration || 0 }}s</span>
          </div>
          <div class="term-detail-row">
            <span class="term-detail-label">{{ localeStore.t('openTime') }}</span>
            <span class="term-detail-value">{{ formatDateTime(termOrderDetail?.openTime) }}</span>
          </div>
          <div v-if="termOrderDetail?.status === 'CLOSED'" class="term-detail-row">
            <span class="term-detail-label">{{ localeStore.t('closeTime') }}</span>
            <span class="term-detail-value">{{ formatDateTime(termOrderDetail?.closeTime) }}</span>
          </div>
        </div>
      </div>
    </div>

    <Tabbar />
    
    <!-- 成功弹窗 -->
    <SuccessModal 
      v-model:visible="showSuccessModal"
      :message="successMessage"
      @confirm="() => {}"
    />
    
    <!-- 页面提示消息 -->
    <div v-if="toastMessage" class="toast-message" :class="toastType">
      {{ toastMessage }}
    </div>
  </div>
</template>

<style scoped>
.trade-page {
  min-height: 100vh;
  background: #f5f7fb;
  display: flex;
  flex-direction: column;
  padding-bottom: 80px;
}

/* 顶部标签页 */
.top-tabs-container {
  padding: 12px 16px;
  background: #f5f5f5;
}

.top-tabs {
  display: flex;
  background: #fff;
  border-radius: 8px;
  padding: 4px;
  gap: 4px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.tab-btn {
  flex: 1;
  padding: 10px 0;
  border: none;
  background: transparent;
  font-size: 15px;
  font-weight: 600;
  color: #999;
  cursor: pointer;
  position: relative;
  border-radius: 6px;
  transition: all 0.3s ease;
}

.tab-btn.active {
  background: #85bd00;
  color: #fff;
}

.tab-btn:not(.active) {
  background: #f5f5f5;
  color: #999;
}

/* 交易对和价格 */
.symbol-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: #fff;
  position: relative;
  border-bottom: 1px solid #e0e0e0;
}

.symbol-selector-dropdown {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}

.symbol-name {
  font-size: 20px;
  font-weight: 700;
  color: #333;
}

.dropdown-icon {
  font-size: 10px;
  color: #666;
  transition: transform 0.2s;
}

.dropdown-icon.active {
  transform: rotate(180deg);
}

/* 币种选择侧边栏遮罩 */
.symbol-sidebar-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 999;
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

/* 币种选择侧边栏（从左侧弹出） */
.symbol-sidebar {
  position: fixed;
  top: 0;
  left: 0;
  width: 300px;
  height: 100vh;
  background: #fff;
  box-shadow: 2px 0 8px rgba(0, 0, 0, 0.15);
  z-index: 1000;
  transform: translateX(-100%);
  transition: transform 0.3s ease;
  display: flex;
  flex-direction: column;
}

.symbol-sidebar.active {
  transform: translateX(0);
}

.symbol-sidebar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #e0e0e0;
  background: #f9f9f9;
}

.symbol-sidebar-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.symbol-sidebar-close {
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  font-size: 24px;
  color: #666;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  transition: all 0.2s;
}

.symbol-sidebar-close:hover {
  background: #f0f0f0;
  color: #333;
}

.symbol-list {
  flex: 1;
  overflow-y: auto;
  padding: 0;
}

.symbol-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  cursor: pointer;
  transition: background-color 0.2s;
  border-bottom: 1px solid #f0f0f0;
}

.symbol-item:last-child {
  border-bottom: none;
}

.symbol-item:hover {
  background-color: #f5f5f5;
}

.symbol-item.active {
  background-color: #e8f5e9;
}

.symbol-item-name {
  font-size: 14px;
  font-weight: 500;
  color: #333;
}

.symbol-item-price {
  font-size: 14px;
  font-weight: 600;
}

.market-closed {
  color: #999;
  font-size: 14px;
  font-weight: 500;
}

.price-display {
  display: flex;
  align-items: center;
  gap: 8px;
}

.price-value {
  font-size: 24px;
  font-weight: 700;
  line-height: 1;
}

.price-change {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  font-weight: 600;
}

.change-icon {
  font-size: 12px;
}

/* 时间周期选择 */
.interval-selector {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #e0e0e0;
  overflow-x: auto;
}

.interval-btn {
  flex: 1;
  min-width: 50px;
  padding: 8px 12px;
  border: none;
  border-radius: 4px;
  background: #f5f5f5;
  font-size: 13px;
  font-weight: 600;
  color: #666;
  cursor: pointer;
  transition: all 0.2s;
}

.interval-btn.active {
  background: #85bd00;
  color: #fff;
}

.interval-btn:not(.active) {
  background: #f5f5f5;
  color: #666;
}

/* K线图区域 */
.chart-section {
  background: #fff;
  padding: 12px 16px;
  border-bottom: 1px solid #e0e0e0;
}

.kline-info {
  display: flex;
  gap: 20px;
  margin-bottom: 8px;
  font-size: 13px;
  color: #333;
  font-weight: 500;
}

.kline-info-item {
  font-weight: 500;
}

.chart-section {
  position: relative;
}

.chart-container {
  width: 100%;
  height: 420px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  overflow: hidden;
}

.low-price-indicator {
  position: absolute;
  bottom: 50px;
  left: 16px;
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #666;
  z-index: 10;
  pointer-events: none;
}

.low-price-arrow {
  color: #666;
  font-size: 14px;
}

.low-price-value {
  font-weight: 600;
}

/* 订单区域 */
.order-section {
  background: #fff;
  padding: 16px;
  margin-top: 8px;
}

.order-type-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.order-tab-btn {
  flex: 1;
  padding: 10px 0;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  background: #fff;
  font-size: 14px;
  font-weight: 600;
  color: #666;
  cursor: pointer;
  transition: all 0.2s;
}

.order-tab-btn.active {
  border-color: #73b100;
  background: #73b100;
  color: #fff;
}

.order-item {
  margin-bottom: 16px;
}

.order-item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.order-label {
  font-size: 14px;
  font-weight: 600;
  color: #333;
}

/* 开关样式 */
.toggle-switch {
  position: relative;
  display: inline-block;
  width: 44px;
  height: 24px;
}

.toggle-switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.toggle-slider {
  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: #ccc;
  transition: 0.3s;
  border-radius: 24px;
}

.toggle-slider:before {
  position: absolute;
  content: '';
  height: 18px;
  width: 18px;
  left: 3px;
  bottom: 3px;
  background-color: white;
  transition: 0.3s;
  border-radius: 50%;
}

.toggle-switch input:checked + .toggle-slider {
  background-color: #73b100;
}

.toggle-switch input:checked + .toggle-slider:before {
  transform: translateX(20px);
}

.order-input-group {
  display: flex;
  align-items: center;
  gap: 8px;
}

.input-btn {
  width: 36px;
  height: 36px;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  background: #fff;
  font-size: 18px;
  font-weight: 600;
  color: #666;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.input-btn:active {
  background: #f0f0f0;
}

.order-input {
  flex: 1;
  height: 36px;
  padding: 0 12px;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  background: #fff;
  font-size: 14px;
  text-align: center;
}

.order-price-input {
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  background: #fff;
  font-size: 14px;
  color: #333;
}

.order-price-input::placeholder {
  color: #999;
}

/* 交易详情 */
.trade-details {
  background: #f9f9f9;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 16px;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  font-size: 13px;
}

.detail-label {
  color: #666;
}

.detail-value {
  color: #333;
  font-weight: 600;
}

/* 买入/卖出按钮 */
.trade-buttons {
  display: flex;
  gap: 12px;
}

.buy-btn,
.sell-btn {
  flex: 1;
  height: 48px;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 700;
  color: #fff;
  cursor: pointer;
  transition: all 0.2s;
}

.buy-btn {
  background: #73b100;
}

.buy-btn:active:not(:disabled) {
  background: #5a8a00;
}

.buy-btn:disabled,
.sell-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
  opacity: 0.6;
}

.sell-btn {
  background: #ef5350;
}

.sell-btn:active:not(:disabled) {
  background: #d32f2f;
}

/* 页面提示消息 */
.toast-message {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 16px 24px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  z-index: 2000;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  animation: toastSlideIn 0.3s ease-out;
  max-width: 80%;
  text-align: center;
  word-wrap: break-word;
}

.toast-message.error {
  background: #ff4444;
  color: #fff;
}

.toast-message.success {
  background: #73b100;
  color: #fff;
}

@keyframes toastSlideIn {
  from {
    opacity: 0;
    transform: translate(-50%, -60%);
  }
  to {
    opacity: 1;
    transform: translate(-50%, -50%);
  }
}

/* 期限交易样式 */
.term-trade-section {
  padding: 20px 12px;
}

/* 期限统计信息 */
.term-statistics-wrapper {
  margin-bottom: 20px;
}

.term-statistics-container {
  position: relative;
}

.statistics-title {
  position: absolute;
  top: -8px;
  left: 12px;
  font-size: 16px;
  font-weight: 600;
  color: #333;
  z-index: 2;
  padding: 0 8px;
  background: #fff;
}

.term-statistics-box {
  background: rgba(161, 161, 161, 0.18);
  border-radius: 12px;
  padding: 16px;
  position: relative;
  width: 100%;
}

.statistics-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.stat-label {
  font-size: 14px;
  color: #666;
  font-weight: 500;
}

.stat-value {
  font-size: 16px;
  color: #333;
  font-weight: 600;
  line-height: 1.4;
}

.stat-value.close-value {
  color: #73b100;
}

.term-action-buttons {
  display: flex;
  gap: 12px;
  padding: 0 12px;
}

.term-action-btn {
  flex: 1;
  height: 60px;
  border: none;
  border-radius: 8px;
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  cursor: pointer;
  transition: all 0.3s;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}

.term-action-btn-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

.term-action-btn-text {
  font-size: 18px;
  font-weight: 700;
  line-height: 1.2;
}

.term-action-btn-multiplier {
  font-size: 12px;
  font-weight: 500;
  opacity: 0.9;
  line-height: 1;
}

.term-action-btn.buy-action {
  background: #73b100;
}

.term-action-btn.buy-action:active:not(:disabled) {
  background: #5a8a00;
  transform: scale(0.98);
}

.term-action-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
  opacity: 0.6;
}

.term-action-btn.sell-action {
  background: #ff4444;
}

.term-action-btn.sell-action:active:not(:disabled) {
  background: #d32f2f;
  transform: scale(0.98);
}

/* 期限订单弹窗 */
.term-order-modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  animation: fadeIn 0.3s ease-out;
}

.term-order-modal {
  position: relative;
  width: 100%;
  max-width: 400px;
  background: #fff;
  border-radius: 16px;
  padding: 24px;
  max-height: 90vh;
  overflow-y: auto;
  animation: slideUp 0.3s ease-out;
}

.term-modal-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #f0f0f0;
  border: none;
  font-size: 20px;
  color: #666;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s;
}

.term-modal-close:active {
  background: #e0e0e0;
  transform: scale(0.95);
}

.term-modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid #f0f0f0;
}

.term-modal-symbol {
  font-size: 18px;
  font-weight: 700;
  color: #333;
}

.term-modal-price {
  font-size: 18px;
  font-weight: 700;
}

.term-modal-item {
  margin-bottom: 20px;
}

.term-modal-label {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.amount-limit-hint {
  font-size: 12px;
  font-weight: 400;
  color: #999;
}

.direction-buttons {
  display: flex;
  gap: 12px;
}

.direction-btn {
  flex: 1;
  padding: 12px 20px;
  border: 2px solid #e0e0e0;
  border-radius: 8px;
  background: #fff;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s;
}

.direction-btn.buy-direction.active {
  background: #73b100;
  color: #fff;
  border-color: #73b100;
}

.direction-btn.sell-direction.active {
  background: #ff4444;
  color: #fff;
  border-color: #ff4444;
}

.direction-btn:not(.active) {
  color: #666;
}

.direction-btn:not(.active):hover {
  border-color: #999;
}

.duration-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}

.duration-btn {
  padding: 10px 12px;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  background: #f8f8f8;
  color: #73b100;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s;
}

.duration-btn.active {
  background: #73b100;
  color: #fff;
  border-color: #73b100;
}

.duration-btn:not(.active):hover {
  border-color: #73b100;
  background: #f0f0f0;
}

.term-amount-input-wrapper {
  position: relative;
  margin-top: 8px;
  margin-bottom: 12px;
}

.term-amount-input {
  width: 100%;
  height: 44px;
  padding: 0 40px 0 16px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  background: #fff;
  font-size: 16px;
  color: #333;
}

.term-amount-input::placeholder {
  color: #999;
}

.amount-arrow {
  position: absolute;
  right: 16px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 20px;
  color: #999;
  pointer-events: none;
}

.expected-return {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  padding: 12px;
  background: #f8f8f8;
  border-radius: 6px;
}

.expected-label {
  font-size: 14px;
  color: #666;
}

.expected-value {
  font-size: 16px;
  font-weight: 600;
  color: #73b100;
}

.term-balance-info {
  margin-top: 16px;
  padding: 12px;
  background: #f8f8f8;
  border-radius: 6px;
  font-size: 14px;
  color: #666;
  text-align: center;
}

.term-profit-rate {
  margin-top: 16px;
  padding: 0;
  font-size: 14px;
  text-align: center;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
}

.profit-rate-label {
  color: #666;
  font-weight: 500;
}

.profit-rate-value {
  color: #73b100;
  font-weight: 700;
  font-size: 16px;
}

.term-modal-action {
  margin-top: 24px;
}

.term-confirm-btn {
  width: 100%;
  padding: 16px;
  border: none;
  border-radius: 8px;
  font-size: 18px;
  font-weight: 700;
  color: #fff;
  cursor: pointer;
  transition: all 0.3s;
}

.term-confirm-btn.buy-direction {
  background: #73b100;
}

.term-confirm-btn.buy-direction:active:not(:disabled) {
  background: #5a8a00;
  transform: scale(0.98);
}

.term-confirm-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
  opacity: 0.6;
}

.term-confirm-btn.sell-direction {
  background: #ff4444;
}

.term-confirm-btn.sell-direction:active:not(:disabled) {
  background: #d32f2f;
  transform: scale(0.98);
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 订单详情弹窗样式 */
.term-order-detail-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
  padding: 20px;
}

.term-order-detail-modal {
  position: relative;
  width: 100%;
  max-width: 400px;
  background: #fff;
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
}

.term-detail-close {
  position: absolute;
  top: 16px;
  right: 16px;
  width: 32px;
  height: 32px;
  border: none;
  background: #f5f5f5;
  border-radius: 50%;
  font-size: 20px;
  color: #666;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.term-detail-close:hover {
  background: #e0e0e0;
  color: #333;
}

.term-detail-header {
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid #f0f0f0;
}

.term-detail-symbol {
  font-size: 20px;
  font-weight: 700;
  color: #333;
  margin-bottom: 8px;
}

.term-detail-price {
  font-size: 18px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}

.term-detail-price .arrow {
  color: #999;
}

.term-countdown-section {
  display: flex;
  justify-content: center;
  margin: 24px 0;
  padding: 20px 0;
}

.countdown-circle {
  position: relative;
  width: 140px;
  height: 140px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.countdown-svg {
  width: 100%;
  height: 100%;
}

/* 虚线圆环保持静态 */

.countdown-progress-bg {
  stroke: rgba(6, 13, 71, 0.1);
}

.countdown-progress {
  stroke: #060d47;
  stroke-linecap: round;
  /* 使用平滑的缓动函数，实现缓慢缓冲效果 */
  transition: stroke-dashoffset 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.countdown-arrow-group {
  /* SVG transform 的旋转中心在 SVG 坐标系中，使用 transform-origin 可能无效 */
  /* 直接使用 SVG transform 属性，在模板中设置 rotate(angle x y) */
  /* 不需要 CSS transform-origin，因为 SVG transform 已经指定了旋转中心 (60, 60) */
  /* 注意：SVG transform 属性不能直接用 CSS transition，需要通过 CSS 过渡配合 JavaScript 来实现 */
  /* 由于使用了 requestAnimationFrame 高频更新，这里保持无过渡，确保实时跟随 */
  /* 如果要实现平滑动画，需要在 JavaScript 中使用插值算法，而不是 CSS transition */
  transition: none;
  /* 如果需要平滑动画，可以尝试使用 CSS transition，但 SVG transform 的过渡效果可能不理想 */
  /* transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1); */
}

.countdown-arrow {
  filter: drop-shadow(0 1px 2px rgba(6, 13, 71, 0.3));
  transform-origin: 60px 12px;
}

.countdown-inner-circle {
  position: absolute;
  width: 85px;
  height: 85px;
  border-radius: 50%;
  background: rgba(6, 13, 71, 0.08);
  backdrop-filter: blur(2px);
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  z-index: 0;
}

.countdown-number {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  font-size: 38px;
  font-weight: 700;
  color: #060d47;
  z-index: 2;
  text-shadow: 0 1px 2px rgba(6, 13, 71, 0.2);
}

.term-detail-info {
  margin-top: 24px;
}

.term-detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid #f5f5f5;
}

.term-detail-row:last-child {
  border-bottom: none;
}

.term-detail-label {
  font-size: 14px;
  color: #666;
}

.term-detail-value {
  font-size: 14px;
  font-weight: 600;
  color: #333;
}

.term-detail-value.positive {
  color: #85bd00;
}

.term-detail-value.negative {
  color: #ef5350;
}

.leverage-value { font-weight: 700; color: #78aa00; font-variant-numeric: tabular-nums; }
.leverage-slider { display: block; width: 100%; height: 36px; margin: 4px 0; accent-color: #8cc63f; cursor: pointer; }
.leverage-presets { display: flex; flex-wrap: wrap; gap: 6px; }
.leverage-presets button { min-width: 44px; min-height: 44px; padding: 4px 8px; border: 1px solid #ddd; border-radius: 6px; background: #fff; color: #555; font-size: 12px; cursor: pointer; }
.leverage-presets button[aria-pressed="true"] { border-color: #78aa00; background: #eff7df; color: #4b7100; font-weight: 700; }
.leverage-selector :focus-visible { outline: 2px solid #78aa00; outline-offset: 3px; }
</style>
