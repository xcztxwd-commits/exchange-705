// Run against isolated mobile/PC Vite servers; every API response is mocked.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const ports = (process.env.SHARE_QA_PORTS || '5193,5195').split(',').map(Number)
const out = process.env.SHARE_QA_OUTPUT
if (!out) throw Error('Set SHARE_QA_OUTPUT to an evidence directory outside the repository')
fs.mkdirSync(out, { recursive: true })
const raw = { id: 3391, symbol: 'USDJPY', status: 'CLOSED', side: 'SELL', profit: 21.82, openPrice: 158.225, closePrice: 158.164, leverage: 100, margin: 649.15, quantity: 1, fee: 0, openTime: '2026-10-08T15:20:00Z', closeTime: '2026-10-08T15:22:13Z' }
;(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) })
  const checks = []
  try {
    for (const [index, port] of ports.entries()) for (const mode of ['REAL', 'DEMO']) {
      const base = 'http://127.0.0.1:' + port, app = index === 0 ? 'mobile' : 'pc'
      const page = await browser.newPage({ viewport: { width: index === 0 ? 390 : 1440, height: 900 } })
      await page.context().grantPermissions(['local-network-access'], { origin: base })
      const errors = [], requests = []
      let brand = ' QA Tenant Exchange ', design
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(mode => {
        localStorage.setItem('token', 'QA'); localStorage.setItem('user', JSON.stringify({ id: 7, tenantId: 1 }))
        sessionStorage.setItem('account-mode:1:7', mode)
        const texts = new WeakMap(), draw = CanvasRenderingContext2D.prototype.fillText, toBlob = HTMLCanvasElement.prototype.toBlob
        window.__posterExports = []
        CanvasRenderingContext2D.prototype.fillText = function (text, ...args) { const list = texts.get(this.canvas) || []; list.push(String(text)); texts.set(this.canvas, list); return draw.call(this, text, ...args) }
        HTMLCanvasElement.prototype.toBlob = function (callback, ...args) { window.__posterExports.push({ texts: texts.get(this) || [], width: this.width, height: this.height }); return toBlob.call(this, callback, ...args) }
      }, mode)
      await page.route('**/__qa', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><html><head><meta charset="utf-8"><title>Share brand QA</title></head><body style="margin:0"><div id="app"></div></body></html>' }))
      await page.route('**/*api/**', route => {
        const request = route.request(), url = new URL(request.url())
        assert.equal(request.method(), 'GET', 'Sharing must not modify account data')
        requests.push({ path: url.pathname, mode: request.headers()['x-account-mode'] })
        let data = {}
        if (url.pathname.endsWith('/user/share-templates')) {
          assert.equal(url.pathname, '/api/user/share-templates', 'Brand and template metadata use the tenant real gateway even for demo orders')
          assert.equal(request.headers()['x-account-mode'], 'REAL'); assert.equal(url.searchParams.get('details'), 'true')
          data = { brand, templates: ['architecture', 'light', 'custom-qa'], focus: 'amount', definitions: [{ id: 'custom-qa', name: 'QA Custom', base: 'light', enabled: true, focus: 'amount', languages: ['*'], design }] }
        } else if (url.pathname.endsWith('/trade/contract/orders')) {
          assert.equal(url.pathname, (mode === 'DEMO' ? '/demo-api' : '/api') + '/trade/contract/orders')
          assert.equal(request.headers()['x-account-mode'], mode); data = { list: [raw] }
        } else if (url.pathname.endsWith('/timezone')) data = { timezone: 'UTC' }
        return route.fulfill({ json: data })
      })
      await page.goto(base + '/__qa'); assert.equal(await page.title(), 'Share brand QA')
      design = await page.evaluate(async () => {
        const source = await (await fetch('/src/components/OrderShareModal.vue')).text()
        const module = source.match(/from ["']([^"']*shareTemplateDesign\.ts[^"']*)["']/)[1]
        return (await import(module)).createShareDesign('light')
      })
      await page.evaluate(async desktop => {
        const source = await (await fetch('/src/store/locale.ts')).text()
        const dependency = name => source.match(new RegExp('from ["\']([^"\']*' + name + '\\.js[^"\']*)["\']'))[1]
        const { createApp, h } = await import(dependency('vue')), { createPinia, setActivePinia } = await import(dependency('pinia'))
        const { useLocaleStore } = await import('/src/store/locale.ts'), { default: Modal } = await import('/src/components/OrderShareModal.vue')
        const pinia = createPinia(); setActivePinia(pinia); const locale = useLocaleStore(); locale.setLocale('en')
        window.__qa = { locale, app: createApp({ render: () => h(Modal, { orderId: 3391, kind: 'contract', desktop }) }).use(pinia) }
        window.__qa.app.mount('#app')
      }, index !== 0)
      const ready = async () => { await page.locator('.poster-preview').waitFor(); await page.waitForFunction(() => !document.querySelector('.pnl-save')?.disabled) }
      const poster = () => page.evaluate(() => window.__posterExports.at(-1))
      const checkBrand = async expected => {
        await ready(); const value = await poster()
        assert.ok(value.texts.includes(expected), app + '/' + mode + ' renders ' + expected)
        assert.ok(!value.texts.some(text => ['DEMO', 'FOREX', 'GTCFX'].includes(text)))
        assert.ok(!value.texts.some(text => /SIMULATION|VIRTUAL FUNDS|DEMO|模拟账户/.test(text)), 'Sharing never adds account-mode watermarks')
      }
      await checkBrand('QA Tenant Exchange')
      await page.screenshot({ path: path.join(out, app + '-' + mode + '-brand.png') })
      await page.locator('.pnl-template').nth(2).click(); await checkBrand('QA Tenant Exchange')
      const downloading = page.waitForEvent('download'); await page.locator('.pnl-save').click()
      const download = await downloading, file = path.join(out, app + '-' + mode + '.png'); await download.saveAs(file)
      const png = fs.readFileSync(file); assert.equal(png.subarray(1, 4).toString(), 'PNG'); assert.equal(png.readUInt32BE(16), 1080); assert.equal(png.readUInt32BE(20), 1440)
      if (mode === 'DEMO') assert.deepEqual(png, fs.readFileSync(path.join(out, app + '-REAL.png')), 'Identical real/demo orders export identical images')
      brand = ' Renamed Tenant Exchange '; await page.evaluate(() => window.__qa.locale.setLocale('ja')); await checkBrand('Renamed Tenant Exchange')
      assert.ok(!(await poster()).texts.includes('QA Tenant Exchange'))
      brand = '   '; await page.evaluate(() => window.__qa.locale.setLocale('zh-TW')); await checkBrand('EXCHANGE')
      brand = undefined; await page.evaluate(() => window.__qa.locale.setLocale('fr')); await checkBrand('EXCHANGE')
      assert.equal(await page.locator('vite-error-overlay').count(), 0); assert.deepEqual(errors, [])
      checks.push({ app, mode, builtInAndCustomBrand: true, brandRefresh: true, missingBrandFallback: true, pngExport: true, noWatermark: true, requests, errors })
      await page.close()
    }
    fs.writeFileSync(path.join(out, 'brand-results.json'), JSON.stringify(checks, null, 2))
    console.log('PASS: mobile/PC real/demo branding, real-gateway metadata, custom/built-in templates, changed/empty/missing brand, no watermarks and PNG export')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
