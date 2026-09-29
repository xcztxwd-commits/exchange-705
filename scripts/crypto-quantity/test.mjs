import assert from 'node:assert/strict'
import { manualOrderEstimate } from '../../exchange-admin/src/utils/manualOrderEstimate.ts'
let checks=0
for(const app of ['exchange-pc','exchange-frontend']) {
 const m=await import(`../../${app}/src/utils/contract.ts`)
 for(const [asset,price,step,quantities] of [['BTC',80000,.001,[.001,.002,.01,.1,10]],['ETH',2500,.001,[.004,.005,.01,.1,10]],['SOL',100,.01,[.1,.11,.15,1,10]]]) {
  const spec={quantityUnitType:'BASE_ASSET',baseCurrency:asset,quantityStep:step,minOrderQuantity:step,minOrderNotional:10}
  assert.equal(m.quantityUnit(spec),asset);checks++
  for(const q of quantities)for(const lev of [1,5,10,20,50,100])for(const side of ['BUY','SELL']) {
   const expected=q*price/lev
   assert.ok(Math.abs(m.contractMargin(q,1,price,lev)-expected)<1e-9);assert.ok(m.validQuantity(q,spec))
   const basis={quotes:{openPrice:price,closePrice:price+100,openRate:1,closeRate:1},lotSize:1,feePerLot:.03,walletBefore:100000,quantityStep:step,minOrderQuantity:step,minOrderNotional:10}
   const manual=manualOrderEstimate({driver:'QUANTITY',input:String(q),side,leverage:String(lev)},basis)
   assert.ok(manual);assert.ok(Math.abs(manual.margin-expected)<1e-9);assert.ok(Math.abs(manual.fee-q*.03)<1e-12)
   assert.ok(Math.abs(m.calculateContractProfit({quantity:q,lotSize:1,openPrice:price,side,status:'OPEN'},price+100)-(side==='BUY'?1:-1)*100*q)<1e-9)
   checks+=6
  }
 }
 assert.equal(m.quantityFromAllocation(8.0003,100,800,.03,.001,.001,80000,10),.01)
 assert.equal(m.quantityFromAllocation(8.000299999,100,800,.03,.001,.001,80000,10),.009)
 assert.equal(m.quantityFromAllocation(.09,100,25,.03,.001,.001,2500,10),0)
 for(const percent of [0,1,25,50,100]) {
  const q=m.quantityFromAllocation(8.0003,percent,800,.03,.001,.001,80000,10)
  assert.ok(q*800.03<=8.0003*percent/100+1e-14);checks++
 }
 for(const bad of [NaN,Infinity,-1,0,'abc',.0009,.0015])assert.equal(m.validQuantity(bad,{quantityStep:.001,minOrderQuantity:.001}),false)
 assert.equal(m.validQuantity(.015,{quantityStep:.005,minOrderQuantity:.005}),true)
 assert.equal(m.validQuantity(.016,{quantityStep:.005,minOrderQuantity:.005}),false)
 assert.equal(m.displayFee(.00003),'<0.01');assert.equal(m.displayFee(0),'0.00')
 assert.equal(m.quantityUnit({quantity:null,lotSize:1000}), '手')
 assert.equal(m.calculateContractProfit({quantity:.01,lotSize:1000,openPrice:80000,side:'BUY',status:'OPEN'},80100),1000)
 assert.equal(m.quantityFromAllocation(8,100,NaN,.03,.001),0)
 checks+=17
}
const basis={quotes:{openPrice:80000,closePrice:80100,openRate:1,closeRate:1},lotSize:1,feePerLot:'0.03',walletBefore:'8.0003',quantityStep:'0.001',minOrderQuantity:'0.001',minOrderNotional:10}
assert.equal(manualOrderEstimate({driver:'PERCENT',input:'100',side:'BUY',leverage:'100'},basis).quantity,.01)
assert.equal(manualOrderEstimate({driver:'QUANTITY',input:'0.0015',side:'BUY',leverage:'100'},basis),null)
assert.equal(manualOrderEstimate({driver:'NET',input:'0.159952',side:'BUY',leverage:'100'},basis).quantity,.002)
assert.equal(manualOrderEstimate({driver:'PERCENT',input:'100',side:'BUY',leverage:'100'},{...basis,walletBefore:'8.0002999999999999'}).quantity,.009)
checks+=4
console.log(JSON.stringify({status:'PASS',checks,liveOrders:0}))
