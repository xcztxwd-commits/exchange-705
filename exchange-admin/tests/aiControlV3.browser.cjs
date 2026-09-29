// Isolated V3 preview regression. Every API call is mocked; no task is started.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage()
    const errors = []
    let starts = 0, previews = 0
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(() => {
      localStorage.setItem('admin_token', 'isolated-v3-test')
      localStorage.setItem('admin_user', JSON.stringify({ id: 1, role: 'super_admin', isSuperAdmin: true }))
    })
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url())
      if (url.pathname.endsWith('/ai-control/symbols')) return route.fulfill({ json: [{ id: 1, symbol: 'TEST', pricePrecision: 2, isEnabled: true }] })
      if (url.pathname.endsWith('/ai-control/1/history')) return route.fulfill({ json: [] })
      if (url.pathname.endsWith('/ai-control/1')) return route.fulfill({ json: {
        id: 1, virtualTrading: true, v3Enabled: true, available: true, canStart: true,
        enabled: false, running: false, restoring: false, randomOscillation: true,
        rawPrice: 100000, currentPrice: 100000, offset: 0,
        durationSeconds: 60, intensity: 10, targetPrice: 100060, remainingSeconds: 0
      } })
      if (url.pathname.endsWith('/ai-control/1/preview')) {
        ++previews
        const request = route.request().postDataJSON()
        if (request.intensity === 1) await new Promise(resolve => setTimeout(resolve, 1000))
        const feasible = request.intensity === 10
        return route.fulfill({ json: {
          feasible, errorCode: feasible ? undefined : 'TARGET_AMPLITUDE_INFEASIBLE', message: feasible ? undefined : '参数冲突',
          startPrice: '100000.00', minAmount: feasible ? '9.00' : '0.90', maxAmount: feasible ? '11.00' : '1.10',
          amountRandom: true, tiers: [{ intensity: request.intensity, minAmount: feasible ? '9.00' : '0.90', maxAmount: feasible ? '11.00' : '1.10', feasible }],
          summary: feasible ? { points: 61, minPrice: '99990.00', maxPrice: '100070.00', maxStep: '10.99', averageStep: '10.00',
            segmentAverages: ['10.00', '10.00', '10.00'], rolling10Min: '9.99', rolling10Max: '10.01', rolling30Min: '9.99', rolling30Max: '10.01' } : undefined
        } })
      }
      if (url.pathname.endsWith('/ai-control/1/start')) { ++starts; return route.fulfill({ status: 500, json: { message: 'unexpected start' } }) }
      return route.fulfill({ json: {} })
    })
    await page.goto(process.env.ADMIN_URL || 'http://127.0.0.1:5197/#/ai-control')
    const start = page.getByRole('button', { name: '开始自动控盘', exact: true })
    await page.getByText('预览起点 100000.00；当前档单秒幅度 9.00～11.00。', { exact: false }).waitFor()
    assert.equal(await start.isDisabled(), false)
    assert.ok(await page.getByText('均衡随机 V3：强度决定每秒绝对涨跌额的固定范围。', { exact: false }).isVisible())
    const lowPreview = page.waitForRequest(req => req.url().endsWith('/ai-control/1/preview') && req.postDataJSON().intensity === 1)
    await page.locator('#control-intensity').fill('1'); await page.locator('#control-intensity').press('Tab')
    await lowPreview
    await page.locator('#control-intensity').fill('10'); await page.locator('#control-intensity').press('Tab')
    await page.getByText('预览起点 100000.00；当前档单秒幅度 9.00～11.00。', { exact: false }).waitFor()
    await page.waitForTimeout(1200)
    assert.equal(await start.isDisabled(), false, 'late infeasible response must not replace latest preview')
    assert.equal(await page.getByText('TARGET_AMPLITUDE_INFEASIBLE:', { exact: false }).count(), 0)
    assert.equal(starts, 0)
    assert.ok(previews >= 3)
    assert.deepEqual(errors, [])
    console.log(JSON.stringify({ ok: true, previews, starts, checks: ['fixed interval', 'balance summary', 'stale response guard'] }))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
