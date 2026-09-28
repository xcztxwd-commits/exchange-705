import assert from 'node:assert/strict'
import { generationConstraints, generationRequest, generationConstraintError } from '../src/utils/manualOrderGeneration.ts'

// Generated preview values may fill form, but empty input fields remain movable.
const form = { userId: 1, symbol: 'BTCUSDT', timezone: 'Asia/Shanghai', side: 'BUY', leverage: '100', openLocal: '2026-09-20T10:00', closeLocal: '2026-09-21T10:00', openOffset: '+08:00', closeOffset: '+08:00', walletEnabled: false, historyEnabled: false }
const values = generationConstraints()
let payload = generationRequest(form, values)
for (const key of ['side', 'leverage', 'quantity', 'percent', 'targetNet', 'openLocal', 'closeLocal']) assert.equal(payload[key], undefined, `${key} must be movable when its input is blank`)
values.net = '100'
payload = generationRequest(form, values)
assert.equal(payload.targetNet, '100')
values.openLocal = form.openLocal; values.openOffset = form.openOffset
payload = generationRequest(form, values)
assert.equal(payload.openLocal, form.openLocal)
assert.equal(payload.openOffset, '+08:00')
assert.equal(payload.closeLocal, undefined)
values.quantity = '1'; values.percent = '120'; values.leverage = '50'; values.side = 'SELL'
payload = generationRequest(form, values)
assert.equal(payload.quantity, '1'); assert.equal(payload.percent, '120'); assert.equal(payload.leverage, '50'); assert.equal(payload.side, 'SELL')
values.net = '0'
assert.equal(generationRequest(form, values).targetNet, '0')
values.net = '-100.0000000000000001'
assert.equal(generationRequest(form, values).targetNet, values.net, 'never round decimal payload through Number')
values.net = '  '
assert.equal(generationRequest(form, values).targetNet, undefined)
values.net = 'bad'
assert.throws(() => generationRequest(form, values), /数值无效/)
values.net = ''
assert.throws(() => generationRequest({ ...form, userId: null }, values), /用户/)
values.openLocal = ''; values.openOffset = ''
assert.equal(generationRequest(form, values).openLocal, undefined)
console.log('PASS: filled input constrains generation; blank input remains movable after preview')
assert.equal(generationConstraintError({quantity: 1, percent: 120}, values), '')
assert.match(generationConstraintError({quantity: 2, percent: 120}, values), /填写的手数/)
assert.match(generationConstraintError({quantity: 1, percent: 121}, values), /填写的仓位比例/)
assert.equal(generationConstraintError({quantity: 1, percent: 120.01}, values), '')
assert.match(generationConstraintError({quantity: 1, percent: null}, values), /填写的仓位比例/)
values.quantity = ''; values.percent = ''
assert.equal(generationConstraintError({quantity: 2, percent: 121}, values), '')
console.log('PASS: preview checks only filled quantity and allocation inputs')
