<template>
  <div class="customer-service-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('contactCustomerService') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="customer-service-content">
      <div v-if="loading" class="loading-container">
        <div class="loading-spinner"></div>
        <div class="loading-text">{{ localeStore.t('loading') }}</div>
      </div>

      <div v-else-if="!serviceAvailable" class="empty-state">
        <div class="empty-icon">💬</div>
        <div class="empty-text">{{ localeStore.t('noCustomerService') }}</div>
        <div class="empty-desc">{{ localeStore.t('pleaseTryLaterOrContactUs') }}</div>
      </div>

      <div v-else class="service-container">
        <iframe 
          v-if="serviceLink"
          :src="iframeUrl" 
          class="service-iframe"
          frameborder="0"
          allowfullscreen
        ></iframe>
        <div v-else class="service-placeholder">
          <div class="service-icon">💬</div>
          <div class="service-title">{{ localeStore.t('onlineCustomerService') }}</div>
          <div class="service-desc">{{ localeStore.t('loadingCustomerService') }}</div>
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
const serviceLink = ref('')
const serviceAvailable = ref(false)
const iframeUrl = ref('')

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

// 加载客服链接
async function loadServiceLink() {
  loading.value = true
  try {
    const res: any = await request.get('/user/customer-service/link')
    if (res && res.link) {
      serviceLink.value = res.link
      serviceAvailable.value = res.available || false
      
      // 处理链接，确保包含协议
      if (serviceAvailable.value) {
        let url = serviceLink.value.trim()
        if (!url.startsWith('http://') && !url.startsWith('https://')) {
          url = 'https://' + url
        }
        iframeUrl.value = url
      }
    } else {
      serviceAvailable.value = false
    }
  } catch (e: any) {
    console.error('Failed to load customer service link:', e)
    serviceAvailable.value = false
    showToast(localeStore.t('loadFailed'), 'error')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadServiceLink()
})
</script>

<style scoped>
.customer-service-page {
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
.customer-service-content {
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

/* 客服容器 */
.service-container {
  width: 100%;
  height: calc(100vh - 140px);
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
  position: relative;
}

.service-iframe {
  width: 100%;
  height: 100%;
  border: none;
  display: block;
}

.service-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  height: 100%;
  padding: 40px 24px;
}

.service-icon {
  font-size: 64px;
}

.service-title {
  font-size: 20px;
  font-weight: 600;
  color: #333;
}

.service-desc {
  font-size: 14px;
  color: #666;
  text-align: center;
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

