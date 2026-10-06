const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const Module = require('node:module')
const root = path.join(__dirname, '..')
const req = Module.createRequire(path.join(root, 'exchange-frontend/package.json'))
const ts = req('typescript')
const { ref } = req('vue')
const file = path.join(root, 'exchange-frontend/src/utils/depositChannels.ts')
const loaded = new Module(file)
loaded.filename = file
loaded.paths = Module._nodeModulePaths(path.dirname(file))
loaded._compile(ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, file)
const { useDepositChannels } = loaded.exports
for (const [digital, bank, expected, tabs] of [[false, true, 'bank', false], [true, false, 'digital', false], [true, true, 'digital', true], [false, false, 'bank', false]]) {
  test(`enabled channels digital=${digital}, bank=${bank}`, () => {
    const state = useDepositChannels(() => digital, () => bank)
    assert.equal(state.depositType.value, expected)
    assert.equal(state.showDepositTypeTabs.value, tabs)
    state.depositType.value = expected === 'bank' ? 'digital' : 'bank'
    assert.equal(state.depositType.value, tabs ? 'bank' : expected)
  })
}
test('channel changes keep enabled selection and fall back to bank/customer service', () => {
  const digital = ref(true), bank = ref(true)
  const state = useDepositChannels(() => digital.value, () => bank.value)
  state.depositType.value = 'bank'
  assert.equal(state.depositType.value, 'bank')
  bank.value = false
  assert.equal(state.depositType.value, 'digital')
  assert.equal(state.showDepositTypeTabs.value, false)
  digital.value = false
  assert.equal(state.depositType.value, 'bank')
  bank.value = true
  assert.equal(state.depositType.value, 'bank')
})
test('all user deposit views hide the switch unless both channels are enabled', () => {
  for (const file of ['exchange-frontend/src/views/Deposit.vue', 'exchange-frontend/src/advanced/views/Deposit.vue', 'exchange-pc/src/views/Deposit.vue', 'exchange-pc/src/views/DesktopTrade.vue']) {
    const source = fs.readFileSync(path.join(root, file), 'utf8')
    assert.ok(source.includes('useDepositChannels('), file)
    assert.ok(source.includes('useDepositChannelRefresh('), file)
    assert.ok(source.includes('v-if="showDepositTypeTabs" data-testid="deposit-type-tabs"'), file)
  }
})

test('PC and mobile channel helper stay identical while using their own Vue package', () => {
  assert.equal(fs.readFileSync(path.join(root, 'exchange-pc/src/utils/depositChannels.ts'), 'utf8'), fs.readFileSync(file, 'utf8'))
})

test('open deposit pages refresh on return and every 15 seconds, not while hidden, busy or already reading', async () => {
  const mounted = [], unmounted = []
  const module = new Module(file)
  module.filename = file
  module.require = name => name === 'vue' ? { ...req('vue'), onMounted: f => mounted.push(f), onUnmounted: f => unmounted.push(f) } : req(name)
  module._compile(ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, file)
  const names = ['window', 'document', 'setInterval', 'clearInterval']
  const saved = names.map(name => Object.getOwnPropertyDescriptor(globalThis, name))
  const window = new EventTarget(), document = new EventTarget()
  document.visibilityState = 'visible'
  let tick, stopped = false, active = true, count = 0, resolve
  Object.assign(globalThis, { window, document,
    setInterval: (f, delay) => { assert.equal(delay, 15000); tick = f; return 71 },
    clearInterval: id => { assert.equal(id, 71); stopped = true },
  })
  try {
    module.exports.useDepositChannelRefresh(() => { count++; return new Promise(done => { resolve = done }) }, () => active)
    mounted.forEach(f => f())
    window.dispatchEvent(new Event('focus'))
    assert.equal(count, 1)
    document.dispatchEvent(new Event('visibilitychange')); await tick()
    assert.equal(count, 1, 'coalesce overlapping reads')
    resolve(); await new Promise(setImmediate)
    const polling = tick(); assert.equal(count, 2); resolve(); await polling
    document.visibilityState = 'hidden'; await tick(); window.dispatchEvent(new Event('focus'))
    assert.equal(count, 2)
    document.visibilityState = 'visible'; active = false; await tick()
    assert.equal(count, 2)
    active = true; window.dispatchEvent(new Event('pageshow'))
    assert.equal(count, 3); resolve(); await new Promise(setImmediate)
    unmounted.forEach(f => f()); assert.equal(stopped, true)
    window.dispatchEvent(new Event('focus')); window.dispatchEvent(new Event('pageshow')); document.dispatchEvent(new Event('visibilitychange'))
    assert.equal(count, 3, 'no reads after leaving the page')
  } finally {
    names.forEach((name, i) => saved[i] ? Object.defineProperty(globalThis, name, saved[i]) : delete globalThis[name])
  }
})
