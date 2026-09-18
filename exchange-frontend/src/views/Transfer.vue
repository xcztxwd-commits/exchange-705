<template>
  <div class="transfer-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('transfer') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="transfer-content">
      <!-- 账户选择 -->
      <div class="account-section">
        <div class="account-line" @click="openAccountModal('from')">
          <div class="account-indicator green"></div>
          <div class="account-text">{{ localeStore.t('from') }} {{ getAccountName(fromAccount) }}</div>
        </div>
        <div class="account-line" @click="openAccountModal('to')">
          <div class="account-indicator red"></div>
          <div class="account-text">{{ localeStore.t('to') }} {{ getAccountName(toAccount) }}</div>
        </div>
        <div class="swap-button" @click.stop="swapAccounts">
          <img src="/img/hz.png" :alt="localeStore.t('swap')" class="swap-icon" />
        </div>
      </div>

      <!-- 金额输入 -->
      <div class="amount-section">
        <div class="amount-header">
          <span class="amount-label">{{ localeStore.t('transferAmount') }}</span>
          <span class="available-text">{{ localeStore.t('available') }}: {{ formatAmount(getAvailableBalance(fromAccount)) }}</span>
        </div>
        <div class="amount-input-wrapper">
          <input 
            type="number" 
            v-model.number="transferAmount" 
            class="amount-input" 
            :placeholder="localeStore.t('pleaseEnterTransferAmount')"
            step="0.01"
            min="0"
          />
          <span class="currency-text">USD</span>
          <span class="all-button" @click="setMaxAmount">{{ localeStore.t('all') }}</span>
        </div>
      </div>

      <!-- 划转按钮 -->
      <button class="transfer-button" @click="submitTransfer" :disabled="transferring">
        {{ transferring ? localeStore.t('transferring') : localeStore.t('transfer') }}
      </button>

      <!-- 划转记录 -->
      <div class="records-section">
        <div class="records-title">{{ localeStore.t('transferRecords') }}</div>
        <div v-if="loadingRecords" class="loading-records">{{ localeStore.t('loading') }}</div>
        <div v-else-if="records.length === 0" class="empty-records">{{ localeStore.t('noTransferRecords') }}</div>
        <div v-else class="records-list">
          <div 
            v-for="record in records" 
            :key="record.id" 
            class="record-item"
          >
            <div class="record-amount">{{ localeStore.t('quantity') }}: {{ formatAmount(record.amount) }}</div>
            <div class="record-desc">
              {{ getAccountName(record.fromAccount) }} -- {{ getAccountName(record.toAccount) }}
            </div>
            <div class="record-time">{{ formatDateTime(record.createdAt) }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 账户选择弹窗 -->
    <div v-if="showAccountModal" class="modal-overlay" @click="showAccountModal = false">
      <div class="modal-content" @click.stop>
        <div class="modal-header">
          <span class="modal-cancel" @click="showAccountModal = false">{{ localeStore.t('cancel') }}</span>
          <span class="modal-title">{{ localeStore.t('selectAccount') }}</span>
          <span class="modal-confirm" @click="confirmAccount">{{ localeStore.t('confirm') }}</span>
        </div>
        <div class="modal-list">
          <div 
            v-for="account in accountTypes" 
            :key="account.value"
            class="modal-item"
            :class="{ active: selectedAccount === account.value }"
            @click="selectedAccount = account.value"
          >
            <span>{{ account.label }}</span>
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

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'
import { formatDateTime } from '@/utils/dateTime'

const router = useRouter()
const auth = useAuthStore()
auth.load()

const localeStore = useLocaleStore()
localeStore.loadLocale()

// 账户类型
const accountTypes = computed(() => [
  { label: localeStore.t('fundAccount').replace(':', ''), value: 'FUND' },
  { label: localeStore.t('optionAccount').replace(':', ''), value: 'OPTION' },
  { label: localeStore.t('contractAccount'), value: 'CONTRACT' },
])

// 转出和转入账户
const fromAccount = ref('FUND')
const toAccount = ref('OPTION')

// 划转金额
const transferAmount = ref<number | null>(null)

// 资产余额
const fundBalance = ref(0)
const contractBalance = ref(0)
const optionBalance = ref(0)

// 划转记录
const records = ref<any[]>([])
const loadingRecords = ref(false)

// 状态
const transferring = ref(false)
const showAccountModal = ref(false)
const selectedAccount = ref('')
const currentSelectingAccount = ref<'from' | 'to' | null>(null)

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

// 获取账户名称
function getAccountName(accountType: string) {
  const account = accountTypes.value.find(a => a.value === accountType)
  return account ? account.label : accountType
}

// 获取可用余额
function getAvailableBalance(accountType: string): number {
  switch (accountType) {
    case 'FUND':
      return fundBalance.value
    case 'CONTRACT':
      return contractBalance.value
    case 'OPTION':
      return optionBalance.value
    default:
      return 0
  }
}

// 加载资产信息
async function loadAssets() {
  try {
    const res: any = await request.get('/user/assets')
    if (res && res.success !== false) {
      fundBalance.value = Number(res.fundBalance || 0)
      contractBalance.value = Number(res.contractBalance || 0)
      optionBalance.value = Number(res.optionBalance || 0)
    }
  } catch (e: any) {
    console.error('加载资产信息失败:', e)
  }
}

// 加载划转记录
async function loadRecords() {
  loadingRecords.value = true
  try {
    const res: any = await request.get('/transfer/records', {
      params: { page: 0, size: 20 }
    })
    if (res && res.success !== false) {
      records.value = res.list || []
    }
  } catch (e: any) {
    console.error('加载划转记录失败:', e)
  } finally {
    loadingRecords.value = false
  }
}

// 交换账户
function swapAccounts() {
  const temp = fromAccount.value
  fromAccount.value = toAccount.value
  toAccount.value = temp
  transferAmount.value = null
}

// 设置最大金额
function setMaxAmount() {
  const available = getAvailableBalance(fromAccount.value)
  transferAmount.value = available
}

// 格式化金额
function formatAmount(amount: number | string | null | undefined): string {
  const n = Number(amount || 0)
  return n.toLocaleString('en-US', { 
    minimumFractionDigits: 2, 
    maximumFractionDigits: 8 
  })
}


// 打开账户选择弹窗
function openAccountModal(type: 'from' | 'to') {
  currentSelectingAccount.value = type
  selectedAccount.value = type === 'from' ? fromAccount.value : toAccount.value
  showAccountModal.value = true
}

// 确认账户选择
function confirmAccount() {
  if (!currentSelectingAccount.value) return
  
  if (currentSelectingAccount.value === 'from') {
    fromAccount.value = selectedAccount.value
    // 如果选择的账户和转入账户相同，需要避免
    if (fromAccount.value === toAccount.value) {
      // 自动选择另一个账户
      const otherAccounts = accountTypes.value.filter(a => a.value !== fromAccount.value)
      if (otherAccounts.length > 0 && otherAccounts[0]) {
        toAccount.value = otherAccounts[0].value
      }
    }
  } else if (currentSelectingAccount.value === 'to') {
    toAccount.value = selectedAccount.value
    // 如果选择的账户和转出账户相同，需要避免
    if (toAccount.value === fromAccount.value) {
      // 自动选择另一个账户
      const otherAccounts = accountTypes.value.filter(a => a.value !== toAccount.value)
      if (otherAccounts.length > 0 && otherAccounts[0]) {
        fromAccount.value = otherAccounts[0].value
      }
    }
  }
  showAccountModal.value = false
  currentSelectingAccount.value = null
  transferAmount.value = null
}

// 提交划转
async function submitTransfer() {
  // 验证
  if (!transferAmount.value || transferAmount.value <= 0) {
    showToast(localeStore.t('pleaseEnterValidTransferAmount'), 'error')
    return
  }
  
  const available = getAvailableBalance(fromAccount.value)
  if (transferAmount.value > available) {
    showToast(localeStore.t('insufficientBalance'), 'error')
    return
  }
  
  if (fromAccount.value === toAccount.value) {
    showToast(localeStore.t('fromAndToAccountCannotBeSame'), 'error')
    return
  }
  
  transferring.value = true
  
  try {
    const res: any = await request.post('/transfer/submit', {
      fromAccount: fromAccount.value,
      toAccount: toAccount.value,
      amount: transferAmount.value
    })
    
    if (res && res.success !== false) {
      showToast(localeStore.t('transferSuccess'), 'success')
      transferAmount.value = null
      // 重新加载资产和记录
      await loadAssets()
      await loadRecords()
    } else {
      showToast(res.message || localeStore.t('transferFailed'), 'error')
    }
  } catch (e: any) {
    console.error('劃轉失敗:', e)
    showToast(e.response?.data?.message || e.message || localeStore.t('transferFailed'), 'error')
  } finally {
    transferring.value = false
  }
}

onMounted(() => {
  loadAssets()
  loadRecords()
})
</script>

<style scoped>
.transfer-page {
  min-height: 100vh;
  background: #f8f8f8;
  padding-bottom: 80px;
}

/* 顶部导航 */
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

/* 内容区域 */
.transfer-content {
  padding: 16px;
}

/* 账户选择区域 */
.account-section {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 16px;
  position: relative;
}

.account-line {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
  cursor: pointer;
  padding: 8px;
  border-radius: 8px;
}

.account-line:last-child {
  margin-bottom: 0;
}

.account-line:active {
  background: #f5f5f5;
}

.account-indicator {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 12px;
}

.account-indicator.green {
  background: #73b100;
}

.account-indicator.red {
  background: #ff4444;
}

.account-text {
  font-size: 16px;
  color: #333;
  flex: 1;
}

.swap-button {
  position: absolute;
  right: 20px;
  top: 50%;
  transform: translateY(-50%);
  width: 40px;
  height: 40px;
  background: #f0f0f0;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.swap-button:active {
  opacity: 0.8;
}

.swap-icon {
  width: 24px;
  height: 24px;
  object-fit: contain;
}

/* 金额输入区域 */
.amount-section {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 16px;
}

.amount-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.amount-label {
  font-size: 16px;
  color: #333;
  font-weight: 600;
}

.available-text {
  font-size: 14px;
  color: #666;
}

.amount-input-wrapper {
  display: flex;
  align-items: center;
  gap: 12px;
}

.amount-input {
  flex: 1;
  padding: 12px;
  background: #f5f7fb;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  color: #333;
  outline: none;
}

.currency-text {
  font-size: 14px;
  color: #666;
}

.all-button {
  font-size: 14px;
  color: #73b100;
  font-weight: 600;
  cursor: pointer;
  padding: 4px 8px;
}

.all-button:active {
  opacity: 0.8;
}

/* 划转按钮 */
.transfer-button {
  width: 100%;
  padding: 14px;
  background: #73b100;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  margin-bottom: 24px;
}

.transfer-button:active:not(:disabled) {
  opacity: 0.8;
}

.transfer-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 记录区域 */
.records-section {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
}

.records-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin-bottom: 16px;
}

.loading-records,
.empty-records {
  text-align: center;
  padding: 40px 0;
  color: #999;
  font-size: 14px;
}

.records-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.record-item {
  padding-bottom: 16px;
  border-bottom: 1px solid #f0f0f0;
}

.record-item:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.record-amount {
  font-size: 16px;
  font-weight: 600;
  color: #333;
  margin-bottom: 8px;
}

.record-desc {
  font-size: 14px;
  color: #666;
  margin-bottom: 8px;
}

.record-time {
  font-size: 12px;
  color: #999;
}

/* 账户选择弹窗 */
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
  padding: 20px;
  max-height: 70vh;
  overflow-y: auto;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #eee;
}

.modal-cancel,
.modal-confirm {
  font-size: 16px;
  cursor: pointer;
  padding: 8px 16px;
}

.modal-cancel {
  color: #666;
}

.modal-confirm {
  color: #73b100;
  font-weight: 600;
}

.modal-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.modal-list {
  display: flex;
  flex-direction: column;
}

.modal-item {
  padding: 16px;
  font-size: 16px;
  color: #333;
  cursor: pointer;
  border-radius: 8px;
}

.modal-item.active {
  background: #f0f7ff;
  color: #73b100;
}

.modal-item:active {
  background: #f5f5f5;
}

/* Toast 提示 */
.toast-message {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 12px 24px;
  background: rgba(0, 0, 0, 0.8);
  color: #fff;
  border-radius: 8px;
  font-size: 14px;
  z-index: 9999;
  max-width: 80%;
  text-align: center;
}

.toast-message.success {
  background: rgba(115, 177, 0, 0.9);
}

.toast-message.error {
  background: rgba(255, 68, 68, 0.9);
}
</style>

