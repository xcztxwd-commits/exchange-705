import { ref } from 'vue'

// Country defaults use existing translations; unsupported languages fall back to English.
const countryLanguages: Record<string, string> = {
  CN: 'zh-TW', TW: 'zh-TW', HK: 'zh-TW', MO: 'zh-TW',
  FR: 'fr', BE: 'fr', MC: 'fr', DE: 'de', AT: 'de', CH: 'de', LI: 'de',
  RU: 'ru', BY: 'ru', KZ: 'ru', ES: 'es', MX: 'es', AR: 'es', CO: 'es',
  CL: 'es', PE: 'es', VE: 'es', EC: 'es', BO: 'es', UY: 'es', PY: 'es',
  CR: 'es', PA: 'es', GT: 'es', HN: 'es', SV: 'es', NI: 'es', DO: 'es', CU: 'es',
  PT: 'pt', BR: 'pt', AO: 'pt', MZ: 'pt', IT: 'it', SM: 'it', VA: 'it',
  SA: 'ar', AE: 'ar', EG: 'ar', IQ: 'ar', JO: 'ar', KW: 'ar', QA: 'ar',
  BH: 'ar', OM: 'ar', LB: 'ar', SY: 'ar', YE: 'ar', LY: 'ar', TN: 'ar', DZ: 'ar', MA: 'ar', SD: 'ar',
  TR: 'tr', ID: 'id', MM: 'my', IN: 'hi', CZ: 'cs', PL: 'pl',
  JP: 'ja', KR: 'ko', TH: 'th', VN: 'vi',
}
export function localeForCountry(country: string) { return countryLanguages[country.toUpperCase()] || 'en' }
export function validRegionTimezone(value: unknown): value is string {
  if (typeof value !== 'string' || !value.trim()) return false
  try { new Intl.DateTimeFormat('en', { timeZone: value }); return true } catch { return false }
}
export const visitorRegion = ref({ timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC', locale: '', source: 'device' })
let pending: Promise<void> | undefined
export function initVisitorRegion(): Promise<void> {
  return pending ??= (async () => {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 2500)
    try {
      // Direct lookup sees the visitor's public IP, not the web server/proxy IP.
      // No account data, tokens or cookies are sent to the provider.
      const response = await fetch('https://ipwho.is/?fields=success,country_code,timezone.id', {
        signal: controller.signal, credentials: 'omit', referrerPolicy: 'no-referrer', cache: 'no-store',
      })
      if (!response.ok) return
      const data = await response.json()
      if (data?.success !== true || !/^[A-Z]{2}$/.test(data.country_code) || !validRegionTimezone(data.timezone?.id)) return
      visitorRegion.value = { timezone: data.timezone.id, locale: localeForCountry(data.country_code), source: 'ip' }
    } catch { /* Offline, blocked or rate-limited: retain device timezone and browser language. */ }
    finally { clearTimeout(timer) }
  })()
}

export function formatQuoteTime(timestamp: number, timezone = visitorRegion.value.timezone): string {
  if (!Number.isFinite(timestamp) || !validRegionTimezone(timezone)) return '—'
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone, hour: 'numeric', minute: '2-digit', second: '2-digit', hour12: true, timeZoneName: 'short',
  }).formatToParts(timestamp)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find(p => p.type === type)?.value || ''
  // Intl uses GMT offsets for some Asian zones; prefer their established abbreviations.
  const abbreviations: Record<string, string> = {
    'Asia/Singapore': 'SGT', 'Asia/Kuala_Lumpur': 'MYT', 'Asia/Kuching': 'MYT',
    'Asia/Tokyo': 'JST', 'Asia/Seoul': 'KST', 'Asia/Shanghai': 'CST', 'Asia/Taipei': 'CST',
    'Asia/Hong_Kong': 'HKT', 'Asia/Kolkata': 'IST', 'Asia/Calcutta': 'IST',
  }
  const zone = abbreviations[timezone] || part('timeZoneName')
  return `${zone} ${part('hour')}:${part('minute')}:${part('second')} ${part('dayPeriod').toLowerCase()}`
}
