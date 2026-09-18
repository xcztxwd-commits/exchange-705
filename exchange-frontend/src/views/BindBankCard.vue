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

// 货币列表
const currencies = [
  { code: 'USD', name: '美元' },
  { code: 'EUR', name: '欧元' },
  { code: 'GBP', name: '英镑' },
  { code: 'JPY', name: '日元' },
  { code: 'CNY', name: '人民币' },
  { code: 'KRW', name: '韩元' },
  { code: 'SGD', name: '新加坡元' },
  { code: 'AUD', name: '澳元' },
  { code: 'CHF', name: '瑞士法郎' },
  { code: 'IDR', name: '印尼盾' },
  { code: 'MYR', name: '马来西亚林吉特' },
  { code: 'TWD', name: '新台币' },
  { code: 'VND', name: '越南盾' },
]

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
const showForm = ref(false) // 控制表单显示/隐藏

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
  <div class="bind-bank-card-page">
    <!-- 顶部导航 -->
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('bindBankCard') }}</div>
      <div class="add-button" @click="resetForm()">+</div>
    </div>

    <!-- 银行卡列表 -->
    <div v-if="bankCards.length > 0 && !showForm" class="cards-list">
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
    <div v-if="showForm" class="form-section">
      <div class="form-item" @click="showCurrencyModal = true">
        <div class="form-label">{{ localeStore.t('currency') }}</div>
        <div class="form-input">
          <span>{{ formData.currency || localeStore.t('pleaseSelectCurrency') }}</span>
          <span class="arrow">›</span>
        </div>
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
        <button class="submit-button" @click="submitForm">
          {{ editingCard ? localeStore.t('update') : localeStore.t('add') }}
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

    <Tabbar />
  </div>
</template>

<style scoped>
.bind-bank-card-page {
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

/* 银行卡列表 */
.cards-list {
  padding: 16px;
}

.swipe-item-wrapper {
  position: relative;
  margin-bottom: 12px;
  overflow: hidden;
}

.card-item {
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

.card-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}


.card-info {
  flex: 1;
}

.card-currency {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.card-bank {
  font-size: 14px;
  color: #666;
  margin-top: 4px;
}

.card-details {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}

.card-detail-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-label {
  font-size: 14px;
  color: #999;
}

.detail-value {
  font-size: 14px;
  color: #333;
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

