/**
 * K线工具函数
 * 用于转换前端使用的 interval 字符串到后端需要的 kline_type 整数
 */

/**
 * 将前端的 interval 字符串转换为后端的 kline_type 整数
 * 
 * 根据 Alltick API 文档，kline_type 映射规则：
 * 1 = 1分钟K
 * 2 = 5分钟K
 * 3 = 15分钟K
 * 4 = 30分钟K
 * 5 = 小时K
 * 6 = 2小时K（股票不支持）
 * 7 = 4小时K（股票不支持）
 * 8 = 日K
 * 9 = 周K
 * 10 = 月K
 * 
 * @param interval K线周期字符串（如：'1m', '5m', '15m', '30m', '1h', '2h', '4h', '1d', '1w', '1M'）
 * @param isStock 是否为股票（美股、港股、A股），如果是股票，2h和4h会降级为1h
 * @returns kline_type 整数（1-10）
 */
export function convertIntervalToKlineType(interval: string, isStock: boolean = false): number {
  const normalizedInterval = interval.toLowerCase().trim()
  
  switch (normalizedInterval) {
    case '1m':
    case '1min':
      return 1
    case '5m':
    case '5min':
      return 2
    case '15m':
    case '15min':
      return 3
    case '30m':
    case '30min':
      return 4
    case '1h':
    case '60m':
    case '60min':
    case '1hour':
      return 5
    case '2h':
    case '2hour':
      // 股票不支持2小时K，降级为1小时K
      return isStock ? 5 : 6
    case '4h':
    case '4hour':
      // 股票不支持4小时K，降级为1小时K
      return isStock ? 5 : 7
    case '1d':
    case '1day':
      return 8
    case '1w':
    case '1week':
      return 9
    case '1M':
    case '1month':
    case '1mo':
      // 月K（注意：'1m' 是1分钟，不是月K）
      return 10
    default:
      // 默认返回1分钟K
      console.warn(`[Kline Utils] Unknown interval: ${interval}, defaulting to 1m (kline_type=1)`)
      return 1
  }
}

/**
 * 将 kline_type 整数转换为前端的 interval 字符串
 * 
 * @param klineType kline_type 整数（1-10）
 * @returns interval 字符串（如：'1m', '5m' 等）
 */
export function convertKlineTypeToInterval(klineType: number): string {
  switch (klineType) {
    case 1:
      return '1m'
    case 2:
      return '5m'
    case 3:
      return '15m'
    case 4:
      return '30m'
    case 5:
      return '1h'
    case 6:
      return '2h'
    case 7:
      return '4h'
    case 8:
      return '1d'
    case 9:
      return '1w'
    case 10:
      return '1M'
    default:
      console.warn(`[Kline Utils] Unknown kline_type: ${klineType}, defaulting to 1m`)
      return '1m'
  }
}

/**
 * 检查某个 interval 是否被股票支持
 * 
 * @param interval K线周期字符串
 * @returns 如果为股票不支持的周期，返回降级后的 interval
 */
export function getStockCompatibleInterval(interval: string): string {
  const normalizedInterval = interval.toLowerCase().trim()
  
  // 股票不支持2小时K和4小时K，降级为1小时K
  if (normalizedInterval === '2h' || normalizedInterval === '2hour') {
    return '1h'
  }
  if (normalizedInterval === '4h' || normalizedInterval === '4hour') {
    return '1h'
  }
  
  return interval
}

