import axios from 'axios'
import { useAuthStore } from '@/store/auth'

const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
})

instance.interceptors.request.use((config) => {
  try {
    const auth = useAuthStore()
    if (!auth.token) auth.load()
    if (config.url === '/transfer/submit' && config.data) {
      const key = 'pending-transfer:' + JSON.stringify([auth.user?.id, config.data.fromAccount, config.data.toAccount, config.data.amount])
      const requestId = sessionStorage.getItem(key) || Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('')
      sessionStorage.setItem(key, requestId)
      config.data.requestId = requestId
      ;(config as any).transferRetryKey = key
    }
    if (auth.token) {
      config.headers = config.headers || {}
      config.headers.Authorization = `Bearer ${auth.token}`
    }
  } catch (e) {
    // ignore
  }
  return config
})

instance.interceptors.response.use(
  (res) => {
    const key = (res.config as any).transferRetryKey
    if (key) sessionStorage.removeItem(key)
    return res.data
  },
  (err) => {
    const key = err.config?.transferRetryKey
    if (key && err.response?.status >= 400 && err.response?.status < 500) sessionStorage.removeItem(key)
    // 检测token失效（单设备登录：其他设备登录导致当前设备token失效）
    if (err?.response?.status === 401 || 
        err?.response?.data?.code === 'TOKEN_INVALID' ||
        (err?.response?.data?.message && (
          err.response.data.message.includes('其他设备登录') ||
          err.response.data.message.includes('账号已在其他设备登录')
        ))) {
      // 自动退出登录
      const auth = useAuthStore()
      auth.logout()
      // 跳转到登录页（使用hash路由）
      const currentPath = window.location.hash.replace('#', '')
      if (currentPath !== '/login' && currentPath !== '/register' && currentPath !== '/forgot-password') {
        // 使用setTimeout确保在下一个事件循环中执行，避免在请求拦截器中直接跳转
        setTimeout(() => {
          window.location.hash = '/login'
        }, 100)
      }
      return Promise.reject(new Error(err?.response?.data?.message || '登录已失效，请重新登录'))
    }
    
    const msg =
      err?.response?.data?.message ||
      err?.response?.data?.error ||
      err?.message ||
      '请求失败'
    return Promise.reject(new Error(msg))
  }
)

export default instance





