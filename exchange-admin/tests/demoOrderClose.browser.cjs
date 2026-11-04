// Browser plugin not available in the skills catalog; isolated Playwright regression with mocked APIs.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const { createServer } = require('vite'), assert = require('node:assert/strict'), path = require('node:path'), fs = require('node:fs'), os = require('node:os')
;(async () => {
  const root = path.join(__dirname, '..'), evidence = process.env.EVIDENCE_DIR || fs.mkdtempSync(path.join(os.tmpdir(), 'demo-close-'))
  fs.mkdirSync(evidence, { recursive: true })
  const server = await createServer({ root, configFile: path.join(root, 'vite.config.ts'), cacheDir: path.join(evidence, 'vite-cache'), server: { host: '127.0.0.1', port: 0, hmr: false } })
  await server.listen()
  const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) })
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } }), errors = [], consoleErrors = [], commands = [], closed = new Set()
  let failNext = true, allowed = true
  const order = (mode, kind) => ({ id: 7, userId: 700, symbol: 'BTCUSDT', side: 'BUY', direction: 'UP', type: 'MARKET', quantity: 1, amount: 100,
    openPrice: 100, currentPrice: 110, closePrice: closed.has(`${mode}:${kind}`) ? 110 : null, fee: 1, margin: 10, profit: 9, netProfit: 8,
    userEmail: 'canonical@example.test', userRemark: '同一用户的共享备注', duration: 60, leverage: 10, deleted: false,
    createdAt: '2026-11-04T01:00:00', status: closed.has(`${mode}:${kind}`) ? 'CLOSED' : kind === 'contract' ? 'OPEN' : 'TRADING' })
  try {
    page.on('pageerror', error => errors.push(error.message))
    page.on('console', entry => { if (entry.type() === 'error' && !entry.text().includes('503')) consoleErrors.push(entry.text()) })
    await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ mode: 'admin', token: 'demo-close-qa', user: { id: 1, tenantId: 1, userType: 'admin', role: 'super_admin', isSuperAdmin: true } })))
    await page.route('**/api/**', async route => {
      const request = route.request(), url = new URL(request.url()); let endpoint = url.pathname, mode = 'REAL'
      let body = request.method() === 'POST' ? request.postDataJSON() : Object.fromEntries(url.searchParams)
      if (endpoint === '/api/admin/menus/current') return route.fulfill({ json: { success: true, superAdmin: allowed, menus: [{ id: 1, menuCode: 'orders', menuName: '交易订单', path: '/orders' }], groups: [], actions: { orders: allowed ? ['*'] : ['view'] } } })
      if (endpoint.includes('/table-preferences/')) return route.fulfill({ json: [] })
      if (endpoint === '/api/admin/account-query') { mode = 'DEMO'; endpoint = body.path; body = body.body || body.params }
      const query = endpoint.match(/^\/api\/admin\/orders\/(contract|option)\/query$/)
      if (query) return route.fulfill({ json: { list: [order(mode, query[1])], total: 1 } })
      const live = endpoint.match(/^\/api\/admin\/orders\/(contract|option)\/live$/)
      if (live) return route.fulfill({ json: { list: [{ ...order(mode, live[1]), liveAvailable: true }] } })
      const close = endpoint.match(/^\/api\/admin\/(simulation\/)?orders\/(contract|option)\/7\/close$/)
      if (close) {
        mode = close[1] ? 'DEMO' : 'REAL'; const kind = close[2]
        assert.match(body.requestId, /^[a-zA-Z0-9_-]{16,64}$/); assert.deepEqual(Object.keys(body).sort(), ['reason', 'requestId'])
        commands.push({ mode, kind, endpoint, body })
        if (mode === 'DEMO' && failNext) { failNext = false; return route.fulfill({ status: 503, json: { success: false, message: '模拟平仓结果待确认，请保留原请求重试' } }) }
        closed.add(`${mode}:${kind}`)
        return route.fulfill({ json: { success: true, accountMode: mode, message: '平仓成功' } })
      }
      assert.equal(request.method(), 'GET', 'unexpected write: ' + endpoint)
      return route.fulfill({ json: { success: true, list: [], data: {}, total: 0, count: 0 } })
    })
    const base = 'http://127.0.0.1:' + server.httpServer.address().port
    await page.goto(base + '/orders'); assert.equal(new URL(page.url()).pathname, '/orders'); assert.ok(await page.title())
    const contractTable = page.locator('.orders-page .admin-table-container').first()
    const row = (table, mode) => table.locator('.el-table__body tbody tr').filter({ hasText: mode === 'DEMO' ? '模拟账户' : '真实账户' })
    await row(contractTable, 'REAL').waitFor()
    await page.locator('.account-type-filter').first().getByText('模拟账户', { exact: true }).click()
    await row(contractTable, 'DEMO').waitFor()
    assert.equal(await row(contractTable, 'DEMO').getByRole('button', { name: '平仓', exact: true }).isEnabled(), true)
    assert.equal(await row(contractTable, 'REAL').getByRole('button', { name: '平仓', exact: true }).isEnabled(), true)
    await page.screenshot({ path: path.join(evidence, 'before-close.png') })
    const confirm = async (table, mode, submit = true) => {
      await row(table, mode).getByRole('button', { name: '平仓', exact: true }).click()
      const dialog = page.locator('.el-message-box')
      await dialog.waitFor(); assert.ok((await dialog.innerText()).includes(mode === 'DEMO' ? '模拟账户' : '真实账户'))
      await dialog.getByRole('button', { name: submit ? '平仓' : '取消', exact: true }).click()
    }
    await confirm(contractTable, 'DEMO', false); assert.equal(commands.length, 0)
    await confirm(contractTable, 'DEMO'); await page.getByText('模拟平仓结果待确认，请保留原请求重试', { exact: true }).waitFor()
    assert.equal(closed.has('DEMO:contract'), false); assert.equal(closed.has('REAL:contract'), false)
    await confirm(contractTable, 'DEMO'); await row(contractTable, 'DEMO').getByText('已平仓', { exact: true }).waitFor()
    assert.equal(commands[0].body.requestId, commands[1].body.requestId)
    assert.equal(await row(contractTable, 'REAL').getByRole('button', { name: '平仓', exact: true }).isEnabled(), true)
    assert.equal(closed.has('REAL:contract'), false)
    await page.getByText('模拟平仓结果待确认，请保留原请求重试', { exact: true }).waitFor({ state: 'hidden' })
    await page.screenshot({ path: path.join(evidence, 'demo-closed-real-open.png') })
    await confirm(contractTable, 'REAL'); await row(contractTable, 'REAL').getByText('已平仓', { exact: true }).waitFor()
    assert.notEqual(commands[2].body.requestId, commands[1].body.requestId)
    await page.getByRole('tab', { name: '期货订单', exact: true }).click()
    const optionTable = page.locator('.orders-page .admin-table-container').nth(1)
    await row(optionTable, 'DEMO').waitFor(); await confirm(optionTable, 'DEMO')
    await row(optionTable, 'DEMO').getByText('已平仓', { exact: true }).waitFor()
    assert.equal(commands.at(-1).endpoint, '/api/admin/simulation/orders/option/7/close')
    assert.equal(closed.has('REAL:option'), false)
    await page.screenshot({ path: path.join(evidence, 'option-closed.png') })
    allowed = false; await page.reload(); await page.getByRole('tab', { name: '合约订单', exact: true }).waitFor()
    assert.equal(await page.getByRole('button', { name: '平仓', exact: true }).count(), 0)
    assert.deepEqual(errors, []); assert.deepEqual(consoleErrors, [])
    fs.writeFileSync(path.join(evidence, 'browser-result.json'), JSON.stringify({ passed: true, browserPlugin: 'not available in skills catalog', mockedApis: true, viewport: [1600, 1000], commands, errors, consoleErrors }, null, 2))
    console.log('PASS demo contract/option close, mixed realms, confirmation dismissal, retry identity, permission gating and refreshed status')
  } catch (error) { await page.screenshot({ path: path.join(evidence, 'failure.png') }).catch(() => {}); throw error }
  finally { await browser.close(); await server.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
