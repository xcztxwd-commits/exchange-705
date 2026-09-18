<template>
  <div class="loan-personal-info-review-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>贷款个人信息审核</span>
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
              <el-option label="已通过" value="APPROVED" />
              <el-option label="已拒绝" value="REJECTED" />
            </el-select>
            <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          </div>
        </div>
      </template>

      <el-table :data="infoList" style="width: 100%" v-loading="loading">
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
        <el-table-column prop="idNumber" label="身份证号" width="180" />
        <el-table-column prop="phone" label="电话" width="120" />
        <el-table-column prop="address" label="地址" min-width="200" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="申请时间" width="160">
          <template #default="{ row }">
            {{ formatDate(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="reviewedAt" label="审核时间" width="160">
          <template #default="{ row }">
            {{ formatDate(row.reviewedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button 
              size="small" 
              type="primary" 
              @click="handleViewDetail(row)"
            >
              查看详情
            </el-button>
            <el-button 
              v-if="row.status === 'PENDING'" 
              size="small" 
              type="success" 
              @click="handleApprove(row)"
            >
              通过
            </el-button>
            <el-button 
              v-if="row.status === 'PENDING'" 
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
    <el-dialog v-model="detailDialogVisible" title="个人信息详情" width="600px">
      <div v-if="currentDetail" class="info-detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="申请ID">{{ currentDetail.id }}</el-descriptions-item>
          <el-descriptions-item label="用户ID">{{ currentDetail.userId }}</el-descriptions-item>
          <el-descriptions-item label="真实姓名">{{ currentDetail.realName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="身份证号">{{ currentDetail.idNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="电话">{{ currentDetail.phone || '-' }}</el-descriptions-item>
          <el-descriptions-item label="家庭住址">{{ currentDetail.address || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="getStatusType(currentDetail.status)">
              {{ getStatusText(currentDetail.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="申请时间">{{ formatDate(currentDetail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="审核时间" v-if="currentDetail.reviewedAt">
            {{ formatDate(currentDetail.reviewedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="审核备注" v-if="currentDetail.reviewRemark">
            {{ currentDetail.reviewRemark }}
          </el-descriptions-item>
        </el-descriptions>
        
        <!-- 身份证图片 -->
        <div v-if="currentDetail.idFrontImage || currentDetail.idBackImage" class="id-images-section">
          <div class="section-title">身份证图片</div>
          <div class="id-images">
            <div v-if="currentDetail.idFrontImage" class="id-image-item">
              <div class="image-label">身份证正面</div>
              <el-image
                :src="getImageUrl(currentDetail.idFrontImage)"
                :preview-src-list="[getImageUrl(currentDetail.idFrontImage)]"
                style="width: 100%; max-width: 400px; cursor: pointer;"
                fit="contain"
              />
            </div>
            <div v-if="currentDetail.idBackImage" class="id-image-item">
              <div class="image-label">身份证反面</div>
              <el-image
                :src="getImageUrl(currentDetail.idBackImage)"
                :preview-src-list="[getImageUrl(currentDetail.idBackImage)]"
                style="width: 100%; max-width: 400px; cursor: pointer;"
                fit="contain"
              />
            </div>
            <div v-if="currentDetail.handheldImage" class="id-image-item">
              <div class="image-label">手持身份证</div>
              <el-image
                :src="getImageUrl(currentDetail.handheldImage)"
                :preview-src-list="[getImageUrl(currentDetail.handheldImage)]"
                style="width: 100%; max-width: 400px; cursor: pointer;"
                fit="contain"
              />
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
        <el-button 
          v-if="currentDetail && currentDetail.status === 'PENDING'" 
          type="success" 
          @click="handleApproveFromDetail"
        >
          通过
        </el-button>
        <el-button 
          v-if="currentDetail && currentDetail.status === 'PENDING'" 
          type="danger" 
          @click="handleRejectFromDetail"
        >
          拒绝
        </el-button>
      </template>
    </el-dialog>

    <!-- 拒绝对话框 -->
    <el-dialog v-model="rejectDialogVisible" title="拒绝个人信息申请" width="400px">
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

const infoList = ref<any[]>([])
const loading = ref(false)
const statusFilter = ref('')
const filterUserId = ref('')
const filterUserEmail = ref('')
const rejectDialogVisible = ref(false)
const rejectRemark = ref('')
const currentRejectInfo = ref<any>(null)
const detailDialogVisible = ref(false)
const currentDetail = ref<any>(null)

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
    APPROVED: '已通过',
    REJECTED: '已拒绝',
  }
  return statusMap[status] || status
}

function getStatusType(status: string) {
  const typeMap: Record<string, string> = {
    PENDING: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger',
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
    const res: any = await request.get('/admin/loan/personal-info/list', { params })
    if (res && res.success) {
      infoList.value = res.list || []
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

function handleViewDetail(row: any) {
  currentDetail.value = row
  detailDialogVisible.value = true
}

function handleApprove(row: any) {
  ElMessageBox.confirm('确定要通过这条个人信息申请吗？', '确认通过', {
    type: 'warning',
  })
    .then(() => {
      return request.post(`/admin/loan/personal-info/approve/${row.id}`)
    })
    .then((res: any) => {
      if (res && res.success) {
        ElMessage.success('审核通过')
        detailDialogVisible.value = false
        loadList()
      }
    })
    .catch((e: any) => {
      if (e !== 'cancel') {
        ElMessage.error('操作失败: ' + (e.message || '未知错误'))
      }
    })
}

function handleApproveFromDetail() {
  if (!currentDetail.value) return
  handleApprove(currentDetail.value)
}

function handleReject(row: any) {
  currentRejectInfo.value = row
  rejectRemark.value = ''
  rejectDialogVisible.value = true
}

function handleRejectFromDetail() {
  if (!currentDetail.value) return
  handleReject(currentDetail.value)
}

function confirmReject() {
  if (!currentRejectInfo.value) return

  request
    .post(`/admin/loan/personal-info/reject/${currentRejectInfo.value.id}`, {
      remark: rejectRemark.value,
    })
    .then((res: any) => {
      if (res && res.success) {
        ElMessage.success('拒绝成功')
        rejectDialogVisible.value = false
        detailDialogVisible.value = false
        loadList()
      }
    })
    .catch((e: any) => {
      ElMessage.error('操作失败: ' + (e.message || '未知错误'))
    })
}

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.loan-personal-info-review-page {
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.info-detail {
  padding: 10px 0;
}

.id-images-section {
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

.id-images {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.id-image-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.image-label {
  font-size: 12px;
  color: #666;
}
</style>

