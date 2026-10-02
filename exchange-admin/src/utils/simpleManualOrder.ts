export const simpleFields = ['openPrice', 'closePrice', 'leverage', 'quantity', 'side', 'targetNet'] as const
export type SimpleField = typeof simpleFields[number]
export function simpleConditions(): Record<SimpleField, string> {
  return { openPrice: '', closePrice: '', leverage: '', quantity: '', side: '', targetNet: '' }
}
export function simplePayload(form: any, conditions: Record<SimpleField, string>, symbol: any, timezone: string) {
  if (!form.symbol) throw new Error('请选择品种')
  if (!form.userId && (form.walletEnabled || form.historyEnabled)) throw new Error('未绑定订单不能入钱包或回填历史权益')
  if (form.historyEnabled && !form.walletEnabled) throw new Error('历史开启必须同时开启钱包入账')
  const payload: Record<string, unknown> = {
    userId: form.userId || null, symbol: form.symbol, timezone,
    specVersion: symbol?.spec_version ?? null, quantityUnitType: symbol?.quantity_unit_type ?? null,
    allowNetAdjustment: form.allowNetAdjustment, netTolerance: form.allowNetAdjustment ? (form.netTolerance.trim() || '5') : '0',
    walletEnabled: form.walletEnabled, historyEnabled: form.historyEnabled,
  }
  for (const field of simpleFields) {
    const value = conditions[field].trim()
    if (value && field !== 'side' && !/^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(value)) throw new Error('价格、杠杆、手数、净收益必须为有效数字')
    payload[field] = value || null
  }
  return payload
}
