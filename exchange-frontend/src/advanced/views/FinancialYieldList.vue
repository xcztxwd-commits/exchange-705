<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting, setTimeout } = useBusinessLifecycle()
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
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
<BusinessPage :title="localeStore.t('yieldList')" :error="advancedError" :busy="advancedWriting">
<div class="page-content" v-if="!loading">
      <!-- 收益统计 -->
      <div v-if="stats" class="yield-stats card">
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
