const routes: Record<string, string> = {
  '/': 'home', '/home': 'home', '/trade': 'trade', '/orders': 'orders', '/profile': 'profile',
  '/assets': 'assets', '/deposit': 'deposit', '/deposit/records': 'deposit', '/withdraw': 'withdraw',
  '/wallet': 'wallet', '/wallet/bind-bank-card': 'wallet', '/wallet/bind-digital-currency': 'wallet',
  '/verification': 'verification', '/transfer': 'transfer', '/change-password': 'security',
  '/customer-service': 'support', '/inbox': 'inbox', '/complaint': 'support', '/announcements': 'announcement',
  '/credit-loan': 'loan', '/loan/personal-info': 'loan', '/loan/apply-info': 'loan', '/loan/contract': 'loan',
  '/loan/sign': 'loan', '/loan/records': 'loan', '/financial-management': 'financial',
  '/financial/purchase': 'financial', '/financial/orders': 'financial', '/financial/yield-list': 'financial',
  '/search': 'search', '/invite': 'invite', '/language': 'settings',
}
const codes = new Set([...Object.values(routes), 'option', 'contract', 'unknown'])
export const pageCodeForPath = (path: string) => routes[path.split(/[?#]/, 1)[0] || ''] || 'unknown'
export const safePageCode = (code: unknown) => typeof code === 'string' && codes.has(code) ? code : 'unknown'
export function createPageReporter(send: (payload: { pageCode: string; deviceType: 'PC'|'MOBILE'; sequence: number }) => Promise<unknown>, deviceType: 'PC'|'MOBILE', visible: () => boolean) {
  let pageCode = 'unknown', sequence = 0
  // Event-driven only. No heartbeat, interval, or retry that could manufacture activity.
  return async (code?: string) => {
    if (code !== undefined) pageCode = safePageCode(code)
    if (!visible()) return
    sequence = Math.max(sequence + 1, Date.now())
    try { await send({ pageCode, deviceType, sequence }) } catch { /* A later real navigation can report again. */ }
  }
}
export function reportPageView(code: string) {
  window.dispatchEvent(new CustomEvent('exchange-page-view', { detail: safePageCode(code) }))
}
