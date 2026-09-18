<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import request from '@/utils/request'
import Tabbar from '@/components/Tabbar.vue'
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
    showToast(`${localeStore.t('purchaseAmountCannotBeLessThan')}${formatMoney(product.value.minPurchase)}`, 'error')
    return
  }

  if (amount > product.value.maxPurchase) {
    showToast(`${localeStore.t('purchaseAmountCannotBeGreaterThan')}${formatMoney(product.value.maxPurchase)}`, 'error')
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
  <div class="financial-purchase-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('purchase') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="page-content" v-if="product && !loading">
      <!-- 产品信息 -->
      <div class="product-info">
        <div class="product-image" v-if="product.imageUrl">
          <img :src="getImageUrl(product.imageUrl)" :alt="localeStore.t('productImage')" @error="handleImageError" />
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
          <span class="detail-value">{{ product.termDays }}</span>
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
      <button class="purchase-btn" @click="showConfirm">{{ localeStore.t('purchase') }}</button>

      <!-- 产品介绍 -->
      <div class="product-description">
        <div class="description-title">{{ localeStore.t('productDescription') }}</div>
        <div class="description-content">
          {{ product.description || 'The cloud mining machine is hosted by USD to the super-computing power mining machine of the platform, and the mining revenue from the Al super-intelligent platform mining pool is carried out. Super Al intelligent mining, the daily rate of return is ' + formatPercent(product.dailyYieldRate) + '%. One-time limit is ' + formatMoney(product.minPurchase) + ' to ' + formatMoney(product.maxPurchase) + '.' }}
        </div>
      </div>
    </div>

    <Tabbar />

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
            <span class="confirm-value">{{ confirmData?.termDays }}</span>
          </div>
          <div class="confirm-item">
            <span class="confirm-label">{{ localeStore.t('expectedDailyYield') }}</span>
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
          <button class="confirm-btn" @click="confirmPurchase">{{ localeStore.t('purchase') }}</button>
        </div>
      </div>
    </div>

    <!-- Toast提示 -->
    <div v-if="toastMessage" :class="['toast-message', toastType]">
      {{ toastMessage }}
    </div>
  </div>
</template>

<style scoped>
.financial-purchase-page {
  min-height: 100vh;
  background: #fff;
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
  padding: 20px 16px;
}

.product-info {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.product-image {
  width: 60px;
  height: 60px;
  border-radius: 8px;
  overflow: hidden;
}

.product-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.product-name {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.product-currency {
  font-size: 14px;
  color: #666;
  margin-top: 4px;
}

.product-yield {
  margin-left: auto;
  padding: 6px 12px;
  background: #2abf4b;
  border-radius: 20px;
  color: #fff;
  font-size: 12px;
}

.yield-label {
  margin-right: 4px;
}

.purchase-details {
  margin-bottom: 24px;
}

.detail-item {
  display: flex;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px solid #eee;
}

.detail-label {
  font-size: 14px;
  color: #666;
}

.detail-value {
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.purchase-input-section {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
}

.purchase-input {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 16px;
}

.max-btn {
  padding: 12px 20px;
  background: #f5f5f5;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 14px;
  color: #333;
  cursor: pointer;
}

.purchase-btn {
  width: 100%;
  padding: 16px;
  background: #2abf4b;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  margin-bottom: 24px;
}

.product-description {
  margin-top: 24px;
  padding-top: 24px;
  border-top: 1px solid #eee;
}

.description-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin-bottom: 12px;
}

.description-content {
  font-size: 14px;
  color: #666;
  line-height: 1.6;
}

/* 确认对话框 */
.confirm-dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: flex-end;
  z-index: 2000;
}

.confirm-dialog {
  width: 100%;
  background: #fff;
  border-radius: 20px 20px 0 0;
  padding: 20px;
  max-height: 80vh;
  overflow-y: auto;
}

.dialog-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.dialog-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.dialog-close {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  color: #999;
  cursor: pointer;
}

.dialog-content {
  margin-bottom: 20px;
}

.confirm-item {
  display: flex;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px solid #eee;
}

.confirm-label {
  font-size: 14px;
  color: #666;
}

.confirm-value {
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.dialog-footer {
  margin-top: 20px;
}

.confirm-btn {
  width: 100%;
  padding: 16px;
  background: #2abf4b;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}

/* Toast提示 */
.toast-message {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 16px 24px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  z-index: 3000;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  animation: toastSlideIn 0.3s ease-out;
  max-width: 80%;
  text-align: center;
  word-wrap: break-word;
}

.toast-message.error {
  background: #ff4444;
  color: #fff;
}

.toast-message.success {
  background: #2abf4b;
  color: #fff;
}

@keyframes toastSlideIn {
  from {
    opacity: 0;
    transform: translate(-50%, -60%);
  }
  to {
    opacity: 1;
    transform: translate(-50%, -50%);
  }
}
</style>

