<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { init, dispose, type Chart } from 'klinecharts'
import { useMarketStore } from '@/store/market'
import { useLocaleStore } from '@/store/locale'
import type { KlineData } from '@/utils/ws'

const props = defineProps<{
  symbol: string // 交易对符号
  category?: string // 分类
  interval?: string // K线周期，默认1m
  height?: number // 图表高度
}>()

const emit = defineEmits<{
  (e: 'ready'): void
}>()

const chartContainer = ref<HTMLDivElement | null>(null)
const chart = ref<Chart | null>(null)
const marketStore = useMarketStore()
const localeStore = useLocaleStore()
const isMounted = ref(false)
const watchStopHandles: Array<() => void> = []


// MA 均线数据
const ma5Value = ref('')
const ma10Value = ref('')
const volume24h = ref('')

import request from '@/utils/request'

// 初始化图表
onMounted(async () => {
  // 从后台获取时区配置
  let systemTimezone = 'Europe/London'
  try {
    const res: any = await request.get('/user/system/timezone')
    if (res && res.timezone) {
      systemTimezone = res.timezone
      globalSystemTimezone = systemTimezone
      console.log('[KlineChart] Fetched timezone config:', systemTimezone)
    }
  } catch (e) {
    console.error('[KlineChart] Error fetching timezone config, using default:', e)
  }

  if (!chartContainer.value) {
    console.error('[KlineChart] chartContainer is null')
    return
  }

  // 等待DOM完全渲染
  await nextTick()
  await new Promise(resolve => setTimeout(resolve, 50))
  
  // 确保容器有尺寸
  if (chartContainer.value.clientWidth === 0 || chartContainer.value.clientHeight === 0) {
    console.warn('[KlineChart] Container has no dimensions, waiting...')
    await new Promise(resolve => setTimeout(resolve, 100))
  }

  // 创建K线图表
  const containerWidth = chartContainer.value.clientWidth || chartContainer.value.offsetWidth || 800
  const containerHeight = chartContainer.value.clientHeight || chartContainer.value.offsetHeight || 300
  const chartHeight = Math.max(containerHeight || 300, 300)
  
  console.log('[KlineChart] Creating chart with dimensions:', { 
    width: containerWidth, 
    height: chartHeight
  })
  
  if (containerWidth === 0 || chartHeight === 0) {
    console.error('[KlineChart] Invalid container dimensions, cannot create chart')
    return
  }
  
  try {
    // 初始化klinecharts
    const containerId = `kline-chart-${Date.now()}`
    chartContainer.value.id = containerId
    
    chart.value = init(containerId, {
      width: containerWidth,
      height: chartHeight,
      timezone: systemTimezone
    })
    
    if (!chart.value) {
      console.error('[KlineChart] Failed to create chart instance')
      return
    }
    
    // 设置主题样式（浅色主题）
    chart.value.setStyleOptions({
      grid: {
        show: true,
        horizontal: {
          show: true,
          color: '#e0e0e0',
          style: 'dashed'
        },
        vertical: {
          show: true,
          color: '#e0e0e0',
          style: 'dashed'
        }
      },
      candle: {
        priceMark: {
          show: true,
          high: {
            show: true,
            color: '#929AA5',
            textMargin: 5
          },
          low: {
            show: true,
            color: '#929AA5',
            textMargin: 5
          },
          last: {
            show: true,
            upColor: '#02C289',
            downColor: '#E86D43',
            noChangeColor: '#929AA5',
            textMargin: 5
          }
        },
        tooltip: {
          showRule: 'always',
          labels: [
            localeStore.t('chartTime'),
            localeStore.t('chartOpen'),
            localeStore.t('chartClose'),
            localeStore.t('chartHigh'),
            localeStore.t('chartLow')
          ],
          values: (kLineData: any) => {
            return [
              { value: formatTime(kLineData.timestamp) },
              { value: kLineData.open.toFixed(5) },
              { value: kLineData.close.toFixed(5) },
              { value: kLineData.high.toFixed(5) },
              { value: kLineData.low.toFixed(5) }
            ]
          }
        },
        bar: {
          upColor: '#02C289',
          downColor: '#E86D43',
          noChangeColor: '#929AA5'
        }
      },
      indicator: {
        tooltip: {
          showRule: 'always'
        }
      },
      xAxis: {
        show: true,
        axisLine: {
          show: true,
          color: '#e0e0e0'
        },
        tickLine: {
          show: true,
          color: '#e0e0e0'
        },
        tickText: {
          show: true,
          color: '#76808F',
          size: 12
        }
      },
      yAxis: {
        show: true,
        axisLine: {
          show: true,
          color: '#e0e0e0'
        },
        tickLine: {
          show: true,
          color: '#e0e0e0'
        },
        tickText: {
          show: true,
          color: '#76808F',
          size: 12
        }
      },
      separator: {
        color: '#e0e0e0'
      }
    })
    
    // 创建MA5和MA10均线指标
    chart.value.createTechnicalIndicator({
      name: 'MA',
      calcParams: [5, 10],
      styles: {
        line: [
          {
            color: 'rgba(255, 0, 0, 1)', // MA5 红色
            size: 1
          },
          {
            color: 'rgba(25, 117, 0, 1)', // MA10 绿色
            size: 1
          }
        ]
      }
    }, false, {
      id: 'candle_pane'
    })
    
    // 创建成交量指标
    chart.value.createTechnicalIndicator({
      name: 'VOL',
      styles: {
        bar: {
          upColor: 'rgba(2,194,137,.5)',
          downColor: 'rgba(232,109,67,.5)',
          noChangeColor: '#929AA5'
        },
        line: {
          show: false
        }
      }
    }, false, {
      height: 80
    })
    
    console.log('[KlineChart] Chart created successfully')
    isMounted.value = true
    
    // 加载历史K线数据
    await loadHistoricalKlines()
    
    emit('ready')
  } catch (e) {
    console.error('[KlineChart] Failed to create chart:', e)
    return
  }
  
  // 监听实时价格更新
  const stopWatch1 = watch(
    () => marketStore.getPrice(props.symbol),
    (newPrice) => {
      if (!isMounted.value || !chart.value) return
      if (newPrice > 0) {
        try {
          updateLastCandle(newPrice)
        } catch (e) {
          console.error('[KlineChart] Error updating price:', e)
        }
      }
    }
  )
  watchStopHandles.push(stopWatch1)
  
  // 监听K线数据更新
  const stopWatch2 = watch(
    () => {
      const klines = marketStore.getKlines(props.symbol, 100)
      return klines
    },
    (klines) => {
      if (!isMounted.value || !chart.value) return
      if (klines && klines.length > 0) {
        try {
          updateKlines(klines)
        } catch (e) {
          console.error('[KlineChart] Error updating klines:', e)
        }
      }
    },
    { deep: true, immediate: true }
  )
  watchStopHandles.push(stopWatch2)
  
  // 监听symbol和interval变化，重新加载K线
  const stopWatch3 = watch(
    () => [props.symbol, props.interval],
    async ([newSymbol, newInterval], [oldSymbol, oldInterval]) => {
      if (!isMounted.value || !chart.value) return
      if (newSymbol && (newSymbol !== oldSymbol || newInterval !== oldInterval)) {
        try {
          console.log(`[KlineChart] Symbol/Interval changed: ${oldSymbol}/${oldInterval} -> ${newSymbol}/${newInterval}, reloading klines`)
          await loadHistoricalKlines()
        } catch (e) {
          console.error('[KlineChart] Error reloading klines:', e)
        }
      }
    },
    { immediate: false }
  )
  watchStopHandles.push(stopWatch3)
  
  // 监听语言变化，更新图表tooltip labels
  const stopWatch4 = watch(
    () => localeStore.getCurrentLocale(),
    () => {
      if (!isMounted.value || !chart.value) return
      try {
        // 更新tooltip labels
        chart.value.setStyleOptions({
          candle: {
            tooltip: {
              showRule: 'always',
              labels: [
                localeStore.t('chartTime'),
                localeStore.t('chartOpen'),
                localeStore.t('chartClose'),
                localeStore.t('chartHigh'),
                localeStore.t('chartLow')
              ]
            }
          }
        })
      } catch (e) {
        console.error('[KlineChart] Error updating tooltip labels:', e)
      }
    }
  )
  watchStopHandles.push(stopWatch4)
})

onUnmounted(() => {
  isMounted.value = false
  
  // 停止所有watch监听器
  watchStopHandles.forEach(stop => stop())
  watchStopHandles.length = 0
  
  // 移除窗口大小监听
  if (resizeHandler) {
    window.removeEventListener('resize', resizeHandler)
    resizeHandler = null
  }
  
  // 清理图表
  try {
    if (chart.value && chartContainer.value?.id) {
      dispose(chartContainer.value.id)
      chart.value = null
    }
  } catch (e) {
    console.error('[KlineChart] Error disposing chart:', e)
  }
})

// 全局存储获取到的系统时区
let globalSystemTimezone = 'Europe/London'

// 格式化时间（使用系统配置时区）
function formatTime(timestamp: number): string {
  const date = new Date(timestamp)
  // 使用 Intl.DateTimeFormat 格式化时间
  const formatter = new Intl.DateTimeFormat('en-GB', {
    timeZone: globalSystemTimezone, // 使用动态获取的时区（自动处理夏令时）
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
  const parts = formatter.formatToParts(date)
  const year = parts.find(p => p.type === 'year')?.value || ''
  const month = parts.find(p => p.type === 'month')?.value || ''
  const day = parts.find(p => p.type === 'day')?.value || ''
  const hour = parts.find(p => p.type === 'hour')?.value || ''
  const minute = parts.find(p => p.type === 'minute')?.value || ''
  return `${year}-${month}-${day} ${hour}:${minute}`
}

// 加载历史K线数据
async function loadHistoricalKlines() {
  try {
    const interval = props.interval || '1m'
    console.log(`[KlineChart] Loading klines for symbol: ${props.symbol}, interval: ${interval}`)
    
    // 先检查store中是否有数据
    let klines = marketStore.getKlines(props.symbol, 100)
    const storeKlineCount = klines?.length || 0
    console.log(`[KlineChart] Klines from store: ${storeKlineCount}`)
    
    // 如果store中没有数据，或者数据量不足（少于50条），从后端获取
    if (!klines || klines.length < 50) {
      if (storeKlineCount > 0) {
        console.log(`[KlineChart] Store has only ${storeKlineCount} klines (need more), fetching from API...`)
      } else {
        console.log(`[KlineChart] No klines in store, fetching from API...`)
      }
      // 强制从HTTP接口获取，传入较大的limit确保获取足够的数据
      await marketStore.fetchKlines(props.symbol, props.category || 'Crypto', interval, 200)
      klines = marketStore.getKlines(props.symbol, 200)
      console.log(`[KlineChart] Klines after fetch: ${klines?.length || 0}`)
    } else {
      console.log(`[KlineChart] Store has sufficient klines (${storeKlineCount}), using cached data`)
    }
    
    if (klines && klines.length > 0) {
      console.log(`[KlineChart] Updating chart with ${klines.length} klines`)
      updateKlines(klines)
    } else {
      console.warn(`[KlineChart] No klines available for ${props.symbol}`)
    }
  } catch (error) {
    console.error('[KlineChart] Load historical klines error:', error)
  }
}

// 更新K线数据
function updateKlines(klines: KlineData[]) {
  if (!isMounted.value || !chart.value) {
    console.warn('[KlineChart] Cannot update klines: chart not ready')
    return
  }
  
  if (!klines || klines.length === 0) {
    console.warn('[KlineChart] Cannot update klines: no data provided')
    return
  }
  
  console.log(`[KlineChart] Received ${klines.length} klines to update`)
  
  // 过滤无效数据
  const validKlines = klines.filter((k, index) => {
    const timestamp = Number(k.timestamp || 0)
    const open = Number(k.open || 0)
    const high = Number(k.high || 0)
    const low = Number(k.low || 0)
    const close = Number(k.close || 0)
    
    if (timestamp <= 0 || open <= 0 || high <= 0 || low <= 0 || close <= 0) {
      if (index < 5 || index >= klines.length - 5) {
        console.warn(`[KlineChart] Invalid kline filtered [${index}]:`, { timestamp, open, high, low, close })
      }
      return false
    }
    
    const maxPrice = Math.max(open, close)
    const minPrice = Math.min(open, close)
    
    const isValid = high >= low && high >= minPrice && low <= maxPrice
    
    if (!isValid && (index < 5 || index >= klines.length - 5)) {
      console.warn(`[KlineChart] Invalid kline filtered (logic error) [${index}]:`, {
        timestamp, open, high, low, close, maxPrice, minPrice
      })
    }
    
    return isValid
  })
  
  if (validKlines.length === 0) {
    console.warn('[KlineChart] No valid kline data after filtering')
    return
  }
  
  console.log(`[KlineChart] ${validKlines.length} valid klines after filtering`)
  
  // 转换为klinecharts格式的数据
  // klinecharts需要的数据格式：{ timestamp, open, high, low, close, volume }
  // 时间戳需要是毫秒级
  const chartData = validKlines.map((k) => {
    const timestamp = k.timestamp < 10000000000 ? k.timestamp * 1000 : k.timestamp
    
    return {
      timestamp: timestamp,
      open: Number(k.open),
      high: Number(k.high),
      low: Number(k.low),
      close: Number(k.close),
      volume: Number(k.volume || 0)
    }
  })
  
  // 按时间戳排序
  chartData.sort((a, b) => a.timestamp - b.timestamp)
  
  try {
    // 清除旧数据并应用新数据
    chart.value.clearData()
    chart.value.applyNewData(chartData)
    
    // 应用新数据后，如果有实时价格，立即更新最后一根K线
    const currentPrice = marketStore.getPrice(props.symbol)
    if (currentPrice > 0 && chartData.length > 0) {
      // 延迟一下，确保图表已经渲染
      setTimeout(() => {
        updateLastCandle(currentPrice)
      }, 100)
    }
    
    // 计算MA5和MA10的值（用于显示）
    if (validKlines.length >= 5) {
      const last5 = validKlines.slice(-5)
      const ma5 = last5.reduce((sum, k) => sum + Number(k.close), 0) / 5
      ma5Value.value = ma5.toFixed(5)
    }
    
    if (validKlines.length >= 10) {
      const last10 = validKlines.slice(-10)
      const ma10 = last10.reduce((sum, k) => sum + Number(k.close), 0) / 10
      ma10Value.value = ma10.toFixed(5)
    }
    
    // 计算24小时成交量
    const totalVolume = validKlines.reduce((sum, k) => sum + Number(k.volume || 0), 0)
    volume24h.value = totalVolume.toFixed(2)
    
    console.log('[KlineChart] ✅ K-line data set successfully')
  } catch (error) {
    console.error('[KlineChart] ❌ Failed to set kline data:', error)
  }
}

// 更新最后一根K线（实时价格更新）
function updateLastCandle(price: number) {
  if (!isMounted.value || !chart.value) {
    console.warn('[KlineChart] Cannot update last candle: chart not ready')
    return
  }

  const klines = marketStore.getKlines(props.symbol, 200)
  const interval = props.interval || '1m'
  const intervalMs = getIntervalMs(interval)
  const now = Date.now()
  
  if (klines && klines.length > 0) {
    const lastKline = klines[klines.length - 1]
    if (!lastKline) {
      console.warn('[KlineChart] No last kline found')
      return
    }
    
    const lastTime = lastKline.timestamp < 10000000000 ? lastKline.timestamp * 1000 : lastKline.timestamp
    const timeDiff = now - lastTime

    // 如果是同一周期内的数据，更新最后一根K线
    if (timeDiff < intervalMs) {
      const updatedKline = {
        timestamp: lastTime,
        open: lastKline.open,
        high: Math.max(lastKline.high, price),
        low: Math.min(lastKline.low, price),
        close: price,
        volume: lastKline.volume || 0
      }
      
      // 更新图表
      try {
        chart.value?.updateData(updatedKline)
        console.log(`[KlineChart] ✅ Updated last candle: ${props.symbol} @ ${price}, high=${updatedKline.high}, low=${updatedKline.low}`)
      } catch (e) {
        console.error('[KlineChart] Error updating chart data:', e)
      }
      
      // 同步更新store中的K线数据
      const updatedKlineData: KlineData = {
        symbol: props.symbol,
        timestamp: lastTime,
        open: lastKline.open,
        high: Math.max(lastKline.high, price),
        low: Math.min(lastKline.low, price),
        close: price,
        volume: lastKline.volume || 0,
        interval: interval
      }
      
      // 更新store中的最后一根K线
      const storeKlines = marketStore.klineDataMap[props.symbol] || []
      if (storeKlines.length > 0) {
        storeKlines[storeKlines.length - 1] = updatedKlineData
        // 触发响应式更新（通过重新赋值整个数组）
        marketStore.klineDataMap[props.symbol] = [...storeKlines]
      } else {
        // 如果没有数据，初始化
        marketStore.klineDataMap[props.symbol] = [updatedKlineData]
      }
    } else {
      // 新的周期，创建新的K线
      const newKline = {
        timestamp: now,
        open: price,
        high: price,
        low: price,
        close: price,
        volume: 0
      }
      
      // 更新图表
      try {
        chart.value?.updateData(newKline)
        console.log(`[KlineChart] ✅ Created new candle: ${props.symbol} @ ${price}, new period started`)
      } catch (e) {
        console.error('[KlineChart] Error creating new candle:', e)
      }
      
      // 同步更新store中的K线数据
      const newKlineData: KlineData = {
        symbol: props.symbol,
        timestamp: now,
        open: price,
        high: price,
        low: price,
        close: price,
        volume: 0,
        interval: interval
      }
      
      // 添加到store中
      const storeKlines = marketStore.klineDataMap[props.symbol] || []
      storeKlines.push(newKlineData)
      // 只保留最近200根K线
      const trimmedKlines = storeKlines.slice(-200)
      marketStore.klineDataMap[props.symbol] = trimmedKlines
    }
  } else {
    // 如果没有历史数据，先尝试加载历史数据
    console.warn(`[KlineChart] No kline data found for ${props.symbol}, attempting to load...`)
    loadHistoricalKlines().then(() => {
      // 加载完成后，再次尝试更新
      const updatedKlines = marketStore.getKlines(props.symbol, 1)
      if (updatedKlines && updatedKlines.length > 0) {
        updateLastCandle(price)
      } else {
        // 如果仍然没有数据，创建新的K线
        const newKline = {
          timestamp: now,
          open: price,
          high: price,
          low: price,
          close: price,
          volume: 0
        }
        
        try {
          chart.value?.updateData(newKline)
          console.log(`[KlineChart] ✅ Created initial candle: ${props.symbol} @ ${price}`)
        } catch (e) {
          console.error('[KlineChart] Error creating initial candle:', e)
        }
        
        // 同步更新store中的K线数据
        const newKlineData: KlineData = {
          symbol: props.symbol,
          timestamp: now,
          open: price,
          high: price,
          low: price,
          close: price,
          volume: 0,
          interval: interval
        }
        
        const existingKlines = marketStore.klineDataMap[props.symbol] || []
        existingKlines.push(newKlineData)
        marketStore.klineDataMap[props.symbol] = [...existingKlines]
      }
    }).catch((error) => {
      console.error('[KlineChart] Failed to load historical klines:', error)
    })
  }
}

// 获取周期的毫秒数
function getIntervalMs(interval: string): number {
  const intervalMap: Record<string, number> = {
    '1m': 60 * 1000,
    '5m': 5 * 60 * 1000,
    '15m': 15 * 60 * 1000,
    '30m': 30 * 60 * 1000,
    '1h': 60 * 60 * 1000,
    '4h': 4 * 60 * 60 * 1000,
    '1d': 24 * 60 * 60 * 1000
  }
  return intervalMap[interval] || 60 * 1000
}

// 监听窗口大小变化
function handleResize() {
  if (!isMounted.value || !chart.value || !chartContainer.value) return
  try {
    // klinecharts v8版本resize方法不需要参数，会自动从DOM获取尺寸
    chart.value.resize()
  } catch (e) {
    console.error('[KlineChart] Error resizing chart:', e)
  }
}

// 窗口大小变化监听（在onMounted内部设置）
let resizeHandler: (() => void) | null = null
onMounted(() => {
  resizeHandler = handleResize
  window.addEventListener('resize', resizeHandler)
})
</script>

<template>
  <div class="chart-wrapper">
    <!-- MA指标显示 -->
    <div class="ma-indicators">
      <span class="ma-item ma5">{{ localeStore.t('chartMA5') }}: {{ ma5Value || '--' }}</span>
      <span class="ma-item ma10">{{ localeStore.t('chartMA10') }}: {{ ma10Value || '--' }}</span>
    </div>
    
    <!-- K线图 -->
    <div ref="chartContainer" class="kline-chart" :style="{ width: '100%', height: (height || 300) + 'px' }"></div>
    
    <!-- 成交量显示 -->
    <div class="volume-indicator">
      <span>{{ localeStore.t('chart24H') }}: {{ volume24h || '--' }}</span>
    </div>
  </div>
</template>

<style scoped>
.chart-wrapper {
  width: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
  min-height: 400px;
}

.ma-indicators {
  display: flex;
  gap: 16px;
  padding: 8px 16px;
  font-size: 14px;
  background: #f5f5f5;
  border-bottom: 1px solid #e0e0e0;
}

.ma-item {
  font-weight: 500;
}

.ma5 {
  color: rgba(255, 0, 0, 1); /* 红色 */
}

.ma10 {
  color: rgba(25, 117, 0, 1); /* 绿色 */
}

.kline-chart {
  width: 100% !important;
  height: 300px !important;
  min-height: 300px !important;
  border-bottom: 2px solid #e0e0e0;
  position: relative;
  overflow: hidden;
  display: block;
}

.volume-indicator {
  padding: 8px 16px;
  font-size: 14px;
  color: rgba(255, 0, 0, 1);
  background: #f5f5f5;
  border-bottom: 1px solid #e0e0e0;
}
</style>
