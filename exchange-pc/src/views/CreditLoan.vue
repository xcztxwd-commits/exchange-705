<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'

const router = useRouter()
const auth = useAuthStore()
auth.load()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

// 用户信息
const totalLoanAmount = ref(0) // 借款总金额
const verified = ref(false) // 是否已实名认证

// 贷款设置
const loanSettings = ref<any[]>([])
const selectedSetting = ref<any>(null)

// 表单数据
const amount = ref<string>('')
const showTermSelector = ref(false) // 显示期限选择器

// Toast提示
const toastMessage = ref('')
const toastType = ref<'success' | 'error' | ''>('')

function showToast(message: string, type: 'success' | 'error' = 'error') {
  toastMessage.value = message
  toastType.value = type
  setTimeout(() => {
    toastMessage.value = ''
    toastType.value = ''
  }, 3000)
}

// 加载贷款设置
async function loadLoanSettings() {
  try {
    const res: any = await request.get('/loan/settings')
    if (res && res.success && res.list) {
      loanSettings.value = res.list
      if (res.list.length > 0) {
        selectedSetting.value = res.list[0]
      }
    }
  } catch (e) {
    console.error('加载贷款设置失败:', e)
  }
}

// 计算总利息
const totalInterest = computed(() => {
  if (!selectedSetting.value || !amount.value) return 0
  const amt = Number(amount.value) || 0
  const days = selectedSetting.value.days || 0
  const freeDays = selectedSetting.value.freeDays || 0
  const dailyRate = Number(selectedSetting.value.dailyRate || 0) / 100
  const chargeableDays = Math.max(0, days - freeDays)
  return amt * dailyRate * chargeableDays
})

// 格式化金额
function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 设置最大金额（使用贷款设置的最大金额）
function setMaxAmount() {
  if (selectedSetting.value && selectedSetting.value.maxAmount) {
    amount.value = selectedSetting.value.maxAmount.toString()
  }
}

// 选择期限
function selectTerm(setting: any) {
  selectedSetting.value = setting
  showTermSelector.value = false
  // 清空金额，让用户重新输入
  amount.value = ''
}

// 提交贷款申请
async function submitLoan() {
  if (!verified.value) {
    showToast(localeStore.t('pleaseCompleteVerification'), 'error')
    return
  }

  const amountNum = Number(amount.value)
  if (!amount.value || amountNum <= 0) {
    showToast(localeStore.t('pleaseEnterValidAmount'), 'error')
    return
  }

  if (!selectedSetting.value) {
    showToast(localeStore.t('pleaseSelectLoanTerm'), 'error')
    return
  }

  // 验证金额范围
  if (selectedSetting.value.minAmount && amountNum < selectedSetting.value.minAmount) {
    showToast(`${localeStore.t('amountCannotBeLessThan')}${formatMoney(selectedSetting.value.minAmount)}`, 'error')
    return
  }

  if (selectedSetting.value.maxAmount && amountNum > selectedSetting.value.maxAmount) {
    showToast(`${localeStore.t('amountCannotBeGreaterThan')}${formatMoney(selectedSetting.value.maxAmount)}`, 'error')
    return
  }

  // 如果个人信息已审核通过，直接提交贷款申请
  try {
    const personalInfoRes: any = await request.get('/loan/personal-info/status')
    if (personalInfoRes && personalInfoRes.success && personalInfoRes.verified && personalInfoRes.data) {
      // 个人信息已审核通过，直接使用已审核的信息提交贷款申请
      const personalInfo = personalInfoRes.data
      const res: any = await request.post('/loan/apply', {
        amount: Number(amount.value),
        settingId: selectedSetting.value.id.toString(),
        realName: personalInfo.realName,
        idNumber: personalInfo.idNumber,
        phone: personalInfo.phone,
        address: personalInfo.address
      })

      if (res && res.success && res.data) {
        showToast(localeStore.t('applicationSubmittedSuccess'), 'success')
        setTimeout(() => {
          router.push({
            path: '/loan/contract',
            query: { id: res.data.id.toString() }
          })
        }, 1000)
      } else {
        showToast(res.message || localeStore.t('applicationFailed'), 'error')
      }
    } else {
      // 个人信息未审核通过，跳转到信息填写页面
      router.push({
        path: '/loan/apply-info',
        query: {
          amount: amount.value,
          settingId: selectedSetting.value.id.toString()
        }
      })
    }
  } catch (e: any) {
    console.error('提交贷款申请失败:', e)
    // 如果获取个人信息失败，也跳转到信息填写页面
    router.push({
      path: '/loan/apply-info',
      query: {
        amount: amount.value,
        settingId: selectedSetting.value.id.toString()
      }
    })
  }
}

// 加载借款总金额
async function loadTotalLoanAmount() {
  try {
    const res: any = await request.get('/loan/total')
    if (res && res.success) {
      totalLoanAmount.value = Number(res.totalAmount || 0)
    }
  } catch (e) {
    console.error('加载借款总金额失败:', e)
  }
}

// 加载个人信息审核状态
async function loadKycStatus() {
  try {
    const res: any = await request.get('/loan/personal-info/status')
    if (res && res.success && res.verified) {
      verified.value = true
    } else {
      verified.value = false
    }
  } catch (e) {
    console.error('加载个人信息状态失败:', e)
    verified.value = false
  }
}

onMounted(() => {
  loadTotalLoanAmount()
  loadLoanSettings()
  loadKycStatus()
})
</script>

<template>
  <div class="credit-loan-page">
    <!-- 顶部导航 -->
    <div class="page-header">
      <div class="back-button" @click="router.push('/')">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('loan') }}</div>
      <div class="header-menu" @click="router.push('/loan/records')">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <path d="M4 6H20M4 12H20M4 18H20" stroke="#333" stroke-width="2" stroke-linecap="round"/>
        </svg>
      </div>
    </div>

    <!-- 用户信息 -->
    <div class="user-info">
      <div class="brand">{{ localeStore.t('loan') }}</div>
      <div class="balance">{{ formatMoney(totalLoanAmount) }}</div>
    </div>

    <div class="page-content">
      <!-- 验证状态卡片 -->
      <div class="status-card">
        <div class="status-text">{{ localeStore.t('startEnjoyLoanService') }}</div>
        <button 
          class="verified-btn" 
          :class="{ verified: verified }"
          @click="!verified && router.push('/loan/personal-info')"
        >
          <svg v-if="verified" width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M20 6L9 17L4 12" stroke="white" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <span>{{ verified ? localeStore.t('verified') : localeStore.t('unverified') }}</span>
        </button>
      </div>

      <!-- 金额输入 -->
      <div class="form-section">
        <div class="form-label">{{ localeStore.t('amount') }}</div>
        <div class="amount-input-wrapper">
          <input
            v-model="amount"
            type="number"
            class="amount-input"
            :placeholder="selectedSetting ? `${localeStore.t('enterAmount')} (${formatMoney(selectedSetting.minAmount || 0)} - ${formatMoney(selectedSetting.maxAmount || 0)})` : localeStore.t('enterAmount')"
            :min="selectedSetting ? selectedSetting.minAmount : undefined"
            :max="selectedSetting ? selectedSetting.maxAmount : undefined"
          />
          <button class="max-btn" @click="setMaxAmount" :disabled="!selectedSetting || !selectedSetting.maxAmount">{{ localeStore.t('max') }}</button>
        </div>
        <div v-if="selectedSetting" class="amount-hint">
          <span>{{ localeStore.t('min') }}: {{ formatMoney(selectedSetting.minAmount || 0) }}</span>
          <span>{{ localeStore.t('max') }}: {{ formatMoney(selectedSetting.maxAmount || 0) }}</span>
        </div>
      </div>

      <!-- 贷款期限 -->
      <div class="form-section">
        <div class="form-label">{{ localeStore.t('loanTerm') }}</div>
        <div class="term-select-wrapper">
          <div class="term-select" @click="showTermSelector = !showTermSelector">
            <span>{{ selectedSetting ? `${selectedSetting.days} ${localeStore.t('days')}` : localeStore.t('pleaseSelect') }}</span>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" :class="{ rotated: showTermSelector }">
              <path d="M9 18L15 12L9 6" stroke="#333" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          
          <!-- 期限选择下拉菜单 -->
          <div v-if="showTermSelector" class="term-dropdown">
            <div
              v-for="setting in loanSettings"
              :key="setting.id"
              class="term-option"
              :class="{ active: selectedSetting && selectedSetting.id === setting.id }"
              @click="selectTerm(setting)"
            >
              <div class="term-option-main">
                <span class="term-days">{{ setting.days }} {{ localeStore.t('days') }}</span>
                <span class="term-rate">{{ localeStore.t('dailyRate') }} {{ setting.dailyRate }}%</span>
              </div>
              <div class="term-option-detail">
                <span>{{ localeStore.t('freeDays') }}: {{ setting.freeDays }} {{ localeStore.t('days') }}</span>
                <span>{{ localeStore.t('amount') }}: {{ formatMoney(setting.minAmount || 0) }} - {{ formatMoney(setting.maxAmount || 0) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 利率信息 -->
      <div class="interest-section">
        <div class="interest-item">
          <span class="interest-label">{{ localeStore.t('dailyRate') }}</span>
          <span class="interest-value">{{ selectedSetting ? selectedSetting.dailyRate + '%' : '0%' }}</span>
        </div>
        <div class="interest-item">
          <span class="interest-label">{{ localeStore.t('freeDays') }}</span>
          <span class="interest-value">{{ selectedSetting ? selectedSetting.freeDays : 0 }}</span>
        </div>
        <div class="interest-item">
          <span class="interest-label">{{ localeStore.t('totalInterest') }}</span>
          <span class="interest-value">{{ formatMoney(totalInterest) }}</span>
        </div>
      </div>

      <!-- 现在借款按钮 -->
      <button class="submit-btn" @click="submitLoan">{{ localeStore.t('borrowNow') }}</button>
    </div>

    <!-- 点击外部关闭选择器 -->
    <div v-if="showTermSelector" class="dropdown-overlay" @click="showTermSelector = false"></div>

    <Tabbar />

    <!-- Toast提示 -->
    <div v-if="toastMessage" :class="['toast-message', toastType]">
      {{ toastMessage }}
    </div>
  </div>
</template>

<style scoped>
.credit-loan-page {
  min-height: 100vh;
  background: #f5f5f5;
  padding-bottom: 80px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  background: #fff;
  border-bottom: 1px solid #eee;
}

.back-button,
.header-menu {
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

.user-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px;
  background: #fff;
  border-bottom: 1px solid #f0f0f0;
}

.brand {
  font-size: 16px;
  font-weight: 500;
  color: #333;
}

.balance {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.page-content {
  padding: 20px 16px;
}

.status-card {
  background: linear-gradient(135deg, #fdfdff 0%, #f5f5f5 100%);
  border-radius: 16px;
  padding: 24px;
  margin-bottom: 24px;
  text-align: center;
}

.status-text {
  font-size: 16px;
  color: #333;
  margin-bottom: 16px;
}

.verified-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 24px;
  border-radius: 20px;
  border: none;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  background: #ccc;
  color: #fff;
  transition: all 0.3s;
}

.verified-btn:hover:not(.verified) {
  background: #bbb;
}

.verified-btn.verified {
  background: #2abf4b;
  color: #fff;
  cursor: default;
}

.form-section {
  margin-bottom: 20px;
}

.form-label {
  font-size: 14px;
  color: #333;
  margin-bottom: 8px;
  font-weight: 500;
}

.amount-input-wrapper {
  display: flex;
  gap: 8px;
  align-items: center;
}

.amount-input {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 16px;
  background: #f8f8f8;
}

.amount-input::placeholder {
  color: #999;
}

.max-btn {
  padding: 12px 20px;
  background: #2abf4b;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}

.max-btn:disabled {
  background: #ccc;
  cursor: not-allowed;
}

.amount-hint {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 12px;
  color: #999;
}

.term-select-wrapper {
  position: relative;
}

.term-select {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  background: #fff;
  cursor: pointer;
}

.term-select svg {
  transition: transform 0.3s ease;
}

.term-select svg.rotated {
  transform: rotate(90deg);
}

.term-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  right: 0;
  background: #fff;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  z-index: 100;
  max-height: 300px;
  overflow-y: auto;
}

.term-option {
  padding: 16px;
  border-bottom: 1px solid #f0f0f0;
  cursor: pointer;
  transition: background 0.2s;
}

.term-option:last-child {
  border-bottom: none;
}

.term-option:hover {
  background: #f5f5f5;
}

.term-option.active {
  background: #e8f5e9;
}

.term-option.active .term-days {
  color: #2abf4b;
  font-weight: 600;
}

.term-option-main {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.term-days {
  font-size: 16px;
  font-weight: 500;
  color: #333;
}

.term-rate {
  font-size: 14px;
  color: #666;
}

.term-option-detail {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #999;
}

.dropdown-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 99;
  background: transparent;
}

.interest-section {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 24px;
}

.interest-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}

.interest-item:last-child {
  border-bottom: none;
}

.interest-label {
  font-size: 14px;
  color: #666;
}

.interest-value {
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.submit-btn {
  width: 100%;
  padding: 16px;
  background: #2abf4b;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
}

/* Toast提示 */
.toast-message {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 16px 24px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  z-index: 2000;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  animation: toastSlideIn 0.3s ease-out;
  max-width: 80%;
  text-align: center;
  word-wrap: break-word;
}

.toast-message.error {
  background: #ff4444;
  color: #fff;
}

.toast-message.success {
  background: #2abf4b;
  color: #fff;
}

@keyframes toastSlideIn {
  from {
    opacity: 0;
    transform: translate(-50%, -60%);
  }
  to {
    opacity: 1;
    transform: translate(-50%, -50%);
  }
}

.dropdown-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 99;
  background: transparent;
}
</style>
