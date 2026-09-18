/**
 * 阿里云市场行情服务（HTTP轮询）
 * 替换 Alltick WebSocket
 */

export interface PriceData {
  code: string
  symbol?: string
  price: number
  change24h: number
  changePct24h: number
  price24hAgo: number
  timestamp?: number
}

class AliyunMarketService {
  private pollingInterval = 15000 // 15秒轮询一次（实时价格更新）
  private batchPollingTimer: number | null = null // 批量轮询定时器
  private priceCallbacks = new Map<string, (data: PriceData) => void>()
  private subscribedSymbols = new Set<string>() // 已订阅的symbol列表
  private apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
  
  /**
   * 订阅实时价格（添加到订阅列表，使用批量轮询）
   */
  subscribePrice(symbol: string, callback: (data: PriceData) => void) {
    // 保存回调函数和symbol
    this.priceCallbacks.set(symbol, callback)
    this.subscribedSymbols.add(symbol)
    
    // 启动批量轮询（如果还没有启动）
    // 注意：不立即调用 fetchBatchPrices()，避免批量订阅时触发多次请求
    // 批量轮询会在定时器中统一处理
    this.startBatchPolling()
    
    // 使用防抖机制，延迟批量请求，避免批量订阅时立即触发多次请求
    this.debouncedBatchFetch()
  }
  
  // 防抖定时器
  private debounceTimer: number | null = null
  
  /**
   * 防抖批量请求（批量订阅时，延迟执行，合并多次调用）
   */
  private debouncedBatchFetch() {
    // 清除之前的定时器
    if (this.debounceTimer !== null) {
      clearTimeout(this.debounceTimer)
    }
    
    // 延迟100ms执行，如果在这100ms内有新的订阅，会合并为一次请求
    this.debounceTimer = window.setTimeout(() => {
      this.debounceTimer = null
      // 只有在有订阅时才请求
      if (this.subscribedSymbols.size > 0) {
        this.fetchBatchPrices()
      }
    }, 100)
  }
  
  /**
   * 取消订阅
   */
  unsubscribePrice(symbol: string) {
    this.priceCallbacks.delete(symbol)
    this.subscribedSymbols.delete(symbol)
    
    // 清除防抖定时器
    if (this.debounceTimer !== null) {
      clearTimeout(this.debounceTimer)
      this.debounceTimer = null
    }
    
    // 如果没有订阅了，停止批量轮询
    if (this.subscribedSymbols.size === 0) {
      this.stopBatchPolling()
    }
  }
  
  /**
   * 取消所有订阅
   */
  unsubscribeAll() {
    this.priceCallbacks.clear()
    this.subscribedSymbols.clear()
    
    // 清除防抖定时器
    if (this.debounceTimer !== null) {
      clearTimeout(this.debounceTimer)
      this.debounceTimer = null
    }
    
    this.stopBatchPolling()
  }
  
  /**
   * 启动批量轮询
   */
  private startBatchPolling() {
    // 如果已经在轮询，不重复启动
    if (this.batchPollingTimer !== null) {
      return
    }
    
    // 定时批量轮询
    this.batchPollingTimer = window.setInterval(() => {
      this.fetchBatchPrices()
    }, this.pollingInterval)
    
    console.log('[AliyunMarket] ✅ Started batch polling')
  }
  
  /**
   * 停止批量轮询
   */
  private stopBatchPolling() {
    if (this.batchPollingTimer !== null) {
      clearInterval(this.batchPollingTimer)
      this.batchPollingTimer = null
      console.log('[AliyunMarket] ✅ Stopped batch polling')
    }
  }
  
  /**
   * 批量获取实时价格
   */
  private async fetchBatchPrices() {
    const symbols = Array.from(this.subscribedSymbols)
    
    if (symbols.length === 0) {
      return
    }
    
    try {
      const response = await fetch(`${this.apiBaseUrl}/market/price/batch`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ symbols })
      })
      
      const result = await response.json()
      
      if (result.ret === 200 && result.data) {
        // 调用每个symbol的回调函数
        for (const [symbol, data] of Object.entries(result.data)) {
          const callback = this.priceCallbacks.get(symbol)
          if (callback && data) {
            callback(data as PriceData)
          }
        }
      }
    } catch (error) {
      console.error('[AliyunMarket] Failed to fetch batch prices:', error)
    }
  }
  
  /**
   * 批量获取实时价格（一次性请求，不订阅）
   */
  async getBatchPrices(symbols: string[]): Promise<Map<string, PriceData>> {
    try {
      const response = await fetch(`${this.apiBaseUrl}/market/price/batch`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ symbols })
      })
      const result = await response.json()
      
      if (result.ret === 200 && result.data) {
        const prices = new Map<string, PriceData>()
        for (const [symbol, data] of Object.entries(result.data)) {
          prices.set(symbol, data as PriceData)
        }
        return prices
      }
    } catch (error) {
      console.error('[AliyunMarket] Failed to fetch batch prices:', error)
    }
    return new Map()
  }
  
  /**
   * 设置轮询间隔（毫秒）
   */
  setPollingInterval(interval: number) {
    const wasPolling = this.batchPollingTimer !== null
    
    // 更新间隔
    this.pollingInterval = interval
    
    // 如果正在轮询，重启批量轮询以应用新的间隔
    if (wasPolling) {
      this.stopBatchPolling()
      this.startBatchPolling()
    }
  }
  
  /**
   * 获取当前轮询间隔
   */
  getPollingInterval(): number {
    return this.pollingInterval
  }
}

export default new AliyunMarketService()

