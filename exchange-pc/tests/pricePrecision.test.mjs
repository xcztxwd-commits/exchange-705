import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { computed, nextTick, reactive, ref, watch } from 'vue'

const desktop = readFileSync(new URL('../src/views/DesktopTrade.vue', import.meta.url), 'utf8')
const chart = readFileSync(new URL('../src/components/KlineChart.vue', import.meta.url), 'utf8')
const compile = source => ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
const marketStore = reactive({ symbols: [{ symbol: 'JPY=X', pricePrecision: 3 }, { symbol: 'EURUSD=X', pricePrecision: 5 }], priceMap: { 'JPY=X': { price: 157.59123456 } } })
const currentSymbol = ref('JPY=X')
const formatter = desktop.match(/const formatSymbolPrice = [\s\S]*?\n};/)[0]
const getPrice = desktop.match(/const getSymbolPrice = [^\n]+/)[0]
const functions = new Function('marketStore', 'currentSymbol', compile(formatter + '\n' + getPrice) + '\nreturn { formatSymbolPrice, getSymbolPrice }')(marketStore, currentSymbol)
const { formatSymbolPrice } = functions
const rawPrice = marketStore.priceMap['JPY=X'].price
for (const digits of [3, 5, 0, 8, 2]) {
  marketStore.symbols = [{ symbol: 'JPY=X', pricePrecision: digits }, { symbol: 'EURUSD=X', pricePrecision: 5 }]
  assert.equal(functions.getSymbolPrice('JPY=X'), rawPrice.toFixed(digits))
  assert.equal(formatSymbolPrice(1.12, 'EURUSD=X'), '1.12000', 'Orders use their own symbol, not the selected symbol')
  assert.equal(formatSymbolPrice(0), (0).toFixed(digits))
}
for (const invalid of [null, undefined, '', ' ', NaN, Infinity, 'bad']) assert.equal(formatSymbolPrice(invalid), '—')
for (const precision of [undefined, null, -1, 9, 2.5, '3']) {
  marketStore.symbols[0].pricePrecision = precision
  assert.equal(formatSymbolPrice(rawPrice), rawPrice.toFixed(2))
}
assert.equal(formatSymbolPrice('157.59123', 'UNKNOWN'), '157.59')
assert.equal(marketStore.priceMap['JPY=X'].price, rawPrice, 'Display rounding must never mutate trading prices')

// Selected metadata must follow refreshed catalog objects, without reselecting the symbol.
const infoSource = desktop.match(/const currentSymbolInfo = [^\n]+/)[0]
const info = new Function('computed', 'marketStore', 'currentSymbol', compile(infoSource) + '\nreturn currentSymbolInfo')(computed, marketStore, currentSymbol)
marketStore.symbols = [{ symbol: 'JPY=X', pricePrecision: 0 }]
assert.equal(info.value.pricePrecision, 0)
marketStore.symbols = [{ symbol: 'JPY=X', pricePrecision: 5 }]
assert.equal(info.value.pricePrecision, 5)
currentSymbol.value = 'UNKNOWN'
assert.equal(info.value, null)

// Refreshes share one promise: focus during initial load must not skip initialization.
const refreshSource = desktop.match(/let lastSymbolRefresh = [\s\S]*?\n};/)[0]
let calls = 0, finish
const refresh = new Function('marketStore', compile(refreshSource) + '\nreturn refreshSymbols')({ fetchSymbols: () => { calls++; return new Promise(resolve => { finish = resolve }) } })
const first = refresh()
assert.equal(refresh(), first)
assert.equal(calls, 1)
finish(); await first
const second = refresh()
assert.equal(calls, 2)
finish(); await second

// Same catalog refreshed every 15 seconds must not reset/scroll the chart.
const chartWatch = chart.match(/watch\(\[\(\) => props\.symbol,[\s\S]*?resetMarket\)/)[0]
const props = reactive({ symbol: 'JPY=X', category: 'Forex' }), interval = ref('1m')
const market = reactive({ symbols: [{ symbol: 'JPY=X', pricePrecision: 3 }] })
let resets = 0
const stop = new Function('watch', 'props', 'interval', 'market', 'resetMarket', 'return ' + compile(chartWatch))(watch, props, interval, market, () => { resets++ })
market.symbols = [{ symbol: 'JPY=X', pricePrecision: 3 }]
await nextTick(); assert.equal(resets, 0)
market.symbols = [{ symbol: 'JPY=X', pricePrecision: 0 }]
await nextTick(); assert.equal(resets, 1)
interval.value = '5m'
await nextTick(); assert.equal(resets, 2); stop()

assert.match(desktop, /visibilityState !== 'hidden'.*lastSymbolRefresh >= 15000/)
assert.match(desktop, /removeEventListener\('focus', refreshSymbols\)/)
for (const field of ['open', 'high', 'low', 'close']) assert.match(desktop, new RegExp(`${field}: formatSymbolPrice\\(last\\.${field}\\)`))
assert.match(desktop, /formatSymbolPrice\(contractDisplayPrice\(order\), order\.symbol\)/)
assert.doesNotMatch(desktop, /(?:openPrice|closePrice|last\.(?:open|high|low|close))[^\n]*toFixed/)
// The available-funds card is display-only: invalid balances render zero without changing account readiness.
const moneySource = desktop.match(/const formatMoney = [\s\S]*?\n};/)[0]
const formatMoney = new Function('localeStore', compile(moneySource) + '\nreturn formatMoney')({ locale: 'ja' })
const balanceExpression = desktop.match(/localeStore\.t\('availableFund'\)[\s\S]*?\$\{\{ ([^\n]+) \}\}/)[1]
const cardAmount = new Function('formatMoney', 'tradeMode', 'tradingAvailable', 'optionTradingAvailable', 'return ' + balanceExpression)
for (const value of [undefined, null, '', ' ', 'bad', 'NaN', NaN, Infinity, -Infinity]) {
  assert.equal(cardAmount(formatMoney, 'contract', value, 100), '0.00')
  assert.equal(cardAmount(formatMoney, 'options', 100, value), '0.00')
}
for (const value of [0, 1.2, '42.5', 1234.567]) {
  const expected = Number(value).toLocaleString('ja', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  assert.equal(cardAmount(formatMoney, 'contract', value, 100), expected)
  assert.equal(cardAmount(formatMoney, 'options', 100, value), expected)
}
assert.equal(formatMoney(NaN), '--', 'Other unavailable values retain their existing placeholder')
const { selectedAvailable, trialState } = await import('../src/utils/trialLifecycle.ts')
const unavailable = selectedAvailable({}, 'OPTION', trialState(null, NaN))
assert.ok(Number.isNaN(unavailable), 'Invalid account balances remain unavailable for trading')
assert.equal(cardAmount(formatMoney, 'options', 100, unavailable), '0.00')
assert.match(desktop, /formatMoney\(tradingAvailable, '0\.00'\)/, 'The balance detail uses the same fallback')
console.log('PASS: quote precision, refresh, raw prices unchanged; missing/invalid balances display 0.00, trading sentinel preserved')
