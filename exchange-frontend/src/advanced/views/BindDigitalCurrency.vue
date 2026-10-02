<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { ref, onMounted } from 'vue'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

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
const showForm = ref(true) // 控制表单显示/隐藏

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
<BusinessPage :title="localeStore.t('bindDigitalCurrencyAddress')" :error="advancedError" :busy="advancedWriting">
<div class="row"><h2>{{ localeStore.text('已绑定地址','Saved addresses') }}</h2><button class="text-button" :disabled="advancedWriting" @click="resetForm">{{ localeStore.t('add') }} +</button></div>
    

    <!-- 地址列表 -->
    <div v-if="addresses.length > 0" class="addresses-list">
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
    <div v-if="showForm" class="form-section card"><h2>{{ localeStore.text('收款地址信息','Address details') }}</h2>
      <div class="form-item" @click="showCurrencyModal = true">
        <div class="form-label">{{ localeStore.t('currency') }}</div>
        <button class="form-input" type="button">
          <span>{{ addressForm.currency && addressForm.network ? `${addressForm.currency} -- ${addressForm.network}` : localeStore.t('pleaseSelectCurrency') }}</span>
          <span class="arrow ui-chevron" aria-hidden="true"></span>
        </button>
      </div>

      <label class="field">{{ localeStore.t('network') }}<button class="form-input" @click="showCurrencyModal=true">{{ addressForm.network || localeStore.text('选择网络','Select network') }} ⌄</button></label><div class="form-item">
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
        <button class="submit-button" :disabled="advancedWriting || loading || !!advancedError" @click="submitForm">
          {{ editingAddress ? localeStore.t('update') : localeStore.t('add') }}
        </button>
      </div>
    </div>

    <p class="notice">{{ localeStore.text('网络不一致可能导致无法到账','The receiving network must match the sending network') }}</p><button v-if="advancedError" @click="loadAddresses">{{ localeStore.text('重试','Retry') }}</button>
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

.card-info,.card-details,.address-info{display:flex;justify-content:space-between;gap:12px}.card-item,.address-item{display:flex;flex-direction:column;gap:12px}.card-detail-item{display:flex;flex-direction:column;gap:5px}.address-currency{font-size:17px;font-weight:500}.address-detail-item{display:flex;flex-direction:column;gap:8px}.form-input{display:flex;align-items:center;justify-content:space-between;border:1px solid #e9edef!important;text-align:left!important;color:#707780!important;min-height:50px!important}.form-section.card{gap:12px}.form-actions{margin-top:8px}
</style>
