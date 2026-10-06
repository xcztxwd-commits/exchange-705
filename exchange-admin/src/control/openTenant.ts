import { exactOrigin } from '@/utils/adminSession'
import { api } from './api'
export function openTenant(tenantId: number): Promise<void> {
  if (!Number.isSafeInteger(tenantId) || tenantId <= 0) return Promise.reject(new Error('目标租户无效'))
  let child: Window | null = null
  try {
    // Open synchronously in the click gesture. Never copy the control credential into the tenant window.
    child = window.open('about:blank', '_blank', 'popup=yes,width=1280,height=900,resizable=yes,scrollbars=yes')
    if (!child) return Promise.reject(new Error('浏览器阻止新窗口，请允许弹出窗口后重试'))
    child.sessionStorage.clear()
    child.document.title = '正在进入租户后台'
    child.document.body.textContent = '正在建立总控访问会话…'
  } catch {
    child?.close()
    return Promise.reject(new Error('无法打开独立后台窗口，请允许弹出窗口后重试'))
  }
  return new Promise((resolve, reject) => {
    let done = false, adminOrigin = '', ticket = '', challenge = ''
    const browserBinding = crypto.randomUUID() + crypto.randomUUID(), controller = new AbortController()
    const finish = (error?: Error) => {
      if (done) return
      done = true
      window.removeEventListener('message', receive); clearTimeout(timer); clearInterval(closed); controller.abort()
      if (error) { child?.close(); reject(error) } else resolve()
    }
    const receive = (event: MessageEvent) => {
      if (done || !adminOrigin || !ticket || event.source !== child || event.origin !== adminOrigin) return
      if (!challenge && event.data?.type === 'control-exchange-ready' && typeof event.data.challenge === 'string' && /^[a-f0-9-]{36}$/.test(event.data.challenge)) {
        challenge = event.data.challenge
        try { child!.postMessage({ type: 'control-exchange-ticket', challenge, browserBinding, tenantId, ticket }, adminOrigin) } catch { finish(new Error('后台窗口已关闭，请重新进入')) }
      } else if (challenge && event.data?.type === 'control-exchange-result' && event.data.challenge === challenge && event.data.tenantId === tenantId) {
        finish(event.data.success === true ? undefined : new Error(event.data.message || '总控访问交换失败，请重新进入'))
      }
    }
    const timer = setTimeout(() => finish(new Error('后台交换超时，请重新进入')), 65000)
    const closed = setInterval(() => { if (child?.closed) finish(new Error('后台窗口已关闭，请重新进入')) }, 500)
    window.addEventListener('message', receive)
    void (async () => {
      try {
        const response = await api(`/control/tenants/${tenantId}/access-ticket`, 'POST', { browserBinding }, controller.signal)
        if (done) return
        const data = response.data ?? response
        if (data.tenantId !== tenantId || typeof data.ticket !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(data.ticket) || !Number.isFinite(data.expiresAt) || data.expiresAt <= Date.now()) throw new Error('交换票据目标或有效期不符')
        // The authenticated server response, not a missing build variable or the tenant frontend host, chooses the admin entry.
        adminOrigin = exactOrigin(data.adminOrigin)
        if (adminOrigin === location.origin) throw new Error('总控与租户后台必须配置独立入口')
        ticket = data.ticket
        child!.location.replace(adminOrigin + '/control-exchange')
      } catch (error: any) { finish(error instanceof Error ? error : new Error('进入后台失败')) }
    })()
  })
}
