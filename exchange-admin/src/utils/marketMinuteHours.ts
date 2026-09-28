export const isCryptoMarket = (category: string) => ['crypto', 'cryptoperpetual'].includes(category.toLowerCase())

export function marketMinuteHours(category: string, availableLocalMinutes: string[]) {
  return isCryptoMarket(category)
    ? Array.from({ length: 24 }, (_, hour) => String(hour).padStart(2, '0'))
    : [...new Set(availableLocalMinutes.map(local => local.slice(11, 13)))].sort()
}
