// Both dev servers are required. All APIs and WebSockets are mocked; no accounts are changed.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
;(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
  try {
    for (const app of ['pc', 'mobile']) {
      const page = await browser.newPage({ viewport: app === 'pc' ? { width: 1500, height: 950 } : { width: 390, height: 844 } })
      const session = Math.floor(Date.now() / 1000) * 1000
      let simulated = false, socket
      await page.addInitScript(() => { localStorage.setItem('token', 'history-fixture'); localStorage.setItem('locale', 'en') })
      await page.routeWebSocket('**/api/ws/market', ws => {
        socket = ws
        ws.onMessage(raw => {
          const message = JSON.parse(raw)
          if (message.action === 'subscribe') ws.send(JSON.stringify({ type: 'subscribed', symbols: message.symbols }))
          if (message.action === 'ping') ws.send(JSON.stringify({ type: 'pong' }))
        })
      })
      await page.route('**/api/**', async route => {
        const url = new URL(route.request().url())
        let body = { ret: 200, data: [], list: [], success: true }
        if (/\/market\/(all|symbols)$/.test(url.pathname)) body = { list: [{ symbol: 'XAUUSD', alltickSymbol: 'XAUUSD', category: 'Metal', pricePrecision: 2 }] }
        else if (url.pathname.endsWith('/timezone')) body = { timezone: 'UTC' }
        else if (url.pathname.includes('/kline/') && !url.pathname.endsWith('/batch')) {
          const interval = url.searchParams.get('interval'), step = { '1m': 60000, '5m': 300000, '1h': 3600000 }[interval] || 300000
          const limit = Number(url.searchParams.get('limit')) || 200, bucket = Math.floor(session / step) * step
          const cursor = Number(url.searchParams.get('endTime')) || Infinity
          const oldEnd = Math.min(bucket - 2 * 86400000, Math.floor(cursor / step) * step)
          let rows = Array.from({ length: limit }, (_, i) => ({ timestamp: oldEnd - (limit - i - 1) * step, open_price: 90, high_price: 95, low_price: 85, close_price: 90, volume: 10 }))
          if (simulated && cursor >= bucket) rows.push({ timestamp: bucket, open_price: 90, high_price: 91, low_price: 89, close_price: 91, volume: 0 })
          rows = rows.slice(-limit)
          body = { ret: 200, data: { status: 'available', simulated, kline_list: rows } }
        }
        await route.fulfill({ json: body })
      })
      const read = () => page.locator('.chart-workspace').evaluate(el => el.__vueParentComponent.setupState.chart.getDataList().map(b => ({ ...b })))
      const url = app === 'pc' ? 'http://127.0.0.1:5187/' : 'http://127.0.0.1:5188/#/trade?symbol=XAUUSD&category=Metal'
      await page.goto(url)
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.count === 200)
      const old = await read()
      simulated = true
      assert.ok(socket)
      socket.send(JSON.stringify({ type: 'price', data: { XAUUSD: { price: 91, timestamp: Date.now(), fetchedAt: Date.now(), available: true, status: 'available', simulated: true, simulationSession: session, marketRevision: 1 } } }))
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.count === 201)
      let bars = await read()
      assert.deepEqual(bars.slice(0, 200), old, 'activation must preserve all existing candles')
      assert.ok(bars.at(-1).timestamp - bars.at(-2).timestamp >= 2 * 86400000, 'weekend gap must not be filled')
      assert.equal(new Set(bars.map(b => b.timestamp)).size, bars.length)
      await page.reload()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.count === 200)
      bars = await read()
      assert.deepEqual(bars.slice(0, -1), old.slice(1), 'refresh must retain available original history')
      await page.getByRole('button', { name: /^1h$/i }).click()
      await page.waitForFunction(() => {
        const s = document.querySelector('.chart-workspace')?.__vueParentComponent.setupState
        return s?.interval === '1h' && !s.loading && s.count === 200
      })
      bars = await read()
      assert.equal(bars.at(-1).open, 90)
      assert.equal(bars.at(-2).close, 90)
      assert.ok(bars.at(-1).timestamp - bars.at(-2).timestamp >= 2 * 86400000)
      assert.equal(new Set(bars.map(b => b.timestamp)).size, bars.length)
      console.log(app + ': PASS activation preserves history, reload, hourly period switch, no gap backfill or duplicates')
      await page.close()
    }
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
