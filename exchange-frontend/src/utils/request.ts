import { accountMode, getAccountApiBase, realApiBase } from './accountMode'
import axios from 'axios'
import { trackAccountWrite, finishAccountWrite } from './accountRequests'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
})

instance.interceptors.request.use((config) => {
  const sharedIdentity = config.url?.startsWith('/auth/') || config.url?.startsWith('/user/changePassword') || config.url?.startsWith('/user/support/') || config.url === '/user/customer-service/link'
  const mode = sharedIdentity ? 'REAL' : accountMode()
  config.baseURL = sharedIdentity ? realApiBase() : getAccountApiBase()
  config.headers.set('X-Account-Mode', mode)
  try {
    const auth = useAuthStore()
    if (!auth.token) auth.load()
    if (config.url === '/transfer/submit' && config.data) {
      const key = 'pending-transfer:' + mode + ':' + JSON.stringify([auth.user?.id, config.data.fromAccount, config.data.toAccount, config.data.amount])
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
  trackAccountWrite(config)
  return config
})

instance.interceptors.response.use(
  (res) => {
    finishAccountWrite(res.config)
    const key = (res.config as any).transferRetryKey
    if (key) sessionStorage.removeItem(key)
    if (typeof res.data?.message === 'string') res.data.message = useLocaleStore().backendMessage(res.data.message, res.data?.success === false)
    return res.data
  },
  (err) => {
    finishAccountWrite(err.config)
    if (err.response?.status === 403 && err.response?.data?.errorCode === 'KYC_REQUIRED') {
      return Promise.reject(Object.assign(new Error(err.response.data.message || 'Identity verification required'), {
        errorCode: 'KYC_REQUIRED', kycStatus: err.response.data.kycStatus, status: 403,
      }))
    }

    if (['/auth/captcha', '/auth/register'].includes(err.config?.url || '')) {
      const failure = new Error(useLocaleStore().backendMessage(err.response?.data?.message || 'Unable to confirm the result. Please check before trying again.', true)) as Error & { status?: number; code?: string; retryAfter?: number }
      failure.status = err.response?.status
      failure.code = err.response?.data?.code
      failure.retryAfter = Number(err.response?.headers?.['retry-after'] || err.response?.data?.retryAfter || 0)
      return Promise.reject(failure)
    }
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
      const currentPath = window.location.pathname.replace(/^\/mobile(?=\/|$)/, '') || '/'
      if (currentPath !== '/login' && currentPath !== '/register' && currentPath !== '/forgot-password') {
        // 使用setTimeout确保在下一个事件循环中执行，避免在请求拦截器中直接跳转
        setTimeout(() => {
          window.location.replace(`${import.meta.env.BASE_URL}login`)
        }, 100)
      }
      return Promise.reject(new Error(useLocaleStore().backendMessage(err?.response?.data?.message || '登录已失效，请重新登录')))
    }
    
    // A missing write response does not prove that the operation failed.
    if (!err.response) {
      const readOnly = ['get', 'head', 'options'].includes(String(err.config?.method || 'get').toLowerCase()) || err.config?.url === '/withdraw/calculate'
      return Promise.reject(new Error(readOnly ? 'A network error occurred. Please try again later.' : 'Unable to confirm the result. Check the relevant history or status before submitting again.'))
    }
    // Unknown transport/server responses use English in every frontend locale.
    if (!err.response?.data?.message && !err.response?.data?.error && /^Request failed with status code [1-5]\d{2}$/.test(err.message || '')) {
      return Promise.reject(new Error('Unable to confirm the result. Check the relevant history or status before submitting again.' + ' (HTTP ' + err.response.status + ')'))
    }
    const msg =
      err?.response?.data?.message ||
      err?.response?.data?.error ||
      err?.message ||
      '请求失败'
    return Promise.reject(new Error(useLocaleStore().backendMessage(msg, true)))
  }
)

export default instance





