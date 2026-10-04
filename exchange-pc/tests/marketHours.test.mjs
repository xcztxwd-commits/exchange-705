import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFile } from 'node:fs/promises'
import ts from '../node_modules/typescript/lib/typescript.js'

for (const app of ['exchange-pc', 'exchange-frontend']) {
  test(`${app}: closure is distinct from stale/unavailable, including no-price snapshots`, async () => {
    globalThis.window = new EventTarget()
    globalThis.document = Object.assign(new EventTarget(), { visibilityState: 'visible' })
    globalThis.location = { protocol: 'https:', host: 'test.local', hostname: 'test.local', origin: 'https://test.local' }
    const { normalizeQuote } = await import(`../../${app}/src/utils/marketWebSocket.ts`)
    const now = Date.now(), expired = { price: 1.1, timestamp: now - 120000, fetchedAt: now - 120000, expiresAt: now - 60000, status: 'closed', available: false }
    assert.equal(normalizeQuote(expired, now).status, 'closed')
    assert.equal(normalizeQuote({ ...expired, status: 'stale' }, now).status, 'stale')
    assert.equal(normalizeQuote({ ...expired, status: 'unavailable' }, now).status, 'unavailable')
    assert.equal(normalizeQuote({ ...expired, status: 'available', available: true }, now).status, 'stale')
    const source = await readFile(new URL(`../../${app}/src/store/market.ts`, import.meta.url), 'utf8')
    const record = source.slice(source.indexOf('  const recordQuoteStatus ='), source.indexOf('  const getConversionRate ='))
    const status = source.slice(source.indexOf('  const getQuoteStatus ='), source.indexOf('\n  // 各交易对的最新Tick数据'))
    const map = { value: {} }
    const methods = new Function('quoteStatusMap', 'normalizeQuote', ts.transpile(`let activeQuoteEpoch;const retiredQuoteEpochs=new Set();${record}${status}`, { target: ts.ScriptTarget.ES2022 }) + '; return {recordQuoteStatus,getQuoteStatus}')(map, normalizeQuote)
    assert.equal(methods.recordQuoteStatus('EURUSD=X', { ...expired, quoteVersion: 1, epoch: 'test' }), true)
    assert.equal(methods.getQuoteStatus('EURUSD=X', now), 'closed')
    assert.equal(methods.recordQuoteStatus('NEW', { status: 'closed', quoteVersion: 1, epoch: 'test' }), false)
    assert.equal(methods.getQuoteStatus('NEW', now), 'closed')
    methods.recordQuoteStatus('EURUSD=X', { ...expired, status: 'available', available: true, quoteVersion: 2, epoch: 'test' })
    assert.equal(methods.getQuoteStatus('EURUSD=X', now), 'stale')
    methods.recordQuoteStatus('EURUSD=X', { ...expired, timestamp: now, fetchedAt: now, expiresAt: now + 60000, status: 'available', available: true, quoteVersion: 3, epoch: 'test' })
    assert.equal(methods.getQuoteStatus('EURUSD=X', now), 'available')
  })
}
