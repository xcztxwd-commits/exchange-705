import assert from 'node:assert/strict'
import { test } from 'node:test'

for (const app of ['exchange-pc', 'exchange-frontend']) {
  const { normalizeCandles, candleFromQuote, chartPeriod } = await import('../../' + app + '/src/utils/chartData.ts')
  const { normalizeQuote } = await import('../../' + app + '/src/utils/marketWebSocket.ts')
  test(app + ': candles are validated, sorted, deduplicated, and strictly older than the cursor', () => {
    const row = { kline_timestamp: 1_800_000_000, open_price: '10', high_price: '12', low_price: '9', close_price: '11', volume: '3' }
    const data = normalizeCandles([row, { ...row, close_price: '12' }, { ...row, kline_timestamp: 1_800_000_060 }, { ...row, kline_timestamp: 1_799_999_940, high_price: '8' }, { ...row, kline_timestamp: Infinity }], 1_800_000_060_000)
    assert.deepEqual(data, [{ timestamp: 1_800_000_000_000, open: 10, high: 12, low: 9, close: 12, volume: 3 }])
    assert.deepEqual(normalizeCandles([row], 1_800_000_000_000), [])
  })
  test(app + ': quotes update existing candles but cannot invent new OHLCV', () => {
    const last = { timestamp: 1_800_000_000_000, open: 10, high: 12, low: 9, close: 11, volume: 3 }
    assert.deepEqual(candleFromQuote(last, 13, last.timestamp + 1000, '1m'), { ...last, high: 13, close: 13 })
    assert.equal(candleFromQuote(last, 14, last.timestamp + 125000, '1m'), null)
    assert.equal(candleFromQuote(last, 12, last.timestamp - 1, '1m'), null)
    assert.equal(candleFromQuote(last, NaN, last.timestamp, '1m'), null)
    assert.equal(candleFromQuote(undefined, 12, last.timestamp, '1m'), null)
    const sessionBar = { ...last, timestamp: last.timestamp + 21 * 3_600_000 }
    assert.equal(candleFromQuote(sessionBar, 12, sessionBar.timestamp + 3_600_000, '1d').timestamp, sessionBar.timestamp)
    assert.deepEqual(chartPeriod('1M'), { span: 1, type: 'month' })
    assert.deepEqual(chartPeriod('15m'), { span: 15, type: 'minute' })
  })
  test(app + ': intraday snapshots cannot change the period grid; sessions remain intact', () => {
    const bar = { timestamp: 1_800_000_000_000, open: 10, high: 12, low: 9, close: 11, volume: 3 }
    const tail = { ...bar, timestamp: bar.timestamp + 96000 }
    assert.deepEqual(normalizeCandles([bar, tail], Infinity, '5m'), [bar])
    assert.equal(candleFromQuote(tail, 12, bar.timestamp + 300000, '5m'), null)
    const session = { ...bar, timestamp: bar.timestamp + 1800000 }
    assert.deepEqual(normalizeCandles([session, { ...session, timestamp: session.timestamp + 3600000 }], Infinity, '1h').map(b => b.timestamp), [session.timestamp, session.timestamp + 3600000])
    assert.deepEqual(normalizeCandles([session], Infinity, '1d'), [session])
    const daily = [0, 1, 2, 3].map(i => ({ ...bar, timestamp: bar.timestamp + i * 86400000 + (i < 2 ? 23 : 22) * 3600000 }))
    assert.deepEqual(normalizeCandles([...daily, { ...bar, timestamp: daily.at(-1).timestamp + 86400000 + 96000 }], Infinity, '1d'), daily)
  })
  test(app + ': legacy freshness uses source time; invalid or delayed prices never become live', () => {
    const now = 1_800_000_000_000
    assert.equal(normalizeQuote({ price: 10, timestamp: now / 1000 }, now).status, 'available')
    assert.equal(normalizeQuote({ price: 10, timestamp: now - 16000, fetchedAt: now }, now).status, 'stale')
    assert.equal(normalizeQuote({ price: 10, timestamp: now, status: 'stale' }, now).status, 'stale')
    assert.equal(normalizeQuote({ price: 10, timestamp: now, expiresAt: now - 1 }, now).status, 'stale')
    assert.equal(normalizeQuote({ price: -1, timestamp: now, status: 'available' }, now), null)
    assert.equal(normalizeQuote({ price: 10 }, now), null)
    assert.equal(normalizeQuote({ price: 10, timestamp: now + 6000 }, now), null)
  })
}
