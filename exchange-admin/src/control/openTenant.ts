import { exactOrigin } from '@/utils/adminSession'
import { api } from './api'
export function openTenant(tenantId: number): Promise<void> {
  const adminOrigin = exactOrigin(import.meta.env.VITE_ADMIN_ORIGIN || '')
  let child: Window | null = null
  return new Promise((resolve, reject) => {
    let handling = false, done = false
    const finish = (error?: Error) => { if (done) return; done = true; window.removeEventListener('message', receive); clearTimeout(timer); if (error) { child?.close(); reject(error) } else resolve() }
    const timer = setTimeout(() => finish(new Error('后台握手超时，请关闭新标签页后重试')), 65000)
    const receive = async (event: MessageEvent) => {
      if (handling || event.source !== child || event.origin !== adminOrigin || event.data?.type !== 'control-exchange-ready' || typeof event.data.challenge !== 'string' || !/^[a-f0-9-]{36}$/.test(event.data.challenge)) return
      handling = true
      const browserBinding = crypto.randomUUID() + crypto.randomUUID()
      try {
        const response = await api(`/control/tenants/${tenantId}/access-ticket`, 'POST', { browserBinding })
        const data = response.data ?? response
        if (done) return
        if (data.tenantId !== tenantId || exactOrigin(data.adminOrigin) !== adminOrigin || !data.ticket || data.expiresAt <= Date.now()) throw new Error('交换票据目标或有效期不符')
        child!.postMessage({ type: 'control-exchange-ticket', challenge: event.data.challenge, browserBinding, tenantId, ticket: data.ticket }, adminOrigin)
        finish()
      } catch (error: any) { finish(error) }
    }
    window.addEventListener('message', receive)
    // Unique unnamed tab, fixed configured origin. No token or ticket in URL/window.name.
    child = window.open(adminOrigin + '/control-exchange', '_blank')
    if (!child) finish(new Error('浏览器阻止新标签页，请允许弹出窗口后重试'))
  })
}
