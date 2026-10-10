import { requireKlineInterval } from './kline.ts'
import type { KLineData, Period } from 'klinecharts'

export function chartPeriod(interval: string): Period {
  requireKlineInterval(interval)
  const match = /^(\d+)(m|h|d|w)$/.exec(interval)
  if (!match) return { span: 1, type: 'minute' }
  const types = { m: 'minute', h: 'hour', d: 'day', w: 'week' } as const
  return { span: Number(match[1]), type: types[match[2] as keyof typeof types] }
}

export function normalizeCandles(rows: unknown[], before = Infinity, interval = ''): KLineData[] {
  const candles = new Map<number, KLineData>()
  for (const item of rows) {
    if (!item || typeof item !== 'object') continue
    const row = item as Record<string, unknown>
    const time = Number(row.kline_timestamp ?? row.timestamp ?? row.time)
    const timestamp = time < 10_000_000_000 ? time * 1000 : time
    const open = Number(row.open_price ?? row.open)
    const high = Number(row.high_price ?? row.high)
    const low = Number(row.low_price ?? row.low)
    const close = Number(row.close_price ?? row.close)
    const volume = Number(row.volume ?? row.vol ?? 0)
    if (![timestamp, open, high, low, close].every(n => Number.isFinite(n) && n > 0)
      || timestamp >= before || high < Math.max(open, close) || low > Math.min(open, close)) continue
    candles.set(timestamp, { timestamp, open, high, low, close, volume: Number.isFinite(volume) && volume >= 0 ? volume : 0 })
  }
  const sorted = [...candles.values()].sort((a, b) => a.timestamp - b.timestamp)
  // Intraday responses can append a quote snapshot, not a period OHLCV candle.
  // Keep hourly session offsets (e.g. US stocks at :30).
  const period = chartPeriod(interval)
  const duration = period.span * (period.type === 'minute' ? 60_000 : 3_600_000)
  const anchor = period.type === 'hour' ? (sorted[0]?.timestamp || 0) : 0
  if (period.type === 'day' && sorted.length > 2) {
    // Accept recurring source session offsets, including both sides of DST.
    // An irregular quote-time tail must not become another daily bar on every poll.
    // ponytail: infer sessions from this page; exceptional session changes need exchange calendar metadata.
    const offsets = new Map<number, number>()
    for (const bar of sorted) {
      const offset = bar.timestamp % 86_400_000
      offsets.set(offset, (offsets.get(offset) || 0) + 1)
    }
    const sessions = [...offsets.keys()].filter(offset => offsets.get(offset)! > 1)
    if (sessions.length) return sorted.filter(bar => sessions.some(offset => {
      const difference = (bar.timestamp % 86_400_000 - offset + 86_400_000) % 86_400_000
      return difference === 0 || difference === 3_600_000 || difference === 82_800_000
    }))
  }
  return interval && ['minute', 'hour'].includes(period.type)
    ? sorted.filter(bar => (bar.timestamp - anchor) % duration === 0)
    : sorted
}

// Only an unbroken newest segment may advance a continuously traded crypto cursor.
// Older disconnected cache rows stay hidden until the source completes that window.
export function contiguousCryptoCandles(candles: KLineData[], before: number, interval: string, category: string): KLineData[] {
  if (!/^Crypto(?:Perpetual)?$/i.test(category) || !candles.length) return candles
  const period = chartPeriod(interval)
  const durations = { second: 1000, minute: 60_000, hour: 3_600_000, day: 86_400_000, week: 604_800_000, month: 0, year: 0 }
  const previous = (timestamp: number): number => timestamp - durations[period.type] * period.span
  if (Number.isFinite(before) && candles[candles.length - 1]!.timestamp !== previous(before)) return []
  let first = candles.length - 1
  while (first > 0 && candles[first - 1]!.timestamp === previous(candles[first]!.timestamp)) first--
  return candles.slice(first)
}

export type HistoryRepairPolicy = { terminal: boolean; pending: boolean; reason: string; nextCursor: number }

// Only explicit backend repair receipts may release a sparse window. Legacy responses retain the continuity guard.
export function historyRepairPolicy(data: unknown): HistoryRepairPolicy | null {
  if (!data || typeof data !== 'object') return null
  const repair = (data as { historyRepair?: unknown }).historyRepair
  if (!repair || typeof repair !== 'object') return null
  const value = repair as { pending?: unknown; gaps?: unknown; nextCursor?: unknown }
  if (typeof value.pending !== 'boolean' || !Array.isArray(value.gaps)) return null
  const reasons = value.gaps.map(gap => gap && typeof gap === 'object' ? (gap as { reason?: unknown }).reason : null)
  if (reasons.some(reason => typeof reason !== 'string')) return null
  const nextCursor = Number(value.nextCursor)
  return { terminal: !value.pending && reasons.length > 0, pending: value.pending,
    reason: String(reasons.find(reason => /protected|frozen|sealed|control_samples|simulation|projection/.test(String(reason))) || reasons[0] || ''),
    nextCursor: Number.isFinite(nextCursor) && nextCursor >= 946684800000 ? nextCursor : 0 }
}

// Quotes only update an existing candle; new OHLCV must come from the candle endpoint.
export function candleFromQuote(last: KLineData | undefined, price: number, time: number, interval: string): KLineData | null {
  if (!last || !Number.isFinite(price) || price <= 0 || !Number.isFinite(time) || time < last.timestamp) return null
  const period = chartPeriod(interval)

  const durations = { second: 1000, minute: 60_000, hour: 3_600_000, day: 86_400_000, week: 604_800_000, month: 0, year: 0 }
  const duration = durations[period.type] * period.span
  if (!duration) return null
  // Preserve the source's session boundary (daily/weekly bars need not start at UTC midnight).
  const anchor = period.type === 'minute' ? Math.floor(last.timestamp / duration) * duration : last.timestamp
  const timestamp = anchor + Math.floor((time - anchor) / duration) * duration
  return timestamp === last.timestamp
    ? { ...last, high: Math.max(last.high, price), low: Math.min(last.low, price), close: price }
    : null // A quote cannot supply a new candle's open, extremes or volume.
}
