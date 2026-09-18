<template>
  <div class="verification-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('verification') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="verification-content">
      <!-- 已认证状态 -->
      <div v-if="kycStatus === 'VERIFIED'" class="verified-status">
        <div class="status-icon">✓</div>
        <div class="status-text">{{ localeStore.t('verificationCompleted') }}</div>
        <div v-if="latestRecord" class="status-info">
          <div>{{ localeStore.t('fullName') }}：{{ latestRecord.realName }}</div>
          <div>{{ localeStore.t('idNumberLabel') }}：{{ latestRecord.idNumber }}</div>
        </div>
      </div>

      <!-- 待审核状态 -->
      <div v-else-if="latestRecord && latestRecord.status === 'PENDING'" class="pending-status">
        <div class="status-icon">⏳</div>
        <div class="status-text">{{ localeStore.t('verificationPending') }}</div>
      </div>

      <!-- 审核拒绝状态 -->
      <div v-else-if="latestRecord && latestRecord.status === 'REJECTED'" class="rejected-status">
        <div class="status-icon">✗</div>
        <div class="status-text">{{ localeStore.t('verificationRejected') }}</div>
        <div v-if="latestRecord.reviewRemark" class="reject-reason">
          {{ localeStore.t('rejectReason') }}：{{ latestRecord.reviewRemark }}
        </div>
        <button class="retry-button" @click="resetForm">{{ localeStore.t('resubmit') }}</button>
      </div>

      <!-- 认证表单 -->
      <div v-if="!kycStatus || kycStatus === 'NOT_VERIFIED' || (latestRecord && latestRecord.status === 'REJECTED')" class="verification-form">
        <div class="form-item">
          <div class="form-label">{{ localeStore.t('fullName') }}</div>
          <input 
            type="text" 
            v-model="formData.realName" 
            class="form-input-text" 
            :placeholder="localeStore.t('enterName')"
          />
        </div>

        <div class="form-item">
          <div class="form-label">{{ localeStore.t('passportOrIdNumber') }}</div>
          <input 
            type="text" 
            v-model="formData.idNumber" 
            class="form-input-text" 
            :placeholder="localeStore.t('enterPassportOrIdNumber')"
          />
        </div>

        <div class="upload-section">
          <div class="upload-title">{{ localeStore.t('uploadPassportOrIdBothSides') }}</div>
          
          <!-- 正面 -->
          <div class="upload-item">
            <div 
              class="upload-area"
              :class="{ 'has-image': frontImagePreview }"
              @click="triggerFrontImageSelect"
            >
              <img 
                v-if="frontImagePreview" 
                :src="frontImagePreview" 
                class="upload-preview"
                :alt="localeStore.t('uploadPassportOrIdFront')"
              />
              <img 
                v-else
                src="/img/id-front7ede1e14.png" 
                class="upload-placeholder"
                :alt="localeStore.t('uploadPassportOrIdFront')"
              />
              <div v-if="!frontImagePreview" class="upload-plus">+</div>
            </div>
            <div class="upload-label">{{ localeStore.t('uploadPassportOrIdFront') }}</div>
            <input 
              ref="frontImageInputRef"
              type="file" 
              accept="image/*" 
              style="display: none"
              @change="handleFrontImageSelect"
            />
          </div>

          <!-- 反面 -->
          <div class="upload-item">
            <div 
              class="upload-area"
              :class="{ 'has-image': backImagePreview }"
              @click="triggerBackImageSelect"
            >
              <img 
                v-if="backImagePreview" 
                :src="backImagePreview" 
                class="upload-preview"
                :alt="localeStore.t('uploadPassportOrIdBack')"
              />
              <img 
                v-else
                src="/img/id-backgroundc00f8b70.png" 
                class="upload-placeholder"
                :alt="localeStore.t('uploadPassportOrIdBack')"
              />
              <div v-if="!backImagePreview" class="upload-plus">+</div>
            </div>
            <div class="upload-label">{{ localeStore.t('uploadPassportOrIdBack') }}</div>
            <input 
              ref="backImageInputRef"
              type="file" 
              accept="image/*" 
              style="display: none"
              @change="handleBackImageSelect"
            />
          </div>
        </div>

        <button class="submit-button" @click="submitKyc" :disabled="submitting">
          {{ submitting ? localeStore.t('submitting') : localeStore.t('confirm') }}
        </button>
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

<style scoped>
.verification-page {
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
.verification-content {
  padding: 16px;
}

/* 状态显示 */
.verified-status,
.pending-status,
.rejected-status {
  background: #fff;
  border-radius: 12px;
  padding: 40px 20px;
  text-align: center;
  margin-bottom: 16px;
}

.status-icon {
  font-size: 48px;
  margin-bottom: 16px;
}

.verified-status .status-icon {
  color: #73b100;
}

.pending-status .status-icon {
  color: #ffa500;
}

.rejected-status .status-icon {
  color: #ff4444;
}

.status-text {
  font-size: 16px;
  color: #333;
  margin-bottom: 12px;
}

.status-info {
  font-size: 14px;
  color: #666;
  margin-top: 16px;
}

.status-info div {
  margin-bottom: 8px;
}

.reject-reason {
  font-size: 14px;
  color: #ff4444;
  margin-top: 16px;
  padding: 12px;
  background: #fff5f5;
  border-radius: 8px;
}

.retry-button {
  margin-top: 20px;
  padding: 12px 32px;
  background: #73b100;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}

.retry-button:active {
  opacity: 0.8;
}

/* 表单 */
.verification-form {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
}

.form-item {
  margin-bottom: 20px;
}

.form-label {
  font-size: 14px;
  color: #666;
  margin-bottom: 8px;
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

/* 上传区域 */
.upload-section {
  margin-top: 24px;
}

.upload-title {
  font-size: 14px;
  color: #666;
  margin-bottom: 16px;
}

.upload-item {
  margin-bottom: 24px;
}

.upload-area {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  background: #f5f7fb;
  border: 2px dashed #ddd;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  overflow: hidden;
}

.upload-area.has-image {
  border-color: #73b100;
}

.upload-placeholder {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.upload-preview {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.upload-plus {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 40px;
  height: 40px;
  background: #73b100;
  color: #fff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  font-weight: 600;
  z-index: 1;
}

.upload-label {
  text-align: center;
  font-size: 14px;
  color: #666;
  margin-top: 8px;
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

.submit-button:active:not(:disabled) {
  opacity: 0.8;
}

.submit-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
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



