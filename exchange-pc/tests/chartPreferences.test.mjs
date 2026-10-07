import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { runInNewContext } from 'node:vm'
import { computed, nextTick, ref, watch } from 'vue'

for (const app of ['exchange-pc', 'exchange-frontend']) {
  const { indicatorCatalog, normalizePreferences, validParameters, validTimezone } = await import('../../' + app + '/src/utils/chartPreferences.ts')
  test(app + ': chart defaults follow saved language and react to language switches without changing candle data', async () => {
    const source = readFileSync(new URL(`../../${app}/src/components/KlineChart.vue`, import.meta.url), 'utf8')
    const setup = source.slice(source.indexOf('const preferenceKey ='), source.indexOf('const count ='))
    const choose = source.slice(source.indexOf('function chooseTimezone('), source.indexOf('async function takeSnapshot('))
    const device = source.match(/class="local-timezone" @click="([^"]+)"/)[1]
    const reset = source.match(/class="reset-preferences" @click="([^"]+)"/)[1].replace(/\b(preferences|timezone)\b/g, '$1.value')
    const timezoneSource = readFileSync(new URL('../../exchange-frontend/src/utils/displayTimezone.ts', import.meta.url), 'utf8')
    const languageWatch = source.match(/watch\(preferredTimeLocale[^\n]+/)[0]
    const zoneWatch = source.match(/watch\(timezone[^\n]+/)[0]
    const load = (stored = null, browserTimezone = 'America/New_York', language = '') => runInNewContext(
      stripTypeScriptTypes(timezoneSource + '\nconst getSystemTimezone = () => getDisplayTimezone();\n' + setup + choose + languageWatch + '\n' + zoneWatch).replace(/^import [^\n]+\n/gm, '').replace(/^export /gm, '') + `; ({ chooseTimezone, useDeviceTimezone: () => { ${device} }, reset: () => { ${reset} }, changeLanguage: value => { preferredTimeLocale.value = value }, state: () => ({ preferences: preferences.value, timezone: timezone.value }) })`,
      {
        normalizePreferences, validTimezone, computed, ref, watch,
        localStorage: { getItem: key => key === 'locale' ? language : JSON.stringify(stored) },
        chart: { setTimezone: value => applied.push(value) },
        Intl: { DateTimeFormat: () => ({ resolvedOptions: () => ({ timeZone: browserTimezone }) }) },
      },
    )
    const applied = []
    const chart = load()
    assert.equal(chart.state().timezone, 'America/New_York')
    assert.equal(chart.state().preferences.timezone, '')
    chart.chooseTimezone('Asia/Tokyo')
    assert.equal(chart.state().timezone, 'Asia/Tokyo')
    assert.equal(load(chart.state().preferences, 'Asia/Singapore').state().timezone, 'Asia/Tokyo')
    chart.useDeviceTimezone()
    assert.equal(chart.state().timezone, 'America/New_York')
    assert.equal(chart.state().preferences.timezone, '')
    assert.equal(load(chart.state().preferences, 'Asia/Singapore').state().timezone, 'Asia/Singapore')
    chart.chooseTimezone('UTC')
    chart.reset()
    assert.equal(chart.state().timezone, 'America/New_York')
    assert.equal(chart.state().preferences.timezone, '')
    assert.equal(load({ timezone: 'not/a/timezone' }).state().timezone, 'America/New_York')
    assert.equal(load(null, '').state().timezone, 'UTC')
    chart.chooseTimezone('UTC')
    chart.changeLanguage('ja')
    assert.equal(chart.state().timezone, 'Asia/Tokyo')
    assert.equal(chart.state().preferences.timezone, '')
    await nextTick()
    assert.equal(applied.at(-1), 'Asia/Tokyo')
    const revisit = load({ timezone: 'Asia/Shanghai' }, 'Asia/Singapore', 'ja')
    assert.equal(revisit.state().timezone, 'Asia/Tokyo')
    assert.equal(revisit.state().preferences.timezone, '')
    revisit.chooseTimezone('UTC')
    revisit.changeLanguage('ko')
    assert.equal(revisit.state().timezone, 'Asia/Seoul')
    revisit.reset()
    assert.equal(revisit.state().timezone, 'Asia/Seoul')
  })
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
