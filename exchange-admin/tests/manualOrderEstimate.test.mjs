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
const quantity = { driver: 'QUANTITY', input: '2', side: 'BUY', leverage: '100' }
const simple = { quotes: { openPrice: '99.5', closePrice: '100', openRate: '1', closeRate: '1' }, lotSize: '1', feePerLot: '0.01', walletBefore: '0' }
assert.equal(manualOrderEstimate(quantity, simple).netText, '0.98')
assert.equal(manualOrderEstimate({ ...quantity, side: 'SELL' }, simple).netText, '-1.02')
for (const input of ['2', '+2', '2.', '2.00000000000000000', '2e0', '200e-2']) assert.equal(manualOrderEstimate({ ...quantity, input }, simple).netText, '0.98', input)
assert.equal(manualOrderEstimate({ ...quantity, input: '.5' }, simple).netText, '0.245')
for (const input of ['', '0', '-1', '1.001', 'NaN', 'Infinity', '1e1000000', '1e-17', '10000000000000000']) assert.equal(manualOrderEstimate({ ...quantity, input }, simple), null, input)
assert.equal(manualOrderEstimate(quantity, { ...simple, quotes: { ...simple.quotes, closePrice: '99.5' } }).netText, '-0.02')
assert.equal(manualOrderEstimate(quantity, { ...simple, feePerLot: '0.5' }).netText, '0')
assert.equal(manualOrderEstimate(quantity, { ...simple, quotes: { ...simple.quotes, closeRate: '0.5' } }).netText, '0.48')
assert.equal(manualOrderEstimate(quantity, { ...simple, quotes: { ...simple.quotes, closeRate: undefined } }), null)
const precise = { ...simple, quantityStep: '1', minOrderQuantity: '1', lotSize: '1.0000000000000001', feePerLot: '0', quotes: { openPrice: '1', closePrice: '1.0000000000000001', openRate: '1', closeRate: '0.5000000000000001' } }
assert.equal(manualOrderEstimate({ ...quantity, input: '9999999999999999' }, precise).netText, '0.5000000000000001', 'Do not truncate unit profit before multiplying lots')
assert.equal(manualOrderEstimate({ ...quantity, input: '9999999999999999', side: 'SELL' }, precise).netText, '-0.5000000000000001')
console.log('manualOrderEstimate checks passed')
