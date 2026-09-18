import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('token'))
  const user = ref<any>(null)

  const setAuth = (tk: string, info: any) => {
    token.value = tk
    user.value = info
    localStorage.setItem('token', tk)
    localStorage.setItem('user', JSON.stringify(info || {}))
  }

  const load = () => {
    const tk = localStorage.getItem('token')
    const u = localStorage.getItem('user')
    token.value = tk
    user.value = u ? JSON.parse(u) : null
  }

  const logout = () => {
    token.value = null
    user.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('user')
  }

  return { token, user, setAuth, load, logout }
})







