// Isolated UI regression: all API calls are intercepted; never submits a control action.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage()
    const errors = []
    let statusReads = 0, historyReads = 0, failStatus = false, writes = 0
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(() => {
      localStorage.setItem('admin_token', 'isolated-timeout-test')
      localStorage.setItem('admin_user', JSON.stringify({ id: 1, role: 'super_admin', isSuperAdmin: true }))
    })
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url())
      if (route.request().method() === 'POST' && url.pathname.includes('/ai-control/')) ++writes
      let body = { list: [], data: {} }
      if (url.pathname.endsWith('/ai-control/symbols')) body = [{ id: 1, symbol: 'TEST', pricePrecision: 3, isEnabled: true }]
      else if (url.pathname.endsWith('/ai-control/1/history')) {
        ++historyReads
        if (historyReads === 1) {
          await new Promise(resolve => setTimeout(resolve, 11000))
          return route.abort().catch(() => {})
        }
        body = []
      } else if (url.pathname.endsWith('/ai-control/1')) {
        ++statusReads
        if (failStatus) return route.fulfill({ status: 503, json: { message: 'fixture unavailable' } })
        body = { id: 1, virtualTrading: true, available: true, canStart: true, enabled: false, running: false,
          rawPrice: 156.998, currentPrice: 156.998, offset: 0, durationSeconds: 600, intensity: 7, targetPrice: 157 }
      }
      await route.fulfill({ json: body })
    })
    await page.goto(process.env.ADMIN_URL || 'http://127.0.0.1:5197/#/ai-control')
    const submit = page.getByRole('button', { name: '开始自动控盘', exact: true })
    await submit.waitFor()
    await page.waitForFunction(() => !document.querySelector('#control-target')?.disabled)
    await page.locator('#control-target').fill('160')
    await page.getByText('任务历史请求超时，正在自动重试', { exact: true }).waitFor({ timeout: 15000 })
    assert.ok(statusReads >= 7, 'slow history must not block status polling')
    assert.equal(await submit.isDisabled(), false, 'history error must not disable healthy status controls')
    assert.equal(Number(await page.locator('#control-target').inputValue()), 160, 'polling preserves draft')
    await page.getByText('任务历史请求超时，正在自动重试', { exact: true }).waitFor({ state: 'hidden' })
    assert.ok(historyReads >= 2, 'history retries independently')
    failStatus = true
    await page.getByText('控盘状态加载失败：fixture unavailable', { exact: true }).waitFor()
    assert.equal(await submit.isDisabled(), true, 'stale status must disable actions')
    failStatus = false
    await page.getByText('控盘状态加载失败：fixture unavailable', { exact: true }).waitFor({ state: 'hidden' })
    assert.equal(await submit.isDisabled(), false)
    failStatus = true
    await page.reload()
    await page.getByText('控盘状态加载失败：fixture unavailable', { exact: true }).waitFor()
    failStatus = false
    await page.waitForFunction(() => document.querySelector('#control-duration')?.value === '600')
    assert.equal(await page.locator('#control-intensity').inputValue(), '7', 'initial failure must retain pending form reset')
    assert.equal(Number(await page.locator('#control-target').inputValue()), 157)
    assert.equal(writes, 0)
    assert.deepEqual(errors, [])
    console.log(JSON.stringify({ ok: true, statusReads, historyReads, writes, checks: ['history timeout isolation', 'independent retry', 'draft preservation', 'stale status guard', 'initial failure recovery'] }))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
