/**
 * 获取图片完整 URL
 * 使用生产环境的 API 域名
 */
export function getImageUrl(url: string | null | undefined): string {
  if (!url) return ''
  
  // 如果是完整 URL（http://或https://开头），直接返回
  if (url.startsWith('http://') || url.startsWith('https://')) {
    return url
  }
  
  // 获取 API 基础 URL（生产环境域名）
  // 后台管理系统使用 api.748zn.com 作为图片 API 域名
  // 生产环境强制使用 api.748zn.com，开发环境可以通过环境变量覆盖
  const isProduction = import.meta.env.PROD
  // 生产环境强制使用 api.748zn.com/api，除非明确设置了其他值
  const apiBaseUrl = isProduction 
    ? (import.meta.env.VITE_API_BASE_URL || 'https://api.748zn.com/api')
    : (import.meta.env.VITE_API_BASE_URL || '')
  
  // 检查 apiBaseUrl 是否已经包含 /api（在末尾）
  // 例如：https://api.748zn.com/api
  const hasApiInBase = apiBaseUrl.endsWith('/api') || apiBaseUrl.match(/\/api\/?$/)
  
  // 标准化图片路径：统一转换为 /uploads/images/xxx 格式（不带 /api 前缀）
  let imagePath = ''
  
  if (url.startsWith('/')) {
    // 如果路径以 /api/uploads/images/ 开头，去掉 /api 前缀
    if (url.startsWith('/api/uploads/images/')) {
      imagePath = url.replace('/api/uploads/images/', '/uploads/images/')
    }
    // 如果路径以 /uploads/images/ 开头，直接使用
    else if (url.startsWith('/uploads/images/')) {
      imagePath = url
    }
    // 其他路径直接使用
    else {
      imagePath = url
    }
  } else {
    // 如果不是以 / 开头，添加 /uploads/images/ 前缀
    imagePath = `/uploads/images/${url}`
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
    return `${base}${imagePath}`
  }
  
  // 如果没有 API 基础 URL，返回相对路径（开发环境）
  return imagePath.startsWith('/market/icons/') ? `/api${imagePath}` : imagePath
}

