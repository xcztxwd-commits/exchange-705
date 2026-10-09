export type AccountMode = 'REAL' | 'DEMO'
export function accountModeKey() {
  try { const user = JSON.parse(localStorage.getItem('user') || '{}'); return user?.id && user?.tenantId ? `account-mode:${user.tenantId}:${user.id}` : '' } catch { return '' }
}
export function accountMode(): AccountMode {
  const key = accountModeKey()
  return key && sessionStorage.getItem(key) === 'DEMO' ? 'DEMO' : 'REAL'
}
// Native Node has no Vite environment; read the object once before accessing keys.
const environment = import.meta.env || {}
function gateway(value: string, fallback: string) {
  if (typeof location === 'undefined') return value || fallback
  const url = new URL(value || fallback, location.origin)
  if (url.origin !== location.origin || url.search || url.hash || url.username || url.password) throw new Error('Tenant API must use the same-origin gateway')
  return url.pathname.replace(/\/$/, '')
}
export const realApiBase = () => gateway(environment.VITE_API_BASE_URL, '/api')
export const demoApiBase = () => gateway(environment.VITE_DEMO_API_BASE_URL, '/demo-api')
export const getAccountApiBase = () => accountMode() === 'DEMO' ? demoApiBase() : realApiBase()
export function setAccountMode(mode: AccountMode) {
  const key = accountModeKey()
  if (!key) throw new Error('Please sign in first')
  sessionStorage.setItem(key, mode)
}
