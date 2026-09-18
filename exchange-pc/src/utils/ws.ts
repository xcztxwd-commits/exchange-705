/**
 * Alltick WebSocket 行情连接管理
 * 参考文档: https://github.com/alltick/alltick-realtime-forex-crypto-stock-tick-finance-websocket-api
 */

// 导出接口类型
export interface TickData {
  symbol: string
  price: number
  volume?: number
  timestamp: number
  bid?: number
  ask?: number
}

export interface KlineData {
  symbol: string
  open: number
  high: number
  low: number
  close: number
  volume: number
  timestamp: number
  interval?: string // 1m, 5m, 15m, 1h, 1d等
}

type MessageHandler = (data: any) => void

class AlltickWebSocket {
  private ws: WebSocket | null = null
  private url: string
  private apiKey: string = ''
  private reconnectTimer: number | null = null
  private heartbeatTimer: number | null = null
  private reconnectAttempts = 0
  private maxReconnectAttempts = 10
  private reconnectDelay = 3000
  private lastConnectTime = 0 // 上次连接尝试时间
  private minConnectInterval = 5000 // 最小连接间隔（5秒），避免429错误
  private subscribedSymbols = new Set<string>()
  private messageHandlers = new Map<string, MessageHandler[]>()
  private isConnecting = false
  private configLoaded = false
  private category: string = 'Crypto' // 默认分类

  // Alltick WebSocket 地址（根据官方文档）
  // 美股、港股、A股、大盘数据: wss://quote.alltick.co/quote-stock-b-ws-api
  // 外汇、贵金属、加密货币、原油、CFD指数、商品: wss://quote.alltick.co/quote-b-ws-api
  constructor(category: string = 'Crypto') {
    this.category = category
    this.url = this.getWebSocketUrl(category)
    console.log(`[Alltick WS] Constructor: category=${category}, url=${this.url}`)
  }

  getCategory(): string {
    return this.category
  }

  /**
   * 根据分类获取WebSocket地址
   * 根据Alltick文档：
   * 1. 美股、港股、A股、大盘数据：wss://quote.alltick.co/quote-stock-b-ws-api
   * 2. 外汇、贵金属、加密货币、原油、CFD指数、商品：wss://quote.alltick.co/quote-b-ws-api
   */
  private getWebSocketUrl(category: string): string {
    // 需要股票API的分类：US（美股）、HK（港股）、CN（A股）、Index（大盘数据）
    const stockCategories = ['US', 'HK', 'CN', 'Index']
    if (stockCategories.includes(category)) {
      return 'wss://quote.alltick.co/quote-stock-b-ws-api'
    }
    // 其他分类（外汇、贵金属、加密货币、原油、CFD指数、商品）使用通用API
    return 'wss://quote.alltick.co/quote-b-ws-api'
  }

  /**
   * 设置分类并更新URL
   */
  setCategory(category: string) {
    const newUrl = this.getWebSocketUrl(category)
    
    // 验证新URL格式（双重保险）
    if (newUrl.includes('api.alltick.com')) {
      console.error('[Alltick WS] ❌ ERROR: getWebSocketUrl returned old URL format!', newUrl)
      throw new Error('Invalid WebSocket URL format: ' + newUrl)
    }
    
    if (this.category === category && this.url === newUrl) {
      // 分类和URL都没变，不需要更新
      return
    }
    
    this.category = category
    this.url = newUrl
    
    // 如果URL改变且已连接，需要断开重连
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      console.log(`[Alltick WS] Category changed, reconnecting from ${this.url} to ${newUrl}`)
      this.disconnect()
    }
    
    console.log(`[Alltick WS] ✅ Category set to ${category}, URL: ${newUrl}`)
  }

  /**
   * 从后端加载配置
   */
  async loadConfig(): Promise<void> {
    // 即使已加载，也确保URL是正确的（防止被其他地方修改）
    const correctUrl = this.getWebSocketUrl(this.category)
    if (this.url !== correctUrl) {
      console.warn('[Alltick WS] URL was incorrect, correcting:', this.url, '->', correctUrl)
      this.url = correctUrl
    }
    
    if (this.configLoaded) {
      return Promise.resolve()
    }

    try {
      // 使用环境变量配置的 API base URL
      const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
      const response = await fetch(`${apiBaseUrl}/market/config/alltick`)
      const data = await response.json()
      
      // 只加载API Key，完全忽略后端返回的wsUrl（可能包含旧的URL）
      // 分类URL由构造函数和setCategory管理，根据分类动态选择
      if (data.apiKey) {
        this.apiKey = data.apiKey
      }
      
      // 强制使用正确的分类URL，忽略后端返回的wsUrl
      this.url = correctUrl
      
      // 如果后端返回了旧的URL，记录警告
      if (data.wsUrl && data.wsUrl.includes('api.alltick.com')) {
        console.warn('[Alltick WS] ⚠️ Backend returned old URL format, ignoring it:', data.wsUrl)
        console.warn('[Alltick WS] ✅ Using correct URL instead:', correctUrl)
      }
      
      this.configLoaded = true
      console.log('[Alltick WS] Config loaded:', { 
        category: this.category,
        url: this.url, 
        hasApiKey: !!this.apiKey,
        backendWsUrl: data.wsUrl // 仅用于日志，不使用
      })
    } catch (error) {
      console.warn('[Alltick WS] Failed to load config, using defaults:', error)
      // 使用默认配置继续，确保URL正确
      this.url = correctUrl
      this.configLoaded = true
    }
  }

  /**
   * 连接WebSocket
   */
  async connect(): Promise<void> {
    if (this.ws?.readyState === WebSocket.OPEN) {
      return Promise.resolve()
    }

    if (this.isConnecting) {
      console.log('[Alltick WS] Already connecting, waiting...')
      return Promise.resolve()
    }
    
    // 检查连接间隔，避免429错误
    const now = Date.now()
    const timeSinceLastConnect = now - this.lastConnectTime
    if (timeSinceLastConnect < this.minConnectInterval) {
      const waitTime = this.minConnectInterval - timeSinceLastConnect
      console.log(`[Alltick WS] Rate limiting: waiting ${waitTime}ms before connect (min interval: ${this.minConnectInterval}ms)`)
      await new Promise(resolve => setTimeout(resolve, waitTime))
    }

    // 确保配置已加载
    await this.loadConfig()

    this.isConnecting = true
    this.lastConnectTime = Date.now()

    return new Promise((resolve, reject) => {
      try {
        // 强制使用正确的分类URL（防止被其他地方修改）
        const correctBaseUrl = this.getWebSocketUrl(this.category)
        this.url = correctBaseUrl
        
        // 根据Alltick文档，API Key应该作为token参数传递
        let wsUrl = correctBaseUrl
        if (this.apiKey) {
          const separator = wsUrl.includes('?') ? '&' : '?'
          wsUrl = `${wsUrl}${separator}token=${encodeURIComponent(this.apiKey)}`
        }
        
        // 最终验证URL格式，确保不是旧的URL（双重保险）
        if (wsUrl.includes('api.alltick.com')) {
          console.error('[Alltick WS] CRITICAL ERROR: Detected old URL format!', wsUrl)
          console.error('[Alltick WS] Forcing correct URL for category:', this.category)
          // 强制使用正确的URL
          wsUrl = this.apiKey 
            ? `${correctBaseUrl}?token=${encodeURIComponent(this.apiKey)}`
            : correctBaseUrl
          console.error('[Alltick WS] Corrected URL:', wsUrl)
        }
        
        // 最终验证：确保URL是正确的格式
        if (!wsUrl.includes('quote.alltick.co')) {
          console.error('[Alltick WS] CRITICAL: URL format is incorrect!', wsUrl)
          console.error('[Alltick WS] Expected URL format: wss://quote.alltick.co/quote-*-ws-api')
          const finalUrl = this.apiKey 
            ? `${correctBaseUrl}?token=${encodeURIComponent(this.apiKey)}`
            : correctBaseUrl
          console.error('[Alltick WS] Using corrected URL:', finalUrl)
          wsUrl = finalUrl
        }
        
        console.log('[Alltick WS] Connecting to:', wsUrl)
        console.log('[Alltick WS] Category:', this.category)
        console.log('[Alltick WS] Base URL:', correctBaseUrl)
        
        this.ws = new WebSocket(wsUrl)

        this.ws.onopen = () => {
          console.log(`[Alltick WS] [${this.category}] ✅ Connected to`, wsUrl)
          this.isConnecting = false
          this.reconnectAttempts = 0
          
          // Alltick WebSocket连接成功后，通常不需要额外的认证消息
          // API Key已经通过URL参数token传递
          // 延迟启动心跳和订阅，给服务器时间处理连接
          console.log(`[Alltick WS] [${this.category}] 🔄 Connection opened, will start heartbeat and resubscribe in 2 seconds...`)
          setTimeout(() => {
            console.log(`[Alltick WS] [${this.category}] 🚀 Starting heartbeat...`)
            this.startHeartbeat()
            console.log(`[Alltick WS] [${this.category}] 📡 Resubscribing ${this.subscribedSymbols.size} symbols...`)
            // 重新订阅之前的交易对
            this.resubscribe()
            console.log(`[Alltick WS] [${this.category}] ✅ Heartbeat started and resubscription completed`)
          }, 2000) // 延迟2秒，确保连接稳定
          
          resolve()
        }

        this.ws.onmessage = (event) => {
          try {
            const data = JSON.parse(event.data)
            this.handleMessage(data)
          } catch (e) {
            console.error('[Alltick WS] Parse message error:', e)
          }
        }

        this.ws.onerror = (error) => {
          console.error('[Alltick WS] WebSocket error event:', error)
          // 注意：onerror 事件不提供详细的错误信息
          // 实际错误信息会在 onmessage 中通过服务器返回的错误消息获取
          this.isConnecting = false
          // 不立即reject，等待onclose或服务器错误消息
          // 如果是429错误，需要增加重连延迟
        }

        this.ws.onclose = (event) => {
          console.log('[Alltick WS] Connection closed', {
            code: event.code,
            reason: event.reason,
            wasClean: event.wasClean
          })
          this.isConnecting = false
          this.stopHeartbeat()
          
          // 如果是429错误（Too Many Requests），增加重连延迟
          if (event.code === 1006 || event.code === 1002) {
            // 可能是429错误导致的异常关闭
            console.warn('[Alltick WS] Connection closed with error code, may be rate limited')
            // 增加重连延迟到30秒
            this.reconnectDelay = Math.min(this.reconnectDelay * 2, 30000)
          }
          
          // 如果连接被服务器关闭（非正常关闭），可能需要重新连接
          if (!event.wasClean && event.code !== 1000) {
            console.warn('[Alltick WS] Connection closed unexpectedly, will attempt reconnect')
            // 延迟重连，避免429错误
            setTimeout(() => {
              this.attemptReconnect()
            }, 5000) // 延迟5秒再重连
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
    this.stopHeartbeat()
    this.stopReconnect()
    if (this.ws) {
      this.ws.close()
      this.ws = null
    }
    this.subscribedSymbols.clear()
  }

  /**
   * 订阅Tick行情
   * 根据Alltick文档，使用协议号22004
   * @param symbol 交易对符号，如 BTCUSDT, XAUUSD
   */
  subscribeTick(symbol: string) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn('[Alltick WS] Not connected, will subscribe after connect')
      this.subscribedSymbols.add(symbol)
      return
    }

    // 根据Alltick API文档，订阅消息格式：
    // cmd_id: 22004 (integer)
    // seq_id: integer, 必须小于10位数字
    // trace: string, 每次请求不可重复
    // data.symbol_list: array of {code: string}
    
    // seq_id 必须小于10位数字，使用时间戳的后9位
    const seqId = Number(String(Date.now()).slice(-9)) // 取时间戳后9位，确保小于10位
    
    // trace 每次请求不可重复，使用时间戳+随机字符串
    const trace = `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
    
    const message = {
      cmd_id: 22004,
      seq_id: seqId,
      trace: trace,
      data: {
        symbol_list: [
          {
            code: symbol
          }
        ]
      }
    }

    console.log('[Alltick WS] Sending subscribe message:', JSON.stringify(message))
    this.send(message)
    this.subscribedSymbols.add(symbol)
    console.log(`[Alltick WS] Subscribed tick: ${symbol}`)
  }

  /**
   * 批量订阅Tick行情
   */
  subscribeTicks(symbols: string[]) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      console.warn('[Alltick WS] Not connected, will subscribe after connect')
      symbols.forEach(s => this.subscribedSymbols.add(s))
      return
    }

    // 根据Alltick API文档，批量订阅格式
    // seq_id 必须小于10位数字，使用时间戳的后9位
    const seqId = Number(String(Date.now()).slice(-9)) // 取时间戳后9位，确保小于10位
    
    // trace 每次请求不可重复
    const trace = `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
    
    const message = {
      cmd_id: 22004,
      seq_id: seqId,
      trace: trace,
      data: {
        symbol_list: symbols.map(code => ({ code }))
      }
    }

    console.log('[Alltick WS] Sending batch subscribe message:', JSON.stringify(message))
    this.send(message)
    symbols.forEach(s => this.subscribedSymbols.add(s))
    console.log(`[Alltick WS] Subscribed ticks:`, symbols)
  }

  /**
   * 订阅K线数据
   * 注意：根据Alltick文档，WebSocket不支持K线推送，需要通过HTTP接口获取
   * 此方法保留用于未来可能的支持
   * @param symbol 交易对符号
   * @param interval K线周期，如 '1m', '5m', '15m', '1h', '1d'
   */
  subscribeKline(_symbol: string, _interval = '1m') {
    console.warn('[Alltick WS] Kline subscription not supported via WebSocket, use HTTP API instead')
    // Alltick WebSocket不支持K线数据推送，需要通过HTTP接口获取
    // 这里暂时不实现，避免错误
  }

  /**
   * 取消订阅
   */
  unsubscribe(symbol: string) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return
    }

    // 取消订阅协议号：22006
    const message = {
      protocol: 22006,
      symbols: [symbol],
    }

    this.send(message)
    this.subscribedSymbols.delete(symbol)
    console.log(`[Alltick WS] Unsubscribed: ${symbol}`)
  }

  /**
   * 注册消息处理器
   */
  on(event: 'tick' | 'kline' | 'error', handler: MessageHandler) {
    if (!this.messageHandlers.has(event)) {
      this.messageHandlers.set(event, [])
    }
    this.messageHandlers.get(event)!.push(handler)
  }

  /**
   * 移除消息处理器
   */
  off(event: 'tick' | 'kline' | 'error', handler: MessageHandler) {
    const handlers = this.messageHandlers.get(event)
    if (handlers) {
      const index = handlers.indexOf(handler)
      if (index > -1) {
        handlers.splice(index, 1)
      }
    }
  }

  /**
   * 发送消息
   */
  private send(data: any) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(data))
    } else {
      console.warn('[Alltick WS] Cannot send, not connected')
    }
  }

  /**
   * 处理接收到的消息
   * 根据Alltick API返回格式处理
   * 推送协议号：22998
   */
  private handleMessage(data: any) {
    // Alltick返回格式：
    // 推送数据协议号：22998
    // { "cmd_id": 22998, "data": { "code": "1288.HK", "seq": "...", "tick_time": "1605509068", "price": "651.12", "volume": "300", ... } }
    // 订阅响应协议号：22005
    // { "ret": 200, "msg": "ok", "cmd_id": 22005, "seq_id": 123, "trace": "...", "data": {} }
    
    // 首先检查是否有错误消息
    if (data.ret && data.ret !== 200) {
      const errorMsg = data.msg || data.error || '未知错误'
      console.error('[Alltick WS] Server error:', errorMsg, data)
      this.emit('error', errorMsg)
      return
    }
    
    // 检查是否是错误消息（即使ret不是200也可能在其他字段）
    if (data.msg && (data.msg.includes('invalid') || data.msg.includes('error') || data.msg.includes('失败'))) {
      console.error('[Alltick WS] Error message:', data.msg, data)
      this.emit('error', data.msg)
      return
    }
    
    if (data.cmd_id === 22998 && data.data) {
      // 实时tick数据推送
      console.log(`[Alltick WS] [${this.category}] 📨 Received tick data:`, data.data)
      this.processTickData(data.data)
    } else if (data.cmd_id === 22005) {
      // 订阅响应（协议号22005）
      if (data.ret === 200) {
        console.log(`[Alltick WS] [${this.category}] ✅ Subscribe success:`, data)
        console.log(`[Alltick WS] [${this.category}] ✅ Subscription confirmed for trace:`, data.trace)
      } else {
        console.error(`[Alltick WS] [${this.category}] ❌ Subscribe failed:`, data)
        console.error(`[Alltick WS] [${this.category}] ❌ Error code: ${data.ret}, message: ${data.msg}`)
        this.emit('error', data.msg || '订阅失败')
      }
    } else if (data.cmd_id === 22001) {
      // 心跳响应（协议号22001）
      // 忽略，心跳已处理
    } else if (data.pong || data === 'pong') {
      // 心跳响应（字符串格式）
      // 忽略
    } else {
      // 其他消息，记录日志以便调试
      console.log('[Alltick WS] Received message:', data)
    }
  }

  /**
   * 处理单个tick数据
   */
  private processTickData(data: any) {
    // Alltick tick数据格式（协议22998）：
    // { "code": "1288.HK", "seq": "1605509068000001", "tick_time": "1605509068", "price": "651.12", "volume": "300", "turnover": "12345.6", "trade_direction": 1 }
    // 根据文档，tick_time 单位是毫秒，但示例中显示的是秒级时间戳
    // 需要根据实际情况处理：如果小于 10000000000，说明是秒级，需要转换为毫秒
    const tickTime = data.tick_time || data.time || data.timestamp
    let timestamp: number
    if (tickTime) {
      const tickTimeNum = Number(tickTime)
      // 如果小于 10000000000，说明是秒级时间戳，需要转换为毫秒
      // 否则认为是毫秒级时间戳
      timestamp = tickTimeNum < 10000000000 ? tickTimeNum * 1000 : tickTimeNum
    } else {
      timestamp = Date.now()
    }
    
    const tickData: TickData = {
      symbol: data.code || data.symbol, // Alltick使用code字段
      price: Number(data.price || 0),
      volume: Number(data.volume || 0),
      timestamp: timestamp,
      bid: data.bid ? Number(data.bid) : undefined,
      ask: data.ask ? Number(data.ask) : undefined,
    }
    
    console.log(`[Alltick WS] [${this.category}] 🔄 Processing tick data:`, {
      symbol: tickData.symbol,
      price: tickData.price,
      category: this.category
    })
    
    if (tickData.symbol && tickData.price > 0) {
      console.log(`[Alltick WS] [${this.category}] ✅ Emitting tick event for ${tickData.symbol}:`, tickData.price)
      this.emit('tick', tickData)
    } else {
      console.warn(`[Alltick WS] [${this.category}] ⚠️ Invalid tick data (missing symbol or price):`, tickData)
    }
  }

  /**
   * 触发事件
   */
  private emit(event: 'tick' | 'kline' | 'error', data: any) {
    const handlers = this.messageHandlers.get(event)
    if (handlers) {
      handlers.forEach((handler) => handler(data))
    }
  }

  /**
   * 启动心跳
   * 根据文档，每10秒发送一次心跳，30秒内没有收到心跳会断开连接
   * 注意：连接成功后不要立即发送心跳，等待服务器准备好
   */
  private startHeartbeat() {
    this.stopHeartbeat()
    
    // 立即发送第一次心跳，然后每10秒发送一次
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      // 发送第一次心跳
      const seqId = Number(String(Date.now()).slice(-9))
      const trace = `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
      console.log(`[Alltick WS] [${this.category}] 💓 Sending first heartbeat...`)
      this.send({ 
        cmd_id: 22001,
        seq_id: seqId,
        trace: trace
      })
    }
    
    // 然后每10秒发送一次心跳
    this.heartbeatTimer = window.setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        // 发送心跳消息（协议号：22001）
        // 心跳消息也需要seq_id和trace（如果文档要求）
        const seqId = Number(String(Date.now()).slice(-9)) // 取时间戳后9位，确保小于10位
        const trace = `${Date.now()}-${Math.random().toString(36).substr(2, 9)}`
        console.log(`[Alltick WS] [${this.category}] 💓 Sending heartbeat (seq_id: ${seqId}, trace: ${trace})`)
        this.send({ 
          cmd_id: 22001,
          seq_id: seqId,
          trace: trace
        })
      } else {
        console.warn(`[Alltick WS] [${this.category}] ⚠️ Cannot send heartbeat: WebSocket not open (readyState: ${this.ws?.readyState})`)
      }
    }, 10000) // 10秒心跳（根据文档要求）
    
    console.log(`[Alltick WS] [${this.category}] ✅ Heartbeat timer started (interval: 10s)`)
  }

  /**
   * 停止心跳
   */
  private stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  /**
   * 尝试重连
   * 使用指数退避策略，避免429错误
   */
  private attemptReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) {
      console.error('[Alltick WS] Max reconnect attempts reached')
      return
    }

    this.stopReconnect()
    this.reconnectAttempts++

    // 指数退避：每次重连延迟递增
    const delay = Math.min(this.reconnectDelay * Math.pow(1.5, this.reconnectAttempts - 1), 60000)
    
    console.log(`[Alltick WS] Will reconnect in ${delay}ms (attempt ${this.reconnectAttempts}/${this.maxReconnectAttempts})`)
    
    this.reconnectTimer = window.setTimeout(() => {
      console.log(`[Alltick WS] Reconnecting... (${this.reconnectAttempts}/${this.maxReconnectAttempts})`)
      this.connect().catch(() => {
        // 重连失败会在onclose中再次尝试
      })
    }, delay)
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
   * 重新订阅所有交易对
   */
  private resubscribe() {
    const symbols = Array.from(this.subscribedSymbols)
    if (symbols.length > 0) {
      // 批量订阅更高效
      this.subscribeTicks(symbols)
    }
  }

  /**
   * 获取连接状态
   */
  get isConnected(): boolean {
    return this.ws?.readyState === WebSocket.OPEN
  }
}

// 按分类存储WebSocket实例
const wsInstances = new Map<string, AlltickWebSocket>()

export function getAlltickWS(category: string = 'Crypto'): AlltickWebSocket {
  if (!wsInstances.has(category)) {
    wsInstances.set(category, new AlltickWebSocket(category))
  }
  return wsInstances.get(category)!
}

export default getAlltickWS

