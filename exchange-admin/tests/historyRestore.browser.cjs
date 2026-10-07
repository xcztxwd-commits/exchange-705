// Local app verification. Every API call uses isolated synthetic data.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const output = process.env.HISTORY_RESTORE_QA || 'C:/workspace/fx/new/history-restore-qa'
fs.mkdirSync(output, { recursive: true })
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const findings = []
  try {
    for (const width of [1280, 390]) {
      const context = await browser.newContext({ viewport: { width, height: 1050 }, reducedMotion: 'reduce' })
      const page = await context.newPage(), errors = [], writes = []
      let restored = false, jobs = [], ready, loseFirstReceipt = true
      const at = Date.parse('2026-10-07T09:21:00Z')
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ token: 'isolated-local-test', user: { id: 9, tenantId: 1, role: 'super_admin' }, mode: 'admin', loginSessionId: '33333333-3333-4333-a333-333333333333' })))
      await page.route('**/api/**', async route => {
        const url = new URL(route.request().url()), method = route.request().method(), payload = method === 'POST' ? route.request().postDataJSON() : null
        let body = {}
        const candle = (timestamp, low = 158.2100067138672) => ({ timestamp, open_price: 158.221, high_price: 158.236, low_price: low, close_price: 158.229, volume: 0 })
        if (url.pathname.endsWith('/menus/current')) body = { success: true, superAdmin: true, groups: [], menus: [{ menuCode: 'ai_control', menuName: '行情调控', path: '/ai-control' }], actions: { ai_control: ['*'] } }
        else if (url.pathname.endsWith('/ai-control/symbols')) body = [{ id: 61, symbol: 'JPY=X', displayName: 'USD/JPY', sourceCategory: 'Forex', category: 'Forex', pricePrecision: 3, isEnabled: true }]
        else if (url.pathname.endsWith('/ai-control/61/history')) body = []
        else if (url.pathname.endsWith('/ai-control/61')) body = { id: 61, available: true, running: false, rawPrice: 158.229, currentPrice: 158.229, offset: 0 }
        else if (url.pathname.includes('/history-restore/')) {
          if (url.pathname.endsWith('/chart')) {
            const source = []; for (let n = Number(url.searchParams.get('from')); n <= Number(url.searchParams.get('to')); n += 60000) source.push(candle(n))
            body = { source, before: source.map(row => row.timestamp === at && !restored ? candle(at, 157.321) : row), sourceIdentity: 'Yahoo:Forex:JPY=X', historyRestoreRevision: restored ? 1 : 0 }
          } else if (url.pathname.endsWith('/preview')) {
            assert.equal(payload.from, at); assert.equal(payload.to, at)
            ready = { previewToken: '11111111-1111-4111-a111-111111111111', state: restored ? 'NO_CHANGE' : 'READY', from: at, to: at, total: 1, changed: 1, sourceIdentity: 'Yahoo:Forex:JPY=X', differences: [{ timestamp: at, before: candle(at, 157.321), source: candle(at) }] }; body = ready
          } else if (url.pathname.endsWith('/jobs') && method === 'POST') {
            writes.push(payload); if (!jobs.length) jobs = [{ id: ready.previewToken, requestKey: payload.requestKey, from: at, to: at, timezone: 'Asia/Singapore', total: 1, completed: 1, actorId: 9, createdAt: Date.now(), state: 'COMPLETED', kind: 'RESTORE', sourceIdentity: 'Yahoo:Forex:JPY=X' }]
            restored = true; body = jobs[0]
            if (loseFirstReceipt) { loseFirstReceipt = false; return route.abort('failed') }
          } else if (url.pathname.endsWith('/jobs')) body = url.searchParams.has('requestKey') ? jobs[0] || { state: 'NOT_FOUND' } : jobs
          else if (url.pathname.endsWith('/undo-preview')) body = { previewToken: '22222222-2222-4222-a222-222222222222', total: 1, from: at, to: at }
          else if (url.pathname.endsWith('/undo')) { restored = false; writes.push(payload); body = {}; jobs.unshift({ ...jobs[0], id: payload.previewToken, kind: 'UNDO', requestKey: payload.requestKey }) }
        } else if (url.pathname.includes('table-preferences')) body = { success: true, columns: [] }
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
      })
      await page.goto('http://127.0.0.1:5197/ai-control', { waitUntil: 'networkidle' })
      await page.getByRole('tab', { name: '历史源恢复', exact: true }).first().click()
      await page.getByLabel('恢复开始时间', { exact: true }).fill('2026-10-07T17:21')
      await page.getByLabel('恢复开始时间', { exact: true }).press('Tab')
      await page.getByLabel('恢复结束时间', { exact: true }).fill('2026-10-07T17:21')
      await page.getByLabel('恢复结束时间', { exact: true }).press('Tab')
      await page.getByRole('button', { name: '预览恢复', exact: true }).click()
      await page.getByRole('button', { name: '确认恢复 1 根', exact: true }).waitFor()
      await page.screenshot({ path: `${output}/history-restore-${width}.png`, fullPage: true })
      const plot = page.locator('.history-restore .chart'); await plot.scrollIntoViewIfNeeded()
      let box = await plot.boundingBox()
      const point = (index, length) => ({ x: box.x + 65 + (index + .5) / length * (box.width - 95), y: box.y + 120 })
      const a = point(8, 11), b = point(3, 11)
      await page.mouse.move(a.x, a.y); await page.mouse.down(); await page.mouse.move(b.x, b.y, { steps: 8 }); await page.mouse.up()
      assert.equal(await page.getByLabel('恢复开始时间', { exact: true }).inputValue(), '2026-10-07T17:19')
      assert.equal(await page.getByLabel('恢复结束时间', { exact: true }).inputValue(), '2026-10-07T17:24')
      await page.getByText('5m', { exact: true }).click(); await page.waitForTimeout(100)
      await plot.scrollIntoViewIfNeeded(); box = await plot.boundingBox()
      const five = point(1, 3); await page.mouse.click(five.x, five.y)
      assert.equal(await page.getByLabel('恢复开始时间', { exact: true }).inputValue(), '2026-10-07T17:20')
      assert.equal(await page.getByLabel('恢复结束时间', { exact: true }).inputValue(), '2026-10-07T17:24')
      await page.getByText('1m', { exact: true }).click(); await page.waitForTimeout(100)
      await plot.scrollIntoViewIfNeeded(); box = await plot.boundingBox()
      const single = point(5, 11); await page.mouse.click(single.x, single.y)
      assert.equal(await page.getByLabel('恢复开始时间', { exact: true }).inputValue(), '2026-10-07T17:21')
      assert.equal(await page.getByLabel('恢复结束时间', { exact: true }).inputValue(), '2026-10-07T17:21')
      await page.getByRole('button', { name: '预览恢复', exact: true }).click()
      await page.getByRole('button', { name: '确认恢复 1 根', exact: true }).click()
      const dialog = page.getByRole('dialog'); await dialog.getByRole('button', { name: '确认恢复 1 根', exact: true }).click()
      await page.getByText('历史源恢复任务已完成', { exact: true }).waitFor()
      assert.equal(writes.length, 1, 'Lost receipt queries original key without a second mutation')
      await page.getByRole('tab', { name: '恢复记录', exact: true }).click()
      await page.getByRole('button', { name: '撤销', exact: true }).click()
      await page.getByRole('dialog').getByRole('button', { name: '确认撤销 1 根', exact: true }).click()
      await page.getByText('撤销记录', { exact: true }).waitFor()
      assert.equal(writes.length, 2); assert.ok(writes[1].undoOf)
      assert.deepEqual(errors, [])
      findings.push({ width, preview: true, reverseBrush: true, singleCandle: true, expanded5m: true, lostReceipt: true, undo: true, errors })
      await context.close()
    }
    fs.writeFileSync(`${output}/browser-results.json`, JSON.stringify(findings, null, 2)); console.log(JSON.stringify(findings))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exit(1) })
