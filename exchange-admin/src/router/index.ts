import { createRouter, createWebHashHistory } from 'vue-router'
import Layout from '@/views/Layout.vue'
import Dashboard from '@/views/Dashboard.vue'
import Users from '@/views/Users.vue'
import Symbols from '@/views/Symbols.vue'
import Durations from '@/views/Durations.vue'
import Orders from '@/views/Orders.vue'
import DepositSettings from '@/views/DepositSettings.vue'
import DepositReview from '@/views/DepositReview.vue'
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

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: Login },
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
        { path: 'settings', component: Settings },
        { path: 'operation-log', component: OperationLog },
        { path: 'statistics', component: Statistics },
      ],
    },
  ],
})

router.beforeEach((to, _from, next) => {
  const auth = useAuthStore()
  if (!auth.token) auth.load()
  if (to.path !== '/login' && !auth.token) {
    next('/login')
    return
  }
  if (to.path === '/login' && auth.token) {
    next('/dashboard')
    return
  }
  next()
})

export default router

