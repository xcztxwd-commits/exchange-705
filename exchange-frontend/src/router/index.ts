import { createRouter, createWebHashHistory } from 'vue-router'
import Login from '@/views/Login.vue'
import Register from '@/views/Register.vue'
import ForgotPassword from '@/views/ForgotPassword.vue'
import LanguageSelect from '@/views/LanguageSelect.vue'
import Home from '@/views/Home.vue'
import Trade from '@/views/Trade.vue'
import Orders from '@/views/Orders.vue'
import Profile from '@/views/Profile.vue'
import Assets from '@/views/Assets.vue'
import Deposit from '@/views/Deposit.vue'
import DepositRecords from '@/views/DepositRecords.vue'
import Wallet from '@/views/Wallet.vue'
import BindBankCard from '@/views/BindBankCard.vue'
import BindDigitalCurrency from '@/views/BindDigitalCurrency.vue'
import Verification from '@/views/Verification.vue'
import Transfer from '@/views/Transfer.vue'
import ChangePassword from '@/views/ChangePassword.vue'
import CustomerService from '@/views/CustomerService.vue'
import Complaint from '@/views/Complaint.vue'
import Announcements from '@/views/Announcements.vue'
import Withdraw from '@/views/Withdraw.vue'
import CreditLoan from '@/views/CreditLoan.vue'
import LoanApplyInfo from '@/views/LoanApplyInfo.vue'
import LoanContract from '@/views/LoanContract.vue'
import LoanSign from '@/views/LoanSign.vue'
import LoanRecords from '@/views/LoanRecords.vue'
import FinancialManagement from '@/views/FinancialManagement.vue'
import FinancialPurchase from '@/views/FinancialPurchase.vue'
import FinancialOrders from '@/views/FinancialOrders.vue'
import FinancialYieldList from '@/views/FinancialYieldList.vue'
import Search from '@/views/Search.vue'
import Invite from '@/views/Invite.vue'
import { useAuthStore } from '@/store/auth'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    { path: '/home', component: Home },
    { path: '/trade', component: Trade },
    { path: '/orders', component: Orders },
    { path: '/profile', component: Profile },
    { path: '/assets', component: Assets },
    { path: '/deposit', component: Deposit },
    { path: '/deposit/records', component: DepositRecords },
    { path: '/wallet', component: Wallet },
    { path: '/wallet/bind-bank-card', component: BindBankCard },
    { path: '/wallet/bind-digital-currency', component: BindDigitalCurrency },
    { path: '/verification', component: Verification },
    { path: '/transfer', component: Transfer },
    { path: '/change-password', component: ChangePassword },
    { path: '/customer-service', component: CustomerService },
    { path: '/complaint', component: Complaint },
    { path: '/announcements', component: Announcements },
    { path: '/withdraw', component: Withdraw },
    { path: '/credit-loan', component: CreditLoan },
    { path: '/loan/personal-info', component: () => import('@/views/LoanPersonalInfo.vue') },
    { path: '/loan/apply-info', component: LoanApplyInfo },
    { path: '/loan/contract', component: LoanContract },
    { path: '/loan/sign', component: LoanSign },
    { path: '/loan/records', component: LoanRecords },
    { path: '/financial-management', component: FinancialManagement },
    { path: '/financial/purchase', component: FinancialPurchase },
    { path: '/financial/orders', component: FinancialOrders },
    { path: '/financial/yield-list', component: FinancialYieldList },
    { path: '/search', component: Search },
    { path: '/invite', component: Invite },
    { path: '/login', component: Login },
    { path: '/register', component: Register },
    { path: '/forgot-password', component: ForgotPassword },
    { path: '/language', component: LanguageSelect },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

router.beforeEach((to, _from, next) => {
  const auth = useAuthStore()
  if (!auth.token) auth.load()
  const publicPages = ['/login', '/register', '/forgot-password', '/language', '/home']
  // 如果访问首页且未登录，允许访问（首页会显示公告）
  if (to.path === '/home' && !auth.token) {
    next()
    return
  }
  // 如果访问其他非公开页面且未登录，跳转到登录页
  if (!publicPages.includes(to.path) && !auth.token) {
    next('/login')
    return
  }
  // 如果已登录且访问登录页，跳转到语言选择页
  if (to.path === '/login' && auth.token) {
    next('/language')
    return
  }
  next()
})

export default router

