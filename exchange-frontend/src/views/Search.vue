<template>
  <div class="search-page">
    <!-- 顶部搜索栏 -->
    <div class="search-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="search-input-wrapper">
        <svg class="search-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <circle cx="11" cy="11" r="6" stroke="#666" stroke-width="1.6" />
          <line x1="15.5" y1="15.5" x2="20" y2="20" stroke="#666" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        <input
          ref="searchInputRef"
          type="text"
          v-model="searchKeyword"
          class="search-input"
          placeholder="搜索币种/合约"
          @input="handleSearch"
          @focus="isSearching = true"
        />
        <button v-if="searchKeyword" class="clear-button" @click="clearSearch">×</button>
      </div>
    </div>

    <!-- 搜索结果 -->
    <div class="search-content">
      <div v-if="loading" class="loading-state">
        <div class="loading-text">搜索中...</div>
      </div>
      <div v-else-if="searchKeyword && searchResults.length === 0" class="empty-state">
        <div class="empty-text">未找到相关结果</div>
      </div>
      <div v-else-if="searchKeyword && searchResults.length > 0" class="results-section">
        <div class="results-title">搜索結果</div>
        <div class="results-list">
          <div
            v-for="item in searchResults"
            :key="item.id"
            class="result-item"
            @click="goToTrade(item)"
          >
            <div class="result-left">
              <div class="result-symbol">{{ item.symbol }}</div>
              <div class="result-price">{{ formatPrice(getPrice(item)) }}</div>
            </div>
            <div class="result-arrow">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M9 18L15 12L9 6" stroke="#999" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
          </div>
        </div>
      </div>
      <div v-else class="empty-state">
        <div class="empty-text">请输入搜索关键词</div>
      </div>
    </div>

    <Tabbar />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
import { useMarketStore } from '@/store/market'

const router = useRouter()
const marketStore = useMarketStore()

const searchInputRef = ref<HTMLInputElement | null>(null)
const searchKeyword = ref('')
const searchResults = ref<any[]>([])
const loading = ref(false)
const isSearching = ref(false)

let searchTimer: number | null = null

// 格式化价格
function formatPrice(price: number | null | undefined) {
  if (price === null || price === undefined || isNaN(Number(price))) {
    return '0.00'
  }
  const numPrice = Number(price)
  if (isNaN(numPrice)) {
    return '0.00'
  }
  return numPrice.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 5 })
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
  searchTimer = window.setTimeout(() => {
    performSearch(keyword)
  }, 300)
}

// 执行搜索
async function performSearch(keyword: string) {
  if (!keyword.trim()) {
    searchResults.value = []
    return
  }
  
  loading.value = true
  try {
    const res: any = await request.get('/market/search', {
      params: { keyword: keyword.trim() }
    })
    
    if (res && res.list) {
      searchResults.value = res.list
      
      // 订阅搜索结果的实时价格
      searchResults.value.forEach(symbol => {
        const symbolName = symbol.symbol || symbol.alltickSymbol
        const category = symbol.category || 'Crypto'
        try {
          marketStore.subscribeSymbol(symbolName, category)
        } catch (e: any) {
          console.error(`订阅价格失败: ${symbolName}`, e)
        }
      })
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
  searchKeyword.value = ''
  searchResults.value = []
  if (searchInputRef.value) {
    searchInputRef.value.focus()
  }
}

// 跳转到交易页面（默认跳转到期限页面）
function goToTrade(symbol: any) {
  const symbolName = symbol.symbol || symbol.alltickSymbol
  const category = symbol.category || 'Crypto'
  router.push({
    path: '/trade',
    query: {
      symbol: symbolName,
      category: category,
      tab: 'term', // 默认跳转到期限页面
    },
  })
}

onMounted(() => {
  // 自动聚焦搜索输入框
  setTimeout(() => {
    searchInputRef.value?.focus()
  }, 100)
})
</script>

<style scoped>
.search-page {
  min-height: 100vh;
  background: #f8f8f8;
  padding-bottom: 80px;
}

/* 顶部搜索栏 */
.search-header {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #eee;
  gap: 12px;
}

.back-button {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  flex-shrink: 0;
}

.search-input-wrapper {
  flex: 1;
  position: relative;
  display: flex;
  align-items: center;
  background: #f5f7fb;
  border-radius: 20px;
  padding: 8px 16px;
  gap: 8px;
}

.search-icon {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
}

.search-input {
  flex: 1;
  border: none;
  background: transparent;
  font-size: 14px;
  color: #333;
  outline: none;
}

.search-input::placeholder {
  color: #999;
}

.clear-button {
  width: 24px;
  height: 24px;
  border: none;
  background: #ccc;
  color: #fff;
  border-radius: 50%;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.clear-button:active {
  opacity: 0.8;
}

/* 搜索结果内容 */
.search-content {
  padding: 16px;
}

.loading-state,
.empty-state {
  padding: 60px 20px;
  text-align: center;
}

.loading-text,
.empty-text {
  font-size: 14px;
  color: #999;
}

.results-section {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
}

.results-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin-bottom: 16px;
}

.results-list {
  display: flex;
  flex-direction: column;
}

.result-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 0;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
}

.result-item:last-child {
  border-bottom: none;
}

.result-item:active {
  background: #f8f8f8;
  margin: 0 -16px;
  padding-left: 16px;
  padding-right: 16px;
}

.result-left {
  flex: 1;
}

.result-symbol {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin-bottom: 4px;
}

.result-price {
  font-size: 14px;
  color: #666;
}

.result-arrow {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
</style>

