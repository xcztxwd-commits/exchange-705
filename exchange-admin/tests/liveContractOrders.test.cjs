// Run: node --test tests/liveContractOrders.test.cjs
const { test } = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path')
const ts = require('../node_modules/typescript'), vue = require('../node_modules/vue')
const source = fs.readFileSync(path.join(__dirname, '../src/utils/useLiveContractOrders.ts'), 'utf8')
const exportsForTest = {}
new Function('require', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText)(require, exportsForTest)
const flush = () => new Promise(resolve => setImmediate(resolve))
function fixture(initial, kind = 'contract') {
  const timers = new Map(), listeners = new Map(), requests = [], originalWindow = global.window, originalDocument = global.document
  let sequence = 0, closed = false
  global.window = { setTimeout: (run, delay) => { timers.set(++sequence, { run, delay }); return sequence }, clearTimeout: id => timers.delete(id) }
  global.document = { hidden: false, addEventListener: (name, run) => listeners.set(name, run), removeEventListener: name => listeners.delete(name) }
  const rows = vue.ref(initial), tab = vue.ref(kind), modes = vue.ref(['REAL']), loading = vue.ref(false)
  const renderer = vue.createRenderer({ createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {} })
  const app = renderer.createApp({ setup() {
    exportsForTest.useLiveContractOrders(rows, tab, modes, loading, (mode, url, body, method, signal) => new Promise((resolve, reject) => requests.push({ mode, url, body, method, signal, resolve, reject })), kind)
    return () => null
  } })
  app.mount({})
  return { rows, tab, modes, loading, requests, timers,
    async tick() { const [id, task] = timers.entries().next().value || []; if (task) { timers.delete(id); task.run(); await flush() } },
    visibility(hidden) { document.hidden = hidden; listeners.get('visibilitychange')() },
    close() { if (closed) return; closed = true; app.unmount(); global.window = originalWindow; global.document = originalDocument } }
}
const open = (id, mode = 'REAL') => ({ id, accountMode: mode, status: 'OPEN', profit: '1', netProfit: '-1', currentPrice: '150' })
test('only current-page active IDs; no overlapping polls, row replacement or reset; closure freezes', async () => {
  const s = fixture([open(1), { id: 2, status: 'CLOSED', profit: '9' }, { ...open(3), deleted: true }])
  try {
    const page = s.rows.value, row = page[0]
    await s.tick(); assert.deepEqual(s.requests[0].body, { ids: [1] }); assert.equal(s.requests[0].method, 'POST')
    assert.equal(s.timers.size, 0); await s.tick(); assert.equal(s.requests.length, 1)
    s.requests[0].resolve({ list: [{ id: 1, status: 'OPEN', liveAvailable: true, profit: '-10', netProfit: '-12', currentPrice: '149' }] }); await flush()
    assert.equal(s.rows.value, page); assert.equal(s.rows.value[0], row); assert.equal(row.profit, '-10'); assert.equal(row.netProfit, '-12')
    assert.equal(page[1].profit, '9'); assert.equal(s.timers.values().next().value.delay, 1000)
    await s.tick(); s.requests[1].resolve({ list: [{ id: 1, status: 'CLOSED', closePrice: '148', profit: '-20', netProfit: '-22', liveAvailable: true }] }); await flush()
    await s.tick(); assert.equal(s.requests.length, 2); assert.equal(s.timers.size, 0); assert.equal(row.status, 'CLOSED'); assert.equal(row.profit, '-20')
  } finally { s.close() }
})
test('page, tab, visibility and unmount cancel reads and reject late results', async () => {
  const s = fixture([open(1)])
  try {
    await s.tick(); const old = s.requests[0], oldRow = s.rows.value[0]
    s.loading.value = true; assert.equal(old.signal.aborted, true)
    s.rows.value = [open(11)]; s.loading.value = false; await s.tick()
    assert.deepEqual(s.requests[1].body.ids, [11]); old.resolve({ list: [{ id: 1, profit: '999' }] }); await flush(); assert.equal(oldRow.profit, '1')
    s.visibility(true); assert.equal(s.requests[1].signal.aborted, true)
    s.requests[1].resolve({ list: [{ id: 11, profit: '999' }] }); await flush(); assert.equal(s.rows.value[0].profit, '1'); assert.equal(s.timers.size, 0)
    s.visibility(false); await s.tick(); s.tab.value = 'option'; assert.equal(s.requests[2].signal.aborted, true)
    s.requests[2].resolve({ list: [] }); await flush(); assert.equal(s.timers.size, 0)
    s.tab.value = 'contract'; await s.tick(); s.close(); assert.equal(s.requests[3].signal.aborted, true)
  } finally { s.close() }
})
test('same ID in REAL and DEMO is queried separately; stale quotes and errors preserve values', async () => {
  const s = fixture([open(1), open(1, 'DEMO'), { ...open(2), status: 'PENDING' }])
  try {
    s.modes.value = ['REAL', 'DEMO']; await s.tick()
    assert.deepEqual(s.requests.map(r => [r.mode, r.body.ids]), [['REAL', [1, 2]], ['DEMO', [1]]])
    s.requests[0].resolve({ list: [{ id: 1, profit: '10', netProfit: '8', liveAvailable: true }, { id: 2, status: 'OPEN', openPrice: '150', fee: '2', profit: '3', netProfit: '1', liveAvailable: true }] })
    s.requests[1].resolve({ list: [{ id: 1, profit: '-10', netProfit: '-12', liveAvailable: true }] }); await flush()
    assert.deepEqual(s.rows.value.map(r => r.profit), ['10', '-10', '3']); assert.equal(s.rows.value[2].status, 'OPEN')
    await s.tick(); s.requests[2].resolve({ list: [{ id: 1, profit: '0', netProfit: '-2', currentPrice: '0', liveAvailable: false }, { id: 2, liveAvailable: false }] }); s.requests[3].reject(Error('offline')); await flush()
    assert.deepEqual(s.rows.value.map(r => r.profit), ['10', '-10', '3']); assert.equal(s.rows.value[0].currentPrice, '150'); assert.ok(s.rows.value.every(r => r.liveAvailable === false))
    await s.tick(); const real = s.requests[4]; s.modes.value = ['DEMO']; assert.equal(real.signal.aborted, true)
    real.resolve({ list: [{ id: 1, profit: '999' }] }); s.requests[5].resolve({ list: [] }); await flush()
    assert.equal(s.rows.value[0].profit, '10'); await s.tick(); assert.equal(s.requests.at(-1).mode, 'DEMO')
  } finally { s.close() }
})
test('monitor batches across pages stay bounded and removing an order cancels late updates', async () => {
  const s = fixture(Array.from({ length: 205 }, (_, index) => open(index + 1)))
  try {
    await s.tick(); assert.deepEqual(s.requests.map(r => r.body.ids.length), [100, 100, 5])
    const removed = s.rows.value[0], pending = [...s.requests]
    s.rows.value = s.rows.value.filter(row => row.id !== 1)
    assert.ok(pending.every(r => r.signal.aborted))
    pending.forEach(r => r.resolve({ list: [{ id: 1, profit: '999' }] })); await flush()
    assert.equal(removed.profit, '1'); await s.tick()
    assert.ok(s.requests.slice(3).every(r => !r.body.ids.includes(1) && r.body.ids.length <= 100))
  } finally { s.close() }
})
test('option monitor preserves stale estimates, resumes after hiding, and freezes final settlement', async () => {
  const s = fixture([{ id: 1, status: 'TRADING', currentPrice: '150', profit: '80' }], 'option')
  try {
    await s.tick(); assert.equal(s.requests[0].url, '/admin/orders/option/live')
    s.requests[0].resolve({ list: [{ id: 1, status: 'TRADING', currentPrice: null, profit: '0', liveAvailable: false }] }); await flush()
    assert.equal(s.rows.value[0].profit, '80'); assert.equal(s.rows.value[0].currentPrice, '150')
    s.loading.value = true; assert.equal(s.timers.size, 0)
    s.loading.value = false; await s.tick()
    s.requests[1].resolve({ list: [{ id: 1, status: 'CLOSED', closePrice: '151', currentPrice: '151', profit: '63', liveAvailable: true }] }); await flush()
    await s.tick(); assert.equal(s.requests.length, 2); assert.equal(s.timers.size, 0)
    assert.equal(s.rows.value[0].profit, '63'); assert.equal(s.rows.value[0].closePrice, '151'); assert.ok(s.rows.value[0].liveUpdatedAt)
  } finally { s.close() }
})
test('pending monitor retains its last market quote when fresh quotes become unavailable', async () => {
  const s = fixture([{ ...open(1), status: 'PENDING' }])
  try {
    await s.tick()
    s.requests[0].resolve({ list: [{ id: 1, status: 'PENDING', liveAvailable: false, currentPrice: '0', profit: '0' }] }); await flush()
    assert.equal(s.rows.value[0].currentPrice, '150'); assert.equal(s.rows.value[0].profit, '1')
  } finally { s.close() }
})
