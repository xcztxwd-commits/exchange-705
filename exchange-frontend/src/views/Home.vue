<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import Sparkline from '@/components/Sparkline.vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { useMarketStore } from '@/store/market'
import { useLocaleStore } from '@/store/locale'
import { getImageUrl } from '@/utils/imageUrl'
// 市场休市时间判断已移除，改用阿里云市场API返回的数据来判断市场状态

const router = useRouter()

// 用户与资产信息
const auth = useAuthStore()
auth.load()

// 公告弹窗相关
const showAnnouncementModal = ref(false)
const countdown = ref(6)
const countdownTimer = ref<number | null>(null)
const hasShownAnnouncement = ref(false)
const latestAnnouncement = ref<{ title?: string; content?: string } | null>(null) // 最新公告（根据当前语言）

// 价格轮询定时器
const pricePollingTimer = ref<number | null>(null)
// K线轮询定时器
const klinePollingTimer = ref<number | null>(null)

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 行情数据
const marketStore = useMarketStore()

const uid = ref<string | number>('')
const nickname = ref('')
const fundBalance = ref(0)
const contractBalance = ref(0)
const optionBalance = ref(0)

// 今日收益数据
const todayYield = ref(0)
const todayPct = ref(0)

// 币种数据
const hotSymbols = ref<any[]>([])
const categorySymbols = ref<any[]>([])
const allSymbols = ref<any[]>([]) // 存储所有币种数据
const activeCategory = ref('US')

// 使用 computed 确保响应式更新
 const quickList = computed(() => [
   { id: 'credit', icon: '/img/loana.png', label: localeStore.t('creditLoan') },
   { id: 'bank', icon: '/img/banke.png', label: localeStore.t('bankCardBuy') },
   { id: 'video', icon: '/img/video.png', label: localeStore.t('videoIntro') },
   { id: 'financial', icon: '/img/licai.png', label: localeStore.t('financialManagement') },
 ])

// 监听语言变化，确保页面更新
watch(
  () => localeStore.locale,
  (newLocale) => {
    console.log('[Home] Language changed to:', newLocale)
    // 触发响应式更新
  },
  { immediate: false }
)

const categories = ref<{ key: string; label: string }[]>([])

function formatPrice(price: number | string | null | undefined, precision: number = 2) {
  const n = Number(price || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: precision, maximumFractionDigits: precision })
}

// 判断K线数据是否已加载
function hasSparklineData(symbol: any): boolean {
  const symbolKey = symbol.alltickSymbol || symbol.symbol
  if (!symbolKey) return false
  
  // 检查market store中的实时数据
  const sparklineData = marketStore.getSparklineData(symbolKey)
  if (sparklineData && sparklineData.length > 0) {
    return true
  }
  
  // 检查数据库缓存数据
  if (symbol.sparklineData) {
    try {
      const data = JSON.parse(symbol.sparklineData)
      if (data && data.length > 0) {
        return true
      }
    } catch (e) {
      // 忽略解析错误
    }
  }
  
  return false
}

function parseSparklineData(data: string | null | undefined, symbol?: string): number[] {
  // 优先使用market store中的实时K线数据
  if (symbol) {
    // 尝试多种symbol匹配方式
    const symbolKeys = [symbol].filter(Boolean)
    
    for (const key of symbolKeys) {
      const sparklineData = marketStore.getSparklineData(key)
      if (sparklineData && sparklineData.length > 0) {
        console.log(`[Home] Using real-time sparkline data for ${key}, length: ${sparklineData.length}`)
        return sparklineData
      }
    }
  }
  
  // 如果没有实时数据，使用数据库中的缓存数据
  if (!data) {
    return []
  }
  try {
    const arr = JSON.parse(data)
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

// 处理图标URL，确保能正确加载
// 获取图标URL（使用统一的 getImageUrl 函数）
function getIconUrl(iconUrl: string | null | undefined): string {
  return getImageUrl(iconUrl)
}

// 处理图片加载错误
function handleImageError(event: Event) {
  const img = event.target as HTMLImageElement
  const src = img.src
  console.error('[Home] Image load error:', src)
  // 如果图片加载失败，隐藏图片或显示默认图标
  img.style.display = 'none'
}

// 获取实时价格（使用市场数据存储的价格）
function getRealTimePrice(symbol: any): number {
  const wsPrice = marketStore.getPrice(symbol.symbol || symbol.alltickSymbol)
  if (wsPrice > 0) {
    return wsPrice
  }
  return Number(symbol.currentPrice || 0)
}

// 判断是否显示休市
// 休市判断已由后端处理（基于美股交易时间），前端不再基于K线数据判断
function isMarketClosed(_symbol: any): boolean {
  // 后端已处理美股交易时间的休市判断，前端不再判断
  return false
}

// 获取实时涨跌幅（使用市场数据存储的涨跌幅）
function getRealTimeChange(symbol: any): { change: number; changePct: number } {
  
  // 尝试多种symbol匹配方式
  const symbolKeys = [
    symbol.symbol,
    symbol.alltickSymbol,
    symbol.code,
  ].filter(Boolean)
  
  for (const key of symbolKeys) {
    const wsChange = marketStore.getChange24h(key)
    if (wsChange.changePct !== 0) {
      return wsChange
    }
  }
  
  return {
    change: Number(symbol.priceChange24h || 0),
    changePct: Number(symbol.priceChangePct24h || 0),
  }
}

function getChangeColor(change: number | null | undefined) {
  const val = Number(change || 0)
  return val >= 0 ? '#2abf4b' : '#e25d4d'
}

// 缓存键名
const SYMBOLS_CACHE_KEY = 'home_symbols_cache'
const SYMBOLS_CACHE_TIME_KEY = 'home_symbols_cache_time'
const CACHE_EXPIRE_TIME = 5 * 60 * 1000 // 缓存有效期：5分钟（缩短缓存时间，确保数据及时更新）

// 一次性加载所有币种数据（优先使用API最新数据，缓存仅作为降级方案）
async function loadAllSymbols(forceRefresh = false) {
  try {
    // 如果强制刷新，清除缓存
    if (forceRefresh) {
      localStorage.removeItem(SYMBOLS_CACHE_KEY)
      localStorage.removeItem(SYMBOLS_CACHE_TIME_KEY)
      console.log('[Home] Cache cleared, forcing refresh')
    }
    
    // 先加载分类配置（用于首页分类排序）
    try {
      const catRes: any = await request.get('/market/categories')
      const catList = (catRes && catRes.list) || []
      if (Array.isArray(catList) && catList.length > 0) {
        categories.value = catList
          .filter((c: any) => c && (c.enabled !== false))
          .map((c: any) => ({
            key: c.key,
            label: c.label || c.key,
          }))
      } else {
        categories.value = [
          { key: 'US', label: 'US' },
          { key: 'Crypto', label: 'Crypto' },
          { key: 'Metal', label: 'Metal' },
          { key: 'Forex', label: 'Forex' },
          { key: 'CFD', label: 'CFD' },
          { key: 'Oil', label: 'Oil' },
        ]
      }
    } catch (e) {
      console.error('[Home] 加载分类配置失败:', e)
      categories.value = [
        { key: 'US', label: 'US' },
        { key: 'Crypto', label: 'Crypto' },
        { key: 'Metal', label: 'Metal' },
        { key: 'Forex', label: 'Forex' },
        { key: 'CFD', label: 'CFD' },
        { key: 'Oil', label: 'Oil' },
      ]
    }
    
    // 再调用API获取最新币种数据（确保数据准确性）
    console.log('[Home] Fetching latest symbols from API...')
    let symbols: any[] = []
    let apiSuccess = false
    
    try {
      const res: any = await request.get('/market/all')
      symbols = res.list || []
      apiSuccess = true
      console.log(`[Home] ✅ Fetched ${symbols.length} symbols from API`)
    } catch (e) {
      console.error('[Home] Failed to fetch symbols from API, will try cache as fallback', e)
      apiSuccess = false
    }
    
    // 如果API调用成功，验证并更新缓存
    if (apiSuccess) {
      // 检查缓存，如果数量差异超过50%，清除旧缓存
      const cachedData = localStorage.getItem(SYMBOLS_CACHE_KEY)
      if (cachedData) {
        try {
          const cachedSymbols = JSON.parse(cachedData)
          const apiCount = symbols.length
          const cachedCount = cachedSymbols.length
          const diffRatio = Math.abs(apiCount - cachedCount) / Math.max(apiCount, cachedCount, 1)
          
          if (diffRatio > 0.5) {
            console.warn(`[Home] ⚠️ Symbol count mismatch: API=${apiCount}, Cache=${cachedCount}, diffRatio=${diffRatio.toFixed(2)}`)
            console.warn('[Home] ⚠️ Clearing stale cache (data changed significantly)')
            localStorage.removeItem(SYMBOLS_CACHE_KEY)
            localStorage.removeItem(SYMBOLS_CACHE_TIME_KEY)
          } else {
            console.log(`[Home] ✅ Symbol count consistent: API=${apiCount}, Cache=${cachedCount}`)
          }
        } catch (e) {
          console.warn('[Home] Failed to compare cache with API data', e)
        }
      }
      
      // 使用API返回的最新数据
      allSymbols.value = symbols
      
      // 保存到缓存
      try {
        localStorage.setItem(SYMBOLS_CACHE_KEY, JSON.stringify(symbols))
        localStorage.setItem(SYMBOLS_CACHE_TIME_KEY, String(Date.now()))
        console.log('[Home] ✅ Updated cache with latest symbols from API')
      } catch (e) {
        console.warn('[Home] Failed to cache symbols', e)
      }
    } else {
      // API调用失败，尝试使用缓存（降级方案）
      const cachedTime = localStorage.getItem(SYMBOLS_CACHE_TIME_KEY)
      const cachedData = localStorage.getItem(SYMBOLS_CACHE_KEY)
      
      if (cachedData && cachedTime) {
        const cacheAge = Date.now() - Number(cachedTime)
        if (cacheAge < CACHE_EXPIRE_TIME) {
          try {
            const cachedSymbols = JSON.parse(cachedData)
            console.log(`[Home] ⚠️ Using cached symbols as fallback: ${cachedSymbols.length} symbols (cache age: ${Math.round(cacheAge / 1000)}s)`)
            symbols = cachedSymbols
            allSymbols.value = symbols
          } catch (e) {
            console.error('[Home] Failed to parse cached symbols', e)
            allSymbols.value = []
            return
          }
        } else {
          console.log('[Home] Cache expired, cannot use fallback')
          allSymbols.value = []
          return
        }
      } else {
        console.log('[Home] No cache available, cannot use fallback')
        allSymbols.value = []
        return
      }
    }
    
    // 如果 symbols 为空，直接返回
    if (!symbols || symbols.length === 0) {
      console.warn('[Home] ⚠️ No symbols loaded, skipping subscription')
      hotSymbols.value = []
      categorySymbols.value = []
      return
    }
    
    // 提取热门币种（只显示启用的）
    hotSymbols.value = symbols.filter((s: any) => s.isHot === true && (s.isEnabled !== false))
    
    // 检查当前分类是否有可用币种，如果没有则切换到第一个有可用币种的分类
    const currentCategoryHasSymbols = symbols.some((s: any) => 
      s.category === activeCategory.value && (s.isEnabled !== false)
    )
    if (!currentCategoryHasSymbols) {
      // 找到第一个有可用币种的分类
      const list = Array.isArray(categories.value) ? categories.value : []
      const firstAvailableCategory = list.find((cat) => {
        return symbols.some(
          (s: any) => s.category === cat.key && (s.isEnabled !== false)
        )
      })
      if (firstAvailableCategory) {
        activeCategory.value = firstAvailableCategory.key
      }
    }
    
    // 按分类过滤当前分类的币种
    updateCategorySymbols()
    
    // 订阅所有币种的行情（按分类分组）
    // 确保所有币种都被正确映射，包括所有分类（Forex、CFD、Metal等）
    const symbolList = symbols.map((s: any) => {
      const category = s.category || 'Crypto'
      const alltickSymbol = s.alltickSymbol || s.symbol
      return {
        symbol: s.symbol,
        category: category,
        alltickSymbol: alltickSymbol,
      }
    })
    
    // 批量获取K线数据（按分类分组）
    // 先准备分组数据
    const grouped = new Map<string, string[]>()
    symbolList.forEach((s: { symbol: string; category: string; alltickSymbol?: string }) => {
      const cat = s.category || 'Crypto'
      const alltickSymbol = s.alltickSymbol || s.symbol
      if (!grouped.has(cat)) {
        grouped.set(cat, [])
      }
      // 使用alltickSymbol而不是symbol，因为后端API需要alltickSymbol
      grouped.get(cat)!.push(alltickSymbol)
    })
    
    // 立即批量从 Redis 获取价格和涨幅数据（不等待 WebSocket）
    // 传递完整的 symbol 映射信息（包括 symbol 和 alltickSymbol）
    if (symbolList.length > 0) {
      // 价格数据立即加载（异步，不阻塞）
      marketStore.fetchBatchPricesFromRedis(symbolList).then(() => {
        console.log('[Home] ✅ Loaded prices from Redis for all symbols')
      }).catch((error) => {
        console.error('[Home] Failed to load prices from Redis:', error)
      })
    }
    
    // K线数据异步加载（不等待，在后台加载）
    // 使用5分钟K线用于预览，获取20条数据用于sparkline显示
    // 页面会先使用数据库中的sparklineData显示，然后异步更新
    Array.from(grouped.entries()).forEach(([category, alltickSymbols]) => {
      marketStore.fetchBatchKlines(alltickSymbols, category, '5m', 20).then(() => {
        console.log(`[Home] ✅ K-line data loaded for category ${category} (5m interval)`)
      }).catch((error) => {
        console.error(`[Home] Failed to load K-line data for category ${category}:`, error)
      })
    })
    console.log('[Home] K-line and price data loading in background...')
    
    // 统计各分类的币种数量
    const categoryCounts = new Map<string, number>()
    symbolList.forEach((s: { symbol: string; category: string; alltickSymbol?: string }) => {
      const cat = s.category || 'Crypto'
      categoryCounts.set(cat, (categoryCounts.get(cat) || 0) + 1)
    })
    console.log('[Home] Symbol counts by category:', Object.fromEntries(categoryCounts))
    console.log('[Home] Total symbols to subscribe:', symbolList.length)
    console.log('[Home] Sample symbols:', symbolList.slice(0, 10))
    
    if (symbolList.length > 0) {
      console.log('[Home] Starting batch subscription for all symbols...')
      // 异步订阅，在后台执行
      marketStore.subscribeSymbols(symbolList).then(() => {
        console.log('[Home] ✅ Batch subscription completed for all symbols')
      }).catch((error) => {
        console.error('[Home] Batch subscription failed:', error)
      })
      
      // 启动批量价格轮询（每3秒更新一次，与WebSocket保持一致）
      startPricePolling(symbolList)
      // 启动K线轮询（每3秒更新一次，与WebSocket保持一致）
      startKlinePolling()
    }
  } catch (e) {
    console.error('load all symbols error', e)
    // 如果接口调用失败，尝试使用缓存（即使已过期）
    const cachedData = localStorage.getItem(SYMBOLS_CACHE_KEY)
    if (cachedData) {
      try {
        const symbols = JSON.parse(cachedData)
        allSymbols.value = symbols
        hotSymbols.value = symbols.filter((s: any) => s.isHot === true)
        updateCategorySymbols()
        console.log('[Home] Using expired cache as fallback')
      } catch (parseError) {
        console.error('[Home] Failed to parse fallback cache', parseError)
      }
    }
  }
}

// 更新当前分类的币种列表（前端过滤，不调用接口）
function updateCategorySymbols() {
  categorySymbols.value = allSymbols.value.filter((s: any) => {
    return s.category === activeCategory.value && (s.isEnabled !== false) // 过滤掉未启用的币种
  })
  console.log(`[Home] Filtered ${categorySymbols.value.length} symbols for category ${activeCategory.value}`)
}

// 计算可用的分类（有启用币种的分类）
const availableCategories = computed(() => {
  // categories 是 ref，需要使用 .value
  const list = Array.isArray(categories.value) ? categories.value : []
  return list.filter((cat) => {
    // 检查该分类下是否有启用的币种
    const hasEnabledSymbols = allSymbols.value.some((s: any) => {
      return s.category === cat.key && (s.isEnabled !== false)
    })
    return hasEnabledSymbols
  })
})

// 选择分类（只在前端过滤，不调用接口）
function selectCategory(cat: string) {
  activeCategory.value = cat
  
  // 确保该分类的WebSocket已初始化
        marketStore.initMarketService(cat).catch((e) => {
    console.error(`[Home] Failed to init WebSocket for ${cat}:`, e)
  })
  
  // 只在前端过滤，不调用接口
  updateCategorySymbols()
}

// 点击币种，跳转到交易页面（默认跳转到期限页面）
function goToTrade(symbol: any) {
  const symbolName = symbol.symbol || symbol.alltickSymbol
  const category = symbol.category || activeCategory.value
  // 跳转到交易页面，并传递币种参数，默认显示期限标签页
  router.push({
    path: '/trade',
    query: {
      symbol: symbolName,
      category: category,
      tab: 'term', // 默认跳转到期限页面
    },
  })
}

// 处理快速入口点击
function handleQuickItemClick(id: string) {
  if (id === 'bank') {
    router.push('/deposit')
  } else if (id === 'credit') {
    router.push('/credit-loan')
  } else if (id === 'financial') {
    router.push('/financial-management')
  } else if (id === 'video') {
    // router.push('/video-intro') // Implement video intro later
  }
}

// 加载首页统计数据
async function loadDashboardStats() {
  try {
    const res: any = await request.get('/user/dashboard/stats')
    if (res && res.success && res.data) {
      todayYield.value = Number(res.data.todayYieldAmount || 0)
      todayPct.value = Number(res.data.changePct || 0)
    }
  } catch (e) {
    console.error('load dashboard stats error', e)
  }
}

// 关闭公告弹窗
function closeAnnouncementModal() {
  showAnnouncementModal.value = false
  if (countdownTimer.value) {
    clearInterval(countdownTimer.value)
    countdownTimer.value = null
  }
  // 标记已显示过公告，避免重复显示
  hasShownAnnouncement.value = true
  localStorage.setItem('hasShownAnnouncement', 'true')
}

// 启动倒计时
function startCountdown() {
  countdown.value = 6
  countdownTimer.value = window.setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      closeAnnouncementModal()
    }
  }, 1000)
}

// 将前端的 LocaleKey 映射到后端支持的语言代码
function getLanguageForBackend(localeKey: string): string {
  // 后端支持的语言代码映射（与前端所有支持的语种完全一致，共20种）
  const langMap: Record<string, string> = {
    'zh-TW': 'zh-TW',
    'zh-CN': 'zh-CN', // 简体中文（虽然前端LocaleKey中没有，但浏览器可能返回，需要支持）
    'en': 'en',
    'fr': 'fr',
    'de': 'de',
    'ru': 'ru',
    'es': 'es',
    'pt': 'pt',
    'it': 'it',
    'ar': 'ar',
    'tr': 'tr',
    'id': 'id',
    'my': 'my', // 缅甸语
    'hi': 'hi', // 印地语
    'cs': 'cs', // 捷克语
    'pl': 'pl', // 波兰语
    'ja': 'ja',
    'ko': 'ko',
    'th': 'th',
    'vi': 'vi',
  }
  return langMap[localeKey] || 'en'
}

// 加载最新公告（根据当前语言）
async function loadLatestAnnouncement() {
  try {
    // 获取当前语言代码
    const currentLocale = localeStore.getCurrentLocale ? localeStore.getCurrentLocale() : (localeStore.locale || 'en')
    const currentLanguage = getLanguageForBackend(currentLocale)
    console.log('[Home] Loading latest announcement for language:', currentLanguage, 'locale:', currentLocale)
    
    // 从后端获取最新公告（根据当前语言）
    const res: any = await request.get('/user/announcements/latest', {
      params: {
        language: currentLanguage
      }
    })
    
    if (res && res.announcement) {
      latestAnnouncement.value = {
        title: res.announcement.title || 'Welcome to use',
        content: res.announcement.content || ''
      }
      console.log('[Home] Loaded latest announcement for language:', currentLanguage)
    } else {
      // 如果没有公告，使用默认内容
      latestAnnouncement.value = {
        title: localeStore.t('welcomeToUse') || 'Welcome to use',
        content: 'Due to policy reasons, services are not provided to North Korea, Israel, China, Vanuatu, and Cuba.'
      }
    }
  } catch (e) {
    console.error('[Home] Failed to load latest announcement:', e)
    // 如果加载失败，使用默认内容
    latestAnnouncement.value = {
      title: localeStore.t('welcomeToUse') || 'Welcome to use',
      content: 'Due to policy reasons, services are not provided to North Korea, Israel, China, Vanuatu, and Cuba.'
    }
  }
}

// 检查是否需要显示公告
async function checkAnnouncement() {
  // 如果已登录，不显示公告
  if (auth.token) {
    return
  }
  
  // 检查是否已经显示过公告
  const hasShown = localStorage.getItem('hasShownAnnouncement')
  if (hasShown === 'true') {
    hasShownAnnouncement.value = true
    return
  }
  
  // 加载最新公告（根据当前语言）
  await loadLatestAnnouncement()
  
  // 首次访问且未登录，显示公告
  if (latestAnnouncement.value) {
    showAnnouncementModal.value = true
    startCountdown()
  }
}

onMounted(async () => {
  // 检查是否需要显示公告（未登录时，异步加载最新公告）
  await checkAnnouncement()
  
  const user = auth.user
  const userId = user?.id
  if (!userId) {
    // 未登录时，仍然加载币种数据（但不加载用户信息）
    await loadAllSymbols()
    return
  }

  try {
    const res: any = await request.get(`/user/${userId}/info`)
    uid.value = res.uid || res.id || userId
    nickname.value = res.nickname || res.email || user?.email || ''
    fundBalance.value = Number(res.fundBalance || 0)
    contractBalance.value = Number(res.contractBalance || 0)
    optionBalance.value = Number(res.optionBalance || 0)
  } catch (e) {
    console.error('load user info error', e)
  }

  // 加载首页统计数据
  await loadDashboardStats()

  // 一次性加载所有币种数据（会自动订阅行情和K线数据）
  // loadAllSymbols 内部会调用 subscribeSymbols，它会为所有分类初始化WebSocket并批量订阅
  await loadAllSymbols()
})

// 启动批量价格轮询（每3秒更新一次，与WebSocket保持一致）
function startPricePolling(_symbolList: Array<{ symbol: string; category: string; alltickSymbol?: string }>) {
  // 清除之前的定时器
  if (pricePollingTimer.value) {
    clearInterval(pricePollingTimer.value)
  }
  
  // 使用 allSymbols.value 的最新数据，确保始终使用最新的币种列表
  const updatePrices = () => {
    const currentSymbols = allSymbols.value.map((s: any) => ({
      symbol: s.symbol,
      category: s.category || 'Crypto',
      alltickSymbol: s.alltickSymbol || s.symbol
    }))
    updateBatchPrices(currentSymbols)
  }
  
  // 立即执行一次
  updatePrices()
  
  // 每3秒轮询一次（与WebSocket保持一致）
  pricePollingTimer.value = window.setInterval(() => {
    updatePrices()
  }, 3000) // 3秒 = 3000毫秒
  
  console.log('[Home] ✅ Started price polling (every 3 seconds)')
}

// 批量更新价格
async function updateBatchPrices(symbolList: Array<{ symbol: string; category: string; alltickSymbol?: string }>) {
  try {
    if (symbolList.length === 0) return
    
    console.log(`[Home] 🔄 Polling prices for ${symbolList.length} symbols...`)
    // 转换格式以匹配 fetchBatchPricesFromRedis 的参数类型
    const priceRequestData = symbolList.map(s => ({
      symbol: s.symbol,
      alltickSymbol: s.alltickSymbol || s.symbol
    }))
    await marketStore.fetchBatchPricesFromRedis(priceRequestData)
    console.log('[Home] ✅ Price polling completed')
  } catch (error) {
    console.error('[Home] ❌ Price polling failed:', error)
  }
}

// 停止价格轮询
function stopPricePolling() {
  if (pricePollingTimer.value) {
    clearInterval(pricePollingTimer.value)
    pricePollingTimer.value = null
    console.log('[Home] ✅ Stopped price polling')
  }
}

// 启动K线轮询（每3秒更新一次，与WebSocket保持一致）
function startKlinePolling() {
  // 清除之前的定时器
  if (klinePollingTimer.value) {
    clearInterval(klinePollingTimer.value)
  }
  
  // 使用 allSymbols.value 的最新数据，按分类分组更新K线
  const updateKlines = () => {
    if (allSymbols.value.length === 0) return
    
    // 按分类分组
    const grouped = new Map<string, string[]>()
    allSymbols.value.forEach((s: any) => {
      const category = s.category || 'Crypto'
      const alltickSymbol = s.alltickSymbol || s.symbol
      if (!grouped.has(category)) {
        grouped.set(category, [])
      }
      grouped.get(category)!.push(alltickSymbol)
    })
    
    // 为每个分类更新K线数据（使用5分钟K线用于sparkline显示）
    Array.from(grouped.entries()).forEach(([category, alltickSymbols]) => {
      marketStore.fetchBatchKlines(alltickSymbols, category, '5m', 20).catch((error) => {
        console.error(`[Home] Failed to update K-line data for category ${category}:`, error)
      })
    })
  }
  
  // 立即执行一次
  updateKlines()
  
  // 每3秒轮询一次（与WebSocket保持一致）
  klinePollingTimer.value = window.setInterval(() => {
    updateKlines()
  }, 3000) // 3秒 = 3000毫秒
  
  console.log('[Home] ✅ Started K-line polling (every 3 seconds)')
}

// 停止K线轮询
function stopKlinePolling() {
  if (klinePollingTimer.value) {
    clearInterval(klinePollingTimer.value)
    klinePollingTimer.value = null
    console.log('[Home] ✅ Stopped K-line polling')
  }
}

// 组件卸载时清理定时器
onUnmounted(() => {
  if (countdownTimer.value) {
    clearInterval(countdownTimer.value)
  }
  stopPricePolling()
  stopKlinePolling()
})

// 监听语言变化，重新加载公告
watch(
  () => {
    // 使用 getCurrentLocale 函数获取当前语言，如果没有则使用 locale 属性
    return localeStore.getCurrentLocale ? localeStore.getCurrentLocale() : (localeStore.locale || 'en')
  },
  async (newLocale) => {
    console.log('[Home] Language changed to:', newLocale)
    // 如果未登录且需要显示公告，重新加载公告（根据新语言）
    if (!auth.token && !hasShownAnnouncement.value) {
      await loadLatestAnnouncement()
    }
  },
  { immediate: false }
)

// 监听行情数据变化，实时更新UI
watch(
  () => marketStore.priceMap,
  () => {
    // 价格更新时，触发响应式更新
    // Vue会自动检测到computed的变化
    console.log('[Home] Price map updated:', Object.keys(marketStore.priceMap))
  },
  { deep: true }
)

// 监听Tick数据变化
watch(
  () => marketStore.tickDataMap,
  () => {
    // Tick数据更新时，触发响应式更新
    console.log('[Home] Tick data updated')
  },
  { deep: true }
)

// 监听K线数据变化，确保Sparkline能够更新
watch(
  () => marketStore.klineDataMap,
  () => {
    // K线数据更新时，触发响应式更新
    console.log('[Home] Kline data updated, symbols:', Object.keys(marketStore.klineDataMap))
  },
  { deep: true }
)
</script>

<template>
  <div class="home">
    <div class="card">
      <div class="card-header-top">
        <span class="logo">DEMO</span>
      </div>
      <div class="card-header">
        <div class="user-info">
          <template v-if="auth.token">
            <div class="user-email">{{ nickname || (auth.user && auth.user.email) || localeStore.t('user') }}</div>
            <div class="user-uid">UID: {{ uid || (auth.user && auth.user.id) }}</div>
          </template>
          <template v-else>
            <div class="user-email">{{ localeStore.t('pleaseLoginFirst') }}</div>
          </template>
        </div>
        <div class="header-right">
          <button class="icon-btn" @click="router.push('/customer-service')">
            <img src="/img/kf.png" :alt="localeStore.t('customerService')" />
          </button>
          <button class="icon-btn" @click="router.push('/language')">
            <img src="/img/yy.png" :alt="localeStore.t('language')" />
          </button>
        </div>
      </div>

      <div class="search-row">
        <div class="search-bar" @click="router.push('/search')">
          <svg class="search-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <circle cx="11" cy="11" r="6" stroke="#333" stroke-width="2" />
            <line x1="15.5" y1="15.5" x2="20" y2="20" stroke="#333" stroke-width="2" stroke-linecap="round" />
          </svg>
          <span class="search-placeholder" style="display: none;">{{ localeStore.t('searchSymbolContract') }}</span>
        </div>
      </div>

      <!-- 余额卡片 -->
      <div class="balance-card">
        <div class="balance-title">{{ localeStore.t('balanceText') }}</div>
        <div class="balance-amount" v-if="auth.token">${{ formatPrice(fundBalance + contractBalance + optionBalance, 2) }}</div>
        <div class="balance-amount" v-else>$0.00</div>
        <div class="balance-actions" style="margin-top: 16px;">
          <button class="btn-action btn-deposit" @click="router.push('/deposit')">
            <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 16V8"></path>
              <path d="M8 12l4-4 4 4"></path>
            </svg>
            {{ localeStore.t('quickDeposit') || '快速充值' }}
          </button>
          <button class="btn-action btn-withdraw" @click="router.push('/withdraw')">
            <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 8v8"></path>
              <path d="M8 12l4 4 4-4"></path>
            </svg>
            {{ localeStore.t('lightningWithdraw') }}
          </button>
        </div>
      </div>

      <!-- 热门币种卡片 -->
      <div class="market-scroll-container" v-if="hotSymbols.length > 0">
        <div class="market-row">
          <div 
            class="market-card" 
            v-for="(s, index) in hotSymbols" 
            :key="s.id" 
            :class="`market-card-${index % 2 === 0 ? 'left' : 'right'}`"
            @click="goToTrade(s)"
          >
          <div class="market-top">
            <div class="market-icons">
              <img 
                v-if="s.iconUrl" 
                class="symbol-icon" 
                :src="getIconUrl(s.iconUrl)" 
                :alt="s.symbol"
                @error="handleImageError"
              />
            </div>
            <span class="name">{{ s.symbol }}</span>
          </div>
          <div class="market-sparkline">
            <div v-if="!hasSparklineData(s)" class="sparkline-loading">
              <div class="spinner"></div>
            </div>
            <Sparkline
              v-else
              :data="parseSparklineData(s.sparklineData, s.alltickSymbol || s.symbol)"
              :color="getChangeColor(getRealTimeChange(s).changePct)"
              :width="100"
              :height="30"
            />
          </div>
          <div class="market-bottom">
            <div class="market-price" v-if="!isMarketClosed(s)">{{ formatPrice(getRealTimePrice(s), s.pricePrecision) }}</div>
            <div class="market-price market-closed" v-else>{{ localeStore.t('marketClosed') }}</div>
            <div class="market-change" v-if="!isMarketClosed(s)" :style="{ color: getChangeColor(getRealTimeChange(s).changePct) }">
              <span class="change-icon">{{ getRealTimeChange(s).changePct >= 0 ? '▲' : '▼' }}</span>
              {{ getRealTimeChange(s).changePct >= 0 ? '+' : '' }}{{ (getRealTimeChange(s).changePct || 0).toFixed(2) }}%
            </div>
            <div class="market-change" v-else style="color: #999;">-</div>
          </div>
        </div>
      </div>
      </div>

       <div class="quick-row">
        <div 
          class="quick-item" 
          v-for="q in quickList" 
          :key="q.id"
          @click="handleQuickItemClick(q.id)"
        >
          <img :src="q.icon" class="quick-icon" :alt="q.label" />
          <div class="quick-label">{{ q.label }}</div>
        </div>
      </div> 

      <!-- 分类标签 -->
      <div class="section-tabs-container" v-if="availableCategories.length > 0">
        <div class="section-tabs">
          <span
            v-for="cat in availableCategories"
            :key="cat.key"
            class="tab"
            :class="{ active: activeCategory === cat.key }"
            @click="selectCategory(cat.key)"
          >
            {{ cat.label }}
          </span>
        </div>
      </div>

      <!-- 分类币种列表 -->
      <div class="symbol-list" v-if="categorySymbols.length > 0">
        <div class="symbol-item" v-for="s in categorySymbols" :key="s.id" @click="goToTrade(s)">
          <div class="symbol-icons">
            <img 
              v-if="s.iconUrl" 
              class="symbol-icon" 
              :src="getIconUrl(s.iconUrl)" 
              :alt="s.symbol"
              @error="handleImageError"
            />
          </div>
          <div class="symbol-ticker">{{ s.symbol }}</div>
          <div class="symbol-sparkline">
            <div v-if="!hasSparklineData(s)" class="sparkline-loading">
              <div class="spinner"></div>
            </div>
            <Sparkline
              v-else
              :data="parseSparklineData(s.sparklineData, s.alltickSymbol || s.symbol)"
              :color="getChangeColor(getRealTimeChange(s).changePct)"
              :width="80"
              :height="24"
            />
          </div>
          <div class="symbol-price-group">
            <div class="symbol-price" v-if="!isMarketClosed(s)">{{ formatPrice(getRealTimePrice(s), s.pricePrecision) }}</div>
            <div class="symbol-price market-closed" v-else>{{ localeStore.t('marketClosed') }}</div>
            <div class="symbol-change" v-if="!isMarketClosed(s)" :style="{ color: getChangeColor(getRealTimeChange(s).changePct) }">
              <span class="change-icon">{{ getRealTimeChange(s).changePct >= 0 ? '▲' : '▼' }}</span>
              {{ Math.abs(getRealTimeChange(s).changePct).toFixed(2) }}%
            </div>
            <div class="symbol-change" v-else style="color: #999;">-</div>
          </div>
        </div>
      </div>
      <div v-else class="empty-symbols">{{ localeStore.t('noSymbolData') }}</div>
    </div>

    <Tabbar />

    <!-- 公告弹窗（未登录时显示，根据当前语言显示对应公告） -->
    <div v-if="showAnnouncementModal && latestAnnouncement" class="announcement-modal-overlay">
      <div class="announcement-modal-content">
        <div class="announcement-modal-title">{{ latestAnnouncement.title || localeStore.t('welcomeToUse') || 'Welcome to use' }}</div>
        <div class="announcement-modal-body">
          {{ latestAnnouncement.content || 'Due to policy reasons, services are not provided to North Korea, Israel, China, Vanuatu, and Cuba.' }}
        </div>
        <div class="announcement-modal-footer">
          <button class="announcement-accept-btn" :disabled="countdown > 0" @click="closeAnnouncementModal">
            {{ localeStore.t('accept') || 'Accept' }} <span v-if="countdown > 0">({{ countdown }}s)</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.home {
  background: #f5f7fb;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px 12px 80px;
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
  overflow-x: hidden;
}

.card {
  width: 100%;
  max-width: 430px;
  background: #fff;
  border-radius: 16px;
  padding: 16px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.06);
}

.card-header-top {
  display: flex;
  justify-content: center;
  align-items: center;
  margin-bottom: 12px;
}
.logo {
  font-size: 16px;
  font-weight: 800;
  letter-spacing: 1px;
  color: #333;
}
.card-header {
  position: relative;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 4px;
}
.user-info {
  display: flex;
  flex-direction: column;
}
.user-email {
  font-size: 15px;
  color: #333;
}
.user-uid {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
}
.header-right {
  display: flex;
  gap: 12px;
}
.icon-btn {
  width: 40px;
  height: 40px;
  background: transparent;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  cursor: pointer;
}
.icon-btn img {
  width: 32px;
  height: 32px;
  opacity: 0.85;
}

.search-row {
  margin-top: 12px;
}
.search-bar {
  width: 100%;
  height: 40px;
  background: #f5f5f5;
  border-radius: 20px;
  display: flex;
  align-items: center;
  padding: 0 16px;
  box-sizing: border-box;
}
.search-icon {
  width: 18px;
  height: 18px;
  margin-right: 8px;
}
.search-placeholder {
  font-size: 13px;
  color: #b0b7c4;
}

.balance-card {
  margin-top: 16px;
  background: #fafafa;
  border-radius: 16px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.02);
}
.balance-title {
  font-size: 14px;
  font-weight: bold;
  color: #333;
  margin-bottom: 4px;
}
.balance-amount {
  font-size: 28px;
  font-weight: 900;
  color: #8bc34a;
  margin-bottom: 4px;
}
.balance-today {
  font-size: 12px;
  color: #666;
  margin-bottom: 16px;
}
.text-green {
  color: #8bc34a;
}
.text-red {
  color: #e25d4d;
}
.balance-actions {
  display: flex;
  gap: 12px;
  width: 100%;
}
.btn-action {
  flex: 1;
  height: 44px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 15px;
  font-weight: bold;
  color: #fff;
  border: none;
}
.btn-deposit {
  background: #8bc34a;
}
.btn-withdraw {
  background: #673ab7;
}

.market-scroll-container {
  margin-top: 14px;
  overflow-x: auto;
  overflow-y: hidden;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE and Edge */
}

.market-scroll-container::-webkit-scrollbar {
  display: none; /* Chrome, Safari, Opera */
}

.market-row {
  display: flex;
  gap: 10px;
  width: max-content;
  padding-bottom: 4px;
}
.market-card {
  border-radius: 12px;
  padding: 12px;
  min-height: 120px;
  min-width: 180px;
  flex-shrink: 0;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.03);
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  background: linear-gradient(135deg, #f2f7e8 0%, #e6f0d3 100%);
}
.market-card-left {
}
.market-card-right {
}
.market-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08);
}
.market-top {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  margin-bottom: 8px;
}
.market-icons {
  position: relative;
  display: flex;
  align-items: center;
  width: 40px;
  height: 40px;
}
.market-card .symbol-icon {
  width: 36px;
  height: 36px;
  border-radius: 6px;
  object-fit: cover;
}
.name {
  font-size: 15px;
  color: #333;
}
.market-sparkline {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 8px 0;
}

.market-sparkline .sparkline-loading {
  width: 100px;
  height: 30px;
  min-height: 30px;
}
.market-bottom {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-top: auto;
}
.market-price {
  font-size: 18px;
  font-weight: 800;
  color: #333;
}
.market-change {
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 2px;
}
.change-icon {
  font-size: 10px;
}

.quick-row {
  display: flex;
  justify-content: space-between;
  margin-top: 18px;
  padding: 0 4px;
}
.quick-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  padding: 8px 4px;
  color: #333;
  cursor: pointer;
  width: 25%;
}
.quick-icon {
  width: 64px;
  height: 64px;
  margin-bottom: 8px;
  flex-shrink: 0;
}
.quick-label {
  font-size: 11px;
  font-weight: 500;
  text-align: center;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.2;
  width: 100%;
}

.section-tabs-container {
  margin-top: 12px;
  overflow-x: auto;
  overflow-y: hidden;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE and Edge */
}

.section-tabs-container::-webkit-scrollbar {
  display: none; /* Chrome, Safari, Opera */
}

.section-tabs {
  display: flex;
  gap: 12px;
  width: max-content;
  font-weight: 700;
  padding-bottom: 4px;
}
.section-tabs .tab {
  padding: 6px 10px;
  border-radius: 10px;
  background: #f5f7fb;
  color: #666;
  white-space: nowrap;
  flex-shrink: 0;
  cursor: pointer;
}
.section-tabs .tab.active {
  background: #73b100;
  color: #fff;
}

.symbol-list {
  margin-top: 12px;
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
}
.symbol-item {
  display: grid;
  grid-template-columns: 56px 90px 1fr auto;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
  transition: background-color 0.2s;
}
.symbol-item:last-child {
  border-bottom: none;
}
.symbol-item:hover {
  background: #f8f9fa;
}
.symbol-icons {
  position: relative;
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.symbol-icon {
  width: 44px;
  height: 44px;
  border-radius: 8px;
  object-fit: cover;
}
.symbol-ticker {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  white-space: nowrap;
}
.symbol-sparkline {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 2px 0;
}

.sparkline-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 80px;
  height: 24px;
}

.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid #e0e0e0;
  border-top-color: #999;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.symbol-price-group {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}
.symbol-price {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  white-space: nowrap;
  line-height: 1.2;
}
.symbol-change {
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: 2px;
  line-height: 1.2;
}
.change-icon {
  font-size: 12px;
}
.market-closed {
  color: #999;
  font-size: 13px;
  font-weight: 500;
}
.empty-symbols {
  margin-top: 12px;
  text-align: center;
  color: #999;
  font-size: 14px;
  padding: 20px;
}

/* 公告弹窗样式 */
.announcement-modal-overlay {
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

.announcement-modal-content {
  background: #fff;
  border-radius: 16px;
  padding: 24px;
  max-width: 400px;
  width: 100%;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
  animation: modalFadeIn 0.3s ease-out;
}

@keyframes modalFadeIn {
  from {
    opacity: 0;
    transform: scale(0.9);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

.announcement-modal-title {
  font-size: 20px;
  font-weight: 700;
  color: #333;
  text-align: center;
  margin-bottom: 16px;
}

.announcement-modal-body {
  font-size: 14px;
  color: #666;
  line-height: 1.6;
  text-align: left;
  margin-bottom: 24px;
}

.announcement-modal-footer {
  display: flex;
  justify-content: center;
}

.announcement-accept-btn {
  background: #73b100;
  color: #fff;
  border: none;
  border-radius: 12px;
  padding: 12px 32px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
  min-width: 150px;
}

.announcement-accept-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
  opacity: 0.7;
}

.announcement-accept-btn:not(:disabled):hover {
  background: #5a9200;
}

.announcement-accept-btn:not(:disabled):active {
  transform: scale(0.98);
}

</style>

