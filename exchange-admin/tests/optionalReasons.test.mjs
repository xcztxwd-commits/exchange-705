import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import ts from 'typescript'
import { parse } from '@vue/compiler-sfc'

function handler(file, name, bindings) {
  const source = parse(fs.readFileSync(new URL(`../src/${file}`, import.meta.url), 'utf8')).descriptor.scriptSetup.content
  const ast = ts.createSourceFile(file + '.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const declaration = ast.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === name)
  assert.ok(declaration, `${file}: actual ${name} handler must exist`)
  const script = ts.transpileModule(declaration.getText(ast), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
  return new Function(...Object.keys(bindings), `${script};return ${name}`)(...Object.values(bindings))
}
const ref = value => ({ value })
const ElMessage = { success() {}, error() {}, warning() {} }

test('customer detail balance save accepts optional reasons and retains money validation and retry identity', async () => {
  for (const reason of ['', '   ', '改', '  补款  ']) {
    const calls = [], pending = ref(null), balances = ref({ FUND: '5', CONTRACT: '2', OPTION: '0' })
    const readOnly = ref(false), canSave = ref(true), baseline = ref({ user: { rowVersion: 7 } })
    const save = handler('components/CustomerDetailEditor.vue', 'save', {
      saving: ref(false), uploading: ref(''), readOnly, pending, canSave, password: ref(''), section: ref('assets'),
      balanceChanged: ref(true), balances, reason: ref(reason), baseline, profilePatch: ref({}), identityChanged: ref(false),
      initialBalances: () => ({ FUND: '1', CONTRACT: '2', OPTION: '0' }), crypto, generation: 0,
      props: { row: { id: 11 } }, request: { post: async (url, payload) => { calls.push({ url, payload }); throw new Error('response lost') } },
      live: ref(null), apply() {}, error: ref(''), lastRefreshed: ref(''), emit() {}, refresh: async () => {}, ElMessage,
    })
    await save(); assert.equal(calls.length, 1)
    assert.equal(calls[0].url, '/admin/users/11/profile'); assert.equal(calls[0].payload.reason, reason.trim())
    assert.equal(calls[0].payload.balances.FUND, '5'); assert.equal(calls[0].payload.expectedBalances.FUND, '1')
    await save(); assert.equal(calls.length, 2); assert.equal(calls[1].payload, calls[0].payload, 'retry keeps exact original request')
    pending.value = null; balances.value.FUND = '-1'; await save(); assert.equal(calls.length, 2, 'negative balance is rejected')
    balances.value.FUND = '5'; readOnly.value = true; await save(); assert.equal(calls.length, 2, 'read-only customer cannot save')
    readOnly.value = false; canSave.value = false; await save(); assert.equal(calls.length, 2, 'existing save eligibility remains enforced')
  }
})

test('KYC rejection and deposit rejection submit an empty reason', async () => {
  const calls = []
  const reject = handler('views/KycReview.vue', 'confirmReject', {
    currentRejectId: ref(9), rejectForm: ref({ remark: '' }), rejectDialogVisible: ref(true), loadList() {}, ElMessage,
    request: { post: async (url, body) => { calls.push({ url, body }); return { success: true } } },
  })
  await reject(); assert.deepEqual(calls[0], { url: '/admin/kyc/9/reject', body: { remark: '' } })
  let dismissed
  const review = handler('views/DepositOrders.vue', 'review', {
    ElMessageBox: { prompt: async () => { if (dismissed) throw dismissed; return { value: '' } } },
    request: { post: async (url, body) => calls.push({ url, body }) }, load: async () => {}, ElMessage,
  })
  await review({ id: 10 }, false); assert.deepEqual(calls[1], { url: '/admin/deposit/orders/10/reject', body: { remark: '' } })
  for (const action of ['cancel', 'close']) { dismissed = action; await review({ id: 10 }, false) }
  assert.equal(calls.length, 2, 'dismissal never rejects the deposit')
})

test('tenant save accepts an empty reason but still requires a name and confirmation', async () => {
  const calls = [], form = { id: 2, code: 't2', name: 'Tenant', frontendHost: '', status: 'DRAFT', configReady: false, reason: '' }
  let dismissed
  const save = handler('control/TenantManager.vue', 'save', {
    form, busy: ref(false), editOpen: ref(true), ElMessage, load: async () => {},
    ElMessageBox: { confirm: async () => { if (dismissed) throw dismissed } }, api: async (...args) => calls.push(args),
  })
  await save(); assert.equal(calls.length, 1); assert.equal(calls[0][2].reason, '')
  for (const action of ['cancel', 'close']) { dismissed = action; await save() }
  assert.equal(calls.length, 1)
  dismissed = undefined; form.name = ''; await save(); assert.equal(calls.length, 1, 'tenant name remains required')
})

test('operation review accepts an empty reason and keeps evidence and confirmation', async () => {
  const calls = [], prompts = []; let dismissed
  const review = handler('control/Operations.vue', 'review', {
    generation: 0, disposed: false, tenant: ref('all'), ElMessage, load: async () => {},
    ElMessageBox: {
      prompt: async (...args) => { prompts.push(args); return { value: prompts.length % 2 ? '' : 'a'.repeat(64) } },
      confirm: async () => { if (dismissed) throw dismissed },
    }, api: async (...args) => calls.push(args),
  })
  const row = { tenant_id: 2, job: 'archive', latest_failure_id: 3 }
  await review(row); assert.equal(calls.length, 1); assert.equal(calls[0][2].reason, '')
  assert.equal(prompts[0][2].inputValidator(''), true); assert.equal(typeof prompts[0][2].inputValidator('a'.repeat(501)), 'string')
  assert.equal(prompts[1][2].inputPattern.test(''), false); assert.equal(calls[0][2].evidenceSha256, 'a'.repeat(64))
  dismissed = 'cancel'; await review(row); assert.equal(calls.length, 1)
})

test('controlled loan repayment accepts blank reasons and keeps retry identity, eligibility and cancellation', async () => {
  const calls = [], inputs = new Map(); let fail = true, dismissed, options
  const repay = handler('views/LoanReview.vue', 'handleControlledRepay', {
    repayBusy: ref(false), repayInputs: inputs, crypto, ElMessage, detailDialogVisible: ref(true), loadLoans: async () => {},
    ElMessageBox: { prompt: async (_message, _title, input) => { options = input; if (dismissed) throw dismissed; return { value: '' } } },
    request: { post: async (url, body) => { calls.push({ url, body }); if (fail) throw new Error('response lost'); return { success: true } } },
  })
  const row = { id: 4, status: 'APPROVED', accountMode: 'REAL' }
  await repay(row); assert.equal(calls.length, 1); assert.equal(calls[0].body.reason, '')
  assert.equal(options.inputValidator(''), true); assert.equal(typeof options.inputValidator('a'.repeat(501)), 'string')
  fail = false; await repay(row); assert.equal(calls[1].body, calls[0].body); assert.equal(inputs.size, 0)
  for (const action of ['cancel', 'close']) { dismissed = action; await repay(row) }
  dismissed = undefined; await repay({ ...row, accountMode: 'DEMO' }); await repay({ ...row, status: 'PENDING' })
  assert.equal(calls.length, 2, 'cancelled or ineligible repayment never submits')
})
