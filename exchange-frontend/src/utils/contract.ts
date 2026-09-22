// Estimates use the same position-value model as the server; settlement uses server decimals.
export const DEFAULT_LEVERAGE = 100

export function leverageLimit(value: unknown, enabled = true): number {
  if (!enabled) return 1
  const limit = Number(value ?? DEFAULT_LEVERAGE)
  return Number.isInteger(limit) && limit >= 1 && limit <= 100 ? limit : DEFAULT_LEVERAGE
}

export function leverageChoices(max: number): number[] {
  return [...new Set([1, 5, 10, 20, 50, 100, max])].filter(value => value <= max).sort((a, b) => a - b)
}

export function contractMargin(quantity: number, lotSize: number, price: number, leverage: number, conversionRate = 1): number {
  if (!Number.isFinite(conversionRate) || conversionRate <= 0) return NaN
  if (![quantity, lotSize, price, leverage].every(value => Number.isFinite(value) && value > 0)) return 0
  return quantity * lotSize * price / leverage * conversionRate
}

export function calculateContractProfit(order: any, currentPrice: number, conversionRate = 1): number {
  if (order.status && order.status !== 'OPEN') return Number(order.profit ?? 0)
  const quantity = Number(order.quantity || 0)
  const openPrice = Number(order.openPrice || 0)
  if (![quantity, openPrice, currentPrice].every(value => Number.isFinite(value) && value > 0)) return Number(order.profit ?? 0)
  if (order.side !== 'BUY' && order.side !== 'SELL') return Number(order.profit ?? 0)
  // NULL snapshots identify legacy orders. Never reprice them using current symbol settings.
  const multiplier = Number(order.lotSize ?? order.leverage ?? 1)
  const difference = order.side === 'BUY' ? currentPrice - openPrice : openPrice - currentPrice
  return difference * quantity * multiplier * conversionRate
}

export function contractEquity(available: number, orders: any[]): number {
  return available + orders.reduce((sum, order) => sum + Number(order.margin || 0) + Number(order.profit ?? 0)
    + (order.lotSize == null ? Number(order.fee || 0) : 0), 0)
}

// The order form trades in 0.01 lots. Reserve both margin and the opening fee.
export function quantityFromAllocation(available: number, percent: number, marginPerLot: number, feePerLot: number): number {
  if (![available, percent, marginPerLot, feePerLot].every(Number.isFinite)
    || available <= 0 || percent <= 0 || marginPerLot <= 0 || feePerLot < 0) return 0
  const budget = available * Math.min(percent, 100) / 100
  const cost = marginPerLot + feePerLot
  let steps = Math.floor(budget / cost * 100)
  if (!Number.isSafeInteger(steps)) return 0
  // Floating point must never round a 100% allocation above the available funds.
  while (steps > 0 && ((steps / 100) * marginPerLot + (steps / 100) * feePerLot) > budget) steps--
  return steps / 100
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
