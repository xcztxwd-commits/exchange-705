<script setup lang="ts">
import BusinessPage from '@/advanced/components/business/BusinessPage.vue'
const { request, error: advancedError, writing: advancedWriting } = useBusinessLifecycle()
import { ref, onMounted, computed } from 'vue'
import { useBusinessLifecycle } from '@/advanced/components/business/useBusinessLifecycle'
import { useLocaleStore } from '@/store/locale'
import { formatDateTime } from '@/utils/dateTime'


// 多语言
const localeStore = useLocaleStore()
localeStore.loadLocale()

const loading = ref(false)
const records = ref<any[]>([])
const recordType = ref('all')
const filteredRecords = computed(() => records.value.filter(r => recordType.value === 'all' || r.type === recordType.value))
const cancelling = ref<number | null>(null)
async function cancelDeposit(record: any) {
  if (cancelling.value !== null || !window.confirm(localeStore.text('取消尚未入账的充值申请？', 'Cancel this uncredited deposit request?'))) return
  cancelling.value = record.id
  try { await request.post('/deposit/cancel/' + record.id); await loadRecords() }
  catch (error: any) { window.alert(error.message || 'Unable to confirm the result. Check deposit history before retrying.') }
  finally { cancelling.value = null }
}

// 格式化金额
function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}


// 加载入金记录
async function loadRecords() {
  loading.value = true
  try {
    const res: any = await request.get('/deposit/records')
    if (res && res.success !== false) {
      records.value = res.list || res.data || []
    }
  } catch (e: any) {
    console.error('加载入金记录失败:', e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadRecords()
})
</script>

<template>
<BusinessPage :title="localeStore.t('depositRecords')" :error="advancedError" :busy="advancedWriting || loading"><div class="tabs"><button v-for="tab in [{value:'all',zh:'全部记录',en:'All'},{value:'digital',zh:'数字货币',en:'Crypto'},{value:'bank',zh:'银行汇款',en:'Bank'}]" :key="tab.value" :class="{active:recordType===tab.value}" @click="recordType=tab.value">{{ localeStore.text(tab.zh,tab.en) }}</button></div><p v-if="loading" class="empty">{{ localeStore.t('loading') }}</p><p v-else-if="!filteredRecords.length" class="empty">{{ advancedError ? localeStore.text('记录暂不可用','Records unavailable') : localeStore.t('noRecords') }}</p><section v-for="record in filteredRecords" :key="record.id" class="card"><div class="record-heading"><h2>{{ formatMoney(record.amount) }} USD</h2><span class="muted">{{ record.status==='CANCELLED' ? localeStore.text('已取消','Cancelled') : record.status==='PENDING' ? localeStore.t('statusPending') : record.status==='COMPLETED' ? localeStore.t('statusCompleted') : record.status==='REJECTED' ? localeStore.t('statusRejected') : record.status || localeStore.text('未知','Unknown') }}</span></div><div class="metrics"><div class="metric"><small>{{ localeStore.t('depositType') }}</small><span>{{ record.type==='digital' ? localeStore.t('depositTypeDigital') : localeStore.t('depositTypeBank') }}</span></div><div class="metric"><small>{{ localeStore.t('network') }}</small><span>{{ record.network || '—' }}</span></div><div class="metric"><small>{{ localeStore.t('unit') }}</small><span>{{ record.currency || 'USD' }}</span></div></div><div class="row"><span>{{ localeStore.t('remark') }}</span><span>{{ record.remark || '—' }}</span></div><div class="row"><span>{{ localeStore.text('提交时间','Submitted') }}</span><span>{{ formatDateTime(record.createdAt || record.createTime) }}</span></div><button v-if="record.status==='PENDING' && record.source==='USER_SUBMITTED'" :disabled="cancelling!==null || advancedWriting" @click="cancelDeposit(record)">{{ localeStore.text('取消申请','Cancel request') }}</button></section><button v-if="advancedError" @click="loadRecords">{{ localeStore.text('重试','Retry') }}</button></BusinessPage>
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

.record-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}
</style>
