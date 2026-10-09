import { ref } from 'vue'

// Explicit language choices use the representative country shown in the language menu.
export const languageTimezones: Record<string, string> = {
  en: 'America/New_York', fr: 'Europe/Paris', de: 'Europe/Berlin', ru: 'Europe/Moscow',
  es: 'Europe/Madrid', pt: 'Europe/Lisbon', it: 'Europe/Rome', ar: 'Asia/Riyadh',
  tr: 'Europe/Istanbul', id: 'Asia/Jakarta', my: 'Asia/Yangon', hi: 'Asia/Kolkata',
  cs: 'Europe/Prague', pl: 'Europe/Warsaw', ja: 'Asia/Tokyo', ko: 'Asia/Seoul',
  'zh-TW': 'Asia/Taipei', th: 'Asia/Bangkok', vi: 'Asia/Ho_Chi_Minh',
}

export const preferredTimeLocale = ref('')
try { preferredTimeLocale.value = localStorage.getItem('locale') || '' } catch { /* Use the browser when storage is unavailable. */ }

export function getDisplayTimezone(fallback = 'UTC'): string {
  const language = preferredTimeLocale.value
  return (Object.prototype.hasOwnProperty.call(languageTimezones, language) ? languageTimezones[language] : '')
    || Intl.DateTimeFormat().resolvedOptions().timeZone || fallback
}
