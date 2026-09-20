export const indicatorCatalog = [
  { name: 'MA', zh: '移動平均線', en: 'Moving average', main: true, params: [5, 10, 30] },
  { name: 'EMA', zh: '指數移動平均', en: 'Exponential moving average', main: true, params: [6, 12, 20] },
  { name: 'SMA', zh: '平滑移動平均', en: 'Smoothed moving average', main: true, params: [12, 2] },
  { name: 'BOLL', zh: '布林通道', en: 'Bollinger bands', main: true, params: [20, 2] },
  { name: 'SAR', zh: '拋物線轉向', en: 'Parabolic SAR', main: true, params: [2, 2, 20] },
  { name: 'BBI', zh: '多空指標', en: 'Bull and bear index', main: true, params: [3, 6, 12, 24] },
  { name: 'VOL', zh: '成交量', en: 'Volume', main: false, params: [5, 10, 20] },
  { name: 'MACD', zh: '平滑異同移動平均', en: 'Moving average convergence divergence', main: false, params: [12, 26, 9] },
  { name: 'RSI', zh: '相對強弱指標', en: 'Relative strength index', main: false, params: [6, 12, 24] },
  { name: 'KDJ', zh: '隨機指標', en: 'Stochastic oscillator', main: false, params: [9, 3, 3] },
  { name: 'DMI', zh: '動向指標 / ADX', en: 'Directional movement / ADX', main: false, params: [14, 6] },
  { name: 'CCI', zh: '順勢指標', en: 'Commodity channel index', main: false, params: [20] },
  { name: 'OBV', zh: '能量潮', en: 'On-balance volume', main: false, params: [30] },
  { name: 'WR', zh: '威廉指標', en: 'Williams %R', main: false, params: [6, 10, 14] },
  { name: 'ROC', zh: '變動率', en: 'Rate of change', main: false, params: [12, 6] },
  { name: 'MTM', zh: '動量指標', en: 'Momentum', main: false, params: [12, 6] },
  { name: 'AO', zh: '動量震盪指標', en: 'Awesome oscillator', main: false, params: [5, 34] },
  { name: 'BIAS', zh: '乖離率', en: 'Bias ratio', main: false, params: [6, 12, 24] },
  { name: 'BRAR', zh: '情緒指標', en: 'Buying and selling sentiment', main: false, params: [26] },
  { name: 'CR', zh: '能量指標', en: 'Energy indicator', main: false, params: [26, 10, 20, 40, 60] },
  { name: 'DMA', zh: '平均線差', en: 'Difference of moving averages', main: false, params: [10, 50, 10] },
  { name: 'EMV', zh: '簡易波動指標', en: 'Ease of movement', main: false, params: [14, 9] },
  { name: 'PSY', zh: '心理線', en: 'Psychological line', main: false, params: [12, 6] },
  { name: 'PVT', zh: '價量趨勢', en: 'Price volume trend', main: false, params: [] },
  { name: 'TRIX', zh: '三重指數平滑平均', en: 'Triple exponential average', main: false, params: [12, 9] },
  { name: 'VR', zh: '成交量比率', en: 'Volume ratio', main: false, params: [26, 6] },
]

export const defaultPreferences = {
  indicators: ['MA', 'VOL'], parameters: {} as Record<string, number[]>, timezone: '',
  candleType: 'candle_solid', grid: true, crosshair: true, lastPrice: true,
  upColor: '#26a69a', downColor: '#ef5350', scale: 'normal',
}

export function validTimezone(value: unknown): value is string {
  if (typeof value !== 'string' || !value) return false
  try { new Intl.DateTimeFormat('en', { timeZone: value }); return true } catch { return false }
}

export function validParameters(name: string, values: unknown): values is number[] {
  const item = indicatorCatalog.find(item => item.name === name)
  if (!item || !Array.isArray(values) || values.length !== item.params.length
    || !values.every(n => typeof n === 'number' && Number.isFinite(n) && n > 0 && n <= 500 && (name === 'SAR' || name === 'BOLL' || Number.isInteger(n)))) return false
  if (['MACD', 'AO', 'DMA'].includes(name) && values[0] >= values[1]) return false
  if (name === 'SAR' && (values[0] > values[2] || values[1] > values[2])) return false
  if (name === 'SMA' && values[1] > values[0]) return false
  if (name === 'BOLL' && !Number.isInteger(values[0])) return false
  return true
}

export function normalizePreferences(raw: unknown) {
  const value = raw && typeof raw === 'object' ? raw as Record<string, unknown> : {}
  const result = { ...defaultPreferences, indicators: [...defaultPreferences.indicators], parameters: {} as Record<string, number[]> }
  if (Array.isArray(value.indicators)) result.indicators = [...new Set(value.indicators.filter((name): name is string => typeof name === 'string' && indicatorCatalog.some(item => item.name === name)))]
  if (value.parameters && typeof value.parameters === 'object') {
    for (const [name, params] of Object.entries(value.parameters)) if (validParameters(name, params)) result.parameters[name] = [...params]
  }
  if (validTimezone(value.timezone)) result.timezone = value.timezone
  for (const key of ['grid', 'crosshair', 'lastPrice'] as const) if (typeof value[key] === 'boolean') result[key] = value[key]
  for (const key of ['upColor', 'downColor'] as const) if (typeof value[key] === 'string' && /^#[a-f\d]{6}$/i.test(value[key])) result[key] = value[key]
  if (['candle_solid', 'candle_stroke', 'ohlc', 'area'].includes(String(value.candleType))) result.candleType = String(value.candleType)
  if (['normal', 'percentage', 'logarithm'].includes(String(value.scale))) result.scale = String(value.scale)
  return result
}
