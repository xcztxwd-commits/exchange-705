import assert from 'node:assert/strict'
import { manualOrderEstimate } from '../src/utils/manualOrderEstimate.ts'
const basis = { quotes: { openPrice: 86226, closePrice: 86538.61, openRate: 1, closeRate: 1 }, lotSize: 1000, feePerLot: 30, walletBefore: 15586.89 }
const form = { driver: 'PERCENT', input: '111', side: 'BUY', leverage: '100' }
const value = manualOrderEstimate(form, basis)
assert.equal(value.quantity, 0.02)
assert.equal(value.profit, 6252.2)
assert.equal(value.fee, 0.6)
assert.equal(value.net, 6251.6)
assert.equal(Number((basis.walletBefore + value.net).toFixed(2)), 21838.49)
assert.notEqual(manualOrderEstimate({ ...form, input: '200' }, basis).net, value.net)
assert.equal(manualOrderEstimate({ ...form, input: '' }, basis), null)
assert.equal(manualOrderEstimate({ ...form, side: '' }, basis), null)
assert.equal(manualOrderEstimate({ ...form, leverage: '0' }, basis), null)
assert.equal(manualOrderEstimate({ ...form, driver: 'NET', input: '6251.6' }, basis).quantity, 0.02)
assert.ok(manualOrderEstimate({ ...form, side: 'SELL' }, basis).net < 0)
assert.equal(manualOrderEstimate(form, null), null)
console.log('manualOrderEstimate checks passed')
