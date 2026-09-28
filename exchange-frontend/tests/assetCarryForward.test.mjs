import assert from 'node:assert/strict'
import { assetHistoryPoints, assetDisplayIndex, assetDisplayValue, assetDisplayLine } from '../src/utils/assetPixelWindow.ts'
const day=86400000
const history={schemaVersion:2,basisVersion:'net_equity_v1',from:10*day,asOf:11*day,intervalMs:60000,income:'0',total:null,carryIn:{time:1,value:'-5'},points:[{time:10*day+100,value:null},{time:10*day+200,value:'0'},{time:10*day+300,value:'9'}],live:{time:11*day,value:null}}
const original=JSON.stringify(history), points=assetHistoryPoints(history)
assert.equal(assetDisplayValue(points,history.from),-5)
assert.equal(assetDisplayValue(points,history.from+150),-5)
assert.equal(assetDisplayValue(points,history.from+250),0)
assert.equal(assetDisplayValue(points,history.asOf),9)
assert.equal(assetDisplayIndex(points,history.asOf),3)
assert.deepEqual(assetDisplayLine(points,history.from,history.asOf),[
 {time:history.from,value:-5},{time:history.from+200,value:-5},{time:history.from+200,value:0},
 {time:history.from+300,value:0},{time:history.from+300,value:9},{time:history.asOf,value:9}])
assert.equal(JSON.stringify(history),original)
assert.equal(points.at(-1).value,null)
assert.equal(points[0].time,1)
assert.equal(assetDisplayValue(points,0),null)
const noEarlier=assetHistoryPoints({...history,carryIn:null,points:[{time:history.from+200,value:'9'}],live:undefined,total:'9'})
assert.equal(noEarlier[0].quality,'INFERRED_ZERO')
assert.deepEqual(assetDisplayLine(noEarlier,history.from,history.asOf),[
 {time:history.from,value:0},{time:history.from+200,value:0},{time:history.from+200,value:9},{time:history.asOf,value:9}])
assert.deepEqual(assetDisplayLine([{time:200,value:8}],0,300),[{time:200,value:8},{time:300,value:8}])
assert.deepEqual(assetDisplayLine([{time:200,value:null}],0,300),[])
assert.throws(()=>assetHistoryPoints({...history,carryIn:{time:history.from,value:'1'}}))
assert.throws(()=>assetHistoryPoints({...history,carryIn:{time:1,value:null}}))
console.log('PASS: unlimited carry, cross-window seed, NULL skip, real zero, negative equity, step geometry and unchanged raw data')
