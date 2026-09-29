export type AccountMode = 'REAL' | 'DEMO'
export function tagRows(value: any, mode: AccountMode): any {
  if (Array.isArray(value)) return value.map(item => tagRows(item, mode))
  if (value && typeof value === 'object') {
    const tagged = Object.fromEntries(Object.entries(value).map(([key, item]) => [key, tagRows(item, mode)]))
    // Only records get realm fields; summary dictionaries must remain numeric.
    if ('id' in value || 'userId' in value) Object.assign(tagged, { accountMode: mode, accountModeLabel: mode === 'REAL' ? '真实账户' : '模拟账户' })
    return tagged
  }
  return value
}
export function mergeAccountLists(results: any[], offset = 0, size?: number) {
  const rows = results.flatMap(r => Array.isArray(r) ? r : r.list || [])
  rows.sort((a, b) => String(b.createdAt || b.purchaseTime || '').localeCompare(String(a.createdAt || a.purchaseTime || '')) || String(b.id || '').localeCompare(String(a.id || ''), 'en', { numeric: true }) || String(a.accountMode).localeCompare(String(b.accountMode)))
  const result: any = { ...(results[0] || {}), success: true, list: size == null ? rows : rows.slice(offset, offset + size), total: results.reduce((sum, r) => sum + Number(r.total ?? (Array.isArray(r) ? r.length : r.list?.length || 0)), 0) }
  if (results.some(r => r.stats)) {
    result.stats = { recordCount: results.reduce((sum, r) => sum + Number(r.stats?.recordCount || 0), 0) }
    for (const key of ['totalYield','paidYield','pendingYield']) result.stats[key] = results.reduce((sum, r) => addDecimal(sum, r.stats?.[key] || '0'), '0')
  }
  return result
}
// Monetary summaries use decimal strings, not floating-point addition.
export function addDecimal(a: string | number, b: string | number): string {
  const parts = [String(a), String(b)].map(v => { if (!/^-?\d+(\.\d+)?$/.test(v)) throw new Error('无效金额汇总'); return v })
  const scale = Math.max(...parts.map(v => v.split('.')[1]?.length || 0))
  const integers = parts.map(v => { const [whole, fraction = ''] = v.split('.'); return BigInt(whole! + fraction.padEnd(scale, '0')) })
  const sum = integers[0]! + integers[1]!, sign = sum < 0n ? '-' : '', digits = (sum < 0n ? -sum : sum).toString().padStart(scale + 1, '0')
  return sign + (scale ? digits.slice(0, -scale) + '.' + digits.slice(-scale) : digits)
}
export function mergeDepositSummary(results: any[]) {
  const output: any = { groups: {} }
  for (const result of results) {
    for (const key of ['count', 'pending', 'historicalTimeUnknown']) output[key] = (output[key] || 0) + Number(result[key] || 0)
    for (const key of ['creditedUsd','userUsd','manualUsd','legacyUsd']) output[key] = addDecimal(output[key] || '0', result[key] || '0')
    for (const [key, value] of Object.entries(result.groups || {})) output.groups[key] = addDecimal(output.groups[key] || '0', value as string)
  }
  return output
}
export function realmCsv(csv: string, mode: AccountMode, includeHeader: boolean): string {
  // Insert a realm column at logical record boundaries, including quoted multiline fields.
  let quoted = false, start = 0, record = 0, out = ''
  csv = csv.replace(/^\uFEFF/, '')
  for (let i = 0; i <= csv.length; i++) {
    if (csv[i] === '"') { if (quoted && csv[i + 1] === '"') i++; else quoted = !quoted }
    if (i === csv.length || (csv[i] === '\n' && !quoted)) {
      const line = csv.slice(start, i).replace(/\r$/, '')
      if (line && (record > 0 || includeHeader)) out += (record === 0 ? '账户类型' : mode === 'REAL' ? '真实账户' : '模拟账户') + ',' + line + '\r\n'
      record++; start = i + 1
    }
  }
  return out
}
