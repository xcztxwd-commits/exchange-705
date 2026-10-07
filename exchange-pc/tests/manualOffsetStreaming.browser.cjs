const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')
const assert = require('node:assert/strict')

// All endpoints and the market socket are fixtures; no live control commands or orders.
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    for (const app of ['pc', 'mobile']) {
      const page = await browser.newPage({ viewport: app === 'pc' ? { width: 1500, height: 950 } : { width: 390, height: 844 } })
      let socket, source = 100, offset = 2, enabled = true, version = 1, configuration = 1, connections = 0, klineReads = 0, writes = 0
      const subscriptions = new Set(), errors = [], widths = { '1m': 60000, '5m': 300000, '15m': 900000, '30m': 1800000, '1h': 3600000, '1d': 86400000 }
      const ends = new Map(), previousEnds = new Map()
      const end = interval => { if (!ends.has(interval)) ends.set(interval, Math.floor(Date.now() / widths[interval]) * widths[interval] - widths[interval]); return ends.get(interval) }
      const displayed = () => source + (enabled ? offset : 0)
      const quote = () => ({ price: displayed(), timestamp: Date.now(), fetchedAt: Date.now(), available: true, status: 'available', epoch: 'fixture', quoteVersion: version,
        marketRevision: configuration, controlState: enabled ? 'MANUAL' : 'SOURCE', controlHistory: true, controlHistoryRevision: enabled ? 'manual:' + configuration : 'source', controlPublicationRevision: '0:0' })
      const bar = timestamp => ({ timestamp, open_price: 100, high_price: Math.max(110, displayed()), low_price: Math.min(90, displayed()), close_price: displayed(), volume: 1 })
      const live = interval => [bar(previousEnds.get(interval) ?? end(interval) - widths[interval]), bar(end(interval))]
      const push = (intervals = [...subscriptions], q = quote()) => socket?.send(JSON.stringify({ type: 'price', data: { XAUUSD: q }, klines: intervals.map(interval => ({ symbol: 'XAUUSD', interval, bars: live(interval), pending: false })) }))
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(() => { localStorage.setItem('locale', 'en'); localStorage.setItem('token', 'offset-test'); localStorage.setItem('user', JSON.stringify({ id: 1 })) })
      await page.routeWebSocket('**/api/ws/market', ws => {
        socket = ws; connections++; subscriptions.clear()
        ws.onMessage(raw => {
          const message = JSON.parse(raw)
          if (message.action === 'ping') ws.send(JSON.stringify({ type: 'pong' }))
          if (message.action === 'subscribe') { ws.send(JSON.stringify({ type: 'subscribed', symbols: message.symbols })); push() }
          if (message.action === 'subscribeKline') { subscriptions.add(message.interval); push() }
          if (message.action === 'unsubscribeKline') subscriptions.delete(message.interval)
        })
      })
      await page.route('**/api/**', async route => {
        const request = route.request(), url = new URL(request.url())
        if (request.method() === 'POST' && /\/trade\//.test(url.pathname)) writes++
        let body = { ret: 200, success: true, list: [], data: [] }
        if (/\/market\/(all|symbols)$/.test(url.pathname)) body = { list: [{ symbol: 'XAUUSD', alltickSymbol: 'XAUUSD', category: 'Metal', pricePrecision: 2, isHot: true, isEnabled: true }] }
        else if (url.pathname.endsWith('/timezone')) body = { timezone: 'UTC' }
        else if (url.pathname.endsWith('/tenant/features')) body = { tenantId: 1, status: 'ACTIVE', acceptNewBusiness: true, configReady: true, domainVerified: true, features: { contract: true, option: true, financial: true } }
        else if (url.pathname.endsWith('/price/batch')) body = { ret: 200, data: { XAUUSD: quote() } }
        else if (url.pathname.includes('/kline/')) {
          klineReads++
          const interval = url.searchParams.get('interval') || '5m', limit = Number(url.searchParams.get('limit') || 200)
          const last = url.searchParams.has('endTime') ? Number(url.searchParams.get('endTime')) - widths[interval] : end(interval)
          body = { ret: 200, data: { status: 'available', pending: false, kline_list: Array.from({ length: limit }, (_, i) => bar(last - (limit - 1 - i) * widths[interval])) } }
        }
        await route.fulfill({ json: body })
      })
      let pulse
      try {
        const url = app === 'pc' ? (process.env.PC_URL || 'http://127.0.0.1:5317/') : (process.env.MOBILE_URL || 'http://127.0.0.1:5318/trade?symbol=XAUUSD&category=Metal')
        await page.goto(url)
        await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.count >= 200)
        pulse = setInterval(() => push(), 250)
        const state = () => page.locator('.chart-workspace').evaluate(element => {
          const s = element.__vueParentComponent.setupState, data = s.chart.getDataList()
          return { close: data[data.length - 1]?.close, count: data.length, interval: s.interval, drawings: s.chart.getOverlays({groupId:'trading-drawings'}).length,
            first: data[0]?.timestamp, range: s.chart.getVisibleRange(), space: s.chart.getBarSpace(), healthy: s.klineStreamHealthy() }
        })
        await page.waitForFunction(() => document.querySelector('.chart-workspace').__vueParentComponent.setupState.klineStreamHealthy())
        await page.locator('.chart-workspace').evaluate(element => {
          const s = element.__vueParentComponent.setupState, last = s.chart.getDataList().at(-1)
          window.originalOffsetChart = s.chart
          s.chart.createOverlay({ name:'horizontalStraightLine',groupId:'trading-drawings',points:[{timestamp:last.timestamp,value:102}] })
          s.chart.scrollByDistance(30, 0)
        })
        const before = await state(), reads = klineReads
        offset = 5; configuration++; version++; push()
        await page.waitForFunction(() => document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getDataList().at(-1).close === 105)
        source = 101; version++; push()
        await page.waitForFunction(() => document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getDataList().at(-1).close === 106)
        push([...subscriptions], { ...quote(), quoteVersion: 1, price: 50 })
        await page.waitForTimeout(100)
        assert.equal((await state()).close, 106, 'old frame cannot roll back the chart')
        offset = -2; configuration++; version++; push()
        await page.waitForFunction(() => document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getDataList().at(-1).close === 99)
        enabled = false; configuration++; version++; push()
        await page.waitForFunction(() => document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getDataList().at(-1).close === 101)
        await page.waitForTimeout(3300)
        const after = await state()
        assert.equal(klineReads, reads, 'healthy control stream does not poll HTTP candles')
        assert.equal(after.drawings, before.drawings)
        assert.deepEqual(after.range, before.range)
        assert.deepEqual(after.space, before.space)
        assert(await page.locator('.chart-workspace').evaluate(element => element.__vueParentComponent.setupState.chart === window.originalOffsetChart))
        const interval = after.interval, oldEnd = end(interval)
        previousEnds.set(interval, oldEnd); ends.set(interval, oldEnd + widths[interval]); version++; push()
        await page.waitForFunction(count => document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getDataList().length === count + 1, after.count)
        assert.equal((await state()).drawings, before.drawings)
        await page.getByRole('button', { name: /^1m$/i }).first().click()
        await page.waitForFunction(() => document.querySelector('.chart-workspace').__vueParentComponent.setupState.interval === '1m')
        assert(subscriptions.has('1m'))
        assert(!subscriptions.has(interval) || interval === '1m')
        socket.close()
        await page.waitForFunction(() => document.querySelector('.chart-workspace').__vueParentComponent.setupState.klineStreamHealthy(), {timeout:10000})
        // Wait for the fixture's actual new subscription, not a retained pre-close candle.
        const until = Date.now() + 10000
        while ((connections < 2 || !subscriptions.has('1m')) && Date.now() < until) await page.waitForTimeout(50)
        assert(connections >= 2 && subscriptions.has('1m'))
        source = 101.25; version++; push()
        await page.waitForFunction(() => document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getDataList().at(-1).close === 101.25)
        assert.equal(writes, 0)
        assert.deepEqual(errors, [])
        console.log(`${app}: PASS offset/source updates, no chart reset or HTTP polling, drawings/viewport, ordering, new candle, period switch, reconnect; live writes=0`)
      } finally { clearInterval(pulse); await page.close() }
    }
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
