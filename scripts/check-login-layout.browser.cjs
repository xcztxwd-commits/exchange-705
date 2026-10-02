// Browser plugin not available; use installed Playwright with mocked APIs.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const output = path.resolve(__dirname, '../reports/login-layout');
fs.mkdirSync(output, { recursive: true });
(async () => {
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  try {
    for (const width of [1440, 390, 320]) {
      const page = await browser.newPage({ viewport: { width, height: 900 } });
      const errors = [];
      page.on('pageerror', e => errors.push(e.message));
      page.on('console', msg => { if (msg.type() === 'error') errors.push(msg.text()); });
      await page.addInitScript(() => localStorage.setItem('locale', 'ja'));
      await page.routeWebSocket('**/api/ws/market', () => {});
      await page.route(/^https?:\/\/(?!127\.0\.0\.1:5415)/, route => route.fulfill({ status: 200, body: '{}' }));
      await page.route(/\/(?:demo-api|api)\//, async route => {
        const endpoint = new URL(route.request().url()).pathname;
        let body = { success: true, list: [], data: [], balances: [], announcements: [], items: [], content: [], totalPages: 0 };
        if (endpoint.endsWith('/tenant/features')) body = { tenantId: 1, tenantName: 'QA', status: 'ACTIVE', acceptNewBusiness: true, features: {} };
        if (endpoint.includes('timezone')) body = { success: true, timezone: 'UTC' };
        if (endpoint.includes('/price/batch')) body = { ret: 200, data: {} };
        if (endpoint.includes('/support/config')) body = { mode: 'off', inboxEnabled: false };
        await route.fulfill({ json: body });
      });
      await page.goto('http://127.0.0.1:5415/?login=1');
      const dialog = page.locator('.login-dialog:visible');
      await dialog.waitFor();
      await page.locator('.forex-transition').waitFor({ state: 'hidden' });
      assert.ok(await page.title());
      assert.equal(await page.locator('vite-error-overlay').count(), 0);
      await dialog.getByText('メールアドレスでログイン').first().waitFor();
      const layout = await dialog.evaluate(el => {
        const bounds = el.getBoundingClientRect();
        const input = el.querySelector('input').getBoundingClientRect();
        const submit = el.querySelector('button[type="submit"]').getBoundingClientRect();
        return { fits: bounds.left >= 0 && bounds.right <= innerWidth,
          aligned: Math.abs(input.left - submit.left) < 1 && Math.abs(input.right - submit.right) < 1,
          overflow: [...el.querySelectorAll('.login-links, .login-links button, .login-register')].some(e => e.scrollWidth > e.clientWidth + 1 || e.getBoundingClientRect().right > bounds.right) };
      });
      assert.deepEqual(layout, { fits: true, aligned: true, overflow: false });
      await dialog.locator('.el-dialog__headerbtn').click({ trial: true });
      await page.screenshot({ path: path.join(output, `login-ja-${width}.png`) });
      await dialog.locator('.login-forgot').click();
      await page.locator('.el-dialog:visible').getByText('パスワードを忘れた', { exact: false }).first().waitFor();
      await page.goto('http://127.0.0.1:5415/?login=1');
      await dialog.locator('.login-register button').click();
      await page.locator('#pc-register-form').waitFor();
      assert.deepEqual(errors, []);
      console.log(`PASS ${width}: Japanese copy, no clipping, aligned fields/button, forgot/register transitions, no console/runtime errors`);
      await page.close();
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exit(1); });
