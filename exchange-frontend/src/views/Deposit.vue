<script setup lang="ts">
import { ref, onMounted } from 'vue'
import CurrencyPicker from '@/components/CurrencyPicker.vue'
import { useFiatCurrency } from '@/utils/fiatCurrency'
const { currency, rate, usdPreview } = useFiatCurrency()
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
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
  showToast(localeStore.t('pleaseContactUsThroughService'), 'success')
  // 这里可以跳转到客服页面或打开客服对话框
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
  proofFile.value = null
  proofPreview.value = ''
  if (proofInputRef.value) {
    proofInputRef.value.value = ''
  }
}

// 提交充值
async function submitDeposit() {
  if (rate.value === null) { showToast('汇率暂不可用，请稍后重试', 'error'); return }
  if (!depositAmount.value || depositAmount.value <= 0) {
    showToast(localeStore.t('pleaseEnterValidDepositAmount'), 'error')
    return
  }
  
  if (!proofFile.value) {
    showToast(localeStore.t('pleaseUploadDepositProof'), 'error')
    return
  }
  
  uploading.value = true
  
  try {
    // 先上传图片
    const formData = new FormData()
    formData.append('file', proofFile.value)
    
    const uploadRes: any = await request.post('/upload/image', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
    
    const imageUrl = uploadRes.url || uploadRes.data?.url
    
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
  <div class="deposit-page">
    <!-- 顶部导航 -->
    <div class="deposit-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('deposit') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <!-- 充值方式选择 -->
    <div class="deposit-type-tabs">
      <div 
        class="deposit-tab" 
        :class="{ active: depositType === 'digital' }"
        @click="depositType = 'digital'"
      >
        {{ localeStore.t('digitalCurrencyLabel') }}
      </div>
      <div 
        class="deposit-tab" 
        :class="{ active: depositType === 'bank' }"
        @click="depositType = 'bank'"
      >
        {{ localeStore.t('bankCardLabel') }}
      </div>
    </div>

    <!-- 数字货币充值 -->
    <div v-if="depositType === 'digital'" class="deposit-content">
      <!-- 网络选择 -->
      <div class="form-section">
        <div class="form-label">{{ localeStore.t('selectNetwork') }}</div>
        <div class="network-selector" @click="showNetworkModal = true" v-if="!loadingNetworks">
          <span>{{ selectedNetwork || localeStore.t('pleaseSelectNetwork') }}</span>
          <span class="arrow">›</span>
        </div>
        <div class="network-selector" v-else>
          <span>{{ localeStore.t('loading') }}</span>
        </div>
      </div>

      <!-- 二维码和地址 -->
      <div class="qr-section">
        <div class="qr-code" v-if="qrCodeUrl">
          <img :src="qrCodeUrl" alt="QR Code" />
        </div>
        <div class="qr-code-placeholder" v-else>
          <svg class="placeholder-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect x="4" y="4" width="16" height="16" rx="2" stroke="#999" stroke-width="1.5" fill="none"/>
            <rect x="7" y="7" width="3" height="3" fill="#999"/>
            <rect x="14" y="7" width="3" height="3" fill="#999"/>
            <rect x="7" y="14" width="3" height="3" fill="#999"/>
            <rect x="14" y="14" width="3" height="3" fill="#999"/>
            <rect x="11" y="11" width="2" height="2" fill="#999"/>
            <line x1="11" y1="7" x2="11" y2="11" stroke="#999" stroke-width="1.5"/>
            <line x1="7" y1="11" x2="11" y2="11" stroke="#999" stroke-width="1.5"/>
            <line x1="13" y1="11" x2="17" y2="11" stroke="#999" stroke-width="1.5"/>
            <line x1="11" y1="13" x2="11" y2="17" stroke="#999" stroke-width="1.5"/>
            <line x1="7" y1="13" x2="7" y2="17" stroke="#999" stroke-width="1.5"/>
            <line x1="17" y1="13" x2="17" y2="17" stroke="#999" stroke-width="1.5"/>
          </svg>
        </div>
        <div class="address-section">
          <div class="address-text">{{ depositAddress || localeStore.t('loading') }}</div>
          <button class="copy-button" @click="copyAddress" :disabled="!depositAddress">
            {{ localeStore.t('copyAddress') }}
          </button>
        </div>
      </div>

      <!-- 充值金额 -->
      <div class="form-section">
        <div class="form-label">{{ localeStore.t('depositAmount') }}</div>
        <CurrencyPicker v-model="currency" />
        <p aria-live="polite">{{ usdPreview(depositAmount) }}</p>
        <input 
          type="number" 
          v-model.number="depositAmount" 
          class="amount-input" 
          :placeholder="localeStore.t('enterDepositAmount')"
          step="0.01"
          min="0"
        />
      </div>

      <!-- 上传凭证 -->
      <div class="form-section">
        <div class="form-label">{{ localeStore.t('uploadProof') }}</div>
        <div class="upload-section">
          <input 
            ref="proofInputRef"
            type="file" 
            accept="image/*" 
            @change="handleFileSelect"
            style="display: none;"
          />
          <div 
            v-if="!proofPreview" 
            class="upload-placeholder"
            @click="triggerFileSelect"
          >
            <svg class="upload-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect x="3" y="3" width="18" height="18" rx="2" stroke="#999" stroke-width="1.5" fill="none"/>
              <circle cx="8.5" cy="8.5" r="1.5" fill="#999"/>
              <path d="M21 15l-5-5L5 15" stroke="#999" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
            </svg>
            <div class="upload-text">{{ localeStore.t('clickToUpload') }}</div>
          </div>
          <div v-else class="upload-preview">
            <img :src="proofPreview" alt="Proof" />
            <button class="remove-button" @click="removeProof">×</button>
          </div>
        </div>
      </div>

      <!-- 提交按钮 -->
      <button 
        class="submit-button" 
        @click="submitDeposit"
        :disabled="uploading || rate === null || !depositAmount || !proofFile"
      >
        {{ uploading ? localeStore.t('submitting') : localeStore.t('submit') }}
      </button>
    </div>

    <!-- 银行卡充值 -->
    <div v-else class="deposit-content">
      <!-- 如果后台有设置银行卡，显示银行卡信息 -->
      <div v-if="bankInfo.hasBank && !loadingBankInfo" class="bank-info-section">
        <div class="form-section">
          <div class="form-label">{{ localeStore.t('bankName') }}</div>
          <div class="bank-info-item">{{ bankInfo.bankName }}</div>
        </div>
        
        <div class="form-section">
          <div class="form-label">{{ localeStore.t('bankAccount') }}</div>
          <div class="bank-info-item">
            {{ bankInfo.bankAccount }}
            <button class="copy-button-small" @click="copyBankAccount">
              <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" width="16" height="16">
                <path d="M16 1H4C2.9 1 2 1.9 2 3V17H4V3H16V1ZM19 5H8C6.9 5 6 5.9 6 7V21C6 22.1 6.9 23 8 23H19C20.1 23 21 22.1 21 21V7C21 5.9 20.1 5 19 5ZM19 21H8V7H19V21Z" fill="#73b100"/>
              </svg>
            </button>
          </div>
        </div>
        
        <div class="form-section">
          <div class="form-label">{{ localeStore.t('accountName') }}</div>
          <div class="bank-info-item">{{ bankInfo.accountName }}</div>
        </div>
        
        <!-- 充值金额 -->
        <div class="form-section">
          <div class="form-label">{{ localeStore.t('depositAmountLabel') }}</div>
        <CurrencyPicker v-model="currency" />
        <p aria-live="polite">{{ usdPreview(depositAmount) }}</p>
          <input 
            type="number" 
            v-model.number="depositAmount" 
            class="amount-input" 
            :placeholder="localeStore.t('enterDepositAmount')"
            step="0.01"
            min="0"
          />
        </div>
        
        <!-- 上传凭证 -->
        <div class="form-section">
          <div class="form-label">{{ localeStore.t('uploadProof') }}</div>
          <div class="upload-section">
            <input 
              ref="proofInputRef"
              type="file" 
              accept="image/*" 
              @change="handleFileSelect"
              style="display: none;"
            />
            <div 
              v-if="!proofPreview" 
              class="upload-placeholder"
              @click="triggerFileSelect"
            >
              <svg class="upload-icon" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <rect x="3" y="3" width="18" height="18" rx="2" stroke="#999" stroke-width="1.5" fill="none"/>
                <circle cx="8.5" cy="8.5" r="1.5" fill="#999"/>
                <path d="M21 15l-5-5L5 15" stroke="#999" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
              </svg>
              <div class="upload-text">{{ localeStore.t('clickToUpload') }}</div>
            </div>
            <div v-else class="upload-preview">
              <img :src="proofPreview" alt="Proof" />
              <button class="remove-button" @click="removeProof">×</button>
            </div>
          </div>
        </div>
        
        <!-- 提交按钮 -->
        <button 
          class="submit-button" 
          @click="submitDeposit"
          :disabled="uploading || rate === null || !depositAmount || !proofFile"
        >
          {{ uploading ? localeStore.t('submitting') : localeStore.t('submit') }}
        </button>
      </div>
      
      <!-- 如果后台没有设置银行卡，显示提示信息 -->
      <div v-else-if="!loadingBankInfo" class="bank-no-setting">
        <div class="no-setting-icon">🏦</div>
        <div class="no-setting-text">{{ localeStore.t('contactServiceForBankCard') }}</div>
        <button class="contact-button" @click="contactService">{{ localeStore.t('contactService') }}</button>
      </div>
      
      <!-- 加载中 -->
      <div v-else class="loading-bank">
        <div class="loading-text">{{ localeStore.t('loading') }}</div>
      </div>
    </div>

    <!-- 入金记录 -->
    <div class="deposit-records-section">
      <div class="records-title">{{ localeStore.t('depositRecords') }}</div>
      <div v-if="loadingRecords" class="loading-records">
        <div class="loading-text">{{ localeStore.t('loading') }}</div>
      </div>
      <div v-else-if="depositRecords.length === 0" class="empty-records">
        <div class="empty-text">{{ localeStore.t('noRecords') }}</div>
      </div>
      <div v-else class="records-list">
        <div 
          v-for="record in depositRecords" 
          :key="record.id"
          class="record-item"
        >
          <div class="record-row">
            <div class="record-label">{{ localeStore.t('depositAmountLabel') }}</div>
            <div class="record-value">{{ formatMoney(record.originalAmount ?? record.amount) }} {{ record.currency || 'USD' }} · {{ formatMoney(record.amount) }} USD</div>
          </div>
          <div class="record-row">
            <div class="record-label">{{ localeStore.t('status') }}</div>
            <div class="record-value" :class="{
              'status-pending': record.status === 'PENDING',
              'status-completed': record.status === 'COMPLETED',
              'status-rejected': record.status === 'REJECTED'
            }">
              {{ getStatusText(record.status) }}
            </div>
          </div>
          <div class="record-row" v-if="record.remark">
            <div class="record-label">{{ localeStore.t('remark') }}</div>
            <div class="record-value">{{ record.remark || '-' }}</div>
          </div>
          <div class="record-row">
            <div class="record-label">{{ localeStore.t('depositType') }}</div>
            <div class="record-value">{{ record.type === 'digital' ? localeStore.t('depositTypeDigital') : localeStore.t('depositTypeBank') }}</div>
          </div>
          <div class="record-row">
            <div class="record-label">{{ localeStore.t('unit') }}</div>
            <div class="record-value">{{ record.network || record.unit || '-' }}</div>
          </div>
          <div class="record-row">
            <div class="record-label">{{ localeStore.t('time') }}</div>
            <div class="record-value">{{ formatDateTime(record.createdAt || record.createTime) }}</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 网络选择弹窗 -->
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
    </div>

    <!-- Toast 提示消息 -->
    <div v-if="toastMessage" class="toast-message" :class="toastType">
      {{ toastMessage }}
    </div>

    <Tabbar />
  </div>
</template>

<style scoped>
.deposit-page {
  min-height: 100vh;
  background: #f5f7fb;
  padding-bottom: 80px;
}

/* 顶部导航 */
.deposit-header {
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

.header-placeholder {
  width: 40px;
}

/* 充值方式选择 */
.deposit-type-tabs {
  display: flex;
  background: #fff;
  padding: 8px;
  margin: 12px 16px;
  border-radius: 8px;
  gap: 8px;
}

.deposit-tab {
  flex: 1;
  text-align: center;
  padding: 10px;
  border-radius: 6px;
  background: #f0f0f0;
  color: #666;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.3s;
}

.deposit-tab.active {
  background: #73b100;
  color: #fff;
}

/* 充值内容 */
.deposit-content {
  background: #fff;
  margin: 16px;
  padding: 20px;
  border-radius: 12px;
}

.form-section {
  margin-bottom: 20px;
}

.form-label {
  font-size: 14px;
  color: #666;
  margin-bottom: 8px;
}

.network-selector {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  background: #f5f7fb;
  border-radius: 8px;
  cursor: pointer;
}

.network-selector .arrow {
  color: #999;
  font-size: 20px;
}

/* 二维码和地址 */
.qr-section {
  text-align: center;
  margin: 24px 0;
}

.qr-code {
  width: 200px;
  height: 200px;
  margin: 0 auto 16px;
  background: #fff;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.qr-code img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.qr-code-placeholder {
  width: 200px;
  height: 200px;
  margin: 0 auto 16px;
  background: #f5f7fb;
  border: 1px dashed #ddd;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.placeholder-icon {
  width: 48px;
  height: 48px;
  color: #999;
}

.address-section {
  margin-top: 16px;
}

.address-text {
  font-size: 12px;
  color: #333;
  word-break: break-all;
  margin-bottom: 12px;
  padding: 8px;
  background: #f5f7fb;
  border-radius: 6px;
}

.copy-button {
  padding: 8px 24px;
  background: #73b100;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
}

.copy-button:disabled {
  background: #ccc;
  cursor: not-allowed;
}

/* 金额输入 */
.amount-input {
  width: 100%;
  padding: 12px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 16px;
}

/* 上传凭证 */
.upload-section {
  margin-top: 8px;
}

.upload-placeholder {
  width: 120px;
  height: 120px;
  border: 2px dashed #ddd;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  background: #fafafa;
}

.upload-icon {
  width: 32px;
  height: 32px;
  margin-bottom: 8px;
  color: #999;
}

.upload-text {
  font-size: 12px;
  color: #999;
}

.upload-preview {
  position: relative;
  width: 120px;
  height: 120px;
  border-radius: 8px;
  overflow: hidden;
}

.upload-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.remove-button {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 24px;
  height: 24px;
  background: rgba(0, 0, 0, 0.6);
  color: #fff;
  border: none;
  border-radius: 50%;
  font-size: 18px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 提交按钮 */
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
  margin-top: 24px;
}

.submit-button:disabled {
  background: #ccc;
  cursor: not-allowed;
}

/* 银行卡信息 */
.bank-info-section {
  padding: 0;
}

.bank-info-item {
  padding: 12px;
  background: #f5f7fb;
  border-radius: 8px;
  font-size: 14px;
  color: #333;
  display: flex;
  align-items: center;
  justify-content: space-between;
  word-break: break-all;
}

.copy-button-small {
  background: none;
  border: none;
  padding: 4px;
  cursor: pointer;
  display: flex;
  align-items: center;
  margin-left: 8px;
  flex-shrink: 0;
}

.bank-no-setting {
  text-align: center;
  padding: 60px 20px;
}

.no-setting-icon {
  font-size: 64px;
  margin-bottom: 20px;
}

.no-setting-text {
  font-size: 16px;
  color: #666;
  margin-bottom: 30px;
  line-height: 1.6;
}

.contact-button {
  padding: 12px 32px;
  background: #73b100;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}

.contact-button:active {
  opacity: 0.8;
}

.loading-bank {
  text-align: center;
  padding: 40px;
}

.loading-text {
  color: #999;
  font-size: 14px;
}

/* 入金记录 */
.deposit-records-section {
  margin: 16px;
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
  padding: 40px;
  text-align: center;
}

.loading-text,
.empty-text {
  color: #999;
  font-size: 14px;
}

.records-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.record-item {
  padding: 16px;
  background: #f8f8f8;
  border-radius: 8px;
}

.record-row {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
}

.record-row:last-child {
  margin-bottom: 0;
}

.record-label {
  font-size: 14px;
  color: #666;
}

.record-value {
  font-size: 14px;
  color: #333;
  font-weight: 500;
  text-align: right;
  flex: 1;
  margin-left: 16px;
}

.status-pending {
  color: #ff9800;
}

.status-completed {
  color: #73b100;
}

.status-rejected {
  color: #f56c6c;
}

/* 网络选择弹窗 */
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
  border-radius: 16px 16px 0 0;
  max-height: 60vh;
  overflow-y: auto;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid #f0f0f0;
}

.modal-cancel {
  background: none;
  border: none;
  color: #666;
  font-size: 14px;
  cursor: pointer;
}

.modal-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.modal-confirm {
  background: none;
  border: none;
  color: #73b100;
  font-size: 14px;
  cursor: pointer;
}

.modal-body {
  padding: 8px 0;
}

.network-option {
  padding: 16px;
  font-size: 14px;
  color: #666;
  cursor: pointer;
}

.network-option.active {
  color: #333;
  font-weight: 600;
  background: #f5f7fb;
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

