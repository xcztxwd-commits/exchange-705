<script setup lang="ts">
import { ref, onMounted } from 'vue'
import CurrencyPicker from '@/components/CurrencyPicker.vue'
import { useFiatCurrency } from '@/utils/fiatCurrency'
const { currency, rate, formatAsset } = useFiatCurrency()
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const auth = useAuthStore()
auth.load()
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 资产信息
const totalAssets = ref(0)
const fundBalance = ref(0)
const fundFrozen = ref(0)
const contractBalance = ref(0)
const contractFrozen = ref(0)
const optionBalance = ref(0)
const optionFrozen = ref(0)
const balanceVisible = ref(true)
const loading = ref(false)

// 切换余额显示/隐藏
function toggleBalance() {
  balanceVisible.value = !balanceVisible.value
}

// 加载资产信息
async function loadAssets() {
  loading.value = true
  try {
    const user = auth.user
    const userId = user?.id
    if (!userId) return

    const res: any = await request.get('/user/assets')
    
    if (res && res.success !== false) {
      // 资金账户
      fundBalance.value = Number(res.fundBalance || res.fund?.available || 0)
      fundFrozen.value = Number(res.fundFrozen || res.fund?.frozen || 0)
      
      // 合约账户
      contractBalance.value = Number(res.contractBalance || res.contract?.available || 0)
      contractFrozen.value = Number(res.contractFrozen || res.contract?.frozen || 0)
      
      // 期权账户
      optionBalance.value = Number(res.optionBalance || res.option?.available || 0)
      optionFrozen.value = Number(res.optionFrozen || res.option?.frozen || 0)
      
      // 计算总资产
      totalAssets.value = fundBalance.value + contractBalance.value + optionBalance.value + fundFrozen.value + contractFrozen.value + optionFrozen.value
    }
  } catch (e: any) {
    console.error(localeStore.t('loadAssetsFailed'), e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadAssets()
})
</script>

<template>
  <div class="assets-page">
    <!-- 顶部导航 -->
    <div class="assets-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('myAssets') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <!-- 总资产卡片 -->
    <div class="total-assets-card">
      <CurrencyPicker v-model="currency" />
      <p v-if="rate === null" role="alert">汇率暂不可用，请稍后重试</p>
      <div class="total-assets-header">
        <div class="total-assets-label">{{ localeStore.t('totalAccountAssetsConverted') }}</div>
        <div 
          class="eye-icon"
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
        </div>
      </div>
      <div class="total-assets-value">
        <span v-if="balanceVisible">{{ formatAsset(totalAssets) }}</span>
        <span v-else class="hidden-balance">****</span>
      </div>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="loading-state">
      <div class="loading-text">{{ localeStore.t('loading') }}</div>
    </div>

    <!-- 账户列表 -->
    <div v-else class="accounts-list">
      <!-- 资金账户 -->
      <div class="account-card">
        <div class="account-header">
          <div class="account-title">{{ localeStore.t('fundAccountTitle') }}</div>
          <div class="account-arrow">›</div>
        </div>
        <div class="account-details">
          <div class="account-detail-item">
            <span class="detail-label">{{ localeStore.t('balance') }}</span>
            <span class="detail-value">
              <span v-if="balanceVisible">{{ formatAsset(fundBalance) }}</span>
              <span v-else class="hidden-balance">****</span>
            </span>
          </div>
          <div class="account-detail-item">
            <span class="detail-label">{{ localeStore.t('frozen') }}</span>
            <span class="detail-value">
              <span v-if="balanceVisible">{{ formatAsset(fundFrozen) }}</span>
              <span v-else class="hidden-balance">****</span>
            </span>
          </div>
        </div>
      </div>

      <!-- 期权账户 -->
      <div class="account-card">
        <div class="account-header">
          <div class="account-title">{{ localeStore.t('optionAccountTitle') }}</div>
          <div class="account-arrow">›</div>
        </div>
        <div class="account-details">
          <div class="account-detail-item">
            <span class="detail-label">{{ localeStore.t('balance') }}</span>
            <span class="detail-value">
              <span v-if="balanceVisible">{{ formatAsset(optionBalance) }}</span>
              <span v-else class="hidden-balance">****</span>
            </span>
          </div>
          <div class="account-detail-item">
            <span class="detail-label">{{ localeStore.t('frozen') }}</span>
            <span class="detail-value">
              <span v-if="balanceVisible">{{ formatAsset(optionFrozen) }}</span>
              <span v-else class="hidden-balance">****</span>
            </span>
          </div>
        </div>
      </div>

      <!-- 合约账户 -->
      <div class="account-card">
        <div class="account-header">
          <div class="account-title">{{ localeStore.t('contractAccountTitle') }}</div>
          <div class="account-arrow">›</div>
        </div>
        <div class="account-details">
          <div class="account-detail-item">
            <span class="detail-label">{{ localeStore.t('balance') }}</span>
            <span class="detail-value">
              <span v-if="balanceVisible">{{ formatAsset(contractBalance) }}</span>
              <span v-else class="hidden-balance">****</span>
            </span>
          </div>
          <div class="account-detail-item">
            <span class="detail-label">{{ localeStore.t('frozen') }}</span>
            <span class="detail-value">
              <span v-if="balanceVisible">{{ formatAsset(contractFrozen) }}</span>
              <span v-else class="hidden-balance">****</span>
            </span>
          </div>
        </div>
      </div>
    </div>

    <Tabbar />
  </div>
</template>

<style scoped>
.assets-page {
  min-height: 100vh;
  background: #f5f7fb;
  padding-bottom: 80px;
}

/* 顶部导航 */
.assets-header {
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

/* 总资产卡片 */
.total-assets-card {
  background: #fff;
  border-radius: 12px;
  padding: 24px 20px;
  margin: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.total-assets-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.total-assets-label {
  font-size: 14px;
  color: #999;
}

.eye-icon {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.total-assets-value {
  font-size: 32px;
  font-weight: 700;
  color: #73b100;
}

.hidden-balance {
  font-size: 32px;
  font-weight: 700;
  color: #333;
}

/* 加载状态 */
.loading-state {
  padding: 40px;
  text-align: center;
}

.loading-text {
  color: #999;
  font-size: 14px;
}

/* 账户列表 */
.accounts-list {
  padding: 0 16px;
}

.account-card {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.account-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
}

.account-title {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.account-arrow {
  font-size: 24px;
  color: #999;
}

.account-details {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.account-detail-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-label {
  font-size: 14px;
  color: #666;
}

.detail-value {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}
</style>

