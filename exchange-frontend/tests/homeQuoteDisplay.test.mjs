import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire, stripTypeScriptTypes } from 'node:module'
import { test } from 'node:test'
import { runInNewContext } from 'node:vm'
import { compile } from '@vue/compiler-dom'
import { compileScript, parse } from '@vue/compiler-sfc'
import ts from 'typescript'
import * as Vue from 'vue'

const require = createRequire(import.meta.url)
test('cached homepage catalogs render immediately and survive a timed-out request', async () => {
  const source = readFileSync(new URL('../src/views/Home.vue', import.meta.url), 'utf8')
  const functions = ['restoreHomeCatalog', 'updateCategorySymbols', 'loadAllSymbols'].map(name => source.match(new RegExp(`(?:async )?function ${name}\\([\\s\\S]*?\\r?\\n\\}`))?.[0]).join('\n')
  const rows = [
    { symbol: 'JPY=X', category: 'Forex', isHot: true },
    { symbol: 'BTCUSDT', category: 'Crypto' },
    { symbol: 'DISABLED', isHot: true, isEnabled: false },
  ]
  let saved = JSON.stringify([...rows, null, { symbol: '' }]), previewCalls = 0
  const context = {
    SYMBOLS_CACHE_KEY: 'catalog-test', localStorage: { getItem: key => key.endsWith(':time') ? '1' : saved },
    allSymbols: { value: [] }, hotSymbols: { value: [] }, categorySymbols: { value: [] },
    activeCategory: { value: 'All' }, categories: { value: [] },
    request: { get: async () => { throw Error('timeout') } },
    marketStore: { subscribeSymbols: async () => {}, fetchBatchPricesFromRedis: async () => {} },
    loadSparklinePreview: async () => { previewCalls++ }, startSparklinePolling() {},
    console: { log() {}, warn() {}, error() {} },
  }
  const home = runInNewContext(stripTypeScriptTypes(functions) + '; ({ restoreHomeCatalog, loadAllSymbols })', context)
  home.restoreHomeCatalog()
  assert.deepEqual(JSON.parse(JSON.stringify(context.hotSymbols.value)), [rows[0]])
  assert.deepEqual(JSON.parse(JSON.stringify(context.categorySymbols.value)), rows.slice(0, 2))
  // An unreadable cache must not clear the catalog already on screen.
  saved = '{invalid'
  await home.loadAllSymbols()
  assert.deepEqual(JSON.parse(JSON.stringify(context.categorySymbols.value)), rows.slice(0, 2))
  assert.equal(previewCalls, 1)
  assert.match(source, /onMounted\(async \(\) => \{\s*restoreHomeCatalog\(\)/)
})

for (const app of ['exchange-frontend', 'exchange-pc']) {
  test(`${app}: sparkline renders neutral colors and updates when quotes arrive`, () => {
    const source = readFileSync(new URL(`../../${app}/src/components/Sparkline.vue`, import.meta.url), 'utf8')
    const { descriptor } = parse(source)
    const script = compileScript(descriptor, { id: 'sparkline-test' }).content
    const code = ts.transpileModule(script, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
    const module = { exports: {} }
    new Function('require', 'module', 'exports', 'setTimeout', code)(require, module, module.exports, () => {})
    const props = Vue.reactive({ data: [1, 3, 2], width: 80, height: 24, color: '#999' })
    const state = Vue.proxyRefs(module.exports.default.setup(props, { expose() {} }))
    const render = new Function('Vue', compile(descriptor.template.content, { mode: 'function' }).code)(Vue)
    const paths = () => render(state, []).children.filter(node => node.type === 'path')
    assert.equal(paths().length, 2)
    assert.ok(!paths()[0].props.fill.includes('NaN'))
    assert.equal(paths()[0].props.fill, '#999')
    assert.equal(Number(paths()[0].props['fill-opacity']), 0.15)
    props.color = '#2abf4b'
    assert.equal(paths()[0].props.fill, '#2abf4b')
    assert.equal(paths()[1].props.stroke, '#2abf4b')
  })

  test(`${app}: missing homepage quotes show a placeholder and balances still allow zero`, () => {
    const source = readFileSync(new URL(`../../${app}/src/views/Home.vue`, import.meta.url), 'utf8')
    const functions = ['formatPrice', 'getRealTimePrice', 'formatQuotePrice'].map(name => source.match(new RegExp(`function ${name}\\([\\s\\S]*?\\r?\\n\\}`))?.[0]).join('\n')
    let livePrice = 0, points = []
    const display = runInNewContext(stripTypeScriptTypes(functions) + '; ({ formatPrice, formatQuotePrice })', {
      marketStore: { getPrice: () => livePrice }, homeSparkline: () => ({ points }),
    })
    const symbol = { symbol: 'JPY=X', currentPrice: 0, pricePrecision: 3 }
    assert.equal(display.formatQuotePrice(symbol), '—')
    assert.equal(display.formatQuotePrice({ ...symbol, currentPrice: 'invalid' }), '—')
    assert.equal(display.formatQuotePrice({ ...symbol, currentPrice: Infinity }), '—')
    assert.equal(display.formatQuotePrice({ ...symbol, currentPrice: 158.16 }), '158.160')
    if (app === 'exchange-frontend') {
      points = [158.12, 158.16]
      assert.equal(display.formatQuotePrice(symbol), '158.160')
    }
    livePrice = 158.19
    assert.equal(display.formatQuotePrice(symbol), '158.190')
    assert.equal(display.formatPrice(0, 2), '0.00')
  })
}
