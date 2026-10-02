<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
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
<BusinessPage :title="localeStore.t('financialOrders')" :error="advancedError" :busy="advancedWriting">
<div class="page-content" v-if="!loading">
      <div v-if="orders.length === 0" class="empty-state">
        <div class="empty-text">{{ localeStore.t('noPurchaseRecords') }}</div>
      </div>
      <div v-else class="order-list">
        <div v-for="order in orders" :key="order.id" class="order-card card">
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
                  <span class="info-label">{{ localeStore.t('estDailyYield') }}</span>
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

    

    <!-- 违约赎回对话框 -->
    <div v-if="showRedeemDialog" class="redeem-dialog-overlay" @click.self="showRedeemDialog = false">
      <div class="redeem-dialog">
        <div class="dialog-content">
          <div class="dialog-message">
            {{ localeStore.text('確認提前贖回？手續費為 {amount} {currency}。', 'Redeem early? The early redemption fee is {amount} {currency}.', { amount: penaltyAmount, currency: currentOrder?.currency || '' }) }}
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
</BusinessPage>
</template>

<style scoped>

.page-content,.cards-list,.addresses-list,.records-list,.yield-list,.product-list,.orders-list{display:flex;flex-direction:column;gap:20px}
.form-group,.form-item,.form-section{display:flex;flex-direction:column;gap:8px}
.form-label,.detail-label,.info-label,.stat-label{font-size:12px;color:#707780}
.form-actions,.order-actions,.dialog-footer,.amount-input-wrapper,.password-input-wrapper,.code-input-wrapper{display:flex;gap:8px}.form-actions>*,.order-actions>*{flex:1}
.record-row,.info-item,.info-row,.detail-item,.yield-header{display:flex;justify-content:space-between;gap:8px;padding:12px 0;overflow-wrap:anywhere}
.submit-btn,.submit-button,.confirm-btn,.purchase-btn{background:#d9e6c8!important;color:#2f4129!important;min-height:48px}
.contract-wrapper,.contract-box{display:flex;flex-direction:column;gap:20px}.contract-section{display:flex;flex-direction:column;gap:12px}.section-title{font-size:17px;font-weight:500}.section-text,.section-note{overflow-wrap:anywhere}.signature-image img{max-width:100%}
.card-item,.address-item{position:relative;z-index:1;border:1px solid #e9edef;border-radius:10px;padding:12px;background:white;overflow-wrap:anywhere}.swipe-item-wrapper{position:relative;overflow:hidden}.delete-button-wrapper{position:absolute;right:0;top:0;height:100%;display:flex;align-items:center}.delete-button{color:#9a3939}
.upload-area,.upload-box{background:#f5f6f7;border:1px solid #e9edef;border-radius:10px;padding:16px;min-height:84px}.upload-area img,.upload-box img,.id-image img{max-width:100%;object-fit:contain}
.empty-state,.loading-state{padding:28px 0;color:#707780;text-align:center}.status-badge,.status-text{color:#736582}.signature-canvas{width:100%;height:180px;touch-action:none;background:#f5f6f7}.image-preview img{max-width:100%;max-height:180px;object-fit:contain}

</style>
