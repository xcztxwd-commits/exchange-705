import assert from 'node:assert/strict'
import { validIncrement, protectionError, orderTimestamp } from './tradeValidation.ts'
import { contractMargin, quantityFromAllocation, estimateLiquidationPrice } from './contract.ts'

assert(validIncrement(151.27851, 0.00001))
assert(!validIncrement(151.278511, 0.00001))
for (const invalid of [0, -1, NaN, Infinity, 0.001]) assert(!validIncrement(invalid, 0.01))
assert(!protectionError('BUY', 150, 151, 149))
assert(protectionError('SELL', 150, 151, 149))
assert(!protectionError('SELL', 150, 149, 151))
assert(protectionError('BUY', 150, 150, null))
assert(protectionError('BUY', NaN, 151, null))
assert(protectionError('BUY', 150, Infinity, null))
assert(!protectionError('BUY', 150, null, null))
assert.equal(orderTimestamp('2026-09-22T17:00:00'), Date.parse('2026-09-22T17:00:00Z'))
assert.equal(orderTimestamp('2026-09-23T01:00:00+08:00'), Date.parse('2026-09-22T17:00:00Z'))
const perLot = contractMargin(1, 1000, 150, 20, 1 / 150)
const size = quantityFromAllocation(8000, 100, perLot, 30)
assert(size * (perLot + 30) <= 8000)
assert(Number.isNaN(contractMargin(1, 1000, 150, 20, NaN)))
assert.equal(estimateLiquidationPrice(100, [], { symbol: 'TEST', side: 'BUY', quantity: 1, lotSize: 1, price: 150, fee: 0, conversionRate: 1 }), 50)
console.log('Mobile trade validation checks passed')
