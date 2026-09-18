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

const loading = ref(false)
const records = ref<any[]>([])

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
  <div class="records-page">
    <!-- 顶部导航 -->
    <div class="records-header">
      <div class="back-button" @click="router.back()">
        <svg t="1767810682938" class="icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" width="24" height="24">
          <path d="M768 903.232l-50.432 56.768L256 512l461.568-448 50.432 56.768L364.928 512z" fill="#333"></path>
        </svg>
      </div>
      <div class="header-title">{{ localeStore.t('depositRecords') }}</div>
      <div class="header-placeholder"></div>
    </div>

    <!-- 加载状态 -->
    <div v-if="loading" class="loading-state">
      <div class="loading-text">{{ localeStore.t('loading') }}</div>
    </div>

    <!-- 记录列表 -->
    <div v-else class="records-list">
      <div v-if="records.length === 0" class="empty-state">
        <div class="empty-text">{{ localeStore.t('noRecords') }}</div>
      </div>
      <div 
        v-for="record in records" 
        :key="record.id"
        class="record-card"
      >
        <div class="record-row">
          <span class="record-label">{{ localeStore.t('depositAmountLabel') }}</span>
          <span class="record-value">{{ formatMoney(record.amount) }}</span>
        </div>
        <div class="record-row">
          <span class="record-label">{{ localeStore.t('depositType') }}</span>
          <span class="record-value">{{ record.type === 'digital' ? localeStore.t('depositTypeDigital') : localeStore.t('depositTypeBank') }}</span>
        </div>
        <div class="record-row">
          <span class="record-label">{{ localeStore.t('status') }}</span>
          <span class="record-value" :class="{
            'status-pending': record.status === 'PENDING',
            'status-completed': record.status === 'COMPLETED',
            'status-rejected': record.status === 'REJECTED'
          }">
            {{ record.status === 'PENDING' ? localeStore.t('statusPending') : record.status === 'COMPLETED' ? localeStore.t('statusCompleted') : localeStore.t('statusRejected') }}
          </span>
        </div>
        <div class="record-row" v-if="record.remark">
          <span class="record-label">{{ localeStore.t('remark') }}</span>
          <span class="record-value">{{ record.remark }}</span>
        </div>
        <div class="record-row">
          <span class="record-label">{{ localeStore.t('unit') }}</span>
          <span class="record-value">{{ record.network || record.unit || '-' }}</span>
        </div>
        <div class="record-row">
          <span class="record-label">{{ localeStore.t('time') }}</span>
          <span class="record-value">{{ formatDateTime(record.createdAt || record.createTime) }}</span>
        </div>
      </div>
    </div>

    <Tabbar />
  </div>
</template>

<style scoped>
.records-page {
  min-height: 100vh;
  background: #f5f7fb;
  padding-bottom: 80px;
}

.records-header {
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

.loading-state {
  padding: 40px;
  text-align: center;
}

.loading-text {
  color: #999;
  font-size: 14px;
}

.records-list {
  padding: 16px;
}

.empty-state {
  padding: 60px 20px;
  text-align: center;
}

.empty-text {
  color: #999;
  font-size: 14px;
}

.record-card {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.record-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.record-row:last-child {
  margin-bottom: 0;
}

.record-label {
  font-size: 14px;
  color: #666;
}

.record-value {
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.status-pending {
  color: #ff9800;
}

.status-completed {
  color: #73b100;
}

.status-rejected {
  color: #f56c6c;
}
</style>

