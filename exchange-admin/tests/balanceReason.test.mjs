import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'

const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript')
const source = fs.readFileSync(new URL('../src/views/Users.vue', import.meta.url), 'utf8')
const script = source.slice(source.indexOf('const submitBalance ='), source.indexOf('const getStatusType ='))
const js = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText

for (const reason of ['', '   ', '改', '  补款  ']) {
  const calls = [], errors = [], confirmations = [], saved = new Map()
  const saving = { value: false }, pending = { value: null }, visible = { value: true }
  const submit = new Function('balanceSaving', 'balanceMode', 'manualDepositVisible', 'canSetBalance',
    'balancePending', 'balanceRemark', 'balanceForm', 'auth', 'ElMessageBox', 'ElMessage',
    'sessionStorage', 'balanceKey', 'request', 'balanceDialogVisible', 'loadUsers', js + ';return submitBalance;')(
    saving, { value: 'balance' }, { value: false }, { value: true }, pending, { value: reason },
    { value: { userId: 7, fundBalance: 0, contractBalance: 25, optionBalance: 0 } },
    { user: { tenantId: 1 } }, { confirm: async message => { confirmations.push(message) } },
    { success: () => {}, error: message => errors.push(message) },
    { setItem: (key, value) => saved.set(key, value), removeItem: key => saved.delete(key) },
    { value: 'balance-test' }, { post: async (url, payload) => { calls.push({ url, payload }) } },
    visible, () => {})
  await submit()
  assert.deepEqual(errors, [], 'optional reason must not block submission')
  assert.equal(confirmations.length, 1, 'balance overwrite still requires confirmation')
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, '/admin/users/updateBalance')
  assert.deepEqual({ ...calls[0].payload, idempotencyKey: undefined }, {
    userId: 7, fundBalance: 0, contractBalance: 25, optionBalance: 0,
    remark: reason.trim(), confirm: true, idempotencyKey: undefined,
  })
  assert.match(calls[0].payload.idempotencyKey, /^[A-Za-z0-9_-]{16,64}$/)
  assert.equal(saving.value, false)
  assert.equal(pending.value, null)
  assert.equal(visible.value, false)
  assert.equal(saved.size, 0)
}
console.log('balance reason: 4 optional reason submission cases passed')
