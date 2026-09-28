// The local backend serializes UTC LocalDateTime without an offset.
export function orderTimestamp(value: unknown): number | null {
  if (value instanceof Date) return Number.isFinite(value.getTime()) ? value.getTime() : null
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value !== 'string' || !value.trim()) return null
  const raw = value.trim()
  const normalized = raw.replace(' ', 'T')
  const zoned = /(?:Z|[+-]\d{2}:?\d{2})$/i.test(normalized) ? normalized : `${normalized}Z`
  const timestamp = Date.parse(zoned)
  return Number.isFinite(timestamp) ? timestamp : null
}

export function withinDays(value: unknown, days: number, now = Date.now()): boolean {
  if (days <= 0) return true
  const timestamp = orderTimestamp(value)
  return timestamp !== null && timestamp <= now && timestamp >= now - days * 86_400_000
}

export function profitRate(profit: unknown, capital: unknown): number | null {
  const pnl = Number(profit)
  const base = Number(capital)
  return Number.isFinite(pnl) && Number.isFinite(base) && base > 0 ? pnl / base * 100 : null
}
