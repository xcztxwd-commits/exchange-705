const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage()
    let requests = 0
    await page.addInitScript(() => {
      localStorage.setItem('token', 'currency-test')
      localStorage.setItem('user', JSON.stringify({ id: 1 }))
      localStorage.setItem('locale', 'en')
    })
    await page.route('**/api/**', route => {
      const path = new URL(route.request().url()).pathname
      if (path === '/api/market/currencies') {
        requests++
        return route.fulfill(requests !== 2 ? { status: 503, json: { message: 'temporary outage' } }
          : { json: { rates: { EUR: { quoteToUsdRate: 1.2, conversionAvailable: true, conversionExpiresAt: Date.now() + 3600000 } } } })
      }
      return route.fulfill({ json: path === '/api/user/assets' ? { fundBalance: 120 } : {} })
    })
    await page.goto('http://127.0.0.1:5189/assets')
    await page.getByRole('combobox', { name: /currency/i }).first().click()
    await page.getByRole('option', { name: /EUR/i }).click()
    await page.getByText(/EUR\s*100\.00/).first().waitFor()
    assert.ok(requests >= 2, 'switching after initial failure must retry immediately')
    await page.evaluate(() => { const original = Date.now; Date.now = () => original() + 31000 })
    await page.waitForTimeout(1300)
    assert.ok(requests >= 3, 'periodic refresh must run')
    assert.ok(await page.getByText(/EUR\s*100\.00/).first().isVisible(), 'failed refresh must retain valid rate')
    assert.equal(await page.getByRole('alert').count(), 0)
    console.log('PASS: switch retries initial failure; later refresh failure retains valid rate')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exit(1) })
