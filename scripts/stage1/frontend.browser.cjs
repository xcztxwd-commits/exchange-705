// Actual Playwright Chromium automation against built frontends and the MySQL/Redis backend. Strict normal TLS; no response mocks.
const fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto'), net = require('node:net');
const { execFileSync } = require('node:child_process');
const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'C:/Users/徐乾妖/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const root = path.resolve(__dirname, '../..'), run = path.resolve(process.env.STAGE1_RUN || process.argv[2] || '');
if (!run.startsWith(path.join(root, 'reports') + path.sep) || !path.basename(run).startsWith('stage1-')) throw Error('Explicit owned stage1 run directory required');
const read = name => JSON.parse(fs.readFileSync(path.join(run, name), 'utf8'));
const identities = read('private/browser-identities.json'), environment = read('environment.json');
const attempt = new Date().toISOString().replace(/[:.]/g, '-'), evidence = path.join(run, 'browser', attempt);
fs.mkdirSync(evidence, { recursive: true });
const results = [], pages = [], sensitive = [], uiCreatedAccounts = [], livePages = new Set();
const linuxFixture = process.env.STAGE1_LINUX_FIXTURE === '1';
let linuxProof;
const browserArgs = [];
if (linuxFixture) {
  assert.equal(process.platform, 'linux');
  const proofPath = path.resolve(process.env.STAGE1_LINUX_OWNER_PROOF || ''); assert.ok(proofPath.startsWith(run + path.sep));
  linuxProof = JSON.parse(fs.readFileSync(proofPath, 'utf8'));
  assert.equal(linuxProof.run, environment.run); assert.equal(process.env.STAGE1_HOST_IP, linuxProof.hostIp); assert.ok(net.isIP(linuxProof.hostIp));
  assert.equal(process.env.STAGE1_REDIS_CONTAINER_ID, linuxProof.redisContainerId); assert.equal(linuxProof.redisRunLabel, environment.run);
  assert.equal(process.env.STAGE1_REDIS_HOST, 'host.docker.internal'); assert.equal(Number(process.env.STAGE1_REDIS_PORT), environment.redisPort);
  browserArgs.push('--host-resolver-rules=' + ['control', 'admin', 'default', 'a', 'a2', 'b'].map(host => `MAP ${host}.localhost ${linuxProof.hostIp}`).join(', '));
}
async function captchaValue(key) {
  if (!linuxFixture) return execFileSync('docker', ['exec', environment.containers.redis, 'redis-cli', '--raw', 'GET', key], { encoding: 'utf8', windowsHide: true }).trim();
  // Only the wrapper-verified run's loopback-published Redis; one bounded, read-only RESP GET.
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: 'host.docker.internal', port: environment.redisPort }), bytes = Buffer.from(key), chunks = [];
    let length = 0, settled = false;
    const finish = (error, value) => { if (settled) return; settled = true; socket.destroy(); error ? reject(error) : resolve(value); };
    socket.setTimeout(5000, () => finish(Error('Owned Redis GET timed out'))); socket.on('error', error => finish(error));
    socket.on('connect', () => socket.write(Buffer.concat([Buffer.from(`*2\r\n$3\r\nGET\r\n$${bytes.length}\r\n`), bytes, Buffer.from('\r\n')])));
    socket.on('data', chunk => {
      chunks.push(chunk); length += chunk.length; if (length > 4096) return finish(Error('Unexpected Redis response length'));
      const buffer = Buffer.concat(chunks), end = buffer.indexOf('\r\n'); if (end < 0) return;
      const header = buffer.subarray(0, end).toString(); if (!/^\$\d+$/.test(header)) return finish(Error('Owned captcha absent or Redis GET rejected'));
      const count = Number(header.slice(1)); if (count > 256) return finish(Error('Unexpected captcha value length'));
      if (buffer.length >= end + 2 + count + 2) finish(null, buffer.subarray(end + 2, end + 2 + count).toString());
    });
    socket.on('end', () => finish(Error('Owned Redis closed before its reply')));
  });
}
function secrets(value) { if (!value || typeof value !== 'object') return; for (const [key, item] of Object.entries(value)) { if (typeof item === 'string' && /password|secret|token/i.test(key)) sensitive.push(item); else secrets(item); } }
secrets(identities);
const redact = text => sensitive.reduce((s, value) => value ? s.split(value).join('[REDACTED]') : s, String(text));
function totp(secret) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567', bits = [...secret.toUpperCase().replace(/=+$/, '')].map(c => alphabet.indexOf(c).toString(2).padStart(5, '0')).join('');
  const key = Buffer.from(bits.match(/.{8}/g).map(byte => parseInt(byte, 2))), counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const h = crypto.createHmac('sha1', key).update(counter).digest(), offset = h[h.length - 1] & 15;
  return String((h.readUInt32BE(offset) & 0x7fffffff) % 1000000).padStart(6, '0');
}
async function check(name, action) { try { await action(); results.push({ name, status: 'PASS' }); console.log('PASS ' + name); } catch (e) { results.push({ name, status: 'FAIL', error: redact(e.message) }); let index = 0; for (const page of livePages) { await page.screenshot({ path: path.join(evidence, 'failure-' + results.length + '-' + index++ + '.png'), mask: [page.locator('input[type=password], .control-login input')] }).catch(() => {}); } throw e; } }
function expectedResponse(page, predicate) { const promise = page.waitForResponse(predicate); promise.catch(() => {}); return promise; }
function observe(page, name) {
  const events = { name, errors: [], console: [], failedRequests: [] }; pages.push(events); livePages.add(page); page.on('close', () => livePages.delete(page));
  page.on('pageerror', e => events.errors.push(redact(e.message)));
  page.on('console', msg => { if (['error', 'warning'].includes(msg.type())) events.console.push(redact(msg.text())); });
  page.on('requestfailed', req => events.failedRequests.push({ path: new URL(req.url()).pathname, error: req.failure()?.errorText }));
}
async function evidenceFor(page, name) {
  assert.ok(await page.title(), 'Page title required');
  assert.ok((await page.locator('body').innerText()).trim().length > 20, 'App must not be blank');
  assert.equal(await page.locator('vite-error-overlay, nextjs-portal').count(), 0, 'No framework error overlay');
  await page.screenshot({ path: path.join(evidence, name + '.png'), fullPage: false });
  fs.writeFileSync(path.join(evidence, name + '.txt'), redact(await page.locator('body').innerText()));
}
async function localGoto(page, url) {
  const target = new URL(url), hosts = ['control.localhost', 'admin.localhost', 'default.localhost', 'a.localhost', 'a2.localhost', 'b.localhost'];
  assert.ok(target.protocol === 'https:' && hosts.includes(target.hostname), 'Only exact owned HTTPS fixture hosts allowed');
  // Normal browser certificate validation. The fixture CA must be trusted before this run.
  await page.goto(url);
  assert.equal(await page.getByText('已防止访问不可信赖的网站', { exact: true }).count(), 0, 'Local certificate trust must be approved; warnings are never bypassed');
}
async function session(page) { return page.evaluate(() => JSON.parse(sessionStorage.getItem('exchange.admin.session.v2') || 'null')); }
async function adminApi(page, route, method = 'GET', body) {
  return page.evaluate(async ({ route, method, body }) => {
    const state = JSON.parse(sessionStorage.getItem('exchange.admin.session.v2') || 'null');
    const response = await fetch('/api' + route, { method, headers: { Authorization: `Bearer ${state?.token}`, 'Content-Type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    return { status: response.status, data: await response.json() };
  }, { route, method, body });
}
async function configuration(page) {
  const reply = await adminApi(page, '/admin/config/get?key=system.timezone'); assert.equal(reply.status, 200); return reply.data.value;
}
async function settings(page) {
  await localGoto(page, 'https://admin.localhost/settings'); await page.getByRole('tab', { name: '时区设置', exact: true }).click();
  const field = page.locator('.el-tab-pane:visible input').nth(1); await field.waitFor();
  await page.waitForFunction(() => [...document.querySelectorAll('.el-tab-pane')].some(p => p.offsetParent && [...p.querySelectorAll('input')].filter(i => !i.disabled).length >= 2));
  return field;
}
async function loginUser(page, tenant, mobile, credentials = tenant.user) {
  const base = 'https://' + tenant.host, response = expectedResponse(page, r => r.url().endsWith('/api/auth/login') && r.request().method() === 'POST');
  await localGoto(page, base + (mobile ? '/mobile/login' : '/?login=1'));
  const form = mobile ? page.locator('.auth-card') : page.locator('#pc-login-form');
  await form.locator('input[type=email]').fill(credentials.email); await form.locator('input[type=password]').fill(credentials.password);
  if (mobile) await form.locator('.primary-btn').click(); else await page.locator('button[form=pc-login-form]').click();
  const reply = await response; assert.equal(reply.status(), 200, 'Real user login must succeed');
  const user = await reply.json(); assert.equal(user.user.tenantId, tenant.id);
  await page.waitForFunction(() => !!localStorage.getItem('token'));
  if (mobile) await page.waitForURL('**/mobile/home'); else await page.locator('#pc-login-form').waitFor({ state: 'hidden' });
  const actual = await page.evaluate(() => JSON.parse(localStorage.getItem('user'))); assert.equal(actual.tenantId, tenant.id);
  await evidenceFor(page, (mobile ? 'mobile' : 'pc') + '-' + tenant.id);
  return user;
}
async function registerUser(page, tenant, mobile, email, password, phoneNumber) {
  const captcha = expectedResponse(page, r => r.url().endsWith('/api/auth/captcha') && r.request().method() === 'POST');
  await localGoto(page, 'https://' + tenant.host + (mobile ? '/mobile/register' : '/?register=1'));
  const form = mobile ? page.locator('.auth-card') : page.locator('#pc-register-form');
  await form.locator('input[type=email]').fill(email); const passwords = form.locator('input[type=password]');
  await passwords.nth(0).fill(password); await passwords.nth(1).fill(password);
  const challenge = await captcha; assert.equal(challenge.status(), 200);
  const data = await challenge.json(), captchaSession = await page.evaluate(() => sessionStorage.getItem('registration-captcha-session'));
  assert.ok(environment.run === path.basename(run) && environment.containers.redis.includes(environment.run + '-redis'), 'Only this run Redis may be read');
  // Local test reads its own existing challenge; the normal server verification/consumption remains unchanged.
  const value = await captchaValue(`security:{registration}:tenant:${tenant.id}:challenge:${captchaSession}`);
  const [id, answer] = value.split('|'); assert.equal(id, data.captchaId); assert.match(answer, /^[A-Z0-9]{4}$/);
  await form.locator('#registration-captcha-code').fill(answer);
  assert.match(phoneNumber, /^139[0-9]{8}$/); const phone = form.locator('#registration-phone'); assert.equal(await phone.count(), 1, 'Configured phone field must exist'); await phone.fill(phoneNumber);
  const income = form.locator('#registration-income'); if (await income.count()) await income.fill('12000');
  const reply = expectedResponse(page, r => r.url().endsWith('/api/auth/register') && r.request().method() === 'POST');
  if (mobile) await form.locator('.primary-btn').click(); else await page.locator('button[form=pc-register-form]').click();
  const response = await reply; assert.equal(response.request().postDataJSON().phone, phoneNumber, 'Real UI request must include this run shared phone'); assert.equal(response.status(), 200, 'Real registration must succeed');
  const user = await response.json(); assert.equal(user.user.tenantId, tenant.id); assert.equal(user.user.email, email);
  await evidenceFor(page, 'registration-' + tenant.id);
  return { email, password, phone: phoneNumber, countryCode: response.request().postDataJSON().countryCode, user: user.user };
}
(async () => {
  const browser = await chromium.launch({ headless: true, chromiumSandbox: linuxFixture, args: browserArgs, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' });
  let context, control, aPage, bPage;
  try {
    context = await browser.newContext({ viewport: { width: 1440, height: 1000 } }); control = await context.newPage(); observe(control, 'control');
    await check('总控真实账号密码及 MFA 登录', async () => {
      await localGoto(control, 'https://control.localhost/'); const inputs = control.locator('.control-login input');
      await inputs.nth(0).fill(identities.control.account); await inputs.nth(1).fill(identities.control.password); await inputs.nth(2).fill(totp(identities.control.totpSecret));
      const reply = expectedResponse(control, r => r.url().endsWith('/api/control/auth/login')); await control.getByRole('button', { name: '登录总控', exact: true }).click(); assert.equal((await reply).status(), 200);
      await control.getByRole('heading', { name: '租户管理', exact: true }).waitFor(); await evidenceFor(control, 'control-login');
    });
    const tenantReply = await control.evaluate(async () => { const state = JSON.parse(sessionStorage.getItem('exchange.control.session.v1')); return (await fetch('/api/control/tenants', { headers: { Authorization: `Bearer ${state.token}` } })).json(); });
    for (const t of identities.tenants) t.name ||= tenantReply.data.find(row => row.id === t.id)?.name;
    const a = identities.tenants.find(t => t.host.startsWith('a')), b = identities.tenants.find(t => t.host.startsWith('b')); assert.ok(a && b);
    await check('总控业务监管界面只读实际查询', async () => {
      await control.getByRole('button', { name: '业务监管 · 只读', exact: true }).click(); const selects = control.locator('.control-content .el-select');
      await selects.nth(0).click(); const reply = expectedResponse(control, r => r.url().includes(`/business/users?`) && r.request().method() === 'GET');
      await control.locator('.el-select-dropdown:visible').getByText(a.name, { exact: true }).click(); assert.equal((await reply).status(), 200);
      assert.equal(await control.getByRole('button', { name: /保存|新增|删除|审核/ }).count(), 0); await evidenceFor(control, 'read-only-supervision');
      await control.getByRole('button', { name: '租户 / 授权 / 在线', exact: true }).click();
    });
    await check('总控 A/B 多标签页一键进入独立真实访问会话', async () => {
      for (const tenant of [a, b]) {
        const row = control.locator('.el-table__body tbody tr').filter({ has: control.getByText(tenant.name, { exact: true }) }); await row.waitFor({ state: 'visible' }); assert.equal(await row.count(), 1, 'One exact tenant row required');
        const popup = context.waitForEvent('page'); await row.getByRole('button', { name: '进入后台', exact: true }).click(); const page = await popup; observe(page, 'control-access-' + tenant.id);
        await page.waitForURL('**/dashboard'); await page.getByText('总控管理', { exact: true }).waitFor(); const identity = await session(page); assert.equal(identity.mode, 'control'); assert.equal(identity.user.tenantId, tenant.id);
        assert.equal(await page.evaluate(() => localStorage.getItem('admin_token')), null); await evidenceFor(page, 'control-access-' + tenant.id);
        if (tenant === a) aPage = page; else bPage = page;
      }
      assert.notEqual((await session(aPage)).accessSession.id, (await session(bPage)).accessSession.id);
    });
    await check('总控 A 配置真实界面写入 B 保持不变', async () => {
      const beforeB = await configuration(bPage), field = await settings(aPage), previous = await configuration(aPage), next = previous === 'Asia/Singapore' ? 'Europe/London' : 'Asia/Singapore';
      await field.fill(next); const saved = expectedResponse(aPage, r => r.url().endsWith('/api/admin/config/saveBatch') && r.request().method() === 'POST');
      await aPage.getByRole('button', { name: '保存配置', exact: true }).click(); assert.equal((await saved).status(), 200); assert.equal(await configuration(aPage), next); assert.equal(await configuration(bPage), beforeB); await evidenceFor(aPage, 'control-a-config-write');
    });
    await check('总控访问会话真实撤销 A 失效不 fallback 且 B 仍有效', async () => {
      const id = (await session(aPage)).accessSession.id; await control.getByRole('button', { name: '安全与访问会话', exact: true }).click();
      await control.locator('.el-table__body tbody tr').filter({ hasText: id }).getByRole('button', { name: '撤销', exact: true }).click();
      const reply = expectedResponse(control, r => r.url().includes('/access-sessions/' + id + '/revoke')); await control.locator('.el-message-box__btns .el-button--primary').click(); assert.equal((await reply).status(), 200);
      await aPage.reload(); await aPage.waitForURL('**/access-ended'); assert.equal(await session(aPage), null); await bPage.reload(); await bPage.getByText('总控管理', { exact: true }).waitFor(); assert.equal((await session(bPage)).user.tenantId, b.id); await evidenceFor(aPage, 'revoked-no-fallback');
    });
    for (const tenant of identities.tenants) {
      await check('共用后台账号密码登录租户 ' + tenant.id, async () => {
        const own = await browser.newContext({ viewport: { width: 1440, height: 1000 } }), page = await own.newPage(); observe(page, 'owner-' + tenant.id);
        try { await localGoto(page, 'https://admin.localhost/login'); assert.equal(await page.locator('.login-box input').count(), 2, 'No tenant selection in backend login'); await page.getByPlaceholder('输入后台账号').fill(tenant.owner.account); await page.getByPlaceholder('输入密码').fill(tenant.owner.password);
          const reply = expectedResponse(page, r => r.url().endsWith('/api/admin/auth/login')); await page.getByRole('button', { name: '登 录', exact: true }).click(); assert.equal((await reply).status(), 200); await page.locator('.layout-container').waitFor(); assert.equal((await session(page)).user.tenantId, tenant.id); await evidenceFor(page, 'tenant-owner-' + tenant.id);
          if (tenant.id === a.id) await check('A 负责人真实界面创建员工客服并核对代理与授权入口', async () => {
            const listing = expectedResponse(page, r => r.url().includes('/api/admin/admins?') && r.request().method() === 'GET');
            await localGoto(page, 'https://admin.localhost/admin-list'); assert.equal((await listing).status(), 200);
            for (const [kind, label] of [['staff', 'stage1 staff'], ['support', 'stage1 support']]) {
              const account = `stage1_ui_${kind}_${Date.now()}`, password = 'Local-' + crypto.randomBytes(12).toString('base64url'); sensitive.push(password);
              await page.getByRole('button', { name: '添加管理员', exact: true }).click(); const dialog = page.locator('.el-dialog:visible');
              await dialog.getByPlaceholder('请输入登录账号', { exact: true }).fill(account);
              await dialog.getByPlaceholder('请输入邮箱', { exact: true }).fill(account + '@local.example'); await dialog.getByPlaceholder('请输入密码', { exact: true }).fill(password);
              await dialog.locator('.el-select').click(); await page.locator('.el-select-dropdown:visible').getByText(label, { exact: true }).click();
              const created = expectedResponse(page, r => r.url().endsWith('/api/admin/admins') && r.request().method() === 'POST');
              await dialog.getByRole('button', { name: '确定', exact: true }).click(); const reply = await created; assert.equal(reply.status(), 200); const payload = await reply.json();
              assert.ok(Number.isInteger(payload.data.id)); assert.equal(payload.data.account, account); assert.equal((await session(page)).user.tenantId, a.id); uiCreatedAccounts.push(account); await dialog.waitFor({ state: 'hidden' });
              await page.getByPlaceholder('账号/邮箱', { exact: true }).fill(account); const search = expectedResponse(page, r => r.url().includes('/api/admin/admins?') && r.request().method() === 'GET');
              await page.getByRole('button', { name: '搜索', exact: true }).click(); assert.equal((await search).status(), 200); await page.locator('.el-table__body tbody tr').filter({ hasText: account }).first().waitFor();
              await evidenceFor(page, 'owner-created-' + kind);
            }
            const identities = expectedResponse(page, r => r.url().includes('/api/admin/backend-accounts') && r.request().method() === 'GET');
            await page.getByRole('button', { name: '后台账号', exact: true }).click(); assert.equal((await identities).status(), 200); const accounts = page.locator('.el-dialog:visible');
            await accounts.locator('.el-table__body tbody tr').filter({ hasText: 'stage1_a_agent' }).first().waitFor();
            await accounts.locator('.el-select').nth(0).click(); await page.locator('.el-select-dropdown:visible').getByText('现有代理', { exact: true }).click();
            await accounts.getByText('代理用户 ID', { exact: true }).waitFor(); assert.equal(await accounts.getByRole('button', { name: '开通账号', exact: true }).isEnabled(), true); await evidenceFor(page, 'owner-agent-identity');
            await accounts.getByRole('button', { name: 'Close this dialog' }).click();
            const roles = expectedResponse(page, r => r.url().includes('/api/admin/roles') && r.request().method() === 'GET'); await localGoto(page, 'https://admin.localhost/roles'); assert.equal((await roles).status(), 200);
            const role = page.locator('.el-table__body tbody tr').filter({ hasText: 'stage1_staff' }).first(); const permission = expectedResponse(page, r => /\/api\/admin\/roles\/\d+\/menus$/.test(r.url()));
            await role.getByRole('button', { name: '分配权限', exact: true }).click(); assert.equal((await permission).status(), 200); const grant = page.locator('.el-dialog:visible');
            await grant.getByText('菜单仅授予查看权限；按钮需单独勾选。保存后立即生效，不会自动授予新增按钮。', { exact: true }).waitFor();
            assert.equal(await grant.getByRole('button', { name: '保存', exact: true }).isEnabled(), true); await evidenceFor(page, 'owner-action-grants');
            await grant.getByRole('button', { name: '取消', exact: true }).click();
          });
          if (tenant.id === b.id) await check('B 负责人实际列表查询不可见 A 新建员工客服', async () => {
            const listing = expectedResponse(page, r => r.url().includes('/api/admin/admins?') && r.request().method() === 'GET'); await localGoto(page, 'https://admin.localhost/admin-list'); assert.equal((await listing).status(), 200);
            for (const account of uiCreatedAccounts) {
              await page.getByPlaceholder('账号/邮箱', { exact: true }).fill(account); const search = expectedResponse(page, r => r.url().includes('/api/admin/admins?') && r.request().method() === 'GET');
              await page.getByRole('button', { name: '搜索', exact: true }).click(); const reply = await search; assert.equal(reply.status(), 200); assert.equal((await reply.json()).total, 0);
            }
            await evidenceFor(page, 'b-no-a-ui-created-identities');
          });
        } finally { await own.close(); }
      });
      for (const mobile of [false, true]) await check((mobile ? '移动端' : 'PC') + '真实用户登录租户 ' + tenant.id, async () => {
        const own = await browser.newContext({ viewport: mobile ? { width: 390, height: 844 } : { width: 1440, height: 1000 } }), page = await own.newPage(); observe(page, (mobile ? 'mobile-' : 'pc-') + tenant.id);
        try { await loginUser(page, tenant, mobile); } finally { await own.close(); }
      });
    }
    const sharedEmail = `stage1-browser-${Date.now()}@local.example`, sharedPhone = '139' + String(crypto.randomInt(0, 100000000)).padStart(8, '0'), registered = [];
    for (const [tenant, mobile] of [[a, false], [b, true]]) await check((mobile ? '移动端 B' : 'PC A') + '真实注册同邮箱与手机号且独立身份', async () => {
      const password = 'Local-' + crypto.randomBytes(12).toString('base64url'), own = await browser.newContext({ viewport: mobile ? { width: 390, height: 844 } : { width: 1440, height: 1000 } }), page = await own.newPage(); sensitive.push(password); observe(page, 'registration-' + tenant.id);
      try { const credential = await registerUser(page, tenant, mobile, sharedEmail, password, sharedPhone); registered.push({ tenant, credential }); await loginUser(page, tenant, mobile, credential); } finally { await own.close(); }
    });
    assert.notEqual(registered[0].credential.user.id, registered[1].credential.user.id);
    assert.equal(registered[0].credential.phone, sharedPhone); assert.equal(registered[1].credential.phone, sharedPhone); assert.equal(registered[0].credential.countryCode, registered[1].credential.countryCode);
    await check('当前真实四端无前端运行时异常', async () => { assert.deepEqual(pages.flatMap(p => p.errors.map(error => ({ page: p.name, error }))), []); });
  } catch (e) { if (results.at(-1)?.status !== 'FAIL') results.push({ name: '浏览器基础步骤', status: 'FAIL', error: redact(e.message) }); console.error('FAIL ' + redact(e.message)); process.exitCode = 1; }
  finally {
    const summary = { attempt, browser: 'Chromium / Playwright', browserTool: 'Node.js Playwright / actual Chromium', mocks: false, tls: 'Strict normal certificate validation with an explicitly trusted fixture CA', passed: results.filter(r => r.status === 'PASS').length, failed: results.filter(r => r.status === 'FAIL').length, skipped: 0, notExecuted: Math.max(0, 19 - results.length), results, pages, evidence };
    fs.writeFileSync(path.join(evidence, 'result.json'), JSON.stringify(summary, null, 2)); console.log(`Browser result: passed=${summary.passed} failed=${summary.failed} skipped=0 evidence=${evidence}`); await browser.close();
  }
})().catch(e => { console.error(redact(e.message)); process.exitCode = 1; });
