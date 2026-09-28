import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/views/Orders.vue', import.meta.url), 'utf8')
const condition = source.match(/<span v-if="([^"]+)" class="manual-order-badge"/)?.[1]
assert.ok(condition, 'manual order badge exists')
const visible = new Function('order', `return ${condition}`)
assert.equal(visible({ orderSource: 'MANUAL_TEST' }), true)
assert.equal(visible({ orderSource: 'MANUAL' }), true)
assert.equal(visible({ orderSource: 'NORMAL' }), false)
assert.equal(visible({}), false)
console.log('PASS real template manual badge predicate')
