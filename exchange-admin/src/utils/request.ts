import axios from 'axios'
import { useAuthStore } from '@/store/auth'

const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
})

instance.interceptors.request.use((config) => {
  try {
    const auth = useAuthStore()
    if (auth.token) {
      config.headers = config.headers || {}
      config.headers.Authorization = `Bearer ${auth.token}`
    }
  } catch (e) {
    // Pinia 未初始化时忽略
  }
  return config
})

instance.interceptors.response.use(
  (res) => res.data,
  (err) => {
    // 简单错误提示
    if (err.response?.data?.message) {
      return Promise.reject(new Error(err.response.data.message))
    }
    return Promise.reject(err)
  }
)

export default instance

