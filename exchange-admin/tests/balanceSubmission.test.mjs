import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript')
const source = fs.readFileSync(new URL('../src/views/Users.vue', import.meta.url), 'utf8')
const script = source.slice(source.indexOf('const submitBalance ='), source.indexOf('const getStatusType ='))
const js = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

function fixture(post, confirm = async () => {}) {
  const calls = [], errors = [], confirmations = [], saved = new Map()
  const saving = { value: false }, pending = { value: null }, visible = { value: true }
  const form = { value: { userId: 7, fundBalance: 4, contractBalance: 288888858.8, optionBalance: 0 } }
  const submit = new Function('balanceSaving', 'balanceMode', 'manualDepositVisible', 'canSetBalance',
    'balancePending', 'balanceRemark', 'balanceForm', 'auth', 'ElMessageBox', 'ElMessage',
    'sessionStorage', 'balanceKey', 'request', 'balanceDialogVisible', 'loadUsers', js + ';return submitBalance;')(
    saving, { value: 'balance' }, { value: false }, { value: true }, pending, { value: '' }, form,
    { user: { tenantId: 1 } }, { confirm: async message => { confirmations.push(message); await confirm() } },
    { success: () => {}, error: message => errors.push(message) },
    { setItem: (key, value) => saved.set(key, value), removeItem: key => saved.delete(key) },
    { value: 'balance-test' }, { post: async (url, payload) => { calls.push({ url, payload }); return post(payload) } },
    visible, () => {})
  return { submit, calls, errors, confirmations, saved, saving, pending, visible, form }
}

for (const status of [400, 403]) {
  const rejected = Object.assign(new Error('余额范围无效'), { response: { status, data: { success: false, message: '余额范围无效' } } })
  let reject = true
  const f = fixture(async () => { if (reject) throw rejected })
  await f.submit()
  assert.equal(f.pending.value, null, `HTTP ${status} rejection must unlock the form`)
  assert.equal(f.saved.size, 0, 'definitively rejected request must not remain in session storage')
  assert.equal(f.visible.value, true)
  assert.equal(f.saving.value, false)
  assert.deepEqual(f.errors, ['余额范围无效'])
  reject = false
  f.form.value.fundBalance = 64515
  await f.submit()
  assert.equal(f.calls.length, 2)
  assert.notEqual(f.calls[0].payload.idempotencyKey, f.calls[1].payload.idempotencyKey)
  assert.equal(f.calls[1].payload.fundBalance, 64515)
  assert.equal(f.confirmations.length, 2)
  assert.equal(f.visible.value, false)
}

for (const error of [new Error('Network Error'), Object.assign(new Error('timeout'), { code: 'ECONNABORTED' }),
  Object.assign(new Error('server error'), { response: { status: 500, data: { success: false } } }),
  Object.assign(new Error('bad gateway request'), { response: { status: 400, data: '<html>proxy error</html>' } })]) {
  let loseResponse = true
  const f = fixture(async () => { if (loseResponse) throw error })
  await f.submit()
  const original = f.pending.value
  assert.ok(original, 'uncertain result must remain locked for safe replay')
  assert.equal(f.saved.size, 1)
  assert.equal(f.visible.value, true)
  loseResponse = false
  f.form.value.fundBalance = 999
  await f.submit()
  assert.equal(f.calls[1].payload, original, 'retry must reuse the original immutable request')
  assert.equal(f.calls[1].payload.fundBalance, 4)
  assert.equal(f.confirmations.length, 1)
  assert.equal(f.pending.value, null)
  assert.equal(f.saved.size, 0)
}

for (const action of ['cancel', 'close']) {
  const f = fixture(async () => {}, async () => { throw action })
  await f.submit()
  assert.equal(f.calls.length, 0)
  assert.equal(f.pending.value, null)
  assert.equal(f.saved.size, 0)
  assert.equal(f.saving.value, false)
  assert.deepEqual(f.errors, [], 'canceling confirmation must not show an update failure')
}
for (const status of [400, 403]) {
  let attempt = 0
  const f = fixture(async () => {
    if (++attempt === 1) throw new Error('Network Error')
    throw Object.assign(new Error('retry rejected'), { response: { status, data: { success: false } } })
  })
  await f.submit()
  const original = f.pending.value
  await f.submit()
  assert.equal(f.pending.value, original, 'a rejected retry cannot prove the original request was not committed')
  assert.equal(f.saved.size, 1)
  assert.equal(f.calls[1].payload, original)
}
console.log('balance submission: rejection recovery, uncertain-result replay and cancellation passed')

const recoveryScript = source.slice(source.indexOf('const resolveBalancePending ='), source.indexOf('const submitBalance ='))
const recoveryJs = ts.transpileModule(recoveryScript, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
for (const status of ['COMPLETED', 'CANCELLED', 'NETWORK_ERROR']) {
  const original = { userId: 7, fundBalance: 4, contractBalance: 288888858.8, optionBalance: 0, remark: '', confirm: true, idempotencyKey: 'cached-request-123456' }
  const saved = new Map([['pending-key', JSON.stringify(original)]])
  const pending = { value: null }, saving = { value: false }, form = { value: {} }, calls = [], errors = []
  const open = new Function('balancePending', 'balanceSaving', 'balanceKey', 'request', 'sessionStorage', 'balanceForm',
    'ElMessage', 'loadUsers', 'accountTable', 'balanceMode', 'depositPermissions', 'balanceCurrency',
    'rechargeAccount', 'rechargeAmount', 'refreshRates', 'balanceRemark', 'balanceDialogVisible', recoveryJs + ';return openBalanceDialog;')(
    pending, saving, { value: 'pending-key' }, { post: async (url, input) => {
      calls.push({ url, input })
      if (status === 'NETWORK_ERROR') throw new Error('network unavailable')
      return { status, userId: 7, email: 'current@example.invalid', fundBalance: 777, contractBalance: 12, optionBalance: 0 }
    } }, { getItem: key => saved.get(key), removeItem: key => saved.delete(key) }, form,
    { info() {}, error: message => errors.push(message) }, () => {}, { selectRow() {} }, { value: 'balance' },
    { value: { manual_deposit: false } }, { value: 'USD' }, { value: 'FUND' }, { value: 0 }, () => {}, { value: '' }, { value: false })
  await open({ id: 7, email: 'row@example.invalid', fundBalance: 0, contractBalance: 0, optionBalance: 0 })
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, '/admin/users/balance-adjustments/reconcile', 'opening the dialog must never apply an old balance mutation')
  assert.deepEqual(calls[0].input, original)
  assert.equal(saving.value, false)
  if (status === 'NETWORK_ERROR') {
    assert.deepEqual(pending.value, original)
    assert.equal(saved.size, 1)
    assert.equal(errors.length, 1)
  } else {
    assert.equal(pending.value, null)
    assert.equal(saved.size, 0)
    assert.equal(form.value.fundBalance, 777, 'show current balances rather than the obsolete requested balance')
    assert.equal(form.value.email, 'current@example.invalid')
    assert.deepEqual(errors, [])
  }
}
console.log('cached balance recovery: completed, cancelled and unavailable cases passed')
