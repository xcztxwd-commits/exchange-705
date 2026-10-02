// Grant timestamps are UTC even when Java LocalDateTime omits a suffix.
export type FundingSource = 'TRIAL' | 'CONTRACT' | 'OPTION'
export type CashSource = Exclude<FundingSource, 'TRIAL'>
export type FundingChoice = { source: FundingSource; manual: boolean }
export function utcMillis(value: unknown): number {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})?$/.test(value)) return NaN
  return Date.parse(value.replace(/(\.\d{3})\d+/, '$1') + (/(?:Z|[+-]\d{2}:\d{2})$/.test(value) ? '' : 'Z'))
}
export function createServerClock() {
  let server = NaN, received = 0
  return {
    sync(value: unknown, started: number, ended: number) {
      const parsed = utcMillis(value)
      if (!Number.isFinite(parsed) || ended < started) return false
      // Conservatively charge the whole round trip; slow responses cannot extend a grant.
      server = Math.max(parsed + ended - started, Number.isFinite(server) ? server + Math.max(0, ended - received) : -Infinity)
      received = ended
      return true
    },
    now(monotonic: number) { return server + Math.max(0, monotonic - received) },
    reset() { server = NaN; received = 0 },
  }
}
const amount = (value: unknown) => value != null && Number.isFinite(Number(value)) && Number(value) >= 0 ? Number(value) : 0
export function trialState(snapshot: any, serverNow: number, mode = 'REAL') {
  const unavailable = { eligible: false, available: 0, frozen: 0, expiresAt: null as number | null }
  if (mode !== 'REAL' || snapshot?.trialEligible !== true || !Number.isFinite(serverNow) || !snapshot?.fundingSources?.includes('TRIAL')) return unavailable
  const grants = Array.isArray(snapshot.grants) ? snapshot.grants : null
  if (grants) {
    const live = grants.filter((g: any) => g.active === true && (g.expiresAt == null || utcMillis(g.expiresAt) > serverNow))
    const available = Math.min(amount(snapshot.trialAvailable), live.reduce((sum: number, g: any) => sum + amount(g.available), 0))
    const frozen = Math.min(amount(snapshot.trialFrozen), live.reduce((sum: number, g: any) => sum + amount(g.frozen), 0))
    const dates = live.filter((g: any) => g.expiresAt != null).map((g: any) => utcMillis(g.expiresAt))
    return { eligible: available > 0 || frozen > 0, available, frozen, expiresAt: dates.length ? Math.min(...dates) : null }
  }
  const expiresAt = snapshot.trialExpiresAt == null ? null : utcMillis(snapshot.trialExpiresAt)
  if (expiresAt != null && (!Number.isFinite(expiresAt) || serverNow >= expiresAt)) return unavailable
  return { eligible: true, available: amount(snapshot.trialAvailable), frozen: amount(snapshot.trialFrozen), expiresAt }
}
export function reconcileFunding(choice: FundingChoice, cash: CashSource, trial: ReturnType<typeof trialState>, mode = 'REAL'): FundingChoice {
  const canTrial = mode === 'REAL' && trial.eligible && trial.available > 0
  if (choice.manual) return { source: choice.source === 'TRIAL' && canTrial ? 'TRIAL' : cash, manual: true }
  return { source: canTrial ? 'TRIAL' : cash, manual: false }
}
export function selectedAvailable(snapshot: any, source: FundingSource, state: ReturnType<typeof trialState>): number {
  if (source === 'TRIAL') return state.available
  const value = snapshot?.[source === 'CONTRACT' ? 'contractBalance' : 'optionBalance']
  return value != null && Number.isFinite(Number(value)) && Number(value) >= 0 ? Number(value) : NaN
}
export function fundingPositions(orders: any[], source: FundingSource) {
  return orders.filter(order => order.status === 'OPEN' && (order.fundingSource === source || (source === 'CONTRACT' && order.fundingSource == null)))
}
export function countdown(expiresAt: number | null, now: number): string {
  if (expiresAt == null) return '—'
  const seconds = Math.max(0, Math.ceil((expiresAt - now) / 1000)), days = Math.floor(seconds / 86400)
  return `${days ? days + 'd ' : ''}${[Math.floor(seconds / 3600) % 24, Math.floor(seconds / 60) % 60, seconds % 60].map(n => String(n).padStart(2, '0')).join(':')}`
}
export type ActivityPosition = 'ANONYMOUS_HOME' | 'AUTH_HOME' | 'AUTH_TRADE' | 'AUTH_PROFILE' | 'SUPPORT'
export function activityPosition(path: string, authenticated: boolean): ActivityPosition | null {
  if (path === '/' || path === '/home') return authenticated ? 'AUTH_HOME' : 'ANONYMOUS_HOME'
  if (!authenticated) return null
  if (path === '/trade') return 'AUTH_TRADE'
  if (path === '/profile') return 'AUTH_PROFILE'
  if (path === '/customer-service' || path === '/complaint') return 'SUPPORT'
  return null
}
export function matchesPosition(campaign: any, position: ActivityPosition | null) {
  return !!position && (Array.isArray(campaign?.positions) ? campaign.positions : ['AUTH_HOME']).includes(position)
}
