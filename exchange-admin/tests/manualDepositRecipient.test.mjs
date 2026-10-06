import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript'), vue = require('vue')
const source = fs.readFileSync(new URL('../src/components/ManualDepositDialog.vue', import.meta.url), 'utf8')
const script = source.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const js = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
const props = vue.reactive({ modelValue: false, userId: 7 }), calls = [], timers = new Map(), saved = new Map()
let timerId = 0, unmount
const scope = vue.effectScope()
const app = scope.run(() => new Function('computed', 'reactive', 'ref', 'watch', 'onUnmounted', 'defineProps', 'defineEmits', 'useAuthStore', 'useFiatCurrency', 'previewUsd', 'request', 'sessionStorage', 'setTimeout', 'clearTimeout', js + ';return {customerQuery, recipient, form, querying, lookupError, searchCustomers, customers, searchingCustomers, pending};')(
  vue.computed, vue.reactive, vue.ref, vue.watch, callback => { unmount = callback }, () => props, () => () => {},
  () => ({ user: { tenantId: 11, userType: 'admin', id: 1 } }), () => ({ currency: vue.ref('USD'), rate: vue.ref(1) }), () => '1',
  { get: (url, options) => new Promise((resolve, reject) => calls.push({ url, query: options.params.query, resolve, reject })) },
  { getItem: key => saved.get(key) ?? null },
  callback => { timers.set(++timerId, callback); return timerId }, id => timers.delete(id)
))
const fire = () => { const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach(callback => callback()) }
const settle = async () => { await Promise.resolve(); await vue.nextTick() }
const last = () => calls.at(-1)
const search = input => { app.searchCustomers(input); fire(); return last() }
const customer = (userId, email) => ({ userId, email })
const details = (userId, name) => ({ userId, name, remark: 'fixture', balances: { FUND: '25' } })
props.modelValue = true
await vue.nextTick()
assert.equal(last().query, '7', 'user-management entry resolves its preselected UID')
last().resolve(details(7, 'first@example.com')); await settle()
assert.equal(app.form.userId, '7')
assert.deepEqual(app.customers.value, [customer(7, 'first@example.com')])

app.searchCustomers('70'); app.searchCustomers(' 700 ')
assert.equal(app.recipient.value, null, 'typing immediately invalidates the previous recipient')
assert.equal(app.form.userId, '')
assert.equal(timers.size, 1, 'debounce cancels previous search')
fire()
assert.equal(last().query, '700')
assert.equal(last().url, '/admin/deposit/orders/recipient/customers')
last().resolve([customer(700, 'alice@example.com'), customer(7001, 'other@example.com')]); await settle()
assert.equal(app.customers.value.length, 2, 'UID prefixes return selectable candidates')
assert.equal(app.recipient.value, null, 'suggestions must not implicitly select a recharge recipient')
app.customerQuery.value = '700'
assert.equal(last().url, '/admin/deposit/orders/recipient')
assert.equal(last().query, '700')
assert.equal(app.querying.value, true)
last().resolve(details(700, 'alice@example.com')); await settle()
assert.equal(app.form.userId, '700')
assert.equal(app.recipient.value.name, 'alice@example.com')

const staleSearch = search('alice@')
const emailSearch = search(' second@ ')
assert.equal(emailSearch.query, 'second@', 'partial email searches do not require a complete email')
emailSearch.resolve([customer(8, 'second@example.com')]); await settle()
staleSearch.resolve([customer(99, 'old@example.com')]); await settle()
assert.deepEqual(app.customers.value, [customer(8, 'second@example.com')], 'late suggestions cannot overwrite the current query')
app.customerQuery.value = '8'
const staleRecipient = last()
app.customerQuery.value = '9'
last().resolve(details(9, 'new@example.com')); await settle()
staleRecipient.resolve(details(8, 'second@example.com')); await settle()
assert.equal(app.form.userId, '9', 'late recipient details cannot overwrite the selected UID')

const beforeSelection = search('second')
app.customerQuery.value = '8'
last().resolve(details(8, 'second@example.com')); await settle()
beforeSelection.resolve([customer(88, 'stale@example.com')]); await settle()
assert.equal(app.customers.value[0].userId, 8, 'selection cancels in-flight suggestion updates')
assert.equal(app.form.userId, '8', 'selecting an email match submits its resolved numeric UID')

search('unknown').resolve([]); await settle()
assert.deepEqual(app.customers.value, [])
assert.equal(app.searchingCustomers.value, false)
search('broken').reject(new Error('客户搜索失败')); await settle()
assert.equal(app.lookupError.value, '客户搜索失败')
app.searchCustomers('')
assert.equal(app.lookupError.value, '')
assert.equal(app.searchingCustomers.value, false)
app.customerQuery.value = '10'
last().reject(new Error('客户不存在')); await settle()
assert.equal(app.lookupError.value, '客户不存在')
assert.equal(app.form.userId, '')
app.customerQuery.value = undefined // Element Plus emits undefined when a selection is cleared.
assert.equal(app.lookupError.value, '')
assert.equal(app.recipient.value, null)
assert.equal(app.querying.value, false)

const beforeClear = search('clear')
app.searchCustomers('')
beforeClear.resolve([customer(77, 'stale@example.com')]); await settle()
assert.deepEqual(app.customers.value, [], 'clearing cancels in-flight search responses')
const beforeClose = search('close')
props.modelValue = false
beforeClose.resolve([customer(12, 'stale@example.com')]); await settle()
assert.deepEqual(app.customers.value, [], 'closed dialog ignores late suggestions')
props.modelValue = true; await vue.nextTick()
const closedRecipient = last()
props.modelValue = false
closedRecipient.resolve(details(7, 'first@example.com')); await settle()
assert.equal(app.recipient.value, null, 'closed dialog ignores late recipient details')

saved.set('deposit-pending:11:admin:1', JSON.stringify({ userId: '8', account: 'FUND', amount: '25', currency: 'USD', remark: 'retry', idempotencyKey: 'immutable' }))
props.modelValue = true; await vue.nextTick()
last().resolve(details(8, 'second@example.com')); await settle()
const pending = JSON.stringify(app.pending.value), requestCount = calls.length
app.searchCustomers('other')
assert.equal(timers.size, 0, 'pending recharge cannot search for a different customer')
assert.equal(calls.length, requestCount)
assert.equal(app.form.userId, '8')
assert.equal(JSON.stringify(app.pending.value), pending, 'immutable pending payload survives reopen')
props.modelValue = false; await vue.nextTick()
saved.clear()
props.modelValue = true; await vue.nextTick()
app.searchCustomers('unmount')
unmount(); scope.stop()
assert.equal(timers.size, 0, 'unmount clears debounce timers')
assert.ok(source.includes('filterable remote clearable'))
assert.ok(source.includes('customer.userId} · ${customer.email'))
assert.ok(source.includes('no-data-text="未找到匹配客户"'))
console.log('PASS: UID/email suggestions, linked selection, debounce, stale responses, clear, failure, preselection, close, immutable retry, cleanup')
