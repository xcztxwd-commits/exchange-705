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

/** Exported demo reports must never look like evidence of real-money returns. */
export function markSimulationExport(canvas: HTMLCanvasElement) {
  if (accountMode() !== 'DEMO') return
  const context = canvas.getContext('2d')
  if (!context) return
  context.save(); context.setTransform(1, 0, 0, 1, 0, 0)
  context.fillStyle = '#3730a3'; context.fillRect(0, 0, canvas.width, 44)
  context.fillStyle = '#ffffff'; context.font = 'bold 24px sans-serif'; context.textAlign = 'center'; context.textBaseline = 'middle'
  context.fillText('SIMULATION · VIRTUAL FUNDS ONLY', canvas.width / 2, 22)
  context.restore()
}
