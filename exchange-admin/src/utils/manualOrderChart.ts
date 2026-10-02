export type OrderCandle = { timestamp: number; local: string; offset: string; price: string; low: string; high: string; close: string }
export type OrderChartRange = { open: OrderCandle; close: OrderCandle }

// Number is only for drawing. Selected prices retain their original ledger-precision strings.
export function orderChartCandles(values: unknown, now = Date.now()): OrderCandle[] {
  const rows = new Map<number, OrderCandle>()
  if (!Array.isArray(values)) return []
  for (const value of values) {
    if (!value || !Number.isSafeInteger(value.timestamp) || value.timestamp < 0 || value.timestamp % 60000 || value.timestamp + 60000 > now) continue
    const prices = ['price', 'low', 'high', 'close'].map(key => Number(value[key])) as [number, number, number, number]
    if (prices.some(p => !Number.isFinite(p) || p <= 0) || prices[1] > prices[2] || prices[0] < prices[1] || prices[0] > prices[2] || prices[3] < prices[1] || prices[3] > prices[2]) continue
    if (typeof value.local !== 'string' || typeof value.offset !== 'string') continue
    rows.set(value.timestamp, { timestamp: value.timestamp, local: value.local, offset: value.offset, price: String(value.price), low: String(value.low), high: String(value.high), close: String(value.close) })
  }
  return [...rows.values()].sort((a, b) => a.timestamp - b.timestamp)
}

export function orderChartRange(rows: OrderCandle[], first: number, last: number): OrderChartRange {
  if (!Number.isFinite(first) || !Number.isFinite(last) || rows.length < 2) throw new Error('至少选择两根已结束K线')
  const index = (value: number) => Math.max(0, Math.min(rows.length - 1, Math.round(value)))
  const a = index(first), b = index(last), open = rows[Math.min(a, b)]!, close = rows[Math.max(a, b)]!
  if (open.timestamp >= close.timestamp) throw new Error('至少拖选两根K线，开仓必须早于平仓')
  return { open, close }
}

export function orderMinuteTimestamp(local: string, offset: string): number | undefined {
  if (!local || !offset) return undefined
  const value = Date.parse(`${local}${offset}`)
  return Number.isFinite(value) ? value : undefined
}

// ECharts empty-filter preserves candle indices but still counts offscreen prices in its automatic y extent.
export function orderChartPriceExtent(rows: OrderCandle[], start = 0, end = 100) {
  if (!rows.length) return undefined
  const index = (percent: number) => Math.max(0, Math.min(100, Number.isFinite(percent) ? percent : 0)) / 100 * (rows.length - 1)
  const a = index(start), b = index(end)
  let low = Infinity, high = -Infinity
  for (let i = Math.floor(Math.min(a, b)); i <= Math.ceil(Math.max(a, b)); i++) {
    low = Math.min(low, Number(rows[i]!.low)); high = Math.max(high, Number(rows[i]!.high))
  }
  const padding = (high - low || high * 0.001) * 0.06
  return { min: Math.max(0, low - padding), max: high + padding }
}
