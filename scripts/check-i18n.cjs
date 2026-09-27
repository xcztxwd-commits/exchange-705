// Run: node scripts/check-i18n.cjs (after installing the existing frontend dependencies).
// Structural checks are not linguistic certification; see docs/i18n/terminology-sources.md.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const root = path.resolve(__dirname, '..')
const ts = require('../exchange-frontend/node_modules/typescript')
const storage = new Map()
global.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)) }
global.document = { createElement: () => ({}), documentElement: { lang: '', dir: '' } }
function load(file) {
  const req = createRequire(file), module = { exports: {} }
  const js = ts.transpileModule(fs.readFileSync(file, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  new Function('require', 'module', 'exports', 'console', js)(name => name.startsWith('.') ? load(path.resolve(path.dirname(file), name.endsWith('.ts') ? name : name + '.ts')) : req(name), module, module.exports, { log() {} })
  return module.exports
}
function readMessages(project) {
  const source = fs.readFileSync(path.join(root, project, 'src/store/locale.ts'), 'utf8')
  const sf = ts.createSourceFile('locale.ts', source, ts.ScriptTarget.Latest, true)
  let object
  function visit(node) {
    if (ts.isVariableDeclaration(node) && node.name.getText(sf) === 'messages') object = node.initializer
    ts.forEachChild(node, visit)
  }
  visit(sf)
  const result = {}
  for (const lang of object.properties) {
    const dictionary = {}
    for (const prop of lang.initializer.properties) {
      assert.ok(!Object.hasOwn(dictionary, prop.name.text), 'Duplicate: ' + lang.name.text + '.' + prop.name.text)
      dictionary[prop.name.text] = prop.initializer.text
    }
    result[lang.name.text] = dictionary
  }
  return result
}
function sourceFiles(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? sourceFiles(path.join(dir, entry.name)) : /\.(vue|ts)$/.test(entry.name) ? [path.join(dir, entry.name)] : [])
}
const projects = ['exchange-frontend', 'exchange-pc']
const results = {}, dictionaries = {}
for (const project of projects) {
  const req = createRequire(path.join(root, project, 'package.json'))
  const { setActivePinia, createPinia } = req('pinia'), { computed } = req('vue')
  const { useLocaleStore } = load(path.join(root, project, 'src/store/locale.ts'))
  const { uiMessages, uiAliases } = load(path.join(root, project, 'src/store/uiMessages.ts'))
  const { chartLocale } = load(path.join(root, project, 'src/utils/chartLocale.ts'))
  const all = dictionaries[project] = readMessages(project)
  assert.equal(Object.keys(all).length, 19)
  setActivePinia(createPinia())
  const store = useLocaleStore(), liveLabel = computed(() => store.t('marketPrice'))
  let lookups = 0
  for (const [lang, dictionary] of Object.entries(all)) {
    assert.deepEqual(Object.keys(dictionary).sort(), Object.keys(all.en).sort(), lang + ' key coverage')
    store.setLocale(lang)
    assert.equal(document.documentElement.lang, lang)
    assert.equal(document.documentElement.dir, lang === 'ar' ? 'rtl' : 'ltr')
    assert.equal(liveLabel.value, dictionary.marketPrice)
    assert.equal(storage.get('locale'), lang)
    for (const [key, value] of Object.entries(dictionary)) {
      assert.ok(value && !value.includes('\uFFFD'), lang + '.' + key + ' damaged/empty')
      assert.equal(store.t(key), value)
      lookups++
    }
    for (const [key, value] of Object.entries(uiMessages[lang])) {
      assert.ok(value && !value.includes('\uFFFD'), lang + '.' + key)
      assert.deepEqual([...value.matchAll(/\{(\w+)\}/g)].map(m => m[1]).sort(), [...key.matchAll(/\{(\w+)\}/g)].map(m => m[1]).sort(), lang + ' interpolation: ' + key)
    }
    const preview = store.text('', '≈ {amount} USD (rate at submission applies)', { amount: '123.45' })
    assert.ok(preview.includes('123.45') && !preview.includes('{amount}'))
    const chart = chartLocale(lang, store.t)
    assert.equal(chart.open, dictionary.chartOpen + ': ')
    assert.equal(chart.close, dictionary.chartClose + ': ')
    assert.ok(chart.volume && chart.turnover && chart.change)
    store.loadLocale()
    assert.equal(store.locale, lang)
    assert.equal(store.text('', 'Close'), uiMessages[lang].Close, 'Dialog close must use its UI context')
  }
  for (const file of sourceFiles(path.join(root, project, 'src'))) {
    if (file.endsWith('uiMessages.ts')) continue
    const source = fs.readFileSync(file, 'utf8')
    for (const match of source.matchAll(/\btext\(\s*'([^']*)'\s*,\s*'([^']*)'/g)) {
      assert.ok(Object.hasOwn(uiMessages.ja, match[2]) || Object.hasOwn(uiAliases, match[2]), 'Missing Japanese UI translation: ' + file + ': ' + match[2])
    }
  }
  for (const key of Object.values(uiAliases)) assert.ok(Object.hasOwn(all.en, key), 'Invalid alias ' + key)
  store.setLocale('ja')
  // Stable indicator identifiers are distinct from localized explanatory names.
  const { indicatorCatalog } = load(path.join(root, project, 'src/utils/chartPreferences.ts'))
  assert.deepEqual(indicatorCatalog.map(item => item.name), ['MA','EMA','SMA','BOLL','SAR','BBI','VOL','MACD','RSI','KDJ','DMI','CCI','OBV','WR','ROC','MTM','AO','BIAS','BRAR','CR','DMA','EMV','PSY','PVT','TRIX','VR'])
  assert.equal(store.text('', 'Triple exponential average'), 'TRIX（トリックス）')
  assert.equal(store.text('', 'Price volume trend'), 'PVT（プライス・ボリューム・トレンド）')
  assert.equal(store.text('', 'PIXEL FLOW'), 'PIXEL FLOW')
  assert.ok(store.text('', 'Smoothed moving average').includes('単純移動平均線とは異なります'))
  for (const label of ['MA','EMA','MACD','RSI','BTC','ETH','USDT','USD','XAUUSD','TRC20','ERC20','SWIFT','FOREX EXCHANGE']) {
    assert.equal(store.text('', label), label, 'Stable identifier: ' + label)
  }
  assert.equal(store.text('', 'The loan amount must be at least {amount}.', {amount: '100.50'}), '借入金額は100.50以上にしてください')
  assert.equal(store.text('', 'The subscription amount must not exceed {amount}.', {amount: '200'}), '申込金額は200以下にしてください')
  assert.equal(store.t('rejected'), '不承認')
  assert.equal(store.t('kycVerified'), '本人確認済み')
  assert.equal(store.text('', 'Period close'), '期間末')
  assert.equal(store.text('', 'Valuation unavailable'), '評価額を算出できません')
  assert.ok(store.t('governingLawDescription').includes('執行'))
  assert.ok(store.t('severabilityDescription').includes('その条項は無効'))
  assert.equal(store.t('riskRate'), '証拠金維持率')
  assert.equal(store.t('openPrice'), '新規約定価格')
  assert.equal(store.t('chartOpen'), '始値')
  assert.equal(store.text('', 'Market'), '成行')
  assert.equal(store.text('', 'Limit'), '指値')
  assert.equal(store.text('', 'Close'), '閉じる')
  assert.equal(store.text('1m', '1m'), '1分')
  assert.equal(store.text('1h', '1h'), '1時間')
  assert.equal(store.text('', 'Buy down'), '下落を予想')
  assert.equal(store.categoryLabel('US'), '米国株式')
  assert.equal(store.categoryLabel('Custom', 'User-owned label'), 'User-owned label')
  assert.equal(store.backendMessage('密码错误'), all.ja.passwordWrong)
  assert.equal(store.backendMessage('Unknown diagnostic 42'), 'Unknown diagnostic 42')
  assert.equal(store.backendMessage('申购金额不能小于100.50'), '申込金額は100.50以上にしてください')
  assert.equal(store.backendMessage('提交失败: 参数不完整'), '送信に失敗しました：必要な情報が不足しています')
  assert.equal(store.backendMessage('提交失败: New diagnostic'), '送信に失敗しました：New diagnostic')
  assert.equal(store.backendMessage('贷款金额不能大于200'), '借入金額は200以下にしてください')
  assert.equal(store.backendMessage('资金账户余额不足，当前余额: 1.50，需要: 2.00'), '資金口座の残高が不足しています。現在の残高：1.50、必要額：2.00')
  assert.equal(store.backendMessage('__proto__'), '__proto__')
  for (const lang of ['ja', 'en', 'zh-TW']) {
    store.setLocale(lang)
    for (const message of ['未知错误', 'CUSTOM_DIAGNOSTIC', '提交失败: 数据库内部错误', '__proto__']) {
      assert.equal(store.backendMessage(message, true), 'Unable to confirm the result. Check the relevant history or status before submitting again.')
    }
  }
  store.setLocale('ja')
  assert.equal(store.backendMessage('密码错误', true), all.ja.passwordWrong)
  assert.equal(store.backendMessage('登录已失效，请重新登录'), 'ログインの有効期限が切れました。再度ログインしてください')
  assert.equal(store.text('', 'The loan term is {days} days.', {days: 30}), '借入期間は30日です。')
  assert.equal(store.text('', 'Close the {symbol} position?', {symbol: 'USD/JPY'}), 'USD/JPYのポジションを決済しますか？')
  assert.equal(store.t('unknown-key'), 'unknown-key')
  store.setLocale('invalid')
  assert.equal(store.locale, 'ja')
  storage.set('locale', '__proto__')
  store.loadLocale()
  assert.equal(store.locale, 'en')
  results[project] = { locales: 19, dictionaryLookups: lookups, messagesPerLocale: Object.keys(all.en).length, japaneseUiEntries: Object.keys(uiMessages.ja).length }
}
for (const [lang, values] of Object.entries(dictionaries['exchange-frontend'])) {
  for (const [key, value] of Object.entries(values)) assert.equal(dictionaries['exchange-pc'][lang][key], value, `PC/mobile drift: ${lang}.${key}`)
}
for (const file of ['src/store/uiMessages.ts', 'src/utils/chartLocale.ts', 'src/utils/serverJapanese.ts']) {
  assert.equal(fs.readFileSync(path.join(root, projects[0], file), 'utf8'), fs.readFileSync(path.join(root, projects[1], file), 'utf8'), 'PC/mobile drift: ' + file)
}
console.log(JSON.stringify({ passed: true, ...results }, null, 2))
