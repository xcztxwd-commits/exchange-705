import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import * as mobile from '../src/utils/tradeValidation.ts'
import * as desktop from '../../exchange-pc/src/utils/tradeValidation.ts'

test('optional protection is current input, not enabled state or a retained draft', () => {
  for (const { optionalPrice, protectionError, validIncrement } of [mobile, desktop]) {
    for (const value of ['', ' ', null, undefined]) assert.equal(optionalPrice(value), null)
    for (const [tp, sl] of [['', ''], [110, ''], ['', 90], [110, 90]]) {
      assert.equal(protectionError('BUY', 100, optionalPrice(tp), optionalPrice(sl)), false)
    }
    assert.equal(protectionError('SELL', 100, 90, 110), false)
    assert.equal(protectionError('SELL', 100, 110, null), true)
    assert.equal(protectionError('BUY', 100, null, 110), true)
    assert.equal(protectionError('BUY', 100, optionalPrice('bad'), null), true)
    assert.equal(protectionError('BUY', 100, 0, null), true)
    assert.equal(validIncrement(110.001, .01), false)
    assert.equal(validIncrement(110.01, .01), true)
    let draft = 110
    assert.equal(optionalPrice(draft), 110)
    draft = ''
    assert.equal(optionalPrice(draft), null)
  }
})

test('routed forms omit duplicate editing and history uses real times', () => {
  const read = path => readFileSync(new URL(path, import.meta.url), 'utf8')
  const trade = read('../src/views/Trade.vue'), pc = read('../../exchange-pc/src/views/DesktopTrade.vue')
  assert.doesNotMatch(trade, /takeProfitEnabled|stopLossEnabled|Review side, quantity and costs/)
  const confirmation = trade.slice(trade.indexOf('<TradeSheet :open="showConfirm"'))
  assert.doesNotMatch(confirmation, /v-model="(?:takeProfit|stopLoss)"/)
  assert.doesNotMatch(pc, /useTakeProfit|useStopLoss/)
  const orders = read('../src/views/Orders.vue')
  assert.doesNotMatch(orders, /<select|order\.openTime \|\| order\.createdAt/)
  assert.doesNotMatch(pc, /order\.openTime \|\| order\.createdAt/)
  assert.match(orders, /v-if="isHistory" class="card-footer"/)
})
