import { ref, watch } from 'vue'
const key = 'exchange.control.session.v1'
type ControlSession = { token: string; user: any; expiresAt: number }
function read(): ControlSession | null {
  if (window.opener) { sessionStorage.removeItem(key); window.opener = null }
  try { const data = JSON.parse(sessionStorage.getItem(key) || 'null'); return data?.token && Number(data.expiresAt) > Date.now() ? data : null } catch { return null }
}
export const controlSession = ref<ControlSession | null>(read())
export function clearSession() { controlSession.value = null; sessionStorage.removeItem(key) }
watch(controlSession, (session, _, cleanup) => {
  if (!session) return
  const timer = setTimeout(() => { if (controlSession.value?.token === session.token) clearSession() }, Math.min(2147483647, Math.max(0, session.expiresAt - Date.now())))
  cleanup(() => clearTimeout(timer))
}, { immediate: true, flush: 'sync' })
async function responseFor(path: string, method = 'GET', body?: unknown, signal?: AbortSignal) {
  if (!path.startsWith('/control/') || path.includes('://') || path.includes('..') || path.includes('\\')) throw new Error('非法总控接口')
  const current = controlSession.value
  if (path !== '/control/auth/login' && (!current || current.expiresAt <= Date.now())) { clearSession(); throw new Error('总控登录已失效') }
  const response = await fetch('/api' + path, { method, signal: signal || AbortSignal.timeout(30000), credentials: 'omit', cache: 'no-store', redirect: 'error', headers: { 'Content-Type': path.startsWith('/control/table-preferences/') ? 'application/json;charset=UTF-8' : 'application/json', ...(current ? { Authorization: `Bearer ${current.token}` } : {}) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  if (response.status === 401 && controlSession.value?.token === current?.token) clearSession()
  if (path !== '/control/auth/login' && (current?.token !== controlSession.value?.token || current!.expiresAt <= Date.now())) throw new Error('总控会话已变更，已丢弃旧响应')
  if (!response.ok) { const result = await response.json().catch(() => ({})); throw new Error(result.message || `请求失败 (HTTP ${response.status})`) }
  return response
}
export async function api(path: string, method = 'GET', body?: unknown, signal?: AbortSignal) {
  const token = controlSession.value?.token
  const result = await (await responseFor(path, method, body, signal)).json()
  if (path !== '/control/auth/login' && token !== controlSession.value?.token) throw new Error('总控会话已变更，已丢弃旧响应')
  if (result.success === false) throw new Error(result.message || '请求失败')
  return result
}
export async function apiFile(path: string, signal?: AbortSignal) {
  const token = controlSession.value?.token
  const response = await responseFor(path, 'GET', undefined, signal)
  const blob = await response.blob()
  if (token !== controlSession.value?.token || !controlSession.value || controlSession.value.expiresAt <= Date.now()) throw new Error('总控会话已变更，已丢弃附件')
  return { blob, chainValid: response.headers.get('X-Evidence-Chain-Valid') }
}
export async function login(account: string, password: string, totp: string) {
  clearSession()
  const result = await api('/control/auth/login', 'POST', { account, password, totp })
  if (!result.token || !result.user || !Number.isFinite(result.expiresAt) || result.expiresAt <= Date.now()) throw new Error('总控登录响应无效')
  sessionStorage.setItem(key, JSON.stringify(result)); controlSession.value = result
}
export async function logout() { await api('/control/auth/logout', 'POST'); clearSession() }
export function dataRows(response: any): any[] { const data = response.data ?? response; return Array.isArray(data) ? data : data.content || data.items || [] }
