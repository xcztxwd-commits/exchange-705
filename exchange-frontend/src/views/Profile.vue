<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AccountModeSwitch from '@/components/AccountModeSwitch.vue'
import Tabbar from '@/components/Tabbar.vue'
import EditionSwitch from '@/advanced/components/EditionSwitch.vue'
import MessageHeaderActions from '@/components/MessageHeaderActions.vue'
import { supportText } from '@/utils/support'
import AssetPixelChart from '@/components/AssetPixelChart.vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const auth = useAuthStore()
auth.load()
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 用户信息
const uid = ref<string | number>('')
const nickname = ref('')
const vipLevel = ref(0)
const creditScore = ref(100)

// 资产信息
const totalAssets = ref<number | null>(null)
const fundBalance = ref(0)
const contractBalance = ref(0)
const optionBalance = ref(0)
const balanceVisible = ref(true)
const inspectedAsset = ref<{ time: string; amount: number | null } | null>(null)
const displayedAssets = computed(() => inspectedAsset.value ? inspectedAsset.value.amount : totalAssets.value)

// 功能列表
const menuItems = computed(() => [
  { label: localeStore.t('wallet'), icon: '', route: '/wallet' },
  { label: localeStore.t('transfer'), icon: '', route: '/transfer' },
  { label: localeStore.t('verification'), icon: '', route: '/verification' },
  { label: localeStore.t('inviteFriend'), icon: '', route: '/invite' },
  { label: localeStore.t('changePassword'), icon: '', route: '/change-password' },
  { label: localeStore.t('contactCustomerService'), icon: '', route: '/customer-service' },
  { label: localeStore.t('complaintEmail'), icon: '', route: '/complaint' },
  { label: supportText('站内信与公告', 'Inbox & announcements'), icon: '', route: '/inbox' },
])

// 设置列表
const settingsItems = computed(() => [
  { label: localeStore.t('language'), icon: '', route: '/language' },
])

// 格式化金额
function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString(localeStore.locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 切换余额显示/隐藏
function toggleBalance() {
  balanceVisible.value = !balanceVisible.value
  if (!balanceVisible.value) inspectedAsset.value = null
}

// 处理菜单点击
function handleMenuClick(item: any) {
  if (item.route) {
    router.push(item.route)
  }
}

// 加载用户信息
async function loadUserInfo() {
  const user = auth.user
  const userId = user?.id
  if (!userId) return

  try {
    const res: any = await request.get(`/user/${userId}/info`)
    uid.value = res.uid || res.id || userId
    nickname.value = res.nickname || res.email || user?.email || ''
    vipLevel.value = res.vipLevel || res.vip || 0
    creditScore.value = res.creditScore || res.credit || 100
    fundBalance.value = Number(res.fundBalance || 0)
    contractBalance.value = Number(res.contractBalance || 0)
    optionBalance.value = Number(res.optionBalance || 0)
    // The history endpoint supplies the headline and curve from the same snapshot.
  } catch (e) {
    console.error(localeStore.t('loadUserInfoFailed'), e)
    // 使用默认值
    uid.value = user?.id || ''
    nickname.value = user?.email || user?.nickname || ''
  }
}

// 登出
function handleLogout() {
  auth.logout()
  router.push('/login')
}

onMounted(() => {
  loadUserInfo()
})
</script>

<template>
  <div class="profile-page">
    <!-- 顶部图标和用户信息 -->
    <div class="card-top">
      <div class="top-header-row">
        <div class="user-info-section">
          <div class="user-line">{{ nickname || '111' }} </div>
          <div class="user-line">{{ localeStore.t('uid') }} {{ uid || 9210000 }}</div>
          
        </div>
        <MessageHeaderActions />
      </div>
    </div>

    <!-- 资产信息部分 -->
    <div class="assets-card">
      <div class="assets-header">
        <div class="assets-title" @click="router.push('/assets')">{{ localeStore.t('myAssets') }} <span class="ui-chevron" aria-hidden="true"></span></div>
      </div>
      <div class="assets-value-row">
        <div class="assets-value">
          <!-- 不再使用硬编码的默认资产数值，只显示真实资产（默认为 0） -->
          <span v-if="balanceVisible">{{ displayedAssets === null ? '—' : '$' + formatMoney(displayedAssets) }}</span>
          <span v-else class="hidden-balance">****</span>
        </div>
        <div 
          class="eye-icon"
          @click.stop="toggleBalance"
        >
          <!-- 睁眼图标（显示状态） -->
          <svg v-if="balanceVisible" t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
            <path d="M512 298.666667c-162.133333 0-285.866667 68.266667-375.466667 213.333333 89.6 145.066667 213.333333 213.333333 375.466667 213.333333s285.866667-68.266667 375.466667-213.333333c-89.6-145.066667-213.333333-213.333333-375.466667-213.333333z m0 469.333333c-183.466667 0-328.533333-85.333333-426.666667-256 98.133333-170.666667 243.2-256 426.666667-256s328.533333 85.333333 426.666667 256c-98.133333 170.666667-243.2 256-426.666667 256z m0-170.666667c46.933333 0 85.333333-38.4 85.333333-85.333333s-38.4-85.333333-85.333333-85.333333-85.333333 38.4-85.333333 85.333333 38.4 85.333333 85.333333 85.333333z m0 42.666667c-72.533333 0-128-55.466667-128-128s55.466667-128 128-128 128 55.466667 128 128-55.466667 128-128 128z" fill="#444444"></path>
          </svg>
          <!-- 闭眼图标（隐藏状态） -->
          <svg v-else t="1767810656211" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
            <path d="M469.333333 681.386667c-36.053333-2.432-71.253333-8.533333-104.96-17.92l-69.802666 149.674666a42.368 42.368 0 0 1-56.533334 20.266667 42.666667 42.666667 0 0 1-20.821333-56.32l66.986667-143.658667a451.712 451.712 0 0 1-148.906667-112.682666 388.693333 388.693333 0 0 1-70.570667-119.338667 42.666667 42.666667 0 1 1 80.128-29.354667 303.445333 303.445333 0 0 0 55.210667 93.098667C270.634667 547.413333 383.018667 597.333333 505.728 597.333333c122.752 0 235.136-49.962667 305.706667-132.181333a303.445333 303.445333 0 0 0 55.210666-93.098667 42.666667 42.666667 0 0 1 80.128 29.354667 388.693333 388.693333 0 0 1-70.570666 119.338667 423.68 423.68 0 0 1-18.773334 20.48l104.362667 104.362666a42.666667 42.666667 0 0 1-0.298667 60.032 42.368 42.368 0 0 1-60.032 0.298667l-109.653333-109.653333c-20.48 14.08-42.24 26.581333-65.024 37.418666l66.901333 143.36a42.666667 42.666667 0 0 1-20.821333 56.362667 42.368 42.368 0 0 1-56.533333-20.266667l-69.717334-149.546666a520.533333 520.533333 0 0 1-91.946666 16.810666v130.645334A42.666667 42.666667 0 0 1 512 853.333333c-23.722667 0-42.666667-18.944-42.666667-42.24v-129.706666z" fill="#3D3D3D"></path>
            <path d="M176.128 524.373333a42.368 42.368 0 0 1 60.032 0.256 42.666667 42.666667 0 0 1 0.298667 60.074667l-121.216 121.216a42.368 42.368 0 0 1-60.074667-0.298667 42.666667 42.666667 0 0 1-0.298667-60.032l121.258667-121.258666z" fill="#3D3D3D"></path>
          </svg>
        </div>
      </div>
      <AssetPixelChart :visible="balanceVisible" @total="totalAssets = $event" @inspect="inspectedAsset = $event" />
    </div>

    <!-- 入金和出金按钮 -->
    <div class="action-buttons">
      <div class="action-button deposit" @click="router.push('/deposit')">
        <div class="button-content">
          <img src="/img/chargeb74aec58.png" :alt="localeStore.t('deposit')" class="button-icon" />
          <div class="button-text">
            <div class="button-title">{{ localeStore.t('deposit') }}</div>
            <div class="button-subtitle">{{ localeStore.t('billDetails') }}</div>
          </div>
        </div>
        <div class="button-arrow ui-chevron" aria-hidden="true"></div>
      </div>
      <div class="action-button withdraw" @click="router.push('/withdraw')">
        <div class="button-content">
          <img src="/img/chargeb74aec58 (1).png" :alt="localeStore.t('withdraw')" class="button-icon" />
          <div class="button-text">
            <div class="button-title">{{ localeStore.t('withdraw') }}</div>
            <div class="button-subtitle">{{ localeStore.t('billDetails') }}</div>
          </div>
        </div>
        <div class="button-arrow ui-chevron" aria-hidden="true"></div>
      </div>
    </div>

    <!-- 所有功能列表 -->
    <div class="menu-section">
      <template v-for="item in menuItems" :key="item.label">
        <div
          class="menu-item-card"
          @click="handleMenuClick(item)"
        >
          <div class="menu-indicator"></div>
          <div class="menu-label">{{ item.label }}</div>
          <div class="menu-arrow ui-chevron" aria-hidden="true"></div>
        </div>
        <AccountModeSwitch v-if="auth.token && item.route === '/transfer'" placement="menu" real-path="/trade" />
      </template>
    </div>

    <!-- 设置部分 -->
    <div class="settings-section">
      <div class="settings-header">{{ localeStore.t('settings') }}</div>
      <div 
        v-for="item in settingsItems" 
        :key="item.label"
        class="menu-item-card"
        @click="handleMenuClick(item)"
      >
        <div class="menu-indicator"></div>
        <div class="menu-label">{{ item.label }}</div>
        <div class="menu-arrow ui-chevron" aria-hidden="true"></div>
      </div>
    </div>

    <button class="logout-button" type="button" @click="handleLogout">{{ localeStore.t('logout') }}</button>
    <EditionSwitch />
    <Tabbar />
  </div>
</template>

<style scoped>
.logout-button{display:block;margin:20px 8px;padding:12px 20px;background:#f5f5f5;color:#555;border:1px solid #ddd;border-radius:12px;cursor:pointer;font:inherit}
.profile-page {
  min-height: 100vh;
  background: #fff;
  padding: 12px;
  padding-bottom: 80px;
}

/* 卡片顶部（图标和用户信息） */
.card-top {
  margin-bottom: 24px;
  padding: 0 8px;
}

.top-header-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}

.user-info-section {
  min-width: 0;
  overflow-wrap: anywhere;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.user-line {
  font-size: 14px;
  color: #333;
  line-height: 1.4;
}

.top-icons {
  display: flex;
  gap: 12px;
  align-items: center;
}

.top-icon {
  width: 32px;
  height: 32px;
  cursor: pointer;
  transition: opacity 0.2s;
}

.top-icon:active {
  opacity: 0.6;
}

.user-line {
  font-size: 14px;
  color: #333;
  line-height: 1.4;
}

/* 资产信息卡片 */
.assets-card {
  background: transparent;
  border-radius: 19px;
  padding: 12px 8px;
  margin-bottom: 20px;
  margin-left: 8px;
  margin-right: 8px;
}

.assets-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.assets-title {
  display: inline-flex;
  align-items: center;
  font-size: 18px;
  font-weight: 600;
  color: #000;
}

.assets-value-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.assets-value {
  font-size: clamp(28px, 9vw, 40px);
  letter-spacing: -.05em;
  font-variant-numeric: tabular-nums;
  min-width: 0;
  font-weight: 700;
  color: #000;
  flex: 1;
}

.hidden-balance {
  letter-spacing: 4px;
}

.eye-icon {
  width: 32px;
  height: 32px;
  cursor: pointer;
  margin-left: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  transition: background 0.2s;
}

.eye-icon svg {
  width: 24px;
  height: 24px;
  display: block;
}

.eye-icon:active {
  background: #f0f0f0;
}

/* 入金和出金按钮 */
.action-buttons {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  padding: 0 8px;
}

.action-button {
  flex: 1;
  background: #F8F8F8;
  border-radius: 12px;
  padding: 16px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
  transition: all 0.3s;
  min-height: 80px;
}

.action-button:active {
  transform: scale(0.98);
  background: #F0F0F0;
}

.button-content {
  display: flex;
  align-items: center;
  gap: 12px;
}

.button-icon {
  width: 48px;
  height: 48px;
  object-fit: contain;
}

.button-text {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.button-title {
  font-size: 16px;
  font-weight: 600;
  color: #000;
}

.button-subtitle {
  font-size: 12px;
  color: #999;
}

.button-arrow {
  font-size: 20px;
  color: #999;
  font-weight: 300;
}

/* 功能列表 */
.menu-section {
  padding: 0 8px;
  margin-bottom: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.menu-item-card {
  background: #F0F0F0;
  border-radius: 12px;
  display: flex;
  align-items: center;
  padding: 16px 20px;
  cursor: pointer;
  transition: background 0.2s;
}

.menu-item-card:active {
  background: #E8E8E8;
}

.menu-indicator {
  width: 4px;
  height: 20px;
  background: #73b100;
  border-radius: 2px;
  margin-right: 12px;
}

.menu-label {
  flex: 1;
  font-size: 16px;
  color: #000;
}

.menu-arrow {
  font-size: 20px;
  color: #999;
  font-weight: 300;
}

/* 设置部分 */
.settings-section {
  padding: 0 8px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.settings-header {
  font-size: 14px;
  color: #999;
  padding: 0 4px;
  margin-bottom: 4px;
}
@media (max-width: 360px) {
  .action-button { min-width: 0; padding: 12px 8px; }
  .button-content { min-width: 0; gap: 6px; }
  .button-icon { width: 32px; height: 32px; flex-shrink: 0; }
  .button-text { min-width: 0; overflow-wrap: anywhere; }
  .button-title { font-size: 14px; }
  .button-subtitle { font-size: 10px; }
}
</style>
