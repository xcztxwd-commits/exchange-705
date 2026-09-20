// Estimates use the same position-value model as the server; settlement uses server decimals.
export const DEFAULT_LEVERAGE = 100

export function leverageLimit(value: unknown): number {
  const limit = Number(value ?? DEFAULT_LEVERAGE)
  return Number.isInteger(limit) && limit >= 1 && limit <= 100 ? limit : DEFAULT_LEVERAGE
}

export function leverageChoices(max: number): number[] {
  return [...new Set([1, 2, 5, 10, 20, 25, 50, 75, 100, max])].filter(value => value <= max).sort((a, b) => a - b)
}

export function contractMargin(quantity: number, lotSize: number, price: number, leverage: number): number {
  if (![quantity, lotSize, price, leverage].every(value => Number.isFinite(value) && value > 0)) return 0
  return quantity * lotSize * price / leverage
}

export function calculateContractProfit(order: any, currentPrice: number): number {
  if (order.status && order.status !== 'OPEN') return Number(order.profit || 0)
  const quantity = Number(order.quantity || 0)
  const openPrice = Number(order.openPrice || 0)
  if (![quantity, openPrice, currentPrice].every(value => Number.isFinite(value) && value > 0)) return Number(order.profit || 0)
  if (order.side !== 'BUY' && order.side !== 'SELL') return Number(order.profit || 0)
  // NULL snapshots identify legacy orders. Never reprice them using current symbol settings.
  const multiplier = Number(order.lotSize ?? order.leverage ?? 1)
  const difference = order.side === 'BUY' ? currentPrice - openPrice : openPrice - currentPrice
  return difference * quantity * multiplier
}

export function contractEquity(available: number, orders: any[]): number {
  return available + orders.reduce((sum, order) => sum + Number(order.margin || 0) + Number(order.profit || 0)
    + (order.lotSize == null ? Number(order.fee || 0) : 0), 0)
}
