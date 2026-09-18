<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const localeStore = useLocaleStore()
localeStore.loadLocale()

type LocaleValue = Parameters<typeof localeStore.setLocale>[0]
type LangItem = {
  label: string
  locale: LocaleValue
  flag: string
  desc?: string
}

// 说明：旗帜文件已从根目录复制到 public/img，下列文件名对应可按需调整
const languages: LangItem[] = [
  { label: 'English', locale: 'en', flag: '/img/en140ceb36.png' },
  { label: 'français', locale: 'fr', flag: '/img/下载.png' },
  { label: 'Deutsche', locale: 'de', flag: '/img/spa84479ee4.png' },
  { label: 'Русский язык', locale: 'ru', flag: '/img/ru9776c19e.png' },
  { label: 'Español', locale: 'es', flag: '/img/spa84479ee4.png' },
  { label: 'Português', locale: 'pt', flag: '/img/pt2d69992c.png' },
  { label: 'Italiano', locale: 'it', flag: '/img/rbpng.png' },
  { label: 'عربي', locale: 'ar', flag: '/img/ar7c042e25.png' },
  { label: 'Türkçe', locale: 'tr', flag: '/img/tra980693d.png' },
  { label: 'Indonesia', locale: 'id', flag: '/img/id4917741a.png' },
  { label: 'မြန်မာ', locale: 'my', flag: '/img/my4290a0a4.png' },
  { label: 'हिंदी', locale: 'hi', flag: '/img/hi58747bf8.png' },
  { label: 'čeština', locale: 'cs', flag: '/img/csaa39a20b.png' },
  { label: 'Polska', locale: 'pl', flag: '/img/pla73094cc.png' },
  { label: '日本語', locale: 'ja', flag: '/img/rb.png' },
  { label: '한국어', locale: 'ko', flag: '/img/kr65c1cb50.png' },
  { label: '繁體中文', locale: 'zh-TW', flag: '/img/qw.png' },
  { label: 'ไทย', locale: 'th', flag: '/img/123.png' },
  { label: 'Tiếng Việt', locale: 'vi', flag: '/img/vn1ebb558a.png' },
]

const current = computed(() => localeStore.locale)

const selectLang = (item: LangItem) => {
  console.log('[LanguageSelect] Selecting language:', item.locale)
  localeStore.setLocale(item.locale)
  // 延迟返回，确保语言已保存
  setTimeout(() => {
    router.back()
  }, 100)
}
</script>

<template>
  <div class="page-shell">
    <div class="auth-card">
      <div class="top-bar">
        <button class="icon-btn" @click="router.back()">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
            <path d="M15 6L9 12L15 18" stroke="#111" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </button>
        <div class="auth-title" style="margin: 0 auto;">{{ localeStore.t('language') }}</div>
      </div>

      <div class="lang-list">
        <div
          v-for="item in languages"
          :key="item.locale"
          class="lang-item"
          :style="item.locale === current ? { borderColor: '#85bd00', background: '#f3f8e8' } : {}"
          @click="selectLang(item)"
        >
          <div class="lang-left">
            <img class="flag" :src="item.flag" :alt="item.label" />
            <div>
              <div class="lang-name">{{ item.label }}</div>
              <div v-if="item.desc" class="lang-desc">{{ item.desc }}</div>
            </div>
          </div>
          <div v-if="item.locale === current" class="lang-name" style="color: #85bd00;">✓</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
@import '@/styles/variables.scss';

.page-shell {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  padding: 32px 16px;
  background: $bg-main;
}

.auth-card {
  width: min(520px, 100%);
  background: $bg-card;
  border-radius: $radius-lg;
  padding: 20px 24px 32px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.05);
}

.top-bar {
  display: flex;
  align-items: center;
  margin-bottom: 24px;
}

.icon-btn {
  background: none;
  border: none;
  padding: 8px;
  cursor: pointer;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.auth-title {
  font-size: 24px;
  font-weight: 700;
  color: $text-primary;
}

.lang-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.lang-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  border-radius: 12px;
  border: 1px solid #eaeaea;
  cursor: pointer;
  transition: all 0.2s;
}

.lang-item:hover {
  background: #f9f9f9;
}

.lang-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.flag {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  object-fit: cover;
}

.lang-name {
  font-size: 16px;
  font-weight: 500;
  color: $text-primary;
}

.lang-desc {
  font-size: 12px;
  color: $text-secondary;
}
</style>

