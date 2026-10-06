// Run against local Vite servers. All APIs are mocked; no accounts are created.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path')
const output = path.resolve(__dirname, '../reports/registration-ui-20261006')
const image = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aLl0AAAAASUVORK5CYII='

;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const passed = []
  try {
    for (const app of ['pc', 'mobile', 'advanced']) {
      const desktop = app === 'pc'
      const context = await browser.newContext({ viewport: { width: desktop ? 1280 : 390, height: 1040 } })
      const page = await context.newPage(), errors = []
      let submissions = 0, submitted, fieldMode = 'optional'
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(() => { localStorage.setItem('locale', 'zh-TW'); localStorage.setItem('theme', 'dark') })
      await page.route('**/*', async route => {
        const url = new URL(route.request().url())
        if (url.hostname === 'ipwho.is') return route.fulfill({ json: { success: true, country_code: 'MY', calling_code: '60', timezone: { id: 'Asia/Kuala_Lumpur' } } })
        if (!['127.0.0.1', 'localhost'].includes(url.hostname)) return route.abort()
        if (!url.pathname.startsWith('/api/')) return route.continue()
        if (url.pathname === '/api/auth/register') {
          if (route.request().method() === 'GET') return route.fulfill({ json: {
            phone: { enabled: fieldMode !== 'disabled', required: fieldMode === 'required' },
            annualIncome: { enabled: fieldMode !== 'disabled', required: fieldMode === 'required' },
            currencies: ['USD', 'MYR', 'SGD'], maxAnnualIncome: '999999999999.99',
          } })
          submissions++; submitted = route.request().postDataJSON()
          return route.fulfill({ status: 400, json: { code: 'CAPTCHA_INVALID', message: 'Invalid CAPTCHA' } })
        }
        if (url.pathname === '/api/auth/captcha') return route.fulfill({ json: { captchaId: 'a'.repeat(32), image, expiresIn: 120 } })
        if (url.pathname === '/api/tenant/features') return route.fulfill({ json: { tenantId: 1, status: 'ACTIVE', acceptNewBusiness: true, features: { registration: true } } })
        return route.fulfill({ json: { success: true, list: [], data: [], categories: [], symbols: [] } })
      })
      const base = desktop ? process.env.PC_URL || 'http://127.0.0.1:17056' : process.env.MOBILE_URL || 'http://127.0.0.1:17057'
      const url = base + '/register' + (app === 'advanced' ? '?edition=advanced' : '')
      await page.goto(url)
      const root = desktop ? page.locator('.el-dialog').filter({ has: page.locator('#pc-register-form') }) : page.locator('.auth-card')
      await root.locator('#registration-phone').waitFor()
      await page.locator('.forex-transition').waitFor({ state: 'hidden' })
      assert.equal(await root.locator('.registration-profile input').count(), 2, 'manual calling-code input removed')
      assert.equal(await root.getByText(/手动输入区号|Enter calling code manually/).count(), 0)
      assert.equal(await root.locator('.registration-profile label').allTextContents().then(labels => labels.some(label => /可選|可选|optional/.test(label))), false)

      for (const dark of desktop ? [true, false, true] : [false]) {
        if (desktop) await page.evaluate(dark => document.documentElement.classList.toggle('dark', dark), dark)
        await root.locator('input[type=email]').evaluate(async element => {
          getComputedStyle(element).backgroundColor
          await Promise.all(element.getAnimations().map(animation => animation.finished.catch(() => {})))
        })
        const styles = await root.evaluate(element => {
          const read = selector => getComputedStyle(element.querySelector(selector)).backgroundColor
          return { email: read('input[type=email]'), phone: read('#registration-phone'), income: read('#registration-income'), labels: [...element.querySelectorAll('.registration-profile label')].map(label => getComputedStyle(label).color) }
        })
        assert.equal(styles.phone, styles.email, 'phone background matches existing inputs')
        assert.equal(styles.income, styles.email, 'income background matches existing inputs')
        if (dark) assert.deepEqual(styles.labels, ['rgb(255, 255, 255)', 'rgb(255, 255, 255)'])
      }

      const dial = root.locator('.dial')
      assert.match(await dial.locator('img').getAttribute('src'), /\/my\.svg$/)
      await dial.locator('button').click()
      const menu = page.locator('.app-select__menu')
      await menu.getByRole('option').first().waitFor()
      assert.equal(await menu.getByRole('option').count(), 56)
      await page.waitForFunction(() => [...document.querySelectorAll('.app-select__menu [role=option]')].every(option => { const image = option.querySelector('img'); return image?.complete && image.naturalWidth > 0 }))
      await menu.getByRole('option', { name: '+65', exact: true }).click()
      assert.equal((await dial.innerText()).trim(), '+65')
      assert.match(await dial.locator('img').getAttribute('src'), /\/sg\.svg$/)
      await root.locator('#registration-phone').fill('8123-4567')
      await root.locator('#registration-income').fill('0.00')

      const invite = root.locator('.registration-invite'), summary = invite.locator('summary'), input = invite.locator('input')
      assert.equal(await summary.textContent(), '已有邀请码')
      assert.equal(await summary.evaluate(element => getComputedStyle(element).fontSize), '12px')
      assert.equal(await input.isVisible(), false)
      await summary.focus(); await summary.press('Enter')
      assert.equal(await input.isVisible(), true)
      assert.equal(submissions, 0, 'expanding invitation code never submits the form')
      await input.fill(' INVITE-TEST ')
      await summary.click()
      assert.equal(await input.isVisible(), false)
      await summary.click()
      assert.equal(await input.inputValue(), ' INVITE-TEST ', 'collapsing preserves input')
      await root.locator('input[type=email]').fill('fixture@example.invalid')
      await root.locator('input[type=password]').nth(0).fill('fixture123')
      await root.locator('input[type=password]').nth(1).fill('fixture123')
      await root.locator('#registration-captcha-code').fill('A2B3')
      const submit = desktop ? root.locator('button[form=pc-register-form]') : root.locator('.primary-btn,.primary')
      await page.waitForFunction(selector => !document.querySelector(selector)?.disabled, desktop ? 'button[form=pc-register-form]' : '.auth-card .primary-btn,.auth-card .primary')
      await page.screenshot({ path: path.join(output, `${app}-expanded.png`), fullPage: true })
      await Promise.all([
        page.waitForResponse(response => response.url().endsWith('/auth/register') && response.request().method() === 'POST'),
        submit.click(),
      ])
      assert.equal(submissions, 1)
      assert.equal(submitted.invitationCode, 'INVITE-TEST')
      assert.equal(submitted.countryCode, '+65'); assert.equal(submitted.phone, '81234567')
      assert.equal(submitted.annualIncome, '0.00'); assert.equal(submitted.annualIncomeCurrency, 'MYR')
      assert.equal(await input.inputValue(), ' INVITE-TEST ', 'registration failure preserves invitation')

      fieldMode = 'required'
      await page.goto(desktop ? base + '/?register=1&invite=LINK-CODE' : url + (app === 'advanced' ? '&' : '?') + 'invite=LINK-CODE')
      await root.locator('.registration-profile .required').first().waitFor()
      assert.equal(await root.locator('.registration-profile .required').count(), 2)
      assert.equal(await root.locator('#registration-phone').getAttribute('aria-required'), 'true')
      assert.equal(await root.locator('#registration-income').getAttribute('aria-required'), 'true')
      await invite.locator('summary').click()
      assert.equal(await input.inputValue(), 'LINK-CODE')
      assert.equal(await input.getAttribute('readonly'), '')
      await invite.locator('summary').click()
      await page.screenshot({ path: path.join(output, `${app}-collapsed.png`), fullPage: true })

      if (!desktop) for (const width of [320, 390, 592]) {
        await page.setViewportSize({ width, height: 1040 })
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${app} overflow at ${width}px`)
      }
      fieldMode = 'disabled'
      await Promise.all([
        page.waitForResponse(response => response.url().endsWith('/auth/register') && response.request().method() === 'GET'),
        page.goto(url),
      ])
      await root.locator('.registration-profile').waitFor({ state: 'attached' })
      assert.equal(await root.locator('.registration-profile input').count(), 0)
      assert.deepEqual(errors, [])
      passed.push(`${app}: colors, flags, disclosure, referral, payload, required policy, layout`)
      await context.close()
    }
    fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify({ passed }, null, 2))
    console.log(passed.join('\n'))
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
