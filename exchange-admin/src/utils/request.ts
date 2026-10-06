import axios from 'axios'
import { useAuthStore } from '@/store/auth'
import { assertAdminRequestTarget } from './adminSession'

const instance = axios.create({ baseURL: import.meta.env.VITE_API_BASE_URL || '/api', timeout: 10000 })
export const rawRequest = axios.create({ timeout: 10000 })
const publicAuth = new Set(['/api/admin/auth/login', '/api/admin/auth/control-exchange', '/api/admin/auth/control-exchange-config'])

for (const client of [instance, rawRequest]) {
  client.interceptors.request.use((config) => {
    const target = new URL(client.getUri(config), location.origin)
    // Resolve the same URI Axios will send, including protocol-relative and absolute URLs.
    assertAdminRequestTarget(target, location.origin)
    const auth = useAuthStore()
    if (publicAuth.has(target.pathname)) {
      delete config.headers.Authorization
      return config
    }
    const control = auth.isControl
    if (!auth.ensureValid()) {
      window.location.replace(`${import.meta.env.BASE_URL}${control ? 'access-ended' : 'login'}`)
      throw new Error('登录或总控访问已失效，请重新进入')
    }
    config.headers.Authorization = `Bearer ${auth.token}`
    return config
  })
  client.interceptors.response.use(
    res => {
      const sent = res.config.headers?.Authorization
      if (sent && sent !== `Bearer ${useAuthStore().token}`) throw new Error('会话已变更，已丢弃旧响应')
      return client === instance ? res.data : res
    },
    err => {
      const auth = useAuthStore()
      if (err.response?.status === 401 && err.config?.headers?.Authorization === `Bearer ${auth.token}`) {
        const control = auth.isControl
        auth.logout()
        window.location.replace(`${import.meta.env.BASE_URL}${control ? 'access-ended' : 'login'}`)
      }
      if (err.response?.data?.message) return Promise.reject(Object.assign(new Error(err.response.data.message), { response: err.response }))
      return Promise.reject(err)
    }
  )
}
export default instance
