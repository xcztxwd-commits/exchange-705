// Permanent UI regression: actual admin/client components with an isolated, stateful API fixture.
// No shared database writes. Browser plugin skill not available; use installed Playwright.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const playwrightPath = process.env.PLAYWRIGHT_MODULE || 'playwright';
const { chromium } = require(playwrightPath);
const root = path.resolve(__dirname, '..');
const out = process.env.EVIDENCE_DIR;
if (!out || !path.isAbsolute(out)) throw Error('EVIDENCE_DIR must be an absolute path outside the repository');
if (path.resolve(out).startsWith(root + path.sep)) throw Error('Save browser evidence outside the repository');
fs.mkdirSync(out, { recursive: true });
const urls = {
  admin: process.env.ADMIN_URL || 'http://127.0.0.1:5419',
  mobile: process.env.MOBILE_URL || 'http://127.0.0.1:5413',
  pc: process.env.PC_URL || 'http://127.0.0.1:5415',
};
for (const value of Object.values(urls)) if (!['localhost', '127.0.0.1'].includes(new URL(value).hostname)) throw Error('Only isolated local servers are allowed');

async function safeBrowser() {
  const core = require.resolve('playwright-core', { paths: [path.dirname(require.resolve(playwrightPath))] });
  const source = fs.readFileSync(path.join(path.dirname(core), 'lib/coreBundle.js'), 'utf8');
  const block = source.match(/disabledFeatures = (\[[\s\S]*?\])\.filter\(Boolean\);/);
  assert.ok(block, 'Inspect installed driver defaults before launching a new Windows profile');
  const defaults = vm.runInNewContext(block[1]).filter(Boolean);
  const flag = '--disable-features=' + [...new Set([...defaults, 'AutofillAiWalletPrivatePasses', 'AutofillAmbientAutofill'])].join(',');
  const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true, chromiumSandbox: true,
    ignoreDefaultArgs: ['--disable-features=' + defaults.join(',')], args: [flag, '--enable-automation'] });
  const session = await browser.newBrowserCDPSession();
  const { arguments: actual } = await session.send('Browser.getBrowserCommandLine');
  assert.deepEqual(actual.filter(arg => /^(?:--|-|\/)disable-features=/i.test(arg)), [flag]);
  const end = actual.findIndex(arg => arg === '--' || /^(?:--|-|\/)single-argument(?:=|$)/.test(arg));
  assert.ok(end < 0 || actual.indexOf(flag) < end);
  fs.writeFileSync(path.join(out, 'browser-safety.json'), JSON.stringify({ featureSwitch: flag, verified: true }, null, 2));
  await session.detach();
  return browser;
}
const knownWarning = message => message.startsWith('[Vue warn]: Runtime directive used on component with non-element root node.') && message.includes('at <ElDropdownItem ') && message.includes('at <Layout ')
  || message.startsWith('ElementPlusError: [el-checkbox] [API] label act as value is about to be deprecated in version 3.0.0, please use value instead.')
  // This fixture intentionally contains no market instruments; it tests announcements only.
  || message === '[Home] ⚠️ No symbols loaded, skipping subscription';
function health(page) {
  const errors = [], warnings = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => {
    if (message.type() === 'error') errors.push(message.text());
    if (message.type() === 'warning' && !knownWarning(message.text())) warnings.push(message.text());
  });
  return async () => {
    assert.equal(await page.locator('vite-error-overlay,nextjs-portal').count(), 0);
    assert.ok((await page.locator('body').innerText()).trim().length > 20);
    assert.deepEqual(errors, [], 'No application runtime or console errors');
    assert.deepEqual(warnings, [], 'No unexplained application warnings');
  };
}
async function checkDocument(page, title, name) {
  const document = page.locator('.announcement-document').last();
  await document.waitFor({ state: 'visible' });
  assert.ok((await document.innerText()).includes(title));
  assert.equal(await document.locator('[data-lang],.languages').count(), 0, 'No language toggle inside notice');
  const geometry = await document.evaluate(element => {
    const box = element.getBoundingClientRect();
    const body = element.querySelector('.announcement-content');
    const footer = element.querySelector('.announcement-footer');
    const bounds = footer.getBoundingClientRect();
    return { fits: box.left >= -1 && box.right <= innerWidth + 1 && box.top >= -1 && box.bottom <= innerHeight + 1,
      noOverflow: body.scrollWidth <= body.clientWidth + 1,
      footerVisible: bounds.top >= 0 && bounds.bottom <= innerHeight + 1 };
  });
  assert.deepEqual(geometry, { fits: true, noOverflow: true, footerVisible: true }, name + ' layout');
  assert.equal(await document.locator('table').evaluate(element => getComputedStyle(element).borderCollapse), 'separate', 'Global PC reset must not remove rounded table borders');
  await document.locator('.announcement-content').evaluate(element => { element.scrollTop = 0; });
  await page.screenshot({ path: path.join(out, name + '.png'), animations: 'disabled' });
  await document.locator('.announcement-content').evaluate(element => { element.scrollTop = element.scrollHeight; });
  assert.ok(await document.locator('.announcement-signoff').evaluate(element => {
    const text = element.getBoundingClientRect(), body = element.closest('.announcement-content').getBoundingClientRect();
    return text.top >= body.top - 1 && text.bottom <= body.bottom + 1;
  }), 'Final paragraph is reachable');
  return document;
}

(async () => {
  const browser = await safeBrowser();
  const results = [];
  let savedCampaign, activePage;
  try {
    const context = await browser.newContext({ viewport: { width: 1600, height: 1050 }, reducedMotion: 'reduce' });
    const page = await context.newPage(), checkHealth = health(page), writes = [], rows = [];
    activePage = page;
    await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ token: 'announcement-fixture-admin', mode: 'admin', user: { id: 1, tenantId: 1, userType: 'admin' } })));
    await page.route('**/api/**', async route => {
      const request = route.request(), url = new URL(request.url()), endpoint = url.pathname, method = request.method();
      const reply = data => route.fulfill({ json: data });
      if (endpoint.endsWith('/menus/current')) return reply({ success: true, menus: [{ id: 1, menuCode: 'announcement', menuName: '公告管理', path: '/announcement' }], groups: [], actions: { announcement: ['create', 'edit', 'delete', 'detail'] } });
      if (endpoint.endsWith('/announcement/list')) return reply([]);
      if (endpoint.includes('/table-preferences/')) return reply([]);
      if (endpoint.endsWith('/activities') && method === 'GET') {
        const list = rows.filter(row => row.template === (url.searchParams.get('template') === 'true'));
        return reply({ content: list, totalElements: list.length, totalPages: list.length ? 1 : 0 });
      }
      if (endpoint.includes('/activities') && method !== 'GET') {
        const body = request.postDataJSON();
        writes.push({ endpoint, method, body });
        if (method === 'POST' && endpoint.endsWith('/activities')) {
          const row = { ...body, id: rows.length + 1, rowVersion: 0, accountMode: 'REAL', claimCount: 0, granted: 0 };
          rows.push(row); return reply(row);
        }
        const id = Number(endpoint.match(/activities\/(\d+)/)?.[1]), row = rows.find(item => item.id === id);
        assert.ok(row, 'Only known fixture records can be updated');
        Object.assign(row, body, { rowVersion: row.rowVersion + 1 });
        return reply(row);
      }
      return reply({ success: true, list: [], content: [], total: 0, totalPages: 0, data: {} });
    });
    await page.goto(urls.admin + '/announcement');
    await page.getByText('活动公告 · 体验金', { exact: true }).click();
    await page.getByRole('tab', { name: '模板', exact: true }).click();
    const builtin = page.locator('[data-testid="celebration-template"]');
    await builtin.waitFor();
    assert.equal(writes.length, 0, 'Listing built-in template does not write or send');
    await builtin.getByRole('button', { name: '编辑并保存模板', exact: true }).click();
    const editor = page.locator('.announcement-studio');
    await editor.waitFor();
    await editor.getByRole('textbox', { name: '公告标题', exact: true }).fill('百万用户庆典 · 编辑验证');
    await editor.getByRole('textbox', { name: '第1档奖励金额', exact: true }).fill('18,889');
    assert.ok((await editor.locator('.announcement-document').innerText()).includes('18,889'));
    await editor.getByRole('button', { name: '手机', exact: true }).click();
    await checkDocument(page, '百万用户庆典 · 编辑验证', 'editor-mobile-preview');
    await editor.getByRole('button', { name: '电脑', exact: true }).click();
    await checkDocument(page, '百万用户庆典 · 编辑验证', 'editor-desktop-preview');
    await editor.getByRole('button', { name: '保存模板', exact: true }).click();
    await editor.waitFor({ state: 'hidden' });
    assert.equal(rows.length, 1);
    assert.equal(rows[0].template, true);
    assert.equal(rows[0].status, 'DRAFT');
    assert.equal(rows[0].autoSendEnabled, false);
    assert.equal(rows[0].campaignKind, 'NOTICE');
    assert.equal(Number(rows[0].amount), 0);
    assert.equal(Number(rows[0].budget), 0);
    const savedTemplate = rows[0];
    const row = page.getByRole('row').filter({ hasText: savedTemplate.name });
    await row.getByRole('button', { name: '编辑', exact: true }).click();
    await editor.waitFor();
    assert.equal(await editor.getByRole('textbox', { name: '公告标题', exact: true }).inputValue(), '百万用户庆典 · 编辑验证');
    assert.equal(await editor.getByRole('textbox', { name: '第1档奖励金额', exact: true }).inputValue(), '18,889');
    await editor.getByRole('combobox', { name: '编辑公告语言', exact: true }).press('Enter');
    await page.getByRole('option', { name: '日本語 · 已配置', exact: true }).click();
    await editor.getByRole('button', { name: /移除.*语言/ }).click();
    await page.getByRole('dialog', { name: '移除语言', exact: true }).getByRole('button', { name: '移除', exact: true }).click();
    await editor.getByRole('combobox', { name: '编辑公告语言', exact: true }).press('Enter');
    await page.getByRole('option', { name: '日本語 · 新增', exact: true }).click();
    assert.equal(await editor.getByRole('textbox', { name: '公告标题', exact: true }).inputValue(), '百万用户庆典 · 编辑验证', 'Missing language is copied from the editable reactive fallback');
    await editor.getByRole('button', { name: '取消', exact: true }).click();
    await page.getByRole('dialog', { name: '退出编辑器', exact: true }).getByRole('button', { name: '退出', exact: true }).click();
    await editor.waitFor({ state: 'hidden' });
    await row.getByRole('button', { name: '使用模板', exact: true }).click();
    await editor.waitFor();
    await editor.getByRole('button', { name: '保存活动', exact: true }).click();
    await editor.waitFor({ state: 'hidden' });
    assert.equal(rows.length, 2);
    savedCampaign = rows[1];
    assert.equal(savedCampaign.template, false);
    assert.equal(savedCampaign.status, 'DRAFT');
    const campaignRow = page.getByRole('row').filter({ hasText: savedCampaign.name });
    await campaignRow.getByRole('button', { name: '公告发送设置', exact: true }).click();
    const settings = page.getByRole('dialog', { name: '公告发送设置' });
    await settings.waitFor();
    assert.equal(await settings.getByRole('spinbutton', { name: '每人体验金', exact: true }).count(), 0);
    assert.equal(await settings.getByRole('switch', { name: '允许重复领取', exact: true }).count(), 0);
    assert.equal(await settings.getByRole('switch', { name: '允许同一用户重复发送', exact: true }).getAttribute('aria-checked'), 'true');
    assert.equal(await settings.getByRole('switch', { name: '进入页面自动弹出', exact: true }).getAttribute('aria-checked'), 'true');
    await settings.getByRole('switch', { name: '开启自动发送', exact: true }).locator('..').click();
    await settings.locator('.el-form-item').filter({ hasText: '活动状态' }).locator('.el-select').click();
    await page.getByRole('option', { name: '启用', exact: true }).click();
    await settings.getByRole('button', { name: '保存设置', exact: true }).click();
    await settings.waitFor({ state: 'hidden' });
    assert.deepEqual(savedCampaign.triggerConditions, ['LOGIN']);
    assert.equal(savedCampaign.autoSendEnabled, true);
    assert.equal(savedCampaign.status, 'ACTIVE');
    assert.equal(savedCampaign.repeatUnread, true);
    assert.ok(savedCampaign.positions.includes('AUTH_TRADE'));
    assert.equal(savedCampaign.endsAt, '2026-11-22T00:00:00');
    assert.ok(writes.every(write => !/\/send(?:-selection)?$|\/claim$/.test(write.endpoint)), 'Editing and templates never send or claim');
    await checkHealth();
    results.push('Admin: built-in -> edit content/tiers -> responsive preview -> save template -> reopen -> use template -> save draft -> configure LOGIN');
    fs.writeFileSync(path.join(out, 'saved-template.json'), JSON.stringify(savedTemplate, null, 2));
    await context.close();

    // Use exact content saved by the real editor in the user-side flow.
    for (const [app, edition, lang, width, height, longCopy] of [
      ['mobile', '', 'zh-TW', 390, 844], ['mobile', 'advanced', 'ja', 390, 844],
      ['pc', '', 'ja', 1440, 1000], ['pc', '', 'zh-TW', 1024, 768], ['mobile', '', 'ja', 320, 720],
      ['mobile', '', 'ja', 320, 480, true],
    ]) {
      const context = await browser.newContext({ viewport: { width, height }, reducedMotion: 'reduce' });
      const page = await context.newPage(), checkHealth = health(page), requests = [];
      activePage = page;
      const campaign = structuredClone(savedCampaign);
      if (longCopy) {
        const design = JSON.parse(campaign.layoutJson), document = design.locales[lang].announcement;
        document.title = 'LongTitle'.repeat(22);
        document.label = 'A'.repeat(200);
        document.supportLabel = 'サポート'.repeat(40);
        document.acknowledgeLabel = '確認'.repeat(100);
        document.footerNote = '利用条件'.repeat(50);
        document.tiers[0] = { deposit: '9'.repeat(80), reward: '8'.repeat(80) };
        campaign.layoutJson = JSON.stringify(design);
      }
      let authenticated = false, opened = false, closed = false, logins = 0;
      const user = { id: 7, tenantId: 1, email: 'notice-user@example.test', nickname: 'Notice preview' };
      await page.addInitScript(({ lang, edition }) => { localStorage.setItem('locale', lang); if (edition) localStorage.setItem('ui-edition', edition); }, { lang, edition });
      await page.routeWebSocket(/\/(?:demo-api|api)\/ws\/market/, () => {});
      await page.route('**/*', async route => {
        const request = route.request(), url = new URL(request.url()), endpoint = url.pathname, method = request.method();
        if (url.hostname.endsWith('.kaspersky-labs.com')) return route.continue();
        if (!['127.0.0.1', 'localhost'].includes(url.hostname)) return route.fulfill({ json: {} });
        if (!/^\/(?:demo-api|api)\//.test(endpoint)) return route.continue();
        requests.push({ endpoint, method, body: method === 'POST' ? request.postDataJSON() : undefined });
        const reply = data => route.fulfill({ json: data });
        if (endpoint.endsWith('/tenant/features')) return reply({ tenantId: 1, tenantName: 'Notice fixture', status: 'ACTIVE', acceptNewBusiness: true, features: { activity: true, simulation: true, registration: true } });
        if (endpoint.endsWith('/auth/login')) { authenticated = true; opened = closed = false; logins++; return reply({ token: 'notice-fixture-' + logins, user }); }
        if (endpoint.endsWith('/activity/inbox')) return reply({ content: authenticated ? [{ delivery: { id: 11, openedAt: opened ? '2026-10-21T01:00:00' : null, closedAt: closed ? '2026-10-21T01:00:00' : null }, campaign: { ...campaign, hasQuota: true }, active: true }] : [], totalPages: authenticated ? 1 : 0 });
        if (/\/activity\/messages\/11\/event$/.test(endpoint)) {
          const type = request.postDataJSON().type;
          if (type === 'OPENED') opened = true;
          if (type === 'CLOSED') closed = true;
          return reply({ id: 11, openedAt: opened ? '2026-10-21T01:00:00' : null, closedAt: closed ? '2026-10-21T01:00:00' : null });
        }
        if (endpoint.includes('/support/config')) return reply({ mode: 'off', inboxEnabled: true });
        if (endpoint.includes('/support/unified-inbox')) return reply({ unread: 0, content: [], totalPages: 0 });
        if (endpoint.endsWith('/user/assets')) return reply({ success: true, serverNow: new Date().toISOString(), fundBalance: 0, contractBalance: 0, optionBalance: 0, trialEligible: false, fundingSources: [], trialAvailable: 0, trialFrozen: 0 });
        if (endpoint.endsWith('/auth/activity')) return reply({ success: true });
        if (endpoint.includes('/price/batch')) return reply({ ret: 200, data: {} });
        if (endpoint.includes('/timezone')) return reply({ success: true, timezone: 'UTC' });
        return reply({ success: true, list: [], data: [], balances: [], announcements: [], items: [], content: [], totalPages: 0, symbols: [], categories: [] });
      });
      const mobile = app === 'mobile';
      await page.goto(urls[app] + (mobile ? '/login' + (edition ? '?edition=' + edition : '') : '/?login=1'));
      if (mobile) { await page.locator('#login-account').fill(user.email); await page.locator('#login-password').fill('fixture-only-password'); await page.locator('form button[type="submit"]').click(); }
      else { const form = page.locator('#pc-login-form'); await form.locator('input').first().fill(user.email); await form.locator('input[type="password"]').fill('fixture-only-password'); await page.locator('button[type="submit"][form="pc-login-form"]').click(); }
      const title = longCopy ? 'LongTitle'.repeat(22) : lang === 'ja' ? '100万人達成記念' : '百萬用戶慶典';
      const name = [app, edition || 'classic', lang, width, longCopy ? 'long-copy' : 'default'].join('-');
      const document = await checkDocument(page, title, name);
      assert.equal(logins, 1);
      await document.locator('.announcement-primary').click();
      await page.locator('dialog.announcement-layout').waitFor({ state: 'hidden' });
      assert.equal(opened, true);
      assert.ok(requests.every(request => !/\/claim(?:$|\?)/.test(request.endpoint)), 'Pure notice never calls claim');
      // A new auth session must be able to show the same notification again.
      await page.evaluate(async user => { const { useAuthStore } = await import('/src/store/auth.ts'); useAuthStore().logout(); }, user);
      authenticated = false; opened = closed = false;
      await page.evaluate(async user => { const { useAuthStore } = await import('/src/store/auth.ts'); useAuthStore().setAuth('notice-fixture-relogin', user); }, user);
      authenticated = true;
      await page.evaluate(() => window.dispatchEvent(new Event('activity-deliveries-changed')));
      const reopened = await checkDocument(page, title, name + '-relogin');
      await reopened.locator('.announcement-secondary').click();
      await page.waitForURL('**/customer-service');
      await page.locator('dialog.announcement-layout').waitFor({ state: 'hidden' });
      await checkHealth();
      results.push(name + ': real login form -> automatic localized modal -> read/close -> new session -> modal -> existing support route');
      console.log('PASS ' + name);
      await context.close();
    }
    fs.writeFileSync(path.join(out, 'result.json'), JSON.stringify({ success: true, fixture: true, urls, results }, null, 2));
    console.log('PASS ' + results.length + ' integrated UI scenarios\n' + results.join('\n'));
  } catch (error) {
    if (activePage && !activePage.isClosed()) {
      await activePage.screenshot({ path: path.join(out, 'failure.png'), animations: 'disabled' }).catch(() => {});
      fs.writeFileSync(path.join(out, 'failure-dom.txt'), await activePage.locator('body').innerText().catch(() => 'No body'));
    }
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
