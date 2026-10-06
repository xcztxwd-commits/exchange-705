export function generateTotpSecret(): string {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
  return Array.from(crypto.getRandomValues(new Uint8Array(32)), byte => alphabet[byte & 31]).join('')
}
