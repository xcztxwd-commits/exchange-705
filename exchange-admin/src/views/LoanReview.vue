<template>
  <div class="loan-review-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>贷款申请审核</span>
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
              <el-option label="待审核" value="PENDING" />
              <el-option label="已签约" value="SIGNED" />
              <el-option label="已批准" value="APPROVED" />
              <el-option label="已拒绝" value="REJECTED" />
              <el-option label="已完成" value="COMPLETED" />
              <el-option label="已逾期" value="OVERDUE" />
            </el-select>
            <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          </div>
        </div>
      </template>

      <el-table :data="loanList" style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="userId" label="用户ID" width="100" />
        <el-table-column prop="userRemark" label="用户备注" width="150">
          <template #default="{ row }">
            {{ row.userRemark || row.remark || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="realName" label="真实姓名" width="120" />
        <el-table-column prop="agentInfo" label="所属代理" width="150">
          <template #default="{ row }">
            {{ row.agentInfo || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="phone" label="电话" width="120" />
        <el-table-column prop="address" label="地址" min-width="150" show-overflow-tooltip />
        <el-table-column prop="amount" label="贷款金额" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.amount) }}
          </template>
        </el-table-column>
        <el-table-column prop="days" label="期限(天)" width="100" />
        <el-table-column prop="dailyRate" label="日利率(%)" width="100" />
        <el-table-column prop="freeDays" label="免息天数" width="100" />
        <el-table-column prop="totalInterest" label="总利息" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.totalInterest) }}
          </template>
        </el-table-column>
        <el-table-column prop="repaymentAmount" label="需还款" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.repaymentAmount) }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="contractSigned" label="已签约" width="100">
          <template #default="{ row }">
            <el-tag :type="row.contractSigned ? 'success' : 'info'">
              {{ row.contractSigned ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="还款情况" width="120">
          <template #default="{ row }">
            <el-tag :type="getRepaymentStatusType(row)">
              {{ getRepaymentStatusText(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="申请时间" width="160">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button 
              v-if="canViewLoanDetail"
              size="small" 
              type="primary" 
              @click="handleViewDetail(row)"
            >
              查看详情
            </el-button>
            <el-button 
              v-if="row.status === 'SIGNED' && canApproveLoan" 
              size="small" 
              type="success" 
              @click="handleApprove(row)"
            >
              通过
            </el-button>
            <el-button 
              v-if="(row.status === 'PENDING' || row.status === 'SIGNED') && canRejectLoan" 
              size="small" 
              type="danger" 
              @click="handleReject(row)"
            >
              拒绝
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 详情对话框 -->
    <el-dialog v-model="detailDialogVisible" title="贷款申请详情" width="800px">
      <div v-if="currentLoanDetail" class="loan-detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="申请ID">{{ currentLoanDetail.id }}</el-descriptions-item>
          <el-descriptions-item label="用户ID">{{ currentLoanDetail.userId }}</el-descriptions-item>
          <el-descriptions-item label="真实姓名" :span="2">{{ currentLoanDetail.realName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="身份证号" :span="2">{{ currentLoanDetail.idNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="电话" :span="2">{{ currentLoanDetail.phone || '-' }}</el-descriptions-item>
          <el-descriptions-item label="家庭住址" :span="2">{{ currentLoanDetail.address || '-' }}</el-descriptions-item>
          <el-descriptions-item label="贷款金额">{{ formatMoney(currentLoanDetail.amount) }}</el-descriptions-item>
          <el-descriptions-item label="贷款期限">{{ currentLoanDetail.days }} 天</el-descriptions-item>
          <el-descriptions-item label="日利率">{{ currentLoanDetail.dailyRate }}%</el-descriptions-item>
          <el-descriptions-item label="免息天数">{{ currentLoanDetail.freeDays }} 天</el-descriptions-item>
          <el-descriptions-item label="总利息">{{ formatMoney(currentLoanDetail.totalInterest) }}</el-descriptions-item>
          <el-descriptions-item label="需还款金额">{{ formatMoney(currentLoanDetail.repaymentAmount) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="getStatusType(currentLoanDetail.status)">
              {{ getStatusText(currentLoanDetail.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="已签约">
            <el-tag :type="currentLoanDetail.contractSigned ? 'success' : 'info'">
              {{ currentLoanDetail.contractSigned ? '是' : '否' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="申请时间" :span="2">{{ formatDate(currentLoanDetail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="批准时间" :span="2" v-if="currentLoanDetail.approvedAt">
            {{ formatDate(currentLoanDetail.approvedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="还款日期" :span="2" v-if="currentLoanDetail.repaymentDate">
            {{ formatDate(currentLoanDetail.repaymentDate) }}
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="2" v-if="currentLoanDetail.remark">
            {{ currentLoanDetail.remark }}
          </el-descriptions-item>
        </el-descriptions>
        
        <!-- 签名图片 -->
        <div v-if="currentLoanDetail.signatureImage" class="signature-section">
          <div class="section-title">签名图片</div>
          <el-image
            :src="getImageUrl(currentLoanDetail.signatureImage)"
            :preview-src-list="[getImageUrl(currentLoanDetail.signatureImage)]"
            style="max-width: 400px; max-height: 200px; cursor: pointer;"
            fit="contain"
          />
        </div>
      </div>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
        <el-button 
          v-if="currentLoanDetail && currentLoanDetail.status === 'SIGNED'" 
          type="success" 
          @click="handleApproveFromDetail"
        >
          批准
        </el-button>
        <el-button 
          v-if="currentLoanDetail && (currentLoanDetail.status === 'PENDING' || currentLoanDetail.status === 'SIGNED')" 
          type="danger" 
          @click="handleRejectFromDetail"
        >
          拒绝
        </el-button>
      </template>
    </el-dialog>

    <!-- 拒绝对话框 -->
    <el-dialog v-model="rejectDialogVisible" title="拒绝贷款申请" width="400px">
      <el-form>
        <el-form-item label="拒绝原因">
          <el-input
            v-model="rejectRemark"
            type="textarea"
            :rows="4"
            placeholder="请输入拒绝原因"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="confirmReject">确认拒绝</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { getImageUrl } from '@/utils/imageUrl'
import { usePermissions } from '@/composables/usePermissions'

const { hasPermission } = usePermissions()
const canViewLoanDetail = ref(true)
const canApproveLoan = ref(true)
const canRejectLoan = ref(true)

const loadPermissions = async () => {
  canViewLoanDetail.value = await hasPermission('loan-review', 'view_loan_detail') || await hasPermission('loan_review', 'view_loan_detail')
  canApproveLoan.value = await hasPermission('loan-review', 'approve_loan') || await hasPermission('loan_review', 'approve_loan')
  canRejectLoan.value = await hasPermission('loan-review', 'reject_loan') || await hasPermission('loan_review', 'reject_loan')
}

const loanList = ref<any[]>([])
const loading = ref(false)
const statusFilter = ref('')
const filterUserId = ref('')
const filterUserEmail = ref('')
const rejectDialogVisible = ref(false)
const rejectRemark = ref('')
const currentRejectLoan = ref<any>(null)
const detailDialogVisible = ref(false)
const currentLoanDetail = ref<any>(null)

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
    PENDING: '待审核',
    APPROVED: '已批准',
    SIGNED: '已签约',
    COMPLETED: '已完成',
    OVERDUE: '已逾期',
    REJECTED: '已拒绝',
  }
  return statusMap[status] || status
}

function getStatusType(status: string) {
  const typeMap: Record<string, string> = {
    PENDING: 'warning',
    APPROVED: 'success',
    SIGNED: 'primary',
    COMPLETED: 'success',
    OVERDUE: 'danger',
    REJECTED: 'info',
  }
  return typeMap[status] || ''
}

// 获取还款状态文本
function getRepaymentStatusText(row: any): string {
  // 如果已还款（状态为COMPLETED或有实际还款时间）
  if (row.status === 'COMPLETED' || row.actualRepaymentAt) {
    return '已还款'
  }
  
  // 如果已批准或已签约，检查是否逾期
  if (row.status === 'APPROVED' || row.status === 'SIGNED') {
    if (row.repaymentDate) {
      const repaymentDate = new Date(row.repaymentDate)
      const now = new Date()
      if (now > repaymentDate) {
        return '已逾期'
      }
    }
    return '未还款'
  }
  
  // 其他状态（待审核、已拒绝等）显示未还款
  return '未还款'
}

// 获取还款状态类型
function getRepaymentStatusType(row: any): string {
  const status = getRepaymentStatusText(row)
  if (status === '已还款') return 'success'
  if (status === '已逾期') return 'danger'
  return 'warning'
}

async function loadLoans() {
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
    const res: any = await request.get('/admin/loan/review/list', { params })
    if (res && res.success) {
      loanList.value = res.list || []
    }
  } catch (e: any) {
    ElMessage.error('加载失败: ' + (e.message || '未知错误'))
  } finally {
    loading.value = false
  }
}

// 搜索
const handleSearch = () => {
  loadLoans()
}

// 重置
const handleReset = () => {
  filterUserId.value = ''
  filterUserEmail.value = ''
  statusFilter.value = ''
  loadLoans()
}

function handleApprove(row: any) {
  ElMessageBox.confirm('确定要批准这条贷款申请吗？', '确认批准', {
    type: 'warning',
  })
    .then(() => {
      return request.post(`/admin/loan/review/approve/${row.id}`)
    })
    .then((res: any) => {
      if (res && res.success) {
        ElMessage.success('批准成功')
        loadLoans()
      }
    })
    .catch((e: any) => {
      if (e !== 'cancel') {
        ElMessage.error('批准失败: ' + (e.message || '未知错误'))
      }
    })
}

function handleReject(row: any) {
  currentRejectLoan.value = row
  rejectRemark.value = ''
  rejectDialogVisible.value = true
}

function confirmReject() {
  if (!currentRejectLoan.value) return

  request
    .post(`/admin/loan/review/reject/${currentRejectLoan.value.id}`, {
      remark: rejectRemark.value,
    })
    .then((res: any) => {
      if (res && res.success) {
        ElMessage.success('拒绝成功')
        rejectDialogVisible.value = false
        detailDialogVisible.value = false
        loadLoans()
      }
    })
    .catch((e: any) => {
      ElMessage.error('拒绝失败: ' + (e.message || '未知错误'))
    })
}

function handleViewDetail(row: any) {
  currentLoanDetail.value = row
  detailDialogVisible.value = true
}

function handleApproveFromDetail() {
  if (!currentLoanDetail.value) return
  handleApprove(currentLoanDetail.value)
  detailDialogVisible.value = false
}

function handleRejectFromDetail() {
  if (!currentLoanDetail.value) return
  currentRejectLoan.value = currentLoanDetail.value
  rejectRemark.value = ''
  rejectDialogVisible.value = true
}

onMounted(() => {
  loadLoans()
  loadPermissions()
})
</script>

<style scoped>
.loan-review-page {
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.loan-detail {
  padding: 10px 0;
}

.signature-section {
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #eee;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  margin-bottom: 12px;
}
</style>

