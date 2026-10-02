<template>
  <TenantStatus />
  <AccountModeSwitch v-if="auth.token" real-path="/trade" />
    <router-view :key="auth.user?.id" />
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
import TenantStatus from '@/components/TenantStatus.vue'
import { refreshTenantFeatures } from '@/utils/tenantFeatures'

const auth = useAuthStore()
const localeStore = useLocaleStore()

// 应用启动时自动加载语言（自动检测浏览器语言）
localeStore.loadLocale()

const route = useRoute()
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

