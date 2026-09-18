/**
 * 请求限流工具
 * 规则：
 * 1. 每1秒只能1次请求
 * 2. /batch-kline接口需间隔3秒
 * 3. 所有接口相加，1分钟最大请求60次(1秒1次)
 * 4. 每天总共最大可请求86400次，超过则第二天凌晨恢复使用
 */

interface RequestQueueItem {
  resolve: (value: any) => void
  reject: (error: any) => void
  url: string
  options?: RequestInit
  timestamp: number
}

class RateLimiter {
  private requestQueue: RequestQueueItem[] = []
  private isProcessing = false
  private lastRequestTime = 0
  private lastBatchKlineTime = 0
  private requestCountPerMinute = 0
  private requestCountPerDay = 0
  private lastMinuteResetTime = Date.now()
  private lastDayResetTime = this.getTodayStartTime()
  private readonly MIN_INTERVAL = 1000 // 1秒
  private readonly BATCH_KLINE_INTERVAL = 3000 // 3秒
  private readonly MAX_PER_MINUTE = 60
  private readonly MAX_PER_DAY = 86400

  private getTodayStartTime(): number {
    const now = new Date()
    now.setHours(0, 0, 0, 0)
    return now.getTime()
  }

  private resetCountersIfNeeded(): void {
    const now = Date.now()
    
    // 每分钟重置计数器
    if (now - this.lastMinuteResetTime >= 60000) {
      this.requestCountPerMinute = 0
      this.lastMinuteResetTime = now
    }
    
    // 每天重置计数器
    const todayStart = this.getTodayStartTime()
    if (todayStart > this.lastDayResetTime) {
      this.requestCountPerDay = 0
      this.lastDayResetTime = todayStart
    }
  }

  private isBatchKlineRequest(url: string): boolean {
    return url.includes('/batch-kline') || url.includes('/kline/batch')
  }

  private async waitForNextRequest(isBatchKline: boolean): Promise<void> {
    const now = Date.now()
    const timeSinceLastRequest = now - this.lastRequestTime
    const timeSinceLastBatchKline = now - this.lastBatchKlineTime
    
    let waitTime = 0
    
    if (isBatchKline) {
      // batch-kline需要间隔3秒
      if (timeSinceLastBatchKline < this.BATCH_KLINE_INTERVAL) {
        waitTime = this.BATCH_KLINE_INTERVAL - timeSinceLastBatchKline
      }
    } else {
      // 普通请求需要间隔1秒
      if (timeSinceLastRequest < this.MIN_INTERVAL) {
        waitTime = this.MIN_INTERVAL - timeSinceLastRequest
      }
    }
    
    if (waitTime > 0) {
      await new Promise(resolve => setTimeout(resolve, waitTime))
    }
  }

  private async processQueue(): Promise<void> {
    if (this.isProcessing || this.requestQueue.length === 0) {
      return
    }
    
    this.isProcessing = true
    
    while (this.requestQueue.length > 0) {
      this.resetCountersIfNeeded()
      
      // 检查每分钟限制
      if (this.requestCountPerMinute >= this.MAX_PER_MINUTE) {
        const waitTime = 60000 - (Date.now() - this.lastMinuteResetTime)
        if (waitTime > 0) {
          console.log(`[RateLimiter] 达到每分钟限制，等待 ${waitTime}ms`)
          await new Promise(resolve => setTimeout(resolve, waitTime))
          this.resetCountersIfNeeded()
        }
      }
      
      // 检查每天限制
      if (this.requestCountPerDay >= this.MAX_PER_DAY) {
        const tomorrowStart = this.getTodayStartTime() + 86400000
        const waitTime = tomorrowStart - Date.now()
        if (waitTime > 0) {
          console.log(`[RateLimiter] 达到每天限制，等待到明天凌晨`)
          await new Promise(resolve => setTimeout(resolve, waitTime))
          this.resetCountersIfNeeded()
        }
      }
      
      const item = this.requestQueue.shift()!
      const isBatchKline = this.isBatchKlineRequest(item.url)
      
      // 等待合适的间隔
      await this.waitForNextRequest(isBatchKline)
      
      try {
        // 执行请求
        const response = await fetch(item.url, item.options)
        const data = await response.json()
        
        // 更新计数器
        this.lastRequestTime = Date.now()
        if (isBatchKline) {
          this.lastBatchKlineTime = Date.now()
        }
        this.requestCountPerMinute++
        this.requestCountPerDay++
        
        item.resolve(data)
      } catch (error) {
        item.reject(error)
      }
    }
    
    this.isProcessing = false
  }

  async request(url: string, options?: RequestInit): Promise<any> {
    return new Promise((resolve, reject) => {
      this.requestQueue.push({
        resolve,
        reject,
        url,
        options,
        timestamp: Date.now(),
      })
      
      this.processQueue()
    })
  }
}

// 单例
export const rateLimiter = new RateLimiter()

// 封装的fetch函数
export async function rateLimitedFetch(url: string, options?: RequestInit): Promise<Response> {
  const data = await rateLimiter.request(url, options)
  // 返回一个模拟的Response对象
  return {
    ok: true,
    status: 200,
    json: async () => data,
    text: async () => JSON.stringify(data),
  } as Response
}



