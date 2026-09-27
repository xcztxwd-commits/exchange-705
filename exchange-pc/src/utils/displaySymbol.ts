type Instrument = {
  symbol?: string | null
  category?: string | null
  sourceCategory?: string | null
  baseCurrency?: string | null
  quoteCurrency?: string | null
  name?: string | null
  displayName?: string | null
}
const currencies = new Set(['USD', 'EUR', 'GBP', 'JPY', 'CHF', 'AUD', 'NZD', 'CAD', 'HKD', 'CNY', 'SGD', 'SEK', 'NOK', 'DKK', 'MXN', 'ZAR', 'TRY', 'INR', 'KRW', 'TWD', 'THB', 'RUB', 'BRL', 'PLN', 'CZK', 'HUF', 'ILS', 'AED', 'SAR', 'IDR', 'MYR', 'PHP'])

// Keep provider codes in API calls and orders; format only text shown to people.
export function displaySymbol(value: string | Instrument | null | undefined): string {
  if (!value) return ''
  const item: Instrument = typeof value === 'string' ? { symbol: value } : value
  const symbol = item.symbol || ''
  if (item.displayName?.trim() && !/=X/i.test(item.displayName)) return item.displayName.trim()
  const code = symbol.toUpperCase().replace(/=X$/, '')
  const fiatPair = /^[A-Z]{6}$/.test(code) && currencies.has(code.slice(0, 3)) && currencies.has(code.slice(3))
  const forex = item.category === 'Forex' || item.sourceCategory === 'Forex' || /=X$/i.test(symbol) || (!item.category && !item.sourceCategory && fiatPair)
  if (!forex) return symbol

  if (/=X$/i.test(symbol) && /^[A-Z]{3}$/.test(code) && code !== 'USD') return `USD/${code}`
  if (/^[A-Z]{6}$/.test(code)) return `${code.slice(0, 3)}/${code.slice(3)}`

  const named = item.name?.toUpperCase().match(/\b([A-Z]{3})\s*\/\s*([A-Z]{3})\b/)
  if (named) return `${named[1]}/${named[2]}`
  const base = item.baseCurrency?.toUpperCase(), quote = item.quoteCurrency?.toUpperCase()
  if (base && quote && base !== quote && /^[A-Z]{3}$/.test(base) && /^[A-Z]{3}$/.test(quote)) return `${base}/${quote}`
  return code
}
