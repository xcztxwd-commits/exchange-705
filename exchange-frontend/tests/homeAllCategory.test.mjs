import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'

const source = readFileSync(new URL('../src/views/Home.vue', import.meta.url), 'utf8')
assert.match(source, /const activeCategory = ref\('All'\)/)
assert.match(source, /activeCategory.value !== 'All' && !currentCategoryHasSymbols/)
assert.match(source, /cat.key === 'All' \? localeStore.t\('all'\)/)
const body = source.match(/function updateCategorySymbols\(\) \{([\s\S]*?)\n\}/)[1].replaceAll(': any', '')
const rows = [{ category: 'US' }, { category: 'Forex' }, { category: 'Forex', isEnabled: false }]
const context = { allSymbols: { value: rows }, categorySymbols: { value: [] }, activeCategory: { value: 'All' }, hotSymbols: { value: [] }, marketStore: { subscribeSymbols() {} }, console: { log() {} } }
runInNewContext(body, context)
assert.deepEqual(context.categorySymbols.value, rows.slice(0, 2))
context.activeCategory.value = 'Forex'
runInNewContext(body, context)
assert.deepEqual(context.categorySymbols.value, [rows[1]])
context.allSymbols.value = []
context.activeCategory.value = 'All'
runInNewContext(body, context)
assert.equal(context.categorySymbols.value.length, 0)
console.log('Mobile home all-category checks passed')
