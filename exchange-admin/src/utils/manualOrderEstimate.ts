// Display-only estimate; creation always requires the server's decimal calculation and token.
export function manualOrderEstimate(form: { driver: string; input: string; side: string; leverage: string }, basis: any) {
  if (!basis || !form.input.trim() || !['BUY', 'SELL'].includes(form.side)) return null
  const { openPrice, closePrice, openRate, closeRate } = basis.quotes
  const [p0, p1, r0, r1, lot, fee, balance, leverage, input] = [openPrice, closePrice, openRate, closeRate, basis.lotSize, basis.feePerLot, basis.walletBefore, form.leverage, form.input].map(Number) as [number, number, number, number, number, number, number, number, number]
  if (![p0,p1,r0,r1,lot,fee,balance,leverage,input].every(Number.isFinite) || Math.min(p0,p1,r0,r1,lot,leverage) <= 0 || fee < 0) return null
  const margin = lot * p0 * r0 / leverage
  const profit = (p1 - p0) * lot * r1 * (form.side === 'BUY' ? 1 : -1)
  const unitNet = profit - fee
  const roundLots = (n: number) => Math.round((n + Number.EPSILON) * 100) / 100
  const quantity = form.driver === 'QUANTITY' ? input : form.driver === 'PERCENT' ? (balance > 0 ? roundLots(balance * input / 100 / (margin + fee)) : NaN) : form.driver === 'NET' && unitNet !== 0 ? roundLots(input / unitNet) : NaN
  if (!Number.isFinite(quantity) || quantity <= 0) return null
  const clean = (n: number) => Number(n.toFixed(8))
  return { quantity, margin: clean(quantity * margin), profit: clean(quantity * profit), fee: clean(quantity * fee), net: clean(quantity * unitNet), percent: balance > 0 ? clean(quantity * (margin + fee) / balance * 100) : null }
}
