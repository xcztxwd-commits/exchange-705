<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useLocaleStore } from '@/store/locale'
import { languages } from '@/utils/languages'

const router = useRouter()
const localeStore = useLocaleStore()
localeStore.loadLocale()

type LangItem = (typeof languages)[number]

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
            </div>
          </div>
          <div v-if="item.locale === current" class="lang-name" style="color: #85bd00;">✓</div>
        </div>
      </div>
    </div>
  </div>
</template>


<style scoped>
.flag {
  width: 32px;
  height: 24px;
  object-fit: contain;
  border-radius: 3px;
  flex-shrink: 0;
}
</style>
