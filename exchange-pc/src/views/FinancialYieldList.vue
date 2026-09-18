<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import request from '@/utils/request'
import Tabbar from '@/components/Tabbar.vue'
import { useLocaleStore } from '@/store/locale'
import { formatDate } from '@/utils/dateTime'

const router = useRouter()
const route = useRoute()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

const orderId = ref<string>('')
const yieldList = ref<any[]>([])
const stats = ref<any>(null)
const loading = ref(false)

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

// 格式化金额
function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}


// 获取状态文本
function getStatusText(status: string) {
  const statusMap: Record<string, string> = {
    'PENDING': localeStore.t('statusPendingPayment'),
    'PAID': localeStore.t('statusPaid')
  }
  return statusMap[status] || status
}

// 加载收益列表
async function loadYieldList() {
  if (!orderId.value) {
    showToast(localeStore.t('orderIdNotExists'), 'error')
    router.back()
    return
  }

  loading.value = true
  try {
    const res: any = await request.get(`/financial/yield/order/${orderId.value}`)
    if (res && res.success) {
      yieldList.value = res.list || []
      stats.value = res.stats || null
    } else {
      showToast(localeStore.t('loadYieldListFailed'), 'error')
    }
  } catch (e: any) {
    showToast(e.message || localeStore.t('loadFailed'), 'error')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  orderId.value = (route.query.orderId as string) || ''
  loadYieldList()
})
</script>

<template>
  <div class="financial-yield-list-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('yieldList') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="page-content" v-if="!loading">
      <!-- 收益统计 -->
      <div v-if="stats" class="yield-stats">
        <div class="stat-item">
          <div class="stat-label">{{ localeStore.t('totalYield') }}</div>
          <div class="stat-value">{{ formatMoney(stats.totalYield) }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">{{ localeStore.t('paidYield') }}</div>
          <div class="stat-value success">{{ formatMoney(stats.paidYield) }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">{{ localeStore.t('pendingYield') }}</div>
          <div class="stat-value warning">{{ formatMoney(stats.pendingYield) }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">{{ localeStore.t('yieldDays') }}</div>
          <div class="stat-value">{{ stats.recordCount }}</div>
        </div>
      </div>

      <!-- 收益列表 -->
      <div v-if="yieldList.length === 0" class="empty-state">
        <div class="empty-text">{{ localeStore.t('noYieldRecords') }}</div>
      </div>
      <div v-else class="yield-list">
        <div v-for="yieldRecord in yieldList" :key="yieldRecord.id" class="yield-item">
          <div class="yield-header">
            <div class="yield-date">{{ formatDate(yieldRecord.yieldDate) }}</div>
            <div class="yield-status" :class="yieldRecord.status.toLowerCase()">
              {{ getStatusText(yieldRecord.status) }}
            </div>
          </div>
          <div class="yield-info">
            <div class="info-row">
              <span class="info-label">{{ localeStore.t('dailyYield') }}</span>
              <span class="info-value">{{ formatMoney(yieldRecord.dailyYield) }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">{{ localeStore.t('cumulativeYield') }}</span>
              <span class="info-value">{{ formatMoney(yieldRecord.cumulativeYield) }}</span>
            </div>
            <div v-if="yieldRecord.paidAt" class="info-row">
              <span class="info-label">{{ localeStore.t('paidAt') }}</span>
              <span class="info-value small">{{ formatDate(yieldRecord.paidAt) }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <Tabbar />

    <!-- Toast提示 -->
    <div v-if="toastMessage" :class="['toast-message', toastType]">
      {{ toastMessage }}
    </div>
  </div>
</template>

<style scoped>
.financial-yield-list-page {
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

.page-content {
  padding: 16px;
}

.yield-stats {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.stat-item {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  text-align: center;
}

.stat-label {
  font-size: 12px;
  color: #666;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.stat-value.success {
  color: #2abf4b;
}

.stat-value.warning {
  color: #ff9800;
}

.empty-state {
  padding: 60px 20px;
  text-align: center;
}

.empty-text {
  font-size: 16px;
  color: #999;
}

.yield-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.yield-item {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
}

.yield-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #eee;
}

.yield-date {
  font-size: 16px;
  font-weight: 600;
  color: #333;
}

.yield-status {
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}

.yield-status.pending {
  background: #fff3e0;
  color: #ff9800;
}

.yield-status.paid {
  background: #e8f5e9;
  color: #2abf4b;
}

.yield-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.info-label {
  font-size: 14px;
  color: #666;
}

.info-value {
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.info-value.small {
  font-size: 12px;
  color: #999;
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
  z-index: 3000;
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
</style>

