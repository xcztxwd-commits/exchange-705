// Run against PC/mobile dev servers on 5187/5188. All API and market traffic is mocked.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const output = path.join(require('node:os').tmpdir(), '705-order-sizing-qa')

;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    for (const app of ['pc', 'mobile']) {
      const page = await browser.newPage({ hasTouch: app === 'mobile', isMobile: app === 'mobile', viewport: app === 'pc' ? { width: 1500, height: 1000 } : { width: 390, height: 844 } })
      const submitted = [], errors = [], consoleErrors = []
      let maxLeverage = 100, lotSize = 1000, leverageEnabled = true, currency = 'USD', rate = 1, conversionAvailable = true, available = 10000, feeMultiplier = 30, positions = [], accountFails = false
      page.on('pageerror', error => errors.push(error.message))
      page.on('console', message => { if (message.type() === 'error') consoleErrors.push(message.text()) })
      await page.addInitScript(() => {
        localStorage.setItem('token', 'leverage-test-fixture')
        localStorage.setItem('user', JSON.stringify({ id: 1 }))
        if (!localStorage.getItem('locale')) localStorage.setItem('locale', 'en')
      })
      const quotes = () => ({ XAUUSD: { price: 100, quoteToUsdRate: rate, conversionAvailable, conversionExpiresAt: Date.now() + 15000, timestamp: Date.now(), fetchedAt: Date.now(), expiresAt: Date.now() + 60000, status: 'available', sourceAvailable: true } })
      await page.routeWebSocket('**/api/ws/market', socket => socket.onMessage(raw => {
        const data = JSON.parse(raw)
        if (data.action === 'subscribe') socket.send(JSON.stringify({ type: 'price', data: quotes() }))
        if (data.action === 'ping') socket.send(JSON.stringify({ type: 'pong' }))
      }))
      await page.route('**/api/**', async route => {
        const url = new URL(route.request().url())
        let body = { success: true, list: [], data: [] }
        if (/\/market\/(all|symbols)$/.test(url.pathname)) body = { list: [{ symbol: 'XAUUSD', alltickSymbol: 'XAUUSD', category: 'Metal', leverageEnabled, quoteCurrency: currency, lotSize, feeMultiplier, maxLeverage, pricePrecision: 2 }] }
        else if (url.pathname.endsWith('/price/batch')) body = { ret: 200, data: quotes() }
        else if (url.pathname.endsWith('/user/assets')) body = { success: true, contractBalance: available }
        else if (url.pathname.endsWith('/contract/balance')) body = { success: true, balance: available }
        else if (url.pathname.endsWith('/contract/orders')) body = accountFails ? { success: false } : { list: positions }
        else if (url.pathname.endsWith('/timezone')) body = { timezone: 'UTC' }
        else if (url.pathname.endsWith('/contract/order')) { submitted.push(route.request().postDataJSON()); body = { success: true, orderId: submitted.length } }
        else if (url.pathname.includes('/kline/') && !url.pathname.endsWith('/batch')) {
          const count = Number(url.searchParams.get('limit')) || 200
          const end = Math.floor(Date.now() / 300000) * 300000
          body = { ret: 200, data: { status: 'available', kline_list: Array.from({ length: count }, (_, i) => ({ timestamp: end - (count - i - 1) * 300000, open_price: 100, close: 100, close_price: 100, high_price: 101, low_price: 99, volume: 10 })) } }
        }
        await route.fulfill({ json: body })
      })
      const url = app === 'pc' ? 'http://127.0.0.1:5187/' : 'http://127.0.0.1:5188/#/trade?symbol=XAUUSD&category=Metal&tab=contract'
      const leverage = page.locator('.leverage-trigger')
      const dialog = page.locator('.leverage-dialog')
      const slider = page.locator('#position-allocation')
      const buy = page.getByRole('button', { name: 'Buy', exact: true })
      const sizing = page.locator('.position-sizing')
      const quantity = app === 'pc' ? page.locator('aside .pt-2 input') : page.locator('input.order-input[step="0.01"]')
      const margin = page.getByText(app === 'pc' ? 'Est. Margin' : 'Estimated Margin', { exact: true }).locator('..')
      const liquidationBuy = page.getByTestId('liquidation-buy')
      async function waitReady() {
        await page.waitForFunction(() => document.querySelector('#position-allocation') && !document.querySelector('#position-allocation').disabled)
      }
      async function setLeverage(value) {
        await leverage.click()
        await dialog.getByRole('button', { name: `${value}×`, exact: true }).click()
        await dialog.getByRole('button', { name: /Confirm/i }).click()
        await page.waitForFunction(value => document.querySelector('.leverage-trigger')?.textContent.includes(`${value}×`), value)
      }
      for (const type of ['MARKET', 'LIMIT']) {
        await page.goto(url)
        if (type === 'LIMIT') await page.reload()
        await waitReady()
        assert.ok(await page.title())
        assert.equal(await page.locator('vite-error-overlay').count(), 0)
        assert.match(await leverage.innerText(), /100×/)
        assert.equal(await page.locator('input[type="range"]').count(), 1, 'only allocation uses a slider')
        assert.match(await margin.innerText(), /10\.00/)
        await leverage.click()
        await dialog.locator('input').fill('20')
        await dialog.locator('button.cancel').click()
        assert.match(await leverage.innerText(), /100×/, 'cancel preserves leverage')
        assert.equal(await leverage.evaluate(el => document.activeElement === el), true, 'dialog restores focus')
        await leverage.click()
        await dialog.locator('input').fill('101')
        assert.equal(await dialog.getByRole('button', { name: /Confirm/i }).isDisabled(), true)
        await page.keyboard.press('Escape')
        await setLeverage(20)
        assert.match(await margin.innerText(), /50\.00/)
        // Real pointer/touch dragging, followed by keyboard and exact preset checks.
        await slider.scrollIntoViewIfNeeded()
        const box = await slider.boundingBox()
        const from = { x: box.x + 10, y: box.y + box.height / 2 }
        const to = { x: box.x + box.width / 2, y: from.y }
        if (app === 'mobile') {
          const session = await page.context().newCDPSession(page)
          await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [from] })
          await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [to] })
          await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
          await session.detach()
        } else {
          await page.mouse.move(from.x, from.y); await page.mouse.down()
          await page.mouse.move(to.x, to.y, { steps: 10 }); await page.mouse.up()
        }
        assert.ok(Math.abs(Number(await slider.inputValue()) - 50) <= 1)
        await sizing.getByRole('button', { name: '50%', exact: true }).click()
        assert.equal(Number(await quantity.inputValue()), 0.99)
        assert.match(await margin.innerText(), /4,?950\.00/)
        const atHalf = await liquidationBuy.innerText()
        assert.notEqual(atHalf, '—')
        await sizing.getByRole('button', { name: '100%', exact: true }).click()
        assert.equal(Number(await quantity.inputValue()), 1.98)
        assert.notEqual(await liquidationBuy.innerText(), atHalf)
        assert.equal(await buy.isDisabled(), false)
        await slider.press('Home')
        assert.equal(Number(await quantity.inputValue()), 0)
        assert.equal(await liquidationBuy.innerText(), '—')
        assert.equal(await buy.isDisabled(), true)
        await slider.press('ArrowRight')
        assert.equal(await slider.inputValue(), '1')
        await sizing.getByRole('button', { name: '50%', exact: true }).click()
        await setLeverage(50)
        assert.equal(Number(await quantity.inputValue()), 2.46, 'ratio survives leverage changes')
        await quantity.fill('0.02'); await quantity.press('Tab')
        await setLeverage(20)
        assert.equal(Number(await quantity.inputValue()), 0.02, 'manual quantity survives leverage changes')
        assert.ok(Number(await slider.inputValue()) <= 2)
        if (type === 'LIMIT') {
          if (app === 'pc') {
            await page.locator('aside .custom-select').click()
            await page.getByRole('option', { name: 'Limit', exact: true }).click()
            await page.locator('aside .mb-5').filter({ has: page.getByText('Order Price', { exact: true }) }).getByRole('spinbutton').fill('90')
          } else {
            await page.getByRole('button', { name: 'Limit Order', exact: true }).click()
            await page.locator('input.order-price-input').fill('90')
          }
          assert.match(await margin.innerText(), /90\.00/)
        }
        await slider.scrollIntoViewIfNeeded()
        await page.screenshot({ path: path.join(output, `${app}-${type.toLowerCase()}.png`), fullPage: true })
        const side = type === 'MARKET' ? 'BUY' : 'SELL'
        await Promise.all([
          page.waitForResponse(response => response.url().endsWith('/api/trade/contract/order')),
          page.getByRole('button', { name: side === 'BUY' ? 'Buy' : 'Sell', exact: true }).click(),
        ])
        assert.equal(submitted.at(-1).leverage, 20)
        assert.equal(submitted.at(-1).side, side)
        assert.equal(submitted.at(-1).type, type)
        assert.equal(submitted.at(-1).quantity, 0.02)
        if (type === 'LIMIT') assert.equal(submitted.at(-1).price, 90)
      }
      maxLeverage = 20; lotSize = 2000
      await page.reload(); await waitReady()
      assert.match(await leverage.innerText(), /20×/)
      await leverage.click()
      assert.equal(await dialog.locator('input').getAttribute('max'), '20')
      assert.ok((await dialog.locator('.leverage-presets button').allTextContents()).every(value => Number(value.replace('×', '')) <= 20))
      await page.keyboard.press('Escape')
      assert.match(await margin.innerText(), /100\.00/)
      leverageEnabled = false; await page.reload(); await waitReady()
      assert.match(await leverage.innerText(), /1×/)
      assert.equal(await leverage.isDisabled(), true)
      leverageEnabled = true
      currency = 'BTC'; rate = 2; await page.reload(); await waitReady()
      assert.match(await margin.innerText(), /200\.00/)
      conversionAvailable = false; await page.reload(); await slider.waitFor()
      await page.waitForFunction(() => document.querySelector('.leverage-trigger')?.textContent.includes('20×'))
      assert.equal(await slider.isDisabled(), true)
      assert.equal(await buy.isDisabled(), true)
      assert.equal(await liquidationBuy.innerText(), '—')
      conversionAvailable = true; currency = 'USD'; rate = 1; lotSize = 1000; maxLeverage = 100
      await page.reload(); await waitReady()
      await page.evaluate(() => { const original = Date.now; Date.now = () => original() + 61000 })
      await page.waitForFunction(() => document.querySelector('#position-allocation')?.disabled)
      assert.equal(await buy.isDisabled(), true, 'stale quote cannot submit')
      assert.equal(await liquidationBuy.innerText(), '—')
      available = 0.1; await page.reload(); await waitReady()
      await sizing.getByRole('button', { name: '100%', exact: true }).click()
      assert.equal(Number(await quantity.inputValue()), 0, 'insufficient balance must not round up')
      assert.equal(await buy.isDisabled(), true)
      available = 10000
      positions = [{ symbol: 'XAUUSD', status: 'OPEN', side: 'BUY', quantity: 1, lotSize: 1000, leverage: 100, openPrice: 100, margin: 1000, fee: 30, quoteCurrency: 'USD' }]
      await page.reload(); await waitReady()
      await setLeverage(20)
      await sizing.getByRole('button', { name: '50%', exact: true }).click()
      await page.waitForFunction(() => document.querySelector('[data-testid="liquidation-buy"]')?.textContent !== '—')
      assert.equal(await liquidationBuy.innerText(), (100 - (11000 - 29.7) / 1990).toFixed(2))
      if (app === 'pc') {
        await page.getByRole('button', { name: 'History Records', exact: true }).click()
        assert.notEqual(await liquidationBuy.innerText(), '—', 'history view does not remove account positions from estimate')
      }
      accountFails = true; await page.reload(); await slider.waitFor()
      assert.equal(await liquidationBuy.innerText(), '—')
      assert.equal(await buy.isDisabled(), true)
      accountFails = false; positions = []
      await page.evaluate(() => localStorage.setItem('locale', 'zh-TW'))
      await page.reload(); await waitReady()
      await leverage.click()
      await dialog.getByRole('button', { name: '20×', exact: true }).click()
      await dialog.locator('button.confirm').click()
      await sizing.getByRole('button', { name: '50%', exact: true }).click()
      await slider.scrollIntoViewIfNeeded()
      if (app === 'mobile') await page.locator('.order-section').evaluate(el => el.scrollIntoView({ block: 'start' }))
      await page.screenshot({ path: path.join(output, `${app}-final.png`), fullPage: app === 'pc' })
      await leverage.click()
      await page.screenshot({ path: path.join(output, `${app}-leverage.png`), fullPage: false })
      const dialogBox = await dialog.boundingBox()
      assert.ok(dialogBox.x >= 0 && dialogBox.x + dialogBox.width <= page.viewportSize().width + 1)
      if (app === 'mobile') assert.ok(Math.abs(dialogBox.y + dialogBox.height - page.viewportSize().height) <= 1, 'mobile uses bottom sheet')
      await page.setViewportSize(app === 'pc' ? { width: 1366, height: 768 } : { width: 320, height: 740 })
      const smallBox = await dialog.boundingBox()
      assert.ok(smallBox.x >= 0 && smallBox.x + smallBox.width <= page.viewportSize().width + 1)
      assert.ok(smallBox.y >= 0 && smallBox.y + smallBox.height <= page.viewportSize().height + 1)
      if (app === 'pc') {
        await page.evaluate(() => document.documentElement.classList.add('dark'))
        assert.equal(await dialog.evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(24, 28, 39)')
        assert.equal(await dialog.locator('[aria-pressed="true"]').evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(45, 62, 34)')
        await page.screenshot({ path: path.join(output, 'pc-dark-leverage.png') })
      }
      assert.deepEqual(errors, [])
      assert.deepEqual(consoleErrors, [])
      console.log(`${app}: allocation drag, presets, leverage confirm/cancel, caps, bidirectional sizing, liquidation, balance/FX failures, market/limit payloads passed`)
      await page.close()
    }
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
