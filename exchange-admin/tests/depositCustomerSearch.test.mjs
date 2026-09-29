import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript'), vue = require('vue')
const source = fs.readFileSync(new URL('../src/views/DepositOrders.vue', import.meta.url), 'utf8')
const script = source.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const js = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
const calls = [], timers = new Map()
let id = 0, unmount
const app = new Function('ref', 'reactive', 'onMounted', 'onUnmounted', 'useAccountTable', 'accountTableRequest', 'ElMessage', 'setTimeout', 'clearTimeout', js + ';return {searchCustomers, customers, searchingCustomers, filters, load, rows, summary, resetFilters};')(
  vue.ref, vue.reactive, () => {}, fn => { unmount = fn },
  () => ({ modes: vue.ref(['REAL']), selectRow() {} }), () => ({ get: (url, options) => new Promise((resolve, reject) => calls.push({ url, params: options.params, resolve, reject })) }),
  { error: message => { throw new Error(message) } },
  fn => { timers.set(++id, fn); return id }, key => timers.delete(key)
)
const fire = () => { const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach(fn => fn()) }
app.searchCustomers('70'); app.searchCustomers('alice@')
assert.equal(timers.size, 1)
fire()
assert.equal(calls[0].params.query, 'alice@')
app.searchCustomers('700'); fire()
calls[1].resolve([{ userId: 700, email: 'alice@example.com' }]); await Promise.resolve()
calls[0].resolve([{ userId: 99, email: 'old@example.com' }]); await Promise.resolve()
assert.equal(app.customers.value[0].userId, 700)
app.filters.userId = '700'
const first = app.load(true)
assert.equal(calls[2].params.userId, '700')
app.filters.userId = '701'
const second = app.load(true)
calls[4].resolve({ list: [{ userId: 701 }], total: 1 }); calls[5].resolve({ count: 1 })
await second
calls[2].resolve({ list: [{ userId: 700 }], total: 2 }); calls[3].resolve({ count: 2 })
await first
assert.equal(app.rows.value[0].userId, 701)
assert.equal(app.summary.value.count, 1)
app.resetFilters()
assert.equal(app.filters.userId, '')
assert.deepEqual(app.customers.value, [])
assert.equal(calls[6].params.userId, '')
calls[6].resolve({ list: [], total: 0 }); calls[7].resolve({ count: 0 })
app.searchCustomers('pending'); unmount()
assert.equal(timers.size, 0)
assert.ok(source.includes('@change="load(true)"'))
console.log('PASS: UID/email suggestions, debounce, stale responses, selected UID filtering, linked summary, reset, cleanup')
