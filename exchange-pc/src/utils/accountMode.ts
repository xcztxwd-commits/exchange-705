export type AccountMode = 'REAL' | 'DEMO'
export function accountModeKey() {
  try { const id = JSON.parse(localStorage.getItem('user') || '{}')?.id; return id ? `account-mode:${id}` : '' } catch { return '' }
}
export function accountMode(): AccountMode {
  const key = accountModeKey()
  return key && sessionStorage.getItem(key) === 'DEMO' ? 'DEMO' : 'REAL'
}
// Native Node has no Vite environment; read the object once before accessing keys.
const environment = import.meta.env || {}
export const realApiBase = () => environment.VITE_API_BASE_URL || '/api'
export const demoApiBase = () => environment.VITE_DEMO_API_BASE_URL || '/demo-api'
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
