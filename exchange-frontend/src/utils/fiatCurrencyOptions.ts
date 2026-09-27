export const currencies = ['USD', 'EUR', 'JPY', 'GBP', 'AUD', 'CAD', 'SGD', 'CNY']

export function currencyOptions(locale: string) {
  const names = new Intl.DisplayNames([locale], { type: 'currency' })
  return currencies.map(code => ({ value: code, label: `${code} · ${names.of(code) || code}` }))
}
