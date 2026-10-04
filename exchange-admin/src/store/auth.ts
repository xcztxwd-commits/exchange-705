import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { clearAccess } from '@/utils/access'
import { readSession, validSession, createAdminSession, clearAdminSession, ADMIN_SESSION_KEY, type AdminSession, type AccessSession } from '@/utils/adminSession'

// Never import an old shared token. The server migration revokes it independently.
localStorage.removeItem('admin_token')
localStorage.removeItem('admin_user')
export const exchangeOpener = window.opener as Window | null
if (exchangeOpener || /\/control-exchange\/?$/.test(location.pathname)) clearAdminSession(sessionStorage)
// Keep the reference only for the isolated exchange component; other pages sever it.
if (!/\/control-exchange\/?$/.test(location.pathname)) window.opener = null

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AdminSession | null>(readSession(sessionStorage))
  const token = computed(() => session.value?.token || null)
  const user = computed(() => session.value?.user || null)
  const isControl = computed(() => session.value?.mode === 'control')
  const accessSession = computed(() => session.value?.accessSession)
  const loginSessionId = computed(() => session.value?.loginSessionId)
  const logout = () => { clearAccess(); session.value = null; clearAdminSession(sessionStorage) }
  const setAuth = (tk: string, info: any, access?: AccessSession) => {
    const next = createAdminSession(tk, info, access)
    if (!validSession(next)) { logout(); throw new Error('登录响应缺少有效租户或访问会话') }
    if (token.value !== tk) clearAccess()
    sessionStorage.setItem(ADMIN_SESSION_KEY, JSON.stringify(next))
    session.value = next
  }
  const load = () => {
    const next = readSession(sessionStorage)
    if (next?.token !== token.value) clearAccess()
    session.value = next
  }
  const ensureValid = () => {
    if (!validSession(session.value)) { logout(); return false }
    return true
  }
  return { token, user, isControl, accessSession, loginSessionId, setAuth, load, ensureValid, logout }
})
