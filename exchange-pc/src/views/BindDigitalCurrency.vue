<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const auth = useAuthStore()
auth.load()

const localeStore = useLocaleStore()
localeStore.loadLocale()

// 数字货币列表
const digitalCurrencies = [
  { currency: 'BTC', network: 'BTC', name: 'Bitcoin' },
  { currency: 'ETH', network: 'ETH', name: 'Ethereum' },
  { currency: 'USDT', network: 'TRC20', name: 'Tether (TRC20)' },
  { currency: 'USDT', network: 'ERC20', name: 'Tether (ERC20)' },
  { currency: 'USDC', network: 'ERC20', name: 'USD Coin (ERC20)' },
]

// 表单数据
const addressForm = ref({
  currency: '',
  network: '',
  address: '',
})

// 地址列表
const addresses = ref<any[]>([])
const loading = ref(false)
const showCurrencyModal = ref(false)
const editingAddress = ref<any>(null)
const showForm = ref(false) // 控制表单显示/隐藏

// 滑动删除相关
const swipeStates = ref<Record<number, number>>({}) // 记录每个地址的滑动距离
const touchStartX = ref(0)
const touchStartY = ref(0)
const currentSwipeId = ref<number | null>(null) // 当前正在滑动的地址ID

// Toast 提示
const toastMessage = ref('')
const toastType = ref<'success' | 'error' | ''>('')

// 显示提示消息
function showToast(message: string, type: 'success' | 'error' = 'error') {
  toastMessage.value = message
  toastType.value = type
  setTimeout(() => {
    toastMessage.value = ''
    toastType.value = ''
  }, 3000)
}

// 加载地址列表
async function loadAddresses() {
  loading.value = true
  try {
    const res: any = await request.get('/wallet/digital-addresses')
    if (res && res.success !== false) {
      addresses.value = res.list || []
    }
  } catch (e: any) {
    console.error('加载地址失败:', e)
  } finally {
    loading.value = false
  }
}

// 选择货币
function selectCurrency(currency: any) {
  addressForm.value.currency = currency.currency
  addressForm.value.network = currency.network
  showCurrencyModal.value = false
}

// 提交表单
async function submitForm() {
  if (!addressForm.value.currency || !addressForm.value.network || !addressForm.value.address) {
    showToast(localeStore.t('pleaseFillCompleteInfo'), 'error')
    return
  }

  try {
    let res: any
    if (editingAddress.value) {
      res = await request.put(`/wallet/digital-addresses/${editingAddress.value.id}`, addressForm.value)
    } else {
      res = await request.post('/wallet/digital-addresses', addressForm.value)
    }
    
    if (res && res.success !== false) {
      showToast(editingAddress.value ? localeStore.t('updateSuccess') : localeStore.t('addSuccess'), 'success')
      resetForm()
      showForm.value = false // 隐藏表单
      loadAddresses()
    } else {
      showToast(res.message || localeStore.t('operationFailed'), 'error')
    }
  } catch (e: any) {
    console.error('操作失败:', e)
    showToast(e.response?.data?.message || localeStore.t('operationFailed'), 'error')
  }
}

// 编辑地址
function editAddress(address: any) {
  editingAddress.value = address
  addressForm.value = {
    currency: address.currency || '',
    network: address.network || '',
    address: address.address || '',
  }
  showForm.value = true // 显示表单（编辑模式）
}

// 删除地址
async function deleteAddress(id: number) {
  try {
    const res: any = await request.delete(`/wallet/digital-addresses/${id}`)
    if (res && res.success !== false) {
      showToast(localeStore.t('deleteSuccess'), 'success')
      loadAddresses()
    } else {
      showToast(res.message || localeStore.t('deleteFailed'), 'error')
    }
  } catch (e: any) {
    console.error('删除失败:', e)
    showToast(e.response?.data?.message || localeStore.t('deleteFailed'), 'error')
  }
}

// 重置表单
function resetForm() {
  editingAddress.value = null
  addressForm.value = {
    currency: '',
    network: '',
    address: '',
  }
  showForm.value = true // 显示表单（添加模式）
}

// 取消编辑
function cancelEdit() {
  resetForm()
  showForm.value = false
}

// 触摸开始
function handleTouchStart(e: TouchEvent, addressId: number) {
  if (!e.touches || e.touches.length === 0) return
  touchStartX.value = e.touches[0]?.clientX || 0
  touchStartY.value = e.touches[0]?.clientY || 0
  currentSwipeId.value = addressId
  // 关闭其他地址的滑动
  Object.keys(swipeStates.value).forEach(id => {
    if (Number(id) !== addressId) {
      swipeStates.value[Number(id)] = 0
    }
  })
}

// 触摸移动
function handleTouchMove(e: TouchEvent, addressId: number) {
  if (currentSwipeId.value !== addressId) return
  if (!e.touches || e.touches.length === 0) return
  
  const deltaX = (e.touches[0]?.clientX || 0) - touchStartX.value
  const deltaY = Math.abs((e.touches[0]?.clientY || 0) - touchStartY.value)
  
  // 如果垂直滑动大于水平滑动，不处理（允许页面滚动）
  if (deltaY > Math.abs(deltaX)) {
    return
  }
  
  // 只允许向左滑动（负值）
  if (deltaX < 0) {
    const translateX = Math.max(deltaX, -80) // 最大滑动80px
    swipeStates.value[addressId] = translateX
  } else if (deltaX > 0 && (swipeStates.value[addressId] ?? 0) < 0) {
    // 向右滑动时，恢复位置
    const translateX = Math.min(0, (swipeStates.value[addressId] ?? 0) + deltaX)
    swipeStates.value[addressId] = translateX
  }
}

// 触摸结束
function handleTouchEnd(addressId: number) {
  if (currentSwipeId.value !== addressId) return
  
  const currentTranslate = swipeStates.value[addressId] || 0
  // 如果滑动超过40px，自动展开到-80px，否则恢复
  if (currentTranslate < -40) {
    swipeStates.value[addressId] = -80
  } else {
    swipeStates.value[addressId] = 0
  }
  currentSwipeId.value = null
}

// 关闭滑动
function closeSwipe(addressId: number) {
  swipeStates.value[addressId] = 0
}

// 获取显示名称
function getDisplayName(item: any) {
  const currency = digitalCurrencies.find(
    c => c.currency === item.currency && c.network === item.network
  )
  return currency ? `${item.currency} -- ${item.network}` : `${item.currency} -- ${item.network}`
}

onMounted(() => {
  loadAddresses()
})
</script>

<template>
  <div class="bind-digital-currency-page">
    <!-- 顶部导航 -->
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('bindDigitalCurrencyAddress') }}</div>
      <div class="add-button" @click="resetForm()">+</div>
    </div>

    <!-- 地址列表 -->
    <div v-if="addresses.length > 0 && !showForm" class="addresses-list">
      <div 
        v-for="address in addresses" 
        :key="address.id" 
        class="swipe-item-wrapper"
        @touchstart="handleTouchStart($event, address.id)"
        @touchmove="handleTouchMove($event, address.id)"
        @touchend="handleTouchEnd(address.id)"
      >
        <div 
          class="address-item"
          :style="{ transform: `translateX(${swipeStates[address.id] || 0}px)` }"
          @click="closeSwipe(address.id); editAddress(address)"
        >
          <div class="address-header">
            <div class="address-info">
              <div class="address-currency">{{ getDisplayName(address) }}</div>
            </div>
          </div>
          <div class="address-details">
            <div class="address-detail-item">
              <span class="detail-label">{{ localeStore.t('withdrawAddress') }}</span>
              <span class="detail-value">{{ address.address }}</span>
            </div>
          </div>
        </div>
        <div class="delete-button-wrapper">
          <button class="delete-button" @click.stop="deleteAddress(address.id)">{{ localeStore.t('delete') }}</button>
        </div>
      </div>
    </div>

    <!-- 空状态提示 -->
    <div v-if="addresses.length === 0 && !showForm" class="empty-state">
      <div class="empty-text">{{ localeStore.t('noBoundDigitalAddresses') }}</div>
      <button class="empty-button" @click="resetForm()">{{ localeStore.t('addAddress') }}</button>
    </div>

    <!-- 添加/编辑表单 -->
    <div v-if="showForm" class="form-section">
      <div class="form-item" @click="showCurrencyModal = true">
        <div class="form-label">{{ localeStore.t('currency') }}</div>
        <div class="form-input">
          <span>{{ addressForm.currency && addressForm.network ? `${addressForm.currency} -- ${addressForm.network}` : localeStore.t('pleaseSelectCurrency') }}</span>
          <span class="arrow">›</span>
        </div>
      </div>

      <div class="form-item">
        <div class="form-label">{{ localeStore.t('walletAddress') }}</div>
        <input 
          type="text" 
          v-model="addressForm.address" 
          class="form-input-text" 
          :placeholder="localeStore.t('walletAddress')"
        />
      </div>

      <div class="form-actions">
        <button class="cancel-button" @click="cancelEdit">{{ localeStore.t('cancel') }}</button>
        <button class="submit-button" @click="submitForm">
          {{ editingAddress ? localeStore.t('update') : localeStore.t('add') }}
        </button>
      </div>
    </div>

    <!-- 货币选择弹窗 -->
    <div v-if="showCurrencyModal" class="modal-overlay" @click="showCurrencyModal = false">
      <div class="modal-content" @click.stop>
        <div class="modal-header">
          <span class="modal-cancel" @click="showCurrencyModal = false">{{ localeStore.t('cancel') }}</span>
          <span class="modal-title">{{ localeStore.t('selectCurrencyTitle') }}</span>
          <span class="modal-confirm" @click="showCurrencyModal = false">{{ localeStore.t('confirm') }}</span>
        </div>
        <div class="modal-list">
          <div 
            v-for="currency in digitalCurrencies" 
            :key="`${currency.currency}-${currency.network}`"
            class="modal-item"
            :class="{ active: addressForm.currency === currency.currency && addressForm.network === currency.network }"
            @click="selectCurrency(currency)"
          >
            <span>{{ currency.currency }} -- {{ currency.network }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Toast 提示 -->
    <div v-if="toastMessage" class="toast-message" :class="toastType">
      {{ toastMessage }}
    </div>

    <Tabbar />
  </div>
</template>

<style scoped>
.bind-digital-currency-page {
  min-height: 100vh;
  background: #f8f8f8;
  padding-bottom: 80px;
}

/* 顶部导航 */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #f0f0f0;
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

.add-button {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  color: #73b100;
  cursor: pointer;
}

.header-placeholder {
  width: 40px;
}

/* 地址列表 */
.addresses-list {
  padding: 16px;
}

.swipe-item-wrapper {
  position: relative;
  margin-bottom: 12px;
  overflow: hidden;
}

.address-item {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  position: relative;
  z-index: 2;
  transition: transform 0.3s ease;
  touch-action: pan-y;
  cursor: pointer;
}

.delete-button-wrapper {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1;
}

.address-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}


.address-info {
  flex: 1;
}

.address-currency {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.address-details {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}

.address-detail-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  word-break: break-all;
}

.detail-label {
  font-size: 14px;
  color: #999;
  margin-right: 12px;
}

.detail-value {
  font-size: 14px;
  color: #333;
  flex: 1;
  text-align: right;
}

.delete-button {
  width: 100%;
  height: 100%;
  padding: 0 20px;
  background: #ff4444;
  color: #fff;
  border: none;
  border-radius: 0 12px 12px 0;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.delete-button:active {
  opacity: 0.8;
}

/* 表单 */
.form-section {
  padding: 16px;
}

.form-item {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
}

.form-label {
  font-size: 14px;
  color: #666;
  margin-bottom: 8px;
}

.form-input {
  display: flex;
  align-items: center;
  padding: 12px;
  background: #f5f7fb;
  border-radius: 8px;
  font-size: 14px;
  color: #333;
  cursor: pointer;
}

.form-input-text {
  width: 100%;
  padding: 12px;
  background: #f5f7fb;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  color: #333;
  outline: none;
}

.arrow {
  margin-left: auto;
  font-size: 20px;
  color: #999;
}

.submit-button {
  width: 100%;
  padding: 14px;
  background: #73b100;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 12px;
}

.submit-button:active {
  opacity: 0.8;
}

/* 空状态 */
.empty-state {
  padding: 60px 20px;
  text-align: center;
}

.empty-text {
  font-size: 16px;
  color: #999;
  margin-bottom: 24px;
}

.empty-button {
  padding: 12px 32px;
  background: #73b100;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}

.empty-button:active {
  opacity: 0.8;
}

/* 表单操作按钮 */
.form-actions {
  display: flex;
  gap: 12px;
  margin-top: 12px;
}

.cancel-button {
  flex: 1;
  padding: 14px;
  background: #f5f5f5;
  color: #666;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}

.cancel-button:active {
  opacity: 0.8;
}

.submit-button {
  flex: 1;
  padding: 14px;
  background: #73b100;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 0;
}

/* 货币选择弹窗 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: flex-end;
  z-index: 1000;
}

.modal-content {
  width: 100%;
  background: #fff;
  border-radius: 20px 20px 0 0;
  max-height: 70vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
}

.modal-cancel {
  font-size: 16px;
  color: #666;
  cursor: pointer;
}

.modal-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.modal-confirm {
  font-size: 16px;
  color: #73b100;
  cursor: pointer;
}

.modal-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.modal-item {
  display: flex;
  align-items: center;
  padding: 16px 20px;
  cursor: pointer;
  gap: 12px;
}

.modal-item.active {
  background: #f0f7e8;
}

.modal-item span {
  font-size: 16px;
  color: #333;
}

/* Toast 提示消息 */
.toast-message {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 16px 24px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  z-index: 2000;
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
  background: #73b100;
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

