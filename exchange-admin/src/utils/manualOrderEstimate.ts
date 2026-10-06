// Display-only estimate. Decimal arithmetic mirrors the server; Number is used only for rendering.
const SCALE = 10n ** 32n
const MONEY = 10n ** 16n
function decimal(value: unknown): bigint {
  const match = /^([+-]?)(?:(\d+)(?:\.(\d*))?|\.(\d+))(?:[eE]([+-]?\d+))?$/.exec(String(value).trim())
  if (!match) throw new Error('decimal')
  const fraction = match[3] ?? match[4] ?? ''
  let digits = ((match[2] ?? '0') + fraction).replace(/^0+/, '')
  if (!digits) return 0n
  const exponent = Number(match[5] ?? 0), trailing = /0+$/.exec(digits)?.[0].length ?? 0
  const scale = fraction.length - exponent - trailing
  if (!Number.isSafeInteger(exponent) || scale > 16 || digits.length - trailing - scale > 16) throw new Error('range')
  if (trailing) digits = digits.slice(0, -trailing)
  return BigInt(digits) * 10n ** BigInt(32 - scale) * (match[1] === '-' ? -1n : 1n)
}
function divide(a: bigint, b: bigint, mode: 'half' | 'ceil' | 'floor' = 'half'): bigint {
  if (!b) throw new Error('division')
  if (b < 0n) { a = -a; b = -b }
  const q = a / b, r = a % b
  if (mode === 'ceil') return q + (r > 0n ? 1n : 0n)
  if (mode === 'floor') return q - (r < 0n ? 1n : 0n)
  return q + ((r < 0n ? -r : r) * 2n >= b ? (a < 0n ? -1n : 1n) : 0n)
}
const mul = (a: bigint, b: bigint) => a * b / SCALE
const money = (a: bigint, mode: 'half' | 'ceil' = 'half') => divide(a, MONEY, mode) * MONEY
const text = (a: bigint) => {
  const digits = (a < 0n ? -a : a).toString().padStart(33, '0'), fraction = digits.slice(-32).replace(/0+$/, '')
  return (a < 0n ? '-' : '') + digits.slice(0, -32) + (fraction ? '.' + fraction : '')
}
const display = (a: bigint) => Number(text(a))
export function manualOrderEstimate(form: { driver: string; input: string; side: string; leverage: string }, basis: any) {
  if (!basis || !form.input.trim() || !['BUY', 'SELL'].includes(form.side)) return null
  try {
    const p0 = decimal(basis.quotes.openPrice), p1 = decimal(basis.quotes.closePrice)
    const r0 = decimal(basis.quotes.openRate), r1 = decimal(basis.quotes.closeRate)
    const lot = decimal(basis.lotSize), fee = decimal(basis.feePerLot)
    const balance = decimal(basis.walletBefore), leverage = decimal(form.leverage), input = decimal(form.input)
    const step = decimal(basis.quantityStep ?? '0.01'), minimum = decimal(basis.minOrderQuantity ?? '0.01')
    if ([p0,p1,r0,r1,lot,leverage,step,minimum].some(v => v <= 0n) || fee < 0n) return null
    const rate = basis.quotes.marginRate == null ? mul(p0,r0) : decimal(basis.quotes.marginRate)
    if (rate <= 0n) return null
    const unitMargin = divide(lot * rate, leverage)
    const unitProfit = mul(mul(p1-p0,lot),r1) * (form.side === 'BUY' ? 1n : -1n)
    const unitNet = unitProfit-fee
    let quantity: bigint
    if (form.driver === 'QUANTITY') {
      quantity = input
      if (quantity % step) return null
    } else if (form.driver === 'PERCENT' && balance > 0n && input > 0n) {
      quantity = divide(balance * input, 100n * (unitMargin+fee) * step, 'floor') * step
    } else if (form.driver === 'NET' && unitNet !== 0n) {
      quantity = divide(input * SCALE, unitNet * step) * step
    } else return null
    if (quantity < minimum || mul(mul(mul(quantity,lot),p0),r0) < decimal(basis.minOrderNotional ?? 0)) return null
    const margin = money(divide(quantity * lot * rate, SCALE * leverage, 'ceil'), 'ceil')
    // Round only after the full quantity/price/lot/rate product, as the server does.
    const totalFee = money(mul(quantity,fee)), profit = money(quantity * (p1-p0) * lot * r1 * (form.side === 'BUY' ? 1n : -1n) / (SCALE ** 3n)), net = profit-totalFee
    if ([margin,totalFee,profit,net].some(v => (v < 0n ? -v : v) / SCALE >= 10n ** 16n)) return null
    const percent = balance > 0n ? Number(divide((margin+totalFee)*100n*100000000n,balance))/1e8 : null
    return { quantity: display(quantity), margin: display(margin), profit: display(profit), fee: display(totalFee), net: display(net), netText: text(net), percent }
  } catch { return null }
}
