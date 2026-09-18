<template>
  <div class="announcements-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('announcements') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="announcements-content">
      <div v-if="loading" class="loading-container">
        <div class="loading-spinner"></div>
        <div class="loading-text">{{ localeStore.t('loading') }}</div>
      </div>

      <div v-else-if="announcements.length === 0" class="empty-state">
        <div class="empty-icon">
          <svg width="80" height="80" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <!-- 喇叭主体 -->
            <path d="M5 6L14 2L15 3.5L6 8L5 6Z" fill="#E0E0E0"/>
            <path d="M14 2L15 3.5L18.5 4L18 2.5L14 2Z" fill="#D0D0D0"/>
            <!-- 喇叭开口 -->
            <path d="M6 8L14 12L18 7L10 3L6 8Z" fill="#F0F0F0"/>
            <!-- 声音波纹 -->
            <path d="M18 7C19 7 20 7.5 20 8.5C20 9.5 19 10 18 10" stroke="#999" stroke-width="1.5" stroke-linecap="round" fill="none"/>
            <path d="M18 10C20 10 21.5 10.5 21.5 12C21.5 13.5 20 14 18 14" stroke="#999" stroke-width="1.5" stroke-linecap="round" fill="none"/>
            <!-- 手柄 -->
            <rect x="3" y="8" width="2.5" height="6" rx="1" fill="#E0E0E0"/>
            <rect x="3.75" y="10" width="1" height="2" rx="0.5" fill="#999"/>
          </svg>
        </div>
        <div class="empty-text">{{ localeStore.t('noAnnouncements') }}</div>
        <div class="empty-desc">{{ localeStore.t('noAnnouncementsDescription') }}</div>
      </div>

      <div v-else class="announcements-list">
        <div 
          v-for="announcement in announcements" 
          :key="announcement.id"
          class="announcement-card"
        >
          <div class="announcement-header">
            <div class="announcement-icon">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <!-- 喇叭主体 -->
                <path d="M5 6L14 2L15 3.5L6 8L5 6Z" fill="#73b100"/>
                <path d="M14 2L15 3.5L18.5 4L18 2.5L14 2Z" fill="#5a9200"/>
                <!-- 喇叭开口 -->
                <path d="M6 8L14 12L18 7L10 3L6 8Z" fill="#85c700"/>
                <!-- 声音波纹 -->
                <path d="M18 7C19 7 20 7.5 20 8.5C20 9.5 19 10 18 10" stroke="#73b100" stroke-width="1.5" stroke-linecap="round" fill="none"/>
                <path d="M18 10C20 10 21.5 10.5 21.5 12C21.5 13.5 20 14 18 14" stroke="#73b100" stroke-width="1.5" stroke-linecap="round" fill="none"/>
                <!-- 手柄 -->
                <rect x="3" y="8" width="2.5" height="6" rx="1" fill="#73b100"/>
                <rect x="3.75" y="10" width="1" height="2" rx="0.5" fill="#5a9200"/>
              </svg>
            </div>
            <div class="announcement-title">{{ announcement.title }}</div>
          </div>
          <div class="announcement-content">
            {{ announcement.content }}
          </div>
          <div class="announcement-footer">
            <div class="announcement-date">{{ formatDate(announcement.createdAt) }}</div>
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
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
import { useLocaleStore } from '@/store/locale'
import { formatDate } from '@/utils/dateTime'

const router = useRouter()
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 状态
const loading = ref(true)
const announcements = ref<any[]>([])

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

// 将前端的 LocaleKey 映射到后端支持的语言代码
function getLanguageForBackend(localeKey: string): string {
  // 后端支持的语言代码映射（与前端所有支持的语种完全一致，共20种）
  const langMap: Record<string, string> = {
    'zh-TW': 'zh-TW',
    'zh-CN': 'zh-CN', // 简体中文（虽然前端LocaleKey中没有，但浏览器可能返回，需要支持）
    'en': 'en',
    'fr': 'fr',
    'de': 'de',
    'ru': 'ru',
    'es': 'es',
    'pt': 'pt',
    'it': 'it',
    'ar': 'ar',
    'tr': 'tr',
    'id': 'id',
    'my': 'my', // 缅甸语
    'hi': 'hi', // 印地语
    'cs': 'cs', // 捷克语
    'pl': 'pl', // 波兰语
    'ja': 'ja',
    'ko': 'ko',
    'th': 'th',
    'vi': 'vi',
  }
  // 如果映射中存在，返回映射值；否则返回 'en' 作为默认值
  return langMap[localeKey] || 'en'
}

// 加载公告列表（根据当前语言）
async function loadAnnouncements() {
  loading.value = true
  try {
    // 获取当前语言代码（转换为后端支持的格式）
    // 使用 getCurrentLocale 函数获取当前语言，如果没有则使用 'en'
    const currentLocale = localeStore.getCurrentLocale ? localeStore.getCurrentLocale() : (localeStore.locale || 'en')
    const currentLanguage = getLanguageForBackend(currentLocale)
    console.log('[Announcements] Loading announcements for language:', currentLanguage, 'locale:', currentLocale)
    
    // 请求公告时传递语言参数
    const res: any = await request.get('/user/announcements', {
      params: {
        language: currentLanguage
      }
    })
    
    if (res && res.announcements) {
      announcements.value = res.announcements
      console.log('[Announcements] Loaded', res.announcements.length, 'announcements for language:', currentLanguage)
    } else {
      announcements.value = []
    }
  } catch (e: any) {
    console.error(localeStore.t('loadAnnouncementsFailed'), e)
    showToast(localeStore.t('loadFailed'), 'error')
    announcements.value = []
  } finally {
    loading.value = false
  }
}

// 监听语言变化，重新加载公告
watch(
  () => {
    // 使用 getCurrentLocale 函数获取当前语言，如果没有则使用 locale 属性
    return localeStore.getCurrentLocale ? localeStore.getCurrentLocale() : (localeStore.locale || 'en')
  },
  (newLocale) => {
    console.log('[Announcements] Language changed to:', newLocale)
    loadAnnouncements()
  },
  { immediate: false }
)

onMounted(() => {
  loadAnnouncements()
})
</script>

<style scoped>
.announcements-page {
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
.announcements-content {
  padding: 16px;
}

/* 加载状态 */
.loading-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 80px 16px;
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
  padding: 80px 16px;
  text-align: center;
}

.empty-icon {
  width: 80px;
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 8px;
}

.empty-icon svg {
  width: 100%;
  height: 100%;
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

/* 公告列表 */
.announcements-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 公告卡片 */
.announcement-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.announcement-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.announcement-icon {
  width: 24px;
  height: 24px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.announcement-icon svg {
  width: 100%;
  height: 100%;
}

.announcement-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
  flex: 1;
}

.announcement-content {
  font-size: 15px;
  color: #666;
  line-height: 1.6;
  margin-bottom: 16px;
  white-space: pre-wrap;
  word-wrap: break-word;
}

.announcement-footer {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
}

.announcement-date {
  font-size: 12px;
  color: #999;
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

