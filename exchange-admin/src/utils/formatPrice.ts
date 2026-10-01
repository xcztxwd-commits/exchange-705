const priceFormatter = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 3,
  maximumFractionDigits: 3,
})

// Display only: never round prices used by requests or calculations.
export function formatPrice(value: number | string | null | undefined): string {
  if (value == null || (typeof value === 'string' && !value.trim())) return '—'
  const price = Number(value)
  return Number.isFinite(price) ? priceFormatter.format(price) : '—'
}
