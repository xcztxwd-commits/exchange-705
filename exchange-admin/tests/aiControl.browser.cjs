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
    let tasks = []
    let state = { virtualTrading: true, randomMarketEnabled: false, randomMarketBasePrice: null, id: 1, enabled: false, running: false, restoring: false, available: true, randomOscillation: false, rawPrice: 90, currentPrice: 90, offset: 0,
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
        else if (action === 'history') body = tasks
        else if (action === 'replace') {
          calls.push({ action, data: null })
          tasks = tasks.map(task => ({ ...task, historyReplacedAt: task.historyReplacedAt || Date.now() }))
          body = tasks[0]
        }
        else if (route.request().method() === 'POST') {
          const data = route.request().postDataJSON(); calls.push({ action, data })
          if (action === 'start' || action === 'restore') state = { ...state, ...data, running: true, enabled: true, restoring: action === 'restore',
            startedAt: Date.now(), remainingSeconds: data.durationSeconds, startPrice: state.currentPrice }
          if (action === 'stop' || action === 'manual') state = { ...state, running: false, restoring: false, randomOscillation: false,
            startPrice: null, targetPrice: null, durationSeconds: null, intensity: null, startedAt: null, completedAt: null, remainingSeconds: 0 }
          if (action === 'stop') state = { ...state, offset: 5, currentPrice: 95 }
          if (action === 'manual') state = { ...state, ...data, currentPrice: 90 + (data.enabled ? data.offset : 0) }
          if (action === 'random-market') state = { ...state, randomMarketEnabled: data.enabled, randomMarketBasePrice: data.basePrice ?? state.randomMarketBasePrice, available: data.enabled || state.available }
          body = state
        } else body = state
      }
      await route.fulfill({ json: body })
    })
    await page.goto(process.env.ADMIN_URL || 'http://127.0.0.1:5191/#/ai-control')
    if (process.env.EXPECT_INSECURE === '1') {
      assert.equal(await page.evaluate(() => window.isSecureContext), false)
      assert.equal(await page.evaluate(() => typeof crypto.randomUUID), 'undefined')
    }
    const submit = page.getByRole('button', { name: '开始自动控盘', exact: true })
    await submit.waitFor()
    await page.getByText('跟随原始行情', { exact: true }).waitFor()
    const oscillation = page.getByRole('switch', { name: '开启随机震荡', exact: true })
    assert.equal(await oscillation.isChecked(), false)
    assert.equal(await page.locator('#control-intensity').isDisabled(), false)
    await page.locator('#control-target').fill('100')
    await page.locator('#control-intensity').fill('8')
    await Promise.all([page.waitForResponse(response => response.url().endsWith('/start')), submit.click()])
    assert.equal(calls.at(-1).data.randomOscillation, false)
    assert.equal(calls.at(-1).data.intensity, 8)
    assert.match(calls.at(-1).data.requestKey, /^[0-9a-f-]{36}$/)
    await page.getByRole('button', { name: '停止任务并保存历史', exact: true }).click()
    await oscillation.locator('..').click()
    assert.equal(await oscillation.isChecked(), true)
    await page.locator('#control-target').fill('100')
    await page.locator('#control-duration').fill('10')
    await page.locator('#control-intensity').fill('10')
    await Promise.all([page.waitForResponse(response => response.url().endsWith('/start')), submit.click()])
    assert.deepEqual(calls.at(-1), { action: 'start', data: { durationSeconds: 10, intensity: 10, targetPrice: 100, randomOscillation: true, requestKey: calls.at(-1).data.requestKey } })
    assert.match(calls.at(-1).data.requestKey, /^[0-9a-f-]{36}$/)
    await page.getByText('正在前往目标价 · 剩余 10 秒', { exact: true }).waitFor()
    assert.equal(await submit.isDisabled(), true)
    await page.reload()
    await page.getByText('正在前往目标价 · 剩余 10 秒', { exact: true }).waitFor()
    assert.equal(await page.locator('#control-intensity').inputValue(), '10')
    assert.equal(await oscillation.isChecked(), true)
    await page.getByRole('button', { name: '停止任务并保存历史', exact: true }).click()
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
    assert.deepEqual(calls.at(-1), { action: 'restore', data: { durationSeconds: 20, intensity: 7, randomOscillation: true, requestKey: calls.at(-1).data.requestKey } })
    await page.reload()
    await page.getByText('正在恢复原始行情 · 剩余 20 秒', { exact: true }).waitFor()
    assert.equal(await page.getByRole('radio', { name: '渐进恢复', exact: true }).isChecked(), true)
    assert.equal(await oscillation.isChecked(), true)
    assert.equal(await page.locator('#control-duration').inputValue(), '20')
    assert.equal(await page.locator('#control-intensity').inputValue(), '7')
    await page.waitForResponse(response => /\/ai-control\/1$/.test(response.url()) && response.request().method() === 'GET')
    const output = path.join(require('node:os').tmpdir(), '705-price-control')
    fs.mkdirSync(output, { recursive: true })
    await page.screenshot({ path: path.join(output, 'admin-restore.png'), fullPage: true })
    await page.locator('#control-intensity').fill('')
    await oscillation.locator('..').click()
    assert.equal(await page.locator('#control-intensity').isDisabled(), false)
    const requestsBeforeInvalid = calls.length
    await page.getByRole('button', { name: '按设定恢复原始行情', exact: true }).click()
    await page.getByText('时长需为 1–86400 秒，波动强度需为 1–10', { exact: true }).waitFor()
    assert.equal(calls.length, requestsBeforeInvalid)
    await page.locator('#control-intensity').fill('6')
    await Promise.all([
      page.waitForResponse(response => response.url().endsWith('/restore'), { timeout: 5000 }),
      page.getByRole('button', { name: '按设定恢复原始行情', exact: true }).click()
    ])
    assert.deepEqual(calls.at(-1), { action: 'restore', data: { durationSeconds: 20, intensity: 6, randomOscillation: false, requestKey: calls.at(-1).data.requestKey } })
    state.available = false
    await page.reload()
    await page.getByText('原始行情异常，交易仍不可用；存在有效历史起点时可启动目标控盘。', { exact: true }).waitFor()
    assert.equal(await oscillation.isChecked(), false)
    const reset = page.getByRole('button', { name: '一键恢复原始行情', exact: true })
    assert.equal(await reset.isEnabled(), true)
    await reset.click()
    await page.getByText('跟随原始行情', { exact: true }).waitFor()
    assert.deepEqual(calls.at(-1), { action: 'manual', data: { enabled: false, offset: 0 } })
    state.available = true
    await page.reload()
    await page.getByText('手动偏移', { exact: true }).click()
    await page.getByRole('switch', { name: '启用控盘', exact: true }).locator('..').click()
    await page.locator('#control-offset').fill('5')
    await page.getByRole('button', { name: '保存手动偏移', exact: true }).click()
    await page.getByText('固定偏移已开启', { exact: true }).waitFor()
    assert.deepEqual(calls.at(-1), { action: 'manual', data: { enabled: true, offset: 5 } })
    await page.getByRole('button', { name: '一键恢复原始行情', exact: true }).click()
    await page.getByText('跟随原始行情', { exact: true }).waitFor()
    const randomMarket = page.getByRole('switch', { name: '随机行情', exact: true })
    await randomMarket.locator('..').click()
    await page.getByText('随机行情已开启 · 每秒更新 · 虚拟资金结算', { exact: true }).waitFor()
    assert.deepEqual(calls.at(-1), { action: 'random-market', data: { enabled: true } })
    await page.reload()
    await page.getByText('随机行情已开启 · 每秒更新 · 虚拟资金结算', { exact: true }).waitFor()
    assert.equal(await randomMarket.isChecked(), true)
    assert.equal(await submit.isEnabled(), true)
    await submit.click()
    await page.getByRole('button', { name: '停止任务并保存历史', exact: true }).waitFor()
    assert.equal(await randomMarket.isChecked(), true)
    await page.getByRole('button', { name: '停止任务并保存历史', exact: true }).click()
    await page.getByText('随机行情 + 指定偏移 · 虚拟资金结算', { exact: true }).waitFor()
    await page.getByRole('button', { name: '取消指定并继续随机', exact: true }).click()
    await page.getByText('随机行情已开启 · 每秒更新 · 虚拟资金结算', { exact: true }).waitFor()
    assert.equal(await randomMarket.isChecked(), true)
    await randomMarket.locator('..').click()
    await page.getByText('跟随原始行情', { exact: true }).waitFor()
    assert.deepEqual(calls.at(-1), { action: 'random-market', data: { enabled: false } })
    state.virtualTrading = false
    await page.reload()
    await page.getByText('当前环境未启用虚拟交易配置，随机行情不可开启。', { exact: true }).waitFor()
    assert.equal(await randomMarket.isDisabled(), true)
    state = { ...state, available: false, sourceAvailable: false, canStart: false, running: false, enabled: false, controlState: undefined }
    await page.reload()
    assert.equal(await submit.isDisabled(), true, 'no history must disable target start')
    state.canStart = true
    state.startBasis = { source: 'COMPLETED_CANDLE', price: 91, timestamp: Date.now() - 60000 }
    tasks = [{ id: 'saved-control', startSource: 'COMPLETED_CANDLE', sourceTime: state.startBasis.timestamp, startedAt: Date.now() - 30000, endedAt: Date.now() - 20000, startPrice: 91, targetPrice: 120, status: 'COMPLETED' }]
    await page.reload()
    await page.getByRole('cell', { name: 'COMPLETED', exact: true }).waitFor()
    assert.equal(await submit.isEnabled(), true, 'valid historical start must work during outage')
    await page.locator('#control-target').fill('120')
    await submit.click()
    await page.getByText('正在前往目标价 · 剩余 10 秒', { exact: true }).waitFor()
    const stopOffline = page.getByRole('button', { name: '停止任务并保存历史', exact: true })
    assert.equal(await stopOffline.isEnabled(), true)
    await stopOffline.click()
    state = { ...state, enabled: false, running: false, controlState: 'WAITING_SOURCE', currentPrice: 120 }
    await page.reload()
    await page.getByText('静态价格 · 等待原始行情恢复', { exact: true }).waitFor()
    await page.screenshot({ path: path.join(output, 'admin-outage-history.png'), fullPage: true })
    await page.getByRole('cell', { name: 'COMPLETED', exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: path.join(output, 'admin-history.png'), fullPage: false })
    state = { ...state, holding: true, enabled: true, controlState: 'HOLDING', sourceAvailable: false }
    tasks[0].holding = true
    await page.reload()
    await page.getByText('保持偏移 · 源异常，静态等待恢复', { exact: true }).waitFor()
    const replace = page.getByRole('button', { name: '替代历史行情', exact: true })
    await replace.click()
    await page.getByRole('button', { name: '已替代历史行情', exact: true }).waitFor()
    assert.equal(calls.at(-1).action, 'replace')
    await page.reload()
    assert.equal(await page.getByRole('button', { name: '已替代历史行情', exact: true }).isDisabled(), true)
    assert.equal(await submit.isEnabled(), true, 'holding permits a subsequent target')
    state.sourceAvailable = true; state.available = true
    await page.reload()
    await page.getByText('已到目标 · 保持偏移，等待手动恢复', { exact: true }).waitFor()
    await page.screenshot({ path: path.join(output, 'admin-holding-replaced.png'), fullPage: true })
    assert.deepEqual(errors, [])
    console.log('PASS: random oscillation on/off, intensity, target/restore persistence, polling, stop, immediate restore during quote outage, manual offset, virtual random-market switch and environment guard')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
