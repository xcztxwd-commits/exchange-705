import assert from 'node:assert/strict'
import fs from 'node:fs'
import ts from 'typescript'
import { transform } from 'esbuild'
import { parse, compileTemplate } from '@vue/compiler-sfc'
const source = file => fs.readFileSync(new URL(`../src/control/${file}`, import.meta.url), 'utf8')
async function handler(file, name, bindings, prefix = '') {
 const script = parse(source(file)).descriptor.scriptSetup.content
 const ast = ts.createSourceFile(file + '.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
 const declaration = ast.statements.find(node => ts.isFunctionDeclaration(node) && node.name.text === name)
 assert.ok(declaration, `${name} exists in actual Vue script`)
 const compiled = (await transform(declaration.getText(ast), { loader: 'ts', target: 'es2022' })).code
 return new Function(...Object.keys(bindings), `${prefix};${compiled};return ${name}`)(...Object.values(bindings))
}
const definitions = { value: [{ key: 'feature.registration', name: '注册入口', options: ['false', 'true'], defaultValue: 'true', tenantEditable: true }, { key: 'config.example.mode', name: '模式', options: ['strict', 'normal'], defaultValue: 'strict', tenantEditable: true }] }
const policies = { value: [{ key: 'feature.registration', value: 'false', locked: false }] }
const policy = { key: '', value: '', locked: true, reason: 'old reason' }
const choose = await handler('TenantManager.vue', 'choosePolicy', { policies, policyDefinitions: definitions, policy })
choose('feature.registration')
assert.equal(policy.value, 'false', 'stored tenant grant wins over catalog default true')
assert.equal(policy.locked, false)
assert.equal(policy.reason, '')
choose('config.example.mode')
assert.equal(policy.value, 'strict', 'missing policy prefills default but does not save it')
assert.equal(policy.locked, true)
assert.equal(policies.value.length, 1, 'prefill does not fabricate a tenant grant')

const form = {}
const state = { form, existing: { value: false }, fixedValues: { value: false }, note: { value: '' }, open: { value: false } }
const edit = await handler('PolicyDefinitions.vue', 'edit', state)
edit({ ...definitions.value[1], version: 3 })
assert.equal(form.name, '模式');assert.equal(form.version, 3)
form.options.push('test')
assert.deepEqual(definitions.value[1].options, ['strict', 'normal'], 'cancelled edit must not mutate catalog options')
const change = await handler('PolicyDefinitions.vue', 'changeOptions', { form })
form.options = ['normal'];change();assert.equal(form.defaultValue, 'normal')

// Saving the catalog reports actual automatic assignments; failed readiness never reports success.
const catalogWrites = [], catalogMessages = [], catalogBindings = { form: { key: 'feature.registration', name: '注册入口', options: ['false', 'true'], defaultValue: 'true', version: 0, reason: '' }, busy: { value: false }, open: { value: true }, load: async () => {}, api: async (...args) => { catalogWrites.push(args);return { data: { appliedTenants: 2, retainedTenants: 3 } } }, ElMessage: { success: value => catalogMessages.push(value), error: value => catalogMessages.push(value), warning: value => catalogMessages.push(value) } }
const saveCatalog = await handler('PolicyDefinitions.vue', 'save', catalogBindings)
await saveCatalog();assert.equal(catalogWrites[0][0], '/control/policy-definitions');assert.equal(catalogWrites[0][1], 'PUT');assert.equal(catalogWrites[0][2].defaultValue, 'true');assert.equal(catalogWrites[0][2].reason, '');assert.equal(catalogBindings.open.value, false);assert.equal(catalogBindings.busy.value, false);assert.match(catalogMessages[0], /自动设置 2 个租户，保留 3 个已有值/)
catalogBindings.api = async () => { throw new Error('租户配置未就绪') };catalogBindings.open.value = true
const failCatalog = await handler('PolicyDefinitions.vue', 'save', catalogBindings)
await failCatalog();assert.equal(catalogBindings.open.value, true);assert.equal(catalogBindings.busy.value, false);assert.equal(catalogMessages[1], '租户配置未就绪')

const writes = [], messages = []
const saveBindings = { busy: { value: false }, policiesLoading: { value: false }, policiesError: { value: '' }, selected: { value: { id: 2 } }, currentDefinition: { value: definitions.value[0] }, policy: { key: 'feature.registration', value: 'false', locked: true, reason: '' }, api: async (...args) => writes.push(args), policyList: async () => {}, ElMessage: { success: value => messages.push(value), error: value => messages.push(value) } }
const save = await handler('TenantManager.vue', 'savePolicy', saveBindings, 'let policyGeneration=0')
await save()
assert.equal(writes.length, 1, 'blank operation reason still sends policy update')
assert.equal(writes[0][0], '/control/tenants/2/policies')
assert.equal(writes[0][2].reason, '')
assert.equal(saveBindings.busy.value, false)
saveBindings.currentDefinition.value = { tenantEditable: false }
await save();assert.equal(writes.length, 1, 'retention/special pages cannot be written through authorization form')

// Out-of-order read responses cannot mix one tenant's grant values into another tenant's form.
const pending = [], loadBindings = { selected: { value: null }, policies: { value: [] }, policyDefinitions: { value: [] }, policiesError: { value: '' }, policiesOpen: { value: false }, policiesLoading: { value: false }, policy: {}, api: path => new Promise(resolve => pending.push({ path, resolve })), dataRows: response => response.data, editableDefinitions: { value: definitions.value }, choosePolicy: () => {} }
const load = await handler('TenantManager.vue', 'policyList', loadBindings, 'let policyGeneration=0')
const first = load({ id: 2 }), second = load({ id: 3 })
pending[2].resolve({ data: [{ key: 'feature.registration', value: 'false', tenantId: 3 }] });pending[3].resolve({ data: definitions.value });await second
pending[0].resolve({ data: [{ key: 'feature.registration', value: 'true', tenantId: 2 }] });pending[1].resolve({ data: [] });await first
assert.equal(loadBindings.selected.value.id, 3);assert.equal(loadBindings.policies.value[0].tenantId, 3);assert.deepEqual(loadBindings.policyDefinitions.value, definitions.value)

const tenantTemplate = parse(source('TenantManager.vue')).descriptor.template.content
assert.match(tenantTemplate, /table-key="control.policies.v2"/)
assert.match(tenantTemplate, /:data="namedPolicies"[^>]*><el-table-column prop="name" label="策略名字"/)
assert.doesNotMatch(tenantTemplate, /label="操作原因" required><el-input v-model="policy.reason"/)
assert.match(tenantTemplate, /@change="choosePolicy"/)
assert.match(tenantTemplate, /currentDefinition\.options/)
assert.match(source('App.vue'), /<PolicyDefinitions v-else-if="tab==='policies'"/)
for (const file of ['PolicyDefinitions.vue', 'TenantManager.vue', 'App.vue']) {
 const descriptor = parse(source(file)).descriptor
 const result = compileTemplate({ source: descriptor.template.content, filename: file, id: file, compilerOptions: { expressionPlugins: ['typescript'] } })
 assert.deepEqual(result.errors, [], `${file} template compiles`)
}
assert.match(source('PolicyDefinitions.vue'), /已有明确值和锁定项不覆盖/);assert.match(source('PolicyDefinitions.vue'), /草稿租户仍须配置齐备后激活/)
console.log('PASS control policies: catalog automatic-assignment counts, readiness failure, preserved tenant values, named first column, optional reason, no retention bypass or stale cross-tenant response')
