// Run against PC/mobile dev servers on 5189/5190. All API and market traffic is mocked.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const output = path.resolve(__dirname, '../../reports/leverage-ui')

;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    for (const app of ['pc', 'mobile']) {
      const page = await browser.newPage({ hasTouch: app === 'mobile', isMobile: app === 'mobile', viewport: app === 'pc' ? { width: 1500, height: 1000 } : { width: 390, height: 844 } })
      const submitted = [], errors = []
      let maxLeverage = 100, lotSize = 1000
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(() => {
        localStorage.setItem('token', 'leverage-test-fixture')
        localStorage.setItem('user', JSON.stringify({ id: 1 }))
        localStorage.setItem('locale', 'en')
      })
      const quotes = () => ({ XAUUSD: { price: 100, timestamp: Date.now(), fetchedAt: Date.now(), expiresAt: Date.now() + 60000, status: 'available', sourceAvailable: true } })
      await page.routeWebSocket('**/api/ws/market', socket => socket.onMessage(raw => {
        const data = JSON.parse(raw)
        if (data.action === 'subscribe') socket.send(JSON.stringify({ type: 'price', data: quotes() }))
        if (data.action === 'ping') socket.send(JSON.stringify({ type: 'pong' }))
      }))
      await page.route('**/api/**', async route => {
        const url = new URL(route.request().url())
        let body = { success: true, list: [], data: [] }
        if (/\/market\/(all|symbols)$/.test(url.pathname)) body = { list: [{ symbol: 'XAUUSD', alltickSymbol: 'XAUUSD', category: 'Metal', lotSize, feeMultiplier: 0, maxLeverage, pricePrecision: 2 }] }
        else if (url.pathname.endsWith('/price/batch')) body = { ret: 200, data: quotes() }
        else if (url.pathname.endsWith('/user/assets')) body = { success: true, contractBalance: 10000 }
        else if (url.pathname.endsWith('/contract/balance')) body = { success: true, balance: 10000 }
        else if (url.pathname.endsWith('/timezone')) body = { timezone: 'UTC' }
        else if (url.pathname.endsWith('/contract/order')) { submitted.push(route.request().postDataJSON()); body = { success: true, orderId: submitted.length } }
        else if (url.pathname.includes('/kline/') && !url.pathname.endsWith('/batch')) {
          const count = Number(url.searchParams.get('limit')) || 200
          const end = Math.floor(Date.now() / 300000) * 300000
          body = { ret: 200, data: { status: 'available', kline_list: Array.from({ length: count }, (_, i) => ({ timestamp: end - (count - i - 1) * 300000, open_price: 100, close: 100, close_price: 100, high_price: 101, low_price: 99, volume: 10 })) } }
        }
        await route.fulfill({ json: body })
      })
      const url = app === 'pc' ? 'http://127.0.0.1:5189/' : 'http://127.0.0.1:5190/#/trade?symbol=XAUUSD&category=Metal&tab=contract'
      for (const type of ['MARKET', 'LIMIT']) {
        if (type === 'MARKET') await page.goto(url)
        else await page.reload()
        const select = page.locator('#contract-leverage')
        await select.waitFor()
        assert.equal(await select.inputValue(), '100')
        const margin = page.getByText(app === 'pc' ? 'Est. Margin' : 'Estimated Margin', { exact: true }).locator('..')
        await page.waitForFunction(() => [...document.querySelectorAll('span')].some(el => el.textContent === '10.00'))
        assert.match(await margin.innerText(), /10\.00/)
        assert.match(await page.getByText(app === 'pc' ? 'Est. Fee' : 'Estimated Fee', { exact: true }).locator('..').innerText(), /0\.00/)
        await select.scrollIntoViewIfNeeded()
        const box = await select.boundingBox()
        const from = { x: box.x + box.width - 8, y: box.y + box.height / 2 }
        const to = { x: box.x + 8 + (box.width - 16) * 36 / 99, y: from.y }
        if (app === 'mobile') {
          const session = await page.context().newCDPSession(page)
          await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [from] })
          await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [to] })
          await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
          await session.detach()
        } else {
          await page.mouse.move(from.x, from.y)
          await page.mouse.down()
          await page.mouse.move(to.x, to.y, { steps: 10 })
          await page.mouse.up()
        }
        const dragged = Number(await select.inputValue())
        assert.ok(Math.abs(dragged - 37) <= 1, `drag selected ${dragged}x`)
        assert.ok((await margin.innerText()).includes((1000 / dragged).toFixed(2)))
        assert.equal(await page.locator('output[for="contract-leverage"]').innerText(), `${dragged}×`)
        await select.press('Home'); assert.equal(await select.inputValue(), '1')
        await select.press('ArrowRight'); assert.equal(await select.inputValue(), '2')
        await select.press('End'); assert.equal(await select.inputValue(), '100')
        await page.locator('.leverage-selector').getByRole('button', { name: '20×', exact: true }).click()
        assert.match(await margin.innerText(), /50\.00/)
        if (type === 'LIMIT') {
          if (app === 'pc') {
            await page.locator('aside .custom-select').click()
            await page.getByRole('option', { name: 'Limit', exact: true }).click()
            await page.locator('aside .mb-5').filter({ has: page.getByText('Order Price', { exact: true }) }).getByRole('spinbutton').fill('90')
          } else {
            await page.getByRole('button', { name: 'Limit Order', exact: true }).click()
            await page.locator('input.order-price-input').fill('90')
          }
          assert.match(await margin.innerText(), /45\.00/)
        }
        await select.scrollIntoViewIfNeeded()
        await page.screenshot({ path: path.join(output, `${app}-${type.toLowerCase()}.png`), fullPage: true })
        const side = type === 'MARKET' ? 'BUY' : 'SELL'
        await Promise.all([
          page.waitForResponse(response => response.url().endsWith('/api/trade/contract/order')),
          page.getByRole('button', { name: side === 'BUY' ? 'Buy' : 'Sell', exact: true }).click(),
        ])
        assert.equal(submitted.at(-1).leverage, 20)
        assert.equal(submitted.at(-1).side, side)
        assert.equal(submitted.at(-1).type, type)
        assert.equal(submitted.at(-1).quantity, 0.01)
        if (type === 'LIMIT') assert.equal(submitted.at(-1).price, 90)
      }
      maxLeverage = 20; lotSize = 2000
      await page.reload()
      await page.waitForFunction(() => document.querySelector('#contract-leverage')?.value === '20')
      assert.equal(await page.locator('#contract-leverage').getAttribute('max'), '20')
      await page.locator('#contract-leverage').press('End'); assert.equal(await page.locator('#contract-leverage').inputValue(), '20')
      assert.ok((await page.locator('.leverage-selector button').allTextContents()).every(value => Number(value.replace('×', '')) <= 20))
      const margin = page.getByText(app === 'pc' ? 'Est. Margin' : 'Estimated Margin', { exact: true }).locator('..')
      assert.match(await margin.innerText(), /100\.00/)
      assert.deepEqual(errors, [])
      console.log(`${app}: default 100x, mouse/touch drag, keyboard, shortcuts, cap, live margin and order payloads passed`)
      await page.close()
    }
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
