const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const out = path.resolve('reports/visitor-region-20260929');
fs.mkdirSync(out, { recursive: true });

(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  const passed = [];
  try {
    for (const desktop of [false, true]) {
      for (const scenario of ['ip', 'manual', 'offline']) {
        const context = await browser.newContext({ viewport: { width: desktop ? 1440 : 390, height: 960 }, locale: 'fr-FR', timezoneId: 'Europe/Paris' });
        const page = await context.newPage();
        const errors = [];
        page.on('pageerror', e => errors.push(e.message));
        await page.addInitScript(scenario => {
          localStorage.setItem('token', 'region-test');
          localStorage.setItem('user', JSON.stringify({ id: 1, email: 'fixture@example.invalid' }));
          if (scenario === 'manual') {
            localStorage.setItem('locale', 'zh-TW');
            localStorage.setItem('exchange:chart-preferences:v1', JSON.stringify({ timezone: 'America/New_York' }));
          }
        }, scenario);
        let lookups = 0;
        await page.route('https://ipwho.is/**', async route => {
          lookups++;
          if (scenario === 'offline') return route.abort();
          await route.fulfill({ json: { success: true, country_code: 'JP', timezone: { id: 'Asia/Tokyo' } } });
        });
        await page.route('**/api/**', async route => {
          const pathname = new URL(route.request().url()).pathname;
          let data = { success: true, list: [], data: [], symbols: [], orders: [], balances: [], announcements: [] };
          if (pathname === '/api/market/all') data = { list: [{ symbol: 'USDJPY', category: 'Forex', sourceCategory: 'Forex', nameCn: '美元日元', nameEn: 'US Dollar / Yen', baseCurrency: 'USD', quoteCurrency: 'JPY', pricePrecision: 5, isEnabled: true }] };
          if (pathname === '/api/kyc/status') data = { kycStatus: 'VERIFIED', latestRecord: { status: 'APPROVED' } };
          if (pathname.endsWith('/timezone')) data = { timezone: 'America/Los_Angeles' };
          if (pathname.includes('/balance')) data = { available: 10000, balance: 10000 };
          if (pathname.endsWith('/assets')) data = { contractBalance: 10000, fundBalance: 0, optionBalance: 0 };
          await route.fulfill({ json: data });
        });
        const base = desktop ? process.env.PC_URL || 'http://127.0.0.1:5295' : process.env.MOBILE_URL || 'http://127.0.0.1:5293';
        await page.goto(base + (desktop ? '/' : '/trade'));
        await page.locator('.trade-page').waitFor();
        await page.waitForFunction(() => !document.querySelector('.forex-transition:not([hidden])'));
        const expected = scenario === 'manual' ? 'zh-TW' : scenario === 'offline' ? 'fr' : 'ja';
        assert.equal(await page.locator('html').getAttribute('lang'), expected);
        assert.equal(lookups, 1);
        const zone = await page.evaluate(async () => (await import('/src/utils/dateTime.ts')).getSystemTimezone());
        assert.equal(zone, scenario === 'offline' ? 'Europe/Paris' : 'Asia/Tokyo');
        assert.equal(await page.evaluate(() => localStorage.getItem('locale')), scenario === 'manual' ? 'zh-TW' : null);
        const chartZone = await page.locator('.chart-workspace').first().evaluate(element => element.__vueParentComponent.setupState.timezone);
        assert.equal(chartZone, scenario === 'manual' ? 'America/New_York' : zone);
        if (!desktop) {
          await page.evaluate(async () => {
            const element = document.querySelector('.trade-page');
            const trade = element.__vueParentComponent.setupState;
            trade.market.priceMap.USDJPY = { price: 157.245, change24h: 0.0628, changePct24h: 0.04, price24hAgo: 157.1822, firstPriceTime: 1 };
            trade.market.quoteStatusMap.USDJPY = { timestamp: Date.parse('2026-07-01T05:53:56Z'), fetchedAt: 1, expiresAt: 1, status: 'stale' };
          });
          await page.waitForFunction(() => document.querySelector('.quote-time')?.textContent.includes('am') || document.querySelector('.quote-time')?.textContent.includes('pm'));
          assert.equal(await page.locator('.quote-state').count(), 0);
          assert.equal(await page.locator('.mobile-chart .chart-footer').count(), 0);
          assert.equal(await page.locator('.action-dock > p').count(), 0);
          assert.equal(await page.getByText('按市場可成交價格執行', { exact: true }).count(), 0);
          const time = await page.locator('.quote-time').textContent();
          assert.match(time, scenario === 'offline' ? /GMT\+2 7:53:56 am/ : /JST 2:53:56 pm/);
          assert.equal(await page.locator('.action-dock .buy').isDisabled(), true);
          for (const width of [320, 390, 666]) {
            await page.setViewportSize({ width, height: 960 });
            assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, `overflow at ${width}px`);
            const strip = page.locator('.intervals');
            const layout = await strip.evaluate(element => ({
              tops: [...element.children].map(button => button.getBoundingClientRect().top),
              height: element.getBoundingClientRect().height,
              scrollable: element.scrollWidth > element.clientWidth,
            }));
            assert.equal(new Set(layout.tops).size, 1, 'intervals stay on one row');
            assert.ok(layout.height <= 40, 'compact interval strip');
            if (width <= 390) assert.ok(layout.scrollable, 'narrow screens scroll horizontally');
            await strip.locator('button').last().click();
            assert.equal(await strip.locator('button').last().getAttribute('aria-pressed'), 'true');
            const chartBox = await page.locator('.mobile-chart .chart-workspace').boundingBox();
            assert.ok(chartBox.height >= 240, 'chart uses the reclaimed space');
            await strip.locator('button').nth(1).click();
            const timeBox = await page.locator('.quote-time').boundingBox();
            const percentBox = await page.locator('.quote-details > span').boundingBox();
            assert.ok(Math.abs(timeBox.y - percentBox.y) < 3, 'quote time and percentage must align');
          }
          await page.setViewportSize({ width: 390, height: 960 });
        }
        await page.screenshot({ path: path.join(out, `${desktop ? 'pc' : 'mobile'}-${scenario}.png`), fullPage: true });
        assert.deepEqual(errors, []);
        passed.push(`${desktop ? 'pc' : 'mobile'} ${scenario}`);
        await context.close();
      }
    }
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify({ passed }, null, 2));
    console.log(passed.join('\n'));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
