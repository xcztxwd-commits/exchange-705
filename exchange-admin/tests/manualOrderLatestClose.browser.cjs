const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')
const assert = require('node:assert/strict')

;(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 1400 } })
    const errors = [], requests = []
    page.on('pageerror', error => errors.push(error.message))
    await page.route('**/__qa', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><div id="app"></div>' }))
    await page.route('**/api/**', route => {
      const path = new URL(route.request().url()).pathname
      if (path.endsWith('/manual/context')) return route.fulfill({ json: route.request().url().includes('userId=')
        ? { account: { available: 1000 } }
        : { users: [{ id: 1, email: 'qa@local.invalid' }], symbols: [{ symbol: 'FIXTUREUSD', name: 'fixture', source_category: 'Forex' }], timezone: 'UTC' } })
      if (path.endsWith('/manual/generate')) {
        const payload = route.request().postDataJSON(); requests.push(payload)
        if (payload.targetClosePrice === '200') return route.fulfill({ status: 400, json: { message: '最近有效平仓分钟之前 7 天没有符合条件的开仓分钟' } })
        return route.fulfill({ json: {
          request: { specVersion: null, quantityUnitType: null, userId: 1, symbol: 'FIXTUREUSD', timezone: 'UTC', openLocal: '2026-09-28T10:00', closeLocal: '2026-09-28T14:00', openOffset: 'Z', closeOffset: 'Z', side: 'BUY', leverage: Number(payload.leverage || 100), driver: 'QUANTITY', input: 10, targetNet: null, walletEnabled: false, historyEnabled: false },
          previewToken: 'fixture-preview', quotes: { openPrice: 100, closePrice: 110, source: 'fixture' },
          calculation: { quantity: 10, percent: 12, profit: 100, fee: 20, net: 80, margin: 100 }, walletBefore: 1000, walletAfter: 1000,
          openUtc: '2026-09-28T10:00:00Z', closeUtc: '2026-09-28T14:00:00Z', openOffset: 'Z', closeOffset: 'Z', lotSize: 1, feePerLot: 2,
          generation: { leverageTarget: payload.leverage || null, leverageErrorPercent: 0, closeAutomaticallySelected: true, closePriceTarget: 112, closePriceErrorPercent: 1.785714, quantityTarget: payload.quantity || null, quantityErrorPercent: 0, percentTarget: payload.percent || null, percentErrorPercent: 0, durationMinutes: 240, targetNet: payload.targetNet || null, errorPercent: 0, warning: '隔离测试', from: '2026-09-21T14:00:00Z', to: '2026-09-28T14:01:00Z' }
        } })
      }
      errors.push(`unexpected API: ${path}`)
      return route.abort()
    })
    await page.goto('http://127.0.0.1:5198/__qa')
    await page.evaluate(async () => {
      sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({token:'qa-local-fixture',user:{tenantId:1,userType:'admin'},mode:'admin'}))
      const source = await (await fetch('/src/components/ManualContractOrder.vue')).text()
      const dependency = name => source.match(new RegExp(`from "([^"]*${name}\\.js[^"]*)"`))[1]
      const { createApp } = await import(dependency('vue'))
      const element = await import(dependency('element-plus'))
      const authSource = await (await fetch('/src/store/auth.ts')).text()
      const { createPinia } = await import(authSource.match(/from "([^"]*pinia\.js[^"]*)"/)[1])
      const { default: ManualOrder } = await import('/src/components/ManualContractOrder.vue')
      await import('/node_modules/element-plus/dist/index.css')
      window.manual = createApp(ManualOrder).use(createPinia()).use(element.default).mount('#app')
      await window.manual.open()
    })
    await page.locator('.manual-form .el-select').nth(0).click()
    await page.getByText('1 · qa@local.invalid', { exact: true }).click()
    await page.locator('.manual-form .el-select').nth(1).click()
    await page.getByText('FIXTUREUSD · fixture', { exact: true }).click()
    await page.getByLabel('目标平仓价').fill('112')
    await page.getByRole('button', { name: '一键生成' }).click()
    await page.getByText(/平仓取最近有效分钟/).waitFor()
    assert.deepEqual(Object.keys(requests[0]).sort(), ['historyEnabled', 'symbol', 'targetClosePrice', 'timezone', 'userId', 'walletEnabled', 'specVersion', 'quantityUnitType', 'leverage'].sort())
    assert.equal(requests[0].targetClosePrice, '112'); assert.equal(requests[0].leverage, '100')
    assert.equal(await page.getByLabel('目标平仓价').inputValue(), '112')
    await page.getByLabel('目标平仓价').fill('200')
    await page.getByRole('button', { name: '一键生成' }).click()
    await page.getByText('最近有效平仓分钟之前 7 天没有符合条件的开仓分钟').waitFor()
    assert.equal(await page.getByRole('button', { name: '确认创建已平仓单' }).isDisabled(), true)
    await page.getByLabel('目标平仓价').fill('112')
    await page.locator('.manual-form .el-select').nth(3).click()
    await page.getByText('做多', { exact: true }).click()
    await page.getByLabel('杠杆').fill('10')
    await page.getByLabel('数量（手）', { exact: true }).fill('10')
    await page.getByRole('textbox', { name: '仓位比例' }).fill('12')
    await page.getByRole('textbox', { name: '目标净收益' }).fill('80')
    await page.getByRole('button', { name: '一键生成' }).click()
    await page.getByText(/目标净收益 80，实际 80/).waitFor()
    assert.deepEqual(requests[2], { userId: 1, symbol: 'FIXTUREUSD', timezone: 'UTC', walletEnabled: false, historyEnabled: false, side: 'BUY', leverage: '10', quantity: '10', percent: '12', targetNet: '80', targetClosePrice: '112', specVersion: null, quantityUnitType: null })
    assert.equal(await page.getByLabel('杠杆', { exact: true }).inputValue(), '10')
    await page.getByText(/固定杠杆 10×/).waitFor()
    assert.equal(await page.getByRole('button', { name: '确认创建已平仓单' }).isEnabled(), true)
    await page.screenshot({ path: process.env.TEMP + '/manual-generation-tolerance.png', fullPage: true })
    assert.equal(await page.locator('vite-error-overlay').count(), 0)
    assert.equal(requests.length, 3)
    assert.deepEqual(errors, [])
    console.log('PASS browser: leverage fixed exactly, default 100x, original target preserved, confirmation enabled; price-only, mixed targets, latest close, seven-day failure prompt; no order create')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exit(1) })
