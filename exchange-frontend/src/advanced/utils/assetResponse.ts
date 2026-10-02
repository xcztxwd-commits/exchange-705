type AssetField = `${'fund' | 'contract' | 'option'}${'Balance' | 'Frozen'}`

// Validate the whole displayed snapshot before changing any visible account amount.
export function readAssetResponse<K extends AssetField>(raw: unknown, fields: readonly K[], message: string): Record<K, number> {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw) || (raw as any).success === false) throw new Error(message)
  const source = raw as Record<string, unknown>
  const entries = fields.map(field => {
    const suffix = field.endsWith('Balance') ? 'Balance' : 'Frozen'
    const group = source[field.slice(0, -suffix.length)] as Record<string, unknown> | undefined
    // An explicit zero/null must not silently fall through to a legacy nested field.
    const value = Object.prototype.hasOwnProperty.call(source, field) ? source[field] : group?.[suffix === 'Balance' ? 'available' : 'frozen']
    if (typeof value !== 'number' && (typeof value !== 'string' || !/^[+-]?\d+(?:\.\d+)?(?:e[+-]?\d+)?$/i.test(value.trim()))) throw new Error(message)
    const amount = Number(value)
    if (!Number.isFinite(amount)) throw new Error(message)
    return [field, amount] as const
  })
  for (const suffix of ['Balance', 'Frozen', '']) {
    if (!Number.isFinite(entries.filter(([field]) => field.endsWith(suffix)).reduce((total, [, amount]) => total + amount, 0))) throw new Error(message)
  }
  return Object.fromEntries(entries) as Record<K, number>
}
