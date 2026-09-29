import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/views/Orders.vue', import.meta.url), 'utf8')
const desktopSource = readFileSync(new URL('../../exchange-pc/src/views/DesktopTrade.vue', import.meta.url), 'utf8')
for (const page of [source, desktopSource]) {
  assert.doesNotMatch(page, /manual-order-badge|copy\('手動', 'Manual'\)|localeStore\.text\('手動訂單', 'Manual order'\)/)
}
console.log('PASS manual order hint removed from mobile and desktop pages')
