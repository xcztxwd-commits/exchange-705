import axios from 'axios'
import { useAuthStore } from '@/store/auth'

const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
})

// Legacy callers need AxiosResponse; share authentication without changing their response shape.
export const rawRequest = axios.create({ timeout: 10000 })

for (const client of [instance, rawRequest]) {
client.interceptors.request.use((config) => {
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

client.interceptors.response.use(
  (res) => client === instance ? res.data : res,
  (err) => {
    if (err.response?.status === 401) {
      useAuthStore().logout()
      window.location.hash = '/login'
      return Promise.reject(new Error(err.response?.data?.message || '登录已失效，请重新登录'))
    }
    // 简单错误提示
    if (err.response?.data?.message) {
      return Promise.reject(new Error(err.response.data.message))
    }
    return Promise.reject(err)
  }
)
}

export default instance

