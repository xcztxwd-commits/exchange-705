import { createRouter, createWebHistory } from 'vue-router'
import LanguageSelect from '@/views/LanguageSelect.vue'
import DesktopTrade from '@/views/DesktopTrade.vue'
import { useAuthStore } from '@/store/auth'

const legacyRoute = location.hash.startsWith('#/') ? new URL(location.hash.slice(1), location.origin) : null
if (location.hash) {
  const base = import.meta.env.BASE_URL.replace(/\/$/, '')
  history.replaceState(history.state, '', legacyRoute ? `${base}${legacyRoute.pathname}${legacyRoute.search}` : `${location.pathname}${location.search}`)
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', component: DesktopTrade },
    { path: '/trade', redirect: '/' }, // backward compatibility
    { path: '/login', redirect: '/?login=1' },
    { path: '/register', redirect: '/?register=1' },
    { path: '/forgot-password', redirect: '/?forgot=1' },
    { path: '/language', component: LanguageSelect },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

router.beforeEach((to, _from, next) => {
  const auth = useAuthStore()
  if (!auth.token) auth.load()
  
  // 允许未登录访问的页面
  const publicPages = ['/login', '/register', '/forgot-password', '/language', '/']
  if (publicPages.includes(to.path) || to.path === '/trade') {
    next()
    return
  }
  
  // 其他需要登录
  if (!auth.token) {
    next('/?login=1')
    return
  }
  
  // 已登录不访问登录页
  if (to.path === '/login' && auth.token) {
    next('/')
    return
  }
  
  next()
})

router.afterEach((to, from, failure) => {
  if (!failure && from.matched.length && to.fullPath !== from.fullPath) {
    window.dispatchEvent(new Event('forex-route-change'))
  }
})

export default router
