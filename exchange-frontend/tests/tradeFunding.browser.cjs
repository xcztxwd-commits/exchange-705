// Isolated UI acceptance. All assets and quotes are fixtures; no real funds or orders.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const { pathToFileURL } = require('node:url')
const { createRequire } = require('node:module')
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const repository = path.resolve(__dirname, '../..'), report = path.join(repository, 'reports/trade-funding')
fs.mkdirSync(report, { recursive: true })
const languages = ['zh-TW', 'en', 'fr', 'de', 'ru', 'es', 'pt', 'it', 'ar', 'tr', 'id', 'my', 'hi', 'cs', 'pl', 'ja', 'ko', 'th', 'vi']
const item = { symbol: 'USDJPY=X', category: 'Forex', sourceCategory: 'Forex', baseCurrency: 'USD', quoteCurrency: 'JPY', nameCn: 'USD/JPY', nameEn: 'USD/JPY', lotSize: 100000, feeMultiplier: 5, maxLeverage: 100, pricePrecision: 3, quantityStep: 0.01, minOrderQuantity: 0.01, isEnabled: true }

async function check(browser, project, view, name) {
  const desktop = name === 'pc', root = path.join(repository, project)
  const projectRequire = createRequire(path.join(root, 'package.json'))
  const postcss = desktop ? { plugins: [projectRequire('tailwindcss')({ darkMode: 'class', content: [path.join(root, 'index.html'), path.join(root, 'src/**/*.{vue,js,ts,jsx,tsx}')] }), projectRequire('autoprefixer')()] } : undefined
  const { createServer } = await import(pathToFileURL(path.join(root, 'node_modules/vite/dist/node/index.js')))
  const vue = (await import(pathToFileURL(path.join(root, 'node_modules/@vitejs/plugin-vue/dist/index.mjs')))).default
  const virtual = root.replaceAll('\\', '/') + '/__trade-funding-fixture.js'
  const entry = `import {createApp} from 'vue';import {createPinia} from 'pinia';import {createRouter,createMemoryHistory} from 'vue-router';import App from '${view}';import {useAuthStore} from '@/store/auth';import {useLocaleStore} from '@/store/locale';import {useTrialWallet} from '@/utils/useTrialWallet';import {useMarketStore} from '@/store/market';import socket from '@/utils/marketWebSocket';import '@/styles/global.scss';${desktop ? "import ElementPlus from 'element-plus';import 'element-plus/dist/index.css';" : ''}
    const app=createApp(App);app.use(createPinia());${desktop ? 'app.use(ElementPlus);' : ''}useAuthStore().setAuth('fixture-only',{id:999,tenantId:1});const locale=useLocaleStore();locale.setLocale('zh-TW');const wallet=useTrialWallet();const market=useMarketStore();socket.connect=async()=>{};market.initMarketService=async()=>{};market.subscribeSymbol=async()=>{};market.subscribeSymbols=async()=>{};
    const item=${JSON.stringify(item)};market.symbols=[item];market.priceMap[item.symbol]={price:158.434,timestamp:Date.now()};market.quoteStatusMap[item.symbol]={status:'available',timestamp:Date.now(),fetchedAt:Date.now(),expiresAt:Date.now()+300000,quoteToUsdRate:1/158.434,conversionAvailable:true,conversionExpiresAt:Date.now()+300000,marginBaseToUsdRate:1,marginRateExpiresAt:Date.now()+300000};
    const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});app.use(router);await router.replace('/trade');app.mount('#app');window.fixture={locale,wallet};window.trade=app._instance.setupState;`
  const server = await createServer({ root, configFile: false, envDir: report, cacheDir: path.join(report, 'cache-' + name),
    plugins: [{ name: 'trade-funding-fixture', resolveId(id) { if (id === 'trade-funding-fixture') return virtual }, load(id) { if (id === virtual) return entry; if (id.endsWith('/components/KlineChart.vue')) return '<template><div style="height:160px;background:#f5f7fb;border-radius:8px" /></template>' },
      configureServer(s) { s.middlewares.use('/__trade_test', (_req, res) => { res.setHeader('Content-Type', 'text/html'); res.end('<html><body><div id="app"></div><script type="module" src="/@id/trade-funding-fixture"></script></body></html>') }) } }, vue()],
    resolve: { dedupe: ['vue', 'vue-router', 'pinia'], alias: { '@': path.join(root, 'src') } }, css: { postcss }, server: { host: '127.0.0.1', port: 0 } })
  await server.listen()
  const port = server.httpServer.address().port, page = await browser.newPage({ viewport: { width: desktop ? 1440 : 390, height: 1200 } })
  const errors = [], writes = []
  page.on('pageerror', error => errors.push(error.message))
  let walletData = { success: true, fundBalance: 0, fundFrozen: 0, contractBalance: 2500, contractFrozen: 0, optionBalance: 3500, optionFrozen: 0, trialEligible: true, trialAvailable: 1439.62, trialFrozen: 0, trialExpiresAt: null, fundingSources: ['CONTRACT', 'OPTION', 'TRIAL'] }
  await page.route('**/*', async route => {
    const url = new URL(route.request().url())
    if (url.hostname !== '127.0.0.1' || url.port !== String(port)) return route.abort('blockedbyclient')
    if (!url.pathname.startsWith('/api/')) return route.continue()
    if (route.request().method() !== 'GET' && url.pathname !== '/api/market/price/batch') writes.push(url.pathname)
    let data = { success: true, list: [], orders: [], records: [], data: [] }
    if (url.pathname === '/api/user/assets') data = { ...walletData, serverNow: new Date().toISOString() }
    if (url.pathname === '/api/market/all') data = { success: true, list: [item] }
    if (url.pathname === '/api/trade/option/durations') data = [{ duration: 60, enabled: true, profitRate: .8, lossRate: 1, minAmount: 1, maxAmount: 10000 }]
    if (url.pathname === '/api/kyc/status') data = { canTrade: true, kycStatus: 'VERIFIED', latestRecord: { status: 'APPROVED' } }
    if (url.pathname.endsWith('/timezone')) data = { timezone: 'UTC' }
    await route.fulfill({ json: data })
  })
  const control = () => page.locator('[data-testid="funding-selector"]:visible').getByRole('combobox')
  async function setWallet(changes) {
    walletData = { ...walletData, ...changes }
    await page.evaluate(data => window.fixture.wallet.accept({ ...data, serverNow: new Date().toISOString() }, performance.now()), walletData)
  }
  async function choose(value) {
    const label = await page.evaluate(value => window.trade.fundingOptions.find(option => option.value === value).label, value)
    await control().click()
    await page.getByRole('option', { name: label, exact: true }).click()
    await page.waitForFunction(value => window.trade.fundingSource === value, value)
  }
  try {
    await page.goto(`http://127.0.0.1:${port}/__trade_test`)
    await control().waitFor()
    await page.waitForFunction(() => window.trade.fundingSource === 'TRIAL')
    await page.waitForFunction(desktop => (desktop ? window.trade.tradingAvailable : window.trade.available) === 1439.62, desktop)
    assert.equal(await page.locator('.product-header + [data-testid="funding-selector"]').count(), 0, 'selector must leave the chart header')
    const next = await page.locator('[data-testid="funding-selector"]:visible').evaluate(row => row.nextElementSibling.textContent)
    assert.ok(next.includes('1,439.62'), 'selector is immediately above the selected available balance')
    assert.ok((await control().boundingBox()).width <= 144, 'dropdown remains compact')
    await page.locator(desktop ? 'aside' : '.trading-card').last().screenshot({ path: path.join(report, name + '.png') })
    await setWallet({ trialExpiresAt: new Date(Date.now() + 1800000).toISOString() })
    await page.locator('[data-testid="trade-trial-countdown"]:visible').waitFor()
    assert.ok((await page.locator('[data-testid="funding-selector"]:visible').boundingBox()).height <= 44, 'trial countdown must not widen or stretch the funding row')
    await setWallet({ trialExpiresAt: null })
    await choose('CONTRACT')
    await page.waitForFunction(desktop => (desktop ? window.trade.tradingAvailable : window.trade.available) === 2500, desktop)
    await choose('TRIAL')
    await setWallet({ trialAvailable: 0, trialFrozen: 20 })
    await page.waitForFunction(() => window.trade.fundingSource === 'CONTRACT' && window.trade.fundingOptions.length === 1)
    await control().click(); assert.equal(await page.getByRole('option').count(), 1); await page.keyboard.press('Escape')
    await setWallet({ trialAvailable: 1439.62, trialFrozen: 0 })
    assert.equal(await page.evaluate(() => window.trade.fundingSource), 'CONTRACT', 'explicit cash selection persists')
    await choose('TRIAL')
    await setWallet({ trialExpiresAt: '2000-01-01T00:00:00Z' })
    await page.waitForFunction(() => window.trade.fundingSource === 'CONTRACT' && window.trade.fundingOptions.length === 1)
    await setWallet({ trialExpiresAt: null, trialEligible: false })
    assert.equal(await page.evaluate(() => window.trade.fundingOptions.length), 1, 'ineligible credit must stay hidden')
    await setWallet({ trialEligible: true })
    for (const language of languages) {
      await page.evaluate(language => window.fixture.locale.setLocale(language), language)
      if (!desktop) await page.setViewportSize({ width: 320, height: 1200 })
      const label = await page.evaluate(() => window.fixture.locale.text('資金來源', 'Funding source'))
      assert.equal(await control().getAttribute('aria-label'), label)
      assert.ok((await control().boundingBox()).width <= 144)
      await control().click()
      assert.equal(await page.getByRole('option').count(), 2)
      await page.keyboard.press('Escape')
      if (['ar', 'de', 'my'].includes(language)) await page.locator('[data-testid="funding-selector"]:visible').screenshot({ path: path.join(report, name + '-' + language + '.png') })
    }
    await page.evaluate(desktop => { if (desktop) window.trade.tradeMode = 'options'; else window.trade.activeTab = 'term' }, desktop)
    await page.waitForFunction(() => window.trade.fundingOptions[0].value === 'OPTION')
    await choose('OPTION')
    assert.equal(await page.evaluate(() => window.trade.optionAvailable ?? window.trade.optionTradingAvailable), 3500)
    await choose('TRIAL')
    await setWallet({ trialAvailable: 0 })
    await page.waitForFunction(() => window.trade.fundingSource === 'OPTION' && window.trade.fundingOptions.length === 1)
    assert.deepEqual(errors, [])
    assert.deepEqual(writes, [], 'changing funding must never submit an order or move funds')
    console.log(JSON.stringify({ view: name, passed: true, languages: languages.length, trialAvailability: true, cashFallback: true, compact: true }))
  } finally { await page.close(); await server.close() }
}

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    await check(browser, 'exchange-frontend', '@/views/Trade.vue', 'mobile')
    await check(browser, 'exchange-frontend', '@/advanced/views/Trade.vue', 'advanced')
    await check(browser, 'exchange-pc', '@/views/DesktopTrade.vue', 'pc')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
