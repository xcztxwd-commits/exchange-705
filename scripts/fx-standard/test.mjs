// Offline standard-FX regression. Uses the actual PC/mobile arithmetic; no live orders.
import assert from 'node:assert/strict'
const rates = { USD:1, EUR:1.1, GBP:1.25, AUD:.65, NZD:.6, CAD:1/1.4, CHF:1/.9, JPY:1/157.2, CNH:1/7.2, SGD:1/1.35, HKD:1/7.8, MXN:1/18, ZAR:1/18, NOK:1/10, SEK:1/10, PLN:1/4, DKK:1/7, TRY:1/35, THB:1/35 }
let checks=0
for (const app of ['exchange-frontend','exchange-pc']) {
  const {contractMargin,quantityFromAllocation}=await import(new URL(`../../${app}/src/utils/contract.ts`,import.meta.url))
  for(const baseRate of Object.values(rates))for(const quoteRate of Object.values(rates))for(const q of [.01,.1,1,2.37])for(const lev of [1,10,50,100]) {
    const expected=q*100000/lev*baseRate
    // Deliberately stale quote conversion cannot affect base-currency FX margin.
    const actual=contractMargin(q,100000,baseRate/quoteRate,lev,quoteRate*.99,baseRate)
    assert.ok(Math.abs(actual-expected)<1e-7);checks++
  }
  assert.equal(contractMargin(1,100000,157.2,100,.0063,1),1000)
  assert.equal(contractMargin(.01,100000,157.2,100,.0063,1),10)
  assert.equal(contractMargin(1,100000,1.1,100,1,1.1),1100)
  assert.ok(Number.isNaN(contractMargin(1,100000,170,100,.0063,NaN)))
  assert.equal(quantityFromAllocation(1007,100,1000,7),1)
  assert.equal(quantityFromAllocation(1006.99,100,1000,7),.99)
  // Non-FX keeps its previous contract calculation.
  assert.equal(contractMargin(1,1000,150,100,.0063),9.45)
  checks+=7
}
const {manualOrderEstimate}=await import(new URL('../../exchange-admin/src/utils/manualOrderEstimate.ts',import.meta.url))
const basis={quotes:{openPrice:157.2,closePrice:157.2,openRate:.0063,closeRate:.0063,marginRate:1},lotSize:100000,feePerLot:7,walletBefore:1007}
const manual=manualOrderEstimate({driver:'QUANTITY',input:'1',side:'BUY',leverage:'100'},basis)
assert.equal(manual.margin,1000);assert.equal(manual.net,-7)
assert.equal(manualOrderEstimate({driver:'PERCENT',input:'100',side:'BUY',leverage:'100'},basis).quantity,1)
checks+=3
console.log(JSON.stringify({checks,status:'PASS',liveOrdersPlaced:0}))
