import assert from 'node:assert/strict'
import { generationConstraints, generationRequest } from '../src/utils/manualOrderGeneration.ts'

const form = { userId: 1, symbol: 'FIXTUREUSD', timezone: 'UTC', walletEnabled: false, historyEnabled: false, side: 'SELL', leverage: '100' }
const fields = ['net', 'openLocal', 'closeLocal', 'side', 'leverage', 'quantity', 'percent', 'closePrice']
const known = ['80', '2026-09-20T10:00', '2026-09-20T14:00', 'BUY', '10', '10', '12', '112']
for (let mask = 0; mask < 256; mask++) {
  const inputs = generationConstraints()
  fields.forEach((field, bit) => { if (mask & (1 << bit)) inputs[field] = known[bit] })
  const check = () => {
    const payload = generationRequest(form, inputs)
    fields.forEach((field, bit) => assert.equal(payload[field === 'net' ? 'targetNet' : field === 'closePrice' ? 'targetClosePrice' : field], inputs[field] || undefined, `mask ${mask}, ${field}`))
    assert.equal(payload.walletEnabled, false); assert.equal(payload.historyEnabled, false)
  }
  check()
  // Computed results never become constraints; removing each input releases exactly that field.
  fields.forEach(field => { inputs[field] = ''; check() })
  fields.forEach((field, bit) => { inputs[field] = known[bit]; check() })
  Object.assign(inputs, generationConstraints()); check()
}
for (const value of ['NaN', 'Infinity', '-Infinity', '1e999', 'text']) {
  const inputs = generationConstraints(); inputs.net = value
  assert.throws(() => generationRequest(form, inputs))
}
console.log('PASS 256 UI payload masks; remove/re-enter/clear all; generated defaults not pinned; nonfinite rejected')
