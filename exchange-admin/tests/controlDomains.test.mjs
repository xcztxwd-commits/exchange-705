import assert from 'node:assert/strict'
import fs from 'node:fs'
import { ref, reactive, computed } from 'vue'
import { transform } from 'esbuild'
import { parse, compileTemplate } from '@vue/compiler-sfc'
const vue = fs.readFileSync(new URL('../src/control/TenantManager.vue', import.meta.url), 'utf8')
const descriptor = parse(vue).descriptor
assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename: 'TenantManager.vue', id: 'domains' }).errors, [])
const script = (await transform(descriptor.scriptSetup.content.replace(/^import .*$/gm, ''), { loader: 'ts', target: 'es2022' })).code
const expose = 'return {domain, domainAction, copyAddress, domainState, domainTenant, domainForm, domainCandidates, domainBusy, domainError, preparedMatches}'
const tenant = { id: 1, name: 'Tenant A', entryHost: 'entry.forex-exchange.net', frontendHost: 'different.forex-exchange.cc', entryEnabled: false }
const binding = (role, hostname, status, version) => ({ role, hostname, status, version })
let state = { tenantId: 1, entryHost: tenant.entryHost, frontendHost: tenant.frontendHost, entryEnabled: false, domainVersion: 10, bindings: [binding('ENTRY', 'newentry.forex-exchange.net', 'PENDING', 3), binding('FRONTEND', 'newfront.forex-exchange.cc', 'PENDING', 6)] }
const calls = [], messages = [], copied = []
const api = async (path, method = 'GET', body) => {
 calls.push({ path, method, body: body && structuredClone(body) })
 if (path.endsWith('/domains/verify')) { const b = state.bindings.find(b => b.role === body.role);assert.equal(body.domainVersion, state.domainVersion);assert.equal(body.version, b.version);b.status = 'VERIFIED';b.version++;return { data: structuredClone(b) } }
 if (path.endsWith('/domains/activate-change')) { state.entryHost = state.bindings.find(b => b.role === 'ENTRY').hostname;state.frontendHost = state.bindings.find(b => b.role === 'FRONTEND').hostname;state.bindings.forEach(b => b.status = 'ACTIVE');state.entryEnabled = body.entryEnabled;state.domainVersion++ }
 if (path.endsWith('/domains/entry-switch')) { state.entryEnabled = body.entryEnabled;state.domainVersion++ }
 if (path.endsWith('/domains/state')) return { data: structuredClone(state) }
 if (path === '/control/tenants') return { data: [tenant] }
 return { data: [] }
}
function editor(apiFn = api) {
 return new Function('ref', 'reactive', 'computed', 'onMounted', 'onUnmounted', 'api', 'dataRows', 'ElMessage', 'ElMessageBox', 'navigator', 'startReadPolling', 'openTenant', `${script};${expose}`)(ref, reactive, computed, () => {}, () => {}, apiFn, r => r.data, { success: x => messages.push(x), warning: x => messages.push(x), error: x => messages.push(x) }, { confirm: async () => {} }, { clipboard: { writeText: async x => copied.push(x) } }, () => () => {}, async () => {})
}
const e = editor();await e.domain(tenant);assert.equal(e.domainCandidates.value.length, 2);assert.equal(e.domainForm.reason, '')
await e.domainAction('activate');assert.match(e.domainError.value, /全部候选/);assert.equal(calls.filter(c => c.method === 'POST').length, 0)
await e.domainAction('prepare');const prepared = calls.find(c => c.path.endsWith('/prepare-change'));assert.equal(prepared.body.entryHost, 'newentry.forex-exchange.net');assert.equal(prepared.body.frontendHost, 'newfront.forex-exchange.cc');assert.equal(prepared.body.domainVersion, 10)
for (const b of [...e.domainCandidates.value]) await e.domainAction('verify', b)
const verifications = calls.filter(c => c.path.endsWith('/verify'));assert.deepEqual(verifications.map(c => c.body.role), ['ENTRY', 'FRONTEND']);assert.deepEqual(verifications.map(c => c.body.version), [3, 6]);assert(verifications.every(c => c.body.domainVersion === 10))
e.domainForm.frontendHost = 'edited.forex-exchange.cc';assert.equal(e.preparedMatches.value, false);const before = calls.length;await e.domainAction('activate');assert.equal(calls.length, before, 'edited input cannot activate old verification');assert.match(e.domainError.value, /重新准备/)
e.domainForm.frontendHost = 'newfront.forex-exchange.cc';e.domainForm.entryEnabled = true;await e.domainAction('activate');const activation = calls.find(c => c.path.endsWith('/activate-change'));assert.equal(activation.body.candidates.length, 2);assert.equal(activation.body.domainVersion, 10);assert.equal(activation.body.entryEnabled, true);assert.equal(e.domainState.value.domainVersion, 11)
e.domainForm.entryEnabled = false;await e.domainAction('switch');assert.equal(calls.find(c => c.path.endsWith('/entry-switch')).body.domainVersion, 11);assert.equal(e.domainState.value.entryEnabled, false)
for (const endpoint of ['prepare-change', 'activate-change', 'entry-switch']) assert.equal(calls.find(c => c.path.endsWith('/' + endpoint)).body.reason, '', 'An empty optional reason must not block domain operations')
await e.domainAction('release', binding('FRONTEND', 'retired.forex-exchange.cc', 'RETIRED', 9));const release = calls.find(c => c.path.endsWith('/domains/release'));assert.equal(release.body.reason, '');assert.equal(release.body.version, 9)
e.domainForm.reason = ' \t ';e.domainForm.entryEnabled = true;await e.domainAction('switch');assert.equal(calls.filter(c => c.path.endsWith('/entry-switch')).at(-1).body.reason, ' \t ');assert.equal(e.domainError.value, '')
e.domainForm.reason = ' 原因已记录 ';e.domainForm.entryEnabled = false;await e.domainAction('switch');assert.equal(calls.filter(c => c.path.endsWith('/entry-switch')).at(-1).body.reason, ' 原因已记录 ')
e.domainForm.reason = '短';const beforeShort = calls.length;await e.domainAction('switch');assert.equal(calls.length, beforeShort);assert.match(e.domainError.value, /选填/)
assert.match(descriptor.template.content, /操作原因（选填）/);assert.match(descriptor.template.content, /留空自动记录“总控域名管理”/)
await e.copyAddress({ ...tenant, entryEnabled: true }, 'EXTERNAL');assert.equal(copied.at(-1), 'https://entry.forex-exchange.net/')
await e.copyAddress(tenant, 'EXTERNAL');assert.equal(copied.at(-1), 'https://different.forex-exchange.cc/');assert.match(messages.at(-1), /只能直访前台/)
await e.copyAddress(tenant, 'ENTRY');assert.equal(copied.at(-1), 'https://entry.forex-exchange.net/');assert.match(messages.at(-1), /不能访问/)
await e.copyAddress(tenant, 'FRONTEND');assert.equal(copied.at(-1), 'https://different.forex-exchange.cc/')
const pending = [];const stale = editor(path => new Promise(resolve => pending.push({ path, resolve })))
const a = stale.domain(tenant), b = stale.domain({ ...tenant, id: 2, name: 'Tenant B' });pending[1].resolve({ data: { ...structuredClone(state), tenantId: 2, domainVersion: 20 } });await b;pending[0].resolve({ data: structuredClone(state) });await a;assert.equal(stale.domainTenant.value.id, 2);assert.equal(stale.domainState.value.domainVersion, 20)
const failed = editor(async path => { if (path.endsWith('/domains/state')) return { data: structuredClone(state) };throw new Error('版本冲突') });await failed.domain(tenant);failed.domainForm.reason = 'switch test';failed.domainForm.entryEnabled = true;await failed.domainAction('switch');assert.match(failed.domainError.value, /版本冲突/);assert.equal(failed.domainState.value.entryEnabled, false)
assert(descriptor.template.content.indexOf('label="入口域名"') < descriptor.template.content.indexOf('label="前台域名"'));assert.match(vue, /入口状态/);assert.match(vue, /复制入口地址/);assert.match(vue, /复制前台地址/);assert.match(vue, /前台换域可能需要重新登录/)
console.log('PASS: actual Vue handlers, optional empty/whitespace reason for prepare/activate/switch/release, supplied reason validation, separate roles, all-candidate activation, versions, stale response discard, switch failures, copy defaults and compiled template')
