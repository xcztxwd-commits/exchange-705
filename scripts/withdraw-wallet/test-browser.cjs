// Isolated withdrawal acceptance. All API calls use fixture balances, never a real backend.
// Run with PLAYWRIGHT_PATH pointing to the existing Playwright package when it is not on NODE_PATH.
const fs = require('node:fs'), path = require('node:path'), os = require('node:os'), assert = require('node:assert/strict')
const { pathToFileURL } = require('node:url')
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const repository = path.resolve(__dirname, '../..'), report = process.env.EVIDENCE_DIR || fs.mkdtempSync(path.join(os.tmpdir(), 'withdraw-wallet-'))
const languages = ['zh-TW', 'en', 'fr', 'de', 'ru', 'es', 'pt', 'it', 'ar', 'tr', 'id', 'my', 'hi', 'cs', 'pl', 'ja', 'ko', 'th', 'vi']
fs.mkdirSync(report, { recursive: true })

async function check(browser, project, view, name) {
  const root = path.join(repository, project)
  const { createServer } = await import(pathToFileURL(path.join(root, 'node_modules/vite/dist/node/index.js')))
  const vue = (await import(pathToFileURL(path.join(root, 'node_modules/@vitejs/plugin-vue/dist/index.mjs')))).default
  const virtual = root.replaceAll('\\', '/') + '/__withdraw-fixture.js'
  const entry = `import {createApp} from 'vue';import {createPinia} from 'pinia';import {createRouter,createMemoryHistory} from 'vue-router';import App from '${view}';import {useAuthStore} from '@/store/auth';import {useLocaleStore} from '@/store/locale';import '@/styles/global.scss';const app=createApp(App);app.use(createPinia());useAuthStore().setAuth('fixture-only',{id:999,tenantId:1});const locale=useLocaleStore();locale.setLocale('zh-TW');window.fixture={locale};const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});app.use(router);await router.replace('/withdraw');app.mount('#app');`
  const server = await createServer({ root, configFile: false, envDir: report, cacheDir: path.join(report, 'cache-' + name),
    plugins: [{ name: 'withdraw-fixture', resolveId(id) { if (id === 'withdraw-fixture') return virtual }, load(id) { if (id === virtual) return entry },
      configureServer(s) { s.middlewares.use('/__withdraw_test', (_req, res) => { res.setHeader('Content-Type', 'text/html'); res.end('<html><body><div id="app"></div><script type="module" src="/@id/withdraw-fixture"></script></body></html>') }) } }, vue()],
    resolve: { dedupe: ['vue', 'vue-router', 'pinia'], alias: { '@': path.join(root, 'src') } }, server: { host: '127.0.0.1', port: 0 } })
  await server.listen()
  const port = server.httpServer.address().port
  const page = await browser.newPage({ viewport: { width: name === 'pc' ? 1000 : 390, height: 844 } })
  const errors = [], transfers = [], withdrawals = [], receipts = new Map()
  const balances = { FUND: 120.5, CONTRACT: 80.25, OPTION: 30 }
  let loseResponse = false, failAssets = false
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.hostname !== '127.0.0.1' || url.port !== String(port)) return route.abort('blockedbyclient')
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const json = body => route.fulfill({ json: body })
    switch (url.pathname) {
      case '/api/market/currencies': return json({ rates: {} })
      case '/api/user/assets': return failAssets ? route.fulfill({ status: 503, json: { message: 'fixture unavailable' } }) : json({ success: true, fundBalance: balances.FUND, contractBalance: balances.CONTRACT, optionBalance: balances.OPTION })
      case '/api/wallet/bank-cards': return json({ success: true, list: [{ id: 1, currency: 'USD', bankName: 'Fixture Bank', recipientAccount: '585958', recipientName: 'Fixture' }] })
      case '/api/wallet/digital-addresses': return json({ success: true, list: [{ id: 1, network: 'USDT-TRC20', address: 'FIXTURE-ADDRESS' }] })
      case '/api/withdraw/records': return json({ success: true, list: [] })
      case '/api/withdraw/calculate': return json({ success: true, fee: 0, actualAmount: route.request().postDataJSON().amount })
      case '/api/withdraw/submit': {
        const body = route.request().postDataJSON(); withdrawals.push(body)
        const wallet = body.accountType || 'FUND'
        assert.ok(Object.hasOwn(balances, wallet)); assert.ok(body.amount > 0 && body.amount <= balances[wallet])
        balances[wallet] -= body.amount
        return json({ success: true })
      }
      case '/api/transfer/submit': {
        const body = route.request().postDataJSON(); transfers.push(body)
        assert.notEqual(body.fromAccount, body.toAccount); assert.ok(body.requestId)
        if (!receipts.has(body.requestId)) {
          assert.ok(body.amount > 0 && body.amount <= balances[body.fromAccount])
          balances[body.fromAccount] -= body.amount; balances[body.toAccount] += body.amount; receipts.set(body.requestId, body)
        }
        if (loseResponse) { loseResponse = false; return route.abort('failed') }
        return json({ success: true })
      }
      default: return route.fulfill({ status: 404, json: { message: 'unimplemented fixture' } })
    }
  })
  try {
    await page.goto(`http://127.0.0.1:${port}/__withdraw_test`)
    const wallet = page.locator('.withdraw-wallet'), dialog = page.locator('.transfer-dialog')
    const quick = wallet.locator('.quick-transfer'), walletSelect = wallet.locator('.app-select__trigger')
    const summarySelector = name === 'advanced' ? '.metric:last-child span' : '.summary-item:last-child span:last-child'
    const waitBalance = value => page.waitForFunction(({ selector, value }) => document.querySelector(selector)?.textContent.trim() === value, {
      selector: summarySelector, value: value + (name === 'advanced' ? '' : ' USD') })
    await waitBalance('120.50')
    assert.equal(await wallet.locator('.wallet-balance').count(), 0)
    assert.ok((await walletSelect.textContent()).includes('資金'))
    // Both withdrawal tabs keep the selected wallet and use the new amount label.
    await page.locator(name === 'advanced' ? '.tabs button' : '.tab-item').nth(1).click()
    assert.equal(await wallet.count(), 1)
    assert.ok((await page.locator('#app').textContent()).includes('金額'))
    await page.screenshot({ path: path.join(report, name + '-default-wallet.png'), fullPage: true })
    await quick.click()
    await page.screenshot({ path: path.join(report, name + '-default-transfer.png') })
    await page.keyboard.press('Escape')
    // A FUND withdrawal retried after a frontend upgrade must keep its original request ID.
    await page.evaluate(() => {
      const body = { address: '585958', amount: 1, currency: 'USD', network: 'USD', remark: '', type: 'bank' }
      const key = 'pending-funds:' + JSON.stringify([location.origin, 'REAL', 1, 999, '/withdraw/submit', body])
      sessionStorage.setItem(key, 'fixture-legacy-fund-request-id')
    })
    await page.locator('#app input[type=number]').first().fill('1')
    await page.locator(name === 'advanced' ? '.primary' : '.withdraw-button').click()
    await waitBalance('119.50')
    assert.equal(withdrawals[0].requestId, 'fixture-legacy-fund-request-id')
    assert.equal(withdrawals[0].accountType, undefined)
    await page.locator(name === 'advanced' ? '.tabs button' : '.tab-item').nth(0).click()
    await page.locator(name === 'advanced' ? '.tabs button' : '.tab-item').nth(1).click()
    await page.waitForFunction(() => document.querySelector('#app')?.textContent.includes('585958'))
    await walletSelect.click()
    await page.locator('.app-select__option').nth(1).click()
    await waitBalance('80.25')
    await page.locator(name === 'advanced' ? '.tabs button' : '.tab-item').nth(0).click()
    assert.ok((await walletSelect.textContent()).includes('合約'), 'changing withdrawal type preserves the source wallet')
    await waitBalance('80.25')
    await page.locator(name === 'advanced' ? '.tabs button' : '.tab-item').nth(1).click()
    await page.waitForFunction(() => document.querySelector('#app')?.textContent.includes('585958'))
    const amountInput = page.locator('#app input[type=number]').first()
    await amountInput.fill('10')
    await page.locator(name === 'advanced' ? '.primary' : '.withdraw-button').click()
    await waitBalance('70.25')
    assert.equal(withdrawals[1].accountType, 'CONTRACT')
    assert.equal(balances.FUND, 119.5)
    await quick.click()
    assert.ok(await dialog.isVisible())
    assert.ok((await dialog.locator('.transfer-field').nth(1).textContent()).includes(await walletSelect.textContent()))
    assert.ok(await dialog.locator('.confirm-transfer').isDisabled())
    await dialog.locator('input').fill('999999')
    assert.ok(await dialog.locator('.confirm-transfer').isDisabled())
    assert.ok(await dialog.locator('.transfer-error').isVisible())
    await dialog.locator('input').fill('20')
    if (name !== 'advanced') {
      loseResponse = true
      await dialog.locator('.confirm-transfer').click()
      await dialog.locator('.transfer-error').waitFor()
      assert.equal(transfers.length, 1)
      await dialog.locator('.confirm-transfer').click()
      await dialog.waitFor({ state: 'hidden' })
      assert.equal(transfers.length, 2)
      assert.equal(transfers[0].requestId, transfers[1].requestId, 'a lost response must not create a second transfer')
    } else {
      await dialog.locator('.confirm-transfer').click()
      await dialog.waitFor({ state: 'hidden' })
    }
    await waitBalance('90.25')
    assert.equal(balances.FUND, 99.5)
    await page.screenshot({ path: path.join(report, name + '-wallet.png'), fullPage: true })
    await quick.click()
    await page.screenshot({ path: path.join(report, name + '-transfer.png') })
    await dialog.locator('.swap-accounts').click()
    assert.equal(await dialog.locator('input').inputValue(), '')
    await dialog.locator('.transfer-amount button').click()
    assert.equal(await dialog.locator('input').inputValue(), '90.25')
    await page.keyboard.press('Escape')
    await dialog.waitFor({ state: 'hidden' })
    for (const language of languages) {
      await page.evaluate(language => window.fixture.locale.setLocale(language), language)
      await page.setViewportSize({ width: 320, height: 720 })
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), name + ' layout overflow: ' + language)
      const translated = await page.evaluate(() => window.fixture.locale.text('快捷劃轉', 'Quick transfer'))
      assert.equal((await quick.textContent()).trim(), translated)
      await quick.click()
      assert.equal((await dialog.locator('h2').textContent()).trim(), translated)
      assert.ok(await dialog.evaluate(element => element.getBoundingClientRect().right <= innerWidth && element.getBoundingClientRect().left >= 0))
      assert.ok(!['Quick transfer', 'Swap accounts', 'amountText'].includes((await dialog.locator('h2').textContent()).trim()) || language === 'en')
      if (['ar', 'de', 'my', 'ja'].includes(language)) await page.screenshot({ path: path.join(report, name + '-' + language + '.png') })
      await page.keyboard.press('Escape')
    }
    await page.evaluate(() => window.fixture.locale.setLocale('zh-TW'))
    await page.locator(name === 'advanced' ? '.tabs button' : '.tab-item').nth(0).click()
    await page.locator(name === 'advanced' ? 'button.row' : '.select-input').first().click()
    await page.locator('.modal-item').first().click()
    await page.locator('.modal-confirm').click()
    await page.locator('#app input[type=number]').first().fill('5')
    await page.waitForFunction(() => document.querySelector('#app')?.textContent.includes('FIXTURE-ADDRESS'))
    await page.locator(name === 'advanced' ? '.primary' : '.withdraw-button').click()
    await waitBalance('85.25')
    assert.equal(withdrawals[2].type, 'digital')
    assert.equal(withdrawals[2].accountType, 'CONTRACT')
    await walletSelect.click()
    await page.locator('.app-select__option').nth(2).click()
    await waitBalance('30.00')
    // A failed refresh must never display a fabricated zero balance or permit money writes.
    failAssets = true
    await page.reload()
    await waitBalance('—')
    assert.ok(await quick.isDisabled())
    assert.ok(await page.locator(name === 'advanced' ? '.primary' : '.withdraw-button').isDisabled())
    assert.deepEqual(errors, [])
    console.log(JSON.stringify({ view: name, passed: true, languages: languages.length, withdrawalWallet: withdrawals[1].accountType, legacyFundRetry: true, transferWrites: receipts.size }))
  } finally { await page.close(); await server.close() }
}

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    await check(browser, 'exchange-frontend', '@/views/Withdraw.vue', 'mobile')
    await check(browser, 'exchange-frontend', '@/advanced/views/Withdraw.vue', 'advanced')
    await check(browser, 'exchange-pc', '@/views/Withdraw.vue', 'pc')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
