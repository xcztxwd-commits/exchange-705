// Build PC normally and mobile with `npm run build -- --base=/mobile/`, then run this file.
// Uses isolated headless Chrome, mocked APIs, and no external requests. `--serve` previews locally.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const http = require('node:http');
const path = require('node:path');

const repo = path.resolve(__dirname, '..');
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png' };
const server = http.createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    if (pathname.startsWith('/api/')) {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ code: 200, data: [], message: 'Local test stub' }));
      return;
    }
    const mobile = pathname.startsWith('/mobile/');
    const root = path.join(repo, mobile ? 'exchange-frontend' : 'exchange-pc', 'dist');
    let file = pathname === '/device-layout.js' ? path.join(__dirname, 'device-layout.js')
      : path.resolve(root, '.' + (mobile ? pathname.slice(7) : pathname) + (pathname.endsWith('/') ? 'index.html' : ''));
    if (file !== path.join(__dirname, 'device-layout.js') && !file.startsWith(root + path.sep)) {
      response.writeHead(403).end();
      return;
    }
    let body;
    try { body = await fs.readFile(file); }
    catch (error) {
      if (path.extname(pathname)) throw error;
      file = path.join(root, 'index.html');
      body = await fs.readFile(file);
    }
    if (file.endsWith('index.html')) body = body.toString().replace('</head>',
      `<script src="/device-layout.js" data-layout="${mobile ? 'mobile' : 'pc'}"></script></head>`);
    response.writeHead(200, { 'Content-Type': mime[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    response.end(body);
  } catch { response.writeHead(404).end(); }
});
server.on('upgrade', (_request, socket) => socket.destroy());

(async () => {
  await new Promise(resolve => server.listen(Number(process.env.DEMO_TEST_PORT || 17053), '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  if (process.argv.includes('--serve')) {
    console.log(`Local mocked preview: ${origin}`);
    return;
  }
  const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright');
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' });
  try {
    async function context(options) {
      const result = await browser.newContext(options);
      await result.route('**/*', route => {
        const url = new URL(route.request().url());
        return url.origin === origin ? route.continue() : route.abort();
      });
      await result.routeWebSocket('**/*', socket => socket.close());
      return result;
    }
    async function check(page, url) {
      await page.goto(url);
      await page.waitForFunction(() => document.querySelector('#app')?.children.length > 0);
      assert.equal(await page.locator('.demo-notice').count(), 0);
    }
    const desktop = await context({ viewport: { width: 1280, height: 900 }, locale: 'en-US' });
    const page = await desktop.newPage();
    await check(page, `${origin}/?login=1`);
    await page.reload();
    assert.equal(await page.locator('.demo-notice').count(), 0);
    await desktop.close();

    const mobile = await context({ viewport: { width: 390, height: 844 }, locale: 'zh-CN', isMobile: true, hasTouch: true });
    const phone = await mobile.newPage();
    await check(phone, `${origin}/mobile/register`);
    await mobile.close();
    console.log('PASS: PC/mobile routes render without a top notice.');
  } finally {
    await browser.close();
    server.close();
  }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
