export const ADMIN_SESSION_KEY = 'exchange.admin.session.v2'
export type AccessSession = { id: string; tenantId: number; tenantName: string; expiresAt: number }
export type AdminSession = { token: string; user: any; mode: 'admin' | 'control'; accessSession?: AccessSession }

export function validSession(value: any, now = Date.now()): value is AdminSession {
  if (!value || !['admin', 'control'].includes(value.mode) || typeof value.token !== 'string' || !value.token || !Number.isSafeInteger(value.user?.tenantId) || value.user.tenantId <= 0) return false
  if (value.mode === 'admin') return value.user.userType !== 'control'
  const access = value.accessSession
  return !!access && typeof access.id === 'string' && !!access.id && access.tenantId === value.user.tenantId && Number.isFinite(access.expiresAt) && access.expiresAt > now
}

export function readSession(storage: Storage): AdminSession | null {
  try {
    const value = JSON.parse(storage.getItem(ADMIN_SESSION_KEY) || 'null')
    if (validSession(value)) return value
  } catch { /* Invalid or obsolete sessions never fall back to another identity. */ }
  storage.removeItem(ADMIN_SESSION_KEY)
  return null
}

export function clearAdminSession(storage: Storage) {
  storage.removeItem(ADMIN_SESSION_KEY)
  // A copied opener must not carry pending money commands into a new session.
  for (const key of Object.keys(storage)) if (key.startsWith('deposit-pending:') || key.startsWith('balance-pending:')) storage.removeItem(key)
}

export function exactOrigin(value: string): string {
  const parsed = new URL(value)
  if (!['https:', 'http:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.pathname !== '/' || parsed.search || parsed.hash) throw new Error('后台来源配置无效')
  if (parsed.protocol !== 'https:' && !['localhost', '127.0.0.1', '[::1]'].includes(parsed.hostname)) throw new Error('后台来源必须使用 HTTPS')
  return parsed.origin
}

export function matchesExchangeMessage(event: Pick<MessageEvent, 'source'|'origin'|'data'>, source: Window, origin: string, challenge: string): boolean {
  return event.source === source && event.origin === origin && event.data?.type === 'control-exchange-ticket' && event.data.challenge === challenge && typeof event.data.ticket === 'string' && !!event.data.ticket && typeof event.data.browserBinding === 'string' && event.data.browserBinding.length >= 32 && Number.isSafeInteger(event.data.tenantId) && event.data.tenantId > 0
}

export function assertAdminRequestTarget(target: URL, origin: string) {
  if (target.origin !== origin || target.username || target.password || !target.pathname.startsWith('/api/')) throw new Error('拒绝非后台同源 API 请求')
  if (target.pathname.startsWith('/api/control/')) throw new Error('租户会话不能调用总控接口')
}
