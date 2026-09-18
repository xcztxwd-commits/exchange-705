<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
auth.load()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

const formData = ref({
  realName: '',
  idNumber: '',
  phone: '',
  address: ''
})

// const loading = ref(false) // 保留用于未来扩展
const submitting = ref(false)

// Toast提示
const toastMessage = ref('')
const toastType = ref<'success' | 'error' | ''>('')

function showToast(message: string, type: 'success' | 'error' = 'error') {
  toastMessage.value = message
  toastType.value = type
  setTimeout(() => {
    toastMessage.value = ''
    toastType.value = ''
  }, 3000)
}

// 加载实名认证信息
async function loadKycInfo() {
  try {
    const res: any = await request.get('/loan/kyc-info')
    if (res && res.success && res.data) {
      const info = res.data
      if (info.realName) formData.value.realName = info.realName
      if (info.idNumber) formData.value.idNumber = info.idNumber
      if (info.phone) formData.value.phone = info.phone
    }
  } catch (e) {
    console.error('加载实名认证信息失败:', e)
  }
}

// 提交贷款信息
async function submitLoanInfo() {
  if (!formData.value.realName || !formData.value.realName.trim()) {
    showToast(localeStore.t('enterRealName'), 'error')
    return
  }

  if (!formData.value.idNumber || !formData.value.idNumber.trim()) {
    showToast(localeStore.t('enterIdNumber'), 'error')
    return
  }

  if (!formData.value.phone || !formData.value.phone.trim()) {
    showToast(localeStore.t('enterPhoneNumber'), 'error')
    return
  }

  if (!formData.value.address || !formData.value.address.trim()) {
    showToast(localeStore.t('enterHomeAddress'), 'error')
    return
  }

  submitting.value = true
  try {
    const loanData = route.query
    const res: any = await request.post('/loan/apply', {
      amount: loanData.amount,
      settingId: loanData.settingId,
      realName: formData.value.realName.trim(),
      idNumber: formData.value.idNumber.trim(),
      phone: formData.value.phone.trim(),
      address: formData.value.address.trim()
    })

    if (res && res.success && res.data) {
      showToast(localeStore.t('applicationSubmittedSuccess'), 'success')
      setTimeout(() => {
        router.push({
          path: '/loan/contract',
          query: { id: res.data.id.toString() }
        })
      }, 1000)
    } else {
      showToast(res.message || localeStore.t('applicationFailed'), 'error')
    }
  } catch (e: any) {
    showToast(e.message || localeStore.t('applicationFailed'), 'error')
    console.error('提交貸款申請失敗:', e)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadKycInfo()
})
</script>

<template>
  <div class="loan-apply-info-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('loanInfo') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="page-content">
      <div class="form-section">
        <div class="form-label">{{ localeStore.t('realName') }} <span class="required">*</span></div>
        <input
          v-model="formData.realName"
          type="text"
          class="form-input"
          :placeholder="localeStore.t('enterRealName')"
        />
      </div>

      <div class="form-section">
        <div class="form-label">{{ localeStore.t('idNumber') }} <span class="required">*</span></div>
        <input
          v-model="formData.idNumber"
          type="text"
          class="form-input"
          :placeholder="localeStore.t('enterIdNumber')"
        />
      </div>

      <div class="form-section">
        <div class="form-label">{{ localeStore.t('phoneNumber') }} <span class="required">*</span></div>
        <input
          v-model="formData.phone"
          type="tel"
          class="form-input"
          :placeholder="localeStore.t('enterPhoneNumber')"
        />
      </div>

      <div class="form-section">
        <div class="form-label">{{ localeStore.t('homeAddress') }} <span class="required">*</span></div>
        <textarea
          v-model="formData.address"
          class="form-textarea"
          :placeholder="localeStore.t('enterHomeAddress')"
          rows="3"
        ></textarea>
      </div>

      <button class="submit-btn" @click="submitLoanInfo" :disabled="submitting">
        {{ submitting ? localeStore.t('submitting') : localeStore.t('submitApplication') }}
      </button>
    </div>

    <!-- Toast提示 -->
    <div v-if="toastMessage" :class="['toast-message', toastType]">
      {{ toastMessage }}
    </div>
  </div>
</template>

<style scoped>
.loan-apply-info-page {
  min-height: 100vh;
  background: #f5f5f5;
}

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

.page-content {
  padding: 20px 16px;
}

.form-section {
  margin-bottom: 24px;
}

.form-label {
  font-size: 14px;
  color: #333;
  margin-bottom: 8px;
  font-weight: 500;
}

.required {
  color: #ff4444;
}

.form-input,
.form-textarea {
  width: 100%;
  padding: 12px 16px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 16px;
  background: #fff;
  box-sizing: border-box;
}

.form-input::placeholder,
.form-textarea::placeholder {
  color: #999;
}

.form-textarea {
  resize: vertical;
  min-height: 80px;
}

.submit-btn {
  width: 100%;
  padding: 16px;
  background: #2abf4b;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 20px;
}

.submit-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}

/* Toast提示 */
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
  background: #2abf4b;
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

