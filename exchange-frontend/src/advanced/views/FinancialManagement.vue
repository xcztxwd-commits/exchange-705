<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting } = useBusinessLifecycle()
import ProtectedImage from '@/components/ProtectedImage.vue'
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
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

<template>
<BusinessPage :title="localeStore.t('financialManagement')" :error="advancedError" :busy="advancedWriting">
<div class="page-content" v-if="!loading">
      <div v-if="products.length === 0" class="empty-state">
        <div class="empty-text">{{ localeStore.t('noFinancialProducts') }}</div>
      </div>
      <div v-else class="product-list">
        <div
          v-for="product in products"
          :key="product.id"
          class="product-card card"
          @click="goToPurchase(product.id)"
        >
          <div class="product-header">
            <div class="product-name">{{ product.name }}</div>
            <div class="product-image" v-if="product.imageUrl">
              <ProtectedImage :src="getImageUrl(product.imageUrl)" :alt="localeStore.t('productImage')" @error="handleImageError" />
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
</BusinessPage>
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

</style>
