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
const keys = [], files = []
function walk(dir) { for (const entry of fs.readdirSync(dir, { withFileTypes: true })) { const p = path.join(dir, entry.name); if (entry.isDirectory()) walk(p); else if (p.endsWith('.vue')) files.push(p) } }
walk(fileURLToPath(new URL('../src', import.meta.url)))
for (const file of files) {
  const text = fs.readFileSync(file, 'utf8')
  assert.ok(!/<el-table[\s>]/.test(text), `Unconverted table: ${file}`)
  const ast = parse(text).descriptor.template?.ast
  function visit(node) {
    if (node.tag === 'admin-table') {
      const key = node.props.find(p => p.name === 'table-key' || (p.name === 'bind' && p.arg?.content === 'table-key'))
      assert.ok(key, 'Missing table-key: ' + file)
      if (key.name === 'table-key') keys.push(key.value.content)
      else assert.ok(file.endsWith('AccountInspection.vue') && key.exp.content.includes('kind'), 'Unexpected dynamic table-key: ' + file)
    }
    for (const child of node.children || []) visit(child)
  }
  if (ast) visit(ast)
}
assert.equal(new Set(keys).size, keys.length)
assert.equal(keys.length, 42) // AccountInspection uses a separate dynamic key per data category.
console.log('PASS: visibility/order/fixed/defaults/schema changes; all 43 tables integrated with distinct keys')
