import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import marketWebSocket, { normalizeQuote, type PriceUpdate } from '@/utils/marketWebSocket'
import type { TickData, KlineData } from '@/utils/ws'
import { convertIntervalToKlineType, getStockCompatibleInterval } from '@/utils/kline'

// Cold K-line requests are queued by the backend. Briefly poll only while work is pending.
async function fetchMarketKline(url: string, options?: RequestInit): Promise<Response> {
  for (let attempt = 0; ; attempt++) {
    const response = await fetch(url, options)
    const body = await response.clone().json().catch(() => ({}))
    const items = Array.isArray(body.data) ? body.data : [body.data]
    if (attempt >= 5 || !items.some((item: any) => item?.pending)) return response
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
}

/**
 * 行情数据Store
 * 管理K线、最新价、涨跌幅等行情数据
 */
export const useMarketStore = defineStore('market', () => {
  // 当前选中的交易对
  const currentSymbol = ref<string>('')
  const quoteStatusMap = ref<Record<string, { status: string, fetchedAt: number, expiresAt: number, timestamp: number, epoch?: string, quoteVersion?: number, simulated?: boolean, simulationSession?: number, marketRevision?: number, controlSourceResumed?: boolean, controlHistory?: boolean, controlState?: string, controlTaskId?: string, sourceAvailable?: boolean, quoteToUsdRate?: number | null, conversionAvailable?: boolean, conversionExpiresAt?: number }>>({})
  let activeQuoteEpoch: string | undefined
  const retiredQuoteEpochs = new Set<string>()
  const recordQuoteStatus = (symbol: string, quote: any): boolean => {
    if (quote.epoch && retiredQuoteEpochs.has(quote.epoch)) return false
    if (quote.epoch && quote.epoch !== activeQuoteEpoch) {
      if (activeQuoteEpoch) retiredQuoteEpochs.add(activeQuoteEpoch)
      activeQuoteEpoch = quote.epoch
    }
    const previous = quoteStatusMap.value[symbol]
    if (previous?.epoch === quote.epoch && quote.quoteVersion != null && quote.quoteVersion < (previous?.quoteVersion ?? -1)) return false
    if (previous && quote.marketRevision != null && quote.marketRevision < (previous.marketRevision ?? 0)) return false
    // A source switch can legitimately return no external price. Clear simulation
    // state without inventing a quote or leaving its last price executable.
    if (previous?.simulationSession && !quote.simulated && quote.status === 'unavailable'
      && Number.isFinite(quote.marketRevision) && quote.marketRevision > (previous.marketRevision ?? 0)) {
      quoteStatusMap.value[symbol] = { status: 'unavailable', timestamp: 0, fetchedAt: 0, expiresAt: 0, marketRevision: quote.marketRevision }
      return false
    }
    const valid = normalizeQuote(quote)
    if (!valid) {
      if (quote.status === 'unavailable' || quote.status === 'stale') quoteStatusMap.value[symbol] = {
        ...(previous || { timestamp: 0, fetchedAt: 0, expiresAt: 0 }), status: quote.status,
        epoch: quote.epoch, quoteVersion: quote.quoteVersion,
      }
      return false
    }
    if (previous && (valid.marketRevision ?? 0) < (previous.marketRevision ?? 0)) return false
    const sameTask = previous?.controlTaskId && previous.controlTaskId === valid.controlTaskId
      && previous.marketRevision === valid.marketRevision
    if (sameTask && ((previous.controlSourceResumed && !valid.controlSourceResumed)
      || (previous.controlState !== 'RUNNING' && valid.controlState === 'RUNNING'))) return false
    const sameSource = previous?.simulationSession === valid.simulationSession && previous?.controlState === valid.controlState && previous?.controlTaskId === valid.controlTaskId && previous?.controlSourceResumed === valid.controlSourceResumed
    if (previous && previous.epoch === quote.epoch && sameSource && (valid.timestamp < previous.timestamp ||
      (valid.timestamp === previous.timestamp && quote.fetchedAt != null && valid.fetchedAt < previous.fetchedAt))) return false
    const { status, fetchedAt, expiresAt, timestamp, simulated, simulationSession, marketRevision, controlSourceResumed, controlHistory, controlState, controlTaskId, sourceAvailable } = valid
    quoteStatusMap.value[symbol] = { quoteToUsdRate: valid.quoteToUsdRate, conversionAvailable: valid.conversionAvailable, conversionExpiresAt: valid.conversionExpiresAt, status, fetchedAt, expiresAt, timestamp, epoch: quote.epoch, quoteVersion: quote.quoteVersion, simulated, simulationSession, marketRevision, controlSourceResumed, controlHistory, controlState, controlTaskId, sourceAvailable }
    return true
  }
  const getConversionRate = (symbol: string, currency = 'USD'): number => {
    if (currency === 'USD' || currency === 'USDT') return 1
    const quote = quoteStatusMap.value[symbol]
    const rate = Number(quote?.quoteToUsdRate)
    return quote?.conversionAvailable && Number(quote.conversionExpiresAt) > Date.now() && Number.isFinite(rate) && rate > 0 ? rate : NaN
  }
  const getQuoteStatus = (symbol: string, now = Date.now()): string => {
    const quote = quoteStatusMap.value[symbol]
    if (!quote) return 'unavailable'
    if (quote.status === 'unavailable') return 'unavailable'
    return now >= quote.expiresAt ? 'stale' : quote.status
  }

  // 各交易对的最新Tick数据 { symbol: TickData }
  const tickDataMap = ref<Record<string, TickData>>({})

  // 各交易对的K线数据 { symbol: KlineData[] }
  const klineDataMap = ref<Record<string, KlineData[]>>({})

  // 各交易对的价格变化 { symbol: { price: number, change24h: number, changePct24h: number, price24hAgo: number } }
  const priceMap = ref<Record<string, { 
    price: number
    change24h: number
    changePct24h: number
    price24hAgo: number // 24小时前的价格
    firstPriceTime: number // 首次收到价格的时间戳
  }>>({})

  // 市场符号到内部符号的映射 { marketSymbol: internalSymbol }
  const symbolMapping = ref<Record<string, string>>({})
  
  // 符号到分类的映射 { symbol: category }
  const symbolCategoryMap = ref<Record<string, string>>({})
  let stopPriceListener: (() => void) | undefined
  const ensurePriceListener = () => {
    if (stopPriceListener) return
    stopPriceListener = marketWebSocket.onPriceUpdate((prices: PriceUpdate) => {
      for (const [symbol, quote] of Object.entries(prices)) {
        const internal = symbolCategoryMap.value[symbol] ? symbol : symbolMapping.value[symbol] || symbol
        if (!recordQuoteStatus(internal, quote)) continue
        const old = priceMap.value[internal]
        tickDataMap.value[internal] = { symbol: internal, price: quote.price, timestamp: quote.timestamp || 0 }
        priceMap.value[internal] = {
          price: quote.price, change24h: quote.change24h ?? old?.change24h ?? 0,
          changePct24h: quote.changePct24h ?? old?.changePct24h ?? 0,
          price24hAgo: quote.price24hAgo ?? old?.price24hAgo ?? quote.price,
          firstPriceTime: old?.firstPriceTime || Date.now(),
        }
      }
    })
  }


  /**
   * 初始化市场数据服务（WebSocket）
   */
  const initMarketService = async (category: string = 'Crypto', owner = `category:${category}`) => {
    try {
      console.log(`[Market Store] [${category}] 🔌 Initializing Market Service (WebSocket)...`)
      
      // 连接 WebSocket
      if (!marketWebSocket.isConnected) {
        ensurePriceListener()
        void marketWebSocket.connect().catch(() => {})
      }
      
      // 注册价格更新回调（只注册一次）
      ensurePriceListener()
      
      await loadAllSymbols(category, owner)
      console.log(`[Market Store] [${category}] ✅ Market service initialized`)
    } catch (error) {
      console.error(`[Market Store] [${category}] ❌ Init market service failed:`, error)
    }
  }
  
  /**
   * 加载所有交易对并建立映射
   */
  const loadAllSymbols = async (category: string = 'Crypto', owner = `category:${category}`) => {
    try {
      const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
      const res: any = await fetch(`${apiBaseUrl}/market/all`)
      const data = await res.json()
      const symbols = data.list || []
      
      if (symbols.length > 0) {
        // 建立映射关系
        symbols.forEach((s: any) => {
          const symbol = s.symbol
          const marketSymbol = s.alltickSymbol || s.symbol
          if (marketSymbol !== symbol) {
            symbolMapping.value[marketSymbol] = symbol
            console.log(`[Market Store] Mapped marketSymbol ${marketSymbol} -> internalSymbol ${symbol}`)
          }
          // 建立符号到分类的映射
          const cat = s.category || 'Crypto'
          symbolCategoryMap.value[symbol] = cat
          symbolCategoryMap.value[marketSymbol] = cat
        })
        
        // 按分类过滤，只订阅当前分类的交易对
        const categorySymbols = symbols
          .filter((s: any) => (s.category || 'Crypto') === category)
          .map((s: any) => s.symbol)
          .filter((symbol: string) => symbol)
        
        if (categorySymbols.length > 0) {
          console.log(`[Market Store] [${category}] 📡 Subscribing ${categorySymbols.length} symbols via WebSocket...`)
          // 批量订阅实时价格（WebSocket）
          await subscribeSymbolsBatch(categorySymbols, category, owner)
        }
      }
    } catch (error) {
      console.error(`[Market Store] [${category}] ❌ Failed to load symbols:`, error)
    }
  }

  /**
   * 订阅交易对行情（WebSocket）
   * @param symbol 内部 symbol（如 "ETHUSD"）
   * @param category 分类
   * @param marketSymbol 可选，如果提供则直接使用，否则使用symbol本身
   */
  const subscribeSymbol = async (symbol: string, category: string = 'Crypto', marketSymbol?: string) => {
    console.log(`[Market Store] Subscribing - internal symbol: ${symbol}, marketSymbol: ${marketSymbol || symbol}, category: ${category}`)
    
    // 确保 WebSocket 已连接
    if (!marketWebSocket.isConnected) {
      ensurePriceListener()
        void marketWebSocket.connect().catch(() => {})
    }
    
    // 使用 marketSymbol 订阅（后端需要 marketSymbol）
    const actualSymbol = marketSymbol || symbol
    ensurePriceListener()
    marketWebSocket.setSubscriptions('active', [symbol])
    
    // 建立映射关系（marketSymbol -> internalSymbol）
    if (actualSymbol !== symbol) {
      symbolMapping.value[actualSymbol] = symbol
    }
    
    // 更新符号到分类的映射
    if (!symbolCategoryMap.value[symbol]) {
      symbolCategoryMap.value[symbol] = category
    }
  }
  
  /**
   * 批量订阅交易对行情（WebSocket）
   */
  const subscribeSymbolsBatch = async (symbols: string[], category: string = 'Crypto', owner = `category:${category}`) => {
    if (!symbols || symbols.length === 0) {
      console.warn(`[Market Store] [${category}] ⚠️ No symbols to subscribe`)
      return
    }
    
    console.log(`[Market Store] [${category}] 📤 Subscribing ${symbols.length} symbols via WebSocket:`)
    console.log(`[Market Store] [${category}] 📤 Category: ${category}`)
    console.log(`[Market Store] [${category}] 📤 Symbols:`, symbols.slice(0, 10), symbols.length > 10 ? '...' : '')
    
    // 确保 WebSocket 已连接
    if (!marketWebSocket.isConnected) {
      ensurePriceListener()
        void marketWebSocket.connect().catch(() => {})
    }
    
    // 批量订阅（WebSocket 支持批量订阅）
    ensurePriceListener()
    marketWebSocket.setSubscriptions(owner, symbols.map(code => symbolMapping.value[code] || code))
    
    // 建立映射关系和分类映射
    symbols.forEach(symbol => {
      if (!symbolCategoryMap.value[symbol]) {
        symbolCategoryMap.value[symbol] = category
      }
    })
    
    console.log(`[Market Store] [${category}] ✅ Batch subscription completed (${symbols.length} symbols) - using WebSocket`)
  }

  /**
   * 取消订阅
   */
  const unsubscribeSymbol = (symbol: string, _category: string = 'Crypto') => {
    // 取消 WebSocket 订阅
    marketWebSocket.unsubscribe([symbol], 'active')
    
    // 清理数据
    delete tickDataMap.value[symbol]
    delete klineDataMap.value[symbol]
    delete priceMap.value[symbol]
  }

  /**
   * 加载所有交易对并订阅（刷新页面后重新订阅）
   */
  const ensureHeartbeatAndSubscription = async (category: string = 'Crypto') => {
    try {
      console.log(`[Market Store] [${category}] 🔄 Loading all symbols and subscribing...`)
      await loadAllSymbols(category)
      console.log(`[Market Store] [${category}] ✅ Subscription check completed`)
    } catch (error) {
      console.error(`[Market Store] [${category}] ❌ Failed to ensure subscription:`, error)
    }
  }

  /**
   * 批量订阅交易对（WebSocket）
   */
  const subscribeSymbols = async (symbols: Array<{ symbol: string; category: string; alltickSymbol?: string }>, owner = 'list') => {
    if (!symbols || symbols.length === 0) {
      marketWebSocket.release(owner)
      return
    }
    
    console.log(`[Market Store] ========== Starting batch subscription for ${symbols.length} symbols (WebSocket) ==========`)
    
    // 建立映射关系
    const allMarketSymbols: string[] = []
    
    symbols.forEach(({ symbol, category, alltickSymbol }) => {
      const cat = category || 'Crypto'
      const marketSymbol = alltickSymbol || symbol
      
      // 建立映射关系：marketSymbol -> internalSymbol
      if (marketSymbol !== symbol) {
        symbolMapping.value[marketSymbol] = symbol
        console.log(`[Market Store] Mapped marketSymbol ${marketSymbol} -> internalSymbol ${symbol}`)
      }
      
      // 建立符号到分类的映射
      symbolCategoryMap.value[symbol] = cat
      symbolCategoryMap.value[marketSymbol] = cat
      
      // 收集所有 marketSymbol
      allMarketSymbols.push(marketSymbol)
    })
    
    // 批量订阅所有 symbol（WebSocket 支持批量订阅）
    if (allMarketSymbols.length > 0) {
      console.log(`[Market Store] 🔄 Subscribing ${allMarketSymbols.length} symbols via WebSocket`)
      try {
        // 确保 WebSocket 已连接
        if (!marketWebSocket.isConnected) {
          ensurePriceListener()
        void marketWebSocket.connect().catch(() => {})
        }
        
        // 批量订阅
        ensurePriceListener()
        marketWebSocket.setSubscriptions(owner, symbols.map(s => s.symbol))
        
        console.log(`[Market Store] ✅ Subscribed ${allMarketSymbols.length} symbols via WebSocket`)
      } catch (error) {
        console.error(`[Market Store] ❌ Failed to subscribe:`, error)
      }
    }
    
    console.log(`[Market Store] ========== Batch subscription completed: ${allMarketSymbols.length}/${symbols.length} symbols ==========`)
  }
  
  /**
   * 从Redis批量获取价格和涨幅数据
   * @param symbolsData 可以是字符串数组或包含映射关系的对象数组
   */
  const fetchBatchPricesFromRedis = async (symbolsData: string[] | Array<{ symbol: string; alltickSymbol: string }>) => {
    try {
      if (!symbolsData || symbolsData.length === 0) {
        console.warn('[Market Store] No symbols provided for batch price fetch')
        return
      }
      
      // 构建 symbol 映射
      const symbolMap = new Map<string, string>() // alltickSymbol -> internalSymbol
      let alltickSymbols: string[] = []
      
      if (typeof symbolsData[0] === 'string') {
        // 如果是字符串数组，直接使用
        alltickSymbols = symbolsData as string[]
      } else {
        // 如果是对象数组，提取 alltickSymbol 并建立映射
        const symbolArray = symbolsData as Array<{ symbol: string; alltickSymbol: string }>
        alltickSymbols = symbolArray.map(s => s.symbol)
        symbolArray.forEach(s => {
          symbolMap.set(s.symbol, s.symbol)
        })
      }
      
      const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
      console.log(`[Market Store] Fetching batch prices from Redis for ${alltickSymbols.length} symbols`)
      
      const requestEpoch = activeQuoteEpoch
      const response = await fetch(`${apiBaseUrl}/market/redis/price/batch`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ symbols: alltickSymbols }),
      })
      
      const data = await response.json()
      if (activeQuoteEpoch !== requestEpoch) return
      
      if (data.ret === 200 && data.data) {
        const prices = data.data
        let loadedCount = 0
        
        // 将Redis中的价格数据填充到priceMap
        for (const [alltickSymbol, priceData] of Object.entries(prices)) {
          if (priceData && typeof priceData === 'object') {
            const internalSymbol = symbolMap.get(alltickSymbol) || symbolMapping.value[alltickSymbol] || alltickSymbol
            if (!recordQuoteStatus(internalSymbol, priceData)) continue
            const price = Number((priceData as any).price || 0)
            const change24h = Number((priceData as any).change24h || 0)
            const changePct24h = Number((priceData as any).changePct24h || 0)
            
            if (price > 0) {
              const priceInfo = {
                price,
                change24h,
                changePct24h,
                price24hAgo: price - change24h,
                firstPriceTime: Date.now(),
              }
              
              // 存储 alltickSymbol 对应的价格
              priceMap.value[alltickSymbol] = priceInfo
              
              // 如果有映射关系，也存储 internalSymbol 对应的价格
              if (internalSymbol && internalSymbol !== alltickSymbol) {
                priceMap.value[internalSymbol] = priceInfo
              }
              tickDataMap.value[internalSymbol] = {
                symbol: internalSymbol, price, timestamp: quoteStatusMap.value[internalSymbol]!.timestamp
              }
              
              loadedCount++
            }
          }
        }
        
        console.log(`[Market Store] ✅ Loaded ${loadedCount} prices from Redis`)
      } else {
        console.log('[Market Store] No cached prices in Redis')
      }
    } catch (error) {
      console.error('[Market Store] Failed to fetch batch prices from Redis:', error)
    }
  }

  /**
   * 通过HTTP获取K线数据（优先从Redis加载）
   */
  const fetchKlines = async (symbol: string, category: string = 'Crypto', interval: string = '1m', limit: number = 100) => {
    try {
      // 使用环境变量配置的 API base URL
      const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
      
      // 检查是否为股票分类（美股、港股、A股）
      const isStock = category === 'Stock' || category === 'US' || category === 'HK' || category === 'A'
      
      // 如果是股票，确保使用支持的 interval（2h和4h会降级为1h）
      const compatibleInterval = isStock ? getStockCompatibleInterval(interval) : interval
      
      // 转换为 kline_type（用于日志和验证）
      const klineType = convertIntervalToKlineType(compatibleInterval, isStock)
      console.log(`[Market Store] Fetching klines for ${symbol}, interval: ${compatibleInterval}, kline_type: ${klineType}, category: ${category}`)
      
      // 先尝试从Redis获取
      let response = await fetchMarketKline(`${apiBaseUrl}/market/redis/kline/${symbol}?interval=${compatibleInterval}`)
      let data = await response.json()
      
      // 检查Redis中的数据量
      const redisKlineCount = data.ret === 200 && data.data && data.data.kline_list && Array.isArray(data.data.kline_list) 
        ? data.data.kline_list.length 
        : 0
      
      // 如果Redis中没有数据，或者数据量不足（少于limit的50%），从HTTP接口获取
      if (data.ret === 404 || !data.data || !data.data.kline_list || redisKlineCount < Math.max(limit * 0.5, 10)) {
        if (redisKlineCount > 0) {
          console.log(`[Market Store] Redis has only ${redisKlineCount} klines (need ${limit}), fetching more from HTTP API...`)
        } else {
          console.log(`[Market Store] No cached klines for ${symbol}, fetching from HTTP API...`)
        }
        // 从HTTP接口获取完整的历史K线数据
        response = await fetchMarketKline(`${apiBaseUrl}/market/kline/${symbol}?interval=${compatibleInterval}&limit=${limit}&category=${category}`)
        data = await response.json()
        console.log(`[Market Store] Fetched ${data.data?.kline_list?.length || 0} klines from HTTP API for ${symbol}`)
      } else {
        console.log(`[Market Store] Loaded ${redisKlineCount} klines from Redis for ${symbol} (sufficient)`)
      }
      
      console.log(`[Market Store] Fetch klines response for ${symbol}:`, data)
      
      // 处理Alltick API返回格式：{ ret: 200, msg: "ok", data: { code: "...", kline_list: [...] } }
      if (data.ret === 200 && data.data && data.data.kline_list && Array.isArray(data.data.kline_list)) {
        console.log(`[Market Store] Received ${data.data.kline_list.length} klines from API for ${symbol}`)
        
        const klines: KlineData[] = data.data.kline_list.map((item: any, index: number) => {
          // 后端返回的timestamp字段可能是 kline_timestamp 或 timestamp
          // 后端返回的是毫秒级时间戳（kline_timestamp字段）
          const timestampMs = Number(item.kline_timestamp || item.timestamp || item.time || 0)
          
          // 如果时间戳是秒级的（小于10000000000），转换为毫秒级
          const timestamp = timestampMs < 10000000000 ? timestampMs * 1000 : timestampMs
          
          const kline: KlineData = {
            symbol: symbol,
            open: Number(item.open_price || item.open || 0),
            high: Number(item.high_price || item.high || 0),
            low: Number(item.low_price || item.low || 0),
            close: Number(item.close_price || item.close || 0),
            volume: Number(item.volume || item.vol || 0),
            timestamp: timestamp,
            interval: compatibleInterval, // 使用兼容的 interval
          }
          
          // 验证数据有效性（打印前3个和最后3个的详细信息）
          if (kline.open === 0 || kline.high === 0 || kline.low === 0 || kline.close === 0 || kline.timestamp === 0) {
            if (index < 3 || index >= data.data.kline_list.length - 3) {
              console.warn(`[Market Store] Invalid kline data for ${symbol} [${index}]:`, {
                original: item,
                parsed: kline,
                'item.keys': Object.keys(item),
                'item.kline_timestamp': item.kline_timestamp,
                'item.timestamp': item.timestamp,
                'item.time': item.time
              })
            }
          }
          
          return kline
        })
        
        // 更新K线数据
        if (!klineDataMap.value[symbol]) {
          klineDataMap.value[symbol] = []
        }
        klineDataMap.value[symbol] = klines.sort((a, b) => a.timestamp - b.timestamp)
        console.log(`[Market Store] Loaded ${klines.length} klines for ${symbol}`)
      } else if (data.data && Array.isArray(data.data)) {
        // 兼容其他格式
        const klines: KlineData[] = data.data.map((item: any) => ({
          symbol: item.symbol || symbol,
          open: Number(item.open || 0),
          high: Number(item.high || 0),
          low: Number(item.low || 0),
          close: Number(item.close || 0),
          volume: Number(item.volume || 0),
          timestamp: item.time || item.timestamp || Date.now(),
          interval: interval,
        }))
        
        if (!klineDataMap.value[symbol]) {
          klineDataMap.value[symbol] = []
        }
        klineDataMap.value[symbol] = klines.sort((a, b) => a.timestamp - b.timestamp)
      }
    } catch (error) {
      console.error('[Market Store] Fetch klines failed:', error)
    }
  }
  
  /**
   * 批量获取K线数据（优先从Redis加载）
   * @param symbols 交易对列表
   * @param category 分类
   * @param interval K线周期
   * @param limit 返回数量，默认20（用于sparkline显示）
   */
  const fetchBatchKlines = async (symbols: string[], category: string = 'Crypto', interval: string = '1m', limit: number = 20) => {
    if (!symbols || symbols.length === 0) {
      console.warn('[Market Store] No symbols provided for batch kline fetch')
      return
    }
    
    try {
      // 使用环境变量配置的 API base URL
      const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
      
      // 检查是否为股票分类
      const isStock = category === 'Stock' || category === 'US' || category === 'HK' || category === 'A'
      // 如果是股票，确保使用支持的 interval
      const compatibleInterval = isStock ? getStockCompatibleInterval(interval) : interval
      const klineType = convertIntervalToKlineType(compatibleInterval, isStock)
      console.log(`[Market Store] Fetching batch klines, interval: ${compatibleInterval}, kline_type: ${klineType}, category: ${category}, symbols count: ${symbols.length}`)
      
      // 先尝试从Redis批量获取
      let response = await fetchMarketKline(`${apiBaseUrl}/market/redis/kline/batch`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          symbols,
          interval: compatibleInterval, // 使用兼容的 interval
        }),
      })
      
      let data = await response.json()
      
      // 检查Redis中的数据量是否足够
      let needFetchFromAPI = false
      if (data.ret === 200 && data.data && Array.isArray(data.data)) {
        const cachedCount = data.data.length
        
        // 检查每个symbol的K线数量是否足够
        for (const item of data.data) {
          const klineList = item.kline_list || []
          if (!klineList || klineList.length < limit) {
            needFetchFromAPI = true
            console.log(`[Market Store] Redis cache insufficient for ${item.symbol || item.code}: has ${klineList.length}, need ${limit}`)
            break
          }
        }
        
        // 如果symbol数量不足，也需要从API获取
        if (cachedCount < symbols.length) {
          needFetchFromAPI = true
          console.log(`[Market Store] Only ${cachedCount}/${symbols.length} symbols cached, fetching missing from API...`)
        }
        
        if (!needFetchFromAPI) {
          console.log(`[Market Store] Loaded all ${cachedCount} symbols from Redis with sufficient data (limit: ${limit})`)
        }
      } else {
        needFetchFromAPI = true
      }
      
      // 如果Redis数据不足，从HTTP API获取
      if (needFetchFromAPI) {
        if (data.ret === 200 && data.data && Array.isArray(data.data)) {
          // 找出缺失的symbols或数据不足的symbols
          const cachedSymbols = new Set(data.data.map((item: any) => item.symbol || item.code))
          const missingSymbols = symbols.filter(s => {
            const cachedItem = data.data.find((item: any) => (item.symbol || item.code) === s)
            return !cachedItem || !cachedItem.kline_list || cachedItem.kline_list.length < limit
          })
          
          if (missingSymbols.length > 0) {
            console.log(`[Market Store] Fetching ${missingSymbols.length} symbols from HTTP API (limit: ${limit})...`)
            // 获取缺失的symbols
            const apiResponse = await fetchMarketKline(`${apiBaseUrl}/market/kline/batch`, {
              method: 'POST',
              headers: {
                'Content-Type': 'application/json',
              },
              body: JSON.stringify({
                symbols: missingSymbols,
                category,
                interval: compatibleInterval, // 使用兼容的 interval
                limit: limit, // 传递limit参数
              }),
            })
            const apiData = await apiResponse.json()
            
            // 合并结果：用API数据替换或补充缓存数据
            if (apiData.data && Array.isArray(apiData.data)) {
              // 创建symbol到数据的映射
              const apiDataMap = new Map()
              apiData.data.forEach((item: any) => {
                const key = item.symbol || item.code
                if (key) {
                  apiDataMap.set(key, item)
                }
              })
              
              // 更新或添加数据
              data.data = data.data.map((item: any) => {
                const key = item.symbol || item.code
                if (apiDataMap.has(key)) {
                  return apiDataMap.get(key) // 用API数据替换
                }
                return item
              })
              
              // 添加新的symbols
              apiData.data.forEach((item: any) => {
                const key = item.symbol || item.code
                if (!cachedSymbols.has(key)) {
                  data.data.push(item)
                }
              })
            }
          }
        } else {
          // Redis中没有数据，从API获取
          console.log(`[Market Store] No cached klines, fetching from HTTP API (limit: ${limit})...`)
          response = await fetchMarketKline(`${apiBaseUrl}/market/kline/batch`, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
            },
            body: JSON.stringify({
              symbols,
              category,
              interval: compatibleInterval, // 使用兼容的 interval
              limit: limit, // 传递limit参数
            }),
          })
          data = await response.json()
        }
      } else {
        // Redis中没有数据，从API获取
        console.log(`[Market Store] No cached klines, fetching from API...`)
        response = await fetchMarketKline(`${apiBaseUrl}/market/kline/batch`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            symbols,
            category,
            interval: compatibleInterval, // 使用兼容的 interval
            limit: limit, // 传递limit参数
          }),
        })
        data = await response.json()
      }
      
      if (!response.ok) {
        const errorData = await response.json().catch(() => ({ error: 'Unknown error' }))
        console.error('[Market Store] Batch kline fetch failed:', response.status, errorData)
        return
      }
      
      if (data.error) {
        console.warn('[Market Store] Batch kline API returned error:', data.error)
        return
      }
      
      // 后端返回格式：{ ret: 200, msg: "ok", data: [{ code: "BTCUSDT", symbol: "BTCUSD", kline_type: 1, kline_list: [...] }] }
      if (data.ret === 200 && data.data && Array.isArray(data.data)) {
        data.data.forEach((item: any) => {
          // 优先使用后端映射的symbol，如果没有则使用code
          const symbol = item.symbol || item.code
          const alltickCode = item.code || item.symbol
          if (!symbol) return
          
          // 同时使用symbol和alltickCode作为key存储，确保能找到
          const keysToStore = [symbol]
          if (alltickCode && alltickCode !== symbol) {
            keysToStore.push(alltickCode)
          }
          
          // 如果symbol在symbolMapping中，也要存储对应的alltickSymbol
          const mappedAlltick = symbolMapping.value[symbol]
          if (mappedAlltick && !keysToStore.includes(mappedAlltick)) {
            keysToStore.push(mappedAlltick)
          }
          
          // 如果alltickCode在symbolMapping中，也要存储对应的内部symbol
          const mappedInternal = symbolMapping.value[alltickCode]
          if (mappedInternal && !keysToStore.includes(mappedInternal)) {
            keysToStore.push(mappedInternal)
          }
          
          // 处理kline_list数组
          if (item.kline_list && Array.isArray(item.kline_list)) {
            const klines: KlineData[] = []
            
            item.kline_list.forEach((k: any) => {
              // 后端返回的timestamp字段可能是 kline_timestamp 或 timestamp
              // 后端返回的是毫秒级时间戳（kline_timestamp字段）
              const timestampMs = Number(k.kline_timestamp || k.timestamp || 0)
              
              // 如果时间戳是秒级的（小于10000000000），转换为毫秒级
              const timestamp = timestampMs < 10000000000 ? timestampMs * 1000 : timestampMs
              
              const kline: KlineData = {
                symbol: symbol,
                open: Number(k.open_price || 0),
                high: Number(k.high_price || 0),
                low: Number(k.low_price || 0),
                close: Number(k.close_price || 0),
                volume: Number(k.volume || 0),
                timestamp: timestamp,
                interval: compatibleInterval, // 使用兼容的 interval
              }
              
              // 验证数据有效性
              if (kline.open === 0 || kline.high === 0 || kline.low === 0 || kline.close === 0 || kline.timestamp === 0) {
                console.warn(`[Market Store] Invalid kline data in batch for ${symbol}:`, k, 'parsed:', kline)
              }
              
              klines.push(kline)
            })
            
            // 按时间戳排序
            klines.sort((a, b) => a.timestamp - b.timestamp)
            
            // 为每个key存储K线数据
            keysToStore.forEach(key => {
              if (!klineDataMap.value[key]) {
                klineDataMap.value[key] = []
              }
              
              // 合并现有数据，去重
              const existingKlines = klineDataMap.value[key]
              klines.forEach(newKline => {
                const existingIndex = existingKlines.findIndex((k) => k.timestamp === newKline.timestamp)
                if (existingIndex >= 0) {
                  existingKlines[existingIndex] = newKline
                } else {
                  existingKlines.push(newKline)
                }
              })
              
              // 重新排序并限制数量
              existingKlines.sort((a, b) => a.timestamp - b.timestamp)
              if (existingKlines.length > 100) {
                klineDataMap.value[key] = existingKlines.slice(-100)
              }
            })
            
            console.log(`[Market Store] ✅ Updated ${item.kline_list.length} klines for ${symbol} (stored with keys: ${keysToStore.join(', ')})`)
          }
        })
        
        console.log('[Market Store] ✅ Batch klines updated, available symbols:', Object.keys(klineDataMap.value))
      } else {
        console.warn('[Market Store] ⚠️ Unexpected batch kline response format:', data)
      }
    } catch (error) {
      console.error('[Market Store] Fetch batch klines failed:', error)
    }
  }

  /**
   * 设置当前交易对
   */
  const setCurrentSymbol = (symbol: string, category: string = 'Crypto') => {
    if (currentSymbol.value && currentSymbol.value !== symbol) {
      // 可以取消之前的订阅，或者保留订阅
    }
    currentSymbol.value = symbol
    subscribeSymbol(symbol, category)
    // 同时获取K线数据
    fetchKlines(symbol, category, '1m', 100)
  }

  /**
   * 获取交易对的最新价格
   */
  const getPrice = (symbol: string): number => {
    if (!symbol) return 0
    
    // 优先使用价格映射
    if (priceMap.value[symbol]?.price && priceMap.value[symbol].price > 0) {
      return priceMap.value[symbol].price
    }
    
    // 其次使用Tick数据
    if (tickDataMap.value[symbol]?.price && tickDataMap.value[symbol].price > 0) {
      return tickDataMap.value[symbol].price
    }
    
    return 0
  }

  /**
   * 获取交易对的24h涨跌幅
   */
  const getChange24h = (symbol: string): { change: number; changePct: number } => {
    const priceInfo = priceMap.value[symbol]
    if (priceInfo) {
      return {
        change: priceInfo.change24h,
        changePct: priceInfo.changePct24h,
      }
    }
    return { change: 0, changePct: 0 }
  }

  /**
   * 获取交易对的K线数据
   */
  const getKlines = (symbol: string, limit = 100): KlineData[] => {
    const klines = klineDataMap.value[symbol] || []
    return klines.slice(-limit)
  }

  /**
   * 获取K线数据用于Sparkline（小图）
   * 只返回5分钟K线数据，用于首页预览
   */
  const getSparklineData = (symbol: string): number[] => {
    if (!symbol) return []
    
    // 尝试多种symbol匹配方式
    const symbolKeys = [
      symbol, // 直接使用symbol
      // 如果symbol是内部symbol，尝试查找对应的alltickSymbol
      symbolMapping.value[symbol],
      // 如果symbol是alltickSymbol，尝试查找对应的内部symbol
      ...Object.entries(symbolMapping.value).filter(([_, internal]) => internal === symbol).map(([alltick]) => alltick),
      // 尝试所有可能的映射关系
      ...Object.keys(symbolMapping.value).filter(key => symbolMapping.value[key] === symbol),
      ...Object.keys(symbolMapping.value).filter(key => key === symbol),
    ].filter((key): key is string => Boolean(key)) // 确保过滤掉所有falsy值，并类型断言为string
    
    // 去重
    const uniqueKeys = Array.from(new Set(symbolKeys))
    
    // 同时尝试从categorySymbolMap中查找
    // 如果symbol在categoryMap中，尝试查找对应的alltickSymbol
    const alltickSymbols = Object.keys(symbolMapping.value).filter(key => symbolMapping.value[key] === symbol)
    if (alltickSymbols.length > 0) {
      uniqueKeys.push(...alltickSymbols)
    }
    
    console.log(`[Market Store] 🔍 Looking for sparkline data (5m) for ${symbol}, trying keys:`, uniqueKeys)
    console.log(`[Market Store] 🔍 Available kline keys:`, Object.keys(klineDataMap.value).slice(0, 20))
    
    for (const key of uniqueKeys) {
      const allKlines = klineDataMap.value[key] || []
      // 只获取5分钟K线数据
      const klines5m = allKlines.filter(k => k.interval === '5m')
      if (klines5m && klines5m.length > 0) {
        // 取最近20根5分钟K线
        const recentKlines = klines5m.slice(-20)
        const sparklineData = recentKlines.map((k) => k.close).filter(price => price > 0)
        if (sparklineData.length > 0) {
          console.log(`[Market Store] ✅ Found sparkline data (5m) for ${symbol} (key: ${key}), length: ${sparklineData.length}`)
          return sparklineData
        }
      }
    }
    
    // 如果还是找不到5m数据，尝试在所有K线数据中查找（可能是大小写或格式问题）
    const allKlineKeys = Object.keys(klineDataMap.value)
    const matchedKey = allKlineKeys.find(key => {
      // 精确匹配
      if (key === symbol) return true
      // 大小写不敏感匹配
      if (key.toLowerCase() === symbol.toLowerCase()) return true
      // 通过映射关系匹配
      if (symbolMapping.value[key] === symbol) return true
      if (symbolMapping.value[symbol] === key) return true
      // 检查是否是反向映射
      const reverseMapped = Object.entries(symbolMapping.value).find(([alltick, internal]) => 
        (alltick === symbol && internal === key) || (alltick === key && internal === symbol)
      )
      return !!reverseMapped
    })
    
    if (matchedKey) {
      const allKlines = klineDataMap.value[matchedKey] || []
      // 只获取5分钟K线数据
      const klines5m = allKlines.filter(k => k.interval === '5m')
      if (klines5m && klines5m.length > 0) {
        // 取最近20根5分钟K线
        const recentKlines = klines5m.slice(-20)
        const sparklineData = recentKlines.map((k) => k.close).filter(price => price > 0)
        if (sparklineData.length > 0) {
          console.log(`[Market Store] ✅ Found sparkline data (5m) for ${symbol} (matched key: ${matchedKey}), length: ${sparklineData.length}`)
          return sparklineData
        }
      }
    }
    
    // 只在调试模式下输出详细日志，避免日志过多
    if (Math.random() < 0.01) { // 只输出1%的日志
      console.log(`[Market Store] ⚠️ No sparkline data found for ${symbol}, available symbols:`, Object.keys(klineDataMap.value))
    }
    return []
  }

  /**
   * 当前交易对的Tick数据
   */
  const currentTick = computed(() => {
    return currentSymbol.value ? tickDataMap.value[currentSymbol.value] : null
  })

  /**
   * 当前交易对的K线数据
   */
  const currentKlines = computed(() => {
    return currentSymbol.value ? getKlines(currentSymbol.value) : []
  })

  /**
   * 断开连接（WebSocket）
   */
  const disconnect = (category: string = 'Crypto') => {
    // 取消所有订阅并断开 WebSocket
    marketWebSocket.unsubscribeAll()
    marketWebSocket.disconnect()
    stopPriceListener?.(); stopPriceListener = undefined
    console.log(`[Market Store] [${category}] All subscriptions cancelled and WebSocket disconnected`)
  }

  return {
    currentSymbol,
    tickDataMap,
    klineDataMap,
    priceMap,
    quoteStatusMap,
    getConversionRate,
    getQuoteStatus,
    initMarketService,
    subscribeSymbol,
    subscribeSymbolsBatch,
    subscribeSymbols,
    unsubscribeSymbol,
    setCurrentSymbol,
    getPrice,
    getChange24h,
    getKlines,
    getSparklineData,
    fetchKlines,
    fetchBatchKlines,
    fetchBatchPricesFromRedis,
    ensureHeartbeatAndSubscription,
    currentTick,
    currentKlines,
    disconnect,
  }
})


