// Actual admin UI with an isolated API fixture; this test never writes to a database.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const runtime = process.env.PLAYWRIGHT_MODULE || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright';
const { chromium } = require(runtime);
const base = process.env.ADMIN_URL || 'http://127.0.0.1:5419';
const output = process.env.EVIDENCE_DIR;
assert.ok(['localhost', '127.0.0.1'].includes(new URL(base).hostname), 'Only isolated local servers are allowed');
assert.ok(output && path.isAbsolute(output) && !path.resolve(output).startsWith(path.resolve(__dirname, '..') + path.sep), 'Evidence must be saved outside the repository');
fs.mkdirSync(output, { recursive: true });

async function safeBrowser() {
  const core = require.resolve('playwright-core', { paths: [path.dirname(require.resolve(runtime))] });
  const source = fs.readFileSync(path.join(path.dirname(core), 'lib/coreBundle.js'), 'utf8');
  const block = source.match(/disabledFeatures = (\[[\s\S]*?\])\.filter\(Boolean\);/);
  assert.ok(block, 'Inspect browser driver defaults before creating a Windows profile');
  const defaults = vm.runInNewContext(block[1]).filter(Boolean);
  const flag = '--disable-features=' + [...new Set([...defaults, 'AutofillAiWalletPrivatePasses', 'AutofillAmbientAutofill'])].join(',');
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe',
    ignoreDefaultArgs: ['--disable-features=' + defaults.join(',')], args: [flag, '--enable-automation'] });
  const session = await browser.newBrowserCDPSession();
  const { arguments: actual } = await session.send('Browser.getBrowserCommandLine');
  assert.deepEqual(actual.filter(arg => /^(?:--|-|\/)disable-features=/i.test(arg)), [flag]);
  const end = actual.findIndex(arg => arg === '--' || /^(?:--|-|\/)single-argument(?:=|$)/.test(arg));
  assert.ok(end < 0 || actual.indexOf(flag) < end);
  fs.writeFileSync(path.join(output, 'browser-safety.json'), JSON.stringify({ verified: true, featureSwitch: flag }, null, 2));
  await session.detach();
  return browser;
}

const types = [{ value: 'ADMIN_IN', label: '后台入金' }, { value: 'ADMIN_OUT', label: '后台出金' }, { value: 'CONTRACT_FEE', label: '合约手续费' }];
const events = Array.from({ length: 32 }, (_, index) => ({ key: 'adjust-' + index, id: 'adjust-' + index, userId: 10001 + index % 3,
  nickname: ['陈先生', '王女士', '李先生'][index % 3], email: ['chen@example.test', 'wang@example.test', 'li@example.test'][index % 3],
  type: index % 3 === 0 ? 'ADMIN_IN' : index % 3 === 1 ? 'ADMIN_OUT' : 'CONTRACT_FEE',
  typeLabel: types[index % 3].label, amount: index % 3 === 0 ? '1250.50' : index % 3 === 1 ? '-200.25' : '-5.75',
  accountType: index % 3 === 2 ? 'CONTRACT' : 'FUND', currency: 'USD', originalCurrency: 'USD', originalAmount: null,
  balanceBefore: index % 3 === 2 ? null : '1000.00', balanceAfter: index % 3 === 0 ? '2250.50' : index % 3 === 1 ? '799.75' : null,
  reference: 'BAL-' + (9000 + index), remark: index % 3 === 2 ? '结算扣除手续费' : '客户资金校正', operator: '管理员', time: `2026-10-21T12:${String(59 - index).padStart(2, '0')}:00` }));

async function fixture(page) {
  const requests = [], errors = [], warnings = [], writes = [], receipts = new Map();
  let expectedNetworkErrors = 0;
  const customer = { id: 10001, email: 'chen@example.test', nickname: '陈先生', countryCode: '+86', phone: '13800000001', status: 'normal', userType: 'normal',
    remark: '重点客户', myInviteCode: 'CHEN01', parentUserId: null, parentUserEmail: '', annualIncome: 120000, annualIncomeCurrency: 'USD', avatarUrl: '',
    rowVersion: 1, fundBalance: '1000.00', contractBalance: '300.00', optionBalance: '50.00', totalBalance: '1500.00', kycStatus: 'VERIFIED', createdAt: '2026-10-01T10:00:00' };
  const identity = { id: 1, updatedAt: '2026-10-21T11:00:00', realName: '陈大明', idNumber: 'TEST12345678', idFrontImage: '', idBackImage: '', status: 'APPROVED', reviewRemark: '' };
  const state = { requests, errors, warnings, writes, customer, identity, dropNextSave: false };
  const profile = () => ({ success: true, data: { user: { ...customer }, kyc: { ...identity }, totalBalance: customer.totalBalance,
    accounts: [{ coin: 'FUND', available: customer.fundBalance, frozen: '100.00' }, { coin: 'CONTRACT', available: customer.contractBalance, frozen: '50.00' }, { coin: 'OPTION', available: customer.optionBalance, frozen: '0.00' }],
    permissions: { edit: true, resetPassword: true, modifyBalance: true, editKyc: true } } });
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => {
    if (message.type() === 'error') {
      if (expectedNetworkErrors && message.text().includes('net::ERR_CONNECTION_RESET')) expectedNetworkErrors--;
      else errors.push(message.text());
    }
    if (message.type() === 'warning') warnings.push(message.text());
  });
  await page.addInitScript(() => sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ token: 'customer-console-fixture', mode: 'admin', user: { id: 1, tenantId: 1, userType: 'admin', account: '运营管理' } })));
  await page.route('**/api/**', async route => {
    const input = route.request(), url = new URL(input.url()), endpoint = url.pathname;
    const reply = value => route.fulfill({ json: value });
    if (endpoint.startsWith('/api/uploads/')) return route.fulfill({ contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7x8AAAAASUVORK5CYII=', 'base64') });
    if (endpoint.endsWith('/menus/current')) return reply({ success: true, menus: [
      { id: 1, parentId: 101, menuCode: 'users', menuName: '客户管理', path: '/users' },
      { id: 2, parentId: 102, menuCode: 'funds_ledger', menuName: '资金明细', path: '/funds-ledger' }],
      groups: [{ id: 101, menuCode: 'group_users', menuName: '客户管理', icon: 'User' }, { id: 102, menuCode: 'group_funds', menuName: '资金管理', icon: 'Wallet' }],
      actions: { users: ['*'], funds_ledger: ['*'] } });
    if (endpoint.includes('/table-preferences/')) return reply([]);
    if (endpoint.endsWith('/notification/pending-counts')) return reply({ success: true, data: { deposit: 0, withdraw: 0, kyc: 1, order: 0 } });
    if (endpoint.endsWith('/notification/sounds')) return reply([]);
    if (endpoint.endsWith('/online-count')) return reply({ success: true, count: 3 });
    if (endpoint.endsWith('/agents/simple')) return reply({ success: true, list: [] });
    if (endpoint === '/api/market/currencies') return reply({ rates: {} });
    if (endpoint.endsWith('/deposit/orders/permissions')) return reply({ view_deposit_orders: true, manual_deposit: true });
    if (endpoint === '/api/admin/users/query') {
      const body = input.postDataJSON(); requests.push({ endpoint, body });
      return reply({ success: true, list: [customer], total: 1 });
    }
    if (endpoint === '/api/admin/account-query') {
      const body = input.postDataJSON();
      assert.equal(body.path, '/api/admin/users/query');
      assert.equal(body.method, 'POST');
      return reply({ success: true, list: [{ ...customer }], total: 1 });
    }
    if (endpoint === '/api/admin/users/10001/profile') {
      if (input.method() === 'GET') return reply(profile());
      const body = input.postDataJSON(); writes.push(body);
      if (body.idempotencyKey && receipts.has(body.idempotencyKey)) {
        assert.equal(JSON.stringify(body), receipts.get(body.idempotencyKey), 'Retries preserve the exact mutation');
        return reply(profile());
      }
      assert.equal(body.rowVersion, customer.rowVersion, 'Profile save carries optimistic version');
      Object.assign(customer, body.profile);
      if (body.balances) {
        assert.equal(body.expectedBalances.FUND, customer.fundBalance);
        assert.ok(body.reason && body.idempotencyKey);
        customer.fundBalance = body.balances.FUND; customer.contractBalance = body.balances.CONTRACT; customer.optionBalance = body.balances.OPTION;
        customer.totalBalance = (Number(customer.fundBalance) + Number(customer.contractBalance) + Number(customer.optionBalance) + 150).toFixed(2);
      }
      if (body.kyc) { Object.assign(identity, body.kyc); identity.updatedAt = '2026-10-21T13:00:00'; customer.kycStatus = identity.status === 'APPROVED' ? 'VERIFIED' : 'NOT_VERIFIED'; }
      customer.rowVersion++;
      if (body.idempotencyKey) receipts.set(body.idempotencyKey, JSON.stringify(body));
      if (state.dropNextSave) { state.dropNextSave = false; expectedNetworkErrors++; return route.abort('connectionreset'); }
      return reply(profile());
    }
    if (endpoint === '/api/upload/image') return reply({ success: true, url: '/uploads/images/customer-console-fixture.png' });
    if (endpoint.includes('/user-lookup/')) { requests.push({ endpoint, params: Object.fromEntries(url.searchParams) }); return reply([{ userId: 10001, email: 'chen@example.test' }]); }
    if (endpoint === '/api/admin/funds/ledger') {
      const params = Object.fromEntries(url.searchParams); requests.push({ endpoint, params });
      let selected = events.filter(row => (!params.type || params.type === row.type) && (!params.userId || params.userId === String(row.userId)) &&
        (!params.accountType || params.accountType === row.accountType) && (!params.direction || (params.direction === 'IN' ? !row.amount.startsWith('-') : row.amount.startsWith('-'))) &&
        (!params.keyword || `${row.nickname} ${row.email} ${row.reference} ${row.remark}`.includes(params.keyword)) &&
        (!params.amountMin || Math.abs(Number(row.amount)) >= Number(params.amountMin)) && (!params.amountMax || Math.abs(Number(row.amount)) <= Number(params.amountMax)));
      const income = selected.reduce((sum, row) => sum + (Number(row.amount) > 0 ? Number(row.amount) : 0), 0);
      const expense = selected.reduce((sum, row) => sum + (Number(row.amount) < 0 ? -Number(row.amount) : 0), 0);
      const start = Number(params.page || 0) * Number(params.size || 25);
      return reply({ success: true, list: selected.slice(start, start + Number(params.size || 25)), total: selected.length, types, summary: { income: income.toFixed(2), expense: expense.toFixed(2), net: (income - expense).toFixed(2), count: selected.length, currency: 'USD' } });
    }
    if (input.method() !== 'GET') {
      if (endpoint === '/api/admin/users/query') return reply({ success: true, list: [], total: 0 });
      throw Error('Unexpected mutation blocked: ' + endpoint);
    }
    return reply({ success: true, list: [], data: {}, count: 0, total: 0 });
  });
  return state;
}

(async () => {
  const browser = await safeBrowser();
  let activePage;
  try {
    const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, reducedMotion: 'reduce' });
    const page = await context.newPage(), state = await fixture(page);
    activePage = page;
    await page.goto(base + '/funds-ledger');
    await page.getByRole('heading', { name: '资金明细', exact: true }).waitFor();
    await page.locator('.el-table__body-wrapper tbody tr').first().waitFor();
    assert.match(await page.title(), /.+/);
    assert.ok((await page.locator('#app').innerText()).length > 100);
    assert.equal(await page.locator('vite-error-overlay').count(), 0);
    assert.equal(await page.locator('#layout-navigation').evaluate(element => Math.round(element.getBoundingClientRect().width)), 180);
    assert.equal(await page.locator('.el-table__body-wrapper tbody tr').count(), 25, '25 dense rows per page');
    await page.screenshot({ path: path.join(output, 'funds-ledger-desktop.png'), animations: 'disabled' });
    await page.getByPlaceholder('客户、单号、备注').fill('BAL-9000');
    await page.waitForFunction(() => document.querySelectorAll('.el-table__body-wrapper tbody tr').length === 1);
    assert.equal(state.requests.at(-1).params.keyword, 'BAL-9000');
    assert.equal(state.requests.at(-1).params.page, '0');
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('.el-table__body-wrapper tbody tr').length === 25);
    await page.getByRole('button', { name: '更多筛选', exact: true }).click();
    const minimum = page.locator('.el-form-item').filter({ hasText: '最小变动金额' }).getByRole('textbox');
    await minimum.fill('1000');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll('.el-table__body-wrapper tbody tr').length === 11);
    assert.equal(state.requests.at(-1).params.amountMin, '1000');
    await page.getByRole('button', { name: '重置', exact: true }).click();
    const customer = page.getByRole('combobox', { name: '用户 ID / 邮箱' });
    await customer.fill('chen');
    await page.getByRole('option', { name: '10001 · chen@example.test' }).click();
    await page.waitForFunction(() => document.querySelectorAll('.el-table__body-wrapper tbody tr').length === 11);
    assert.ok(state.requests.some(item => item.endpoint.endsWith('/user-lookup/funds_ledger') && item.params.query === 'chen'));
    assert.equal(state.requests.at(-1).params.userId, '10001');
    await page.getByRole('button', { name: '重置', exact: true }).click();
    await page.getByRole('button', { name: '收起后台导航', exact: true }).click();
    await page.waitForFunction(() => Math.round(document.querySelector('#layout-navigation').getBoundingClientRect().width) === 64);
    await page.getByRole('button', { name: '展开后台导航', exact: true }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('heading', { name: '资金明细', exact: true }).waitFor();
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'No mobile page overflow');
    await page.screenshot({ path: path.join(output, 'funds-ledger-mobile.png'), animations: 'disabled' });
    await page.getByRole('button', { name: '打开后台导航', exact: true }).click();
    await page.locator('#layout-navigation').waitFor({ state: 'visible' });
    await page.locator('.navigation-backdrop').click({ position: { x: 300, y: 400 } });
    await page.locator('#layout-navigation').waitFor({ state: 'hidden' });
    await page.setViewportSize({ width: 1600, height: 1000 });
    await page.goto(base + '/users?userId=10001');
    await page.getByRole('button', { name: '查看详情', exact: true }).first().waitFor();
    assert.equal(state.requests.findLast(item => item.endpoint === '/api/admin/users/query').body.userId, '10001');
    await page.screenshot({ path: path.join(output, 'customers-desktop.png'), animations: 'disabled' });
    await page.getByRole('button', { name: '查看详情', exact: true }).first().click();
    const drawer = page.getByRole('dialog');
    await drawer.getByRole('tab', { name: '基本资料', exact: true }).waitFor();
    const nickname = drawer.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: /^昵称$/ }) }).getByRole('textbox');
    await nickname.fill('陈先生已修改');
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click();
    await page.getByText('客户资料已保存', { exact: true }).waitFor();
    assert.equal(state.customer.nickname, '陈先生已修改');
    assert.equal(state.writes.at(-1).profile.nickname, '陈先生已修改');
    await drawer.getByRole('tab', { name: '账户余额', exact: true }).click();
    await drawer.getByRole('textbox', { name: '资金账户可用余额', exact: true }).fill('2000.00');
    await drawer.getByPlaceholder('调整余额时必填，便于核对资金记录').fill('本地回归资金校正');
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.customer-total strong')?.textContent === '2,500.00');
    assert.equal(state.writes.at(-1).expectedBalances.FUND, '1000.00');
    assert.equal(state.writes.at(-1).balances.FUND, '2000.00');
    await page.locator('.el-message').evaluateAll(elements => elements.forEach(element => element.remove()));
    await page.screenshot({ path: path.join(output, 'customer-balances.png'), animations: 'disabled' });
    await drawer.getByRole('tab', { name: '实名信息', exact: true }).click();
    const realName = drawer.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: /^真实姓名$/ }) }).getByRole('textbox');
    await realName.fill('陈大明已更新');
    await drawer.getByRole('button', { name: '一键实名', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.customer-footer')?.textContent.includes('资料已同步'));
    assert.equal(state.identity.realName, '陈大明已更新');
    assert.equal(state.identity.status, 'APPROVED');
    await drawer.locator('input[type="file"]').nth(1).setInputFiles({ name: 'identity.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7x8AAAAASUVORK5CYII=', 'base64') });
    await page.getByText('图片已上传，保存后生效', { exact: true }).waitFor();
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.customer-footer')?.textContent.includes('资料已同步'));
    assert.equal(state.identity.idFrontImage, '/uploads/images/customer-console-fixture.png');
    await drawer.locator('.customer-document-image img').waitFor();
    await drawer.locator('.customer-document-image').click();
    await page.locator('.el-image-viewer__wrapper').waitFor();
    await page.keyboard.press('Escape');
    await page.locator('.el-image-viewer__wrapper').waitFor({ state: 'hidden' });
    await page.locator('.el-message').evaluateAll(elements => elements.forEach(element => element.remove()));
    await page.screenshot({ path: path.join(output, 'customer-identity.png'), animations: 'disabled' });
    await drawer.getByRole('tab', { name: '账户余额', exact: true }).click();
    await drawer.getByRole('textbox', { name: '期权账户可用余额', exact: true }).fill('75.00');
    await drawer.getByPlaceholder('调整余额时必填，便于核对资金记录').fill('验证结果未知后的原请求重试');
    const beforeRetry = state.customer.rowVersion;
    state.dropNextSave = true;
    await drawer.getByRole('button', { name: '保存修改', exact: true }).click();
    await drawer.getByRole('button', { name: '重试原请求', exact: true }).waitFor();
    assert.equal(state.customer.rowVersion, beforeRetry + 1);
    await drawer.getByRole('button', { name: '重试原请求', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.customer-footer')?.textContent.includes('资料已同步'));
    assert.equal(state.customer.rowVersion, beforeRetry + 1, 'Successful retry does not apply the mutation twice');
    assert.deepEqual(state.writes.at(-1), state.writes.at(-2));
    await drawer.getByRole('tab', { name: '基本资料', exact: true }).click();
    await nickname.fill('本地未保存的草稿');
    state.customer.nickname = '其他窗口已更新'; state.customer.rowVersion++;
    await drawer.getByText('客户资料或余额已更新。当前编辑内容已保留，请重新加载后再保存。', { exact: true }).waitFor({ timeout: 10000 });
    assert.equal(await nickname.inputValue(), '本地未保存的草稿', 'Polling preserves unsaved edits');
    assert.equal(await drawer.getByRole('button', { name: '保存修改', exact: true }).isDisabled(), true, 'Conflicting edits cannot overwrite newer data');
    await drawer.getByRole('button', { name: '重新加载', exact: true }).click();
    await page.getByRole('dialog', { name: '重新加载', exact: true }).getByRole('button', { name: '重新加载', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.customer-footer')?.textContent.includes('资料已同步'));
    assert.equal(await nickname.inputValue(), '其他窗口已更新');
    await drawer.getByRole('button', { name: '关闭', exact: true }).click();
    await page.locator('.account-type-filter .el-checkbox').filter({ hasText: '模拟账户' }).click();
    assert.equal(await page.getByRole('checkbox', { name: '模拟账户', exact: true }).isChecked(), true);
    const realRow = page.locator('.el-table__body-wrapper tbody tr').filter({ hasText: '真实账户' });
    await realRow.getByRole('button', { name: '查看详情', exact: true }).click();
    await drawer.getByText('当前账户筛选仅供查看，请切回真实账户后编辑', { exact: true }).waitFor();
    await drawer.getByRole('tab', { name: '账户余额', exact: true }).click();
    assert.equal(await drawer.getByRole('textbox', { name: '资金账户可用余额', exact: true }).isDisabled(), true);
    assert.equal(await drawer.getByRole('button', { name: '保存修改', exact: true }).isDisabled(), true);
    await drawer.getByRole('button', { name: '关闭', exact: true }).click();
    assert.deepEqual(state.errors, []);
    fs.writeFileSync(path.join(output, 'results.json'), JSON.stringify({ passed: true, url: page.url(), viewports: ['1600x1000', '390x844'], checks: ['page identity', 'nonblank', 'no overlay', '25 rows', '180px sidebar', 'keyword', 'amount', 'customer lookup', 'collapse', 'mobile overflow', 'mobile menu', 'customer query routing', 'profile save', 'balance save', 'one-click identity', 'identity upload and preview', 'idempotent retry after lost response', 'polling preserves draft and blocks conflict', 'mixed real/demo remains read-only'], requests: state.requests, warnings: [...new Set(state.warnings.map(value => value.split('\n')[0]))] }, null, 2));
    console.log('PASS: customer profile/balances/identity/photo, original-request retry, polling conflict protection, funds filters, desktop/mobile, no unexpected page errors.');
    await context.close();
  } catch (error) {
    if (activePage && !activePage.isClosed()) {
      await activePage.screenshot({ path: path.join(output, 'failure.png'), animations: 'disabled' }).catch(() => {});
      fs.writeFileSync(path.join(output, 'failure.txt'), await activePage.locator('body').innerText().catch(() => 'Page unavailable'));
    }
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
