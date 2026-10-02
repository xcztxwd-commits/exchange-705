<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { computed, ref, onMounted } from 'vue'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

const auth = useAuthStore()
auth.load()

const localeStore = useLocaleStore()
localeStore.loadLocale()

// 货币列表
const currencies = computed(() => {
  const names = new Intl.DisplayNames([localeStore.locale], { type: 'currency' })
  return ['USD', 'EUR', 'GBP', 'JPY', 'CNY', 'KRW', 'SGD', 'AUD', 'CHF', 'IDR', 'MYR', 'TWD', 'VND']
    .map(code => ({ code, name: names.of(code) || code }))
})

// 表单数据
const formData = ref({
  currency: '',
  bankName: '',
  bankAddress: '',
  swift: '',
  recipientName: '',
  recipientAccount: '',
})

// 银行卡列表
const bankCards = ref<any[]>([])
const loading = ref(false)
const showCurrencyModal = ref(false)
const editingCard = ref<any>(null)
const showForm = ref(true) // 控制表单显示/隐藏

// 滑动删除相关
const swipeStates = ref<Record<number, number>>({}) // 记录每个卡片的滑动距离
const touchStartX = ref(0)
const touchStartY = ref(0)
const currentSwipeId = ref<number | null>(null) // 当前正在滑动的卡片ID

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

// 加载银行卡列表
async function loadBankCards() {
  loading.value = true
  try {
    const res: any = await request.get('/wallet/bank-cards')
    if (res && res.success !== false) {
      bankCards.value = res.list || []
    }
  } catch (e: any) {
    console.error('加载银行卡失败:', e)
  } finally {
    loading.value = false
  }
}

// 选择货币
function selectCurrency(currency: any) {
  formData.value.currency = currency.code
  showCurrencyModal.value = false
}

// 提交表单
async function submitForm() {
  if (!formData.value.currency || !formData.value.bankName || 
      !formData.value.recipientName || !formData.value.recipientAccount) {
    showToast(localeStore.t('pleaseFillCompleteInfo'), 'error')
    return
  }

  try {
    let res: any
    if (editingCard.value) {
      res = await request.put(`/wallet/bank-cards/${editingCard.value.id}`, formData.value)
    } else {
      res = await request.post('/wallet/bank-cards', formData.value)
    }
    
    if (res && res.success !== false) {
      showToast(editingCard.value ? localeStore.t('updateSuccess') : localeStore.t('addSuccess'), 'success')
      resetForm()
      showForm.value = false // 隐藏表单
      loadBankCards()
    } else {
      showToast(res.message || localeStore.t('operationFailed'), 'error')
    }
  } catch (e: any) {
    console.error('操作失败:', e)
    showToast(e.response?.data?.message || localeStore.t('operationFailed'), 'error')
  }
}

// 编辑银行卡
function editCard(card: any) {
  editingCard.value = card
  formData.value = {
    currency: card.currency || '',
    bankName: card.bankName || '',
    bankAddress: card.bankAddress || '',
    swift: card.swift || '',
    recipientName: card.recipientName || '',
    recipientAccount: card.recipientAccount || '',
  }
  showForm.value = true // 显示表单（编辑模式）
}

// 删除银行卡
async function deleteCard(id: number) {
  try {
    const res: any = await request.delete(`/wallet/bank-cards/${id}`)
    if (res && res.success !== false) {
      showToast(localeStore.t('deleteSuccess'), 'success')
      loadBankCards()
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
  editingCard.value = null
  formData.value = {
    currency: '',
    bankName: '',
    bankAddress: '',
    swift: '',
    recipientName: '',
    recipientAccount: '',
  }
  showForm.value = true // 显示表单（添加模式）
}

// 取消编辑
function cancelEdit() {
  resetForm()
  showForm.value = false
}

// 触摸开始
function handleTouchStart(e: TouchEvent, cardId: number) {
  if (!e.touches || !e.touches[0]) return
  touchStartX.value = e.touches[0].clientX
  touchStartY.value = e.touches[0].clientY
  currentSwipeId.value = cardId
  // 关闭其他卡片的滑动
  Object.keys(swipeStates.value).forEach(id => {
    if (Number(id) !== cardId) {
      swipeStates.value[Number(id)] = 0
    }
  })
}

// 触摸移动
function handleTouchMove(e: TouchEvent, cardId: number) {
  if (currentSwipeId.value !== cardId || !e.touches || !e.touches[0]) return
  
  const deltaX = e.touches[0].clientX - touchStartX.value
  const deltaY = Math.abs(e.touches[0].clientY - touchStartY.value)
  
  // 如果垂直滑动大于水平滑动，不处理（允许页面滚动）
  if (deltaY > Math.abs(deltaX)) {
    return
  }
  
  // 只允许向左滑动（负值）
  if (deltaX < 0) {
    const translateX = Math.max(deltaX, -80) // 最大滑动80px
    swipeStates.value[cardId] = translateX
  } else if (deltaX > 0 && (swipeStates.value[cardId] || 0) < 0) {
    // 向右滑动时，恢复位置
    const translateX = Math.min(0, (swipeStates.value[cardId] || 0) + deltaX)
    swipeStates.value[cardId] = translateX
  }
}

// 触摸结束
function handleTouchEnd(cardId: number) {
  if (currentSwipeId.value !== cardId) return
  
  const currentTranslate = swipeStates.value[cardId] || 0
  // 如果滑动超过40px，自动展开到-80px，否则恢复
  if (currentTranslate < -40) {
    swipeStates.value[cardId] = -80
  } else {
    swipeStates.value[cardId] = 0
  }
  currentSwipeId.value = null
}

// 关闭滑动
function closeSwipe(cardId: number) {
  swipeStates.value[cardId] = 0
}

onMounted(() => {
  loadBankCards()
})
</script>

<template>
<BusinessPage :title="localeStore.t('bindBankCard')" :error="advancedError" :busy="advancedWriting">
<div class="row"><h2>{{ localeStore.text('已绑定银行卡','Saved bank accounts') }}</h2><button class="text-button" :disabled="advancedWriting" @click="resetForm">{{ localeStore.t('add') }} +</button></div>
    

    <!-- 银行卡列表 -->
    <div v-if="bankCards.length > 0" class="cards-list">
      <div 
        v-for="card in bankCards" 
        :key="card.id" 
        class="swipe-item-wrapper"
        @touchstart="handleTouchStart($event, card.id)"
        @touchmove="handleTouchMove($event, card.id)"
        @touchend="handleTouchEnd(card.id)"
      >
        <div 
          class="card-item"
          :style="{ transform: `translateX(${swipeStates[card.id] || 0}px)` }"
          @click="closeSwipe(card.id); editCard(card)"
        >
          <div class="card-header">
            <div class="card-info">
              <div class="card-currency">{{ card.currency }}</div>
              <div class="card-bank">{{ card.bankName }}</div>
            </div>
          </div>
          <div class="card-details">
            <div class="card-detail-item">
              <span class="detail-label">{{ localeStore.t('recipientName') }}</span>
              <span class="detail-value">{{ card.recipientName }}</span>
            </div>
            <div class="card-detail-item">
              <span class="detail-label">{{ localeStore.t('recipientAccount') }}</span>
              <span class="detail-value">{{ card.recipientAccount }}</span>
            </div>
          </div>
        </div>
        <div class="delete-button-wrapper">
          <button class="delete-button" @click.stop="deleteCard(card.id)">{{ localeStore.t('delete') }}</button>
        </div>
      </div>
    </div>

    <!-- 空状态提示 -->
    <div v-if="bankCards.length === 0 && !showForm" class="empty-state">
      <div class="empty-text">{{ localeStore.t('noBoundBankCards') }}</div>
      <button class="empty-button" @click="resetForm()">{{ localeStore.t('addBankCard') }}</button>
    </div>

    <!-- 添加/编辑表单 -->
    <div v-if="showForm" class="form-section card"><h2>{{ localeStore.text('银行卡信息','Bank details') }}</h2>
      <div class="form-item" @click="showCurrencyModal = true">
        <div class="form-label">{{ localeStore.t('currency') }}</div>
        <button class="form-input" type="button">
          <span>{{ formData.currency || localeStore.t('pleaseSelectCurrency') }}</span>
          <span class="arrow ui-chevron" aria-hidden="true"></span>
        </button>
      </div>

      <div class="form-item">
        <div class="form-label">{{ localeStore.t('bankName') }}</div>
        <input 
          type="text" 
          v-model="formData.bankName" 
          class="form-input-text" 
          :placeholder="localeStore.t('bankName')"
        />
      </div>

      <div class="form-item">
        <div class="form-label">{{ localeStore.t('bankAddress') }}</div>
        <input 
          type="text" 
          v-model="formData.bankAddress" 
          class="form-input-text" 
          :placeholder="localeStore.t('bankAddress')"
        />
      </div>

      <div class="form-item">
        <div class="form-label">{{ localeStore.t('swift') }}</div>
        <input 
          type="text" 
          v-model="formData.swift" 
          class="form-input-text" 
          :placeholder="localeStore.t('swift')"
        />
      </div>

      <div class="form-item">
        <div class="form-label">{{ localeStore.t('recipientName') }}</div>
        <input 
          type="text" 
          v-model="formData.recipientName" 
          class="form-input-text" 
          :placeholder="localeStore.t('recipientName')"
        />
      </div>

      <div class="form-item">
        <div class="form-label">{{ localeStore.t('recipientAccount') }}</div>
        <input 
          type="text" 
          v-model="formData.recipientAccount" 
          class="form-input-text" 
          :placeholder="localeStore.t('recipientAccount')"
        />
      </div>

      <div class="form-actions">
        <button class="cancel-button" @click="cancelEdit">{{ localeStore.t('cancel') }}</button>
        <button class="submit-button" :disabled="advancedWriting || loading || !!advancedError" @click="submitForm">
          {{ editingCard ? localeStore.t('update') : localeStore.t('add') }}
        </button>
      </div>
    </div>

    <p class="notice">{{ localeStore.text('银行卡信息由您配置，左右滑动卡片可删除','Bank details are configured by you; swipe a card to delete it') }}</p><button v-if="advancedError" @click="loadBankCards">{{ localeStore.text('重试','Retry') }}</button>
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
            v-for="currency in currencies" 
            :key="currency.code"
            class="modal-item"
            :class="{ active: formData.currency === currency.code }"
            @click="selectCurrency(currency)"
          >
            <span>{{ currency.code }}</span>
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
