// Browser plugin not available. Use installed Playwright with isolated API fixtures.
// Flow: inbox -> ID/email search -> add repeatedly -> remove -> send selected IDs.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')
const assert = require('node:assert/strict')
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
    const errors = [], sent = []
    page.on('pageerror', e => errors.push(e.message))
    await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ token: 'inbox-qa', mode: 'admin', user: { id: 1, tenantId: 1, userType: 'admin' } })))
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url())
      if (url.pathname.endsWith('/menus/current')) return route.fulfill({ json: { success: true, menus: [{ id: 1, menuCode: 'inbox', menuName: '站内信管理', path: '/inbox' }], groups: [], actions: { inbox: ['send'] } } })
      if (url.pathname.endsWith('/support/config')) return route.fulfill({ json: { inboxEnabled: true, mode: 'disabled' } })
      if (url.pathname.endsWith('/inbox/recipients')) return route.fulfill({ json: url.searchParams.get('query') === '7' ? [{ id: 7, email: 'first@example.com' }] : [{ id: 8, email: 'second@example.com' }] })
      if (url.pathname.endsWith('/support/inbox')) {
        if (route.request().method() === 'POST') { sent.push(route.request().postDataJSON()); return route.fulfill({ json: { sent: 1 } }) }
        return route.fulfill({ json: [] })
      }
      return route.fulfill({ json: { success: true, list: [], data: {}, total: 0 } })
    })
    await page.goto(process.env.ADMIN_QA_URL || 'http://127.0.0.1:5187/inbox')
    await page.getByRole('heading', { name: '站内信管理' }).waitFor()
    assert.ok(page.url().endsWith('/inbox'))
    assert.ok(await page.title())
    const search = page.getByRole('textbox', { name: '搜索收件用户 ID 或邮箱' })
    await search.fill('7')
    await page.getByRole('button', { name: '添加用户 7', exact: true }).click()
    assert.equal(await page.locator('.recipient-selected .el-tag').count(), 1)
    assert.equal(await page.getByRole('button', { name: '添加用户 7', exact: true }).isDisabled(), true)
    await search.fill('second@')
    await page.getByRole('button', { name: '添加用户 8', exact: true }).click()
    assert.equal(await page.locator('.recipient-selected .el-tag').count(), 2)
    await page.locator('.recipient-selected .el-tag').filter({ hasText: 'ID 7' }).locator('.el-tag__close').click()
    await page.locator('.recipient-selected .el-tag').filter({ hasText: 'ID 7' }).waitFor({ state: 'detached' })
    assert.equal(await page.locator('.recipient-selected .el-tag').count(), 1)
    await page.screenshot({ path: 'C:/workspace/fx/705/reports/inbox-recipients-desktop.png', fullPage: true })
    await page.setViewportSize({ width: 560, height: 1000 })
    await page.screenshot({ path: 'C:/workspace/fx/705/reports/inbox-recipients-narrow.png', fullPage: true })
    await page.locator('input[maxlength="120"]').fill('测试标题')
    await page.locator('textarea[maxlength="4000"]').fill('测试正文')
    await page.getByRole('button', { name: '发送站内信', exact: true }).click()
    await page.getByRole('button', { name: '确定', exact: true }).click()
    await page.getByText('站内信已发送', { exact: true }).waitFor()
    assert.deepEqual(sent[0].users, [8])
    await page.locator('.recipient-selected .el-tag').waitFor({ state: 'detached' })
    assert.equal(await page.locator('.recipient-selected .el-tag').count(), 0)
    assert.equal(await page.locator('vite-error-overlay').count(), 0)
    assert.deepEqual(errors, [])
    console.log('PASS: rendered ID/email search, continuous selection, duplicate guard, removal, selected-ID submission, reset; desktop/narrow screenshots; no page errors')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
