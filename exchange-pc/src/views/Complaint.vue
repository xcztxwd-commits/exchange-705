<template>
  <div class="complaint-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('complaintEmail') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="complaint-content">
      <div v-if="loading" class="loading-container">
        <div class="loading-spinner"></div>
        <div class="loading-text">{{ localeStore.t('loading') }}</div>
      </div>

      <div v-else-if="!emailAvailable" class="empty-state">
        <div class="empty-icon">📧</div>
        <div class="empty-text">{{ localeStore.t('noComplaintEmail') }}</div>
        <div class="empty-desc">{{ localeStore.t('pleaseTryLaterOrContactUs') }}</div>
      </div>

      <div v-else class="email-container">
        <div class="email-icon">📧</div>
        <div class="email-title">{{ localeStore.t('complaintEmail') }}</div>
        <div class="email-address" @click="copyEmail">{{ complaintEmail }}</div>
        <button class="copy-button" @click="copyEmail">
          {{ copied ? localeStore.t('copiedToClipboard') : localeStore.t('copy') }}
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
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 状态
const loading = ref(true)
const complaintEmail = ref('')
const emailAvailable = ref(false)
const copied = ref(false)

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

// 加载投诉邮箱
async function loadComplaintEmail() {
  loading.value = true
  try {
    const res: any = await request.get('/user/complaint/email')
    if (res && res.email) {
      complaintEmail.value = res.email
      emailAvailable.value = res.available || false
    } else {
      emailAvailable.value = false
    }
  } catch (e: any) {
    console.error('Failed to load complaint email:', e)
    emailAvailable.value = false
    showToast(localeStore.t('loadFailed'), 'error')
  } finally {
    loading.value = false
  }
}

// 复制邮箱地址
async function copyEmail() {
  if (!complaintEmail.value) {
    showToast(localeStore.t('emailNotConfigured'), 'error')
    return
  }
  
  try {
    // 使用 Clipboard API
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(complaintEmail.value)
      copied.value = true
      showToast(localeStore.t('copiedToClipboard'), 'success')
      setTimeout(() => {
        copied.value = false
      }, 2000)
    } else {
      // 降级方案：使用传统方法
      const textarea = document.createElement('textarea')
      textarea.value = complaintEmail.value
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      try {
        document.execCommand('copy')
        copied.value = true
        showToast(localeStore.t('copiedToClipboard'), 'success')
        setTimeout(() => {
          copied.value = false
        }, 2000)
      } catch (err) {
        showToast(localeStore.t('copyFailedPleaseCopyManually'), 'error')
      } finally {
        document.body.removeChild(textarea)
      }
    }
  } catch (e: any) {
    console.error('Failed to copy email:', e)
    showToast(localeStore.t('copyFailedPleaseCopyManually'), 'error')
  }
}

onMounted(() => {
  loadComplaintEmail()
})
</script>

<style scoped>
.complaint-page {
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
.complaint-content {
  padding: 40px 16px;
  min-height: 400px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 加载状态 */
.loading-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
}

.loading-spinner {
  width: 48px;
  height: 48px;
  border: 4px solid #f3f3f3;
  border-top: 4px solid #73b100;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.loading-text {
  font-size: 14px;
  color: #999;
}

/* 空状态 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  text-align: center;
}

.empty-icon {
  font-size: 64px;
}

.empty-text {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.empty-desc {
  font-size: 14px;
  color: #999;
}

/* 邮箱容器 */
.email-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  background: #fff;
  border-radius: 12px;
  padding: 40px 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  max-width: 400px;
  width: 100%;
}

.email-icon {
  font-size: 64px;
}

.email-title {
  font-size: 20px;
  font-weight: 600;
  color: #333;
}

.email-address {
  font-size: 16px;
  color: #333;
  padding: 12px 16px;
  background: #f5f7fb;
  border-radius: 8px;
  cursor: pointer;
  user-select: all;
  word-break: break-all;
  text-align: center;
  width: 100%;
  box-sizing: border-box;
}

.email-address:active {
  opacity: 0.8;
}

.copy-button {
  width: 100%;
  padding: 14px;
  background: #73b100;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  margin-top: 8px;
}

.copy-button:active {
  opacity: 0.8;
}

.copy-button:disabled {
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

