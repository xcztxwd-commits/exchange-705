<template>
  <div class="kyc-review-page">
    <div class="page-header">
      <h2>实名认证审核</h2>
    </div>

    <!-- 搜索筛选 -->
    <div class="search-section">
      <el-form :inline="true" :model="queryForm" class="search-form">
        <el-form-item label="用户ID">
          <el-input
            v-model="queryForm.userId"
            placeholder="用户ID"
            clearable
            style="width: 150px"
            @keyup.enter="loadList"
          />
        </el-form-item>
        <el-form-item label="用户邮箱">
          <el-input
            v-model="queryForm.userEmail"
            placeholder="用户邮箱"
            clearable
            style="width: 200px"
            @keyup.enter="loadList"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryForm.status" placeholder="全部" clearable style="width: 150px">
            <el-option label="待审核" value="PENDING" />
            <el-option label="已通过" value="APPROVED" />
            <el-option label="已拒绝" value="REJECTED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="el-icon-search" @click="loadList">搜索</el-button>
          <el-button icon="el-icon-refresh" @click="resetSearch">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 列表 -->
    <div class="table-section">
      <el-table :data="list" border stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="userId" label="用户ID" width="100" />
        <el-table-column prop="userRemark" label="用户备注" width="150">
          <template #default="{ row }">
            {{ row.userRemark || row.remark || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="realName" label="姓名" width="120" />
        <el-table-column prop="idNumber" label="证件号" width="180" />
        <el-table-column prop="agentInfo" label="所属代理" width="150">
          <template #default="{ row }">
            {{ row.agentInfo || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'PENDING'" type="warning">待审核</el-tag>
            <el-tag v-else-if="row.status === 'APPROVED'" type="success">已通过</el-tag>
            <el-tag v-else-if="row.status === 'REJECTED'" type="danger">已拒绝</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="证件正面" width="120">
          <template #default="{ row }">
            <el-image
              v-if="row.idFrontImage"
              :src="getImageUrl(row.idFrontImage)"
              :preview-src-list="getPreviewImageList(row)"
              style="width: 80px; height: 60px; cursor: pointer; border: 1px solid #eee;"
              fit="cover"
              :initial-index="0"
              :hide-on-click-modal="true"
              :preview-teleported="true"
              loading="lazy"
            >
              <template #error>
                <div class="image-slot" style="display: flex; justify-content: center; align-items: center; width: 100%; height: 100%; background: #f5f7fa; color: #909399;">
                  <span>加载失败</span>
                </div>
              </template>
            </el-image>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="证件反面" width="120">
          <template #default="{ row }">
            <el-image
              v-if="row.idBackImage"
              :src="getImageUrl(row.idBackImage)"
              :preview-src-list="getPreviewImageList(row)"
              style="width: 80px; height: 60px; cursor: pointer; border: 1px solid #eee;"
              fit="cover"
              :initial-index="row.idFrontImage ? 1 : 0"
              :hide-on-click-modal="true"
              :preview-teleported="true"
              loading="lazy"
            >
              <template #error>
                <div class="image-slot" style="display: flex; justify-content: center; align-items: center; width: 100%; height: 100%; background: #f5f7fa; color: #909399;">
                  <span>加载失败</span>
                </div>
              </template>
            </el-image>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="reviewRemark" label="审核备注" min-width="150" />
        <el-table-column prop="createdAt" label="申请时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="reviewedAt" label="审核时间" width="180">
          <template #default="{ row }">
            {{ row.reviewedAt ? formatDateTime(row.reviewedAt) : '-' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING' && canApproveKyc"
              type="success"
              size="small"
              @click="handleApprove(row)"
            >
              通过
            </el-button>
            <el-button
              v-if="row.status === 'PENDING' && canRejectKyc"
              type="danger"
              size="small"
              @click="handleReject(row)"
            >
              拒绝
            </el-button>
            <span v-if="row.status !== 'PENDING'">-</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 分页 -->
    <div class="pagination-section">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="size"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="loadList"
        @current-change="loadList"
      />
    </div>

    <!-- 拒绝对话框 -->
    <el-dialog
      v-model="rejectDialogVisible"
      title="拒绝原因"
      width="500px"
    >
      <el-form :model="rejectForm" label-width="100px">
        <el-form-item label="拒绝原因" required>
          <el-input
            v-model="rejectForm.remark"
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
import { usePermissions } from '@/composables/usePermissions'

const { hasPermission } = usePermissions()
const canApproveKyc = ref(true)
const canRejectKyc = ref(true)

const loadPermissions = async () => {
  canApproveKyc.value = await hasPermission('kyc-review', 'approve_kyc') || await hasPermission('kyc_review', 'approve_kyc')
  canRejectKyc.value = await hasPermission('kyc-review', 'reject_kyc') || await hasPermission('kyc_review', 'reject_kyc')
}
import request from '@/utils/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getImageUrl } from '@/utils/imageUrl'

const loading = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

const queryForm = ref({
  status: '',
  userId: '',
  userEmail: '',
})

// 拒绝对话框
const rejectDialogVisible = ref(false)
const rejectForm = ref({
  remark: '',
})
const currentRejectId = ref<number | null>(null)

// 加载列表
async function loadList() {
  loading.value = true
  try {
    const params: any = {
      page: page.value - 1,
      size: size.value,
    }
    if (queryForm.value.status) {
      params.status = queryForm.value.status
    }
    if (queryForm.value.userId) {
      params.userId = queryForm.value.userId
    }
    if (queryForm.value.userEmail) {
      params.userEmail = queryForm.value.userEmail
    }
    
    const res: any = await request.get('/admin/kyc/list', { params })
    
    if (res && res.success !== false) {
      list.value = res.list || []
      total.value = res.total || 0
    }
  } catch (e: any) {
    console.error('加载列表失败:', e)
    ElMessage.error(e.response?.data?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

// 重置搜索
function resetSearch() {
  queryForm.value = {
    status: '',
    userId: '',
    userEmail: '',
  }
  page.value = 1
  loadList()
}

// 格式化日期时间
function formatDateTime(dateTime: string) {
  if (!dateTime) return '-'
  return new Date(dateTime).toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

// getImageUrl 函数已从 @/utils/imageUrl 导入

// 获取预览图片列表（包含正面和反面）
function getPreviewImageList(row: any): string[] {
  const images: string[] = []
  if (row.idFrontImage) {
    const frontUrl = getImageUrl(row.idFrontImage)
    // 确保URL有效且不是空字符串
    if (frontUrl && frontUrl.trim() !== '') {
      images.push(frontUrl)
    }
  }
  if (row.idBackImage) {
    const backUrl = getImageUrl(row.idBackImage)
    // 确保URL有效且不是空字符串
    if (backUrl && backUrl.trim() !== '') {
      images.push(backUrl)
    }
  }
  // 确保至少返回一个有效的URL，如果没有图片则返回空数组
  return images.length > 0 ? images : []
}

// 审核通过
async function handleApprove(row: any) {
  try {
    await ElMessageBox.confirm('确认通过该用户的实名认证申请吗？', '确认', {
      type: 'warning',
    })
    
    const res: any = await request.post(`/admin/kyc/${row.id}/approve`)
    
    if (res && res.success !== false) {
      ElMessage.success('审核通过')
      loadList()
    } else {
      ElMessage.error(res.message || '操作失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      console.error('审核失败:', e)
      ElMessage.error(e.response?.data?.message || '操作失败')
    }
  }
}

// 审核拒绝
function handleReject(row: any) {
  currentRejectId.value = row.id
  rejectForm.value.remark = ''
  rejectDialogVisible.value = true
}

// 确认拒绝
async function confirmReject() {
  if (!rejectForm.value.remark || !rejectForm.value.remark.trim()) {
    ElMessage.warning('请输入拒绝原因')
    return
  }
  
  if (!currentRejectId.value) return
  
  try {
    const res: any = await request.post(`/admin/kyc/${currentRejectId.value}/reject`, {
      remark: rejectForm.value.remark.trim(),
    })
    
    if (res && res.success !== false) {
      ElMessage.success('已拒绝')
      rejectDialogVisible.value = false
      loadList()
    } else {
      ElMessage.error(res.message || '操作失败')
    }
  } catch (e: any) {
    console.error('拒绝失败:', e)
    ElMessage.error(e.response?.data?.message || '操作失败')
  }
}

onMounted(() => {
  loadList()
  loadPermissions()
})
</script>

<style scoped>
.kyc-review-page {
  padding: 20px;
}

.page-header {
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.search-section {
  background: #fff;
  padding: 20px;
  border-radius: 8px;
  margin-bottom: 20px;
}

.search-form {
  margin: 0;
}

.table-section {
  background: #fff;
  padding: 20px;
  border-radius: 8px;
  margin-bottom: 20px;
}

.pagination-section {
  display: flex;
  justify-content: flex-end;
  background: #fff;
  padding: 20px;
  border-radius: 8px;
}
</style>

