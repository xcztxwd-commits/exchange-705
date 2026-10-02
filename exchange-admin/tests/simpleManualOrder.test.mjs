import assert from 'node:assert/strict'
import { simpleConditions, simplePayload } from '../src/utils/simpleManualOrder.ts'
const form = { symbol: 'FIXTURE', userId: null, allowNetAdjustment: true, netTolerance: '5', walletEnabled: false, historyEnabled: false }
const fixed = simpleConditions()
assert.equal(simplePayload(form, fixed, {}, 'UTC').leverage, null)
assert.equal(simplePayload(form, fixed, {}, 'UTC').quantity, null)
fixed.targetNet = '5999.0000000000000001'
assert.equal(simplePayload(form, fixed, {}, 'UTC').targetNet, fixed.targetNet)
fixed.quantity = '150'
assert.equal(simplePayload(form, fixed, {}, 'UTC').quantity, '150')
assert.throws(() => simplePayload({ ...form, walletEnabled: true }, fixed, {}, 'UTC'), /未绑定/)
assert.throws(() => simplePayload({ ...form, symbol: '' }, fixed, {}, 'UTC'), /品种/)
assert.equal(simplePayload({ ...form, allowNetAdjustment: false, netTolerance: 'invalid' }, fixed, {}, 'UTC').netTolerance, '0')
fixed.quantity = 'NaN'; assert.throws(() => simplePayload(form, fixed, {}, 'UTC'), /有效数字/)
console.log('simple order payload checks passed')
