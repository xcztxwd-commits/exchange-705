import assert from 'node:assert/strict'
import test from 'node:test'
import { orderTimestamp, profitRate, withinDays } from '../src/utils/orderView.ts'

test('order view dates use UTC backend timestamps and reject missing timestamps', () => {
  const now = Date.parse('2026-09-28T04:00:00Z')
  assert.equal(orderTimestamp('2026-09-28 03:00:00'), Date.parse('2026-09-28T03:00:00Z'))
  assert.equal(withinDays('2026-09-28 03:00:00', 7, now), true)
  assert.equal(withinDays('2026-09-20 03:00:00', 7, now), false)
  assert.equal(withinDays('', 30, now), false)
  assert.equal(withinDays('', 0, now), true)
})

test('return rate stays unavailable when margin is zero', () => {
  assert.equal(profitRate(199.82, 199.88)?.toFixed(2), '99.97')
  assert.equal(profitRate(-150, 150), -100)
  assert.equal(profitRate(10, 0), null)
})
