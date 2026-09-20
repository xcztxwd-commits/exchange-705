import { test } from 'node:test'
import assert from 'node:assert/strict'

for (const app of ['exchange-pc', 'exchange-frontend']) {
  const { indicatorCatalog, normalizePreferences, validParameters, validTimezone } = await import('../../' + app + '/src/utils/chartPreferences.ts')
  test(app + ': chart preferences validate persisted input and preserve multiple studies', () => {
    const value = normalizePreferences({ indicators: ['MA', 'BOLL', 'MACD', 'RSI', 'MA', 'unknown'], timezone: 'Asia/Shanghai', scale: 'logarithm', grid: false, upColor: 'bad', parameters: { MACD: [12, 26, 9], RSI: [-1], BOLL: [20, 2.5] } })
    assert.deepEqual(value.indicators, ['MA', 'BOLL', 'MACD', 'RSI'])
    assert.equal(value.timezone, 'Asia/Shanghai')
    assert.equal(value.scale, 'logarithm')
    assert.equal(value.grid, false)
    assert.equal(value.upColor, '#26a69a')
    assert.deepEqual(value.parameters, { MACD: [12, 26, 9], BOLL: [20, 2.5] })
    assert.equal(normalizePreferences({ timezone: 'not/a/timezone' }).timezone, '')
    assert.deepEqual(normalizePreferences({ indicators: [] }).indicators, [])
    assert.ok(validTimezone('UTC'))
    assert.ok(!validParameters('MACD', [30, 20, 9]))
    assert.ok(!validParameters('SAR', [30, 2, 20]))
    for (const item of indicatorCatalog) assert.ok(validParameters(item.name, item.params), item.name)
  })
}
