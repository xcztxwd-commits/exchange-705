import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript')
const { parse } = require('@vue/compiler-sfc')
const source = fs.readFileSync(new URL('../src/utils/tablePreferences.ts', import.meta.url), 'utf8')
const exports = {}
new Function('exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(exports)
const { mergeColumns, moveColumn } = exports
const columns = ['id', 'email', 'action'].map(id => ({ id, label: id, visible: true, fixed: id === 'action' ? 'right' : '' }))
const saved = [{ id: 'email', visible: false, fixed: 'left' }, { id: 'id', visible: true, fixed: '' }, { id: 'removed', visible: true, fixed: 'right' }]
assert.deepEqual(mergeColumns(columns, saved).map(c => c.id), ['email', 'id', 'action'])
assert.equal(mergeColumns(columns, saved)[0].visible, false)
assert.equal(mergeColumns(columns, saved)[0].fixed, 'left')
assert.equal(mergeColumns(columns, saved)[2].fixed, 'right')
assert.deepEqual(moveColumn(columns, 2, 0).map(c => c.id), ['action', 'id', 'email'])
assert.deepEqual(moveColumn(columns, -1, 0), columns)
assert.equal(mergeColumns(columns.slice(0, 1), [{ id: 'id', visible: false, fixed: '' }])[0].visible, true)
assert.deepEqual(mergeColumns(columns, []), columns)
const orderColumns = ['profit', 'netProfit', 'fee', 'status', 'action'].map((id, index) => ({ id, label: id, visible: true, fixed: id === 'action' ? 'right' : '', ...(index === 1 ? { defaultAfter: 'profit' } : index === 2 ? { defaultAfter: 'netProfit' } : {}) }))
const oldOrderColumns = orderColumns.filter(c => !['netProfit', 'fee'].includes(c.id)).map(({ id, visible, fixed }) => ({ id, visible, fixed }))
assert.deepEqual(mergeColumns(orderColumns, oldOrderColumns).map(c => c.id), ['profit', 'netProfit', 'fee', 'status', 'action'])
const reordered = [{ id: 'status', visible: true, fixed: 'left' }, { id: 'profit', visible: false, fixed: '' }, { id: 'action', visible: true, fixed: 'right' }]
const upgraded = mergeColumns(orderColumns, reordered)
assert.deepEqual(upgraded.map(c => c.id), ['status', 'profit', 'netProfit', 'fee', 'action'])
assert.equal(upgraded[0].fixed, 'left'); assert.equal(upgraded[1].visible, false); assert.equal(upgraded[2].visible, true)
const explicit = [...reordered, { id: 'fee', visible: false, fixed: 'left' }, { id: 'netProfit', visible: true, fixed: '' }]
assert.deepEqual(mergeColumns(orderColumns, explicit).map(c => c.id), explicit.map(c => c.id))
assert.equal(mergeColumns(orderColumns, explicit).find(c => c.id === 'fee').visible, false)
assert.deepEqual(mergeColumns(orderColumns, []), orderColumns)
assert.equal(mergeColumns([{ id: 'fee', label: 'fee', visible: true, fixed: '', defaultAfter: 'missing' }], []).length, 1)
const keys = [], files = [], sites = []
function walk(dir) { for (const entry of fs.readdirSync(dir, { withFileTypes: true })) { const p = path.join(dir, entry.name); if (entry.isDirectory()) walk(p); else if (p.endsWith('.vue')) files.push(p) } }
walk(fileURLToPath(new URL('../src', import.meta.url)))
for (const file of files.sort()) {
  const text = fs.readFileSync(file, 'utf8')
  assert.ok(!/<el-table[\s>]/.test(text), `Unconverted table: ${file}`)
  const ast = parse(text).descriptor.template?.ast
  function visit(node) {
    if (node.tag === 'admin-table') {
      const key = node.props.find(p => p.name === 'table-key' || (p.name === 'bind' && p.arg?.content === 'table-key'))
      assert.ok(key, 'Missing table-key: ' + file)
      if (key.name === 'table-key') keys.push(key.value.content)
      sites.push({ file: path.relative(fileURLToPath(new URL('..', import.meta.url)), file).replaceAll('\\', '/'), binding: key.name === 'table-key' ? 'table-key' : ':table-key', key: key.name === 'table-key' ? key.value.content : key.exp.content })
    }
    for (const child of node.children || []) visit(child)
  }
  if (ast) visit(ast)
}
assert.equal(new Set(keys).size, keys.length)
assert.deepEqual(sites, JSON.parse(fs.readFileSync(new URL('./table-preference-sites.json', import.meta.url), 'utf8')), 'Reviewed table sites changed; review and extend inventory before acceptance')
const requests = exports.preferenceRequests()
const first = requests.next(), second = requests.next()
assert.equal(first.signal.aborted, true); assert.equal(first.active(), false); assert.equal(second.active(), true)
requests.stop(); assert.equal(second.signal.aborted, true); assert.equal(second.active(), false)
const component = fs.readFileSync(new URL('../src/components/AdminTable.ts', import.meta.url), 'utf8')
assert.ok(!component.includes('useAuthStore') && !component.includes('@/utils/request'), 'Shared table must not pull ordinary credentials into independent control')
assert.ok(component.includes('draft.value = []') && component.includes('ticket.active()') && component.includes('onUnmounted'))
const independent = fs.readFileSync(new URL('../control/main.ts', import.meta.url), 'utf8')
assert.ok(independent.includes('/control/table-preferences/') && !independent.includes('pinia') && !independent.includes('useAuthStore'))
console.log(`PASS: merge/order/visibility/fixed defaults, identity cancellation, two credential domains, ${sites.length} reviewed table sites`)
