// Run independently of unrelated global locale checks: node --test scripts/check-trade-i18n.cjs
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const ts = require('../exchange-frontend/node_modules/typescript')
const root = path.resolve(__dirname, '..')
const english = 'Quantity must satisfy instrument minimum and step'
const chinese = '数量不符合品种最小数量或步长'
const additions = {
  "Trial credit": "体験資金",
  "Funding source": "資金の種類",
  "Funding account": "資金口座",
  "Time remaining": "有効期限まで",
  "No expiry": "期限なし",
  "Trial credit unavailable. Cash account selected; existing orders can still close.": "体験資金を利用できません。通常の取引口座に切り替えました。既存の注文は引き続き決済できます。",
  "Cancel request": "申請を取り消す",
  "Cancelled": "取消済み",
  "Have an invitation code?": "招待コードをお持ちですか？",
  "Claim again (new reward)": "再度受け取る（新しい特典）",
  "History repair failed; retry later": "履歴の補完に失敗しました。後ほど再試行してください",
  "Switch to advanced": "アドバンス版に切り替え",
  "Switch to classic": "クラシック版に切り替え",
  "Home": "ホーム",
  "Me": "マイページ",
  "Indices": "指数",
  "Risk rate": "証拠金維持率",
  "Customer support": "カスタマーサポート",
  "Message center": "メッセージセンター",
  "Send image": "画像を送信",
  "1W": "1週間",
  "1MO": "1か月",
  "Quantity": "数量",
  "Open / close each": "新規建て・決済それぞれ",
  "Round-trip commission reserved; settled on close, refunded on cancellation": "往復手数料を確保し、決済時に精算します。注文取消時には返還します",
  "Reserved round-trip fee": "確保済み往復手数料",
  "Per input unit": "入力単位あたり",
  "Round-trip reserved; settled on close, refunded on cancellation": "往復手数料を確保し、決済時に精算します。注文取消時には返還します",
  "Your approved identity is reused. Add loan contact details and a photo holding your ID.": "承認済みの本人確認情報を再利用します。借入の連絡先情報と証明書を持った写真を追加してください。",
  "Previous details are incomplete or do not match your identity. Please resubmit.": "以前の資料に不足があるか、本人確認情報と一致していません。再提出してください。"
}
const japanese = '数量は銘柄の最小数量および数量刻みに従って指定してください'

function load(file) {
  const req = createRequire(file), module = { exports: {} }
  const js = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText
  new Function('require', 'module', 'exports', js)(name => name.startsWith('.')
    ? load(path.resolve(path.dirname(file), name.endsWith('.ts') ? name : name + '.ts'))
    : req(name), module, module.exports)
  return module.exports
}

test('Trade quantity validation uses the translated message key', () => {
  const source = fs.readFileSync(path.join(root, 'exchange-frontend/src/views/Trade.vue'), 'utf8')
  assert.ok(source.includes(`text('${chinese}', '${english}')`))
})

for (const project of ['exchange-frontend', 'exchange-pc']) {
  test(`${project}: trial card uses the locale store instead of a hard-coded English label`, () => {
    const source = fs.readFileSync(path.join(root, project, 'src/components/TrialAccountCard.vue'), 'utf8')
    assert.ok(source.includes("locale.text('体验金', 'Trial credit')"))
    assert.ok(!source.includes('TRIAL CREDIT'))
  })
  test(`${project}: quantity validation resolves Japanese and preserves English`, () => {
    const storage = new Map()
    global.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, String(value)) }
    global.document = { createElement: () => ({}), documentElement: { lang: '', dir: '' } }
    const req = createRequire(path.join(root, project, 'package.json'))
    const { createPinia, setActivePinia } = req('pinia')
    setActivePinia(createPinia())
    const { useLocaleStore } = load(path.join(root, project, 'src/store/locale.ts'))
    const store = useLocaleStore()
    store.setLocale('ja')
    assert.equal(store.text(chinese, english), japanese)
    for (const [key, value] of Object.entries(additions)) assert.equal(store.text('', key), value, key)
    store.setLocale('en')
    assert.equal(store.text(chinese, english), english)
    for (const key of Object.keys(additions)) assert.equal(store.text('', key), key)
  })
}
