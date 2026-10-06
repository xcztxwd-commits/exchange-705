import assert from 'node:assert/strict'
import fs from 'node:fs'
import ts from 'typescript'
import { transform } from 'esbuild'
import { parse, compileTemplate } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import { renderToString } from '@vue/server-renderer'

// Execute actual setup and templates for every total-control tenant selector, never a copied implementation.
const files = ['BusinessSupervision', 'SupervisionRecords', 'StatisticsSupervision', 'Operations']
const A = { tenant_id: 2, tenant_name: 'Tenant A', id: 9, job: 'contract-match', latest_failure_id: 10, last_review_id: 0 }
const B = { ...A, tenant_id: 3, tenant_name: 'Tenant B', latest_failure_id: 12 }
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }
const deferred = () => { let resolve;const promise = new Promise(yes => { resolve = yes });return { promise, resolve } }
const built = new Map()
for (const file of files) {
 const descriptor = parse(fs.readFileSync(new URL(`../src/control/${file}.vue`, import.meta.url), 'utf8')).descriptor
 const ast = ts.createSourceFile(file + '.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
 const script = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast)).join('\n')
 const exposed = ast.statements.flatMap(node => ts.isVariableStatement(node) ? node.declarationList.declarations.map(item => item.name.text) : ts.isFunctionDeclaration(node) ? [node.name.text] : [])
 const code = (await transform(script, { loader: 'ts', target: 'es2022' })).code
 const template = compileTemplate({ source: descriptor.template.content, filename: file + '.vue', id: 'all-' + file, compilerOptions: { expressionPlugins: ['typescript'] } })
 assert.deepEqual(template.errors, [])
 const module = { exports: {} }
 new Function('require', 'module', 'exports', (await transform(template.code, { loader: 'ts', format: 'cjs' })).code)(() => Vue, module, module.exports)
 built.set(file, { descriptor, code, exposed, render: module.exports.render })
}
function fixture(file, kind = 'kyc') {
 const { code, exposed } = built.get(file), calls = [], notices = [], prompts = [], mounted = [], unmounted = [], props = Vue.reactive({ kind })
 let reply = call => {
  if (call.path === '/control/tenants') return { data: [{ id: 2, name: 'Tenant A' }, { id: 3, name: 'Tenant B' }] }
  if (call.path === '/control/operations/health') return { data: { failureDelivery: 'DATABASE' } }
  const rows = call.path.includes('/tenants/2') ? [A] : call.path.includes('/tenants/3') ? [B] : [A, B]
  if (file === 'Operations') return { data: rows }
  if (file === 'StatisticsSupervision') return { data: { counts: { users: rows.length }, assets: rows, contracts: [], options: [], deposits: [], withdrawals: [] } }
  return { data: { rows, columns: ['tenant_id', 'tenant_name', 'id'], total: rows.length } }
 }, promptReply = () => ({ value: prompts.length % 2 ? 'Reviewed original engine evidence' : 'a'.repeat(64) }), confirmReply = () => {}
 const scope = Vue.effectScope()
 const args = { ref: Vue.ref, computed: Vue.computed, watch: Vue.watch, onMounted: fn => mounted.push(fn), onUnmounted: fn => unmounted.push(fn), defineProps: () => props,
  ElMessage: Object.fromEntries(['success', 'error'].map(key => [key, value => notices.push({ key, value })])),
  ElMessageBox: { prompt: async (text, title, options) => { prompts.push({ text, title, options });return promptReply() }, confirm: async text => confirmReply(text) },
  api: async (path, method = 'GET', payload, signal) => { const call = { path, method, payload, signal };calls.push(call);return reply(call) }, dataRows: result => result.data ?? result }
 const editor = scope.run(() => new Function(...Object.keys(args), code + ';return {' + exposed.join(',') + '};')(...Object.values(args)))
 return { file, editor, calls, notices, prompts, props, mount: () => mounted[0](), stop: () => { unmounted.forEach(fn => fn());scope.stop() }, api: fn => { reply = fn }, prompt: fn => { promptReply = fn }, confirm: fn => { confirmReply = fn } }
}
async function html(f) {
 const { descriptor, render } = built.get(f.file), app = Vue.createSSRApp({ setup: () => ({ ...f.editor, ...f.props }), render })
 const plain = { setup: (_, { slots }) => () => Vue.h('div', slots.default?.()) }
 for (const tag of new Set([...descriptor.template.content.matchAll(/<(el-[a-z-]+|admin-table)\b/g)].map(item => item[1]))) if (!['el-select', 'el-option', 'el-button', 'admin-table', 'el-table-column'].includes(tag)) app.component(tag, plain)
 app.component('el-select', { props: ['modelValue'], setup: (props, { slots }) => () => Vue.h('select', { 'data-selected': props.modelValue }, slots.default?.()) })
 app.component('el-option', { props: ['value', 'label'], setup: props => () => Vue.h('option', { value: props.value }, props.label) })
 app.component('el-button', { props: ['disabled'], setup: (props, { slots }) => () => Vue.h('button', { disabled: props.disabled }, slots.default?.()) })
 app.component('admin-table', { props: ['data'], setup: (props, { slots }) => { Vue.provide('rows', props);return () => Vue.h('div', slots.default?.()) } })
 app.component('el-table-column', { props: ['label'], setup: (props, { slots }) => { const table = Vue.inject('rows');return () => Vue.h('div', [props.label, ...table.data.map(row => slots.default?.({ row }))]) } })
 app.directive('loading', {});return renderToString(app)
}
const route = (file, kind) => file === 'BusinessSupervision' ? '/business/users' : file === 'StatisticsSupervision' ? '/supervision/statistics' : file === 'Operations' ? '/operations/issues' : `/supervision/${kind}`
for (const [file, kind] of [['BusinessSupervision'], ...['kyc', 'admins', 'agents'].map(kind => ['SupervisionRecords', kind]), ['StatisticsSupervision'], ['Operations']]) {
 const f = fixture(file, kind), e = f.editor
 try {
  assert.equal(e.tenant.value, 'all');await f.mount();assert(f.calls.some(call => call.path.startsWith('/control' + route(file, kind))))
  const rendered = await html(f);assert.match(rendered, /data-selected="all"/);assert.match(rendered, /<option value="all">全部<\/option>/);assert.match(rendered, /租户 ID/)
  assert.doesNotMatch(rendered, /<button[^>]*disabled[^>]*>(查询|刷新|刷新快照)<\/button>/)
  assert.equal(file === 'StatisticsSupervision' ? e.data.value.counts.users : e.rows.value.length, 2)
  if (e.page) { e.page.value = file === 'Operations' ? 1 : 2;await e.load();assert(f.calls.some(call => call.path.includes('page=' + (file === 'Operations' ? '1' : '2')))) }
  e.tenant.value = 2;await flush();assert(f.calls.some(call => call.path.startsWith(file === 'Operations' ? '/control/operations/tenants/2?' : '/control/tenants/2/')))
  if (e.page) assert.equal(e.page.value, file === 'Operations' ? 0 : 1)
  assert.equal(file === 'StatisticsSupervision' ? e.data.value.counts.users : e.rows.value.length, 1)
  e.tenant.value = 'all';await flush()
  if (e.userEmail) {
   e.userEmail.value = ' person@example.test ';if (e.userId) e.userId.value = '7';if (e.subject) e.subject.value = '7';if (e.status) e.status.value = 'PENDING'
   await e.load(true);const call = new URL(f.calls.at(-1).path, 'https://test.invalid');assert.equal(call.searchParams.get('userEmail'), 'person@example.test');assert.equal(call.searchParams.get(e.userId ? 'userId' : 'subjectId'), '7')
   if (e.status) assert.equal(call.searchParams.has('status'), kind !== 'admins')
   const count = f.calls.length;if (e.userId) e.userId.value = '0';else e.subject.value = '0';await e.load();assert.equal(f.calls.length, count)
   if (e.userId) e.userId.value = '';else e.subject.value = ''
  }
  const pending = deferred();f.api(call => call.path.includes('/tenants/3') ? (file === 'StatisticsSupervision' ? { data: { counts: { users: 1 } } } : file === 'Operations' ? { data: [B] } : { data: { rows: [B], columns: [], total: 1 } }) : call.path.endsWith('/health') ? { data: { failureDelivery: 'DATABASE' } } : pending.promise)
  const stale = e.load(), oldSignal = f.calls.findLast(call => !call.path.endsWith('/health')).signal;e.tenant.value = 3;await flush();assert.equal(oldSignal.aborted, true)
  pending.resolve(file === 'StatisticsSupervision' ? { data: { counts: { users: 99 } } } : { data: { rows: [A], columns: [], total: 99 } });await stale
  if (file === 'StatisticsSupervision') assert.equal(e.data.value.counts.users, 1);else assert.deepEqual(e.rows.value, [B])
  assert(f.calls.every(call => !/\/tenants\/(all|undefined)/.test(call.path)))
 } finally { f.stop() }
 const loading = fixture(file, kind), late = deferred();try { loading.api(call => call.path === '/control/tenants' ? late.promise : { data: file === 'Operations' ? [] : { rows: [], columns: [], total: 0 } });const mounted = loading.mount();await loading.editor.load();late.resolve({ data: [{ id: 2 }] });await mounted;assert.equal(loading.editor.tenants.value.length, 1);assert.equal(loading.calls.filter(call => !call.path.endsWith('/health')).length, 2) } finally { loading.stop() }
 const destroyed = fixture(file, kind), after = deferred();destroyed.api(() => after.promise);const mounted = destroyed.mount();destroyed.stop();after.resolve({ data: [{ id: 2 }] });await mounted;assert.equal(destroyed.calls.length, 1);assert.equal(destroyed.editor.tenants.value.length, 0)
}
const f = fixture('Operations'), e = f.editor
try {
 await f.mount();await e.review(B);const write = f.calls.find(call => call.method === 'POST');assert.equal(write.path, '/control/operations/tenants/3/review');assert.equal(write.payload.expectedFailureId, 12);assert.equal(write.payload.evidenceSha256, 'a'.repeat(64));assert.match(f.prompts[0].title, /Tenant B/)
 e.tenant.value = 2;await flush();const before = f.prompts.length;await e.review(B);await e.review({ job: 'unknown' });assert.equal(f.prompts.length, before)
 e.tenant.value = 'all';await flush();const late = deferred();f.prompt(() => late.promise);const review = e.review(A), writes = f.calls.filter(call => call.method === 'POST').length;e.tenant.value = 3;await flush();late.resolve({ value: 'Too late to act' });await review;assert.equal(f.calls.filter(call => call.method === 'POST').length, writes)
} finally { f.stop() }
// Keep the full selector inventory covered; global-only pages have no per-tenant dropdown to change.
const inventory = fs.readdirSync(new URL('../src/control/', import.meta.url)).filter(name => name.endsWith('.vue') && /v-model="tenant"/.test(fs.readFileSync(new URL('../src/control/' + name, import.meta.url), 'utf8'))).sort()
assert.deepEqual(inventory, [...files.map(file => file + '.vue'), 'ChatSupervision.vue'].sort())
console.log('PASS all total-control tenant selectors: default all, first load, global/scoped filters and paging, tenant provenance, stale/unmount safety; operational reviews remain row-tenant-bound')
