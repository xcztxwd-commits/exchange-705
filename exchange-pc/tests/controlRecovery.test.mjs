import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { test } from 'node:test'
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript')
for (const app of ['exchange-pc', 'exchange-frontend']) {
  const { normalizeQuote } = await import(`../../${app}/src/utils/marketWebSocket.ts`)
  const store = readFileSync(new URL(`../../${app}/src/store/market.ts`, import.meta.url), 'utf8')
  const record = store.slice(store.indexOf('  let activeQuoteEpoch'), store.indexOf('  const getQuoteStatus'))
  test(`${app}: recovery/source transition retains revision and rejects old recovery responses`, () => {
    const quoteStatusMap = { value: {} }
    const apply = new Function('quoteStatusMap', 'normalizeQuote', ts.transpile(record, { target: ts.ScriptTarget.ES2022 }) + '; return recordQuoteStatus')(quoteStatusMap, normalizeQuote)
    const now = Date.now(), base = { price: 105, timestamp: now, fetchedAt: now, available: true, status: 'available', marketRevision: 1, controlTaskId: 'one', controlHistory: true }
    for (const state of ['RUNNING','WAITING_SOURCE','RECOVERING','SOURCE']) {
      const q = { ...base, controlState: state, controlSourceResumed: state === 'SOURCE', controlHistoryRevision: 'one:' + state }
      assert.equal(apply('TEST', q), true)
      assert.equal(quoteStatusMap.value.TEST.controlHistoryRevision, q.controlHistoryRevision)
    }
    assert.equal(apply('TEST',{ ...base,controlState:'RECOVERING',controlSourceResumed:false }),false)
  })
  test(`${app}: publication revision clears every cached period only for the current symbol`, () => {
    const source = readFileSync(new URL(`../../${app}/src/components/KlineChart.vue`, import.meta.url),'utf8')
    const start = source.indexOf('watch(() => market.quoteStatusMap[props.symbol]?.controlHistoryRevision')
    assert.ok(start >= 0)
    const end = source.indexOf('watch(() => market.quoteStatusMap[props.symbol], replayQuote)',start)
    const block = ts.transpile(source.slice(start,end), {target:ts.ScriptTarget.ES2022})
    const market={quoteStatusMap:{},klineDataMap:{TEST_1m:[1],TEST_5m:[2],TEST_1h:[3],OTHER_1m:[4]}}
    let callback, resets=0
    new Function('watch','market','props','resetMarket',block)((get,changed)=>{callback=changed},market,{symbol:'TEST'},()=>resets++)
    callback('first',undefined);assert.equal(resets,0)
    callback('first','first');assert.equal(resets,0)
    callback('published','first');assert.equal(resets,1)
    assert.deepEqual(market.klineDataMap,{OTHER_1m:[4]})
  })
}
