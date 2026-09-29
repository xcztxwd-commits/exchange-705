import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript'), vue = require('vue')
const source = fs.readFileSync(new URL('../src/components/ManualDepositDialog.vue', import.meta.url), 'utf8')
const script = source.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const js = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
const props = vue.reactive({ modelValue: false, userId: 7 }), calls = [], timers = new Map(), saved = new Map()
let timerId = 0
const scope = vue.effectScope()
const app = scope.run(() => new Function('computed', 'reactive', 'ref', 'watch', 'defineProps', 'defineEmits', 'useAuthStore', 'useFiatCurrency', 'previewUsd', 'request', 'sessionStorage', 'setTimeout', 'clearTimeout', js + ';return {customerQuery, recipient, form, querying, lookupError};')(
  vue.computed, vue.reactive, vue.ref, vue.watch, () => props, () => () => {},
  () => ({ user: { id: 1 } }), () => ({ currency: vue.ref('USD'), rate: vue.ref(1) }), () => '1',
  { get: (url, options) => new Promise((resolve, reject) => calls.push({ url, query: options.params.query, resolve, reject })) },
  { getItem: key => saved.get(key) ?? null },
  callback => { timers.set(++timerId, callback); return timerId }, id => timers.delete(id)
))
const fire = () => { const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach(callback => callback()) }
const settle = async () => { await Promise.resolve(); await vue.nextTick() }
props.modelValue = true
await vue.nextTick()
fire()
assert.equal(calls[0].query, '7')
calls[0].resolve({ userId: 7, name: 'first@example.com' })
await settle()
assert.equal(app.form.userId, '7')
app.customerQuery.value = ' second@example.com '
assert.equal(app.recipient.value, null)
assert.equal(app.form.userId, '')
fire()
app.customerQuery.value = '9'
fire()
calls[2].resolve({ userId: 9 })
await settle()
calls[1].resolve({ userId: 8 })
await settle()
assert.equal(app.form.userId, '9', 'stale email response must not replace current recipient')
app.customerQuery.value = 'second@example.com'
fire()
calls[3].resolve({ userId: 8, name: 'second@example.com' })
await settle()
assert.equal(app.form.userId, '8', 'email lookup must submit the resolved ID')
app.customerQuery.value = '10'
app.customerQuery.value = '11'
assert.equal(timers.size, 1, 'debounce cancels previous lookup')
fire()
calls[4].reject(new Error('客户不存在'))
await settle()
assert.equal(app.lookupError.value, '客户不存在')
assert.equal(app.form.userId, '')
app.customerQuery.value = ''
assert.equal(app.lookupError.value, '')
assert.equal(app.querying.value, false)
app.customerQuery.value = '12'
fire()
props.modelValue = false
calls[5].resolve({ userId: 12 })
await settle()
assert.equal(app.recipient.value, null, 'closed dialog ignores late response')
scope.stop()
assert.ok(!source.includes('>查询客户</el-button>'))
console.log('PASS: ID/email lookup, debounce, stale response, resolved ID, clear, failure, close, button removal')
