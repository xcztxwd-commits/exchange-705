/**
 * 获取音频文件完整 URL
 * 使用生产环境的 API 域名
 */
export function getAudioUrl(url: string | null | undefined): string {
  if (!url) return ''
  
  // 如果是完整 URL（http://或https://开头），直接返回
  if (url.startsWith('http://') || url.startsWith('https://')) {
    return url
  }
  
  // 获取 API 基础 URL（生产环境域名）
  // 生产环境使用 api1.m.ydgggd.com 作为 API 域名
  const isProduction = import.meta.env.PROD
  const apiBaseUrl = isProduction 
    ? (import.meta.env.VITE_API_BASE_URL || 'https://api1.m.ydgggd.com')
    : (import.meta.env.VITE_API_BASE_URL || '')
  
  // 检查 apiBaseUrl 是否已经包含 /api（在末尾）
  const hasApiInBase = apiBaseUrl.endsWith('/api') || apiBaseUrl.match(/\/api\/?$/)
  
  // 标准化音频路径：统一转换为 /uploads/audio/xxx 格式（不带 /api 前缀）
  let audioPath = ''
  
  if (url.startsWith('/')) {
    // 如果路径以 /api/uploads/audio/ 开头，去掉 /api 前缀
    if (url.startsWith('/api/uploads/audio/')) {
      audioPath = url.replace('/api/uploads/audio/', '/uploads/audio/')
    }
    // 如果路径以 /uploads/audio/ 开头，直接使用
    else if (url.startsWith('/uploads/audio/')) {
      audioPath = url
    }
    // 其他路径直接使用
    else {
      audioPath = url
    }
  } else {
    // 如果不是以 / 开头，添加 /uploads/audio/ 前缀
    audioPath = `/uploads/audio/${url}`
  }
  
  // 如果有 API 基础 URL，拼接完整 URL
  if (apiBaseUrl) {
    // 确保 apiBaseUrl 不以 / 结尾
    let base = apiBaseUrl.endsWith('/') ? apiBaseUrl.slice(0, -1) : apiBaseUrl
    
    // 如果 baseUrl 不包含 /api，需要添加 /api
    if (!hasApiInBase) {
      // 确保 base 不以 / 结尾，然后添加 /api
      base = base.endsWith('/') ? base.slice(0, -1) : base
      base = `${base}/api`
    }
    
    // 拼接完整 URL
    return `${base}${audioPath}`
  }
  
  // 如果没有 API 基础 URL，返回相对路径（开发环境）
  return audioPath
}


