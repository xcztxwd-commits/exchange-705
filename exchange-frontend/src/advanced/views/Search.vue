<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useLocaleStore } from '@/store/locale'
import { useRouter } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useMarketStore } from '@/store/market'
import { displaySymbol } from '@/utils/displaySymbol'
import marketWebSocket from '@/utils/marketWebSocket'

const router = useRouter()
const locale = useLocaleStore()
const marketStore = useMarketStore()

const searchInputRef = ref<HTMLInputElement | null>(null)
const searchKeyword = ref('')
const searchResults = ref<any[]>([])
const loading = ref(false)
const searchOwner = 'advanced-search'
let searchGeneration = 0, disposed = false
const catalogue = ref<any[]>([])
const category = ref('all')
const displayedResults = computed(() => (searchKeyword.value ? searchResults.value : catalogue.value).filter(item => category.value === 'all' || item.category === category.value))
async function loadCatalogue() {
  loading.value = true
  try { const data: any = await request.get('/market/all'); catalogue.value = Array.isArray(data) ? data : data.list || [] }
  catch { catalogue.value = [] }
  finally { loading.value = false }
}

let searchTimer: number | null = null

// 格式化价格
function formatPrice(price: number | null | undefined) {
  if (price === null || price === undefined || isNaN(Number(price))) {
    return '—'
  }
  const numPrice = Number(price)
  if (isNaN(numPrice) || numPrice <= 0) {
    return '—'
  }
  return numPrice.toLocaleString(locale.locale, { minimumFractionDigits: 2, maximumFractionDigits: 5 })
}

// 获取价格（优先使用实时价格，否则使用数据库中的价格）
function getPrice(symbol: any): number {
  const symbolName = symbol.symbol || symbol.alltickSymbol
  const wsPrice = marketStore.getPrice(symbolName)
  if (wsPrice > 0) {
    return wsPrice
  }
  return Number(symbol.currentPrice || 0)
}

// 搜索处理
function handleSearch() {
  // 清除之前的定时器
  if (searchTimer) {
    clearTimeout(searchTimer)
  }
  
  const keyword = searchKeyword.value.trim()
  
  if (!keyword) {
    searchResults.value = []
    return
  }
  
  // 防抖：延迟500ms后执行搜索
  searchTimer = setTimeout(() => {
    performSearch(keyword)
  }, 300)
}

// 执行搜索
async function performSearch(keyword: string) {
  if (!keyword.trim()) {
    searchResults.value = []
    return
  }
  
  const run = ++searchGeneration
  loading.value = true
  try {
    const res: any = await request.get('/market/search', {
      params: { keyword: keyword.trim() }
    })
    
    if (run !== searchGeneration || keyword !== searchKeyword.value.trim()) return
    if (res && res.list) {
      searchResults.value = res.list
      
      await marketStore.subscribeSymbols(searchResults.value, searchOwner)
      if (disposed) marketWebSocket.release(searchOwner)
    } else {
      searchResults.value = []
    }
  } catch (e: any) {
    console.error('搜索失败:', e)
    searchResults.value = []
  } finally {
    loading.value = false
  }
}

// 清空搜索
function clearSearch() {
  searchGeneration++
  window.clearTimeout(searchTimer ?? undefined)
  marketWebSocket.release(searchOwner)
  searchKeyword.value = ''
  searchResults.value = []
  if (searchInputRef.value) {
    searchInputRef.value.focus()
  }
}

// 跳转到交易页面（默认跳转到合约页面）
function goToTrade(symbol: any) {
  const symbolName = symbol.symbol || symbol.alltickSymbol
  const category = symbol.category || 'Crypto'
  router.push({
    path: '/trade',
    query: {
      symbol: symbolName,
      category: category,
    },
  })
}

onUnmounted(() => { disposed = true; searchGeneration++; marketWebSocket.release(searchOwner) })
onMounted(() => {
  void loadCatalogue()
  // 自动聚焦搜索输入框
  setTimeout(() => {
    searchInputRef.value?.focus()
  }, 100)
})
</script>

<template>
<BusinessPage :title="locale.text('搜索行情','Search markets')" :error="advancedError" :busy="advancedWriting || loading"><label class="field">{{ locale.text('搜索品种','Search markets') }}<div class="input-row"><input ref="searchInputRef" v-model="searchKeyword" type="search" :placeholder="locale.text('搜索名称 / 代码','Search name / symbol')" @input="handleSearch" /><button v-if="searchKeyword" class="text-button" @click="clearSearch">{{ locale.text('清除','Clear') }}</button></div></label><div class="tabs"><button v-for="tab in [{value:'all',zh:'全部',en:'All'},{value:'Crypto',zh:'加密货币',en:'Crypto'},{value:'Forex',zh:'外汇',en:'Forex'},{value:'Indices',zh:'指数',en:'Indices'}]" :key="tab.value" :class="{active:category===tab.value}" @click="category=tab.value">{{ locale.text(tab.zh,tab.en) }}</button></div><div class="market-heading"><h2>{{ locale.text('行情列表','Markets') }}</h2><span class="muted">{{ locale.text('最新价','Last price') }}</span></div><p v-if="loading" class="empty">{{ locale.text('加载中…','Loading…') }}</p><template v-else><button v-for="item in displayedResults" :key="item.id || item.symbol" class="row market-result" @click="goToTrade(item)"><span>{{ displaySymbol(item) }}</span><span>{{ formatPrice(getPrice(item)) }}</span></button><p v-if="!displayedResults.length" class="empty">{{ advancedError ? locale.text('行情暂不可用','Market data unavailable') : locale.text('没有相关结果','No matching results') }}</p></template><p class="notice">{{ locale.text('按当前行情目录搜索 · 未获取报价时显示 —','Search the current catalogue · Unavailable quotes show —') }}</p><button v-if="advancedError" @click="searchKeyword ? performSearch(searchKeyword) : loadCatalogue()">{{ locale.text('重试','Retry') }}</button></BusinessPage>
</template>
<style scoped>

.page-content,.cards-list,.addresses-list,.records-list,.yield-list,.product-list,.orders-list{display:flex;flex-direction:column;gap:20px}
.form-group,.form-item,.form-section{display:flex;flex-direction:column;gap:8px}
.form-label,.detail-label,.info-label,.stat-label{font-size:12px;color:#707780}
.form-actions,.order-actions,.dialog-footer,.amount-input-wrapper,.password-input-wrapper,.code-input-wrapper{display:flex;gap:8px}.form-actions>*,.order-actions>*{flex:1}
.record-row,.info-item,.info-row,.detail-item,.yield-header{display:flex;justify-content:space-between;gap:8px;padding:12px 0;overflow-wrap:anywhere}
.submit-btn,.submit-button,.confirm-btn,.purchase-btn{background:#d9e6c8!important;color:#2f4129!important;min-height:48px}
.contract-wrapper,.contract-box{display:flex;flex-direction:column;gap:20px}.contract-section{display:flex;flex-direction:column;gap:12px}.section-title{font-size:17px;font-weight:500}.section-text,.section-note{overflow-wrap:anywhere}.signature-image img{max-width:100%}
.card-item,.address-item{position:relative;z-index:1;border:1px solid #e9edef;border-radius:10px;padding:12px;background:white;overflow-wrap:anywhere}.swipe-item-wrapper{position:relative;overflow:hidden}.delete-button-wrapper{position:absolute;right:0;top:0;height:100%;display:flex;align-items:center}.delete-button{color:#9a3939}
.upload-area,.upload-box{background:#f5f6f7;border:1px solid #e9edef;border-radius:10px;padding:16px;min-height:84px}.upload-area img,.upload-box img,.id-image img{max-width:100%;object-fit:contain}
.empty-state,.loading-state{padding:28px 0;color:#707780;text-align:center}.status-badge,.status-text{color:#736582}.signature-canvas{width:100%;height:180px;touch-action:none;background:#f5f6f7}.image-preview img{max-width:100%;max-height:180px;object-fit:contain}

.results-list{display:flex;flex-direction:column;gap:12px}.result-item{display:flex;align-items:center;justify-content:space-between;padding:16px 0;border-bottom:1px solid #e9edef;cursor:pointer}.result-left{display:flex;justify-content:space-between;gap:16px;flex:1}.result-price{font-variant-numeric:tabular-nums}.results-title{font-weight:500;font-size:17px}.result-arrow{margin-left:12px}
.market-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.market-result{width:100%;margin:0}.market-result span:last-child{text-align:right}
</style>
