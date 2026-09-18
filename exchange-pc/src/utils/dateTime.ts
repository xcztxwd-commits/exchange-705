import request from '@/utils/request'

// 全局存储获取到的系统时区
let globalSystemTimezone = 'Europe/London'

// 尝试从后台获取系统时区配置
async function initSystemTimezone() {
  try {
    const res: any = await request.get('/user/system/timezone')
    if (res && res.timezone) {
      globalSystemTimezone = res.timezone
      console.log('[dateTime] Fetched timezone config:', globalSystemTimezone)
    }
  } catch (e) {
    console.error('[dateTime] Error fetching timezone config, using default:', e)
  }
}

// 立即触发初始化
initSystemTimezone()

/**
 * 日期时间格式化工具
 * 注意：后端可能返回服务器本地时间（北京时间 UTC+8），而不是UTC时间
 * 前端需要将其转换为UTC，然后转换为系统配置的时区（自动处理夏令时）显示
 */

/**
 * 格式化日期为 YYYY-MM-DD 格式（英国时区）
 * @param dateTime 日期时间字符串或 Date 对象
 * @returns 格式化的日期字符串，如 "2023-12-25"
 */
export function formatDate(dateTime: string | Date | null | undefined): string {
  if (!dateTime) return ''
  
  try {
    let date: Date
    
    if (typeof dateTime === 'string') {
      // 后端可能返回服务器本地时间（北京时间 UTC+8），而不是UTC时间
      let dateStr = dateTime.trim()
      
      // 检查是否已经有时区标识
      if (dateStr.endsWith('Z') || dateStr.match(/[+-]\d{2}:?\d{2}$/)) {
        // 已经有时区信息，直接解析
        date = new Date(dateStr)
      } else {
        // 没有时区信息，假设后端返回的是北京时间（UTC+8）
        let normalizedStr = dateStr
        if (dateStr.includes('T')) {
          normalizedStr = dateStr.replace(/\.\d+$/, '')
        } else if (dateStr.match(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?$/)) {
          normalizedStr = dateStr.replace(/\.\d+$/, '').replace(' ', 'T')
        }
        // 明确指定为北京时间（UTC+8）
        date = new Date(normalizedStr + '+08:00')
        if (isNaN(date.getTime())) {
          // 如果解析失败，尝试其他方法
          const tempDate = new Date(normalizedStr)
          if (!isNaN(tempDate.getTime())) {
            date = new Date(tempDate.getTime() - 8 * 60 * 60 * 1000)
          } else {
            date = tempDate
          }
        }
      }
    } else {
      date = dateTime
    }
    
    // 使用 Intl.DateTimeFormat 格式化系统配置时区的日期
    const formatter = new Intl.DateTimeFormat('en-GB', {
      timeZone: globalSystemTimezone, // 使用动态获取的时区
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    })
    
    // 格式化为 YYYY-MM-DD
    const parts = formatter.formatToParts(date)
    const year = parts.find(p => p.type === 'year')?.value || ''
    const month = parts.find(p => p.type === 'month')?.value || ''
    const day = parts.find(p => p.type === 'day')?.value || ''
    
    return `${year}-${month}-${day}`
  } catch (e) {
    console.error('formatDate error:', e)
    return typeof dateTime === 'string' ? dateTime : ''
  }
}

/**
 * 格式化日期时间为 YYYY-MM-DD HH:mm:ss 格式（英国时区）
 * @param dateTime 日期时间字符串或 Date 对象
 * @returns 格式化的日期时间字符串，如 "2023-12-25 14:30:00"
 */
export function formatDateTime(dateTime: string | Date | null | undefined): string {
  if (!dateTime) return ''
  
  try {
    let date: Date
    
    if (typeof dateTime === 'string') {
      // 后端可能返回服务器本地时间（北京时间 UTC+8），而不是UTC时间
      // 我们需要将其作为北京时间解析，然后转换为UTC，再转换为英国时区显示
      let dateStr = dateTime.trim()
      const originalStr = dateStr
      
      // 检查是否已经有时区标识
      if (dateStr.endsWith('Z') || dateStr.match(/[+-]\d{2}:?\d{2}$/)) {
        // 已经有时区信息，直接解析
        date = new Date(dateStr)
      } else {
        // 没有时区信息，假设后端返回的是北京时间（UTC+8）
        // 需要将其转换为UTC时间
        let normalizedStr = dateStr
        
        // 标准化格式
        if (dateStr.includes('T')) {
          normalizedStr = dateStr.replace(/\.\d+$/, '') // 移除毫秒
        } else if (dateStr.match(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?$/)) {
          normalizedStr = dateStr.replace(/\.\d+$/, '').replace(' ', 'T')
        }
        
        // 解析为北京时间（UTC+8），然后减去8小时得到UTC时间
        // 方法：将时间字符串解析为本地时间，然后减去8小时偏移
        const beijingDate = new Date(normalizedStr + '+08:00') // 明确指定为北京时间
        if (!isNaN(beijingDate.getTime())) {
          date = beijingDate
        } else {
          // 如果解析失败，尝试其他方法
          // 直接解析，然后手动减去8小时
          const tempDate = new Date(normalizedStr)
          if (!isNaN(tempDate.getTime())) {
            // 减去8小时（8 * 60 * 60 * 1000 毫秒）
            date = new Date(tempDate.getTime() - 8 * 60 * 60 * 1000)
          } else {
            console.warn('[formatDateTime] Invalid date string:', originalStr)
            return originalStr
          }
        }
      }
      
      // 验证日期是否有效
      if (isNaN(date.getTime())) {
        console.warn('[formatDateTime] Invalid date string:', originalStr)
        return originalStr
      }
      
      // 调试信息（开发环境）
      if (process.env.NODE_ENV === 'development') {
        const utcTime = date.toISOString()
        const ukFormatter = new Intl.DateTimeFormat('en-GB', {
          timeZone: 'Europe/London',
          year: 'numeric',
          month: '2-digit',
          day: '2-digit',
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit',
          hour12: false
        })
        const ukTime = ukFormatter.format(date)
        console.log('[formatDateTime]', {
          input: originalStr,
          parsed: date.toISOString(),
          utc: utcTime,
          uk: ukTime,
          beijing: new Date(date.getTime() + 8 * 60 * 60 * 1000).toISOString()
        })
      }
    } else {
      date = dateTime
    }
    
    // 使用 Intl.DateTimeFormat 格式化系统配置时区的日期时间
    const formatter = new Intl.DateTimeFormat('en-GB', {
      timeZone: globalSystemTimezone, // 使用动态获取的时区
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    })
    
    // 格式化为 YYYY-MM-DD HH:mm:ss
    const parts = formatter.formatToParts(date)
    const year = parts.find(p => p.type === 'year')?.value || ''
    const month = parts.find(p => p.type === 'month')?.value || ''
    const day = parts.find(p => p.type === 'day')?.value || ''
    const hour = parts.find(p => p.type === 'hour')?.value || ''
    const minute = parts.find(p => p.type === 'minute')?.value || ''
    const second = parts.find(p => p.type === 'second')?.value || ''
    
    return `${year}-${month}-${day} ${hour}:${minute}:${second}`
  } catch (e) {
    console.error('formatDateTime error:', e, 'input:', dateTime)
    return typeof dateTime === 'string' ? dateTime : ''
  }
}

/**
 * 格式化时间为 HH:mm:ss 格式（英国时区）
 * @param dateTime 日期时间字符串或 Date 对象
 * @returns 格式化的时间字符串，如 "14:30:00"
 */
export function formatTime(dateTime: string | Date | null | undefined): string {
  if (!dateTime) return ''
  
  try {
    let date: Date
    
    if (typeof dateTime === 'string') {
      // 后端可能返回服务器本地时间（北京时间 UTC+8），而不是UTC时间
      let dateStr = dateTime.trim()
      
      // 检查是否已经有时区标识
      if (dateStr.endsWith('Z') || dateStr.match(/[+-]\d{2}:?\d{2}$/)) {
        // 已经有时区信息，直接解析
        date = new Date(dateStr)
      } else {
        // 没有时区信息，假设后端返回的是北京时间（UTC+8）
        let normalizedStr = dateStr
        if (dateStr.includes('T')) {
          normalizedStr = dateStr.replace(/\.\d+$/, '')
        } else if (dateStr.match(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?$/)) {
          normalizedStr = dateStr.replace(/\.\d+$/, '').replace(' ', 'T')
        }
        // 明确指定为北京时间（UTC+8）
        date = new Date(normalizedStr + '+08:00')
        if (isNaN(date.getTime())) {
          // 如果解析失败，尝试其他方法
          const tempDate = new Date(normalizedStr)
          if (!isNaN(tempDate.getTime())) {
            date = new Date(tempDate.getTime() - 8 * 60 * 60 * 1000)
          } else {
            date = tempDate
          }
        }
      }
    } else {
      date = dateTime
    }
    
    // 使用 Intl.DateTimeFormat 格式化系统配置时区的时间
    const formatter = new Intl.DateTimeFormat('en-GB', {
      timeZone: globalSystemTimezone, // 使用动态获取的时区
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false
    })
    
    // 格式化为 HH:mm:ss
    const parts = formatter.formatToParts(date)
    const hour = parts.find(p => p.type === 'hour')?.value || ''
    const minute = parts.find(p => p.type === 'minute')?.value || ''
    const second = parts.find(p => p.type === 'second')?.value || ''
    
    return `${hour}:${minute}:${second}`
  } catch (e) {
    console.error('formatTime error:', e)
    return ''
  }
}

/**
 * 格式化日期时间为本地化格式（英国时区，除非options中指定了其他时区）
 * @param dateTime 日期时间字符串或 Date 对象
 * @param locale 本地化语言代码，默认为 'en-GB'
 * @param options Intl.DateTimeFormatOptions 选项
 * @returns 格式化的日期时间字符串
 */
export function formatDateTimeLocalized(
  dateTime: string | Date | null | undefined,
  locale: string = 'en-GB',
  options?: Intl.DateTimeFormatOptions
): string {
  if (!dateTime) return ''
  
  try {
    let date: Date
    
    if (typeof dateTime === 'string') {
      // 如果字符串没有时区信息（没有Z或+/-），假设它是UTC时间
      let dateStr = dateTime.trim()
      // 检查是否已经有时区标识
      if (!dateStr.endsWith('Z') && !dateStr.match(/[+-]\d{2}:?\d{2}$/)) {
        // 没有时区信息，假设是UTC时间，添加Z后缀
        if (dateStr.includes('T')) {
          dateStr = dateStr.replace(/T(\d{2}):(\d{2}):(\d{2})(\.\d+)?$/, 'T$1:$2:$3Z')
        } else if (dateStr.match(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/)) {
          dateStr = dateStr.replace(' ', 'T') + 'Z'
        }
      }
      date = new Date(dateStr)
    } else {
      date = dateTime
    }
    
    const defaultOptions: Intl.DateTimeFormatOptions = {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
      timeZone: globalSystemTimezone, // 默认使用系统动态时区
      ...options // 如果options中指定了timeZone，会覆盖默认值
    }
    
    const formatter = new Intl.DateTimeFormat(locale, defaultOptions)
    return formatter.format(date)
  } catch (e) {
    console.error('formatDateTimeLocalized error:', e)
    return typeof dateTime === 'string' ? dateTime : ''
  }
}

