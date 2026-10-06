// Real mobile/PC route wrappers, local API fixtures and intercepted external chat; no tenant writes or external traffic.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const targets = { mobile: process.env.MOBILE_URL, pc: process.env.PC_URL }
for (const url of Object.values(targets)) if (!url || !['127.0.0.1', 'localhost'].includes(new URL(url).hostname)) throw Error('MOBILE_URL and PC_URL must identify isolated local Vite servers')
const out = process.env.EVIDENCE_DIR || path.resolve(__dirname, '../reports/external-support-fullscreen-20261006')
const external = 'https://support.example.test/chat'
const chat = '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><body style="margin:0;height:100dvh;display:flex;flex-direction:column;font:16px system-ui"><header style="padding:20px;background:#008be8;color:white">FOREX EXCHANGE · 本地客服布局验证</header><main style="flex:1;min-height:0;overflow:auto;padding:20px;background:#f4f6f9">欢迎联系在线客服。<p>English · 中文 · 日本語</p></main><footer style="padding:14px;border-top:1px solid #ddd;display:flex;gap:12px"><textarea aria-label="消息" placeholder="输入消息" style="flex:1;min-width:0;resize:none;padding:10px"></textarea><button>发送</button></footer>'
fs.mkdirSync(out, { recursive: true })
;(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) })
  const results = []
  try {
    for (const [app, base] of Object.entries(targets)) {
      const context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: app === 'mobile', hasTouch: app === 'mobile' })
      const page = await context.newPage(), errors = [], requests = []
      let config = { mode: 'external', link: external, inboxEnabled: false, offline: '', userSound: '' }
      page.on('pageerror', error => errors.push(error.message))
      await context.addInitScript(() => { localStorage.setItem('token', 'QA'); localStorage.setItem('user', JSON.stringify({ id: 1, tenantId: 1 })); localStorage.setItem('locale', 'zh-TW') })
      await context.route('**/*', async route => {
        const url = new URL(route.request().url())
        if (url.origin === new URL(external).origin) return route.fulfill({ contentType: 'text/html; charset=utf-8', body: chat })
        if (url.hostname !== '127.0.0.1' && url.hostname !== 'localhost') return route.abort()
        if (url.pathname === '/__qa') return route.fulfill({ contentType: 'text/html; charset=utf-8', body: '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><div id="app"></div></body></html>' })
        if (!url.pathname.startsWith('/api/')) return route.continue()
        assert.equal(route.request().method(), 'GET', 'layout and navigation cannot create conversations or send messages')
        requests.push(url.pathname)
        if (url.pathname === '/api/user/support/config') return route.fulfill({ json: config })
        if (url.pathname === '/api/user/support/sessions') return route.fulfill({ json: [] })
        throw Error('Unexpected API: ' + url.pathname)
      })
      await page.goto(base + '/__qa')
      const mount = async (demo = false) => page.evaluate(async ({ app, demo }) => {
        window.__app?.unmount(); document.querySelector('#app').innerHTML = ''
        sessionStorage.setItem('account-mode:1:1', demo ? 'DEMO' : 'REAL')
        const main = await (await fetch('/src/main.ts')).text(), source = await (await fetch('/src/router/index.ts')).text()
        const dep = (text, name) => text.match(new RegExp('from ["\']([^"\']*' + name + '\\.js[^"\']*)["\']'))[1]
        const { createApp, h } = await import(dep(main, 'vue')), { createPinia, setActivePinia } = await import(dep(main, 'pinia'))
        const { createRouter, createMemoryHistory, RouterView } = await import(dep(source, 'vue-router'))
        const pinia = createPinia(); setActivePinia(pinia)
        const { useAuthStore } = await import('/src/store/auth.ts'); useAuthStore().load()
        const { useLocaleStore } = await import('/src/store/locale.ts'), locale = useLocaleStore(); locale.locale = 'zh-TW'
        const { default: View } = await import(app === 'mobile' ? '/src/views/CustomerService.vue' : '/src/views/SupportPage.vue')
        const { default: Banner } = await import('/src/components/AccountModeSwitch.vue')
        await import('/src/styles/global.scss?import')
        const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/previous', component: { render: () => h('p', 'Previous page') } }, { path: '/customer-service', component: View }] })
        await router.push('/previous'); await router.push('/customer-service'); await router.isReady()
        window.__app = createApp({ render: () => [h(Banner, { realPath: '/trade' }), h(RouterView)] }).use(pinia).use(router)
        window.__app.mount('#app'); window.__qa = { router, locale }
      }, { app, demo })
      const measure = async (demo = false) => {
        await page.locator('.external iframe').waitFor()
        const geometry = await page.evaluate(() => {
          const box = selector => { const rect = document.querySelector(selector)?.getBoundingClientRect(); return rect ? { x: rect.x, y: rect.y, width: rect.width, height: rect.height, right: rect.right, bottom: rect.bottom } : null }
          return { width: innerWidth, height: innerHeight, page: box('.support-page'), frame: box('iframe'), header: box('.support-page header'), link: box('.external-open'), banner: box('.account-mode-bar'), overflowX: document.documentElement.scrollWidth > innerWidth + 1, overflowY: document.documentElement.scrollHeight > innerHeight + 1 }
        })
        assert(Math.abs(geometry.frame.bottom - geometry.height) <= 1, 'no unused space below the iframe')
        assert(Math.abs(geometry.frame.y - geometry.header.bottom) <= 1, 'iframe starts directly below the header')
        assert.equal(geometry.frame.x, 0); assert(Math.abs(geometry.frame.width - geometry.width) <= 1, 'iframe fills the viewport width')
        assert.equal(geometry.overflowX, false); assert.equal(geometry.overflowY, false)
        assert(geometry.link.x > geometry.width / 2 && geometry.link.bottom <= geometry.header.bottom, 'new-window action is in the upper-right header')
        assert.equal(await page.locator('.support-page header .external-open').count(), 1)
        if (demo) { assert(geometry.banner.height >= 56); assert(Math.abs(geometry.page.y - geometry.banner.bottom) <= 1, 'demo account banner stays visible above the full-height chat') }
        else assert.equal(geometry.page.y, 0)
        return geometry
      }
      await mount()
      const dimensions = []
      for (const viewport of [{ width: 390, height: 844 }, { width: 320, height: 568 }, { width: 844, height: 390 }, { width: 768, height: 1024 }, { width: 1600, height: 1000 }]) {
        await page.setViewportSize(viewport); dimensions.push(await measure())
      }
      await page.setViewportSize(app === 'mobile' ? { width: 390, height: 844 } : { width: 1600, height: 1000 })
      const link = page.locator('.external-open'), frame = page.locator('iframe')
      assert.equal(await link.getAttribute('href'), external); assert.equal(await link.getAttribute('target'), '_blank')
      assert.equal(await link.getAttribute('rel'), 'noopener noreferrer'); assert.equal(await frame.getAttribute('referrerpolicy'), 'no-referrer')
      assert.equal(await frame.getAttribute('sandbox'), 'allow-forms allow-scripts allow-same-origin allow-popups')
      const [popup] = await Promise.all([context.waitForEvent('page'), link.click()]); await popup.waitForLoadState('domcontentloaded')
      assert.equal(popup.url(), external); assert.equal(await popup.evaluate(() => window.opener), null); await popup.close()
      await page.screenshot({ path: path.join(out, app + '-external.png') })
      for (const locale of ['ja', 'de', 'fr', 'en']) { await page.evaluate(locale => { window.__qa.locale.locale = locale }, locale); await measure() }
      await mount(true); await measure(true)
      await page.screenshot({ path: path.join(out, app + '-external-demo.png') })
      config = { ...config, mode: 'internal' }; await page.evaluate(() => { window.__qa.locale.locale = 'ja' })
      await page.locator('.support-page:not(.external-mode) .welcome').waitFor(); assert.equal(await frame.count(), 0); assert.equal(await link.count(), 0)
      assert.equal(await page.locator('#app').evaluate(el => getComputedStyle(el).display), 'block', 'external-only viewport sizing is removed when the service mode changes')
      config = { ...config, mode: 'off' }; await page.evaluate(() => { window.__qa.locale.locale = 'en' }); await page.locator('.mode-notice').waitFor()
      config = { ...config, mode: 'external', link: '' }; await page.evaluate(() => { window.__qa.locale.locale = 'zh-TW' }); await page.locator('.external > p').waitFor()
      assert.equal(await frame.count(), 0); assert.equal(await link.count(), 0)
      await page.locator('.back').click(); await page.waitForFunction(() => window.__qa.router.currentRoute.value.path === '/previous')
      assert.deepEqual(errors, [])
      results.push({ app, dimensions, checks: ['full-width and full-height at five viewport sizes', 'header action opens a safe new window', 'iframe security attributes unchanged', 'localized header fits', 'demo banner keeps its actual height', 'internal/off/missing URL/back navigation preserved'], errors, requests })
      await context.close()
    }
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify(results, null, 2)); console.log('PASS mobile + PC external support: full remaining viewport, upper-right action, safe popup, five viewport sizes, localization, demo banner, internal/off/missing URL/back regression')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
