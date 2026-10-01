import assert from 'node:assert/strict'
import { formatPrice } from '../src/utils/formatPrice.ts'
import { readFileSync } from 'node:fs'

for (const [value, expected] of [
  [0, '0.000'], [12, '12.000'], ['12.3', '12.300'],
  ['12345.6789', '12,345.679'], [1.2344, '1.234'], [1.2345, '1.235'],
  [0.00001, '0.000'], [-1.2345, '-1.235'], ['1e-3', '0.001'],
  [null, '—'], [undefined, '—'], ['', '—'], ['  ', '—'],
  ['bad', '—'], [NaN, '—'], [Infinity, '—'], [-Infinity, '—'],
]) assert.equal(formatPrice(value), expected, String(value))

const quote = { openPrice: '1.23456789', closePrice: '2.34567891' }
const original = JSON.stringify(quote)
formatPrice(quote.openPrice)
formatPrice(quote.closePrice)
assert.equal(JSON.stringify(quote), original, 'Display must not change raw prices')

const source = file => readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')
for (const file of [
  'views/Orders.vue', 'views/Symbols.vue', 'views/AiControl.vue',
  'components/ManualContractOrder.vue', 'components/MarketMinutePicker.vue',
  'components/AccountInspection.vue', 'utils/orderShare.ts',
]) assert.match(source(file), /import \{ formatPrice \}/, `${file} must use shared price formatting`)

assert.doesNotMatch(source('views/Orders.vue'), /formatMoney\(row\.(?:openPrice|closePrice|stopLoss|takeProfit)/)
assert.doesNotMatch(source('views/Symbols.vue'), /\{\{ row\.currentPrice/)
assert.doesNotMatch(source('views/AiControl.vue'), /\{\{ preview\.(?:startPrice|summary\.(?:minPrice|maxPrice))/)
assert.doesNotMatch(source('components/ManualContractOrder.vue'), /\{\{ result\.quotes\.(?:openPrice|closePrice)/)
assert.doesNotMatch(source('utils/orderShare.ts'), /maximumFractionDigits: 16/)
// Changing display precision must not lower editable/trading precision.
assert.match(source('views/AiControl.vue'), /currentSymbol\.value\?\.pricePrecision/)
assert.match(source('views/Symbols.vue'), /v-model="formData\.pricePrecision"/)
console.log('PASS: three-decimal admin prices, invalid values, coverage and raw-price preservation')

// Exercise every poster path with a text-capturing canvas, without a browser dependency.
const { drawSharePoster, shareCopy, shareTemplates } = await import('../src/utils/orderShare.ts')
const order = { id: 'PRICE-TEST', symbol: 'TESTUSD', kind: 'contract', buy: true, profit: 10,
  openPrice: 123.4, closePrice: 123.56789, quantity: null, leverage: null, margin: 100,
  fee: null, amount: null, openTime: '2026-09-01 10:00:00', closeTime: '2026-09-01 10:01:00', currency: 'USD' }
const chart = { candles: [{ timestamp: 1, open: 123, high: 124, low: 122, close: 123 }], recent: true }
for (const template of shareTemplates) {
  const text = []
  const context = new Proxy({
    fillText: value => text.push(value), measureText: value => ({ width: String(value).length * 4 }),
    createLinearGradient: () => ({ addColorStop() {} }), createRadialGradient: () => ({ addColorStop() {} }),
  }, { get: (target, key) => target[key] ?? (() => {}) })
  drawSharePoster({ getContext: () => context }, order, { template, mode: 'amount', quantity: false,
    capital: false, fee: false, leverage: false, orderId: false, openTime: false }, shareCopy('en'), 'TEST', 'UTC', undefined, chart, {})
  assert.ok(text.includes('123.400'), `${template}: padded entry price`)
  assert.ok(text.includes('123.568'), `${template}: rounded exit price`)
}
assert.equal(order.closePrice, 123.56789)
console.log('PASS: all 16 poster templates render three-decimal prices without changing order data')
