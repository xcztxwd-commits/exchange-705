// Regression: viewport and account-state changes must not remove primary trading controls.
// Optional: PLAYWRIGHT_PATH / CHROME_PATH select installed tools; TRADE_ACTIONS_REPORT_DIR stores evidence outside the repository.
const fs = require('node:fs'), path = require('node:path'), os = require('node:os'), assert = require('node:assert/strict')
const { pathToFileURL } = require('node:url')
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const root = path.resolve(__dirname, '..')
const output = process.env.TRADE_ACTIONS_REPORT_DIR || fs.mkdtempSync(path.join(os.tmpdir(), 'trade-actions-'))
fs.mkdirSync(output, { recursive: true })
const item = { symbol: 'USDJPY=X', category: 'Forex', sourceCategory: 'Forex', baseCurrency: 'USD', quoteCurrency: 'JPY', lotSize: 100000, feeMultiplier: 5, maxLeverage: 100, pricePrecision: 3, quantityStep: 0.01, minOrderQuantity: 0.01, isEnabled: true }
const capabilities = { tenantId: 1, tenantName: 'Fixture', status: 'ACTIVE', policyVersion: 1, acceptNewBusiness: true, features: { contract: true, option: true } }
const report = { browserPlugin: 'Browser plugin not available', keyboard: 'Simulated VisualViewport metrics; native iOS keyboard is not emulated', cases: [] }

function entry(edition) {
  const component = edition === 'classic' ? '@/views/Trade.vue' : '@/advanced/views/Trade.vue'
  return `import {createApp,h} from 'vue';import {createPinia} from 'pinia';import {createRouter,createMemoryHistory,RouterView} from 'vue-router';
    import Trade from '${component}';import {useAuthStore} from '@/store/auth';import {useLocaleStore} from '@/store/locale';import {useTrialWallet} from '@/utils/useTrialWallet';
    import {useMarketStore} from '@/store/market';import socket from '@/utils/marketWebSocket';import {tenantFeatures} from '@/utils/tenantFeatures';import '@/styles/global.scss';
    let view;const app=createApp({render:()=>h(RouterView,null,{default:({Component})=>Component&&h(Component,{ref:instance=>view=instance})})});app.use(createPinia());
    useAuthStore().setAuth('local-fixture',{id:999,tenantId:1});const locale=useLocaleStore();locale.setLocale('ja');const wallet=useTrialWallet();
    tenantFeatures.value=${JSON.stringify(capabilities)};const market=useMarketStore();socket.connect=async()=>{};market.initMarketService=async()=>{};market.subscribeSymbol=async()=>{};market.subscribeSymbols=async()=>{};
    const item=${JSON.stringify(item)};market.symbols=[item];market.priceMap[item.symbol]={price:158.324,timestamp:Date.now()};
    const quote={status:'available',timestamp:Date.now(),fetchedAt:Date.now(),expiresAt:Date.now()+300000,quoteToUsdRate:1/158.324,conversionAvailable:true,conversionExpiresAt:Date.now()+300000,marginBaseToUsdRate:1,marginRateExpiresAt:Date.now()+300000};market.quoteStatusMap[item.symbol]=quote;
    const router=createRouter({history:createMemoryHistory(),routes:[{path:'/',redirect:'/trade'},{path:'/trade',component:Trade},{path:'/:pathMatch(.*)*',component:{render:()=>h('p','Other page')}}]});app.use(router);
    window.fixture={locale,wallet,market,tenantFeatures,router,quote,trade:()=>view.$.setupState};await router.replace('/trade');app.mount('#app');`
}

async function run() {
  const { createServer } = await import(pathToFileURL(path.join(root, 'node_modules/vite/dist/node/index.js')))
  const vue = (await import(pathToFileURL(path.join(root, 'node_modules/@vitejs/plugin-vue/dist/index.mjs')))).default
  const server = await createServer({ root, configFile: false, envDir: output, cacheDir: path.join(output, 'vite-cache'),
    plugins: [{ name: 'trade-actions-fixture', resolveId(id) { if (id.startsWith('trade-actions-fixture-')) return root.replaceAll('\\', '/') + '/' + id + '.js' },
      load(id) { if (id.endsWith('trade-actions-fixture-classic.js')) return entry('classic'); if (id.endsWith('trade-actions-fixture-advanced.js')) return entry('advanced'); if (id.endsWith('/components/KlineChart.vue')) return '<template><div style="height:160px;background:#f5f7fb;border-radius:8px" /></template>' },
      configureServer(s) { s.middlewares.use('/__trade_actions', (req, res) => { const edition = req.url.includes('advanced') ? 'advanced' : 'classic'; res.setHeader('Content-Type', 'text/html'); res.end('<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Trade actions regression</title></head><body><div id="app"></div><script type="module" src="/@id/trade-actions-fixture-' + edition + '"></script></body></html>') }) }
    }, vue()], resolve: { dedupe: ['vue', 'vue-router', 'pinia'], alias: { '@': path.join(root, 'src') } },
    css: { preprocessorOptions: { scss: { silenceDeprecations: ['legacy-js-api', 'import'] } } }, server: { host: '127.0.0.1', port: 0 } })
  await server.listen()
  const port = server.httpServer.address().port
  const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) })
  try {
    for (const edition of ['classic', 'advanced']) {
      for (const noVisualViewport of [false, true]) {
        const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })
        const current = { edition, noVisualViewport, checks: [], errors: [], warnings: [], writes: [], isolatedResources: [] }
        report.cases.push(current)
        page.on('pageerror', error => current.errors.push(error.message))
        page.on('console', message => { if (message.type() === 'error') current.errors.push(message.text()); if (message.type() === 'warning') current.warnings.push(message.text()) })
        let walletUnavailable = false
        if (noVisualViewport) await page.addInitScript(() => Object.defineProperty(window, 'visualViewport', { configurable: true, value: undefined }))
        await page.route('**/*', async route => {
          const url = new URL(route.request().url())
          if (url.hostname !== '127.0.0.1' || url.port !== String(port)) {
            current.isolatedResources.push({ host: url.hostname, type: route.request().resourceType() })
            return route.fulfill({ contentType: route.request().resourceType() === 'script' ? 'text/javascript' : 'application/json', body: route.request().resourceType() === 'script' ? '' : '{"success":false}' })
          }
          if (!url.pathname.startsWith('/api/')) return route.continue()
          if (route.request().method() !== 'GET' && url.pathname !== '/api/market/price/batch') current.writes.push(url.pathname)
          let data = { success: true, list: [], orders: [], records: [], data: [] }
          if (url.pathname === '/api/user/assets') data = walletUnavailable ? { success: false, message: 'Fixture wallet unavailable' } : { success: true, fundBalance: 0, fundFrozen: 0, contractBalance: 17544.17, contractFrozen: 0, optionBalance: 3500, optionFrozen: 0, trialEligible: false, trialAvailable: 0, trialFrozen: 0, trialExpiresAt: null, fundingSources: ['CONTRACT', 'OPTION'], serverNow: new Date().toISOString() }
          if (url.pathname === '/api/market/all') data = { success: true, list: [item] }
          if (url.pathname === '/api/trade/option/durations') data = [{ duration: 60, enabled: true, profitRate: .8, lossRate: 1, minAmount: 1, maxAmount: 10000 }]
          if (url.pathname === '/api/kyc/status') data = { canTrade: true, kycStatus: 'VERIFIED', latestRecord: { status: 'APPROVED' } }
          if (url.pathname.endsWith('/timezone')) data = { timezone: 'UTC' }
          await route.fulfill({ json: data })
        })
        const buttons = page.locator('.action-dock .primary-actions button')
        async function check(label, disabled) {
          assert.equal(await buttons.count(), 2, `${edition}: ${label}: both directions remain in DOM`)
          for (const button of await buttons.all()) assert.ok(await button.isVisible(), `${edition}: ${label}: direction is visible`)
          if (disabled != null) assert.deepEqual(await buttons.evaluateAll(elements => elements.map(element => element.disabled)), [disabled, disabled], `${edition}: ${label}: disabled state`)
          const navigation = page.locator(edition === 'classic' ? '.tabbar' : '.advanced-nav')
          assert.ok(await navigation.isVisible(), `${edition}: ${label}: navigation is visible`)
          current.checks.push({ label, disabled, visibleDirections: 2, navigationVisible: true })
        }
        try {
          await page.goto(`http://127.0.0.1:${port}/__trade_actions/${edition}`)
          assert.equal(await page.title(), 'Trade actions regression')
          assert.equal(await page.locator('vite-error-overlay').count(), 0)
          await page.waitForFunction(() => window.fixture.trade().tradeVerified && window.fixture.trade().available === 17544.17)
          await check('initial render', false)
          await page.locator('#contract-quantity').fill('16.8')
          await check('manual edit to 16.8 lots', false)
          assert.deepEqual(await page.evaluate(() => [window.fixture.trade().margin, window.fixture.trade().fee]), [16800, 84])
          if (!noVisualViewport) {
            await page.evaluate(() => {
              window.testViewport = { height: innerHeight - 320, offsetTop: 0 }
              Object.defineProperty(visualViewport, 'height', { configurable: true, get: () => window.testViewport.height })
              Object.defineProperty(visualViewport, 'offsetTop', { configurable: true, get: () => window.testViewport.offsetTop })
              visualViewport.dispatchEvent(new Event('resize'))
            })
            await check('keyboard shrinks visual viewport by 320px', false)
            await page.locator('.fees dt').nth(1).click()
            await check('blur with stale keyboard height', false)
            await page.evaluate(() => { const card = document.querySelector('.trading-card'); window.scrollTo(0, card.getBoundingClientRect().top + scrollY - 12) })
            await page.screenshot({ path: path.join(output, `${edition}-stale-viewport.png`), fullPage: false })
            await page.evaluate(() => { window.testViewport.height = innerHeight; window.dispatchEvent(new Event('resize')) })
            await check('keyboard closes with window resize only', false)
            for (const height of [524, 0, NaN, 700, 844]) {
              await page.evaluate(height => { window.testViewport.height = height; window.testViewport.offsetTop = 80; visualViewport.dispatchEvent(new Event('scroll')); visualViewport.dispatchEvent(new Event('resize')) }, height)
              await check(`delayed or out-of-order viewport height ${height}`, false)
            }
            await page.evaluate(() => { delete visualViewport.height; delete visualViewport.offsetTop; visualViewport.dispatchEvent(new Event('resize')) })
          }
          await page.locator('#contract-quantity').fill('')
          await check('empty quantity', true)
          await page.locator('#contract-quantity').fill('16.801')
          await check('quantity outside lot step', true)
          await page.locator('#contract-quantity').fill('100')
          await check('insufficient funds', true)
          await page.locator('#contract-quantity').fill('16.8')
          await page.evaluate(() => { window.fixture.market.quoteStatusMap['USDJPY=X'] = { ...window.fixture.quote, expiresAt: Date.now() - 1 }; window.fixture.trade().now = Date.now() })
          await check('expired quote', true)
          await page.evaluate(() => { window.fixture.market.quoteStatusMap['USDJPY=X'] = { ...window.fixture.quote }; window.fixture.trade().now = Date.now() })
          walletUnavailable = true
          await page.evaluate(() => window.fixture.wallet.refresh())
          await check('wallet refresh unavailable', true)
          walletUnavailable = false
          await page.evaluate(() => window.fixture.trade().refreshAccount())
          await page.waitForFunction(() => window.fixture.trade().contractReason === '')
          for (const snapshot of [null, { ...capabilities, features: { contract: false, option: false } }, { ...capabilities, status: 'SUSPENDED' }]) {
            await page.evaluate(snapshot => { window.fixture.tenantFeatures.value = snapshot }, snapshot)
            await check('capabilities unavailable or trading restricted', true)
          }
          await page.evaluate(snapshot => { window.fixture.tenantFeatures.value = snapshot }, capabilities)
          await buttons.first().click()
          const confirm = page.locator('dialog[open] footer .sheet-primary')
          await confirm.waitFor()
          assert.equal(await page.evaluate(() => window.fixture.trade().side), 'BUY')
          assert.equal(await confirm.isDisabled(), false)
          await page.evaluate(() => { window.fixture.tenantFeatures.value = null })
          assert.equal(await confirm.count(), 1, 'Confirmation remains present when capabilities disappear')
          assert.ok(await confirm.isVisible())
          assert.ok(await confirm.isDisabled())
          await page.keyboard.press('Escape')
          await page.evaluate(snapshot => { window.fixture.tenantFeatures.value = snapshot }, capabilities)
          await buttons.last().click()
          await confirm.waitFor()
          assert.equal(await page.evaluate(() => window.fixture.trade().side), 'SELL')
          assert.equal(await confirm.isDisabled(), false)
          await page.keyboard.press('Escape')
          await page.evaluate(snapshot => { window.fixture.tenantFeatures.value = snapshot; window.fixture.trade().activeTab = 'term' }, capabilities)
          await check('term trading directions', false)
          await buttons.first().click()
          await page.locator('dialog[open] input[type=number]').fill('10')
          await confirm.waitFor()
          assert.equal(await confirm.isDisabled(), false)
          await page.evaluate(snapshot => { window.fixture.tenantFeatures.value = { ...snapshot, features: { ...snapshot.features, option: false } } }, capabilities)
          assert.equal(await confirm.count(), 1, 'Term confirmation remains present when trading is restricted')
          assert.ok(await confirm.isDisabled())
          await page.keyboard.press('Escape')
          await check('term trading restricted', true)
          await page.evaluate(snapshot => { window.fixture.tenantFeatures.value = snapshot; window.fixture.trade().activeTab = 'contract' }, capabilities)
          for (const viewport of [{ width: 375, height: 667 }, { width: 844, height: 390 }, { width: 768, height: 1024 }, { width: 1440, height: 900 }]) {
            await page.setViewportSize(viewport)
            await check(`viewport ${viewport.width}x${viewport.height}`)
            await buttons.first().scrollIntoViewIfNeeded()
            const bounds = await buttons.first().boundingBox()
            assert.ok(bounds && bounds.y >= 0 && bounds.y + bounds.height <= viewport.height, 'Trading direction can be reached after viewport resize')
          }
          await page.setViewportSize({ width: 390, height: 844 })
          await page.evaluate(() => { window.dispatchEvent(new Event('pageshow')); window.dispatchEvent(new Event('focus')); document.dispatchEvent(new Event('visibilitychange')) })
          await check('foreground and page restore')
          await page.evaluate(() => window.fixture.router.push('/empty'))
          await page.locator('.trade-page').waitFor({ state: 'detached' })
          await page.evaluate(() => window.fixture.router.push('/trade'))
          await page.locator('#contract-quantity').waitFor()
          await check('leave and return to trade')
          await page.locator('#contract-quantity').fill('16.8')
          await buttons.first().scrollIntoViewIfNeeded()
          await page.screenshot({ path: path.join(output, `${edition}-${noVisualViewport ? 'no-viewport-api' : 'mobile'}.png`), fullPage: false })
          const bounds = await buttons.first().boundingBox()
          assert.ok(bounds && bounds.y >= 0 && bounds.y + bounds.height <= 844, 'Trading direction can be reached in the visible mobile viewport')
          assert.deepEqual(current.errors, [])
          assert.deepEqual(current.warnings, [])
          assert.deepEqual(current.writes, [], 'Interactions must not create orders or mutate funds')
          console.log(`${edition}, VisualViewport ${!noVisualViewport}: ${current.checks.length} checks passed`)
        } catch (error) {
          current.failure = error.message
          await page.screenshot({ path: path.join(output, `${edition}-failure.png`), fullPage: false })
          throw error
        } finally { fs.writeFileSync(path.join(output, 'evidence.json'), JSON.stringify(report, null, 2)); await page.close() }
      }
    }
    console.log(`PASS: trading action visibility and disabled states. Evidence: ${output}`)
  } finally { await browser.close(); await server.close() }
}

run().catch(error => { console.error(error); process.exitCode = 1 })
