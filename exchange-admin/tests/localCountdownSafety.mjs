import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'
import * as pinia from 'pinia'
import { parse } from '@vue/compiler-sfc'

const policy = (ok, message) => assert(ok, `LOCAL_COUNTDOWN_POLICY: ${message}`)
const walk = (node, visit) => { visit(node); ts.forEachChild(node, child => walk(child, visit)) }
const tree = source => ts.createSourceFile('countdown.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const printer = ts.createPrinter({ removeComments: true })
const text = node => printer.printNode(ts.EmitHint.Unspecified, node, node.getSourceFile()).replace(/\s+/g, '')
const calls = (node, name) => { const found = []; walk(node, n => { if (ts.isCallExpression(n) && text(n.expression) === name) found.push(n) }); return found }
const timers = new Set(['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout', 'requestAnimationFrame', 'requestIdleCallback', 'queueMicrotask'])
const reactiveEffects = new Set(['watch', 'watchEffect', 'watchPostEffect', 'watchSyncEffect'])
const transports = new Set(['fetch', 'axios', 'request', 'api', 'XMLHttpRequest', 'WebSocket', 'navigator', 'eval', 'Function', 'require'])

export function readCountdownSources(app) {
  policy(['exchange-frontend', 'exchange-pc'].includes(app), 'unknown application')
  const read = path => fs.readFileSync(new URL(`../../${app}/src/${path}`, import.meta.url), 'utf8')
  const shared = path => fs.readFileSync(new URL(`../../exchange-frontend/src/utils/${path}`, import.meta.url), 'utf8')
  return { appName: app, app: read('App.vue'), wallet: read('utils/useTrialWallet.ts'), lifecycle: read('utils/trialLifecycle.ts'), accountMode: read('utils/accountMode.ts'), activity: read('utils/pageActivity.ts'), features: shared('tenantFeatures.ts'), capabilities: shared('tenantCapabilities.ts'), uiEdition: shared('uiEdition.ts') }
}

function pureComputed(ast, expected) {
  const found = calls(ast, 'computed')
  policy(found.length === Object.keys(expected).length, 'computed dependency shape changed; review effects')
  for (const call of found) {
    const declaration = call.parent, callback = call.arguments[0]
    policy(ts.isVariableDeclaration(declaration) && declaration.initializer === call && Object.hasOwn(expected, text(declaration.name)), 'computed binding must be reviewed')
    policy(call.arguments.length === 1 && ts.isArrowFunction(callback) && callback.parameters.length === 0 && !callback.modifiers?.length && !ts.isBlock(callback.body), 'computed must be a pure expression')
    walk(callback.body, node => {
      policy(!ts.isNewExpression(node) && !ts.isAwaitExpression(node) && !ts.isDeleteExpression(node) && !ts.isPostfixUnaryExpression(node), 'computed cannot create effects')
      if (ts.isBinaryExpression(node)) policy(node.operatorToken.kind < ts.SyntaxKind.FirstAssignment || node.operatorToken.kind > ts.SyntaxKind.LastAssignment, 'computed cannot assign')
      if (ts.isPrefixUnaryExpression(node)) policy(![ts.SyntaxKind.PlusPlusToken, ts.SyntaxKind.MinusMinusToken].includes(node.operator), 'computed cannot mutate')
      if (ts.isCallExpression(node)) policy(expected[text(declaration.name)].includes(text(node.expression)), 'computed cannot invoke an unreviewed helper')
    })
  }
}

// Only unwrap the reviewed route getter's optional `as const`, never arbitrary casts/helpers.
const watchSource = source => {
  if (ts.isArrowFunction(source) && source.parameters.length === 0 && !source.modifiers?.length) {
    let body = source.body
    if (ts.isAsExpression(body) && text(body.type) === 'const') body = body.expression
    if (ts.isArrayLiteralExpression(body) && body.elements.length === 2 && text(body.elements[0]) === 'route.path' && text(body.elements[1]) === 'route.matched.length') return '()=>[route.path,route.matched.length]'
  }
  return text(source)
}

// Fail closed for this reviewed App/store clock, not an arbitrary-program network analyser.
// Other routed trade components and their polling require their own owner acceptance.
export function assertLocalCountdownSafety(sources) {
  const parsed = parse(sources.app)
  policy(!parsed.errors.length && parsed.descriptor.scriptSetup, 'valid App script setup required')
  const app = tree(parsed.descriptor.scriptSetup.content)
  policy(!/heartbeat/i.test(sources.app), 'no permanent heartbeat')
  const interval = calls(app, 'setInterval'), cleanup = calls(app, 'clearInterval')
  policy(interval.length === 1 && cleanup.length === 1, 'exactly one local interval and its cleanup required')
  const call = interval[0], callback = call.arguments[0]
  policy(call.arguments.length === 2 && text(call.arguments[1]) === '1000', 'local tick interval must be 1000 ms')
  policy(ts.isArrowFunction(callback) && callback.parameters.length === 0 && !callback.modifiers?.length && ts.isBlock(callback.body) && callback.body.statements.length === 1, 'timer can only update the local clock')
  policy(text(callback.body.statements[0]) === 'trialWallet.tick=performance.now();', 'timer cannot call network or indirect helpers')
  policy(ts.isBinaryExpression(call.parent) && text(call.parent.left) === 'trialTimer' && call.parent.operatorToken.kind === ts.SyntaxKind.EqualsToken, 'timer handle must be retained')
  const lifecycle = (node, hook) => {
    const statement = node.parent, block = statement.parent, fn = block.parent
    return ts.isExpressionStatement(statement) && ts.isBlock(block) && ts.isArrowFunction(fn) && ts.isCallExpression(fn.parent) && text(fn.parent.expression) === hook && fn.parent.arguments[0] === fn
  }
  policy(lifecycle(call.parent, 'onMounted') && lifecycle(cleanup[0], 'onUnmounted'), 'timer must start on mount and stop on unmount')
  policy(text(cleanup[0]) === 'clearInterval(trialTimer)', 'unmount must clear the same timer handle')
  policy(calls(app, 'onMounted').length === 1 && calls(app, 'onUnmounted').length === 1, 'lifecycle shape must be reviewed')
  const watched = new Set(['()=>auth.token', '()=>[route.path,route.matched.length]', '[()=>auth.token,()=>auth.user?.id,()=>auth.user?.tenantId]'])
  policy(calls(app, 'watch').length === 3, 'extra reactive subscription requires review')
  for (const watch of calls(app, 'watch')) policy(watched.has(watchSource(watch.arguments[0])), 'watch cannot subscribe to a clock or an indirect helper')
  walk(app, node => {
    if (!ts.isIdentifier(node)) return
    if (timers.has(node.text)) {
      const allowed = (node === call.expression || node === cleanup[0].expression) || (node.text === 'setInterval' && ts.isTypeQueryNode(node.parent))
      policy(allowed, 'no additional, aliased or recursive scheduler')
    }
    if (reactiveEffects.has(node.text)) policy(node.text === 'watch' && ((ts.isImportSpecifier(node.parent) && !node.parent.propertyName) || (ts.isCallExpression(node.parent) && node.parent.expression === node)), 'no aliased reactive subscription')
    if (node.text === 'trialWallet') {
      if (ts.isVariableDeclaration(node.parent) && node.parent.name === node) return
      const member = node.parent
      policy(ts.isPropertyAccessExpression(member) && member.expression === node && ['tick', 'reset', 'refresh'].includes(member.name.text), 'no clock subscription or helper alias through the store')
      if (member.name.text !== 'tick') policy(ts.isCallExpression(member.parent) && member.parent.expression === member, 'wallet methods cannot be aliased')
      else policy(ts.isBinaryExpression(member.parent) && member.parent.left === member && member.parent.operatorToken.kind === ts.SyntaxKind.EqualsToken && text(member.parent.right) === 'performance.now()', 'tick cannot invoke an unreviewed getter/helper')
    }
  })
  pureComputed(app, sources.appName === 'exchange-frontend'
    ? { activityPlacement: ['activityPosition'], presentationEdition: ['routeUiEdition'], viewKey: ['accountMode'] }
    : { activityPlacement: ['activityPosition'] })
  const wallet = tree(sources.wallet)
  pureComputed(wallet, { serverNow: ['clock.now'], state: ['trialState', 'accountMode'], remaining: ['countdown'], ready: [] })
  for (const [name, source] of Object.entries({ wallet: sources.wallet, lifecycle: sources.lifecycle, accountMode: sources.accountMode, activity: sources.activity })) {
    const ast = tree(source)
    policy(!ast.parseDiagnostics.length, `${name}: valid TypeScript required`)
    walk(ast, node => {
      if (!ts.isIdentifier(node)) return
      policy(!timers.has(node.text) && !reactiveEffects.has(node.text), `${name}: no scheduler/reactive network effect`)
      if (['lifecycle', 'accountMode'].includes(name)) policy(!transports.has(node.text), `${name}: clock helpers cannot perform network effects`)
      if (name === 'wallet' && node.text === 'clock' && !(ts.isVariableDeclaration(node.parent) && node.parent.name === node)) {
        const member = node.parent
        policy(ts.isPropertyAccessExpression(member) && ['now', 'sync', 'reset'].includes(member.name.text) && ts.isCallExpression(member.parent) && member.parent.expression === member, 'clock methods cannot be replaced or aliased')
      }
    })
  }
  return { app: sources.appName, staticPolicy: 'reviewed-local-clock-only', intervalCount: 1, lifecycleCleanup: true }
}

// Actual App/helper TypeScript and Vue/Pinia reactivity execute with a deterministic
// scheduler and in-memory transports. This is a source-level unit test, not Chrome,
// rendered routed children, real HTTP or financial acceptance.
export async function observeCountdownRuntime(sources) {
  let monotonic = 100000, nextId = 0
  const epoch = Date.parse('2026-10-01T00:00:00Z'), intervals = new Map(), mounted = [], unmounted = [], network = []
  class Surface extends EventTarget {
    listeners = new Map()
    addEventListener(type, fn, options) { const list = this.listeners.get(type) || new Set(); list.add(fn); this.listeners.set(type, list); super.addEventListener(type, fn, options) }
    removeEventListener(type, fn, options) { this.listeners.get(type)?.delete(fn); super.removeEventListener(type, fn, options) }
    count() { return [...this.listeners.values()].reduce((total, list) => total + list.size, 0) }
  }
  const document = Object.assign(new Surface(), { visibilityState: 'visible' }), window = new Surface()
  const auth = vue.reactive({ token: 'synthetic-test-only', user: { id: 1, tenantId: 2 }, load() {} }), route = vue.reactive({ path: '/', matched: [], query: { token: 'synthetic-private' } })
  const assets = () => ({ success: true, serverNow: new Date(epoch + monotonic).toISOString(), trialEligible: true, trialAvailable: 10, trialFrozen: 0, fundingSources: ['TRIAL', 'CONTRACT'], trialExpiresAt: new Date(epoch + 115000).toISOString() })
  const request = { get: async path => { network.push({ method: 'GET', path }); monotonic += 0.25; return assets() }, post: async (path, body) => { network.push({ method: 'POST', path, body }); return { success: true } } }
  const storage = new Map([['user', JSON.stringify(auth.user)]])
  const globalStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)) }
  const context = vm.createContext({ console, AbortController, AbortSignal, Event, CustomEvent, URL, performance: { now: () => monotonic }, Date: class extends Date { static now() { return epoch + monotonic } }, localStorage: globalStorage, sessionStorage: { getItem: () => 'REAL' }, document, window,
    setInterval: (fn, delay) => { assert.equal(delay, 1000, 'LOCAL_COUNTDOWN_RUNTIME: scheduler period'); intervals.set(++nextId, fn); return nextId }, clearInterval: id => intervals.delete(id),
    setTimeout: () => assert.fail('LOCAL_COUNTDOWN_RUNTIME: unreviewed timeout'), clearTimeout() {},
    fetch: async path => { network.push({ method: 'FETCH', path }); return { ok: true, json: async () => ({ tenantId: 2, status: 'ACTIVE', acceptNewBusiness: true, features: {} }) } }, axios: request })
  const owner = pinia.createPinia(); pinia.setActivePinia(owner)
  const scope = vue.effectScope(), modules = new Map()
  const framework = { ...vue, onMounted: fn => mounted.push(fn), onUnmounted: fn => unmounted.push(fn) }
  function evaluate(key, source) {
    if (modules.has(key)) return modules.get(key)
    const module = { exports: {} }; modules.set(key, module.exports)
    const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, esModuleInterop: true } }).outputText
    const require = name => {
      if (name === 'vue') return framework
      if (name === 'vue-router') return { useRoute: () => route }
      if (name === 'pinia') return pinia
      if (name.endsWith('/auth')) return { useAuthStore: () => auth }
      if (name === '@/store/uiEdition') return { useUiEditionStore: () => vue.reactive({ edition: 'classic' }) }
      if (name === '@/utils/uiEdition') return evaluate('uiEdition', sources.uiEdition)
      if (name === '@/advanced/pageRegistry') return { advancedPageForPath: () => undefined }
      if (name.endsWith('/locale')) return { useLocaleStore: () => ({ locale: 'zh', loadLocale() {} }) }
      if (name.endsWith('/request')) return { __esModule: true, default: request }
      for (const part of ['useTrialWallet', 'trialLifecycle', 'pageActivity', 'tenantFeatures', 'tenantCapabilities']) if (name.endsWith('/' + part)) return evaluate(part, { useTrialWallet: sources.wallet, trialLifecycle: sources.lifecycle, pageActivity: sources.activity, tenantFeatures: sources.features, tenantCapabilities: sources.capabilities }[part])
      if (name.endsWith('/accountMode')) {
        const functions = tree(sources.accountMode).statements.filter(n => ts.isFunctionDeclaration(n) && ['accountModeKey', 'accountMode'].includes(n.name?.text)).map(n => n.getText()).join('\n')
        return evaluate('accountMode', functions)
      }
      if (name.endsWith('.vue')) return { __esModule: true, default: {} }
      if (name.endsWith('/elementLocale')) return { elementLocales: { zh: {} } }
      if (name === 'element-plus') return { ElConfigProvider: {} }
      throw Error(`LOCAL_COUNTDOWN_RUNTIME: unreviewed import ${name}`)
    }
    new vm.Script(`(function(require,module,exports){${code}\n})`, { filename: `${sources.appName}:${key}` }).runInContext(context)(require, module, module.exports)
    return module.exports
  }
  const flush = async () => { for (let i = 0; i < 4; i++) { await Promise.resolve(); await vue.nextTick() } }
  let timerCallbacks = 0
  const advance = async seconds => { for (let i = 0; i < seconds; i++) { monotonic += 1000; for (const callback of [...intervals.values()]) { timerCallbacks++; callback() } await flush(); void wallet.remaining; void wallet.state; void wallet.ready } }
  let wallet
  try {
    scope.run(() => evaluate('App', parse(sources.app).descriptor.scriptSetup.content))
    wallet = evaluate('useTrialWallet', sources.wallet).useTrialWallet(owner)
    assert.equal(mounted.length, 1); assert.equal(unmounted.length, 1)
    mounted[0](); await flush()
    assert.equal(intervals.size, 1, 'LOCAL_COUNTDOWN_RUNTIME: exactly one interval'); assert.equal(wallet.ready, true); assert.equal(wallet.state.eligible, true)
    const first = wallet.remaining, startupRequests = network.length
    const pagePosts = () => network.filter(x => x.method === 'POST' && x.path === '/auth/activity')
    const expectPage = (page, reason) => {
      const reports = pagePosts()
      assert(reports.length > 0, `LOCAL_COUNTDOWN_RUNTIME: ${reason} must report`)
      for (const report of reports) {
        assert.equal(report.body.pageCode, page, `LOCAL_COUNTDOWN_RUNTIME: ${reason} must not report a wrong default page`)
        assert.equal(report.body.deviceType, sources.appName === 'exchange-pc' ? 'PC' : 'MOBILE')
        assert.deepEqual(Object.keys(report.body).sort(), ['deviceType', 'pageCode', 'sequence'])
        assert(!JSON.stringify(report.body).includes('synthetic-private'), 'LOCAL_COUNTDOWN_RUNTIME: navigation must not leak query data')
      }
    }
    assert(startupRequests > 0, 'transport spies must observe genuine startup source calls')
    assert.equal(pagePosts().length, 0, 'LOCAL_COUNTDOWN_RUNTIME: unmatched startup must not post page activity; initial assets/config reads are permitted')
    network.length = 0; route.path = '/customer-service'; await flush()
    assert.equal(pagePosts().length, 0, 'LOCAL_COUNTDOWN_RUNTIME: pending support navigation must not report before matched readiness')
    network.length = 0
    route.matched = [{ path: '/customer-service' }]; await flush()
    expectPage('support', 'same-path matched zero-to-one readiness')
    network.length = 0
    await advance(120)
    assert.equal(network.length, 0, 'LOCAL_COUNTDOWN_RUNTIME: visible timer must not request/report')
    assert.notEqual(wallet.remaining, first, 'actual computed countdown advances')
    assert.equal(wallet.state.eligible, false, 'actual local UTC expiry boundary is evaluated')
    assert.equal(wallet.ready, false, 'stale snapshot stays fail-closed without polling')
    assert.equal(network.length, 0, 'LOCAL_COUNTDOWN_RUNTIME: visible timer must not request/report')
    document.visibilityState = 'hidden'; document.dispatchEvent(new Event('visibilitychange')); window.dispatchEvent(new Event('focus')); await flush()
    await advance(120)
    assert.equal(network.length, 0, 'LOCAL_COUNTDOWN_RUNTIME: hidden timer/events must not request/report')
    document.visibilityState = 'visible'; document.dispatchEvent(new Event('visibilitychange')); await flush()
    assert(network.some(x => x.path === '/auth/activity'), 'real visibility event may report activity')
    assert(network.some(x => x.path === '/user/assets'), 'real visibility event may refresh wallet')
    network.length = 0; route.path = '/assets'; route.matched = [{ path: '/assets' }]; await flush()
    expectPage('assets', 'real navigation')
    network.length = 0; route.path = '/customer-service'; route.matched = [{ path: '/customer-service' }]; await flush()
    expectPage('support', 'support navigation')
    network.length = 0; auth.token = ''; await flush(); route.path = '/inbox'; route.matched = [{ path: '/inbox' }]; await flush()
    assert.equal(pagePosts().length, 0, 'LOCAL_COUNTDOWN_RUNTIME: anonymous navigation must not report activity')
    network.length = 0; auth.token = 'synthetic-test-only'; await flush()
    expectPage('inbox', 'authenticated event')
    network.length = 0; document.visibilityState = 'hidden'; route.path = '/customer-service'; route.matched = [{ path: '/customer-service' }]; await flush()
    document.dispatchEvent(new Event('visibilitychange')); await flush()
    assert.equal(pagePosts().length, 0, 'LOCAL_COUNTDOWN_RUNTIME: hidden navigation must not report activity')
    network.length = 0; document.visibilityState = 'visible'; document.dispatchEvent(new Event('visibilitychange')); await flush()
    expectPage('support', 'visible restoration')
    network.length = 0; route.matched = []; await flush(); document.dispatchEvent(new Event('visibilitychange')); await flush()
    assert.equal(pagePosts().length, 0, 'LOCAL_COUNTDOWN_RUNTIME: readiness loss must suppress page reports')
    network.length = 0; route.matched = [{ path: '/customer-service' }]; await flush()
    expectPage('support', 'readiness restoration')
    unmounted[0](); scope.stop(); network.length = 0
    assert.equal(intervals.size, 0, 'LOCAL_COUNTDOWN_RUNTIME: unmount must clear timer')
    assert.equal(document.count() + window.count(), 0, 'LOCAL_COUNTDOWN_RUNTIME: unmount must remove listeners')
    const before = timerCallbacks; await advance(10)
    document.dispatchEvent(new Event('visibilitychange')); window.dispatchEvent(new Event('focus')); route.path = '/assets'; route.matched = [{ path: '/assets' }]; auth.token = 'synthetic-after-unmount'; await flush()
    assert.equal(timerCallbacks, before); assert.equal(network.length, 0, 'unmounted application cannot request/report')
    return { app: sources.appName, timerCallbacks, simulatedVisibleSeconds: 120, simulatedHiddenSeconds: 120, periodicRequests: 0, startupRequests, initialUnmatchedActivityPosts: 0, pendingSupportUnmatchedActivityPosts: 0, samePathReadinessSupport: true, supportNavigation: true, anonymousPageSuppressed: true, hiddenNavigationSuppressed: true, readinessLossSuppressed: true, queryNotReported: true, countdownAdvanced: true, expiredLocally: true, staleFailClosed: true, hiddenEventsSuppressed: true, eventDrivenReportsPreserved: true, unmountCleared: true, mockedTransport: true, browser: false }
  } finally { scope.stop(); intervals.clear(); pinia.disposePinia(owner) }
}
