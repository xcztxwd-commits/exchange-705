import { createServerClock, utcMillis } from './trialLifecycle.ts'

const clock = createServerClock()
let lastSample = 0
let lowerTime = 0, receivedAt = 0

// Quote expiry uses the server epoch and monotonic elapsed time, never the device wall clock.
export const marketNow = () => clock.now(performance.now())
export function resetMarketTime() { clock.reset(); lastSample = 0; lowerTime = 0; receivedAt = 0 }

export function syncMarketTime(value: unknown, started = performance.now(), ended = performance.now()): boolean {
  const timestamp = typeof value === 'number' ? value : typeof value === 'string' ? Date.parse(value) : NaN
  if (!Number.isSafeInteger(timestamp) || timestamp <= 0 || timestamp > 8_640_000_000_000_000) return false
  if (![started, ended].every(Number.isFinite) || started < 0 || ended < started || timestamp <= lastSample) return false
  const lower = Math.max(timestamp, lowerTime + Math.max(0, ended - receivedAt))
  const estimate = Math.max(lower, timestamp + ended - started)
  if (!Number.isFinite(estimate) || estimate > 8_640_000_000_000_000) return false
  const iso = new Date(Math.ceil(estimate)).toISOString()
  if (!Number.isFinite(utcMillis(iso))) return false
  // A newer sample can correct an earlier conservative RTT estimate. Old replies cannot reset the epoch.
  // Keep the raw server-time lower bound advancing, so delayed packets cannot revive expired quotes.
  clock.reset()
  const accepted = clock.sync(iso, ended, ended)
  if (accepted) { lastSample = timestamp; lowerTime = lower; receivedAt = ended }
  return accepted
}

export function syncMarketResponse(response: Response, started: number, body?: { serverTime?: unknown }): boolean {
  if (syncMarketTime(body?.serverTime, started)) return true
  const date = response.headers?.get('Date')
  // HTTP Date has whole-second precision. Its upper bound and the complete RTT cannot extend expiry.
  return date != null && syncMarketTime(Date.parse(date) + 1000, started)
}
