import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { settledShareOrder, shareReturn, shareNumber, historyWindow, coveredCandles, recentShareChart, drawSharePoster, shareCopy, shareTemplates, shareNeedsChart, shareBackgrounds } from './orderShare.ts'

// Only import location and admin price precision may differ across the three clients.
const rendererSource = url => readFileSync(url, 'utf8').replace(/\r\n/g, '\n')
  .replace(/from '[^']*shareTemplateDesign\.ts'/, "from './shareTemplateDesign.ts'")
  .replace("import { formatPrice } from './formatPrice.ts'\n", '')
  .replace('// Order data stays shared with mobile and PC; admin price display uses three decimals.', '// Kept identical in mobile, PC and admin builds.')
  .replace(/^  const price = \((?:n|value): number\) => new Intl\.NumberFormat\('en-US', \{ maximumFractionDigits: 16 \}\)\.format\((?:n|value)\)\n/gm, '')
  .replace(/\bprice\(order\./g, 'formatPrice(order.')

const raw = { id: 3391, symbol: 'USDJPY', status: 'CLOSED', side: 'SELL', profit: -50,
  openPrice: 161.24, closePrice: 161.305, margin: 1000, quantity: 78, fee: 3,
  openTime: '2026-06-19T12:00:00+08:00', closeTime: '2026-06-19T12:53:00+08:00', userId: 'private-user' }
const order = settledShareOrder(raw, 'contract')
assert.equal(order.buy, false)
assert.equal(order.profit, -50)
assert.equal(order.currency, 'USD')
assert.equal(settledShareOrder({ ...raw, direction: 'UP', amount: 200 }, 'option').currency, 'USD')
assert.equal(shareReturn(order), -5)
assert.equal(shareReturn({ ...order, margin: 0 }), null)
assert.equal(shareReturn({ ...order, kind: 'option', amount: 200 }), -25)
assert.equal(shareNumber(-0.00001, 2, true), '0.00')
assert.equal('userId' in order, false)
assert.throws(() => settledShareOrder({ ...raw, profit: null }, 'contract'))
assert.throws(() => settledShareOrder({ ...raw, status: 'OPEN' }, 'contract'))
assert.throws(() => settledShareOrder({ ...raw, side: 'unknown' }, 'contract'))
const window = historyWindow(order, Date.parse('2026-06-19T06:00:00Z'))
assert.equal(historyWindow({ ...order, openTime: '2026-09-28 03:00:00', closeTime: '2026-09-28 03:02:00' }, Date.parse('2026-09-28T04:00:00Z')).start, Date.parse('2026-09-28T03:00:00Z'))
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
    assert.equal(canvas.height, ({ light: 1080, gold: 675, racing: 785, aurora: 1350, receipt: 1665, journal: 720 })[template] || 1440)
    if (mode === 'rate' || mode === 'none') for (const privateValue of ['78.00ロット', '78.00 ロット', '1000.00', '3.00']) assert.ok(!printed.includes(privateValue), `${template} must hide ${privateValue}`)
  }
}
for (const template of ['referenceGold', 'referenceWhite', 'referenceTerminal']) {
  assert.throws(() => drawSharePoster(canvas, order, { ...options, template }, shareCopy('en'), 'DEMO', 'UTC', undefined, undefined, {}))
  printed.length = 0
  drawSharePoster(canvas, { ...order, kind: 'option', amount: 200, closePrice: 1e-8 }, { ...options, template, mode: 'both' }, shareCopy('en'), 'DEMO', 'UTC', undefined, chart, {})
  assert.ok(!printed.includes(shareCopy('en').investment))
  assert.ok(printed.some(value => value.includes('-25.00%')))
  assert.ok(printed.some(value => value.includes('0.00000001')))
}
assert.throws(() => drawSharePoster(canvas, order, { ...options, template: 'referenceGold' }, shareCopy('en'), 'DEMO', 'UTC', undefined, chart))
for (const template of shareTemplates) {
  if (shareNeedsChart(template)) assert.throws(() => drawSharePoster(canvas, order, { ...options, template }, shareCopy('en'), 'DEMO', 'UTC', undefined, undefined, {}))
  if (shareBackgrounds[template]) assert.throws(() => drawSharePoster(canvas, order, { ...options, template }, shareCopy('en'), 'DEMO', 'UTC', undefined, chart))
}
assert.equal(coveredCandles([{ ...rows[0], volume: 0 }, ...rows.slice(1)], window)[0].volume, 0)
assert.equal(rendererSource(new URL('./orderShare.ts', import.meta.url)), rendererSource(new URL('../../../exchange-pc/src/utils/orderShare.ts', import.meta.url)))
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

// Every drawable word must originate in the selected locale, never a fixed-language label.
const { posterLocales, shareLanguage } = await import('./orderShareLocales.ts')
assert.equal(shareLanguage('ja-JP'), 'ja')
assert.equal(shareLanguage('ZH_cn'), 'zh-TW')
assert.equal(shareLanguage('fr-CA'), 'fr')
assert.equal(shareLanguage('unknown'), 'en')
for (const locale of Object.keys(posterLocales)) {
  const copy = shareCopy(locale)
  const dictionary = Object.values(copy)
  for (const template of shareTemplates) {
    for (const mode of ['amount', 'rate', 'both', 'none']) {
      printed.length = 0
      drawSharePoster(canvas, { ...order, leverage: 10 }, { ...options, template, mode }, copy, 'DEMO', 'UTC', undefined, recent, {})
      assert.ok(printed.some(text => text.includes(copy.leverage) && text.includes('10×')), `${locale}/${template}: leverage is required`)
      for (const text of printed) {
        let remainder = text.replaceAll('USDJPY', '').replaceAll('USD/JPY', '').replaceAll('USD / JPY', '').replaceAll('DEMO', '').replaceAll('UTC', '').replaceAll('Test', '').replaceAll('USD', '')
        for (const word of [...dictionary].sort((a, b) => b.length - a.length)) remainder = remainder.replaceAll(word, '')
        remainder = remainder.replaceAll('1m', '').replace(/[0-9\s.,:·()+#%/*—→×T-]/g, '')
        assert.equal(remainder, '', `${locale}/${template}/${mode} unlocalized text: ${text}`)
      }
      if (locale !== 'ja') assert.ok(!printed.some(text => /[\u3040-\u30ff]/.test(text)), `${locale}/${template} leaked Japanese`)
    }
  }
}
assert.equal(readFileSync(new URL('./orderShareLocales.ts', import.meta.url), 'utf8'), readFileSync(new URL('../../../exchange-pc/src/utils/orderShareLocales.ts', import.meta.url), 'utf8'))
assert.equal(shareBackgrounds.referenceWhite, 'reference-white-neutral.png')
console.log('Localization: 19 locales × 16 templates × 4 display modes passed')

// Privacy cannot be re-enabled by old clients; emphasis and sign colors are consistent in all templates.
const draws = []
context.fillText = (text, x, y) => { printed.push(text); draws.push({ text, x, y, font: context.font, color: context.fillStyle }) }
for (const template of shareTemplates) for (const focus of ['amount', 'rate']) for (const profit of [125.5, -125.5, 0, 123456789012345.67, -123456789012345.67]) {
  draws.length = 0
  const specimen = { ...order, id: 'PRIVATE-ORDER-987654', profit, margin: 1234, fee: 9.87 }
  const copy = shareCopy('ja')
  drawSharePoster(canvas, specimen, { ...options, template, mode: 'both', focus, orderId: true }, copy, 'DEMO', 'UTC', undefined, recent, {})
  const amount = draws.find(d => d.text === shareNumber(profit, 2, true))
  const rate = draws.find(d => d.text === `${shareNumber(shareReturn(specimen), 2, true)}%`)
  assert.ok(amount && rate, `${template}/${focus}: both metrics present`)
  const primary = focus === 'rate' ? rate : amount, secondary = focus === 'rate' ? amount : rate
  assert.ok(primary.y < secondary.y, `${template}/${focus}: primary above secondary`)
  assert.ok(parseFloat(primary.font.split(' ')[1]) > parseFloat(secondary.font.split(' ')[1]), `${template}/${focus}: larger primary`)
  for (const metric of [amount, rate]) {
    assert.ok((profit > 0 ? ['#17804c', '#69d8a3'] : profit < 0 ? ['#c43d4b', '#ff828b'] : ['#546571', '#b2c1ce']).includes(metric.color), `${template}: signed color`)
  }
  for (const forbidden of [specimen.id, copy.margin, copy.investment, copy.fee, copy.orderId, copy.orderNumber, '1,234.00', '1234.00', '9.87', '世界', '集中', '実行'])
    assert.ok(!draws.some(d => d.text.includes(forbidden)), `${template}: must never print ${forbidden}`)
  assert.equal(copy.contractRate, copy.rate)
  assert.equal(copy.optionRate, copy.rate)
}
for (const template of shareTemplates) {
  draws.length = 0
  drawSharePoster(canvas, { ...order, margin: 0 }, { ...options, template, mode: 'both', focus: 'rate' }, shareCopy('en'), 'DEMO', 'UTC', undefined, recent, {})
  assert.ok(draws.find(d => d.text === '-50.00').y < draws.find(d => d.text === '—').y, 'Unavailable return falls back to P&L')
  assert.ok(draws.some(d => d.text === '—' && ['#546571', '#b2c1ce'].includes(d.color)))
}
assert.equal(shareCopy('ja').entry, '新規約定価格')
assert.equal(shareCopy('ja').exit, '決済約定価格')
assert.equal(shareCopy('ja').pnl, '実現損益')
assert.equal(rendererSource(new URL('./orderShare.ts', import.meta.url)), rendererSource(new URL('../../../exchange-admin/src/utils/orderShare.ts', import.meta.url)))
console.log('Redesign: all 16 templates, both emphasis modes, positive/negative/zero, privacy and Japanese terminology passed')
