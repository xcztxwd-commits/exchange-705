<template>
  <div class="withdraw-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('withdraw') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <!-- 标签页：数字货币 / 银行卡 -->
    <div class="tabs-container">
      <div 
        class="tab-item" 
        :class="{ active: withdrawType === 'digital' }"
        @click="switchWithdrawType('digital')"
      >
        {{ localeStore.t('digitalCurrency') }}
      </div>
      <div 
        class="tab-item" 
        :class="{ active: withdrawType === 'bank' }"
        @click="switchWithdrawType('bank')"
      >
        {{ localeStore.t('bankCard') }}
      </div>
    </div>

    <div class="withdraw-content">
      <!-- 数字货币提现 -->
      <div v-if="withdrawType === 'digital'" class="withdraw-form">
        <!-- 货币选择 -->
        <div class="form-group">
          <div class="form-label">{{ localeStore.t('currency') }}</div>
          <div class="select-input" @click="showCurrencyModal = true">
            <span :class="{ placeholder: !selectedCurrency }">
              {{ selectedCurrency || localeStore.t('pleaseSelectCurrency') }}
            </span>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M9 18L15 12L9 6" stroke="#999" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
        </div>

        <!-- 提币地址 -->
        <div class="form-group">
          <div class="form-label">{{ localeStore.t('withdrawAddress') }}</div>
          <div class="select-input" @click="showAddressModal = true">
            <span :class="{ placeholder: !selectedAddress }">
              {{ selectedAddress || localeStore.t('pleaseSelectWithdrawAddress') }}
            </span>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M9 18L15 12L9 6" stroke="#999" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
        </div>

        <!-- 数量 -->
        <div class="form-group">
          <div class="form-label">{{ localeStore.t('quantity') }}</div>
          <input 
            type="number" 
            v-model.number="amount" 
            class="input-box" 
            :placeholder="localeStore.t('quantity')"
            step="0.00000001"
            min="0"
          />
        </div>

        <!-- 备注 -->
        <div class="form-group">
          <div class="form-label">{{ localeStore.t('remarkCurrencyName') }}</div>
          <input 
            type="text" 
            v-model="remark" 
            class="input-box" 
            :placeholder="localeStore.t('remarkPlaceholder')"
          />
        </div>

        <!-- 手续费和预计到账 -->
        <div class="summary-section">
          <div class="summary-item">
            <span>{{ localeStore.t('fee') }}</span>
            <span>{{ fee }} {{ selectedCurrency || '' }}</span>
          </div>
          <div class="summary-item">
            <span>{{ localeStore.t('expectedArrivalAmount') }}</span>
            <span>{{ actualAmount }} {{ selectedCurrency || '' }}</span>
          </div>
          <div class="summary-item">
            <span>{{ localeStore.t('balance') }}</span>
            <span>{{ fundBalance }} USD</span>
          </div>
        </div>

        <!-- 提币按钮 -->
        <button class="withdraw-button" @click="submitWithdraw" :disabled="submitting">
          {{ submitting ? localeStore.t('submitting') : localeStore.t('withdrawButton') }}
        </button>
      </div>

      <!-- 银行卡提现 -->
      <div v-if="withdrawType === 'bank'" class="withdraw-form">
        <!-- 货币选择 -->
        <div class="form-group">
          <div class="form-label">{{ localeStore.t('currency') }}</div>
          <div class="select-input" @click="showCurrencyModal = true">
            <span :class="{ placeholder: !selectedCurrency }">
              {{ selectedCurrency || localeStore.t('pleaseSelectCurrency') }}
            </span>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M9 18L15 12L9 6" stroke="#999" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
        </div>

        <!-- 收款人账户 -->
        <div class="form-group">
          <div class="form-label">{{ localeStore.t('recipientAccount') }}</div>
          <div class="select-input" @click="showAccountModal = true">
            <span :class="{ placeholder: !selectedAccount }">
              {{ selectedAccount || localeStore.t('pleaseSelectRecipientAccount') }}
            </span>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M9 18L15 12L9 6" stroke="#999" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
        </div>

        <!-- 数量 -->
        <div class="form-group">
          <div class="form-label">{{ localeStore.t('quantity') }}</div>
          <input 
            type="number" 
            v-model.number="amount" 
            class="input-box" 
            :placeholder="localeStore.t('quantity')"
            step="0.01"
            min="0"
          />
        </div>

        <!-- 备注 -->
        <div class="form-group">
          <div class="form-label">{{ localeStore.t('remarkCurrencyName') }}</div>
          <input 
            type="text" 
            v-model="remark" 
            class="input-box" 
            :placeholder="localeStore.t('remarkPlaceholder')"
          />
        </div>

        <!-- 手续费和预计到账 -->
        <div class="summary-section">
          <div class="summary-item">
            <span>{{ localeStore.t('fee') }}</span>
            <span>{{ fee }} {{ selectedCurrency || '' }}</span>
          </div>
          <div class="summary-item">
            <span>{{ localeStore.t('expectedArrivalAmount') }}</span>
            <span>{{ actualAmount }} {{ selectedCurrency || '' }}</span>
          </div>
          <div class="summary-item">
            <span>{{ localeStore.t('balance') }}</span>
            <span>{{ fundBalance }} USD</span>
          </div>
        </div>

        <!-- 提币按钮 -->
        <button class="withdraw-button" @click="submitWithdraw" :disabled="submitting">
          {{ submitting ? localeStore.t('submitting') : localeStore.t('withdrawButton') }}
        </button>
      </div>

      <!-- 提币记录 -->
      <div class="records-section">
        <div class="records-title">{{ localeStore.t('withdrawRecords') }}</div>
        <div v-if="loadingRecords" class="loading-records">{{ localeStore.t('loading') }}</div>
        <div v-else-if="records.length === 0" class="empty-records">{{ localeStore.t('noWithdrawRecords') }}</div>
        <div v-else class="records-list">
          <div v-for="record in records" :key="record.id" class="record-item">
            <div class="record-row">
              <div class="record-label">{{ localeStore.t('quantity') }}</div>
              <div class="record-value">
                {{ record.amount }}
                {{ record.type === 'bank' ? 'USD' : record.network }}
              </div>
            </div>
            <div class="record-row">
              <div class="record-label">{{ localeStore.t('arrivalAmount') }}</div>
              <div class="record-value">{{ record.actualAmount || record.amount }} {{ record.network }}</div>
            </div>
            <div class="record-row">
              <div class="record-label">{{ localeStore.t('unit') }}</div>
              <div class="record-value">{{ record.network }}</div>
            </div>
            <div class="record-row">
              <div class="record-label">{{ localeStore.t('fee') }}</div>
              <div class="record-value">{{ record.fee || 0 }}</div>
            </div>
            <div class="record-row">
              <div class="record-label">{{ localeStore.t('status') }}</div>
              <div class="record-value" :class="getStatusClass(record.status)">
                {{ getStatusText(record.status) }}
              </div>
            </div>
            <div class="record-row">
              <div class="record-label">{{ localeStore.t('time') }}</div>
              <div class="record-value">{{ formatDate(record.createdAt) }}</div>
            </div>
            <div v-if="record.address" class="record-row">
              <div class="record-label">{{ withdrawType === 'digital' ? localeStore.t('withdrawAddressLabel') : localeStore.t('recipientAccountLabel') }}</div>
              <div class="record-value record-address">{{ record.address }}</div>
            </div>
            <div v-if="record.remark" class="record-row">
              <div class="record-label">{{ localeStore.t('remark') }}</div>
              <div class="record-value">{{ record.remark }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 货币选择弹窗 -->
    <div v-if="showCurrencyModal" class="modal-overlay" @click="showCurrencyModal = false">
      <div class="modal-content" @click.stop>
        <div class="modal-header">
          <span class="modal-cancel" @click="showCurrencyModal = false">{{ localeStore.t('cancel') }}</span>
          <span class="modal-title">{{ localeStore.t('selectCurrencyTitle') }}</span>
          <span class="modal-confirm" @click="confirmCurrency">{{ localeStore.t('confirm') }}</span>
        </div>
        <div class="modal-list">
          <div
            v-for="currency in currencies"
            :key="currency.value"
            class="modal-item"
            :class="{ active: tempCurrency === currency.value }"
            @click="tempCurrency = currency.value"
          >
            {{ currency.label }}
          </div>
        </div>
      </div>
    </div>

        <!-- 地址选择弹窗 -->
    <div v-if="showAddressModal" class="modal-overlay" @click="showAddressModal = false">
      <div class="modal-content" @click.stop>
        <div class="modal-header">
          <span class="modal-cancel" @click="showAddressModal = false">{{ localeStore.t('cancel') }}</span>
          <span class="modal-title">{{ localeStore.t('selectWithdrawAddressTitle') }}</span>
          <span class="modal-confirm" @click="confirmAddress">{{ localeStore.t('confirm') }}</span>
        </div>
        <div class="modal-list">
          <div
            v-for="address in filteredDigitalAddresses"
            :key="address.id"
            class="modal-item"
            :class="{ active: tempAddress === address.address }"
            @click="tempAddress = address.address; tempAddressNetwork = address.network"
          >
            <div>{{ address.network }}</div>
            <div class="address-text">{{ address.address }}</div>
          </div>
          <div v-if="filteredDigitalAddresses.length === 0" class="modal-empty">
            {{ localeStore.t('noAvailableAddresses') }}
          </div>
        </div>
      </div>
    </div>

        <!-- 账户选择弹窗 -->
    <div v-if="showAccountModal" class="modal-overlay" @click="showAccountModal = false">
      <div class="modal-content" @click.stop>
        <div class="modal-header">
          <span class="modal-cancel" @click="showAccountModal = false">{{ localeStore.t('cancel') }}</span>
          <span class="modal-title">{{ localeStore.t('selectRecipientAccountTitle') }}</span>
          <span class="modal-confirm" @click="confirmAccount">{{ localeStore.t('confirm') }}</span>
        </div>
        <div class="modal-list">
          <div
            v-for="account in filteredBankAccounts"
            :key="account.id"
            class="modal-item"
            :class="{ active: tempAccount === account.recipientAccount }"
            @click="tempAccount = account.recipientAccount; tempAccountCurrency = account.currency"
          >
            <div>{{ account.currency }} - {{ account.bankName }}</div>
            <div class="address-text">{{ account.recipientAccount }} ({{ account.recipientName }})</div>
          </div>
          <div v-if="filteredBankAccounts.length === 0" class="modal-empty">
            {{ localeStore.t('noAvailableAccounts') }}
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
import { ref, onMounted, watch, computed } from 'vue'
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
import { useLocaleStore } from '@/store/locale'
import { formatDateTime } from '@/utils/dateTime'

const router = useRouter()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 提现方式：数字货币 / 银行卡
const withdrawType = ref<'digital' | 'bank'>('digital')

// 货币选择
const selectedCurrency = ref('')
const showCurrencyModal = ref(false)
const tempCurrency = ref('')
const currencies = ref<Array<{ label: string; value: string }>>([])

// 地址选择（数字货币）
const selectedAddress = ref('')
const selectedAddressNetwork = ref('')
const showAddressModal = ref(false)
const tempAddress = ref('')
const tempAddressNetwork = ref('')
const digitalAddresses = ref<any[]>([])

// 账户选择（银行卡）
const selectedAccount = ref('')
const selectedAccountCurrency = ref('')
const showAccountModal = ref(false)
const tempAccount = ref('')
const tempAccountCurrency = ref('')
const bankAccounts = ref<any[]>([])

// 表单数据
const amount = ref<number | null>(null)
const remark = ref('')

// 计算数据
const fee = ref('0')
const actualAmount = ref('0')
const fundBalance = ref('0')

// 状态
const submitting = ref(false)
const loadingRecords = ref(false)
const records = ref<any[]>([])

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

// 切换提现方式
function switchWithdrawType(type: 'digital' | 'bank') {
  withdrawType.value = type
  selectedCurrency.value = ''
  selectedAddress.value = ''
  selectedAddressNetwork.value = ''
  selectedAccount.value = ''
  selectedAccountCurrency.value = ''
  amount.value = null
  remark.value = ''
  fee.value = '0'
  actualAmount.value = '0'
  loadCurrencies()
  if (type === 'digital') {
    loadDigitalAddresses()
  } else {
    loadBankAccounts()
  }
}

// 加载货币列表
function loadCurrencies() {
  if (withdrawType.value === 'digital') {
    // 数字货币货币列表（从绑定的地址中获取网络，使用网络作为货币）
    const networks = new Set<string>()
    digitalAddresses.value.forEach(addr => {
      if (addr.network) {
        networks.add(addr.network)
      }
    })
    currencies.value = Array.from(networks).map(n => ({
      label: n,
      value: n
    }))
  } else {
    // 银行卡货币列表（从绑定的银行卡中获取）
    const bankCurrencies = new Set<string>()
    bankAccounts.value.forEach(acc => {
      if (acc.currency) {
        bankCurrencies.add(acc.currency)
      }
    })
    currencies.value = Array.from(bankCurrencies).map(c => ({
      label: c,
      value: c
    }))
  }
}

// 加载数字货币地址
async function loadDigitalAddresses() {
  try {
    const res: any = await request.get('/wallet/digital-addresses')
    if (res && res.success !== false && res.list) {
      digitalAddresses.value = res.list
      if (withdrawType.value === 'digital') {
        loadCurrencies()
      }
    }
  } catch (e: any) {
    console.error('加载数字货币地址失败:', e)
  }
}

// 加载银行卡账户
async function loadBankAccounts() {
  try {
    const res: any = await request.get('/wallet/bank-cards')
    if (res && res.success !== false && res.list) {
      bankAccounts.value = res.list
      if (withdrawType.value === 'bank') {
        loadCurrencies()
      }
    }
  } catch (e: any) {
    console.error('加载银行卡账户失败:', e)
  }
}

// 加载余额
async function loadBalance() {
  try {
    const res: any = await request.get('/user/assets')
    if (res && res.success !== false) {
      fundBalance.value = res.fundBalance || '0'
    }
  } catch (e: any) {
    console.error('加载余额失败:', e)
  }
}

// 确认货币选择
function confirmCurrency() {
  if (!tempCurrency.value) {
    showToast(localeStore.t('pleaseSelectCurrency'), 'error')
    return
  }
  selectedCurrency.value = tempCurrency.value
  showCurrencyModal.value = false
  // 清空地址/账户选择（watch会自动选择第一个）
  selectedAddress.value = ''
  selectedAddressNetwork.value = ''
  selectedAccount.value = ''
  selectedAccountCurrency.value = ''
  // watch会自动处理选择和计算
}

// 确认地址选择
function confirmAddress() {
  selectedAddress.value = tempAddress.value
  selectedAddressNetwork.value = tempAddressNetwork.value
  showAddressModal.value = false
  calculateAmount()
}

// 确认账户选择
function confirmAccount() {
  selectedAccount.value = tempAccount.value
  selectedAccountCurrency.value = tempAccountCurrency.value
  showAccountModal.value = false
  calculateAmount()
}

// 计算手续费和预计到账金额
async function calculateAmount() {
  if (!amount.value || amount.value <= 0) {
    fee.value = '0'
    actualAmount.value = '0'
    return
  }
  
  // 需要先选择货币和地址/账户
  if (withdrawType.value === 'digital' && (!selectedCurrency.value || !selectedAddressNetwork.value)) {
    fee.value = '0'
    actualAmount.value = '0'
    return
  }
  
  if (withdrawType.value === 'bank' && !selectedCurrency.value) {
    fee.value = '0'
    actualAmount.value = '0'
    return
  }
  
  try {
    const network = withdrawType.value === 'digital' ? selectedAddressNetwork.value : selectedCurrency.value
    const res: any = await request.post('/withdraw/calculate', {
      type: withdrawType.value,
      network: network,
      amount: amount.value
    })
    
    if (res && res.success !== false) {
      fee.value = (res.fee || 0).toString()
      actualAmount.value = (res.actualAmount || amount.value).toString()
    }
  } catch (e: any) {
    console.error('计算金额失败:', e)
    fee.value = '0'
    actualAmount.value = amount.value ? amount.value.toString() : '0'
  }
}

// 过滤后的数字货币地址（根据选择的货币/网络）
const filteredDigitalAddresses = computed(() => {
  if (!selectedCurrency.value) {
    return digitalAddresses.value
  }
  // 数字货币中，selectedCurrency实际上是网络（如USDT-TRC20）
  return digitalAddresses.value.filter(addr => addr.network === selectedCurrency.value)
})

// 过滤后的银行卡账户（根据选择的货币）
const filteredBankAccounts = computed(() => {
  if (!selectedCurrency.value) {
    return bankAccounts.value
  }
  return bankAccounts.value.filter(acc => acc.currency === selectedCurrency.value)
})

// 监听金额变化
watch(amount, () => {
  calculateAmount()
})

// 监听货币选择变化，自动选择第一个地址/账户
watch(selectedCurrency, () => {
  if (withdrawType.value === 'digital') {
    if (filteredDigitalAddresses.value.length > 0) {
      const firstAddr = filteredDigitalAddresses.value[0]
      selectedAddress.value = firstAddr.address
      selectedAddressNetwork.value = firstAddr.network
    } else {
      selectedAddress.value = ''
      selectedAddressNetwork.value = ''
    }
  } else {
    if (filteredBankAccounts.value.length > 0) {
      const firstAcc = filteredBankAccounts.value[0]
      selectedAccount.value = firstAcc.recipientAccount
      selectedAccountCurrency.value = firstAcc.currency
    } else {
      selectedAccount.value = ''
      selectedAccountCurrency.value = ''
    }
  }
  calculateAmount()
})

// 提交提现申请
async function submitWithdraw() {
  if (submitting.value) return
  
  // 验证
  if (withdrawType.value === 'digital') {
    if (!selectedCurrency.value || !selectedAddress.value) {
      showToast(localeStore.t('pleaseSelectCurrencyAndWithdrawAddress'), 'error')
      return
    }
  } else {
    if (!selectedCurrency.value || !selectedAccount.value) {
      showToast(localeStore.t('pleaseSelectCurrencyAndRecipientAccount'), 'error')
      return
    }
  }
  
  if (!amount.value || amount.value <= 0) {
    showToast(localeStore.t('pleaseEnterWithdrawAmount'), 'error')
    return
  }
  
  submitting.value = true
  
  try {
    // 对于数字货币，network是地址的网络（如USDT-TRC20）
    // 对于银行卡，network是货币（如USD）
    const network = withdrawType.value === 'digital' ? selectedAddressNetwork.value : selectedCurrency.value
    
    if (!network) {
      showToast(localeStore.t('pleaseSelectCurrency'), 'error')
      submitting.value = false
      return
    }
    
    const res: any = await request.post('/withdraw/submit', {
      type: withdrawType.value,
      network: network,
      amount: amount.value,
      address: withdrawType.value === 'digital' ? selectedAddress.value : selectedAccount.value,
      remark: remark.value
    })
    
    if (res && res.success !== false) {
      showToast(res.message || localeStore.t('withdrawApplicationSubmitted'), 'success')
      // 清空表单
      amount.value = null
      remark.value = ''
      selectedAddress.value = ''
      selectedAccount.value = ''
      fee.value = '0'
      actualAmount.value = '0'
      // 刷新记录
      setTimeout(() => {
        loadRecords()
        loadBalance()
      }, 1000)
    } else {
      showToast(res.message || localeStore.t('submitFailedPleaseRetry'), 'error')
    }
  } catch (e: any) {
    console.error('提交提现申请失败:', e)
    showToast(e.response?.data?.message || e.message || localeStore.t('submitFailedPleaseRetry'), 'error')
  } finally {
    submitting.value = false
  }
}

// 加载提现记录
async function loadRecords() {
  loadingRecords.value = true
  try {
    const res: any = await request.get('/withdraw/records')
    if (res && res.success !== false && res.list) {
      records.value = res.list
    }
  } catch (e: any) {
    console.error('加载提现记录失败:', e)
  } finally {
    loadingRecords.value = false
  }
}

// 使用工具函数 formatDateTime 代替 formatDate
const formatDate = formatDateTime

// 获取状态文本
function getStatusText(status: string) {
  const statusMap: Record<string, string> = {
    PENDING: localeStore.t('statusPendingReview'),
    APPROVED: localeStore.t('statusApproved'),
    REJECTED: localeStore.t('statusRejected'),
    COMPLETED: localeStore.t('statusCompleted')
  }
  return statusMap[status] || status
}

// 获取状态样式类
function getStatusClass(status: string) {
  const classMap: Record<string, string> = {
    PENDING: 'status-pending',
    APPROVED: 'status-approved',
    REJECTED: 'status-rejected',
    COMPLETED: 'status-completed'
  }
  return classMap[status] || ''
}

onMounted(() => {
  loadBalance()
  loadRecords()
  loadDigitalAddresses()
  loadBankAccounts()
})
</script>

<style scoped>
.withdraw-page {
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

/* 标签页 */
.tabs-container {
  display: flex;
  background: #fff;
  border-bottom: 1px solid #eee;
}

.tab-item {
  flex: 1;
  padding: 14px;
  text-align: center;
  font-size: 15px;
  color: #999;
  cursor: pointer;
  border-bottom: 2px solid transparent;
}

.tab-item.active {
  color: #73b100;
  border-bottom-color: #73b100;
  font-weight: 600;
}

/* 内容区域 */
.withdraw-content {
  padding: 16px;
}

.withdraw-form {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 16px;
}

.form-group {
  margin-bottom: 20px;
}

.form-label {
  font-size: 14px;
  color: #666;
  margin-bottom: 8px;
}

.select-input {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px;
  background: #f5f7fb;
  border-radius: 8px;
  cursor: pointer;
}

.select-input span.placeholder {
  color: #999;
}

.input-box {
  width: 100%;
  padding: 12px;
  background: #f5f7fb;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  color: #333;
  outline: none;
  box-sizing: border-box;
}

/* 摘要区域 */
.summary-section {
  padding: 16px 0;
  border-top: 1px solid #f0f0f0;
  margin-top: 20px;
}

.summary-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  font-size: 14px;
  color: #666;
}

.summary-item span:last-child {
  color: #333;
  font-weight: 500;
}

/* 提币按钮 */
.withdraw-button {
  width: 100%;
  padding: 14px;
  background: #73b100;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 20px;
}

.withdraw-button:active:not(:disabled) {
  opacity: 0.8;
}

.withdraw-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

/* 提币记录 */
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
  padding: 40px;
  color: #999;
  font-size: 14px;
}

.records-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.record-item {
  padding: 16px;
  background: #f8f8f8;
  border-radius: 8px;
}

.record-row {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  font-size: 14px;
}

.record-label {
  color: #666;
}

.record-value {
  color: #333;
  font-weight: 500;
  word-break: break-all;
  text-align: right;
  flex: 1;
  margin-left: 16px;
}

.record-address {
  font-family: monospace;
  font-size: 12px;
}

.status-pending {
  color: #ff9800;
}

.status-approved {
  color: #73b100;
}

.status-rejected {
  color: #ff4444;
}

.status-completed {
  color: #2196f3;
}

/* 弹窗 */
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 1000;
  display: flex;
  align-items: flex-end;
}

.modal-content {
  width: 100%;
  max-height: 70vh;
  background: #fff;
  border-radius: 20px 20px 0 0;
  display: flex;
  flex-direction: column;
  animation: slideUp 0.3s ease-out;
}

@keyframes slideUp {
  from {
    transform: translateY(100%);
  }
  to {
    transform: translateY(0);
  }
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #eee;
}

.modal-cancel {
  color: #999;
  font-size: 14px;
  cursor: pointer;
}

.modal-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.modal-confirm {
  color: #73b100;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.modal-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
}

.modal-item {
  padding: 16px;
  font-size: 15px;
  color: #333;
  cursor: pointer;
  border-bottom: 1px solid #f0f0f0;
}

.modal-item.active {
  color: #73b100;
  font-weight: 600;
  background: #f5f9f0;
}

.modal-item .address-text {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
  word-break: break-all;
}

.modal-empty {
  padding: 40px;
  text-align: center;
  color: #999;
  font-size: 14px;
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

