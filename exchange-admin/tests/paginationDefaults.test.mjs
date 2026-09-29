import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const views = new URL('../src/views/', import.meta.url)
const defaults = {
  'AgentManagement.vue': /const pageSize = ref\(10\)/,
  'AdminList.vue': /const pageSize = ref\(10\)/,
  'DepositOrders.vue': /size = ref\(10\)/,
  'KycReview.vue': /const size = ref\(10\)/,
  'OperationLog.vue': /size: 10,/,
  'Orders.vue': /size: 10,/,
  'Symbols.vue': /size: 10,/,
  'Users.vue': /size: 10,/,
}

test('all admin pagers default to 10 and offer page-size choices', () => {
  let pagerCount = 0
  for (const [name, pattern] of Object.entries(defaults)) {
    const source = readFileSync(new URL(name, views), 'utf8')
    assert.match(source, pattern, `${name}: default page size`)
    if (name === 'Orders.vue') assert.equal((source.match(/size: 10,/g) || []).length, 2)
    const pagers = source.match(/<el-pagination\b[\s\S]*?\/>/g) || []
    for (const pager of pagers) {
      assert.match(pager, /:page-sizes="\[10,\s*20,\s*50,\s*100\]"/, `${name}: size options`)
      assert.match(pager, /layout="[^"]*sizes/, `${name}: size selector`)
      assert.match(pager, /@change=/, `${name}: reload on pagination change`)
    }
    pagerCount += pagers.length
  }
  assert.equal(pagerCount, 9)
})
