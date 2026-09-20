// Kept identical in the two independently built frontends.
export type ShareKind = 'contract' | 'option'
export const shareTemplates = ['light', 'dark', 'chart', 'gold', 'globe', 'architecture', 'city', 'referenceGold', 'referenceWhite', 'referenceTerminal', 'launch', 'aurora', 'racing', 'receipt', 'journal', 'voyage'] as const
export type ShareTemplate = typeof shareTemplates[number]
export const shareNeedsChart = (template: ShareTemplate) => template === 'chart' || template === 'journal' || template.startsWith('reference')
export const shareBackgrounds: Partial<Record<ShareTemplate, string>> = {
  referenceGold: 'reference-gold.png', referenceWhite: 'reference-white.png', referenceTerminal: 'reference-terminal.png',
  launch: 'launch.png', aurora: 'aurora.png', racing: 'racing.png', voyage: 'voyage.png',
}
export type ShareMode = 'amount' | 'rate' | 'both' | 'none'
export interface ShareOrder {
  id: string; symbol: string; kind: ShareKind; buy: boolean
  profit: number; openPrice: number; closePrice: number
  quantity: number | null; leverage: number | null; margin: number | null; fee: number | null; amount: number | null
  openTime: string; closeTime: string; currency: string
}
export interface ShareOptions {
  template: ShareTemplate; mode: ShareMode; quantity: boolean; capital: boolean
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
  contractRate: 'Return on margin', optionRate: 'Return on investment', qr: 'Invitation QR code',
  qrNote: 'Opens registration, never private order details.', qrError: 'Invitation code unavailable. Retry or turn off the QR code.',
  invite: 'Join us', preview: 'Trade sharing preview',
  chart: 'Trade review', chartError: 'Recent candles unavailable. Please retry.',
  recentCaption: 'Recent market candles',
  chartCaption: 'Source market candles · Execution prices marked separately',
}
export type ShareCopy = typeof en
export function shareCopy(locale: string): ShareCopy {
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
    contractRate: '保證金收益率', optionRate: '投入金額收益率', qr: '邀請二維碼',
    qrNote: '掃碼開啟註冊頁，不公開訂單詳情。', qrError: '無法取得邀請碼，請重試或關閉二維碼。',
    invite: '邀請加入', preview: '交易分享預覽',
    chart: '行情復盤', chartError: '暫時無法取得最近 K 線，請重試。',
    recentCaption: '最近市場 K 線',
    chartCaption: '來源市場 K 線 · 獨立標註成交價',
  }
  if (locale === 'ja') return {
    ...en, launch: 'オブシディアン', aurora: 'オーロラ', racing: 'ブルーサーキット', receipt: '取引チケット', journal: 'プロレビュー', voyage: '京都の夕暮れ',
    referenceGold: 'ゴールド原版', referenceWhite: 'ホワイト原版', referenceTerminal: 'チャート原版', backgroundError: '背景を読み込めません。再試行してください。',
    gold: 'ブラックゴールド', globe: 'グローブ', architecture: '建築', city: '夕暮れの街',
    previousTemplate: '前のテンプレート', nextTemplate: '次のテンプレート', swipe: '左右にスワイプして切り替え',
    qrShort: 'QRコード', shareShort: 'シェア', shareFallback: '画像を保存して、写真アプリからシェアしてください。', share: '損益をシェア', title: 'この取引をシェア', subtitle: '取引の記録を、自分らしく。',
    light: 'ホワイト', dark: 'ダーク', template: 'テンプレート', content: '表示内容',
    amount: '損益額', rate: '収益率', both: '損益額と収益率', details: '詳細情報',
    quantity: '数量', leverage: 'レバレッジ', capital: '証拠金 / 投資額', fee: '記録上の手数料', orderId: '伏せ字の注文番号',
    openTime: 'エントリー日時', closeTime: '決済日時', save: '画像を保存', system: 'シェア', close: '閉じる',
    loading: '画像を作成中…', retry: '再試行', error: '注文を取得できません。再試行してください。',
    saveError: '保存できません。プレビュー画像を長押ししてください。', shareError: 'シェアできません。画像を保存してください。',
    saved: 'ダウンロードを開始しました。画像の長押しでも保存できます。', tip: '1080 × 1440 PNG · 長押しで画像を保存',
    privacy: '個人情報や口座残高は含まれません。', accounting: '記録された決済損益を使用。手数料は再控除しません。通貨不明時は口座単位で表示。',
    rateNote: '収益率＝決済損益 ÷ 証拠金または投資額。手数料は再控除しません。', ratePrivacy: '収益率のみの場合、数量・証拠金・投資額・手数料を非表示。',
    closed: '決済済み', contract: '契約', option: '期限取引', buy: '買い / ロング', sell: '売り / ショート', up: '買い', down: '売り',
    pnl: '実現損益', entry: 'エントリー価格', exit: '決済価格', units: '口座単位', margin: '証拠金', investment: '投資額',
    record: '取引記録', footer: '個別の取引記録 · 将来の収益を保証しません', basis: '決済記録に基づく',
    contractRate: '証拠金に対する収益率', optionRate: '投資額に対する収益率', qr: '招待QRコード', qrNote: '登録ページが開きます。注文の詳細は公開しません。',
    qrError: '招待コードを取得できません。再試行するかQRコードをオフにしてください。', invite: '参加する', preview: '取引シェアのプレビュー',
    chart: '取引レビュー', chartError: '最新のローソク足を取得できません。再試行してください。',
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
    fee: optionalNumber(raw.fee), amount: optionalNumber(raw.amount),
    openTime: String(raw.openTime || raw.createdAt || ''), closeTime: String(raw.closeTime),
    currency: typeof raw.settlementCurrency === 'string' ? raw.settlementCurrency : '',
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
  return Date.parse(/[zZ]|[+-]\d{2}:?\d{2}$/.test(normalized) ? normalized : `${normalized}+08:00`)
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
  if (['launch', 'aurora', 'racing', 'receipt', 'journal', 'voyage'].includes(options.template)) {
    drawCollectionPoster(canvas, order, options, copy, brand, timezone, qr, chart, background)
    return
  }
  if (options.template.startsWith('reference')) {
    drawReferencePoster(canvas, order, options, copy, brand, qr, chart, background)
    return
  }
  canvas.width = 1080; canvas.height = 1440
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('Canvas unavailable')
  const theme = options.template
  const dark = ['dark', 'gold', 'globe'].includes(theme)
  const review = options.template === 'chart'
  if (review && (!chart || !chart.candles.length)) throw new Error('Missing historical candles')
  const ink = dark ? '#f5f7fb' : '#111827', muted = dark ? '#a2abb9' : '#697386'
  const panel = theme === 'gold' ? '#20211de8' : theme === 'city' ? '#ffffffcc' : dark ? '#1b222ce8' : '#f0f3f7'
  const border = theme === 'gold' ? '#6d6034' : dark ? '#303a48' : '#e6e8ed'
  const accent = '#85bd00', pnl = order.profit > 0 ? (dark ? '#9ad348' : '#5e9000') : order.profit < 0 ? '#e64042' : muted
  const text = (value: string, x: number, y: number, size: number, color = ink, weight = 400, width = 936) => {
    ctx.fillStyle = color
    let fontSize = size
    do {
      ctx.font = `${weight} ${fontSize}px "Microsoft YaHei", "PingFang SC", Arial, sans-serif`
      if (ctx.measureText(value).width <= width || fontSize <= 12) break
      fontSize -= 1
    } while (true)
    ctx.fillText(value, x, y)
  }
  const box = (x: number, y: number, w: number, h: number, fill: string, radius = 24) => {
    ctx.beginPath(); ctx.moveTo(x + radius, y)
    ctx.arcTo(x + w, y, x + w, y + h, radius); ctx.arcTo(x + w, y + h, x, y + h, radius)
    ctx.arcTo(x, y + h, x, y, radius); ctx.arcTo(x, y, x + w, y, radius)
    ctx.closePath(); ctx.fillStyle = fill; ctx.fill()
  }
  const line = (y: number) => { ctx.strokeStyle = border; ctx.beginPath(); ctx.moveTo(72, y); ctx.lineTo(1008, y); ctx.stroke() }
  ctx.fillStyle = dark ? '#10151e' : '#ffffff'; ctx.fillRect(0, 0, 1080, 1440)
  const polygon = (points: number[][], fill: string) => {
    ctx.beginPath(); points.forEach(([x, y], i) => i ? ctx.lineTo(x!, y!) : ctx.moveTo(x!, y!))
    ctx.closePath(); ctx.fillStyle = fill; ctx.fill()
  }
  // Backgrounds are decorative artwork, never synthetic market data.
  if (theme === 'city') {
    const sky = ctx.createLinearGradient(0, 0, 0, 1440)
    sky.addColorStop(0, '#d4e1eb'); sky.addColorStop(.52, '#fff4e4'); sky.addColorStop(1, '#e0cdbb')
    ctx.fillStyle = sky; ctx.fillRect(0, 0, 1080, 1440)
    ctx.fillStyle = '#fff9e7'; ctx.beginPath(); ctx.arc(874, 277, 100, 0, Math.PI * 2); ctx.fill()
    for (let layer = 0; layer < 3; layer++) {
      const base = 1180 + layer * 35
      for (let i = 0; i < 19; i++) {
        const x = i * 65 - layer * 24, h = 45 + ((i * 47 + layer * 31) % 150)
        ctx.fillStyle = ['#a7b6ba', '#84989b', '#5e767a'][layer]!
        ctx.fillRect(x, base - h, 51, h)
        ctx.fillRect(x + 23, base - h - 12, 5, 12)
        ctx.fillStyle = '#e8e7c7'
        for (let y = base - h + 13; y < base - 8; y += 18) for (let col = 0; col < 3; col++) ctx.fillRect(x + 8 + col * 13, y, 4, 5)
      }
    }
    // A slender skyline tower echoes the travel poster reference without borrowing a landmark photo.
    polygon([[890, 980], [924, 1220], [856, 1220]], '#50696d')
    ctx.fillStyle = '#50696d'; ctx.fillRect(888, 928, 4, 90); ctx.fillRect(866, 1040, 48, 8)
    const fade = ctx.createLinearGradient(0, 1160, 0, 1290)
    fade.addColorStop(0, '#f3e9de00'); fade.addColorStop(1, '#f3e9de')
    ctx.fillStyle = fade; ctx.fillRect(0, 1160, 1080, 280)
  }
  if (theme === 'gold') {
    const glow = ctx.createRadialGradient(1020, 1060, 10, 1020, 1060, 750)
    glow.addColorStop(0, '#9c792b66'); glow.addColorStop(1, '#10151e00')
    ctx.fillStyle = glow; ctx.fillRect(0, 0, 1080, 1440)
    polygon([[570, 0], [1080, 0], [1080, 410]], '#252719')
    ctx.strokeStyle = '#b99a4e'; ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(560, 0); ctx.lineTo(1080, 420); ctx.stroke()
    polygon([[0, 1200], [165, 1100], [292, 1145], [470, 960], [680, 1090], [864, 980], [1080, 1080], [1080, 1440], [0, 1440]], '#393521')
    polygon([[0, 1270], [228, 1160], [390, 1210], [660, 1010], [880, 1150], [1080, 990], [1080, 1440], [0, 1440]], '#171b1c')
    ctx.strokeStyle = '#b99a4e'; ctx.beginPath(); ctx.moveTo(390, 1210); ctx.lineTo(660, 1010); ctx.lineTo(880, 1150); ctx.lineTo(1080, 990); ctx.stroke()
    line(182)
  }
  if (theme === 'architecture') {
    for (let i = 0; i < 3; i++) {
      const x = 610 + i * 132, y = 320 - i * 60
      polygon([[x, y], [x + 108, y - 52], [x + 108, 654], [x, 654]], '#eef0ec')
      polygon([[x + 108, y - 52], [x + 150, y - 20], [x + 150, 654], [x + 108, 654]], '#e2e5df')
    }
    polygon([[794, 247], [1080, 112], [1080, 127], [794, 262]], '#85bd00')
    for (let i = 0; i < 5; i++) polygon([[600 + i * 86, 1170], [940 + i * 86, 965], [969 + i * 86, 965], [629 + i * 86, 1170]], i === 2 ? '#85bd00' : '#ecf0e6')
    box(48, 361, 984, 294, '#fffffff0', 28)
  }
  if (theme === 'globe') {
    const glow = ctx.createRadialGradient(805, 1040, 10, 805, 1040, 390)
    glow.addColorStop(0, '#405829'); glow.addColorStop(1, '#10151e00')
    ctx.fillStyle = glow; ctx.fillRect(0, 0, 1080, 1440)
    ctx.save(); ctx.translate(195, 340); ctx.scale(.76, .76); ctx.strokeStyle = '#9ac95766'; ctx.lineWidth = 1.3
    for (let i = -3; i <= 3; i++) {
      ctx.beginPath(); ctx.ellipse(805, 1070, 274, Math.max(10, 274 * Math.cos(i * .45)), 0, 0, Math.PI * 2); ctx.stroke()
      ctx.beginPath(); ctx.ellipse(805, 1070, Math.max(10, 274 * Math.cos(i * .45)), 274, 0, 0, Math.PI * 2); ctx.stroke()
    }
    for (let i = 0; i < 160; i++) {
      const angle = i * 2.39996, radius = 256 * Math.sqrt(i / 160)
      ctx.fillStyle = i % 4 === 0 ? '#c2e789' : '#769d50'
      ctx.beginPath(); ctx.arc(805 + Math.cos(angle) * radius, 1070 + Math.sin(angle) * radius, i % 4 === 0 ? 2.8 : 1.5, 0, Math.PI * 2); ctx.fill()
    }
    ctx.restore()
  }
  ctx.save(); ctx.globalAlpha = dark ? 0.13 : 0.055; ctx.strokeStyle = accent; ctx.lineWidth = 2
  for (let i = 0; i < (['light', 'dark', 'chart'].includes(theme) ? 5 : 0); i++) {
    ctx.beginPath(); ctx.ellipse(950, 155, 190 + i * 38, 340, Math.PI / 5, 0, Math.PI * 2); ctx.stroke()
  }
  ctx.restore()
  box(72, 66, 12, 46, accent, 6)
  text(brand, 102, 106, 44, ink, 750, 600)
  text(copy.record, 72, 151, 22, muted, 500)
  box(800, 72, 208, 48, dark ? '#26341c' : '#edf6dc')
  text(copy.closed, 822, 104, 22, dark ? '#acd966' : '#548400', 600, 165)
  text(order.symbol, 72, review ? 225 : 270, review ? 56 : 68, ink, 750, 936)
  const side = order.kind === 'option' ? (order.buy ? copy.up : copy.down) : (order.buy ? copy.buy : copy.sell)
  text(`${order.kind === 'contract' ? copy.contract : copy.option}  /  ${side}`, 72, review ? 270 : 326, 28, order.buy ? (dark ? '#acd966' : '#548400') : '#e64042', 600)
  const rate = shareReturn(order)
  const rateLabel = order.kind === 'contract' ? copy.contractRate : copy.optionRate
  if (theme === 'gold' || theme === 'globe') {
    box(48, 367, 984, 290, theme === 'gold' ? '#1e231ce8' : '#1b222ce8')
    box(48, 397, 5, 212, theme === 'gold' ? '#b99a4e' : accent, 2)
  }
  if (options.mode !== 'none') {
  text(options.mode === 'rate' ? rateLabel : copy.pnl, 72, review ? 330 : 418, 28, muted)
  text(options.mode === 'rate' ? `${shareNumber(rate!, 2, true)}%` : shareNumber(order.profit, 2, true), 65, review ? 425 : 534, review ? 90 : 110, pnl, 750, 943)
  text(options.mode === 'rate' ? copy.basis : order.currency || copy.units, 72, review ? 465 : 582, 23, muted)
  if (options.mode === 'both' && rate !== null) text(`${rateLabel}  ${shareNumber(rate, 2, true)}%`, 72, review ? 502 : 628, 25, pnl, 600)
  } else {
    text(copy.record, 72, review ? 415 : 520, 60, ink, 650)
    text(copy.basis, 72, review ? 465 : 582, 23, muted)
  }
  if (review && chart) {
    const { candles, start, end, step } = chart
    const min = Math.min(...(chart.recent ? [] : [order.openPrice, order.closePrice]), ...candles.map(c => c.low))
    const max = Math.max(...(chart.recent ? [] : [order.openPrice, order.closePrice]), ...candles.map(c => c.high))
    const spread = Math.max(max - min, max * 0.0001)
    const first = candles[0]!.timestamp, last = candles[candles.length - 1]!.timestamp + step
    const x = (t: number) => 112 + (t - first) / (last - first) * 840
    const y = (p: number) => 745 - (p - min) / spread * 170
    for (let i = 0; i < 4; i++) line(555 + i * 65)
    const width = Math.min(14, 600 / candles.length)
    for (const candle of candles) {
      const center = x(candle.timestamp + step / 2)
      ctx.strokeStyle = candle.close >= candle.open ? '#85bd00' : '#e64042'; ctx.fillStyle = ctx.strokeStyle
      ctx.beginPath(); ctx.moveTo(center, y(candle.high)); ctx.lineTo(center, y(candle.low)); ctx.stroke()
      ctx.fillRect(center - width / 2, Math.min(y(candle.open), y(candle.close)), width, Math.max(2, Math.abs(y(candle.close) - y(candle.open))))
    }
    const mark = (time: number, price: number, label: string, above: boolean) => {
      ctx.fillStyle = '#526584'; ctx.beginPath(); ctx.arc(x(time), y(price), 6, 0, Math.PI * 2); ctx.fill()
      ctx.save(); ctx.textAlign = time > (first + last) / 2 ? 'right' : 'left'
      text(`${label} ${price}`, x(time), above ? y(price) - 15 : y(price) + 30, 19, ink, 600, 360); ctx.restore()
    }
    if (!chart.recent) { mark(start, order.openPrice, copy.entry, true); mark(end, order.closePrice, copy.exit, false) }
    text(`${chart.source} · ${chart.interval} · ${chart.recent ? copy.recentCaption : copy.chartCaption}`, 72, 800, 18, muted)
  }
  const priceTop = review ? 825 : 677
  box(72, priceTop, 936, review ? 120 : 169, panel)
  text(copy.entry, 108, priceTop + 43, 23, muted, 400, 370)
  text(copy.exit, 573, priceTop + 43, 23, muted, 400, 395)
  const price = (n: number) => new Intl.NumberFormat('en-US', { maximumFractionDigits: 16 }).format(n)
  text(price(order.openPrice), 108, priceTop + (review ? 92 : 123), review ? 39 : 48, ink, 650, 365)
  text(price(order.closePrice), 573, priceTop + (review ? 92 : 123), review ? 39 : 48, ink, 650, 395)
  text('→', 504, priceTop + (review ? 87 : 114), 35, muted, 400, 40)
  const rows: Array<[string, string]> = []
  if (options.mode === 'amount' || options.mode === 'both') {
    if (options.quantity && order.kind === 'contract' && order.quantity !== null) rows.push([copy.quantity, new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 16 }).format(order.quantity)])
    if (options.capital) {
      const capital = order.kind === 'contract' ? order.margin : order.amount
      if (capital !== null) rows.push([order.kind === 'contract' ? copy.margin : copy.investment, shareNumber(capital)])
    }
    if (options.fee && order.kind === 'contract' && order.fee !== null) rows.push([copy.fee, shareNumber(order.fee)])
  }
  if (options.leverage && order.kind === 'contract' && order.leverage !== null) rows.push([copy.leverage, `${order.leverage}×`])
  if (options.orderId) rows.push([copy.orderId, `****${order.id.slice(-4)}`])
  if (options.openTime) rows.push([copy.openTime, order.openTime || '—'])
  rows.push([copy.closeTime, order.closeTime])
  rows.forEach(([label, value], i) => {
    const y = (review ? 982 : 900) + i * (review ? Math.min(40, 210 / Math.max(1, rows.length - 1)) : Math.min(51, 290 / Math.max(1, rows.length - 1)))
    text(label, 72, y, 22, muted, 400, 390)
    ctx.save(); ctx.textAlign = 'right'; text(value, 1008, y, 24, ink, 500, 490); ctx.restore()
    line(y + 17)
  })
  text(timezone, 72, theme === 'city' ? 1260 : 1224, 19, muted)
  text(copy.basis, 72, 1300, 21, muted, 400, qr ? 730 : 936)
  text(copy.footer, 72, 1352, 20, muted, 400, qr ? 730 : 936)
  box(72, 1380, 64, 5, accent, 2)
  if (qr) {
    box(851, 1242, 157, 157, '#ffffff', 12)
    ctx.drawImage(qr, 861, 1252, 137, 137)
  }
}

// Reference layouts use their original portrait proportions and fixed Japanese/English typography.
// Financial values come from the settled order; background candles come from market data.
function drawReferencePoster(canvas: HTMLCanvasElement, order: ShareOrder, options: ShareOptions,
  copy: ShareCopy, brand: string, qr?: HTMLImageElement, chart?: ShareChart, background?: HTMLImageElement) {
  if (!chart?.candles.length) throw new Error(copy.chartError)
  const terminal = options.template === 'referenceTerminal', gold = options.template === 'referenceGold'
  if (!background) throw new Error(copy.backgroundError)
  const w = terminal ? 285 : 654, h = terminal ? 484 : 1200
  canvas.width = 1080; canvas.height = Math.round(1080 * h / w)
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('Canvas unavailable')
  ctx.scale(canvas.width / w, canvas.height / h)
  const dark = terminal || gold, ink = dark ? '#f5f5f5' : '#080808', muted = dark ? '#b3b8bd' : '#777777'
  const gain = terminal ? '#5ac8a4' : gold ? '#15d598' : '#129f36', loss = '#e6545d'
  const profitColor = order.profit < 0 ? loss : order.profit > 0 ? gain : muted
  const amountVisible = options.mode === 'amount' || options.mode === 'both'
  const rateVisible = options.mode === 'rate' || options.mode === 'both'
  const returnValue = shareReturn(order)
  const amount = terminal ? shareNumber(order.profit, 2, true) : shareNumber(order.profit).replace(/,/g, '')
  const rate = returnValue === null ? '—' : `${shareNumber(returnValue, 2, true)}%`
  const visibleMain = amountVisible ? amount : rateVisible ? rate : '取引記録'
  const side = order.buy ? '買い' : '売り', sideColor = order.buy ? gain : loss
  const num = (value: number | null, digits = 2) => value === null ? '—' : value.toFixed(digits)
  const money = (value: number | null) => amountVisible ? num(value) : '—'
  const quantity = amountVisible ? `${num(order.quantity)}${terminal ? ' ロット' : 'ロット'}` : '—'
  const capital = order.kind === 'contract' ? order.margin : order.amount
  const currency = order.currency || copy.units
  const price = (value: number, exit = false) => new Intl.NumberFormat('en-US', {
    useGrouping: false, minimumFractionDigits: exit ? 6 : 0, maximumFractionDigits: 16,
  }).format(value)
  const t = (value: string, x: number, y: number, size: number, color = ink, weight = 400, max = w - x - 25, family = 'Arial, "Microsoft YaHei", sans-serif') => {
    let fontSize = size
    do {
      ctx.font = `${weight} ${fontSize}px ${family}`
      if (ctx.measureText(value).width <= max || fontSize <= 5) break
      fontSize -= .5
    } while (true)
    ctx.fillStyle = color; ctx.fillText(value, x, y)
  }
  const rect = (x: number, y: number, width: number, height: number, fill: string | CanvasGradient, radius = 16, stroke?: string) => {
    ctx.beginPath(); ctx.roundRect(x, y, width, height, radius); ctx.fillStyle = fill; ctx.fill()
    if (stroke) { ctx.strokeStyle = stroke; ctx.lineWidth = 1; ctx.stroke() }
  }
  const line = (x: number, y: number, ex: number, ey: number, color: string, width = 1) => {
    ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(ex, ey); ctx.strokeStyle = color; ctx.lineWidth = width; ctx.stroke()
  }
  const gradient = (x: number, y: number, ex: number, ey: number, from: string, to: string) => {
    const result = ctx.createLinearGradient(x, y, ex, ey); result.addColorStop(0, from); result.addColorStop(1, to); return result
  }
  const plot = (x: number, y: number, width: number, height: number, labels: boolean, volumes = false) => {
    const candles = chart.candles, first = candles[0]!.timestamp, last = candles[candles.length - 1]!.timestamp + chart.step
    const low = Math.min(...(chart.recent ? [] : [order.openPrice, order.closePrice]), ...candles.map(c => c.low))
    const high = Math.max(...(chart.recent ? [] : [order.openPrice, order.closePrice]), ...candles.map(c => c.high))
    const spread = Math.max(high - low, high * .00005), padding = spread * .13
    const volumeHeight = volumes && candles.some(c => c.volume !== undefined) ? 38 : 0
    const px = (stamp: number) => x + (stamp - first) / (last - first) * width
    const py = (value: number) => y + height - volumeHeight - (value - low + padding) / (spread + padding * 2) * (height - volumeHeight)
    for (let i = 0; i <= 5; i++) {
      const gy = y + i * (height - volumeHeight) / 5
      line(x, gy, x + width, gy, dark ? '#17232a' : '#e6e8e6', .6)
      if (gold && labels) t((high + padding - i * (spread + 2 * padding) / 5).toFixed(3), x + width + 12, gy + 5, 15, '#9ba4af', 400, 70)
    }
    const body = Math.max(.7, Math.min(gold ? 7 : terminal ? 1.7 : 4, width / candles.length * .62))
    const maximumVolume = Math.max(1, ...candles.map(c => c.volume || 0))
    candles.forEach(c => {
      const cx = px(c.timestamp + chart.step / 2), color = c.close >= c.open ? (gold ? '#edc444' : '#39b98a') : gold ? '#109d74' : '#b9564b'
      line(cx, py(c.high), cx, py(c.low), color, terminal ? .55 : .9)
      ctx.fillStyle = color; ctx.fillRect(cx - body / 2, Math.min(py(c.open), py(c.close)), body, Math.max(terminal ? .65 : 1.5, Math.abs(py(c.open) - py(c.close))))
      if (volumeHeight && c.volume !== undefined) {
        ctx.save(); ctx.globalAlpha = .4
        if (gold) ctx.fillStyle = c.close >= c.open ? '#1c754f' : '#9b523b'
        ctx.fillRect(cx - body / 2, y + height - c.volume / maximumVolume * 32, body, c.volume / maximumVolume * 32); ctx.restore()
      }
    })
    if (labels && !chart.recent) {
      const dot = (stamp: number, value: number, close: boolean) => {
        const cx = Math.max(x + 7, Math.min(x + width - 7, px(stamp))), cy = py(value)
        ctx.beginPath(); ctx.arc(cx, cy, terminal ? 1.7 : 4.5, 0, Math.PI * 2); ctx.fillStyle = close ? (gold ? '#ffe78c' : '#4c83f5') : '#f5f5f5'; ctx.fill()
        const label = close ? price(value, true) : price(value)
        const fontSize = terminal ? 9 : 17, tagWidth = terminal ? (close ? 59 : 38) : Math.min(132, label.length * 9)
        const tx = Math.min(x + width - tagWidth, Math.max(x, cx - (close ? tagWidth : 0)))
        const ty = Math.max(y + 12, Math.min(y + height - 4, cy + (close ? -19 : 24)))
        if (terminal) rect(tx - 2, ty - 10, tagWidth, 16, close ? '#3164f6' : '#244540', 3)
        t(label, tx, ty + (terminal ? 1 : 0), fontSize, '#fff', 500, tagWidth)
      }
      dot(chart.start, order.openPrice, false); dot(chart.end, order.closePrice, true)
    }
  }
  ctx.fillStyle = dark ? '#020b12' : '#fafafa'; ctx.fillRect(0, 0, w, h)
  if (background) ctx.drawImage(background, 0, 0, w, h)

  if (gold) {
    t(brand, 32, 67, 36, '#f7ca20', 750, 400)
    t('TRADE SMARTER', 490, 49, 13, muted, 400, 140); t('TRADE FREER', 490, 69, 13, muted, 400, 140)
    line(30, 101, 624, 101, '#8a8980'); line(30, 101, 118, 101, '#ffe255', 2)
    t('ご注文の詳細', 32, 171, 42, ink, 750); t('ORDER DETAILS', 32, 202, 18, '#d8d8d8')
    t(order.symbol, 32, 270, 44, ink, 750, 340)
    rect(400, 216, 120, 51, '#090d0bbb', 12, '#edcf21'); t(side, 432, 253, 28, '#f2cf2d', 700, 84)
    if (order.symbol.endsWith('JPY')) { rect(33, 287, 40, 30, '#fff', 3); ctx.fillStyle = '#e2002d'; ctx.beginPath(); ctx.arc(53, 302, 10, 0, Math.PI * 2); ctx.fill() }
    t(order.symbol.replace(/^([A-Z]{3})([A-Z]{3})$/, '$1/$2'), order.symbol.endsWith('JPY') ? 86 : 32, 310, 22, ink)
    plot(32, 338, 520, 235, true, true)
    t(`${chart.source} · ${chart.interval}`, 33, 585, 9, muted, 400, 560)
    rect(30, 592, 596, 94, gradient(30, 592, 626, 686, '#1b2023ed', '#101518ed'), 13, '#657075')
    t('エントリー価格', 60, 627, 20, muted, 400, 230); t('決済価格', 398, 627, 20, muted)
    t(price(order.openPrice), 60, 665, 29, ink, 500, 225); t('→', 288, 651, 40, ink, 400, 60); t(price(order.closePrice, true), 398, 665, 29, ink, 500, 202)
    rect(30, 701, 596, 345, gradient(30, 701, 626, 1046, '#12191cef', '#0c1114e8'), 12, '#606c72')
    t(amountVisible ? `実現損益 (${currency})` : rateVisible ? '収益率' : '取引記録', 56, 746, 25, ink, 500, 400)
    t(visibleMain, 54, 824, 78, profitColor, 750, 430)
    const barsVisible = amountVisible || rateVisible
    if (barsVisible) {
      for (let i = 0; i < 9; i++) {
        const barHeight = 7 + (order.profit < 0 ? 8 - i : i) * 9
        ctx.fillStyle = gradient(0, 794 - barHeight, 0, 807, '#fbd447', '#ad812900')
        ctx.fillRect(433 + i * 18, 807 - barHeight, 12, barHeight)
      }
    }
    if (amountVisible && rateVisible) t(rate, 501, 830, 28, profitColor, 700, 110)
    line(31, 855, 625, 855, '#384249')
    for (const x of [236, 468]) line(x, 876, x, 935, '#384249')
    t('取引数量', 55, 894, 18, muted); t(order.kind === 'contract' ? 'セキュリティーデポジット' : '投資額', 258, 894, 16, muted, 400, 200); t('手数料', 493, 894, 18, muted)
    t(quantity, 55, 929, 24, ink, 500, 167); t(money(capital), 258, 929, 24, ink, 500, 198); t(money(order.fee), 493, 929, 24, ink, 500, 112)
    line(31, 952, 625, 952, '#384249'); line(236, 968, 236, 1024, '#384249')
    t('注文 ID #', 55, 986, 19, muted); t(order.id, 55, 1019, 25, ink, 400, 166)
    t('注文日時', 258, 986, 19, muted); t(order.openTime || order.closeTime, 258, 1019, 25, muted, 400, 345)
    t('B U I L D  Y O U R  F R E E D O M', 31, 1116, 13, '#a8aaab', 400, 400)
    t(`W I T H  ${brand}`, 31, 1140, 14, '#a8aaab', 400, 400); line(31, 1156, 72, 1156, '#f4d023', 5)
    if (qr) { rect(513, 1082, 101, 101, '#fff', 5); ctx.drawImage(qr, 518, 1087, 91, 91) }
    else { t('TRADE RECORD', 460, 1150, 11, muted, 400, 170); t('RECORDED SETTLEMENT', 460, 1169, 10, muted, 400, 170) }
  } else if (!terminal) {
    t(brand, 46, 88, 62, '#050505', 900, 300); t('T R A D I N G  F O R  A  B R I G H T E R  T O M O R R O W', 46, 122, 10, '#303030', 400, 510)
    t('世界とつながる', 469, 56, 17, '#333', 400, 160); t('次のチャンスを、あなたに', 397, 79, 17, '#333', 400, 230); line(570, 103, 601, 103, '#555')
    t(order.symbol, 44, 231, 53, '#000', 750, 365)
    t(order.symbol === 'USDJPY' ? '米ドル / 日本円' : order.symbol.replace(/^([A-Z]{3})([A-Z]{3})$/, '$1 / $2'), 44, 269, 25, '#333', 400, 370)
    rect(46, 283, 180, 54, '#efffe2c9', 28, '#a0ed3d'); t(`${side}  ${order.buy ? 'Buy' : 'Sell'}`, 73, 321, 29, sideColor, 500, 145)
    rect(20, 360, 610, 146, '#ffffffec', 20, '#e3e3e3')
    t('エントリー価格', 50, 402, 21, '#080808', 600, 255); t('Entry Price', 50, 429, 20, '#777')
    t(price(order.openPrice), 50, 477, 40, '#050505', 650, 240)
    t('決済価格', 368, 402, 21, '#080808', 600, 238); t('Exit Price', 368, 429, 20, '#777')
    t(price(order.closePrice, true), 368, 477, 40, '#050505', 650, 235); t('→', 286, 455, 48, '#888', 400, 66)
    rect(20, 512, 610, 150, '#ffffffed', 20, '#e3e3e3')
    if (amountVisible || rateVisible) {
      const endY = order.profit < 0 ? 634 : 550
      ctx.beginPath(); ctx.moveTo(275, 660); ctx.bezierCurveTo(460, 631, 556, 615, 584, endY); ctx.lineTo(602, endY); ctx.lineTo(602, 660); ctx.closePath()
      ctx.fillStyle = gradient(275, 660, 602, endY, '#b2ef6200', order.profit < 0 ? '#ffd2d2' : '#e0ffc2'); ctx.fill()
      line(554, endY + (order.profit < 0 ? -38 : 38), 584, endY, order.profit < 0 ? '#f4b8b8' : '#c9f79d', 8)
      line(554, endY + (order.profit < 0 ? -5 : 9), 584, endY, order.profit < 0 ? '#f4b8b8' : '#c9f79d', 8)
      line(584, endY, 580, endY + (order.profit < 0 ? -33 : 33), order.profit < 0 ? '#f4b8b8' : '#c9f79d', 8)
    }
    t(amountVisible ? '実現損益' : rateVisible ? '収益率' : '取引記録', 51, 557, 23, '#080808', 600, 220)
    t(amountVisible ? 'Realized PnL' : rateVisible ? 'Return' : 'Trade Record', 170, 557, 20, '#777', 400, 320)
    t(visibleMain, 50, 631, 75, profitColor, 750, 465)
    if (amountVisible && rateVisible) t(rate, 475, 641, 25, profitColor, 650, 130)
    t(currency, 52, 655, 11, muted, 400, 360)
    rect(20, 666, 610, 240, '#ffffffca', 19, '#e3e3e3')
    t('取引数量', 51, 703, 18, '#555', 400, 150); t('Lots', 51, 727, 19, '#777'); t(quantity, 51, 764, 25, '#050505', 500, 154)
    t(order.kind === 'contract' ? 'セキュリティーデポジット' : '投資額', 232, 703, 17, '#555', 400, 218); t(order.kind === 'contract' ? 'Security Deposit' : 'Investment', 232, 727, 19, '#777', 400, 215); t(money(capital), 232, 764, 25, '#050505', 500, 216)
    t('手数料', 498, 703, 18, '#555'); t('Fee', 498, 727, 19, '#777'); t(money(order.fee), 498, 764, 25, '#050505', 500, 110)
    line(211, 686, 211, 765, '#e6e6e6'); line(475, 686, 475, 765, '#e6e6e6'); line(52, 790, 600, 790, '#e6e6e6'); line(270, 808, 270, 883, '#e6e6e6')
    t('注文 ID', 51, 825, 19, '#444'); t('Order ID', 51, 849, 18, '#777'); t(`# ${order.id}`, 51, 882, 26, '#050505', 500, 204)
    t('注文日時', 300, 825, 19, '#444'); t('Open Time', 300, 849, 18, '#777'); t(order.openTime || order.closeTime, 300, 882, 24, '#050505', 500, 305)
    plot(38, 936, 419, 129, false)
    line(470, 940, 470, 1049, '#ddd')
    for (const [i, word] of ['SMALL', 'TRADES', 'BIG', 'POSSIBILITIES'].entries()) t(word, 502, 954 + i * 24, 16, '#b4b4b4', 400, 134)
    t(`${chart.source} · ${chart.interval}`, 39, 1081, 9, '#999', 400, 410)
    t('Trade the World', 44, 1118, 26, '#050505', 650, 365); t('取引で、より良い自分へ', 44, 1142, 15, '#666', 400, 365)
    if (qr) { rect(542, 1070, 78, 78, '#fff', 2); ctx.drawImage(qr, 545, 1073, 72, 72) }
    t(brand, qr ? 412 : 493, 1125, 25, '#080808', 850, qr ? 119 : 130)
    t('※ 単一取引の記録です。将来の収益を保証するものではありません。', 87, 1175, 12, '#888', 400, 535)
  } else {
    t(brand, 20, 43, 23, '#f4f5f6', 750, 220)
    t(order.symbol, 20, 96, 22, '#f4f5f6', 650, 245)
    t(`${price(order.openPrice)}  →  ${price(order.closePrice, true)}`, 20, 120, 15, '#c8cbd0', 400, 246)
    plot(20, 132, 250, 157, true)
    // A subtle text backdrop keeps real candles legible around the large P&L overlay.
    ctx.fillStyle = gradient(16, 0, 207, 0, '#020b12f5', '#020b1200'); ctx.fillRect(16, 132, 201, 70)
    t(visibleMain, 20, 167, 39, profitColor, 750, 225)
    if (amountVisible && rateVisible) t(rate, 20, 197, 18, profitColor, 700, 188)
    t(`${side} · ${quantity}`, 20, 315, 15, '#ddf0ed', 500, 250)
    const labels = [order.kind === 'contract' ? '証拠金' : '投資額', '手数料', '注文ID'], values = [money(capital), money(order.fee), `#${order.id}`]
    for (let i = 0; i < 3; i++) {
      const x = [20, 119, 210][i]!, width = [94, 85, 62][i]!
      rect(x, 328, width, 54, '#09131e', 7)
      t(labels[i]!, x + 7, 349, 12, '#9da8b4', 400, width - 14); t(values[i]!, x + 7, 370, 14, '#e2e7ed', 500, width - 14)
    }
    t(order.openTime || order.closeTime, 20, 406, 14, '#bac2ce', 400, 250)
    line(20, 418, 269, 418, '#142331', .6)
    t('Trade the World', 20, 452, 21, '#d8dde5', 400, qr ? 180 : 190, '"Segoe Script", cursive')
    t(`${chart.source} · ${chart.interval}`, 20, 473, 7, '#768490', 400, 193)
    if (qr) { rect(220, 420, 53, 53, '#fff', 4); ctx.drawImage(qr, 223, 423, 47, 47) }
  }
}

function drawCollectionPoster(canvas: HTMLCanvasElement, order: ShareOrder, options: ShareOptions,
  copy: ShareCopy, brand: string, timezone: string, qr?: HTMLImageElement, chart?: ShareChart, background?: HTMLImageElement) {
  const theme = options.template
  if (shareBackgrounds[theme] && !background) throw new Error(copy.backgroundError)
  if (theme === 'journal' && !chart?.candles.length) throw new Error(copy.chartError)
  canvas.width = 1080; canvas.height = 1440
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('Canvas unavailable')
  ctx.scale(2, 2)
  const light = ['receipt', 'journal', 'voyage'].includes(theme)
  const ink = light ? '#162320' : '#f5f8fa', muted = light ? '#697570' : '#a0b1bb'
  const accent = theme === 'launch' ? '#e1b55c' : theme === 'aurora' ? '#67e4d6' : theme === 'racing' ? '#bdf267' : '#7b9d20'
  const profitColor = order.profit > 0 ? (light ? '#36884f' : '#82e2af') : order.profit < 0 ? (light ? '#c24047' : '#ff7e85') : muted
  const amountVisible = options.mode === 'amount' || options.mode === 'both'
  const rateVisible = options.mode === 'rate' || options.mode === 'both'
  const rateValue = shareReturn(order), rate = rateValue === null ? '—' : `${shareNumber(rateValue, 2, true)}%`
  const main = amountVisible ? shareNumber(order.profit, 2, true) : rateVisible ? rate : copy.record
  const mainLabel = amountVisible ? copy.pnl : rateVisible ? copy.rate : copy.record
  const unit = amountVisible ? order.currency || copy.units : copy.basis
  const side = order.kind === 'contract' ? (order.buy ? copy.buy : copy.sell) : order.buy ? copy.up : copy.down
  const chinese = copy.record === '交易記錄', japanese = copy.record === '取引記録'
  const slogan = (zh: string, en: string, ja: string) => chinese ? zh : japanese ? ja : en
  const text = (value: string, x: number, y: number, size: number, color = ink, weight = 400, width = 468, family = 'Arial, "Microsoft YaHei", sans-serif') => {
    let fontSize = size
    do {
      ctx.font = `${weight} ${fontSize}px ${family}`
      if (ctx.measureText(value).width <= width || fontSize <= 6) break
      fontSize -= .5
    } while (true)
    ctx.fillStyle = color; ctx.fillText(value, x, y)
  }
  const box = (x: number, y: number, w: number, h: number, fill: string | CanvasGradient, radius = 16, stroke?: string) => {
    ctx.beginPath(); ctx.roundRect(x, y, w, h, radius); ctx.fillStyle = fill; ctx.fill()
    if (stroke) { ctx.strokeStyle = stroke; ctx.lineWidth = 1; ctx.stroke() }
  }
  const line = (x: number, y: number, ex: number, ey: number, color: string, width = 1) => {
    ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(ex, ey); ctx.strokeStyle = color; ctx.lineWidth = width; ctx.stroke()
  }
  const grad = (y: number, end: number, first: string, last: string) => {
    const g = ctx.createLinearGradient(0, y, 0, end); g.addColorStop(0, first); g.addColorStop(1, last); return g
  }
  const price = (n: number) => new Intl.NumberFormat('en-US', { maximumFractionDigits: 16 }).format(n)
  const brandLine = () => {
    box(35, 34, 5, 26, accent, 2)
    text(brand, 51, 56, 25, ink, 750, 280)
    text(copy.closed, 410, 53, 12, muted, 500, 96)
  }
  const direction = (x: number, y: number, width = 220) => {
    const color = order.buy ? (light ? '#327749' : '#87ddb7') : (light ? '#b64650' : '#f28e94')
    text(`${side}  ·  ${order.kind === 'contract' ? copy.contract : copy.option}`, x, y, 15, color, 600, width)
  }
  const metric = (x: number, y: number, width = 468, size = 70) => {
    text(mainLabel, x, y, 15, muted, 500, width)
    text(main, x - 2, y + 72, size, options.mode === 'none' ? ink : profitColor, 750, width)
    text(unit, x, y + 96, 12, muted, 400, width)
    if (amountVisible && rateVisible) text(`${copy.rate}  ${rate}`, x, y + 132, 25, profitColor, 650, width)
  }
  const pricePair = (x: number, y: number, width: number, color = ink) => {
    text(copy.entry, x, y, 12, muted, 400, width / 2 - 15)
    text(copy.exit, x + width / 2 + 12, y, 12, muted, 400, width / 2 - 12)
    text(price(order.openPrice), x, y + 34, 27, color, 600, width / 2 - 20)
    text(price(order.closePrice), x + width / 2 + 12, y + 34, 27, color, 600, width / 2 - 12)
  }
  const footer = (color = muted, top = 663, qrTop = 629, qrSize = 70) => {
    text(`${copy.closeTime}  ${order.closeTime}`, 36, top, 11, color, 400, qr ? 386 : 468)
    text(`${timezone} · ${copy.basis}`, 36, top + 20, 10, color, 400, qr ? 386 : 468)
    text(copy.footer, 36, top + 39, 9, color, 400, qr ? 386 : 468)
    if (qr) { box(439, qrTop, qrSize, qrSize, '#fff', 8); ctx.drawImage(qr, 443, qrTop + 4, qrSize - 8, qrSize - 8) }
  }
  ctx.fillStyle = light ? '#fafbf7' : '#071018'; ctx.fillRect(0, 0, 540, 720)
  if (background) ctx.drawImage(background, 0, 0, 540, 720)

  if (theme === 'launch') {
    brandLine()
    text('OWN YOUR', 35, 132, 51, ink, 800)
    text('EXECUTION.', 35, 187, 51, ink, 800)
    text(slogan('讓每一次執行，留下記錄。', 'Every execution deserves a record.', '一つひとつの取引を、記録に。'), 36, 217, 15, '#b7aa91')
    text(order.symbol, 35, 290, 43, ink, 750, 450); direction(36, 318, 400)
    metric(36, 359, 466, 70)
    box(26, 516, 281, 108, '#11171bd9', 16, '#ffffff18')
    pricePair(42, 548, 244)
    line(36, 642, 105, 642, accent, 3)
    ctx.fillStyle = grad(644, 720, '#07101800', '#071018f0'); ctx.fillRect(0, 644, 540, 76)
    footer('#c0c7c7')
  } else if (theme === 'aurora') {
    brandLine()
    text('LIQUID', 35, 137, 57, '#e2fffb', 750, 268)
    text('FOCUS.', 35, 194, 57, '#e2fffb', 750, 268)
    text(slogan('市場在流動，記錄有自己的節奏。', 'A record of your rhythm in the market.', '動く市場、自分のリズム。'), 36, 224, 13, '#b6d7d3', 400, 284)
    text(order.symbol, 36, 284, 39, ink, 700, 454); direction(37, 312, 435)
    box(26, 335, 488, 275, '#06292ce6', 24, '#68e0da66')
    metric(48, 370, 443, 69)
    line(48, 521, 491, 521, '#7cddd433')
    pricePair(48, 551, 443)
    text('PRECISION IN EVERY DETAIL', 36, 645, 12, accent, 600, qr ? 370 : 468)
    footer('#b3c6c5', 666)
  } else if (theme === 'racing') {
    brandLine()
    text('IN THE ZONE.', 35, 137, 53, '#f1f6ff', 850, 475)
    text(slogan('保持專注，記下這一刻。', 'Stay focused. Keep the moment.', '集中を、その一瞬の記録に。'), 36, 169, 16, '#b6c7e8')
    text(order.symbol, 36, 233, 45, '#fff', 750); direction(37, 264, 450)
    box(26, 286, 488, 190, '#123a87e8', 5, '#78a2ed66')
    box(26, 286, 6, 190, '#bdf267', 0)
    metric(47, 316, 443, 71)
    box(27, 497, 486, 91, '#091831d9', 6, '#90b5ed30')
    pricePair(47, 524, 444, '#f0f7ff')
    ctx.fillStyle = grad(613, 720, '#06122c00', '#06122cf5'); ctx.fillRect(0, 613, 540, 107)
    text('FOCUS. EXECUTE. REVIEW.', 36, 643, 16, '#d7edac', 750, qr ? 380 : 470)
    footer('#b8c9df', 665)
  } else if (theme === 'receipt') {
    ctx.fillStyle = '#e6e8de'; ctx.fillRect(0, 0, 540, 720)
    box(25, 22, 490, 679, '#fcfcf7', 4)
    const mono = 'Consolas, "Microsoft YaHei", monospace'
    text(brand, 48, 61, 24, '#222d26', 750, 240, mono)
    text('TRADE TICKET', 327, 57, 12, '#768271', 400, 165, mono)
    line(48, 83, 492, 83, '#d5d9cf')
    text(order.symbol, 46, 150, 58, '#263d2b', 500, 448, 'Georgia, "Microsoft YaHei", serif')
    direction(49, 182, 350)
    text(`${copy.closed}  /  #${order.id}`, 49, 210, 12, '#73806c', 400, 442, mono)
    ctx.setLineDash([3, 5]); line(26, 237, 514, 237, '#b5bfaa'); ctx.setLineDash([])
    ctx.fillStyle = '#e6e8de'
    for (const x of [25, 515]) { ctx.beginPath(); ctx.arc(x, 237, 10, 0, Math.PI * 2); ctx.fill() }
    box(46, 258, 448, 174, '#ebf0df', 3)
    metric(62, 284, 411, 65)
    const receiptRow = (label: string, value: string, y: number) => {
      text(label, 48, y, 13, '#7c8376', 400, 165, mono)
      ctx.save(); ctx.textAlign = 'right'; text(value, 491, y, 17, '#2b372d', 600, 273, mono); ctx.restore()
    }
    receiptRow(copy.entry, price(order.openPrice), 454)
    receiptRow(copy.exit, price(order.closePrice), 488)
    receiptRow(copy.openTime, order.openTime || '—', 528)
    receiptRow(copy.closeTime, order.closeTime, 563)
    ctx.setLineDash([3, 5]); line(47, 583, 493, 583, '#b5bfaa'); ctx.setLineDash([])
    text(slogan('每筆交易，都值得被記錄。', 'Every trade has a story.', 'すべての取引に、記録を。'), 48, 615, 15, '#65764d', 500, qr ? 345 : 440)
    text(timezone, 48, 640, 11, '#7b8372', 400, 350, mono)
    text(copy.basis, 48, 663, 10, '#7b8372', 400, qr ? 345 : 440)
    text(copy.footer, 48, 682, 9, '#7b8372', 400, qr ? 345 : 440)
    if (qr) { box(421, 607, 70, 70, '#fff', 3); ctx.drawImage(qr, 425, 611, 62, 62) }
    ctx.fillStyle = '#e6e8de'
    for (let x = 33; x < 515; x += 16) { ctx.beginPath(); ctx.arc(x, 702, 5, 0, Math.PI * 2); ctx.fill() }
  } else if (theme === 'journal' && chart) {
    ctx.fillStyle = '#edf57d'; ctx.fillRect(0, 0, 540, 13)
    brandLine()
    text('TRADE', 34, 133, 55, '#172a2b', 800)
    text('JOURNAL', 34, 188, 55, '#172a2b', 800)
    text(slogan('讀懂過程，記錄結果。', 'Understand the process. Record the result.', '過程を読み、結果を残す。'), 36, 216, 15, '#778278')
    text(order.symbol, 36, 269, 32, '#173932', 750, 320)
    ctx.save(); ctx.textAlign = 'right'; text(side, 502, 266, 14, order.buy ? '#398551' : '#c24047', 600, 175); ctx.restore()
    line(36, 286, 503, 286, '#d6ddcf')
    text(mainLabel, 36, 316, 12, '#74806e')
    text(main, 34, 365, 56, options.mode === 'none' ? ink : profitColor, 750, amountVisible && rateVisible ? 330 : 468)
    if (amountVisible && rateVisible) { text(copy.rate, 383, 316, 12, '#74806e', 400, 119); text(rate, 383, 365, 31, profitColor, 700, 119) }
    text(unit, 36, 387, 10, '#798374')
    const candles = chart.candles, left = candles[0]!.timestamp, right = candles[candles.length - 1]!.timestamp + chart.step
    const low = Math.min(...(chart.recent ? [] : [order.openPrice, order.closePrice]), ...candles.map(c => c.low)), high = Math.max(...(chart.recent ? [] : [order.openPrice, order.closePrice]), ...candles.map(c => c.high))
    const spread = Math.max(high - low, high * .0001)
    const px = (stamp: number) => 44 + (stamp - left) / (right - left) * 452
    const py = (value: number) => 532 - (value - low) / spread * 106
    box(26, 402, 488, 159, '#f0f3e9', 7)
    for (let i = 0; i < 4; i++) line(43, 422 + i * 36, 498, 422 + i * 36, '#dde4d2', .7)
    const body = Math.max(.8, Math.min(5, 300 / candles.length))
    for (const candle of candles) {
      const x = px(candle.timestamp + chart.step / 2), color = candle.close >= candle.open ? '#53a17a' : '#d56d68'
      line(x, py(candle.high), x, py(candle.low), color, .7)
      ctx.fillStyle = color; ctx.fillRect(x - body / 2, Math.min(py(candle.open), py(candle.close)), body, Math.max(1, Math.abs(py(candle.open) - py(candle.close))))
    }
    if (!chart.recent) for (const [stamp, value, label] of [[chart.start, order.openPrice, copy.entry], [chart.end, order.closePrice, copy.exit]] as const) {
      const x = px(stamp), y = py(value)
      ctx.beginPath(); ctx.moveTo(x, y - 5); ctx.lineTo(x - 4, y - 12); ctx.lineTo(x + 4, y - 12); ctx.closePath(); ctx.fillStyle = '#495ba3'; ctx.fill()
      ctx.save(); ctx.textAlign = stamp === chart.end ? 'right' : 'left'; text(label, x, y - 17, 8, '#526394', 500, 100); ctx.restore()
    }
    text(`${chart.source} · ${chart.interval} · ${chart.recent ? copy.recentCaption : copy.chartCaption}`, 36, 578, 8, '#7a8676', 400, 468)
    pricePair(36, 603, 468)
    line(36, 652, 504, 652, '#d6ddcf')
    footer('#75806f', 671, 649, 59)
  } else if (theme === 'voyage') {
    ctx.fillStyle = grad(0, 565, '#edf3f9aa', '#fff5e900'); ctx.fillRect(0, 0, 540, 565)
    brandLine()
    text('TRADE THE', 34, 135, 51, '#183247', 700, 469, 'Georgia, serif')
    text('WORLD.', 34, 192, 55, '#183247', 700, 469, 'Georgia, serif')
    text(slogan('每一程，都有自己的交易故事。', 'Your journey. Your trading story.', '旅の途中に、自分だけの取引記録。'), 36, 223, 14, '#465c6e', 400, 425)
    text(order.symbol, 35, 283, 39, '#183247', 750, 455)
    direction(36, 311, 412)
    box(25, 331, 374, 179, '#fffcf3df', 18)
    metric(42, 363, 338, 57)
    box(25, 526, 374, 90, '#fff9eee8', 14)
    pricePair(43, 553, 336, '#283f41')
    ctx.fillStyle = grad(619, 720, '#07132100', '#071321de'); ctx.fillRect(0, 619, 540, 101)
    footer('#ecede7', 666)
  }
}
