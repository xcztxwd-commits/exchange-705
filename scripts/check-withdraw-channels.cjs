const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), Module = require('node:module')
const root = path.resolve(__dirname, '..')
const request = Module.createRequire(path.join(root, 'exchange-frontend/package.json'))
const ts = request('typescript'), vue = request('vue')
const file = path.join(root, 'exchange-frontend/src/utils/withdrawChannels.ts')
const modules = new Map()
function load(file) {
  if (modules.has(file)) return modules.get(file).exports
  const module = new Module(file)
  modules.set(file, module)
  module.require = name => name === 'vue'
    ? { ...vue, onMounted() {}, onUnmounted() {} }
    : name.startsWith('.') ? load(path.resolve(path.dirname(file), name + '.ts')) : request(name)
  module._compile(ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, file)
  return module.exports
}
const { useWithdrawChannels } = load(file)

for (const digital of [false, true]) for (const bank of [false, true]) {
  test(`withdraw channels digital=${digital}, bank=${bank}`, async () => {
    const state = useWithdrawChannels(async () => ({ success: true, digital, bank }))
    assert.equal(state.hasWithdrawChannel.value, false, 'hide forms before loading')
    await state.loadWithdrawChannels()
    assert.equal(state.withdrawChannelsReady.value, true)
    assert.equal(state.hasWithdrawChannel.value, digital || bank)
    assert.equal(state.showWithdrawTypeTabs.value, digital && bank)
    assert.equal(state.withdrawType.value, digital ? 'digital' : 'bank')
    state.withdrawType.value = digital ? 'bank' : 'digital'
    assert.equal(state.withdrawType.value, digital && bank ? 'bank' : digital ? 'digital' : 'bank')
  })
}
test('open pages preserve selection and switch to the remaining enabled channel', async () => {
  let response = { digital: true, bank: true }
  const state = useWithdrawChannels(async () => response)
  await state.loadWithdrawChannels()
  state.withdrawType.value = 'bank'
  await state.loadWithdrawChannels()
  assert.equal(state.withdrawType.value, 'bank')
  response = { digital: true, bank: false }; await state.loadWithdrawChannels()
  assert.equal(state.withdrawType.value, 'digital')
  response = { digital: false, bank: false }; await state.loadWithdrawChannels()
  assert.equal(state.hasWithdrawChannel.value, false)
  assert.equal(state.showWithdrawTypeTabs.value, false)
})
test('failed and malformed responses hide forms; retry restores valid channels', async () => {
  let response = { digital: true, bank: false }
  const state = useWithdrawChannels(async () => response)
  await state.loadWithdrawChannels()
  for (const invalid of [null, {}, { digital: 'true', bank: true }, { success: false, digital: true, bank: true }]) {
    response = invalid; await state.loadWithdrawChannels()
    assert.equal(state.hasWithdrawChannel.value, false)
    assert.equal(state.showWithdrawTypeTabs.value, false)
    assert.equal(state.withdrawChannelsReady.value, false)
    assert.equal(state.withdrawChannelsError.value, true)
  }
  response = { digital: false, bank: true }; await state.loadWithdrawChannels()
  assert.equal(state.hasWithdrawChannel.value, true)
  assert.equal(state.withdrawChannelsError.value, false)
  const failed = useWithdrawChannels(async () => { throw new Error('fixture unavailable') })
  await failed.loadWithdrawChannels()
  assert.equal(failed.hasWithdrawChannel.value, false)
  assert.equal(failed.withdrawChannelsError.value, true)
})
test('overlapping refresh calls share one request', async () => {
  let finish, count = 0
  const state = useWithdrawChannels(() => { count++; return new Promise(resolve => { finish = resolve }) })
  const first = state.loadWithdrawChannels(), second = state.loadWithdrawChannels()
  assert.equal(first, second)
  await Promise.resolve()
  assert.equal(count, 1)
  finish({ digital: true, bank: true }); await first
  assert.equal(state.showWithdrawTypeTabs.value, true)
})
test('PC and mobile reuse the same channel rules', () => {
  assert.equal(fs.readFileSync(file, 'utf8'), fs.readFileSync(path.join(root, 'exchange-pc/src/utils/withdrawChannels.ts'), 'utf8'))
})
