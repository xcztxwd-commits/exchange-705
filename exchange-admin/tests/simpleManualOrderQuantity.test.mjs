import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { simpleConditions, simpleFields, simplePayload } from '../src/utils/simpleManualOrder.ts'
import { manualOrderEstimate } from '../src/utils/manualOrderEstimate.ts'
const require = createRequire(new URL('../package.json', import.meta.url)), ts = require('typescript'), vue = require('vue')
const source = readFileSync(new URL('../src/components/SimpleManualContractOrder.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
const posts = [], snapshot = { lotSize: '1', feePerLot: '0.01', quantityStep: '0.01', minOrderQuantity: '0.01', minOrderNotional: '0', walletBefore: null, openUtc: '2026-10-05T10:00:00Z', closeUtc: '2026-10-05T10:21:00Z' }
const request = {
  get: async () => ({ users: [], timezone: 'UTC', symbols: [{ symbol: 'FIXTURE', spec_version: 1, quantity_step: '0.01', max_leverage: '100' }, { symbol: 'OTHER', spec_version: 1, quantity_step: '0.01', max_leverage: '100' }] }),
  post: async (url, payload) => {
    assert.ok(url.endsWith('/simple/generate'), 'Never creates an order or changes funds')
    posts.push({ url, payload })
    const quotes = { openPrice: payload.openPrice ?? '99.5', closePrice: payload.closePrice ?? '100', openRate: '1', closeRate: '1' }, quantity = payload.quantity ?? '100', side = payload.side ?? 'BUY'
    const calculation = manualOrderEstimate({ driver: 'QUANTITY', input: quantity, side, leverage: '100' }, { ...snapshot, walletBefore: '0', quotes })
    return { ...snapshot, request: { ...payload, side, leverage: '100' }, quotes, calculation: { ...calculation, quantity, net: calculation.netText }, previewToken: 'isolated' }
  },
}
const names = ['computed','nextTick','reactive','ref','watch','ElMessage','request','simpleConditions','simpleFields','simplePayload','manualOrderEstimate','defineEmits','defineExpose']
const scope = vue.effectScope()
const component = scope.run(() => new Function(...names, js + ';return {open,form,conditions,quantityDrivesNet,edit,generate,result,canCreate,sliderValue};')(...[vue.computed,vue.nextTick,vue.reactive,vue.ref,vue.watch,{success(){}},request,simpleConditions,simpleFields,simplePayload,manualOrderEstimate,()=>()=>{},()=>{}]))
try {
  await component.open(); component.form.symbol = 'FIXTURE'; await vue.nextTick()
  component.edit('targetNet', '49'); await component.generate()
  assert.equal(component.form.targetNet, '49'); assert.equal(component.canCreate.value, true)
  await component.generate(); assert.equal(posts.at(-1).payload.targetNet, '49', 'Untouched generation retains its original target')
  const previous = component.result.value, count = posts.length
  component.edit('quantity', '2')
  assert.equal(component.form.targetNet, '0.98'); assert.equal(component.conditions.targetNet, '0.98')
  assert.equal(component.form.openTime, Date.parse(snapshot.openUtc)); assert.equal(component.form.closeTime, Date.parse(snapshot.closeUtc))
  assert.equal(component.conditions.openPrice, '99.5'); assert.equal(component.conditions.closePrice, '100'); assert.equal(component.conditions.side, 'BUY')
  assert.equal(previous.calculation.net, '49', 'Do not mutate a previously signed preview')
  assert.equal(Boolean(component.canCreate.value), false, 'An edited quantity invalidates the old preview immediately')
  await vue.nextTick(); assert.equal(component.result.value, null)
  for (const [quantity, net] of [['3','1.47'], ['.5','0.245'], ['2e0','0.98'], ['150','73.5']]) {
    component.edit('quantity', quantity); assert.equal(component.form.targetNet, net); assert.equal(component.conditions.targetNet, net)
    await vue.nextTick()
  }
  assert.equal(component.sliderValue.value, 100); assert.equal(component.form.quantity, '150', 'Slider limit must not cap a typed quantity')
  assert.equal(posts.length, count, 'Typing uses the current authoritative rates, never restarts a history search')
  component.edit('side', 'SELL'); assert.equal(component.form.targetNet, '-76.5')
  component.edit('leverage', '50'); assert.equal(component.form.targetNet, '-76.5', 'Leverage changes margin, not net profit')
  component.edit('targetNet', '999'); assert.equal(component.quantityDrivesNet.value, false)
  component.edit('quantity', '4'); assert.equal(component.form.targetNet, '-2.04', 'An explicit quantity edit replaces the former target')
  for (const value of ['', '0', '1.001', 'NaN']) {
    component.edit('quantity', value); assert.equal(component.form.targetNet, ''); assert.equal(component.conditions.targetNet, '')
    assert.equal(Boolean(component.canCreate.value), false)
  }
  component.edit('side', 'BUY'); component.edit('quantity', '2'); await component.generate()
  assert.equal(posts.at(-1).payload.quantity, '2'); assert.equal(posts.at(-1).payload.targetNet, '0.98')
  assert.equal(posts.at(-1).payload.openTime, Date.parse(snapshot.openUtc)); assert.equal(posts.at(-1).payload.closeTime, Date.parse(snapshot.closeUtc))
  assert.equal(component.form.quantity, '2'); assert.equal(component.form.targetNet, '0.98'); assert.equal(component.canCreate.value, true)
  component.edit('openPrice', '100'); component.edit('quantity', '5'); assert.equal(component.form.targetNet, '', 'Changed prices need matching rates/basis, not a stale estimate')
  await component.generate(); assert.equal(component.form.targetNet, '-0.05', 'Equal prices lose only the actual fee, never produce positive profit')
  component.form.symbol = 'OTHER'; await vue.nextTick(); component.edit('quantity', '2'); assert.equal(component.form.targetNet, '', 'Changing symbol must not reuse another symbol’s fee or exchange rates')
  await component.open(); assert.equal(component.form.targetNet, ''); assert.equal(component.quantityDrivesNet.value, false)
  component.form.symbol = 'FIXTURE'; await vue.nextTick(); component.edit('quantity', '2'); assert.equal(component.form.targetNet, '', 'Missing prices/rates stay unknown, never guessed')
  console.log('PASS real simple form: quantity/slider net sync, exact decimal inputs, signed/zero profits, unchanged trade basis, old-preview invalidation, no background search, resets and missing-data safety')
} finally { scope.stop() }
