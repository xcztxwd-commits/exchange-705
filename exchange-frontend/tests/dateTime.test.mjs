import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { test } from 'node:test'
import { runInNewContext } from 'node:vm'
import { nextTick, ref, watch } from 'vue'

for (const app of ['exchange-frontend', 'exchange-pc']) {
  test(`${app}: chart and order defaults follow explicit saved language, otherwise browser timezone`, () => {
    const source = readFileSync(new URL(`../../${app}/src/utils/dateTime.ts`, import.meta.url), 'utf8')
    const timezoneSource = readFileSync(new URL('../src/utils/displayTimezone.ts', import.meta.url), 'utf8')
    const code = stripTypeScriptTypes(timezoneSource + '\n' + source).replace(/^import [^\n]+\n/gm, '').replace(/^export /gm, '')
    const load = (browserTimezone, ipTimezone = 'Asia/Tokyo', saved = null) => runInNewContext(
      code + '; ({ preferredTimeLocale, getSystemTimezone, formatDate, formatDateTime, formatTime, formatDateTimeLocalized, refreshOrderTimes })',
      {
        ref: value => ({ value }), localStorage: { getItem: () => saved },
        initVisitorRegion: () => Promise.resolve(), visitorRegion: { value: { timezone: ipTimezone } },
        process: { env: { NODE_ENV: 'test' } },
        Intl: { DateTimeFormat: function (locale, options) {
          return locale ? new Intl.DateTimeFormat(locale, options) : { resolvedOptions: () => ({ timeZone: browserTimezone }) }
        } },
      },
    )
    const dateTime = load('America/New_York')
    assert.equal(dateTime.getSystemTimezone(), 'America/New_York')
    assert.equal(dateTime.formatDateTime('2026-01-01T05:00:00Z'), '2026-01-01 00:00:00')
    assert.equal(dateTime.formatDateTime('2026-07-01T05:00:00Z'), '2026-07-01 01:00:00')
    assert.equal(dateTime.formatDate('2026-01-01T03:00:00Z'), '2025-12-31')
    assert.equal(dateTime.formatTime('2026-07-01T05:00:00Z'), '01:00:00')
    assert.equal(dateTime.formatDateTimeLocalized('2026-07-01T05:00:00Z'), '01/07/2026, 01:00:00')
    assert.equal(dateTime.formatDateTimeLocalized('2026-07-01T05:00:00Z', 'en-GB', { timeZone: 'Asia/Singapore' }), '01/07/2026, 13:00:00')
    assert.equal(load('').getSystemTimezone(), 'Asia/Tokyo')
    assert.equal(load('').formatDateTime('2026-07-01T05:00:00Z'), '2026-07-01 14:00:00')
    assert.equal(load('', '').getSystemTimezone(), 'UTC')
    assert.equal(load('', '').formatDateTime('2026-07-01T05:00:00Z'), '2026-07-01 05:00:00')
    const japanese = load('Asia/Singapore', 'America/New_York', 'ja')
    assert.equal(japanese.getSystemTimezone(), 'Asia/Tokyo')
    assert.equal(japanese.formatDateTime('2026-07-01T05:00:00Z'), '2026-07-01 14:00:00')
    japanese.preferredTimeLocale.value = 'ko'
    assert.equal(japanese.getSystemTimezone(), 'Asia/Seoul')
    japanese.preferredTimeLocale.value = 'en'
    assert.equal(japanese.formatDateTime('2026-01-01T05:00:00Z'), '2026-01-01 00:00:00')
    assert.equal(japanese.formatDateTime('2026-07-01T05:00:00Z'), '2026-07-01 01:00:00')
    assert.equal(load('Asia/Singapore', 'Asia/Tokyo', '__proto__').getSystemTimezone(), 'Asia/Singapore')
    const row = { id: 102, duration: 60, openTimeRaw: '2026-07-01T05:00:00Z', closeTimeRaw: '2026-07-01T06:00:00Z', createdTimeRaw: '2026-07-01T04:00:00Z', manualCloseTimeRaw: null }
    japanese.preferredTimeLocale.value = 'ja'
    japanese.refreshOrderTimes([row, null])
    assert.equal(row.openTime, '2026-07-01 14:00:00')
    assert.equal(row.closeTime, '2026-07-01 15:00:00')
    assert.equal(row.createdTime, '2026-07-01 13:00:00')
    assert.equal(row.manualCloseTime, '')
    japanese.preferredTimeLocale.value = 'en'
    japanese.refreshOrderTimes([row])
    assert.equal(row.openTime, '2026-07-01 01:00:00')
    assert.equal(row.openTimeRaw, '2026-07-01T05:00:00Z')
    assert.equal(row.duration, 60)
    assert.equal(row.id, 102)
    // Exercise the actual announcement template expressions, not an alternate formatter.
    const views = app === 'exchange-pc' ? ['views/DesktopTrade.vue', 'views/Announcements.vue'] : ['advanced/views/Explore.vue']
    const announcementClock = load('America/New_York', 'UTC', 'zh-TW')
    for (const view of views) {
      const template = readFileSync(new URL(`../../${app}/src/${view}`, import.meta.url), 'utf8')
      const expressions = [...template.matchAll(/{{\s*(formatDateTimeLocalized\([^}]+\))\s*}}/g)].map(match => match[1])
      assert.equal(expressions.length, view.endsWith('Explore.vue') ? 2 : 1, `${view}: announcement date formatter`)
      const render = announcement => expressions.map(expression => runInNewContext(expression, {
        formatDateTimeLocalized: announcementClock.formatDateTimeLocalized, item: announcement, announcement, announcements: [announcement],
      }))
      const creation = '2026-10-07T03:22:56'
      const configured = { displayAt: '2022-09-18T00:00:00', createdAt: creation }
      assert.ok(render(configured).every(time => time === '2022-09-18 08:00:00'), `${view}: configured UTC time takes precedence`)
      assert.ok(render({ createdAt: creation }).every(time => time === '2026-10-07 11:22:56'), `${view}: legacy creation fallback`)
      assert.ok(render({ displayAt: null, createdAt: creation }).every(time => time === '2026-10-07 11:22:56'))
      assert.ok(render({ ...configured, displayAt: '2022-09-18T08:00:00+08:00' }).every(time => time === '2022-09-18 08:00:00'))
      assert.ok(render({ ...configured, displayAt: '2022-09-18T00:00:00.123456789Z' }).every(time => time === '2022-09-18 08:00:00'))
      assert.ok(render({}).every(time => time === ''))
      announcementClock.preferredTimeLocale.value = 'en'
      assert.ok(render(configured).every(time => time === '2022-09-17 20:00:00'), `${view}: saved language timezone`)
      announcementClock.preferredTimeLocale.value = 'zh-TW'
      assert.equal(configured.createdAt, creation, 'display never rewrites audit time')
    }
    const languages = readFileSync(new URL('../src/utils/languages.ts', import.meta.url), 'utf8')
    for (const [, language] of languages.matchAll(/locale: '([^']+)'/g)) {
      const zone = load('UTC', 'UTC', language).getSystemTimezone()
      assert.notEqual(zone, 'UTC', `${language} must have a representative timezone`)
      assert.doesNotThrow(() => new Intl.DateTimeFormat('en', { timeZone: zone }))
    }
  })
  test(`${app}: automatic Japanese does not override browser time; explicit choice persists on revisit`, () => {
    const storeSource = readFileSync(new URL(`../../${app}/src/store/locale.ts`, import.meta.url), 'utf8')
    const store = storeSource.slice(storeSource.indexOf('export const useLocaleStore ='))
    const timezone = readFileSync(new URL('../src/utils/displayTimezone.ts', import.meta.url), 'utf8')
    const code = stripTypeScriptTypes(timezone + '\n' + store).replace(/^import [^\n]+\n/gm, '').replace(/^export /gm, '')
    let saved = null
    const open = () => runInNewContext(code + '; ({ locale: useLocaleStore(), getDisplayTimezone })', {
      ref: value => ({ value }), computed: read => ({ get value() { return read() } }), watch() {},
      defineStore: (_name, setup) => setup, messages: { en: {}, ja: {}, ko: {}, 'zh-TW': {} },
      uiMessages: {}, uiAliases: {}, console: { log() {} },
      navigator: { languages: ['ja-JP'] },
      localStorage: { getItem: () => saved, setItem: (_key, value) => { saved = value } },
      Intl: { DateTimeFormat: () => ({ resolvedOptions: () => ({ timeZone: 'Asia/Singapore' }) }) },
    })
    const first = open()
    first.locale.loadLocale()
    assert.equal(first.locale.locale.value, 'ja')
    assert.equal(first.getDisplayTimezone(), 'Asia/Singapore')
    first.locale.setLocale('ja')
    assert.equal(first.getDisplayTimezone(), 'Asia/Tokyo')
    assert.equal(saved, 'ja')
    const second = open()
    second.locale.loadLocale()
    assert.equal(second.locale.locale.value, 'ja')
    assert.equal(second.getDisplayTimezone(), 'Asia/Tokyo')
    saved = 'invalid'; second.locale.loadLocale()
    assert.equal(second.getDisplayTimezone(), 'Asia/Singapore')
  })
}

test('loaded order lists and open details update on language changes without another request', async () => {
  for (const [app, view] of [['exchange-frontend', 'Orders'], ['exchange-pc', 'Orders'], ['exchange-pc', 'DesktopTrade']]) {
    const source = readFileSync(new URL(`../../${app}/src/views/${view}.vue`, import.meta.url), 'utf8')
    const watcher = source.match(/^watch\(getSystemTimezone[^\n]+/m)[0]
    const timezone = readFileSync(new URL('../src/utils/displayTimezone.ts', import.meta.url), 'utf8')
    const dates = readFileSync(new URL(`../../${app}/src/utils/dateTime.ts`, import.meta.url), 'utf8')
    const code = stripTypeScriptTypes(timezone + '\n' + dates).replace(/^import [^\n]+\n/gm, '').replace(/^export /gm, '')
    const row = { id: 102, duration: 60, openTimeRaw: '2026-07-01T05:00:00Z', closeTimeRaw: '2026-07-01T06:00:00Z' }
    const rows = ref([row]), detail = ref({ ...row })
    const state = runInNewContext(code + '\nconst formatOrderTime = value => formatDateTime(value);\n' + watcher + '; ({ preferredTimeLocale })', {
      ref, watch, localStorage: { getItem: () => null },
      positionsData: rows, pendingOrdersData: ref([]), historyData: ref([]), termTradingData: ref([]), termClosedData: ref([]), detailOrder: detail,
      initVisitorRegion: () => Promise.resolve(), visitorRegion: { value: { timezone: 'Asia/Tokyo' } },
      process: { env: { NODE_ENV: 'test' } },
      Intl: { DateTimeFormat: function (locale, options) {
        return locale ? new Intl.DateTimeFormat(locale, options) : { resolvedOptions: () => ({ timeZone: 'Asia/Singapore' }) }
      } },
    })
    state.preferredTimeLocale.value = 'ja'
    await nextTick()
    assert.equal(rows.value[0].openTime, '2026-07-01 14:00:00', `${app}/${view}`)
    assert.equal(rows.value[0].closeTime, '2026-07-01 15:00:00')
    if (view === 'Orders') assert.equal(detail.value.openTime, '2026-07-01 14:00:00')
    state.preferredTimeLocale.value = 'en'
    await nextTick()
    assert.equal(rows.value[0].openTime, '2026-07-01 01:00:00')
    assert.equal(rows.value[0].openTimeRaw, row.openTimeRaw)
    assert.equal(rows.value[0].duration, 60)
  }
})
