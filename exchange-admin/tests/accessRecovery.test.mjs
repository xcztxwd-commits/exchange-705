import assert from 'node:assert/strict'
import fs from 'node:fs'
import { transformSync } from 'esbuild'
import axios from 'axios'
import { reactive, watchEffect } from 'vue'
import { assertAdminRequestTarget } from '../src/utils/adminSession.ts'

const read = path => fs.readFileSync(new URL('../' + path, import.meta.url), 'utf8')
const compile = source => transformSync(source.replace(/^import .*$/gm, '').replace(/^export default .*$/gm, '').replace(/^export /gm, ''), { loader: 'ts', target: 'es2022' }).code
const snapshot = { success: true, menus: [{ menuCode: 'durations', path: '/durations' }], groups: [], actions: { durations: ['create'] } }
const fromPage = { path: '/durations', matched: [{ path: '/' }] }
const target = path => ({ path: path.split('?')[0], fullPath: path, query: {} })

function environment() {
  let client, permissions, mode = 'ok', deferred, writes = 0
  const redirects = [], calls = []
  const auth = { token: 'qa-one', user: { tenantId: 2, userType: 'admin' }, isControl: false,
    load() {}, ensureValid() { return !!this.token }, logout() { this.token = null; permissions.clearAccess() } }
  permissions = new Function('reactive', 'watchEffect', 'useAuthStore', 'request', compile(read('src/utils/access.ts')) + ';return {access,clearAccess,loadAccess,can,canRoute}')(
    reactive, watchEffect, () => auth, { get: (...args) => client.get(...args) })
  const source = compile(read('src/utils/request.ts').replaceAll('import.meta.env', 'environment'))
  const clients = new Function('axios', 'access', 'useAuthStore', 'assertAdminRequestTarget', 'location', 'window', 'environment', source + ';return {instance,rawRequest}')(
    axios, permissions.access, () => auth, assertAdminRequestTarget, { origin: 'https://admin.example.test' }, { location: { replace: path => redirects.push(path) } }, { BASE_URL: '/' })
  client = clients.instance
  const adapter = async config => {
    calls.push(config.url)
    if (!['get', 'head', 'options'].includes(config.method)) writes++
    let status = 200, data = { success: true }
    if (config.url === '/admin/menus/current') {
      if (deferred) return deferred(config)
      if (mode === 'network') throw new axios.AxiosError('Network Error', 'ERR_NETWORK', config)
      if (mode === 'maintenance') { status = 503; data = { code: 'RELEASE_MAINTENANCE' } }
      else if (mode === 'denied') { status = 403; data = { success: false, message: '无权访问' } }
      else if (mode === 'edge-denied') { status = 403; data = '<html>Gateway denied</html>' }
      else if (mode === 'expired') { status = 401; data = { message: '登录已失效' } }
      else data = mode === 'empty' ? { success: true, menus: [], groups: [], actions: {} } : mode === 'invalid' ? { success: true } : snapshot
    } else if (config.url === '/admin/unrelated-failure') status = 500
    const response = { status, statusText: String(status), data, headers: {}, config }
    if (status >= 400) throw new axios.AxiosError('HTTP ' + status, 'ERR_BAD_RESPONSE', config, {}, response)
    return response
  }
  for (const instance of Object.values(clients)) instance.defaults.adapter = adapter
  const router = { resolve: path => ({ path: path.split('?')[0] }), replace: async path => { redirects.push(path) } }
  const guardSource = read('src/router/index.ts').split('router.beforeEach(')[1].split('\nexport default router')[0].trim().replace(/\)$/, '')
  const guard = new Function('useAuthStore', 'access', 'loadAccess', 'canRoute', 'router', compile('const guard = ' + guardSource) + ';return guard')(
    () => auth, permissions.access, permissions.loadAccess, permissions.canRoute, router)
  const menusSource = read('src/views/Layout.vue').match(/const loadMenus = async[\s\S]*?\n\}/)[0]
  const layout = new Function('auth', 'access', 'loadAccess', 'canRoute', 'route', 'router', compile('let permissionActive=true; const loadingMenus={value:false};' + menusSource) + ';return {refresh:loadMenus,stop:()=>{permissionActive=false}}')(
    auth, permissions.access, permissions.loadAccess, permissions.canRoute, { path: '/durations' }, router)
  return { ...permissions, ...clients, auth, guard, layout, redirects, calls, mode: value => { mode = value }, delay: handler => { deferred = handler }, writes: () => writes }
}

const env = environment()
await env.loadAccess()
assert(env.can('durations:create'))
await assert.rejects(env.instance.get('/admin/unrelated-failure'))
assert.equal(env.access.error, '', 'unrelated API failures do not change permission state')
env.mode('maintenance')
assert.equal(await env.layout.refresh(true), false)
assert.match(env.access.error, /发布维护/)
assert.equal(env.access.loaded, true)
assert.deepEqual(env.access.menus, snapshot.menus)
assert(env.can('durations:view'), 'keep already mounted read-only components and drafts')
assert(!env.can('durations:create'), 'cached snapshot must not authorize sensitive actions')
assert.equal(env.redirects.length, 0, 'periodic failure must not unmount the current page')
assert.equal(await env.guard(target('/orders'), fromPage), false, 'failed navigation keeps the existing page')
for (const instance of [env.instance, env.rawRequest]) {
  for (const method of ['post', 'put', 'patch', 'delete']) await assert.rejects(instance.request({ url: '/api/admin/durations', baseURL: '', method }), /敏感操作已暂停/)
}
assert.equal(env.writes(), 0, 'keyboard/programmatic writes must not reach either HTTP adapter')
await env.instance.get('/admin/durations')
await env.instance.post('/admin/auth/control-exit')
await env.instance.post('/admin/auth/control-activity')
env.mode('ok')
assert(await env.layout.refresh(true))
assert.equal(env.access.error, '')
assert(env.can('durations:create'))
await env.instance.post('/admin/durations')
assert.equal(env.writes(), 3)
console.log('PASS maintenance: keep page and confirmed read-only snapshot, cancel navigation, block all HTTP writes, allow session exit/activity, recover in place')

for (const failure of ['network', 'invalid', 'edge-denied']) {
  env.mode(failure)
  await assert.rejects(env.loadAccess(true))
  assert(env.access.error)
  assert(env.can('durations:view'))
  assert(!env.can('durations:create'))
  assert.equal(env.redirects.length, 0)
  env.mode('ok'); await env.loadAccess(true)
}
const boot = environment()
boot.mode('maintenance')
assert.deepEqual(await boot.guard(target('/durations?tab=draft'), { path: '', matched: [] }), { path: '/access-unavailable', query: { redirect: '/durations?tab=draft' } })
assert(!boot.can('durations:view'))
assert(!boot.can('durations:create'))
assert.equal(await boot.guard(target('/access-unavailable'), { path: '', matched: [] }), true)
boot.mode('ok'); await boot.loadAccess(true)
const beforeRecovery = boot.calls.length
assert.equal(await boot.guard({ ...target('/access-unavailable'), query: { redirect: '/durations?tab=draft' } }, { path: '/access-unavailable', matched: [] }), '/durations?tab=draft')
assert.equal(boot.calls.length, beforeRecovery, 'recovery does not immediately repeat a successful read')
for (const redirect of ['//evil.example/steal', 'https://evil.example/steal', '/unauthorized']) {
  assert.equal(await boot.guard({ ...target('/access-unavailable'), query: { redirect } }, { path: '/access-unavailable', matched: [] }), '/durations')
}
console.log('PASS network, malformed response and edge 403: pause without stale writes; cold start has no fallback grants; recovery respects original route/query and rejects external/ungranted destinations')

for (const denial of ['empty', 'denied']) {
  env.mode(denial)
  assert(await env.layout.refresh(true))
  assert.equal(env.redirects.at(-1), '/forbidden')
  assert.equal(env.access.error, '')
  assert(!env.can('durations:view'))
  assert(!env.can('durations:create'))
  const before = env.calls.length
  assert.equal(await env.guard(target('/forbidden'), fromPage), true)
  assert.equal(env.calls.length, before, 'confirmed denial is not undone by another failing request')
  env.mode('ok'); await env.loadAccess(true)
}
for (const control of [false, true]) {
  const expired = environment(); await expired.loadAccess()
  expired.auth.isControl = control; expired.mode('expired')
  assert.equal(await expired.guard(target('/durations'), fromPage), control ? '/access-ended' : '/login')
  assert.equal(expired.auth.token, null)
  assert.equal(expired.access.loaded, false)
  assert.equal(expired.access.error, '')
}
console.log('PASS authoritative empty grants/JSON 403 deny immediately; 401 logs out normal and control sessions without pretending to be a temporary outage')

const late = environment(); await late.loadAccess()
let rejectOld
late.delay(config => new Promise((_, reject) => { rejectOld = () => reject(new axios.AxiosError('old maintenance', 'ERR_BAD_RESPONSE', config, {}, { status: 503, data: { code: 'RELEASE_MAINTENANCE' } })) }))
const pending = late.loadAccess(true).catch(() => {})
while (!rejectOld) await Promise.resolve()
late.auth.token = 'qa-two'; late.clearAccess(); late.delay(null); await late.loadAccess()
rejectOld(); await pending
assert.equal(late.access.error, '')
assert(late.can('durations:create'))
const stopped = environment(); await stopped.loadAccess(); stopped.layout.stop(); stopped.mode('empty')
assert.equal(await stopped.layout.refresh(true), false)
assert.equal(stopped.redirects.length, 0)
console.log('PASS old-session failures cannot poison a new session; unmounted layout cannot redirect on a late refresh')
