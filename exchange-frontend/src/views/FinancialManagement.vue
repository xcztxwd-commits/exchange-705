<template>
  <div class="financial-management-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('financialManagement') }}</div>
      <div class="header-menu" @click="router.push('/financial/orders')">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M3 12H21M3 6H21M3 18H21" stroke="#333" stroke-width="2" stroke-linecap="round"/>
        </svg>
      </div>
    </div>

    <div class="page-content" v-if="!loading">
      <div v-if="products.length === 0" class="empty-state">
        <div class="empty-text">{{ localeStore.t('noFinancialProducts') }}</div>
      </div>
      <div v-else class="product-list">
        <div
          v-for="product in products"
          :key="product.id"
          class="product-card"
          @click="goToPurchase(product.id)"
        >
          <div class="product-header">
            <div class="product-name">{{ product.name }}</div>
            <div class="product-image" v-if="product.imageUrl">
              <img :src="getImageUrl(product.imageUrl)" :alt="localeStore.t('productImage')" @error="handleImageError" />
            </div>
          </div>
          <div class="product-info">
            <div class="info-row">
              <span class="info-currency">{{ product.currency }}</span>
              <div class="info-details">
                <div class="detail-item">
                  <span class="detail-label">{{ localeStore.t('expectedDailyYield') }}:</span>
                  <span class="detail-value">{{ formatPercent(product.dailyYieldRate) }}%</span>
                </div>
                <div class="detail-item">
                  <span class="detail-label">{{ localeStore.t('miningMachineRental') }}</span>
                  <span class="detail-value">{{ formatMoney(product.rentalFee) }}</span>
                </div>
              </div>
            </div>
          </div>
          <button class="purchase-btn" @click.stop="goToPurchase(product.id)">{{ localeStore.t('purchase') }}</button>
        </div>
      </div>
    </div>

    <Tabbar />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/utils/request'
import Tabbar from '@/components/Tabbar.vue'
import { getImageUrl } from '@/utils/imageUrl'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const products = ref<any[]>([])
const loading = ref(false)

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 格式化金额
function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 格式化百分比
function formatPercent(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toFixed(2)
}

// getImageUrl 函数已从 @/utils/imageUrl 导入

// 处理图片加载错误
function handleImageError(e: Event) {
  const img = e.target as HTMLImageElement
  img.style.display = 'none'
}

// 跳转到申购页面
function goToPurchase(productId: number) {
  router.push({
    path: '/financial/purchase',
    query: { id: productId.toString() }
  })
}

// 加载产品列表
async function loadProducts() {
  loading.value = true
  try {
    const res: any = await request.get('/financial/products')
    if (res && res.success && res.list) {
      products.value = res.list
    }
  } catch (e: any) {
    console.error('加载产品列表失败:', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadProducts()
})
</script>

<style scoped>
.financial-management-page {
  min-height: 100vh;
  background: #f8f8f8;
  padding-bottom: 80px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  background: #fff;
  border-bottom: 1px solid #eee;
}

.back-button {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.header-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.header-placeholder {
  width: 40px;
}

.page-content {
  padding: 40px 20px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
}

.empty-icon {
  margin-bottom: 24px;
  opacity: 0.5;
}

.empty-text {
  font-size: 16px;
  color: #999;
  text-align: center;
}

.header-menu {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.product-list {
  padding: 16px;
}

.product-card {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
  cursor: pointer;
}

.product-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.product-name {
  font-size: 20px;
  font-weight: 600;
  color: #333;
}

.product-image {
  width: 50px;
  height: 50px;
  border-radius: 8px;
  overflow: hidden;
}

.product-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.product-info {
  margin-bottom: 16px;
}

.info-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.info-currency {
  font-size: 14px;
  color: #666;
}

.info-details {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.detail-item {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
}

.detail-label {
  color: #666;
}

.detail-value {
  color: #333;
  font-weight: 500;
}

.purchase-btn {
  width: 100%;
  padding: 12px;
  background: #2abf4b;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}
</style>

