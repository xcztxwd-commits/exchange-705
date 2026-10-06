<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore, exchangeOpener } from '@/store/auth'
import { exactOrigin, matchesExchangeMessage } from '@/utils/adminSession'
import request from '@/utils/request'
const status = ref('正在建立总控访问会话…')
const router = useRouter(), auth = useAuthStore()
let cleanup = () => {}, active = true
onMounted(async () => {
  auth.logout()
  try {
    if (!exchangeOpener || exchangeOpener.closed) throw new Error('请从总控后台重新进入，不能直接打开此地址')
    // Public origins come from the same-origin server's approved configuration, never URL parameters or referrer.
    const config: any = await request.get('/admin/auth/control-exchange-config')
    if (!active) return
    const origin = exactOrigin(config.controlOrigin)
    if (exactOrigin(config.adminOrigin) !== location.origin || origin === location.origin) throw new Error('总控与后台入口配置不匹配')
    const challenge = crypto.randomUUID()
    let consumed = false
    const timer = setTimeout(() => { cleanup(); status.value = '交换超时。请关闭此窗口，从总控后台重新进入。' }, 65000)
    const receive = async (event: MessageEvent) => {
      if (consumed || !matchesExchangeMessage(event, exchangeOpener!, origin, challenge)) return
      consumed = true
      cleanup()
      try {
        const data: any = await request.post('/admin/auth/control-exchange', { ticket: event.data.ticket, browserBinding: event.data.browserBinding, expectedTenantId: event.data.tenantId })
        if (!active) return
        if (data.user?.userType !== 'control' || data.user?.tenantId !== event.data.tenantId || data.accessSession?.tenantId !== event.data.tenantId) throw new Error('交换身份或目标租户不一致')
        auth.setAuth(data.token, data.user, data.accessSession)
        if (await router.replace('/')) throw new Error('后台导航未完成，请重新进入')
        if (!auth.isControl || auth.user?.tenantId !== event.data.tenantId || ['/login', '/access-ended', '/forbidden'].includes(router.currentRoute.value.path)) throw new Error('总控后台访问未建立，请重新进入')
        exchangeOpener!.postMessage({ type: 'control-exchange-result', challenge, tenantId: event.data.tenantId, success: true }, origin)
      } catch (error: any) {
        auth.logout(); status.value = error.message || '交换失败，请重新进入'
        exchangeOpener!.postMessage({ type: 'control-exchange-result', challenge, tenantId: event.data.tenantId, success: false, message: status.value }, origin)
      }
    }
    cleanup = () => { clearTimeout(timer); window.removeEventListener('message', receive); window.opener = null }
    window.addEventListener('message', receive)
    exchangeOpener.postMessage({ type: 'control-exchange-ready', challenge }, origin)
  } catch (error: any) { status.value = error.message; window.opener = null }
})
onUnmounted(() => { active = false; cleanup() })
</script>
<template><main class="exchange"><h1>总控管理</h1><p role="status">{{ status }}</p><p>访问仅绑定当前窗口及指定租户，不影响租户人员登录。</p></main></template>
<style scoped>.exchange{max-width:680px;margin:12vh auto;padding:32px;line-height:1.8}</style>
