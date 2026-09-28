<script setup lang="ts">
import marketWebSocket from '@/utils/marketWebSocket'
import { ref, computed, onMounted, watch, onUnmounted } from 'vue'
import Tabbar from '@/components/Tabbar.vue'
import OrderShareModal from '@/components/OrderShareModal.vue'
import { shareCopy, type ShareKind } from '@/utils/orderShare'
import request from '@/utils/request'
import { calculateContractProfit, contractEquity } from '@/utils/contract'
import { useMarketStore } from '@/store/market'
import { useLocaleStore } from '@/store/locale'
import { formatDateTime } from '@/utils/dateTime'
import { displaySymbol } from '@/utils/displaySymbol'
import { orderTimestamp, profitRate, withinDays } from '@/utils/orderView'

const marketStore = useMarketStore()
const localeStore = useLocaleStore()
localeStore.loadLocale()
const shareOrder = ref<{ id: string | number; kind: ShareKind } | null>(null)
const shareLabel = computed(() => shareCopy(localeStore.locale).share)
const copy = (zh: string, en: string) => localeStore.text(zh, en)
const formatOrderTime = (value: unknown) => {
  const timestamp = orderTimestamp(value)
  return timestamp === null ? '' : formatDateTime(new Date(timestamp).toISOString())
}

// 主标签：合約 / 期限
const mainTab = ref<'contract' | 'term'>('contract')

// 子标签：持倉 / 掛單 / 歷史
const subTab = ref<'positions' | 'pending' | 'history'>('positions')

// 期限页面的子标签：交易中 / 已平倉
const termSubTab = ref<'trading' | 'closed'>('trading')
const symbolFilter = ref('all')
const directionFilter = ref('all')
const periodFilter = ref(30)
const nowTick = ref(Date.now())

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
        profitRate: Number(item.profitRate ?? 0.8),
        lossRate: Number(item.lossRate ?? 1.0),
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
const editingTPSL = ref(false)
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
  const displayPrice = currentPrice > 0 ? currentPrice : Number(order.currentPrice || 0)
  
  const leverage = Number(order.leverage ?? 1)
  const calculatedProfit = calculateContractProfit(order, displayPrice, marketStore.getConversionRate(order.symbol, order.quoteCurrency))

  // 对于挂单（PENDING），使用 createdAt 作为创建时间；对于持仓（OPEN），使用 openTime
  const displayTime = order.status === 'PENDING' 
    ? (order.createdAt || order.openTime) 
    : (order.openTime || order.createdAt)
  const rawOpenTime = order.manualOpenTimeUtc != null ? new Date(order.manualOpenTimeUtc).toISOString() : displayTime
  const rawCloseTime = order.manualCloseTimeUtc != null ? new Date(order.manualCloseTimeUtc).toISOString() : order.closeTime
  
  return {
    id: order.id,
    symbol: order.symbol,
    displayName: order.displayName,
    type: order.side?.toLowerCase() || 'buy', // BUY -> buy, SELL -> sell
    orderType: order.type,
    lots: Number(order.quantity || 0),
    openPrice: Number(order.openPrice || 0),
    price: Number(order.price || 0), // 限价单价格
    currentPrice: displayPrice,
    closePrice: Number(order.closePrice || 0),
    amount: Number(order.margin || 0),
    profit: calculatedProfit,
    margin: Number(order.margin || 0),
    fee: Number(order.fee || 0),
    orderSource: order.orderSource,
    manualCloseTime: order.manualCloseTimeUtc != null ? formatDateTime(new Date(order.manualCloseTimeUtc).toISOString()) : '',
    openTime: formatOrderTime(rawOpenTime), // 使用创建时间或开仓时间
    closeTime: formatOrderTime(rawCloseTime),
    openTimeRaw: rawOpenTime,
    closeTimeRaw: rawCloseTime,
    status: order.status, // 保留状态
    side: order.side, // 保留原始side用于计算
    quantity: order.quantity, // 保留原始quantity用于计算
    stopLoss: order.stopLoss ? Number(order.stopLoss) : null, // 止损
    takeProfit: order.takeProfit ? Number(order.takeProfit) : null, // 止盈
    leverage,
    quoteCurrency: order.quoteCurrency,
    lotSize: order.lotSize,
  }
}

// Trading estimates use the same configured payout and preset outcome as settlement.
function calculateOptionProfit(order: any, currentPrice: number): number {
  if (order.status === 'CLOSED' && order.profit != null) {
    return Number(order.profit)
  }
  if (order.status === 'TRADING') {
    const amount = Number(order.amount || 0)
    const orderDuration = Number(order.duration || 0)
    const durationOption = durationOptions.value.find(opt => opt.value === orderDuration)
    if (!durationOption) return NaN
    if (order.presetProfitType === 'PROFIT') return amount * durationOption.profitRate
    if (order.presetProfitType === 'LOSS') return -amount * durationOption.lossRate
    if (Number(order.openPrice) <= 0 || currentPrice <= 0) return NaN
    const priceDiff = currentPrice - Number(order.openPrice)
    if (order.direction === 'UP') return priceDiff > 0 ? amount * durationOption.profitRate : -amount * durationOption.lossRate
    if (order.direction === 'DOWN') return priceDiff < 0 ? amount * durationOption.profitRate : -amount * durationOption.lossRate
    return NaN
  }
  return Number(order.profit ?? NaN)
}

// 存储所有交易对信息
const allSymbols = ref<any[]>([])
watch(() => [allSymbols.value, positionsData.value, pendingOrdersData.value, termTradingData.value], () => {
  const needed = new Set([...positionsData.value, ...pendingOrdersData.value, ...termTradingData.value].map(order => order.symbol))
  void marketStore.subscribeSymbols(allSymbols.value.filter(symbol => needed.has(symbol.symbol)), 'orders')
})


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
  if (symbolInfo?.category === 'Forex' || /=X$/i.test(symbol)) {
    const [baseCurrency, quoteCurrency] = displaySymbol(symbolInfo || symbol).split('/')
    if (baseCurrency && quoteCurrency) return { baseCurrency, quoteCurrency }
  }
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
    displayName: order.displayName,
    type: order.direction === 'UP' ? 'buy' : 'sell', // UP -> buy (绿色), DOWN -> sell (红色)
    openPrice: Number(order.openPrice || 0),
    currentPrice: currentPrice > 0 ? currentPrice : order.status === 'CLOSED' ? Number(order.closePrice || 0) : 0,
    closePrice: Number(order.closePrice || 0),
    amount: Number(order.amount || 0),
    profit: calculatedProfit,
    duration: order.duration || 0,
    openTime: formatOrderTime(order.openTime),
    openTimeRaw: order.openTime, // 保留原始时间用于计算倒计时
    closeTime: formatOrderTime(order.closeTime),
    closeTimeRaw: order.closeTime,
    direction: order.direction, // 保留原始direction用于计算
    presetProfitType: order.presetProfitType,
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
  const openTime = orderTimestamp(order.openTimeRaw)
  return openTime === null ? 0 : Math.max(0, Number(order.duration) - Math.floor((nowTick.value - openTime) / 1000))
}

// 加载合约订单
let loadVersion = 0
async function loadContractOrders() {
  const version = ++loadVersion
  const requestedTab = subTab.value
  loading.value = true
  try {
    // 根据子标签加载不同状态的订单
    let status: string | null = null
    if (requestedTab === 'positions') {
      status = 'OPEN' // 持倉
    } else if (requestedTab === 'pending') {
      status = 'PENDING' // 掛單
    } else if (requestedTab === 'history') {
      status = 'CLOSED' // 歷史
    }

    const res: any = await request.get('/trade/contract/orders', {
      params: status ? { status } : {},
    })
    if (version !== loadVersion) return

    const orders = (res.list || []).map(transformContractOrder)

    if (requestedTab === 'positions') {
      positionsData.value = orders
      // 计算总保证金
      totalMargin.value = orders.reduce((sum: number, item: any) => sum + item.margin, 0)
    } else if (requestedTab === 'pending') {
      pendingOrdersData.value = orders
      // 计算总保证金（挂单也需要冻结保证金）
      totalMargin.value = orders.reduce((sum: number, item: any) => sum + item.margin, 0)
      // 同时加载持仓订单，用于统计信息显示
      await loadPositionsForSummary(version)
    } else if (requestedTab === 'history') {
      historyData.value = orders
      totalMargin.value = 0
    }

    // 加载合约余额
    if (version === loadVersion) await loadContractBalance()
  } catch (e: any) {
    console.error(localeStore.t('loadContractOrdersFailed'), e)
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

// 加载期限订单
async function loadOptionOrders() {
  const version = ++loadVersion
  const requestedTab = termSubTab.value
  loading.value = true
  try {
    // 根据子标签加载不同状态的订单
    let status: string | null = null
    if (requestedTab === 'trading') {
      status = 'TRADING' // 交易中
    } else if (termSubTab.value === 'closed') {
      status = 'CLOSED' // 已平倉
    }

    const res: any = await request.get('/trade/option/orders', {
      params: status ? { status } : {},
    })
    if (version !== loadVersion) return

    const orders = (res.list || []).map(transformOptionOrder)

    if (requestedTab === 'trading') {
      termTradingData.value = orders
    } else {
      termClosedData.value = orders
    }
  } catch (e: any) {
    console.error(localeStore.t('loadOptionOrdersFailed'), e)
  } finally {
    if (version === loadVersion) loading.value = false
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
async function loadPositionsForSummary(version = loadVersion) {
  try {
    const res: any = await request.get('/trade/contract/orders', {
      params: { status: 'OPEN' },
    })
    const orders = (res.list || []).map(transformContractOrder)
    if (version === loadVersion) positionsData.value = orders
  } catch (e: any) {
    console.error(localeStore.t('loadPositionsFailed'), e)
  }
}

// 计算持仓订单的总盈亏
const positionsTotalProfit = computed(() => {
  return positionsData.value.reduce((sum, item) => sum + (item.profit ?? 0), 0)
})

// 打开订单详情弹窗
function openOrderDetailModal(order: any, edit = false) {
  detailOrder.value = order
  editingTPSL.value = edit
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
  editingTPSL.value = false
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
      editingTPSL.value = false
      
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
        
        const calculatedProfit = calculateContractProfit({ ...order, side, quantity }, currentPrice, marketStore.getConversionRate(order.symbol, order.quoteCurrency))
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
          presetProfitType: order.presetProfitType,
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
    
  }
  
  // 更新风险率（持仓页面）
  if (mainTab.value === 'contract' && subTab.value === 'positions') {
    updateRiskRate()
  }
  // 挂单页面的风险率通过 computed 属性自动更新，不需要手动调用
}

const currentRows = computed<any[]>(() => mainTab.value === 'contract'
  ? subTab.value === 'positions' ? positionsData.value : subTab.value === 'pending' ? pendingOrdersData.value : historyData.value
  : termSubTab.value === 'trading' ? termTradingData.value : termClosedData.value)

const availableSymbols = computed(() => [...new Set(currentRows.value.map(order => order.symbol).filter(Boolean))])
const isHistory = computed(() => mainTab.value === 'contract' ? subTab.value === 'history' : termSubTab.value === 'closed')
const visibleRows = computed(() => {
  const rows = currentRows.value.filter(order =>
    (symbolFilter.value === 'all' || order.symbol === symbolFilter.value)
    && (directionFilter.value === 'all' || order.type === directionFilter.value)
    && (!isHistory.value || withinDays(order.closeTimeRaw || order.openTimeRaw, periodFilter.value, nowTick.value)),
  )
  return isHistory.value ? rows.sort((a, b) =>
    (orderTimestamp(b.closeTimeRaw || b.openTimeRaw) || 0) - (orderTimestamp(a.closeTimeRaw || a.openTimeRaw) || 0)) : rows
})

function orderUnit(_order: any): string {
  return 'USD' // Both CONTRACT and OPTION asset accounts settle in USD.
}

function priceUnit(order: any): string {
  return String(order?.quoteCurrency || parseSymbol(order?.symbol || '').quoteCurrency || '')
}

function orderLabel(order: any): string {
  const symbol = typeof order === 'string' ? order : String(order?.symbol || '')
  return /^[A-Z0-9]+USDT$/.test(symbol) ? symbol : displaySymbol(order)
}

const summaryUnit = computed(() => {
  const units = [...new Set(visibleRows.value.map(orderUnit).filter(Boolean))]
  return units.length === 1 ? units[0] : ''
})

function signedMoney(value: number): string {
  return `${value > 0 ? '+' : ''}${formatMoney(value)}`
}

function returnRate(value: number, base: number): string {
  const rate = profitRate(value, base)
  return rate === null ? '--' : `${rate > 0 ? '+' : ''}${rate.toFixed(2)}%`
}

function amountColor(value: number): string {
  return value > 0 ? 'positive' : value < 0 ? 'negative' : ''
}

function sumProfit(orders: any[]): number {
  return orders.reduce((sum, order) => sum + Number(order.profit), 0)
}

function sumAmount(orders: any[], field: string): number {
  return orders.reduce((sum, order) => sum + Number(order[field] || 0), 0)
}

const summary = computed(() => {
  const rows = visibleRows.value
  const total = sumProfit(rows)
  const profit = sumProfit(rows.filter(order => Number(order.profit) > 0))
  const loss = sumProfit(rows.filter(order => Number(order.profit) < 0))
  if (mainTab.value === 'contract' && subTab.value === 'positions') return {
    label: copy('未實現盈虧', 'Unrealized P/L'), value: total, count: rows.length,
    items: [
      { label: copy('可用餘額', 'Available'), value: formatMoney(contractBalance.value) },
      { label: localeStore.t('currentMargin'), value: formatMoney(sumAmount(rows, 'margin')) },
      { label: localeStore.t('riskRate'), value: totalMargin.value > 0 ? `${formatMoney(riskRate.value)}%` : '—' },
    ],
  }
  if (mainTab.value === 'contract' && subTab.value === 'pending') return {
    label: copy('當前委託', 'Open orders'), value: rows.length, count: rows.length,
    items: [
      { label: copy('凍結資金', 'Frozen funds'), value: formatMoney(sumAmount(rows, 'margin') + sumAmount(rows, 'fee')) },
      { label: copy('可用餘額', 'Available'), value: formatMoney(contractBalance.value) },
      { label: copy('未實現盈虧', 'Unrealized P/L'), value: signedMoney(positionsTotalProfit.value) },
    ],
  }
  if (mainTab.value === 'contract' && subTab.value === 'history') return {
    label: copy('歷史已實現盈虧', 'Realized P/L'), value: total, count: rows.length,
    items: [
      { label: copy('盈利', 'Profit'), value: signedMoney(profit) },
      { label: copy('虧損', 'Loss'), value: signedMoney(loss) },
    ],
  }
  if (termSubTab.value === 'trading') return {
    label: copy('交易中的期限訂單', 'Active term orders'), value: rows.length, count: rows.length,
    items: [
      { label: copy('總投入', 'Total invested'), value: formatMoney(sumAmount(rows, 'amount')) },
      { label: localeStore.t('estimatedProfitLoss'), value: signedMoney(total) },
    ],
  }
  return {
    label: copy('期限已實現盈虧', 'Settled P/L'), value: total, count: rows.length,
    items: [
      { label: copy('盈利', 'Profit'), value: signedMoney(profit) },
      { label: copy('虧損', 'Loss'), value: signedMoney(loss) },
    ],
  }
})

function countdown(order: any): string {
  void nowTick.value
  const seconds = calculateCountdown(order)
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = String(seconds % 60).padStart(2, '0')
  return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${rest}` : `${minutes}:${rest}`
}

function countdownProgress(order: any): number {
  void nowTick.value
  return order.duration > 0 ? Math.max(0, Math.min(100, calculateCountdown(order) / order.duration * 100)) : 0
}

function displayDuration(seconds: number): string {
  if (seconds > 0 && seconds % 3600 === 0) return `${seconds / 3600} ${copy('小時', 'h')}`
  if (seconds > 0 && seconds % 60 === 0) return `${seconds / 60} ${copy('分鐘', 'min')}`
  return `${seconds || 0} s`
}

const cardKind = computed(() => mainTab.value === 'contract' ? subTab.value : termSubTab.value)
const countSummary = computed(() => cardKind.value === 'pending' || cardKind.value === 'trading')

function cardStatus(): string {
  if (cardKind.value === 'pending') return copy('待成交', 'Pending')
  if (cardKind.value === 'history') return localeStore.t('closed')
  if (cardKind.value === 'trading') return localeStore.t('trading')
  if (cardKind.value === 'closed') return copy('已結算', 'Settled')
  return ''
}

function cardRate(order: any): string {
  return returnRate(order.profit, mainTab.value === 'contract' ? order.margin : order.amount)
}

function cardTime(order: any): string {
  return isHistory.value ? order.closeTime || '--' : order.openTime || '--'
}

function cardTimeLabel(): string {
  return cardKind.value === 'pending' ? localeStore.t('createTime')
    : cardKind.value === 'closed' ? copy('結算時間', 'Settlement time')
      : isHistory.value ? localeStore.t('closeTime') : localeStore.t('openTime')
}

function cardMetrics(order: any): Array<{ label: string; value: string }> {
  const unit = orderUnit(order)
  const withUnit = (label: string, currency = priceUnit(order)) => currency ? `${label}(${currency})` : label
  if (cardKind.value === 'positions') return [
    { label: withUnit(copy('開倉價', 'Entry price')), value: formatPrice(order.openPrice) },
    { label: withUnit(localeStore.t('currentPrice')), value: formatPrice(order.currentPrice) },
    { label: withUnit(localeStore.t('margin'), unit), value: formatMoney(order.margin) },
    { label: copy('持倉數量', 'Position size'), value: `${order.lots} ${localeStore.t('lots')}` },
    { label: copy('止盈價', 'Take profit'), value: order.takeProfit ? formatPrice(order.takeProfit) : '--' },
    { label: copy('止損價', 'Stop loss'), value: order.stopLoss ? formatPrice(order.stopLoss) : '--' },
  ]
  if (cardKind.value === 'pending') return [
    { label: withUnit(copy('委託價', 'Order price')), value: formatPrice(order.price || order.openPrice) },
    { label: withUnit(localeStore.t('currentPrice')), value: order.currentPrice > 0 ? formatPrice(order.currentPrice) : '--' },
    { label: copy('委託數量', 'Order size'), value: `${order.lots} ${localeStore.t('lots')}` },
    { label: copy('委託保證金', 'Order margin'), value: formatMoney(order.margin) },
    { label: localeStore.t('orderId'), value: `#${order.id}` },
    { label: copy('委託類型', 'Order type'), value: order.orderType === 'LIMIT' ? copy('限價', 'Limit') : order.orderType === 'MARKET' ? copy('市價', 'Market') : '--' },
  ]
  if (cardKind.value === 'history') return [
    { label: withUnit(localeStore.t('margin'), unit), value: formatMoney(order.margin) },
    { label: withUnit(copy('開倉價', 'Entry price')), value: formatPrice(order.openPrice) },
    { label: withUnit(copy('平倉價', 'Exit price')), value: formatPrice(order.closePrice) },
    { label: copy('平倉數量', 'Closed size'), value: `${order.lots} ${localeStore.t('lots')}` },
    { label: withUnit(localeStore.t('fee'), unit), value: formatMoney(order.fee) },
    { label: localeStore.t('orderId'), value: `#${order.id}` },
  ]
  if (cardKind.value === 'trading') return [
    { label: copy('投入金額', 'Invested'), value: `${formatMoney(order.amount)} ${unit}`.trim() },
    { label: withUnit(copy('開倉價', 'Entry price')), value: formatPrice(order.openPrice) },
    { label: withUnit(localeStore.t('currentPrice')), value: order.currentPrice > 0 ? formatPrice(order.currentPrice) : '--' },
  ]
  return [
    { label: copy('投入金額', 'Invested'), value: `${formatMoney(order.amount)} ${unit}`.trim() },
    { label: withUnit(copy('開倉價', 'Entry price')), value: formatPrice(order.openPrice) },
    { label: withUnit(copy('結算價', 'Settlement price')), value: formatPrice(order.closePrice) },
    { label: localeStore.t('duration'), value: displayDuration(Number(order.duration)) },
    { label: localeStore.t('orderId'), value: `#${order.id}` },
    { label: copy('結果', 'Result'), value: order.profit >= 0 ? copy('盈利', 'Profit') : copy('虧損', 'Loss') },
  ]
}

watch([mainTab, subTab, termSubTab], () => {
  symbolFilter.value = 'all'
  directionFilter.value = 'all'
  periodFilter.value = 30
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

  
  // 初始加载
  if (mainTab.value === 'contract') {
    await loadContractOrders()
  } else {
    await loadOptionOrders()
  }
  
  // 每2秒更新一次实时价格和盈亏
  priceUpdateInterval = window.setInterval(() => {
    nowTick.value = Date.now()
    updateOrdersWithRealTimePrice()
  }, 2000)
})

onUnmounted(() => {
  marketWebSocket.release('orders')
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
  const n = Number(v ?? 0)
  if (!Number.isFinite(n)) return '--'
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 格式化价格
function formatPrice(v: number | string | undefined | null) {
  const n = Number(v ?? 0)
  if (!Number.isFinite(n)) return '--'
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
</script>

<template>
  <div class="orders-page">
    <header class="orders-header">
      <div class="orders-titlebar">
        <h1>{{ localeStore.t('tabbarOrder') }}</h1>
        <button type="button" class="history-shortcut" :aria-label="copy('歷史倉位', 'Position history')" @click="mainTab = 'contract'; subTab = 'history'">
          <svg viewBox="0 0 24 24" width="21" height="21" fill="none" aria-hidden="true"><path d="M20 11a8 8 0 1 1-2.3-5.7M20 4v5h-5M12 7v5l3 2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>
        </button>
      </div>
      <nav class="main-tabs" :aria-label="copy('訂單類型', 'Order type')">
        <button type="button" class="main-tab" :class="{ active: mainTab === 'contract' }" :aria-current="mainTab === 'contract' ? 'page' : undefined" @click="mainTab = 'contract'">{{ copy('合約', 'Contracts') }}</button>
        <button type="button" class="main-tab" :class="{ active: mainTab === 'term' }" :aria-current="mainTab === 'term' ? 'page' : undefined" @click="mainTab = 'term'">{{ copy('期限', 'Term') }}</button>
      </nav>
      <nav v-if="mainTab === 'contract'" class="sub-tabs" :aria-label="copy('合約訂單分類', 'Contract order tabs')">
        <button v-for="tab in (['positions', 'pending', 'history'] as const)" :key="tab" type="button" class="sub-tab" :class="{ active: subTab === tab }" :aria-current="subTab === tab ? 'page' : undefined" @click="subTab = tab">{{ tab === 'positions' ? copy('持倉', 'Positions') : tab === 'pending' ? copy('當前委託', 'Open orders') : copy('歷史倉位', 'Position history') }}</button>
      </nav>
      <nav v-else class="sub-tabs" :aria-label="copy('期限訂單分類', 'Term order tabs')">
        <button type="button" class="sub-tab" :class="{ active: termSubTab === 'trading' }" :aria-current="termSubTab === 'trading' ? 'page' : undefined" @click="termSubTab = 'trading'">{{ copy('交易中', 'Active') }}</button>
        <button type="button" class="sub-tab" :class="{ active: termSubTab === 'closed' }" :aria-current="termSubTab === 'closed' ? 'page' : undefined" @click="termSubTab = 'closed'">{{ copy('已結算', 'Settled') }}</button>
      </nav>
    </header>

    <main class="orders-content">
      <section class="summary-card" :aria-label="summary.label">
        <div class="summary-heading"><span>{{ summary.label }}</span><span>{{ copy('訂單', 'Orders') }} {{ summary.count }}</span></div>
        <div class="summary-primary" :class="!countSummary && amountColor(summary.value)">{{ countSummary ? summary.value : signedMoney(summary.value) }}<small v-if="countSummary">{{ copy('筆', 'orders') }}</small><small v-else-if="summaryUnit">{{ summaryUnit }}</small></div>
        <div class="summary-stats" :class="{ 'two-stats': summary.items.length === 2 }"><div v-for="item in summary.items" :key="item.label"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div>
      </section>

      <div class="orders-filters">
        <label class="filter-select"><span class="sr-only">{{ copy('幣種', 'Symbol') }}</span><select v-model="symbolFilter"><option value="all">{{ copy('全部幣種', 'All symbols') }}</option><option v-for="symbol in availableSymbols" :key="symbol" :value="symbol">{{ orderLabel(symbol) }}</option></select></label>
        <label v-if="isHistory" class="filter-select"><span class="sr-only">{{ copy('時間範圍', 'Period') }}</span><select v-model.number="periodFilter"><option :value="30">{{ copy('近30天', 'Last 30 days') }}</option><option :value="7">{{ copy('近7天', 'Last 7 days') }}</option><option :value="90">{{ copy('近90天', 'Last 90 days') }}</option><option :value="0">{{ copy('全部時間', 'All time') }}</option></select></label>
        <label v-else class="filter-select"><span class="sr-only">{{ copy('方向', 'Direction') }}</span><select v-model="directionFilter"><option value="all">{{ copy('全部方向', 'All directions') }}</option><option value="buy">{{ mainTab === 'term' ? copy('看漲', 'Up') : copy('買入 / 多', 'Buy / Long') }}</option><option value="sell">{{ mainTab === 'term' ? copy('看跌', 'Down') : copy('賣出 / 空', 'Sell / Short') }}</option></select></label>
        <span class="filter-count">{{ copy('共', 'Total') }} {{ visibleRows.length }}</span>
      </div>

      <div v-if="loading" class="list-state" role="status">{{ copy('載入中…', 'Loading…') }}</div>
      <div v-else-if="visibleRows.length === 0" class="list-state" role="status"><span class="empty-icon">◎</span>{{ copy('暫無訂單', 'No orders') }}</div>
      <div v-else class="orders-list">
        <article v-for="order in visibleRows" :key="`${mainTab}-${order.id}`" class="order-card">
          <div class="card-heading">
            <div class="card-identity"><h2>{{ orderLabel(order) }}</h2><div class="card-tags">
              <span class="direction-tag" :class="order.type === 'buy' ? 'long' : 'short'">{{ mainTab === 'term' ? order.type === 'buy' ? copy('看漲', 'Up') : copy('看跌', 'Down') : order.type === 'buy' ? copy('多', 'Long') : copy('空', 'Short') }}</span>
              <span v-if="mainTab === 'contract' && order.leverage" class="neutral-tag">{{ order.leverage }}×</span>
              <span v-if="order.orderType === 'LIMIT' && cardKind === 'pending'" class="pending-tag">{{ copy('限價委託', 'Limit') }}</span>
              <span v-if="cardStatus()" class="status-tag" :class="cardKind === 'pending' ? 'pending-tag' : cardKind === 'trading' ? 'live-tag' : ''">{{ cardStatus() }}</span>
              <span v-if="order.orderSource === 'MANUAL_TEST' || order.orderSource === 'MANUAL'" class="manual-order-badge">{{ copy('手動', 'Manual') }}</span>
            </div></div>
            <div v-if="cardKind === 'pending'" class="card-pnl card-pending">{{ copy('待成交', 'Pending') }}</div>
            <div v-else class="card-pnl" :class="amountColor(order.profit)"><strong>{{ signedMoney(order.profit) }}</strong><small>{{ cardRate(order) }}</small></div>
          </div>
          <div v-if="cardKind === 'trading'" class="countdown-block"><div class="countdown-caption"><span>{{ copy('距離結算', 'To settlement') }}</span><strong>{{ countdown(order) }}</strong></div><div class="countdown-track"><span :style="{ width: `${countdownProgress(order)}%` }"></span></div></div>
          <div class="card-metrics"><div v-for="(metric, index) in cardMetrics(order)" :key="index" class="metric" :class="{ right: index % 3 === 2 }"><span>{{ metric.label }}</span><strong>{{ metric.value }}</strong></div></div>
          <div class="card-footer"><span>{{ cardTimeLabel() }}</span><div><time>{{ cardTime(order) }}</time><button v-if="isHistory" type="button" class="order-share-entry" :aria-label="shareLabel" :title="shareLabel" @click="shareOrder = { id: order.id, kind: mainTab === 'contract' ? 'contract' : 'option' }"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 16V3m-4 4 4-4 4 4M5 13v7h14v-7" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg></button></div></div>
          <div v-if="cardKind === 'positions' || cardKind === 'pending'" class="card-actions">
            <template v-if="cardKind === 'positions'"><button type="button" class="subtle-action" @click="openOrderDetailModal(order, true)">{{ copy('止盈止損', 'TP / SL') }}</button><button type="button" class="primary-action" @click="openOrderDetailModal(order)">{{ copy('平倉', 'Close') }}</button></template>
            <template v-else><button type="button" class="subtle-action" @click="openOrderDetailModal(order)">{{ copy('查看詳情', 'Details') }}</button><button type="button" class="quiet-action" @click="openOrderDetailModal(order)">{{ copy('撤單', 'Cancel') }}</button></template>
          </div>
        </article>
      </div>
    </main>

    <div v-if="showOrderDetailModal && detailOrder" class="order-detail-modal-overlay" @click.self="closeOrderDetailModal"><section class="order-detail-modal" role="dialog" aria-modal="true" :aria-label="detailOrder.status === 'PENDING' ? copy('委託詳情', 'Order details') : copy('持倉詳情', 'Position details')">
      <div class="sheet-handle"></div><header class="detail-header"><h2>{{ detailOrder.status === 'PENDING' ? copy('委託詳情', 'Order details') : copy('持倉詳情', 'Position details') }}</h2><button type="button" class="order-detail-modal-close" :aria-label="copy('關閉', 'Close')" @click="closeOrderDetailModal">×</button></header>
      <div class="detail-body"><div class="detail-identity"><h3>{{ orderLabel(detailOrder) }}</h3><div class="card-tags"><span class="direction-tag" :class="detailOrder.type === 'buy' ? 'long' : 'short'">{{ detailOrder.type === 'buy' ? copy('多', 'Long') : copy('空', 'Short') }}</span><span class="neutral-tag">{{ detailOrder.leverage }}×</span><span :class="detailOrder.status === 'PENDING' ? 'pending-tag' : 'live-tag'" class="status-tag">{{ detailOrder.status === 'PENDING' ? copy('待成交', 'Pending') : copy('持倉中', 'Open') }}</span></div></div>
        <div class="detail-focus"><span>{{ detailOrder.status === 'PENDING' ? copy('委託價格', 'Order price') : copy('未實現盈虧', 'Unrealized P/L') }}</span><strong :class="detailOrder.status === 'OPEN' && amountColor(detailOrder.profit)">{{ detailOrder.status === 'PENDING' ? formatPrice(detailOrder.price || detailOrder.openPrice) : signedMoney(detailOrder.profit) }}</strong><small>{{ detailOrder.status === 'PENDING' ? priceUnit(detailOrder) : `${orderUnit(detailOrder)} · ${returnRate(detailOrder.profit, detailOrder.margin)}` }}</small></div>
        <div class="detail-grid"><div v-for="(metric, index) in cardMetrics(detailOrder)" :key="index"><span>{{ metric.label }}</span><strong>{{ metric.value }}</strong></div><div><span>{{ localeStore.t('fee') }}</span><strong>{{ formatMoney(detailOrder.fee) }} {{ orderUnit(detailOrder) }}</strong></div><div><span>{{ detailOrder.status === 'OPEN' ? localeStore.t('orderId') : localeStore.t('createTime') }}</span><strong>{{ detailOrder.status === 'OPEN' ? `#${detailOrder.id}` : detailOrder.openTime }}</strong></div></div>
        <div v-if="detailOrder.status === 'OPEN'" class="tpsl-section"><h4>{{ copy('止盈止損', 'TP / SL') }}</h4><template v-if="editingTPSL"><label class="tpsl-row"><span>{{ copy('止盈價', 'Take profit') }}</span><input v-model="takeProfitEnabled" type="checkbox" /></label><div v-if="takeProfitEnabled" class="stepper"><button type="button" @click="adjustTakeProfit(-1)">−</button><input v-model.number="takeProfitValue" type="number" min="0" step="0.01" :aria-label="copy('止盈價', 'Take profit')" /><button type="button" @click="adjustTakeProfit(1)">+</button></div><label class="tpsl-row"><span>{{ copy('止損價', 'Stop loss') }}</span><input v-model="stopLossEnabled" type="checkbox" /></label><div v-if="stopLossEnabled" class="stepper"><button type="button" @click="adjustStopLoss(-1)">−</button><input v-model.number="stopLossValue" type="number" min="0" step="0.01" :aria-label="copy('止損價', 'Stop loss')" /><button type="button" @click="adjustStopLoss(1)">+</button></div></template><template v-else><div class="tpsl-row"><span>{{ copy('止盈價', 'Take profit') }}</span><strong>{{ detailOrder.takeProfit ? formatPrice(detailOrder.takeProfit) : '--' }}</strong></div><div class="tpsl-row"><span>{{ copy('止損價', 'Stop loss') }}</span><strong>{{ detailOrder.stopLoss ? formatPrice(detailOrder.stopLoss) : '--' }}</strong></div></template></div>
        <div v-else class="tpsl-section"><h4>{{ copy('訂單狀態', 'Order status') }}</h4><div class="tpsl-row"><span>{{ copy('成交情況', 'Filled') }}</span><strong>{{ copy('未成交', 'Not filled') }}</strong></div><div class="tpsl-row"><span>{{ copy('委託數量', 'Order size') }}</span><strong>{{ detailOrder.lots }} {{ localeStore.t('lots') }}</strong></div></div>
      </div>
      <footer class="detail-actions"><template v-if="detailOrder.status === 'OPEN'"><button v-if="editingTPSL" type="button" class="subtle-action" @click="editingTPSL = false">{{ copy('返回', 'Back') }}</button><button v-else type="button" class="subtle-action" @click="editingTPSL = true">{{ copy('修改止盈止損', 'Edit TP / SL') }}</button><button type="button" class="primary-action" @click="editingTPSL ? handleUpdateTPSL() : handleCloseOrder()">{{ editingTPSL ? copy('儲存', 'Save') : copy('平倉', 'Close') }}</button></template><template v-else><button type="button" class="subtle-action" @click="closeOrderDetailModal">{{ copy('返回列表', 'Back') }}</button><button type="button" class="primary-action" @click="handleCancelOrder">{{ copy('撤單', 'Cancel order') }}</button></template></footer>
    </section></div>
    <OrderShareModal v-if="shareOrder" :order-id="shareOrder.id" :kind="shareOrder.kind" brand="DEMO" @close="shareOrder = null" />
    <Tabbar />
  </div>
</template>

<style scoped>
.orders-page{min-height:100vh;background:#f4f6f9;color:#17212d;padding-bottom:84px;font-family:inherit}
.orders-header{background:#fff}.orders-titlebar{height:58px;display:flex;justify-content:center;align-items:center;position:relative}.orders-titlebar h1{margin:0;font-size:19px;font-weight:700}.history-shortcut{position:absolute;right:13px;top:9px;width:40px;height:40px;display:grid;place-items:center;border:0;background:none;color:#5f6b79;cursor:pointer}
.main-tabs,.sub-tabs{display:flex;align-items:stretch;gap:34px;padding:0 25px;border-bottom:1px solid #eef0f2}.main-tabs{height:45px}.sub-tabs{height:44px;gap:28px;overflow-x:auto;scrollbar-width:none}.sub-tabs::-webkit-scrollbar{display:none}.main-tab,.sub-tab{position:relative;flex:none;padding:0;border:0;background:none;color:#8995a3;white-space:nowrap;cursor:pointer}.main-tab{font-size:17px;font-weight:700}.sub-tab{font-size:14px;font-weight:600}.main-tab.active,.sub-tab.active{color:#101b28}.main-tab.active:after,.sub-tab.active:after{content:'';position:absolute;left:0;right:0;bottom:-1px;height:3px;border-radius:3px 3px 0 0;background:#73b100}
.orders-content{max-width:650px;margin:auto;padding:12px 12px 24px}.summary-card,.order-card{background:#fff;border:1px solid #e8ecf0;border-radius:15px;box-shadow:0 2px 12px #17212d05}.summary-card{padding:16px 15px 14px}.summary-heading{display:flex;justify-content:space-between;gap:12px;color:#8491a0;font-size:12px}.summary-primary{margin-top:8px;font-size:30px;line-height:1.15;font-weight:700;letter-spacing:-.6px;font-variant-numeric:tabular-nums}.summary-primary small{margin-left:5px;color:#758395;font-size:12px;font-weight:600;letter-spacing:0}.positive{color:#66aa00!important}.negative{color:#ee5264!important}.summary-stats{display:flex;justify-content:space-between;gap:12px;border-top:1px solid #e9edf1;margin-top:14px;padding-top:12px}.summary-stats>div{min-width:0}.summary-stats>div:last-child{text-align:right}.summary-stats span,.metric span,.detail-grid span{display:block;color:#8a96a4;font-size:11px;line-height:1.35}.summary-stats strong{display:block;margin-top:3px;font-size:12px;font-weight:650;white-space:nowrap;font-variant-numeric:tabular-nums}
.orders-filters{display:flex;align-items:center;gap:6px;padding:14px 0 9px}.filter-select{position:relative;display:block}.filter-select:after{content:'';position:absolute;right:11px;top:14px;width:6px;height:6px;border-right:1.5px solid #394655;border-bottom:1.5px solid #394655;transform:rotate(45deg);pointer-events:none}.filter-select select{appearance:none;max-width:150px;height:35px;padding:0 26px 0 10px;border:1px solid #e1e6eb;border-radius:8px;background:#fff;color:#253444;font-family:inherit;font-size:12px;font-weight:600;cursor:pointer}.filter-count{margin-left:auto;color:#95a0ac;font-size:11px}.sr-only{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
.orders-list{display:flex;flex-direction:column;gap:9px}.order-card{padding:15px 14px 0}.card-heading{display:flex;justify-content:space-between;gap:8px}.card-identity{min-width:0}.card-identity h2,.detail-identity h3{margin:0;font-size:17px;line-height:1.25;font-weight:750;letter-spacing:-.2px;white-space:nowrap}.card-tags{display:flex;align-items:center;flex-wrap:wrap;gap:4px;margin-top:5px}.card-tags span{display:inline-flex;align-items:center;min-height:18px;padding:1px 5px;border-radius:4px;font-size:10px;font-weight:700;line-height:1.2;white-space:nowrap}.direction-tag.long{background:#ecf7e8;color:#4da537}.direction-tag.short{background:#fff0f2;color:#e94c62}.card-tags .neutral-tag,.card-tags .status-tag{background:#f0f3f5;color:#637182}.card-tags .pending-tag,.card-tags .status-tag.pending-tag{background:#fff7e8;color:#bd8a1b}.card-tags .live-tag,.card-tags .status-tag.live-tag{background:#eff8e8;color:#63a500}.card-tags .manual-order-badge{background:#f2f4f7;color:#657586}.card-pnl{text-align:right;min-width:95px;font-variant-numeric:tabular-nums}.card-pnl strong{display:block;font-size:19px;line-height:1.1;font-weight:750}.card-pnl small{display:block;margin-top:3px;font-size:11px;font-weight:700}.card-pending{padding-top:2px;color:#bd8a1b;font-size:11px;font-weight:700}
.card-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px 6px;border-top:1px solid #edf0f2;margin-top:12px;padding:12px 0}.metric{min-width:0}.metric.right{text-align:right}.metric strong{display:block;margin-top:3px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px;font-weight:650;font-variant-numeric:tabular-nums}.countdown-block{margin-top:12px}.countdown-caption{display:flex;justify-content:space-between;font-size:11px;color:#8793a0}.countdown-caption strong{color:#6ca900;font-variant-numeric:tabular-nums}.countdown-track{height:3px;margin-top:5px;border-radius:3px;background:#edf1e8;overflow:hidden}.countdown-track span{display:block;height:100%;background:#78b500}.card-footer{display:flex;align-items:center;justify-content:space-between;gap:8px;min-height:39px;border-top:1px solid #edf0f2;color:#8793a0;font-size:11px}.card-footer>div{display:flex;align-items:center;gap:7px}.card-footer time{color:#506071;font-weight:600;font-variant-numeric:tabular-nums;white-space:nowrap}.order-share-entry{display:grid;place-items:center;width:28px;height:30px;padding:0;border:0;background:none;color:#8695a5;cursor:pointer}.card-actions{display:flex;gap:6px;border-top:1px solid #edf0f2;padding:8px 0 10px}.card-actions button,.detail-actions button{flex:1;height:33px;border-radius:7px;font:600 11px inherit;cursor:pointer}.subtle-action{border:1px solid #e1e6e9;background:#fff;color:#253341}.primary-action{border:1px solid #73b100;background:#73b100;color:#fff}.quiet-action{border:1px solid transparent;background:#f3f5f6;color:#4f5c69}.list-state{display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:180px;color:#8d9aa6;font-size:13px}.empty-icon{font-size:30px;margin-bottom:8px;color:#c6cfd6}
.order-detail-modal-overlay{position:fixed;z-index:1100;inset:0;display:flex;align-items:flex-end;justify-content:center;background:#141d26a6}.order-detail-modal{display:flex;flex-direction:column;width:100%;max-width:650px;max-height:88dvh;overflow:hidden;border-radius:20px 20px 0 0;background:#fff;box-shadow:0 -10px 35px #10182024}.sheet-handle{width:34px;height:4px;margin:8px auto 0;border-radius:3px;background:#dce2e5}.detail-header{position:relative;display:flex;align-items:center;justify-content:space-between;min-height:45px;padding:0 18px}.detail-header h2{font-size:15px;margin:0}.order-detail-modal-close{display:grid;place-items:center;width:28px;height:28px;border:0;border-radius:50%;background:#f2f4f6;color:#798694;font-size:21px;cursor:pointer}.detail-body{overflow:auto;padding:6px 18px 18px}.detail-identity{padding:4px 0 13px}.detail-focus{border-bottom:1px solid #edf0f2;padding-bottom:13px}.detail-focus>span{display:block;color:#8793a0;font-size:11px}.detail-focus strong{display:inline-block;margin-top:4px;font-size:27px;font-weight:750;font-variant-numeric:tabular-nums}.detail-focus small{margin-left:6px;font-size:11px;color:#82909c}.detail-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px 10px;padding:14px 0}.detail-grid strong{display:block;margin-top:3px;font-size:12px;font-weight:650;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.tpsl-section{border-top:1px solid #edf0f2;padding-top:13px}.tpsl-section h4{margin:0 0 8px;font-size:12px}.tpsl-row{display:flex;align-items:center;justify-content:space-between;min-height:32px;color:#8995a0;font-size:11px}.tpsl-row strong{color:#243240;font-size:12px}.tpsl-row input{accent-color:#73b100}.stepper{display:flex;align-items:center;margin:3px 0 7px;border:1px solid #e1e7e9;border-radius:7px;overflow:hidden}.stepper button{width:32px;height:32px;border:0;background:#f6f8f9;color:#61707d;font-size:17px}.stepper input{flex:1;min-width:0;height:32px;border:0;text-align:center;font-size:12px;outline:none}.detail-actions{display:flex;gap:6px;padding:11px 18px max(17px,env(safe-area-inset-bottom));border-top:1px solid #edf0f2}.detail-actions button{height:38px;font-size:12px}
button:focus-visible,select:focus-visible,input:focus-visible{outline:2px solid #73b100;outline-offset:2px}
.summary-stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));column-gap:10px}.summary-stats.two-stats{grid-template-columns:repeat(2,minmax(0,1fr))}.summary-stats>div:nth-child(2):not(:last-child){text-align:center}.summary-stats strong{font-size:11px;overflow:hidden;text-overflow:ellipsis}
.card-actions button,.detail-actions button{font-family:inherit;font-weight:600;font-size:11px}.detail-actions button{font-size:12px}
@media(max-width:360px){.sub-tabs{gap:20px;padding:0 16px}.orders-content{padding-left:9px;padding-right:9px}.order-card{padding-left:11px;padding-right:11px}.metric strong{font-size:11px}.summary-stats{gap:6px}.summary-stats strong{font-size:11px}}
</style>
