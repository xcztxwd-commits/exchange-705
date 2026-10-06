// Isolated local Vite fixture: all API reads and writes are intercepted, never sent to a tenant.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const base = process.env.ADMIN_URL, out = process.env.EVIDENCE_DIR || path.resolve(__dirname, '../../reports/support-channel-module-20261006')
if (!base || !['127.0.0.1', 'localhost'].includes(new URL(base).hostname)) throw Error('ADMIN_URL must point to an isolated local admin Vite server')
fs.mkdirSync(out, { recursive: true })
;(async () => {
  const browser = await chromium.launch({ headless: true, ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}) })
  const page = await browser.newPage({ viewport: { width: 1300, height: 900 } }), errors = [], requests = [], writes = [], checks = []
  let config = { mode: 'internal', inboxEnabled: true, capacity: 5, welcome: '已发布欢迎语', offline: '已发布离线提示', fallbackLocale: '', replies: { ja: { welcome: 'こんにちは', offline: '' } }, rules: [], adminSound: '/api/user/support/tones/arrival.wav', userSound: '/api/user/support/tones/reply.wav' }
  let policy = { tenantId: 1, tenantName: 'QA', status: 'ACTIVE', policyVersion: 1, features: { support: true, external_support: true, inbox: true }, configs: [], supportChannel: null }
  let supportView = true, supportSave = true, policyFailure = false, saveFailure = false, readFailure = false
  page.on('pageerror', error => errors.push(error.message))
  try {
    await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ mode: 'admin', token: 'QA', user: { id: 1, tenantId: 1, userType: 'admin', role: 'admin', account: 'QA' } })))
    await page.route('**/__qa', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"></head><body style="margin:0;padding:20px;background:#f4f6f9"><div id="app"></div></body></html>' }))
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url()), method = route.request().method()
      requests.push({ path: url.pathname, method })
      if (url.pathname === '/api/admin/menus/current') return route.fulfill({ json: { success: true, superAdmin: false, menus: [], groups: [], actions: { settings: ['save'], ...(supportView ? { support_settings: supportSave ? ['save'] : [] } : {}) } } })
      if (url.pathname.startsWith('/api/admin/tenant-policies/')) {
        if (policyFailure && url.pathname.endsWith('/support')) return route.fulfill({ status: 503, json: { message: 'QA 策略加载失败' } })
        return route.fulfill({ json: policy })
      }
      if (url.pathname === '/api/admin/support/settings') {
        assert(supportView, 'unauthorized module must not request support settings')
        if (method === 'POST') {
          assert(supportSave, 'view-only module must not save')
          if (saveFailure) return route.fulfill({ status: 500, json: { message: 'QA 保存失败' } })
          config = route.request().postDataJSON(); writes.push(structuredClone(config))
          return route.fulfill({ json: {} })
        }
        if (readFailure) return route.fulfill({ status: 503, json: { message: 'QA 配置读取失败' } })
        return route.fulfill({ json: { ...config, mode: policy.supportChannel || config.mode } })
      }
      if (url.pathname === '/api/admin/config/list') return route.fulfill({ json: [{ configKey: 'customer.service.link', configValue: 'https://support.example.test' }] })
      if (url.pathname === '/api/admin/config/get') return route.fulfill({ json: { value: null } })
      if (url.pathname === '/api/admin/config/saveBatch') { assert(!route.request().postDataJSON().some(row => row.key === 'support.settings')); return route.fulfill({ json: {} }) }
      throw Error('Unexpected API request: ' + method + ' ' + url.pathname)
    })
    await page.goto(base + '/__qa')
    await page.addStyleTag({ url: base + '/node_modules/element-plus/dist/index.css' })
    const mount = async view => page.evaluate(async view => {
      window.__app?.unmount(); document.querySelector('#app').innerHTML = ''
      const source = await (await fetch('/src/views/' + view + '.vue')).text(), auth = await (await fetch('/src/store/auth.ts')).text()
      const dep = (source, name) => source.match(new RegExp('from ["\']([^"\']*' + name + '\\.js[^"\']*)["\']'))[1]
      const { createApp } = await import(dep(source, 'vue')), element = await import(dep(source, 'element-plus'))
      const { createPinia, setActivePinia } = await import(dep(auth, 'pinia')), pinia = createPinia(); setActivePinia(pinia)
      const { loadAccess, permissionDirective } = await import('/src/utils/access.ts'); await loadAccess(true)
      const { default: View } = await import('/src/views/' + view + '.vue')
      await import('/src/styles/global.scss?import')
      window.__closeMessages = () => element.ElMessage.closeAll()
      window.__app = createApp(View).use(pinia).use(element.default).directive('permission', permissionDirective)
      window.__app.mount('#app')
    }, view)
    const screenshot = async name => { await page.evaluate(() => window.__closeMessages()); await page.waitForFunction(() => !document.querySelector('.el-message')); await page.screenshot({ path: path.join(out, name) }) }
    const channel = page.locator('.support-channel-settings'), save = channel.getByRole('button', { name: '保存服务渠道', exact: true })
    const select = async name => { await channel.locator('label.el-radio-button').filter({ hasText: name }).click(); assert(await channel.getByRole('radio', { name, exact: true }).isChecked()) }
    const capacity = async value => { await channel.getByRole('spinbutton').fill(String(value)); await channel.getByRole('spinbutton').press('Tab') }
    const posted = () => page.waitForResponse(response => response.url().endsWith('/api/admin/support/settings') && response.request().method() === 'POST')
    const saveChannel = async () => { const before = writes.length; await Promise.all([posted(), save.click()]); assert.equal(writes.length, before + 1) }

    await mount('SupportSettings'); await channel.getByRole('spinbutton').waitFor()
    assert.equal(await channel.getByRole('spinbutton').inputValue(), '5')
    const heading = await channel.locator('h2').boundingBox(), button = await save.boundingBox()
    assert(button.x > heading.x && Math.abs(button.y + button.height / 2 - heading.y - heading.height / 2) < 2, 'save button sits beside the independent module heading')
    const welcome = page.getByRole('textbox', { name: '欢迎回复', exact: true })
    await welcome.fill('欢迎语未提交草稿')
    await select('关闭客服'); await capacity(7); await saveChannel()
    assert.equal(config.mode, 'off'); assert.equal(config.capacity, 7); assert.equal(config.welcome, '已发布欢迎语')
    assert.equal(config.offline, '已发布离线提示'); assert.equal(config.replies.ja.welcome, 'こんにちは')
    assert.equal(await welcome.inputValue(), '欢迎语未提交草稿')
    checks.push('Independent service save does not publish welcome, offline, language reply or sound drafts')
    await select('外部客服')
    const beforeReplies = writes.length
    await Promise.all([posted(), page.getByRole('button', { name: '保存回复与提示音', exact: true }).click()])
    assert.equal(writes.length, beforeReplies + 1); assert.equal(config.mode, 'off'); assert.equal(config.capacity, 7); assert.equal(config.welcome, '欢迎语未提交草稿')
    assert(await channel.getByRole('radio', { name: '外部客服', exact: true }).isChecked())
    checks.push('Reply save preserves the latest published channel, without publishing its unsaved draft')
    await page.evaluate(() => window.scrollTo(0, 0)); await screenshot('support-settings.png')

    await mount('Settings'); await page.getByRole('tab', { name: '客服配置', exact: true }).click(); await channel.getByRole('spinbutton').waitFor()
    assert(await channel.getByRole('radio', { name: '关闭客服', exact: true }).isChecked()); assert.equal(await channel.getByRole('spinbutton').inputValue(), '7')
    await select('站内客服'); await channel.locator('.el-switch').click(); assert.equal(await channel.getByRole('switch').isChecked(), false); await capacity(9); await saveChannel()
    assert.equal(config.mode, 'internal'); assert.equal(config.inboxEnabled, false); assert.equal(config.capacity, 9); assert.equal(config.welcome, '欢迎语未提交草稿')
    await select('关闭客服'); const beforeBatch = writes.length
    await page.getByRole('button', { name: '保存配置', exact: true }).click(); await page.getByText('配置保存成功', { exact: true }).waitFor()
    assert.equal(writes.length, beforeBatch); assert.equal(config.mode, 'internal')
    await screenshot('system-service-settings.png')
    checks.push('System configuration shares the same saved channels; its batch save cannot publish channel drafts')

    policy = { ...policy, features: { support: true, external_support: false, inbox: true }, supportChannel: 'internal', configs: [{ key: 'support.channel', locked: true, denied: false, version: 1 }] }
    await mount('Settings'); await page.getByRole('tab', { name: '客服配置', exact: true }).click(); await channel.getByRole('spinbutton').waitFor()
    assert.equal(await page.locator('.external-service-config').count(), 0)
    assert(await channel.getByRole('radio', { name: '站内客服', exact: true }).isDisabled())
    checks.push('Internal-only tenants retain the channel module; external URL fields stay hidden and forced modes stay locked')
    await page.setViewportSize({ width: 390, height: 844 })
    assert(await channel.evaluate(el => el.scrollWidth <= el.clientWidth + 1), 'module fits a phone viewport')
    await screenshot('service-settings-mobile.png'); await page.setViewportSize({ width: 1300, height: 900 })

    policy = { ...policy, supportChannel: null, configs: [], features: { support: true, external_support: true, inbox: true } }
    supportSave = false; await mount('Settings'); await page.getByRole('tab', { name: '客服配置', exact: true }).click(); await channel.getByRole('spinbutton').waitFor()
    assert(await save.isHidden()); assert(await channel.getByRole('spinbutton').isDisabled()); assert(await channel.getByRole('switch', { includeHidden: true }).isHidden()); for (const radio of await channel.getByRole('radio').all()) assert(await radio.isDisabled())
    supportView = false; const readCount = requests.filter(item => item.path === '/api/admin/support/settings').length
    await mount('Settings'); await page.getByRole('tab', { name: '客服配置', exact: true }).click()
    assert.equal(await channel.count(), 0); assert.equal(requests.filter(item => item.path === '/api/admin/support/settings').length, readCount)
    checks.push('Existing support read/save permissions are respected in both locations')

    supportView = supportSave = true; policy.configs = [{ key: 'support.settings', locked: true, denied: false, version: 1 }]
    await mount('Settings'); await page.getByRole('tab', { name: '客服配置', exact: true }).click(); await channel.getByRole('spinbutton').waitFor()
    assert(await save.isDisabled()); assert(await channel.getByRole('spinbutton').isDisabled())
    policy.configs = []; policyFailure = true
    await mount('Settings'); await page.getByRole('tab', { name: '客服配置', exact: true }).click(); await channel.getByText('QA 策略加载失败', { exact: true }).waitFor()
    assert(await save.isDisabled()); policyFailure = false; await channel.getByRole('button', { name: '重新加载服务渠道', exact: true }).click(); await channel.getByRole('spinbutton').waitFor()
    checks.push('Policy locks and failed policy loads prevent saving; failed loads can be retried')

    await select('关闭客服'); const beforeFailure = writes.length
    readFailure = true; await save.click(); await page.getByText('QA 配置读取失败', { exact: true }).waitFor(); assert.equal(writes.length, beforeFailure)
    readFailure = false; saveFailure = true; await save.click(); await page.getByText('QA 保存失败', { exact: true }).waitFor(); assert.equal(writes.length, beforeFailure); assert.equal(config.mode, 'internal')
    saveFailure = false; await saveChannel(); assert.equal(config.mode, 'off')
    await mount('SupportSettings'); await channel.getByRole('spinbutton').waitFor(); assert(await channel.getByRole('radio', { name: '关闭客服', exact: true }).isChecked()); assert.equal(await channel.getByRole('spinbutton').inputValue(), '9')
    checks.push('Read/save failures retain drafts without publishing; retry and reload show the same saved configuration')
    assert.deepEqual(errors, [])
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify({ checks, errors, writes: writes.length }, null, 2))
    console.log('PASS ' + checks.join('; '))
  } catch (error) { await page.screenshot({ path: path.join(out, 'failure.png') }).catch(() => {}); throw error }
  finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
