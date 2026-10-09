<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { ref, onMounted, watch, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import CurrencyPicker from '@/advanced/components/business/CurrencyPicker.vue'
import WithdrawWallet from '@/components/WithdrawWallet.vue'
import { formatWalletBalance, useWithdrawalWallet } from '@/utils/withdrawalWallet'
import { useWithdrawChannels } from '@/utils/withdrawChannels'
import WithdrawChannelStatus from '@/components/WithdrawChannelStatus.vue'
import { useFiatCurrency } from '@/utils/fiatCurrency'
const { currency: bankCurrency, rate: bankRate, usdPreview } = useFiatCurrency()
import { useLocaleStore } from '@/store/locale'
import { formatDateTime } from '@/utils/dateTime'

const router = useRouter()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 提现方式：数字货币 / 银行卡
const { withdrawType, showWithdrawTypeTabs, hasWithdrawChannel, withdrawChannelsReady, withdrawChannelsError, loadWithdrawChannels } = useWithdrawChannels(
  () => request.get('/withdraw/channels'), () => !submitting.value && !advancedWriting.value
)

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
const fee = ref('—')
const { withdrawAccount, balances, balanceReady, selectedBalance, loadBalance } = useWithdrawalWallet(() => request.get('/user/assets'))
const calculationReady = ref(false)
let calculationGeneration = 0
const actualAmount = ref('—')

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
  if (submitting.value || advancedWriting.value || !hasWithdrawChannel.value) return
  withdrawType.value = type
}
watch(withdrawType, type => {
  showCurrencyModal.value = showAddressModal.value = showAccountModal.value = false
  selectedCurrency.value = ''
  bankCurrency.value = 'USD'
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
})
watch(hasWithdrawChannel, available => {
  if (!available) showCurrencyModal.value = showAddressModal.value = showAccountModal.value = false
})

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
    currencies.value = [] // 银行卡输入币种由 CurrencyPicker 独立选择
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
      if (!selectedAccount.value && res.list.length) {
        selectedAccount.value = res.list[0].recipientAccount
        selectedAccountCurrency.value = res.list[0].currency
      }
      if (withdrawType.value === 'bank') {
        loadCurrencies()
      }
    }
  } catch (e: any) {
    console.error('加载银行卡账户失败:', e)
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
  const run = ++calculationGeneration
  calculationReady.value = false
  if (!hasWithdrawChannel.value) return
  if (withdrawType.value === 'bank') return // 银行卡预估使用与充值一致的 Redis 汇率快照
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
  
  try {
    const network = selectedAddressNetwork.value
    const res: any = await request.post('/withdraw/calculate', {
      type: withdrawType.value,
      network: network,
      amount: amount.value
    })
    
    if (res && res.success !== false) {
      if (run !== calculationGeneration) return
      calculationReady.value = true
      fee.value = (res.fee || 0).toString()
      actualAmount.value = (res.actualAmount || amount.value).toString()
    }
  } catch (e: any) {
    console.error('计算金额失败:', e)
    fee.value = '0'
    actualAmount.value = '—'
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

// 输入币种与收款账户独立选择
const filteredBankAccounts = computed(() => bankAccounts.value)

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
  if (submitting.value || advancedWriting.value || !balanceReady.value || advancedError.value || !hasWithdrawChannel.value) return
  if (withdrawType.value === 'bank' && bankRate.value === null) { showToast(localeStore.text('匯率暫不可用，請稍後重試', 'Exchange rate unavailable; please retry later')); return }
  
  // 验证
  if (withdrawType.value === 'digital') {
    if (!selectedCurrency.value || !selectedAddress.value) {
      showToast(localeStore.t('pleaseSelectCurrencyAndWithdrawAddress'), 'error')
      return
    }
  } else {
    if (!selectedAccount.value) {
      showToast(localeStore.t('pleaseSelectCurrencyAndRecipientAccount'), 'error')
      return
    }
  }
  
  if (!amount.value || !Number.isFinite(amount.value) || amount.value <= 0) {
    showToast(localeStore.t('enterAmount'), 'error')
    return
  }
  const requiredUsd = amount.value * (withdrawType.value === 'bank' ? bankRate.value! : 1) + (Number(fee.value) || 0)
  if (requiredUsd > selectedBalance.value) {
    showToast(localeStore.t('insufficientBalance'), 'error')
    return
  }
  
  submitting.value = true
  
  try {
    // 对于数字货币，network是地址的网络（如USDT-TRC20）
    // 对于银行卡，network是货币（如USD）
    const network = withdrawType.value === 'digital' ? selectedAddressNetwork.value : (selectedAccountCurrency.value || 'USD')
    
    if (!network) {
      showToast(localeStore.t('pleaseSelectCurrency'), 'error')
      submitting.value = false
      return
    }
    
    const res: any = await request.post('/withdraw/submit', {
      type: withdrawType.value,
      accountType: withdrawAccount.value === 'FUND' ? undefined : withdrawAccount.value,
      currency: withdrawType.value === 'bank' ? bankCurrency.value : undefined,
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
      void loadBalance()
      // 刷新记录
      setTimeout(() => {
        loadRecords()
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
<template>
<BusinessPage :title="localeStore.t('withdraw')" :error="advancedError" :busy="advancedWriting">

<WithdrawChannelStatus :ready="withdrawChannelsReady" :available="hasWithdrawChannel" :error="withdrawChannelsError" @retry="loadWithdrawChannels" />
<template v-if="hasWithdrawChannel">
<div v-if="showWithdrawTypeTabs" data-testid="withdraw-type-tabs" class="tabs"><button :class="{active:withdrawType==='digital'}" :disabled="submitting" @click="switchWithdrawType('digital')">{{ localeStore.t('depositTypeDigital') }}</button><button :class="{active:withdrawType==='bank'}" :disabled="submitting" @click="switchWithdrawType('bank')">{{ localeStore.t('depositTypeBank') }}</button></div>
<section class="card"><h2>{{ localeStore.text('收款账户','Receiving account') }}</h2><template v-if="withdrawType==='digital'"><label class="field">{{ localeStore.text('币种 / 网络','Currency / Network') }}<button class="row" @click="tempCurrency=selectedCurrency;showCurrencyModal=true">{{ selectedCurrency || localeStore.t('pleaseSelectCurrency') }} ⌄</button></label><label class="field">{{ localeStore.t('withdrawAddress') }}<button class="row" @click="tempAddress=selectedAddress;tempAddressNetwork=selectedAddressNetwork;showAddressModal=true">{{ selectedAddress || localeStore.t('pleaseSelectWithdrawAddress') }} ⌄</button></label><button v-if="!digitalAddresses.length" @click="router.push('/wallet/bind-digital-currency')">{{ localeStore.t('bindDigitalCurrencyAddress') }}</button></template><template v-else><label class="field">{{ localeStore.t('currency') }}<CurrencyPicker v-model="bankCurrency" /></label><label class="field">{{ localeStore.t('recipientAccount') }}<button class="row" @click="tempAccount=selectedAccount;tempAccountCurrency=selectedAccountCurrency;showAccountModal=true">{{ selectedAccount || localeStore.t('pleaseSelectRecipientAccount') }} ⌄</button></label><button v-if="!bankAccounts.length" @click="router.push('/wallet/bind-bank-card')">{{ localeStore.t('bindBankCard') }}</button></template></section>
<section class="card"><h2>{{ localeStore.text('出金金额','Withdrawal amount') }}</h2>
  <WithdrawWallet v-model="withdrawAccount" :balances="balances" :ready="balanceReady" :disabled="submitting || advancedWriting || !!advancedError" :send-transfer="body => request.post('/transfer/submit', body)" @transferred="loadBalance" />
  <label class="field">{{ localeStore.t('amountText') }}<input v-model.number="amount" type="number" min="0" :step="withdrawType==='bank' ? '0.01' : '0.00000001'" :placeholder="localeStore.t('enterAmount')" /></label><label class="field">{{ localeStore.t('remark') }}<input v-model="remark" :placeholder="localeStore.t('remarkPlaceholder')" /></label><div class="metrics"><div class="metric"><small>{{ localeStore.t('fee') }}</small><span>{{ advancedError ? '—' : withdrawType==='bank' ? '0.00 USD' : calculationReady ? fee+' '+selectedCurrency : '—' }}</span></div><div class="metric"><small>{{ localeStore.t('expectedArrivalAmount') }}</small><span>{{ advancedError || !amount ? '—' : withdrawType==='bank' ? usdPreview(amount) : calculationReady ? actualAmount+' '+selectedCurrency : '—' }}</span></div><div class="metric"><small>{{ localeStore.t('balance') }} USD</small><span>{{ !balanceReady || advancedError ? '—' : formatWalletBalance(selectedBalance, localeStore.locale) }}</span></div></div></section>
<p class="notice">{{ localeStore.text('仅允许通过实名认证的账户提交，费用以服务端结果为准','Identity verification is required; fees follow the service result') }}</p><button class="primary" :disabled="!balanceReady || submitting || advancedWriting || advancedError!=='' || (withdrawType==='bank' && bankRate===null)" @click="submitWithdraw">{{ submitting ? localeStore.t('submitting') : localeStore.text('提交出金','Submit withdrawal') }}</button>
</template>
<h2>{{ localeStore.t('withdrawRecords') }}</h2><p v-if="loadingRecords" class="empty">{{ localeStore.t('loading') }}</p><p v-else-if="!records.length" class="empty">{{ localeStore.t('noWithdrawRecords') }}</p><section v-for="r in records" :key="r.id" class="card"><div class="row"><span>{{ r.amount }} USD</span><span :class="getStatusClass(r.status)">{{ getStatusText(r.status) }}</span></div><div class="row"><span>{{ localeStore.t('fee') }}</span><span>{{ r.fee || 0 }}</span></div><div class="row"><span>{{ localeStore.t('arrivalAmount') }}</span><span>{{ r.actualAmount ?? r.amount }}</span></div><p class="muted">{{ formatDate(r.createdAt) }} · {{ r.network }}</p><p class="muted" style="overflow-wrap:anywhere">{{ r.address }} {{ r.remark }}</p></section><button v-if="advancedError" @click="advancedError='';loadBalance();loadRecords();loadDigitalAddresses();loadBankAccounts()">{{ localeStore.text('重试','Retry') }}</button>
<div v-if="hasWithdrawChannel && showCurrencyModal" class="modal-overlay" @click="showCurrencyModal = false">
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
    <div v-if="hasWithdrawChannel && showAddressModal" class="modal-overlay" @click="showAddressModal = false">
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
    <div v-if="hasWithdrawChannel && showAccountModal" class="modal-overlay" @click="showAccountModal = false">
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
