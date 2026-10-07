// Isolated wallet display acceptance; every request uses fixture data, never a real backend.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const { pathToFileURL } = require('node:url')
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const repository = path.resolve(__dirname, '../..'), report = path.join(repository, 'reports/wallet-balances')
const languages = ['zh-TW', 'en', 'fr', 'de', 'ru', 'es', 'pt', 'it', 'ar', 'tr', 'id', 'my', 'hi', 'cs', 'pl', 'ja', 'ko', 'th', 'vi']
fs.mkdirSync(report, { recursive: true })
const cash = { fundBalance: '180000.10', fundFrozen: '1000.10', contractBalance: '50000.50', contractFrozen: '1240.06', optionBalance: '11200', optionFrozen: '123.50' }

async function check(browser, project, view, name) {
  const root = path.join(repository, project)
  const { createServer } = await import(pathToFileURL(path.join(root, 'node_modules/vite/dist/node/index.js')))
  const vue = (await import(pathToFileURL(path.join(root, 'node_modules/@vitejs/plugin-vue/dist/index.mjs')))).default
  const virtual = root.replaceAll('\\', '/') + '/__wallet-balances-fixture.js'
  const entry = `import {createApp} from 'vue';import {createPinia} from 'pinia';import {createRouter,createMemoryHistory} from 'vue-router';import App from '${view}';import {useAuthStore} from '@/store/auth';import {useLocaleStore} from '@/store/locale';import '@/styles/global.scss';const app=createApp(App);app.use(createPinia());useAuthStore().setAuth('fixture-only',{id:999,tenantId:1});const locale=useLocaleStore();locale.setLocale('zh-TW');window.fixture={locale};const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});app.use(router);await router.replace('/wallet');app.mount('#app');`
  const server = await createServer({ root, configFile: false, envDir: report, cacheDir: path.join(report, 'cache-' + name),
    plugins: [{ name: 'wallet-balances-fixture', resolveId(id) { if (id === 'wallet-balances-fixture') return virtual }, load(id) { if (id === virtual) return entry },
      configureServer(s) { s.middlewares.use('/__wallet_test', (_req, res) => { res.setHeader('Content-Type', 'text/html'); res.end('<html><body><div id="app"></div><script type="module" src="/@id/wallet-balances-fixture"></script></body></html>') }) } }, vue()],
    resolve: { dedupe: ['vue', 'vue-router', 'pinia'], alias: { '@': path.join(root, 'src') } }, server: { host: '127.0.0.1', port: 0 } })
  await server.listen()
  const port = server.httpServer.address().port
  const page = await browser.newPage({ viewport: { width: name === 'pc' ? 1200 : 390, height: 1200 } })
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  let data = { ...cash }, eligible = true, unblock
  const pending = new Promise(resolve => { unblock = resolve })
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.hostname !== '127.0.0.1' || url.port !== String(port)) return route.abort('blockedbyclient')
    if (!url.pathname.startsWith('/api/')) return route.continue()
    assert.equal(route.request().method(), 'GET', 'wallet display must not write funds')
    if (url.pathname === '/api/user/assets') {
      await pending
      return route.fulfill({ json: { success: true, ...data, serverNow: new Date().toISOString(), trialEligible: eligible, trialAvailable: '1439.62', trialFrozen: 0, trialExpiresAt: null, fundingSources: ['CONTRACT', 'OPTION', 'TRIAL'] } })
    }
    return route.fulfill({ status: 404, json: { message: 'unimplemented fixture endpoint' } })
  })
  const total = page.locator(name === 'advanced' ? '.balance-line .large' : '.assets-value')
  const cards = page.locator('.account-card'), mainAmounts = page.locator('.account-balance strong')
  const toggle = () => page.getByRole('button', { name: '隱藏餘額', exact: true })
  try {
    await page.goto(`http://127.0.0.1:${port}/__wallet_test`)
    await cards.last().waitFor()
    assert.equal(await cards.count(), 3)
    assert.deepEqual(await mainAmounts.allTextContents(), ['—', '—', '—'])
    assert.equal((await total.textContent()).trim(), '—')
    unblock()
    await page.waitForFunction(() => document.querySelector('.fund .account-balance')?.textContent.includes('181,000.20'))
    assert.deepEqual((await mainAmounts.allTextContents()).map(value => value.trim()), ['181,000.20', '51,240.56', '11,323.50'])
    assert.ok((await total.textContent()).includes('243,564.26'), 'total includes available and frozen cash, excludes trial credit')
    assert.deepEqual((await page.locator('.fund dd').allTextContents()).map(value => value.trim()), ['180,000.10', '1,000.10'])
    assert.ok(await page.locator('.trial-account-card').isVisible())
    assert.ok((await page.locator('.trial-account-card').textContent()).includes('1,439.62'))
    await page.screenshot({ path: path.join(report, name + '.png'), fullPage: true })
    await toggle().click()
    assert.equal((await total.textContent()).trim(), '****')
    assert.deepEqual(await mainAmounts.allTextContents(), ['****', '****', '****'])
    assert.ok((await page.locator('.account-details dd').allTextContents()).every(value => value === '****'))
    assert.ok(!(await page.locator('.trial-account-card').textContent()).includes('1,439.62'))
    await page.screenshot({ path: path.join(report, name + '-hidden.png') })
    await page.getByRole('button', { name: '顯示餘額', exact: true }).click()
    for (const language of languages) {
      await page.evaluate(language => window.fixture.locale.setLocale(language), language)
      await page.setViewportSize({ width: 320, height: 720 })
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), name + ' overflow in ' + language)
      const labels = await page.evaluate(() => ({ heading: window.fixture.locale.text('帳戶餘額', 'Account balances'), frozen: window.fixture.locale.text('凍結金額', 'Frozen balance'), names: ['fundAccountTitle', 'contractAccountTitle', 'optionAccountTitle'].map(key => window.fixture.locale.t(key)), balance: new Intl.NumberFormat(window.fixture.locale.locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(181000.2) }))
      assert.equal((await page.locator('.wallet-accounts h2').textContent()).trim(), labels.heading)
      assert.deepEqual(await cards.locator('h3').allTextContents(), labels.names)
      assert.equal((await mainAmounts.first().textContent()).trim(), labels.balance)
      assert.equal((await cards.first().locator('dt').last().textContent()).trim(), labels.frozen)
      if (['ar', 'de', 'my', 'ja'].includes(language)) await page.screenshot({ path: path.join(report, name + '-' + language + '.png'), fullPage: true })
    }
    await page.evaluate(() => window.fixture.locale.setLocale('zh-TW'))
    delete data.optionFrozen
    await page.reload()
    await page.getByRole('button', { name: '重試', exact: true }).waitFor()
    assert.deepEqual(await mainAmounts.allTextContents(), ['—', '—', '—'], 'missing frozen fields must not invent a zero balance')
    assert.equal((await total.textContent()).trim(), '—')
    data = Object.fromEntries(Object.keys(cash).map(key => [key, 0])); eligible = false
    await page.getByRole('button', { name: '重試', exact: true }).click()
    await page.waitForFunction(() => document.querySelector('.fund .account-balance')?.textContent.includes('0.00'))
    assert.deepEqual(await mainAmounts.allTextContents(), ['0.00', '0.00', '0.00'], 'all three accounts remain visible at zero')
    assert.ok(!(await page.locator('.trial-account-card').count()))
    assert.deepEqual(errors, [])
    console.log(JSON.stringify({ view: name, passed: true, languages: languages.length, accounts: 3, hiddenAmounts: true, totalIncludesFrozen: true }))
  } finally { unblock(); await page.close(); await server.close() }
}

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    await check(browser, 'exchange-frontend', '@/views/Wallet.vue', 'mobile')
    await check(browser, 'exchange-frontend', '@/advanced/views/Wallet.vue', 'advanced')
    await check(browser, 'exchange-pc', '@/views/Wallet.vue', 'pc')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
