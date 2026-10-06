// Global permission selection regression; every API, including grants, is mocked.
// Run against npm run dev with ADMIN_URL and PLAYWRIGHT_PATH set if needed.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const catalog = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../exchange-backend/src/main/resources/admin-permissions.json'), 'utf8'))
let nextId = 0
const groups = catalog.groups.map(group => ({ id: ++nextId, parentId: 0, menuCode: 'group_' + group.code, menuName: group.name, menuType: 'directory' }))
const menus = catalog.menus.map(menu => ({ id: ++nextId, parentId: groups.find(group => group.menuCode === 'group_' + menu.group).id, menuCode: menu.code, menuName: menu.name, menuType: 'menu' }))
const buttons = catalog.menus.flatMap(menu => Object.entries(menu.actions).map(([action, name]) => ({ id: ++nextId,
  parentId: menus.find(parent => parent.menuCode === menu.code).id, menuCode: `${menu.code}:${action}`, menuName: name, menuType: 'button' })))
const all = [...groups, ...menus, ...buttons]
const parent = menus.find(menu => buttons.filter(button => button.parentId === menu.id).length > 1)
const siblings = buttons.filter(button => button.parentId === parent.id)

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    const page = await browser.newPage({ viewport: { width: 1400, height: 1000 } })
    page.setDefaultTimeout(10000)
    const errors = [], unexpected = [], writes = []
    let savedIds = [parent.id], pauseSave = false, resumeSave
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ mode: 'admin', token: 'QA',
      loginSessionId: '00000000-0000-4000-8000-000000000001', user: { id: 1, tenantId: 1, userType: 'admin', role: 'admin' } })))
    await page.route('**/__qa', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><meta charset="utf-8"><div id="app"></div>' }))
    await page.route('**/api/**', async route => {
      const request = route.request(), url = new URL(request.url())
      assert.equal(request.headers().authorization, 'Bearer QA')
      if (url.pathname === '/api/admin/menus/current') return route.fulfill({ json: { success: true, menus: [], groups: [], actions: { roles: ['assign_permission'] } } })
      if (url.pathname === '/api/admin/menus') return route.fulfill({ json: { success: true, list: all } })
      if (url.pathname === '/api/admin/roles') return route.fulfill({ json: { success: true, list: [
        { id: 101, roleName: '全选回归角色', roleCode: 'qa_all_permissions', status: 'active', isSuper: false, menuCount: savedIds.length }
      ] } })
      if (url.pathname === '/api/admin/roles/101/menus') {
        if (request.method() === 'POST') {
          const ids = request.postDataJSON().menuIds; writes.push(ids)
          if (pauseSave) { pauseSave = false; await new Promise(resolve => { resumeSave = resolve }) }
          savedIds = ids
        }
        return route.fulfill({ json: { success: true, menuIds: savedIds } })
      }
      unexpected.push({ method: request.method(), path: url.pathname }); return route.fulfill({ status: 500, json: { message: 'unexpected request' } })
    })
    await page.goto((process.env.ADMIN_URL || 'http://127.0.0.1:17060').replace(/\/$/, '') + '/__qa')
    await page.evaluate(async () => {
      const source = await (await fetch('/src/views/Roles.vue')).text()
      const auth = await (await fetch('/src/store/auth.ts')).text()
      const dep = (s, n) => s.match(new RegExp('from ["\']([^"\']*' + n + '\\.js[^"\']*)["\']'))[1]
      const { createApp } = await import(dep(source, 'vue'))
      const element = await import(dep(source, 'element-plus'))
      const { createPinia, setActivePinia } = await import(dep(auth, 'pinia'))
      const pinia = createPinia(); setActivePinia(pinia)
      const { loadAccess, permissionDirective } = await import('/src/utils/access.ts'); await loadAccess()
      const { default: Component } = await import('/src/views/Roles.vue')
      const { default: AdminTable } = await import('/src/components/AdminTable.ts')
      const { TABLE_PREFERENCES } = await import('/src/utils/tablePreferences.ts')
      await import('/node_modules/element-plus/dist/index.css')
      const app = createApp(Component).use(pinia).use(element.default)
      app.directive('permission', permissionDirective); app.component('AdminTable', AdminTable)
      app.provide(TABLE_PREFERENCES, { identityKey: () => 'QA', load: async () => [], save: async () => {} }); app.mount('#app')
    })
    const dialog = page.getByRole('dialog', { name: '分配菜单与按钮权限' }), tree = dialog.locator('.el-tree')
    const outer = dialog.getByRole('checkbox', { name: '全选所有权限', exact: true })
    const label = dialog.locator('label.el-checkbox').filter({ hasText: '全选所有权限' })
    const search = dialog.getByPlaceholder('搜索菜单或操作')
    const open = async () => { await page.getByRole('button', { name: '分配权限', exact: true }).click(); await tree.locator(`[data-key="${parent.id}"]`).waitFor() }
    const count = () => tree.locator('input[type="checkbox"]:checked').count()
    const indeterminate = () => outer.evaluate(input => input.indeterminate)
    const nodeInput = id => tree.locator(`[data-key="${id}"] > .el-tree-node__content input[type="checkbox"]`)
    const clickNode = id => tree.locator(`[data-key="${id}"] > .el-tree-node__content .el-checkbox`).click()
    const toggleAll = async checked => { if (await outer.isChecked() !== checked) await label.click(); assert.equal(await outer.isChecked(), checked) }

    await open()
    assert.equal(await outer.evaluate(input => input.closest('.el-tree') === null), true, 'global checkbox is outside every tree root')
    assert.equal(await count(), 1); assert.equal(await outer.isChecked(), false); assert.equal(await indeterminate(), true)
    await search.fill(parent.menuCode)
    await clickNode(siblings[0].id)
    assert.equal(await nodeInput(parent.id).isChecked(), true); assert.equal(await nodeInput(siblings[1].id).isChecked(), false)
    assert.equal(await indeterminate(), true, 'individual button selection updates global partial state')
    await clickNode(parent.id); assert.equal(await count(), 0); assert.equal(await indeterminate(), false)
    await clickNode(siblings[0].id); assert.equal(await count(), 2, 'button still selects only its menu, not sibling actions')
    await toggleAll(true); assert.equal(await count(), all.length); assert.equal(await indeterminate(), false)
    await toggleAll(false); assert.equal(await count(), 0, 'clearing also affects permissions hidden by search')
    await toggleAll(true); assert.equal(await count(), all.length, 'selecting includes permissions hidden by search')
    assert.equal(writes.length, 0, 'checking never immediately persists role grants')
    await search.fill('')
    if (process.env.OUTPUT_DIR) await page.screenshot({ path: path.join(process.env.OUTPUT_DIR, 'permission-select-all.png'), fullPage: true })

    pauseSave = true
    const saving = page.waitForRequest(request => request.url().endsWith('/roles/101/menus') && request.method() === 'POST')
    await dialog.getByRole('button', { name: '保存', exact: true }).click(); await saving
    assert.equal(await outer.isDisabled(), true, 'global selection is frozen while saving')
    assert.deepEqual([...writes[0]].sort((a, b) => a - b), all.map(node => node.id).sort((a, b) => a - b))
    resumeSave(); await dialog.waitFor({ state: 'hidden' })
    await open(); assert.equal(await outer.isChecked(), true)
    await toggleAll(false); await dialog.getByRole('button', { name: '取消', exact: true }).click()
    await dialog.waitFor({ state: 'hidden' }); await open()
    assert.equal(await outer.isChecked(), true, 'cancelled draft does not change persisted grants'); assert.equal(writes.length, 1)
    await clickNode(siblings[0].id); assert.equal(await indeterminate(), true)
    await toggleAll(true); assert.equal(await count(), all.length)
    await toggleAll(false); await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await dialog.waitFor({ state: 'hidden' }); assert.deepEqual(writes[1], [])
    await open(); assert.equal(await count(), 0); assert.equal(await outer.isChecked(), false); assert.equal(await indeterminate(), false)
    await toggleAll(true); await dialog.getByRole('button', { name: '保存', exact: true }).click(); await dialog.waitFor({ state: 'hidden' })

    const added = { id: ++nextId, parentId: parent.id, menuCode: parent.menuCode + ':qa_added', menuName: '回归新增按钮', menuType: 'button' }
    all.push(added); await open(); await search.fill(parent.menuCode)
    assert.equal(await outer.isChecked(), false); assert.equal(await indeterminate(), true)
    assert.equal(await nodeInput(added.id).isChecked(), false, 'saved all-selection never automatically grants future buttons')
    await toggleAll(true); assert.equal(await nodeInput(added.id).isChecked(), true); assert.equal(savedIds.includes(added.id), false)
    await page.setViewportSize({ width: 390, height: 844 })
    const bounds = await label.boundingBox(); assert.ok(bounds.x >= 0 && bounds.x + bounds.width <= 390)
    await page.evaluate(async () => { const { access } = await import('/src/utils/access.ts'); access.actions.roles = [] })
    await label.waitFor({ state: 'hidden' })
    assert.deepEqual(errors, []); assert.deepEqual(unexpected, [])
    console.log(`PASS: ${all.length} permissions; outer full/clear/partial state, filtered global selection, preserved strict button/menu behavior, exact mock save, cancel/reopen, save freeze, future buttons, responsive width, assignment permission guard; no browser errors`)
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
