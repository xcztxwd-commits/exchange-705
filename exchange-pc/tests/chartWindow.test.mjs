import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { test } from 'node:test'

const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript')
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))
const until = async (condition, timeout = 8000) => {
  const start = Date.now()
  while (!condition()) { if (Date.now() - start > timeout) throw new Error('Timed out waiting for chart'); await wait(10) }
}
const bars = (from, to) => Array.from({ length: to - from + 1 }, (_, i) => ({ timestamp: from + i, open: 1, high: 2, low: 1, close: 2, volume: 1 }))
const normalizeCandles = (rows, before = Infinity) => [...new Map(rows.filter(row => row.timestamp < before).map(row => [row.timestamp, row])).values()].sort((a, b) => a.timestamp - b.timestamp)

for (const app of ['exchange-pc', 'exchange-frontend']) {
  const { contiguousCryptoCandles, historyRepairPolicy } = await import('../../' + app + '/src/utils/chartData.ts')
  const source = readFileSync(new URL(`../../${app}/src/components/KlineChart.vue`, import.meta.url), 'utf8')
  const code = ts.transpile(source.slice(source.indexOf('function roundedRequestSize('), source.indexOf('\nasync function syncLatest()')), { target: ts.ScriptTarget.ES2022 })

  function fixture(respond, visible = 400, options = {}) {
    const data = [], commits = [], calls = []
    let loader
    const chart = {
      getDataList: () => data,
      getBarSpace: () => ({ bar: 7 }),
      getSize: () => ({ width: visible * 7 }),
      convertToPixel: () => ({ x: (visible - data.length) * 7 }),
      getVisibleRange: () => ({ from: Math.max(0, data.length - visible) }),
      scrollByDistance: () => { if (data.length <= visible) queueMicrotask(() => loader('forward')) },
    }
    const request = { get: async (url, options) => {
      calls.push({ url, ...options.params })
      return respond(url, options.params)
    } }
    const context = {
      chart, container: { value: { clientWidth: visible * 7 } }, request, normalizeCandles, contiguousCryptoCandles, historyRepairPolicy,
      props: { symbol: 'TEST', category: 'US', ...options.props }, interval: { value: options.interval || '1m' },
      sourceMissing: { value: false }, historyGap: { value: '' }, historyPaused: { value: false }, historyCursor: null, historySkips: 0, pageRequests: new Map(), historyWindow: 200,
      historyUnsupported: { value: false }, historyLimited: { value: false },
      historyFailures: 0, historyRetryAt: 0, historyRetryTimer: undefined,
      pendingHistory: null, historyWaiting: { value: false }, historyLoading: { value: false },
      activeHistoryLoads: 0, loading: { value: false }, error: { value: false },
      empty: { value: false }, exhausted: { value: false }, count: { value: 0 },
      retry: null, pendingLatestTimer: undefined, pendingLatestAttempts: 0,
      gapCheckTimer: undefined, controller: new AbortController(), revision: 1,
      market: { klineDataMap: {}, quoteStatusMap: {}, symbols: options.symbols || [] }, replayQuote: () => {}, restoreDrawings: () => {}, emit: () => {},
      syncLatest: () => {},
    }
    const api = new Function(...Object.keys(context), `${code}; return {
      loadBars, fetchBars, requestedBarCount, continueOlderHistory, retryLoad: () => retry?.(),
      version: value => { revision = value },
      dispose: () => { controller.abort(); clearTimeout(historyRetryTimer); clearTimeout(pendingLatestTimer); clearTimeout(gapCheckTimer) },
      state: () => ({ error: error.value, loading: loading.value, waiting: historyWaiting.value, historyLoading: historyLoading.value, unsupported: historyUnsupported.value, gap: historyGap.value, paused: historyPaused.value, cursor: historyCursor, exhausted: exhausted.value })
    }`)(...Object.values(context))
    loader = type => {
      const timestamp = type === 'forward' ? data[0]?.timestamp ?? null : null
      return api.loadBars({ type, timestamp, callback(page, more) {
        if (type === 'init') data.splice(0, data.length, ...page)
        else data.unshift(...page)
        if (page.length) commits.push({ count: data.length, time: Date.now(), type })
        if (more?.forward && data.length <= visible && page.length) queueMicrotask(() => loader('forward'))
      } }, 1, context.controller.signal)
    }
    return { api, data, calls, commits, loader, controller: context.controller }
  }

  test(`${app}: viewport and zoom control each page; latest is one reusable 200-bar page`, () => {
    const f = fixture(async () => ({ ret: 200, data: { kline_list: [] } }), 400)
    assert.equal(f.api.requestedBarCount(false), 200)
    f.data.push(...bars(301, 500))
    assert.equal(f.api.requestedBarCount(true), 200)
    f.data.unshift(...bars(101, 300))
    assert.equal(f.api.requestedBarCount(true), 100)
  })

  test(`${app}: first 200 render before delayed second page; 200, 400, 500 in order`, async () => {
    const started = Date.now()
    const f = fixture(async (url, query) => {
      if (!url.includes('/history/')) return { ret: 200, data: { kline_list: bars(301, 500) } }
      if (query.endTime === 300) { await wait(5000); return { ret: 200, data: { kline_list: bars(101, 300) } } }
      return { ret: 200, data: { kline_list: bars(1, 100) } }
    })
    await f.loader('init')
    await until(() => f.data.length === 200)
    const firstMs = f.commits[0].time - started
    assert.ok(firstMs < 1000, `first page took ${firstMs}ms`)
    assert.equal(f.data.length, 200)
    await until(() => f.data.length === 500)
    const fullMs = f.commits.at(-1).time - started
    assert.deepEqual(f.commits.map(item => item.count), [200, 400, 500])
    assert.deepEqual(f.data.map(item => item.timestamp), Array.from({ length: 500 }, (_, i) => i + 1))
    assert.ok(fullMs - firstMs >= 4900)
    assert.deepEqual(f.calls.map(item => [item.endTime ?? null, item.limit]), [[null, 200], [300, 200], [100, 100]])
    console.log(`${app} progressive first=${firstMs}ms full=${fullMs}ms`)
  })

  test(`${app}: second-page failure leaves first page; retry starts at failed cursor`, async () => {
    let failures = 0
    const f = fixture(async (url, query) => {
      if (!url.includes('/history/')) return { ret: 200, data: { kline_list: bars(301, 500) } }
      if (query.endTime === 300 && failures++ === 0) throw new Error('timeout')
      return { ret: 200, data: { kline_list: query.endTime === 300 ? bars(101, 300) : bars(1, 100) } }
    })
    await f.loader('init')
    await until(() => f.api.state().error)
    assert.equal(f.data.length, 200)
    f.api.retryLoad()
    await until(() => f.data.length === 500)
    assert.deepEqual(f.calls.map(item => item.endTime ?? null), [null, 300, 300, 100])
  })

  test(`${app}: valid pending page renders without polling; partial cursor is retried before advancing`, async () => {
    let partial = true
    const f = fixture(async (url, query) => {
      if (!url.includes('/history/')) return { ret: 200, data: { pending: true, kline_list: bars(301, 500) } }
      if (query.endTime === 300 && partial) { partial = false; return { ret: 200, data: { pending: true, kline_list: bars(201, 300) } } }
      return { ret: 200, data: { kline_list: query.endTime === 300 ? bars(101, 300) : bars(1, 100) } }
    }, 300)
    await f.loader('init')
    await until(() => f.data.length === 300)
    assert.equal(f.api.state().waiting, true)
    assert.equal(f.calls.filter(call => !call.url.includes('/history/')).length, 1)
    await until(() => f.data.length >= 400, 4000)
    assert.deepEqual(f.calls.filter(call => call.url.includes('/history/')).slice(0, 2).map(call => call.endTime), [300, 300])
    assert.equal(new Set(f.data.map(item => item.timestamp)).size, f.data.length)
  })

  test(`${app}: a full pending page must retry the same cursor before advancing`, async () => {
    let pending = true
    const f = fixture(async (url, query) => {
      if (!url.includes('/history/')) return { ret: 200, data: { kline_list: bars(301, 500) } }
      if (query.endTime === 100) return { ret: 200, data: { kline_list: bars(1, 100) } }
      const response = { ret: 200, data: { pending, kline_list: bars(101, 300) } }
      pending = false
      return response
    })
    try {
      await f.loader('init')
      await until(() => f.data.length === 400)
      assert.equal(f.api.state().waiting, true)
      await until(() => f.data.length === 500)
      assert.equal(f.api.state().waiting, false)
      assert.deepEqual(f.calls.map(call => call.endTime ?? null), [null, 300, 300, 100])
      assert.deepEqual(f.data, bars(1, 500))
    } finally { f.api.dispose() }
  })

  test(`${app}: crypto sparse available cache publishes only a contiguous suffix and fills its gap`, async () => {
    const cryptoBars = (from, to) => bars(from, to).map(bar => ({ ...bar, timestamp: 1_800_000_000_000 + bar.timestamp * 300000 }))
    const before = cryptoBars(301, 301)[0].timestamp
    let pending = true
    const f = fixture(async url => {
      if (!url.includes('/history/')) return { ret: 200, data: { kline_list: cryptoBars(301, 500) } }
      const response = { ret: 200, data: { status: 'available', pending: false, kline_list: pending
        ? [...cryptoBars(1, 100), ...cryptoBars(201, 300)] : cryptoBars(101, 300) } }
      pending = false
      return response
    }, 300, { interval: '5m', props: { symbol: 'BTCUSDT', category: '' }, symbols: [{ symbol: 'BTCUSDT', sourceCategory: 'Crypto' }] })
    try {
      await f.loader('init')
      await until(() => f.calls.length >= 2 && !f.api.state().historyLoading)
      assert.equal(f.data.length, 300)
      assert.equal(f.api.state().waiting, true)
      assert.deepEqual(f.data, cryptoBars(201, 500))
      await until(() => f.data.length === 400)
      assert.deepEqual(f.data, cryptoBars(101, 500))
      assert.deepEqual(f.calls.slice(1).map(call => call.endTime), [before - 1, before - 1])
      assert.equal(f.api.state().error, false)
    } finally { f.api.dispose() }
  })

  test(`${app}: crypto pending cache with a stale tail cannot skip the requested window`, async () => {
    const cryptoBars = (from, to) => bars(from, to).map(bar => ({ ...bar, timestamp: 1_800_000_000_000 + bar.timestamp * 300000 }))
    const before = cryptoBars(301, 301)[0].timestamp
    let pending = true
    const f = fixture(async url => {
      if (!url.includes('/history/')) return { ret: 200, data: { kline_list: cryptoBars(301, 500) } }
      const response = { ret: 200, data: { pending, kline_list: pending ? cryptoBars(1, 200) : cryptoBars(101, 300) } }
      pending = false
      return response
    }, 250, { interval: '5m', props: { symbol: 'BTCUSDT', category: 'Crypto' } })
    try {
      await f.loader('init')
      await until(() => f.calls.length === 2)
      assert.equal(f.data.length, 200)
      await until(() => f.data.length === 400)
      assert.deepEqual(f.data, cryptoBars(101, 500))
      assert.deepEqual(f.calls.slice(1).map(call => call.endTime), [before - 1, before - 1])
      assert.equal(f.api.state().error, false)
    } finally { f.api.dispose() }
  })

  test(`${app}: old response is ignored after symbol/period revision`, async () => {
    let release
    const f = fixture(() => new Promise(resolve => { release = resolve }))
    const pending = f.loader('init')
    await until(() => !!release)
    f.api.version(2); f.controller.abort()
    release({ ret: 200, data: { kline_list: bars(301, 500) } })
    await pending
    assert.equal(f.data.length, 0)
  })

  for (const reason of ['protected_control_history', 'missing_control_samples', 'upstream_no_data', 'source_calendar_unverified', 'existing_partial_or_invalid']) {
    test(`${app}: terminal ${reason} gaps expose real older sparse bars without waiting or invented prices`, async () => {
      const cryptoBars = (from, to) => bars(from, to).map(bar => ({ ...bar, timestamp: 1_800_000_000_000 + bar.timestamp * 300000 }))
      const f = fixture(async url => ({ ret: 200, data: { kline_list: !url.includes('/history/') ? cryptoBars(301, 500)
        : [...cryptoBars(101, 150), ...cryptoBars(201, 300)], pending: false,
        ...(url.includes('/history/') ? { historyRepair: { pending: false, gaps: [{ reason }], nextCursor: 1_800_000_000_000 } } : {}) } }),
      250, { interval: '5m', props: { symbol: 'BTCUSDT', category: 'Crypto' } })
      try {
        await f.loader('init')
        await until(() => f.data.length === 350)
        assert.equal(f.api.state().waiting, false)
        assert.equal(f.api.state().error, false)
        assert.equal(f.api.state().gap, reason)
        assert.deepEqual(f.data, [...cryptoBars(101, 150), ...cryptoBars(201, 500)])
        assert.equal(f.calls.length, 2)
      } finally { f.api.dispose() }
    })
  }

  test(`${app}: immutable legacy controlled responses keep genuine sparse bars without adding backend metadata`, async () => {
    const cryptoBars = (from, to) => bars(from, to).map(bar => ({ ...bar, timestamp: 1_800_000_000_000 + bar.timestamp * 300000 }))
    const archived = { ret: 200, data: { pending: false, kline_list: [...cryptoBars(101, 150), ...cryptoBars(201, 300)]
      .map(bar => ({ ...bar, controlled: true, historyReplaced: true })) } }
    const original = structuredClone(archived)
    const f = fixture(async url => !url.includes('/history/') ? { ret: 200, data: { kline_list: cryptoBars(301, 500) } } : archived,
      250, { interval: '5m', props: { symbol: 'BTCUSDT', category: 'Crypto' } })
    try {
      await f.loader('init'); await until(() => f.data.length === 350)
      assert.equal(f.api.state().waiting, false); assert.equal(f.api.state().gap, 'protected_control_history')
      assert.equal(f.calls.length, 2); assert.deepEqual(archived, original)
    } finally { f.api.dispose() }
  })

  test(`${app}: empty upstream window advances explicit cursor without falsely exhausting older history`, async () => {
    const origin = 1_800_000_000_000
    const cryptoBars = (from, to) => bars(from, to).map(bar => ({ ...bar, timestamp: origin + bar.timestamp * 300000 }))
    const next = origin + 101 * 300000
    const f = fixture(async (url, query) => {
      if (!url.includes('/history/')) return { ret: 200, data: { kline_list: cryptoBars(301, 500) } }
      if (query.endTime === origin + 301 * 300000 - 1) return { ret: 200, data: { kline_list: [], pending: false,
        historyRepair: { pending: false, gaps: [{ reason: 'upstream_no_data' }], nextCursor: next } } }
      return { ret: 200, data: { kline_list: cryptoBars(1, 100), pending: false } }
    }, 250, { interval: '5m', props: { symbol: 'BTCUSDT', category: 'Crypto' } })
    try {
      await f.loader('init')
      await until(() => f.data.length === 300)
      assert.equal(f.api.state().waiting, false)
      assert.equal(f.api.state().error, false)
      assert.equal(f.api.state().exhausted, false)
      assert.equal(f.calls[2].endTime, next - 1)
    } finally { f.api.dispose() }
  })

  test(`${app}: repeated terminal empty windows stop automatic skipping and leave manual continuation`, async () => {
    const origin = 1_800_000_000_000
    const latest = bars(301, 500).map(bar => ({ ...bar, timestamp: origin + bar.timestamp * 300000 }))
    const f = fixture(async (url, query) => !url.includes('/history/') ? { ret: 200, data: { kline_list: latest } }
      : { ret: 200, data: { kline_list: [], pending: false, historyRepair: { pending: false,
        gaps: [{ reason: 'protected_control_history' }], nextCursor: query.endTime - 200 * 300000 } } },
    250, { interval: '5m', props: { symbol: 'BTCUSDT', category: 'Crypto' } })
    try {
      await f.loader('init')
      await until(() => f.calls.length === 5, 5000)
      await wait(1200)
      assert.equal(f.calls.length, 5)
      assert.equal(f.api.state().waiting, false)
      assert.equal(f.api.state().error, false)
      assert.ok(f.api.state().cursor > 0)
      f.api.retryLoad()
      await until(() => f.calls.length === 6)
    } finally { f.api.dispose() }
  })

  test(`${app}: capped pending repair exposes explicit older-history continuation without falsely repairing the gap`, async () => {
    const origin = 1_800_000_000_000
    const cryptoBars = (from, to) => bars(from, to).map(bar => ({ ...bar, timestamp: origin + bar.timestamp * 300000 }))
    const f = fixture(async (url, query) => {
      if (!url.includes('/history/')) return { ret: 200, data: { kline_list: cryptoBars(301, 500) } }
      if (query.endTime === origin + 101 * 300000 - 1) return { ret: 200, data: { kline_list: cryptoBars(1, 100), pending: false } }
      return { ret: 200, data: { kline_list: cryptoBars(201, 300), pending: true,
        historyRepair: { pending: true, gaps: [{ reason: 'missing_source' }], nextCursor: origin + 101 * 300000 } } }
    }, 300, { interval: '5m', props: { symbol: 'BTCUSDT', category: 'Crypto' } })
    try {
      await f.loader('init'); await until(() => f.api.state().paused, 12000)
      const attempts = f.calls.length; await wait(1100); assert.equal(f.calls.length, attempts)
      assert.equal(f.api.state().waiting, true); assert.equal(f.api.state().gap, 'missing_source'); assert.equal(f.data.length, 300)
      f.api.continueOlderHistory(); await until(() => f.data.length === 400)
      assert.equal(f.calls.at(-1).endTime, origin + 101 * 300000 - 1)
      assert.deepEqual(f.data, [...cryptoBars(1, 100), ...cryptoBars(201, 500)])
      assert.equal(f.api.state().waiting, false); assert.equal(f.api.state().paused, false)
    } finally { f.api.dispose() }
  })

  test(`${app}: closed-market gaps use the actual oldest timestamp, not elapsed periods`, async () => {
    const latest = bars(301, 500).map(bar => ({ ...bar, timestamp: 1_800_000_000_000 + bar.timestamp * 60000 }))
    const f = fixture(async (url, query) => ({ ret: 200, data: { kline_list: url.includes('/history/')
      ? bars(101, 300).map(bar => ({ ...bar, timestamp: 1_799_800_000_000 + bar.timestamp * 60000 })) : latest } }), 250)
    await f.loader('init')
    await until(() => f.calls.length >= 2)
    assert.equal(f.calls[1].endTime, latest[0].timestamp - 1)
    f.api.dispose()
  })

  test(`${app}: empty history is unknown unless source explicitly says exhausted`, async () => {
    const f = fixture(async (url) => ({ ret: 200, data: { kline_list: url.includes('/history/') ? [] : bars(301, 500) } }))
    await f.loader('init')
    await until(() => f.api.state().error)
    assert.equal(f.data.length, 200)
    assert.equal(f.api.state().exhausted, false)
    f.api.dispose()
    const g = fixture(async url => ({ ret: 200, data: { exhausted: url.includes('/history/'), kline_list: url.includes('/history/') ? [] : bars(301, 500) } }))
    await g.loader('init')
    await until(() => g.api.state().exhausted)
    assert.equal(g.api.state().error, false)
    g.api.dispose()
  })

  test(`${app}: 429 does not trigger large fallback; 404 reports unsupported finite fallback`, async () => {
    const limited = fixture(async (url) => {
      if (url.includes('/history/')) throw { response: { status: 429 } }
      return { ret: 200, data: { kline_list: bars(301, 500) } }
    })
    await limited.loader('init')
    await until(() => limited.api.state().error)
    assert.deepEqual(limited.calls.map(call => call.limit), [200, 200])
    limited.api.dispose()
    const unsupported = fixture(async (url, query) => {
      if (url.includes('/history/')) throw { response: { status: 404 } }
      return { ret: 200, data: { kline_list: query.limit === 200 ? bars(301, 500) : bars(101, 500) } }
    })
    await unsupported.loader('init')
    await until(() => unsupported.data.length === 400)
    assert.equal(unsupported.api.state().unsupported, true)
    assert.deepEqual(unsupported.calls.slice(0, 3).map(call => call.limit), [200, 200, 400])
    unsupported.api.dispose()
  })
}
