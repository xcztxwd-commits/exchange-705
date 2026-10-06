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
  const setup = new Function(...Object.keys(bindings), `${compiled}\nreturn { submit, fetchCommand, loadSymbols, selectedId, status, pendingCommand, commandError, receiptNotice, busy, target, mode, commandProtocol, rescueDisabled, rescueBusy, saving, statusError, progress, progressLabel };`)
  return { ...setup(...Object.values(bindings)), messages, auth }
}
function fixture({ timeout = false, legacy = false, delayed = false } = {}) {
  const storage = new Storage(); storage.setItem(ADMIN_SESSION_KEY, JSON.stringify(identity))
  const events = [], status = { id: 7, enabled: false, running: false, available: true, offset: 0, remainingSeconds: 0, v3Enabled: false, v4Enabled: false }
  let state = 'ACCEPTED', activeKey, activeAction, resolvePost
  const result = () => ({ ...receipt(state), requestKey: activeKey, action: state === 'CANCELLED' ? 'CANCEL' : activeAction.toUpperCase() })
  const api = {
    request: {
      get: async (path, options) => {
        events.push({ method: 'GET', path, options })
        if (path.endsWith('/symbols')) return [{ id: 7, symbol: 'S2FIXTURE', pricePrecision: 2, isEnabled: true }]
        if (path.endsWith('/commands')) { assert.equal(options.params.requestKey, activeKey); return result() }
        if (path.endsWith('/history')) return []
        return status
      },
      post: async (path, payload) => {
        events.push({ method: 'POST', path, payload }); assert(path.endsWith('/stop') || path.endsWith('/manual')); assert.equal(payload.requestKey, activeKey); state = 'CANCELLED'
        return path.endsWith('/manual') ? { ...status, enabled: false, offset: 0 } : result()
      },
    },
    rawRequest: {
      post: async (path, payload) => {
        events.push({ method: 'POST', path, payload }); activeKey = payload.requestKey; activeAction = path.split('/').at(-1)
        if (timeout) throw Object.assign(new Error('timeout'), { code: 'ECONNABORTED' })
        if (legacy) return { status: 200, data: { ...status, enabled: true, running: true } }
        if (delayed) return new Promise(resolve => { resolvePost = resolve })
        return { status: 202, data: result() }
      },
    },
  }
  return { storage, api, events, status, setState: value => { state = value }, resolve: (lateState = state) => resolvePost({ status: 202, data: { ...result(), state: lateState, action: activeAction.toUpperCase() } }) }
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
await cancelView.loadSymbols(); await cancelView.submit('start', pending.payload)
const cancelKey = cancelView.pendingCommand.value.requestKey
await cancelView.submit('stop')
assert.equal(cancelView.pendingCommand.value, null, 'persisted cancellation plus successful status read clears pending')
assert.equal(commands.readPendingCommand(cancelling.storage, scope, 7), null)
assert.match(cancelView.receiptNotice.value, /取消/)
assert.equal(cancelling.events.find(event => event.path.endsWith('/stop')).payload.requestKey, cancelKey)

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

// Activated receipts remain an audit result, not a pending command or current task state.
for (const action of ['stop', 'manual']) {
  const activated = fixture(), activatedView = page(activated.storage, activated.api)
  await activatedView.loadSymbols(); await activatedView.submit('start', pending.payload)
  activated.setState('RUNNING'); await activatedView.fetchCommand(true)
  activated.api.request.post = async (path, payload) => {
    activated.events.push({ method: 'POST', path, payload })
    assert.equal(payload?.requestKey, undefined, 'resolved RUNNING receipt is not a pending cancellation key')
    return { ...activated.status, enabled: false, running: false, controlState: 'SOURCE' }
  }
  await activatedView.submit(action, action === 'manual' ? { enabled: false, offset: 0 } : undefined)
  assert.equal(activatedView.status.value.running, false)
  assert(activatedView.messages.some(message => message.kind === 'success' && (action === 'manual' ? /已恢复原始行情/ : /任务已停止/).test(message.text)))
}

// RESTORE has the same persistent 202 protocol, including response-loss reconciliation.
for (const timeout of [false, true]) {
  const restoreFixture = fixture({ timeout }), restoreView = page(restoreFixture.storage, restoreFixture.api)
  await restoreView.loadSymbols(); await restoreView.submit('restore', { durationSeconds: 10, intensity: 2, randomOscillation: false })
  const restoreKey = restoreView.pendingCommand.value.requestKey
  assert.equal(restoreView.pendingCommand.value.action, 'restore'); assert.match(restoreView.receiptNotice.value, /恢复/)
  await restoreView.submit('restore', { durationSeconds: 20, intensity: 3, randomOscillation: true })
  assert.equal(restoreView.pendingCommand.value.requestKey, restoreKey)
  assert.equal(restoreFixture.events.filter(event => event.method === 'POST').length, 1)
  restoreFixture.setState('RUNNING'); await restoreView.fetchCommand(true)
  assert.match(restoreView.receiptNotice.value, /恢复命令已运行/)
}
assert.throws(() => commands.readCommandReceipt({ ...receipt('ACCEPTED'), action: 'RESTORE' }, pending), /回执不匹配/)
assert.equal(commands.readCommandReceipt({ ...receipt('CANCELLED'), action: 'CANCEL' }, pending).state, 'CANCELLED')

// A missing command is uncertainty, not permission to forget or start a new key.
const unknown = fixture({ timeout: true }), unknownView = page(unknown.storage, unknown.api)
await unknownView.loadSymbols(); await unknownView.submit('start', pending.payload)
const unknownKey = unknownView.pendingCommand.value.requestKey, unknownGet = unknown.api.request.get
unknown.api.request.get = async (...args) => { if (args[0].endsWith('/commands')) throw Object.assign(new Error('启动命令不存在'), { response: { status: 404 } }); return unknownGet(...args) }
await unknownView.fetchCommand(true)
assert.equal(unknownView.pendingCommand.value.requestKey, unknownKey); assert(unknownView.busy.value)
assert.equal(unknownView.rescueDisabled.value, false, '404/pending does not disable cancellation or SOURCE')
unknown.api.request.get = unknownGet
await unknownView.submit('stop')
assert.equal(unknownView.pendingCommand.value, null, 'unknown-key tombstone receipt plus status resolves uncertainty')
assert.equal(commands.readPendingCommand(unknown.storage, scope, 7), null)

const cancelFailure = fixture({ timeout: true }), cancelFailureView = page(cancelFailure.storage, cancelFailure.api)
await cancelFailureView.loadSymbols(); await cancelFailureView.submit('start', pending.payload)
const retainedKey = cancelFailureView.pendingCommand.value.requestKey
cancelFailure.api.request.post = async () => { throw Object.assign(new Error('timeout'), { code: 'ECONNABORTED' }) }
await cancelFailureView.submit('stop')
assert.equal(commands.readPendingCommand(cancelFailure.storage, scope, 7).requestKey, retainedKey, 'lost cancellation response retains unknown original key')
assert(cancelFailureView.busy.value); assert.equal(cancelFailureView.rescueDisabled.value, false)

const readFailure = fixture(), readFailureView = page(readFailure.storage, readFailure.api)
await readFailureView.loadSymbols(); await readFailureView.submit('start', pending.payload)
const readFailureGet = readFailure.api.request.get
readFailure.api.request.get = async (...args) => { if (args[0] === '/admin/ai-control/7') throw new Error('status timeout'); return readFailureGet(...args) }
await readFailureView.submit('stop')
assert.equal(readFailureView.pendingCommand.value.receipt.state, 'CANCELLED', 'receipt alone does not clear before status verification')
assert.equal(commands.readPendingCommand(readFailure.storage, scope, 7).receipt.state, 'CANCELLED')
assert.equal(readFailureView.rescueDisabled.value, false, 'failed status read still allows SOURCE')
readFailure.api.request.get = readFailureGet
await readFailureView.fetchCommand()
assert.equal(readFailureView.pendingCommand.value, null, 'poll retries status confirmation for a cancelled request')

// Rescue can supersede an in-flight POST; late receipts and old GETs cannot revive pending.
for (const timedAction of ['start', 'restore']) for (const action of ['stop', 'manual']) {
  const inFlight = fixture({ delayed: true }), inFlightView = page(inFlight.storage, inFlight.api)
  await inFlightView.loadSymbols()
  const sending = inFlightView.submit(timedAction, timedAction === 'restore' ? { durationSeconds: 10, intensity: 2, randomOscillation: false } : pending.payload), key = inFlightView.pendingCommand.value.requestKey
  assert(inFlightView.saving.value); assert.equal(inFlightView.rescueDisabled.value, false)
  await inFlightView.submit(action, action === 'manual' ? { enabled: false, offset: 0 } : undefined)
  assert.equal(inFlightView.pendingCommand.value, null)
  assert.equal(inFlight.events.find(event => event.path.endsWith(`/${action}`)).payload.requestKey, key)
  inFlight.resolve('RUNNING'); await sending
  assert.equal(inFlightView.pendingCommand.value, null, 'late old POST ignored after cancellation or SOURCE')
  assert.equal(commands.readPendingCommand(inFlight.storage, scope, 7), null)
  assert.equal(inFlight.events.filter(event => event.method === 'POST' && event.path.endsWith(`/${timedAction}`)).length, 1)
  assert(!inFlightView.messages.some(message => /命令已运行/.test(message.text)), 'late receipt never replaces confirmed cancellation/SOURCE notice')
}
const manualUnconfirmed = fixture({ timeout: true }), manualUnconfirmedView = page(manualUnconfirmed.storage, manualUnconfirmed.api)
await manualUnconfirmedView.loadSymbols(); await manualUnconfirmedView.submit('restore', { durationSeconds: 10, intensity: 1, randomOscillation: false })
const manualKey = manualUnconfirmedView.pendingCommand.value.requestKey, manualGet = manualUnconfirmed.api.request.get
manualUnconfirmed.api.request.get = async (...args) => { if (args[0].endsWith('/commands')) throw new Error('receipt unavailable'); return manualGet(...args) }
await manualUnconfirmedView.submit('manual', { enabled: false, offset: 0 })
assert.equal(commands.readPendingCommand(manualUnconfirmed.storage, scope, 7).requestKey, manualKey, 'SOURCE status does not replace durable original-key cancellation confirmation')
manualUnconfirmed.api.request.get = manualGet; await manualUnconfirmedView.fetchCommand(true)
assert.equal(manualUnconfirmedView.pendingCommand.value, null)
// Mid-recovery rescue failures do not erase either unresolved requests or activated audit receipts.
for (const activated of [false, true]) for (const action of ['stop', 'manual']) for (const failure of ['network', 'timeout', 'server']) {
  const interrupted = fixture({ timeout: !activated }), interruptedView = page(interrupted.storage, interrupted.api)
  const restorePayload = { durationSeconds: 10, intensity: 2, randomOscillation: false }
  await interruptedView.loadSymbols(); await interruptedView.submit('restore', restorePayload)
  if (activated) { interrupted.setState('RUNNING'); await interruptedView.fetchCommand(true) }
  interruptedView.status.value = { ...interrupted.status, running: true, restoring: true, controlState: 'RECOVERING', progressStartedAt: 20000, progressEndAt: 30000, controlProgressWatermark: 23000 }
  const bytes = JSON.stringify(commands.readPendingCommand(interrupted.storage, scope, 7)), originalKey = interruptedView.pendingCommand.value.requestKey
  interrupted.api.request.post = async (path, payload) => {
    interrupted.events.push({ method: 'POST', path, payload })
    assert.equal(payload?.requestKey, activated ? undefined : originalKey)
    throw Object.assign(new Error(`fixture rescue ${failure}`), failure === 'timeout' ? { code: 'ECONNABORTED' } : failure === 'server' ? { response: { status: 503 } } : { code: 'ERR_NETWORK' })
  }
  await interruptedView.submit(action, action === 'manual' ? { enabled: false, offset: 0 } : undefined)
  assert.equal(JSON.stringify(commands.readPendingCommand(interrupted.storage, scope, 7)), bytes, 'failure does not pretend to cancel a durable original key')
  assert.equal(interruptedView.status.value.controlState, 'RECOVERING'); assert.equal(interruptedView.progress.value, 30)
  assert.equal(interruptedView.rescueDisabled.value, false); assert.equal(interruptedView.rescueBusy.value, false); assert.equal(interruptedView.saving.value, false)
  assert(!interruptedView.messages.some(message => message.kind === 'success'))
  if (!activated) {
    assert(interruptedView.busy.value)
    await interruptedView.submit('restore', { ...restorePayload, durationSeconds: 20 })
    assert.equal(interruptedView.pendingCommand.value.requestKey, originalKey)
    assert.equal(interrupted.events.filter(event => event.method === 'POST' && event.path.endsWith('/restore')).length, 1, 'retry reconciles original key, never replays a timed POST')
  }
}
// A lost RESTORE acceptance is restored byte-for-byte after reload and queried without replay.
const restoreLost = fixture({ timeout: true }), restoreLostView = page(restoreLost.storage, restoreLost.api)
const restoreLostPayload = { durationSeconds: 13, intensity: 4, randomOscillation: true }
await restoreLostView.loadSymbols(); await restoreLostView.submit('restore', restoreLostPayload)
const restoreLostKey = restoreLostView.pendingCommand.value.requestKey, restoreLostGet = restoreLost.api.request.get
restoreLost.api.request.get = async (...args) => { if (args[0].endsWith('/commands')) throw Object.assign(new Error('late restore acceptance unavailable'), { response: { status: 404 } }); return restoreLostGet(...args) }
const restoreReloaded = page(restoreLost.storage, restoreLost.api); await restoreReloaded.loadSymbols()
assert.equal(restoreReloaded.pendingCommand.value.requestKey, restoreLostKey); assert.deepEqual(restoreReloaded.pendingCommand.value.payload, restoreLostPayload)
assert.equal(restoreReloaded.mode.value, 'restore'); assert(restoreReloaded.busy.value); assert.equal(restoreReloaded.rescueDisabled.value, false)
restoreLost.api.request.get = restoreLostGet; restoreLost.setState('RUNNING'); await restoreReloaded.fetchCommand(true)
assert.equal(restoreReloaded.pendingCommand.value.receipt.state, 'RUNNING')
assert.equal(restoreLost.events.filter(event => event.method === 'POST').length, 1)
const committedView = page(accepted.storage, accepted.api)
committedView.status.value = { durationSeconds: 10, remainingSeconds: 0, startedAt: 1000, plannedEnd: 11000, sampledUntil: 10000 }
assert.equal(committedView.progress.value, 90, 'elapsed wall clock never displays an uncommitted endpoint as finished')
committedView.status.value = { durationSeconds: 10, remainingSeconds: 0, startedAt: 1000, plannedEnd: 11000, sampledUntil: 11000, progressStartedAt: 20000, progressEndAt: 30000, controlProgressWatermark: 22000 }
assert.equal(committedView.progress.value, 20, 'recovery uses its committed watermark and current recovery segment')
committedView.status.value = { running: true, restoring: true, progressStatus: 'WAITING_SOURCE', available: false, sampledUntil: 1000, controlProgressWatermark: 42000, progressStartedAt: 40000, progressEndAt: 50000, committedAt: 43000 }
assert.equal(committedView.progress.value, 20); assert.equal(committedView.progressLabel.value, '等待有效原始行情')
committedView.status.value = { ...committedView.status.value, committedAt: 59000, remainingSeconds: 0 }
assert.equal(committedView.progress.value, 20, 'snapshot refresh and zero wall-clock remaining do not advance committed recovery')
committedView.status.value = { ...committedView.status.value, available: true, progressStatus: 'HEALTHY', progressStartedAt: 60000, progressEndAt: 68000, controlProgressWatermark: 62000 }
assert.equal(committedView.progress.value, 25, 'resume uses the current segment, not an expired pre-pause end')
committedView.status.value = { ...committedView.status.value, controlProgressWatermark: 64000 }
assert.equal(committedView.progress.value, 50)
committedView.status.value = { durationSeconds: 10, remainingSeconds: 5 }
assert.equal(committedView.progress.value, 50, 'legacy synchronous response remains explicit fallback')
for (const [progressStatus, label] of Object.entries({ WAITING_SOURCE: '等待有效原始行情', WAITING_VALID_SOURCE: '等待有效原始行情', ENGINE_LAG: '控盘推进延迟', AUTHORITY_CHANGED: '授权已变化', HEALTHY: '正常' })) {
  committedView.status.value = { progressStatus }; assert.equal(committedView.progressLabel.value, label)
}
assert.match(source, /timeText\(status\.controlProgressWatermark \?\? status\.sampledUntil\)/)
assert.doesNotMatch(source, /控盘未达到预期水位/)
assert.match(source, /:disabled="rescueDisabled"[^\n]*submit\('manual'/)
assert.match(source, /sampledUntil.*expectedSampledUntil/s)
assert.match(source, /最后可信价格仅供展示，不可交易/)

assert.match(source, /v-permission="'ai_control:stop'"/)
assert.match(source, /v-permission="'ai_control:view'"[^\n]*核对原控盘请求/)
assert.match(source, /commandIdentityMatches/)
console.log('PASS durable START/RESTORE receipts: states, immutable scope/storage, 202, response loss query-only, durable unknown cancellation, SOURCE escape, read failure retention, stale response fencing, explicit legacy 200')
