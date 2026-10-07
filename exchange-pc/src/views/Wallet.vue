<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useTrialWallet } from '@/utils/useTrialWallet'
import { useRouter } from 'vue-router'
import TrialAccountCard from '@/components/TrialAccountCard.vue'
import WalletAccountBalances from '../../../exchange-frontend/src/components/WalletAccountBalances.vue'
import { readAssetResponse } from '../../../exchange-frontend/src/advanced/utils/assetResponse'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const auth = useAuthStore()
const trialWallet = useTrialWallet()
auth.load()

const localeStore = useLocaleStore()
localeStore.loadLocale()

// 资产信息
const totalAssets = ref(0)
const fundBalance = ref(0)
const contractBalance = ref(0)
const optionBalance = ref(0)
const fundFrozen = ref(0)
const contractFrozen = ref(0)
const optionFrozen = ref(0)
const balanceVisible = ref(true)
const loading = ref(true)
const error = ref('')

// 格式化金额
function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString(localeStore.locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 切换余额显示/隐藏
function toggleBalance() {
  balanceVisible.value = !balanceVisible.value
}

// 加载资产信息
async function loadAssets() {
  loading.value = true
  error.value = ''
  try {
    const owner = auth.token, started = performance.now()
    const res: any = await request.get('/user/assets')
    if (owner !== auth.token) return
    const assets = readAssetResponse(res, ['fundBalance', 'contractBalance', 'optionBalance', 'fundFrozen', 'contractFrozen', 'optionFrozen'], localeStore.t('loadAssetsFailed'))
    trialWallet.accept(res, started)
    
    if (res && res.success !== false) {
      fundBalance.value = assets.fundBalance
      contractBalance.value = assets.contractBalance
      optionBalance.value = assets.optionBalance
      fundFrozen.value = assets.fundFrozen
      contractFrozen.value = assets.contractFrozen
      optionFrozen.value = assets.optionFrozen
      totalAssets.value = fundBalance.value + fundFrozen.value + contractBalance.value + contractFrozen.value + optionBalance.value + optionFrozen.value
    }
  } catch (e: any) {
    error.value = localeStore.t('loadAssetsFailed')
    console.error('加载资产信息失败:', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadAssets()
})
</script>

<template>
  <div class="wallet-page">
    <!-- 顶部导航 -->
    <div class="wallet-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('wallet') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <!-- 我的资产卡片 -->
    <div class="assets-card">
      <div class="assets-header">
        <div class="assets-title">{{ localeStore.t('myAssets') }}</div>
        <div class="assets-subtitle">{{ localeStore.t('totalAccountAssetsConverted') }}</div>
      </div>
      <div class="assets-value-row">
        <div class="assets-value">
          <span v-if="loading || error">—</span>
          <span v-else-if="balanceVisible"><bdi>${{ formatMoney(totalAssets) }}</bdi></span>
          <span v-else class="hidden-balance">****</span>
        </div>
        <button type="button"
          class="eye-icon"
          :aria-label="balanceVisible ? localeStore.text('隱藏餘額', 'Hide balances') : localeStore.text('顯示餘額', 'Show balances')"
          :aria-pressed="!balanceVisible"
          @click="toggleBalance"
        >
          <!-- 睁眼图标（显示状态） -->
          <svg v-if="balanceVisible" t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
            <path d="M512 298.666667c-162.133333 0-285.866667 68.266667-375.466667 213.333333 89.6 145.066667 213.333333 213.333333 375.466667 213.333333s285.866667-68.266667 375.466667-213.333333c-89.6-145.066667-213.333333-213.333333-375.466667-213.333333z m0 469.333333c-183.466667 0-328.533333-85.333333-426.666667-256 98.133333-170.666667 243.2-256 426.666667-256s328.533333 85.333333 426.666667 256c-98.133333 170.666667-243.2 256-426.666667 256z m0-170.666667c46.933333 0 85.333333-38.4 85.333333-85.333333s-38.4-85.333333-85.333333-85.333333-85.333333 38.4-85.333333 85.333333 38.4 85.333333 85.333333 85.333333z m0 42.666667c-72.533333 0-128-55.466667-128-128s55.466667-128 128-128 128 55.466667 128 128-55.466667 128-128 128z" fill="#444444"></path>
          </svg>
          <!-- 闭眼图标（隐藏状态） -->
          <svg v-else t="1767810656211" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
            <path d="M508.8 704c-70.4 0-128-57.6-128-128 0-12.8 2.133333-25.6 5.333333-38.4l-70.4-70.4c-25.6 38.4-38.4 83.2-38.4 128 0 140.8 115.2 256 256 256 44.8 0 89.6-12.8 128-38.4l-70.4-70.4c-12.8 3.2-25.6 5.333333-38.4 5.333333z m256-128c0 12.8-2.133333 25.6-5.333333 38.4l70.4 70.4c25.6-38.4 38.4-83.2 38.4-128 0-140.8-115.2-256-256-256-44.8 0-89.6 12.8-128 38.4l70.4 70.4c12.8-3.2 25.6-5.333333 38.4-5.333333 70.4 0 128 57.6 128 128z" fill="#999999"></path>
            <path d="M764.8 576c-12.8-32-32-57.6-57.6-83.2l-70.4-70.4c25.6-19.2 44.8-44.8 57.6-76.8l128-128-57.6-57.6-128 128c-32 12.8-57.6 32-76.8 57.6l-70.4-70.4c-25.6-25.6-51.2-44.8-83.2-57.6l-128-128-57.6 57.6 128 128c-12.8 32-12.8 64 0 96l-128 128 57.6 57.6 128-128c32 12.8 64 12.8 96 0l70.4 70.4c25.6 25.6 51.2 44.8 83.2 57.6l128 128 57.6-57.6-128-128c12.8-32 12.8-64 0-96z" fill="#999999"></path>
          </svg>
        </button>
      </div>
    </div>

    <WalletAccountBalances class="wallet-account-section" :accounts="[
      { type: 'FUND', name: localeStore.t('fundAccountTitle'), available: fundBalance, frozen: fundFrozen },
      { type: 'CONTRACT', name: localeStore.t('contractAccountTitle'), available: contractBalance, frozen: contractFrozen },
      { type: 'OPTION', name: localeStore.t('optionAccountTitle'), available: optionBalance, frozen: optionFrozen },
    ]" :visible="balanceVisible" :unavailable="loading || !!error" />
    <div v-if="error" class="wallet-load-error" role="alert"><span>{{ error }}</span><button type="button" @click="loadAssets">{{ localeStore.text('重試', 'Retry') }}</button></div>
    <div style="margin:16px"><TrialAccountCard :visible="balanceVisible && !loading && !error" /></div>
    <!-- 绑定银行卡 -->
    <div class="bind-item" @click="router.push('/wallet/bind-bank-card')">
      <div class="bind-label">{{ localeStore.t('bindBankCard') }}</div>
      <div class="bind-arrow ui-chevron" aria-hidden="true"></div>
    </div>

    <!-- 绑定数字货币地址 -->
    <div class="bind-item" @click="router.push('/wallet/bind-digital-currency')">
      <div class="bind-label">{{ localeStore.t('bindDigitalCurrencyAddress') }}</div>
      <div class="bind-arrow ui-chevron" aria-hidden="true"></div>
    </div>

    <Tabbar />
  </div>
</template>

<style scoped>
.wallet-page {
  min-height: 100vh;
  background: #f8f8f8;
  padding-bottom: 80px;
}

/* 顶部导航 */
.wallet-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #f0f0f0;
}

.back-button {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.header-title {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.header-placeholder {
  width: 40px;
}

/* 我的资产卡片 */
.assets-card {
  background: #fff;
  border-radius: 12px;
  padding: 24px 20px;
  margin: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.assets-header {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.assets-title {
  font-size: 18px;
  font-weight: 600;
  color: #000;
}

.assets-subtitle {
  font-size: 13px;
  color: #999;
  overflow-wrap: anywhere;
}

.assets-value-row {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.assets-value {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 32px;
  font-weight: 700;
  color: #73b100;
}

.hidden-balance {
  font-size: 32px;
  font-weight: 700;
  color: #333;
  letter-spacing: 4px;
}

.eye-icon {
  flex: none;
  padding: 0;
  border: 0;
  background: transparent;
  width: 32px;
  height: 32px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  transition: background 0.2s;
}

.eye-icon:active {
  background: #f0f0f0;
}

.eye-icon:focus-visible { outline: 2px solid #73b100; outline-offset: 3px; }
.wallet-account-section { margin: 0 16px 20px; }
.wallet-load-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 16px; padding: 12px; border-radius: 10px; background: #fff0ef; color: #a44343; font-size: 13px; }
.wallet-load-error button { flex: none; padding: 8px 12px; border: 0; border-radius: 6px; background: #fff; color: inherit; font: inherit; cursor: pointer; }

/* 绑定项 */
.bind-item {
  background: #fff;
  border-radius: 12px;
  padding: 16px 20px;
  margin: 0 16px 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  transition: background 0.2s;
}

.bind-item:active {
  background: #f5f5f5;
}

.bind-label {
  font-size: 16px;
  color: #333;
  font-weight: 500;
}

.bind-arrow {
  font-size: 20px;
  color: #999;
  font-weight: 300;
}
</style>

