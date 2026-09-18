<template>
  <div class="financial-orders-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>理财订单管理</span>
          <div>
            <el-input
              v-model="filterUserId"
              placeholder="用户ID"
              clearable
              style="width: 150px; margin-right: 10px"
              @keyup.enter="handleSearch"
            />
            <el-input
              v-model="filterUserEmail"
              placeholder="用户邮箱"
              clearable
              style="width: 200px; margin-right: 10px"
              @keyup.enter="handleSearch"
            />
            <el-select v-model="statusFilter" placeholder="筛选状态" clearable style="width: 150px; margin-right: 10px">
              <el-option label="全部" value="" />
              <el-option label="进行中" value="IN_PROGRESS" />
              <el-option label="已结束" value="COMPLETED" />
              <el-option label="已赎回" value="REDEEMED" />
            </el-select>
            <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          </div>
        </div>
      </template>

      <el-table :data="ordersList" style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="userId" label="用户ID" width="100" />
        <el-table-column prop="userRemark" label="用户备注" width="150">
          <template #default="{ row }">
            {{ row.userRemark || row.remark || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="productName" label="产品名称" width="150" />
        <el-table-column prop="purchaseAmount" label="申购数量" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.purchaseAmount) }}
          </template>
        </el-table-column>
        <el-table-column prop="currency" label="货币" width="80" />
        <el-table-column prop="dailyYield" label="预计日产" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.dailyYield) }}
          </template>
        </el-table-column>
        <el-table-column prop="totalYield" label="预计总收益" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.totalYield) }}
          </template>
        </el-table-column>
        <el-table-column prop="termDays" label="期限(天)" width="100" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="purchaseTime" label="申购时间" width="160">
          <template #default="{ row }">
            {{ formatDate(row.purchaseTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="endTime" label="结束时间" width="160">
          <template #default="{ row }">
            {{ formatDate(row.endTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="viewYieldList(row)">查看收益</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 收益列表对话框 -->
    <el-dialog
      v-model="yieldDialogVisible"
      title="收益列表"
      width="800px"
    >
      <div v-if="yieldStats" class="yield-stats">
        <el-row :gutter="20">
          <el-col :span="6">
            <div class="stat-item">
              <div class="stat-label">总收益</div>
              <div class="stat-value">{{ formatMoney(yieldStats.totalYield) }}</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-item">
              <div class="stat-label">已发放</div>
              <div class="stat-value success">{{ formatMoney(yieldStats.paidYield) }}</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-item">
              <div class="stat-label">待发放</div>
              <div class="stat-value warning">{{ formatMoney(yieldStats.pendingYield) }}</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-item">
              <div class="stat-label">收益天数</div>
              <div class="stat-value">{{ yieldStats.recordCount }}</div>
            </div>
          </el-col>
        </el-row>
      </div>

      <el-table :data="yieldList" style="width: 100%" v-loading="yieldLoading">
        <el-table-column prop="yieldDate" label="收益日期" width="120">
          <template #default="{ row }">
            {{ formatDate(row.yieldDate) }}
          </template>
        </el-table-column>
        <el-table-column prop="dailyYield" label="当日收益" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.dailyYield) }}
          </template>
        </el-table-column>
        <el-table-column prop="cumulativeYield" label="累计收益" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.cumulativeYield) }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'PAID' ? 'success' : 'warning'">
              {{ row.status === 'PAID' ? '已发放' : '待发放' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="paidAt" label="发放时间" width="160">
          <template #default="{ row }">
            {{ row.paidAt ? formatDate(row.paidAt) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING'"
              size="small"
              type="success"
              @click="payoutYield(row.id)"
            >
              发放
            </el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'

const ordersList = ref<any[]>([])
const loading = ref(false)
const statusFilter = ref('')
const filterUserId = ref('')
const filterUserEmail = ref('')
const yieldDialogVisible = ref(false)
const yieldList = ref<any[]>([])
const yieldStats = ref<any>(null)
const yieldLoading = ref(false)
const currentOrderId = ref<number | null>(null)

function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function formatDate(dateStr: string | null) {
  if (!dateStr) return '-'
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).replace(/\//g, '-')
}

function getStatusText(status: string) {
  const statusMap: Record<string, string> = {
    'IN_PROGRESS': '进行中',
    'COMPLETED': '已结束',
    'REDEEMED': '已赎回'
  }
  return statusMap[status] || status
}

function getStatusType(status: string) {
  const typeMap: Record<string, string> = {
    'IN_PROGRESS': 'warning',
    'COMPLETED': 'success',
    'REDEEMED': 'info'
  }
  return typeMap[status] || ''
}

async function loadList() {
  loading.value = true
  try {
    const params: any = {}
    if (statusFilter.value) {
      params.status = statusFilter.value
    }
    if (filterUserId.value) {
      params.userId = filterUserId.value
    }
    if (filterUserEmail.value) {
      params.userEmail = filterUserEmail.value
    }
    const res: any = await request.get('/admin/financial/orders', { params })
    if (res && res.success) {
      ordersList.value = res.list || []
    }
  } catch (e: any) {
    ElMessage.error('加载失败: ' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

// 搜索
const handleSearch = () => {
  loadList()
}

// 重置
const handleReset = () => {
  filterUserId.value = ''
  filterUserEmail.value = ''
  statusFilter.value = ''
  loadList()
}

// 查看收益列表
async function viewYieldList(row: any) {
  currentOrderId.value = row.id
  yieldDialogVisible.value = true
  yieldLoading.value = true
  
  try {
    const res: any = await request.get(`/admin/financial/yield/order/${row.id}`)
    if (res && res.success) {
      yieldList.value = res.list || []
      yieldStats.value = res.stats || null
    } else {
      ElMessage.error('加载收益列表失败')
    }
  } catch (e: any) {
    ElMessage.error('加载失败: ' + (e.message || '未知错误'))
  } finally {
    yieldLoading.value = false
  }
}

// 发放收益
async function payoutYield(yieldRecordId: number) {
  try {
    await ElMessageBox.confirm('确定要发放该收益吗？', '确认发放', {
      type: 'warning',
    })
    
    const res: any = await request.post(`/admin/financial/yield/payout/${yieldRecordId}`)
    if (res && res.success) {
      ElMessage.success('收益发放成功')
      // 重新加载收益列表
      if (currentOrderId.value) {
        viewYieldList({ id: currentOrderId.value })
      }
    } else {
      ElMessage.error('发放失败: ' + (res.message || '未知错误'))
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('发放失败: ' + (e.message || '未知错误'))
    }
  }
}

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.financial-orders-page {
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.yield-stats {
  margin-bottom: 20px;
  padding: 16px;
  background: #f5f7fa;
  border-radius: 4px;
}

.stat-item {
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
  color: #67c23a;
}

.stat-value.warning {
  color: #e6a23c;
}
</style>

