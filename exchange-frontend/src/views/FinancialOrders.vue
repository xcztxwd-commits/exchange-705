<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/utils/request'
import Tabbar from '@/components/Tabbar.vue'
import { useLocaleStore } from '@/store/locale'
import { formatDateTime } from '@/utils/dateTime'

const router = useRouter()

// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()
const orders = ref<any[]>([])
const loading = ref(false)
const showRedeemDialog = ref(false)
const currentOrder = ref<any>(null)
const penaltyAmount = ref<string>('')

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

// 使用 formatDateTime 作为 formatDate（它返回的是 YYYY-MM-DD HH:mm:ss 格式）
const formatDate = formatDateTime

// 获取状态文本
function getStatusText(status: string) {
  const statusMap: Record<string, string> = {
    'IN_PROGRESS': localeStore.t('statusInProgress'),
    'COMPLETED': localeStore.t('statusCompleted'),
    'REDEEMED': localeStore.t('statusRedeemed')
  }
  return statusMap[status] || status
}

// 加载订单列表
async function loadOrders() {
  loading.value = true
  try {
    const res: any = await request.get('/financial/orders')
    if (res && res.success && res.list) {
      orders.value = res.list
    }
  } catch (e: any) {
    showToast(localeStore.t('loadOrderListFailed'), 'error')
  } finally {
    loading.value = false
  }
}

// 显示违约赎回对话框
async function showRedeem(order: any) {
  currentOrder.value = order
  try {
    const res: any = await request.get(`/financial/penalty/${order.id}`)
    if (res && res.success) {
      penaltyAmount.value = formatMoney(res.penaltyAmount)
      showRedeemDialog.value = true
    }
  } catch (e: any) {
    showToast(localeStore.t('calculatePenaltyFailed'), 'error')
  }
}

// 确认违约赎回
async function confirmRedeem() {
  if (!currentOrder.value) return

  try {
    const res: any = await request.post(`/financial/redeem/${currentOrder.value.id}`)
    if (res && res.success) {
      showToast(localeStore.t('redeemSuccess'), 'success')
      showRedeemDialog.value = false
      loadOrders()
    } else {
      showToast(res.message || localeStore.t('redeemFailed'), 'error')
    }
  } catch (e: any) {
    showToast(e.response?.data?.message || e.message || localeStore.t('redeemFailed'), 'error')
  }
}

// 查看收益列表
function viewYieldList(order: any) {
  router.push({
    path: '/financial/yield-list',
    query: { orderId: order.id.toString() }
  })
}

onMounted(() => {
  loadOrders()
})
</script>

<template>
  <div class="financial-orders-page">
    <div class="page-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('purchaseList') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <div class="page-content" v-if="!loading">
      <div v-if="orders.length === 0" class="empty-state">
        <div class="empty-text">{{ localeStore.t('noPurchaseRecords') }}</div>
      </div>
      <div v-else class="order-list">
        <div v-for="order in orders" :key="order.id" class="order-card">
          <div class="order-header">
            <div class="order-name">{{ order.productName }}</div>
            <div class="order-status" :class="order.status.toLowerCase()">
              {{ getStatusText(order.status) }}
            </div>
          </div>
          <div class="order-info">
            <div class="info-row">
              <div class="info-left">
                <div class="info-item">
                  <span class="info-label">{{ localeStore.t('purchaseAmount') }}</span>
                  <span class="info-value">{{ formatMoney(order.purchaseAmount) }}</span>
                </div>
                <div class="info-item">
                  <span class="info-label">{{ localeStore.t('expectedDailyYield') }}</span>
                  <span class="info-value">{{ formatMoney(order.dailyYield) }}</span>
                </div>
              </div>
              <div class="info-right">
                <div class="info-item">
                  <span class="info-label">{{ order.currency }}</span>
                </div>
                <div class="info-item">
                  <span class="info-label">{{ localeStore.t('purchaseTime') }}</span>
                  <span class="info-value">{{ formatDate(order.purchaseTime) }}</span>
                </div>
                <div class="info-item">
                  <span class="info-label">{{ localeStore.t('expectedTotalYield') }}</span>
                  <span class="info-value">{{ formatMoney(order.totalYield) }}</span>
                </div>
              </div>
            </div>
          </div>
          <div class="order-actions">
            <button class="action-btn redeem-btn" @click="showRedeem(order)">{{ localeStore.t('earlyRedeem') }}</button>
            <button class="action-btn yield-btn" @click="viewYieldList(order)">{{ localeStore.t('viewYieldList') }}</button>
          </div>
        </div>
      </div>
    </div>

    <Tabbar />

    <!-- 违约赎回对话框 -->
    <div v-if="showRedeemDialog" class="redeem-dialog-overlay" @click.self="showRedeemDialog = false">
      <div class="redeem-dialog">
        <div class="dialog-content">
          <div class="dialog-message">
            {{ localeStore.t('payPenaltyToRedeem') }} {{ penaltyAmount }}{{ currentOrder?.currency }} {{ localeStore.t('penaltyFee') }}
          </div>
        </div>
        <div class="dialog-footer">
          <button class="dialog-btn cancel-btn" @click="showRedeemDialog = false">{{ localeStore.t('cancel') }}</button>
          <button class="dialog-btn confirm-btn" @click="confirmRedeem">{{ localeStore.t('confirm') }}</button>
        </div>
      </div>
    </div>

    <!-- Toast提示 -->
    <div v-if="toastMessage" :class="['toast-message', toastType]">
      {{ toastMessage }}
    </div>
  </div>
</template>

<style scoped>
.financial-orders-page {
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

.empty-state {
  padding: 60px 20px;
  text-align: center;
}

.empty-text {
  font-size: 16px;
  color: #999;
}

.order-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.order-card {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
}

.order-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.order-name {
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.order-status {
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}

.order-status.in_progress {
  background: #e8f5e9;
  color: #2abf4b;
}

.order-status.completed {
  background: #f5f5f5;
  color: #666;
}

.order-status.redeemed {
  background: #fff3e0;
  color: #ff9800;
}

.order-info {
  margin-bottom: 16px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.info-left,
.info-right {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.info-label {
  font-size: 12px;
  color: #666;
}

.info-value {
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.order-actions {
  display: flex;
  gap: 12px;
}

.action-btn {
  flex: 1;
  padding: 12px;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
}

.redeem-btn {
  background: #e8f5e9;
  color: #2abf4b;
}

.yield-btn {
  background: #2abf4b;
  color: #fff;
}

/* 违约赎回对话框 */
.redeem-dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}

.redeem-dialog {
  width: 90%;
  max-width: 400px;
  background: #fff;
  border-radius: 12px;
  padding: 24px;
}

.dialog-content {
  margin-bottom: 24px;
}

.dialog-message {
  font-size: 16px;
  color: #333;
  text-align: center;
  line-height: 1.6;
}

.dialog-footer {
  display: flex;
  gap: 12px;
}

.dialog-btn {
  flex: 1;
  padding: 12px;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 500;
  cursor: pointer;
}

.cancel-btn {
  background: #f5f5f5;
  color: #666;
}

.confirm-btn {
  background: #2abf4b;
  color: #fff;
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

