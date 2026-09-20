<script setup lang="ts">
import { ref, computed, onMounted, watch, onUnmounted } from 'vue'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
import { calculateContractProfit, contractEquity } from '@/utils/contract'
import { useMarketStore } from '@/store/market'
import { useLocaleStore } from '@/store/locale'
import { formatDateTime } from '@/utils/dateTime'

const marketStore = useMarketStore()
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 主标签：合約 / 期限
const mainTab = ref<'contract' | 'term'>('contract')

// 子标签：持倉 / 掛單 / 歷史
const subTab = ref<'positions' | 'pending' | 'history'>('positions')

// 期限页面的子标签：交易中 / 已平倉
const termSubTab = ref<'trading' | 'closed'>('trading')

// 加载状态
const loading = ref(false)

// 合约订单数据
const positionsData = ref<any[]>([]) // 持倉（OPEN状态）
const pendingOrdersData = ref<any[]>([]) // 掛單（PENDING状态）
const historyData = ref<any[]>([]) // 歷史（CLOSED状态）

// 期限订单数据
const termTradingData = ref<any[]>([]) // 交易中（TRADING状态）
const termClosedData = ref<any[]>([]) // 已平倉（CLOSED状态）

// 统计信息
const contractBalance = ref(0) // 合约余额
const totalMargin = ref(0) // 总保证金
const riskRate = ref(0) // 风险率

// 期限选项列表（用于计算盈亏）
const durationOptions = ref<Array<{ value: number; profitRate: number; lossRate: number }>>([])

// 加载期限选项
async function loadDurationOptions() {
  try {
    const res: any = await request.get('/trade/option/durations')
    if (res && Array.isArray(res)) {
      durationOptions.value = res.map((item: any) => ({
        value: item.duration,
        profitRate: Number(item.profitRate || 0.8), // 盈利比例，默认0.8（80%）
        lossRate: Number(item.lossRate || 1.0), // 亏损比例，默认1.0（100%，全部亏损）
      }))
    }
  } catch (e) {
    console.error('加载期限选项失败:', e)
    // 使用默认值
    durationOptions.value = []
  }
}

// 订单详情弹窗相关
const showOrderDetailModal = ref(false) // 显示订单详情弹窗
const detailOrder = ref<any>(null) // 当前查看的订单
const closePrice = ref(0) // 平仓价格
const stopLossEnabled = ref(false) // 止损开关
const takeProfitEnabled = ref(false) // 止盈开关
const stopLossValue = ref(0) // 止损价格
const takeProfitValue = ref(0) // 止盈价格

// 获取实时价格
function getCurrentPrice(symbol: string): number {
  return marketStore.getPrice(symbol) || 0
}

// 转换合约订单数据格式
function transformContractOrder(order: any) {
  const currentPrice = getCurrentPrice(order.symbol)
  
  const leverage = Number(order.leverage ?? 1)
  const calculatedProfit = calculateContractProfit(order, currentPrice)

  // 对于挂单（PENDING），使用 createdAt 作为创建时间；对于持仓（OPEN），使用 openTime
  const displayTime = order.status === 'PENDING' 
    ? (order.createdAt || order.openTime) 
    : (order.openTime || order.createdAt)
  
  return {
    id: order.id,
    symbol: order.symbol,
    type: order.side?.toLowerCase() || 'buy', // BUY -> buy, SELL -> sell
    lots: Number(order.quantity || 0),
    openPrice: Number(order.openPrice || 0),
    price: Number(order.price || 0), // 限价单价格
    currentPrice: currentPrice > 0 ? currentPrice : Number(order.currentPrice || order.openPrice || 0),
    closePrice: Number(order.closePrice || 0),
    amount: Number(order.margin || 0),
    profit: calculatedProfit,
    margin: Number(order.margin || 0),
    fee: Number(order.fee || 0),
    openTime: formatDateTime(displayTime), // 使用创建时间或开仓时间
    closeTime: formatDateTime(order.closeTime),
    status: order.status, // 保留状态
    side: order.side, // 保留原始side用于计算
    quantity: order.quantity, // 保留原始quantity用于计算
    stopLoss: order.stopLoss ? Number(order.stopLoss) : null, // 止损
    takeProfit: order.takeProfit ? Number(order.takeProfit) : null, // 止盈
    leverage,
    lotSize: order.lotSize,
  }
}

// 计算期限订单盈亏（使用期限设置中的盈利比例和亏损比例）
function calculateOptionProfit(order: any, currentPrice: number): number {
  // 如果订单已平仓，使用已计算的盈亏
  if (order.status === 'CLOSED' && order.profit != null) {
    return Number(order.profit || 0)
  }
  
  // 如果订单交易中，根据当前价格计算盈亏
  if (order.status === 'TRADING' && order.openPrice && currentPrice > 0) {
    const priceDiff = currentPrice - order.openPrice
    const amount = Number(order.amount || 0)
    
    // 获取订单对应的期限设置的盈利比例和亏损比例（从后端加载的配置中查找）
    const orderDuration = Number(order.duration || 0)
    const durationOption = durationOptions.value.find(opt => opt.value === orderDuration)
    const profitRate = durationOption?.profitRate || 0.8 // 盈利比例，默认80%（从后端配置获取）
    const lossRate = durationOption?.lossRate || 1.0 // 亏损比例，默认100%（全部亏损，从后端配置获取）
    
    // 买涨：价格上涨盈利，价格下跌亏损
    // 买跌：价格下跌盈利，价格上涨亏损
    if (order.direction === 'UP') {
      if (priceDiff > 0) {
        // 价格上涨，盈利：使用盈利比例
        return amount * profitRate
      } else {
        // 价格下跌，亏损：使用亏损比例
        return -(amount * lossRate)
      }
    } else if (order.direction === 'DOWN') {
      if (priceDiff < 0) {
        // 价格下跌，盈利：使用盈利比例
        return amount * profitRate
      } else {
        // 价格上涨，亏损：使用亏损比例
        return -(amount * lossRate)
      }
    }
  }
  
  return Number(order.profit || 0)
}

// 存储所有交易对信息
const allSymbols = ref<any[]>([])

// 加载所有交易对信息
async function loadAllSymbols() {
  try {
    const res: any = await request.get('/trade/option/symbols')
    if (res && res.list && Array.isArray(res.list)) {
      allSymbols.value = res.list
    }
  } catch (e) {
    console.error('加载交易对信息失败:', e)
  }
}

// 解析交易对符号，获取基础货币和计价货币
function parseSymbol(symbol: string): { baseCurrency: string; quoteCurrency: string } {
  if (!symbol) {
    return { baseCurrency: '', quoteCurrency: '' }
  }
  
  // 从已加载的交易对列表中查找
  const symbolInfo = allSymbols.value.find((s: any) => s.symbol === symbol)
  if (symbolInfo) {
    return {
      baseCurrency: symbolInfo.baseCurrency || '',
      quoteCurrency: symbolInfo.quoteCurrency || '',
    }
  }
  
  // 如果没有找到，尝试从 symbol 字符串中解析
  // 假设格式为 BASEQUOTE，如 BTCUSD, ETHUSD
  const commonQuotes = ['USD', 'USDT', 'EUR', 'GBP', 'JPY', 'CNY']
  for (const quote of commonQuotes) {
    if (symbol.endsWith(quote)) {
      return {
        baseCurrency: symbol.slice(0, -quote.length),
        quoteCurrency: quote,
      }
    }
  }
  
  // 如果无法解析，返回空字符串
  return { baseCurrency: '', quoteCurrency: '' }
}

// 转换期限订单数据格式
function transformOptionOrder(order: any) {
  const currentPrice = getCurrentPrice(order.symbol)
  const calculatedProfit = calculateOptionProfit(order, currentPrice)
  const symbolInfo = parseSymbol(order.symbol)
  
  return {
    id: order.id,
    symbol: order.symbol,
    type: order.direction === 'UP' ? 'buy' : 'sell', // UP -> buy (绿色), DOWN -> sell (红色)
    openPrice: Number(order.openPrice || 0),
    currentPrice: currentPrice > 0 ? currentPrice : Number(order.closePrice || order.openPrice || 0),
    closePrice: Number(order.closePrice || 0),
    amount: Number(order.amount || 0),
    profit: calculatedProfit,
    duration: order.duration || 0,
    openTime: formatDateTime(order.openTime),
    openTimeRaw: order.openTime, // 保留原始时间用于计算倒计时
    closeTime: formatDateTime(order.closeTime),
    direction: order.direction, // 保留原始direction用于计算
    status: order.status, // 保留状态用于计算
    baseCurrency: symbolInfo.baseCurrency, // 基础货币
    quoteCurrency: symbolInfo.quoteCurrency, // 计价货币
  }
}

// 计算订单剩余倒计时（秒）
function calculateCountdown(order: any): number {
  if (!order || order.status !== 'TRADING' || !order.openTimeRaw || !order.duration) {
    return 0
  }
  
  try {
    const openTime = new Date(order.openTimeRaw).getTime()
    const now = Date.now()
    const elapsed = Math.floor((now - openTime) / 1000) // 已过秒数
    const remaining = Math.max(0, order.duration - elapsed)
    return remaining
  } catch (e) {
    console.error(localeStore.t('calculateCountdownFailed'), e)
    return 0
  }
}

// 自动结算倒计时结束的订单
async function autoCloseExpiredOrders() {
  if (mainTab.value !== 'term' || termSubTab.value !== 'trading') {
    return
  }
  
  const expiredOrders = termTradingData.value.filter((order: any) => {
    if (order.status !== 'TRADING') return false
    const countdown = calculateCountdown(order)
    return countdown <= 0
  })
  
  if (expiredOrders.length === 0) {
    return
  }
  
  console.log('发现过期订单，开始自动结算:', expiredOrders.length)
  
  for (const order of expiredOrders) {
    try {
      console.log('自动结算过期订单:', order.id)
      const currentPrice = getCurrentPrice(order.symbol)
      if (currentPrice <= 0) {
        console.warn('无法获取当前价格，跳过自动结算:', order.symbol)
        continue
      }
      
      const res: any = await request.post(`/trade/option/order/${order.id}/close`, {
        closePrice: currentPrice
      })
      
      if (res.success || res.success === undefined) {
        console.log('订单自动结算成功:', order.id)
        // 重新加载订单列表
        await loadOptionOrders()
      } else {
        console.error('订单自动结算失败:', order.id, res.message)
      }
    } catch (e: any) {
      console.error('自动结算订单异常:', order.id, e)
    }
  }
}

// 加载合约订单
async function loadContractOrders() {
  if (loading.value) return
  loading.value = true
  try {
    // 根据子标签加载不同状态的订单
    let status: string | null = null
    if (subTab.value === 'positions') {
      status = 'OPEN' // 持倉
    } else if (subTab.value === 'pending') {
      status = 'PENDING' // 掛單
    } else if (subTab.value === 'history') {
      status = 'CLOSED' // 歷史
    }

    const res: any = await request.get('/trade/contract/orders', {
      params: status ? { status } : {},
    })

    const orders = (res.list || []).map(transformContractOrder)

    if (subTab.value === 'positions') {
      positionsData.value = orders
      // 计算总保证金
      totalMargin.value = orders.reduce((sum: number, item: any) => sum + item.margin, 0)
    } else if (subTab.value === 'pending') {
      pendingOrdersData.value = orders
      // 计算总保证金（挂单也需要冻结保证金）
      totalMargin.value = orders.reduce((sum: number, item: any) => sum + item.margin, 0)
      // 同时加载持仓订单，用于统计信息显示
      await loadPositionsForSummary()
    } else if (subTab.value === 'history') {
      historyData.value = orders
      totalMargin.value = 0
    }

    // 加载合约余额
    await loadContractBalance()
  } catch (e: any) {
    console.error(localeStore.t('loadContractOrdersFailed'), e)
  } finally {
    loading.value = false
  }
}

// 加载期限订单
async function loadOptionOrders() {
  if (loading.value) return
  loading.value = true
  try {
    // 根据子标签加载不同状态的订单
    let status: string | null = null
    if (termSubTab.value === 'trading') {
      status = 'TRADING' // 交易中
    } else if (termSubTab.value === 'closed') {
      status = 'CLOSED' // 已平倉
    }

    const res: any = await request.get('/trade/option/orders', {
      params: status ? { status } : {},
    })

    const orders = (res.list || []).map(transformOptionOrder)

    if (termSubTab.value === 'trading') {
      termTradingData.value = orders
    } else {
      termClosedData.value = orders
    }
  } catch (e: any) {
    console.error(localeStore.t('loadOptionOrdersFailed'), e)
  } finally {
    loading.value = false
  }
}

// 加载合约余额
async function loadContractBalance() {
  try {
    const res: any = await request.get('/trade/contract/balance')
    if (res && res.success !== false) {
      contractBalance.value = Number(res.balance || res.available || 0)
      // 只在持仓页面更新风险率，挂单页面的风险率通过 computed 属性自动更新
      if (mainTab.value === 'contract' && subTab.value === 'positions') {
        updateRiskRate()
      }
    }
  } catch (e: any) {
    console.error(localeStore.t('loadContractBalanceFailed'), e)
  }
}

// 更新风险率
function updateRiskRate() {
  const totalMarginValue = totalMargin.value
  if (totalMarginValue > 0) {
    riskRate.value = (contractEquity(contractBalance.value, positionsData.value) / totalMarginValue) * 100
  } else {
    riskRate.value = 0
  }
}

// 加载持仓订单用于统计信息（挂单页面使用）
async function loadPositionsForSummary() {
  try {
    const res: any = await request.get('/trade/contract/orders', {
      params: { status: 'OPEN' },
    })
    const orders = (res.list || []).map(transformContractOrder)
    positionsData.value = orders
  } catch (e: any) {
    console.error(localeStore.t('loadPositionsFailed'), e)
  }
}

// 计算持仓订单的总盈亏
const positionsTotalProfit = computed(() => {
  return positionsData.value.reduce((sum, item) => sum + (item.profit || 0), 0)
})

// 计算持仓订单的总保证金
const positionsTotalMargin = computed(() => {
  return positionsData.value.reduce((sum, item) => sum + (item.margin || 0), 0)
})

// 计算持仓订单的风险率
const positionsRiskRate = computed(() => {
  const totalMarginValue = positionsTotalMargin.value
  if (totalMarginValue > 0) {
    return (contractEquity(contractBalance.value, positionsData.value) / totalMarginValue) * 100
  } else {
    return 0
  }
})

// 打开订单详情弹窗
function openOrderDetailModal(order: any) {
  detailOrder.value = order
  closePrice.value = order.currentPrice || order.openPrice || 0
  
  // 初始化止盈止损
  stopLossEnabled.value = order.stopLoss != null && order.stopLoss > 0
  takeProfitEnabled.value = order.takeProfit != null && order.takeProfit > 0
  stopLossValue.value = order.stopLoss || 0
  takeProfitValue.value = order.takeProfit || 0
  
  // 如果开关打开但值为0，设置为当前价格
  if (stopLossEnabled.value && stopLossValue.value === 0 && order.currentPrice > 0) {
    stopLossValue.value = order.currentPrice
  }
  if (takeProfitEnabled.value && takeProfitValue.value === 0 && order.currentPrice > 0) {
    takeProfitValue.value = order.currentPrice
  }
  
  showOrderDetailModal.value = true
}

// 关闭订单详情弹窗
function closeOrderDetailModal() {
  showOrderDetailModal.value = false
  detailOrder.value = null
  closePrice.value = 0
  stopLossEnabled.value = false
  takeProfitEnabled.value = false
  stopLossValue.value = 0
  takeProfitValue.value = 0
}

// 调整止损
function adjustStopLoss(delta: number) {
  if (!stopLossEnabled.value) return
  const step = 1
  stopLossValue.value = Math.max(0, stopLossValue.value + delta * step)
}

// 调整止盈
function adjustTakeProfit(delta: number) {
  if (!takeProfitEnabled.value) return
  const step = 1
  takeProfitValue.value = Math.max(0, takeProfitValue.value + delta * step)
}

// 监听止损开关
watch(stopLossEnabled, (enabled) => {
  if (enabled && detailOrder.value?.currentPrice > 0) {
    if (stopLossValue.value === 0) {
      stopLossValue.value = detailOrder.value.currentPrice
    }
  } else if (!enabled) {
    stopLossValue.value = 0
  }
})

// 监听止盈开关
watch(takeProfitEnabled, (enabled) => {
  if (enabled && detailOrder.value?.currentPrice > 0) {
    if (takeProfitValue.value === 0) {
      takeProfitValue.value = detailOrder.value.currentPrice
    }
  } else if (!enabled) {
    takeProfitValue.value = 0
  }
})

// 修改TP/SL
async function handleUpdateTPSL() {
  if (!detailOrder.value) return
  
  try {
    const res: any = await request.post(`/trade/contract/order/${detailOrder.value.id}/update-tp-sl`, {
      stopLoss: stopLossEnabled.value ? stopLossValue.value : null,
      takeProfit: takeProfitEnabled.value ? takeProfitValue.value : null
    })
    
    // 检查响应，success为true或不存在success字段都视为成功
    if (res && (res.success === true || res.success === undefined)) {
      // 重新加载订单列表
      await loadContractOrders()
      // 更新当前订单数据
      detailOrder.value.stopLoss = stopLossEnabled.value ? stopLossValue.value : null
      detailOrder.value.takeProfit = takeProfitEnabled.value ? takeProfitValue.value : null
      
      // 显示成功提示
      showToastMessage(localeStore.t('modifySuccess'), 'success')
    } else {
      throw new Error(res?.message || localeStore.t('modifyFailed'))
    }
  } catch (e: any) {
    console.error('修改失败:', e)
    showToastMessage(e?.response?.data?.message || e?.message || localeStore.t('modifyFailed'), 'error')
  }
}

// 显示提示消息
function showToastMessage(message: string, type: 'success' | 'error' = 'error') {
  // 移除之前的提示
  const existingToasts = document.querySelectorAll('.toast-message')
  existingToasts.forEach(toast => {
    if (document.body.contains(toast)) {
      document.body.removeChild(toast)
    }
  })
  
  // 创建新的提示
  const toast = document.createElement('div')
  toast.className = `toast-message ${type}`
  toast.textContent = message
  
  // 直接设置内联样式，确保样式生效
  toast.style.cssText = `
    position: fixed !important;
    top: 50% !important;
    left: 50% !important;
    transform: translate(-50%, -50%) !important;
    padding: 18px 36px !important;
    border-radius: 12px !important;
    font-size: 18px !important;
    font-weight: 600 !important;
    z-index: 99999 !important;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3) !important;
    pointer-events: none !important;
    white-space: nowrap !important;
    min-width: 200px !important;
    text-align: center !important;
    opacity: 0 !important;
    transition: opacity 0.3s ease-in-out !important;
    ${type === 'success' ? 'background: #73b100 !important; color: #fff !important;' : 'background: #ff4444 !important; color: #fff !important;'}
  `
  
  document.body.appendChild(toast)
  
  // 强制重排，确保样式应用
  void toast.offsetHeight
  
  // 触发动画 - 使用双重 requestAnimationFrame 确保动画执行
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      toast.style.opacity = '1'
    })
  })
  
  // 自动移除
  setTimeout(() => {
    toast.style.opacity = '0'
    setTimeout(() => {
      if (document.body.contains(toast)) {
        document.body.removeChild(toast)
      }
    }, 300)
  }, 3000) // 显示3秒
}

// 执行平仓
async function handleCloseOrder() {
  if (!detailOrder.value) return
  
  try {
    const res: any = await request.post(`/trade/contract/order/${detailOrder.value.id}/close`, {
      closePrice: closePrice.value
    })
    
    console.log('平仓响应:', res) // 调试日志
    
    // 检查响应，success为true或不存在success字段都视为成功
    if (res && (res.success === true || res.success === undefined || res.success === null)) {
      // 先关闭弹窗
      closeOrderDetailModal()
      
      // 立即显示成功提示（不延迟，确保用户能看到）
      showToastMessage(localeStore.t('closeOrderSuccess'), 'success')
      
      // 重新加载订单列表
      await loadContractOrders()
    } else {
      throw new Error(res?.message || res?.error || localeStore.t('closeOrderFailed'))
    }
  } catch (e: any) {
    console.error('平仓失败:', e)
    // 关闭弹窗后再显示错误提示
    closeOrderDetailModal()
    // 立即显示错误提示
    showToastMessage(e?.response?.data?.message || e?.message || localeStore.t('closeOrderFailed'), 'error')
  }
}

// 执行撤单
async function handleCancelOrder() {
  if (!detailOrder.value) return
  
  try {
    const res: any = await request.post(`/trade/contract/order/${detailOrder.value.id}/cancel`)
    
    // 检查响应，success为true或不存在success字段都视为成功
    if (res && (res.success === true || res.success === undefined)) {
      // 先关闭弹窗
      closeOrderDetailModal()
      // 延迟显示提示，确保弹窗完全关闭后再显示
      setTimeout(() => {
        showToastMessage(localeStore.t('cancelOrderSuccess'), 'success')
      }, 300)
      // 重新加载订单列表
      await loadContractOrders()
    } else {
      throw new Error(res?.message || localeStore.t('cancelOrderFailed'))
    }
  } catch (e: any) {
    console.error('撤单失败:', e)
    // 关闭弹窗后再显示错误提示
    closeOrderDetailModal()
    setTimeout(() => {
      showToastMessage(e?.response?.data?.message || e?.message || localeStore.t('cancelOrderFailed'), 'error')
    }, 300)
  }
}

// 更新订单的实时价格和盈亏
function updateOrdersWithRealTimePrice() {
  // 更新合约持倉订单（持仓页面和挂单页面都需要更新，因为挂单页面显示持仓的统计信息）
  if (mainTab.value === 'contract' && (subTab.value === 'positions' || subTab.value === 'pending')) {
    positionsData.value = positionsData.value.map((order: any) => {
      const currentPrice = getCurrentPrice(order.symbol)
      if (currentPrice > 0 && order.openPrice > 0) {
        // 使用保留的原始字段进行计算
        const side = order.side || (order.type === 'buy' ? 'BUY' : 'SELL')
        const quantity = order.quantity || order.lots
        
        const calculatedProfit = calculateContractProfit({ ...order, side, quantity }, currentPrice)
        const updatedOrder = {
          ...order,
          currentPrice,
          profit: calculatedProfit,
        }
        
        // 如果弹窗打开且是当前订单，同步更新弹窗中的订单数据
        if (showOrderDetailModal.value && detailOrder.value && detailOrder.value.id === order.id) {
          detailOrder.value.currentPrice = currentPrice
          detailOrder.value.profit = calculatedProfit
        }
        
        return updatedOrder
      }
      return order
    })
  }
  
  // 更新合约挂单订单（仅更新当前价格）
  if (mainTab.value === 'contract' && subTab.value === 'pending') {
    pendingOrdersData.value = pendingOrdersData.value.map((order: any) => {
      const currentPrice = getCurrentPrice(order.symbol)
      if (currentPrice > 0) {
        const updatedOrder = {
          ...order,
          currentPrice,
        }
        
        // 如果弹窗打开且是当前订单，同步更新弹窗中的订单数据
        if (showOrderDetailModal.value && detailOrder.value && detailOrder.value.id === order.id) {
          detailOrder.value.currentPrice = currentPrice
        }
        
        return updatedOrder
      }
      return order
    })
  }
  
  // 更新期限交易中订单
  if (mainTab.value === 'term' && termSubTab.value === 'trading') {
    termTradingData.value = termTradingData.value.map((order: any) => {
      const currentPrice = getCurrentPrice(order.symbol)
      if (currentPrice > 0 && order.openPrice > 0) {
        // 使用保留的原始字段进行计算，确保包含 duration 字段以便正确查找期限配置
        const direction = order.direction || (order.type === 'buy' ? 'UP' : 'DOWN')
        const calculatedProfit = calculateOptionProfit({
          direction,
          amount: order.amount,
          openPrice: order.openPrice,
          profit: order.profit,
          status: 'TRADING',
          duration: order.duration, // 必须包含 duration 字段，用于查找对应的盈利比例和亏损比例
        }, currentPrice)
        const updatedOrder = {
          ...order,
          currentPrice,
          profit: calculatedProfit,
        }
        
        // 如果弹窗打开且是当前订单，同步更新弹窗中的订单数据
        if (showOrderDetailModal.value && detailOrder.value && detailOrder.value.id === order.id) {
          detailOrder.value.currentPrice = currentPrice
          detailOrder.value.profit = calculatedProfit
        }
        
        return updatedOrder
      }
      return order
    })
    
    // 检查并自动结算倒计时结束的订单
    autoCloseExpiredOrders()
  }
  
  // 更新风险率（持仓页面）
  if (mainTab.value === 'contract' && subTab.value === 'positions') {
    updateRiskRate()
  }
  // 挂单页面的风险率通过 computed 属性自动更新，不需要手动调用
}

// 计算总盈亏
const totalProfit = computed(() => {
  if (mainTab.value === 'contract') {
    if (subTab.value === 'positions') {
      return positionsData.value.reduce((sum, item) => sum + (item.profit || 0), 0)
    } else if (subTab.value === 'history') {
      return historyData.value.reduce((sum, item) => sum + (item.profit || 0), 0)
    }
  } else {
    if (termSubTab.value === 'trading') {
      return termTradingData.value.reduce((sum, item) => sum + (item.profit || 0), 0)
    } else {
      return termClosedData.value.reduce((sum, item) => sum + (item.profit || 0), 0)
    }
  }
  return 0
})

// 监听标签页切换
watch([mainTab, subTab, termSubTab], () => {
  if (mainTab.value === 'contract') {
    loadContractOrders()
  } else {
    loadOptionOrders()
  }
}, { immediate: false })

// 定时更新实时价格和盈亏
let priceUpdateInterval: number | null = null
let balanceUpdateInterval: number | null = null

onMounted(async () => {
  // 加载期限选项（用于计算盈亏）
  await loadDurationOptions()
  // 加载所有交易对信息（用于解析基础货币和计价货币）
  await loadAllSymbols()
  // 初始化WebSocket连接（确保能获取实时价格）
      await marketStore.initMarketService('Crypto')
  
  // 初始加载
  if (mainTab.value === 'contract') {
    await loadContractOrders()
  } else {
    await loadOptionOrders()
  }
  
  // 每2秒更新一次实时价格和盈亏
  priceUpdateInterval = window.setInterval(() => {
    updateOrdersWithRealTimePrice()
  }, 2000)
})

onUnmounted(() => {
  if (priceUpdateInterval) {
    clearInterval(priceUpdateInterval)
    priceUpdateInterval = null
  }
  if (balanceUpdateInterval) {
    clearInterval(balanceUpdateInterval)
    balanceUpdateInterval = null
  }
})

// 格式化金额
function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 格式化价格
function formatPrice(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<template>
  <div class="orders-page">
    <!-- 顶部主标签 -->
    <div class="main-tabs">
      <div 
        class="main-tab" 
        :class="{ active: mainTab === 'contract' }"
        @click="mainTab = 'contract'"
      >
        {{ localeStore.t('contract') }}
      </div>
      <div 
        class="main-tab" 
        :class="{ active: mainTab === 'term' }"
        @click="mainTab = 'term'"
      >
        {{ localeStore.t('term') }}
      </div>
    </div>

    <!-- 子标签 -->
    <div class="sub-tabs" v-if="mainTab === 'contract'">
      <div 
        class="sub-tab" 
        :class="{ active: subTab === 'positions' }"
        @click="subTab = 'positions'"
      >
        {{ localeStore.t('positions') }}
      </div>
      <div 
        class="sub-tab" 
        :class="{ active: subTab === 'pending' }"
        @click="subTab = 'pending'"
      >
        {{ localeStore.t('pendingOrders') }}
      </div>
      <div 
        class="sub-tab" 
        :class="{ active: subTab === 'history' }"
        @click="subTab = 'history'"
      >
        {{ localeStore.t('orderHistory') }}
      </div>
    </div>

    <div class="sub-tabs" v-else>
      <div 
        class="sub-tab" 
        :class="{ active: termSubTab === 'trading' }"
        @click="termSubTab = 'trading'"
      >
        {{ localeStore.t('trading') }}
      </div>
      <div 
        class="sub-tab" 
        :class="{ active: termSubTab === 'closed' }"
        @click="termSubTab = 'closed'"
      >
        {{ localeStore.t('closed') }}
      </div>
    </div>

    <!-- 合約 - 持倉 -->
    <template v-if="mainTab === 'contract' && subTab === 'positions'">
      <!-- 统计信息 -->
      <div class="summary-card">
        <div class="summary-item">
          <div class="summary-label">{{ localeStore.t('profitLoss') }}</div>
          <div class="summary-value" :class="{ 
            negative: totalProfit < 0, 
            positive: totalProfit > 0 
          }">
            {{ formatMoney(totalProfit) }}
          </div>
        </div>
        <div class="summary-item">
          <div class="summary-label">{{ localeStore.t('balance') }}</div>
          <div class="summary-value">{{ formatMoney(contractBalance) }}</div>
        </div>
        <div class="summary-item">
          <div class="summary-label">{{ localeStore.t('currentMargin') }}</div>
          <div class="summary-value">{{ formatMoney(totalMargin) }}</div>
        </div>
        <div class="summary-item">
          <div class="summary-label">{{ localeStore.t('riskRate') }}</div>
          <div class="summary-value">{{ formatMoney(riskRate) }}%</div>
        </div>
      </div>

      <!-- 订单列表 -->
      <div class="orders-list" v-if="!loading">
        <div 
          v-for="order in positionsData" 
          :key="order.id"
          class="order-card"
          @click="openOrderDetailModal(order)"
        >
          <div class="order-header">
            <div class="order-symbol">{{ order.symbol }}</div>
            <div class="order-price">
              <span>{{ formatPrice(order.openPrice) }}</span>
              <span class="arrow">→</span>
              <span :class="{ 
                positive: order.currentPrice > order.openPrice, 
                negative: order.currentPrice < order.openPrice 
              }">
                {{ formatPrice(order.currentPrice) }}
              </span>
            </div>
          </div>
          <div class="order-body">
            <div class="order-type-badge" :class="order.type === 'buy' ? 'buy' : 'sell'">
              {{ order.type === 'buy' ? localeStore.t('buy') : localeStore.t('sell') }} {{ order.lots }}{{ localeStore.t('lots') }} · {{ order.leverage }}×
            </div>
            <div class="order-details">
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('orderId') }}</span>
                <span class="detail-value">#{{ order.id }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('openTime') }}</span>
                <span class="detail-value">{{ order.openTime }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('profitLoss') }}</span>
                <span class="detail-value" :class="{ 
                  negative: order.profit < 0, 
                  positive: order.profit > 0 
                }">
                  {{ formatMoney(order.profit) }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 合約 - 掛單 -->
    <template v-if="mainTab === 'contract' && subTab === 'pending'">
      <!-- 统计信息（使用持仓页面的数据） -->
      <div class="summary-card">
        <div class="summary-item">
          <div class="summary-label">{{ localeStore.t('profitLoss') }}</div>
          <div class="summary-value" :class="{ 
            negative: positionsTotalProfit < 0, 
            positive: positionsTotalProfit > 0 
          }">
            {{ formatMoney(positionsTotalProfit) }}
          </div>
        </div>
        <div class="summary-item">
          <div class="summary-label">{{ localeStore.t('balance') }}</div>
          <div class="summary-value">{{ formatMoney(contractBalance) }}</div>
        </div>
        <div class="summary-item">
          <div class="summary-label">{{ localeStore.t('currentMargin') }}</div>
          <div class="summary-value">{{ formatMoney(positionsTotalMargin) }}</div>
        </div>
        <div class="summary-item">
          <div class="summary-label">{{ localeStore.t('riskRate') }}</div>
          <div class="summary-value">{{ formatMoney(positionsRiskRate) }}%</div>
        </div>
      </div>

      <!-- 加载状态 -->
      <div class="empty-state" v-if="loading">
        <div class="empty-icon">⏳</div>
        <div class="empty-text">{{ localeStore.t('loading') }}</div>
      </div>
      <!-- 空状态 -->
      <div class="empty-state" v-else-if="pendingOrdersData.length === 0">
        <div class="empty-icon">📄</div>
        <div class="empty-text">{{ localeStore.t('noData') }}</div>
      </div>
      <!-- 订单列表 -->
      <div class="orders-list" v-else>
        <div 
          v-for="order in pendingOrdersData" 
          :key="order.id"
          class="order-card"
          @click="openOrderDetailModal(order)"
        >
          <div class="order-header">
            <div class="order-symbol">{{ order.symbol }}</div>
            <div class="order-price">
              <span>{{ formatPrice(order.price || order.openPrice) }}</span>
              <span class="arrow">→</span>
              <span :class="{ 
                positive: order.currentPrice > (order.price || order.openPrice), 
                negative: order.currentPrice < (order.price || order.openPrice) 
              }">
                {{ formatPrice(order.currentPrice) }}
              </span>
            </div>
          </div>
          <div class="order-body">
            <div class="order-type-badge" :class="order.type === 'buy' ? 'buy' : 'sell'">
              {{ order.type === 'buy' ? localeStore.t('buy') : localeStore.t('sell') }} {{ order.lots }}{{ localeStore.t('lots') }} · {{ order.leverage }}×
            </div>
            <div class="order-details">
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('orderId') }}</span>
                <span class="detail-value">#{{ order.id }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('createTime') }}</span>
                <span class="detail-value">{{ order.openTime }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 合約 - 歷史 -->
    <template v-if="mainTab === 'contract' && subTab === 'history'">
      <!-- 统计信息 -->
      <div class="summary-card">
        <div class="summary-item">
          <div class="summary-label">{{ localeStore.t('totalProfitLoss') }}</div>
          <div class="summary-value" :class="{ 
            negative: totalProfit < 0, 
            positive: totalProfit > 0 
          }">
            {{ formatMoney(totalProfit) }}
          </div>
        </div>
        <div class="summary-item">
          <div class="summary-label">{{ localeStore.t('balance') }}</div>
          <div class="summary-value">{{ formatMoney(contractBalance) }}</div>
        </div>
      </div>

      <!-- 加载状态 -->
      <div class="empty-state" v-if="loading">
        <div class="empty-icon">⏳</div>
        <div class="empty-text">{{ localeStore.t('loading') }}</div>
      </div>
      <!-- 订单列表 -->
      <div class="orders-list" v-else>
        <div 
          v-for="order in historyData" 
          :key="order.id"
          class="order-card"
        >
          <div class="order-header">
            <div class="order-symbol">{{ order.symbol }}</div>
            <div class="order-price">
              <span>{{ formatPrice(order.openPrice) }}</span>
              <span class="arrow">→</span>
              <span>{{ formatPrice(order.closePrice) }}</span>
            </div>
          </div>
          <div class="order-body">
            <div class="order-type-badge" :class="order.type === 'buy' ? 'buy' : 'sell'">
              {{ order.type === 'buy' ? '買入' : '賣出' }} {{ order.lots }} 手數 · {{ order.leverage }}×
            </div>
            <div class="order-details">
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('orderId') }}</span>
                <span class="detail-value">#{{ order.id }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('openTime') }}</span>
                <span class="detail-value">{{ order.openTime }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('profitLoss') }}</span>
                <span class="detail-value" :class="{ 
                  negative: order.profit < 0, 
                  positive: order.profit > 0 
                }">
                  {{ formatMoney(order.profit) }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 期限 - 交易中 -->
    <template v-if="mainTab === 'term' && termSubTab === 'trading'">
      <!-- 加载状态 -->
      <div class="empty-state" v-if="loading">
        <div class="empty-icon">⏳</div>
        <div class="empty-text">{{ localeStore.t('loading') }}</div>
      </div>
      <!-- 订单列表 -->
      <div class="orders-list" v-else>
        <div 
          v-for="order in termTradingData" 
          :key="order.id"
          class="order-card"
        >
          <div class="order-header">
            <div class="order-symbol">{{ order.symbol }}</div>
            <div class="order-price">
              <span>{{ formatPrice(order.openPrice) }}</span>
              <span class="arrow">→</span>
              <span :class="{ 
                positive: order.currentPrice > order.openPrice, 
                negative: order.currentPrice < order.openPrice 
              }">
                {{ formatPrice(order.currentPrice) }}
              </span>
            </div>
          </div>
          <div class="order-body">
            <div class="order-type-badge" :class="order.type === 'buy' ? 'buy' : 'sell'">
              {{ localeStore.t('lookUp') }}{{ order.direction === 'UP' ? (order.baseCurrency || '') : (order.quoteCurrency || '') }}
            </div>
            <div class="order-details">
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('amount') }}</span>
                <span class="detail-value">{{ formatMoney(order.amount) }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('estimatedProfitLoss') }}</span>
                <span class="detail-value" :class="{ 
                  negative: order.profit < 0, 
                  positive: order.profit > 0 
                }">
                  {{ formatMoney(order.profit) }}
                </span>
              </div>
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('duration') }}</span>
                <span class="detail-value" :class="{ 'countdown-warning': order.status === 'TRADING' && calculateCountdown(order) <= 10 }">
                  <template v-if="order.status === 'TRADING'">
                    {{ calculateCountdown(order) }}s / {{ order.duration }}s
                  </template>
                  <template v-else>
                    {{ order.duration }}s
                  </template>
                </span>
              </div>
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('openTime') }}</span>
                <span class="detail-value">{{ order.openTime }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 期限 - 已平倉 -->
    <template v-if="mainTab === 'term' && termSubTab === 'closed'">
      <!-- 加载状态 -->
      <div class="empty-state" v-if="loading">
        <div class="empty-icon">⏳</div>
        <div class="empty-text">{{ localeStore.t('loading') }}</div>
      </div>
      <!-- 订单列表 -->
      <div class="orders-list" v-else>
        <div 
          v-for="order in termClosedData" 
          :key="order.id"
          class="order-card"
        >
          <div class="order-header">
            <div class="order-symbol">{{ order.symbol }}</div>
            <div class="order-price">
              <span>{{ formatPrice(order.openPrice) }}</span>
              <span class="arrow">→</span>
              <span>{{ formatPrice(order.closePrice) }}</span>
            </div>
          </div>
          <div class="order-body">
            <div class="order-type-badge" :class="order.type === 'buy' ? 'buy' : 'sell'">
              {{ localeStore.t('lookUp') }}:{{ order.direction === 'UP' ? (order.baseCurrency || '') : (order.quoteCurrency || '') }}
            </div>
            <div class="order-details">
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('amount') }}</span>
                <span class="detail-value">{{ formatMoney(order.amount) }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('profitLoss') }}</span>
                <span class="detail-value" :class="{ 
                  negative: order.profit < 0, 
                  positive: order.profit > 0 
                }">
                  {{ formatMoney(order.profit) }}
                </span>
              </div>
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('duration') }}</span>
                <span class="detail-value">{{ order.duration }}</span>
              </div>
              <div class="detail-item">
                <span class="detail-label">{{ localeStore.t('openTime') }}</span>
                <span class="detail-value">{{ order.openTime }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 订单详情弹窗 -->
    <div 
      v-if="showOrderDetailModal" 
      class="order-detail-modal-overlay"
      @click.self="closeOrderDetailModal"
    >
      <div class="order-detail-modal">
        <button class="order-detail-modal-close" @click="closeOrderDetailModal">×</button>
        <div class="order-detail-header">{{ localeStore.t('orderDetails') }}</div>
        
        <div class="order-detail-content">
          <!-- 订单基本信息 -->
          <div class="order-detail-basic">
            <div class="order-detail-symbol">{{ detailOrder?.symbol }}</div>
            <div class="order-detail-id-time">
              <span>訂單 ID #{{ detailOrder?.id }}</span>
              <span>{{ detailOrder?.openTime }}</span>
            </div>
          </div>

          <!-- 价格信息 -->
          <div class="order-detail-price">
            <!-- 挂单：显示限价 → 当前价 -->
            <template v-if="detailOrder?.status === 'PENDING'">
              <span>{{ formatPrice(detailOrder?.price || detailOrder?.openPrice) }}</span>
              <span class="arrow">→</span>
              <span class="positive">{{ formatPrice(detailOrder?.currentPrice) }}</span>
            </template>
            <!-- 持仓：显示开仓价 → 当前价 -->
            <template v-else>
              <span>{{ formatPrice(detailOrder?.openPrice) }}</span>
              <span class="arrow">→</span>
              <span :class="{ 
                positive: detailOrder?.currentPrice > detailOrder?.openPrice, 
                negative: detailOrder?.currentPrice < detailOrder?.openPrice 
              }">
                {{ formatPrice(detailOrder?.currentPrice) }}
              </span>
            </template>
          </div>

          <!-- 订单类型和数量 -->
          <div class="order-detail-type">
            <!-- 期限订单显示看涨基础货币或计价货币 -->
            <span 
              v-if="detailOrder?.direction" 
              class="order-type-badge pending-badge" 
              :class="detailOrder?.direction === 'UP' ? 'buy' : 'sell'"
            >
              {{ localeStore.t('lookUp') }}{{ detailOrder?.direction === 'UP' ? (detailOrder?.baseCurrency || '') : (detailOrder?.quoteCurrency || '') }}
            </span>
            <!-- 合约订单显示买入/卖出 -->
            <span 
              v-else
              class="order-type-badge pending-badge" 
              :class="detailOrder?.type === 'buy' ? 'buy' : 'sell'"
            >
              {{ detailOrder?.type === 'buy' ? '買入' : '賣出' }} {{ detailOrder?.lots }}手數
            </span>
          </div>
          
          <!-- 挂单显示当前价格（大字体，绿色） -->
          <div class="order-detail-current-price" v-if="detailOrder?.status === 'PENDING'">
            {{ formatPrice(detailOrder?.currentPrice) }}
          </div>

          <!-- 保证金、手续费、盈亏 -->
          <div class="order-detail-finance">
            <div class="finance-item">
              <span>{{ localeStore.t('margin') }}:</span>
              <span>{{ formatMoney(detailOrder?.margin || 0) }}</span>
            </div>
            <div class="finance-item">
              <span>{{ localeStore.t('fee') }}:</span>
              <span>{{ formatMoney(detailOrder?.fee || 0) }}</span>
            </div>
            <!-- 持仓订单显示盈亏 -->
            <div class="finance-item profit-item" v-if="detailOrder?.status === 'OPEN'">
              <span>{{ localeStore.t('profitLoss') }}:</span>
              <span :class="{ 
                negative: detailOrder?.profit < 0, 
                positive: detailOrder?.profit > 0 
              }">
                {{ formatMoney(detailOrder?.profit || 0) }}
              </span>
            </div>
          </div>

          <!-- 止盈止损设置（仅持仓订单显示） -->
          <div class="order-detail-tpsl" v-if="detailOrder?.status === 'OPEN'">
            <!-- 止损 -->
            <div class="tpsl-item">
              <div class="tpsl-label-row">
                <span class="tpsl-label">{{ localeStore.t('stopLoss') }}</span>
                <label class="tpsl-toggle">
                  <input type="checkbox" v-model="stopLossEnabled" />
                  <span class="tpsl-slider"></span>
                </label>
              </div>
              <div class="tpsl-input-wrapper" v-if="stopLossEnabled">
                <button class="tpsl-btn" @click="adjustStopLoss(-1)">-</button>
                <input 
                  type="number" 
                  v-model.number="stopLossValue" 
                  class="tpsl-input" 
                  step="0.01"
                  min="0"
                />
                <button class="tpsl-btn" @click="adjustStopLoss(1)">+</button>
              </div>
            </div>

            <!-- 止盈 -->
            <div class="tpsl-item">
              <div class="tpsl-label-row">
                <span class="tpsl-label">{{ localeStore.t('takeProfit') }}</span>
                <label class="tpsl-toggle">
                  <input type="checkbox" v-model="takeProfitEnabled" />
                  <span class="tpsl-slider"></span>
                </label>
              </div>
              <div class="tpsl-input-wrapper" v-if="takeProfitEnabled">
                <button class="tpsl-btn" @click="adjustTakeProfit(-1)">-</button>
                <input 
                  type="number" 
                  v-model.number="takeProfitValue" 
                  class="tpsl-input" 
                  step="0.01"
                  min="0"
                />
                <button class="tpsl-btn" @click="adjustTakeProfit(1)">+</button>
              </div>
            </div>
          </div>
        </div>

        <!-- 操作按钮 -->
        <div class="order-detail-actions">
          <!-- 持仓订单：显示修改TP/SL和平仓按钮 -->
          <template v-if="detailOrder?.status === 'OPEN'">
            <button class="order-detail-btn modify-btn" @click="handleUpdateTPSL">{{ localeStore.t('modifyTPSL') }}</button>
            <button class="order-detail-btn close-btn" @click="handleCloseOrder">{{ localeStore.t('closeOrder') }}</button>
          </template>
          <!-- 挂单：显示撤单按钮 -->
          <template v-else-if="detailOrder?.status === 'PENDING'">
            <button class="order-detail-btn cancel-btn" @click="handleCancelOrder">{{ localeStore.t('cancelOrder') }}</button>
          </template>
        </div>
      </div>
    </div>

    <Tabbar />
  </div>
</template>

<style scoped>
.orders-page {
  min-height: 100vh;
  background: #f5f7fb;
  padding-bottom: 80px;
}

/* 主标签 */
.main-tabs {
  display: flex;
  background: #fff;
  padding: 12px;
  gap: 8px;
}

.main-tab {
  flex: 1;
  text-align: center;
  padding: 10px 0;
  border-radius: 8px;
  background: #f0f0f0;
  color: #666;
  font-size: 16px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s;
}

.main-tab.active {
  background: #73b100;
  color: #fff;
}

/* 子标签 */
.sub-tabs {
  display: flex;
  background: #fff;
  padding: 8px 12px;
  gap: 8px;
  border-top: 1px solid #f0f0f0;
}

.sub-tab {
  flex: 1;
  text-align: center;
  padding: 8px 0;
  border-radius: 6px;
  background: #f0f0f0;
  color: #666;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.3s;
}

.sub-tab.active {
  background: #73b100;
  color: #fff;
}

/* 统计卡片 */
.summary-card {
  background: #fff;
  margin: 12px;
  padding: 16px;
  border-radius: 12px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.summary-label {
  font-size: 12px;
  color: #999;
}

.summary-value {
  font-size: 16px;
  font-weight: 600;
  color: #000;
}

.summary-value.negative {
  color: #ff4444;
}

.summary-value.positive {
  color: #73b100;
}

/* 订单列表 */
.orders-list {
  padding: 0 12px 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.order-card {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.order-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
}

.order-symbol {
  font-size: 16px;
  font-weight: 600;
  color: #000;
}

.order-price {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #666;
}

.order-price .arrow {
  color: #999;
}

.order-price .positive {
  color: #73b100;
}

.order-price .negative {
  color: #ff4444;
}

.order-price .negative {
  color: #ff4444;
}

.order-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.order-type-badge {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
  width: fit-content;
}

.order-type-badge.buy {
  background: #73b100;
  color: #fff;
}

.order-type-badge.sell {
  background: #ff4444;
  color: #fff;
}

.order-details {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.detail-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
}

.detail-label {
  color: #999;
}

.detail-value {
  color: #000;
  font-weight: 500;
}

.detail-value.negative {
  color: #ff4444;
}

.detail-value.positive {
  color: #73b100;
}

/* 空状态 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  text-align: center;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
  opacity: 0.3;
}

.empty-text {
  font-size: 14px;
  color: #999;
}

/* 订单详情弹窗 */
.order-detail-modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.order-detail-modal {
  background: #fff;
  border-radius: 16px;
  padding: 24px;
  width: 90%;
  max-width: 420px;
  position: relative;
  max-height: 90vh;
  overflow-y: auto;
  padding-top: 50px; /* 为关闭按钮留出空间 */
}

.order-detail-modal-close {
  position: absolute;
  top: 12px;
  right: 12px;
  background: rgba(0, 0, 0, 0.05);
  border: none;
  font-size: 24px;
  color: #666;
  cursor: pointer;
  padding: 0;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
  border-radius: 50%;
  z-index: 10;
  transition: all 0.2s;
}

.order-detail-modal-close:hover {
  background: rgba(0, 0, 0, 0.1);
  color: #000;
}

.order-detail-header {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 20px;
  text-align: center;
  padding-right: 0; /* 移除右边距，因为关闭按钮已经不在标题区域 */
  margin-top: -26px; /* 向上移动，因为 padding-top 增加了 */
}

.order-detail-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.order-detail-basic {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.order-detail-symbol {
  font-size: 24px;
  font-weight: 600;
  color: #000;
}

.order-detail-id-time {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #666;
}

.order-detail-price {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: 20px;
  font-weight: 600;
  padding: 12px 0;
  padding-right: 50px; /* 为关闭按钮留出空间，避免遮挡 */
  word-break: break-all; /* 如果数字太长，允许换行 */
  flex-wrap: wrap; /* 允许换行 */
}

.order-detail-price .arrow {
  color: #999;
}

.order-detail-price .positive {
  color: #73b100;
}

.order-detail-price .negative {
  color: #ff4444;
}

.order-detail-type {
  display: flex;
  justify-content: center;
}

.order-detail-finance {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  background: #f8f9fa;
  border-radius: 8px;
}

.finance-item {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
}

.finance-item span:first-child {
  color: #666;
}

.finance-item span:last-child {
  font-weight: 600;
  color: #000;
}

.finance-item.profit-item span:last-child.positive {
  color: #73b100;
}

.finance-item.profit-item span:last-child.negative {
  color: #ff4444;
}

.order-detail-tpsl {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 16px;
  background: #f8f9fa;
  border-radius: 8px;
}

.tpsl-item {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tpsl-label-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.tpsl-label {
  font-size: 14px;
  font-weight: 500;
  color: #333;
}

.tpsl-toggle {
  position: relative;
  display: inline-block;
  width: 44px;
  height: 24px;
}

.tpsl-toggle input {
  opacity: 0;
  width: 0;
  height: 0;
}

.tpsl-slider {
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

.tpsl-slider:before {
  position: absolute;
  content: "";
  height: 18px;
  width: 18px;
  left: 3px;
  bottom: 3px;
  background-color: white;
  transition: 0.3s;
  border-radius: 50%;
}

.tpsl-toggle input:checked + .tpsl-slider {
  background-color: #73b100;
}

.tpsl-toggle input:checked + .tpsl-slider:before {
  transform: translateX(20px);
}

.tpsl-input-wrapper {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  border-radius: 8px;
  padding: 4px;
}

.tpsl-btn {
  width: 32px;
  height: 32px;
  border: 1px solid #ddd;
  background: #fff;
  border-radius: 4px;
  font-size: 18px;
  color: #666;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}

.tpsl-btn:active {
  background: #f0f0f0;
}

.tpsl-input {
  flex: 1;
  border: none;
  padding: 8px;
  font-size: 14px;
  text-align: center;
  background: transparent;
}

.tpsl-input:focus {
  outline: none;
}

.order-detail-actions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}

.order-detail-btn {
  flex: 1;
  padding: 12px;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}

.order-detail-btn.modify-btn {
  background: #e8f5e9;
  color: #73b100;
  border: 1px solid #73b100;
}

.order-detail-btn.close-btn {
  background: #73b100;
  color: #fff;
}

.order-detail-btn.cancel-btn {
  background: #73b100;
  color: #fff;
  width: 100%;
}

.order-detail-btn:active {
  opacity: 0.8;
}


/* Toast消息 */
.toast-message {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 18px 36px;
  border-radius: 12px;
  font-size: 18px;
  font-weight: 600;
  z-index: 9999;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
  pointer-events: none;
  white-space: nowrap;
  min-width: 200px;
  text-align: center;
  animation: toastFadeIn 0.3s ease-out;
}

@keyframes toastFadeIn {
  from {
    opacity: 0;
    transform: translate(-50%, -50%) scale(0.9);
  }
  to {
    opacity: 1;
    transform: translate(-50%, -50%) scale(1);
  }
}

/* 倒计时警告样式 */
.countdown-warning {
  color: #ff4444 !important;
  font-weight: 600 !important;
}

.toast-message.success {
  background: #73b100;
  color: #fff;
}

.toast-message.error {
  background: #ff4444;
  color: #fff;
}
</style>