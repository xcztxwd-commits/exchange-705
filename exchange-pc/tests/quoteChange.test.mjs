import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { quoteNumber, formatChangePercent } from '../../exchange-frontend/src/utils/quoteChange.ts'
import { createRequire } from 'node:module'
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript')
const compile = text => ts.transpileModule(text, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
const read = path => readFileSync(new URL(path, import.meta.url), 'utf8').replace(/\r\n/g, '\n')

for (const value of [undefined, null, '', ' ', false, true, NaN, Infinity, -Infinity, 'bad', {}, []]) {
  assert.ok(Number.isNaN(quoteNumber(value)))
  assert.equal(formatChangePercent(value), '—')
}
assert.equal(formatChangePercent(0, true), '0.00%')
assert.equal(formatChangePercent('0'), '0.00%')
assert.equal(formatChangePercent(0.12, true), '+0.12%')
assert.equal(formatChangePercent(-0.95, true), '-0.95%')
assert.equal(formatChangePercent(0.001), '0.00%', 'A valid tiny move can legitimately round to zero')

for (const area of ['exchange-pc', 'exchange-frontend']) {
  const source = read(`../../${area}/src/store/market.ts`)
  let listener
  const priceMap = { value: { FX: { price: 100, change24h: 2, changePct24h: 2, price24hAgo: 98, firstPriceTime: 1 } } }
  const quoteStatusMap = { value: {} }, tickDataMap = { value: {} }
  const setup = source.match(/let stopPriceListener:[\s\S]*?\n  }\n/)[0]
  const ensure = new Function('marketWebSocket', 'symbolCategoryMap', 'symbolMapping', 'recordQuoteStatus', 'tickDataMap', 'priceMap', 'quoteNumber', compile(setup) + '\nreturn ensurePriceListener')(
    { onPriceUpdate: callback => { listener = callback; return () => {} } }, { value: { FX: 'Forex' } }, { value: {} }, () => true, tickDataMap, priceMap, quoteNumber)
  ensure()
  listener({ FX: { price: 101, timestamp: 1 } })
  assert.ok(Number.isNaN(priceMap.value.FX.changePct24h), 'Missing new statistics must not inherit old percentages or invent zero')
  assert.ok(Number.isNaN(priceMap.value.FX.price24hAgo), 'No fabricated comparison price')
  assert.equal(priceMap.value.FX.price, 101, 'Do not change executable prices')
  listener({ FX: { price: 102, timestamp: 2, change24h: 0, changePct24h: 0 } })
  assert.equal(priceMap.value.FX.changePct24h, 0)
  listener({ FX: { price: 103, timestamp: 3, change24h: -1, changePct24h: -0.95 } })
  assert.equal(priceMap.value.FX.changePct24h, -0.95)
  const getter = source.match(/const getChange24h =[\s\S]*?\n  }\n/)[0]
  const getChange = new Function('priceMap', compile(getter) + '\nreturn getChange24h')(priceMap)
  assert.ok(Number.isNaN(getChange('MISSING').changePct))
  assert.equal(getChange('FX').changePct, -0.95)
  assert.match(source, /quoteNumber\(\(priceData as any\)\.changePct24h\)/, 'HTTP and WS preserve the same missing-value semantics')
  const home = read(`../../${area}/src/views/Home.vue`)
  assert.doesNotMatch(home, /wsChange\.changePct !== 0|priceChangePct24h \|\| 0/)
  assert.match(home, /Number\.isFinite\(getRealTimeChange\(s\)\.changePct\)/)
}
const desktop = read('../src/views/DesktopTrade.vue')
const renderer = desktop.match(/const getSymbolChange =[\s\S]*?\n};/)[0]
const getSymbolChange = new Function('marketStore', 'formatChangePercent', compile(renderer) + '\nreturn getSymbolChange')({ getChange24h: symbol => ({ changePct: { ZERO: 0, DOWN: -0.95, UP: 1.25 }[symbol] }) }, formatChangePercent)
assert.equal(getSymbolChange('FX'), '—')
assert.equal(getSymbolChange('ZERO'), '0.00%')
assert.equal(getSymbolChange('DOWN'), '-0.95%')
assert.equal(getSymbolChange('UP'), '+1.25%')
assert.match(desktop, /Change data is unavailable/)
assert.match(desktop, /Number\.isFinite\(marketStore\.getChange24h\(symbol\.symbol\)\.changePct\) \? 'bg-gray-400'/)
console.log('PASS missing/null/invalid changes, genuine zero, signed moves, raw prices, HTTP/WS consistency, source-switch reset and all rendered fallbacks')
