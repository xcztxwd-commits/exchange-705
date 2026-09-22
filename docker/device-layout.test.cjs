// Run after `docker compose up -d --build pc mobile`.
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright');

(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
    const origin = 'http://127.0.0.1:17050';
    async function check(path, layout) {
      await page.waitForURL(url => url.pathname === path);
      await page.waitForFunction(() => document.querySelector('#app')?.children.length > 0);
      assert.equal(await page.locator('script[data-layout]').getAttribute('data-layout'), layout);
      assert.equal(new URL(page.url()).origin, origin);
    }
    await page.goto(origin);
    await check('/', 'pc');
    await page.evaluate(() => localStorage.setItem('layout-test', 'preserved'));
    for (const width of [400, 769, 768, 1280]) {
      await page.setViewportSize({ width, height: 900 });
      await check(width <= 768 ? '/mobile/' : '/', width <= 768 ? 'mobile' : 'pc');
      assert.equal(await page.evaluate(() => localStorage.getItem('layout-test')), 'preserved');
    }
    await page.setViewportSize({ width: 400, height: 811 });
    await check('/mobile/', 'mobile');
    await page.goto(`${origin}/#/?register=1`);
    await check('/mobile/', 'mobile');
    assert.equal(new URL(page.url()).hash, '#/register');
    await page.setViewportSize({ width: 1280, height: 900 });
    await check('/', 'pc');
    const phone = await browser.newPage({ viewport: { width: 400, height: 811 }, isMobile: true, hasTouch: true });
    await phone.goto(origin);
    await phone.waitForURL('**/mobile/**');
    await phone.waitForFunction(() => document.querySelector('#app')?.children.length > 0);
    assert.equal(await phone.locator('script[data-layout]').getAttribute('data-layout'), 'mobile');
    await phone.reload();
    await phone.waitForFunction(() => document.querySelector('#app')?.children.length > 0);
    assert.equal(new URL(phone.url()).pathname, '/mobile/');
    console.log('PASS: initial phone load, reload, resize both ways, 768/769 boundary, same-origin storage, registration route');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
