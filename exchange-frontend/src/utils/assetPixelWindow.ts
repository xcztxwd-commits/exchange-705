export type AssetPoint = { time: number; value: number | null; quality?: string; closeAt?: number | null; bucketStart?: number; bucketEnd?: number; filled?: boolean }

export type AssetHistory = {
  schemaVersion?: number; basisVersion?: string; points: Record<string, unknown>[];
  total: string | null; from: number; asOf: number; intervalMs: number;
  income: string; incomePercent?: string | null; timezone: string;
  live?: Record<string, unknown>; valuationStatus?: string;
  extrema?: { high: { time: number; value: string } | null; low: { time: number; value: string } | null };
}

/** NULL is unavailable, not Number(null) === 0. Live remains distinct in the API. */
export function assetHistoryPoints(data: AssetHistory): AssetPoint[] {
  if (!Number.isFinite(data.from) || !Number.isFinite(data.asOf) || data.asOf <= data.from || !Number.isFinite(data.intervalMs) || data.intervalMs <= 0
      || data.income == null || !Number.isFinite(Number(data.income)) || data.total !== null && !Number.isFinite(Number(data.total))) throw new Error('Invalid history')
  if (data.schemaVersion === 2 && data.basisVersion !== 'net_equity_v1') throw new Error('Unsupported equity basis')
  const raw = [...data.points, ...(data.schemaVersion === 2 && data.live ? [data.live] : [])]
  return raw.map((p, i) => {
    const time = Number(p.time), value = p.value == null ? null : Number(p.value)
    if (!Number.isFinite(time) || value !== null && !Number.isFinite(value) || time < data.from || time > data.asOf || i > 0 && time < Number(raw[i - 1]!.time)) throw new Error('Invalid history point')
    return { time, value, quality: String(p.quality || p.valuationStatus || 'COMPLETE'), closeAt: p.closeAt == null ? null : Number(p.closeAt), bucketStart: p.bucketStart == null ? undefined : Number(p.bucketStart), bucketEnd: p.bucketEnd == null ? undefined : Number(p.bucketEnd) }
  })
}

export function assetScrubTime(clientX: number, left: number, width: number, from: number, to: number): number {
  return from + Math.max(0, Math.min(1, (clientX - left) / Math.max(1, width))) * (to - from)
}

export function assetHighlight(time: number, selectedTime: number | null): number {
  return selectedTime !== null && time > selectedTime ? .16 : 1
}

/** Last observed value at a real timestamp. Missing history stays missing. */
export function assetPointIndex(points: AssetPoint[], time: number, maxGap: number): number {
  let left = 0, right = points.length
  while (left < right) {
    const middle = (left + right) >>> 1
    if (points[middle]!.time <= time) left = middle + 1
    else right = middle
  }
  const index = left - 1
  if (index >= 0 && points[index]!.value === null) return index
  return index >= 0 && time - points[index]!.time <= maxGap ? index : -1
}

/** Rendering density is separate from sampling resolution. Never stretch observed history. */
export function assetColumnTime(from: number, to: number, column: number, count = 68): number {
  return from + (to - from) * column / (count - 1)
}

/** Presentation-only zero fill; keep the observation list unchanged. */
export function assetDisplayValue(points: AssetPoint[], time: number, maxGap: number): number | null {
  const last = assetPointIndex(points, time, Infinity)
  if (last >= 0 && points[last]!.value === null) return null
  const index = assetPointIndex(points, time, maxGap)
  return index < 0 ? 0 : points[index]!.value
}

/** Four evenly spaced money ticks. Positive balances always retain the zero origin. */
export function assetPriceTicks(values: number[]): number[] {
  const low = Math.min(0, ...values), high = Math.max(0, ...values)
  if (low === high) return [0, 1, 2, 3]
  const nice = (raw: number) => {
    const power = 10 ** Math.floor(Math.log10(Math.max(.01, raw)))
    const candidate = [1, 2, 2.5, 5, 10].find(n => n * power >= raw)! * power
    return candidate < 1 ? Math.ceil(candidate * 100 - 1e-9) / 100 : candidate
  }
  let step = nice((high - low) / 3), bottom = Math.floor(low / step) * step
  while (bottom + step * 3 < high) {
    step = nice(step * 1.01); bottom = Math.floor(low / step) * step
  }
  return Array.from({ length: 4 }, (_, i) => Number((bottom + i * step).toPrecision(12)))
}
