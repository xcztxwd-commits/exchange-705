import { createRouter, createWebHashHistory } from 'vue-router'
import LanguageSelect from '@/views/LanguageSelect.vue'
import DesktopTrade from '@/views/DesktopTrade.vue'
import { useAuthStore } from '@/store/auth'

const router = createRouter({
  history: createWebHashHistory(),
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

export default router

