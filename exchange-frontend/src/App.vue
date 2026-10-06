<template>
  <AccountModeSwitch v-if="auth.token" real-path="/trade" />
  <router-view v-slot="{ Component, route: viewRoute }">
    <component :is="presentationEdition === 'advanced' ? advancedPageForPath(viewRoute.path) || Component : Component" :key="viewKey" />
  </router-view>
    <SupportNotifications :key="auth.user?.id" mobile />
    <ActivityCenter hide-trigger :position="activityPlacement" />
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted, watch } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'
import request from '@/utils/request'
import { useRoute } from 'vue-router'
import { useTrialWallet } from '@/utils/useTrialWallet'
import { activityPosition } from '@/utils/trialLifecycle'
import { createPageReporter, pageCodeForPath, safePageCode } from '@/utils/pageActivity'
import ActivityCenter from '@/components/ActivityCenter.vue'
import SupportNotifications from '@/components/SupportNotifications.vue'
import AccountModeSwitch from '@/components/AccountModeSwitch.vue'
import { refreshTenantFeatures } from '@/utils/tenantFeatures'
import { useUiEditionStore } from '@/store/uiEdition'
import { advancedPageForPath } from '@/advanced/pageRegistry'
import { accountMode } from '@/utils/accountMode'
import { routeUiEdition } from '@/utils/uiEdition'

const auth = useAuthStore()
// Resolve the presentation namespace before mounting a routed view (no classic flash/read on an advanced refresh).
auth.load()
const localeStore = useLocaleStore()

// 应用启动时自动加载语言（自动检测浏览器语言）
localeStore.loadLocale()

const route = useRoute()
const ui = useUiEditionStore()
const presentationEdition = computed(() => routeUiEdition(ui.edition, route.path, route.query.edition, !!auth.token))
const viewKey = computed(() => `${auth.user?.tenantId ?? ''}:${auth.user?.id ?? ''}:${!!auth.token}:${accountMode()}:${presentationEdition.value}:${route.path}`)
const trialWallet = useTrialWallet()
const pcPage = ref('home')
const activityPlacement = computed(() => activityPosition(route.path, !!auth.token))
let trialTimer: ReturnType<typeof setInterval> | undefined
watch([() => auth.token, () => auth.user?.id, () => auth.user?.tenantId], () => { trialWallet.reset(); void trialWallet.refresh() }, { immediate: true })
const refreshWallet = () => { if (document.visibilityState === 'visible') { trialWallet.tick = performance.now(); void trialWallet.refresh() } }
const trialChanged = () => { void trialWallet.refresh() }
const report = createPageReporter(async payload => { const result = await request.post('/auth/activity', payload); window.dispatchEvent(new Event('activity-deliveries-changed')); return result }, 'MOBILE', () => !!auth.token && route.matched.length > 0 && document.visibilityState === 'visible')
watch(() => [route.path, route.matched.length] as const, ([path]) => { void report(pageCodeForPath(path)); void refreshTenantFeatures() }, { immediate: true })
watch(() => auth.token, token => { if (token) void report() })
const onVisible = () => { if (document.visibilityState === 'visible') { void report(); void refreshTenantFeatures() } }
const onPageView = (event: Event) => { pcPage.value = safePageCode((event as CustomEvent).detail); void report(pcPage.value) }

const onLocaleStorage = (event: StorageEvent) => {
  if (event.key === 'token' || event.key === 'user') { window.location.reload(); return }
  if (event.key === 'locale') localeStore.loadLocale()
}
onMounted(() => {
  trialTimer = setInterval(() => { trialWallet.tick = performance.now() }, 1000)
  window.addEventListener('focus', refreshWallet)
  window.addEventListener('trial-account-changed', trialChanged)
  document.addEventListener('visibilitychange', refreshWallet)
  window.addEventListener('storage', onLocaleStorage)
  document.addEventListener('visibilitychange', onVisible)
  window.addEventListener('exchange-page-view', onPageView)
  void report(pageCodeForPath(route.path))
  // 加载用户信息
  auth.load()
})

onUnmounted(() => {
  clearInterval(trialTimer)
  window.removeEventListener('focus', refreshWallet)
  window.removeEventListener('trial-account-changed', trialChanged)
  document.removeEventListener('visibilitychange', refreshWallet)
  window.removeEventListener('storage', onLocaleStorage)
  document.removeEventListener('visibilitychange', onVisible)
  window.removeEventListener('exchange-page-view', onPageView)
})
</script>

