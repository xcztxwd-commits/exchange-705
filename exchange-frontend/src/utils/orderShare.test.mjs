import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { settledShareOrder, shareReturn, shareNumber, historyWindow, coveredCandles, recentShareChart, drawSharePoster, shareCopy, shareTemplates, shareNeedsChart, shareBackgrounds } from './orderShare.ts'

const raw = { id: 3391, symbol: 'USDJPY', status: 'CLOSED', side: 'SELL', profit: -50,
  openPrice: 161.24, closePrice: 161.305, margin: 1000, quantity: 78, fee: 3,
  openTime: '2026-06-19T12:00:00+08:00', closeTime: '2026-06-19T12:53:00+08:00', userId: 'private-user' }
const order = settledShareOrder(raw, 'contract')
assert.equal(order.buy, false)
assert.equal(order.profit, -50)
assert.equal(shareReturn(order), -5)
assert.equal(shareReturn({ ...order, margin: 0 }), null)
assert.equal(shareReturn({ ...order, kind: 'option', amount: 200 }), -25)
assert.equal(shareNumber(-0.00001, 2, true), '0.00')
assert.equal('userId' in order, false)
assert.throws(() => settledShareOrder({ ...raw, profit: null }, 'contract'))
assert.throws(() => settledShareOrder({ ...raw, status: 'OPEN' }, 'contract'))
assert.throws(() => settledShareOrder({ ...raw, side: 'unknown' }, 'contract'))
const window = historyWindow(order, Date.parse('2026-06-19T06:00:00Z'))
const rows = Array.from({ length: 60 }, (_, i) => ({ timestamp: window.start / 1000 + i * 60, open_price: 161.24, high_price: 162, low_price: 161, close_price: 161.3 }))
assert.ok(coveredCandles(rows, window).length > 50)
assert.throws(() => coveredCandles(rows.slice(10), window))
assert.throws(() => coveredCandles(rows.slice(0, 20), window))
const printed = []
const gradient = () => ({ addColorStop() {} })
const context = new Proxy({ createLinearGradient: gradient, createRadialGradient: gradient, measureText: text => ({ width: text.length * 10 }), fillText: text => printed.push(text) }, { get: (target, key) => target[key] || (() => {}) })
const canvas = { getContext: () => context }
const options = { template: 'light', mode: 'rate', quantity: true, capital: true, fee: true, orderId: false, openTime: false }
drawSharePoster(canvas, order, options, shareCopy('en'), 'DEMO', 'UTC')
assert.ok(printed.includes('-5.00%'))
for (const privateValue of ['78.00', '1,000.00', '3.00', '****3391', '-50.00']) assert.ok(!printed.includes(privateValue))
assert.throws(() => drawSharePoster(canvas, order, { ...options, template: 'chart' }, shareCopy('en'), 'DEMO', 'UTC'))
printed.length = 0
drawSharePoster(canvas, order, { ...options, mode: 'none' }, shareCopy('en'), 'DEMO', 'UTC')
for (const hidden of ['-5.00%', '-50.00', '78.00', '1,000.00', '3.00']) assert.ok(!printed.includes(hidden))
assert.ok(printed.includes('TRADE RECORD'))
const chart = { ...window, candles: coveredCandles(rows, window), source: 'Test' }
for (const template of shareTemplates) {
  for (const mode of ['amount', 'rate', 'both', 'none']) {
    printed.length = 0
    drawSharePoster(canvas, order, { ...options, template, mode }, shareCopy('zh-TW'), 'DEMO', 'UTC', undefined, chart, {})
    assert.equal(printed.includes('-50.00'), mode === 'amount' || mode === 'both', `${template} amount visibility`)
    assert.equal(printed.some(text => text.includes('-5.00%')), mode === 'rate' || mode === 'both', `${template} return visibility`)
    assert.equal(canvas.width, 1080)
    assert.equal(canvas.height, template === 'referenceTerminal' ? Math.round(1080 * 484 / 285) : template.startsWith('reference') ? Math.round(1080 * 1200 / 654) : 1440)
    if (mode === 'rate' || mode === 'none') for (const privateValue of ['78.00ロット', '78.00 ロット', '1000.00', '3.00']) assert.ok(!printed.includes(privateValue), `${template} must hide ${privateValue}`)
  }
}
for (const template of ['referenceGold', 'referenceWhite', 'referenceTerminal']) {
  assert.throws(() => drawSharePoster(canvas, order, { ...options, template }, shareCopy('en'), 'DEMO', 'UTC', undefined, undefined, {}))
  printed.length = 0
  drawSharePoster(canvas, { ...order, kind: 'option', amount: 200, closePrice: 1e-8 }, { ...options, template, mode: 'both' }, shareCopy('en'), 'DEMO', 'UTC', undefined, chart, {})
  assert.ok(printed.includes('投資額'))
  assert.ok(printed.some(value => value.includes('-25.00%')))
  assert.ok(printed.some(value => value.includes('0.00000001')))
}
assert.throws(() => drawSharePoster(canvas, order, { ...options, template: 'referenceGold' }, shareCopy('en'), 'DEMO', 'UTC', undefined, chart))
for (const template of shareTemplates) {
  if (shareNeedsChart(template)) assert.throws(() => drawSharePoster(canvas, order, { ...options, template }, shareCopy('en'), 'DEMO', 'UTC', undefined, undefined, {}))
  if (shareBackgrounds[template]) assert.throws(() => drawSharePoster(canvas, order, { ...options, template }, shareCopy('en'), 'DEMO', 'UTC', undefined, chart))
}
assert.equal(coveredCandles([{ ...rows[0], volume: 0 }, ...rows.slice(1)], window)[0].volume, 0)
assert.equal(readFileSync(new URL('./orderShare.ts', import.meta.url), 'utf8'), readFileSync(new URL('../../../exchange-pc/src/utils/orderShare.ts', import.meta.url), 'utf8'))
console.log('Order share: settlement, returns, privacy, history coverage and renderer checks passed')

// Old orders must still render against recent market candles, without historical coverage.
const recentRows = Array.from({ length: 100 }, (_, i) => ({ ...rows[0], timestamp: 1800000000 + i * 60 }))
const recent = recentShareChart([...recentRows.reverse(), recentRows[0], { timestamp: 'invalid' }], 'Test')
assert.equal(recent.candles.length, 80)
assert.equal(recent.candles[0].timestamp, 1800001200000)
assert.equal(recent.candles.at(-1).timestamp, 1800005940000)
assert.equal(recent.recent, true)
assert.throws(() => recentShareChart([]))
assert.throws(() => recentShareChart([{ ...rows[0], high_price: 1 }]))
const points = []
context.moveTo = (...args) => points.push(args)
context.lineTo = (...args) => points.push(args)
context.arc = (...args) => points.push(args)
context.fillRect = (...args) => points.push(args)
for (const template of shareTemplates.filter(shareNeedsChart)) {
  points.length = 0
  printed.length = 0
  drawSharePoster(canvas, { ...order, openPrice: 1000000, closePrice: 2000000 }, { ...options, template }, shareCopy('en'), 'DEMO', 'UTC', undefined, recent, {})
  assert.ok(points.flat().every(Number.isFinite), `${template} finite geometry`)
  assert.ok(points.flat().every(value => Math.abs(value) < 10000), `${template} no out-of-range execution markers`)
  if (template === 'journal' || template === 'chart') assert.ok(printed.some(value => value.includes('Recent market candles')))
}
console.log('Order share: recent candles and old-order background rendering passed')
