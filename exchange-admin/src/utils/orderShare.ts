import { posterLocales, shareLanguage } from './orderShareLocales.ts'
export { shareLanguage } from './orderShareLocales.ts'
// Kept identical in mobile, PC and admin builds.
export type ShareKind = 'contract' | 'option'
export const shareTemplates = ['light', 'dark', 'chart', 'gold', 'globe', 'architecture', 'city', 'referenceGold', 'referenceWhite', 'referenceTerminal', 'launch', 'aurora', 'racing', 'receipt', 'journal', 'voyage'] as const
export type ShareTemplate = typeof shareTemplates[number]
export const shareNeedsChart = (template: ShareTemplate) => template === 'chart' || template === 'journal' || template.startsWith('reference')
export const shareBackgrounds: Partial<Record<ShareTemplate, string>> = {
  referenceGold: 'reference-gold.png', referenceWhite: 'reference-white-neutral.png', referenceTerminal: 'reference-terminal.png',
  launch: 'launch.png', aurora: 'aurora.png', racing: 'racing.png', voyage: 'voyage.png',
}
export type ShareFocus = 'amount' | 'rate'
export type ShareMode = 'amount' | 'rate' | 'both' | 'none'
export interface ShareOrder {
  id: string; symbol: string; kind: ShareKind; buy: boolean
  profit: number; openPrice: number; closePrice: number
  quantityUnit?: string; quantity: number | null; leverage: number | null; margin: number | null; fee: number | null; amount: number | null
  openTime: string; closeTime: string; currency: string
}
export interface ShareOptions {
  template: ShareTemplate; mode: ShareMode; focus?: ShareFocus; quantity: boolean; capital: boolean
  fee: boolean; leverage: boolean; orderId: boolean; openTime: boolean
}
const en = {
  launch: 'Obsidian launch', aurora: 'Aurora flow', racing: 'Blue circuit', receipt: 'FX trade ticket', journal: 'Professional review', voyage: 'Kyoto dusk',
  referenceGold: 'Gold original', referenceWhite: 'White original', referenceTerminal: 'Market original', backgroundError: 'Background unavailable. Retry or choose another template.',
  gold: 'Black & gold', globe: 'Global lights', architecture: 'Architecture', city: 'City dusk',
  previousTemplate: 'Previous template', nextTemplate: 'Next template', swipe: 'Swipe to change template',
  qrShort: 'QR code', shareShort: 'Share', shareFallback: 'Save the image, then share it from your photos.',
  share: 'Share P&L', title: 'Share this trade', subtitle: 'A trading record, in your style.',
  light: 'Clean white', dark: 'Professional dark', template: 'Template', content: 'Display',
  amount: 'P&L amount', rate: 'Return', both: 'Amount & return', details: 'Optional details',
  quantity: 'Quantity', leverage: 'Leverage', capital: 'Margin / investment', fee: 'Recorded fee', orderId: 'Masked order ID',
  openTime: 'Open time', closeTime: 'Close time', save: 'Save image', system: 'System share',
  close: 'Close', loading: 'Preparing image…', retry: 'Try again', error: 'Unable to prepare this order. Please try again.',
  saveError: 'Unable to save. Long-press or open the preview to save it.', shareError: 'Sharing unavailable. Please save the image instead.',
  saved: 'Download requested. You can also long-press the preview to save.', tip: '1080 × 1440 PNG · Long-press the preview to save on mobile.',
  privacy: 'Personal details and account balances are never included.',
  accounting: 'Uses the recorded settlement P&L; no additional fee deduction. Amounts use account units when the currency is unavailable.',
  rateNote: 'Return = recorded P&L / margin (contracts) or investment (term trades). Fees are not deducted again.',
  ratePrivacy: 'Return-only mode hides quantity, capital and fees.',
  closed: 'Closed', contract: 'Contract', option: 'Term trade', buy: 'Buy / Long', sell: 'Sell / Short',
  up: 'Buy up', down: 'Buy down', pnl: 'Realized P&L', entry: 'Entry price', exit: 'Exit price',
  units: 'Account units', margin: 'Margin', investment: 'Investment', record: 'TRADE RECORD',
  footer: 'Single trade record · Not proof of future returns', basis: 'Recorded settlement P&L',
  contractRate: 'Return', optionRate: 'Return', qr: 'Invitation QR code',
  qrNote: 'Opens registration, never private order details.', qrError: 'Invitation code unavailable. Retry or turn off the QR code.',
  invite: 'Join us', preview: 'Trade sharing preview',
  chart: 'Trade review', chartError: 'Recent candles unavailable. Please retry.',
  recentCaption: 'Recent market candles',
  chartCaption: 'Source market candles · Execution prices marked separately',
}
export type ShareCopy = typeof en & {
  lots: string; orderDetails: string; orderNumber: string
}
export function shareCopy(locale: string): ShareCopy {
  const language = shareLanguage(locale)
  const localized = { ...baseShareCopy(language), orderDetails: language === 'zh-TW' ? '訂單詳情' : language === 'ja' ? '注文の詳細' : 'Order details',
    ...posterLocales[language], orderNumber: language === 'zh-TW' ? '訂單編號' : language === 'ja' ? '注文番号' : posterLocales[language]?.orderId || 'Order ID' } as ShareCopy
  return { ...localized, contractRate: localized.rate, optionRate: localized.rate }
}
function baseShareCopy(locale: string): typeof en {
  if (locale.startsWith('zh')) return {
    launch: '曜石啟航', aurora: '極光流動', racing: '藍色競速', receipt: '外匯交易票', journal: '專業復盤', voyage: '京都暮色',
    referenceGold: '黑金原版', referenceWhite: '白色原版', referenceTerminal: '行情原版', backgroundError: '背景載入失敗，請重試或選擇其他模板。',
    gold: '黑金山巒', globe: '環球光點', architecture: '極簡建築', city: '城市暮色',
    previousTemplate: '上一款模板', nextTemplate: '下一款模板', swipe: '左右滑動切換模板',
    qrShort: '二維碼', shareShort: '分享', shareFallback: '請儲存圖片後，從相簿分享。',
    share: '分享盈虧', title: '分享這筆交易', subtitle: '讓每一筆交易，留下自己的風格。',
    light: '清爽白', dark: '專業黑', template: '選擇模板', content: '展示內容',
    amount: '盈虧金額', rate: '收益率', both: '金額與收益率', details: '更多資訊',
    quantity: '數量', leverage: '槓桿倍數', capital: '保證金 / 投入金額', fee: '訂單記錄手續費', orderId: '脫敏訂單編號',
    openTime: '開倉時間', closeTime: '平倉時間', save: '儲存圖片', system: '系統分享',
    close: '關閉', loading: '正在生成圖片…', retry: '重試', error: '無法取得這筆訂單，請重試。',
    saveError: '無法下載，請長按或開啟預覽圖儲存。', shareError: '無法使用系統分享，請先儲存圖片。',
    saved: '已請求下載，也可長按預覽圖片儲存。', tip: '1080 × 1440 PNG · 手機可長按預覽圖片儲存',
    privacy: '圖片不包含個人資訊及帳戶餘額。',
    accounting: '採用訂單結算盈虧，不額外扣減手續費。幣種缺失時使用帳戶計價單位。',
    rateNote: '收益率＝結算盈虧 ÷ 合約保證金或期限投入金額，不再次扣減手續費。',
    ratePrivacy: '僅顯示收益率時，隱藏數量、保證金、投入金額及手續費。',
    closed: '已平倉', contract: '合約', option: '期限', buy: '買入 / 做多', sell: '賣出 / 做空',
    up: '買漲', down: '買跌', pnl: '已實現盈虧', entry: '開倉價格', exit: '平倉價格',
    units: '帳戶計價單位', margin: '保證金', investment: '投入金額', record: '交易記錄',
    footer: '單筆交易記錄 · 不代表未來收益', basis: '依訂單結算記錄',
    contractRate: '收益率', optionRate: '收益率', qr: '邀請二維碼',
    qrNote: '掃碼開啟註冊頁，不公開訂單詳情。', qrError: '無法取得邀請碼，請重試或關閉二維碼。',
    invite: '邀請加入', preview: '交易分享預覽',
    chart: '行情復盤', chartError: '暫時無法取得最近 K 線，請重試。',
    recentCaption: '最近市場 K 線',
    chartCaption: '來源市場 K 線 · 獨立標註成交價',
  }
  if (locale === 'ja') return {
    ...en, launch: 'オブシディアン', aurora: 'オーロラ', racing: 'ブルーサーキット', receipt: '取引明細', journal: '取引履歴', voyage: '京都の夕暮れ',
    referenceGold: 'ゴールドチャート', referenceWhite: 'ホワイトチャート', referenceTerminal: 'ダークチャート', backgroundError: '背景を読み込めません。再試行してください。',
    gold: 'ブラックゴールド', globe: '地球', architecture: '建築', city: '夕暮れの街',
    previousTemplate: '前のテンプレート', nextTemplate: '次のテンプレート', swipe: '左右にスワイプして切り替え',
    qrShort: 'QRコード', shareShort: 'シェア', shareFallback: '画像を保存して、写真アプリからシェアしてください。', share: '損益をシェア', title: 'この取引をシェア', subtitle: '取引の記録を、自分らしく。',
    light: 'ホワイト', dark: 'ダーク', template: 'テンプレート', content: '表示内容',
    amount: '損益額', rate: '収益率', both: '損益額と収益率', details: '詳細情報',
    quantity: '数量', leverage: 'レバレッジ', capital: '証拠金 / 投資額', fee: '記録上の手数料', orderId: '伏せ字の注文番号',
    openTime: '新規約定日時', closeTime: '決済約定日時', save: '画像を保存', system: 'シェア', close: '閉じる',
    loading: '画像を作成中…', retry: '再試行', error: '注文を取得できません。再試行してください。',
    saveError: '保存できません。プレビュー画像を長押ししてください。', shareError: 'シェアできません。画像を保存してください。',
    saved: 'ダウンロードを開始しました。画像の長押しでも保存できます。', tip: '1080 × 1440 PNG · 長押しで画像を保存',
    privacy: '個人情報や口座残高は含まれません。', accounting: '記録された決済損益を使用。手数料は再控除しません。通貨不明時は口座単位で表示。',
    rateNote: '収益率＝決済損益 ÷ 証拠金または投資額。手数料は再控除しません。', ratePrivacy: '収益率のみの場合、数量・証拠金・投資額・手数料を非表示。',
    closed: '決済済み', contract: '証拠金取引', option: '期限取引', buy: '買い / ロング', sell: '売り / ショート', up: '上昇を予想', down: '下落を予想',
    pnl: '実現損益', entry: '新規約定価格', exit: '決済約定価格', units: '口座単位', margin: '証拠金', investment: '投資額',
    record: '取引記録', footer: '個別の取引記録 · 将来の収益を保証しません', basis: '決済記録に基づく損益',
    contractRate: '収益率', optionRate: '収益率', qr: '招待QRコード', qrNote: '登録ページが開きます。注文の詳細は公開しません。',
    qrError: '招待コードを取得できません。再試行するかQRコードをオフにしてください。', invite: '参加する', preview: '取引シェアのプレビュー',
    chart: '取引チャート', chartError: '最新のローソク足を取得できません。再試行してください。',
    recentCaption: '最新のローソク足',
    chartCaption: '市場のローソク足 · 約定価格は別途表示',
  }
  return en
}
function optionalNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}
export function settledShareOrder(raw: Record<string, unknown>, kind: ShareKind): ShareOrder {
  const profit = optionalNumber(raw.profit)
  const openPrice = optionalNumber(raw.openPrice)
  const closePrice = optionalNumber(raw.closePrice)
  const direction = String(kind === 'contract' ? raw.side : raw.direction)
  if (raw.status !== 'CLOSED' || profit === null || openPrice === null || closePrice === null ||
      openPrice <= 0 || closePrice <= 0 || !raw.id || !raw.symbol || !raw.closeTime ||
      !Number.isFinite(orderTimestamp(String(raw.closeTime))) ||
      !(kind === 'contract' ? ['BUY', 'SELL'] : ['UP', 'DOWN']).includes(direction)) {
    throw new Error('Incomplete settled order')
  }
  return {
    id: String(raw.id), symbol: String(raw.symbol), kind, buy: direction === 'BUY' || direction === 'UP',
    profit, openPrice, closePrice, leverage: optionalNumber(raw.leverage), quantity: optionalNumber(raw.quantity), margin: optionalNumber(raw.margin),
    quantityUnit: raw.quantityUnitType === 'BASE_ASSET' ? String(raw.quantityAsset || '—') : raw.quantityUnitType === 'SHARE' ? '股' : 'lots',
    fee: optionalNumber(raw.fee), amount: optionalNumber(raw.amount),
    openTime: String(raw.openTime || raw.createdAt || ''), closeTime: String(raw.closeTime),
    currency: typeof raw.settlementCurrency === 'string' ? raw.settlementCurrency : 'USD',
  }
}
export function shareReturn(order: ShareOrder): number | null {
  const capital = order.kind === 'contract' ? order.margin : order.amount
  if (capital === null || capital <= 0) return null
  const value = order.profit / capital * 100
  return Number.isFinite(value) ? value : null
}
export function shareNumber(value: number, digits = 2, signed = false): string {
  const rounded = Number(value.toFixed(digits))
  return `${signed && rounded > 0 ? '+' : ''}${new Intl.NumberFormat('en-US', {
    minimumFractionDigits: digits, maximumFractionDigits: digits,
  }).format(Object.is(rounded, -0) ? 0 : rounded)}`
}

export interface ShareCandle { timestamp: number; open: number; high: number; low: number; close: number; volume?: number }
export interface ShareChart { candles: ShareCandle[]; start: number; end: number; step: number; interval: string; source: string; recent?: boolean }
export function orderTimestamp(value: string): number {
  const normalized = value.trim().replace(' ', 'T')
  return Date.parse(/(?:Z|[+-]\d{2}:?\d{2})$/i.test(normalized) ? normalized : `${normalized}Z`)
}
export function historyWindow(order: ShareOrder, now = Date.now()) {
  const start = orderTimestamp(order.openTime), end = orderTimestamp(order.closeTime)
  if (!Number.isFinite(start) || !Number.isFinite(end) || start > end) throw new Error('Invalid order time')
  const intervals: Array<[string, number]> = [['1m', 60000], ['5m', 300000], ['15m', 900000], ['30m', 1800000], ['1h', 3600000], ['1d', 86400000]]
  const minimum = now - start > 60 * 86400000 ? 86400000 : now - start > 7 * 86400000 ? 3600000 : 60000
  const [interval, step] = intervals.find(([, duration]) => duration >= minimum && (end - start) / duration <= 90) || intervals[5]!
  return { start, end, step, interval, endTime: Math.min(now, (Math.floor(end / step) + 2) * step), limit: 160 }
}
export function coveredCandles(rows: Array<Record<string, unknown>>, window?: ReturnType<typeof historyWindow>): ShareCandle[] {
  const unique = new Map<number, ShareCandle>()
  for (const row of rows) {
    let timestamp = Number(row.timestamp)
    if (timestamp < 1e11) timestamp *= 1000
    const candle = { timestamp, open: Number(row.open_price), high: Number(row.high_price), low: Number(row.low_price), close: Number(row.close_price) }
    if (!Object.values(candle).every(n => Number.isFinite(n) && n > 0) || candle.low > Math.min(candle.open, candle.close) || candle.high < Math.max(candle.open, candle.close)) continue
    const volume = optionalNumber(row.volume)
    unique.set(timestamp, { ...candle, ...(volume !== null && volume >= 0 ? { volume } : {}) })
  }
  const candles = [...unique.values()].sort((a, b) => a.timestamp - b.timestamp)
  if (!window) return candles.slice(-80)
  const covers = (time: number) => candles.some(c => c.timestamp <= time && time < c.timestamp + window.step)
  if (!covers(window.start) || !covers(window.end)) throw new Error('Incomplete history coverage')
  return candles.filter(c => c.timestamp >= window.start - window.step * 5 && c.timestamp <= window.end + window.step)
}

export function recentShareChart(rows: Array<Record<string, unknown>>, source = ''): ShareChart {
  const candles = coveredCandles(rows)
  if (!candles.length) throw new Error('No recent candles')
  return { candles, start: candles[0]!.timestamp, end: candles[candles.length - 1]!.timestamp,
    step: 60000, interval: '1m', source, recent: true }
}

export function drawSharePoster(canvas: HTMLCanvasElement, order: ShareOrder, options: ShareOptions,
  copy: ShareCopy, brand: string, timezone: string, qr?: HTMLImageElement, chart?: ShareChart, background?: HTMLImageElement): void {
  const theme = options.template, review = shareNeedsChart(theme)
  if (review && !chart?.candles.length) throw new Error(copy.chartError)
  if (shareBackgrounds[theme] && !background) throw new Error(copy.backgroundError)
  canvas.width = 1080; canvas.height = 1440
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('Canvas unavailable')
  ctx.scale(2, 2)
  // All templates share the same privacy rules and metric hierarchy; only their art changes.
  const light = ['light', 'chart', 'architecture', 'city', 'referenceWhite', 'receipt', 'journal', 'voyage'].includes(theme)
  const ink = light ? '#172b35' : '#f5f8fc', muted = light ? '#546571' : '#b2c1ce'
  const accents: Record<ShareTemplate, string> = {
    light: '#376d66', dark: '#73bba6', chart: '#396a93', gold: '#d9b76a', globe: '#78c8be',
    architecture: '#798e53', city: '#ac715a', referenceGold: '#dfbd72', referenceWhite: '#778f56',
    referenceTerminal: '#73bba6', launch: '#dcbb78', aurora: '#6adbd3', racing: '#a9d874',
    receipt: '#688060', journal: '#698153', voyage: '#486c80',
  }
  const accent = accents[theme], base = light ? '#f4f6f3' : theme === 'racing' ? '#0a1c38' : '#0b151e'
  const panel = light ? '#fffffff5' : '#101e2bf5', border = light ? '#d7e0dd' : '#344755'
  const color = (value: number | null) => value === null || value === 0 ? muted : value > 0 ? (light ? '#17804c' : '#69d8a3') : (light ? '#c43d4b' : '#ff828b')
  const text = (value: string, x: number, y: number, size: number, fill = ink, weight = 400, width = 460) => {
    let fitted = size
    do {
      ctx.font = `${weight} ${fitted}px "Noto Sans JP", "Yu Gothic", "Microsoft YaHei", Arial, sans-serif`
      if (ctx.measureText(value).width <= width || fitted <= 6) break
      fitted -= .5
    } while (true)
    ctx.save(); ctx.fillStyle = fill; ctx.textAlign = 'left'
    ctx.direction = /[\u0600-\u06ff]/.test(value) ? 'rtl' : 'ltr'
    ctx.fillText(value, x, y); ctx.restore()
    return fitted
  }
  const box = (x: number, y: number, w: number, h: number, fill: string, radius = 18) => {
    ctx.beginPath(); ctx.roundRect(x, y, w, h, radius); ctx.fillStyle = fill; ctx.fill()
  }
  const line = (x: number, y: number, ex: number, ey: number, stroke = border, width = 1) => {
    ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(ex, ey); ctx.strokeStyle = stroke; ctx.lineWidth = width; ctx.stroke()
  }
  ctx.fillStyle = base; ctx.fillRect(0, 0, 540, 720)
  if (background) ctx.drawImage(background, 0, 0, 540, 720)
  else if (theme === 'gold') {
    ctx.fillStyle = '#303025'; ctx.beginPath(); ctx.moveTo(260, 720); ctx.lineTo(437, 450); ctx.lineTo(540, 510); ctx.lineTo(540, 720); ctx.fill()
    line(260, 720, 437, 450, accent, 2)
  } else if (theme === 'globe') {
    ctx.strokeStyle = '#42675f'; ctx.lineWidth = 1
    for (let i = 1; i <= 4; i++) {
      ctx.beginPath(); ctx.ellipse(440, 570, 110, i * 25, 0, 0, Math.PI * 2); ctx.stroke()
      ctx.beginPath(); ctx.ellipse(440, 570, i * 25, 110, 0, 0, Math.PI * 2); ctx.stroke()
    }
  } else if (theme === 'architecture') {
    for (let i = 0; i < 6; i++) { box(337 + i * 30, 80 + i * 22, 20, 570, i % 2 ? '#dce2d6' : '#e9ede4', 0) }
  } else if (theme === 'city') {
    const sky = ctx.createLinearGradient(0, 0, 0, 720); sky.addColorStop(0, '#eee7e3'); sky.addColorStop(1, '#d5dfe8')
    ctx.fillStyle = sky; ctx.fillRect(0, 0, 540, 720)
    for (let i = 0; i < 13; i++) box(i * 44, 500 + (i * 37 % 101), 34, 220, '#afbdc5', 0)
  } else if (theme === 'light' || theme === 'dark') {
    ctx.strokeStyle = light ? '#d5e2dc' : '#233c43'
    for (let i = 0; i < 5; i++) { ctx.beginPath(); ctx.ellipse(520, 180, 80 + i * 25, 180, .4, 0, Math.PI * 2); ctx.stroke() }
  }
  if (theme === 'receipt') box(18, 18, 504, 684, '#fffef8', 2)
  if (theme === 'journal') box(0, 0, 540, 12, '#d9e69d', 0)
  // Opaque header/footer surfaces keep every locale legible over photographic art.
  box(24, 24, 492, 172, light ? '#ffffffeb' : '#0b151eeb', 18)
  box(38, 42, 4, 24, accent, 2)
  text(brand, 52, 62, 24, ink, 750, 310)
  text(copy.closed, 393, 60, 12, muted, 500, 108)
  text(copy.record, 39, 89, 12, muted, 600)
  text(order.symbol, 37, 146, 43, ink, 750)
  const side = order.kind === 'contract' ? (order.buy ? copy.buy : copy.sell) : order.buy ? copy.up : copy.down
  // Direction does not reuse profit colors: a short trade can make a profit.
  text(side, 39, 177, 16, muted, 500)
  if (options.leverage && order.kind === 'contract' && order.leverage !== null)
    text(`${copy.leverage} ${shareNumber(order.leverage, 0)}`, 300, 177, 13, muted, 500, 200)

  const amountVisible = options.mode === 'amount' || options.mode === 'both'
  const rateVisible = options.mode === 'rate' || options.mode === 'both'
  const rate = shareReturn(order), currency = order.currency || copy.units
  const amountMetric = { label: copy.pnl, value: shareNumber(order.profit, 2, true), unit: currency, color: color(order.profit) }
  const rateMetric = { label: copy.rate, value: rate === null ? '—' : `${shareNumber(rate, 2, true)}%`, unit: '', color: color(rate) }
  const rateFirst = rateVisible && (!amountVisible || (options.focus === 'rate' && rate !== null))
  const main = rateFirst ? rateMetric : amountMetric, secondary = rateFirst ? amountMetric : rateMetric
  box(24, 209, 492, 239, panel, theme === 'receipt' || theme === 'racing' ? 4 : 22)
  box(24, 231, 4, 193, accent, 2)
  if (amountVisible || rateVisible) {
    text(main.label, 42, 241, 16, muted, 500)
    const primarySize = text(main.value, 38, 327, 82, main.color, 800, 460)
    if (main.unit) text(main.unit, 43, 351, 13, muted, 600)
    if (amountVisible && rateVisible) {
      line(42, 365, 498, 365)
      text(`${secondary.label}${secondary.unit ? ` (${secondary.unit})` : ''}`, 43, 388, 13, muted, 500)
      text(secondary.value, 41, 431, Math.min(43, primarySize * .58), secondary.color, 700, 456)
    }
  } else {
    text(copy.record, 42, 298, 42, ink, 700)
    text(copy.basis, 43, 342, 14, muted)
  }
  box(24, 462, 492, review ? 172 : 152, panel, theme === 'receipt' ? 4 : 18)
  const price = (n: number) => new Intl.NumberFormat('en-US', { maximumFractionDigits: 16 }).format(n)
  text(copy.entry, 42, 484, 12, muted, 500, 212)
  text(copy.exit, 287, 484, 12, muted, 500, 211)
  text(price(order.openPrice), 42, 514, 25, ink, 600, 212)
  text(price(order.closePrice), 287, 514, 25, ink, 600, 211)
  if (review && chart) {
    const candles = chart.candles, first = candles[0]!.timestamp, last = candles[candles.length - 1]!.timestamp + chart.step
    const low = Math.min(...(chart.recent ? [] : [order.openPrice, order.closePrice]), ...candles.map(c => c.low))
    const high = Math.max(...(chart.recent ? [] : [order.openPrice, order.closePrice]), ...candles.map(c => c.high))
    const spread = Math.max(high - low, high * .0001)
    const px = (time: number) => 46 + (time - first) / (last - first) * 448
    const py = (value: number) => 598 - (value - low) / spread * 61
    for (let i = 0; i < 3; i++) line(42, 539 + i * 30, 498, 539 + i * 30)
    const width = Math.max(.8, Math.min(5, 300 / candles.length))
    for (const c of candles) {
      const x = px(c.timestamp + chart.step / 2), fill = color(c.close - c.open)
      line(x, py(c.high), x, py(c.low), fill, .8)
      ctx.fillStyle = fill; ctx.fillRect(x - width / 2, Math.min(py(c.open), py(c.close)), width, Math.max(1, Math.abs(py(c.open) - py(c.close))))
    }
    if (!chart.recent) for (const value of [order.openPrice, order.closePrice]) {
      ctx.setLineDash([3, 4]); line(42, py(value), 498, py(value), accent); ctx.setLineDash([])
    }
    text(`${chart.source} · ${chart.interval} · ${chart.recent ? copy.recentCaption : copy.chartCaption}`, 42, 620, 9, muted, 400, 456)
  } else {
    line(42, 533, 498, 533)
    if (options.openTime) text(`${copy.openTime}  ${order.openTime || '—'}`, 42, 561, 12, muted, 400, 456)
    if (options.quantity && amountVisible && order.quantity !== null)
      text(`${copy.quantity}  ${shareNumber(order.quantity)} ${(!order.quantityUnit || order.quantityUnit === 'lots' ? copy.lots : order.quantityUnit)}`, 42, 591, 12, muted, 400, 456)
  }
  // No capital, fee, or order identifier is ever drawn, including with legacy flags enabled.
  box(24, 644, 492, 64, light ? '#ffffffed' : '#0b151eed', 12)
  const footerWidth = qr ? 376 : 456
  text(`${copy.closeTime}  ${order.closeTime}`, 39, 662, 10, muted, 400, footerWidth)
  text(`${timezone} · ${copy.basis}`, 39, 680, 9, muted, 400, footerWidth)
  text(copy.footer, 39, 698, 9, muted, 400, footerWidth)
  if (qr) { box(438, 647, 58, 58, '#fff', 5); ctx.drawImage(qr, 442, 651, 50, 50) }
}
