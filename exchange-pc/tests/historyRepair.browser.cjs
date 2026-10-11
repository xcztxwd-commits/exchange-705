// Local Vite fixtures only. Every API/WS is intercepted; no backend, exchange or production database is used.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))
const step = 300000, origin = Math.floor(Date.now() / step) * step - 500 * step
const bars = (from, to) => Array.from({ length: to - from + 1 }, (_, i) => ({ timestamp: origin + (from + i) * step,
  open_price: 100, high_price: 105, low_price: 99, close_price: 101, volume: 10 }))
const state = page => page.locator('.chart-workspace').evaluate(el => {
  const s = el.__vueParentComponent.setupState
  return { count: s.count, gap: s.historyGap, waiting: s.historyWaiting, error: s.error,
    syncError: s.syncError, times: s.chart.getDataList().map(bar => bar.timestamp), closes: s.chart.getDataList().map(bar => bar.close) }
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
      const page = await context.newPage(), errors = [], consoleErrors = []
      let reason = '', empty = false, archived = false, pending = false, historyCalls = 0
      let forex = false, latestCalls = 0, latestFailure = false, reopened = false, marketSocket
      const pushForex = (status, controlActive = false) => marketSocket.send(JSON.stringify({ type: 'price', data: { 'JPY=X': {
        price: 158.246, timestamp: Date.now(), fetchedAt: Date.now(), executionExpiresAt: Date.now() + 60000,
        status, available: status === 'available', controlActive } } }))
      page.on('pageerror', error => errors.push(error.message))
      page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()) })
      await page.addInitScript(() => { localStorage.setItem('token', 'chart-test-fixture'); localStorage.setItem('locale', 'en') })
      await page.routeWebSocket('**/api/ws/market', socket => { marketSocket = socket; socket.onMessage(raw => {
        const data = JSON.parse(raw)
        if (data.action === 'subscribe') {
          socket.send(JSON.stringify({ type: 'subscribed', symbols: data.symbols }))
          if (forex && data.symbols.includes('JPY=X')) pushForex(reopened ? 'available' : 'closed')
        }
        if (data.action === 'ping') socket.send(JSON.stringify({ type: 'pong' }))
      }) })
      await page.route('**/api/**', async route => {
        const url = new URL(route.request().url())
        let body = { ret: 200, data: [], list: [], success: true }
        if (url.pathname.endsWith('/market/all') || url.pathname.endsWith('/market/symbols'))
          body = { list: [{ symbol: forex ? 'JPY=X' : 'BTCUSDT', alltickSymbol: forex ? 'JPY=X' : 'BTCUSDT', category: forex ? 'Forex' : 'Crypto', sourceCategory: forex ? 'Forex' : 'Crypto', pricePrecision: forex ? 3 : 2 }] }
        else if (url.pathname.endsWith('/timezone')) body = { timezone: 'Asia/Singapore' }
        else if (url.pathname.includes('/kline/') && !url.pathname.endsWith('/batch')) {
          const history = url.pathname.includes('/history/')
          if (forex && !history) {
            latestCalls++
            if (latestFailure) return route.fulfill({ status: 503, json: { ret: 503 } })
          }
          if (history) historyCalls++
          const sparse = [...bars(101, 150), ...bars(201, 300)]
          body = { ret: 200, data: { pending: false, kline_list: history ? empty ? [] : archived
            ? sparse.map(bar => ({ ...bar, controlled: true, historyReplaced: true })) : sparse : bars(301, 500),
            ...(history && !archived ? { missingData: reason, historyRepair: { pending: false, state: 'unrepairable',
              gaps: [{ reason, recoverable: false }], nextCursor: Number(url.searchParams.get('endTime')) - 200 * step } } : {}),
            ...(!history ? { historyRepair: { pending: false, state: 'complete', gaps: [] } } : {}) } }
          if (forex) {
            body.data.kline_list = body.data.kline_list.map(bar => ({ ...bar, timestamp: bar.timestamp - 86400000,
              open_price: 158.303, high_price: 158.319, low_price: 158.246, close_price: 158.246, volume: 0 }))
            if (reopened && !history) body.data.kline_list.push({ ...body.data.kline_list.at(-1), timestamp: Math.floor(Date.now() / step) * step })
          }
        }
        if (pending && url.pathname.includes('/history/')) {
          const older = Number(url.searchParams.get('endTime')) === origin + 101 * step - 1
          body = { ret: 200, data: { pending: !older, kline_list: older ? bars(1, 100) : bars(201, 300),
            historyRepair: { pending: !older, gaps: older ? [] : [{ reason: 'missing_source', recoverable: true }], nextCursor: origin + 101 * step } } }
        }
        await route.fulfill({ json: body })
      })
      const url = app === 'pc' ? process.env.PC_QA_URL || 'http://127.0.0.1:5187/' : process.env.MOBILE_QA_URL || 'http://127.0.0.1:5188/#/trade?symbol=BTCUSDT&category=Crypto'
      for (reason of ['missing_control_samples', 'control_samples_unverified', 'protected_control_history', 'sealed_history', 'source_period_anchor_unverified', 'upstream_no_data', 'source_calendar_unverified', 'source_fetch_or_write_failed', 'existing_partial_or_invalid']) {
        historyCalls = 0; await page.goto(url); await page.reload()
        assert.equal(await page.title(), 'FOREX-EXCHANGE')
        await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
        await page.locator('.chart-workspace').evaluate(el => { const chart = el.__vueParentComponent.setupState.chart; chart.setBarSpace(chart.getSize('candle_pane', 'main').width / 260) })
        await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 350)
        const s = await state(page)
        assert.equal(s.gap, reason); assert.equal(s.waiting, false); assert.equal(s.error, false)
        assert.equal(await page.locator('.chart-footer').isVisible(), app === 'pc', 'history notices stay on desktop and remain hidden in the mobile app')
        await page.locator('.forex-transition').waitFor({ state: 'hidden' })
        const notice = await page.locator('.chart-footer').innerText()
        assert.doesNotMatch(notice, /control|\u63a7[\u76e4\u76d8]/i, 'public notices must use neutral history wording')
        if (reason === 'missing_control_samples') assert.match(notice, /Historical detail data is missing/)
        if (reason === 'control_samples_unverified') assert.match(notice, /completeness unverified/)
        if (reason === 'protected_control_history') { assert.match(notice, /History in this range is protected/); assert.doesNotMatch(notice, /samples.*(required|missing)/) }
        if (reason === 'sealed_history') assert.match(notice, /History is sealed/)
        if (reason === 'source_period_anchor_unverified') assert.match(notice, /period boundaries.*unverified/)
        if (reason === 'existing_partial_or_invalid') assert.match(notice, /candles are incomplete or invalid/)
        if (process.env.HISTORY_GAP_ARTIFACT_DIR && ['missing_control_samples', 'protected_control_history', 'existing_partial_or_invalid'].includes(reason))
          await page.screenshot({ path: `${process.env.HISTORY_GAP_ARTIFACT_DIR}/browser-${app}-${reason}.png` })
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
      if (app === 'pc') await page.locator('.skip-pending-history').click()
      else await page.locator('.chart-workspace').evaluate(el => el.__vueParentComponent.setupState.continueOlderHistory())
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
      assert.equal(await page.locator('.chart-footer').isVisible(), app === 'pc', 'mobile history notices must remain hidden for empty windows')
      if (app === 'pc') await page.locator('.chart-footer .retry-history').first().click()
      else await page.locator('.chart-workspace').evaluate(el => el.__vueParentComponent.setupState.retryLoad())
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.historySkips === 5)
      assert.equal(historyCalls, 5); assert.deepEqual(errors, [])
      // Wallet responses are deliberately empty fixtures; external resources are deliberately aborted above.
      assert.ok(consoleErrors.every(message => /Wallet unavailable|net::ERR_FAILED/.test(message)), 'unexpected application console error')
      if (process.env.HISTORY_GAP_ARTIFACT_DIR) fs.writeFileSync(`${process.env.HISTORY_GAP_ARTIFACT_DIR}/browser-${app}-console.json`, JSON.stringify({
        errors: consoleErrors, explanation: 'Wallet responses are empty fixtures; external resources are blocked; no chart/runtime errors.' }, null, 2))
      if (process.env.HISTORY_GAP_ARTIFACT_DIR) await page.screenshot({ path: `${process.env.HISTORY_GAP_ARTIFACT_DIR}/browser-${app}-gap.png` })
      console.log(`${app}: empty windows stop automatic traversal; pagination state preserved; no page errors`)
      forex = true; empty = false; reason = 'existing_partial_or_invalid'; historyCalls = 0
      await page.goto(app === 'pc' ? url : url.replace('BTCUSDT', 'JPY%3DX').replace('category=Crypto', 'category=Forex'))
      await page.reload()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.props?.symbol === 'JPY=X'
        && document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
      const closedBars = (await state(page)).times, closedCalls = latestCalls
      await page.locator('.chart-workspace').evaluate(el => { const s = el.__vueParentComponent.setupState; s.syncError = true; s.replayQuote(); return s.syncLatest() })
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.syncError === false)
      await wait(1200)
      assert.equal(latestCalls, closedCalls, 'closed markets do not poll for nonexistent live candles')
      assert.deepEqual((await state(page)).times, closedBars)
      assert.equal(await page.locator('.chart-footer').isVisible(), app === 'pc', 'mobile closure notices must stay hidden')
      assert.match(await page.locator('.chart-footer').innerText(), /Market closed; showing last available candles/)
      assert.doesNotMatch(await page.locator('.chart-footer').innerText(), /Latest candles incomplete/)
      await page.locator('.chart-workspace').evaluate(el => { const c = el.__vueParentComponent.setupState.chart; c.setBarSpace(c.getSize('candle_pane', 'main').width / 260) })
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 350)
      assert.match(await page.locator('.chart-footer').innerText(), /candles are incomplete or invalid/)
      await page.locator('.forex-transition').waitFor({ state: 'hidden' })
      if (process.env.HISTORY_GAP_ARTIFACT_DIR) await page.screenshot({ path: `${process.env.HISTORY_GAP_ARTIFACT_DIR}/browser-${app}-forex-closed.png` })
      if (app === 'mobile') {
        await page.locator('.chart-workspace').getByRole('button', { name: 'Fullscreen', exact: true }).click()
        await page.locator('.chart-workspace.chart-fullscreen').waitFor()
        assert.equal(await page.locator('.chart-footer').isVisible(), false, 'fullscreen must not restore mobile notices')
        await page.setViewportSize({ width: 850, height: 500 })
        assert.equal(await page.locator('.chart-footer').isVisible(), false, 'landscape must not restore mobile notices')
        if (process.env.HISTORY_GAP_ARTIFACT_DIR) await page.screenshot({ path: `${process.env.HISTORY_GAP_ARTIFACT_DIR}/browser-mobile-landscape-fullscreen.png` })
        await page.locator('.chart-workspace').getByRole('button', { name: 'Fullscreen', exact: true }).click()
        await page.setViewportSize({ width: 500, height: 850 })
        assert.equal(await page.locator('.chart-footer').isVisible(), false)
      }
      latestFailure = true; pushForex('closed', true)
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.market.quoteStatusMap['JPY=X']?.controlActive === true)
      await page.locator('.chart-workspace').evaluate(el => el.__vueParentComponent.setupState.syncLatest())
      assert.equal((await state(page)).syncError, true, 'active controls retain actual sync failures even while the source market is closed')
      assert.equal(await page.locator('.chart-footer').isVisible(), app === 'pc', 'mobile sync notices must stay hidden too')
      if (app === 'pc') assert.match(await page.locator('.chart-footer').innerText(), /Latest candles incomplete/)
      else assert.doesNotMatch(await page.locator('body').innerText(), /Latest candles incomplete/)
      const callsBeforeReopen = latestCalls
      latestFailure = false; reopened = true; pushForex('available')
      await page.waitForFunction(() => { const s = document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState
        return s?.market.quoteStatusMap['JPY=X']?.status === 'available' && s.syncError === false && s.chart.getDataList().at(-1)?.timestamp >= Date.now() - 300000 })
      assert.ok(latestCalls > callsBeforeReopen, 'reopening resumes source synchronization')
      assert.equal((await state(page)).gap, 'existing_partial_or_invalid', 'reopening cannot erase an older history warning')
      assert.equal(await page.locator('.chart-footer').isVisible(), app === 'pc', 'mobile notices remain hidden after reopening')
      assert.doesNotMatch(await page.locator('.chart-footer').innerText(), /Market closed/)
      assert.deepEqual(errors, [])
      assert.ok(consoleErrors.every(message => /Wallet unavailable|net::ERR_FAILED|status of 503 \(Service Unavailable\)/.test(message)), 'unexpected console error after Forex transitions')
      if (process.env.HISTORY_GAP_ARTIFACT_DIR) {
        fs.writeFileSync(`${process.env.HISTORY_GAP_ARTIFACT_DIR}/browser-${app}-console.json`, JSON.stringify({ errors: consoleErrors,
          explanation: 'Empty wallet fixtures, blocked external resources and deliberately injected 503 sync failures; no chart/runtime errors.' }, null, 2))
        await page.screenshot({ path: `${process.env.HISTORY_GAP_ARTIFACT_DIR}/browser-${app}-forex-reopened.png` })
      }
      console.log(`${app}: closed Forex keeps last candles, reports actual old partial history, resumes at reopening and preserves control sync failures`)
      await context.close()
    }
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
