export type GenerationConstraints = {
  openLocal: string; openOffset: string; closeLocal: string; closeOffset: string
  side: string; leverage: string; quantity: string; percent: string; net: string; closePrice: string
}
export const generationConstraints = (): GenerationConstraints => ({ openLocal: '', openOffset: '', closeLocal: '', closeOffset: '', side: '', leverage: '', quantity: '', percent: '', net: '', closePrice: '' })

// UI guard only; generation and monetary validation remain authoritative on the server.
export function generationConstraintError(calculation: Record<string, unknown>, values: GenerationConstraints, generated = false, closePrice?: unknown, leverage?: unknown) {
  for (const field of ['quantity', 'percent'] as const) if (values[field].trim()) {
    const actual = Number(calculation[field]), expected = Number(values[field])
    const tolerance = generated ? Math.abs(expected) * 0.05 : field === 'percent' ? 0.010000001 : 0
    if (calculation[field] == null || !Number.isFinite(actual) || !Number.isFinite(expected) || Math.abs(actual - expected) > tolerance + Number.EPSILON * Math.max(Math.abs(actual), Math.abs(expected)))
      return `当前预览不满足填写的${field === 'quantity' ? '手数' : '仓位比例'}，请一键生成或清空该条件`
  }
  if (values.leverage.trim()) {
    const actual = Number(leverage), expected = Number(values.leverage)
    const tolerance = generated ? Math.abs(expected) * 0.05 : 0
    if (leverage == null || !Number.isFinite(actual) || !Number.isFinite(expected) || Math.abs(actual - expected) > tolerance + Number.EPSILON * Math.max(Math.abs(actual), Math.abs(expected)))
      return '当前预览不满足填写的杠杆，请一键生成或清空该条件'
  }
  if (values.closePrice.trim()) {
    const actual = Number(closePrice), expected = Number(values.closePrice)
    if (closePrice == null || !Number.isFinite(actual) || !Number.isFinite(expected) || Math.abs(actual - expected) > Math.abs(expected) * 0.05 + Number.EPSILON * Math.max(Math.abs(actual), Math.abs(expected)))
      return '当前预览的平仓价超出目标价格 ±5%，请一键生成或清空该条件'
  }
  return ''
}

// Only filled input fields constrain generation; generated preview values stay separate.
export function generationRequest(form: any, values: GenerationConstraints) {
  if (!form.userId || !form.symbol) throw new Error('请先选择用户与品种')
  const request: Record<string, unknown> = { userId: form.userId, symbol: form.symbol, timezone: form.timezone, walletEnabled: form.walletEnabled, historyEnabled: form.historyEnabled }
  for (const field of ['open', 'close'] as const) if (values[`${field}Local`].trim()) {
    request[`${field}Local`] = values[`${field}Local`]
    request[`${field}Offset`] = values[`${field}Offset`] || undefined
  }
  if (values.side.trim()) request.side = values.side
  if (values.leverage.trim()) {
    if (!Number.isFinite(Number(values.leverage))) throw new Error('填写的杠杆无效')
    request.leverage = values.leverage.trim()
  }
  for (const field of ['quantity', 'percent', 'net', 'closePrice'] as const) if (values[field].trim()) {
    const value = values[field].trim()
    if (!Number.isFinite(Number(value))) throw new Error('填写的数值无效')
    request[field === 'net' ? 'targetNet' : field === 'closePrice' ? 'targetClosePrice' : field] = value
  }
  return request
}
