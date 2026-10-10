import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFile } from 'node:fs/promises'
import ts from '../node_modules/typescript/lib/typescript.js'
import * as Vue from 'vue'
import { compile } from '@vue/compiler-dom'
import { renderToString } from '@vue/server-renderer'

test('desktop product list renders closure instead of the percentage, and restores it after reopening', async () => {
  const source = await readFile(new URL('../src/views/DesktopTrade.vue', import.meta.url), 'utf8')
  const badge = source.match(/<div class="flex flex-col items-end w-\[25%\]">[\s\S]*?<\/div>/)[0]
  const render = new Function('Vue', compile(badge, { mode: 'function', prefixIdentifiers: true }).code)(Vue)
  for (const status of ['closed', 'available', 'stale', 'unavailable', 'closed', 'available']) {
    const html = await renderToString(Vue.createSSRApp({
      render,
      setup: () => ({
        symbol: { symbol: 'JPY=X' },
        marketStore: { getQuoteStatus: () => status, getChange24h: () => ({ changePct: 0.29 }) },
        localeStore: { t: () => '休市', text: value => value },
        getSymbolChange: () => '+0.29%',
      }),
    }))
    assert.equal(html.includes('休市'), status === 'closed')
    assert.equal(html.includes('+0.29%'), status !== 'closed')
  }
})

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
    for (const status of ['closed', 'available']) {
      methods.recordQuoteStatus('EURUSD=X', { ...expired, timestamp: now, fetchedAt: now, expiresAt: now + 60000, status, available: status === 'available', quoteVersion: 3, epoch: 'test' })
      assert.equal(methods.getQuoteStatus('EURUSD=X', now), status, 'Calendar updates do not require a new price version')
    }
  })
}
