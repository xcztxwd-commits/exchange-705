/**
 * 市场数据 WebSocket 客户端
 * 连接到后端 WebSocket 服务器，接收实时价格推送
 */

export interface PriceUpdate {
  [symbol: string]: {
    quoteCurrency?: string
    quoteToUsdRate?: number | null
    conversionAvailable?: boolean
    conversionExpiresAt?: number
    epoch?: string
    quoteVersion?: number
    changeBasis?: string
    tradeAvailable?: boolean
    price: number
    change24h: number
    changePct24h: number
    controlSourceResumed?: boolean
    controlHistory?: boolean
    controlState?: string
    sourceTimestamp?: number
    sourceAvailable?: boolean
    displayAvailable?: boolean
    controlTaskId?: string
    simulated?: boolean
    simulationSession?: number
    marketRevision?: number
    source?: string
    price24hAgo?: number
    status?: string
    available?: boolean
    stale?: boolean
    fetchedAt?: number
    expiresAt?: number
    executionExpiresAt?: number
    timestamp?: number
  }
}

// Match the server's 15-second freshness window even for legacy payloads.
export function normalizeQuote(quote: PriceUpdate[string], now = Date.now()) {
  const milliseconds = (value: unknown) => { const n = Number(value); return n < 10_000_000_000 ? n * 1000 : n }
  const timestamp = milliseconds(quote.timestamp)
  if (!Number.isFinite(quote.price) || quote.price <= 0 || !Number.isFinite(timestamp) || timestamp <= 0 || timestamp > now + 5000) return null
  const fetchedAt = quote.fetchedAt == null ? timestamp : milliseconds(quote.fetchedAt)
  const expiresAt = quote.executionExpiresAt == null
    ? Math.min(timestamp + 15_000, fetchedAt + 15_000, quote.expiresAt == null ? Infinity : milliseconds(quote.expiresAt))
    : milliseconds(quote.executionExpiresAt)
  if (!Number.isFinite(fetchedAt) || !Number.isFinite(expiresAt)) return null
  const status = quote.status === 'unavailable' ? 'unavailable'
    : quote.status === 'stale' || quote.stale || now >= expiresAt ? 'stale'
    : quote.available === false || (quote.status != null && quote.status !== 'available') ? 'unavailable' : 'available'
  return { ...quote, timestamp, fetchedAt, expiresAt, status }
}

class MarketWebSocket {
  private ws: WebSocket | null = null
  private pending: Promise<void> | null = null
  private stopped = false
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null
  private fallbackTimer: ReturnType<typeof setInterval> | null = null
  private pongTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempts = 0
  private owners = new Map<string, Set<string>>()
  private priceUpdateCallbacks = new Set<(prices: PriceUpdate) => void>()
  private connectedCallbacks = new Set<() => void>()
  private received = new Set<string>()
  private acknowledged = new Set<string>()
  private versions = new Map<string, number>()
  private epoch: string | undefined
  private retiredEpochs = new Set<string>()
  private snapshotRequest: AbortController | null = null
  private generation = 0
  private lastMessage = 0
  private listening = false
  private apiBase = import.meta.env?.VITE_API_BASE_URL || '/api'

  private symbols(): string[] { return [...new Set([...this.owners.values()].flatMap(set => [...set]))] }
  get isConnected(): boolean { return this.ws?.readyState === WebSocket.OPEN }
  get isHealthy(): boolean {
    return this.isConnected && Date.now() - this.lastMessage < 45000 && this.symbols().every(symbol => this.received.has(symbol) && this.acknowledged.has(symbol))
  }
  private recover = () => {
    if (this.stopped || !this.symbols().length || document.visibilityState === 'hidden') return
    if (!this.isConnected) void this.connect().catch(() => {})
    void this.syncSnapshot()
  }
  connect(): Promise<void> {
    if (this.isConnected) return Promise.resolve()
    if (this.pending) return this.pending
    this.stopped = false
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    if (!this.listening) {
      window.addEventListener('online', this.recover)
      document.addEventListener('visibilitychange', this.recover)
      this.listening = true
    }
    if (!this.fallbackTimer) this.fallbackTimer = setInterval(() => {
      if (!this.stopped && !this.isHealthy && document.visibilityState !== 'hidden') {
        if (this.isConnected) this.send({ action: 'subscribe', symbols: this.symbols(), fastSymbols: [...(this.owners.get('active') || [])] })
        void this.syncSnapshot()
      }
    }, 3000)
    let socket: WebSocket
    const attempt = new Promise<void>((resolve, reject) => {
      try {
        const url = this.apiBase.startsWith('/')
          ? `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${import.meta.env?.DEV ? `${location.hostname}:8080` : location.host}${this.apiBase}/ws/market`
          : this.apiBase.replace(/^http/, 'ws') + '/ws/market'
        socket = new WebSocket(url); this.ws = socket
        const timeout = setTimeout(() => { socket.close(); reject(new Error('Market WebSocket timeout')) }, 10000)
        socket.onopen = () => {
          clearTimeout(timeout)
          if (this.ws !== socket || this.stopped) { socket.close(); return }
          this.generation++; this.snapshotRequest?.abort(); this.snapshotRequest = null
          this.received.clear(); this.acknowledged.clear(); this.lastMessage = Date.now(); this.reconnectAttempts = 0
          this.send({ action: 'subscribe', symbols: this.symbols(), fastSymbols: [...(this.owners.get('active') || [])] })
          this.startHeartbeat()
          this.connectedCallbacks.forEach(callback => callback())
          resolve()
          void this.syncSnapshot()
        }
        socket.onmessage = event => {
          if (this.ws !== socket) return
          try {
            const message = JSON.parse(event.data); this.lastMessage = Date.now()
            if (message.type === 'price' && message.data) { Object.keys(message.data).forEach(symbol => this.acknowledged.add(symbol)); this.deliver(message.data) }
            if (message.type === 'subscribed') this.acknowledged = new Set(message.symbols || [])
            if (message.type === 'pong' && this.pongTimer) { clearTimeout(this.pongTimer); this.pongTimer = null }
          } catch { /* Invalid frames never refresh quote freshness. */ }
        }
        socket.onerror = () => { clearTimeout(timeout); reject(new Error('Market WebSocket error')); socket.close() }
        socket.onclose = () => {
          clearTimeout(timeout); reject(new Error('Market WebSocket closed'))
          if (this.ws !== socket) return
          this.ws = null; this.received.clear(); this.acknowledged.clear(); this.stopHeartbeat()
          if (this.stopped) return
          const delay = Math.min(30000, 1000 * 2 ** Math.min(5, this.reconnectAttempts++)) + Math.random() * 500
          this.reconnectTimer = setTimeout(() => { void this.connect().catch(() => {}) }, delay)
          void this.syncSnapshot()
        }
      } catch (error) { reject(error) }
    })
    this.pending = attempt
    void attempt.then(() => { if (this.pending === attempt) this.pending = null }, () => { if (this.pending === attempt) this.pending = null })
    return attempt
  }
  subscribe(symbols: string[], owner = 'default') {
    const before = new Set(this.symbols())
    const owned = this.owners.get(owner) || new Set<string>()
    symbols.forEach(symbol => owned.add(symbol)); this.owners.set(owner, owned)
    const added = this.symbols().filter(symbol => !before.has(symbol))
    if (!this.isConnected) void this.connect().catch(() => {})
    else if (added.length || owner === 'active') this.send({ action: 'subscribe', symbols: owner === 'active' ? symbols : added, fastSymbols: [...(this.owners.get('active') || [])] })
    if (added.length) void this.syncSnapshot()
  }
  setSubscriptions(owner: string, symbols: string[]) {
    const keep = new Set(symbols)
    this.unsubscribe([...(this.owners.get(owner) || [])].filter(symbol => !keep.has(symbol)), owner)
    this.subscribe(symbols, owner)
  }
  unsubscribe(symbols: string[], owner = 'default') {
    const owned = this.owners.get(owner)
    symbols.forEach(symbol => owned?.delete(symbol))
    if (owned?.size === 0) this.owners.delete(owner)
    const remaining = new Set(this.symbols())
    const removed = symbols.filter(symbol => !remaining.has(symbol))
    removed.forEach(symbol => this.received.delete(symbol))
    if (removed.length) this.send({ action: 'unsubscribe', symbols: removed })
  }
  release(owner: string) { this.unsubscribe([...(this.owners.get(owner) || [])], owner) }
  unsubscribeAll() { const symbols = this.symbols(); this.owners.clear(); this.received.clear(); this.send({ action: 'unsubscribe', symbols }) }
  onPriceUpdate(callback: (prices: PriceUpdate) => void): () => void { this.priceUpdateCallbacks.add(callback); return () => { this.priceUpdateCallbacks.delete(callback) } }
  onConnected(callback: () => void): () => void { this.connectedCallbacks.add(callback); return () => { this.connectedCallbacks.delete(callback) } }
  private deliver(prices: PriceUpdate) {
    const accepted: PriceUpdate = {}
    for (const [symbol, quote] of Object.entries(prices)) {
      if (quote.epoch && this.retiredEpochs.has(quote.epoch)) continue
      if (quote.epoch && quote.epoch !== this.epoch) {
        if (this.epoch) this.retiredEpochs.add(this.epoch)
        this.epoch = quote.epoch; this.versions.clear()
      }
      if (quote.quoteVersion != null) {
        if (quote.quoteVersion < (this.versions.get(symbol) ?? -1)) continue
        this.versions.set(symbol, quote.quoteVersion)
      }
      this.received.add(symbol); accepted[symbol] = quote
    }
    if (Object.keys(accepted).length) this.priceUpdateCallbacks.forEach(callback => { try { callback(accepted) } catch (error) { console.error(error) } })
  }
  async syncSnapshot() {
    const symbols = this.symbols()
    if (this.stopped || !symbols.length || this.snapshotRequest) return
    const request = new AbortController(), generation = this.generation
    this.snapshotRequest = request
    const timeout = setTimeout(() => request.abort(), 5000)
    try {
      const response = await fetch(`${this.apiBase}/market/price/batch`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ symbols }), signal: request.signal,
      })
      const result = await response.json()
      if (response.ok && result.ret === 200 && result.data && generation === this.generation && !request.signal.aborted) this.deliver(result.data)
    } catch { /* One bounded fallback retries on the next interval. */ }
    finally { clearTimeout(timeout); if (this.snapshotRequest === request) this.snapshotRequest = null }
  }
  private send(message: unknown) { if (this.isConnected) this.ws!.send(JSON.stringify(message)) }
  private startHeartbeat() {
    this.stopHeartbeat()
    this.heartbeatTimer = setInterval(() => {
      this.send({ action: 'ping' }); this.pongTimer = setTimeout(() => this.ws?.close(), 10000)
    }, 30000)
  }
  private stopHeartbeat() {
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
    if (this.pongTimer) clearTimeout(this.pongTimer)
    this.heartbeatTimer = null; this.pongTimer = null
  }
  disconnect() {
    this.stopped = true; this.generation++; this.pending = null
    this.stopHeartbeat(); this.snapshotRequest?.abort(); this.snapshotRequest = null
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    if (this.fallbackTimer) clearInterval(this.fallbackTimer)
    this.reconnectTimer = null; this.fallbackTimer = null
    const socket = this.ws; this.ws = null; socket?.close()
    this.owners.clear(); this.received.clear()
    window.removeEventListener('online', this.recover); document.removeEventListener('visibilitychange', this.recover); this.listening = false
  }
}

export default new MarketWebSocket()
