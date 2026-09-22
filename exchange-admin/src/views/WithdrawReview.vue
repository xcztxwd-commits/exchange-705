<template>
  <div class="withdraw-review-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>提现审核</span>
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
            <el-select v-model="filterType" placeholder="筛选类型" clearable style="width: 150px; margin-right: 10px">
              <el-option label="全部类型" value="" />
              <el-option label="数字货币" value="digital" />
              <el-option label="银行卡" value="bank" />
            </el-select>
            <el-select v-model="filterStatus" placeholder="筛选状态" clearable style="width: 150px; margin-right: 10px">
              <el-option label="全部状态" value="" />
              <el-option label="待审核" value="PENDING" />
              <el-option label="已通过" value="APPROVED" />
              <el-option label="已驳回" value="REJECTED" />
              <el-option label="已完成" value="COMPLETED" />
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
        <el-table-column prop="amount" label="提现金额" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.amount) }} USD <small v-if="row.currency">({{ row.originalAmount }} {{ row.currency }})</small>
          </template>
        </el-table-column>
        <el-table-column prop="actualAmount" label="到账金额" width="120">
          <template #default="{ row }">
            {{ formatMoney(row.actualAmount || row.amount) }} {{ row.currency ? 'USD' : row.network }}
          </template>
        </el-table-column>
        <el-table-column prop="fee" label="手续费" width="100">
          <template #default="{ row }">
            {{ formatMoney(row.fee || 0) }}
          </template>
        </el-table-column>
        <el-table-column prop="address" label="提币地址/收款账户" min-width="200">
          <template #default="{ row }">
            <div class="address-cell">
              <span class="address-text" :title="row.address">{{ row.address }}</span>
              <el-button
                size="small"
                type="primary"
                link
                @click="copyAddress(row)"
                style="margin-left: 8px"
              >
                复制
              </el-button>
              <el-button
                v-if="row.type === 'bank' && row.bankInfo"
                size="small"
                type="success"
                link
                @click="copyBankInfo(row)"
                style="margin-left: 4px"
              >
                一键复制
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
        <el-table-column prop="reviewRemark" label="审核备注" min-width="150" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="提交时间" width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="340" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PENDING' && hasPermission('withdraw_review', 'approve_withdraw')"
              size="small"
              type="success"
              @click="handleApprove(row)"
            >
              审核通过
            </el-button>
            <el-button
              v-if="row.status === 'PENDING' && hasPermission('withdraw_review', 'reject_withdraw')"
              size="small"
              type="danger"
              @click="handleReject(row)"
            >
              驳回
            </el-button>
            <el-button
              v-if="row.status === 'APPROVED' && hasPermission('withdraw_review', 'complete_withdraw')"
              size="small"
              type="warning"
              @click="handleComplete(row)"
            >
              标记完成
            </el-button>
            <el-button
              size="small"
              type="primary"
              plain
              @click="handleViewDetail(row)"
            >
              详细
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 拒绝对话框 -->
    <el-dialog
      v-model="rejectDialogVisible"
      title="驳回提现申请"
      width="400px"
    >
      <el-form :model="rejectForm" label-width="80px">
        <el-form-item label="驳回原因">
          <el-input
            v-model="rejectForm.remark"
            type="textarea"
            :rows="4"
            placeholder="请输入驳回原因（可选）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectDialogVisible = false">取消</el-button>
        <el-button type="danger" @click="confirmReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 通过对话框 -->
    <el-dialog
      v-model="approveDialogVisible"
      title="审核通过提现申请"
      width="400px"
    >
      <div class="approve-content">
        <p><strong>用户ID:</strong> {{ currentRecord?.userId }}</p>
        <p><strong>提现金额:</strong> {{ formatMoney(currentRecord?.amount) }} {{ currentRecord?.currency ? 'USD' : currentRecord?.network }}</p>
        <p><strong>手续费:</strong> {{ formatMoney(currentRecord?.fee || 0) }} {{ currentRecord?.currency ? 'USD' : currentRecord?.network }}</p>
        <p><strong>预计到账:</strong> {{ formatMoney(currentRecord?.actualAmount || currentRecord?.amount) }} {{ currentRecord?.currency ? 'USD' : currentRecord?.network }}</p>
        <el-form :model="approveForm" label-width="80px" style="margin-top: 20px">
          <el-form-item label="审核备注">
            <el-input
              v-model="approveForm.remark"
              type="textarea"
              :rows="3"
              placeholder="请输入审核备注（可选）"
            />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="approveDialogVisible = false">取消</el-button>
        <el-button type="success" @click="confirmApprove">确认通过</el-button>
      </template>
    </el-dialog>

    <!-- 完成对话框 -->
    <el-dialog
      v-model="completeDialogVisible"
      title="标记为已完成"
      width="400px"
    >
      <div class="complete-content">
        <p>确认该提现申请已实际完成转账？</p>
        <p><strong>用户ID:</strong> {{ currentRecord?.userId }}</p>
        <p><strong>提现金额:</strong> {{ formatMoney(currentRecord?.amount) }} {{ currentRecord?.currency ? 'USD' : currentRecord?.network }}</p>
      </div>
      <template #footer>
        <el-button @click="completeDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmComplete">确认完成</el-button>
      </template>
    </el-dialog>

    <!-- 详细对话框（代理账号使用） -->
    <el-dialog
      v-model="detailDialogVisible"
      title="提现记录详情"
      width="800px"
    >
      <el-descriptions v-if="detailRecord" :column="2" border>
        <el-descriptions-item label="记录ID">{{ detailRecord.id }}</el-descriptions-item>
        <el-descriptions-item label="用户ID">
          <span v-if="detailRecord.agentInfo">{{ detailRecord.userId }}({{ detailRecord.agentInfo }})</span>
          <span v-else>{{ detailRecord.userId }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="类型">
          {{ detailRecord.type === 'digital' ? '数字货币' : '银行卡' }}
        </el-descriptions-item>
        <el-descriptions-item label="网络/币种">{{ detailRecord.network }}</el-descriptions-item>
        <el-descriptions-item label="提现金额">
          <span style="font-size: 16px; font-weight: bold; color: #409eff;">
            {{ formatMoney(detailRecord.amount) }} USD <small v-if="detailRecord.currency">({{ detailRecord.originalAmount }} {{ detailRecord.currency }})</small>
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="到账金额">
          <span style="font-size: 16px; font-weight: bold; color: #67c23a;">
            {{ formatMoney(detailRecord.actualAmount || detailRecord.amount) }} {{ detailRecord.currency ? 'USD' : detailRecord.network }}
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="手续费">
          {{ formatMoney(detailRecord.fee || 0) }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(detailRecord.status)">
            {{ getStatusText(detailRecord.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="提币地址/收款账户" :span="2">
          {{ detailRecord.address }}
        </el-descriptions-item>
        <el-descriptions-item v-if="detailRecord.type === 'bank' && detailRecord.bankInfo" label="收款人" :span="2">
          {{ detailRecord.bankInfo.recipientName }} - {{ detailRecord.bankInfo.bankName }}
        </el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">
          {{ detailRecord.remark || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="审核备注" :span="2">
          {{ detailRecord.reviewRemark || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="提交时间">
          {{ formatDateTime(detailRecord.createdAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="审核时间">
          {{ formatDateTime(detailRecord.reviewedAt) }}
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
import { ElMessage } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'
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
      console.log('[WithdrawReview] 权限API响应:', permissionsResponse)
      
      if (permissionsResponse.success) {
        const allMenus: any = await request.get('/admin/menus/list')
        console.log('[WithdrawReview] 所有菜单列表:', allMenus)
        
        if (allMenus.success) {
          const menuMap = new Map<number, string>()
          allMenus.list.forEach((m: any) => {
            menuMap.set(m.id, m.menuCode)
          })
          console.log('[WithdrawReview] 菜单映射表:', menuMap)
          
          const actions = permissionsResponse.actions || {}
          console.log('[WithdrawReview] 操作权限数据:', actions)
          
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
            console.log(`[WithdrawReview] 菜单ID ${menuId} -> 菜单代码 ${menuCode}, 操作:`, actionCodes)
            
            if (menuCode && Array.isArray(actionCodes)) {
              // 同时支持两种菜单代码格式
              userPermissions.value.set(menuCode, actionCodes as string[])
              // 如果是 withdraw-review，也添加到 withdraw_review
              if (menuCode === 'withdraw-review') {
                userPermissions.value.set('withdraw_review', actionCodes as string[])
              }
              // 如果是 withdraw_review，也添加到 withdraw-review
              if (menuCode === 'withdraw_review') {
                userPermissions.value.set('withdraw-review', actionCodes as string[])
              }
            }
          }
          
          console.log('[WithdrawReview] 最终权限Map:', Array.from(userPermissions.value.entries()))
          permissionsLoaded.value = true
        }
      }
    }
  } catch (error: any) {
    console.error('[WithdrawReview] 加载权限失败:', error)
    permissionsLoaded.value = true
  }
}

interface BankInfo {
  recipientName: string
  bankName: string
  recipientAccount: string
}

interface WithdrawRecord {
  id: number
  userId: number
  type: string
  network: string
  amount: number
  currency?: string
  originalAmount?: number
  exchangeRate?: number
  actualAmount?: number
  fee?: number
  address: string
  remark?: string
  status: string
  reviewRemark?: string
  createdAt: string
  reviewedAt?: string
  agentInfo?: string
  bankInfo?: BankInfo
}

const loading = ref(false)
const recordsList = ref<WithdrawRecord[]>([])
const filterStatus = ref('')
const filterType = ref('')
const filterAgentId = ref<number | null>(null)
const filterUserId = ref('')
const filterUserEmail = ref('')
const agentList = ref<any[]>([])

const rejectDialogVisible = ref(false)
const rejectForm = ref({ remark: '' })
const currentRecord = ref<WithdrawRecord | null>(null)

const approveDialogVisible = ref(false)
const approveForm = ref({ remark: '' })

const completeDialogVisible = ref(false)

// 详细对话框（代理账号使用）
const detailDialogVisible = ref(false)
const detailRecord = ref<WithdrawRecord | null>(null)

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

const loadRecords = async () => {
  loading.value = true
  try {
    const params: any = {}
    if (filterStatus.value) {
      params.status = filterStatus.value
    }
    if (filterType.value) {
      params.type = filterType.value
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
    
    const res: any = await request.get('/admin/withdraw/list', { params })
    recordsList.value = res || []
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
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
  filterType.value = ''
  filterStatus.value = ''
  if (!isAgent.value) {
    filterAgentId.value = null
  }
  loadRecords()
}

const handleApprove = (record: WithdrawRecord) => {
  currentRecord.value = record
  approveForm.value = { remark: '' }
  approveDialogVisible.value = true
}

const confirmApprove = async () => {
  if (!currentRecord.value) return
  
  loading.value = true
  try {
    await request.post(`/admin/withdraw/${currentRecord.value.id}/approve`, {
      remark: approveForm.value.remark
    })
    ElMessage.success('审核通过')
    approveDialogVisible.value = false
    loadRecords()
  } catch (e: any) {
    ElMessage.error(e?.message || '操作失败')
  } finally {
    loading.value = false
  }
}

const handleReject = (record: WithdrawRecord) => {
  currentRecord.value = record
  rejectForm.value = { remark: '' }
  rejectDialogVisible.value = true
}

const confirmReject = async () => {
  if (!currentRecord.value) return
  
  loading.value = true
  try {
    await request.post(`/admin/withdraw/${currentRecord.value.id}/reject`, {
      remark: rejectForm.value.remark
    })
    ElMessage.success('已驳回')
    rejectDialogVisible.value = false
    loadRecords()
  } catch (e: any) {
    ElMessage.error(e?.message || '操作失败')
  } finally {
    loading.value = false
  }
}

const handleComplete = (record: WithdrawRecord) => {
  currentRecord.value = record
  completeDialogVisible.value = true
}

const confirmComplete = async () => {
  if (!currentRecord.value) return
  
  loading.value = true
  try {
    await request.post(`/admin/withdraw/${currentRecord.value.id}/complete`)
    ElMessage.success('已标记为完成')
    completeDialogVisible.value = false
    loadRecords()
  } catch (e: any) {
    ElMessage.error(e?.message || '操作失败')
  } finally {
    loading.value = false
  }
}

// 查看详细（代理账号使用）
function handleViewDetail(record: WithdrawRecord) {
  detailRecord.value = record
  detailDialogVisible.value = true
}

const formatMoney = (amount: number | null | undefined) => {
  if (amount == null) return '0'
  return amount.toFixed(8).replace(/\.?0+$/, '')
}

const formatDateTime = (dateString: string | undefined) => {
  if (!dateString) return '-'
  try {
    const date = new Date(dateString)
    return date.toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
  } catch {
    return dateString
  }
}

const getStatusText = (status: string) => {
  const statusMap: Record<string, string> = {
    PENDING: '待审核',
    APPROVED: '已通过',
    REJECTED: '已驳回',
    COMPLETED: '已完成',
  }
  return statusMap[status] || status
}

const getStatusType = (status: string) => {
  const typeMap: Record<string, string> = {
    PENDING: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger',
    COMPLETED: 'info',
  }
  return typeMap[status] || 'info'
}

// 复制地址/收款账户
const copyAddress = async (record: WithdrawRecord) => {
  try {
    await navigator.clipboard.writeText(record.address)
    ElMessage.success('已复制到剪贴板')
  } catch (e) {
    // 降级方案：使用传统方法
    const textArea = document.createElement('textarea')
    textArea.value = record.address
    textArea.style.position = 'fixed'
    textArea.style.left = '-999999px'
    document.body.appendChild(textArea)
    textArea.select()
    try {
      document.execCommand('copy')
      ElMessage.success('已复制到剪贴板')
    } catch (err) {
      ElMessage.error('复制失败')
    }
    document.body.removeChild(textArea)
  }
}

// 复制银行卡信息（姓名、归属行、卡号、金额）
const copyBankInfo = async (record: WithdrawRecord) => {
  if (!record.bankInfo) {
    ElMessage.error('银行卡信息不存在')
    return
  }
  
  const bankInfo = record.bankInfo
  const amount = formatMoney(record.actualAmount ?? record.amount)
  const text = `姓名：${bankInfo.recipientName}\n归属行：${bankInfo.bankName}\n卡号：${bankInfo.recipientAccount}\n金额：${amount} ${record.currency ? 'USD' : record.network}`
  
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制银行卡信息到剪贴板')
  } catch (e) {
    // 降级方案：使用传统方法
    const textArea = document.createElement('textarea')
    textArea.value = text
    textArea.style.position = 'fixed'
    textArea.style.left = '-999999px'
    document.body.appendChild(textArea)
    textArea.select()
    try {
      document.execCommand('copy')
      ElMessage.success('已复制银行卡信息到剪贴板')
    } catch (err) {
      ElMessage.error('复制失败')
    }
    document.body.removeChild(textArea)
  }
}

onMounted(() => {
  loadAgents()
  loadRecords()
  loadPermissions()
})
</script>

<style scoped>
.withdraw-review-page {
  padding: 0;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filter-section {
  display: flex;
  gap: 10px;
}

.no-action {
  color: #999;
  font-size: 12px;
}

.approve-content p,
.complete-content p {
  margin: 8px 0;
  font-size: 14px;
}

.approve-content strong,
.complete-content strong {
  color: #333;
}

.address-cell {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}

.address-text {
  flex: 1;
  min-width: 0;
  word-break: break-all;
}
</style>

