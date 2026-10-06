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
const { mergeColumns, moveColumn, legacyColumnIds, parseColumnPreferences, assertPreferencesSaved } = exports
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
// Captured production responses (only presentation IDs, no user/business data).
const legacyColumns = [
  { id: 'phone', label: '手机号', legacyIds: ['手机号'] },
  { id: 'annualIncome', label: '年收入', legacyIds: ['年收入'] },
  { id: 'loginLocation', label: '登录IP / 地区', legacyIds: ['登录IP / 地区'] },
  { id: 'userType', label: '用户类型', legacyIds: ['用户类型'] },
  { id: 'actions', label: '操作', legacyIds: ['操作'] },
].map(c => ({ ...c, visible: true, fixed: '' }))
const captured = [
  { id: 'ç”¨æˆ·ç±»åž‹', visible: true, fixed: 'left' },
  { id: 'æ‰‹æœºå�·', visible: true, fixed: '' },
  { id: 'å¹´æ”¶å…¥', visible: false, fixed: '' },
  { id: 'ç™»å½•IP / åœ°åŒº', visible: true, fixed: '' },
  { id: 'æ“�ä½œ', visible: true, fixed: 'right' },
]
const canonical = [
  { id: 'userType', visible: true, fixed: 'left' },
  { id: 'phone', visible: true, fixed: '' },
  { id: 'annualIncome', visible: false, fixed: '' },
  { id: 'loginLocation', visible: true, fixed: '' },
  { id: 'actions', visible: true, fixed: 'right' },
]
const preferences = list => list.map(({ id, visible, fixed }) => ({ id, visible, fixed }))
assert.deepEqual(preferences(mergeColumns(legacyColumns, parseColumnPreferences(captured))), canonical)
assert.deepEqual(preferences(mergeColumns(legacyColumns, canonical)), canonical)
for (const c of legacyColumns) for (const id of legacyColumnIds(c.label)) {
  const recovered = mergeColumns(legacyColumns, [{ id, visible: true, fixed: 'right' }])
  assert.equal(recovered[0].id, c.id); assert.equal(recovered[0].fixed, 'right')
}
// English IDs survive label translations; exact IDs outrank historical aliases.
const translated = legacyColumns.map(c => ({ ...c, label: c.id }))
assert.deepEqual(preferences(mergeColumns(translated, canonical)), canonical)
for (const entries of [[captured[2], canonical[2]], [canonical[2], captured[2]]]) {
  const exact = { ...canonical[2], visible: true, fixed: 'left' }
  const result = mergeColumns(legacyColumns, entries.map(c => c.id === 'annualIncome' ? exact : c))
  assert.deepEqual(preferences(result)[0], exact)
}
const ambiguous = ['a', 'b'].map(id => ({ id, label: id, legacyIds: ['重复'], visible: true, fixed: '' }))
assert.ok(mergeColumns(ambiguous, [{ id: '重复', visible: false, fixed: 'left' }]).every(c => c.visible && c.fixed === ''))
assert.deepEqual(mergeColumns(legacyColumns.filter(c => c.id !== 'annualIncome'), captured).map(c => c.id), ['userType', 'phone', 'loginLocation', 'actions'])
assert.equal(mergeColumns(legacyColumns, [{ id: 'unknown', visible: false, fixed: 'left' }])[0].fixed, '')
const hiddenDefaults = [{ id: 'one', label: 'one', visible: false, fixed: '' }]
assert.equal(mergeColumns(hiddenDefaults, [])[0].visible, true)
assert.equal(hiddenDefaults[0].visible, false, 'Do not mutate default definitions')
assert.deepEqual(parseColumnPreferences([]), [])
assert.deepEqual(parseColumnPreferences(canonical), canonical)
for (const invalid of [null, {}, { success: true, data: [] }, [null], ['id'],
  [{ id: '', visible: true, fixed: '' }], [{ id: 'x'.repeat(201), visible: true, fixed: '' }],
  [{ id: 'x', visible: 'true', fixed: '' }], [{ id: 'x', visible: true, fixed: false }],
  [{ id: 'x', visible: true, fixed: '', actorId: 12 }], [canonical[0], canonical[0]],
  Array.from({ length: 151 }, (_, i) => ({ id: String(i), visible: true, fixed: '' }))]) {
  assert.throws(() => parseColumnPreferences(invalid), /列配置响应无效/)
}
assert.doesNotThrow(() => assertPreferencesSaved({ success: true }))
for (const invalid of [undefined, null, [], {}, { success: false }, { success: 'true' }])
  assert.throws(() => assertPreferencesSaved(invalid), /服务端未确认保存成功/)
const keys = [], files = [], sites = []
function walk(dir) { for (const entry of fs.readdirSync(dir, { withFileTypes: true })) { const p = path.join(dir, entry.name); if (entry.isDirectory()) walk(p); else if (p.endsWith('.vue')) files.push(p) } }
walk(fileURLToPath(new URL('../src', import.meta.url)))
for (const file of files.sort()) {
  const text = fs.readFileSync(file, 'utf8')
  assert.ok(!/<el-table[\s>]/.test(text), `Unconverted table: ${file}`)
  const ast = parse(text).descriptor.template?.ast
  function visit(node, columnIds) {
    if (node.tag === 'admin-table') {
      columnIds = new Set()
      const key = node.props.find(p => p.name === 'table-key' || (p.name === 'bind' && p.arg?.content === 'table-key'))
      assert.ok(key, 'Missing table-key: ' + file)
      if (key.name === 'table-key') keys.push(key.value.content)
      sites.push({ file: path.relative(fileURLToPath(new URL('..', import.meta.url)), file).replaceAll('\\', '/'), binding: key.name === 'table-key' ? 'table-key' : ':table-key', key: key.name === 'table-key' ? key.value.content : key.exp.content })
    }
    if (node.tag === 'el-table-column' && columnIds) {
      const field = name => node.props.find(p => p.type === 6 && p.name === name || p.type === 7 && p.name === 'bind' && p.arg?.content === name)
      const identity = field('column-key') || field('prop')
      assert.ok(identity, `Missing stable column-key/prop: ${file}:${node.loc.start.line}`)
      if (identity.type === 6) {
        const id = identity.value?.content
        assert.match(id, /^[A-Za-z0-9_.-]{1,200}$/, `Non-ASCII column identity: ${file}`)
        assert.ok(!columnIds.has(id), `Duplicate column identity ${id}: ${file}`)
        columnIds.add(id)
      } else assert.ok(identity.exp?.content, `Missing dynamic column identity: ${file}`)
    }
    for (const child of node.children || []) visit(child, columnIds)
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
assert.ok(component.includes('parseColumnPreferences(result)') && component.includes('assertPreferencesSaved(result)'))
assert.ok(component.includes('ready.value ? h(ElTable') && component.includes('legacyIds: [legacyId]'))
const independent = fs.readFileSync(new URL('../control/main.ts', import.meta.url), 'utf8')
assert.ok(independent.includes('/control/table-preferences/') && !independent.includes('pinia') && !independent.includes('useAuthStore'))
console.log(`PASS: captured mojibake/legacy migration, stable IDs, merge/order/visibility/fixed, response/save validation, identity cancellation, two credential domains, ${sites.length} reviewed table sites`)
