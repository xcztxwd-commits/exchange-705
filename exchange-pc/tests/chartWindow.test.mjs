import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { test } from 'node:test'

const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript')

for (const app of ['exchange-pc', 'exchange-frontend']) {
  const source = readFileSync(new URL(`../../${app}/src/components/KlineChart.vue`, import.meta.url), 'utf8')
  const sizing = ts.transpile(source.slice(source.indexOf('function roundedRequestSize('), source.indexOf('\nasync function fetchBars(')), { target: ts.ScriptTarget.ES2022 })
  const window = ts.transpile(source.slice(source.indexOf('async function fetchWindow('), source.indexOf('\nfunction retryHistoryOnScroll(')), { target: ts.ScriptTarget.ES2022 })
  const calculate = (width, space, firstX = 0) => new Function('chart', 'container', `${sizing}; return { roundedRequestSize, requestedBarCount }`)({
    getBarSpace: () => ({ bar: space }),
    getSize: () => ({ width }),
    getDataList: () => [{ timestamp: 500 }],
    convertToPixel: () => ({ x: firstX }),
    getVisibleRange: () => ({ realFrom: 0 }),
  }, { value: { clientWidth: width } })
  const bars = (from, to) => Array.from({ length: to - from + 1 }, (_, index) => ({ timestamp: from + index }))

  test(`${app}: zoom and category switch size requests from viewport`, () => {
    const compact = calculate(1400, 7, 378 * 7)
    assert.equal(compact.roundedRequestSize(378), 500)
    assert.equal(compact.requestedBarCount(true), 500)
    assert.equal(compact.requestedBarCount(false), 300)
    assert.equal(calculate(1500, 2).requestedBarCount(false), 900)
  })

  test(`${app}: historical gap fetches 200 + 200 + 100 without duplicates`, async () => {
    const calls = []
    const fetchHistory = async (before, _signal, limit) => {
      calls.push([before, limit])
      return { candles: bars(before - limit, before - 1), limited: false }
    }
    const fetchWindow = new Function('fetchBars', 'fetchHistory', `${window}; return fetchWindow`)(null, fetchHistory)
    const result = await fetchWindow(501, new AbortController().signal, 500)
    assert.deepEqual(calls, [[501, 200], [301, 200], [101, 100]])
    assert.equal(result.candles.length, 500)
    assert.deepEqual(result.candles.map(bar => bar.timestamp), Array.from({ length: 500 }, (_, index) => index + 1))
  })

  test(`${app}: initial request fills beyond a source's 500-bar cap`, async () => {
    const calls = []
    const fetchBars = async (_before, _signal, limit) => { calls.push(['latest', limit]); return { candles: bars(501, 1000) } }
    const fetchHistory = async (before, _signal, limit) => {
      calls.push(['history', before, limit])
      return { candles: bars(before - limit, before - 1), limited: false }
    }
    const fetchWindow = new Function('fetchBars', 'fetchHistory', `${window}; return fetchWindow`)(fetchBars, fetchHistory)
    const result = await fetchWindow(Infinity, new AbortController().signal, 900)
    assert.deepEqual(calls, [['latest', 900], ['history', 501, 200], ['history', 301, 200]])
    assert.equal(result.candles.length, 900)
    assert.equal(result.candles[0].timestamp, 101)
  })

  test(`${app}: stop paging when the source has no earlier candles`, async () => {
    const calls = []
    const fetchHistory = async (before, _signal, limit) => {
      calls.push([before, limit])
      return { candles: calls.length === 1 ? bars(301, 500) : [], limited: false }
    }
    const fetchWindow = new Function('fetchBars', 'fetchHistory', `${window}; return fetchWindow`)(null, fetchHistory)
    const result = await fetchWindow(501, new AbortController().signal, 500)
    assert.deepEqual(calls, [[501, 200], [301, 200]])
    assert.equal(result.candles.length, 200)
    assert.equal(result.exhausted, true)
  })
}
