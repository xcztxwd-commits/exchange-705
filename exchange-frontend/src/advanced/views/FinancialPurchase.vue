<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { canStartBusiness } from '@/utils/tenantFeatures'
import ProtectedImage from '@/components/ProtectedImage.vue'
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { getImageUrl } from '@/utils/imageUrl'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const route = useRoute()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

const product = ref<any>(null)
const purchaseAmount = ref<string>('')
const loading = ref(false)
const showConfirmDialog = ref(false)
const confirmData = ref<any>(null)

// Toast提示
const toastMessage = ref('')
const toastType = ref<'success' | 'error' | ''>('')

function showToast(message: string, type: 'success' | 'error' = 'error') {
  toastMessage.value = message
  toastType.value = type
  setTimeout(() => {
    toastMessage.value = ''
    toastType.value = ''
  }, 3000)
}

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

// 加载产品详情
async function loadProduct() {
  const productId = route.query.id
  if (!productId) {
    showToast(localeStore.t('productIdNotExists'), 'error')
    router.back()
    return
  }

  loading.value = true
  try {
    const res: any = await request.get(`/financial/product/${productId}`)
    if (res && res.success && res.data) {
      product.value = res.data
    } else {
      showToast(localeStore.t('loadProductInfoFailed'), 'error')
      router.back()
    }
  } catch (e: any) {
    showToast(e.message || localeStore.t('loadFailed'), 'error')
    router.back()
  } finally {
    loading.value = false
  }
}

// 设置最大金额
function setMaxAmount() {
  if (product.value) {
    purchaseAmount.value = formatMoney(product.value.maxPurchase)
  }
}

// 显示确认对话框
function showConfirm() {
  if (!product.value) return
  
  const amount = Number(purchaseAmount.value.replace(/,/g, ''))
  if (!amount || amount <= 0) {
    showToast(localeStore.t('enterValidPurchaseAmount'), 'error')
    return
  }

  if (amount < product.value.minPurchase) {
    showToast(localeStore.text('', 'The subscription amount must be at least {amount}.', { amount: formatMoney(product.value.minPurchase) }), 'error')
    return
  }

  if (amount > product.value.maxPurchase) {
    showToast(localeStore.text('', 'The subscription amount must not exceed {amount}.', { amount: formatMoney(product.value.maxPurchase) }), 'error')
    return
  }

  // 计算收益
  const dailyYield = amount * (Number(product.value.dailyYieldRate) / 100)
  const totalYield = dailyYield * product.value.termDays

  confirmData.value = {
    productName: product.value.name,
    rentalFee: product.value.rentalFee,
    termDays: product.value.termDays,
    dailyYield: dailyYield,
    totalYield: totalYield,
    purchaseAmount: amount
  }
  showConfirmDialog.value = true
}

// 确认申购
async function confirmPurchase() {
  if (!product.value || !confirmData.value) return

  try {
    const amount = Number(purchaseAmount.value.replace(/,/g, ''))
    const res: any = await request.post('/financial/purchase', {
      productId: product.value.id,
      purchaseAmount: amount
    })

    if (res && res.success) {
      showToast(localeStore.t('purchaseSuccess'), 'success')
      setTimeout(() => {
        router.push('/financial/orders')
      }, 1500)
    } else {
      showToast(res.message || localeStore.t('purchaseFailed'), 'error')
    }
  } catch (e: any) {
    showToast(e.response?.data?.message || e.message || localeStore.t('purchaseFailed'), 'error')
  } finally {
    showConfirmDialog.value = false
  }
}

// getImageUrl 函数已从 @/utils/imageUrl 导入

// 处理图片加载错误
function handleImageError(e: Event) {
  const img = e.target as HTMLImageElement
  img.style.display = 'none'
}

onMounted(() => {
  loadProduct()
})
</script>

<template>
<BusinessPage :title="localeStore.t('purchase')" :error="advancedError" :busy="advancedWriting">
<div class="page-content" v-if="product && !loading">
      <!-- 产品信息 -->
      <div class="product-info">
        <div class="product-image" v-if="product.imageUrl">
          <ProtectedImage :src="getImageUrl(product.imageUrl)" :alt="localeStore.t('productImage')" @error="handleImageError" />
        </div>
        <div class="product-name">{{ product.name }}</div>
        <div class="product-currency">{{ product.currency }}</div>
        <div class="product-yield">
          <span class="yield-label">{{ localeStore.t('expectedDailyYield') }}:</span>
          <span class="yield-value">{{ formatPercent(product.dailyYieldRate) }}%</span>
        </div>
      </div>

      <!-- 申购详情 -->
      <div class="purchase-details">
        <div class="detail-item">
          <span class="detail-label">{{ localeStore.t('miningMachineRental') }}</span>
          <span class="detail-value">{{ formatMoney(product.rentalFee) }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">{{ localeStore.t('financialTerm') }}</span>
          <span class="detail-value">{{ product.termDays }}{{ localeStore.t('daysUnit') }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">{{ localeStore.t('maxPurchase') }}</span>
          <span class="detail-value">{{ formatMoney(product.maxPurchase) }}</span>
        </div>
      </div>

      <!-- 申购输入 -->
      <div class="purchase-input-section">
        <input
          v-model="purchaseAmount"
          type="text"
          class="purchase-input"
          :placeholder="localeStore.t('enterLockAmount')"
          @input="purchaseAmount = purchaseAmount.replace(/[^\d.]/g, '')"
        />
        <button class="max-btn" @click="setMaxAmount">{{ localeStore.t('max') }}</button>
      </div>

      <!-- 申购按钮 -->
      <button v-if="canStartBusiness('financial')" class="purchase-btn" @click="showConfirm">{{ localeStore.t('purchase') }}</button>

      <!-- 产品介绍 -->
      <div class="product-description">
        <div class="description-title">{{ localeStore.t('productDescription') }}</div>
        <div class="description-content">
          {{ product.description || localeStore.t('noData') }}
        </div>
      </div>
    </div>

    

    <!-- 确认对话框 -->
    <div v-if="showConfirmDialog" class="confirm-dialog-overlay" @click.self="showConfirmDialog = false">
      <div class="confirm-dialog">
        <div class="dialog-header">
          <span class="dialog-title">{{ confirmData?.productName }}</span>
          <div class="dialog-close" @click="showConfirmDialog = false">×</div>
        </div>
        <div class="dialog-content">
          <div class="confirm-item">
            <span class="confirm-label">{{ localeStore.t('miningMachineRentalFee') }}</span>
            <span class="confirm-value">{{ formatMoney(confirmData?.rentalFee) }}</span>
          </div>
          <div class="confirm-item">
            <span class="confirm-label">{{ localeStore.t('financialTerm') }}</span>
            <span class="confirm-value">{{ confirmData?.termDays }}{{ localeStore.t('daysUnit') }}</span>
          </div>
          <div class="confirm-item">
            <span class="confirm-label">{{ localeStore.t('estDailyYield') }}</span>
            <span class="confirm-value">{{ formatMoney(confirmData?.dailyYield) }}</span>
          </div>
          <div class="confirm-item">
            <span class="confirm-label">{{ localeStore.t('expectedTotalYieldLabel') }}</span>
            <span class="confirm-value">{{ formatMoney(confirmData?.totalYield) }}</span>
          </div>
          <div class="confirm-item">
            <span class="confirm-label">{{ localeStore.t('purchaseAmount') }}</span>
            <span class="confirm-value">{{ formatMoney(confirmData?.purchaseAmount) }}</span>
          </div>
        </div>
        <div class="dialog-footer">
          <button v-if="canStartBusiness('financial')" class="confirm-btn" @click="confirmPurchase">{{ localeStore.t('purchase') }}</button>
        </div>
      </div>
    </div>

    <!-- Toast提示 -->
    <div v-if="toastMessage" :class="['toast-message', toastType]">
      {{ toastMessage }}
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
