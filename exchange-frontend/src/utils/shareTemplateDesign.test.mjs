import assert from 'node:assert/strict'
import { createShareDesign, applyShareFocus, parseShareTemplateConfig, previewShareOrder, validateShareDesign, drawShareDesign, shareFields, createShareShape, shareShapeSvg, shareDesignLayers } from './shareTemplateDesign.ts'
import { createShareAssetLoader, fetchShareImage } from './shareTemplateAssets.ts'
import { shareCopy, shareReturn } from './orderShare.ts'

const input = { symbol: 'BTCUSDT', buy: true, leverage: 10, openPrice: 60000, closePrice: 63000, openTime: '2026-09-30T09:00', closeTime: '2026-09-30T10:00', userName: 'Test user', userEmail: 'test@example.com' }
assert.ok(Math.abs(shareReturn(previewShareOrder(input)) - 50) < 1e-10)
assert.ok(Math.abs(shareReturn(previewShareOrder({ ...input, buy: false })) + 50) < 1e-10)
assert.ok(Math.abs(shareReturn(previewShareOrder({ ...input, leverage: 2 })) - 10) < 1e-10)
assert.equal(shareReturn(previewShareOrder({ ...input, closePrice: 60000 })), 0)
for (const patch of [{ symbol: '' }, { openPrice: 0 }, { closePrice: NaN }, { leverage: Infinity }, { openPrice: 1e-305, closePrice: 1 }, { closeTime: '2026-09-29T10:00' }]) assert.throws(() => previewShareOrder({ ...input, ...patch }))
assert.equal(parseShareTemplateConfig(null).templates.length, 16)
assert.deepEqual(parseShareTemplateConfig('gold,light').templates.map(row => row.id), ['gold', 'light'])
assert.equal(parseShareTemplateConfig('{"version":2,"focus":"rate","templates":[{"id":"light","languages":["*"]}]}').templates[0].focus, 'rate')
const design = createShareDesign('light'), original = structuredClone(design)
applyShareFocus(design); assert.equal(design.boxes.find(box => box.field === 'amount').fontSize, 114)
applyShareFocus(design); assert.deepEqual(design, original)
const rateOnly = structuredClone(design)
rateOnly.boxes.find(box => box.field === 'amount').visible = false
applyShareFocus(rateOnly); validateShareDesign(rateOnly)
assert.equal(rateOnly.boxes.find(box => box.field === 'rate').visible, true, 'Emphasis must not hide the required return field')
assert.equal(rateOnly.boxes.find(box => box.field === 'amount').visible, false)
const custom = { id: 'custom-test', name: 'English layout', base: 'light', enabled: true, focus: 'rate', languages: ['en'], design }
const fallback = { id: 'dark', name: 'Dark', base: 'dark', focus: 'amount', enabled: true, languages: ['*'] }
const config = { version: 3, templates: [custom, fallback] }
assert.deepEqual(parseShareTemplateConfig(JSON.stringify(config)).templates, [custom, fallback])
assert.equal(parseShareTemplateConfig(JSON.stringify({ ...config, templates: [{ ...custom, enabled: false }, fallback] })).templates[0].enabled, false, 'Disabled custom layout must survive reload')
for (const bad of ['', 'unknown', 'light,light', 'null', '{"version":3,"templates":[]}', JSON.stringify({ version: 3, templates: [custom] })]) assert.throws(() => parseShareTemplateConfig(bad))
for (const mutate of [d => d.boxes[0].field = 'script', d => d.boxes[0].x = 3000, d => d.boxes[0].color = 'url(https://evil)', d => d.boxes[3].visible = false, d => d.boxes.push({ ...d.boxes[0] }), d => d.html = '<script>']) {
  const broken = structuredClone(design); mutate(broken); assert.throws(() => validateShareDesign(broken))
}
const draws = [], context = { save() {}, restore() {}, beginPath() {}, rect() {}, clip() {}, fillRect() {}, drawImage() {},
  measureText: text => ({ width: text.length * 10 }), fillText(text, x, y) { draws.push({ text, x, y, color: this.fillStyle, font: this.font }) } }
const canvas = { getContext: () => context }
for (const field of ['userName', 'userEmail']) design.boxes.find(box => box.field === field).visible = true
drawShareDesign(canvas, design, previewShareOrder(input), shareCopy('en'), 'EXCHANGE', 'UTC', 50, 'both', 'en', false)
assert.ok(draws.some(row => row.text === '+50.00%'))
assert.ok(draws.some(row => row.text === '10×'))
assert.ok(!draws.some(row => row.text.includes('test@example.com') || row.text.includes('Test user')), 'Personal details are opt-in')
draws.length = 0
const rate = design.boxes.find(box => box.field === 'rate'); rate.x = 92; rate.y = 420; rate.color = '#123456'; rate.fontSize = 70
drawShareDesign(canvas, design, previewShareOrder(input), shareCopy('ja'), 'EXCHANGE', 'UTC', 50, 'both', 'ja', true)
assert.ok(draws.some(row => row.text === 'test@example.com'))
assert.ok(draws.some(row => row.text === shareCopy('ja').rate))
assert.deepEqual(draws.find(row => row.text === '+50.00%'), { text: '+50.00%', x: 92, y: 456, color: '#123456', font: '800 70px "Noto Sans JP", "Microsoft YaHei", Arial, sans-serif' })
assert.equal(design.boxes.length, Object.keys(shareFields).length)
const layered = createShareDesign('light'); layered.artwork = false
layered.decorations = ['line', 'ellipse', 'rect', 'roundRect'].map(createShareShape)
for (const shape of layered.decorations) assert.ok(decodeURIComponent(shareShapeSvg(shape)).includes('<svg xmlns='))
const imageLayer = { ...createShareShape('rect'), type: 'image', name: 'Logo', src: '/api/uploads/images/1/staff/9/logo.png', fit: 'contain', fill: 'none', stroke: 'none' }
layered.decorations.push(imageLayer); validateShareDesign(layered)
assert.equal(shareDesignLayers(layered)[0].item.type, 'line', 'New decorations default behind data')
layered.layers = shareDesignLayers(layered).map(layer => layer.key).reverse()
assert.equal(shareDesignLayers(layered).at(-1).item.type, 'line')
assert.deepEqual(parseShareTemplateConfig(JSON.stringify({ ...config, templates: [{ ...custom, design: layered }, fallback] })).templates[0].design, layered)
for (const mutate of [d => d.layers.push(d.layers[0]), d => d.layers[0] = 'missing', d => d.layers.pop(), d => d.decorations[1].id = d.decorations[0].id,
  d => d.decorations[0].x = 2159, d => d.decorations[0].fill = '<script>', d => d.decorations[0].opacity = 2, d => d.decorations[0].svg = '<script>',
  d => d.decorations.at(-1).src = 'https://evil.example/logo.png', d => d.decorations.at(-1).src = '/api/uploads/images/1/user/9/id.png',
  d => d.decorations.at(-1).src = '/api/uploads/images/1/staff/9/../../user.png', d => d.decorations.at(-1).src = '/api/uploads/images/1/staff/9/logo.svg',
  d => d.decorations.at(-1).fit = 'evil', d => d.decorations = Array.from({ length: 41 }, () => createShareShape('rect'))]) {
  const bad = structuredClone(layered); mutate(bad); assert.throws(() => validateShareDesign(bad))
}
const events = []
Object.assign(context, { fill() { events.push(this.fillStyle) }, stroke() { events.push(this.strokeStyle) }, ellipse() {}, roundRect() {}, moveTo() {}, lineTo() {},
  drawImage(...args) { events.push(['image', ...args.slice(1)]) } })
assert.throws(() => drawShareDesign(canvas, layered, previewShareOrder(input), shareCopy('en'), 'TEST', 'UTC', 50, 'both', 'en'), /未加载/)
const img = { naturalWidth: 800, naturalHeight: 400 }
drawShareDesign(canvas, layered, previewShareOrder(input), shareCopy('en'), 'TEST', 'UTC', 50, 'both', 'en', false, undefined, undefined, new Map([[imageLayer.src, img]]))
assert.equal(events.filter(Array.isArray).length, 1)
assert.ok(events.includes('#dce9df'))
const OriginalImage = globalThis.Image, originalFetch = globalThis.fetch
globalThis.Image = class { naturalWidth = 4; naturalHeight = 4; async decode() {} }
let identity = 'first', requests = 0
const loader = createShareAssetLoader(async () => { requests++; return new Blob(['png'], { type: 'image/png' }) }, () => identity)
assert.equal((await loader.images(layered)).size, 1); await loader.images(layered); assert.equal(requests, 1)
identity = 'second'; await loader.images(layered); assert.equal(requests, 2)
loader.dispose(); await assert.rejects(() => loader.images(layered), /已关闭/)
let finish
const stale = createShareAssetLoader(() => new Promise(resolve => { finish = resolve }), () => identity)
const pending = stale.images(layered); identity = 'third'; finish(new Blob(['png'], { type: 'image/png' })); await assert.rejects(pending, /登录状态/); stale.dispose()
let fetched
globalThis.fetch = async (url, options) => { fetched = { url, options }; return new Response(new Blob(['png'], { type: 'image/png' })) }
await fetchShareImage(imageLayer.src, 'token'); assert.equal(fetched.url, imageLayer.src); assert.equal(fetched.options.headers.Authorization, 'Bearer token'); assert.equal(fetched.options.redirect, 'error')
await assert.rejects(() => fetchShareImage('https://evil.example', 'token'), /地址无效/)
globalThis.Image = OriginalImage; globalThis.fetch = originalFetch
console.log('PASS: leveraged previews, legacy/v3 persistence, fixed fields, consent, 4 vector shapes, layer ordering, images, strict validation and account-scoped asset loading')
