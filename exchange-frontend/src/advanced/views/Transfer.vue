<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { ref, onMounted, computed } from 'vue'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'
import { formatDateTime } from '@/utils/dateTime'
import { formatTransferAmount } from '@/utils/withdrawalWallet'

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
const toAccount = ref('CONTRACT')

// 划转金额
const transferAmount = ref<number | string | null>(null)

// 资产余额
const assetsReady = ref(false)
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
  assetsReady.value = false
  try {
    const res: any = await request.get('/user/assets')
    if (res && res.success !== false) {
      fundBalance.value = Number(res.fundBalance || 0)
      contractBalance.value = Number(res.contractBalance || 0)
      optionBalance.value = Number(res.optionBalance || 0)
      assetsReady.value = true
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
  transferAmount.value = formatTransferAmount(available)
}

function normalizeAmount() {
  if (transferAmount.value !== null && transferAmount.value !== '') transferAmount.value = formatTransferAmount(Number(transferAmount.value))
}

// 格式化金额
function formatAmount(amount: number | string | null | undefined): string {
  const n = Number(amount || 0)
  return n.toLocaleString('en-US', { 
    minimumFractionDigits: 2, 
    maximumFractionDigits: 2
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
  if (transferring.value || advancedWriting.value || !assetsReady.value || advancedError.value) return
  normalizeAmount()
  const amount = Number(transferAmount.value)
  // 验证
  if (!Number.isFinite(amount) || amount <= 0) {
    showToast(localeStore.t('pleaseEnterValidTransferAmount'), 'error')
    return
  }
  
  const available = getAvailableBalance(fromAccount.value)
  if (amount > available) {
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
      amount
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
<template>
<BusinessPage :title="localeStore.t('transfer')" :error="advancedError" :busy="advancedWriting">

<section class="card"><h2>{{ localeStore.text('划转账户','Transfer accounts') }}</h2><button class="row" @click="openAccountModal('from')"><span>{{ localeStore.t('from') }}</span><span>{{ getAccountName(fromAccount) }} ⌄</span></button><button class="row" @click="openAccountModal('to')"><span>{{ localeStore.t('to') }}</span><span>{{ getAccountName(toAccount) }} ⌄</span></button><button :disabled="transferring" @click="swapAccounts">{{ localeStore.t('swap') }}</button></section><section class="card"><h2>{{ localeStore.t('transferAmount') }}</h2><div class="row"><span>{{ localeStore.t('available') }} USD</span><span>{{ !assetsReady || advancedError ? '—' : formatAmount(getAvailableBalance(fromAccount)) }}</span></div><label class="field">{{ localeStore.t('amount') }} / USD<div class="input-row"><input v-model="transferAmount" type="number" min="0" step="0.01" :placeholder="localeStore.t('pleaseEnterTransferAmount')" @blur="normalizeAmount" /><button class="text-button" :disabled="!assetsReady || !!advancedError" @click="setMaxAmount">{{ localeStore.t('all') }}</button></div></label></section><button class="primary" :disabled="!assetsReady || transferring || advancedWriting || advancedError!==''" @click="submitTransfer">{{ transferring ? localeStore.t('transferring') : localeStore.text('确认划转','Confirm transfer') }}</button><h2>{{ localeStore.t('transferRecords') }}</h2><p v-if="loadingRecords" class="empty">{{ localeStore.t('loading') }}</p><p v-else-if="!records.length" class="empty">{{ localeStore.t('noTransferRecords') }}</p><div v-for="r in records" :key="r.id"><div class="row"><span>{{ getAccountName(r.fromAccount) }} — {{ getAccountName(r.toAccount) }}</span><span>{{ formatAmount(r.amount) }} USD</span></div><p class="muted">{{ formatDateTime(r.createdAt) }}</p></div><button v-if="advancedError" @click="advancedError='';loadAssets();loadRecords()">{{ localeStore.text('重试','Retry') }}</button>
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
    </div><div v-if="toastMessage" class="toast-message" :class="toastType" role="status">{{ toastMessage }}</div>
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
