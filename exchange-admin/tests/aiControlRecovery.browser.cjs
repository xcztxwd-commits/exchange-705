// Local Vite + intercepted API only. No live accounts, database, quotes or mutations.
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright')
const assert = require('node:assert/strict')
const base = process.env.ADMIN_QA_URL || 'http://127.0.0.1:18067'
const key = 'unknown-recovery-request-1234'
const login = '11111111-1111-4111-8111-111111111111'
const scope = encodeURIComponent(JSON.stringify([2, 11, 'admin', `admin:${login}`]))
const storageKey = `ai-control-pending:${scope}:7`
// Interrupted-request fixtures still mount the actual router, Vue page, Axios and permission directive.
// API responses below are simulations: these cases do not validate production authentication or MySQL.
async function interruptionFixture(browser, scenario) {
  const page = await browser.newPage(), errors = [], writes = [], queries = [], failedRequests = []
  const resolved = scenario.startsWith('recovering-') || scenario === 'waiting-source-resume'
  const unresolved = scenario.startsWith('pending-')
  const timedAction = scenario === 'late-start-source' ? 'start' : 'restore'
  const payload = timedAction === 'start' ? { durationSeconds: 10, intensity: 2, targetPrice: 120, randomOscillation: false } : { durationSeconds: 10, intensity: 2, randomOscillation: false }
  let activeKey = key, state = resolved ? 'RUNNING' : 'UNKNOWN', queryVisible = !unresolved, releaseTimed, releaseTimeout
  let rescueAttempts = 0, heldAt = 0
  let status = { id: 7, enabled: timedAction !== 'start', running: resolved || unresolved, restoring: resolved || unresolved, available: true, sourceAvailable: true, canStart: true,
    controlState: resolved || unresolved ? 'RECOVERING' : timedAction === 'start' ? 'SOURCE' : 'HOLDING', rawPrice: 99, currentPrice: timedAction === 'start' ? 99 : 120, offset: timedAction === 'start' ? 0 : 21,
    remainingSeconds: 7, durationSeconds: 10, intensity: 2, progressStatus: 'HEALTHY', sampledUntil: 1000, progressStartedAt: 20000, progressEndAt: 30000, controlProgressWatermark: 23000, expectedSampledUntil: 23000, committedAt: 23000 }
  const receipt = () => ({ commandId: 'durable-interruption-fixture', requestKey: activeKey, symbolId: 7, state, action: state === 'CANCELLED' ? 'CANCEL' : timedAction.toUpperCase() })
  const saved = resolved || unresolved ? { symbolId: 7, action: timedAction, requestKey: key, payload, ...(resolved ? { receipt: receipt() } : {}) } : null
  page.on('pageerror', error => errors.push(error.message))
  page.on('requestfailed', request => failedRequests.push({ path: new URL(request.url()).pathname, failure: request.failure()?.errorText, at: Date.now() }))
  await page.addInitScript(({ login, storageKey, saved }) => {
    if (sessionStorage.getItem('qa-initialized')) return
    sessionStorage.setItem('qa-initialized', '1')
    sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ token: 'isolated-qa', mode: 'admin', loginSessionId: login, user: { id: 11, tenantId: 2, userType: 'admin' } }))
    if (saved) sessionStorage.setItem(storageKey, JSON.stringify(saved))
  }, { login, storageKey, saved })
  await page.route('**/*', async route => {
    const url = new URL(route.request().url()), path = url.pathname, method = route.request().method()
    if (url.origin !== new URL(base).origin) return route.abort('blockedbyclient')
    if (!path.startsWith('/api/')) return route.continue()
    if (path.startsWith('/api/admin/table-preferences/')) return route.fulfill({ json: method === 'GET' ? [] : { success: true } })
    if (path === '/api/admin/menus/current') return route.fulfill({ json: { success: true, menus: [{ id: 1, menuCode: 'ai_control', menuName: 'AI 控盘', path: '/ai-control' }], groups: [], actions: { ai_control: ['*'] } } })
    if (path.endsWith('/ai-control/symbols')) return route.fulfill({ json: [{ id: 7, symbol: 'RECOVERY_INTERRUPTION_FIXTURE', pricePrecision: 2, isEnabled: true }] })
    if (path.endsWith('/ai-control/7/history')) return route.fulfill({ json: [] })
    if (path.endsWith('/ai-control/7/commands')) {
      queries.push(url.searchParams.get('requestKey')); assert.equal(queries.at(-1), activeKey)
      return !queryVisible || state === 'UNKNOWN' ? route.fulfill({ status: 404, json: { message: 'fixture acceptance not visible yet' } }) : route.fulfill({ json: receipt() })
    }
    if (path.endsWith('/ai-control/7') && method === 'GET') return route.fulfill({ json: status })
    if (path.includes('/ai-control/') && method === 'POST') {
      const posted = route.request().postDataJSON(); writes.push({ path, payload: posted })
      if (path.endsWith('/start') || path.endsWith('/restore')) {
        activeKey = posted.requestKey; state = 'ACCEPTED'
        if (scenario === 'restore-response-lost') { queryVisible = false; return route.abort('failed') }
        const staleAcceptance = { ...receipt(), state: 'RUNNING' }
        if (scenario.startsWith('late-')) { await new Promise(resolve => { releaseTimed = resolve }); return route.fulfill({ status: 202, json: staleAcceptance }) }
        return route.fulfill({ status: 202, json: receipt() })
      }
      assert(path.endsWith('/stop') || path.endsWith('/manual'))
      assert.equal(posted?.requestKey, resolved ? undefined : activeKey, 'only unresolved original keys are cancelled')
      ++rescueAttempts
      if (rescueAttempts === 1 && (scenario.startsWith('recovering-') || scenario.startsWith('pending-'))) {
        if (scenario.endsWith('-timeout')) {
          heldAt = Date.now(); await new Promise(resolve => { releaseTimeout = resolve })
          // The real Axios 10000 ms timer has already rejected this deliberately held XHR.
          try { return await route.abort('timedout') } catch { return }
        }
        return route.fulfill({ status: 503, json: { message: 'fixture rescue unavailable' } })
      }
      if (!resolved) { state = 'CANCELLED'; queryVisible = true }
      status = path.endsWith('/manual')
        ? { ...status, enabled: false, running: false, restoring: false, controlState: 'SOURCE', available: true, sourceAvailable: true, offset: 0, rawPrice: 99, currentPrice: 99, progressStatus: 'HEALTHY' }
        : { ...status, running: false, restoring: false, controlState: 'HOLDING' }
      return path.endsWith('/stop') && !resolved ? route.fulfill({ json: receipt() }) : route.fulfill({ json: status })
    }
    return route.fulfill({ json: { success: true, list: [], data: {}, count: 0, total: 0 } })
  })
  await page.goto(base + '/ai-control')
  const source = page.getByRole('button', { name: '一键恢复原始行情', exact: true })
  await source.waitFor(); await page.waitForFunction(() => !document.querySelector('.actions button:last-child')?.disabled)
  return { page, errors, writes, queries, failedRequests, source, payload,
    get key() { return activeKey }, get heldAt() { return heldAt },
    setStatus: next => { status = { ...status, ...next } }, setState: next => { state = next; queryVisible = true },
    releaseTimed: () => { assert(releaseTimed, 'timed POST is actually in flight'); releaseTimed() },
    releaseTimeout: () => { assert(releaseTimeout, 'rescue request is actually held'); releaseTimeout() },
    saved: () => page.evaluate(key => JSON.parse(sessionStorage.getItem(key)), storageKey),
  }
}
async function progressIs(page, percent, label, watermark) {
  await page.waitForFunction(percent => document.querySelector('[role=progressbar]')?.getAttribute('aria-valuenow') === String(percent), percent)
  const detail = page.locator('#app').getByText(new RegExp(`进度 ${label}；已提交水位`))
  await detail.waitFor()
  const time = await page.evaluate(value => new Date(value).toLocaleString(), watermark)
  assert((await detail.textContent()).includes('已提交水位 ' + time), 'progress detail and bar both use committed flow samples, not task metadata')
}
async function browserInterruptions(browser) {
  const scenarios = ['restore-response-lost', 'recovering-stop-failure', 'recovering-source-failure', 'recovering-stop-timeout', 'recovering-source-timeout',
    'pending-stop-failure', 'pending-source-failure', 'late-start-source', 'late-restore-source', 'waiting-source-resume']
  for (const scenario of scenarios) {
    const fixture = await interruptionFixture(browser, scenario), { page, source, writes, queries } = fixture
    if (scenario === 'restore-response-lost') {
      await page.getByText('渐进恢复', { exact: true }).click()
      await page.getByRole('button', { name: '按设定恢复原始行情', exact: true }).click()
      await page.locator('#app').getByText(/原控盘请求结果待确认.*fixture acceptance not visible yet/).waitFor()
      const saved = await fixture.saved(), bytes = JSON.stringify(saved)
      assert.equal(saved.action, 'restore'); assert.equal(saved.receipt, undefined); assert.equal(saved.requestKey, fixture.key)
      assert(fixture.failedRequests.some(request => request.path.endsWith('/restore')), 'RESTORE acceptance response is lost at the browser network layer')
      assert.equal(await source.isDisabled(), false)
      assert.equal(await page.getByRole('button', { name: '按设定恢复原始行情', exact: true }).isDisabled(), true)
      await page.reload(); await page.locator('#app').getByText(/原控盘请求结果待确认.*fixture acceptance not visible yet/).waitFor()
      assert.equal(JSON.stringify(await fixture.saved()), bytes, 'reload preserves immutable unresolved RESTORE bytes')
      await page.getByRole('button', { name: '核对原控盘请求', exact: true }).click()
      fixture.setState('RUNNING'); fixture.setStatus({ enabled: true, running: true, restoring: true, controlState: 'RECOVERING', controlProgressWatermark: 22000 })
      await page.locator('#app').getByText('恢复命令已运行', { exact: true }).waitFor()
      await progressIs(page, 20, '正常', 22000)
      assert(queries.length >= 2 && queries.every(value => value === fixture.key)); assert.equal(writes.length, 1)
    } else if (scenario.startsWith('recovering-') || scenario.startsWith('pending-')) {
      const isSource = scenario.includes('-source-'), isPending = scenario.startsWith('pending-')
      const button = isSource ? source : page.getByRole('button', { name: isPending ? '取消待确认命令' : '停止任务并保存历史', exact: true })
      const saved = await fixture.saved()
      await progressIs(page, 30, '正常', 23000)
      await button.click()
      if (scenario.endsWith('-timeout')) await page.waitForFunction(() => document.querySelector('.actions button:last-child')?.disabled === true)
      const error = scenario.endsWith('-timeout') ? 'timeout of 10000ms exceeded' : 'fixture rescue unavailable'
      await page.getByText(error, { exact: true }).waitFor({ timeout: 20000 })
      if (scenario.endsWith('-timeout')) {
        assert(Date.now() - fixture.heldAt >= 9000, 'unchanged real Axios timeout, not an accelerated fake timer')
        assert(fixture.failedRequests.some(request => request.path.endsWith(isSource ? '/manual' : '/stop')))
        fixture.releaseTimeout()
      }
      await page.waitForFunction(() => !document.querySelector('.actions button:last-child')?.disabled)
      assert.deepEqual(await fixture.saved(), saved, 'failed STOP/SOURCE cannot manufacture a cancellation acknowledgement')
      assert.equal(await button.isDisabled(), false); assert.equal(await source.isDisabled(), false)
      await progressIs(page, 30, '正常', 23000)
      assert.equal(writes.length, 1)
      if (isPending) {
        assert.equal(await page.getByRole('button', { name: '按设定恢复原始行情', exact: true }).isDisabled(), true)
        assert.equal((await fixture.saved()).receipt, undefined)
      } else {
        await button.click()
        await page.getByText(isSource ? '已恢复原始行情' : '任务已停止，历史已保存', { exact: true }).waitFor()
        assert.equal((await fixture.saved()).receipt.state, 'RUNNING', 'resolved audit receipt is not rewritten as a fake pending cancellation')
        assert.equal(writes.length, 2); assert(writes.every(write => !write.path.endsWith('/restore') && !write.path.endsWith('/start')))
        assert.equal(await source.isDisabled(), false)
        if (isSource) assert.deepEqual(await page.locator('.quotes strong').allTextContents(), ['99.00', '99.00', '0.00'])
      }
    } else if (scenario.startsWith('late-')) {
      if (scenario === 'late-restore-source') await page.getByText('渐进恢复', { exact: true }).click()
      const timedRequest = page.waitForRequest(request => request.method() === 'POST' && new URL(request.url()).pathname.endsWith('/' + (scenario === 'late-start-source' ? 'start' : 'restore')))
      await page.getByRole('button', { name: scenario === 'late-start-source' ? '开始自动控盘' : '按设定恢复原始行情', exact: true }).click()
      await timedRequest
      assert.equal((await fixture.saved()).receipt, undefined); assert.equal(await source.isDisabled(), false)
      await source.click(); await page.waitForFunction(key => sessionStorage.getItem(key) === null, storageKey)
      assert.deepEqual(await page.locator('.quotes strong').allTextContents(), ['99.00', '99.00', '0.00'])
      const response = page.waitForResponse(response => response.request().method() === 'POST' && /\/(start|restore)$/.test(new URL(response.url()).pathname))
      fixture.releaseTimed(); await (await response).finished()
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
      assert.equal(await fixture.saved(), null, 'late RUNNING response never resurrects a cancelled original key')
      assert.equal(await page.locator('#app').getByText(/命令已运行/).count(), 0)
      assert.deepEqual(await page.locator('.quotes strong').allTextContents(), ['99.00', '99.00', '0.00'])
      assert.equal(writes.length, 2); assert.equal(writes[1].payload.requestKey, fixture.key)
    } else {
      fixture.setStatus({ available: false, sourceAvailable: false, progressStatus: 'WAITING_SOURCE', degraded: true, controlProgressWatermark: 42000, progressStartedAt: 40000, progressEndAt: 50000, expectedSampledUntil: 42000, committedAt: 43000 })
      await progressIs(page, 20, '等待有效原始行情', 42000)
      await page.getByText('等待有效行情源；最后可信价格仅供展示，不可交易', { exact: true }).waitFor()
      fixture.setStatus({ committedAt: 59000, remainingSeconds: 0 })
      const committed = await page.evaluate(value => new Date(value).toLocaleString(), 59000)
      await page.locator('#app').getByText(new RegExp(`快照提交 ${committed.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`)).waitFor()
      await progressIs(page, 20, '等待有效原始行情', 42000)
      assert.equal(await source.isDisabled(), false)
      fixture.setStatus({ available: true, sourceAvailable: true, progressStatus: 'HEALTHY', degraded: false, progressStartedAt: 60000, progressEndAt: 68000, controlProgressWatermark: 62000, expectedSampledUntil: 62000, committedAt: 62000, remainingSeconds: 6 })
      await progressIs(page, 25, '正常', 62000)
      fixture.setStatus({ controlProgressWatermark: 64000, expectedSampledUntil: 64000, committedAt: 64000, remainingSeconds: 4 })
      await progressIs(page, 50, '正常', 64000)
      fixture.setStatus({ enabled: false, running: false, restoring: false, controlState: 'SOURCE', rawPrice: 99, currentPrice: 99, offset: 0, controlProgressWatermark: 68000, expectedSampledUntil: 68000, remainingSeconds: 0 })
      await page.waitForFunction(() => !document.querySelector('[role=progressbar]') && document.querySelectorAll('.quotes strong')[2]?.textContent === '0.00')
      assert.equal((await fixture.saved()).requestKey, key); assert.equal(writes.length, 0, 'waiting/resume polls never replay START/RESTORE')
    }
    assert.deepEqual(fixture.errors, [])
    console.log('PASS browser recovery: ' + scenario)
    await page.close()
  }
  console.log('Browser scope: actual AiControl.vue, router, permissions, Axios and Chrome; intercepted API only; no production authentication, backend or MySQL acceptance.')
}
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    for (const scenario of ['cancel-unknown', 'source-unknown', 'cancel-read-failure', 'restore-202']) {
      const page = await browser.newPage(), errors = [], writes = []
      let state = 'UNKNOWN', action = 'START', activeKey = key, statusFails = false
      let status = { id: 7, enabled: false, running: false, restoring: false, available: true, sourceAvailable: true, canStart: true,
        controlState: 'SOURCE', rawPrice: 99, currentPrice: 99, offset: 0, remainingSeconds: 0, durationSeconds: 10, intensity: 2 }
      const receipt = () => ({ commandId: 'durable-fixture-1', requestKey: activeKey, symbolId: 7, state, action: state === 'CANCELLED' ? 'CANCEL' : action })
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(({ login, storageKey, key, scenario }) => {
        if (sessionStorage.getItem('qa-initialized')) return
        sessionStorage.setItem('qa-initialized', '1')
        sessionStorage.setItem('exchange.admin.session.v2', JSON.stringify({ token: 'isolated-qa', mode: 'admin', loginSessionId: login, user: { id: 11, tenantId: 2, userType: 'admin' } }))
        if (scenario !== 'restore-202') sessionStorage.setItem(storageKey, JSON.stringify({ symbolId: 7, action: 'start', requestKey: key, payload: { durationSeconds: 10, intensity: 2, targetPrice: 100, randomOscillation: false } }))
      }, { login, storageKey, key, scenario })
      await page.route('**/*', async route => {
        const url = new URL(route.request().url())
        if (url.origin !== new URL(base).origin) return route.abort('blockedbyclient')
        if (!url.pathname.startsWith('/api/')) return route.continue()
        const path = url.pathname, method = route.request().method()
        if (path.startsWith('/api/admin/table-preferences/')) return route.fulfill({ json: method === 'GET' ? [] : { success: true } })
    if (path === '/api/admin/menus/current') return route.fulfill({ json: { success: true, menus: [{ id: 1, menuCode: 'ai_control', menuName: 'AI 控盘', path: '/ai-control' }], groups: [], actions: { ai_control: ['*'] } } })
        if (path.endsWith('/ai-control/symbols')) return route.fulfill({ json: [{ id: 7, symbol: 'RECOVERY_FIXTURE', pricePrecision: 2, isEnabled: true }] })
        if (path.endsWith('/ai-control/7/history')) return route.fulfill({ json: [] })
        if (path.endsWith('/ai-control/7/commands')) return state === 'UNKNOWN' ? route.fulfill({ status: 404, json: { message: '启动命令不存在' } }) : route.fulfill({ json: receipt() })
        if (path.endsWith('/ai-control/7') && method === 'GET') return statusFails ? route.fulfill({ status: 503, json: { message: 'fixture status unavailable' } }) : route.fulfill({ json: status })
        if (path.includes('/ai-control/') && method === 'POST') {
          const payload = route.request().postDataJSON(); writes.push({ path, payload })
          if (path.endsWith('/restore')) { activeKey = payload.requestKey; action = 'RESTORE'; state = 'ACCEPTED'; return route.fulfill({ status: 202, json: receipt() }) }
          assert.equal(payload.requestKey, activeKey)
          state = 'CANCELLED'
          if (path.endsWith('/manual')) {
            status = { ...status, available: false, sourceAvailable: false, canStart: false, controlState: 'SOURCE', progressStatus: 'WAITING_VALID_SOURCE', degraded: true,
              sampledUntil: 1000, controlProgressWatermark: 3000, expectedSampledUntil: 3000, controlLagMillis: 0, sourceEventAt: 900, lastSourceReceivedAt: 1000, committedAt: 2000 }
            return route.fulfill({ json: status })
          }
          assert(path.endsWith('/stop')); statusFails = scenario === 'cancel-read-failure'
          return route.fulfill({ json: receipt() })
        }
        return route.fulfill({ json: { success: true, list: [], data: {}, count: 0, total: 0 } })
      })
      await page.goto(base + '/ai-control')
      const source = page.getByRole('button', { name: '一键恢复原始行情', exact: true })
      await source.waitFor(); await page.waitForFunction(() => !document.querySelector('.actions button:last-child')?.disabled)
      if (scenario === 'restore-202') {
        await page.getByText('渐进恢复', { exact: true }).click()
        await page.getByRole('button', { name: '按设定恢复原始行情', exact: true }).click()
        await page.locator('#app').getByText('恢复命令已受理，尚未开始运行', { exact: true }).waitFor()
        assert.equal(writes.length, 1); assert.equal(writes[0].path, '/api/admin/ai-control/7/restore')
        assert.equal(await source.isDisabled(), false)
        await page.reload(); await page.locator('#app').getByText('恢复命令已受理，尚未开始运行', { exact: true }).waitFor()
        assert.equal(writes.length, 1, 'refresh only queries the original restore key')
        state = 'RUNNING'; status = { ...status, controlState: 'RECOVERING', enabled: true, running: true, restoring: true, progressStatus: 'HEALTHY',
          sampledUntil: 1000, controlProgressWatermark: 22000, progressStartedAt: 20000, progressEndAt: 30000, expectedSampledUntil: 22000 }
        await page.getByText(/进度 正常；已提交水位/).waitFor()
        await page.waitForFunction(() => document.querySelector('[role=progressbar]')?.getAttribute('aria-valuenow') === '20')
        const watermarkText = await page.evaluate(() => new Date(22000).toLocaleString())
        assert((await page.getByText(/进度 正常；已提交水位/).textContent()).includes('已提交水位 ' + watermarkText), 'rendered recovery progress uses the flow watermark, not initial task metadata')
        status = { ...status, progressStatus: 'ENGINE_LAG', degraded: true, available: false }
        await page.getByText('控盘推进延迟；快照刷新不代表控盘推进', { exact: true }).waitFor()
      } else {
        await page.getByText(/原控盘请求结果待确认.*启动命令不存在/).waitFor()
        assert.equal(await page.getByRole('button', { name: '开始自动控盘', exact: true }).isDisabled(), true)
        assert.equal(await source.isDisabled(), false)
        const cancel = page.getByRole('button', { name: '取消待确认命令', exact: true })
        assert.equal(await cancel.isDisabled(), false)
        await (scenario === 'source-unknown' ? source : cancel).click()
        if (scenario === 'cancel-read-failure') {
          await page.getByText('控盘状态加载失败：fixture status unavailable', { exact: true }).waitFor()
          assert.equal((await page.evaluate(key => JSON.parse(sessionStorage.getItem(key)), storageKey)).receipt.state, 'CANCELLED')
          assert.equal(await source.isDisabled(), false)
          statusFails = false
        }
        await page.waitForFunction(key => sessionStorage.getItem(key) === null, storageKey)
        assert.equal(writes.length, 1); assert.equal(writes[0].payload.requestKey, key)
        if (scenario === 'source-unknown') {
          await page.getByText('等待有效行情源；最后可信价格仅供展示，不可交易', { exact: true }).waitFor()
          await page.getByText(/进度 等待有效原始行情；已提交水位/).waitFor()
          await page.getByText('等待有效原始行情；快照刷新不代表控盘推进', { exact: true }).waitFor()
          const watermarkText = await page.evaluate(() => new Date(3000).toLocaleString())
          assert((await page.getByText(/进度 等待有效原始行情；已提交水位/).textContent()).includes('已提交水位 ' + watermarkText))
        }
      }
      assert.deepEqual(errors, [])
      console.log('PASS browser recovery: ' + scenario)
      await page.close()
    }
    await browserInterruptions(browser)
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
