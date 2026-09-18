import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('admin_token'))
  const user = ref<any>(null)

  const setAuth = (tk: string, info: any) => {
    console.log('[AuthStore] 设置用户信息:', info)
    token.value = tk
    user.value = info
    localStorage.setItem('admin_token', tk)
    localStorage.setItem('admin_user', JSON.stringify(info || {}))
    console.log('[AuthStore] 用户信息已保存，userType:', info?.userType)
  }

  const load = () => {
    const tk = localStorage.getItem('admin_token')
    const u = localStorage.getItem('admin_user')
    token.value = tk
    user.value = u ? JSON.parse(u) : null
  }

  const logout = () => {
    token.value = null
    user.value = null
    localStorage.removeItem('admin_token')
    localStorage.removeItem('admin_user')
  }

  return { token, user, setAuth, load, logout }
})







