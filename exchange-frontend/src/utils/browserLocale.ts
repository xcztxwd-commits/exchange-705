export function browserLocale(languages: readonly string[], supported: Record<string, unknown>): string {
  for (const preference of languages) {
    const code = (preference.split('-')[0] || '').toLowerCase()
    if (code === 'zh') return 'zh-TW'
    if (Object.prototype.hasOwnProperty.call(supported, code)) return code
  }
  return 'en'
}
