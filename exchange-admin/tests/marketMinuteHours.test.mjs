import assert from 'node:assert/strict'
import { marketMinuteHours, isCryptoMarket } from '../src/utils/marketMinuteHours.ts'

for (const category of ['Crypto', 'CryptoPerpetual', 'CRYPTO']) {
  const hours = marketMinuteHours(category, [])
  assert.equal(hours.length, 24)
  assert.equal(hours[0], '00')
  assert.equal(hours[23], '23')
  assert.equal(isCryptoMarket(category), true)
}
// Sparse or weekend data must not change a crypto market into a session-limited market.
assert.equal(marketMinuteHours('Crypto', ['2026-09-26T12:00']).length, 24)
assert.deepEqual(marketMinuteHours('Forex', ['2026-09-28T09:00', '2026-09-28T09:01', '2026-09-28T10:00']), ['09', '10'])
assert.deepEqual(marketMinuteHours('Forex', []), [])
assert.equal(isCryptoMarket(''), false)
console.log('marketMinuteHours checks passed')
