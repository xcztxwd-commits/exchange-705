<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AdvancedLayout from '@/advanced/components/AdvancedLayout.vue'
import EditionSwitch from '@/advanced/components/EditionSwitch.vue'
import AccountModeSwitch from '@/components/AccountModeSwitch.vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'
import { accountMode } from '@/utils/accountMode'
import { assetDisplayLine, assetHistoryPoints, type AssetHistory } from '@/utils/assetPixelWindow'
import { getSystemTimezone } from '@/utils/dateTime'
import { pendingAccountWrites } from '@/utils/accountRequests'
import depositIcon from '@/advanced/assets/home-explore/deposit.svg'
import withdrawIcon from '@/advanced/assets/home-explore/withdraw.svg'
import transferIcon from '@/advanced/assets/home-explore/transfer.svg'
import walletIcon from '@/advanced/assets/home-explore/wallet.svg'
import backIcon from '@/advanced/assets/home-explore/back.svg'

const router = useRouter(), auth = useAuthStore(), locale = useLocaleStore()
const text = (zh: string, en: string) => locale.text(zh, en)
const info = ref<any>(null), history = ref<AssetHistory | null>(null), error = ref(''), infoError = ref(''), loading = ref(false), visible = ref(true)
const controller = new AbortController(), owner = auth.token
let disposed = false, timer: ReturnType<typeof setInterval> | undefined
const active = () => !disposed && auth.token === owner
const money = (value: unknown) => value === null || value === undefined || !Number.isFinite(Number(value)) ? '—' : Number(value).toLocaleString(locale.locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const dates = (value: number) => new Intl.DateTimeFormat(locale.locale, { timeZone: history.value?.timezone || getSystemTimezone(), month: '2-digit', day: '2-digit' }).format(value)
const name = computed(() => info.value?.nickname || info.value?.email || auth.user?.nickname || auth.user?.email || text('用户', 'User'))
const uid = computed(() => info.value?.uid || info.value?.id || auth.user?.id || '—')
const shortcuts = computed(() => [
  { path: '/deposit', label: locale.t('deposit'), icon: depositIcon }, { path: '/withdraw', label: locale.t('withdraw'), icon: withdrawIcon },
  { path: '/transfer', label: locale.t('transfer'), icon: transferIcon }, { path: '/wallet', label: locale.t('wallet'), icon: walletIcon },
])
const groups = computed(() => [
  { title: text('账户管理', 'Account management'), entries: [
    { path: '/wallet', title: text('钱包 / 收款方式', 'Wallet / Payment methods') }, { path: '/transfer', title: locale.t('transfer') }, { path: '/verification', title: locale.t('verification') },
    { path: '/financial-management', title: locale.t('financialManagement') }, { path: '/credit-loan', title: locale.t('creditLoan') },
  ] },
  { title: text('安全与设置', 'Security & settings'), entries: [{ path: '/change-password', title: locale.t('changePassword') }, { path: '/language', title: locale.t('language') }] },
  { title: text('服务与支持', 'Service & support'), entries: [{ path: '/invite', title: locale.t('inviteFriend') }, { path: '/customer-service', title: locale.t('contactCustomerService') }, { path: '/complaint', title: locale.t('complaintEmail') }, { path: '/inbox', title: text('站内信与公告', 'Inbox & announcements') }] },
])
const chartPath = computed(() => {
  if (!history.value) return ''
  const data = assetDisplayLine(assetHistoryPoints(history.value), history.value.from, history.value.asOf)
  if (!data.length) return ''
  const values = data.map(p => p.value!), low = Math.min(...values), high = Math.max(...values)
  return data.map((p, i) => `${i ? 'L' : 'M'}${((p.time - history.value!.from) / (history.value!.asOf - history.value!.from) * 330 + 2).toFixed(2)},${(68 - (p.value! - low) / Math.max(high - low, 1) * 60).toFixed(2)}`).join(' ')
})
async function loadHistory() {
  if (loading.value || !auth.token) return
  loading.value = true
  try {
    const data = await request.get('/user/asset-history', { params: { range: '1W' }, signal: controller.signal }) as unknown as AssetHistory
    if (!active()) return
    assetHistoryPoints(data); history.value = data; error.value = ''
  } catch (failure: any) { if (active()) error.value = failure.message || text('资产快照不可用', 'Asset snapshot unavailable') }
  finally { if (active()) loading.value = false }
}
async function loadInfo() {
  if (!auth.user?.id) return
  try {
    const data: any = await request.get(`/user/${auth.user.id}/info`, { signal: controller.signal })
    if (!data || typeof data !== 'object' || data.success === false) throw new Error(data?.message || 'Invalid user details response')
    if (active()) { info.value = data; infoError.value = '' }
  } catch (failure: any) { if (active()) infoError.value = failure.message || text('用户资料暂不可用', 'User details unavailable') }
}
function logout() { if (pendingAccountWrites.value) return; auth.logout(); void router.push('/login') }
function back() { if (window.history.state?.back) router.back(); else void router.push('/home') }
function refreshVisible() { if (document.visibilityState === 'visible') void loadHistory() }
onMounted(() => { void loadInfo(); void loadHistory(); timer = setInterval(refreshVisible, 30000); document.addEventListener('visibilitychange', refreshVisible) })
onBeforeUnmount(() => { disposed = true; controller.abort(); clearInterval(timer); document.removeEventListener('visibilitychange', refreshVisible) })
</script>

<template>
  <AdvancedLayout nav>
    <header class="profile-header"><button type="button" :aria-label="text('返回', 'Back')" @click="back"><img :src="backIcon" alt="" /></button><h1>{{ text('我的', 'Profile') }}</h1><router-link to="/language" :aria-label="locale.t('language')">⋯</router-link></header>
    <div class="profile-content" data-design-node="34:840">
      <section class="panel identity-card"><div class="row"><h2>{{ name }}</h2><nav class="identity-actions" :aria-label="text('消息与客服', 'Inbox and support')"><router-link to="/inbox">{{ text('消息', 'Inbox') }}</router-link><span aria-hidden="true">·</span><router-link to="/customer-service">{{ text('客服', 'Support') }}</router-link></nav></div><p class="muted">UID {{ uid }}</p><span v-if="accountMode() === 'DEMO'" class="mode-label">{{ text('模拟账户 · 虚拟资金', 'Demo account · Virtual funds') }}</span><p v-if="infoError" class="error" role="alert">{{ infoError }} <button type="button" @click="loadInfo">{{ text('重试', 'Retry') }}</button></p></section>
      <section class="panel equity-card"><div class="row"><router-link to="/assets"><h2>{{ text('资产总览', 'Asset overview') }}</h2></router-link><button class="visibility" type="button" :aria-pressed="visible" @click="visible = !visible">{{ visible ? text('隐藏', 'Hide') : text('显示', 'Show') }}</button></div><strong class="equity-value">{{ visible ? money(history?.total) : '••••••' }}</strong><p class="muted">{{ text('真实净资产快照 / USD', 'Actual net equity snapshot / USD') }}</p>
        <p v-if="error" class="error" role="alert">{{ error }} <button type="button" @click="loadHistory">{{ text('重试', 'Retry') }}</button></p>
        <template v-if="visible"><svg v-if="chartPath" class="equity-chart" viewBox="0 0 334 72" role="img" :aria-label="text('真实净资产曲线', 'Actual net equity history')"><path :d="chartPath" fill="none" stroke="#736582" stroke-width="1.5" /></svg><p v-else class="empty">{{ loading ? text('加载中', 'Loading') : text('暂无快照数据', 'No snapshot data') }}</p><div v-if="history" class="axis"><span>{{ dates(history.from) }}</span><span>{{ dates(history.asOf) }}</span></div></template>
        <p v-if="history?.total === null" class="notice">{{ text('估值暂不可用，请刷新', 'Valuation unavailable. Refresh to update.') }}</p>
      </section>
      <nav class="shortcuts" :aria-label="text('资金快捷操作', 'Funding actions')"><router-link v-for="action in shortcuts" :key="action.path" :to="action.path"><span class="shortcut-icon"><img :src="action.icon" alt="" /></span><span>{{ action.label }}</span></router-link></nav>
      <section v-for="(group, index) in groups" :key="group.title" class="panel"><h2>{{ group.title }}</h2><template v-for="entry in group.entries" :key="entry.path"><router-link class="menu-row" :to="entry.path"><span>{{ entry.title }}</span><span aria-hidden="true">›</span></router-link><div v-if="index === 0 && entry.path === '/transfer' && auth.token" class="account-mode"><AccountModeSwitch placement="menu" real-path="/trade" /></div></template></section>
      <button class="logout" type="button" :disabled="pendingAccountWrites > 0" @click="logout">{{ locale.t('logout') }}</button><p v-if="pendingAccountWrites > 0" class="notice" role="status">{{ text('请求正在处理中，请完成后再退出或切换', 'Request in progress. Wait before signing out or switching.') }}</p>
      <EditionSwitch />
    </div>
  </AdvancedLayout>
</template>

<style scoped>
.profile-header{display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:64px;margin-bottom:16px}.profile-header h1{margin:0;flex:1;text-align:center;font-size:17px;font-weight:500}.profile-header button,.profile-header>a{display:grid;place-items:center;width:40px;min-width:40px;height:40px;border:0;background:none;color:#707780;font:inherit}.profile-header img{display:block}.identity-actions{display:flex;align-items:center;gap:5px;font-size:12px;color:#736582;flex-shrink:0}.identity-actions a{min-height:24px;display:flex;align-items:center}
.profile-content{padding:0;display:flex;flex-direction:column;gap:20px}.profile-content p,.profile-content h2{margin:0}.profile-content h2{font-size:17px;font-weight:500;overflow-wrap:anywhere}.panel{border:1px solid #e9edef;border-radius:10px;padding:12px;display:flex;flex-direction:column;gap:12px;min-width:0}.row{display:flex;align-items:center;justify-content:space-between;gap:12px}.row>h2{flex:1;min-width:0}.muted{font-size:12px;color:#707780;line-height:1.45}.identity-card .row{align-items:flex-start}.mode-label{font-size:12px;color:#736582}.equity-value{font-size:30px;font-weight:500;line-height:1.45;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}.equity-chart{width:100%;height:auto}.visibility{font:inherit;font-size:12px;color:#736582;background:none;border:0;min-height:36px}.axis{display:flex;justify-content:space-between;gap:8px;font-size:12px;color:#707780;font-variant-numeric:tabular-nums}.shortcuts{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px}.shortcuts a{display:flex;flex-direction:column;align-items:center;gap:8px;color:#252a30;font-size:12px;text-align:center;overflow-wrap:anywhere}.shortcut-icon{display:flex;align-items:center;justify-content:center;width:44px;height:44px;border-radius:50%;background:#f5f6f7}.shortcut-icon img{display:block}.menu-row{display:flex;justify-content:space-between;align-items:center;gap:12px;min-height:48px;color:#252a30;font-size:14px;overflow-wrap:anywhere}.menu-row span:first-child{min-width:0}.account-mode :deep(.account-mode-menu){background:#f5f2f7;color:#736582;font-size:12px;border-radius:7px;padding:10px;min-height:48px}.account-mode :deep(.entry-indicator){background:#736582;height:14px}.logout{border:0;border-radius:10px;background:#f5f6f7;color:#252a30;min-height:48px;font:inherit;font-size:14px}.logout:disabled{opacity:.5}.empty{min-height:72px;display:grid;place-items:center;color:#707780;font-size:12px}.error{color:#aa5363;font-size:12px;overflow-wrap:anywhere}.error button{background:none;border:0;color:inherit;font:inherit;text-decoration:underline;min-height:36px}.notice{font-size:12px;color:#707780;background:#f5f2f7;padding:10px;border-radius:7px}a:focus-visible,button:focus-visible{outline:2px solid #7557b7;outline-offset:3px}
</style>
