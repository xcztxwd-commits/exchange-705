// Local Vite regression. All HTTP APIs and market sockets are mocked; no business writes.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const image = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aLl0AAAAASUVORK5CYII='
const output = process.env.OUTPUT_DIR || path.resolve(__dirname, '../reports/tenant-status-banner-remove-20261006')

;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    for (const app of ['mobile', 'advanced', 'pc']) {
      const desktop = app === 'pc'
      const context = await browser.newContext({ viewport: { width: desktop ? 1440 : 390, height: 900 } })
      const page = await context.newPage(), errors = []
      let state = 'ACTIVE', featureReads = 0
      const writes = []
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(() => localStorage.setItem('locale', 'zh-TW'))
      await page.routeWebSocket(/\/(?:demo-api|api)\/ws\/market/, () => {})
      await page.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url())
        if (url.hostname === 'ipwho.is') return route.fulfill({ json: { success: true, country_code: 'MY', calling_code: '60', timezone: { id: 'Asia/Kuala_Lumpur' } } })
        if (!['127.0.0.1', 'localhost'].includes(url.hostname)) return route.abort()
        if (!/^\/(?:demo-api|api)\//.test(url.pathname)) return route.continue()
        if (request.method() !== 'GET' && !['/api/auth/captcha', '/api/market/price/batch', '/api/market/home-sparkline/batch'].includes(url.pathname)) writes.push(request.method() + ' ' + url.pathname)
        if (url.pathname === '/api/tenant/features') {
          featureReads++
          return route.fulfill({ status: state === 'ERROR' ? 503 : 200, json: { tenantId: 1, tenantName: 'Default tenant', status: state,
            acceptNewBusiness: state === 'ACTIVE', features: { registration: true, simulation: true } } })
        }
        if (url.pathname === '/api/auth/register') return route.fulfill({ json: {
          phone: { enabled: true, required: false }, annualIncome: { enabled: true, required: false },
          currencies: ['USD', 'MYR'], maxAnnualIncome: '999999999999.99',
        } })
        if (url.pathname === '/api/auth/captcha') return route.fulfill({ json: { captchaId: 'a'.repeat(32), image, expiresIn: 120 } })
        return route.fulfill({ json: { success: true, list: [], data: [], categories: [], symbols: [] } })
      })
      const base = desktop ? process.env.PC_URL || 'http://127.0.0.1:5415' : process.env.MOBILE_URL || 'http://127.0.0.1:5413'
      for (state of ['ACTIVE', 'SUSPENDED', 'ERROR']) {
        await page.goto(base + '/register' + (app === 'advanced' ? '?edition=advanced' : ''))
        const root = desktop ? page.locator('.el-dialog').filter({ has: page.locator('#pc-register-form') }) : page.locator('.auth-card')
        await root.locator('input[type=email]').waitFor()
        await page.waitForFunction(async expected => {
          const features = await import('/src/utils/tenantFeatures.ts')
          return !features.featuresLoading.value && (expected === 'ERROR' ? features.featuresError.value : features.tenantFeatures.value?.status === expected)
        }, state)
        assert.equal(await page.locator('.tenant-status').count(), 0, app + ' tenant banner removed')
        assert.equal(await page.locator('body').innerText().then(text => text.includes('Default tenant')), false)
        const allowed = await page.evaluate(async () => (await import('/src/utils/tenantFeatures.ts')).canStartBusiness('registration'))
        assert.equal(allowed, state === 'ACTIVE', 'capability enforcement survives banner removal')
        const entry = desktop ? page.locator('header').getByRole('button', { name: '註冊', exact: true }) : root.locator('.primary-btn,.primary')
        assert.equal(await entry.count(), state === 'ACTIVE' ? 1 : 0, 'restricted registration entry stays hidden')
        await page.locator('.forex-transition').waitFor({ state: 'hidden' })
        if (state === 'ACTIVE') await page.screenshot({ path: path.join(output, app + '-no-banner.png') })
      }
      await page.goto(base + '/login')
      await page.locator('input[type=password]').first().waitFor()
      assert.equal(await page.locator('.tenant-status').count(), 0, 'login also has no tenant banner')
      await page.goto(base + (desktop ? '/' : '/home'))
      await page.locator(desktop ? '.trade-page' : '.home').waitFor()
      assert.equal(await page.locator('.tenant-status').count(), 0, 'home also has no tenant banner')
      assert.ok(featureReads >= 3, 'root feature refresh still runs')
      assert.deepEqual(writes, [])
      assert.deepEqual(errors, [])
      console.log('PASS ' + app + ': no tenant banner in home/registration/login, ACTIVE/SUSPENDED/offline capabilities unchanged, no business writes or runtime errors')
      await context.close()
    }
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
