import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { test } from 'node:test'
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript')
for (const app of ['exchange-pc', 'exchange-frontend']) {
  const { normalizeQuote } = await import(`../../${app}/src/utils/marketWebSocket.ts`)
  const source = readFileSync(new URL(`../../${app}/src/store/market.ts`, import.meta.url), 'utf8')
  const record = source.slice(source.indexOf('  let activeQuoteEpoch'), source.indexOf('  const getQuoteStatus'))
  test(`${app}: held execution lease preserves old price timestamps and expires on transport loss`, () => {
    const now = Date.now(), old = now - 60000
    const held = { price: 105, timestamp: old, fetchedAt: old, sourceTimestamp: old,
      sourceAvailable: false, controlState: 'HOLDING', available: true, status: 'available',
      executionExpiresAt: now + 15000, expiresAt: now + 15000 }
    assert.equal(normalizeQuote(held, now).status, 'available')
    assert.equal(normalizeQuote(held, now).timestamp, old)
    assert.equal(normalizeQuote(held, now).expiresAt, now + 15000)
    assert.equal(normalizeQuote(held, now + 15000).status, 'stale')
    assert.equal(normalizeQuote({ ...held, available: false, status: 'unavailable' }, now).status, 'unavailable')
    assert.equal(normalizeQuote({ ...held, executionExpiresAt: undefined }, now).status, 'stale')
    assert.equal(normalizeQuote({ ...held, price: 0 }, now), null)
  })
  test(`${app}: simulation switches accept older external quotes but reject outdated mode responses`, () => {
    const quoteStatusMap = { value: {} }
    const apply = new Function('quoteStatusMap', 'normalizeQuote', ts.transpile(record, { target: ts.ScriptTarget.ES2022 }) + '; return recordQuoteStatus')(quoteStatusMap, normalizeQuote)
    const now = Date.now(), base = { price: 100, fetchedAt: now, timestamp: now, status: 'available' }
    assert.equal(apply('TEST', { ...base, marketRevision: 1 }), true)
    assert.equal(apply('TEST', { ...base, marketRevision: 2, simulated: true, simulationSession: now }), true)
    assert.equal(quoteStatusMap.value.TEST.simulated, true)
    assert.equal(apply('TEST', { ...base, marketRevision: 1 }), false)
    assert.equal(apply('TEST', { ...base, timestamp: now - 60000, status: 'stale', marketRevision: 3 }), true)
    assert.equal(quoteStatusMap.value.TEST.simulationSession, undefined)
    assert.equal(quoteStatusMap.value.TEST.status, 'stale')
    assert.equal(apply('TEST', { ...base, marketRevision: 2, simulated: true, simulationSession: now }), false)
    assert.equal(apply('TEST', { ...base, marketRevision: 4, simulated: true, simulationSession: now + 1 }), true)
    assert.equal(apply('TEST', { status: 'unavailable', marketRevision: 5 }), false)
    assert.equal(quoteStatusMap.value.TEST.simulationSession, undefined)
    assert.equal(quoteStatusMap.value.TEST.status, 'unavailable')
  })
  test(`${app}: source return preserves its original timestamp and rejects delayed control packets`, () => {
    const quoteStatusMap = { value: {} }
    const apply = new Function('quoteStatusMap', 'normalizeQuote', ts.transpile(record, { target: ts.ScriptTarget.ES2022 }) + '; return recordQuoteStatus')(quoteStatusMap, normalizeQuote)
    const now = Date.now(), controlled = { price: 120, timestamp: now, fetchedAt: now - 10000, status: 'unavailable', available: false,
      marketRevision: 1, controlHistory: true, controlTaskId: 'task', controlState: 'WAITING_SOURCE' }
    assert.equal(apply('TEST', controlled), true)
    assert.equal(apply('TEST', { ...controlled, price: 90, timestamp: now - 8000, controlSourceResumed: true }), true)
    assert.equal(quoteStatusMap.value.TEST.timestamp, now - 8000)
    assert.equal(quoteStatusMap.value.TEST.status, 'unavailable')
    assert.equal(apply('TEST', controlled), false)
    assert.equal(apply('TEST', { ...controlled, controlState: 'RUNNING' }), false)
  })

}
