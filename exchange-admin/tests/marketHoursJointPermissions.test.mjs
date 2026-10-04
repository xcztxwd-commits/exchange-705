import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import { stripTypeScriptTypes } from 'node:module'

// Component handler tests use mocks; these are not full application authentication acceptance.
const { descriptor } = parse(fs.readFileSync(new URL('../src/views/MarketHours.vue', import.meta.url), 'utf8'))
const script = stripTypeScriptTypes(descriptor.scriptSetup.content.replace(/^import .*$/gm, ''))
function setup(grants, confirm = async () => {}) {
  const calls = [], data = { settings: { version: 1, revision: 3, strategies: [
    { id: 'fx-weekend', name: 'Default', timezone: 'UTC', note: '', weekly: [], exceptions: [] },
    { id: 'custom-test', name: 'Custom', timezone: 'UTC', note: '', weekly: [], exceptions: [] },
  ], categories: {}, symbols: {} }, categories: [], symbols: [], serverTime: Date.now() }
  const bindings = { ref: value => ({ value }), computed: getter => ({ get value() { return getter() } }),
    onMounted: () => {}, can: code => grants.has(code), request: Object.fromEntries(['get', 'put', 'post'].map(method =>
      [method, async (url, body) => { calls.push({ method, url, body }); return structuredClone(data) }])),
    ElMessage: { warning() {}, error() {}, success() {} }, ElMessageBox: { confirm } }
  const state = new Function(...Object.keys(bindings), `${script}\nreturn {apply,load,save,openManual,commitManual,addPolicy,removePolicy,setInstant,setStrategy,addWeekly,removeWeekly,addException,removeException,settings,manual,policyId,policy,saving,dirty}`)(...Object.values(bindings))
  state.apply(structuredClone(data)); state.policyId.value = 'custom-test'
  return { state, calls }
}
const mutations = [
  ['strategy', s => s.setStrategy('categories', 'forex', 'custom-test')],
  ['add policy', s => s.addPolicy()], ['remove policy', s => s.removePolicy()],
  ['add weekly', s => s.addWeekly()], ['remove weekly', s => { s.policy.value.weekly.push({ reason: 'fixture' }); s.removeWeekly(0) }],
  ['add exception', s => s.addException()], ['remove exception', s => { s.policy.value.exceptions.push({ reason: 'fixture' }); s.removeException(0) }],
]
test('actual policy mutation handlers enforce current save grant and in-flight protection', () => {
  for (const [name, invoke] of mutations) {
    // Removal fixtures are seeded before the comparison, not counted as a handler mutation.
    const seed = s => { if (name === 'remove weekly') s.policy.value.weekly.push({ reason: 'fixture' }); if (name === 'remove exception') s.policy.value.exceptions.push({ reason: 'fixture' }) }
    const call = s => name === 'remove weekly' ? s.removeWeekly(0) : name === 'remove exception' ? s.removeException(0) : invoke(s)
    for (const busy of [false, true]) {
      const grants = new Set(busy ? ['market_hours:save'] : ['market_hours:view']), { state, calls } = setup(grants)
      seed(state); state.saving.value = busy
      const before = JSON.stringify(state.settings.value); call(state)
      assert.equal(JSON.stringify(state.settings.value), before, `${name}: denied or busy changed settings`)
      assert.deepEqual(calls, [])
    }
    const allowed = setup(new Set(['market_hours:save'])); seed(allowed.state)
    const before = JSON.stringify(allowed.state.settings.value); call(allowed.state)
    assert.notEqual(JSON.stringify(allowed.state.settings.value), before, `${name}: granted handler did nothing`)
  }
  const grants = new Set(['market_hours:save']), { state } = setup(grants), range = { start: '', end: '' }
  grants.clear(); state.setInstant(range, 'start', { target: { value: '2026-10-04T10:00' } }); assert.equal(range.start, '')
  grants.add('market_hours:save'); state.setInstant(range, 'start', { target: { value: 'invalid' } }); assert.equal(range.start, '')
  state.setInstant(range, 'start', { target: { value: '2026-10-04T10:00' } }); assert.equal(range.start, new Date('2026-10-04T10:00').toISOString())
})
test('load/save/manual handlers reach requests only with their current capability', async () => {
  const denied = setup(new Set()); await denied.state.load(); await denied.state.save()
  denied.state.openManual('SYMBOL', '1', 'Fixture', 'CLOSED'); assert.equal(denied.state.manual.value, undefined)
  denied.state.manual.value = { scope: 'SYMBOL', target: '1', label: 'Fixture', mode: 'CLOSED', until: null, reason: 'verified' }
  await denied.state.commitManual(); assert.deepEqual(denied.calls, [])
  const grants = new Set(['market_hours:view', 'market_hours:save', 'market_hours:manual']), allowed = setup(grants)
  await allowed.state.load(); await allowed.state.save(); allowed.state.openManual('SYMBOL', '1', 'Fixture', 'CLOSED')
  assert.ok(allowed.state.manual.value); allowed.state.manual.value.reason = 'verified'
  grants.delete('market_hours:manual'); await allowed.state.commitManual(); assert.equal(allowed.calls.length, 2)
  grants.add('market_hours:manual'); await allowed.state.commitManual()
  assert.deepEqual(allowed.calls.map(({method,url}) => ({method,url})), [
    {method:'get',url:'/admin/market-hours'}, {method:'put',url:'/admin/market-hours'}, {method:'post',url:'/admin/market-hours/override'} ])
})
test('revocation while reload confirmation is open prevents GET and preserves edits', async () => {
  const grants = new Set(['market_hours:view', 'market_hours:save']), { state, calls } = setup(grants, async () => grants.clear())
  state.addPolicy(); const before = JSON.stringify(state.settings.value)
  await state.load(); assert.deepEqual(calls, []); assert.equal(JSON.stringify(state.settings.value), before)
})
test('client list limits and invalid removal indices cannot be bypassed through handlers', () => {
  const { state } = setup(new Set(['market_hours:save']))
  state.policy.value.weekly = Array.from({length:64}, () => ({reason:'fixture'})); state.addWeekly(); assert.equal(state.policy.value.weekly.length,64)
  state.policy.value.exceptions = Array.from({length:128}, () => ({reason:'fixture'})); state.addException(); assert.equal(state.policy.value.exceptions.length,128)
  state.removeWeekly(-1); state.removeWeekly(64); state.removeException(-1); state.removeException(128)
  assert.equal(state.policy.value.weekly.length,64); assert.equal(state.policy.value.exceptions.length,128)
  while (state.settings.value.strategies.length < 30) state.settings.value.strategies.push({id:`fixture-${state.settings.value.strategies.length}`})
  state.addPolicy(); assert.equal(state.settings.value.strategies.length,30)
})
