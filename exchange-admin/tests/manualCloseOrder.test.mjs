// Run: node tests/manualCloseOrder.test.mjs (Node.js 22.13+)
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { randomUUID } from 'node:crypto'

const source = readFileSync(new URL('../src/views/Orders.vue', import.meta.url), 'utf8')
const script = source.slice(source.indexOf('const exitRetries ='), source.indexOf('// Soft deletion'))
const calls = [], confirmations = [], prompts = [], errors = [], selected = []
let dismissed, response = { success: true }, requestError, reloads = 0
const app = new Function('accountTable', 'ElMessageBox', 'crypto', 'request', 'ElMessage', 'loadContractOrders',
  stripTypeScriptTypes(script) + ';return {handleCloseOrder, handleCancelOrder};'
)(
  { selectRow: row => selected.push(row) },
  {
    confirm: async (...args) => { confirmations.push(args); if (dismissed) throw dismissed },
    prompt: async (...args) => { prompts.push(args); return { value: '管理员受控撤单' } },
  },
  { randomUUID },
  { post: async (...args) => { calls.push(args); if (requestError) throw requestError; return response } },
  { success() {}, error: message => errors.push(message) },
  async () => { reloads++ },
)
const row = { id: 7, status: 'OPEN', accountMode: 'REAL' }

await app.handleCloseOrder(row)
assert.equal(prompts.length, 0, 'Closing must not require text input')
assert.equal(confirmations[0][1], '确认平仓')
assert.equal(confirmations[0][2].confirmButtonText, '平仓')
assert.equal(confirmations[0][2].cancelButtonText, '取消')
assert.equal(selected[0], row)
assert.equal(calls[0][0], '/admin/orders/contract/7/close')
assert.equal(calls[0][1].reason, '管理员手动平仓')
assert.match(calls[0][1].requestId, /^[a-zA-Z0-9_-]{16,64}$/)
assert.equal(reloads, 1)

for (const action of ['cancel', 'close']) {
  dismissed = action
  await app.handleCloseOrder(row)
}
assert.equal(calls.length, 1, 'Dismissing confirmation must not submit')
assert.equal(errors.length, 0)
dismissed = undefined

requestError = new Error('网络错误')
await app.handleCloseOrder(row)
const retryBody = calls.at(-1)[1]
assert.equal(errors.at(-1), '网络错误')
assert.equal(reloads, 1)
requestError = undefined
response = { success: false, message: '行情不可用' }
await app.handleCloseOrder(row)
assert.equal(calls.at(-1)[1], retryBody)
assert.equal(errors.at(-1), '行情不可用')
assert.equal(reloads, 1)
response = { success: true }
await app.handleCloseOrder(row)
assert.equal(calls.at(-1)[1], retryBody, 'Retries must reuse the idempotency key')
assert.equal(reloads, 2)
await app.handleCloseOrder(row)
assert.notEqual(calls.at(-1)[1].requestId, retryBody.requestId)
assert.equal(prompts.length, 0)

await app.handleCancelOrder({ ...row, status: 'PENDING' })
assert.equal(prompts.length, 1)
assert.equal(typeof prompts[0][2].inputValidator(''), 'string')
assert.equal(prompts[0][2].inputValidator('管理员受控撤单'), true)
assert.equal(calls.at(-1)[0], '/admin/orders/contract/7/cancel')
assert.equal(calls.at(-1)[1].reason, '管理员受控撤单')
console.log('PASS: close without text input, dismissal, error handling, idempotent retry, reload, cancel validation')
