import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url), ts = require('typescript')
const { ref, watch, nextTick, effectScope } = require('vue')

for (const app of ['exchange-pc', 'exchange-frontend']) {
  const { contractMargin, quantityFromAllocation, validQuantity } = await import(`../../${app}/src/utils/contract.ts`)
  test(`${app}: zero allocation intent survives account, price and leverage watchers`, async () => {
    const source = readFileSync(new URL(`../../${app}/src/utils/useOrderSizing.ts`, import.meta.url), 'utf8')
    const code = source.slice(source.indexOf('  function applyAllocation()'), source.indexOf('\n  let accountAbort'))
    const input = { quantity: ref(1), symbol: ref('TEST'), available: ref(10000), feePerLot: ref(30) }
    const requestedPercent = ref(null), canAllocate = ref(true), costPerLot = ref(5030), marginPerLot = ref(5000)
    const context = { input, requestedPercent, canAllocate, costPerLot, marginPerLot, specification: ref({ quantityStep: 0.01, minOrderQuantity: 0.01 }), notionalPerUnit: ref(100000), quantityFromAllocation, watch }
    const scope = effectScope()
    const set = scope.run(() => new Function(...Object.keys(context), 'let writingQuantity=false;' + ts.transpile(code, { target: ts.ScriptTarget.ES2022 }) + ';return setAllocation')(...Object.values(context)))
    try {
      set(50); assert(input.quantity.value > 0)
      set(0); assert.equal(input.quantity.value, 0); assert.equal(requestedPercent.value, 0)
      canAllocate.value = false; costPerLot.value = NaN; input.available.value = NaN; await nextTick()
      assert.equal(input.quantity.value, 0); assert.equal(requestedPercent.value, 0)
      canAllocate.value = true; costPerLot.value = 10000; marginPerLot.value = 9000; input.available.value = 5000; await nextTick()
      assert.equal(input.quantity.value, 0); assert.equal(requestedPercent.value, 0)
      input.quantity.value = 0.02; assert.equal(requestedPercent.value, null)
      input.quantity.value = 0; assert.equal(requestedPercent.value, 0)
      input.available.value = 10000; await nextTick(); assert.equal(input.quantity.value, 0)
      assert.equal(validQuantity(0, context.specification.value), false)
    } finally { scope.stop() }
  })
  test(`${app}: zero quantity has zero margin even when FX authority is unavailable`, () => {
    assert.equal(contractMargin(0, 1000, 100, 20, NaN), 0)
    assert.equal(contractMargin(0, 1000, 100, 20, NaN, NaN), 0)
    assert(Number.isNaN(contractMargin(0.01, 1000, 100, 20, NaN)))
    assert(Number.isNaN(contractMargin(0.01, 1000, 100, 20, 1, NaN)))
  })
}
