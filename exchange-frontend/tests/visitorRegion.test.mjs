import assert from 'node:assert/strict'
import { test } from 'node:test'

for (const app of ['exchange-frontend', 'exchange-pc']) {
  test(`${app}: IP defaults, single lookup, fallback and daylight-saving time`, async () => {
    const originalFetch = globalThis.fetch
    let calls = 0
    globalThis.fetch = async (url, options) => {
      calls++
      assert.equal(new URL(url).hostname, 'ipwho.is')
      assert.equal(options.credentials, 'omit')
      assert.equal(options.referrerPolicy, 'no-referrer')
      return { ok: true, json: async () => ({ success: true, country_code: 'JP', calling_code: '81', currency: { code: 'JPY' }, timezone: { id: 'Asia/Tokyo' } }) }
    }
    try {
      const modulePath = `../../${app}/src/utils/visitorRegion.ts`
      const region = await import(modulePath)
      await Promise.all([region.initVisitorRegion(), region.initVisitorRegion()])
      assert.equal(calls, 1)
      assert.deepEqual(region.visitorRegion.value, { locale: 'ja', timezone: 'Asia/Tokyo', source: 'ip', countryCode: 'JP', dialCode: '+81', currency: 'JPY' })
      for (const [country, locale] of [['JP','ja'], ['US','en'], ['SG','en'], ['TW','zh-TW'], ['FR','fr'], ['KR','ko'], ['xx','en']]) {
        assert.equal(region.localeForCountry(country), locale)
      }
      const time = Date.parse('2026-07-01T05:53:56Z')
      assert.equal(region.formatQuoteTime(time, 'Asia/Singapore'), 'SGT 1:53:56 pm')
      assert.equal(region.formatQuoteTime(time, 'America/New_York'), 'EDT 1:53:56 am')
      assert.equal(region.formatQuoteTime(Date.parse('2026-01-01T05:00:00Z'), 'America/New_York'), 'EST 12:00:00 am')
      assert.equal(region.formatQuoteTime(time), 'JST 2:53:56 pm')
      assert.equal(region.formatQuoteTime(NaN), '—')
      assert.equal(region.formatQuoteTime(time, 'Invalid/Zone'), '—')
      assert.equal(region.validRegionTimezone(''), false)

      for (const invalid of [{ success: false }, { success: true, country_code: 'JP', timezone: { id: 'Invalid/Zone' } }, null]) {
        globalThis.fetch = async () => ({ ok: true, json: async () => invalid })
        const fallback = await import(`${modulePath}?invalid=${JSON.stringify(invalid)}`)
        await fallback.initVisitorRegion()
        assert.equal(fallback.visitorRegion.value.source, 'device')
        assert.equal(fallback.visitorRegion.value.locale, '')
      }
      globalThis.fetch = async () => { throw Error('offline') }
      const offline = await import(`${modulePath}?offline`)
      await offline.initVisitorRegion()
      assert.equal(offline.visitorRegion.value.source, 'device')
      globalThis.fetch = async () => ({ ok: false })
      const limited = await import(`${modulePath}?limited`)
      await limited.initVisitorRegion()
      assert.equal(limited.visitorRegion.value.source, 'device')
      globalThis.fetch = (_, { signal }) => new Promise((_, reject) => signal.addEventListener('abort', () => reject(Error('timeout'))))
      const timedOut = await import(`${modulePath}?timeout`)
      await timedOut.initVisitorRegion()
      assert.equal(timedOut.visitorRegion.value.source, 'device')
    } finally { globalThis.fetch = originalFetch }
  })
}
