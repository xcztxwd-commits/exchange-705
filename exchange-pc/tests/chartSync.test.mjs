import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { test } from 'node:test'
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript')

for (const app of ['exchange-pc', 'exchange-frontend']) {
  const source = readFileSync(new URL(`../../${app}/src/components/KlineChart.vue`, import.meta.url), 'utf8')
  const sync = source.slice(source.indexOf('async function syncLatest()'), source.indexOf('\nfunction resetMarket()'))
  const code = ts.transpile(sync, { target: ts.ScriptTarget.ES2022 })
  const bar = (timestamp, close = 11) => ({ timestamp, open: 10, high: 12, low: 9, close, volume: 3 })
  async function run(initial, pages, manual = true) {
    const data = initial.slice(), updates = [], requests = [], scrolls = []
    let offset = 0
    const context = {
      market: { quoteStatusMap: {} }, props: { symbol: "TEST" },
      chart: {
        getDataList: () => data,
        getVisibleRange: () => ({ from: 0, to: data.length }),
        convertToPixel: () => ({ x: 10 + offset }),
        scrollByDistance: distance => { scrolls.push(distance); offset += distance },
      },
      realtime: candle => {
        updates.push(candle)
        if (data.at(-1)?.timestamp === candle.timestamp) data[data.length - 1] = candle
        else { data.push(candle); offset -= 7 }
      },
      loading: { value: false }, historyLoading: { value: false }, syncing: false,
      revision: 1, controller: new AbortController(), lastSyncAttempt: 0,
      fetchBars: async before => {
        requests.push(before)
        return { candles: pages.shift(), damaged: false, stale: false }
      },
      manuallyScrolled: manual, empty: { value: false }, dataWarning: { value: false },
      staleCandles: { value: false }, syncError: { value: false }, cacheBars: () => {}, replayQuote: () => {},
    }
    await new Function(...Object.keys(context), code + '; return syncLatest()')(...Object.values(context))
    assert.equal(context.syncError.value, false)
    return { data, updates, requests, scrolls, offset, empty: context.empty.value }
  }
  test(`${app}: unchanged candles do not update or replace history`, async () => {
    const first = bar(1), last = bar(2)
    const result = await run([first, last], [[bar(1), bar(2)]])
    assert.deepEqual(result.updates, [])
    assert.equal(result.data[0], first)
    assert.equal(result.data[1], last)
    assert.deepEqual(result.scrolls, [])
  })
  test(`${app}: update last, append new candles, preserve manual position even with latest visible`, async () => {
    const first = bar(1)
    const result = await run([first, bar(2)], [[bar(1), bar(2, 12), bar(3), bar(4)]])
    assert.deepEqual(result.updates, [bar(2, 12), bar(3), bar(4)])
    assert.equal(result.data[0], first)
    assert.equal(result.offset, 0)
    assert.deepEqual(result.data.map(b => b.timestamp), [1, 2, 3, 4])
  })
  test(`${app}: reconnect fills missing candles in order without clearing history`, async () => {
    const result = await run([bar(1), bar(2)], [[bar(5)], [bar(2, 12), bar(3), bar(4)]])
    assert.deepEqual(result.requests, [Infinity, 5])
    assert.deepEqual(result.data.map(b => b.timestamp), [1, 2, 3, 4, 5])
    assert.equal(result.offset, 0)
  })
  test(`${app}: latest-following mode does not restore the old viewport`, async () => {
    const result = await run([bar(1)], [[bar(1), bar(2)]], false)
    assert.deepEqual(result.scrolls, [])
  })
  test(`${app}: empty initial data can recover through incremental updates`, async () => {
    const result = await run([], [[bar(1)]])
    assert.equal(result.empty, false)
    assert.deepEqual(result.data, [bar(1)])
  })
}
