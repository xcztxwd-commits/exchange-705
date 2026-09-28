// Run against isolated Vite previews. API calls are mocked: this test never creates accounts.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const output = path.resolve(__dirname, '../reports/registration-security')
const samples = JSON.parse(fs.readFileSync(process.env.CAPTCHA_SAMPLES || path.resolve(__dirname, '../design/captcha-combined-samples.json'), 'utf8'))
const policy = { captchaIpPerMinute: 30, captchaSessionPerMinute: 10, captchaGlobalPerMinute: 600, registerIpPerMinute: 10, registerSessionPerMinute: 5, registerGlobalPerMinute: 120 }
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const report = []
  try {
    for (const app of ['pc', 'mobile']) {
      const page = await browser.newPage({ viewport: app === 'pc' ? { width: 1440, height: 1040 } : { width: 390, height: 960 } })
      const errors = []; page.on('pageerror', e => errors.push(e.message))
      let issues = 0, registrations = 0, mode = 'ok', lastPayload
      await page.addInitScript(() => { localStorage.clear(); sessionStorage.clear(); localStorage.setItem('locale', 'zh-TW') })
      await page.route('**/api/**', async route => {
        const url = new URL(route.request().url()).pathname
        if (url.endsWith('/auth/captcha')) {
          issues++
          assert.match(route.request().postDataJSON().captchaSession, /^[a-f0-9]{32}$/)
          if (mode === 'offline') return route.fulfill({ status: 503, json: { code: 'SECURITY_UNAVAILABLE' } })
          if (mode === 'limited') return route.fulfill({ status: 429, headers: { 'Retry-After': '2' }, json: { code: 'SECURITY_RATE_LIMITED', retryAfter: 2 } })
          return route.fulfill({ json: { captchaId: issues.toString(16).padStart(32, '0'), image: samples[issues % samples.length].image, expiresIn: 120 } })
        }
        if (url.endsWith('/auth/register')) {
          registrations++; lastPayload = route.request().postDataJSON()
          return route.fulfill({ status: 400, json: { code: 'CAPTCHA_INVALID', message: 'invalid captcha' } })
        }
        return route.fulfill({ json: {} })
      })
      await page.goto(`http://127.0.0.1:${app === 'pc' ? 5227 : 5228}/register`)
      const root = app === 'pc' ? page.locator('.el-dialog').filter({ has: page.locator('.captcha-group') }) : page
      const submit = app === 'pc' ? root.locator('.el-dialog__footer button') : root.locator('.primary-btn')
      await page.locator('.captcha-image img').waitFor()
      await page.locator('.forex-transition').waitFor({ state: 'hidden' })
      assert.equal(await submit.isDisabled(), true)
      assert.equal(await page.getByText('驗證碼已更新', { exact: true }).count(), 0)
      assert.equal(await root.getByText(/邀請碼（可選）/).count(), 1)
      await root.locator('input[type=email]').fill('fixture@example.invalid')
      await root.locator('input[type=password]').nth(0).fill('fixture123')
      await root.locator('input[type=password]').nth(1).fill('fixture123')
      await page.locator('#registration-captcha-code').fill('a2B3')
      assert.equal(await submit.isEnabled(), true)
      await page.screenshot({ path: path.join(output, `${app}-register.png`), fullPage: true })
      await submit.click()
      await page.waitForFunction(() => !document.querySelector('.captcha-image').disabled)
      assert.equal(registrations, 1); assert.equal(lastPayload.captchaCode, 'A2B3')
      assert.match(lastPayload.captchaId, /^[a-f0-9]{32}$/)
      assert.equal(await root.locator('input[type=email]').inputValue(), 'fixture@example.invalid')
      assert.equal(await root.locator('input[type=password]').first().inputValue(), 'fixture123')
      assert.equal(await page.locator('#registration-captcha-code').inputValue(), '')
      assert.equal(await submit.isDisabled(), true)

      const before = issues
      await page.locator('.captcha-refresh').click()
      await page.waitForFunction(() => !!document.querySelector('.captcha-image img'))
      assert.equal(issues, before + 1)
      mode = 'offline'; await page.locator('.captcha-refresh').click()
      await page.getByText('安全驗證暫不可用，請稍後重試', { exact: true }).waitFor()
      assert.equal(await submit.isDisabled(), true)
      mode = 'limited'; await page.locator('.captcha-refresh').click()
      await page.getByText(/操作頻繁/).waitFor()
      assert.equal(await page.locator('.captcha-refresh').isDisabled(), true)
      await page.waitForTimeout(2300)
      mode = 'ok'; await page.locator('.captcha-refresh').click()
      await page.locator('.captcha-image img').waitFor()
      assert.equal(await page.locator('#registration-captcha-code').isEnabled(), true)
      // Expiration without waiting two minutes in wall-clock time.
      await page.clock.install(); await page.clock.fastForward(121000)
      await page.getByText('驗證碼已過期，請換一張', { exact: true }).waitFor()
      assert.equal(await submit.isDisabled(), true)
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
      assert.deepEqual(errors, [])
      report.push({ app, issues, registrations, result: 'PASS: refresh, failure, preservation, cooldown, expiry, layout' })
      await page.close()
    }
    const admin = await browser.newPage({ viewport: { width: 1400, height: 1100 } })
    let saved
    await admin.addInitScript(() => { localStorage.setItem('admin_token', 'test-only'); localStorage.setItem('admin_user', JSON.stringify({ role: 'super_admin', isSuperAdmin: true })) })
    await admin.route('**/api/**', async route => {
      if (route.request().url().endsWith('/admin/website-security')) {
        if (route.request().method() === 'PUT') saved = route.request().postDataJSON()
        return route.fulfill({ json: saved || policy })
      }
      return route.fulfill({ json: { success: true, list: [], value: null } })
    })
    await admin.goto('http://127.0.0.1:5229/website-security')
    await admin.getByRole('button', { name: '保存安全配置' }).waitFor()
    await admin.getByRole('spinbutton').first().fill('40')
    await admin.getByRole('button', { name: '保存安全配置' }).click()
    await admin.getByText('网站安全配置已保存，下次请求生效').waitFor()
    assert.equal(saved.captchaIpPerMinute, 40)
    await admin.reload()
    await admin.getByRole('spinbutton').first().waitFor()
    await admin.waitForFunction(() => document.querySelector('[role=spinbutton]')?.value === '40')
    await admin.screenshot({ path: path.join(output, 'admin-security.png'), fullPage: true })
    report.push({ app: 'admin', result: 'PASS: load, edit, save, reload' })
    fs.writeFileSync(path.join(output, 'browser-results.json'), JSON.stringify(report, null, 2))
    console.log(JSON.stringify(report))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
