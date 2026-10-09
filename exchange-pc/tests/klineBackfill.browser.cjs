// Real chart library and Vue applications; all HTTP/WS use isolated fixtures.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const output = process.env.KLINE_GAP_QA
const fixedNow = Date.parse('2026-10-09T02:35:45Z')
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
const read = page => page.locator('.chart-workspace').evaluate(el => {
  const s = el.__vueParentComponent.setupState, c = s.chart
  return { bars: c.getDataList().map(row => ({ ...row })), space: c.getBarSpace().bar,
    overlays: c.getOverlays({ groupId: 'trading-drawings' }).map(row => ({ id: row.id, points: row.points.map(p => ({ timestamp: p.timestamp, value: p.value })), lock: row.lock })),
    indicators: c.getIndicators().map(row => ({ name: row.name, params: row.calcParams, results: row.result.length })),
    waiting: s.historyWaiting, error: s.error, symbol: el.__vueParentComponent.props.symbol, interval: s.interval, version: s.revision, reloads: window.backfillReloads || 0 }
})
const sync = page => page.locator('.chart-workspace').evaluate(el => el.__vueParentComponent.setupState.syncLatest())
const waitChart = page => page.waitForFunction(() => {
  const s = document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState
  return s?.count > 0 && !s.loading && !s.syncing
})
;(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
  const report = []
  try {
    for (const app of ['pc', 'mobile']) {
      const context = await browser.newContext({ viewport: app === 'pc' ? { width: 1600, height: 1000 } : { width: 390, height: 1000 }, reducedMotion: 'reduce' })
      await context.route('**/*', route => ['127.0.0.1', 'localhost'].includes(new URL(route.request().url()).hostname) ? route.continue() : route.abort())
      const page = await context.newPage(), errors = []
      await page.clock.setFixedTime(new Date(fixedNow))
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(() => { localStorage.setItem('token', 'isolated-backfill'); localStorage.setItem('locale', 'en'); localStorage.setItem('user', JSON.stringify({ id: 1, tenantId: 1 })) })
      let socket, epoch = 'gap-one', quoteVersion = 1, marketRevision = 1, restoreRevision = 0, publication = 'p0', historyRevision = 'h0'
      let close = 101, sparse = true, category = 'Crypto', historyPending = false, historyFilled = false, historyCalls = 0, hold = false, release, held
      let simulated = false, session, frozenClose
      let activeSymbol = 'BTCUSDT', latestPending = false, latestCalls = 0
      const step = period => ({ '1m': 60000, '5m': 300000, '15m': 900000, '30m': 1800000, '1h': 3600000 })[period] || 300000
      const end = width => Math.floor(fixedNow / width) * width
      const rows = (first, last, width = 300000) => {
        const origin = end(width) - 500 * width
        return Array.from({ length: Math.max(0, last - first + 1) }, (_, i) => {
          const index = first + i, price = frozenClose != null && index < 500 ? frozenClose : close
          return { timestamp: origin + index * width, open_price: 100, high_price: Math.max(105, price + 1), low_price: 99, close_price: price, volume: 10 }
        })
      }
      const quote = () => ({ price: close, timestamp: fixedNow, fetchedAt: fixedNow, available: true, status: 'available', epoch, quoteVersion, marketRevision,
        historyRestoreRevision: restoreRevision, controlHistoryRevision: historyRevision, controlPublicationRevision: publication, ...(simulated ? { simulated: true, simulationSession: session } : {}) })
      const push = (bars, period = '5m') => socket.send(JSON.stringify({ type: 'price', data: { [activeSymbol]: quote() },
        ...(bars ? { klines: [{ symbol: activeSymbol, interval: period, bars, pending: false }] } : {}) }))
      await page.routeWebSocket('**/api/ws/market', ws => {
        socket = ws
        ws.onMessage(raw => {
          const message = JSON.parse(raw)
          if (message.action === 'ping') ws.send(JSON.stringify({ type: 'pong' }))
          if (message.action === 'subscribe') { ws.send(JSON.stringify({ type: 'subscribed', symbols: message.symbols })); if (message.symbols.includes(activeSymbol)) push() }
        })
      })
      await page.route(/\/(?:api|demo-api)\//, async route => {
        const url = new URL(route.request().url())
        let body = { ret: 200, data: [], list: [], success: true }
        if (/\/market\/(all|symbols)$/.test(url.pathname)) body = { list: ['BTCUSDT', 'ETHUSDT'].map(symbol => ({ symbol, alltickSymbol: symbol, category, sourceCategory: category, pricePrecision: 2 })) }
        else if (url.pathname.endsWith('/timezone')) body = { timezone: 'UTC' }
        else if (url.pathname.includes('/kline/') && !url.pathname.endsWith('/batch')) {
          const period = url.searchParams.get('interval') || '5m', width = step(period), limit = Number(url.searchParams.get('limit')) || 200
          const history = url.pathname.includes('/history/'), origin = end(width) - 500 * width
          let candles, pending = false, gaps = []
          if (history) {
            historyCalls++
            const last = Math.min(300, Math.floor((Number(url.searchParams.get('endTime')) - origin) / width))
            candles = rows(Math.max(101, last - limit + 1), last, width)
            if (historyPending && last === 300 && !historyFilled) {
              candles = candles.filter((_, i) => i !== Math.floor(candles.length / 2)); pending = true; gaps = [{ reason: 'missing_source', recoverable: true }]
            }
          } else {
            latestCalls++
            candles = rows(501 - limit, 500, width)
            if (sparse) { candles = candles.filter(row => row.timestamp !== origin + 401 * width); pending = latestPending; gaps = [{ reason: pending ? 'missing_source' : 'upstream_no_data', recoverable: pending }] }
          }
          body = { ret: 200, data: { kline_list: candles, pending, exhausted: history && candles.length === 0,
            historyRestoreRevision: restoreRevision, ...(simulated ? { simulated: true, simulationSession: session } : {}),
            historyRepair: { pending, state: pending ? 'pending' : gaps.length ? 'unrepairable' : 'complete', gaps, nextCursor: origin + 101 * width } } }
          if (!history && hold) {
            hold = false
            await new Promise(resolve => { release = resolve; held?.() })
          }
        }
        try { await route.fulfill({ json: body }) } catch { /* An authoritative reset intentionally aborts the old request. */ }
      })
      const url = app === 'pc' ? process.env.PC_QA_URL || 'http://127.0.0.1:5587/' : process.env.MOBILE_QA_URL || 'http://127.0.0.1:5588/#/trade?symbol=BTCUSDT&category=Crypto'
      await page.goto(url); await waitChart(page)
      await page.getByRole('button', { name: /^5m$/ }).click(); await waitChart(page)
      assert.equal((await read(page)).bars.length, 199)
      await page.locator('.chart-workspace').evaluate(el => {
        const s = el.__vueParentComponent.setupState, c = s.chart
        c.scrollToDataIndex(0, 0)
      })
      await page.waitForFunction(() => document.querySelector('.chart-workspace').__vueParentComponent.setupState.count > 199)
      await page.locator('.chart-workspace').evaluate(el => {
        const s = el.__vueParentComponent.setupState, c = s.chart
        s.preferences.indicators = ['MA', 'VOL', 'RSI']; c.setBarSpace(10); c.scrollToDataIndex(90, 0)
        const bars = c.getDataList(); c.createOverlay({ name: 'segment', groupId: 'trading-drawings', lock: true, points: [{ timestamp: bars[45].timestamp, value: 100 }, { timestamp: bars[80].timestamp, value: 103 }] })
        s.persistDrawings(); const reset = c.resetData.bind(c); c.resetData = () => { window.backfillReloads = (window.backfillReloads || 0) + 1; reset() }
        window.backfillAnchor = bars[90].timestamp; window.backfillX = c.convertToPixel({ timestamp: window.backfillAnchor }).x
      })
      await delay(150)
      await page.locator('.chart-workspace').evaluate(el => { window.backfillX = el.__vueParentComponent.setupState.chart.convertToPixel({ timestamp: window.backfillAnchor }).x })
      const before = await read(page); sparse = false; close = 102
      await sync(page); await waitChart(page); await delay(100)
      const after = await read(page), filledAt = end(300000) - 99 * 300000
      assert.ok(after.bars.some(row => row.timestamp === filledAt)); assert.equal(after.bars.length, before.bars.length + 1)
      for (const row of before.bars.slice(0, -1)) assert.deepEqual(after.bars.find(next => next.timestamp === row.timestamp), row, 'same revision preserves existing records')
      assert.equal(after.reloads, 1); assert.equal(after.space, before.space); assert.deepEqual(after.overlays, before.overlays)
      assert.deepEqual(after.indicators.map(row => [row.name, row.params]), before.indicators.map(row => [row.name, row.params]))
      assert.ok(after.indicators.every(row => row.results === after.bars.length))
      const shift = await page.locator('.chart-workspace').evaluate(el => el.__vueParentComponent.setupState.chart.convertToPixel({ timestamp: window.backfillAnchor }).x - window.backfillX)
      fs.writeFileSync(`${output}/${app}-viewport-proof.json`, JSON.stringify({ before, after, shift }, null, 2))
      assert.ok(Math.abs(shift) < 1, `timestamp viewport moved ${shift}px`)
      assert.equal(new Set(after.bars.map(row => row.timestamp)).size, after.bars.length)
      assert.ok(after.bars.every((row, i) => !i || row.timestamp > after.bars[i - 1].timestamp))
      await page.screenshot({ path: `${output}/${app}-middle-backfill.png`, fullPage: true })
      report.push({ app, case: 'latest-middle', oldHistoryPreserved: true, drawings: true, indicators: true, zoom: true, viewportShiftPixels: shift, publicResetCalls: after.reloads })
      // Its existing pending timer rereads final backend display without a manual chart sync.
      sparse = true; latestPending = true; close = 101
      await page.reload(); await waitChart(page)
      const autoCalls = latestCalls; assert.equal((await read(page)).bars.length, 99)
      sparse = false; latestPending = false
      await page.waitForFunction(at => document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getDataList().some(row => row.timestamp === at), filledAt)
      await waitChart(page); const automatic = await read(page)
      assert.equal(automatic.bars.length, 200); assert.equal(new Set(automatic.bars.map(row => row.timestamp)).size, 200)
      assert.ok(latestCalls > autoCalls && latestCalls <= autoCalls + 2)
      report.push({ app, case: 'automatic-latest-pending', middleAppeared: true, automaticRequests: latestCalls - autoCalls })
      // Noncontinuous source pages may return a pending sparse page with a genuine internal new timestamp.
      category = 'Metal'; historyPending = true; historyFilled = false; close = 101; sparse = false
      await page.reload(); await waitChart(page)
      await page.locator('.chart-workspace').evaluate(el => el.__vueParentComponent.setupState.chart.scrollToDataIndex(0, 0))
      await page.waitForFunction(() => document.querySelector('.chart-workspace').__vueParentComponent.setupState.historyWaiting)
      const partial = await read(page); const calls = historyCalls; historyFilled = true
      await page.waitForFunction(count => {
        const s = document.querySelector('.chart-workspace').__vueParentComponent.setupState
        return !s.historyWaiting && s.count > count && !s.error
      }, partial.bars.length)
      const completed = await read(page)
      assert.ok(partial.bars.every(row => completed.bars.some(next => next.timestamp === row.timestamp)))
      assert.equal(new Set(completed.bars.map(row => row.timestamp)).size, completed.bars.length)
      await delay(1200); assert.ok(historyCalls <= calls + 3, 'no unbounded reload/history loop')
      report.push({ app, case: 'pending-middle', added: completed.bars.length - partial.bars.length, noError: true, boundedHistoryCalls: historyCalls - calls })
      // An old HTTP response can fill missing history but cannot overwrite a newer WebSocket tail.
      historyPending = false; category = 'Crypto'; sparse = true; close = 101
      await page.reload(); await waitChart(page); sparse = false
      hold = true; const arrival = new Promise(resolve => { held = resolve }); const syncing = sync(page); await arrival
      await delay(30); close = 104; quoteVersion++; push(rows(500, 500).map(row => ({ ...row, volume: 42 })))
      await page.waitForFunction(() => document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getDataList().at(-1).close === 104)
      release(); await syncing; await delay(150)
      const interleaved = await read(page)
      assert.ok(interleaved.bars.some(row => row.timestamp === filledAt)); assert.equal(interleaved.bars.at(-1).close, 104); assert.equal(interleaved.bars.at(-1).volume, 42)
      report.push({ app, case: 'old-http-new-ws', middleFilled: true, latestClose: 104, latestVolume: 42 })
      for (const transition of ['restore', 'undo', 'publication', 'source']) {
        hold = true; const arrives = new Promise(resolve => { held = resolve }); const old = sync(page); await arrives
        close += 10; quoteVersion++; marketRevision++
        if (transition === 'restore' || transition === 'undo') restoreRevision++
        if (transition === 'publication') { historyRevision = 'h1'; publication = 'p1' }
        push(); await delay(100); release(); await old; await waitChart(page)
        await page.waitForFunction(expected => document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getDataList().every(row => row.close === expected), close)
        report.push({ app, case: `late-${transition}`, oldResponseRejected: true })
      }
      // A period switch aborts a pending old request and restarts its real loader.
      hold = true; const periodArrival = new Promise(resolve => { held = resolve }); const oldPeriod = sync(page); await periodArrival
      await page.getByRole('button', { name: /^1m$/ }).click(); await delay(100); release(); await oldPeriod; await waitChart(page)
      const minute = await read(page)
      assert.equal(minute.interval, '1m'); assert.ok(minute.bars.every((row, i) => !i || row.timestamp - minute.bars[i - 1].timestamp === 60000))
      report.push({ app, case: 'late-period', interval: '1m' })
      // Simulation entry keeps the server's frozen prefix; delayed old live data never wins.
      hold = true; const simulationArrival = new Promise(resolve => { held = resolve }); const oldSimulation = sync(page); await simulationArrival
      frozenClose = close; close += 5; simulated = true; session = fixedNow; quoteVersion++; marketRevision++; push(undefined, '1m')
      await delay(100); release(); await oldSimulation; await waitChart(page)
      await page.waitForFunction(expected => document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getDataList().at(-1).close === expected, close)
      const simulation = await read(page); assert.ok(simulation.bars.slice(0, -1).every(row => row.close === frozenClose))
      report.push({ app, case: 'late-simulation', frozenPrefix: true, session })
      hold = true; const exitArrival = new Promise(resolve => { held = resolve }); const oldExit = sync(page); await exitArrival
      simulated = false; session = undefined; frozenClose = undefined; close += 5; quoteVersion++; marketRevision++; push(undefined, '1m')
      await delay(100); release(); await oldExit; await waitChart(page)
      await page.waitForFunction(expected => document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getDataList().every(row => row.close === expected), close)
      report.push({ app, case: 'late-simulation-exit', frozenSessionResponseRejected: true })
      // A tenant/account credential change discards the pending old HTTP body, then an epoch reload fetches the new scope.
      hold = true; const tenantArrival = new Promise(resolve => { held = resolve }); const oldTenant = sync(page); await tenantArrival
      await page.evaluate(async () => { const { useAuthStore } = await import('/src/store/auth.ts'); const auth = useAuthStore(); auth.token = 'isolated-tenant-two'; auth.user = { id: 2, tenantId: 2 }; localStorage.setItem('token', auth.token); localStorage.setItem('user', JSON.stringify(auth.user)) })
      epoch = 'gap-two'; marketRevision++; quoteVersion = 1; simulated = false; session = undefined; frozenClose = undefined; close = 150; push(undefined, '1m')
      await delay(100); release(); await oldTenant; await waitChart(page)
      await page.waitForFunction(() => document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getDataList().every(row => row.close === 150))
      report.push({ app, case: 'late-tenant', staleCredentialResponseRejected: true, newEpoch: epoch })
      // Real parent selection updates chart props while the previous instrument's HTTP is held.
      hold = true; const symbolArrival = new Promise(resolve => { held = resolve }); const oldSymbol = sync(page); await symbolArrival
      activeSymbol = 'ETHUSDT'; close = 250
      await page.locator('.chart-workspace').evaluate(async el => {
        let parent = el.__vueParentComponent.parent
        while (parent && !parent.setupState.selectSymbol) parent = parent.parent
        if (!parent) throw new Error('Real trade selection handler unavailable')
        await parent.setupState.selectSymbol({ symbol: 'ETHUSDT', category: 'Crypto', sourceCategory: 'Crypto', pricePrecision: 2 })
      })
      await delay(100); release(); await oldSymbol; await waitChart(page)
      await page.waitForFunction(() => {
        const component = document.querySelector('.chart-workspace').__vueParentComponent
        return component.props.symbol === 'ETHUSDT' && component.setupState.chart.getDataList().every(row => row.close === 250)
      })
      assert.equal((await read(page)).symbol, 'ETHUSDT'); report.push({ app, case: 'late-symbol', oldInstrumentRejected: true, symbol: 'ETHUSDT' })
      assert.deepEqual(errors, [])
      await page.screenshot({ path: `${output}/${app}-backfill-races.png`, fullPage: true })
      await context.close()
    }
    fs.writeFileSync(`${output}/client-backfill-results.json`, JSON.stringify(report, null, 2)); console.log(JSON.stringify(report))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
