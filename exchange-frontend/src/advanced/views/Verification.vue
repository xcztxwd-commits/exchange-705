<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import ProtectedImage from '@/components/ProtectedImage.vue'
import { ref, onMounted } from 'vue'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

const auth = useAuthStore()
auth.load()

const localeStore = useLocaleStore()
localeStore.loadLocale()

// 表单数据
const formData = ref({
  realName: '',
  idNumber: '',
})

// 图片上传
const frontImageFile = ref<File | null>(null)
const frontImagePreview = ref<string>('')
const backImageFile = ref<File | null>(null)
const backImagePreview = ref<string>('')
const frontImageInputRef = ref<HTMLInputElement | null>(null)
const backImageInputRef = ref<HTMLInputElement | null>(null)

// 状态
const kycStatus = ref<string>('')
const latestRecord = ref<any>(null)
const submitting = ref(false)

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

// 加载实名认证状态
async function loadKycStatus() {
  try {
    const res: any = await request.get('/kyc/status')
    if (res) {
      kycStatus.value = res.kycStatus || 'NOT_VERIFIED'
      if (res.latestRecord) {
        latestRecord.value = res.latestRecord
      }
    }
  } catch (e: any) {
    console.error('加载实名认证状态失败:', e)
  }
}

// 选择正面图片
function handleFrontImageSelect(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  
  // 检查文件类型
  if (!file.type.startsWith('image/')) {
    showToast(localeStore.t('onlyImageFiles'), 'error')
    return
  }
  
  // 检查文件大小（5MB）
  if (file.size > 5 * 1024 * 1024) {
    showToast(localeStore.t('imageSizeExceeded'), 'error')
    return
  }
  
  frontImageFile.value = file
  
  // 创建预览
  const reader = new FileReader()
  reader.onload = (e) => {
    frontImagePreview.value = e.target?.result as string
  }
  reader.readAsDataURL(file)
}

// 选择反面图片
function handleBackImageSelect(e: Event) {
  const target = e.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  
  // 检查文件类型
  if (!file.type.startsWith('image/')) {
    showToast(localeStore.t('onlyImageFiles'), 'error')
    return
  }
  
  // 检查文件大小（5MB）
  if (file.size > 5 * 1024 * 1024) {
    showToast(localeStore.t('imageSizeExceeded'), 'error')
    return
  }
  
  backImageFile.value = file
  
  // 创建预览
  const reader = new FileReader()
  reader.onload = (e) => {
    backImagePreview.value = e.target?.result as string
  }
  reader.readAsDataURL(file)
}

// 触发文件选择
function triggerFrontImageSelect() {
  frontImageInputRef.value?.click()
}

function triggerBackImageSelect() {
  backImageInputRef.value?.click()
}

// 重置表单
function resetForm() {
  formData.value = {
    realName: '',
    idNumber: '',
  }
  frontImageFile.value = null
  frontImagePreview.value = ''
  backImageFile.value = null
  backImagePreview.value = ''
  if (frontImageInputRef.value) {
    frontImageInputRef.value.value = ''
  }
  if (backImageInputRef.value) {
    backImageInputRef.value.value = ''
  }
  latestRecord.value = null
}

// 提交实名认证
async function submitKyc() {
  // 验证表单
  if (!formData.value.realName || !formData.value.realName.trim()) {
    showToast(localeStore.t('enterName'), 'error')
    return
  }
  
  if (!formData.value.idNumber || !formData.value.idNumber.trim()) {
    showToast(localeStore.t('enterPassportOrIdNumber'), 'error')
    return
  }
  
  if (!frontImageFile.value) {
    showToast(localeStore.t('uploadPassportOrIdFront'), 'error')
    return
  }
  
  if (!backImageFile.value) {
    showToast(localeStore.t('uploadPassportOrIdBack'), 'error')
    return
  }
  
  submitting.value = true
  
  try {
    // 创建 FormData
    const formDataToSend = new FormData()
    formDataToSend.append('realName', formData.value.realName.trim())
    formDataToSend.append('idNumber', formData.value.idNumber.trim())
    formDataToSend.append('idFrontImage', frontImageFile.value)
    formDataToSend.append('idBackImage', backImageFile.value)
    
    const res: any = await request.post('/kyc/submit', formDataToSend, {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    })
    
    if (res && res.success !== false) {
      showToast(localeStore.t('submitSuccessPendingReview'), 'success')
      setTimeout(() => {
        loadKycStatus()
      }, 1500)
    } else {
      showToast(res.message || localeStore.t('submitFailed'), 'error')
    }
  } catch (e: any) {
    console.error('提交实名认证失败:', e)
    const errorMessage = e.response?.data?.message || e.message || ''
    
    // 识别后端返回的特定错误消息，使用对应的多语言键
    let displayMessage = errorMessage
    if (errorMessage === '您已有审核中的申请，请等待审核') {
      displayMessage = localeStore.t('kycApplicationPending')
    } else if (errorMessage === '您已完成实名认证') {
      displayMessage = localeStore.t('kycAlreadyCompleted')
    } else if (!errorMessage) {
      displayMessage = localeStore.t('submitFailed')
    }
    
    showToast(displayMessage, 'error')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadKycStatus()
})
</script>

<template>
<BusinessPage :title="localeStore.t('verification')" :error="advancedError" :busy="advancedWriting">
 <p class="notice" role="status">{{ localeStore.text('认证状态','Verification status') }}: {{ advancedError || !kycStatus ? localeStore.text('未知','Unknown') : kycStatus==='VERIFIED' ? localeStore.t('verificationCompleted') : latestRecord?.status==='PENDING' ? localeStore.t('verificationPending') : latestRecord?.status==='REJECTED' ? localeStore.t('verificationRejected') : localeStore.text('未提交','Not submitted') }}</p>
 <section v-if="kycStatus==='VERIFIED'" class="card"><h2>{{ localeStore.t('verificationCompleted') }}</h2><template v-if="latestRecord"><div class="row"><span>{{ localeStore.t('fullName') }}</span><span>{{ latestRecord.realName }}</span></div><div class="row"><span>{{ localeStore.t('idNumberLabel') }}</span><span>{{ latestRecord.idNumber }}</span></div></template></section>
 <section v-if="latestRecord?.status==='REJECTED'" class="card"><p>{{ latestRecord.reviewRemark }}</p><button @click="resetForm">{{ localeStore.t('resubmit') }}</button></section>
 <template v-if="kycStatus && kycStatus!=='VERIFIED' && latestRecord?.status!=='PENDING'">
  <section class="card"><h2>{{ localeStore.text('实名信息','Identity details') }}</h2><label class="field">{{ localeStore.t('fullName') }}<input v-model="formData.realName" autocomplete="name" :placeholder="localeStore.t('enterName')" /></label><label class="field">{{ localeStore.t('passportOrIdNumber') }}<input v-model="formData.idNumber" :placeholder="localeStore.t('enterPassportOrIdNumber')" /></label></section>
  <section class="card"><h2>{{ localeStore.text('身份证照片','Identity photos') }}</h2><button class="upload" @click="triggerFrontImageSelect"><ProtectedImage v-if="frontImagePreview" :src="frontImagePreview" :alt="localeStore.t('uploadPassportOrIdFront')" style="max-height:160px" /><img v-else src="@/advanced/assets/business/upload.svg" alt="" />{{ localeStore.t('uploadPassportOrIdFront') }}</button><input ref="frontImageInputRef" type="file" accept="image/*" hidden @change="handleFrontImageSelect" /><button class="upload" @click="triggerBackImageSelect"><ProtectedImage v-if="backImagePreview" :src="backImagePreview" :alt="localeStore.t('uploadPassportOrIdBack')" style="max-height:160px" /><img v-else src="@/advanced/assets/business/upload.svg" alt="" />{{ localeStore.t('uploadPassportOrIdBack') }}</button><input ref="backImageInputRef" type="file" accept="image/*" hidden @change="handleBackImageSelect" /><p class="muted">{{ localeStore.text('图片不超过 5 MB，沿用原有安全校验','Images up to 5 MB; existing security checks apply') }}</p></section>
  <button class="primary" :disabled="submitting || advancedWriting || !!advancedError" @click="submitKyc">{{ submitting ? localeStore.t('submitting') : localeStore.text('提交认证','Submit verification') }}</button>
 </template><button v-if="advancedError" @click="loadKycStatus">{{ localeStore.text('重试','Retry') }}</button><div v-if="toastMessage" class="toast-message" :class="toastType" role="status">{{ toastMessage }}</div>
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
