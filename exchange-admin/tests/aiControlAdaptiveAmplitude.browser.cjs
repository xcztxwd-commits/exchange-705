// Isolated adaptive-amplitude regression. Every API, including startup, is mocked.
// Run against npm run dev; ADMIN_URL, PLAYWRIGHT_PATH and OUTPUT_DIR are optional.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const path = require('node:path')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage({ viewport: { width: 1400, height: 1100 } })
    page.setDefaultTimeout(10000)
    const errors = [], previews = [], starts = [], unexpected = []
    let scenario = 'ok', previewDelay = 1200
    const symbols = [{ id: 1, symbol: 'JPY=X', displayName: 'USD/JPY', pricePrecision: 3, start: 157.575, target: 157.588, duration: 10 },
      { id: 2, symbol: 'EURUSD=X', displayName: 'EUR/USD', pricePrecision: 5, start: 1.08456, target: 1.08756, duration: 300 }]
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ mode: 'admin', token: 'QA',
      loginSessionId: '00000000-0000-4000-8000-000000000001', user: { id: 1, tenantId: 1, userType: 'admin', role: 'admin' } })))
    await page.route('**/__qa', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><meta charset="utf-8"><div id="app"></div>' }))
    await page.route('**/api/**', async route => {
      const request = route.request(), url = new URL(request.url())
      assert.equal(request.headers().authorization, 'Bearer QA')
      if (url.pathname === '/api/admin/menus/current') return route.fulfill({ json: { success: true, menus: [], groups: [], actions: { ai_control: ['view', 'start', 'preview', 'restore', 'manual', 'stop', 'random'] } } })
      if (url.pathname.endsWith('/ai-control/symbols')) return route.fulfill({ json: symbols.map(symbol => ({ ...symbol, category: 'Forex', isEnabled: true })) })
      const id = Number(url.pathname.match(/\/ai-control\/(\d+)/)?.[1]), symbol = symbols.find(symbol => symbol.id === id)
      if (!symbol) { unexpected.push(url.pathname); return route.fulfill({ status: 500, json: { message: 'unexpected request' } }) }
      if (url.pathname.endsWith('/history')) return route.fulfill({ json: [] })
      if (url.pathname.endsWith('/formula')) return route.fulfill({ json: { stepFormula: 'base * intensity' } })
      if (url.pathname.endsWith('/' + id)) return route.fulfill({ json: {
        id, virtualTrading: true, v3Enabled: true, v4Enabled: true, available: true, canStart: true,
        enabled: false, running: false, restoring: false, randomOscillation: true, rawPrice: null, currentPrice: symbol.target, offset: 0,
        startBasis: { price: symbol.start, source: 'COMPLETED_CANDLE', timestamp: 1 },
        durationSeconds: symbol.duration, intensity: 1, targetPrice: symbol.target, remainingSeconds: 0
      } })
      if (url.pathname.endsWith('/preview')) {
        const body = request.postDataJSON(), selectedScenario = scenario, precision = symbol.pricePrecision, tick = 10 ** -precision
        previews.push({ id, ...body })
        const baseTicks = Math.ceil(Math.max(symbol.start * 0.00001, Math.abs(body.targetPrice - symbol.start) / Math.max(1, body.durationSeconds - 2), tick * 10) / tick - 1e-8)
        const typicalTicks = baseTicks * body.intensity, fixed = amount => amount.toFixed(precision)
        const corridor = body.deviationBandMode === 'MANUAL' ? Math.floor(symbol.start * Number(body.deviationBandPercent) / 100 / tick + 1e-8) * tick : Math.min(symbol.start, typicalTicks * tick * 4)
        const tooShort = body.durationSeconds === 1, narrow = corridor < tick, stale = selectedScenario === 'stale' && body.durationSeconds === 11
        const failed = tooShort || narrow || stale || selectedScenario === 'planFailure'
        if (stale) await sleep(800); else if (!failed) await sleep(previewDelay)
        const errorCode = tooShort ? 'TARGET_AMPLITUDE_INFEASIBLE' : narrow ? 'CORRIDOR_PRECISION_UNREPRESENTABLE' : 'PLAN_SEARCH_EXHAUSTED'
        return route.fulfill({ json: {
          algorithmVersion: 4, mappingVersion: 4, amplitudeMode: 'ADAPTIVE', feasible: !failed,
          ...(failed ? { errorCode, message: narrow ? '偏差带小于最小跳动；请增加偏差带百分比后重新预览' : '当前参数无法生成轨迹；请增加执行时间或调整波动强度后重新预览' } : {}),
          startPrice: fixed(symbol.start), baseAmount: fixed(baseTicks * tick), gapPerSecond: String(Math.abs(body.targetPrice - symbol.start) / body.durationSeconds), priceTick: fixed(tick),
          typicalAmount: fixed(typicalTicks * tick), minAmount: fixed(Math.ceil(typicalTicks * 0.9 - 1e-8) * tick), maxAmount: fixed(Math.floor(typicalTicks * 1.1 + 1e-8) * tick), amountRandom: true,
          deviationBandPercent: body.deviationBandMode === 'MANUAL' ? body.deviationBandPercent : String(Number((corridor / symbol.start * 100).toFixed(8))), corridorAmount: fixed(corridor),
          tiers: Array.from({ length: 10 }, (_, i) => ({ intensity: i + 1, feasible: !tooShort && !narrow,
            minAmount: fixed(Math.ceil(baseTicks * (i + 1) * 0.9 - 1e-8) * tick), maxAmount: fixed(Math.floor(baseTicks * (i + 1) * 1.1 + 1e-8) * tick) })),
          ...(!failed ? { summary: { points: body.durationSeconds + 1, minPrice: fixed(symbol.start), maxPrice: fixed(body.targetPrice), maxStep: fixed(typicalTicks * tick), averageStep: fixed(typicalTicks * tick),
            segmentAverages: [fixed(typicalTicks * tick), fixed(typicalTicks * tick), fixed(typicalTicks * tick)] } } : {})
        } })
      }
      if (url.pathname.endsWith('/start')) {
        const body = request.postDataJSON(); starts.push({ id, ...body })
        return route.fulfill({ status: 202, json: { commandId: 'mock-only', requestKey: body.requestKey, symbolId: id, state: 'FAILED', message: '仅测试回执，未启动任务' } })
      }
      unexpected.push(url.pathname); return route.fulfill({ status: 500, json: { message: 'unexpected request' } })
    })
    const firstPreview = page.waitForRequest(request => request.url().endsWith('/ai-control/1/preview'))
    await page.goto((process.env.ADMIN_URL || 'http://127.0.0.1:17059').replace(/\/$/, '') + '/__qa')
    await page.evaluate(async () => {
      const source = await (await fetch('/src/views/AiControl.vue')).text(), auth = await (await fetch('/src/store/auth.ts')).text()
      const dep = (s, n) => s.match(new RegExp('from ["\']([^"\']*' + n + '\\.js[^"\']*)["\']'))[1]
      const { createApp } = await import(dep(source, 'vue')), element = await import(dep(source, 'element-plus'))
      const { createPinia, setActivePinia } = await import(dep(auth, 'pinia'))
      const pinia = createPinia(); setActivePinia(pinia)
      const { loadAccess, permissionDirective } = await import('/src/utils/access.ts'); await loadAccess()
      const { default: Component } = await import('/src/views/AiControl.vue'), { default: AdminTable } = await import('/src/components/AdminTable.ts')
      const { TABLE_PREFERENCES } = await import('/src/utils/tablePreferences.ts'); await import('/node_modules/element-plus/dist/index.css')
      const app = createApp(Component).use(pinia).use(element.default)
      app.directive('permission', permissionDirective); app.component('AdminTable', AdminTable)
      app.provide(TABLE_PREFERENCES, { identityKey: () => 'QA', load: async () => [], save: async () => {} }); app.mount('#app')
    })
    const start = page.getByRole('button', { name: '开始自动控盘', exact: true })
    const intensity = page.locator('#control-intensity'), target = page.locator('#control-target'), duration = page.locator('#control-duration'), band = page.locator('#control-band-percent')
    const fill = async (input, value) => { await input.fill(value); await input.press('Tab') }
    const waitReady = () => page.waitForFunction(() => [...document.querySelectorAll('button')].some(button => button.textContent.trim() === '开始自动控盘' && !button.disabled))
    const waitBase = amount => page.getByText('自适应1档基础幅度 ' + amount + '；', { exact: false }).waitFor()
    await firstPreview; assert.equal(await start.isDisabled(), true, 'screening cannot authorize startup before full preview returns')
    await waitBase('0.010'); await waitReady()
    assert.equal(await intensity.inputValue(), '1'); assert.equal(Number(await target.inputValue()), 157.588); assert.equal(await duration.inputValue(), '10')
    assert.equal(await page.getByRole('checkbox', { name: '自动匹配可行强度' }).count(), 0)
    assert.equal(starts.length, 0); previewDelay = 0

    await fill(target, '157.975'); await waitBase('0.050'); await waitReady()
    await fill(duration, '30'); await waitBase('0.015'); await waitReady()
    await fill(intensity, '3'); await waitReady()
    assert.equal(previews.at(-1).intensity, 3); assert.equal(previews.at(-1).durationSeconds, 30); assert.equal(previews.at(-1).targetPrice, 157.975)
    await fill(band, '0.0001')
    await page.locator('.el-alert--warning').filter({ hasText: '增加偏差带百分比' }).waitFor()
    assert.equal(await start.isDisabled(), true); await fill(intensity, '1')
    await page.waitForTimeout(450); assert.equal(await band.inputValue(), '0.0001', 'manual band is never widened')
    await page.getByRole('button', { name: '恢复自动', exact: true }).click(); await fill(duration, '1')
    await page.locator('.el-alert--warning').filter({ hasText: '增加执行时间' }).waitFor()
    assert.equal(await intensity.inputValue(), '1'); assert.equal(await duration.inputValue(), '1'); assert.equal(await start.isDisabled(), true)
    await fill(duration, '30'); await waitReady()

    scenario = 'planFailure'; await fill(intensity, '2')
    await page.locator('.el-alert--warning').filter({ hasText: 'PLAN_SEARCH_EXHAUSTED' }).waitFor()
    const before = previews.length; await page.waitForTimeout(1500)
    assert.equal(previews.length, before); assert.equal(await intensity.inputValue(), '2'); assert.equal(await start.isDisabled(), true)
    scenario = 'stale'
    const stale = page.waitForRequest(request => request.url().endsWith('/preview') && request.postDataJSON().durationSeconds === 11)
    await fill(duration, '11'); await stale; await fill(duration, '30'); await waitReady(); await page.waitForTimeout(1000)
    assert.equal(await start.isDisabled(), false, 'stale failure cannot replace current full preview')
    scenario = 'ok'; await fill(target, '157.588'); await fill(duration, '10'); await fill(intensity, '1'); await waitBase('0.010'); await waitReady()
    if (process.env.OUTPUT_DIR) await page.screenshot({ path: path.join(process.env.OUTPUT_DIR, 'adaptive-jpy-tier-one.png'), fullPage: true })
    await page.getByRole('combobox').first().click(); await page.getByRole('option', { name: 'EUR/USD', exact: true }).click()
    await waitBase('0.00010'); await waitReady()
    assert.equal(await intensity.inputValue(), '1'); assert.equal(previews.at(-1).id, 2); assert.equal(previews.at(-1).durationSeconds, 300)
    await page.setViewportSize({ width: 390, height: 844 })
    assert.equal(await intensity.isVisible(), true, 'mobile intensity input remains visible')
    assert.equal(await intensity.inputValue(), '1')
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1))
    if (process.env.OUTPUT_DIR) await page.screenshot({ path: path.join(process.env.OUTPUT_DIR, 'adaptive-mobile.png'), fullPage: true })
    await page.setViewportSize({ width: 1400, height: 1100 })
    await page.getByRole('combobox').first().click(); await page.getByRole('option', { name: 'USD/JPY', exact: true }).click(); await waitBase('0.010'); await waitReady()
    assert.equal(starts.length, 0, 'recalibration never starts a task')
    await start.click(); await page.locator('.el-alert--error').filter({ hasText: '仅测试回执，未启动任务' }).waitFor()
    assert.equal(starts.length, 1); assert.equal(starts[0].intensity, 1); assert.equal(starts[0].targetPrice, 157.588); assert.equal(starts[0].durationSeconds, 10)
    assert.equal(starts[0].stepFormula, 'base * intensity'); assert.equal(starts[0].deviationBandMode, 'AUTO')
    assert.deepEqual(errors, []); assert.deepEqual(unexpected, [])
    console.log('PASS: adaptive JPY/EUR tier 1, price-gap/time recalibration, selected intensity unchanged, slow full-preview gate, manual-band protection, infeasible-duration advice, full-generation failure, stale replies, mobile layout, mock-only explicit startup')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
