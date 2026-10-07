import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'
import { createHomeSparklineClient, HOME_SPARKLINE_REFRESH_MS, homeSparklineScope } from '../src/utils/homeSparklineCache.ts'

const response = (mode = 'REAL', points = [1, 2, 3], at = 0) => ({ ret: 200, data: { mode, items: [{ symbol: 'EURUSD', points, status: points.length ? 'fresh' : 'empty', updatedAt: points.length ? at : null, reason: null }] } })
function fixture() {
  let time = 0, calls = 0, scope = { key: 'tenant-A|REAL', mode: 'REAL' }, state, fail = false
  const client = createHomeSparklineClient(async () => { calls++; if (fail) throw Error('offline'); return response(scope.mode, [1, 2, 3], time) }, () => scope, next => { state = next }, () => time)
  return { client, get state() { return state }, get calls() { return calls }, set time(n) { time = n }, set scope(s) { scope = s }, set fail(v) { fail = v } }
}
test('0/299/300 seconds and same-bucket concurrent requests', async () => {
  const f = fixture()
  await Promise.all(Array.from({ length: 30 }, () => f.client.refresh(['EURUSD'])))
  assert.equal(f.calls, 1); assert.equal(f.state.items.EURUSD.updatedAt, 0)
  f.time = 299_000; await f.client.refresh(['EURUSD']); assert.equal(f.calls, 1)
  f.time = 300_000; await f.client.refresh(['EURUSD']); assert.equal(f.calls, 2); assert.equal(f.state.items.EURUSD.updatedAt, 300_000)
  assert.equal(HOME_SPARKLINE_REFRESH_MS, 300_000)
})
test('failure uses stale/empty and never retries before 300 seconds', async () => {
  const f = fixture(); await f.client.refresh(['EURUSD']); f.time = 300_000; f.fail = true
  await f.client.refresh(['EURUSD']); assert.equal(f.state.items.EURUSD.status, 'stale'); assert.equal(f.state.items.EURUSD.updatedAt, 0)
  await f.client.refresh(['EURUSD']); assert.equal(f.calls, 2)
  f.scope = { key: 'tenant-B|REAL', mode: 'REAL' }; await f.client.refresh(['EURUSD'])
  assert.equal(f.state.items.EURUSD.status, 'empty'); assert.deepEqual(f.state.items.EURUSD.points, [])
})

test('historical snapshots survive a reload and a timed-out first request without crossing scopes', async () => {
  const originalStorage = globalThis.localStorage
  const saved = new Map()
  globalThis.localStorage = { getItem: key => saved.get(key) || null, setItem: (key, value) => saved.set(key, value) }
  try {
    const context = () => ({ key: 'tenant-A|REAL', mode: 'REAL' })
    const first = createHomeSparklineClient(async () => response('REAL', [157, 158, 159], 123), context, () => {}, () => 0)
    await first.refresh(['EURUSD'])
    let state
    const restored = createHomeSparklineClient(async () => { throw Error('timeout') }, context, next => { state = next }, () => 300_000)
    assert.deepEqual(state.items.EURUSD.points, [157, 158, 159])
    assert.equal(state.items.EURUSD.status, 'stale')
    await restored.refresh(['EURUSD'])
    assert.deepEqual(state.items.EURUSD.points, [157, 158, 159])
    assert.equal(state.items.EURUSD.updatedAt, 123)
    for (const key of ['tenant-B|REAL', 'tenant-A|DEMO']) {
      const isolated = createHomeSparklineClient(async () => { throw Error('timeout') }, () => ({ key, mode: key.endsWith('DEMO') ? 'DEMO' : 'REAL' }), next => { state = next })
      await isolated.refresh(['EURUSD'])
      assert.deepEqual(state.items.EURUSD.points, [])
    }
  } finally { globalThis.localStorage = originalStorage }
})

test('empty snapshots preserve the last valid historical graph and its original time', async () => {
  let state, time = 0, points = [1, 2, 3]
  const client = createHomeSparklineClient(async () => response('REAL', points, time), () => ({ key: 'empty-test|REAL', mode: 'REAL' }), next => { state = next }, () => time)
  await client.refresh(['EURUSD'])
  time = 300_000; points = []
  await client.refresh(['EURUSD'])
  assert.deepEqual(state.items.EURUSD.points, [1, 2, 3])
  assert.equal(state.items.EURUSD.status, 'stale')
  assert.equal(state.items.EURUSD.updatedAt, 0)
})
test('blocked browser storage still preserves snapshots in memory after timeout', async () => {
  const originalStorage = globalThis.localStorage
  globalThis.localStorage = { getItem() { throw Error('blocked') }, setItem() { throw Error('quota') } }
  try {
    let state, time = 0
    const client = createHomeSparklineClient(async () => {
      if (time) throw Error('timeout')
      return response('REAL', [1, 2, 3], 123)
    }, () => ({ key: 'blocked|REAL', mode: 'REAL' }), next => { state = next }, () => time)
    await client.refresh(['EURUSD'])
    time = 300_000; await client.refresh(['EURUSD'])
    assert.deepEqual(state.items.EURUSD.points, [1, 2, 3])
    assert.equal(state.items.EURUSD.status, 'stale')
    assert.equal(state.items.EURUSD.updatedAt, 123)
  } finally { globalThis.localStorage = originalStorage }
})
test('mode and tenant switch discard delayed responses', async () => {
  let resolveOld, scope = { key: 'A|REAL', mode: 'REAL' }, state, calls = 0
  const client = createHomeSparklineClient(() => ++calls === 1 ? new Promise(resolve => { resolveOld = resolve }) : Promise.resolve(response('DEMO', [8, 9], 20)), () => scope, s => { state = s }, () => 0)
  const old = client.refresh(['EURUSD']); scope = { key: 'B|DEMO', mode: 'DEMO' }; await client.refresh(['EURUSD'])
  resolveOld(response('REAL')); await old; assert.equal(state.scope, 'B|DEMO'); assert.deepEqual(state.items.EURUSD.points, [8, 9])
})
test('unknown rows and invalid values never become a live/database fallback', async () => {
  let state
  const client = createHomeSparklineClient(async () => ({ ret: 200, data: { mode: 'REAL', items: [{ symbol: 'EURUSD', points: [0, -1, NaN, 2, '9'], status: 'fresh', updatedAt: 12 }, { symbol: 'SECRET', points: [99] }] } }), () => ({ key: 'A', mode: 'REAL' }), s => { state = s }, () => 0)
  await client.refresh(['EURUSD', 'EMPTY']); assert.deepEqual(state.items.EURUSD.points, [2]); assert.equal(state.items.EMPTY.status, 'empty'); assert.equal(state.items.SECRET, undefined)
  let wrong
  const mode = createHomeSparklineClient(async () => response('DEMO'), () => ({ key: 'A', mode: 'REAL' }), s => { wrong = s }, () => 0)
  await mode.refresh(['EURUSD']); assert.equal(wrong.items.EURUSD.status, 'empty')
})
test('host, actor and gateway separate client namespace', () => {
  globalThis.localStorage = { getItem: () => JSON.stringify({ tenantId: 1, id: 2 }) }
  assert.notEqual(homeSparklineScope('/api', 'a.test'), homeSparklineScope('/api', 'b.test'))
  assert.notEqual(homeSparklineScope('/api', 'a.test'), homeSparklineScope('/demo-api', 'a.test'))
  const old = homeSparklineScope('/api', 'a.test'); localStorage.getItem = () => JSON.stringify({ tenantId: 2, id: 2 }); assert.notEqual(old, homeSparklineScope('/api', 'a.test'))
})
test('reachable Home uses only snapshot preview and preserves live prices/T04 actions', () => {
  const source = readFileSync(new URL('../src/views/Home.vue', import.meta.url), 'utf8')
  assert.match(source, /loadHomeSparklineSnapshot/); assert.match(source, /HOME_SPARKLINE_REFRESH_MS/)
  assert.doesNotMatch(source, /fetchBatchKlines|getSparklineData|parseSparklineData|30000/)
  assert.match(source, /<MessageHeaderActions \/>/); assert.match(source, /marketStore.getPrice/); assert.match(source, /marketStore.subscribeSymbols/)
  assert.match(source, /data-sparkline-status/); assert.match(source, /data-sparkline-updated-at/)
})
