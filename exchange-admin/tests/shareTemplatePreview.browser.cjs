// Start the admin Vite server on port 5194, then run this file with Node.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const root = path.resolve(__dirname, '../..')
// Only admin price formatting may differ; renderer logic and translations stay shared.
const rendererSource = file => fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n')
const withoutPriceFormatting = source => source
  .replace(/from '[^']*shareTemplateDesign\.ts'/, "from './shareTemplateDesign.ts'")
  .replace("import { formatPrice } from './formatPrice.ts'\n", '')
  .replace('// Order data stays shared with mobile and PC; admin price display uses three decimals.', '// Kept identical in mobile, PC and admin builds.')
  .replace(/^  const price = \((?:n|value): number\) => new Intl\.NumberFormat\('en-US', \{ maximumFractionDigits: 16 \}\)\.format\((?:n|value)\)\n/gm, '')
  .replace(/\bprice\(order\./g, 'formatPrice(order.')
for (const file of ['orderShare.ts', 'orderShareLocales.ts']) {
  assert.equal(withoutPriceFormatting(rendererSource(path.join(root, 'exchange-admin/src/utils', file))),
    withoutPriceFormatting(rendererSource(path.join(root, 'exchange-frontend/src/utils', file))), `${file} renderer drift`)
}
for (const file of fs.readdirSync(path.join(root, 'exchange-admin/public/share-templates'))) {
  assert.deepEqual(fs.readFileSync(path.join(root, 'exchange-admin/public/share-templates', file)),
    fs.readFileSync(path.join(root, 'exchange-frontend/public/share-templates', file)), `${file} asset drift`)
}
;(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) })
  try {
    const page = await browser.newPage({ viewport: { width: 1500, height: 1100 } })
    const errors = [], writes = []
    page.on('pageerror', e => errors.push(e.message))
    await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ mode: 'admin', token: 'QA', user: { id: 1, tenantId: 1, userType: 'admin', role: 'admin' } })))
    await page.route('**/__qa', r => r.fulfill({ contentType: 'text/html', body: '<!doctype html><body><div id="app"></div>' }))
    await page.route('**/api/**', r => {
      if (r.request().method() !== 'GET') writes.push(r.request().url())
      const url = new URL(r.request().url())
      if (url.pathname.endsWith('/admin/menus/current')) return r.fulfill({ json: { success: true, superAdmin: false, menus: [], groups: [], actions: { share_templates: [] } } })
      if (url.pathname.endsWith('/admin/tenant-policies/share-templates')) return r.fulfill({ json: { tenantId: 1, tenantName: 'QA', status: 'ACTIVE', policyVersion: 1, features: {}, configs: [], supportChannel: null } })
      assert.ok(url.pathname.endsWith('/admin/config/get'), 'Preview must not access customer APIs')
      return r.fulfill({ json: { value: 'gold,light' } })
    })
    await page.goto(`${process.env.ADMIN_URL || 'http://127.0.0.1:5194'}/__qa`)
    await page.evaluate(async () => {
      const source = await (await fetch('/src/components/ShareTemplateSettings.vue')).text()
      const auth = await (await fetch('/src/store/auth.ts')).text()
      const dep = (s, n) => s.match(new RegExp('from ["\']([^"\']*' + n + '\\.js[^"\']*)["\']'))[1]
      const { createApp } = await import(dep(source, 'vue'))
      const element = await import(dep(source, 'element-plus'))
      const { createPinia, setActivePinia } = await import(dep(auth, 'pinia'))
      const pinia = createPinia(); setActivePinia(pinia)
      const { loadAccess, permissionDirective } = await import('/src/utils/access.ts')
      await loadAccess()
      const { default: Settings } = await import('/src/components/ShareTemplateSettings.vue')
      const { default: AdminTable } = await import('/src/components/AdminTable.ts')
      const { TABLE_PREFERENCES } = await import('/src/utils/tablePreferences.ts')
      await import('/node_modules/element-plus/dist/index.css')
      const app = createApp(Settings).use(pinia).use(element.default)
      app.component('AdminTable', AdminTable); app.directive('permission', permissionDirective)
      app.provide(TABLE_PREFERENCES, { identityKey: () => 'QA', load: async () => [], save: async () => {} })
      app.mount('#app')
    })
    await page.waitForFunction(() => document.querySelectorAll('.preview-thumbnail img').length === 16)
    const thumbnails = page.locator('.preview-thumbnail')
    for (let i = 0; i < 16; i++) {
      await thumbnails.nth(i).click()
      const dialog = page.locator('.share-preview-dialog:visible')
      await dialog.waitFor()
      assert.ok((await dialog.innerText()).includes('示例数据'))
      assert.equal(await dialog.locator('.preview-stage img').evaluate(img => img.naturalWidth), 1080)
      if (i % 2) await page.keyboard.press('Escape')
      else await dialog.getByRole('button', { name: '关闭预览' }).click()
      await dialog.waitFor({ state: 'hidden' })
    }
    const before = await thumbnails.first().locator('img').getAttribute('src')
    await page.locator('.el-select').first().click()
    await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '日语' }).click()
    await page.waitForFunction(() => document.querySelectorAll('.preview-thumbnail img[alt*="ja示例图"]').length === 16)
    assert.notEqual(await thumbnails.first().locator('img').getAttribute('src'), before)
    await page.setViewportSize({ width: 390, height: 844 })
    await thumbnails.first().click()
    const dialog = page.locator('.share-preview-dialog:visible')
    await dialog.waitFor()
    const bounds = await dialog.boundingBox()
    assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 390)
    const image = await dialog.locator('.preview-stage img').boundingBox()
    assert.ok(image.width > 0 && image.x >= 0 && image.x + image.width <= 390)
    const output = path.join(require('node:os').tmpdir(), 'share-template-preview-qa')
    fs.mkdirSync(output, { recursive: true })
    await page.waitForTimeout(400) // Allow Element Plus's dialog entrance transition to finish.
    await page.screenshot({ path: path.join(output, 'admin-template-preview.png') })
    await page.mouse.click(2, 2)
    await dialog.waitFor({ state: 'hidden' })
    assert.deepEqual(errors, [])
    assert.deepEqual(writes, [])
    console.log('PASS: 16 previews, disabled templates, locale change, modal close, mobile layout, renderer/asset parity, no writes or customer data')
  } finally { await browser.close() }
})().catch(e => { console.error(e); process.exit(1) })
