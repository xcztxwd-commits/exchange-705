// Browser plugin not available. Use existing Playwright runtime and dev servers; no real orders.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), os = require('node:os')
const output = path.join(os.tmpdir(), '705-market-streaming-browser')
fs.mkdirSync(output, { recursive: true })
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    for (const app of ['pc', 'mobile']) {
      const page = await browser.newPage({ viewport: app === 'pc' ? { width: 1500, height: 950 } : { width: 390, height: 844 } })
      const errors = [], warnings = []
      page.on('pageerror', error => errors.push(error.message))
      page.on('console', message => { if (['error', 'warning'].includes(message.type())) warnings.push(message.text()) })
      let socket, connections = 0, priceRequests = 0, hold = false, price = 321.12, version = 1, epoch = 'one'
      const quote = () => ({ price, timestamp: Date.now(), fetchedAt: Date.now(), status: 'available', available: true, epoch, quoteVersion: version })
      const push = value => socket.send(JSON.stringify({ type: 'price', data: { XAUUSD: value || quote() } }))
      await page.addInitScript(() => { localStorage.setItem('locale', 'en'); localStorage.setItem('token', 'stream-test') })
      await page.routeWebSocket('**/api/ws/market', ws => {
        socket = ws; connections++
        ws.onMessage(raw => {
          const message = JSON.parse(raw)
          if (message.action === 'ping') ws.send(JSON.stringify({ type: 'pong' }))
          if (message.action === 'subscribe' && !hold) {
            ws.send(JSON.stringify({ type: 'subscribed', symbols: message.symbols }))
            if (message.symbols.includes('XAUUSD')) push()
          }
        })
      })
      await page.route('**/api/**', async route => {
        const url = new URL(route.request().url())
        let body = { ret: 200, success: true, list: [], data: [] }
        if (/\/market\/(all|symbols)$/.test(url.pathname)) body = { list: [{ symbol: 'XAUUSD', alltickSymbol: 'XAUUSD', category: 'Metal', pricePrecision: 2, isHot: true }] }
        else if (url.pathname.endsWith('/timezone')) body = { timezone: 'UTC' }
        else if (/\/price\/batch$/.test(url.pathname)) { priceRequests++; body = { ret: 200, data: { XAUUSD: quote() } } }
        else if (url.pathname.includes('/kline/')) {
          const width = ({ '1m': 60000, '5m': 300000, '1h': 3600000 })[url.searchParams.get('interval')] || 300000
          const end = Math.floor(Date.now() / width) * width
          body = { ret: 200, data: { status: 'available', kline_list: Array.from({ length: 200 }, (_, i) => ({ timestamp: end - (199-i)*width, open_price: 320, high_price: 325, low_price: 315, close_price: 320, volume: 1 })) } }
        }
        await route.fulfill({ json: body })
      })
      await page.goto(app === 'pc' ? 'http://127.0.0.1:5187/' : 'http://127.0.0.1:5188/#/trade?symbol=XAUUSD&category=Metal')
      const value = () => page.locator('.chart-workspace').evaluate(el => el.__vueParentComponent.setupState.market.priceMap.XAUUSD?.price)
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.market.priceMap.XAUUSD?.price === 321.12)
      assert.ok(await page.title()); assert.equal(await page.locator('vite-error-overlay').count(), 0)
      const count = priceRequests
      await page.waitForTimeout(3500)
      assert.equal(priceRequests, count, 'healthy subscriptions stop HTTP polling')
      push({ ...quote(), price: 111, quoteVersion: 0 }); await page.waitForTimeout(100)
      assert.equal(await value(), 321.12, 'out-of-order frame is ignored')
      push({ status: 'unavailable', epoch, quoteVersion: ++version })
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.market.getQuoteStatus('XAUUSD') === 'unavailable')
      assert.equal(await value(), 321.12, 'status-only failure retains the visible last price')
      price = 322.12; version++; hold = true; socket.close()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.market.priceMap.XAUUSD?.price === 322.12)
      assert.ok(priceRequests > count, 'disconnect activates HTTP fallback')
      await page.waitForTimeout(1700); assert.ok(connections >= 2)
      hold = false; epoch = 'two'; version = 1; price = 333.12; push()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.market.priceMap.XAUUSD?.price === 333.12)
      push({ ...quote(), epoch: 'one', quoteVersion: 999, price: 111 }); await page.waitForTimeout(100)
      assert.equal(await value(), 333.12)
      await page.getByRole('button', { name: /^1m$/i }).click()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.interval === '1m')
      await page.screenshot({ path: path.join(output, `${app}.png`), fullPage: false })
      assert.deepEqual(errors, [])
      // Record all warnings for review; fixture-empty account endpoints may log application warnings.
      fs.writeFileSync(path.join(output, `${app}.json`), JSON.stringify({ url: page.url(), title: await page.title(), connections, priceRequests, errors, warnings }, null, 2))
      console.log(`${app}: PASS snapshot, no duplicate polling, ordering, status-only outage, fallback, reconnect, epoch switch, chart interaction`)
      await page.close()
    }
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exit(1) })
