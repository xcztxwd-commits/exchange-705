import assert from 'node:assert/strict'
import fs from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { readSession, clearAdminSession, createAdminSession, ADMIN_SESSION_KEY } from '../src/utils/adminSession.ts'
import * as commands from '../src/utils/aiControlCommand.ts'

class Storage {
  getItem(key) { return this[key] ?? null }
  setItem(key, value) { this[key] = String(value) }
  removeItem(key) { delete this[key] }
}
const session = (tenantId = 2, id = 11, access = 'access-a') => ({ token: `token-${tenantId}-${id}-${access}`, mode: 'control', user: { tenantId, id, userType: 'control' }, accessSession: { id: access, tenantId, tenantName: 'fixture', expiresAt: Date.now() + 60000 } })
const storage = new Storage(), identity = session(), scope = commands.commandScope(identity)
const pending = { symbolId: 7, action: 'start', requestKey: 'original-request-1234', payload: { durationSeconds: 60, intensity: 3, targetPrice: 123, randomOscillation: true } }
const receipt = state => ({ commandId: 'command-1', requestKey: pending.requestKey, symbolId: 7, state, errorCode: null, message: null })
for (const state of ['ACCEPTED', 'PREPARING', 'READY', 'RUNNING', 'FAILED', 'CANCELLED']) {
  const saved = { ...pending, receipt: commands.readCommandReceipt(receipt(state), pending) }
  assert.equal(commands.commandBlocksStart(saved), !['RUNNING', 'FAILED', 'CANCELLED'].includes(state))
  assert.equal(commands.commandNotice(saved).includes('已运行'), state === 'RUNNING')
  if (state === 'READY') assert.match(commands.commandNotice(saved), /等待引擎启动/)
  if (state === 'FAILED') assert.match(commands.commandNotice(saved), /失败/)
  if (state === 'CANCELLED') assert.match(commands.commandNotice(saved), /取消/)
}
assert(commands.commandBlocksStart(pending))
for (const bad of [null, { ...receipt('RUNNING'), state: 'COMPLETED' }, { ...receipt('RUNNING'), requestKey: 'another-key-12345' }, { ...receipt('RUNNING'), symbolId: 8 }, { ...receipt('RUNNING'), commandId: '' }]) assert.throws(() => commands.readCommandReceipt(bad, pending))
commands.writePendingCommand(storage, scope, pending)
assert.deepEqual(commands.readPendingCommand(storage, scope, 7), pending)
assert.equal(commands.savedCommandSymbol(storage, scope), 7)
for (const other of [session(3), session(2, 12), session(2, 11, 'access-b')]) {
  const otherScope = commands.commandScope(other)
  assert.notEqual(otherScope, scope)
  assert.equal(commands.readPendingCommand(storage, otherScope, 7), null)
}
const corrupted = new Storage()
corrupted.setItem(`${commands.COMMAND_STORAGE_PREFIX}${scope}:7`, '{broken')
assert.throws(() => commands.readPendingCommand(corrupted, scope, 7))
assert.equal(corrupted.getItem(`${commands.COMMAND_STORAGE_PREFIX}${scope}:7`), '{broken', 'corruption must not delete an unknown command')
storage.setItem('locale', 'zh'); clearAdminSession(storage)
assert.equal(commands.readPendingCommand(storage, scope, 7), null)
assert.equal(storage.getItem('locale'), 'zh')
assert.equal(commands.commandScope({ ...identity, user: { ...identity.user, id: undefined } }), null)
assert.equal(commands.commandScope({ ...identity, accessSession: { ...identity.accessSession, expiresAt: 0 } }), null)

const adminStorage = new Storage(), adminUser = {tenantId:2,id:11,userType:'admin'}
const admin = createAdminSession('first-private-token',adminUser), adminScope = commands.commandScope(admin)
adminStorage.setItem(ADMIN_SESSION_KEY,JSON.stringify(admin));commands.writePendingCommand(adminStorage,adminScope,pending)
assert.equal(commands.commandScope(readSession(adminStorage)),adminScope,'refresh keeps the persisted ordinary login scope')
for (const token of ['second-private-token','first-private-token']) {
  const relogin = createAdminSession(token,adminUser), nextScope = commands.commandScope(relogin)
  assert.notEqual(nextScope,adminScope,'a successful login never shares pending scope, even for the same actor/token')
  assert.equal(commands.readPendingCommand(adminStorage,nextScope,7),null)
  assert.equal(commands.readPendingCommand(adminStorage,adminScope,7).requestKey,pending.requestKey,'old login bytes are not deleted')
}
assert.equal(commands.commandScope({...admin,loginSessionId:'invalid'}),null)
assert(!adminScope.includes(admin.token) && !decodeURIComponent(adminScope).includes(admin.token),'Bearer must not be copied into a pending storage key')
const legacyStorage = new Storage(), legacyScope = encodeURIComponent(JSON.stringify([2,11,'admin','admin']))
legacyStorage.setItem(ADMIN_SESSION_KEY,JSON.stringify({token:'legacy-private-token',user:adminUser,mode:'admin'}))
commands.writePendingCommand(legacyStorage,legacyScope,pending)
const legacyBytes = JSON.stringify(legacyStorage), migrated = readSession(legacyStorage), migratedScope = commands.commandScope(migrated)
assert.equal(commands.commandScope(readSession(legacyStorage)),migratedScope,'legacy authentication session gains one stable opaque id')
assert.throws(()=>commands.readPendingCommand(legacyStorage,migratedScope,7),/旧登录会话.*不会另建/)
assert.throws(()=>commands.savedCommandSymbol(legacyStorage,migratedScope),/旧登录会话.*不会另建/)
assert.equal(legacyStorage.getItem(`${commands.COMMAND_STORAGE_PREFIX}${legacyScope}:7`),JSON.parse(legacyBytes)[`${commands.COMMAND_STORAGE_PREFIX}${legacyScope}:7`],'unknown pre-session command bytes preserved')
commands.writePendingCommand(legacyStorage,legacyScope,{...pending,receipt:receipt('CANCELLED')})
assert.equal(commands.readPendingCommand(legacyStorage,migratedScope,7),null,'terminal legacy command is not adopted')

// Execute the actual Vue setup with inert reactive/DOM hooks; no browser, server or dependency is required.
const source = fs.readFileSync(new URL('../src/views/AiControl.vue', import.meta.url), 'utf8')
const script = source.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const compiled = stripTypeScriptTypes(script)
let nextKey = 0
function page(storage, api) {
  const messages = [], auth = { ...readSession(storage) }, original = readSession(storage)
  auth.user = original.user; auth.accessSession = original.accessSession
  const bindings = {
    ref: value => ({ value }), computed: calculate => ({ get value() { return calculate() } }),
    onMounted: () => {}, onUnmounted: () => {}, watch: () => {},
    ElMessage: Object.fromEntries(['success', 'warning', 'error', 'info'].map(kind => [kind, text => messages.push({ kind, text })])), ElMessageBox: { confirm: async () => {} },
    request: api.request, rawRequest: api.rawRequest, createRequestKey: () => `command-request-${String(++nextKey).padStart(4, '0')}`, displaySymbol: value => value.symbol,
    useAuthStore: () => auth, readSession, sessionStorage: storage, ...commands,
  }
  const setup = new Function(...Object.keys(bindings), `${compiled}\nreturn { submit, fetchCommand, loadSymbols, selectedId, status, pendingCommand, commandError, receiptNotice, busy, target, mode, commandProtocol };`)
  return { ...setup(...Object.values(bindings)), messages, auth }
}
function fixture({ timeout = false, legacy = false, delayed = false } = {}) {
  const storage = new Storage(); storage.setItem(ADMIN_SESSION_KEY, JSON.stringify(identity))
  const events = [], status = { id: 7, enabled: false, running: false, available: true, offset: 0, remainingSeconds: 0, v3Enabled: false, v4Enabled: false }
  let state = 'ACCEPTED', activeKey, resolvePost
  const result = () => ({ ...receipt(state), requestKey: activeKey })
  const api = {
    request: {
      get: async (path, options) => {
        events.push({ method: 'GET', path, options })
        if (path.endsWith('/symbols')) return [{ id: 7, symbol: 'S2FIXTURE', pricePrecision: 2, isEnabled: true }]
        if (path.endsWith('/commands')) { assert.equal(options.params.requestKey, activeKey); return result() }
        if (path.endsWith('/history')) return []
        return status
      },
      post: async (path, payload) => { events.push({ method: 'POST', path, payload }); assert(path.endsWith('/stop')); state = 'CANCELLED'; return result() },
    },
    rawRequest: {
      post: async (path, payload) => {
        events.push({ method: 'POST', path, payload }); activeKey = payload.requestKey
        if (timeout) throw Object.assign(new Error('timeout'), { code: 'ECONNABORTED' })
        if (legacy) return { status: 200, data: { ...status, enabled: true, running: true } }
        if (delayed) return new Promise(resolve => { resolvePost = resolve })
        return { status: 202, data: result() }
      },
    },
  }
  return { storage, api, events, status, setState: value => { state = value }, resolve: () => resolvePost({ status: 202, data: result() }) }
}
const accepted = fixture(), view = page(accepted.storage, accepted.api)
await view.loadSymbols()
const before = view.status.value
await view.submit('start', pending.payload)
assert.equal(view.status.value, before, 'HTTP 202 must not apply a receipt as ControlStatus')
assert.equal(view.pendingCommand.value.receipt.state, 'ACCEPTED')
assert(view.busy.value)
assert(!view.messages.some(message => /已开始|已运行/.test(message.text)))
const originalKey = view.pendingCommand.value.requestKey
for (const state of ['PREPARING', 'READY']) { accepted.setState(state); await view.fetchCommand(true); assert.equal(view.status.value, before); assert(view.busy.value) }
accepted.setState('RUNNING')
const statusReads = accepted.events.filter(event => event.method === 'GET' && event.path === '/admin/ai-control/7').length
await view.fetchCommand(true)
assert.equal(view.pendingCommand.value.receipt.state, 'RUNNING')
assert.equal(accepted.events.filter(event => event.method === 'GET' && event.path === '/admin/ai-control/7').length, statusReads + 1, 'RUNNING reads the existing status separately')
assert.equal(accepted.events.filter(event => event.method === 'POST').length, 1)

const timedOut = fixture({ timeout: true }), timeoutView = page(timedOut.storage, timedOut.api)
await timeoutView.loadSymbols(); await timeoutView.submit('start', pending.payload)
const timeoutKey = timeoutView.pendingCommand.value.requestKey
assert.match(timeoutView.commandError.value, /仅查询.*不会重复启动/)
await timeoutView.submit('start', { ...pending.payload, targetPrice: 999 })
assert.equal(timeoutView.pendingCommand.value.requestKey, timeoutKey)
assert.equal(timedOut.events.filter(event => event.method === 'POST').length, 1, 'a second click after timeout only queries the immutable original key')
const refreshed = page(timedOut.storage, timedOut.api)
await refreshed.loadSymbols()
assert.equal(refreshed.pendingCommand.value.requestKey, timeoutKey)
assert.deepEqual(refreshed.pendingCommand.value.payload, pending.payload)
assert.equal(refreshed.target.value.targetPrice, pending.payload.targetPrice)
assert.equal(timedOut.events.filter(event => event.method === 'POST').length, 1, 'refresh never restarts')
for (const state of ['FAILED', 'CANCELLED']) { timedOut.setState(state); await refreshed.fetchCommand(true); assert(!refreshed.busy.value); assert.match(refreshed.receiptNotice.value, state === 'FAILED' ? /失败/ : /取消/) }

const cancelling = fixture(), cancelView = page(cancelling.storage, cancelling.api)
await cancelView.loadSymbols(); await cancelView.submit('start', pending.payload); await cancelView.submit('stop')
assert.equal(cancelView.pendingCommand.value.receipt.state, 'CANCELLED')
assert.equal(cancelling.events.find(event => event.path.endsWith('/stop')).payload.requestKey, cancelView.pendingCommand.value.requestKey)

const old = fixture({ legacy: true }), legacyView = page(old.storage, old.api)
await legacyView.loadSymbols(); await legacyView.submit('start', pending.payload)
assert.match(legacyView.commandProtocol.value, /旧协议.*HTTP 200/)
assert(legacyView.status.value.running); assert.equal(legacyView.pendingCommand.value, null)
assert.equal(commands.readPendingCommand(old.storage, scope, 7), null)

const late = fixture({ delayed: true }), lateView = page(late.storage, late.api)
await lateView.loadSymbols()
const inflight = lateView.submit('start', pending.payload)
const changed = session(3); late.storage.setItem(ADMIN_SESSION_KEY, JSON.stringify(changed)); lateView.auth.token = changed.token; lateView.auth.user = changed.user; lateView.auth.accessSession = changed.accessSession
late.resolve(); await inflight
assert.equal(commands.readPendingCommand(late.storage, scope, 7).receipt, undefined, 'old-identity response cannot publish a receipt')
assert.equal(lateView.messages.length, 0)
assert.equal(commands.readPendingCommand(late.storage, commands.commandScope(changed), 7), null)

for (const sameToken of [false, true]) {
  const ordinary = fixture({delayed:true}), originalAdmin = createAdminSession('original-admin-private',adminUser)
  ordinary.storage.setItem(ADMIN_SESSION_KEY,JSON.stringify(originalAdmin))
  const ordinaryView = page(ordinary.storage,ordinary.api);await ordinaryView.loadSymbols()
  const originalAdminScope = commands.commandScope(originalAdmin), running = ordinaryView.submit('start',pending.payload)
  const replacement = createAdminSession(sameToken ? originalAdmin.token : 'replacement-admin-private',adminUser)
  ordinary.storage.setItem(ADMIN_SESSION_KEY,JSON.stringify(replacement));ordinaryView.auth.token=replacement.token;ordinaryView.auth.loginSessionId=replacement.loginSessionId
  ordinary.resolve();await running
  assert.equal(commands.readPendingCommand(ordinary.storage,originalAdminScope,7).receipt,undefined,'same-actor relogin discards an actual Vue setup late POST receipt')
  assert.equal(commands.readPendingCommand(ordinary.storage,commands.commandScope(replacement),7),null)
  assert.deepEqual(ordinaryView.messages,[])
}

const blocked = fixture(), blockedView = page(blocked.storage, blocked.api)
await blockedView.loadSymbols()
blocked.storage.setItem = function(key, value) { if (key.startsWith(commands.COMMAND_STORAGE_PREFIX)) throw new Error('quota'); this[key] = String(value) }
await blockedView.submit('start', pending.payload)
assert.equal(blocked.events.filter(event => event.method === 'POST').length, 0, 'persist-before-send fails closed')
const malformed = fixture(), malformedView = page(malformed.storage, malformed.api)
await malformedView.loadSymbols()
const regularPost = malformed.api.rawRequest.post
malformed.api.rawRequest.post = async (...args) => { const value = await regularPost(...args); value.data.state = 'UNKNOWN'; return value }
await malformedView.submit('start', pending.payload)
assert.equal(malformedView.pendingCommand.value.receipt, undefined)
assert.equal(malformedView.status.value.running, false)
const malformedKey = malformedView.pendingCommand.value.requestKey
await malformedView.fetchCommand(true)
assert.equal(malformedView.pendingCommand.value.requestKey, malformedKey)
assert.equal(malformedView.pendingCommand.value.receipt.state, 'ACCEPTED')
assert.equal(malformed.events.filter(event => event.method === 'POST').length, 1)

const unavailable = fixture({ timeout: true }), unavailableView = page(unavailable.storage, unavailable.api)
await unavailableView.loadSymbols(); await unavailableView.submit('start', pending.payload)
const unavailableKey = unavailableView.pendingCommand.value.requestKey
const ordinaryGet = unavailable.api.request.get
unavailable.api.request.get = async (...args) => { if (args[0].endsWith('/commands')) throw new Error('not yet visible'); return ordinaryGet(...args) }
await unavailableView.fetchCommand(true)
assert.equal(unavailableView.pendingCommand.value.requestKey, unavailableKey)
assert(unavailableView.busy.value)
assert.equal(unavailable.events.filter(event => event.method === 'POST').length, 1)

const stale = fixture(), staleView = page(stale.storage, stale.api)
await staleView.loadSymbols(); await staleView.submit('start', pending.payload)
let resolveQuery
const ordinaryQuery = stale.api.request.get
stale.api.request.get = async (...args) => { if (args[0].endsWith('/commands')) return new Promise(resolve => { resolveQuery = resolve }); return ordinaryQuery(...args) }
const oldQuery = staleView.fetchCommand(true)
staleView.selectedId.value = 8
resolveQuery({ ...receipt('RUNNING'), requestKey: staleView.pendingCommand.value.requestKey })
await oldQuery
assert.equal(staleView.pendingCommand.value.receipt.state, 'ACCEPTED', 'a late query for another selected symbol is discarded')

assert.match(source, /v-permission="'ai_control:stop'"/)
assert.match(source, /v-permission="'ai_control:view'"[^\n]*核对原启动请求/)
assert.match(source, /commandIdentityMatches/)
console.log('PASS S2 command receipts: all six states, scope/storage recovery, 202 without false start, RUNNING status read, timeout/refresh query-only, cancellation, explicit legacy 200, stale identity discard, persist-before-send')
