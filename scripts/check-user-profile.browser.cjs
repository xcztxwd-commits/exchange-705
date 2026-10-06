// Isolated local browser regression. Every API/socket is mocked; no real profiles or business data change.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const out = process.env.EVIDENCE_DIR || path.resolve(__dirname, '../reports/user-profile-20261006')
const baseUrls = { mobile: process.env.MOBILE_URL || 'http://127.0.0.1:17068', pc: process.env.PC_URL || 'http://127.0.0.1:17069', admin: process.env.ADMIN_URL || 'http://127.0.0.1:17070' }
for (const base of Object.values(baseUrls)) if (!['localhost', '127.0.0.1'].includes(new URL(base).hostname)) throw Error('Use isolated local servers only')
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aLl0AAAAASUVORK5CYII=', 'base64')
const parseField = (data, name) => data.match(new RegExp('name="' + name + '"\\r\\n\\r\\n([^\\r\\n]*)'))?.[1]

;(async () => {
 fs.mkdirSync(out, { recursive: true })
 const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
 const checks = [], runtimeErrors = []; const push = checks.push.bind(checks); checks.push = message => { console.log('PASS ' + message); return push(message) }
 try {
  for (const app of ['mobile', 'advanced', 'pc']) {
   const desktop = app === 'pc', base = baseUrls[desktop ? 'pc' : 'mobile']
   const context = await browser.newContext({ viewport: { width: desktop ? 1440 : 390, height: 900 } }), page = await context.newPage()
   const requests = [], submissions = []
   let profile = { id: 77, email: 'user@example.com', nickname: null, avatarUrl: null }, failSave = false, slowSave, release
   page.on('pageerror', error => runtimeErrors.push(app + ': ' + error.message))
   await page.addInitScript(({ advanced, desktop }) => {
    if (!localStorage.getItem('token')) { localStorage.setItem('token', 'PROFILE-QA'); localStorage.setItem('user', JSON.stringify({ id: 77, tenantId: 1, email: 'user@example.com', nickname: null, avatarUrl: null })) }
    localStorage.setItem('locale', 'zh-TW'); localStorage.setItem('theme', desktop ? 'dark' : 'light')
    if (advanced) localStorage.setItem(`exchange:ui-edition:v1:${encodeURIComponent(location.origin)}:1:77`, 'advanced')
   }, { advanced: app === 'advanced', desktop })
   await page.routeWebSocket(/.*/, () => {})
   await page.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url())
    if (url.hostname === 'ipwho.is') return route.fulfill({ json: { success: true, country_code: 'MY', calling_code: '60', timezone: { id: 'Asia/Kuala_Lumpur' } } })
    if (!['127.0.0.1', 'localhost'].includes(url.hostname)) return route.abort()
    if (!/^\/(api|demo-api)\//.test(url.pathname)) return route.continue()
    requests.push({ path: url.pathname, method: request.method(), token: request.headers().authorization })
    if (url.pathname === '/api/user/profile') {
     assert.equal(request.headers()['x-account-mode'], 'REAL', 'profile is shared real identity, even in demo mode')
     if (request.method() === 'PUT') {
      if (slowSave) await new Promise(resolve => { release = resolve })
      if (failSave) return route.fulfill({ status: 503, json: { success: false, message: 'Profile save unavailable' } })
      const data = request.postDataBuffer().toString('utf8'); submissions.push(data)
      profile = { ...profile, nickname: parseField(data, 'nickname') || null,
       avatarUrl: parseField(data, 'removeAvatar') === 'true' ? null : /name="avatar"; filename=/.test(data) ? '/api/uploads/images/1/user/77/avatar.png' : profile.avatarUrl }
     }
     return route.fulfill({ json: profile })
    }
    assert.notEqual(url.pathname, '/demo-api/user/profile', 'demo must never receive identity writes')
    if (/^\/api\/uploads\/images\//.test(url.pathname)) { assert.equal(request.headers().authorization, 'Bearer PROFILE-QA'); return route.fulfill({ contentType: 'image/png', body: png }) }
    if (url.pathname.endsWith('/tenant/features')) return route.fulfill({ json: { tenantId: 1, status: 'ACTIVE', acceptNewBusiness: true, features: { simulation: true, registration: true, contract: true, option: true, deposit: true, withdraw: true } } })
    if (url.pathname.endsWith('/user/77/info')) return route.fulfill({ json: { ...profile, uid: 77, fundBalance: 100, contractBalance: 0, optionBalance: 0 } })
    if (url.pathname.endsWith('/user/system/timezone')) return route.fulfill({ json: { timezone: 'UTC' } })
    if (url.pathname.endsWith('/user/support/config')) return route.fulfill({ json: { mode: 'disabled' } })
    return route.fulfill({ json: { success: true, list: [], data: [], categories: [], symbols: [], total: 0 } })
   })
   async function showProfile() {
    if (desktop) await page.locator('header').getByRole('button', { name: profile.nickname || profile.email, exact: true }).click()
    await page.locator('.profile-open:not([disabled])').waitFor()
   }
   await page.goto(base + (desktop ? '/' : '/profile')); await showProfile()
   assert.equal(await page.locator('.profile-name').innerText(), profile.email)
   assert.equal(await page.locator('.profile-identity .user-avatar img').count(), 0)
   assert.equal(await page.locator('.profile-identity').innerText().then(t => t.includes('UID 77')), true)
   assert.equal(requests.filter(r => r.path === '/api/user/profile').length, 1, 'identity update must not trigger a reload loop')
   checks.push(app + ': real user center renders email fallback, initial avatar and UID')
   await page.locator('.profile-open').click(); const editor = page.locator('dialog.profile-editor')
   await editor.waitFor({ state: 'visible' })
   await editor.locator('.nickname-label input').fill('  星空😀  ')
   await editor.locator('input[type=file]').setInputFiles({ name: 'avatar.png', mimeType: 'image/png', buffer: png })
   await editor.locator('.user-avatar img').waitFor()
   await page.screenshot({ path: path.join(out, app + '-edit-profile.png') })
   await editor.locator('button[type=submit]').click(); await editor.waitFor({ state: 'hidden' })
   assert.equal(profile.nickname, '星空😀'); assert.equal(await page.locator('.profile-name').innerText(), '星空😀')
   await page.locator('.profile-open .user-avatar img').waitFor()
   assert.ok((await page.locator('.profile-open .user-avatar img').getAttribute('src')).startsWith('blob:'))
   const stored = await page.evaluate(() => JSON.parse(localStorage.getItem('user')))
   assert.equal(stored.nickname, '星空😀'); assert.equal(stored.avatarUrl, profile.avatarUrl); assert.equal(stored.email, 'user@example.com')
   assert.equal(requests.filter(r => r.path === '/api/user/profile' && r.method === 'GET').length, 1)
   checks.push(app + ': nickname/avatar save immediately, private blob rendering and local session update without reload loops')
   await page.reload(); await showProfile(); assert.equal(await page.locator('.profile-name').innerText(), '星空😀')
   await page.locator('.profile-open').click(); await editor.waitFor({ state: 'visible' })
   const before = submissions.length
   await editor.locator('.nickname-label input').fill('X'.repeat(51)); await editor.locator('button[type=submit]').click()
   assert.equal(submissions.length, before); await editor.locator('.profile-error').waitFor()
   await editor.locator('input[type=file]').setInputFiles({ name: 'invalid.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg/>') })
   assert.equal(await editor.locator('input[type=file]').inputValue(), '')
   await editor.locator('input[type=file]').setInputFiles({ name: 'large.png', mimeType: 'image/png', buffer: Buffer.alloc(5 * 1024 * 1024 + 1) })
   assert.equal(await editor.locator('input[type=file]').inputValue(), '')
   await editor.locator('.nickname-label input').fill('不会保存'); await editor.locator('footer button').first().click()
   assert.equal(await page.locator('.profile-name').innerText(), '星空😀'); assert.equal(submissions.length, before)
   checks.push(app + ': refresh persistence, 51-character/SVG/over-5MB guards and cancel preserves saved profile')
   await page.locator('.profile-open').click(); await editor.locator('.nickname-label input').fill('');
   await editor.getByRole('button', { name: '移除頭像', exact: true }).click()
   await editor.locator('button[type=submit]').click(); await editor.waitFor({ state: 'hidden' })
   assert.equal(profile.nickname, null); assert.equal(profile.avatarUrl, null)
   assert.equal(await page.locator('.profile-name').innerText(), profile.email)
   assert.equal(await page.locator('.profile-open .user-avatar img').count(), 0)
   checks.push(app + ': clear nickname and avatar restores full email and default avatar')
   await page.evaluate(() => sessionStorage.setItem('account-mode:1:77', 'DEMO'))
   await page.reload(); await showProfile(); await page.locator('.profile-open').click()
   await editor.locator('.nickname-label input').fill('模拟也用同一身份'); await editor.locator('button[type=submit]').click()
   await editor.waitFor({ state: 'hidden' }); assert.equal(profile.nickname, '模拟也用同一身份')
   assert.equal(requests.some(r => r.path === '/demo-api/user/profile'), false)
   checks.push(app + ': demo trading mode still reads/writes the shared REAL profile')
   failSave = true; await page.locator('.profile-open').click(); await editor.locator('.nickname-label input').fill('不能假保存')
   await editor.locator('button[type=submit]').click(); await editor.locator('.profile-error').waitFor()
   assert.equal(await editor.isVisible(), true); assert.equal(await page.locator('.profile-name').innerText(), '模拟也用同一身份')
   failSave = false; await editor.locator('footer button').first().click()
   checks.push(app + ': failed save remains editable and never overwrites displayed/session profile')
   // An old user's delayed save must not restore that user's session after an account switch.
   await page.locator('.profile-open:not([disabled])').waitFor(); await page.locator('.profile-open').click()
   await editor.locator('.nickname-label input').fill('旧用户的延迟保存'); slowSave = true
   await editor.locator('button[type=submit]').click(); await page.waitForFunction(() => document.querySelector('dialog button[type=submit]').disabled)
   for (let i = 0; i < 100 && !release; i++) await new Promise(resolve => setTimeout(resolve, 10))
   assert.equal(typeof release, 'function', 'save request reached the mock server before account switch')
   await page.evaluate(async () => { const { useAuthStore } = await import('/src/store/auth.ts'); useAuthStore().setAuth('NEW-PROFILE-QA', { id: 88, tenantId: 1, email: 'next@example.com' }) })
   if (release) release(); await page.waitForTimeout(100)
   const nextSession = await page.evaluate(() => ({ token: localStorage.getItem('token'), user: JSON.parse(localStorage.getItem('user')) }))
   assert.equal(nextSession.token, 'NEW-PROFILE-QA'); assert.equal(nextSession.user.id, 88); assert.equal(nextSession.user.nickname, undefined)
   assert.equal(await editor.isVisible(), false)
   checks.push(app + ': account switch aborts pending profile work and cannot resurrect the old identity')
   await context.close()
  }
  // The actual administrator user list/detail must include both avatars and email-fallback names.
  const context = await browser.newContext({ viewport: { width: 1640, height: 950 } }), page = await context.newPage()
  page.on('pageerror', error => runtimeErrors.push('admin: ' + error.message))
  const users = [{ id: 77, tenantId: 1, email: 'user@example.com', nickname: '星空😀', avatarUrl: '/api/uploads/images/1/user/77/avatar.png', userType: 'normal', status: 'normal' }, { id: 78, tenantId: 1, email: 'blank@example.com', nickname: null, avatarUrl: null, userType: 'normal', status: 'normal' }]
  await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ mode: 'admin', token: 'ADMIN-PROFILE-QA', user: { id: 1, tenantId: 1, userType: 'admin', role: 'admin' } })))
  await page.routeWebSocket(/.*/, () => {})
  await page.route('**/api/**', async route => {
   const url = new URL(route.request().url())
   if (url.pathname === '/api/admin/menus/current') return route.fulfill({ json: { success: true, superAdmin: true, menus: [{ menuCode: 'users', path: '/users' }], groups: [], actions: { users: ['view', 'detail', 'view_subordinates'] } } })
   if (url.pathname === '/api/admin/users/query') return route.fulfill({ json: { success: true, list: users, total: 2, page: 0, size: 20 } })
   if (url.pathname === '/api/admin/users/77') return route.fulfill({ json: users[0] })
   if (url.pathname === '/api/uploads/images/1/user/77/avatar.png') { assert.equal(route.request().headers().authorization, 'Bearer ADMIN-PROFILE-QA'); return route.fulfill({ contentType: 'image/png', body: png }) }
   return route.fulfill({ json: { success: true, list: [], menus: [], groups: [], data: [], total: 0 } })
  })
  await page.goto(baseUrls.admin + '/users'); await page.locator('.user-profile-cell').first().waitFor()
  assert.equal(await page.locator('.user-profile-cell').filter({ hasText: '星空😀' }).count(), 1)
  assert.equal(await page.locator('.user-profile-cell').filter({ hasText: 'blank@example.com' }).count(), 1)
  await page.locator('.user-profile-cell .user-avatar img').first().waitFor()
  await page.screenshot({ path: path.join(out, 'admin-user-list.png') })
  const row = page.locator('.el-table__body tr').filter({ hasText: 'user@example.com' }).first()
  await row.locator('.el-dropdown').click(); await page.locator('.el-dropdown-menu:visible').getByText('查看详情', { exact: true }).click()
  const detail = page.getByRole('dialog').filter({ hasText: '用户详细信息' }); await detail.locator('.user-profile-cell').waitFor()
  assert.equal(await detail.locator('.user-profile-cell').innerText(), '星空😀'); await detail.locator('.user-avatar img').waitFor()
  await page.waitForTimeout(350); await page.screenshot({ path: path.join(out, 'admin-user-detail.png') })
  checks.push('admin: actual users list/detail include protected avatar, nickname and email fallback; original email/UID preserved')
  await context.close()
  assert.deepEqual(runtimeErrors, [], 'no browser runtime errors')
  fs.writeFileSync(path.join(out, 'browser-results.json'), JSON.stringify({ checks, runtimeErrors }, null, 2))
  console.log('PASS ' + checks.length + ' profile checks; zero runtime errors')
 } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
