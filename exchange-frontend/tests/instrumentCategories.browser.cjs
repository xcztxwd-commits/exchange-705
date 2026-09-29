const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    await page.addInitScript(() => { localStorage.setItem('locale', 'zh-TW'); localStorage.setItem('token', 'category-test'); });
    await page.route('**/api/**', route => route.fulfill({ json: { list: [], data: [], success: true } }));
    await page.goto((process.env.MOBILE_URL || 'http://127.0.0.1:5293') + '/trade');
    await page.locator('.trade-page').waitFor();
    await page.evaluate(() => {
      let c = document.querySelector('.trade-page').__vueParentComponent;
      while (c && !c.setupState.catalog) c = c.parent;
      window.trade = c.setupState;
      window.trade.catalog = [
        { symbol: 'USDJPY', category: 'Forex', baseCurrency: 'USD', quoteCurrency: 'JPY' },
        { symbol: 'EURUSD', category: 'Forex', baseCurrency: 'EUR', quoteCurrency: 'USD' },
        { symbol: 'AAPL', category: 'USStock', nameCn: '蘋果', nameEn: 'Apple' },
        { symbol: 'XAUUSD', category: 'Metal', nameCn: '黃金' },
        { symbol: 'DISABLED', category: 'Oil', isEnabled: false }
      ];
    });
    await page.locator('.symbol-select').click();
    const dialog = page.locator('dialog[open]');
    const tabs = dialog.locator('.symbol-categories button');
    assert.deepEqual(await tabs.allTextContents(), ['全部', '外匯', '美股', '貴金屬']);
    assert.equal(await dialog.locator('.symbol-item').count(), 4);
    await tabs.filter({ hasText: '美股' }).click();
    assert.equal(await dialog.locator('.symbol-item').count(), 1);
    await dialog.locator('input').fill(' EUR ');
    assert.equal(await dialog.locator('.symbol-item').count(), 0);
    assert.equal(await tabs.count(), 4, 'Search must not remove category buttons');
    await dialog.locator('input').fill(' apple ');
    assert.equal(await dialog.locator('.symbol-item').count(), 1);
    await dialog.locator('input').fill('');
    await page.evaluate(() => { window.trade.catalog = window.trade.catalog.filter(s => s.category !== 'USStock'); });
    await page.waitForFunction(() => window.trade.symbolCategory === '');
    assert.deepEqual(await tabs.allTextContents(), ['全部', '外匯', '貴金屬']);
    assert.equal(await dialog.evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(255, 255, 255)');
    assert.equal(await tabs.first().evaluate(el => getComputedStyle(el).backgroundColor), 'rgb(237, 245, 223)');
    await page.evaluate(() => {
      for (const category of ['US', 'CFD', 'Oil', 'Crypto', 'CryptoPerpetual']) window.trade.catalog.push({ symbol: category + 'TEST', category });
    });
    await page.setViewportSize({ width: 320, height: 740 });
    assert.equal(await dialog.locator('.symbol-categories').evaluate(el => el.scrollWidth > el.clientWidth), true);
    assert.equal(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth), true, 'No dialog overflow');
    await page.setViewportSize({ width: 390, height: 844 });
    fs.mkdirSync('reports/instrument-categories', { recursive: true });
    await page.screenshot({ path: 'reports/instrument-categories/mobile.png' });
    await dialog.locator('.symbol-item').first().click();
    await dialog.waitFor({ state: 'hidden' });
    assert.equal(await page.evaluate(() => window.trade.currentSymbol), 'USDJPY');
    console.log('PASS: categories, disabled/empty hiding, search, removed-category fallback, colors, 320px overflow, instrument selection');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
