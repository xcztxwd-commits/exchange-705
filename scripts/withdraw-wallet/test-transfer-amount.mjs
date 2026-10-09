import assert from 'node:assert/strict'
import { formatTransferAmount } from '../../exchange-frontend/src/utils/withdrawalWallet.ts'

for (const [balance, expected] of [
  [253442.81187252363, '253442.81'],
  [4.999999999999999, '4.99'],
  [1.13, '1.13'],
  [120.5, '120.50'],
  [30, '30.00'],
  [0.01, '0.01'],
  [0.0099999, '0.00'],
  [1e-7, '0.00'],
  [0, '0.00'],
]) {
  const amount = formatTransferAmount(balance)
  assert.equal(amount, expected)
  assert.ok(Number(amount) <= balance, 'the transfer must not exceed the available balance')
}
for (const invalid of [NaN, Infinity, -Infinity]) assert.equal(formatTransferAmount(invalid), '')
assert.equal(formatTransferAmount(-1.239), '-1.23', 'negative input remains invalid for submission')
console.log('Transfer amount precision checks passed')
