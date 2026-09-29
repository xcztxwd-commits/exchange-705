// Estimates use the same position-value model as the server; settlement uses server decimals.
export const DEFAULT_LEVERAGE = 100

// Settled records must never be presented with a live quote as their settlement price.
export function contractDisplayPrice(order: { status?: string; closePrice?: unknown; currentPrice?: unknown }): number | null {
  const price = Number(order.status === 'CLOSED' ? order.closePrice : order.currentPrice)
  return Number.isFinite(price) && price > 0 ? price : null
}

export function leverageLimit(value: unknown, enabled = true): number {
  if (!enabled) return 1
  const limit = Number(value ?? DEFAULT_LEVERAGE)
  return Number.isInteger(limit) && limit >= 1 && limit <= 100 ? limit : DEFAULT_LEVERAGE
}

export function leverageChoices(max: number): number[] {
  return [...new Set([1, 5, 10, 20, 50, 100, max])].filter(value => value <= max).sort((a, b) => a - b)
}

export function contractMargin(quantity: number, lotSize: number, price: number, leverage: number, conversionRate = 1, marginBaseToUsdRate?: number): number {
  if (marginBaseToUsdRate !== undefined) {
    if (!Number.isFinite(marginBaseToUsdRate) || marginBaseToUsdRate <= 0) return NaN
    if (![quantity, lotSize, leverage].every(value => Number.isFinite(value) && value > 0)) return 0
    return decimalMargin(quantity, lotSize, 1, leverage, marginBaseToUsdRate)
  }
  if (!Number.isFinite(conversionRate) || conversionRate <= 0) return NaN
  if (![quantity, lotSize, price, leverage].every(value => Number.isFinite(value) && value > 0)) return 0
  return decimalMargin(quantity, lotSize, price, leverage, conversionRate)
}

export function calculateContractProfit(order: any, currentPrice: number, conversionRate = 1): number {
  if (order.status && order.status !== 'OPEN') return Number(order.profit ?? 0)
  const quantity = Number(order.quantity || 0)
  const openPrice = Number(order.openPrice || 0)
  if (![quantity, openPrice, currentPrice].every(value => Number.isFinite(value) && value > 0)) return Number(order.profit ?? 0)
  if (order.side !== 'BUY' && order.side !== 'SELL') return Number(order.profit ?? 0)
  // NULL snapshots identify legacy orders. Never reprice them using current symbol settings.
  const multiplier = Number(order.lotSize ?? order.leverage ?? 1)
  try {
    const difference = (decimal(currentPrice)-decimal(openPrice)) * BigInt(order.side === 'BUY' ? 1 : -1)
    return Number(difference * decimal(quantity) * decimal(multiplier) * decimal(conversionRate, false) / (SCALE*SCALE*SCALE)) / Number(SCALE)
  } catch { return NaN }
}

export function contractEquity(available: number, orders: any[]): number {
  return decimalSum(available, ...orders.flatMap(order => [order.margin || 0, order.profit ?? 0, order.lotSize == null ? order.fee || 0 : 0]))
}

// Use the configured quantity grid; reserve margin and the complete round-trip fee.
export function quantityFromAllocation(available: number, percent: number, marginPerLot: number, feePerLot: number, step = 0.01, minimum = 0.01, notionalPerUnit = 0, minNotional = 0): number {
  if (![available,percent,marginPerLot,feePerLot,step,minimum,notionalPerUnit,minNotional].every(Number.isFinite) || available<=0 || percent<=0 || marginPerLot<=0 || feePerLot<0 || step<=0) return 0
  try {
    const budget=decimal(available)*decimal(Math.min(percent,100))/decimal(100)
    const cost=decimal(marginPerLot, false)+decimal(feePerLot), grid=decimal(step)
    const q=budget*SCALE/(cost*grid)*grid
    if(q<decimal(minimum) || q*decimal(notionalPerUnit, false)<decimal(minNotional)*SCALE)return 0
    return decimalDisplay(q)
  } catch { return 0 }
}

export interface LiquidationPosition {
  symbol: string
  side: string
  quantity: number
  openPrice: number
  currentPrice: number
  margin: number
  fee: number
  lotSize: number | null
  leverage: number
  conversionRate: number
}

// Match the server's account-equity-zero rule. Other symbols and FX rates stay fixed.
// Pending orders are excluded: their frozen funds are already absent from available.
export function estimateLiquidationPrice(
  available: number, positions: LiquidationPosition[],
  order: { symbol: string; side: 'BUY' | 'SELL'; quantity: number; lotSize: number; price: number; fee: number; conversionRate: number },
): number | null {
  if (![available, order.quantity, order.lotSize, order.price, order.fee, order.conversionRate].every(Number.isFinite)
    || available < 0 || order.quantity <= 0 || order.lotSize <= 0 || order.price <= 0 || order.fee < 0 || order.conversionRate <= 0) return null
  let equity = available - order.fee
  let exposure = (order.side === 'BUY' ? 1 : -1) * order.quantity * order.lotSize * order.conversionRate
  for (const position of positions) {
    const units = position.lotSize ?? position.leverage
    if (![position.quantity, units, position.openPrice, position.currentPrice, position.margin, position.fee, position.conversionRate].every(Number.isFinite)
      || position.quantity <= 0 || units <= 0 || position.openPrice <= 0 || position.currentPrice <= 0 || position.conversionRate <= 0
      || !['BUY', 'SELL'].includes(position.side)) return null
    const slope = (position.side === 'BUY' ? 1 : -1) * position.quantity * units * position.conversionRate
    const mark = position.symbol === order.symbol ? order.price : position.currentPrice
    equity += position.margin + (position.lotSize == null ? position.fee : 0) + (mark - position.openPrice) * slope
    if (position.symbol === order.symbol) exposure += slope
  }
  if (equity <= 0 || Math.abs(exposure) < 1e-12) return null
  const price = order.price - equity / exposure
  return Number.isFinite(price) && price > 0 ? price : null
}

// Exact base-10 integer arithmetic for quantity grids and allocation budgets (16 decimals).
const SCALE = BigInt('10000000000000000')
function decimal(value: unknown, exact = true): bigint {
  const text = String(value)
  if (!/^[+-]?\d+(?:\.\d+)?(?:e[+-]?\d+)?$/i.test(text)) throw new Error('Invalid decimal')
  const [mantissa, exponent = '0'] = text.toLowerCase().split('e')
  const negative = mantissa!.startsWith('-')
  const [whole, fraction = ''] = mantissa!.replace(/^[+-]/, '').split('.')
  const shift = 16 + Number(exponent) - fraction.length
  if (Math.abs(shift) > 64) throw new Error('Decimal out of range')
  let n = BigInt(whole! + fraction)
  if (shift < 0) { const divisor = BigInt(10) ** BigInt(-shift); if(exact && n % divisor) throw new Error('Decimal precision'); n /= divisor }
  else n *= BigInt(10) ** BigInt(shift)
  if (n >= SCALE * SCALE) throw new Error('Decimal out of range')
  return negative ? -n : n
}
export function quantityUnit(spec: any, lots = '手'): string {
  return spec?.quantityUnitType === 'BASE_ASSET' ? spec.quantityAsset || spec.baseCurrency || '—' : spec?.quantityUnitType === 'SHARE' ? '股' : lots
}
export function validQuantity(quantity: unknown, spec: any): boolean {
  try { const q=decimal(quantity), step=decimal(spec?.quantityStep ?? '0.01'), min=decimal(spec?.minOrderQuantity ?? '0.01'); return step>BigInt(0) && q>=min && q>BigInt(0) && q%step===BigInt(0) } catch { return false }
}
export function displayFee(value: unknown): string {
  const n=Number(value)
  if(!Number.isFinite(n))return '—'
  if(n>0 && n<0.01)return '<0.01'
  return n.toFixed(2)
}
export function decimalProduct(...values: number[]): number {
  try { let n=SCALE, denominator=BigInt(1); for(const v of values){n*=decimal(v,false);denominator*=SCALE} return decimalDisplay(n/denominator) } catch {return NaN}
}

function decimalDisplay(n: bigint): number {
  const digits=(n<BigInt(0)?-n:n).toString().padStart(17,'0')
  return Number((n<BigInt(0)?'-':'')+digits.slice(0,-16)+'.'+digits.slice(-16))
}
export function decimalSum(...values: unknown[]): number {
  try { return decimalDisplay(values.reduce<bigint>((sum,v)=>sum+decimal(v,false),BigInt(0))) } catch { return NaN }
}
function decimalMargin(q: number, lot: number, price: number, leverage: number, rate: number): number {
  try {
    const numerator=decimal(q)*decimal(lot)*decimal(price, false)*decimal(rate, false), denominator=decimal(leverage)*SCALE*SCALE
    return decimalDisplay((numerator+denominator-BigInt(1))/denominator)
  } catch {return NaN}
}
