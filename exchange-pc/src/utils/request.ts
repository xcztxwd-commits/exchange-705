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
  (res) => res.data,
  (err) => {
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
      return Promise.reject(new Error('账号已在其他设备登录，请重新登录'))
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





