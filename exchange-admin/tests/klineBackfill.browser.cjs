// Isolated browser acceptance for checks, safe queue submission and protected results.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const output = process.env.KLINE_GAP_QA
;(async () => {
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true })
  const report = []
  try {
    for (const width of [1280, 390]) for (const canRepair of [true, false]) {
      const context = await browser.newContext({ viewport: { width, height: 1050 }, reducedMotion: 'reduce' })
      await context.route('**/*', route => ['127.0.0.1', 'localhost'].includes(new URL(route.request().url()).hostname) ? route.continue() : route.abort())
      const page = await context.newPage(), errors = [], writes = []
      await page.clock.setFixedTime(new Date('2026-10-09T02:35:45Z'))
      await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ token: 'isolated-gap-admin', user: { id: 9, tenantId: 1, role: 'admin' }, mode: 'admin', loginSessionId: '33333333-3333-4333-a333-333333333333' })))
      page.on('pageerror', error => errors.push(error.message))
      let queued = false, completed = false, checks = 0, holdCheck = false, release, held
      await page.route('**/api/**', async route => {
        const request = route.request(), url = new URL(request.url()), method = request.method()
        let body = {}
        if (url.pathname.endsWith('/menus/current')) body = { success: true, superAdmin: false, groups: [], menus: [{ menuCode: 'ai_control', menuName: '行情调控', path: '/ai-control' }], actions: { ai_control: canRepair ? ['restore_history'] : [] } }
        else if (url.pathname.endsWith('/ai-control/symbols')) body = [{ id: 1, symbol: 'BTCUSDT', displayName: 'BTC/USDT', sourceCategory: 'Crypto', category: 'Crypto', pricePrecision: 2, isEnabled: true }]
        else if (url.pathname.endsWith('/ai-control/1/history')) body = []
        else if (url.pathname.endsWith('/ai-control/1')) body = { id: 1, available: true, running: false, rawPrice: 100, currentPrice: 100, offset: 0 }
        else if (url.pathname.includes('/history-restore/')) {
          if (url.pathname.endsWith('/gaps') || url.pathname.endsWith('/gaps/repair')) {
            const range = method === 'POST' ? request.postDataJSON() : Object.fromEntries(url.searchParams)
            const from = Number(range.from), to = Number(range.to), period = range.period
            if (method === 'POST') {
              assert.ok(canRepair); writes.push({ path: url.pathname, body: range }); queued = true
            } else { checks++; if (queued) completed = true }
            const protectedOnly = period === '5m' || completed
            const gaps = [{ from, to: from + 59999, reason: 'protected_control_history', recoverable: false, sourceMissing: true }]
            if (!protectedOnly) gaps.push({ from: from + 60000, to: from + 119999, reason: 'missing_source', recoverable: true, sourceMissing: true }, { from: from + 120000, to: from + 179999, reason: 'missing_source', recoverable: true, sourceMissing: true })
            const pending = queued && !completed
            const window = { from, to: period === '5m' ? from : to, source: 'Binance:Crypto:BTCUSDT', period, sourceMissing: protectedOnly ? 1 : 3,
              displayMissing: period === '5m' ? 0 : protectedOnly ? 1 : 2, protected: 1, recoverable: protectedOnly ? 0 : 2, retryable: protectedOnly ? 0 : 2,
              inserted: completed ? 2 : 0, queueState: pending ? 'queued' : protectedOnly ? 'blocked' : 'recoverable', gaps, pending, retryAt: 0 }
            body = { ...window, sourceIdentity: window.source, timezone: range.timezone, windows: [window], sourceInputRevision: completed ? 1 : 0 }
            if (holdCheck) { holdCheck = false; await new Promise(resolve => { release = resolve; held?.() }) }
          } else if (url.pathname.endsWith('/chart')) {
            const candles = []; for (let at = Number(url.searchParams.get('from')); at <= Number(url.searchParams.get('to')); at += 60000)
              candles.push({ timestamp: at, open_price: 100, high_price: 105, low_price: 99, close_price: 101, volume: 10 })
            body = { source: candles, before: candles, sourceIdentity: 'Binance:Crypto:BTCUSDT', historyRestoreRevision: 0 }
          } else if (url.pathname.endsWith('/jobs')) body = []
          else if (method === 'POST') { writes.push({ path: url.pathname }); throw new Error('Unexpected restore/undo/source mutation') }
        } else if (url.pathname.includes('table-preferences')) body = { success: true, columns: [] }
        await route.fulfill({ json: body })
      })
      await page.goto((process.env.ADMIN_QA_URL || 'http://127.0.0.1:5597') + '/ai-control', { waitUntil: 'networkidle' })
      await page.getByRole('tab', { name: '历史源恢复', exact: true }).first().click()
      await page.getByRole('button', { name: '缺口检查', exact: true }).click()
      await page.locator('.gap-summary').filter({ hasText: '源缺失 3' }).waitFor()
      assert.equal(writes.length, 0); assert.ok(checks >= 1)
      await page.getByText('受保护缺口保留，自动和手动均不能绕过保护', { exact: true }).waitFor()
      if (canRepair) {
        await page.getByRole('button', { name: '安全补齐', exact: true }).click()
        await page.locator('.gap-summary').filter({ hasText: '最近新增 2' }).waitFor()
        assert.equal(writes.length, 1); assert.ok(writes[0].path.endsWith('/gaps/repair'))
        assert.equal(await page.getByRole('button', { name: '安全补齐', exact: true }).isDisabled(), true)
        await page.locator('.gap-check').scrollIntoViewIfNeeded()
        await page.screenshot({ path: `${output}/admin-gap-${width}-safe.png`, fullPage: true })
      } else assert.equal(await page.getByRole('button', { name: '安全补齐', exact: true }).count(), 0, 'read permission does not grant repair')
      await page.locator('.gap-check .el-select__wrapper').click()
      await page.getByRole('option', { name: '5m', exact: true }).click()
      await page.getByRole('button', { name: '缺口检查', exact: true }).click()
      await page.locator('.gap-summary').filter({ hasText: '源缺失 1 · 展示缺失 0' }).waitFor()
      if (canRepair) assert.equal(await page.getByRole('button', { name: '安全补齐', exact: true }).isDisabled(), true)
      assert.equal(writes.length, canRepair ? 1 : 0)
      holdCheck = true; const arrived = new Promise(resolve => { held = resolve })
      await page.getByRole('button', { name: '缺口检查', exact: true }).click(); await arrived
      await page.getByLabel('恢复开始时间', { exact: true }).fill('2026-10-09T10:31'); await page.getByLabel('恢复开始时间', { exact: true }).press('Tab')
      release(); await page.waitForTimeout(100)
      assert.equal(await page.locator('.gap-summary').count(), 0, 'late check cannot populate a changed selection')
      await page.screenshot({ path: `${output}/admin-gap-${width}-${canRepair ? 'write' : 'read'}-scope.png`, fullPage: true })
      assert.deepEqual(errors, [])
      report.push({ width, canRepair, checkOnly: true, safeWrites: writes.length, protectedStops: true, nativeCoverageIndependent: true, lateRangeRejected: true, errors })
      await context.close()
    }
    fs.writeFileSync(`${output}/admin-backfill-results.json`, JSON.stringify(report, null, 2)); console.log(JSON.stringify(report))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
