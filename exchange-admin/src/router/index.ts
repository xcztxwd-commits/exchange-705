import { createRouter, createWebHistory } from 'vue-router'
import Layout from '@/views/Layout.vue'
import Dashboard from '@/views/Dashboard.vue'
import Users from '@/views/Users.vue'
import Symbols from '@/views/Symbols.vue'
import Durations from '@/views/Durations.vue'
import Orders from '@/views/Orders.vue'
import DepositSettings from '@/views/DepositSettings.vue'
import DepositReview from '@/views/DepositReview.vue'
import DepositOrders from '@/views/DepositOrders.vue'
import WithdrawReview from '@/views/WithdrawReview.vue'
import LoanSettings from '@/views/LoanSettings.vue'
import LoanReview from '@/views/LoanReview.vue'
import LoanPersonalInfoReview from '@/views/LoanPersonalInfoReview.vue'
import KycReview from '@/views/KycReview.vue'
import FinancialProducts from '@/views/FinancialProducts.vue'
import FinancialOrders from '@/views/FinancialOrders.vue'
import AnnouncementManagement from '@/views/AnnouncementManagement.vue'
import Roles from '@/views/Roles.vue'
import AgentManagement from '@/views/AgentManagement.vue'
import AdminList from '@/views/AdminList.vue'
import Settings from '@/views/Settings.vue'
import OperationLog from '@/views/OperationLog.vue'
import Statistics from '@/views/Statistics.vue'
import AiControl from '@/views/AiControl.vue'
import Login from '@/views/Login.vue'
import { useAuthStore } from '@/store/auth'
import { access, loadAccess, canRoute } from '@/utils/access'

const legacyRoute = location.hash.startsWith('#/') ? new URL(location.hash.slice(1), location.origin) : null
if (location.hash) {
  const base = import.meta.env.BASE_URL.replace(/\/$/, '')
  history.replaceState(history.state, '', legacyRoute ? `${base}${legacyRoute.pathname}${legacyRoute.search}` : `${location.pathname}${location.search}`)
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', component: Login },
    { path: '/forbidden', component: () => import('@/views/Forbidden.vue') },
    {
      path: '/',
      component: Layout,
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', component: Dashboard },
        { path: 'users', component: Users },
        { path: 'symbols', component: Symbols },
        { path: 'ai-control', component: AiControl },
        { path: 'durations', component: Durations },
        { path: 'orders', component: Orders },
        { path: 'deposit-settings', component: DepositSettings },
        { path: 'deposit-review', component: DepositReview },
        { path: 'deposit-orders', component: DepositOrders },
        { path: 'withdraw-review', component: WithdrawReview },
        { path: 'loan-settings', component: LoanSettings },
        { path: 'loan-review', component: LoanReview },
        { path: 'loan-personal-info-review', component: LoanPersonalInfoReview },
        { path: 'kyc-review', component: KycReview },
        { path: 'financial-products', component: FinancialProducts },
        { path: 'financial-orders', component: FinancialOrders },
        { path: 'announcement', component: AnnouncementManagement },
        { path: 'roles', component: Roles },
        { path: 'agents', component: AgentManagement },
        { path: 'agents/:id/performance', component: () => import('@/views/AgentPerformance.vue') },
        { path: 'admin-list', component: AdminList },
        { path: 'website-security', component: () => import('@/views/WebsiteSecurity.vue') },
        { path: 'settings', component: Settings },
        { path: 'support', component: () => import('@/views/SupportDesk.vue') },
        { path: 'support-settings', component: () => import('@/views/SupportSettings.vue') },
        { path: 'inbox', component: () => import('@/views/InboxManagement.vue') },
        { path: 'operation-log', component: OperationLog },
        { path: 'statistics', component: Statistics },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.token || !auth.user) auth.load()
  if (!auth.token) return to.path === '/login' ? true : '/login'
  try { await loadAccess(true) } catch { return to.path === '/forbidden' ? true : '/forbidden' }
  const first = access.menus[0]?.path || '/forbidden'
  if (to.path === '/login' || to.path === '/') return first
  if (to.path === '/forbidden') return true
  if (!canRoute(to.path)) return first === to.path ? '/forbidden' : first
  return true
})

export default router
