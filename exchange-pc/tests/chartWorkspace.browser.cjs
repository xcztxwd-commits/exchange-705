// Run with PC/mobile dev servers on 5187/5188 and PLAYWRIGHT_PATH if not installed locally.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const assert = require('node:assert/strict')
const output = path.join(os.tmpdir(), 'chart-professional-705')
fs.mkdirSync(output, { recursive: true })
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const report = []
async function state(page) {
  return page.locator('.chart-workspace').evaluate(el => {
    const s = el.__vueParentComponent.setupState, bars = s.chart.getDataList()
    return { count: bars.length, first: bars[0]?.timestamp, indicators: s.chart.getIndicators().map(i => ({ name: i.name, pane: i.paneId, params: i.calcParams, results: i.result.length, last: i.result.at(-1) })), timezone: s.chart.getTimezone(), grid: s.chart.getStyles().grid.show, limited: s.historyLimited, error: s.error, times: bars.map(b => b.timestamp) }
  })
}
async function dragOlder(page, minimum) {
  for (let i = 0; i < 14 && (await state(page)).count < minimum; i++) {
    const box = await page.locator('.chart-workspace').evaluate(el => {
      const rect = el.__vueParentComponent.setupState.chart.getDom('candle_pane', 'main').getBoundingClientRect()
      return { x: rect.x, y: rect.y, width: rect.width, height: rect.height }
    })
    const y = box.y + Math.min(45, box.height / 2)
    await page.mouse.move(box.x + 22, y)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width - 20, y, { steps: 14 })
    await page.mouse.up()
    await sleep(180)
  }
}
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
  try {
    for (const app of (process.argv.includes('--mobile') ? ['mobile'] : ['pc', 'mobile'])) {
      const page = await browser.newPage({ viewport: app === 'pc' ? { width: 1500, height: 950 } : { width: 390, height: 844 } })
      let mode = 'normal', historyCalls = 0, limits = [], errors = []
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(() => { localStorage.setItem('token', 'chart-test-fixture'); localStorage.setItem('locale', 'en') })
      await page.routeWebSocket('**/api/ws/market', socket => socket.onMessage(raw => {
        const data = JSON.parse(raw)
        if (data.action === 'subscribe') socket.send(JSON.stringify({ type: 'subscribed', symbols: data.symbols }))
        if (data.action === 'ping') socket.send(JSON.stringify({ type: 'pong' }))
      }))
      await page.route('**/api/**', async route => {
        const url = new URL(route.request().url())
        const symbols = [{ symbol: 'XAUUSD', alltickSymbol: 'XAUUSD', category: 'Metal', pricePrecision: 2 }]
        let body = { ret: 200, data: [], list: [], success: true }
        if (url.pathname.endsWith('/market/all') || url.pathname.endsWith('/market/symbols')) body = { list: symbols }
        else if (url.pathname.endsWith('/timezone')) body = { timezone: 'America/New_York' }
        else if (url.pathname.includes('/kline/') && !url.pathname.endsWith('/batch')) {
          const history = url.pathname.includes('/history/'), limit = Number(url.searchParams.get('limit')) || 200
          if (history) historyCalls++
          else limits.push(limit)
          if (history && mode === 'fallback') { await route.fulfill({ status: 404, json: { error: 'Not Found' } }); return }
          if ((history || limit > 200) && mode === 'transient') { await route.fulfill({ json: { ret: 503, data: { status: 'unavailable', pending: false, kline_list: [] } } }); return }
          const step = 300000, base = history ? Math.floor(Number(url.searchParams.get('endTime')) / step) * step : Math.floor(Date.now() / step) * step
          const rows = Array.from({ length: limit }, (_, i) => {
            const timestamp = base - (limit - i - 1) * step, close = 4500 + Math.sin(timestamp / step / 8) * 12
            return { timestamp, open_price: close - 1, high_price: close + 2, low_price: close - 3, close_price: close, volume: 100 + i }
          })
          body = { ret: 200, data: { status: 'available', kline_list: rows } }
        }
        await route.fulfill({ json: body })
      })
      const url = app === 'pc' ? 'http://127.0.0.1:5187/' : 'http://127.0.0.1:5188/#/trade?symbol=XAUUSD&category=Metal'
      const load = async () => { await page.goto(url); await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200); await sleep(150) }
      const record = (name, details) => { report.push({ app, name, pass: true, details }); console.log(app, name, 'PASS') }
      await load()
      await page.getByRole('button', { name: /Indicators/ }).click()
      assert.equal(await page.locator('.indicator-card').count(), 26)
      for (const name of ['EMA', 'BOLL', 'MACD', 'RSI']) await page.getByRole('checkbox', { name, exact: true }).check()
      await page.getByRole('spinbutton', { name: 'MA parameter 1', exact: true }).fill('7')
      await page.getByRole('spinbutton', { name: 'MA parameter 1', exact: true }).press('Tab')
      await page.locator('.panel-content').evaluate(el => { el.scrollTop = 0 })
      await page.screenshot({ path: path.join(output, app + '-indicators.png') })
      await page.getByRole('button', { name: 'Done', exact: true }).click()
      let s = await state(page)
      assert.equal(s.indicators.length, 6)
      assert.equal(s.indicators.find(i => i.name === 'MA').params[0], 7)
      assert.equal(new Set(s.indicators.filter(i => ['VOL', 'MACD', 'RSI'].includes(i.name)).map(i => i.pane)).size, 3)
      record('multi-select, parameters and independent oscillator panes', s.indicators)
      await page.getByRole('button', { name: 'Timezone settings', exact: true }).first().click()
      await page.getByRole('searchbox', { name: 'Search timezones' }).fill('Asia/Tokyo')
      await page.getByRole('button', { name: 'Asia/Tokyo', exact: true }).click()
      await page.getByRole('button', { name: 'Done', exact: true }).click()
      await page.getByRole('button', { name: 'Chart settings', exact: true }).click()
      await page.getByLabel('Grid lines', { exact: true }).uncheck()
      await page.getByRole('combobox', { name: 'Price scale' }).selectOption('logarithm')
      await page.getByRole('button', { name: 'Done', exact: true }).click()
      await page.reload(); await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
      s = await state(page)
      assert.equal(s.timezone, 'Asia/Tokyo'); assert.equal(s.grid, false); assert.equal(s.indicators.length, 6)
      record('timezone, settings and studies survive reload', s)
      await page.getByRole('button', { name: 'Chart snapshot', exact: true }).click()
      await page.locator('.snapshot-preview').waitFor()
      const size = await page.locator('.snapshot-preview').evaluate(image => ({ width: image.naturalWidth, height: image.naturalHeight }))
      assert.ok(size.width > 200 && size.height > 400)
      const downloadPromise = page.waitForEvent('download')
      await page.getByRole('link', { name: 'Download PNG' }).click()
      const download = await downloadPromise
      await download.saveAs(path.join(output, app + '-snapshot.png'))
      assert.ok(download.suggestedFilename().endsWith('.png'))
      await page.getByRole('button', { name: 'Done', exact: true }).click()
      record('PNG preview and actual browser download', size)
      // Exercise every exposed built-in, then restore the compact default workspace.
      await page.getByRole('button', { name: /Indicators/ }).click()
      for (const checkbox of await page.locator('.indicator-card input[type=checkbox]').all()) await checkbox.check()
      await sleep(300)
      s = await state(page); assert.equal(s.indicators.length, 26); assert.ok(s.indicators.every(i => i.results === 200))
      for (const indicator of s.indicators) assert.ok(Object.values(indicator.last).every(Number.isFinite), indicator.name + ' must calculate finite values')
      record('all 26 indicators calculate', s.indicators.map(i => i.name))
      await page.getByRole('button', { name: 'Done', exact: true }).click()
      await page.getByRole('button', { name: 'Chart settings', exact: true }).click()
      await page.getByRole('button', { name: 'Reset settings and indicators' }).click()
      await page.getByRole('button', { name: 'Done', exact: true }).click()
      await sleep(300)
      const before = (await state(page)).first
      await dragOlder(page, 400); s = await state(page)
      await page.screenshot({ path: path.join(output, app + '-drag-debug.png') }); console.log('drag', JSON.stringify({app, count:s.count, before, first:s.first, historyCalls})); assert.ok(s.count >= 400 && s.first < before); assert.equal(new Set(s.times).size, s.count)
      record('real mouse drags automatically fetch older candles without duplicates', { count: s.count, historyCalls })
      await page.screenshot({ path: path.join(output, app + '-workspace.png') })
      if (app === 'mobile') {
        await page.reload(); await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
        const session = await page.context().newCDPSession(page)
        await session.send('Emulation.setTouchEmulationEnabled', { enabled: true })
        for (let attempt = 0; attempt < 12 && (await state(page)).count < 400; attempt++) {
          const box = await page.locator('.chart-workspace').evaluate(el => {
            const rect = el.__vueParentComponent.setupState.chart.getDom('candle_pane', 'main').getBoundingClientRect()
            return { x: rect.x, y: rect.y, width: rect.width }
          })
          const y = box.y + 45
          await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: box.x + 25, y }] })
          for (let step = 1; step <= 12; step++) await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: box.x + 25 + (box.width - 50) * step / 12, y }] })
          await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
          await sleep(180)
        }
        assert.ok((await state(page)).count >= 400)
        await session.send('Emulation.setTouchEmulationEnabled', { enabled: false }); await session.detach()
        record('mobile touch swipes automatically load history', (await state(page)).count)
      }
      mode = 'fallback'; await page.reload(); await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
      await dragOlder(page, 400); s = await state(page)
      assert.ok(s.count >= 400 && limits.includes(400))
      record('missing history route falls back to expanded existing endpoint', { count: s.count, limits })
      await dragOlder(page, 1000)
      // Move to the edge again to verify the finite fallback limit is disclosed.
      await dragOlder(page, 1001); s = await state(page)
      assert.equal(s.count, 1000); assert.ok(s.limited); assert.equal(new Set(s.times).size, 1000)
      record('fallback limit disclosed, no fake or duplicate history', { count: s.count, limited: s.limited })
      mode = 'transient'; await page.reload(); await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count === 200)
      await dragOlder(page, 400); assert.ok((await state(page)).error)
      mode = 'normal'
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent?.setupState?.count >= 400, { timeout: 12000 })
      record('temporary history failure recovers automatically without clicking retry', (await state(page)).count)
      assert.deepEqual(errors, [])
      record('no uncaught browser errors', errors)
      await page.close()
    }
  } finally { fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(report, null, 2)); await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
