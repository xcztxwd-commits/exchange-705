import assert from 'node:assert/strict'
import fs from 'node:fs'
import ts from 'typescript'
import { transform } from 'esbuild'
import { parse, compileTemplate } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import { renderToString } from '@vue/server-renderer'

// Execute actual setup, template and handlers; all requests and downloads stay in local fixtures.
const descriptor = parse(fs.readFileSync(new URL('../src/control/ChatSupervision.vue', import.meta.url), 'utf8')).descriptor
const ast = ts.createSourceFile('ChatSupervision.vue.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const script = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast)).join('\n')
const code = (await transform(script, { loader: 'ts', target: 'es2022' })).code
const exposed = ast.statements.flatMap(node => ts.isVariableStatement(node) ? node.declarationList.declarations.map(item => item.name.text) : ts.isFunctionDeclaration(node) ? [node.name.text] : [])
const template = compileTemplate({ source: descriptor.template.content, filename: 'ChatSupervision.vue', id: 'chat-all-test', compilerOptions: { expressionPlugins: ['typescript'] } })
assert.deepEqual(template.errors, [])
const module = { exports: {} }
new Function('require', 'module', 'exports', (await transform(template.code, { loader: 'ts', format: 'cjs', target: 'es2022' })).code)(() => Vue, module, module.exports)
const A = { id: 9, tenant_id: 2, tenant_name: 'Tenant A', legal_hold: false }, B = { id: 9, tenant_id: 3, tenant_name: 'Tenant B', legal_hold: true }
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve() }
const deferred = () => { let resolve;const promise = new Promise(yes => { resolve = yes });return { promise, resolve } }
function fixture() {
 const calls = [], files = [], notices = [], downloads = [], prompts = [], mounted = [], unmounted = []
 let reply = call => {
  if (call.path === '/control/tenants') return { data: [{ id: 2, name: 'Tenant A' }, { id: 3, name: 'Tenant B' }] }
  if (call.path.includes('/support/conversations?')) return { data: call.path.startsWith('/control/tenants/2/') ? [A] : call.path.startsWith('/control/tenants/3/') ? [B] : [A, B] }
  if (call.path.includes('?after=')) return { data: [{ id: 70, image: true, text: call.path.includes('/tenants/3/') ? 'B message' : 'A message' }] }
  if (call.path.endsWith('/retention')) return { data: { autoDeleteEnabled: false, candidateIds: [], policyVersion: 1, previewHash: 'fixture' } }
  return { data: [] }
 }
 let fileReply = () => ({ blob: new Blob(['{}'], { type: 'application/json' }), chainValid: 'true' }), promptReply = () => ({ value: 'fixture audit reason' })
 const scope = Vue.effectScope()
 const editor = scope.run(() => new Function('ref', 'watch', 'onMounted', 'onUnmounted', 'ElMessage', 'ElMessageBox', 'api', 'apiFile', 'dataRows', 'document', 'URL', 'setTimeout', code + ';return {' + exposed.join(',') + '};')(
  Vue.ref, Vue.watch, callback => mounted.push(callback), callback => unmounted.push(callback),
  Object.fromEntries(['success', 'warning', 'error'].map(key => [key, value => notices.push({ key, value })])),
  { prompt: async (text, title, options) => { prompts.push({ text, title });assert.equal(typeof options.inputValidator(''), 'string');return promptReply() }, confirm: async () => {} },
  async (path, method = 'GET', payload, signal) => { const call = { path, method, payload, signal };calls.push(call);return reply(call) },
  async path => { files.push(path);return fileReply(path) }, result => result.data ?? result,
  { createElement: () => ({ click() { downloads.push({ name: this.download }) } }) }, { createObjectURL: () => 'blob:local-test', revokeObjectURL: () => {} }, () => 1
 ))
 return { editor, calls, files, notices, downloads, prompts, mount: () => mounted[0](), stop: () => { unmounted.forEach(callback => callback());scope.stop() }, api: handler => { reply = handler }, file: handler => { fileReply = handler }, prompt: handler => { promptReply = handler } }
}
async function html(editor) {
 const app = Vue.createSSRApp({ setup: () => editor, render: module.exports.render }), plain = { setup: (_, { slots }) => () => Vue.h('div', slots.default?.()) }
 for (const tag of new Set([...descriptor.template.content.matchAll(/<(el-[a-z-]+|admin-table|ControlAttachment)\b/g)].map(item => item[1]))) if (!['el-select', 'el-option', 'el-button', 'el-dialog', 'admin-table', 'el-table-column', 'ControlAttachment'].includes(tag)) app.component(tag, plain)
 app.component('el-select', { props: ['modelValue'], setup: (props, { slots }) => () => Vue.h('select', { 'data-selected': props.modelValue }, slots.default?.()) })
 app.component('el-option', { props: ['value', 'label'], setup: props => () => Vue.h('option', { value: props.value }, props.label) })
 app.component('el-button', { props: ['disabled'], setup: (props, { slots }) => () => Vue.h('button', { disabled: props.disabled }, slots.default?.()) })
 app.component('el-dialog', { props: ['modelValue'], setup: (props, { slots }) => () => props.modelValue ? Vue.h('section', slots.default?.()) : null })
 app.component('admin-table', { props: ['data'], setup: (props, { slots }) => { Vue.provide('test.rows', props);return () => Vue.h('div', slots.default?.()) } })
 app.component('el-table-column', { setup: (_, { slots }) => { const table = Vue.inject('test.rows');return () => Vue.h('div', table.data.map(row => slots.default?.({ row }))) } })
 app.component('ControlAttachment', { props: ['path'], setup: props => () => Vue.h('span', { 'data-attachment-path': props.path }) })
 return renderToString(app)
}
async function manifest(parsed) {
 const blob = new Blob([JSON.stringify(parsed)], { type: 'application/json' }), sha256 = Buffer.from(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())).toString('hex')
 return { blob, file: { file: 'manifest-local.json', sha256 } }
}
const f = fixture(), e = f.editor
try {
 assert.equal(e.tenant.value, 'all');await f.mount();assert.deepEqual(e.rows.value, [A, B]);assert.equal(f.calls[1].path, '/control/support/conversations?page=0')
 const rendered = await html(e);assert.match(rendered, /<select[^>]*data-selected="all"/);assert.match(rendered, /<option value="all">全部<\/option>/);assert.match(rendered, /<button[^>]*disabled[^>]*>留存预览与策略/);assert.doesNotMatch(rendered, /<button[^>]*disabled[^>]*>查询/)
 e.userId.value = '7';e.adminId.value = '8';e.userEmail.value = ' person@example.test ';e.createdFrom.value = '2026-01-02T08:00';e.createdTo.value = '2026-01-02T08:01';await e.load(true)
 const query = new URL(f.calls.at(-1).path, 'https://local.example.test');assert.equal(query.pathname, '/control/support/conversations');assert.equal(query.searchParams.get('userId'), '7');assert.equal(query.searchParams.get('adminId'), '8');assert.equal(query.searchParams.get('userEmail'), 'person@example.test');assert.equal(query.searchParams.get('createdFrom'), new Date(e.createdFrom.value).toISOString())
 const count = f.calls.length;e.userId.value = '0';await e.load();assert.equal(f.calls.length, count);assert.match(e.error.value, /正整数/)
 e.userId.value = '';e.adminId.value = '';e.userEmail.value = '';e.createdFrom.value = '';e.createdTo.value = '';e.page.value = 2;await e.load();assert.equal(f.calls.at(-1).path, '/control/support/conversations?page=1')
 e.tenant.value = 2;await flush();assert.equal(e.page.value, 1);assert.equal(f.calls.at(-1).path, '/control/tenants/2/support/conversations?page=0');assert.deepEqual(e.rows.value, [A])
 e.tenant.value = 'all';await flush();await e.detail(B);assert.equal(f.calls.at(-2).path, '/control/tenants/3/support/conversations/9?after=0');assert.equal(f.calls.at(-1).path, '/control/tenants/3/support/conversations/9/archive-jobs');assert.equal(e.messages.value[0].text, 'B message');assert.match(await html(e), /data-attachment-path="\/control\/tenants\/3\/support\/conversations\/9\/images\/70"/)
 await e.exportEvidence();assert.equal(f.files.at(-1), '/control/tenants/3/support/conversations/9/evidence');assert.equal(f.downloads.at(-1).name, 'tenant-3-conversation-9.json')
 await e.startArchive();assert(f.calls.some(call => call.path === '/control/tenants/3/support/conversations/9/archive-jobs' && call.method === 'POST' && call.payload.reason));await e.retryArchive({ id: 'job-B' });assert(f.calls.some(call => call.path === '/control/tenants/3/support/archive-jobs/job-B/retry' && call.method === 'POST'))
 const correct = await manifest({ tenantId: 3, jobId: 'job-B', chunks: [{ file: 'chunk.json' }] });f.file(() => ({ blob: correct.blob }));await e.archiveDownload({ id: 'job-B' }, correct.file, true);assert.equal(f.files.at(-1), '/control/tenants/3/support/archive-jobs/job-B/files/manifest-local.json');assert.equal(e.archiveFiles.value[0].file, 'chunk.json')
 const wrong = await manifest({ tenantId: 2, jobId: 'job-B', chunks: [] }), downloaded = f.downloads.length;f.file(() => ({ blob: wrong.blob }));await e.archiveDownload({ id: 'job-B' }, wrong.file, true);assert.equal(f.downloads.length, downloaded);assert.match(f.notices.at(-1).value, /租户或任务不匹配/);assert.equal(e.archiveFiles.value.length, 1);await e.archiveDownload({ id: 'job-B' }, { ...wrong.file, sha256: 'incorrect' }, true);assert.match(f.notices.at(-1).value, /哈希不匹配/);assert.equal(f.downloads.length, downloaded)
 const before = f.calls.length;await e.retention();e.preview.value = { candidateIds: [9] };await e.retentionAction(true);assert.equal(f.calls.length, before, 'all must never enable global retention mutation')
 await e.hold(A);assert(f.calls.some(call => call.path === '/control/tenants/2/retention/hold/9' && call.method === 'POST' && call.payload.enabled === true));assert.match(f.prompts.at(-1).title, /Tenant A/);assert.equal(f.calls.at(-1).path, '/control/support/conversations?page=0')
 e.tenant.value = 2;await flush();const invalid = f.calls.length;await e.detail(B);assert.equal(f.calls.length, invalid);assert.match(e.error.value, /租户范围无效/);assert.equal(e.conversation.value, null);await e.hold({ id: 9 });assert.equal(f.calls.length, invalid);await e.retention();assert.equal(f.calls.at(-1).path, '/control/tenants/2/retention')
 e.tenant.value = 'all';await flush();assert.equal(e.preview.value, null);assert.equal(e.previewOpen.value, false)
 const pendingList = deferred();f.api(call => call.path.startsWith('/control/support/conversations?') ? pendingList.promise : { data: [B] });const staleList = e.load(), signal = f.calls.at(-1).signal;e.tenant.value = 3;await flush();assert.equal(signal.aborted, true);pendingList.resolve({ data: [A] });await staleList;assert.deepEqual(e.rows.value, [B])
 e.tenant.value = 'all';await flush();const pendingDetail = deferred();f.api(call => call.path.includes('/tenants/3/') && call.path.includes('?after=') ? pendingDetail.promise : { data: call.path.includes('?after=') ? [{ id: 72, text: 'new A message' }] : [] });const staleDetail = e.detail(B);await e.detail(A);pendingDetail.resolve({ data: [{ id: 71, text: 'stale B message' }] });await staleDetail;assert.equal(e.conversation.value.tenant_id, 2);assert.equal(e.messages.value[0].text, 'new A message')
 const pendingFile = deferred();f.file(() => pendingFile.promise);const staleExport = e.exportEvidence(), exportCount = f.downloads.length;await e.detail(B);pendingFile.resolve({ blob: new Blob(['{}'], { type: 'application/json' }), chainValid: 'true' });await staleExport;assert.equal(f.downloads.length, exportCount)
 const pendingReason = deferred();f.prompt(() => pendingReason.promise);const staleHold = e.hold(A), writes = f.calls.filter(call => call.method === 'POST').length;e.tenant.value = 3;await flush();pendingReason.resolve({ value: 'too late' });await staleHold;assert.equal(f.calls.filter(call => call.method === 'POST').length, writes);assert(f.calls.every(call => !call.path.includes('/tenants/all/') && !call.path.includes('/tenants/undefined/')))
 console.log('PASS actual ChatSupervision.vue: default all, global paging/filters, tenant-bound details/attachments/evidence/archive/hold, stale-response cancellation, no global retention mutation')
} finally { f.stop() }
const loading = fixture(), pending = deferred()
try { loading.api(call => call.path === '/control/tenants' ? pending.promise : { data: [A, B] });const mounted = loading.mount();await loading.editor.load();pending.resolve({ data: [{ id: 2, name: 'Tenant A' }] });await mounted;assert.equal(loading.editor.tenants.value.length, 1);assert.equal(loading.calls.length, 2) } finally { loading.stop() }
const destroyed = fixture(), late = deferred();destroyed.api(() => late.promise);const mounted = destroyed.mount();destroyed.stop();late.resolve({ data: [{ id: 2 }] });await mounted;assert.equal(destroyed.calls.length, 1);assert.equal(destroyed.editor.tenants.value.length, 0)
console.log('PASS chat lifecycle: options survive concurrent queries; unmounted screen never starts a new list request')
