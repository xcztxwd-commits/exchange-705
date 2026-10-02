export function optionalPrice(value: number | string | null | undefined): number | null {
  return value == null || String(value).trim() === '' ? null : Number(value)
}

// Validation shared by mobile order entry and protection editing. Prices remain server-authoritative.
export function validIncrement(value: number, step: number): boolean {
  return Number.isFinite(value) && value > 0 && Number.isFinite(step) && step > 0
    && Math.abs(value - Math.round(value / step) * step) <= Math.max(step * 1e-7, Math.abs(value) * Number.EPSILON * 4)
}

export function protectionError(side: string, reference: number, takeProfit: number | null, stopLoss: number | null): boolean {
  if ((takeProfit != null || stopLoss != null) && !(Number.isFinite(reference) && reference > 0)) return true
  const sign = side === 'BUY' ? 1 : -1
  return (takeProfit != null && (!Number.isFinite(takeProfit) || takeProfit <= 0 || (takeProfit - reference) * sign <= 0))
    || (stopLoss != null && (!Number.isFinite(stopLoss) || stopLoss <= 0 || (stopLoss - reference) * sign >= 0))
}

// LocalDateTime is emitted by the UTC backend container without an offset.
export function orderTimestamp(value: string): number {
  if (!value) return NaN
  return Date.parse(/[zZ]|[+-]\d{2}:?\d{2}$/.test(value) ? value : value.replace(' ', 'T') + 'Z')
}
