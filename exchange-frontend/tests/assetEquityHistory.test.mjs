import assert from 'node:assert/strict'
import { assetHistoryPoints, assetDisplayValue, assetPriceTicks } from '../src/utils/assetPixelWindow.ts'
const history = { schemaVersion: 2, basisVersion: 'net_equity_v1', from: 0, asOf: 100000, intervalMs: 60000, income: '0', total: null, points: [{time: 1000,value:'-10'},{time:2000,value:null}], live:{time:100000,value:null} }
const points=assetHistoryPoints(history)
assert.equal(points[0].quality,'INFERRED_ZERO')
assert.equal(points[2].value,null)
assert.equal(assetDisplayValue(points,0,60000),0)
assert.equal(assetDisplayValue(points,1000,60000),-10)
assert.equal(assetDisplayValue(points,99000,60000),-10,'unavailable carries the last real value for display only')
assert.equal(assetHistoryPoints({...history,points:[{time:1000,value:'0'}],live:undefined})[0].value,0)
assert.equal(assetHistoryPoints({...history,points:[{time:0,value:'7'}],live:undefined})[0].quality,'COMPLETE','real range-opening value needs no inferred zero')
assert.deepEqual(assetHistoryPoints({...history,points:[],live:undefined}),[])
assert.throws(()=>assetHistoryPoints({...history,basisVersion:'wallet_balance_v1'}))
assert.throws(()=>assetHistoryPoints({...history,points:[{time:100001,value:'1'}]}))
assert.throws(()=>assetHistoryPoints({...history,points:[{time:1000,value:'not-money'}]}))
assert.ok(assetPriceTicks([-200,-20])[0]<=-200)
console.log('PASS: NULL/zero/negative equity, distinct live, version isolation and future observations')
