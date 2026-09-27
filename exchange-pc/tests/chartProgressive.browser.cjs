// Run against local PC/mobile Vite servers on 5187/5188 with PLAYWRIGHT_PATH.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))
const symbols = [{ symbol: 'BABA', alltickSymbol: 'BABA', category: 'US', pricePrecision: 2 }, { symbol: 'XAUUSD', alltickSymbol: 'XAUUSD', category: 'Metal', pricePrecision: 2 }]
const rows = (limit, before, step) => Array.from({ length: limit }, (_, i) => {
  const timestamp = before - (limit - i) * step
  return { timestamp, open_price: step === 60000 ? 100 : 80, high_price: step === 60000 ? 102 : 82,
    low_price: step === 60000 ? 99 : 79, close_price: step === 60000 ? 101 : 81, volume: 10 }
})
const chartState = page => page.locator('.chart-workspace').evaluate(el => {
  const s = el.__vueParentComponent.setupState, data = s.chart.getDataList()
  return { count: data.length, times: data.map(bar => bar.timestamp), closes: data.map(bar => bar.close), loading: s.loading, historyLoading: s.historyLoading,
    error: s.error, waiting: s.historyWaiting, interval: s.interval, width: s.chart.getSize('candle_pane', 'main').width,
    range: s.chart.getVisibleRange(), overlays: el.querySelectorAll('.chart-message').length }
})
async function zoomTo(page, visible) {
  await page.locator('.chart-workspace').evaluate((el, count) => {
    const chart = el.__vueParentComponent.setupState.chart
    chart.setBarSpace(Math.max(1, chart.getSize('candle_pane', 'main').width / count))
  }, visible)
}

;(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
  try {
    for (const app of ['pc', 'mobile']) {
      const page = await browser.newPage({ viewport: app === 'pc' ? { width: 1600, height: 950 } : { width: 500, height: 850 } })
      const errors = [], calls = []
      let mode = 'delay', failures = 0, partialCalls = 0
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
        if (url.pathname.endsWith('/market/all') || url.pathname.endsWith('/market/symbols')) body = { list: symbols }
        else if (url.pathname.endsWith('/timezone')) body = { timezone: 'Asia/Singapore' }
        else if (url.pathname.includes('/kline/') && !url.pathname.endsWith('/batch')) {
          const history = url.pathname.includes('/history/'), interval = url.searchParams.get('interval') || '5m'
          const limit = Number(url.searchParams.get('limit')) || 200, step = interval === '1m' ? 60000 : 300000
          const latest = Math.floor(Date.now() / step) * step + step
          const before = history ? Number(url.searchParams.get('endTime')) + 1 : latest
          calls.push({ mode, history, interval, limit, before })
          if (history && (mode === 'delay' || mode === 'switch')) await wait(5000)
          if (history && mode === 'fail' && failures++ === 0) {
            await route.fulfill({ json: { ret: 503, data: { status: 'unavailable', pending: false, kline_list: [] } } }); return
          }
          if (history && mode === 'partial' && partialCalls++ === 0) {
            body = { ret: 200, data: { pending: true, status: 'available', kline_list: rows(limit, before, step).slice(-100) } }
            await route.fulfill({ json: body }); return
          }
          body = { ret: 200, data: { pending: mode === 'pending' && !history, status: 'available', kline_list: rows(limit, before, step) } }
        }
        await route.fulfill({ json: body })
      })
      const url = app === 'pc' ? 'http://127.0.0.1:5187/' : 'http://127.0.0.1:5188/#/trade?symbol=BABA&category=US'
      const started = Date.now()
      await page.goto(url)
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
      const firstMs = Date.now() - started
      const visible = app === 'pc' ? 400 : 300
      await zoomTo(page, visible)
      await wait(250)
      let state = await chartState(page)
      assert.equal(state.count, 200, `${app}: first batch must remain operable while page two is delayed`)
      assert.equal(state.overlays, 0, `${app}: loading overlay must not cover valid candles`)
      await page.waitForFunction(expected => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count >= expected, app === 'pc' ? 500 : 400, { timeout: 12000 })
      const fullMs = Date.now() - started
      state = await chartState(page)
      assert.equal(new Set(state.times).size, state.count)
      assert.ok(fullMs - firstMs >= 4900)
      assert.ok(calls.filter(call => call.history).length <= 3, 'no request storm on initial zoom')
      console.log(`${app} browser progressive first=${firstMs}ms full=${fullMs}ms count=${state.count}`)

      mode = 'pending'
      await page.reload()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
      state = await chartState(page)
      assert.equal(state.overlays, 0)
      assert.equal(state.loading, false)
      console.log(`${app} pending=true with valid bars visible`)

      mode = 'partial'; partialCalls = 0
      await page.reload()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
      await zoomTo(page, visible)
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 300)
      state = await chartState(page)
      assert.equal(new Set(state.times).size, 300)
      assert.equal(state.overlays, 0)
      await page.waitForFunction(expected => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count >= expected, app === 'pc' ? 500 : 400)
      state = await chartState(page)
      assert.equal(new Set(state.times).size, state.count)
      assert.equal(calls.filter(call => call.mode === 'partial' && call.history)[0].before,
        calls.filter(call => call.mode === 'partial' && call.history)[1].before)
      console.log(`${app} partial history page ${300}->${state.count} retried original cursor`)

      mode = 'fail'; failures = 0
      await page.reload()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
      await zoomTo(page, visible)
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.error === true)
      state = await chartState(page)
      assert.equal(state.count, 200)
      await page.locator('.chart-footer .retry-history').first().click()
      await page.waitForFunction(expected => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count >= expected, app === 'pc' ? 500 : 400)
      assert.ok(calls.filter(call => call.mode === 'fail' && !call.history).length <= 2, 'retry must not reload first page; latest sync may overlap')
      const failedHistory = calls.filter(call => call.mode === 'fail' && call.history)
      assert.equal(failedHistory[0].before, failedHistory[1].before, 'retry resumes the failed cursor')
      console.log(`${app} failed history retried from cursor`)

      mode = 'normal'
      const before = (await chartState(page)).count
      await zoomTo(page, app === 'pc' ? 650 : 450)
      await page.waitForFunction(previous => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count > previous, before)
      state = await chartState(page)
      assert.equal(new Set(state.times).size, state.count)
      const beforeDrag = state.range.from
      const bounds = await page.locator('.kline-chart').boundingBox()
      const x = bounds.x + bounds.width * 0.4, y = bounds.y + bounds.height * 0.5
      await page.mouse.move(x, y)
      await page.mouse.down()
      await page.mouse.move(x + 180, y, { steps: 8 })
      await page.mouse.up()
      assert.notEqual((await chartState(page)).range.from, beforeDrag, 'drag changes the visible range')
      await page.getByRole('button', { name: 'Fullscreen' }).first().click()
      await page.setViewportSize(app === 'pc' ? { width: 1850, height: 1000 } : { width: 520, height: 880 })
      await wait(300)
      assert.ok(calls.filter(call => call.mode === 'normal' && call.history).length < 10)
      assert.deepEqual(errors, [])
      console.log(`${app} zoom/drag/fullscreen/resize count=${(await chartState(page)).count} no page errors`)

      mode = 'switch'
      await page.reload()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
      await zoomTo(page, visible)
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.historyLoading === true)
      await page.getByRole('button', { name: '1m', exact: true }).first().click()
      await page.waitForFunction(() => {
        const s = document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState
        return s?.interval === '1m' && s?.count >= 200
      })
      await wait(5200)
      state = await chartState(page)
      assert.ok(state.closes.every(close => close === 101), 'old 5m response must not enter the 1m chart')
      assert.deepEqual(errors, [])
      console.log(`${app} old 5m response rejected after fast period switch`)
      await page.close()
    }
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
