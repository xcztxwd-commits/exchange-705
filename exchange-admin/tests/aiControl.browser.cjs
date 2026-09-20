// Run with the admin dev server on 5191. Every API request is intercepted; no live quotes or orders.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 1100 }, reducedMotion: 'reduce' })
    const calls = [], errors = []
    let state = { id: 1, enabled: false, running: false, restoring: false, available: true, randomOscillation: false, rawPrice: 90, currentPrice: 90, offset: 0,
      startPrice: null, targetPrice: null, durationSeconds: null, intensity: null, startedAt: null, completedAt: null, remainingSeconds: 0 }
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(() => {
      localStorage.setItem('admin_token', 'control-ui-test')
      localStorage.setItem('admin_user', JSON.stringify({ id: 1, role: 'super_admin', isSuperAdmin: true }))
    })
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url()), action = url.pathname.split('/').pop()
      let body = { success: true, data: {}, list: [] }
      if (url.pathname.includes('/ai-control/')) {
        if (action === 'symbols') body = [{ id: 1, symbol: 'TEST', name: '测试品种', quoteCurrency: 'USD', pricePrecision: 2, isEnabled: true }]
        else if (route.request().method() === 'POST') {
          const data = route.request().postDataJSON(); calls.push({ action, data })
          if (action === 'start' || action === 'restore') state = { ...state, ...data, running: true, enabled: true, restoring: action === 'restore',
            startedAt: Date.now(), remainingSeconds: data.durationSeconds, startPrice: state.currentPrice }
          if (action === 'stop' || action === 'manual') state = { ...state, running: false, restoring: false, randomOscillation: false,
            startPrice: null, targetPrice: null, durationSeconds: null, intensity: null, startedAt: null, completedAt: null, remainingSeconds: 0 }
          if (action === 'stop') state = { ...state, offset: 5, currentPrice: 95 }
          if (action === 'manual') state = { ...state, ...data, currentPrice: 90 + (data.enabled ? data.offset : 0) }
          body = state
        } else body = state
      }
      await route.fulfill({ json: body })
    })
    await page.goto(process.env.ADMIN_URL || 'http://127.0.0.1:5191/#/ai-control')
    const submit = page.getByRole('button', { name: '开始自动控盘', exact: true })
    await submit.waitFor()
    await page.getByText('跟随原始行情', { exact: true }).waitFor()
    const oscillation = page.getByRole('switch', { name: '开启随机震荡', exact: true })
    assert.equal(await oscillation.isChecked(), false)
    assert.equal(await page.locator('#control-intensity').isDisabled(), true)
    await oscillation.locator('..').click()
    assert.equal(await oscillation.isChecked(), true)
    await page.locator('#control-target').fill('100')
    await page.locator('#control-duration').fill('10')
    await page.locator('#control-intensity').fill('10')
    await Promise.all([page.waitForResponse(response => response.url().endsWith('/start')), submit.click()])
    assert.deepEqual(calls.at(-1), { action: 'start', data: { durationSeconds: 10, intensity: 10, targetPrice: 100, randomOscillation: true } })
    await page.getByText('正在前往目标价 · 剩余 10 秒', { exact: true }).waitFor()
    assert.equal(await submit.isDisabled(), true)
    await page.reload()
    await page.getByText('正在前往目标价 · 剩余 10 秒', { exact: true }).waitFor()
    assert.equal(await page.locator('#control-intensity').inputValue(), '10')
    assert.equal(await oscillation.isChecked(), true)
    await page.getByRole('button', { name: '停止任务并保留当前偏移', exact: true }).click()
    await page.getByText('固定偏移已开启', { exact: true }).waitFor()
    await page.locator('#control-target').fill('123.45')
    await page.waitForResponse(response => /\/ai-control\/1$/.test(response.url()) && response.request().method() === 'GET')
    assert.equal(await page.locator('#control-target').inputValue(), '123.45', 'polling must not overwrite an unfinished form')
    assert.equal(await oscillation.isChecked(), true, 'polling must not overwrite the draft switch')
    await page.getByText('渐进恢复', { exact: true }).click()
    assert.equal(await oscillation.isChecked(), false, 'restore has its own setting')
    await oscillation.locator('..').click()
    await page.locator('#control-duration').fill('20')
    await page.locator('#control-intensity').fill('7')
    await page.getByRole('button', { name: '按设定恢复原始行情', exact: true }).click()
    await page.getByText('正在恢复原始行情 · 剩余 20 秒', { exact: true }).waitFor()
    assert.deepEqual(calls.at(-1), { action: 'restore', data: { durationSeconds: 20, intensity: 7, randomOscillation: true } })
    await page.reload()
    await page.getByText('正在恢复原始行情 · 剩余 20 秒', { exact: true }).waitFor()
    assert.equal(await page.getByRole('radio', { name: '渐进恢复', exact: true }).isChecked(), true)
    assert.equal(await oscillation.isChecked(), true)
    assert.equal(await page.locator('#control-duration').inputValue(), '20')
    assert.equal(await page.locator('#control-intensity').inputValue(), '7')
    await page.waitForResponse(response => /\/ai-control\/1$/.test(response.url()) && response.request().method() === 'GET')
    const output = path.resolve(__dirname, '../../reports/price-control')
    fs.mkdirSync(output, { recursive: true })
    await page.screenshot({ path: path.join(output, 'admin-restore.png'), fullPage: true })
    await page.locator('#control-intensity').fill('')
    await oscillation.locator('..').click()
    assert.equal(await page.locator('#control-intensity').isDisabled(), true)
    await Promise.all([
      page.waitForResponse(response => response.url().endsWith('/restore'), { timeout: 5000 }),
      page.getByRole('button', { name: '按设定恢复原始行情', exact: true }).click()
    ])
    assert.deepEqual(calls.at(-1), { action: 'restore', data: { durationSeconds: 20, intensity: 1, randomOscillation: false } })
    state.available = false
    await page.reload()
    await page.getByText('行情暂不可用或已过期，等待有效报价；仍可一键恢复原始行情。', { exact: true }).waitFor()
    assert.equal(await oscillation.isChecked(), false)
    const reset = page.getByRole('button', { name: '一键恢复原始行情', exact: true })
    assert.equal(await reset.isEnabled(), true)
    await reset.click()
    await page.getByText('跟随原始行情', { exact: true }).waitFor()
    assert.deepEqual(calls.at(-1), { action: 'manual', data: { enabled: false, offset: 0 } })
    state.available = true
    await page.reload()
    await page.getByText('手动偏移', { exact: true }).click()
    await page.getByRole('switch').locator('..').click()
    await page.locator('#control-offset').fill('5')
    await page.getByRole('button', { name: '保存手动偏移', exact: true }).click()
    await page.getByText('固定偏移已开启', { exact: true }).waitFor()
    assert.deepEqual(calls.at(-1), { action: 'manual', data: { enabled: true, offset: 5 } })
    assert.deepEqual(errors, [])
    console.log('PASS: random oscillation on/off, intensity, target/restore persistence, polling, stop, immediate restore during quote outage, and manual offset')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
