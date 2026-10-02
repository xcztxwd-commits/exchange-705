// Browser plugin not available. Reusable Playwright UI checks; APIs are isolated fixtures.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const catalog = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../exchange-backend/src/main/resources/admin-permissions.json'), 'utf8'))
const base = process.env.ADMIN_QA_URL || 'http://127.0.0.1:18051'
const output = process.env.ADMIN_QA_OUTPUT || 'C:/workspace/fx/new/admin-permission-evidence-20260929'
fs.mkdirSync(output, { recursive: true })
let id = 0
const groups = catalog.groups.map(g => ({ id: ++id, parentId: 0, menuCode: 'group_'+g.code, menuName: g.name, menuType: 'directory', icon: g.icon }))
const menus = catalog.menus.map(m => ({ id: ++id, parentId: groups.find(g => g.menuCode === 'group_'+m.group).id, menuCode: m.code, menuName: m.name, path: m.path, menuType: 'menu' }))
const buttons = catalog.menus.flatMap(m => Object.entries(m.actions).map(([action, name]) => ({ id: ++id, parentId: menus.find(p => p.menuCode === m.code).id, menuCode: `${m.code}:${action}`, menuName: name, menuType: 'button' })))
const all = [...groups, ...menus, ...buttons]
const duration = { id: 500, duration: 60, label: 'QA 60s', profitRate: .8, lossRate: 1, enabled: true, sortOrder: 0 }
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const errors = [], checks = []
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
    let mode = 'readonly', fail = false, actions = [], writes = 0, savedRoleIds = null
    const snapshot = () => {
      const visible = mode === 'super' ? menus : mode === 'empty' ? [] : menus.filter(m => m.menuCode === 'durations')
      return { success: true, superAdmin: mode === 'super', menus: visible, groups: groups.filter(g => visible.some(m => m.parentId === g.id)), actions: Object.fromEntries(visible.map(m => [m.menuCode, mode === 'super' ? ['*'] : actions])) }
    }
    page.on('pageerror', e => errors.push(e.message))
    page.on('console', message => { if (message.type() === 'error' && !/status of 503|权限加载失败/.test(message.text())) errors.push(message.text()) })
    await page.addInitScript(() => {
      localStorage.setItem('admin_token', 'qa-one')
      // Forging a local super flag must never grant frontend access.
      localStorage.setItem('admin_user', JSON.stringify({ id: 1, userType: 'admin', role: 'super_admin', isSuperAdmin: true }))
    })
    await page.route('**/api/**', async route => {
      const request = route.request(), url = new URL(request.url()), p = url.pathname
      if (p === '/api/admin/menus/current') return route.fulfill(fail ? { status: 503, json: { message: '权限加载失败' } } : { json: snapshot() })
      if (p === '/api/admin/menus' || p === '/api/admin/menus/list') return route.fulfill({ json: { success: true, list: all } })
      if (p === '/api/admin/notification/sounds') return route.fulfill({ json: [] })
      if (p === '/api/admin/notification/pending-counts') return route.fulfill({ json: { success: true, data: { deposit: 0, withdraw: 0, order: 0, kyc: 0 } } })
      if (p === '/api/admin/users/online-count') return route.fulfill({ json: { success: true, count: 0 } })
      if (p === '/api/admin/durations') { if(request.method() === 'POST') writes++; return route.fulfill({ json: { success: true, list: [duration] } }) }
      if (p === '/api/admin/roles') return route.fulfill({ json: { success: true, list: [{ id: 101, roleCode: 'qa_reader', roleName: 'QA 只读角色', status: 'active', isSuper: false, menuCount: 1 }] } })
      if (p === '/api/admin/roles/101/menus') {
        if (request.method() === 'POST') savedRoleIds = request.postDataJSON().menuIds
        return route.fulfill({ json: { success: true, menuIds: [menus.find(m => m.menuCode === 'durations').id] } })
      }
      return route.fulfill({ json: { success: true, list: [], data: {} } })
    })
    const go = async url => { await page.goto(base+url); await page.waitForSelector('.el-result, .layout-container'); await page.waitForTimeout(250) }
    await go('/durations')
    assert.ok((await page.title()).length); assert.ok((await page.locator('body').innerText()).includes('QA 60s'))
    assert.equal(await page.locator('vite-error-overlay').count(), 0)
    assert.equal(await page.locator('.el-sub-menu').count(), 1)
    assert.equal(await page.locator('[data-permission="durations:create"]:visible').count(), 0)
    assert.equal(await page.locator('[data-permission="durations:edit"]:visible').count(), 0)
    assert.equal(await page.locator('[data-permission="durations:delete"]:visible').count(), 0)
    await page.screenshot({ path: path.join(output,'readonly.png'), fullPage: true })
    checks.push('只读角色：仅授权菜单；新增、编辑、删除隐藏；伪造本地超级管理员标记无效')
    await go('/roles'); assert.ok(page.url().endsWith('/durations')); checks.push('未授权深链接重定向至首个可访问菜单')
    actions = ['create']; await go('/durations')
    await page.locator('[data-permission="durations:create"]:visible').first().click()
    const dialog = page.getByRole('dialog')
    await dialog.locator('input[placeholder="如：30秒"]').count().then(async count => {
      if (count) await dialog.locator('input[placeholder="如：30秒"]').fill('QA new')
      else await dialog.locator('.el-form-item').filter({ hasText: '显示标签' }).locator('input').fill('QA new')
    })
    await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await page.waitForTimeout(150); assert.equal(writes, 1)
    assert.equal(await page.locator('[data-permission="durations:edit"]:visible').count(), 0)
    checks.push('单独授权新增：可打开表单并提交；编辑、删除仍不可见')
    actions = []; await go('/durations'); assert.equal(await page.locator('[data-permission="durations:create"]:visible').count(), 0)
    mode = 'empty'; await go('/durations'); assert.ok(page.url().endsWith('/forbidden')); checks.push('撤销授权立即生效；零菜单显示无权限页，无重定向循环')
    mode = 'super'; await go('/roles')
    assert.equal(await page.locator('.el-sub-menu').count(), 7)
    await page.getByRole('button', { name: '分配权限', exact: true }).click()
    const tree = page.getByRole('dialog').locator('.el-tree')
    await tree.waitFor()
    const createButton = buttons.find(b => b.menuCode === 'durations:create')
    await tree.locator(`[data-key="${createButton.id}"] > .el-tree-node__content .el-checkbox`).click()
    await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click()
    await page.waitForTimeout(450)
    assert.ok(savedRoleIds.includes(createButton.id)); assert.ok(savedRoleIds.includes(createButton.parentId))
    assert.ok(!savedRoleIds.includes(buttons.find(b => b.menuCode === 'durations:delete').id))
    await page.screenshot({ path: path.join(output, 'menu-groups.png'), fullPage: true })
    checks.push('超级管理员：七组菜单；权限树独立勾选新增，不联动授予删除')
    await page.getByRole('button', { name: '分配权限', exact: true }).click()
    await page.getByRole('dialog').waitFor({ state: 'visible' })
    await page.waitForTimeout(450)
    await page.screenshot({ path: path.join(output, 'permission-tree.png'), fullPage: true })
    await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click()
    await page.waitForTimeout(450)
    await page.setViewportSize({ width: 1024, height: 768 }); await page.screenshot({ path: path.join(output, 'compact.png'), fullPage: true })
    fail = true; await go('/durations'); assert.ok(page.url().endsWith('/forbidden')); checks.push('权限接口失败关闭访问，不回退全部菜单')
    assert.deepEqual(errors, [])
    console.log(JSON.stringify({ pass: true, base, viewports: ['1440x1000','1024x768'], checks, consoleErrors: errors, screenshots: output },null,2))
  } finally { await browser.close() }
})().catch(e => { console.error(e); process.exitCode=1 })
