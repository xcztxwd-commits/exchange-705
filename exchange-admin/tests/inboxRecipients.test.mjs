import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript'), vue = require('vue')
const source = fs.readFileSync(new URL('../src/views/InboxManagement.vue', import.meta.url), 'utf8')
const script = source.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const js = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
const calls = [], posts = [], timers = new Map(), warnings = []
let timerId = 0, dispose
const request = {
  get: (url, options) => url.endsWith('/recipients') ? new Promise((resolve, reject) => calls.push({ query: options.params.query, resolve, reject })) : Promise.resolve(url.endsWith('/config') ? { inboxEnabled: true } : []),
  post: async (url, body) => posts.push(body),
}
const scope = vue.effectScope()
const app = scope.run(() => new Function('ref', 'watch', 'onMounted', 'onBeforeUnmount', 'useAccountTable', 'accountTableRequest', 'requestId', 'ElMessage', 'ElMessageBox', 'setTimeout', 'clearTimeout', js + ';return {recipientQuery, matches, searching, searchError, recipients, enabled, title, content, addRecipient, removeRecipient, send};')(
  vue.ref, vue.watch, () => {}, callback => { dispose = callback }, () => ({ modes: vue.ref(['REAL']) }), () => request, () => 'test-request-id-123',
  { warning: message => warnings.push(message), success() {}, error() {} }, { confirm: async () => {} },
  callback => { timers.set(++timerId, callback); return timerId }, id => timers.delete(id),
))
const fire = () => { const pending = [...timers.values()]; timers.clear(); pending.forEach(callback => callback()) }
const settle = async () => { await Promise.resolve(); await vue.nextTick() }
app.enabled.value = true
app.recipientQuery.value = '7'; fire()
app.recipientQuery.value = ' first@example.com '; fire()
calls[1].resolve([{ id: 8, email: 'first@example.com' }]); await settle()
calls[0].resolve([{ id: 7, email: 'stale@example.com' }]); await settle()
assert.equal(app.matches.value[0].id, 8)
assert.equal(calls[1].query, 'first@example.com')
app.addRecipient(app.matches.value[0]); app.addRecipient(app.matches.value[0])
assert.equal(app.recipients.value.length, 1, 'duplicates are ignored')
app.recipientQuery.value = '9'; app.recipientQuery.value = '10'
assert.equal(timers.size, 1)
fire(); calls[2].resolve([{ id: 10, email: 'ten@example.com' }]); await settle()
app.addRecipient(app.matches.value[0])
assert.deepEqual(app.recipients.value.map(user => user.id), [8, 10])
app.removeRecipient(8)
assert.deepEqual(app.recipients.value.map(user => user.id), [10])
app.recipientQuery.value = 'missing'; fire(); calls[3].resolve([]); await settle()
assert.equal(app.matches.value.length, 0)
app.recipientQuery.value = 'failed'; fire(); calls[4].reject(new Error('搜索失败')); await settle()
assert.equal(app.searchError.value, '搜索失败')
app.recipientQuery.value = ''
assert.equal(app.searchError.value, '')
assert.equal(app.searching.value, false)
app.title.value = '标题'; app.content.value = '正文'; await app.send()
assert.deepEqual(posts[0].users, [10], 'only selected IDs are sent')
assert.equal(app.recipients.value.length, 0)
await app.send(); assert.equal(posts.length, 1, 'empty recipient list is rejected')
app.recipients.value = Array.from({ length: 200 }, (_, i) => ({ id: i + 1, email: '' }))
app.addRecipient({ id: 201, email: '' }); assert.equal(app.recipients.value.length, 200)
app.recipientQuery.value = 'late'; fire(); dispose()
calls[5].resolve([{ id: 999, email: '' }]); await settle()
assert.equal(app.matches.value.length, 0)
scope.stop()
console.log('PASS: ID/email search, debounce, stale response, continuous add, dedup, remove, empty/error, 200 limit, send/reset, unmount')
