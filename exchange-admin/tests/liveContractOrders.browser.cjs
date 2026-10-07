// Isolated real Orders page; all APIs mocked. Run with PLAYWRIGHT_PATH and optional CHROME_PATH.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const { createServer } = require('vite'), assert = require('node:assert/strict'), path = require('node:path'), fs = require('node:fs'), os = require('node:os')
;(async () => {
  const root = path.join(__dirname, '..'), evidence = process.env.EVIDENCE_DIR || fs.mkdtempSync(path.join(os.tmpdir(), 'live-orders-'))
  fs.mkdirSync(evidence, { recursive: true })
  const server = await createServer({ root, configFile: path.join(root, 'vite.config.ts'), server: { host: '127.0.0.1', port: 0, hmr: false } })
  await server.listen()
  const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) })
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } }), calls = [], errors = []
  const until = async check => {
    const deadline = Date.now() + 10000
    while (!check()) { assert.ok(Date.now() < deadline, 'expected current-page poll within 10 seconds'); await page.waitForTimeout(30) }
  }
  let round = 0, block = false, release, fail = false, close = false
  const order = (id, mode, status = 'CLOSED') => ({ id, status, userId: 700, symbol: 'JPY=X', side: 'BUY', type: 'MARKET', quantity: 1,
    openPrice: 150, closePrice: status === 'CLOSED' ? 149 : null, currentPrice: 150, profit: 1, netProfit: -1, fee: 2,
    userEmail: mode + '@fixture.invalid', createdAt: new Date(Date.UTC(2026, 9, 7, 12) - id * 60000).toISOString(), deleted: false })
  try {
    page.on('pageerror', e => errors.push(e.message))
    await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ mode: 'admin', token: 'live-orders-qa',
      user: { id: 1, tenantId: 1, userType: 'admin', role: 'super_admin', isSuperAdmin: true } })))
    await page.route('**/api/**', async route => {
      const request = route.request(), url = new URL(request.url()); let endpoint = url.pathname, mode = 'REAL'
      let body = request.method() === 'POST' ? request.postDataJSON() : Object.fromEntries(url.searchParams)
      if (endpoint === '/api/admin/menus/current') return route.fulfill({ json: { success: true, superAdmin: true,
        menus: [{ id: 1, menuCode: 'orders', menuName: '交易订单', path: '/orders' }], groups: [], actions: { orders: ['*'] } } })
      if (endpoint.includes('/table-preferences/')) return route.fulfill({ json: [] })
      if (endpoint === '/api/admin/account-query') { mode = 'DEMO'; endpoint = body.path; body = body.body || body.params }
      if (endpoint === '/api/admin/orders/contract/query') {
        calls.push({ mode, endpoint, body })
        const start = body.page * body.size + 1
        return route.fulfill({ json: { list: Array.from({ length: 10 }, (_, i) => order(start + i, mode, i === 0 ? 'OPEN' : 'CLOSED')), total: 20 } })
      }
      if (endpoint === '/api/admin/orders/contract/live') {
        calls.push({ mode, endpoint, body }); round++
        assert.ok(body.ids.length <= 10 && body.ids.every(id => id === 1 || id === 11), 'only displayed active orders may be requested')
        if (block && mode === 'REAL' && body.ids.includes(1)) { block = false; await new Promise(resolve => { release = resolve }) }
        if (fail) return route.fulfill({ status: 503, json: { message: 'fixture offline' } })
        return route.fulfill({ json: { list: body.ids.map(id => ({ id, status: close ? 'CLOSED' : 'OPEN', liveAvailable: true, deleted: false,
          profit: mode === 'REAL' ? round * 10 : -round * 10, netProfit: mode === 'REAL' ? round * 10 - 2 : -round * 10 - 2,
          currentPrice: 151, closePrice: close ? 151 : null, fee: 2 })) } }).catch(() => {})
      }
      assert.ok(request.method() === 'GET' || endpoint === '/api/admin/orders/option/query', 'unexpected mutation: ' + endpoint)
      return route.fulfill({ json: { success: true, list: [], content: [], data: {}, total: 0, count: 0 } })
    })
    await page.goto('http://127.0.0.1:' + server.httpServer.address().port + '/orders')
    const table = page.locator('.orders-page .admin-table-container').first(), row = table.locator('.el-table__body tbody tr').first()
    await row.waitFor(); await page.waitForFunction(() => document.querySelector('.orders-page .el-table__body tbody tr td:nth-child(14)')?.textContent.trim() !== '1.00')
    const email = page.getByPlaceholder('用户邮箱').first(); await email.fill('draft@fixture.invalid')
    await page.evaluate(() => {
      const table = document.querySelector('.orders-page .el-table'), row = table.querySelector('.el-table__body tbody tr'), scroll = table.querySelector('.el-scrollbar__wrap')
      scroll.scrollLeft = 400
      window.__liveEvidence = { table, row, input: document.activeElement, scroll, left: scroll.scrollLeft, profit: row.querySelector('td:nth-child(14)').textContent }
    })
    const queries = calls.filter(c => c.endpoint.endsWith('/contract/query')).length
    await page.waitForFunction(() => window.__liveEvidence.row.querySelector('td:nth-child(14)').textContent !== window.__liveEvidence.profit)
    assert.deepEqual(await page.evaluate(() => {
      const saved = window.__liveEvidence
      return [document.querySelector('.orders-page .el-table') === saved.table, saved.table.querySelector('.el-table__body tbody tr') === saved.row,
        document.activeElement === saved.input, saved.input.value, saved.scroll.scrollLeft === saved.left, !!document.querySelector('.orders-page .el-loading-mask')]
    }), [true, true, true, 'draft@fixture.invalid', true, false])
    assert.equal(calls.filter(c => c.endpoint.endsWith('/contract/query')).length, queries)
    await page.getByRole('button', { name: '生成订单（简版）', exact: true }).click()
    const leverage = page.getByRole('textbox', { name: '杠杆', exact: true }); await leverage.fill('42')
    const beforeDialog = round; await page.waitForFunction(value => Number(document.querySelector('.orders-page .el-table__body tbody tr td:nth-child(14)').textContent.replaceAll(',', '')) > value * 10, beforeDialog)
    assert.equal(await leverage.inputValue(), '42'); assert.equal(await leverage.evaluate(el => document.activeElement === el), true)
    await page.getByRole('button', { name: 'Close this dialog' }).click()
    block = true; await until(() => release)
    await page.locator('.orders-page .pagination').first().getByLabel('page 2').click()
    await page.waitForFunction(() => document.querySelector('.orders-page .el-table__body tbody tr td:nth-child(2)')?.textContent.trim() === '11')
    const newPageStart = calls.length; release(); release = undefined; await page.waitForTimeout(1250)
    assert.ok(calls.slice(newPageStart).filter(c => c.endpoint.endsWith('/contract/live')).every(c => c.body.ids.join(',') === '11'))
    fail = true
    await row.locator('td').nth(13).locator('span[title*="暂停更新"]').waitFor()
    const frozen = await row.locator('td').nth(13).textContent(); await page.waitForTimeout(1250)
    assert.equal(await row.locator('td').nth(13).textContent(), frozen)
    fail = false
    const filters = page.locator('.account-type-filter').first(); await filters.getByText('模拟账户', { exact: true }).click()
    await page.waitForFunction(() => document.querySelectorAll('.orders-page .el-table__body tbody tr').length === 10)
    await until(() => calls.some(c => c.mode === 'DEMO' && c.endpoint.endsWith('/contract/live')))
    const realmCalls = calls.filter(c => c.endpoint.endsWith('/contract/live')).slice(-2)
    assert.deepEqual(new Set(realmCalls.map(c => c.mode)), new Set(['REAL', 'DEMO']))
    assert.ok(realmCalls.every(c => c.body.ids.join(',') === '1'))
    close = true; await page.waitForTimeout(1300)
    const settledCalls = calls.filter(c => c.endpoint.endsWith('/contract/live')).length; await page.waitForTimeout(1300)
    assert.equal(calls.filter(c => c.endpoint.endsWith('/contract/live')).length, settledCalls)
    assert.deepEqual(errors, [])
    await page.screenshot({ path: path.join(evidence, 'live-orders.png') })
    fs.writeFileSync(path.join(evidence, 'result.json'), JSON.stringify({ calls, errors, mockedApis: true }, null, 2))
    console.log('PASS current-page IDs, stable DOM/focus/scroll/drafts, page cancellation, unavailable quotes, realm isolation and settlement freeze')
  } catch (error) {
    await page.screenshot({ path: path.join(evidence, 'failure.png') }).catch(() => {})
    throw error
  } finally { if (release) release(); await browser.close(); await server.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
