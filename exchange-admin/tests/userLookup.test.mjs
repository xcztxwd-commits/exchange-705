import assert from 'node:assert/strict'
import fs from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'
import { parse, compileTemplate } from '@vue/compiler-sfc'

const source = fs.readFileSync(new URL('../src/components/UserLookup.vue', import.meta.url), 'utf8')
const descriptor = parse(source).descriptor
assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename: 'UserLookup.vue', id: 'lookup' }).errors, [])
const timers = new Map(), cleanup = [], calls = []
let timerId = 0
const setTimer = callback => { timers.set(++timerId, callback); return timerId }
const clearTimer = id => timers.delete(id)
const helper = ts.transpileModule(fs.readFileSync(new URL('../src/composables/useDebouncedSearch.ts', import.meta.url), 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
const module = { exports: {} }
new Function('require', 'exports', 'setTimeout', 'clearTimeout', helper)(() => ({ onBeforeUnmount: callback => cleanup.push(callback) }), module.exports, setTimer, clearTimer)
const script = ts.transpileModule(descriptor.scriptSetup.content.replace(/^import .*$/gm, ''), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
const props = Vue.reactive({ modelValue: '', scope: 'orders', accountModes: ['REAL'], agentId: 50 })
const table = { modes: Vue.ref(['REAL']), query: (path, params) => new Promise((resolve, reject) => calls.push({ path, params, modes: [...table.modes.value], resolve, reject })) }
const scope = Vue.effectScope()
const editor = scope.run(() => new Function('computed', 'onBeforeUnmount', 'ref', 'watch', 'defineProps', 'withDefaults', 'defineEmits', 'useAccountTable', 'useDebouncedSearch', script + ';return {search, options, loading, error, selected};')(
  Vue.computed, callback => cleanup.push(callback), Vue.ref, Vue.watch, () => props, value => value, () => (name, value) => { if (name === 'update:modelValue') props.modelValue = value }, () => table, module.exports.useDebouncedSearch
))
const fire = () => { const pending = [...timers.values()]; timers.clear(); pending.forEach(callback => callback()) }
const settle = async () => { await Promise.resolve(); await Vue.nextTick() }
try {
  editor.search('70'); editor.search(' ALICE '); assert.equal(timers.size, 1); fire()
  assert.equal(calls[0].path, '/admin/user-lookup/orders'); assert.deepEqual(calls[0].params, { query: 'ALICE', filterAgentId: 50 })
  editor.search('700'); fire(); calls[1].resolve([{ userId: 7001, email: 'new@example.com' }]); await settle()
  calls[0].resolve([{ userId: 99, email: 'stale@example.com' }]); await settle()
  assert.equal(editor.options.value[0].id, '7001'); assert.equal(editor.loading.value, false)
  editor.selected.value = '7001'; assert.equal(props.modelValue, '7001')
  editor.search('pending'); fire(); editor.selected.value = ''; calls[2].resolve([{ userId: 1, email: 'late@example.com' }]); await settle()
  assert.deepEqual(editor.options.value, []); assert.equal(editor.loading.value, false)
  editor.search('failed'); fire(); calls[3].reject(new Error('搜索失败')); await settle(); assert.equal(editor.error.value, '搜索失败')
  editor.search(''); assert.equal(editor.error.value, '')
  props.accountModes = ['REAL', 'DEMO']; editor.search('700'); fire(); assert.deepEqual(calls[4].modes, ['REAL', 'DEMO'])
  calls[4].resolve({ list: [{ userId: 7001, email: 'real@example.com', accountModeLabel: '真实账户' }, { userId: 7001, email: 'demo@example.com', accountModeLabel: '模拟账户' }] }); await settle()
  assert.equal(editor.options.value.length, 1); assert.match(editor.options.value[0].label, /真实账户.*real@example.com.*模拟账户.*demo@example.com/)
  props.scope = 'agents'; props.agentId = 60; editor.search('agent'); fire()
  assert.equal(calls[5].path, '/admin/user-lookup/agents'); assert.equal(calls[5].params.filterAgentId, 60)
  props.accountModes = []; calls[5].resolve([{ userId: 9, email: 'old-agent@example.com' }]); await settle()
  editor.search('never'); fire(); assert.equal(calls.length, 6); assert.deepEqual(editor.options.value, [])
  props.accountModes = ['DEMO']; editor.search('demo'); fire(); assert.deepEqual(calls[6].modes, ['DEMO'])
  editor.search('scheduled'); for (const dispose of cleanup) dispose(); assert.equal(timers.size, 0)
  calls[6].resolve([{ userId: 8, email: 'unmounted@example.com' }]); await settle(); assert.deepEqual(editor.options.value, [])
  console.log('PASS actual UserLookup.vue: ID/email fragments, debounce, stale responses, select/clear, errors, agent scope, REAL/DEMO grouping, no account fallback, unmount cleanup')
} finally { scope.stop() }

// Exercise the actual agent status handler: read-only searches must not gain write permission.
const agentSource = parse(fs.readFileSync(new URL('../src/views/AgentManagement.vue', import.meta.url), 'utf8')).descriptor.scriptSetup.content
const agentAst = ts.createSourceFile('AgentManagement.ts', agentSource, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const statusDeclaration = agentAst.statements.find(node => ts.isVariableStatement(node) && node.declarationList.declarations.some(declaration => declaration.name.getText(agentAst) === 'handleStatusChange'))
const statusScript = ts.transpileModule(statusDeclaration.getText(agentAst), { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText
const statusWrites = [], statusErrors = []
let statusAllowed = false, statusSucceeds = true
const changeStatus = new Function('accountTable', 'axios', 'API_BASE', 'ElMessage', 'fetchAgents', 'can', statusScript + ';return handleStatusChange;')(
  { selectRow() {} }, { put: async (url, body) => { statusWrites.push({ url, body }); return { data: { success: statusSucceeds } } } }, '',
  { success() {}, error: message => statusErrors.push(message) }, () => {}, () => statusAllowed
)
const agent = { id: 7001, status: 'normal' }
await changeStatus(agent, false); assert.equal(statusWrites.length, 0); assert.equal(agent.status, 'normal')
statusAllowed = true
await changeStatus(agent, false); assert.deepEqual(statusWrites.at(-1), { url: '/api/admin/users/7001/status', body: { status: 'disabled' } }); assert.equal(agent.status, 'disabled')
await changeStatus(agent, true); assert.equal(statusWrites.at(-1).body.status, 'active'); assert.equal(agent.status, 'active')
statusSucceeds = false
await changeStatus(agent, false); assert.equal(agent.status, 'active'); assert.equal(statusErrors.length, 1)
console.log('PASS actual agent status handler: live permission guard, explicit toggle mapping, saved-state update, failure preserves old state')
