<template>
  <div class="deposit-review-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>充值审核</span>
          <div class="filter-section">
            <!-- 代理筛选（只有管理员能看到） -->
            <el-select 
              v-if="!isAgent"
              v-model="filterAgentId" 
              placeholder="筛选代理" 
              clearable
              style="width: 200px; margin-right: 10px"
            >
              <el-option label="全部代理" value="" />
              <el-option 
                v-for="agent in agentList" 
                :key="agent.id" 
                :label="agent.name" 
                :value="agent.id"
              />
            </el-select>
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
            <el-select v-model="filterStatus" placeholder="筛选状态" clearable style="width: 150px; margin-right: 10px">
              <el-option label="全部" value="" />
              <el-option label="未审核" value="PENDING" />
              <el-option label="已完成" value="COMPLETED" />
              <el-option label="已拒绝" value="REJECTED" />
            </el-select>
            <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          </div>
        </div>
      </template>

      <el-table :data="recordsList" style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="userId" label="用户ID" width="200">
          <template #default="{ row }">
            <span v-if="row.agentInfo">{{ row.userId }}({{ row.agentInfo }})</span>
            <span v-else>{{ row.userId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="userRemark" label="用户备注" width="150">
          <template #default="{ row }">
            {{ row.userRemark || row.remark || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="type" label="类型" width="100">
          <template #default="{ row }">
            {{ row.type === 'digital' ? '数字货币' : '银行卡' }}
          </template>
        </el-table-column>
        <el-table-column prop="network" label="网络/币种" width="150" />
        <el-table-column prop="amount" label="充值金额" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.amount) }}
          </template>
        </el-table-column>
        <el-table-column prop="address" label="充值地址" min-width="200" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="proofImage" label="凭证" width="100">
          <template #default="{ row }">
            <el-image
              v-if="row.proofImage"
              :src="getImageUrl(row.proofImage)"
              :preview-src-list="getPreviewImageList(row)"
              style="width: 60px; height: 60px; cursor: pointer; border: 1px solid #eee;"
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
        <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="提交时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING' && hasPermission('deposit_review', 'approve_deposit')"
              size="small"
              type="success"
              @click="handleApprove(row)"
            >
              审核通过
            </el-button>
            <el-button
              v-if="row.status === 'PENDING' && hasPermission('deposit_review', 'reject_deposit')"
              size="small"
              type="danger"
              @click="handleReject(row)"
            >
              拒绝
            </el-button>
            <el-button
              v-if="row.status !== 'PENDING'"
              size="small"
              type="primary"
              @click="handleViewDetail(row)"
            >
              详细
            </el-button>
            <span v-else-if="row.status === 'PENDING' && !hasPermission('deposit_review', 'approve_deposit') && !hasPermission('deposit_review', 'reject_deposit')" class="no-action">待处理</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 拒绝对话框 -->
    <el-dialog
      v-model="rejectDialogVisible"
      title="拒绝充值申请"
      width="400px"
    >
      <el-form :model="rejectForm" label-width="80px">
        <el-form-item label="拒绝原因">
          <el-input
            v-model="rejectForm.remark"
            type="textarea"
            :rows="4"
            placeholder="请输入拒绝原因（可选）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="confirmReject">确认拒绝</el-button>
      </template>
    </el-dialog>

    <!-- 详细对话框（代理账号使用） -->
    <el-dialog
      v-model="detailDialogVisible"
      title="充值记录详情"
      width="800px"
    >
      <el-descriptions v-if="detailRecord" :column="2" border>
        <el-descriptions-item label="记录ID">{{ detailRecord.id }}</el-descriptions-item>
        <el-descriptions-item label="用户ID">{{ detailRecord.userId }}</el-descriptions-item>
        <el-descriptions-item label="类型">
          {{ detailRecord.type === 'digital' ? '数字货币' : '银行卡' }}
        </el-descriptions-item>
        <el-descriptions-item label="网络/币种">{{ detailRecord.network }}</el-descriptions-item>
        <el-descriptions-item label="充值金额">
          <span style="font-size: 16px; font-weight: bold; color: #409eff;">
            {{ formatMoney(detailRecord.amount) }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(detailRecord.status)">
            {{ getStatusText(detailRecord.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="充值地址" :span="2">
          {{ detailRecord.address || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="凭证" :span="2">
          <el-image
            v-if="detailRecord.proofImage"
            :src="getImageUrl(detailRecord.proofImage)"
            :preview-src-list="getPreviewImageList(detailRecord)"
            style="width: 200px; height: 200px; cursor: pointer; border: 1px solid #eee;"
            fit="contain"
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
        </el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">
          {{ detailRecord.remark || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="提交时间">
          {{ formatDateTime(detailRecord.createdAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="更新时间">
          {{ formatDateTime(detailRecord.updatedAt) }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { getImageUrl } from '@/utils/imageUrl'
import { useAuthStore } from '@/store/auth'

const auth = useAuthStore()
// 判断当前登录用户是否是代理
const isAgent = computed(() => auth.user?.userType === 'agent')

// 权限相关
const userPermissions = ref<Map<string, string[]>>(new Map())
const permissionsLoaded = ref(false)

// 检查是否有操作权限
const hasPermission = (menuCode: string, actionCode: string): boolean => {
  if (auth.user?.isSuperAdmin || auth.user?.role === 'super_admin') {
    return true
  }
  if (!isAgent.value) {
    return true
  }
  if (!permissionsLoaded.value) {
    return false
  }
  const actions = userPermissions.value.get(menuCode) || []
  return actions.includes(actionCode)
}

// 加载权限信息
const loadPermissions = async () => {
  if (!isAgent.value) return
  try {
    const userId = auth.user?.id
    if (userId) {
      const permissionsResponse: any = await request.get(`/admin/users/${userId}/menus`)
      console.log('[DepositReview] 权限API响应:', permissionsResponse)
      
      if (permissionsResponse.success) {
        const allMenus: any = await request.get('/admin/menus/list')
        console.log('[DepositReview] 所有菜单列表:', allMenus)
        
        if (allMenus.success) {
          const menuMap = new Map<number, string>()
          allMenus.list.forEach((m: any) => {
            menuMap.set(m.id, m.menuCode)
          })
          console.log('[DepositReview] 菜单映射表:', menuMap)
          
          const actions = permissionsResponse.actions || {}
          console.log('[DepositReview] 操作权限数据:', actions)
          
          userPermissions.value = new Map()
          // 处理actions对象，key可能是字符串或数字
          for (const [menuIdKey, actionCodes] of Object.entries(actions)) {
            let menuId: number
            if (typeof menuIdKey === 'string') {
              menuId = parseInt(menuIdKey, 10)
            } else {
              menuId = Number(menuIdKey)
            }
            
            const menuCode = menuMap.get(menuId)
            console.log(`[DepositReview] 菜单ID ${menuId} -> 菜单代码 ${menuCode}, 操作:`, actionCodes)
            
            if (menuCode && Array.isArray(actionCodes)) {
              // 同时支持两种菜单代码格式
              userPermissions.value.set(menuCode, actionCodes as string[])
              // 如果是 deposit-review，也添加到 deposit_review
              if (menuCode === 'deposit-review') {
                userPermissions.value.set('deposit_review', actionCodes as string[])
              }
              // 如果是 deposit_review，也添加到 deposit-review
              if (menuCode === 'deposit_review') {
                userPermissions.value.set('deposit-review', actionCodes as string[])
              }
            }
          }
          
          console.log('[DepositReview] 最终权限Map:', Array.from(userPermissions.value.entries()))
          permissionsLoaded.value = true
        }
      }
    }
  } catch (error: any) {
    console.error('[DepositReview] 加载权限失败:', error)
    permissionsLoaded.value = true
  }
}

const loading = ref(false)
const recordsList = ref<any[]>([])
const filterStatus = ref('')
const filterAgentId = ref<number | null>(null)
const filterUserId = ref('')
const filterUserEmail = ref('')
const agentList = ref<any[]>([])

const rejectDialogVisible = ref(false)
const rejectForm = ref({
  recordId: null as number | null,
  remark: ''
})

// 格式化金额
function formatMoney(v: number | string | undefined | null) {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// 格式化日期时间
function formatDateTime(dateTime: string | null | undefined): string {
  if (!dateTime) return ''
  try {
    const date = new Date(dateTime)
    const year = date.getFullYear()
    const month = String(date.getMonth() + 1).padStart(2, '0')
    const day = String(date.getDate()).padStart(2, '0')
    const hours = String(date.getHours()).padStart(2, '0')
    const minutes = String(date.getMinutes()).padStart(2, '0')
    const seconds = String(date.getSeconds()).padStart(2, '0')
    return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
  } catch (e) {
    return dateTime
  }
}

// 获取预览图片列表
function getPreviewImageList(row: any): string[] {
  const images: string[] = []
  if (row.proofImage) {
    const imageUrl = getImageUrl(row.proofImage)
    // 确保URL有效且不是空字符串
    if (imageUrl && imageUrl.trim() !== '') {
      images.push(imageUrl)
    }
  }
  // 确保至少返回一个有效的URL，如果没有图片则返回空数组
  return images.length > 0 ? images : []
}

// 获取状态类型
function getStatusType(status: string) {
  switch (status) {
    case 'PENDING':
      return 'warning'
    case 'COMPLETED':
      return 'success'
    case 'REJECTED':
      return 'danger'
    default:
      return ''
  }
}

// 获取状态文本
function getStatusText(status: string) {
  switch (status) {
    case 'PENDING':
      return '未审核'
    case 'COMPLETED':
      return '已完成'
    case 'REJECTED':
      return '已拒绝'
    default:
      return status
  }
}

// 加载代理列表（只有管理员需要）
async function loadAgents() {
  if (isAgent.value) return // 代理不需要加载代理列表
  
  try {
    const res: any = await request.get('/admin/users/agents/simple')
    if (res && res.success !== false) {
      agentList.value = res.list || []
    }
  } catch (e: any) {
    console.error('加载代理列表失败:', e)
  }
}

// 加载充值记录
async function loadRecords() {
  loading.value = true
  try {
    const params: any = {}
    if (filterStatus.value) {
      params.status = filterStatus.value
    }
    if (filterUserId.value) {
      params.userId = filterUserId.value
    }
    if (filterUserEmail.value) {
      params.userEmail = filterUserEmail.value
    }
    // 如果是管理员且选择了代理筛选，传递代理ID
    if (!isAgent.value && filterAgentId.value) {
      params.filterAgentId = filterAgentId.value
    }
    const res: any = await request.get('/admin/deposit/review/list', { params })
    if (res && res.success !== false) {
      recordsList.value = res.list || []
    }
  } catch (e: any) {
    console.error('加载充值记录失败:', e)
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

// 搜索
const handleSearch = () => {
  loadRecords()
}

// 重置
const handleReset = () => {
  filterUserId.value = ''
  filterUserEmail.value = ''
  filterStatus.value = ''
  if (!isAgent.value) {
    filterAgentId.value = null
  }
  loadRecords()
}

// 审核通过
async function handleApprove(row: any) {
  try {
    await ElMessageBox.confirm(
      `确定要通过该充值申请吗？\n用户ID: ${row.userId}\n充值金额: ${formatMoney(row.amount)}\n\n审核通过后，金额将直接充值到用户的资金账户。`,
      '确认审核通过',
      {
        type: 'warning',
        confirmButtonText: '确认通过',
        cancelButtonText: '取消'
      }
    )

    const res: any = await request.post(`/admin/deposit/review/approve/${row.id}`)
    if (res && res.success !== false) {
      ElMessage.success('审核通过，已充值到用户账户')
      loadRecords()
    } else {
      ElMessage.error(res.message || '审核失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      console.error('审核失败:', e)
      ElMessage.error(e.response?.data?.message || e.message || '审核失败')
    }
  }
}

// 拒绝
function handleReject(row: any) {
  rejectForm.value.recordId = row.id
  rejectForm.value.remark = ''
  rejectDialogVisible.value = true
}

// 确认拒绝
async function confirmReject() {
  if (!rejectForm.value.recordId) {
    return
  }

  try {
    const res: any = await request.post(`/admin/deposit/review/reject/${rejectForm.value.recordId}`, {
      remark: rejectForm.value.remark
    })
    if (res && res.success !== false) {
      ElMessage.success('已拒绝该充值申请')
      rejectDialogVisible.value = false
      loadRecords()
    } else {
      ElMessage.error(res.message || '操作失败')
    }
  } catch (e: any) {
    console.error('拒绝失败:', e)
    ElMessage.error(e.response?.data?.message || e.message || '操作失败')
  }
}

// 查看详细（代理账号使用）
const detailDialogVisible = ref(false)
const detailRecord = ref<any>(null)

function handleViewDetail(row: any) {
  detailRecord.value = row
  detailDialogVisible.value = true
}

onMounted(() => {
  loadAgents()
  loadRecords()
  loadPermissions()
})
</script>

<style scoped>
.deposit-review-page {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filter-section {
  display: flex;
  gap: 12px;
}

.no-action {
  color: #999;
  font-size: 12px;
}
</style>

