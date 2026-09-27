// PC/mobile Vite servers: 5217 / 5218. All API/WebSocket traffic is mocked; no real orders.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const output = path.resolve(__dirname, '../reports/i18n-browser')
const languages = ['zh-TW', 'en', 'fr', 'de', 'ru', 'es', 'pt', 'it', 'ar', 'tr', 'id', 'my', 'hi', 'cs', 'pl', 'ja', 'ko', 'th', 'vi']
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const results = []
  try {
    for (const app of ['pc', 'mobile']) {
      const port = app === 'pc' ? 5217 : 5218
      const page = await browser.newPage({ viewport: app === 'pc' ? { width: 1500, height: 1000 } : { width: 390, height: 844 }, isMobile: app === 'mobile', hasTouch: app === 'mobile' })
      const errors = [], businessWrites = []
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(() => {
        localStorage.setItem('token', 'i18n-local-test-fixture')
        localStorage.setItem('user', JSON.stringify({ id: 1, email: 'fixture@example.invalid' }))
        if (!localStorage.getItem('locale')) localStorage.setItem('locale', 'ja')
      })
      const quote = () => ({ XAUUSD: { price: 100, quoteToUsdRate: 1, conversionAvailable: true, conversionExpiresAt: Date.now() + 60000, timestamp: Date.now(), fetchedAt: Date.now(), expiresAt: Date.now() + 60000, status: 'available', sourceAvailable: true } })
      await page.routeWebSocket('**/api/ws/market', socket => socket.onMessage(raw => {
        const message = JSON.parse(raw)
        if (message.action === 'subscribe') socket.send(JSON.stringify({ type: 'price', data: quote() }))
        if (message.action === 'ping') socket.send(JSON.stringify({ type: 'pong' }))
      }))
      await page.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url())
        if (!url.pathname.startsWith('/api/')) return url.hostname === '127.0.0.1' ? route.continue() : route.abort()
        if (request.method() !== 'GET' && !url.pathname.endsWith('/heartbeat') && !url.pathname.endsWith('/price/batch')) businessWrites.push(url.pathname)
        let body = { success: true, list: [], data: [] }
        if (/\/market\/(all|symbols|search)$/.test(url.pathname)) body = { list: [{ symbol: 'XAUUSD', alltickSymbol: 'XAUUSD', category: 'Metal', quoteCurrency: 'USD', lotSize: 1000, feeMultiplier: 30, maxLeverage: 100, leverageEnabled: true, pricePrecision: 2 }] }
        else if (url.pathname.endsWith('/categories')) body = { list: [{ key: 'Metal', label: 'Metal' }, { key: 'Forex', label: 'Forex' }, { key: 'US', label: 'US' }] }
        else if (url.pathname.endsWith('/price/batch')) body = { ret: 200, data: quote() }
        else if (url.pathname.endsWith('/user/assets')) body = { success: true, contractBalance: 10000, fundBalance: 10000, optionBalance: 10000 }
        else if (url.pathname.endsWith('/balance')) body = { success: true, balance: 10000, available: 10000 }
        else if (url.pathname.endsWith('/durations')) body = [{ duration: 60, label: '60秒', profitRate: .8, lossRate: 1, minAmount: 1, maxAmount: 10000 }]
        else if (url.pathname.endsWith('/timezone')) body = { timezone: 'UTC' }
        else if (url.pathname.endsWith('/currencies')) body = { rates: {} }
        else if (url.pathname.includes('/kline/') && !url.pathname.endsWith('/batch')) {
          const count = Number(url.searchParams.get('limit')) || 200, end = Math.floor(Date.now() / 300000) * 300000
          body = { ret: 200, data: { status: 'available', kline_list: Array.from({ length: count }, (_, i) => ({ timestamp: end - (count - i - 1) * 300000, open_price: 100, close: 100, close_price: 100, high_price: 101, low_price: 99, volume: 10 })) } }
        }
        await route.fulfill({ json: body })
      })
      const base = `http://127.0.0.1:${port}`
      await page.goto(base + (app === 'pc' ? '/' : '/trade?symbol=XAUUSD&category=Metal&tab=contract'))
      await page.locator('.chart-workspace').waitFor()
      await page.waitForFunction(() => document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.chart)
      const switched = []
      for (const language of languages) {
        await page.evaluate(language => document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('locale').setLocale(language), language)
        await page.waitForFunction(language => document.documentElement.lang === language && document.querySelector('.chart-workspace').__vueParentComponent.setupState.chart.getLocale() === language, language)
        assert.equal(await page.locator('html').getAttribute('dir'), language === 'ar' ? 'rtl' : 'ltr')
        switched.push(language)
      }
      await page.evaluate(() => document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('locale').setLocale('ja'))
      if (app === 'pc') {
        await page.getByRole('combobox', { name: '言語', exact: true }).click()
        await page.getByRole('option', { name: 'English', exact: true }).click()
        await page.waitForFunction(() => document.documentElement.lang === 'en')
        await page.getByRole('combobox', { name: 'Language', exact: true }).click()
        await page.getByRole('option', { name: '日本語', exact: true }).click()
      }
      await page.reload()
      await page.waitForFunction(() => document.documentElement.lang === 'ja' && document.querySelector('.chart-workspace')?.__vueParentComponent.setupState.chart?.getLocale() === 'ja')
      assert.equal(await page.locator('vite-error-overlay').count(), 0)
      assert.equal(await page.locator('.chart-workspace').getAttribute('aria-label'), 'ローソク足チャート')
      await page.locator('.forex-transition').waitFor({ state: 'hidden' })
      await page.screenshot({ path: path.join(output, app + '-ja.png'), fullPage: true })
      await page.locator('.toolbar-indicators').click()
      assert.ok((await page.locator('.chart-dialog[open]').innerText()).includes('移動平均線'))
      await page.screenshot({ path: path.join(output, app + '-ja-indicators.png'), fullPage: true })
      await page.keyboard.press('Escape')
      if (app === 'mobile') {
        await page.goto(base + '/search')
        await page.getByPlaceholder('銘柄・通貨ペアを検索').waitFor()
        assert.ok((await page.locator('body').innerText()).includes('検索キーワードを入力してください'))
        await page.goto(base + '/loan/personal-info')
        await page.getByRole('combobox', { name: '国名または国番号で検索' }).click()
        assert.ok((await page.locator('body').innerText()).includes('アメリカ合衆国'))
        assert.ok(!(await page.locator('body').innerText()).includes('美国/加拿大'))
        await page.screenshot({ path: path.join(output, 'mobile-ja-countries.png'), fullPage: true })
        await page.keyboard.press('Escape')
        await page.goto(base + '/language')
        await page.getByText('English', { exact: true }).click()
        await page.waitForFunction(() => document.documentElement.lang === 'en')
        await page.goto(base + '/language')
        await page.getByText('日本語', { exact: true }).click()
        await page.waitForFunction(() => document.documentElement.lang === 'ja')
        await page.goto(base + '/profile')
        await page.getByText('ウォレット', { exact: true }).waitFor()
        await page.evaluate(() => {
          localStorage.setItem('locale', 'en')
          window.dispatchEvent(new StorageEvent('storage', { key: 'locale', newValue: 'en' }))
        })
        await page.getByText('Wallet', { exact: true }).waitFor()
        assert.equal(await page.getByText('ウォレット', { exact: true }).count(), 0)
      }
      assert.deepEqual(errors, [], app + ' runtime errors')
      assert.deepEqual(businessWrites, [], 'Read-only test must not submit transactions')
      results.push({ app, languages: switched, japaneseReload: true, languagePicker: true, chartLocale: true, runtimeErrors: errors, businessWrites })
      await page.close()
    }
    fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify(results, null, 2))
    console.log(JSON.stringify(results, null, 2))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
