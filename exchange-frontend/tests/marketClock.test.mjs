import assert from 'node:assert/strict'
import { test, mock } from 'node:test'
import socket, { normalizeQuote } from '../src/utils/marketWebSocket.ts'
import { marketNow, syncMarketTime, resetMarketTime } from '../src/utils/marketClock.ts'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
const ts = createRequire(import.meta.url)('typescript')

test('REST server time permits a fresh quote when the phone clock is 20.874 seconds slow', async () => {
  const serverTime = 1_791_790_872_000
  const quote = { price: 158.422, timestamp: serverTime - 2000, fetchedAt: serverTime - 500,
    executionExpiresAt: serverTime + 58_000, available: true, status: 'available' }
  const phoneClock = mock.method(Date, 'now', () => serverTime - 20_874)
  const fetch = mock.method(globalThis, 'fetch', async () => new Response(JSON.stringify({ ret: 200, data: { 'JPY=X': quote } }),
    { headers: { Date: new Date(serverTime).toUTCString() } }))
  socket.owners.set('clock-regression', new Set(['JPY=X']))
  try {
    await socket.syncSnapshot()
    assert.equal(normalizeQuote(quote)?.status, 'available')
  } finally {
    socket.owners.delete('clock-regression')
    fetch.mock.restore()
    phoneClock.mock.restore()
  }
})

for (const app of ['exchange-frontend', 'exchange-pc']) {
  const clock = await import(`../../${app}/src/utils/marketClock.ts`)
  const market = await import(`../../${app}/src/utils/marketWebSocket.ts`)

  test(`${app}: legacy WebSocket retries server-clock synchronization after an initial HTTP failure`, async context => {
    const originals = Object.fromEntries(['window', 'document', 'location', 'WebSocket', 'fetch'].map(key => [key, globalThis[key]]))
    const sockets = [], service = market.default, epoch = Date.now()
    let attempts = 0
    class LegacySocket {
      static OPEN = 1
      readyState = 0
      constructor() { sockets.push(this) }
      send() {}
      close() { this.readyState = 3 }
    }
    globalThis.window = new EventTarget()
    globalThis.document = Object.assign(new EventTarget(), { visibilityState: 'visible' })
    globalThis.location = { protocol: 'https:', host: 'test.local', hostname: 'test.local', origin: 'https://test.local' }
    globalThis.WebSocket = LegacySocket
    globalThis.fetch = async () => {
      if (++attempts === 1) throw new Error('Transient clock-sync failure')
      return new Response(JSON.stringify({ ret: 200, serverTime: epoch, data: {} }))
    }
    context.mock.timers.enable({ apis: ['setInterval'] })
    clock.resetMarketTime()
    service.owners.set('legacy-clock-regression', new Set(['JPY=X']))
    try {
      const connection = service.connect(), socket = sockets[0]
      socket.readyState = LegacySocket.OPEN; socket.onopen(); await connection
      await new Promise(resolve => setTimeout(resolve, 0))
      socket.onmessage({ data: JSON.stringify({ type: 'price', data: { 'JPY=X': { price: 158, timestamp: epoch, available: true } } }) })
      assert.ok(Number.isNaN(clock.marketNow()))
      assert.equal(service.isHealthy, false, 'Transport cannot be healthy before its server clock is ready')
      context.mock.timers.tick(3000)
      await new Promise(resolve => setTimeout(resolve, 0))
      assert.equal(attempts, 2, 'The fallback must retry failed initial clock synchronization')
      assert.ok(Number.isFinite(clock.marketNow()))
      assert.equal(service.isHealthy, true)
      assert.equal(market.normalizeQuote({ price: 158, timestamp: epoch, available: true })?.status, 'available')
    } finally {
      service.disconnect(); service.owners.delete('legacy-clock-regression'); context.mock.timers.reset()
      for (const [key, value] of Object.entries(originals)) { if (value === undefined) delete globalThis[key]; else globalThis[key] = value }
    }
  })

  test(`${app}: device time changes never authorize quotes before server-time synchronization`, () => {
    clock.resetMarketTime()
    const timestamp = Date.now()
    assert.equal(market.normalizeQuote({ price: 1, timestamp, fetchedAt: timestamp, available: true }), null)
    assert.equal(clock.syncMarketTime('not a date'), false)
    assert.ok(Number.isNaN(clock.marketNow()))
  })

  test(`${app}: server time controls quotes and FX expiry with phones a day fast or slow`, () => {
    const epoch = 1_791_790_800_000
    let elapsed = 1000, phoneTime = epoch
    const monotonic = mock.method(performance, 'now', () => elapsed)
    const phoneClock = mock.method(Date, 'now', () => phoneTime)
    clock.resetMarketTime()
    try {
      clock.syncMarketTime(epoch, elapsed, elapsed)
      const quote = { price: 158.422, timestamp: epoch, fetchedAt: epoch, expiresAt: epoch + 60_000,
        available: true, status: 'available', quoteToUsdRate: 0.006312, conversionAvailable: true,
        conversionExpiresAt: epoch + 60_000, marginBaseToUsdRate: 1.31, marginRateExpiresAt: epoch + 5000 }
      const source = readFileSync(new URL(`../../${app}/src/store/market.ts`, import.meta.url), 'utf8')
      const methods = source.slice(source.indexOf('  const getConversionRate ='), source.indexOf('\n  // 各交易对的最新Tick数据'))
      const state = new Function('quoteStatusMap', 'marketNow', ts.transpile(methods, { target: ts.ScriptTarget.ES2022 })
        + ';return {getConversionRate,getMarginBaseRate,getQuoteStatus}')({ value: { TEST: quote } }, clock.marketNow)
      const instrument = { symbol: 'TEST', sourceCategory: 'Forex', baseCurrency: 'EUR', quoteCurrency: 'JPY' }
      for (const difference of [-86_400_000, -20_874, 86_400_000]) {
        phoneTime = epoch + difference
        assert.equal(market.normalizeQuote(quote)?.status, 'available')
        assert.equal(state.getQuoteStatus('TEST', phoneTime), 'available')
        assert.equal(state.getConversionRate('TEST', 'JPY'), quote.quoteToUsdRate)
        assert.equal(state.getMarginBaseRate(instrument, quote.price), quote.marginBaseToUsdRate)
      }
      assert.equal(market.normalizeQuote({ ...quote, timestamp: epoch + 6000 }), null, 'future provider data is still rejected')
      assert.equal(market.normalizeQuote({ ...quote, status: 'closed' })?.status, 'closed')
      elapsed += 5000
      assert.ok(Number.isNaN(state.getMarginBaseRate(instrument, quote.price)), 'five-second FX leases still expire')
      elapsed += 55_000
      assert.equal(state.getQuoteStatus('TEST', phoneTime), 'stale')
      assert.ok(Number.isNaN(state.getConversionRate('TEST', 'JPY')))
      assert.equal(clock.syncMarketTime(epoch + 1000, elapsed, elapsed), true)
      assert.equal(state.getQuoteStatus('TEST', phoneTime), 'stale', 'a delayed packet cannot revive an expired quote')
      assert.ok(Number.isNaN(state.getMarginBaseRate(instrument, quote.price)))
    } finally { clock.resetMarketTime(); phoneClock.mock.restore(); monotonic.mock.restore() }
  })

  test(`${app}: newer server samples correct slow RTT estimates; invalid and old samples retain authority`, () => {
    const epoch = 1_791_790_800_000
    let elapsed = 6000
    const monotonic = mock.method(performance, 'now', () => elapsed)
    clock.resetMarketTime()
    try {
      clock.syncMarketTime(epoch + 6000, 0, elapsed)
      elapsed = 7000
      clock.syncMarketTime(epoch + 7000, elapsed, elapsed)
      assert.equal(clock.marketNow(), epoch + 7000)
      for (const [value, start, end] of [[epoch + 6000, elapsed, elapsed], [epoch + 7000, elapsed, elapsed],
        ['invalid', elapsed, elapsed], [Infinity, elapsed, elapsed], [epoch + 8000, NaN, elapsed],
        [epoch + 8000, elapsed + 1, elapsed], [8_640_000_000_000_000, elapsed, elapsed]]) {
        assert.equal(clock.syncMarketTime(value, start, end), false)
        assert.equal(clock.marketNow(), epoch + 7000)
      }
    } finally { clock.resetMarketTime(); monotonic.mock.restore() }
  })
}

test('a fresh server sample corrects a slow sample so a five-second FX rate can recover', () => {
  const epoch = 1_791_790_800_000
  let elapsed = 6000
  const monotonic = mock.method(performance, 'now', () => elapsed)
  resetMarketTime()
  try {
    assert.equal(syncMarketTime(epoch + 6000, 0, elapsed), true)
    elapsed = 7000
    assert.equal(syncMarketTime(epoch + 7000, elapsed, elapsed), true)
    assert.equal(marketNow(), epoch + 7000)
    assert.ok(marketNow() < epoch + 7000 + 5000)
  } finally { resetMarketTime(); monotonic.mock.restore() }
})

test('a delayed newer packet cannot revive a truly expired quote', () => {
  const epoch = 1_791_790_800_000
  let elapsed = 0
  const monotonic = mock.method(performance, 'now', () => elapsed)
  resetMarketTime()
  const quote = { price: 158.422, timestamp: epoch, fetchedAt: epoch, executionExpiresAt: epoch + 60_000, available: true }
  try {
    syncMarketTime(epoch, elapsed, elapsed)
    elapsed = 61_000
    assert.equal(normalizeQuote(quote)?.status, 'stale')
    syncMarketTime(epoch + 1000, elapsed, elapsed)
    assert.equal(normalizeQuote(quote)?.status, 'stale')
    assert.ok(marketNow() >= epoch + 61_000)
  } finally { resetMarketTime(); monotonic.mock.restore() }
})
