export const HOME_SPARKLINE_REFRESH_MS = 300_000
export type HomeSparklineStatus = 'fresh' | 'stale' | 'empty'
export type HomeSparklineItem = {
  symbol: string; points: number[]; status: HomeSparklineStatus
  updatedAt: number | null; reason: string | null
}
export type HomeSparklineState = { scope: string; items: Record<string, HomeSparklineItem> }
export const emptyHomeSparkline = (symbol: string): HomeSparklineItem => ({
  symbol, points: [], status: 'empty', updatedAt: null, reason: 'cache_miss',
})

function normalizeHomeSparkline(raw: any): HomeSparklineItem | null {
  if (!raw || typeof raw.symbol !== 'string' || !raw.symbol) return null
  const points = Array.isArray(raw.points) ? raw.points.filter((p: unknown) => typeof p === 'number' && Number.isFinite(p) && p > 0).slice(-20) : []
  return {
    symbol: raw.symbol, points,
    status: !points.length ? 'empty' : raw.status === 'fresh' ? 'fresh' : 'stale',
    updatedAt: Number.isFinite(raw.updatedAt) ? raw.updatedAt : null,
    reason: typeof raw.reason === 'string' ? raw.reason : null,
  }
}

// Host is the anonymous tenant boundary; actor/mode also isolate authenticated browser state.
export function homeSparklineScope(apiBase: string, host = location.host): string {
  let actor = ''
  try { const user = JSON.parse(localStorage.getItem('user') || '{}'); actor = JSON.stringify([user.tenantId, user.id]) } catch { }
  return JSON.stringify([host, apiBase, actor])
}

/** Only the snapshot endpoint is called. Failed/empty reads are paced too; no K-line fallback. */
export function createHomeSparklineClient(
  load: (symbols: string[]) => Promise<any>,
  context: () => { key: string; mode: 'REAL' | 'DEMO' },
  publish: (state: HomeSparklineState) => void,
  now: () => number = Date.now,
) {
  let scope = '', lastAttempt = -Infinity, version = 0
  let items: Record<string, HomeSparklineItem> = {}
  let pending: Promise<void> | undefined
  const synchronize = () => {
    const current = context()
    if (current.key !== scope) {
      scope = current.key; lastAttempt = -Infinity; items = {}; pending = undefined; version++
      try {
        const saved = JSON.parse(localStorage.getItem('exchange:home-sparkline:v1:' + scope) || 'null')
        if (saved?.scope === scope && saved.items && typeof saved.items === 'object') {
          for (const [name, raw] of Object.entries(saved.items)) {
            const item = normalizeHomeSparkline(raw)
            if (item?.symbol === name && item.points.length) items[name] = { ...item, status: 'stale', reason: 'cached_snapshot' }
          }
        }
      } catch { /* Storage may be unavailable or contain an invalid snapshot. */ }
      publish({ scope, items })
    }
    return current
  }
  const commit = () => {
    publish({ scope, items })
    try { localStorage.setItem('exchange:home-sparkline:v1:' + scope, JSON.stringify({ scope, items })) } catch { /* Keep the in-memory snapshot. */ }
  }
  async function refresh(symbols: string[]) {
    const current = synchronize(), names = [...new Set(symbols)].filter(Boolean)
    if (!names.length) return
    if (pending) { await pending; if (context().key === current.key) return refresh(names); return }
    const age = now() - lastAttempt
    if (age >= 0 && age < HOME_SPARKLINE_REFRESH_MS && names.every(name => items[name])) return
    lastAttempt = now()
    const requestVersion = version, requestScope = scope
    const task = (async () => {
      try {
        const received: Record<string, HomeSparklineItem> = {}
        for (let offset = 0; offset < names.length; offset += 512) {
          const batch = names.slice(offset, offset + 512), response = await load(batch)
          const data = response?.data
          if (response?.ret !== 200 || data?.mode !== current.mode || !Array.isArray(data.items)) throw new Error('Invalid homepage snapshot')
          for (const raw of data.items) {
            if (!batch.includes(raw?.symbol)) continue
            const item = normalizeHomeSparkline(raw)
            if (item) received[item.symbol] = item
          }
          for (const name of batch) {
            received[name] ||= emptyHomeSparkline(name)
            const previous = items[name]
            if (!received[name].points.length && previous?.points.length) {
              received[name] = { ...previous, status: 'stale', reason: received[name].reason || 'source_empty' }
            }
          }
        }
        if (version !== requestVersion || context().key !== requestScope) return
        items = { ...items, ...received }; commit()
      } catch {
        if (version !== requestVersion || context().key !== requestScope) return
        const next = { ...items }
        for (const name of names) {
          const previous = items[name] || emptyHomeSparkline(name)
          next[name] = { ...previous, status: previous.points.length ? 'stale' : 'empty', reason: 'request_failed' }
        }
        items = next; commit()
      }
    })()
    pending = task
    try { await task } finally { if (pending === task) pending = undefined }
  }
  synchronize()
  return { refresh }
}
