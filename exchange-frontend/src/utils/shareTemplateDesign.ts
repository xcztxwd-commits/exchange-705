import type { ShareCopy, ShareFocus, ShareOrder, ShareTemplate } from './orderShare.ts'

export const shareTemplateCatalog = [
  ['light', '极简方刊'], ['dark', '专业黑'], ['chart', '行情图'], ['gold', '黑金横幅'],
  ['globe', '环球'], ['architecture', '建筑'], ['city', '城市'], ['referenceGold', '黑金原版'],
  ['referenceWhite', '白色原版'], ['referenceTerminal', '行情原版'], ['launch', '启航'],
  ['aurora', '青绿星环'], ['racing', '斜切竞速'], ['receipt', '纸感票据'], ['journal', '行情终端'], ['voyage', '旅程'],
] as const
export const shareTemplateLanguages = [
  ['zh-TW', '中文（繁体）'], ['en', '英语'], ['ja', '日语'], ['fr', '法语'], ['de', '德语'], ['ru', '俄语'],
  ['es', '西班牙语'], ['pt', '葡萄牙语'], ['it', '意大利语'], ['ar', '阿拉伯语'], ['tr', '土耳其语'],
  ['id', '印度尼西亚语'], ['my', '缅甸语'], ['hi', '印地语'], ['cs', '捷克语'], ['pl', '波兰语'],
  ['ko', '韩语'], ['th', '泰语'], ['vi', '越南语'],
] as const
export const shareFields = {
  brand: '品牌', symbol: '品种', direction: '方向', leverage: '杠杆', amount: '盈亏金额', rate: '收益率',
  openPrice: '开仓价', closePrice: '平仓价', openTime: '开仓时间', closeTime: '平仓时间',
  userName: '用户名字', userEmail: '用户邮箱',
} as const
export type ShareField = keyof typeof shareFields
export const requiredShareFields: ShareField[] = ['symbol', 'leverage', 'rate', 'openPrice', 'closePrice', 'openTime', 'closeTime']
export interface ShareBox {
  field: ShareField; x: number; y: number; width: number; height: number; fontSize: number
  color: string; align: 'left' | 'center' | 'right'; weight: number; label: boolean; visible: boolean
}
export const shareShapes = { line: '线段', ellipse: '圆形 / 椭圆', rect: '长方形', roundRect: '圆角矩形' } as const
export interface ShareDecoration {
  id: string; name: string; type: keyof typeof shareShapes | 'image'; x: number; y: number; width: number; height: number
  fill: string; stroke: string; strokeWidth: number; radius: number; opacity: number; visible: boolean; src?: string; fit?: 'contain' | 'cover' | 'stretch'
}
export interface ShareDesign { width: number; height: number; background: string; artwork: boolean; boxes: ShareBox[]; decorations?: ShareDecoration[]; layers?: string[] }
export type ShareLayer = { key: string; kind: 'field'; item: ShareBox } | { key: string; kind: 'decoration'; item: ShareDecoration }
export interface ShareTemplateRule {
  id: string; name: string; base: ShareTemplate; languages: string[]; focus: ShareFocus; enabled: boolean; design?: ShareDesign
}
const builtins = shareTemplateCatalog.map(([id]) => String(id))
const languages = shareTemplateLanguages.map(([id]) => String(id))
const color = (value: unknown) => typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value)
const number = (value: unknown, min: number, max: number): value is number => typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max
const keys = (value: object, allowed: string[]) => Object.keys(value).every(key => allowed.includes(key))
export const shareImagePath = (value: unknown): value is string => typeof value === 'string' && /^\/api\/uploads\/images\/[1-9][0-9]{0,18}\/staff\/(?:agent-)?-?[0-9]{1,19}\/[a-zA-Z0-9_-]{1,100}\.(png|jpe?g|gif|webp)$/i.test(value)
export function validateShareDecoration(layer: ShareDecoration, width = 2160, height = 2160): void {
  if (!layer || !keys(layer, ['id', 'name', 'type', 'x', 'y', 'width', 'height', 'fill', 'stroke', 'strokeWidth', 'radius', 'opacity', 'visible', 'src', 'fit'])
    || typeof layer.id !== 'string' || !/^layer-[a-z0-9-]{1,48}$/.test(layer.id) || typeof layer.name !== 'string' || !layer.name.trim() || layer.name.length > 80
    || ![...Object.keys(shareShapes), 'image'].includes(layer.type) || !number(layer.x, 0, width) || !number(layer.y, 0, height)
    || !number(layer.width, 2, width) || !number(layer.height, 2, height) || layer.x + layer.width > width || layer.y + layer.height > height
    || !(color(layer.fill) || layer.fill === 'none') || !(color(layer.stroke) || layer.stroke === 'none')
    || !number(layer.strokeWidth, 0, 100) || !number(layer.radius, 0, 1080) || !number(layer.opacity, 0, 1) || typeof layer.visible !== 'boolean'
    || (layer.type === 'image' ? !shareImagePath(layer.src) || !['contain', 'cover', 'stretch'].includes(layer.fit || '') : layer.src !== undefined || layer.fit !== undefined)) throw new Error('形状 / 图片图层配置无效')
}
export function shareDesignLayers(design: ShareDesign): ShareLayer[] {
  const items: ShareLayer[] = [...(design.decorations || []).map(item => ({ key: item.id, kind: 'decoration' as const, item })),
    ...design.boxes.map(item => ({ key: `box:${item.field}`, kind: 'field' as const, item }))]
  return design.layers ? design.layers.map(key => items.find(layer => layer.key === key)!).filter(Boolean) : items
}
export function createShareShape(type: keyof typeof shareShapes): ShareDecoration {
  return { id: `layer-${crypto.randomUUID()}`, name: shareShapes[type], type, x: 60, y: 380, width: type === 'ellipse' ? 200 : 400,
    height: type === 'line' ? 8 : 200, fill: type === 'line' ? 'none' : '#dce9df', stroke: '#17804c', strokeWidth: 3, radius: type === 'roundRect' ? 24 : 0, opacity: 1, visible: true }
}
// SVG is generated only from validated geometry; arbitrary SVG markup never enters a template.
export function shareShapeSvg(layer: ShareDecoration): string {
  validateShareDecoration({ ...layer, x: 0, y: 0 })
  if (layer.type === 'image') throw new Error('图片不能作为矢量形状导出')
  const w = layer.width, h = layer.height, p = Math.min(layer.strokeWidth / 2, w / 2, h / 2)
  const style = `fill="${layer.fill}" stroke="${layer.stroke}" stroke-width="${layer.strokeWidth}" opacity="${layer.opacity}"`
  const shape = layer.type === 'line' ? `<line x1="${p}" y1="${h / 2}" x2="${w - p}" y2="${h / 2}" ${style}/>`
    : layer.type === 'ellipse' ? `<ellipse cx="${w / 2}" cy="${h / 2}" rx="${w / 2 - p}" ry="${h / 2 - p}" ${style}/>`
      : `<rect x="${p}" y="${p}" width="${w - p * 2}" height="${h - p * 2}" rx="${layer.type === 'roundRect' ? Math.min(layer.radius, w / 2, h / 2) : 0}" ${style}/>`
  return `data:image/svg+xml,${encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${w} ${h}">${shape}</svg>`)}`
}

export function validateShareDesign(design: ShareDesign): void {
  if (!design || !keys(design, ['width', 'height', 'background', 'artwork', 'boxes', 'decorations', 'layers'])
    || !number(design.width, 320, 2160) || !number(design.height, 320, 2160) || !Number.isInteger(design.width) || !Number.isInteger(design.height)
    || !color(design.background) || typeof design.artwork !== 'boolean' || !Array.isArray(design.boxes) || design.boxes.length > Object.keys(shareFields).length) throw new Error('模板画布无效')
  const used = new Set<string>()
  for (const box of design.boxes) {
    if (!box || !keys(box, ['field', 'x', 'y', 'width', 'height', 'fontSize', 'color', 'align', 'weight', 'label', 'visible'])
      || !Object.prototype.hasOwnProperty.call(shareFields, box.field) || used.has(box.field) || !number(box.x, 0, design.width) || !number(box.y, 0, design.height)
      || !number(box.width, 20, design.width) || !number(box.height, 20, design.height) || box.x + box.width > design.width || box.y + box.height > design.height
      || !number(box.fontSize, 8, 200) || !color(box.color) || !['left', 'center', 'right'].includes(box.align)
      || ![400, 500, 600, 700, 800].includes(box.weight) || typeof box.label !== 'boolean' || typeof box.visible !== 'boolean') throw new Error('内容盒子配置无效')
    used.add(box.field)
  }
  if (requiredShareFields.some(field => !design.boxes.some(box => box.field === field && box.visible))) throw new Error('必须保留品种、杠杆、收益率、开平仓价格与时间')
  if (design.decorations !== undefined) {
    if (!Array.isArray(design.decorations) || design.decorations.length > 40 || design.decorations.filter(layer => layer?.type === 'image').length > 16) throw new Error('最多添加 40 个装饰图层，其中图片最多 16 张')
    const ids = new Set<string>()
    for (const layer of design.decorations) { validateShareDecoration(layer, design.width, design.height); if (ids.has(layer.id)) throw new Error('图层编号重复'); ids.add(layer.id) }
  }
  if (design.layers !== undefined) {
    const expected = [...design.boxes.map(box => `box:${box.field}`), ...(design.decorations || []).map(layer => layer.id)]
    if (!Array.isArray(design.layers) || design.layers.length !== expected.length || new Set(design.layers).size !== expected.length
      || design.layers.some(key => !expected.includes(key))) throw new Error('图层顺序必须包含全部内容且不能重复')
  }
}

export function parseShareTemplateConfig(value: string | null): { focus: ShareFocus; templates: ShareTemplateRule[] } {
  const root = value?.trim().startsWith('{') ? JSON.parse(value) : { version: 2, templates: (value == null ? builtins : value.split(',')).map(id => ({ id, languages: ['*'] })) }
  if (!root || ![2, 3].includes(root.version) || !Array.isArray(root.templates) || !root.templates.length || root.templates.length > 32
    || !keys(root, ['version', 'focus', 'templates']) || (root.focus !== undefined && !['amount', 'rate'].includes(root.focus))) throw new Error('模板配置无效')
  const focus: ShareFocus = root.focus || 'amount', used = new Set<string>(), covered = new Set<string>()
  const templates: ShareTemplateRule[] = root.templates.map((row: any) => {
    if (!row || typeof row !== 'object') throw new Error('模板配置无效')
    const builtin = builtins.includes(row.id)
    if (!keys(row, root.version === 2 ? ['id', 'languages'] : ['id', 'name', 'base', 'languages', 'focus', 'enabled', 'design'])
      || typeof row.id !== 'string' || (!builtin && (root.version !== 3 || !/^custom-[a-z0-9-]{1,48}$/.test(row.id))) || used.has(row.id)
      || !Array.isArray(row.languages) || !row.languages.length || new Set(row.languages).size !== row.languages.length
      || row.languages.some((code: unknown) => typeof code !== 'string' || (code !== '*' && !languages.includes(code)))
      || (row.languages.includes('*') && row.languages.length !== 1)) throw new Error('模板编号或语言范围无效')
    if (root.version === 3 && (typeof row.name !== 'string' || !row.name.trim() || row.name.length > 80
      || !builtins.includes(row.base) || !['amount', 'rate'].includes(row.focus) || (row.enabled !== undefined && typeof row.enabled !== 'boolean') || (!builtin && !row.design))) throw new Error('模板名称、重心或底版无效')
    if (row.design !== undefined) validateShareDesign(row.design)
    used.add(row.id)
    if (row.enabled !== false) for (const code of row.languages.includes('*') ? languages : row.languages) covered.add(code)
    return { id: row.id, name: row.name || shareTemplateCatalog.find(([id]) => id === row.id)![1], base: row.base || row.id,
      languages: [...row.languages], focus: row.focus || focus, enabled: row.enabled !== false, ...(row.design ? { design: row.design } : {}) }
  })
  if (languages.some(code => !covered.has(code))) throw new Error('每种页面语言至少需要一款可用模板')
  return { focus, templates }
}

export function createShareDesign(base: ShareTemplate, focus: ShareFocus = 'rate'): ShareDesign {
  const light = ['light', 'chart', 'architecture', 'city', 'referenceWhite', 'receipt', 'journal', 'voyage'].includes(base)
  const ink = light ? '#172b35' : '#f5f8fc', background = light ? '#f4f6f3' : '#0b151e'
  const box = (field: ShareField, x: number, y: number, width: number, height: number, fontSize: number, label = true, visible = true): ShareBox =>
    ({ field, x, y, width, height, fontSize, color: ink, align: 'left', weight: 500, label, visible })
  const design: ShareDesign = { width: 1080, height: 1440, background, artwork: true, boxes: [
    box('brand', 80, 50, 920, 70, 44, false), box('symbol', 80, 154, 920, 100, 72, false),
    box('direction', 80, 276, 480, 72, 28, false), box('leverage', 620, 270, 380, 86, 30),
    box('rate', 80, 408, 920, 180, 114), box('amount', 80, 618, 920, 126, 64),
    box('openPrice', 80, 826, 430, 112, 42), box('closePrice', 570, 826, 430, 112, 42),
    box('openTime', 80, 990, 920, 80, 28), box('closeTime', 80, 1100, 920, 80, 28),
    box('userName', 80, 1190, 920, 70, 26, true, false), box('userEmail', 80, 1270, 920, 70, 26, true, false),
  ] }
  for (const field of ['rate', 'amount']) { const metric = design.boxes.find(box => box.field === field)!; metric.weight = 800; metric.color = light ? '#17804c' : '#69d8a3' }
  if (focus === 'amount') applyShareFocus(design)
  return design
}
export function applyShareFocus(design: ShareDesign): void {
  const rate = design.boxes.find(box => box.field === 'rate'), amount = design.boxes.find(box => box.field === 'amount')
  if (!rate || !amount) return
  const { field: _rate, visible: _rateVisible, ...rateStyle } = rate, { field: _amount, visible: _amountVisible, ...amountStyle } = amount
  Object.assign(rate, amountStyle); Object.assign(amount, rateStyle)
}

export interface SharePreviewInput {
  symbol: string; buy: boolean; leverage: number; openPrice: number; closePrice: number
  openTime: string; closeTime: string; userName: string; userEmail: string
}
export function previewShareOrder(input: SharePreviewInput): ShareOrder {
  const start = Date.parse(input.openTime.replace(' ', 'T')), end = Date.parse(input.closeTime.replace(' ', 'T'))
  if (!input.symbol.trim() || input.symbol.length > 80 || !number(input.leverage, 1, 10000)
    || !number(input.openPrice, Number.MIN_VALUE, 1e15) || !number(input.closePrice, Number.MIN_VALUE, 1e15)
    || !Number.isFinite(start) || !Number.isFinite(end) || end < start) throw new Error('请填写有效品种、正数价格、杠杆及开平仓时间（平仓不能早于开仓）')
  const rate = (input.closePrice / input.openPrice - 1) * (input.buy ? 1 : -1) * input.leverage * 100
  if (!Number.isFinite(rate) || !Number.isFinite(rate * 10)) throw new Error('收益率超出范围')
  // Preview is hypothetical and never writes a trade. Settled exports still use recorded P&L / margin.
  return { ...input, symbol: input.symbol.trim(), id: 'PREVIEW', kind: 'contract', profit: rate * 10, margin: 1000,
    quantity: null, fee: null, amount: null, currency: 'USD', openTime: input.openTime.replace('T', ' '), closeTime: input.closeTime.replace('T', ' ') }
}

const profileCopy: Record<string, [string, string, string]> = {
  'zh-TW': ['用戶名字', '用戶郵箱', '在圖片中顯示我的名字與郵箱'], en: ['Name', 'Email', 'Include my name and email in the image'],
  ja: ['名前', 'メール', '画像に名前とメールを含める'], ko: ['이름', '이메일', '이미지에 이름과 이메일 표시'],
  fr: ['Nom', 'E-mail', 'Inclure mon nom et mon e-mail'], de: ['Name', 'E-Mail', 'Namen und E-Mail im Bild anzeigen'],
  ru: ['Имя', 'Эл. почта', 'Добавить имя и почту в изображение'], es: ['Nombre', 'Correo', 'Incluir mi nombre y correo'],
  pt: ['Nome', 'E-mail', 'Incluir meu nome e e-mail'], it: ['Nome', 'E-mail', 'Includi nome ed e-mail'],
  ar: ['الاسم', 'البريد الإلكتروني', 'إظهار اسمي وبريدي في الصورة'], tr: ['Ad', 'E-posta', 'Adımı ve e-postamı göster'],
  id: ['Nama', 'Email', 'Sertakan nama dan email saya'], my: ['အမည်', 'အီးမေးလ်', 'အမည်နှင့် အီးမေးလ်ကို ပြရန်'],
  hi: ['नाम', 'ईमेल', 'चित्र में मेरा नाम और ईमेल दिखाएँ'], cs: ['Jméno', 'E-mail', 'Zahrnout jméno a e-mail'],
  pl: ['Imię', 'E-mail', 'Pokaż imię i e-mail'], th: ['ชื่อ', 'อีเมล', 'แสดงชื่อและอีเมลในภาพ'], vi: ['Tên', 'Email', 'Hiển thị tên và email trong ảnh'],
}
export const shareProfileCopy = (language: string) => profileCopy[language] || profileCopy.en!
export function drawShareDesign(canvas: HTMLCanvasElement, design: ShareDesign, order: ShareOrder, copy: ShareCopy,
  brand: string, timezone: string, rate: number | null, mode: string, language: string, personal = false, qr?: HTMLImageElement, background?: HTMLImageElement, assets?: Map<string, HTMLImageElement>): void {
  validateShareDesign(design)
  canvas.width = design.width; canvas.height = design.height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('Canvas unavailable')
  ctx.fillStyle = design.background; ctx.fillRect(0, 0, canvas.width, canvas.height)
  if (design.artwork && background) { ctx.drawImage(background, 0, 0, canvas.width, canvas.height); ctx.fillStyle = `${design.background}aa`; ctx.fillRect(0, 0, canvas.width, canvas.height) }
  const signed = (value: number) => { const rounded = Number(value.toFixed(2)); return `${rounded > 0 ? '+' : ''}${new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Object.is(rounded, -0) ? 0 : rounded)}` }
  const price = (value: number) => new Intl.NumberFormat('en-US', { maximumFractionDigits: 16 }).format(value)
  const profile = shareProfileCopy(language)
  const labels: Record<ShareField, string> = { brand: '', symbol: '', direction: '', leverage: copy.leverage, amount: `${copy.pnl} (${order.currency || copy.units})`, rate: copy.rate,
    openPrice: copy.entry, closePrice: copy.exit, openTime: copy.openTime, closeTime: copy.closeTime, userName: profile[0], userEmail: profile[1] }
  const values: Record<ShareField, string> = { brand, symbol: order.symbol, direction: order.kind === 'contract' ? order.buy ? copy.buy : copy.sell : order.buy ? copy.up : copy.down,
    leverage: order.leverage === null ? '—' : `${order.leverage}×`, amount: signed(order.profit), rate: rate === null ? '—' : `${signed(rate)}%`,
    openPrice: price(order.openPrice), closePrice: price(order.closePrice), openTime: order.openTime || '—', closeTime: order.closeTime || '—',
    userName: order.userName || '—', userEmail: order.userEmail || '—' }
  for (const layer of shareDesignLayers(design)) {
    if (layer.kind === 'decoration') { if (layer.item.visible) drawDecoration(ctx, layer.item, assets); continue }
    const box = layer.item
    if (!box.visible || ((box.field === 'userName' || box.field === 'userEmail') && !personal)
      || (box.field === 'amount' && !['amount', 'both'].includes(mode)) || (box.field === 'rate' && !['rate', 'both'].includes(mode))) continue
    ctx.save(); ctx.beginPath(); ctx.rect(box.x, box.y, box.width, box.height); ctx.clip()
    ctx.textAlign = box.align; ctx.textBaseline = 'top'
    const x = box.x + (box.align === 'center' ? box.width / 2 : box.align === 'right' ? box.width : 0)
    const labelSize = Math.min(24, Math.max(18, box.fontSize * .4)), labelHeight = box.label && labels[box.field] ? labelSize * 1.5 : 0
    ctx.fillStyle = box.color
    if (labelHeight) { ctx.font = `500 ${labelSize}px "Microsoft YaHei", Arial, sans-serif`; ctx.fillText(labels[box.field], x, box.y, box.width) }
    let size = Math.min(box.fontSize, Math.max(8, (box.height - labelHeight) / 1.25))
    const value = values[box.field]
    do { ctx.font = `${box.weight} ${size}px "Noto Sans JP", "Microsoft YaHei", Arial, sans-serif`; if (ctx.measureText(value).width <= box.width || size <= 8) break; size-- } while (true)
    ctx.direction = /[\u0600-\u06ff]/.test(value) ? 'rtl' : 'ltr'
    ctx.fillText(value, x, box.y + labelHeight, box.width); ctx.restore()
  }
  ctx.font = `400 ${Math.max(9, design.width / 90)}px "Microsoft YaHei", Arial, sans-serif`; ctx.fillStyle = design.boxes[0]?.color || '#546571'
  ctx.fillText(`${timezone} · ${copy.footer}`, design.width * .07, design.height - 28, design.width * (qr ? .73 : .86))
  if (qr) { const size = Math.min(100, design.width * .1); ctx.fillStyle = '#ffffff'; ctx.fillRect(design.width - size - 22, design.height - size - 22, size + 8, size + 8); ctx.drawImage(qr, design.width - size - 18, design.height - size - 18, size, size) }
}

function drawDecoration(ctx: CanvasRenderingContext2D, layer: ShareDecoration, assets?: Map<string, HTMLImageElement>): void {
  ctx.save(); ctx.globalAlpha = layer.opacity
  ctx.beginPath(); ctx.rect(layer.x, layer.y, layer.width, layer.height); ctx.clip()
  if (layer.type === 'image') {
    const image = assets?.get(layer.src!)
    if (!image) { ctx.restore(); throw new Error('素材图片未加载，请重试') }
    const scale = layer.fit === 'cover' ? Math.max(layer.width / image.naturalWidth, layer.height / image.naturalHeight) : Math.min(layer.width / image.naturalWidth, layer.height / image.naturalHeight)
    const width = layer.fit === 'stretch' ? layer.width : image.naturalWidth * scale, height = layer.fit === 'stretch' ? layer.height : image.naturalHeight * scale
    ctx.drawImage(image, layer.x + (layer.width - width) / 2, layer.y + (layer.height - height) / 2, width, height)
  } else {
    const p = Math.min(layer.strokeWidth / 2, layer.width / 2, layer.height / 2), w = layer.width - p * 2, h = layer.height - p * 2
    ctx.beginPath(); ctx.lineWidth = layer.strokeWidth; ctx.fillStyle = layer.fill; ctx.strokeStyle = layer.stroke
    if (layer.type === 'line') { ctx.moveTo(layer.x + p, layer.y + layer.height / 2); ctx.lineTo(layer.x + layer.width - p, layer.y + layer.height / 2) }
    else if (layer.type === 'ellipse') ctx.ellipse(layer.x + layer.width / 2, layer.y + layer.height / 2, w / 2, h / 2, 0, 0, Math.PI * 2)
    else if (layer.type === 'roundRect') ctx.roundRect(layer.x + p, layer.y + p, w, h, Math.min(layer.radius, w / 2, h / 2))
    else ctx.rect(layer.x + p, layer.y + p, w, h)
    if (layer.fill !== 'none' && layer.type !== 'line') ctx.fill()
    if (layer.stroke !== 'none' && layer.strokeWidth > 0) ctx.stroke()
  }
  ctx.restore()
}
