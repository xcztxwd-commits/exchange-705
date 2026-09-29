import { createRouter, createWebHistory } from 'vue-router'
import LanguageSelect from '@/views/LanguageSelect.vue'
import DesktopTrade from '@/views/DesktopTrade.vue'
import { useAuthStore } from '@/store/auth'
import request from '@/utils/request'

const legacyRoute = location.hash.startsWith('#/') ? new URL(location.hash.slice(1), location.origin) : null
if (location.hash) {
  const base = import.meta.env.BASE_URL.replace(/\/$/, '')
  history.replaceState(history.state, '', legacyRoute ? `${base}${legacyRoute.pathname}${legacyRoute.search}` : `${location.pathname}${location.search}`)
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/demo', redirect: '/' },
    { path: '/', component: DesktopTrade },
    { path: '/trade', redirect: '/' }, // backward compatibility
    { path: '/login', redirect: '/?login=1' },
    { path: '/register', redirect: '/?register=1' },
    { path: '/forgot-password', redirect: '/?forgot=1' },
    { path: '/language', component: LanguageSelect },
    { path: '/customer-service', component: () => import('@/views/SupportPage.vue') },
    { path: '/inbox', component: () => import('@/views/Inbox.vue') },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

router.beforeEach(async (to) => {
  if (to.path !== '/withdraw') return true
  const auth = useAuthStore()
  if (!auth.token) return '/login'
  try { const status: any = await request.get('/kyc/status'); return status.simulationExempt === true || (status.kycStatus === 'VERIFIED' && status.latestRecord?.status === 'APPROVED') ? true : { path: '/verification', query: { reason: 'withdraw' } } }
  catch { return { path: '/verification', query: { reason: 'withdraw' } } }
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
