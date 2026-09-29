// Browser plugin not available: regular Playwright with isolated API fixtures.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')
const assert = require('node:assert/strict')
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
    const errors = [], queries = []; let fail = false
    page.on('pageerror', e => errors.push(e.message))
    await page.addInitScript(() => {
      localStorage.setItem('admin_token', 'inspection-qa')
      localStorage.setItem('admin_user', JSON.stringify({ id: 1, userType: 'admin', isSuperAdmin: true }))
    })
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url())
      if (url.pathname === '/api/admin/menus/current') return route.fulfill({ json: { success: true, superAdmin: true, menus: [{ id: 1, menuCode: 'users', menuName: '用户', path: '/users' }], groups: [], actions: { users: ['*'], orders: ['*'] } } })
      if (url.pathname === '/api/admin/account-inspection') {
        assert.equal(route.request().method(), 'GET'); queries.push(Object.fromEntries(url.searchParams))
        return route.fulfill(fail ? { status: 503, json: { message: '模拟账户查询失败，未回退到真实账户' } } : { json: { environment: url.searchParams.get('mode'), total: 1, columns: ['user_id', 'available'], rows: [{ user_id: 101, available: url.searchParams.get('mode') === 'DEMO' ? '12345.67' : '987.65' }] } })
      }
      return route.fulfill({ json: { success: true, list: [], content: [], data: {}, total: 0, count: 0 } })
    })
    await page.goto(process.env.ADMIN_QA_URL || 'http://127.0.0.1:18079/users')
    await page.getByRole('button', { name: '账户筛选查看', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '账户数据查看' })
    await dialog.getByText('12345.67', { exact: true }).waitFor()
    await dialog.getByPlaceholder('留空查看全部').fill('101')
    await dialog.getByRole('button', { name: '查询 / 刷新' }).click()
    await page.waitForTimeout(200)
    assert.equal(queries.at(-1).userId, '101')
    await dialog.locator('.el-select').first().click()
    await page.getByRole('option', { name: '真实账户', exact: true }).click()
    await dialog.getByText('987.65', { exact: true }).waitFor()
    assert.equal(await dialog.getByText('12345.67', { exact: true }).count(), 0)
    fail = true
    await dialog.locator('.el-select').first().click()
    await page.getByRole('option', { name: '模拟账户', exact: true }).click()
    await dialog.getByText('模拟账户查询失败，未回退到真实账户', { exact: true }).waitFor()
    assert.equal(await dialog.getByText('987.65', { exact: true }).count(), 0)
    assert.equal(queries.at(-1).mode, 'DEMO')
    assert.deepEqual(errors, [])
    await page.screenshot({ path: 'C:/workspace/fx/new/simulation-admin-20260929-1212/inspection-browser.png', fullPage: true })
    console.log('PASS: simulated wallet, UID filter, real/demo switch, failed query clears stale data, GET-only, no page errors')
  } finally { await browser.close() }
})().catch(e => { console.error(e); process.exitCode = 1 })
