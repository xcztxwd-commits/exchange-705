import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript'), vue = require('vue')
const file = fs.readFileSync(new URL('../src/views/Orders.vue', import.meta.url), 'utf8')
const script = file.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const js = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
const calls = [], warnings = []
const request = { post: async (...args) => { calls.push(['post', ...args]); return { success: true, list: [], total: 0 } }, delete: async (...args) => { calls.push(['delete', ...args]); return { success: true } } }
const app = new Function('ref', 'computed', 'onMounted', 'useAuthStore', 'usePermissions', 'useAccountTable', 'accountTableRequest', 'ElMessage', 'ElMessageBox', js + ';return {handleDeletion, handleReset, activeTab, contractQueryParams, optionQueryParams};')(
  vue.ref, vue.computed, () => {}, () => ({ user: { role: 'super_admin' } }), () => ({ isAgent: vue.ref(false), hasPermission: async () => true }), () => ({ modes: vue.ref(['REAL']), selectRow() {} }), () => request,
  { success() {}, warning: m => warnings.push(m), error: m => { throw new Error(m) } }, { confirm: async () => {} }
)
for (const type of ['contract', 'option']) {
  calls.length = 0
  await app.handleDeletion({ id: 7, status: 'CLOSED', deleted: false }, type)
  assert.deepEqual(calls[0], ['delete', `/admin/orders/${type}/7`])
  await app.handleDeletion({ id: 7, status: 'CLOSED', deleted: true }, type)
  assert.ok(calls.some(c => c[1] === `/admin/orders/${type}/7/restore`))
  calls.length = 0
  await app.handleDeletion({ id: 8, status: type === 'contract' ? 'OPEN' : 'TRADING', deleted: false }, type)
  assert.equal(calls.length, 0)
  app.activeTab.value = type
  app[`${type}QueryParams`].value.deletion = 'deleted'
  app.handleReset()
  assert.equal(app[`${type}QueryParams`].value.deletion, '')
}
assert.equal(warnings.length, 2)
assert.equal((file.match(/<el-tag v-if="row.deleted" type="info">已删除/g) || []).length, 2)
assert.ok(file.includes('deleted-order'))
assert.ok(!file.includes('abnormal-delete'))
console.log('PASS: delete, restore, reload, active-order guard, filter reset, deleted state for both order tabs')
