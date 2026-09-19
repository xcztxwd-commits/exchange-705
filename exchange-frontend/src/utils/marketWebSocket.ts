/**
 * 市场数据 WebSocket 客户端
 * 连接到后端 WebSocket 服务器，接收实时价格推送
 */

export interface PriceUpdate {
  [symbol: string]: {
    price: number
    change24h: number
    changePct24h: number
    price24hAgo?: number
    status?: string
    available?: boolean
    stale?: boolean
    fetchedAt?: number
    expiresAt?: number
    timestamp?: number
  }
}

// Match the server's 15-second freshness window even for legacy payloads.
export function normalizeQuote(quote: PriceUpdate[string], now = Date.now()) {
  const milliseconds = (value: unknown) => { const n = Number(value); return n < 10_000_000_000 ? n * 1000 : n }
  const timestamp = milliseconds(quote.timestamp)
  if (!Number.isFinite(quote.price) || quote.price <= 0 || !Number.isFinite(timestamp) || timestamp <= 0 || timestamp > now + 5000) return null
  const fetchedAt = quote.fetchedAt == null ? timestamp : milliseconds(quote.fetchedAt)
  const expiresAt = Math.min(timestamp + 15_000, fetchedAt + 15_000, quote.expiresAt == null ? Infinity : milliseconds(quote.expiresAt))
  if (!Number.isFinite(fetchedAt) || !Number.isFinite(expiresAt)) return null
  const status = quote.status === 'unavailable' ? 'unavailable'
    : quote.status === 'stale' || quote.stale || now >= expiresAt ? 'stale'
    : quote.available === false || (quote.status != null && quote.status !== 'available') ? 'unavailable' : 'available'
  return { ...quote, timestamp, fetchedAt, expiresAt, status }
}

class MarketWebSocket {
  private ws: WebSocket | null = null
  private subscribedSymbols = new Set<string>()
  private reconnectTimer: number | null = null
  private heartbeatTimer: number | null = null
  private reconnectAttempts = 0
  private maxReconnectAttempts = 10
  private reconnectDelay = 3000
  private isConnecting = false
  private stopped = false
  private pongTimer: number | null = null
  private connectedCallbacks = new Set<() => void>()

  
  // 价格更新回调
  private priceUpdateCallbacks = new Map<string, (prices: PriceUpdate) => void>()
  
  /**
   * 连接 WebSocket
   */
  connect(): Promise<void> {
    this.stopped = false
    this.stopReconnect()
    if (this.ws?.readyState === WebSocket.OPEN) {
      return Promise.resolve()
    }
    
    if (this.isConnecting) {
      return Promise.resolve()
    }
    
    this.isConnecting = true
    
    return new Promise((resolve, reject) => {
      try {
        // 开发环境直连后端，生产环境使用站点反向代理。
        let wsUrl: string
        const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

        if (apiBaseUrl.startsWith('/')) {
          const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
          const host = import.meta.env.DEV ? `${window.location.hostname}:8080` : window.location.host
          wsUrl = `${protocol}//${host}${apiBaseUrl}/ws/market`
        } else {
          // 生产环境：使用配置的绝对路径
          wsUrl = apiBaseUrl.replace(/^https?/, (match: string) => match === 'https' ? 'wss' : 'ws') + '/ws/market'
        }
        console.log('[Market WS] Connecting to:', wsUrl)
        
        const socket = new WebSocket(wsUrl)
        this.ws = socket
        
        this.ws.onopen = () => {
          if (this.ws !== socket || this.stopped) { socket.close(); return }
          console.log('[Market WS] ✅ Connected')
          this.isConnecting = false
          this.reconnectAttempts = 0
          
          // 重新订阅
          if (this.subscribedSymbols.size > 0) {
            this.subscribe(Array.from(this.subscribedSymbols))
          }
          
          // 启动心跳
          this.startHeartbeat()
          this.connectedCallbacks.forEach(callback => callback())
          
          resolve()
        }
        
        this.ws.onmessage = (event) => {
          if (this.ws !== socket) return
          try {
            const data = JSON.parse(event.data)
            
            if (data.type === 'price' && data.data) {
              // 价格更新
              this.handlePriceUpdate(data.data)
            } else if (data.type === 'subscribed') {
              console.log('[Market WS] ✅ Subscribed:', data.symbols)
            } else if (data.type === 'pong') {
              if (this.pongTimer !== null) clearTimeout(this.pongTimer)
              this.pongTimer = null
            }
          } catch (error) {
            console.error('[Market WS] Error parsing message:', error)
          }
        }
        
        this.ws.onerror = (error) => {
          if (this.ws !== socket) return
          console.error('[Market WS] ❌ Error:', error)
          this.isConnecting = false
          reject(error)
        }
        
        this.ws.onclose = () => {
          reject(new Error('Market WebSocket closed'))
          if (this.ws !== socket) return
          this.ws = null
          console.log('[Market WS] 🔌 Closed')
          this.isConnecting = false
          this.stopHeartbeat()
          
          // 自动重连
          if (this.stopped) return
          if (this.reconnectAttempts < this.maxReconnectAttempts) {
            this.reconnectAttempts++
            const delay = Math.min(this.reconnectDelay * Math.pow(1.5, this.reconnectAttempts - 1), 30000)
            console.log(`[Market WS] Will reconnect in ${delay}ms (attempt ${this.reconnectAttempts}/${this.maxReconnectAttempts})`)
            
            this.reconnectTimer = window.setTimeout(() => {
              this.connect().catch(err => {
                console.error('[Market WS] Reconnect failed:', err)
              })
            }, delay)
          } else {
            console.error('[Market WS] Max reconnect attempts reached')
          }
        }
      } catch (error) {
        this.isConnecting = false
        reject(error)
      }
    })
  }
  
  /**
   * 断开连接
   */
  disconnect() {
    this.stopped = true
    this.isConnecting = false
    this.stopHeartbeat()
    this.stopReconnect()
    
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    
    this.subscribedSymbols.clear()
    console.log('[Market WS] ✅ Disconnected')
  }
  
  /**
   * 订阅 symbol 列表
   */
  subscribe(symbols: string[]) {
    if (symbols.length === 0) {
      return
    }
    
    // 添加到订阅列表
    symbols.forEach(s => this.subscribedSymbols.add(s))
    
    // 如果未连接，先连接
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      this.connect().catch(err => {
        console.error('[Market WS] Connect failed:', err)
      })
      return
    }
    
    // 发送订阅消息
    this.send({
      action: 'subscribe',
      symbols: symbols
    })
    
    console.log('[Market WS] 📤 Subscribed:', symbols)
  }
  
  /**
   * 取消订阅 symbol 列表
   */
  unsubscribe(symbols: string[]) {
    symbols.forEach(s => this.subscribedSymbols.delete(s))
    
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.send({
        action: 'unsubscribe',
        symbols: symbols
      })
    }
  }
  
  /**
   * 取消所有订阅
   */
  unsubscribeAll() {
    const symbols = Array.from(this.subscribedSymbols)
    this.unsubscribe(symbols)
  }
  
  /**
   * 注册价格更新回调
   */
  onPriceUpdate(callback: (prices: PriceUpdate) => void): () => void {
    const id = Date.now().toString() + Math.random().toString(36).substr(2, 9)
    this.priceUpdateCallbacks.set(id, callback)
    
    // 返回取消回调函数
    return () => {
      this.priceUpdateCallbacks.delete(id)
    }
  }

  onConnected(callback: () => void): () => void {
    this.connectedCallbacks.add(callback)
    return () => { this.connectedCallbacks.delete(callback) }
  }
  
  /**
   * 处理价格更新
   */
  private handlePriceUpdate(prices: PriceUpdate) {
    // 调用所有注册的回调
    this.priceUpdateCallbacks.forEach(callback => {
      try {
        callback(prices)
      } catch (error) {
        console.error('[Market WS] Error in price update callback:', error)
      }
    })
  }
  
  /**
   * 发送消息
   */
  private send(data: any) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data))
    } else {
      console.warn('[Market WS] Cannot send, not connected')
    }
  }
  
  /**
   * 启动心跳
   */
  private startHeartbeat() {
    this.stopHeartbeat()
    
    this.heartbeatTimer = window.setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.send({ action: 'ping' })
        this.pongTimer = window.setTimeout(() => { this.ws?.close() }, 10_000)
      }
    }, 30000) // 每30秒发送一次心跳
  }
  
  /**
   * 停止心跳
   */
  private stopHeartbeat() {
    if (this.pongTimer !== null) clearTimeout(this.pongTimer)
    this.pongTimer = null
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }
  
  /**
   * 停止重连
   */
  private stopReconnect() {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
  }
  
  /**
   * 获取连接状态
   */
  get isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN
  }
}

export default new MarketWebSocket()

