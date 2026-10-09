// Local Vite fixtures only. Every API/WS is intercepted; no backend, exchange or production database is used.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))
const step = 300000, origin = Math.floor(Date.now() / step) * step - 500 * step
const bars = (from, to) => Array.from({ length: to - from + 1 }, (_, i) => ({ timestamp: origin + (from + i) * step,
  open_price: 100, high_price: 105, low_price: 99, close_price: 101, volume: 10 }))
const state = page => page.locator('.chart-workspace').evaluate(el => {
  const s = el.__vueParentComponent.setupState
  return { count: s.count, gap: s.historyGap, waiting: s.historyWaiting, error: s.error,
    times: s.chart.getDataList().map(bar => bar.timestamp), closes: s.chart.getDataList().map(bar => bar.close) }
})
;(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
  try {
    for (const app of ['pc', 'mobile']) {
      const context = await browser.newContext({ viewport: app === 'pc' ? { width: 1600, height: 950 } : { width: 500, height: 850 } })
      await context.route('**/*', route => {
        const url = new URL(route.request().url())
        if (!['127.0.0.1', 'localhost'].includes(url.hostname)) return route.abort()
        return route.continue()
      })
      const page = await context.newPage(), errors = []
      let reason = '', empty = false, archived = false, pending = false, historyCalls = 0
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(() => { localStorage.setItem('token', 'chart-test-fixture'); localStorage.setItem('locale', 'en') })
      await page.routeWebSocket('**/api/ws/market', socket => socket.onMessage(raw => {
        const data = JSON.parse(raw)
        if (data.action === 'subscribe') socket.send(JSON.stringify({ type: 'subscribed', symbols: data.symbols }))
        if (data.action === 'ping') socket.send(JSON.stringify({ type: 'pong' }))
      }))
      await page.route('**/api/**', async route => {
        const url = new URL(route.request().url())
        let body = { ret: 200, data: [], list: [], success: true }
        if (url.pathname.endsWith('/market/all') || url.pathname.endsWith('/market/symbols'))
          body = { list: [{ symbol: 'BTCUSDT', alltickSymbol: 'BTCUSDT', category: 'Crypto', sourceCategory: 'Crypto', pricePrecision: 2 }] }
        else if (url.pathname.endsWith('/timezone')) body = { timezone: 'Asia/Singapore' }
        else if (url.pathname.includes('/kline/') && !url.pathname.endsWith('/batch')) {
          const history = url.pathname.includes('/history/')
          if (history) historyCalls++
          const sparse = [...bars(101, 150), ...bars(201, 300)]
          body = { ret: 200, data: { pending: false, kline_list: history ? empty ? [] : archived
            ? sparse.map(bar => ({ ...bar, controlled: true, historyReplaced: true })) : sparse : bars(301, 500),
            ...(history && !archived ? { missingData: reason, historyRepair: { pending: false, state: 'unrepairable',
              gaps: [{ reason, recoverable: false }], nextCursor: Number(url.searchParams.get('endTime')) - 200 * step } } : {}),
            ...(!history ? { historyRepair: { pending: false, state: 'complete', gaps: [] } } : {}) } }
        }
        if (pending && url.pathname.includes('/history/')) {
          const older = Number(url.searchParams.get('endTime')) === origin + 101 * step - 1
          body = { ret: 200, data: { pending: !older, kline_list: older ? bars(1, 100) : bars(201, 300),
            historyRepair: { pending: !older, gaps: older ? [] : [{ reason: 'missing_source', recoverable: true }], nextCursor: origin + 101 * step } } }
        }
        await route.fulfill({ json: body })
      })
      const url = app === 'pc' ? process.env.PC_QA_URL || 'http://127.0.0.1:5187/' : process.env.MOBILE_QA_URL || 'http://127.0.0.1:5188/#/trade?symbol=BTCUSDT&category=Crypto'
      for (reason of ['missing_control_samples', 'upstream_no_data', 'source_calendar_unverified', 'source_fetch_or_write_failed', 'existing_partial_or_invalid']) {
        historyCalls = 0; await page.goto(url); await page.reload()
        await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
        await page.locator('.chart-workspace').evaluate(el => { const chart = el.__vueParentComponent.setupState.chart; chart.setBarSpace(chart.getSize('candle_pane', 'main').width / 260) })
        await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 350)
        const s = await state(page)
        assert.equal(s.gap, reason); assert.equal(s.waiting, false); assert.equal(s.error, false)
        assert.equal(await page.locator('.chart-footer').isVisible(), true, 'gap notice must survive compact trade layout CSS')
        assert.deepEqual(s.times, [...bars(101, 150), ...bars(201, 500)].map(bar => bar.timestamp))
        assert.ok(s.closes.every(close => close === 101)); assert.equal(historyCalls, 1)
        await page.locator('.chart-workspace').evaluate(el => el.__vueParentComponent.setupState.syncLatest())
        assert.equal((await state(page)).gap, reason, 'complete latest coverage cannot erase an older protected/no-data notice')
        console.log(`${app}: ${reason} real sparse bars remain visible without waiting`)
      }
      pending = true; historyCalls = 0; await page.reload()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
      await page.locator('.chart-workspace').evaluate(el => { const c = el.__vueParentComponent.setupState.chart; c.setBarSpace(c.getSize('candle_pane', 'main').width / 300) })
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.historyPaused === true, null, { timeout: 14000 })
      const pausedCalls = historyCalls; await wait(1200); assert.equal(historyCalls, pausedCalls)
      assert.equal((await state(page)).waiting, true)
      await page.locator('.skip-pending-history').click()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 400)
      assert.deepEqual((await state(page)).times, [...bars(1, 100), ...bars(201, 500)].map(bar => bar.timestamp))
      console.log(`${app}: capped pending window allows explicit older traversal without claiming the gap repaired`)
      pending = false
      archived = true; historyCalls = 0; await page.reload()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
      await page.locator('.chart-workspace').evaluate(el => { const c = el.__vueParentComponent.setupState.chart; c.setBarSpace(c.getSize('candle_pane', 'main').width / 260) })
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 350)
      assert.equal((await state(page)).gap, 'protected_control_history'); assert.equal(historyCalls, 1)
      console.log(`${app}: immutable marked archive remains visible`)
      archived = false; empty = true; reason = 'upstream_no_data'; historyCalls = 0; await page.reload()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
      await page.locator('.chart-workspace').evaluate(el => { const c = el.__vueParentComponent.setupState.chart; c.setBarSpace(c.getSize('candle_pane', 'main').width / 260) })
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.historySkips === 4)
      await wait(1400); assert.equal(historyCalls, 4); assert.equal((await state(page)).waiting, false)
      assert.equal(await page.locator('.chart-footer').isVisible(), true, 'compact/mobile gap state must remain accessible')
      await page.locator('.chart-footer .retry-history').first().click()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.historySkips === 5)
      assert.equal(historyCalls, 5); assert.deepEqual(errors, [])
      if (process.env.HISTORY_GAP_ARTIFACT_DIR) await page.screenshot({ path: `${process.env.HISTORY_GAP_ARTIFACT_DIR}/browser-${app}-gap.png` })
      console.log(`${app}: empty windows stop automatic traversal, manual older history remains available; no page errors`)
      await context.close()
    }
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
