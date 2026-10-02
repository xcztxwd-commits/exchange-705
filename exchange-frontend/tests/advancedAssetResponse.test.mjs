import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { readAssetResponse } from '../src/advanced/utils/assetResponse.ts'

const fields = ['fundBalance', 'contractBalance', 'optionBalance', 'fundFrozen', 'contractFrozen', 'optionFrozen']
const valid = () => ({ success: true, serverNow: '2026-10-03T01:00:00', fundBalance: 0, contractBalance: '12.5', optionBalance: '-2', fundFrozen: '0', contractFrozen: 1, optionFrozen: '2e0' })
const invalid = [undefined, null, '', ' ', true, false, [], {}, 'bad', '0x10', NaN, Infinity, -Infinity, 'Infinity', '1e309']

test('asset response keeps real zero/numeric strings and validates the complete selected snapshot', () => {
  assert.deepEqual(readAssetResponse(valid(), fields, 'invalid'), { fundBalance: 0, contractBalance: 12.5, optionBalance: -2, fundFrozen: 0, contractFrozen: 1, optionFrozen: 2 })
  for (const raw of [null, undefined, [], {}, false, 'ok', { ...valid(), success: false }]) assert.throws(() => readAssetResponse(raw, fields, 'invalid'), /invalid/)
  for (const field of fields) {
    const missing = valid(); delete missing[field]
    assert.throws(() => readAssetResponse(missing, fields, 'invalid'), /invalid/)
    for (const value of invalid) assert.throws(() => readAssetResponse({ ...valid(), [field]: value }, fields, 'invalid'), /invalid/)
  }
  assert.throws(() => readAssetResponse({ ...valid(), fundBalance: 1e308, contractBalance: 1e308 }, fields, 'invalid'), /invalid/)
})

test('legacy nested values are accepted only when the top-level field is absent', () => {
  const raw = { fund: { available: '0', frozen: 0 }, contract: { available: 3, frozen: '1' }, option: { available: 2, frozen: 0 } }
  assert.equal(readAssetResponse(raw, fields, 'invalid').fundBalance, 0)
  assert.equal(readAssetResponse({ ...raw, fundBalance: 0, fund: { available: 999, frozen: 0 } }, fields, 'invalid').fundBalance, 0)
  for (const value of invalid) assert.throws(() => readAssetResponse({ ...raw, fundBalance: value }, fields, 'invalid'), /invalid/)
})

function fixture(page) {
  const source = readFileSync(new URL(`../src/advanced/views/${page}.vue`, import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  let response, failure
  const calls = [], accepted = [], error = { value: '' }, auth = { token: 'fixture', user: { id: 1 }, load() {} }
  const context = {
    console: { error() {} }, performance, readAssetResponse,
    ref: value => ({ value }), onMounted() {}, useRouter: () => ({}), useAuthStore: () => auth,
    useLocaleStore: () => ({ loadLocale() {}, text: (_zh, en) => en, t: key => key }),
    useTrialWallet: () => ({ accept: value => accepted.push(value) }),
    useFiatCurrency: () => ({ currency: { value: 'USD' }, rate: { value: 1 }, formatAsset: value => `USD ${value.toFixed(2)}` }),
    useBusinessLifecycle: () => ({ error, writing: { value: false }, request: { async get(url) { calls.push(url); if (failure) throw failure; return response } } }),
    depositIcon: '', withdrawIcon: '', transferIcon: '', walletIcon: '',
  }
  const visible = source.match(/<strong class="large">\{\{ (.*?) \}\}/)[1]
    .replace(/\b(loading|advancedError|balanceVisible|totalAssets)\b/g, '$1.value')
  const expose = `\nglobalThis.api = { loadAssets, loading, error: advancedError, totalAssets, fundBalance, contractBalance, optionBalance, displayed: () => ${visible} }`
  runInNewContext(ts.transpileModule(script + expose, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText, context)
  assert.ok([...source.matchAll(/\{\{ ([^{}]*format(?:Money|Asset)\([^{}]*?) \}\}/g)].every(match => match[1].includes("loading || advancedError ? '—'")), 'All rendered cash amounts must hide during loading/error')
  return { ...context.api, calls, accepted, async load(value, rejected) { response = value; failure = rejected; await context.api.loadAssets() } }
}

for (const page of ['Assets', 'Wallet']) {
  test(`${page}: missing/invalid HTTP-success fields show unknown, keep snapshot atomic, and retry recovers`, async () => {
    const f = fixture(page), selected = page === 'Assets' ? fields : fields.slice(0, 3)
    assert.equal(f.displayed(), '—', 'Initial render must not claim zero funds')
    await f.load(valid())
    assert.equal(f.error.value, '')
    assert.equal(f.totalAssets.value, page === 'Assets' ? 13.5 : 10.5)
    assert.equal(f.accepted.length, 1)
    const previous = f.totalAssets.value
    await f.load({ ...valid(), fundBalance: 99, optionBalance: 'bad' })
    assert.equal(f.fundBalance.value, 0, 'A later invalid field must not apply earlier valid fields')
    assert.equal(f.accepted.length, 1)
    for (const field of selected) for (const value of invalid) {
      await f.load({ ...valid(), [field]: value })
      assert.match(f.error.value, /missing or invalid/)
      assert.equal(f.displayed(), '—')
      assert.equal(f.totalAssets.value, previous, 'No partial replacement of displayed snapshot')
      assert.equal(f.accepted.length, 1, 'Invalid response must not be accepted by TrialWallet')
    }
    for (const value of [null, {}, { ...valid(), success: false }]) {
      await f.load(value); assert.equal(f.displayed(), '—'); assert.ok(f.error.value)
    }
    await f.load(null, Error('offline')); assert.equal(f.displayed(), '—'); assert.equal(f.error.value, 'offline')
    await f.load(Object.fromEntries(fields.map(field => [field, '0'])))
    assert.equal(f.error.value, '')
    assert.equal(f.totalAssets.value, 0)
    assert.equal(f.displayed(), page === 'Assets' ? 'USD 0.00' : '0.00')
    assert.equal(f.accepted.length, 2)
    assert.ok(f.calls.length > 0 && f.calls.every(url => url === '/user/assets'))
  })
}
