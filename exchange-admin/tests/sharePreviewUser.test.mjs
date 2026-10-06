// Run with: node --experimental-strip-types tests/sharePreviewUser.test.mjs
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import * as design from '../../exchange-frontend/src/utils/shareTemplateDesign.ts'
import { displaySymbol } from '../src/utils/displaySymbol.ts'
const require = createRequire(new URL('../package.json', import.meta.url))
const vue = require('vue'), ts = require('typescript'), QRCode = require('qrcode')
const source = fs.readFileSync(new URL('../src/components/ShareTemplateEditor.vue', import.meta.url), 'utf8')
const script = source.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import[\s\S]*?from [^\n]+\n/gm, '').replaceAll('import.meta.env.BASE_URL', "'/'")
const js = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
const calls = [], draws = [], watches = [], errors = []
let allowed = true, unmount
const auth = { token: 'QA' }
const bindings = {
  ...design, ...vue, QRCode, displaySymbol,
  defineProps: () => ({ rule: { id: 'light', name: 'Preview', base: 'light', focus: 'rate', languages: ['*'] }, editable: true, language: 'en' }),
  defineEmits: () => () => {}, useAuthStore: () => auth, can: () => allowed,
  request: { get: (url, options) => new Promise((resolve, reject) => calls.push({ url, params: options?.params, resolve, reject })) },
  ElMessage: { error: message => errors.push(message), warning() {} }, ElMessageBox: {},
  createShareAssetLoader: () => ({ images: async () => new Map(), dispose() {} }),
  shareBackgrounds: {}, shareReturn: () => 0, shareCopy: () => ({}),
  drawSharePoster: (_canvas, order, _options, _copy, _brand, _timezone, qr) => draws.push({ order, qr: qr?.src }),
  watch: (value, callback) => watches.push({ value, callback }), onMounted() {}, onBeforeUnmount: callback => { unmount = callback },
  document: { fonts: { ready: Promise.resolve() }, createElement: () => ({ toDataURL: () => 'poster-' + draws.length }) },
  Image: class { src = ''; async decode() {} },
}
const app = new Function(...Object.keys(bindings), js + ';return {searchSymbols,searchUsers,chooseUser,users,userId,sample,qrImage,inviteUrl,inviteLoading,inviteError,image};')(...Object.values(bindings))
const info = id => ({ userId: id, nickname: 'User ' + id, email: id + '@example.com', inviteCode: 'OWN' + id, inviteUrl: 'https://tenant.example.com/?register=1&invite=OWN' + id })
const select = id => { app.userId.value = id; return app.chooseUser(id) }
const last = () => calls.at(-1)

// Autocomplete uses the same read-only market search as the trading screens.
let suggestions = [], published = 0
const suggest = rows => { suggestions = rows; published++ }
const lookup = keyword => { app.sample.symbol = keyword; app.searchSymbols(keyword, suggest) }
const flush = async () => { await Promise.resolve(); await Promise.resolve() }
lookup(' BTC ')
assert.equal(last().url, '/market/search'); assert.deepEqual(last().params, { keyword: 'BTC' })
last().resolve({ list: [{ symbol: 'BTCUSDT', name: '比特币' }] }); await flush()
assert.deepEqual(suggestions, [{ value: 'BTCUSDT', label: 'BTCUSDT · 比特币' }])
lookup('美元/日元')
assert.equal(last().params.keyword, '美元/日元')
last().resolve({ list: [{ symbol: 'JPY=X', category: 'Forex', nameEn: 'USD/JPY' }] }); await flush()
assert.equal(suggestions[0].value, 'JPY=X'); assert.ok(suggestions[0].label.includes('USD/JPY'))
app.sample.symbol = suggestions[0].value
assert.equal(design.previewShareOrder(app.sample).symbol, 'JPY=X', 'selection keeps the provider code')
lookup('old'); const oldSymbol = last()
lookup('new'); const newSymbol = last()
newSymbol.resolve({ list: [{ symbol: 'NEW' }] }); await flush()
const beforeLate = published
oldSymbol.resolve({ list: [{ symbol: 'OLD' }] }); await flush()
assert.equal(published, beforeLate); assert.equal(suggestions[0].value, 'NEW')
lookup('pending'); const beforeEmpty = last(), requests = calls.length
lookup(' ')
assert.equal(calls.length, requests); assert.deepEqual(suggestions, [])
beforeEmpty.resolve({ list: [{ symbol: 'PENDING' }] }); await flush()
assert.deepEqual(suggestions, [])
lookup('BTC'); const beforeTyping = last()
app.sample.symbol = 'ETH' // Input changes before the debounce starts the next request.
beforeTyping.resolve({ list: [{ symbol: 'BTCUSDT' }] }); await flush()
assert.deepEqual(suggestions, [], 'old keyword must not appear while typing a new one')
lookup('unknown'); last().resolve({ list: [] }); await flush(); assert.deepEqual(suggestions, [])
lookup('BTC'); last().reject(new Error('品种查询失败')); await flush()
assert.deepEqual(suggestions, []); assert.deepEqual(errors.splice(0), ['品种查询失败'])
lookup('BTC'); last().resolve({ list: [{ symbol: 'BTCUSDT' }] }); await flush()
assert.equal(suggestions[0].value, 'BTCUSDT', 'failed search can be retried')
app.sample.symbol = 'BTCUSDT'

let pending = app.searchUsers(' 700 ')
assert.deepEqual(last().params, { page: 1, size: 20, userId: '700' })
last().resolve({ list: [{ id: 700, nickname: 'Cached', email: 'old@example.com' }] }); await pending
pending = select(700)
assert.equal(last().url, '/admin/users/700/invite-preview')
assert.equal(app.image.value, '', 'old poster is removed while the selected user loads')
last().resolve(info(700)); await pending
assert.equal(app.sample.userEmail, '700@example.com', 'fresh selected-user identity wins')
assert.equal(app.inviteUrl.value, info(700).inviteUrl)
assert.equal(draws.at(-1).qr, await QRCode.toDataURL(info(700).inviteUrl, { width: 320, margin: 2, errorCorrectionLevel: 'M' }))

pending = app.searchUsers(' alice@example.com ')
assert.deepEqual(last().params, { page: 1, size: 20, keyword: 'alice@example.com' })
last().resolve({ list: [] }); await pending
const oldSearch = app.searchUsers('old@example.com'), oldCall = last()
const newSearch = app.searchUsers('new@example.com'), newCall = last()
newCall.resolve({ list: [{ id: 702 }] }); await newSearch
oldCall.resolve({ list: [{ id: 701 }] }); await oldSearch
assert.equal(app.users.value[0].id, 702, 'late searches cannot replace newer results')
const count = calls.length
await app.searchUsers(' '); assert.equal(calls.length, count); assert.deepEqual(app.users.value, [])

const first = select(701), firstCall = last()
assert.equal(app.qrImage.value, undefined)
const second = select(702), secondCall = last()
secondCall.resolve(info(702)); await second
firstCall.resolve(info(701)); await first
assert.equal(app.inviteUrl.value, info(702).inviteUrl, 'late invitation cannot overwrite the selected user')
assert.equal(draws.at(-1).qr, app.qrImage.value.src)

pending = select(703); const beforeClear = last()
await select(undefined)
assert.equal(app.inviteUrl.value, ''); assert.equal(app.qrImage.value, undefined); assert.equal(draws.at(-1).qr, undefined)
beforeClear.resolve(info(703)); await pending
assert.equal(app.inviteUrl.value, '', 'clear also invalidates pending invitation requests')

pending = select(704); last().reject(new Error('域名未验证')); await pending
assert.equal(app.inviteError.value, '域名未验证'); assert.equal(app.image.value, ''); assert.equal(app.qrImage.value, undefined)
pending = select(704); last().resolve(info(704)); await pending
assert.equal(app.inviteError.value, ''); assert.equal(app.inviteUrl.value, info(704).inviteUrl)
pending = select(705); last().resolve(info(706)); await pending
assert.match(app.inviteError.value, /用户邀请信息无效/); assert.equal(app.image.value, '')

allowed = false
const beforeDenied = calls.length
await app.searchUsers('700'); await select(700)
assert.equal(calls.length, beforeDenied); assert.match(app.inviteError.value, /权限/)
allowed = true
lookup('BTC'); const sessionSymbol = last(), sessionPublished = published
pending = select(707); const beforeSession = last()
auth.token = 'NEW'; watches.find(watch => typeof watch.value === 'function').callback()
beforeSession.resolve(info(707)); await pending
assert.equal(app.userId.value, undefined); assert.equal(app.inviteUrl.value, '')
sessionSymbol.resolve({ list: [{ symbol: 'BTCUSDT' }] }); await flush(); assert.equal(published, sessionPublished)

lookup('ETH'); const closingSymbol = last(), closingPublished = published
pending = select(708); const beforeClose = last(); unmount()
closingSymbol.resolve({ list: [{ symbol: 'ETHUSDT' }] }); await flush(); assert.equal(published, closingPublished)
const beforeDisposed = calls.length; lookup('BTC'); assert.equal(calls.length, beforeDisposed)
beforeClose.resolve(info(708)); await pending
assert.equal(app.qrImage.value, undefined, 'closed preview ignores pending invitation responses')
assert.ok(calls.every(call => call.url === '/market/search' || call.url.startsWith('/admin/users')), 'preview only calls read-only market/user endpoints')
assert.deepEqual(errors, [])
assert.ok(source.includes('搜索用户 ID / 邮箱')); assert.ok(source.includes(':fetch-suggestions="searchSymbols"'))
console.log('PASS: live symbol lookup, names, raw provider code, blank/no matches, stale search, retry, session/close; ID/email lookup, QR encoding, user switch, permissions; no writes')
