// Read-only snapshot audit. 100x is the user's comparison assumption, not a broker's leverage promise.
import assert from 'node:assert/strict'
import {readFileSync,writeFileSync} from 'node:fs'
import {resolve} from 'node:path'
const root=resolve(import.meta.dirname,'../..')
const out=resolve(root,process.argv[2]||'reports/all-instruments-20260928')
const read=name=>JSON.parse(readFileSync(resolve(out,name),'utf8').replace(/^\uFEFF/,''))
const symbols=read('symbols-api.json'),quotes=new Map(read('quotes-api.json').map(r=>[r.symbol,r.quote?.data]))
const apps=await Promise.all(['exchange-frontend','exchange-pc'].map(async app=>({app,...await import(new URL(`../../${app}/src/utils/contract.ts`,import.meta.url))})))
const near=(a,b)=>assert.ok(Math.abs(a-b)<Math.max(1,Math.abs(b))*1e-9,`${a} != ${b}`)
const sources={Forex:'https://www.metatrader5.com/en/terminal/help/trading_advanced/margin_forex',US:'https://pepperstone.com/en/markets/shares',Crypto:'https://www.binance.com/en/support/faq/detail/360033544231'}
const dbSymbols=readFileSync(resolve(out,'symbols.tsv'),'utf8').replace(/^\uFEFF/,'').trim().split(/\r?\n/).slice(1).map(line=>line.split('\t')[0]).sort()
assert.deepEqual(symbols.map(s=>s.symbol).sort(),dbSymbols,'Every database symbol must be audited, including disabled instruments')
let assertions=0
const rows=symbols.map(s=>{
 const q=quotes.get(s.symbol);assert.ok(q&&q.price>0,`Missing snapshot ${s.symbol}`)
 const fx=s.sourceCategory==='Forex',size=fx?100000:Number(s.lotSize),fee=fx?7:Number(s.feeMultiplier),price=Number(q.price)
 const baseRate=fx?(s.baseCurrency==='USD'?1:s.quoteCurrency==='USD'?price:NaN):undefined
 const rate=Number(q.quoteToUsdRate),expectedMargin=size*(fx?baseRate:price*rate)/100
 assert.equal(Number(s.maxLeverage),100,`${s.symbol}: do not silently override lower limits`)
 for(const app of apps) {
  assert.equal(app.DEFAULT_LEVERAGE,100)
  for(const lots of [.01,.1,1,2.37])for(const lev of [1,50,100])for(const side of ['BUY','SELL']) {
   const margin=app.contractMargin(lots,size,price,lev,rate,baseRate)
   near(margin,expectedMargin*lots*100/lev)
   const close=price*1.01,settlementRate=fx&&s.baseCurrency==='USD'?1/close:rate
   const pnl=app.calculateContractProfit({status:'OPEN',side,quantity:lots,lotSize:size,leverage:lev,openPrice:price},close,settlementRate)
   near(pnl,(side==='BUY'?1:-1)*(close-price)*lots*size*settlementRate)
   const qty=app.quantityFromAllocation(10000,100,expectedMargin,fee)
   assert.ok(qty*expectedMargin+qty*fee<=10000+1e-8)
   assertions+=3
  }
 }
 return {symbol:s.symbol,sourceCategory:s.sourceCategory,base:s.baseCurrency,quote:s.quoteCurrency,price,sourceTimestamp:q.sourceTimestamp,
  quoteAvailableAtCapture:q.available,unitsPerLotBefore:Number(s.lotSize),unitsPerLotAfter:size,leverage:100,
  marginBefore:Number(s.lotSize)*price*rate/100,marginAfter:expectedMargin,
  roundTripCommissionBefore:Number(s.feeMultiplier),roundTripCommissionAfter:fee,
  matchedExposureBrokerExample:fx?7:s.sourceCategory==='US'?Math.max(size*.02,.02)*2:size*price*.0005*2,
  brokerExampleNote:fx?'Confirmed user-selected Raw policy':s.sourceCategory==='US'?'Pepperstone $0.02/share/side; comparison only, not adopted':'Binance futures taker 0.05%/side example; CURRENT FEED IS SPOT; not adopted',
  formulaStatus:fx?'FIXED_STANDARD_FX':'FORMULA_OK_CUSTOM_SPEC_RETAINED',source:sources[s.sourceCategory]}
})
const result={capturedNotLive:true,symbolCount:rows.length,assertions,status:'PASS',liveOrdersPlaced:0,rows}
writeFileSync(resolve(out,'all-symbol-comparison.json'),JSON.stringify(result,null,2))
const money=n=>n.toLocaleString('en-US',{minimumFractionDigits:2,maximumFractionDigits:6,useGrouping:false})
writeFileSync(resolve(out,'all-symbol-table.md'),[
 '| 品种 | 类型 | 快照价格 | 每手单位（修复后） | 1手/100x保证金（旧） | 1手/100x保证金（修复后） | 往返费（修复后） |',
 '|---|---|---:|---:|---:|---:|---:|',
 ...rows.map(r=>`| ${r.symbol} | ${r.sourceCategory} | ${money(r.price)} | ${r.unitsPerLotAfter} | ${money(r.marginBefore)} | ${money(r.marginAfter)} | ${money(r.roundTripCommissionAfter)} |`)
].join('\n'))
console.log(JSON.stringify({symbolCount:rows.length,assertions,status:'PASS',liveOrdersPlaced:0}))
