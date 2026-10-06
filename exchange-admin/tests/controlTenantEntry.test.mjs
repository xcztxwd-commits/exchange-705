import assert from 'node:assert/strict'
import fs from 'node:fs'
import ts from 'typescript'
import { transform } from 'esbuild'
import { parse } from '@vue/compiler-sfc'
import { ref, computed } from 'vue'
import { createPinia, defineStore } from 'pinia'
import * as sessions from '../src/utils/adminSession.ts'

// Execute the actual opener, exchange component and Pinia store in isolated window fixtures.
const read = path => fs.readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
async function script(source) {
 const ast = ts.createSourceFile('entry.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
 const code = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast).replace(/^export /, '')).join('\n')
 return (await transform(code, { loader: 'ts', target: 'es2022' })).code
}
const openerCode = await script(read('src/control/openTenant.ts'))
const exchangeCode = await script(parse(read('src/views/ControlExchange.vue')).descriptor.scriptSetup.content)
const authCode = await script(read('src/store/auth.ts'))
const adminOrigin = 'https://admin.example.test', controlOrigin = 'https://control.example.test'
const flush = async () => { for (let i = 0; i < 16; i++) await Promise.resolve() }
const deferred = () => { let resolve, reject;const promise = new Promise((yes, no) => { resolve = yes;reject = no });return { promise, resolve, reject } }
function storage(initial = {}) {
 return { ...initial, getItem(key) { return typeof this[key] === 'string' ? this[key] : null }, setItem(key, value) { this[key] = String(value) }, removeItem(key) { delete this[key] }, clear() { for (const key of Object.keys(this)) if (typeof this[key] === 'string') delete this[key] } }
}
function observed(promise) {
 const result = { done: false, error: null };promise.then(() => { result.done = true }, error => { result.done = true;result.error = error });return result
}
function environment(options = {}) {
 let serial = 0
 const timers = new Map(), listeners = new Set(), opened = [], calls = [], navigations = [], pending = []
 const parentStorage = storage({ 'exchange.control.session.v1': 'parent-control-token', [sessions.ADMIN_SESSION_KEY]: 'stale-copied-admin', 'balance-pending:2:control:1': 'do-not-copy' })
 const later = (callback, delay) => { const id = ++serial;timers.set(id, { callback, delay });return id }
 const clear = id => timers.delete(id)
 const parent = { closed: false, addEventListener: (_, callback) => listeners.add(callback), removeEventListener: (_, callback) => listeners.delete(callback) }
 parent.open = (...args) => {
  if (options.openError) throw new Error('native window failure')
  if (options.blocked) return null
  const childListeners = new Set(), childStorage = storage(Object.fromEntries(Object.entries(parentStorage).filter(([, value]) => typeof value === 'string')))
  const child = { args, closed: false, sessionStorage: childStorage, document: { title: '', body: { textContent: '' } }, close() { this.closed = true;this.unmount?.() }, addEventListener: (_, callback) => childListeners.add(callback), removeEventListener: (_, callback) => childListeners.delete(callback) }
  const proxy = { closed: false, postMessage: (data, origin) => { assert.equal(origin, controlOrigin);queueMicrotask(() => { for (const callback of listeners) callback({ source: child, origin: adminOrigin, data }) }) } }
  child.opener = proxy
  child.posted = []
  child.postMessage = (data, origin) => { child.posted.push({ data, origin });assert.equal(origin, adminOrigin);queueMicrotask(() => { for (const callback of childListeners) callback({ source: proxy, origin: controlOrigin, data }) }) }
  child.location = { origin: controlOrigin, pathname: 'about:blank', replace(url) {
   navigations.push(url);this.origin = adminOrigin;this.pathname = '/control-exchange'
   if (options.manualChild) return
   const storeModule = new Function('defineStore', 'computed', 'ref', 'clearAccess', ...Object.keys(sessions), 'window', 'location', 'sessionStorage', 'localStorage', authCode + ';return {useAuthStore, exchangeOpener}')(
    defineStore, computed, ref, () => {}, ...Object.values(sessions), child, child.location, childStorage, storage())
   child.auth = storeModule.useAuthStore(createPinia())
   const router = { currentRoute: { value: { path: '/control-exchange' } }, replace: async path => { assert.equal(path, '/');if (options.navigationError) throw new Error('后台导航失败');router.currentRoute.value.path = options.forbidden ? '/forbidden' : '/dashboard';child.unmount();return undefined } }
   const request = { get: async path => { assert.equal(path, '/admin/auth/control-exchange-config');return options.config || { adminOrigin, controlOrigin } }, post: (path, body) => { assert.equal(path, '/admin/auth/control-exchange');const response = deferred();pending.push({ child, body, ...response });return response.promise } }
   let mount
   const view = new Function('ref', 'onMounted', 'onUnmounted', 'useRouter', 'useAuthStore', 'exchangeOpener', 'exactOrigin', 'matchesExchangeMessage', 'request', 'window', 'location', 'crypto', 'setTimeout', 'clearTimeout', exchangeCode + ';return {status}')(
    ref, callback => { mount = callback }, callback => { child.unmount = callback }, () => router, () => child.auth, storeModule.exchangeOpener, sessions.exactOrigin, sessions.matchesExchangeMessage, request, child, child.location, crypto, later, clear)
   child.status = view.status;void mount()
  } }
  opened.push(child);return child
 }
 const api = async (path, method, body, signal) => {
  assert.equal(opened.at(-1).args[0], 'about:blank', 'popup opens before the first await/API')
  assert.equal(opened.at(-1).sessionStorage.getItem('exchange.control.session.v1'), null, 'copied control token cleared before navigation')
  assert.equal(parentStorage.getItem('exchange.control.session.v1'), 'parent-control-token')
  assert.equal(method, 'POST');const tenantId = Number(path.match(/tenants\/(\d+)\//)[1]);calls.push({ path, body, signal })
  if (options.apiError) throw new Error('总控登录已失效')
  return options.ticketPromise || options.ticket || { data: { tenantId, adminOrigin, ticket: 't'.repeat(43), expiresAt: Date.now() + 60000 } }
 }
 const openTenant = new Function('exactOrigin', 'api', 'window', 'location', 'crypto', 'AbortController', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', openerCode + ';return openTenant')(
  sessions.exactOrigin, api, parent, { origin: controlOrigin }, crypto, AbortController, later, clear, later, clear)
 return { openTenant, opened, calls, navigations, pending, listeners, timers, parentStorage, emit: event => { for (const callback of listeners) callback(event) }, fire: delay => { for (const [id, timer] of [...timers]) if (timer.delay === delay) { timers.delete(id);timer.callback() } } }
}
const access = (tenantId, changes = {}) => ({ token: `scoped-control-${tenantId}`, user: { userType: 'control', tenantId, controlActorId: 9, account: 'operator' }, accessSession: { id: `access-${tenantId}`, tenantId, tenantName: `Tenant ${tenantId}`, expiresAt: Date.now() + 3600000 }, ...changes })
function cleaned(env) { assert.equal(env.listeners.size, 0);assert.equal(env.timers.size, 0);assert(env.calls.every(call => call.signal.aborted)) }

const env = environment()
for (const tenantId of [2, 3]) {
 const result = observed(env.openTenant(tenantId));assert.equal(env.opened.length, tenantId - 1)
 await flush();const child = env.opened.at(-1), exchange = env.pending.at(-1)
 assert.match(child.args[2], /popup=yes/);assert.equal(child.args[1], '_blank');assert.equal(result.done, false, 'posting a ticket alone is not a successful login')
 assert.equal(exchange.body.expectedTenantId, tenantId);assert.equal(exchange.body.browserBinding, env.calls.at(-1).body.browserBinding)
 assert.equal(child.auth.token, null);assert.equal(child.sessionStorage.getItem('exchange.control.session.v1'), null)
 exchange.resolve(access(tenantId));await flush();assert.equal(result.error, null);assert.equal(result.done, true)
 assert.equal(child.auth.isControl, true);assert.equal(child.auth.user.tenantId, tenantId);assert.equal(child.opener, null);assert.equal(child.closed, false)
 child.auth.load();assert.equal(child.auth.user.tenantId, tenantId, 'refresh retains only the scoped window session')
 assert.equal(env.parentStorage.getItem('exchange.control.session.v1'), 'parent-control-token');cleaned(env)
}
assert.notEqual(env.opened[0].sessionStorage.getItem(sessions.ADMIN_SESSION_KEY), env.opened[1].sessionStorage.getItem(sessions.ADMIN_SESSION_KEY))
assert(env.navigations.every(url => url === adminOrigin + '/control-exchange'), 'no ticket/token/binding/tenant credentials in URL')
console.log('PASS actual opener + exchange + Pinia: synchronous new popup, runtime origins without VITE variables, real control mode, independent tenant windows, parent unchanged, acknowledgement only after navigation')

for (const options of [{ blocked: true }, { openError: true }, { apiError: true }, ...[
 { tenantId: 3 }, { tenantId: '2' }, { ticket: '' }, { ticket: 4 }, { expiresAt: undefined }, { expiresAt: NaN }, { expiresAt: Date.now() - 1 }, { adminOrigin: '' }, { adminOrigin: 'not-a-url' }, { adminOrigin: 'https://admin.example.test/steal' }, { adminOrigin: 'http://admin.example.test' }, { adminOrigin: controlOrigin }
].map(change => ({ ticket: { tenantId: 2, ticket: 't'.repeat(43), expiresAt: Date.now() + 60000, adminOrigin, ...change } }))]) {
 const failed = environment(options), result = observed(failed.openTenant(2));await flush();assert(result.error);assert(failed.opened.every(child => child.closed));assert.doesNotMatch(result.error.message, /Failed to construct|Invalid URL/);cleaned(failed)
}
for (const id of [0, -1, 1.2, NaN, Infinity]) { const invalid = environment();await assert.rejects(invalid.openTenant(id), /目标租户无效/);assert.equal(invalid.opened.length, 0);assert.equal(invalid.calls.length, 0);cleaned(invalid) }
console.log('PASS blocked/native-failed popup, invalid tenant, expired/malformed/misdirected ticket, missing/invalid origin: clean failure, no raw URL exception')

const manual = environment({ manualChild: true }), manualResult = observed(manual.openTenant(2));await flush()
const child = manual.opened[0], challenge = crypto.randomUUID(), ready = { type: 'control-exchange-ready', challenge }
for (const event of [{ source: {}, origin: adminOrigin, data: ready }, { source: child, origin: adminOrigin + '.evil', data: ready }, { source: child, origin: adminOrigin, data: { ...ready, challenge: 'bad' } }]) manual.emit(event)
assert.equal(child.posted.length, 0)
manual.emit({ source: child, origin: adminOrigin, data: ready });manual.emit({ source: child, origin: adminOrigin, data: ready });assert.equal(child.posted.length, 1)
const resultMessage = { type: 'control-exchange-result', challenge, tenantId: 2, success: true }
for (const event of [{ source: {}, origin: adminOrigin, data: resultMessage }, { source: child, origin: adminOrigin + '.evil', data: resultMessage }, { source: child, origin: adminOrigin, data: { ...resultMessage, tenantId: 3 } }, { source: child, origin: adminOrigin, data: { ...resultMessage, challenge: crypto.randomUUID() } }]) manual.emit(event)
assert.equal(manualResult.done, false)
manual.emit({ source: child, origin: adminOrigin, data: resultMessage });await flush();assert.equal(manualResult.error, null);assert.equal(manualResult.done, true);cleaned(manual)
console.log('PASS exact popup source/origin/challenge/target tenant; duplicate ready suppressed; forged acknowledgements ignored')

for (const response of [access(3), access(2, { user: { userType: 'admin', tenantId: 2 } }), access(2, { accessSession: { ...access(2).accessSession, expiresAt: 0 } }), new Error('访问会话无效或已失效')]) {
 const failed = environment(), result = observed(failed.openTenant(2));await flush()
 const exchange = failed.pending[0];response instanceof Error ? exchange.reject(response) : exchange.resolve(response);await flush()
 assert(result.error);assert.equal(exchange.child.auth.token, null);assert.equal(exchange.child.closed, true);assert.equal(exchange.child.sessionStorage.getItem(sessions.ADMIN_SESSION_KEY), null);cleaned(failed)
}
for (const option of ['navigationError', 'forbidden']) {
 const failed = environment({ [option]: true }), result = observed(failed.openTenant(2));await flush();failed.pending[0].resolve(access(2));await flush();assert(result.error);assert.equal(failed.opened[0].auth.token, null);cleaned(failed)
}
const wrongConfig = environment({ config: { adminOrigin: 'https://wrong.example.test', controlOrigin } }), wrongResult = observed(wrongConfig.openTenant(2));await flush();assert.equal(wrongConfig.pending.length, 0);assert.match(wrongConfig.opened[0].status.value, /配置不匹配/);wrongConfig.fire(65000);await flush();assert(wrongResult.error);cleaned(wrongConfig)
console.log('PASS exchange/identity/tenant/expiry/navigation failure: error acknowledgement, no admin fallback, no stale auth')

const waiting = deferred(), closed = environment({ ticketPromise: waiting.promise }), closedResult = observed(closed.openTenant(2));closed.opened[0].closed = true;closed.fire(500);await flush();assert.match(closedResult.error.message, /已关闭/);cleaned(closed)
waiting.resolve({ tenantId: 2, ticket: 't'.repeat(43), expiresAt: Date.now() + 60000, adminOrigin });await flush();assert.equal(closed.navigations.length, 0, 'late ticket cannot navigate or reopen a closed window')
const timeout = environment({ manualChild: true }), timeoutResult = observed(timeout.openTenant(2));await flush();timeout.fire(65000);await flush();assert.match(timeoutResult.error.message, /超时/);cleaned(timeout)
assert.doesNotMatch(read('src/control/openTenant.ts') + read('src/views/ControlExchange.vue'), /VITE_(?:ADMIN|CONTROL)_ORIGIN|document\.referrer|location\.search/)
assert.match(read('src/utils/request.ts'), /publicAuth = new Set\([^\n]*control-exchange-config/)
console.log('PASS close/timeout/late response cleanup; origin trust uses server only; no credentials in URLs or build variables')
