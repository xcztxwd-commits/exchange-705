<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import Tabbar from '@/components/Tabbar.vue'
import request from '@/utils/request'
import { useLocaleStore } from '@/store/locale'
import { formatDateTime } from '@/utils/dateTime'

const router = useRouter()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

const loans = ref<any[]>([])
const loading = ref(true)

// 使用 formatDateTime 作为 formatDate（它返回的是 YYYY-MM-DD HH:mm:ss 格式，英国时区）
const formatDate = formatDateTime

// 格式化金额
function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 获取状态文本
function getStatusText(status: string) {
  const statusMap: Record<string, string> = {
    PENDING: localeStore.t('statusPending'),
    APPROVED: localeStore.t('statusApproved'),
    SIGNED: localeStore.t('statusSigned'),
    COMPLETED: localeStore.t('statusCompleted'),
    OVERDUE: localeStore.t('statusOverdue'),
    REJECTED: localeStore.t('statusRejected')
  }
  return statusMap[status] || status
}

// 获取状态颜色
function getStatusColor(status: string) {
  const colorMap: Record<string, string> = {
    PENDING: '#ff9800',
    APPROVED: '#2196f3',
    SIGNED: '#4caf50',
    COMPLETED: '#2abf4b',
    OVERDUE: '#f44336',
    REJECTED: '#999'
  }
  return colorMap[status] || '#666'
}

// 查看协议
function viewContract(loan: any) {
  router.push({
    path: '/loan/contract',
    query: { id: loan.id.toString() }
  })
}

// 显示提示消息
function showToastMessage(message: string, type: 'success' | 'error' = 'error') {
  // 移除之前的提示
  const existingToasts = document.querySelectorAll('.toast-message')
  existingToasts.forEach(toast => {
    if (document.body.contains(toast)) {
      document.body.removeChild(toast)
    }
  })
  
  // 创建新的提示
  const toast = document.createElement('div')
  toast.className = `toast-message ${type}`
  toast.textContent = message
  
  // 直接设置内联样式，确保样式生效
  toast.style.cssText = `
    position: fixed !important;
    top: 50% !important;
    left: 50% !important;
    transform: translate(-50%, -50%) !important;
    padding: 18px 36px !important;
    border-radius: 12px !important;
    font-size: 18px !important;
    font-weight: 600 !important;
    z-index: 99999 !important;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3) !important;
    pointer-events: none !important;
    white-space: nowrap !important;
    min-width: 200px !important;
    text-align: center !important;
    opacity: 0 !important;
    transition: opacity 0.3s ease-in-out !important;
    ${type === 'success' ? 'background: #73b100 !important; color: #fff !important;' : 'background: #ff4444 !important; color: #fff !important;'}
  `
  
  document.body.appendChild(toast)
  
  // 强制重排，确保样式应用
  void toast.offsetHeight
  
  // 触发动画
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      toast.style.opacity = '1'
    })
  })
  
  // 3秒后自动移除
  setTimeout(() => {
    toast.style.opacity = '0'
    setTimeout(() => {
      if (document.body.contains(toast)) {
        document.body.removeChild(toast)
      }
    }, 300)
  }, 3000)
}

// 确认对话框
function showConfirmDialog(message: string): Promise<boolean> {
  return new Promise((resolve) => {
    // 创建遮罩层
    const overlay = document.createElement('div')
    overlay.style.cssText = `
      position: fixed;
      top: 0;
      left: 0;
      right: 0;
      bottom: 0;
      background: rgba(0, 0, 0, 0.5);
      z-index: 10000;
      display: flex;
      align-items: center;
      justify-content: center;
    `
    
    // 创建对话框
    const dialog = document.createElement('div')
    dialog.style.cssText = `
      background: #fff;
      border-radius: 12px;
      padding: 24px;
      max-width: 400px;
      width: 90%;
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.3);
    `
    
    const messageEl = document.createElement('div')
    messageEl.textContent = message
    messageEl.style.cssText = `
      font-size: 16px;
      color: #333;
      margin-bottom: 24px;
      line-height: 1.5;
    `
    
    const buttonContainer = document.createElement('div')
    buttonContainer.style.cssText = `
      display: flex;
      gap: 12px;
      justify-content: flex-end;
    `
    
    const cancelBtn = document.createElement('button')
    cancelBtn.textContent = localeStore.t('cancel')
    cancelBtn.style.cssText = `
      padding: 10px 20px;
      border: 1px solid #ddd;
      border-radius: 6px;
      background: #fff;
      color: #666;
      font-size: 14px;
      cursor: pointer;
    `
    cancelBtn.onclick = () => {
      document.body.removeChild(overlay)
      resolve(false)
    }
    
    const confirmBtn = document.createElement('button')
    confirmBtn.textContent = localeStore.t('confirm')
    confirmBtn.style.cssText = `
      padding: 10px 20px;
      border: none;
      border-radius: 6px;
      background: #ff9800;
      color: #fff;
      font-size: 14px;
      cursor: pointer;
    `
    confirmBtn.onclick = () => {
      document.body.removeChild(overlay)
      resolve(true)
    }
    
    buttonContainer.appendChild(cancelBtn)
    buttonContainer.appendChild(confirmBtn)
    dialog.appendChild(messageEl)
    dialog.appendChild(buttonContainer)
    overlay.appendChild(dialog)
    document.body.appendChild(overlay)
  })
}

// 提前还款
async function earlyRepayment(loan: any) {
  try {
    const confirmed = await showConfirmDialog(localeStore.t('earlyRepaymentConfirm'))
    if (!confirmed) {
      return
    }
    
    const res: any = await request.post(`/loan/repay/${loan.id}`)
    if (res && res.success) {
      showToastMessage(localeStore.t('repaymentSuccess'), 'success')
      loadLoans()
    } else {
      showToastMessage(res?.message || localeStore.t('repaymentFailed'), 'error')
    }
  } catch (e: any) {
    showToastMessage(e?.response?.data?.message || e?.message || localeStore.t('repaymentFailed'), 'error')
  }
}

// 判断是否可以提前还款（只有已批准的状态才能提前还款）
function canEarlyRepay(loan: any): boolean {
  return loan.status === 'APPROVED'
}

// 加载贷款记录
async function loadLoans() {
  loading.value = true
  try {
    const res: any = await request.get('/loan/list')
    if (res && res.success && res.list) {
      loans.value = res.list
    }
  } catch (e) {
    console.error('加载贷款记录失败:', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadLoans()
})
</script>

<template>
  <div class="loan-records-page">
    <div class="page-header">
      <div class="back-button" @click="router.push('/credit-loan')">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('loanRecords') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="records-content">
      <div v-if="loading" class="loading">{{ localeStore.t('loading') }}</div>
      <div v-else-if="loans.length === 0" class="empty">{{ localeStore.t('noLoanRecords') }}</div>
      <div v-else class="loan-list">
        <div v-for="loan in loans" :key="loan.id" class="loan-item">
          <div class="loan-header">
            <span class="loan-id"># {{ loan.id }}</span>
            <span class="loan-date">{{ formatDate(loan.createdAt) }}</span>
          </div>

          <div class="loan-details">
            <div class="detail-row">
              <span class="detail-label">{{ localeStore.t('loanAmount') }}</span>
              <span class="detail-value">{{ formatMoney(loan.amount) }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">{{ localeStore.t('loanTerm') }}</span>
              <span class="detail-value">{{ loan.days }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">{{ localeStore.t('totalInterest') }}</span>
              <span class="detail-value">{{ formatMoney(loan.totalInterest) }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">{{ localeStore.t('dailyRate') }}</span>
              <span class="detail-value">{{ loan.dailyRate }}%</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">{{ localeStore.t('repaymentAmount') }}</span>
              <span class="detail-value">{{ formatMoney(loan.repaymentAmount) }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">{{ localeStore.t('overdueFee') }}</span>
              <span class="detail-value">{{ formatMoney(loan.overdueFee) }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">{{ localeStore.t('approvedAt') }}</span>
              <span class="detail-value">{{ loan.approvedAt ? formatDate(loan.approvedAt) : '-' }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">{{ localeStore.t('repaymentDate') }}</span>
              <span class="detail-value">{{ loan.repaymentDate ? formatDate(loan.repaymentDate) : '-' }}</span>
            </div>
          </div>

          <div class="loan-actions">
            <button class="action-btn agreement-btn" @click="viewContract(loan)">{{ localeStore.t('agreement') }}</button>
            <button 
              v-if="canEarlyRepay(loan)" 
              class="action-btn repay-btn" 
              @click="earlyRepayment(loan)"
            >
              {{ localeStore.t('earlyRepaymentButton') }}
            </button>
            <button class="action-btn status-btn" :style="{ color: getStatusColor(loan.status) }">
              {{ getStatusText(loan.status) }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <Tabbar />
  </div>
</template>

<style scoped>
.loan-records-page {
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

.records-content {
  padding: 16px;
}

.loading,
.empty {
  text-align: center;
  padding: 40px 20px;
  color: #999;
}

.loan-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.loan-item {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  border-left: 4px solid #2abf4b;
}

.loan-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f0f0f0;
}

.loan-id {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.loan-date {
  font-size: 14px;
  color: #999;
}

.loan-details {
  margin-bottom: 16px;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
}

.detail-label {
  font-size: 14px;
  color: #666;
}

.detail-value {
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.loan-actions {
  display: flex;
  gap: 12px;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
}

.action-btn {
  flex: 1;
  padding: 10px;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}

.agreement-btn {
  background: #2abf4b;
  color: #fff;
}

.status-btn {
  background: #f5f5f5;
  color: #666;
}

.repay-btn {
  background: #ff9800;
  color: #fff;
}
</style>



