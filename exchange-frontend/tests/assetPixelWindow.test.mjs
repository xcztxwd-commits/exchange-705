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
assert.deepEqual(assetPriceTicks([0,243541.37]), [0,100000,200000,300000])
for (const values of [[0], [0,.01], [0,.07], [100], [-5,200], [-100,-20], [0,99999999]]) {
  const ticks=assetPriceTicks(values)
  assert.equal(ticks.length,4)
  assert.ok(ticks[0]<=Math.min(0,...values))
  assert.ok(ticks[3]>=Math.max(...values))
  assert.equal(new Set(ticks.map(n=>n.toFixed(2))).size,4)
  assert.ok(Math.abs((ticks[1]-ticks[0])-(ticks[3]-ticks[2]))<1e-8)
}
const zeroThenFunded=[{time:1000,value:0},{time:2000,value:0},{time:3000,value:50}]
assert.equal(zeroThenFunded[assetPointIndex(zeroThenFunded,2500,2000)].value,0)
assert.equal(zeroThenFunded[assetPointIndex(zeroThenFunded,3000,2000)].value,50)
console.log('PASS: four rounded price ticks, zero history and funded transition')
const original = JSON.stringify(points)
assert.equal(assetDisplayValue(points, 0, 2000), 0)
assert.equal(assetDisplayValue(points, 5000, 2000), 0)
assert.equal(assetDisplayValue(points, 1500, 2000), 10)
assert.equal(assetDisplayValue(points, 10000, 2000), 30)
assert.equal(JSON.stringify(points), original)
console.log('PASS: display-only zero fill preserves original history')
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
