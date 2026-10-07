import assert from 'node:assert/strict'
import { minuteCandidates, restoreRange, aggregateHistory } from '../src/utils/historyRestore.ts'
const rows = [0, 1, 2].map(n => ({ timestamp: 1791364800000 + n * 60000, open_price: 158, high_price: 159, low_price: 157, close_price: 158.5 }))
assert.deepEqual(restoreRange(rows, 1, 1, 60000), { from: rows[1].timestamp, to: rows[1].timestamp })
assert.deepEqual(restoreRange(rows, 2, 0, 60000), { from: rows[0].timestamp, to: rows[2].timestamp })
assert.equal(restoreRange(rows, 0, 0, 300000).to - rows[0].timestamp, 240000)
assert.equal(aggregateHistory(rows, 300000).length, 1)
assert.equal(minuteCandidates('2026-03-08T02:30', 'America/New_York').length, 0)
assert.deepEqual(minuteCandidates('2026-11-01T01:30', 'America/New_York').map(row => row.offset).sort(), ['-04:00', '-05:00'])
assert.equal(minuteCandidates('2026-10-07T17:21', 'Asia/Singapore')[0].timestamp, Date.parse('2026-10-07T09:21:00Z'))
console.log('history restore range, aggregation and DST checks passed')
