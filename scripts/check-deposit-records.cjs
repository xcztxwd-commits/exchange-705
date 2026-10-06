// Run: node scripts/check-deposit-records.cjs
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('../exchange-frontend/node_modules/typescript')
const root = path.join(__dirname, '..')
const source = fs.readFileSync(path.join(root, 'exchange-frontend/src/utils/depositRecords.ts'), 'utf8')
const exportsObject = {}
new Function('exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText)(exportsObject)
const label = exportsObject.depositRecordTypeLabel
const translate = key => ({ depositTypeDigital: '暗号資産', depositTypeBank: '銀行振込' })[key]
const manual = Object.freeze({ type: 'manual', network: 'MANUAL', currency: 'USD', amount: 100, source: 'ADMIN_MANUAL' })
assert.equal(label(manual, translate), '銀行振込')
assert.equal(label({ type: 'bank', network: 'BANK', source: 'ADMIN_MANUAL' }, translate), '銀行振込', 'Admin receipt entries retain their actual bank channel')
assert.equal(label({ type: 'digital' }, translate), '暗号資産')
assert.equal(label({ type: 'MANUAL' }, translate), '銀行振込')
assert.equal(label({ type: 'unexpected' }, translate), '—', 'Unknown types must not become bank deposits')
assert.equal(label({}, translate), '—')
for (const project of ['exchange-frontend', 'exchange-pc']) {
  for (const name of ['Deposit.vue', 'DepositRecords.vue']) {
    const view = fs.readFileSync(path.join(root, project, 'src/views', name), 'utf8')
    assert.ok(view.includes('depositRecordTypeLabel(record, localeStore.t)'), project + '/' + name)
    assert.ok(view.includes("{{ record.currency || 'USD' }}"), project + '/' + name)
    assert.ok(!view.includes('record.network || record.unit'), 'Internal network markers are not currency units')
  }
}
const advanced = fs.readFileSync(path.join(root, 'exchange-frontend/src/advanced/views/DepositRecords.vue'), 'utf8')
assert.ok(advanced.includes('depositRecordTypeLabel(record, localeStore.t)'))
assert.ok(advanced.includes(`v-if="record.type==='digital'"`), 'Only digital deposits display a network')
console.log('PASS manual/bank/digital labels, unknown types, currency units and advanced network visibility')
