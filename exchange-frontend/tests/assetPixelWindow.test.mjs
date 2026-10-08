// Run with Node 24: node tests/assetPixelWindow.test.mjs
import assert from 'node:assert/strict'
import { assetPointIndex, assetColumnTime, assetPriceTicks, assetDisplayValue, assetScrubTime, assetHighlight } from '../src/utils/assetPixelWindow.ts'
const now = 1790500000000
for (const days of [1, 7, 30, 365]) {
  const from = now - days * 86400000
  const columns = Array.from({ length: 68 }, (_, i) => assetColumnTime(from, now, i))
  assert.equal(columns.length, 68)
  assert.equal(columns[0], from)
  assert.equal(columns[67], now)
  const recent = [{ time: now - 60000, value: 100 }, { time: now, value: 120 }]
  assert.equal(assetPointIndex(recent, columns[0], 180000), -1)
  assert.equal(assetPointIndex(recent, columns[66], 180000), -1)
  assert.equal(assetPointIndex(recent, columns[67], 180000), 1)
}
const points = [{ time: 1000, value: 10 }, { time: 2000, value: 20 }, { time: 10000, value: 30 }]
assert.equal(assetPointIndex(points, 999, 2000), -1)
assert.equal(assetPointIndex(points, 1500, 2000), 0)
assert.equal(assetPointIndex(points, 5000, 2000), -1)
assert.equal(assetPointIndex(points, 10000, 2000), 2)
assert.equal(assetPointIndex([], 10000, 2000), -1)
console.log('PASS: fixed rolling windows, equal columns, genuine timestamps and missing history')
const fundedTicks=assetPriceTicks([0,243541.37])
assert.equal(fundedTicks[0],0)
assert.equal(fundedTicks[3],243541.37,'large jumps use the real bounds without padding')
assert.deepEqual(assetPriceTicks([]), [0,1,2,3])
for (const values of [[0], [0,.01], [0,.07], [100], [-5,200], [-100,-20], [0,99999999], [252257.18,258190.32], [90.29,90.34], [1000000,1000000.01], [10000000000,10000000000.01], [258000,258000]]) {
  const ticks=assetPriceTicks(values)
  assert.equal(ticks.length,4)
  assert.ok(ticks[0]<=Math.min(...values))
  assert.ok(ticks[3]>=Math.max(...values))
  assert.equal(new Set(ticks).size,4)
  assert.ok(Math.abs((ticks[1]-ticks[0])-(ticks[3]-ticks[2]))<Math.max(1e-8,Math.abs(ticks[3])*Number.EPSILON*4))
}
for (const values of [[252257.18,258190.32], [90.29,90.34], [1000000,1000000.01], [10000000000,10000000000.01]]) {
  const ticks=assetPriceTicks(values)
  assert.ok(ticks[0]>0,'positive small fluctuations do not force a zero baseline')
  assert.ok((Math.max(...values)-Math.min(...values))/(ticks[3]-ticks[0])*128>=30,'even large balances retain a visible fluctuation height')
}
for (const values of [[100,130],[100,70],[100.1,130.13],[100.1,70.07],[100,250,50],[-100,-70],[0,100],[100,0]]) {
  const ticks=assetPriceTicks(values)
  assert.equal(ticks[0],Math.min(...values),'large swings have no lower margin')
  assert.equal(ticks[3],Math.max(...values),'large swings have no upper margin')
}
const normalTicks=assetPriceTicks([100,129.99])
assert.ok(normalTicks[0]<100 && normalTicks[3]>129.99,'a swing below 30% keeps its padded axis')
const centBelowTicks=assetPriceTicks([100.1,130.12])
assert.ok(centBelowTicks[0]<100.1 && centBelowTicks[3]>130.12,'one cent below the threshold still retains padding')
const zeroThenFunded=[{time:1000,value:0},{time:2000,value:0},{time:3000,value:50}]
assert.equal(zeroThenFunded[assetPointIndex(zeroThenFunded,2500,2000)].value,0)
assert.equal(zeroThenFunded[assetPointIndex(zeroThenFunded,3000,2000)].value,50)
console.log('PASS: adaptive money ticks, large balances, cent changes, flat/empty/negative ranges and true zero history')
const original = JSON.stringify(points)
assert.equal(assetDisplayValue(points, 0, 2000), null)
assert.equal(assetDisplayValue(points, 5000, 2000), 20)
assert.equal(assetDisplayValue(points, 1500, 2000), 10)
assert.equal(assetDisplayValue(points, 10000, 2000), 30)
assert.equal(JSON.stringify(points), original)
console.log('PASS: display-only carry forward preserves original history')
assert.equal(assetScrubTime(20,20,360,1000,25000),1000)
assert.equal(assetScrubTime(380,20,360,1000,25000),25000)
assert.equal(assetScrubTime(200,20,360,1000,25000),13000)
assert.equal(assetScrubTime(-100,20,360,1000,25000),1000)
assert.equal(assetScrubTime(999,20,360,1000,25000),25000)
assert.equal(assetHighlight(12000,13000),1)
assert.equal(assetHighlight(13000,13000),1)
assert.equal(assetHighlight(14000,13000),.16)
assert.equal(assetHighlight(14000,null),1)
console.log('PASS: edge-to-edge scrubbing and bright-before/dim-after selection')
