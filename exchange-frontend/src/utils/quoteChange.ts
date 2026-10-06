/** Missing quote statistics are unknown, never an unchanged market. */
export function quoteNumber(value: unknown): number {
  if ((typeof value !== 'number' && typeof value !== 'string') || String(value).trim() === '') return NaN
  const number = Number(value)
  return Number.isFinite(number) ? number : NaN
}

export function formatChangePercent(value: unknown, signed = false): string {
  const number = quoteNumber(value)
  if (!Number.isFinite(number)) return '—'
  return `${signed && number > 0 ? '+' : ''}${number.toFixed(2)}%`
}
