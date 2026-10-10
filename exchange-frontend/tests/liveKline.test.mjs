import assert from 'node:assert/strict'
import { test } from 'node:test'
import { matchingLiveKline } from '../src/utils/marketWebSocket.ts'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
const ts = createRequire(import.meta.url)('typescript')
const at = Date.now(), minute = Math.floor(at/60000)*60000
const quote = { price:105, timestamp:at, fetchedAt:at, committedAt:at, epoch:'tenant-a', quoteVersion:10, change24h:0, changePct24h:0, controlHistory:true }
const row = { epoch:'tenant-a',quoteVersion:10,updatedAt:at,interval:'1m',pending:false,bars:[{timestamp:minute,open:100,high:110,low:99,close:105,volume:0}] }
test('live candles require exact publication, time coverage and final close even with protected history', () => {
  assert.equal(matchingLiveKline(row,quote),true)
  for (const changed of [{quoteVersion:9},{epoch:'tenant-b'},{updatedAt:at-1},{bars:[{...row.bars[0],close:104}]},{bars:[{...row.bars[0],timestamp:minute-60000}]}])
    assert.equal(matchingLiveKline({...row,...changed},quote),false)
  assert.equal(matchingLiveKline(row,{...quote,quoteVersion:11}),false)
  assert.equal(matchingLiveKline({...row,interval:'5m'},quote),minute%300000===0)
})
test('store uses committed versions before provider timestamps; stale events cannot regress price or OHLC authority', () => {
  const source=readFileSync(new URL('../src/store/market.ts',import.meta.url),'utf8')
  const code=source.slice(source.indexOf('  let activeQuoteEpoch'),source.indexOf('  const getQuoteStatus'))
  const statuses={value:{}}, apply=new Function('quoteStatusMap','normalizeQuote','klineDataMap',ts.transpile(code,{target:ts.ScriptTarget.ES2022})+';return recordQuoteStatus')(statuses,q=>({...q,status:'available',expiresAt:at+60000}),{value:{}})
  assert.equal(apply('TEST',quote),true)
  assert.equal(apply('TEST',{...quote,price:106,quoteVersion:11,timestamp:at-1000}),true)
  assert.equal(statuses.value.TEST.quoteVersion,11)
  assert.equal(statuses.value.TEST.committedAt,at)
  assert.equal(apply('TEST',{...quote,quoteVersion:10}),false)
})

test('HTTP with an older version fills closed reconnect gaps while preserving every existing candle and the live tail', () => {
  const source=readFileSync(new URL('../src/components/KlineChart.vue',import.meta.url),'utf8')
  const code=source.slice(source.indexOf('function applyLatestCandles('),source.indexOf('\nasync function syncLatest()'))
  const candle=(timestamp,close=100)=>({timestamp,open:99,high:110,low:90,close,volume:1})
  let data=[candle(minute-300000,105)], reloaded=0
  const initial=data[0], context={chart:{getDataList:()=>data,getVisibleRange:()=>({from:0}),convertToPixel:()=>({x:0})},
    realtime:()=>assert.fail('stale open candles must not be delivered'),market:{quoteStatusMap:{TEST:{controlHistory:true}}},props:{symbol:'TEST'},
    reloadOrderedBars:bars=>{data=bars;reloaded++},empty:{value:false},pendingHistory:null,historyWaiting:{value:false},manuallyScrolled:false}
  const apply=new Function(...Object.keys(context),ts.transpile(code,{target:ts.ScriptTarget.ES2022})+';return applyLatestCandles')(...Object.values(context))
  apply(Array.from({length:6},(_,i)=>candle(minute-300000+i*60000)),false,true)
  assert.equal(data[0],initial)
  assert.deepEqual(data.map(b=>b.timestamp),Array.from({length:5},(_,i)=>minute-300000+i*60000))
  assert.equal(data.at(-1).timestamp,minute-60000); assert.equal(reloaded,1)
  data.push(candle(minute,105));const current=data.at(-1)
  apply([candle(minute-60000,1),candle(minute,1)],false,true)
  assert.equal(data.at(-1),current);assert.equal(current.close,105);assert.equal(data.at(-2).close,100)
})
