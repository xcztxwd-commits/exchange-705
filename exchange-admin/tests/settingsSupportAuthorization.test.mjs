import assert from 'node:assert/strict'
import fs from 'node:fs'
import ts from 'typescript'
import { transform } from 'esbuild'
import { parse, compileTemplate } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import { renderToString } from '@vue/server-renderer'
import { configEditable } from '../src/utils/tenantPolicies.ts'

// Execute actual Settings.vue setup, Vue reactivity, template and save handler; no real tenant requests.
const source = fs.readFileSync(new URL('../src/views/Settings.vue', import.meta.url), 'utf8')
const descriptor = parse(source).descriptor
const ast = ts.createSourceFile('Settings.vue.ts', descriptor.scriptSetup.content, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const script = ast.statements.filter(node => !ts.isImportDeclaration(node)).map(node => node.getText(ast)).join('\n')
const compiled = (await transform(script, { loader: 'ts', target: 'es2022' })).code
const bindingNames = name => ts.isIdentifier(name) ? [name.text] : name.elements.flatMap(element => element.name ? bindingNames(element.name) : [])
const exposed = ast.statements.flatMap(node => ts.isVariableStatement(node) ? node.declarationList.declarations.flatMap(declaration => bindingNames(declaration.name)) : ts.isFunctionDeclaration(node) ? [node.name.text] : [])
const snapshot = Vue.ref(null), policyReady = Vue.computed(() => !!snapshot.value), writes = [], messages = []
let reloadFailure = false
const policies = { snapshot, policyReady, policyError: Vue.ref(''), reloadPolicies: async () => { if (reloadFailure) { snapshot.value = null;throw new Error('策略加载失败') } }, editable: key => configEditable(snapshot.value, key), policyLabel: key => configEditable(snapshot.value, key) ? '' : '（总控锁定或未授权）' }
const request = { get: async () => [], post: async (path, payload) => { writes.push({ path, payload });return {} } }
const scope = Vue.effectScope()
const editor = scope.run(() => new Function('ref', 'computed', 'watch', 'onMounted', 'useTenantPolicies', 'request', 'ElMessage', 'playProtectedAudio', compiled + `;return {${exposed.join(',')}};`)(
 Vue.ref, Vue.computed, Vue.watch, () => {}, area => { assert.equal(area, 'settings');return policies }, request, { success: text => messages.push(text), error: text => messages.push(text) }, async () => {}
))
const authorized = { tenantId: 2, tenantName: 'A', status: 'ACTIVE', policyVersion: 7, features: { external_support: true, support: true }, supportChannel: null, configs: [] }
const template = compileTemplate({ source: descriptor.template.content, filename: 'Settings.vue', id: 'settings-support-test', compilerOptions: { expressionPlugins: ['typescript'] } })
assert.deepEqual(template.errors, [])
assert.match(descriptor.template.content, /<el-tab-pane v-if="serviceConfigVisible" label="客服配置" name="service">/)
const rendered = (await transform(template.code, { loader: 'ts', format: 'cjs', target: 'es2022' })).code
const module = { exports: {} }
new Function('require', 'module', 'exports', rendered)(() => Vue, module, module.exports)
async function html() {
 const app = Vue.createSSRApp({ setup: () => ({ ...editor, ...policies }), render: module.exports.render })
 const plain = { name: 'SettingsTestElement', setup: (_, { slots }) => () => Vue.h('div', slots.default?.()) }
 for (const tag of new Set([...descriptor.template.content.matchAll(/<(el-[a-z-]+|TenantPolicyNotice|MarketDepthHealth)\b/g)].map(match => match[1]))) if (tag !== 'el-tab-pane') app.component(tag, plain)
 app.component('el-tab-pane', { props: ['label', 'name'], setup: (props, { slots }) => () => Vue.h('section', { 'data-pane': props.name }, [Vue.h('span', props.label), slots.default?.()]) })
 app.directive('permission', { getSSRProps: () => ({}) })
 return renderToString(app)
}
try {
 assert.equal(editor.serviceConfigVisible.value, false, 'loading/error/unknown policy never exposes external support tab')
 assert.doesNotMatch(await html(), /data-pane="service"/)
 editor.activeTab.value = 'service';assert.equal(editor.activeTab.value, 'mail', 'hidden pane cannot stay selected')
 for (const denied of [{ ...authorized, features: { external_support: false } }, { ...authorized, features: {} }, { ...authorized, features: { external_support: 'true' } }, { ...authorized, supportChannel: 'internal' }, { ...authorized, configs: [{ key: 'customer.service.link', locked: false, denied: true, version: 1 }] }]) {
  snapshot.value = authorized;editor.activeTab.value = 'service';snapshot.value = denied
  assert.equal(editor.serviceConfigVisible.value, false);assert.equal(editor.activeTab.value, 'mail');assert.doesNotMatch(await html(), /data-pane="service"/)
 }
 snapshot.value = authorized;assert.equal(editor.serviceConfigVisible.value, true);assert.match(await html(), /data-pane="service"/);assert.equal(editor.activeTab.value, 'mail', 'granting permission does not force a tab switch')
 editor.serviceConfig.value[0].value = 'https://support.example.test'
 editor.serviceConfig.value[1].value = 'complaints@example.test'
 await editor.saveConfigs();assert.equal(writes.at(-1).path, '/admin/config/saveBatch');assert(writes.at(-1).payload.some(row => row.key === 'customer.service.link'));assert(writes.at(-1).payload.some(row => row.key === 'complaint.email'))
 snapshot.value = { ...authorized, features: { external_support: false, support: true } };editor.activeTab.value = 'risk'
 await editor.saveConfigs();assert(!writes.at(-1).payload.some(row => ['customer.service.link', 'complaint.email'].includes(row.key)));assert(writes.at(-1).payload.some(row => row.key === 'mail.host'));assert.equal(editor.activeTab.value, 'risk');assert.equal(editor.serviceConfig.value[0].value, 'https://support.example.test', 'hiding never deletes the saved/draft address')
 snapshot.value = { ...authorized, configs: [{ key: 'customer.service.link', locked: true, denied: false, version: 2 }] }
 assert.equal(editor.serviceConfigVisible.value, true, 'a locked but authorized address remains visible read-only');assert.equal(policies.editable('customer.service.link'), false)
 await editor.saveConfigs();assert(!writes.at(-1).payload.some(row => row.key === 'customer.service.link'));assert(writes.at(-1).payload.some(row => row.key === 'complaint.email'))
 editor.activeTab.value = 'service';snapshot.value = null;assert.equal(editor.activeTab.value, 'mail');const count = writes.length;await editor.saveConfigs();assert.equal(writes.length, count)
 snapshot.value = authorized;editor.activeTab.value = 'service';reloadFailure = true;await editor.loadConfigs();assert.equal(editor.activeTab.value, 'mail');assert.equal(editor.loading.value, false);assert.equal(messages.at(-1), '策略加载失败')
 assert.doesNotMatch(await html(), /data-pane="service"/)
 console.log('PASS actual Settings.vue: external-support grant controls tab rendering; revoke/internal/DENY/unknown hide it; active pane fallback; hidden fields excluded; locked authorized link stays read-only; drafts/internal support untouched')
} finally { scope.stop() }
