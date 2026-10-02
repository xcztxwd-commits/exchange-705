<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { canStartBusiness } from '@/utils/tenantFeatures'
import ProtectedImage from '@/components/ProtectedImage.vue'
import { accountMode } from "@/utils/accountMode"
const simulation = accountMode() === "DEMO"
import { ref, onMounted } from 'vue'
import CurrencyPicker from '@/advanced/components/business/CurrencyPicker.vue'
import { useFiatCurrency } from '@/utils/fiatCurrency'
const { currency, rate, usdPreview } = useFiatCurrency()
import { useRouter } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useAuthStore } from '@/store/auth'
import { getImageUrl } from '@/utils/imageUrl'
import { useLocaleStore } from '@/store/locale'
import { formatDateTime } from '@/utils/dateTime'

const router = useRouter()
const auth = useAuthStore()
auth.load()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 充值方式：数字货币 / 银行卡
const depositType = ref<'digital' | 'bank'>('digital')

// 网络/币种选择
const showNetworkModal = ref(false)
const selectedNetwork = ref('')
const networks = ref<Array<{ label: string; value: string }>>([])
const loadingNetworks = ref(false)

// 充值地址和二维码
const depositAddress = ref('')
const qrCodeUrl = ref('')

// 银行卡信息
const bankInfo = ref<{
  hasBank: boolean
  bankName?: string
  bankAccount?: string
  accountName?: string
}>({ hasBank: false })
const loadingBankInfo = ref(false)

// 充值金额
const depositAmount = ref<number | null>(null)

// 上传凭证
const proofFile = ref<File | null>(null)
const proofPreview = ref<string>('')
const uploadedProof = ref('')
const uploading = ref(false)
const proofInputRef = ref<HTMLInputElement | null>(null)

// 入金记录
const loadingRecords = ref(false)
const depositRecords = ref<any[]>([])

// Toast 提示
const toastMessage = ref('')
const toastType = ref<'success' | 'error' | ''>('')

// 加载网络列表
async function loadNetworks() {
  loadingNetworks.value = true
  try {
    const res: any = await request.get('/deposit/settings/list', {
      params: { type: 'digital' }
    })
    
    if (res && res.success !== false && res.list) {
      networks.value = res.list.map((item: any) => ({
        label: item.network,
        value: item.network
      }))
      
      // 如果没有选中的网络，选择第一个
      if (networks.value.length > 0 && !selectedNetwork.value && networks.value[0]) {
        selectedNetwork.value = networks.value[0].value
        loadDepositSettings()
      }
    }
  } catch (e: any) {
    console.error('加载网络列表失败:', e)
  } finally {
    loadingNetworks.value = false
  }
}

// 加载银行卡信息
async function loadBankInfo() {
  loadingBankInfo.value = true
  try {
    const res: any = await request.get('/deposit/settings/bank')
    
    if (res && res.success !== false) {
      bankInfo.value = {
        hasBank: res.hasBank || false,
        bankName: res.bankName || '',
        bankAccount: res.bankAccount || '',
        accountName: res.accountName || ''
      }
    }
  } catch (e: any) {
    console.error('加载银行卡信息失败:', e)
    bankInfo.value = { hasBank: false }
  } finally {
    loadingBankInfo.value = false
  }
}

// 加载充值设置
async function loadDepositSettings() {
  if (!selectedNetwork.value) {
    return
  }
  
  try {
    const res: any = await request.get('/deposit/settings', {
      params: { network: selectedNetwork.value }
    })
    
    if (res && res.success !== false) {
      depositAddress.value = res.address || ''
      // 处理二维码URL，使用统一的 getImageUrl 函数
      qrCodeUrl.value = getImageUrl(res.qrCode || '')
      console.log('加载充值设置:', { 
        network: selectedNetwork.value,
        address: depositAddress.value, 
        qrCode: qrCodeUrl.value,
        rawQrCode: res.qrCode
      })
    } else {
      depositAddress.value = ''
      qrCodeUrl.value = ''
    }
  } catch (e: any) {
    console.error('加载充值设置失败:', e)
    showToast(localeStore.t('loadDepositSettingsFailed'), 'error')
  }
}

// 选择网络
function selectNetwork(network: string) {
  selectedNetwork.value = network
  showNetworkModal.value = false
  // 清空之前的地址和二维码
  depositAddress.value = ''
  qrCodeUrl.value = ''
  // 加载新的充值设置
  loadDepositSettings()
}

// 显示提示消息
function showToast(message: string, type: 'success' | 'error' = 'error') {
  toastMessage.value = message
  toastType.value = type
  setTimeout(() => {
    toastMessage.value = ''
    toastType.value = ''
  }, 3000)
}

// 复制地址
function copyAddress() {
  if (depositAddress.value) {
    navigator.clipboard.writeText(depositAddress.value).then(() => {
      showToast(localeStore.t('addressCopied'), 'success')
    }).catch(() => {
      showToast(localeStore.t('copyFailed'), 'error')
    })
  }
}

// 复制银行卡号
function copyBankAccount() {
  if (bankInfo.value.bankAccount) {
    navigator.clipboard.writeText(bankInfo.value.bankAccount).then(() => {
      showToast(localeStore.t('bankAccountCopied'), 'success')
    }).catch(() => {
      showToast(localeStore.t('copyFailed'), 'error')
    })
  }
}

// 联系客服
function contactService() {
  router.push('/customer-service')
}

// 选择文件
function handleFileSelect(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (file) {
    // 检查文件类型
    if (!file.type.startsWith('image/')) {
      showToast(localeStore.t('pleaseSelectImageFile'), 'error')
      return
    }
    
    // 检查文件大小（限制5MB）
    if (file.size > 5 * 1024 * 1024) {
      showToast(localeStore.t('imageSizeCannotExceed5MB'), 'error')
      return
    }
    
    uploadedProof.value = ''
    proofFile.value = file
    
    // 创建预览
    const reader = new FileReader()
    reader.onload = (e) => {
      proofPreview.value = e.target?.result as string
    }
    reader.readAsDataURL(file)
  }
}

// 触发文件选择
function triggerFileSelect() {
  proofInputRef.value?.click()
}

// 移除图片
function removeProof() {
  uploadedProof.value = ''
  proofFile.value = null
  proofPreview.value = ''
  if (proofInputRef.value) {
    proofInputRef.value.value = ''
  }
}

// 提交充值
async function submitDeposit() {
  if (uploading.value || advancedWriting.value || advancedError.value || loadingNetworks.value || loadingBankInfo.value) return
  if (rate.value === null) { showToast('汇率暂不可用，请稍后重试', 'error'); return }
  if (!depositAmount.value || depositAmount.value <= 0) {
    showToast(localeStore.t('pleaseEnterValidDepositAmount'), 'error')
    return
  }
  
  if (!simulation && !proofFile.value) {
    showToast(localeStore.t('pleaseUploadDepositProof'), 'error')
    return
  }
  
  uploading.value = true
  
  try {
    // Keep a successful upload stable when the creation response is unknown.
    let imageUrl = uploadedProof.value
    if (!imageUrl) {
      const formData = new FormData()
      if (proofFile.value) formData.append('file', proofFile.value)
      const uploadRes: any = simulation ? { url: 'SIMULATION-NO-PAYMENT' } : await request.post('/upload/image', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
      imageUrl = String(uploadRes.url || uploadRes.data?.url || '')
      uploadedProof.value = imageUrl
    }
    
    if (!imageUrl) {
      throw new Error(localeStore.t('imageUploadFailed'))
    }
    
    // 提交充值申请
    const depositData: any = {
      type: depositType.value,
      amount: depositAmount.value,
      currency: currency.value,
      proofImage: imageUrl,
    }
    
    if (depositType.value === 'digital') {
      depositData.network = selectedNetwork.value
      depositData.address = depositAddress.value
    } else {
      depositData.network = 'BANK'
      depositData.address = bankInfo.value.bankAccount || ''
    }
    
    const res: any = await request.post('/deposit/submit', depositData)
    
    if (res && res.success !== false) {
      showToast(localeStore.t('depositApplicationSubmitted'), 'success')
      // 清空表单
      depositAmount.value = null
      uploadedProof.value = ''
      proofFile.value = null
      proofPreview.value = ''
      if (proofInputRef.value) {
        proofInputRef.value.value = ''
      }
      // 刷新记录列表
      setTimeout(() => {
        loadRecords()
      }, 1000)
    } else {
      showToast(res.message || localeStore.t('submitFailedPleaseRetry'), 'error')
    }
  } catch (e: any) {
    console.error('提交充值失敗:', e)
    showToast(e.response?.data?.message || e.message || localeStore.t('submitFailedPleaseRetry'), 'error')
  } finally {
    uploading.value = false
  }
}

// 切换充值方式（未使用，保留以备后用）
// function switchDepositType(type: 'digital' | 'bank') {
//   depositType.value = type
//   if (type === 'digital') {
//     loadNetworks()
//   } else {
//     loadBankInfo()
//   }
// }

// 格式化金额
function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toFixed(2).replace(/\.?0+$/, '')
}

// 获取状态文本
function getStatusText(status: string) {
  if (status === 'PENDING') return localeStore.t('statusPending')
  if (status === 'COMPLETED') return localeStore.t('statusCompleted')
  if (status === 'REJECTED') return localeStore.t('statusRejected')
  return status
}

// 加载入金记录
async function loadRecords() {
  loadingRecords.value = true
  try {
    const res: any = await request.get('/deposit/records')
    if (res && res.success !== false) {
      depositRecords.value = res.list || res.data || []
    }
  } catch (e: any) {
    console.error('加载入金记录失败:', e)
  } finally {
    loadingRecords.value = false
  }
}

onMounted(() => {
  loadNetworks()
  loadBankInfo()
  loadRecords()
})
</script>
<template>
<BusinessPage :title="localeStore.t('deposit')" :error="advancedError" :busy="advancedWriting">

<div class="tabs"><button :class="{active:depositType==='digital'}" :disabled="uploading" @click="depositType='digital'">{{ localeStore.t('depositTypeDigital') }}</button><button :class="{active:depositType==='bank'}" :disabled="uploading" @click="depositType='bank'">{{ localeStore.t('depositTypeBank') }}</button></div><p class="notice">{{ simulation ? localeStore.text('模拟入金，不涉及真实付款','Virtual deposit; no real payment') : localeStore.text('入金网络须与转出网络一致，确认实际收款信息后付款','Match the sending network and confirm actual payment details before sending funds') }}</p>
<section v-if="depositType==='digital'" class="card"><h2>{{ localeStore.text('充值地址','Deposit address') }}</h2><div class="row"><span>{{ localeStore.t('network') }}</span><button class="text-button" @click="showNetworkModal=true">{{ loadingNetworks ? localeStore.t('loading') : selectedNetwork || localeStore.text('选择网络','Select network') }} ⌄</button></div><div v-if="qrCodeUrl" class="upload"><ProtectedImage :src="qrCodeUrl" :alt="localeStore.text('充值地址二维码','Deposit address QR code')" style="width:180px;height:180px" /></div><p v-else class="qr-placeholder">{{ localeStore.text('二维码以配置地址为准','QR code follows the configured address') }}</p><p style="overflow-wrap:anywhere">{{ depositAddress || '—' }}</p><button :disabled="!depositAddress" @click="copyAddress">{{ localeStore.t('copyAddress') }}</button></section>
<section v-else class="card"><h2>{{ localeStore.text('银行收款信息','Bank payment details') }}</h2><p v-if="loadingBankInfo">{{ localeStore.t('loading') }}</p><template v-else-if="bankInfo.hasBank"><div class="row"><span>{{ localeStore.t('bankName') }}</span><span>{{ bankInfo.bankName }}</span></div><div class="row"><span>{{ localeStore.t('recipientAccount') }}</span><span>{{ bankInfo.bankAccount }}</span></div><div class="row"><span>{{ localeStore.t('recipientName') }}</span><span>{{ bankInfo.accountName }}</span></div><button @click="copyBankAccount">{{ localeStore.text('复制银行账号','Copy bank account') }}</button></template><template v-else><p>{{ localeStore.text('银行收款信息尚未配置','Bank payment details are not configured') }}</p><button @click="contactService">{{ localeStore.t('customerService') }}</button></template></section>
<section class="card"><h2>{{ localeStore.text('入金信息','Deposit details') }}</h2><label class="field">{{ localeStore.t('depositAmountLabel') }}<input v-model.number="depositAmount" type="number" min="0" step="0.01" :placeholder="localeStore.t('enterAmount')" /></label><div class="row"><span>{{ localeStore.t('currency') }}</span><CurrencyPicker v-model="currency" /></div><div class="row"><span>{{ localeStore.text('折合 USD','USD equivalent') }}</span><span>{{ depositAmount ? usdPreview(depositAmount) : '—' }}</span></div><input ref="proofInputRef" type="file" accept="image/*" hidden @change="handleFileSelect" /><button v-if="!proofPreview" class="upload" @click="triggerFileSelect"><img src="@/advanced/assets/business/upload.svg" alt="" width="24" height="24" />{{ localeStore.t('pleaseUploadDepositProof') }}</button><div v-else class="upload"><img :src="proofPreview" :alt="localeStore.t('pleaseUploadDepositProof')" style="max-height:160px" /><button @click="removeProof">{{ localeStore.t('delete') }}</button></div></section>
<button v-if="canStartBusiness('deposit')" class="primary" :disabled="uploading || loadingNetworks || loadingBankInfo || advancedError!=='' || rate===null || (depositType==='bank' && !bankInfo.hasBank) || (depositType==='digital' && (!selectedNetwork || !depositAddress))" @click="submitDeposit">{{ uploading ? localeStore.t('submitting') : localeStore.text('提交入金','Submit deposit') }}</button>
<div class="row"><h2>{{ localeStore.text('最近记录','Recent records') }}</h2><button class="text-button" @click="router.push('/deposit/records')">{{ localeStore.text('全部','All') }} ›</button></div><p v-if="loadingRecords" class="empty">{{ localeStore.t('loading') }}</p><p v-else-if="!depositRecords.length" class="empty">{{ localeStore.t('noRecords') }}</p><div v-for="r in depositRecords.slice(0,3)" :key="r.id" class="card"><div class="row"><span>{{ formatMoney(r.amount) }} USD</span><span>{{ getStatusText(r.status) }}</span></div><p class="muted">{{ formatDateTime(r.createdAt) }} · {{ r.network }}</p></div><button v-if="advancedError" @click="advancedError='';loadNetworks();loadBankInfo();loadRecords()">{{ localeStore.text('重试','Retry') }}</button>
<div v-if="showNetworkModal" class="modal-overlay" @click="showNetworkModal = false">
      <div class="modal-content" @click.stop>
        <div class="modal-header">
          <button class="modal-cancel" @click="showNetworkModal = false">{{ localeStore.t('cancel') }}</button>
          <div class="modal-title">{{ localeStore.t('selectNetworkTitle') }}</div>
          <button class="modal-confirm" @click="showNetworkModal = false">{{ localeStore.t('confirm') }}</button>
        </div>
        <div class="modal-body">
          <div 
            v-for="network in networks" 
            :key="network.value"
            class="network-option"
            :class="{ active: selectedNetwork === network.value }"
            @click="selectNetwork(network.value)"
          >
            {{ network.label }}
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

.qr-placeholder{background:#f5f6f7;color:#707780;font-size:12px;padding:18px;text-align:center}.network-option{padding:14px;cursor:pointer;border-bottom:1px solid #e9edef}.network-option.active{background:#f5f2f7;color:#736582}
</style>
