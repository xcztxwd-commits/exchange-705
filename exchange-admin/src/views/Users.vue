<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Lock, Edit, Wallet, User, Delete, Document, ArrowDown } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'

const auth = useAuthStore()
// 判断当前登录用户是否是代理
const isAgent = computed(() => auth.user?.userType === 'agent')

// 权限相关
const userPermissions = ref<Map<string, string[]>>(new Map()) // menuCode -> [actionCode1, actionCode2, ...]
const usersMenuId = ref<number | null>(null) // 用户管理菜单的ID
const permissionsLoaded = ref(false) // 权限是否已加载

// 检查是否有操作权限（使用函数，内部使用响应式数据）
const hasPermission = (menuCode: string, actionCode: string): boolean => {
  // 超级管理员拥有所有权限
  if (auth.user?.isSuperAdmin || auth.user?.role === 'super_admin') {
    return true
  }
  // 如果不是代理，默认有所有权限
  if (!isAgent.value) {
    return true
  }
  // 如果权限还未加载，返回false（避免显示所有按钮）
  if (!permissionsLoaded.value) {
    return false
  }
  // 代理需要检查权限
  const actions = userPermissions.value.get(menuCode) || []
  return actions.includes(actionCode)
}

const users = ref<any[]>([])
const total = ref(0)
const loading = ref(false)
const balanceDialogVisible = ref(false)
const balanceForm = ref({
  userId: 0,
  email: '',
  fundBalance: 0,
  contractBalance: 0,
  optionBalance: 0,
})

const walletDialogVisible = ref(false)
const currentUserId = ref(0)
const currentUserEmail = ref('')
const bankCards = ref<any[]>([])
const digitalAddresses = ref<any[]>([])
const loadingWallet = ref(false)
const activeTab = ref('bank')
const editingBankCard = ref<any>(null)
const editingAddress = ref<any>(null)

const subordinatesDialogVisible = ref(false)
const subordinates = ref<any[]>([])
const loadingSubordinates = ref(false)
const currentSubordinateUserId = ref(0)

// 用户详细对话框
const userDetailDialogVisible = ref(false)
const userDetail = ref<any>(null)
const loadingUserDetail = ref(false)

// 账户余额对话框（代理账号使用）
const balanceViewDialogVisible = ref(false)
const balanceViewData = ref<any>(null)
const loadingBalanceView = ref(false)

// 资金明细对话框
const fundDetailsDialogVisible = ref(false)
const fundDetailsData = ref<any>(null)
const loadingFundDetails = ref(false)
const fundDetailsActiveTab = ref('all')
const currentFundDetailsUserId = ref(0)
const currentFundDetailsUserEmail = ref('')

// 修改邀请码对话框
const inviteCodeDialogVisible = ref(false)
const inviteCodeForm = ref({
  userId: 0,
  email: '',
  currentInviteCode: '',
  newInviteCode: '',
})

// 编辑备注对话框
const remarkDialogVisible = ref(false)
const remarkForm = ref({
  userId: 0,
  email: '',
  currentRemark: '',
  newRemark: '',
})

const bankCardForm = ref({
  currency: '',
  bankName: '',
  bankAddress: '',
  swift: '',
  recipientName: '',
  recipientAccount: '',
})

const addressForm = ref({
  currency: '',
  network: '',
  address: '',
})

const queryParams = ref({
  userId: '',
  keyword: '',
  status: '',
  userType: '',
  filterAgentId: null as number | null,
  page: 0,
  size: 20,
})

const agentList = ref<any[]>([])

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

const loadUsers = async () => {
  loading.value = true
  try {
    const res: any = await request.post('/admin/users/query', queryParams.value)
    users.value = res.list || []
    total.value = res.total || 0
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  queryParams.value.page = 0
  loadUsers()
}

const handleReset = () => {
  queryParams.value.userId = ''
  queryParams.value.keyword = ''
  queryParams.value.status = ''
  queryParams.value.userType = ''
  queryParams.value.filterAgentId = null
  queryParams.value.page = 0
  loadUsers()
}

const handlePageChange = (page: number) => {
  queryParams.value.page = page - 1
  loadUsers()
}

const handleResetPassword = async (row: any) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入新密码（至少6位）', '重置密码', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPattern: /.{6,}/,
      inputErrorMessage: '密码长度至少6位',
    })
    
    await request.post('/admin/users/resetPassword', {
      userId: row.id,
      newPassword: value,
    })
    
    ElMessage.success('密码重置成功')
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.message || '重置失败')
    }
  }
}

const handleUpdateStatus = async (row: any, newStatus: string) => {
  const statusText = { normal: '正常', frozen: '冻结', banned: '禁用' }[newStatus] || newStatus
  
  try {
    await ElMessageBox.confirm(`确定将用户状态改为"${statusText}"吗？`, '提示', {
      type: 'warning',
    })
    
    await request.post('/admin/users/updateStatus', {
      userId: row.id,
      status: newStatus,
    })
    
    ElMessage.success('状态更新成功')
    loadUsers()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.message || '更新失败')
    }
  }
}

const handleSetUserType = async (row: any, userType: string) => {
  const typeText = userType === 'agent' ? '代理' : '正常用户'
  
  try {
    await ElMessageBox.confirm(`确定将用户类型改为"${typeText}"吗？`, '提示', {
      type: 'warning',
    })
    
    await request.post('/admin/users/updateUserType', {
      userId: row.id,
      userType: userType,
    })
    
    ElMessage.success('用户类型更新成功')
    loadUsers()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.message || '更新失败')
    }
  }
}

const handleCommand = (command: string, row: any) => {
  switch (command) {
    case 'reset_password':
      handleResetPassword(row)
      break
    case 'freeze':
      handleUpdateStatus(row, 'frozen')
      break
    case 'unfreeze':
      handleUpdateStatus(row, 'normal')
      break
    case 'ban':
      handleUpdateStatus(row, 'banned')
      break
    case 'unban':
      handleUpdateStatus(row, 'normal')
      break
    case 'set_agent':
      handleSetUserType(row, 'agent')
      break
    case 'unset_agent':
      handleSetUserType(row, 'normal')
      break
    case 'modify_balance':
      openBalanceDialog(row)
      break
    case 'wallet_management':
      openWalletDialog(row)
      break
    case 'subordinates':
      openSubordinatesDialog(row)
      break
    case 'fund_details':
      openFundDetailsDialog(row)
      break
    case 'delete':
      handleDeleteUser(row)
      break
    case 'edit_remark':
      openRemarkDialog(row)
      break
  }
}

const openRemarkDialog = (row: any) => {
  remarkForm.value = {
    userId: row.id,
    email: row.email,
    currentRemark: row.remark || '',
    newRemark: row.remark || '',
  }
  remarkDialogVisible.value = true
}

const handleSaveRemark = async () => {
  try {
    await request.post(`/admin/users/${remarkForm.value.userId}/update-remark`, {
      remark: remarkForm.value.newRemark || ''
    })
    ElMessage.success('备注保存成功')
    remarkDialogVisible.value = false
    loadUsers()
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  }
}

const openBalanceDialog = (row: any) => {
  balanceForm.value = {
    userId: row.id,
    email: row.email,
    fundBalance: row.fundBalance ?? 0,
    contractBalance: row.contractBalance ?? 0,
    optionBalance: row.optionBalance ?? 0,
  }
  balanceDialogVisible.value = true
}

const submitBalance = async () => {
  try {
    await request.post('/admin/users/updateBalance', {
      userId: balanceForm.value.userId,
      fundBalance: balanceForm.value.fundBalance,
      contractBalance: balanceForm.value.contractBalance,
      optionBalance: balanceForm.value.optionBalance,
    })
    ElMessage.success('余额更新成功')
    balanceDialogVisible.value = false
    loadUsers()
  } catch (e: any) {
    ElMessage.error(e?.message || '更新失败')
  }
}

const getStatusType = (status: string) => {
  const map: any = {
    normal: 'success',
    frozen: 'warning',
    banned: 'danger',
  }
  return map[status] || 'info'
}

const getStatusText = (status: string) => {
  const map: any = {
    normal: '正常',
    frozen: '冻结',
    banned: '禁用',
  }
  return map[status] || status
}

// 判断用户是否在线（最后活动时间在5分钟内认为在线）
const isUserOnline = (row: any): boolean => {
  // 优先使用lastActivityAt（实时活动时间），如果没有则使用lastLoginAt（兼容旧数据）
  const activityTime = row.lastActivityAt || row.lastLoginAt
  if (!activityTime) return false
  
  try {
    const lastActivityTime = new Date(activityTime).getTime()
    const now = Date.now()
    const diff = now - lastActivityTime
    // 5分钟内认为在线（300000毫秒 = 5分钟）
    return diff <= 300000
  } catch (e) {
    return false
  }
}

// 打开账户余额查看对话框（代理账号使用）
const openBalanceViewDialog = async (row: any) => {
  loadingBalanceView.value = true
  balanceViewDialogVisible.value = true
  try {
    const res: any = await request.get(`/admin/users/${row.id}`)
    balanceViewData.value = {
      email: res.email,
      fundBalance: res.fundBalance || 0,
      contractBalance: res.contractBalance || 0,
      optionBalance: res.optionBalance || 0,
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '加载账户余额失败')
    balanceViewDialogVisible.value = false
  } finally {
    loadingBalanceView.value = false
  }
}

// 打开收款管理对话框
const openWalletDialog = async (row: any) => {
  currentUserId.value = row.id
  currentUserEmail.value = row.email
  walletDialogVisible.value = true
  activeTab.value = 'bank'
  await loadWalletData()
}

// 加载收款数据
const loadWalletData = async () => {
  loadingWallet.value = true
  try {
    const [bankRes, addressRes]: any[] = await Promise.all([
      request.get(`/admin/wallet/${currentUserId.value}/bank-cards`),
      request.get(`/admin/wallet/${currentUserId.value}/digital-addresses`)
    ])
    
    if (bankRes && bankRes.success !== false) {
      bankCards.value = bankRes.list || []
    }
    
    if (addressRes && addressRes.success !== false) {
      digitalAddresses.value = addressRes.list || []
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loadingWallet.value = false
  }
}

// 银行卡操作
const handleAddBankCard = () => {
  editingBankCard.value = null
  bankCardForm.value = {
    currency: '',
    bankName: '',
    bankAddress: '',
    swift: '',
    recipientName: '',
    recipientAccount: '',
  }
}

const handleEditBankCard = (card: any) => {
  editingBankCard.value = card
  bankCardForm.value = {
    currency: card.currency || '',
    bankName: card.bankName || '',
    bankAddress: card.bankAddress || '',
    swift: card.swift || '',
    recipientName: card.recipientName || '',
    recipientAccount: card.recipientAccount || '',
  }
}

const handleSaveBankCard = async () => {
  if (!bankCardForm.value.currency || !bankCardForm.value.bankName || 
      !bankCardForm.value.recipientName || !bankCardForm.value.recipientAccount) {
    ElMessage.warning('请填写完整信息')
    return
  }

  try {
    let res: any
    if (editingBankCard.value) {
      res = await request.put(
        `/admin/wallet/${currentUserId.value}/bank-cards/${editingBankCard.value.id}`,
        bankCardForm.value
      )
    } else {
      res = await request.post(
        `/admin/wallet/${currentUserId.value}/bank-cards`,
        bankCardForm.value
      )
    }
    
    if (res && res.success !== false) {
      ElMessage.success(editingBankCard.value ? '更新成功' : '添加成功')
      handleAddBankCard()
      loadWalletData()
    } else {
      ElMessage.error(res.message || '操作失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

const handleDeleteBankCard = async (id: number) => {
  try {
    await ElMessageBox.confirm('确定要删除这张银行卡吗？', '提示', {
      type: 'warning',
    })
    
    const res: any = await request.delete(`/admin/wallet/${currentUserId.value}/bank-cards/${id}`)
    if (res && res.success !== false) {
      ElMessage.success('删除成功')
      loadWalletData()
    } else {
      ElMessage.error(res.message || '删除失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || '删除失败')
    }
  }
}

// 数字货币地址操作
const handleAddAddress = () => {
  editingAddress.value = null
  addressForm.value = {
    currency: '',
    network: '',
    address: '',
  }
}

const handleEditAddress = (address: any) => {
  editingAddress.value = address
  addressForm.value = {
    currency: address.currency || '',
    network: address.network || '',
    address: address.address || '',
  }
}

const handleSaveAddress = async () => {
  if (!addressForm.value.currency || !addressForm.value.network || !addressForm.value.address) {
    ElMessage.warning('请填写完整信息')
    return
  }

  try {
    let res: any
    if (editingAddress.value) {
      res = await request.put(
        `/admin/wallet/${currentUserId.value}/digital-addresses/${editingAddress.value.id}`,
        addressForm.value
      )
    } else {
      res = await request.post(
        `/admin/wallet/${currentUserId.value}/digital-addresses`,
        addressForm.value
      )
    }
    
    if (res && res.success !== false) {
      ElMessage.success(editingAddress.value ? '更新成功' : '添加成功')
      handleAddAddress()
      loadWalletData()
    } else {
      ElMessage.error(res.message || '操作失败')
    }
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '操作失败')
  }
}

const handleDeleteAddress = async (id: number) => {
  try {
    await ElMessageBox.confirm('确定要删除这个地址吗？', '提示', {
      type: 'warning',
    })
    
    const res: any = await request.delete(`/admin/wallet/${currentUserId.value}/digital-addresses/${id}`)
    if (res && res.success !== false) {
      ElMessage.success('删除成功')
      loadWalletData()
    } else {
      ElMessage.error(res.message || '删除失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || '删除失败')
    }
  }
}

// 打开用户详细对话框
const openUserDetailDialog = async (row: any) => {
  loadingUserDetail.value = true
  userDetailDialogVisible.value = true
  try {
    const res: any = await request.get(`/admin/users/${row.id}`)
    userDetail.value = res
  } catch (e: any) {
    ElMessage.error(e?.message || '加载用户详情失败')
    userDetailDialogVisible.value = false
  } finally {
    loadingUserDetail.value = false
  }
}

// 打开下级用户对话框
const openSubordinatesDialog = async (row: any) => {
  currentSubordinateUserId.value = row.id
  subordinatesDialogVisible.value = true
  await loadSubordinates()
}

// 加载下级用户列表
const loadSubordinates = async () => {
  loadingSubordinates.value = true
  try {
    const res: any = await request.get(`/admin/users/${currentSubordinateUserId.value}/subordinates`)
    subordinates.value = res.list || []
  } catch (e: any) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loadingSubordinates.value = false
  }
}

// 打开资金明细对话框
const openFundDetailsDialog = async (row: any) => {
  currentFundDetailsUserId.value = row.id
  currentFundDetailsUserEmail.value = row.email
  fundDetailsDialogVisible.value = true
  fundDetailsActiveTab.value = 'all'
  await loadFundDetails()
}

// 加载资金明细
const loadFundDetails = async () => {
  loadingFundDetails.value = true
  try {
    const res: any = await request.get(`/admin/users/${currentFundDetailsUserId.value}/fund-details`)
    if (res && res.success && res.data) {
      fundDetailsData.value = res.data
    } else {
      ElMessage.error('加载资金明细失败')
      fundDetailsData.value = null
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '加载资金明细失败')
    fundDetailsData.value = null
  } finally {
    loadingFundDetails.value = false
  }
}

// 格式化金额
const formatAmount = (amount: number | null | undefined) => {
  if (amount == null) return '0.00'
  return Number(amount).toFixed(2)
}

// 格式化日期时间
const formatDateTime = (dateString: string | null | undefined) => {
  if (!dateString) return '-'
  try {
    const date = new Date(dateString)
    return date.toLocaleString('zh-CN', { 
      year: 'numeric', 
      month: '2-digit', 
      day: '2-digit', 
      hour: '2-digit', 
      minute: '2-digit',
      second: '2-digit'
    })
  } catch (e) {
    return dateString
  }
}

// 获取订单状态标签类型
const getOrderStatusType = (status: string) => {
  const map: any = {
    'OPEN': 'warning',
    'CLOSED': 'success',
    'CANCELLED': 'info',
    'PENDING': 'warning',
    'TRADING': 'primary',
  }
  return map[status] || 'info'
}

// 获取订单状态文本
const getOrderStatusText = (status: string) => {
  const map: any = {
    'OPEN': '持仓中',
    'CLOSED': '已平仓',
    'CANCELLED': '已取消',
    'PENDING': '待处理',
    'TRADING': '交易中',
  }
  return map[status] || status
}

// 获取充值/提现状态标签类型
const getRecordStatusType = (status: string) => {
  const map: any = {
    'PENDING': 'warning',
    'COMPLETED': 'success',
    'APPROVED': 'success',
    'REJECTED': 'danger',
  }
  return map[status] || 'info'
}

// 获取充值/提现状态文本
const getRecordStatusText = (status: string) => {
  const map: any = {
    'PENDING': '待审核',
    'COMPLETED': '已完成',
    'APPROVED': '已通过',
    'REJECTED': '已驳回',
  }
  return map[status] || status
}

// 获取贷款状态标签类型
const getLoanStatusType = (status: string) => {
  const map: any = {
    'PENDING': 'warning',
    'APPROVED': 'success',
    'SIGNED': 'primary',
    'COMPLETED': 'success',
    'OVERDUE': 'danger',
    'REJECTED': 'danger',
  }
  return map[status] || 'info'
}

// 获取贷款状态文本
const getLoanStatusText = (status: string) => {
  const map: any = {
    'PENDING': '待审核',
    'APPROVED': '已批准',
    'SIGNED': '已签约',
    'COMPLETED': '已完成',
    'OVERDUE': '已逾期',
    'REJECTED': '已拒绝',
  }
  return map[status] || status
}

// 获取理财订单状态标签类型
const getFinancialStatusType = (status: string) => {
  const map: any = {
    'ACTIVE': 'success',
    'COMPLETED': 'info',
    'CANCELLED': 'danger',
  }
  return map[status] || 'info'
}

// 获取理财订单状态文本
const getFinancialStatusText = (status: string) => {
  const map: any = {
    'ACTIVE': '进行中',
    'COMPLETED': '已完成',
    'CANCELLED': '已取消',
  }
  return map[status] || status
}

// 合并所有资金明细记录（用于"全部"标签页）
const allFundDetails = computed(() => {
  if (!fundDetailsData.value) return []
  
  const all: any[] = []
  
  // 合约订单
  if (fundDetailsData.value.contractOrders) {
    fundDetailsData.value.contractOrders.forEach((order: any) => {
      all.push({
        type: 'contract',
        typeText: '合约订单',
        id: order.id,
        amount: order.margin || 0,
        profit: order.profit || 0,
        status: order.status,
        symbol: order.symbol,
        side: order.side,
        quantity: order.quantity,
        openPrice: order.openPrice,
        closePrice: order.closePrice,
        createdAt: order.createdAt,
      })
    })
  }
  
  // 期权订单
  if (fundDetailsData.value.optionOrders) {
    fundDetailsData.value.optionOrders.forEach((order: any) => {
      all.push({
        type: 'option',
        typeText: '期权订单',
        id: order.id,
        amount: order.amount || 0,
        profit: order.profit || 0,
        status: order.status,
        symbol: order.symbol,
        direction: order.direction,
        duration: order.duration,
        openPrice: order.openPrice,
        closePrice: order.closePrice,
        createdAt: order.createdAt,
      })
    })
  }
  
  // 充值记录
  if (fundDetailsData.value.deposits) {
    fundDetailsData.value.deposits.forEach((record: any) => {
      all.push({
        type: 'deposit',
        typeText: '入金',
        id: record.id,
        amount: record.amount || 0,
        status: record.status,
        typeDetail: record.type,
        network: record.network,
        address: record.address,
        createdAt: record.createdAt,
      })
    })
  }
  
  // 提现记录
  if (fundDetailsData.value.withdraws) {
    fundDetailsData.value.withdraws.forEach((record: any) => {
      all.push({
        type: 'withdraw',
        typeText: '出金',
        id: record.id,
        amount: record.amount || 0,
        actualAmount: record.actualAmount || 0,
        fee: record.fee || 0,
        status: record.status,
        typeDetail: record.type,
        network: record.network,
        address: record.address,
        createdAt: record.createdAt,
      })
    })
  }
  
  // 贷款记录
  if (fundDetailsData.value.loans) {
    fundDetailsData.value.loans.forEach((record: any) => {
      all.push({
        type: 'loan',
        typeText: '贷款',
        id: record.id,
        amount: record.amount || 0,
        repaymentAmount: record.repaymentAmount || 0,
        totalInterest: record.totalInterest || 0,
        status: record.status,
        days: record.days,
        createdAt: record.createdAt,
      })
    })
  }
  
  // 理财订单
  if (fundDetailsData.value.financialOrders) {
    fundDetailsData.value.financialOrders.forEach((order: any) => {
      all.push({
        type: 'financial',
        typeText: '理财',
        id: order.id,
        amount: order.amount || 0,
        status: order.status,
        productName: order.productName,
        purchaseTime: order.purchaseTime,
        maturityTime: order.maturityTime,
        createdAt: order.createdAt,
      })
    })
  }
  
  // 转账记录
  if (fundDetailsData.value.transfers) {
    fundDetailsData.value.transfers.forEach((record: any) => {
      all.push({
        type: 'transfer',
        typeText: '转账',
        id: record.id,
        amount: record.amount || 0,
        fromAccount: record.fromAccount,
        toAccount: record.toAccount,
        createdAt: record.createdAt,
      })
    })
  }
  
  // 按时间倒序排序
  return all.sort((a, b) => {
    const timeA = new Date(a.createdAt || a.purchaseTime || 0).getTime()
    const timeB = new Date(b.createdAt || b.purchaseTime || 0).getTime()
    return timeB - timeA
  })
})

// 打开修改邀请码对话框
const handleEditInviteCode = (row: any) => {
  inviteCodeForm.value = {
    userId: row.id,
    email: row.email,
    currentInviteCode: row.myInviteCode || '',
    newInviteCode: row.myInviteCode || '',
  }
  inviteCodeDialogVisible.value = true
}

// 保存邀请码
const handleSaveInviteCode = async () => {
  if (!inviteCodeForm.value.newInviteCode || !inviteCodeForm.value.newInviteCode.trim()) {
    ElMessage.warning('邀请码不能为空')
    return
  }
  
  try {
    await request.post(`/admin/users/${inviteCodeForm.value.userId}/update-invite-code`, {
      inviteCode: inviteCodeForm.value.newInviteCode.trim(),
    })
    ElMessage.success('邀请码修改成功')
    inviteCodeDialogVisible.value = false
    loadUsers()
  } catch (e: any) {
    ElMessage.error(e?.message || '修改失败')
  }
}

// 删除用户
const handleDeleteUser = async (row: any) => {
  try {
    await ElMessageBox.confirm(
      `确定要删除用户 "${row.email}" 吗？删除后将无法恢复，且会删除该用户的所有关联数据（资产账户、订单等）。`,
      '警告',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'error',
        confirmButtonClass: 'el-button--danger',
      }
    )
    
    await request.delete(`/admin/users/${row.id}`)
    
    ElMessage.success('用户删除成功')
    loadUsers()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.message || '删除失败')
    }
  }
}

// 加载权限信息
const loadPermissions = async () => {
  if (!isAgent.value) return // 非代理用户不需要加载权限
  
  try {
    // 获取用户管理菜单ID
    const menusResponse: any = await request.get('/admin/menus/list')
    if (menusResponse.success) {
      const usersMenu = menusResponse.list.find((m: any) => m.menuCode === 'users')
      if (usersMenu) {
        usersMenuId.value = usersMenu.id
      }
    }
    
    // 获取当前用户的权限
    const userId = auth.user?.id
    if (userId) {
      const permissionsResponse: any = await request.get(`/admin/users/${userId}/menus`)
      console.log('权限API响应:', permissionsResponse)
      
      if (permissionsResponse.success) {
        // 获取所有菜单信息
        const allMenus: any = await request.get('/admin/menus/list')
        console.log('所有菜单列表:', allMenus)
        
        if (allMenus.success) {
          const menuMap = new Map<number, string>()
          allMenus.list.forEach((m: any) => {
            menuMap.set(m.id, m.menuCode)
          })
          console.log('菜单映射表:', menuMap)
          
          // 构建权限Map (menuCode -> [actionCode1, actionCode2, ...])
          const actions = permissionsResponse.actions || {}
          console.log('[Users] 操作权限数据:', actions, '类型:', typeof actions)
          
          userPermissions.value = new Map()
          // 处理actions对象，key可能是字符串或数字
          for (const [menuIdKey, actionCodes] of Object.entries(actions)) {
            // 尝试将key转换为数字（可能是字符串形式的数字）
            let menuId: number
            if (typeof menuIdKey === 'string') {
              menuId = parseInt(menuIdKey, 10)
            } else {
              menuId = Number(menuIdKey)
            }
            
            const menuCode = menuMap.get(menuId)
            console.log(`[Users] 菜单ID ${menuId} (原始key: ${menuIdKey}) -> 菜单代码 ${menuCode}, 操作:`, actionCodes)
            
            if (menuCode && Array.isArray(actionCodes)) {
              userPermissions.value.set(menuCode, actionCodes as string[])
            }
          }
          
          console.log('[Users] 最终权限Map:', Array.from(userPermissions.value.entries()))
          console.log('[Users] users菜单权限:', userPermissions.value.get('users'))
          permissionsLoaded.value = true
        }
      } else {
        console.warn('权限API返回失败:', permissionsResponse)
        permissionsLoaded.value = true
      }
    }
  } catch (error: any) {
    console.error('加载权限失败:', error)
    permissionsLoaded.value = true // 即使失败也标记为已加载，避免一直显示false
  }
}

onMounted(() => {
  loadAgents()
  loadUsers()
  loadPermissions()
})
</script>

<template>
  <div class="users-page">
    <el-card shadow="never">
      <!-- 搜索栏 -->
      <el-form :inline="true" :model="queryParams">
        <el-form-item label="用户ID">
          <el-input
            v-model="queryParams.userId"
            placeholder="用户ID"
            clearable
            style="width: 150px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="关键词">
          <el-input
            v-model="queryParams.keyword"
            placeholder="搜索邮箱/手机/昵称"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="正常" value="normal" />
            <el-option label="冻结" value="frozen" />
            <el-option label="禁用" value="banned" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!isAgent" label="用户类型">
          <el-select v-model="queryParams.userType" placeholder="全部" clearable style="width: 120px">
            <el-option label="普通用户" value="normal" />
            <el-option label="代理" value="agent" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!isAgent" label="所属代理">
          <el-select 
            v-model="queryParams.filterAgentId" 
            placeholder="全部代理" 
            clearable 
            style="width: 200px"
          >
            <el-option 
              v-for="agent in agentList" 
              :key="agent.id" 
              :label="agent.name" 
              :value="agent.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 表格 -->
      <el-table
        :data="users"
        v-loading="loading"
        stripe
        border
        style="margin-top: 16px"
      >
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="remark" label="备注" width="150">
          <template #default="{ row }">
            {{ row.remark || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="180" />
        <el-table-column label="登录IP / 地区" width="250">
          <template #default="{ row }">
            <div v-if="row.lastLoginIp">
              <div style="display: flex; align-items: center; gap: 6px; flex-wrap: wrap;">
                <el-tag v-if="isUserOnline(row)" type="success" size="small" effect="dark">
                  在线
                </el-tag>
                <span style="font-weight: 500;">{{ row.lastLoginIp }}</span>
                <span style="color: #999; font-size: 12px;">{{ row.lastLoginRegion || '-' }}</span>
              </div>
            </div>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="parentUserEmail" label="上级用户" width="150">
          <template #default="{ row }">
            {{ row.parentUserEmail || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="myInviteCode" label="邀请码" width="180">
          <template #default="{ row }">
            <div style="display: flex; align-items: center; gap: 8px;">
              <span>{{ row.myInviteCode || '-' }}</span>
              <el-button 
                v-if="hasPermission('users', 'modify_invite_code')"
                link 
                type="primary" 
                size="small" 
                @click="handleEditInviteCode(row)"
              >
                修改
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="nickname" label="昵称" width="120">
          <template #default="{ row }">
            {{ row.nickname || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="用户类型" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.userType === 'agent'" type="warning">代理</el-tag>
            <el-tag v-else type="success">正常用户</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="kycStatus" label="实名状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.kycStatus === 'VERIFIED'" type="success">已实名</el-tag>
            <el-tag v-else type="info">未实名</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="fundBalance"
          label="余额"
          min-width="260"
        >
          <template #default="{ row }">
            资金账户: {{ row.fundBalance ?? 0 }}　
            合约资产: {{ row.contractBalance ?? 0 }}　
            期权账户: {{ row.optionBalance ?? 0 }}
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="注册时间" width="180" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-dropdown trigger="click" @command="(cmd: string) => handleCommand(cmd, row)">
              <el-button type="primary" link>
                操作 <el-icon class="el-icon--right"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item 
                    v-if="hasPermission('users', 'reset_password')"
                    :command="'reset_password'"
                  >
                    <el-icon style="margin-right: 8px;"><Lock /></el-icon>重置密码
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasPermission('users', 'freeze_user') && row.status === 'normal'"
                    :command="'freeze'"
                    divided
                  >
                    冻结
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasPermission('users', 'unfreeze_user') && row.status === 'frozen'"
                    :command="'unfreeze'"
                  >
                    解冻
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasPermission('users', 'ban_user') && row.status !== 'banned'"
                    :command="'ban'"
                  >
                    禁用
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasPermission('users', 'ban_user') && row.status === 'banned'"
                    :command="'unban'"
                  >
                    取消禁用
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasPermission('users', 'set_agent') && row.userType !== 'agent'"
                    :command="'set_agent'"
                    divided
                  >
                    设为代理
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="hasPermission('users', 'unset_agent') && row.userType === 'agent'"
                    :command="'unset_agent'"
                  >
                    取消代理
                  </el-dropdown-item>
                  <el-dropdown-item 
                    :command="'edit_remark'"
                    divided
                  >
                    <el-icon style="margin-right: 8px;"><Edit /></el-icon>修改备注
                  </el-dropdown-item>
                  <el-dropdown-item 
                    v-if="hasPermission('users', 'modify_balance')"
                    :command="'modify_balance'"
                  >
                    <el-icon style="margin-right: 8px;"><Edit /></el-icon>修改余额
                  </el-dropdown-item>
                  <el-dropdown-item 
                    v-if="hasPermission('users', 'wallet_management')"
                    :command="'wallet_management'"
                  >
                    <el-icon style="margin-right: 8px;"><Wallet /></el-icon>收款管理
                  </el-dropdown-item>
                  <el-dropdown-item 
                    v-if="hasPermission('users', 'view_subordinates')"
                    :command="'subordinates'"
                  >
                    <el-icon style="margin-right: 8px;"><User /></el-icon>下级用户
                  </el-dropdown-item>
                  <el-dropdown-item 
                    :command="'fund_details'"
                  >
                    <el-icon style="margin-right: 8px;"><Document /></el-icon>资金明细
                  </el-dropdown-item>
                  <el-dropdown-item 
                    v-if="hasPermission('users', 'delete_user')"
                    :command="'delete'"
                    divided
                  >
                    <el-icon style="margin-right: 8px;"><Delete /></el-icon>删除
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <el-pagination
        v-if="total > 0"
        style="margin-top: 20px; justify-content: flex-end"
        :current-page="queryParams.page + 1"
        :page-size="queryParams.size"
        :total="total"
        layout="total, prev, pager, next, jumper"
        @current-change="handlePageChange"
      />

      <el-empty v-if="!loading && users.length === 0" description="暂无数据" />

      <el-dialog v-model="balanceDialogVisible" title="修改用户余额" width="420px">
        <el-form label-width="100px">
          <el-form-item label="用户邮箱">
            <span>{{ balanceForm.email }}</span>
          </el-form-item>
          <el-form-item label="资金账户">
            <el-input-number
              v-model="balanceForm.fundBalance"
              :min="0"
              :step="1"
              style="width: 260px"
            />
          </el-form-item>
          <el-form-item label="合约资产">
            <el-input-number
              v-model="balanceForm.contractBalance"
              :min="0"
              :step="1"
              style="width: 260px"
            />
          </el-form-item>
          <el-form-item label="期权账户">
            <el-input-number
              v-model="balanceForm.optionBalance"
              :min="0"
              :step="1"
              style="width: 260px"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <span class="dialog-footer">
            <el-button @click="balanceDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="submitBalance">保存</el-button>
          </span>
        </template>
      </el-dialog>

      <!-- 收款管理对话框 -->
      <el-dialog v-model="walletDialogVisible" title="收款管理" width="900px">
        <div style="margin-bottom: 16px; color: #666;">
          用户邮箱: {{ currentUserEmail }}
        </div>
        
        <el-tabs v-model="activeTab">
          <!-- 银行卡标签页 -->
          <el-tab-pane label="银行卡" name="bank">
            <div style="margin-bottom: 16px;">
              <el-button type="primary" size="small" @click="handleAddBankCard">添加银行卡</el-button>
            </div>
            
            <el-table :data="bankCards" v-loading="loadingWallet" border stripe>
              <el-table-column prop="currency" label="货币" width="100" />
              <el-table-column prop="bankName" label="银行名称" width="150" />
              <el-table-column prop="bankAddress" label="银行地址" min-width="150" />
              <el-table-column prop="swift" label="SWIFT" width="120" />
              <el-table-column prop="recipientName" label="收款人" width="120" />
              <el-table-column prop="recipientAccount" label="收款账户" min-width="150" />
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="handleEditBankCard(row)">编辑</el-button>
                  <el-button link type="danger" size="small" @click="handleDeleteBankCard(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            
            <!-- 添加/编辑银行卡表单 -->
            <el-card v-if="bankCardForm.currency || editingBankCard" style="margin-top: 16px;" shadow="never">
              <template #header>
                <span>{{ editingBankCard ? '编辑银行卡' : '添加银行卡' }}</span>
              </template>
              <el-form :model="bankCardForm" label-width="100px">
                <el-form-item label="货币" required>
                  <el-input v-model="bankCardForm.currency" placeholder="如: USD, EUR" />
                </el-form-item>
                <el-form-item label="银行名称" required>
                  <el-input v-model="bankCardForm.bankName" />
                </el-form-item>
                <el-form-item label="银行地址">
                  <el-input v-model="bankCardForm.bankAddress" />
                </el-form-item>
                <el-form-item label="SWIFT">
                  <el-input v-model="bankCardForm.swift" />
                </el-form-item>
                <el-form-item label="收款人" required>
                  <el-input v-model="bankCardForm.recipientName" />
                </el-form-item>
                <el-form-item label="收款账户" required>
                  <el-input v-model="bankCardForm.recipientAccount" />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" @click="handleSaveBankCard">保存</el-button>
                  <el-button @click="handleAddBankCard">取消</el-button>
                </el-form-item>
              </el-form>
            </el-card>
          </el-tab-pane>
          
          <!-- 数字货币地址标签页 -->
          <el-tab-pane label="数字货币地址" name="digital">
            <div style="margin-bottom: 16px;">
              <el-button type="primary" size="small" @click="handleAddAddress">添加地址</el-button>
            </div>
            
            <el-table :data="digitalAddresses" v-loading="loadingWallet" border stripe>
              <el-table-column prop="currency" label="货币" width="100" />
              <el-table-column prop="network" label="网络" width="120" />
              <el-table-column prop="address" label="地址" min-width="300" />
              <el-table-column label="操作" width="150" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" size="small" @click="handleEditAddress(row)">编辑</el-button>
                  <el-button link type="danger" size="small" @click="handleDeleteAddress(row.id)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
            
            <!-- 添加/编辑地址表单 -->
            <el-card v-if="addressForm.currency || editingAddress" style="margin-top: 16px;" shadow="never">
              <template #header>
                <span>{{ editingAddress ? '编辑地址' : '添加地址' }}</span>
              </template>
              <el-form :model="addressForm" label-width="100px">
                <el-form-item label="货币" required>
                  <el-input v-model="addressForm.currency" placeholder="如: BTC, ETH, USDT" />
                </el-form-item>
                <el-form-item label="网络" required>
                  <el-input v-model="addressForm.network" placeholder="如: BTC, ETH, ERC20, TRC20" />
                </el-form-item>
                <el-form-item label="地址" required>
                  <el-input v-model="addressForm.address" type="textarea" :rows="2" />
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" @click="handleSaveAddress">保存</el-button>
                  <el-button @click="handleAddAddress">取消</el-button>
                </el-form-item>
              </el-form>
            </el-card>
          </el-tab-pane>
        </el-tabs>
      </el-dialog>

      <!-- 下级用户对话框 -->
      <el-dialog v-model="subordinatesDialogVisible" title="下级用户列表" width="900px">
        <el-table
          :data="subordinates"
          v-loading="loadingSubordinates"
          stripe
          border
        >
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="email" label="邮箱" min-width="180" />
          <el-table-column prop="nickname" label="昵称" width="120">
            <template #default="{ row }">
              {{ row.nickname || '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="getStatusType(row.status)">
                {{ getStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="注册时间" width="180" />
        </el-table>
        <el-empty v-if="!loadingSubordinates && subordinates.length === 0" description="暂无下级用户" />
      </el-dialog>

      <!-- 账户余额查看对话框（代理账号使用） -->
      <el-dialog v-model="balanceViewDialogVisible" title="账户余额" width="600px" v-loading="loadingBalanceView">
        <div v-if="balanceViewData" style="padding: 20px 0;">
          <div style="margin-bottom: 20px; color: #606266; font-size: 14px;">
            用户邮箱: <strong>{{ balanceViewData.email }}</strong>
          </div>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="资金账户余额">
              <span style="font-size: 16px; font-weight: bold; color: #409eff;">
                {{ balanceViewData.fundBalance || 0 }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="合约账户余额">
              <span style="font-size: 16px; font-weight: bold; color: #67c23a;">
                {{ balanceViewData.contractBalance || 0 }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="期权账户余额">
              <span style="font-size: 16px; font-weight: bold; color: #e6a23c;">
                {{ balanceViewData.optionBalance || 0 }}
              </span>
            </el-descriptions-item>
          </el-descriptions>
        </div>
        <template #footer>
          <el-button @click="balanceViewDialogVisible = false">关闭</el-button>
        </template>
      </el-dialog>

      <!-- 资金明细对话框 -->
      <el-dialog v-model="fundDetailsDialogVisible" title="资金明细" width="1200px" v-loading="loadingFundDetails">
        <div style="margin-bottom: 16px; color: #666; font-size: 14px;">
          用户邮箱: <strong>{{ currentFundDetailsUserEmail }}</strong>
        </div>
        
        <el-tabs v-model="fundDetailsActiveTab">
          <!-- 全部 -->
          <el-tab-pane label="全部" name="all">
            <el-table :data="allFundDetails" border stripe max-height="500">
              <el-table-column prop="typeText" label="类型" width="100" />
              <el-table-column prop="id" label="ID" width="80" />
              <el-table-column label="金额" width="120">
                <template #default="{ row }">
                  {{ formatAmount(row.amount) }}
                </template>
              </el-table-column>
              <el-table-column label="盈亏/实际金额" width="120">
                <template #default="{ row }">
                  <span v-if="row.profit != null">{{ formatAmount(row.profit) }}</span>
                  <span v-else-if="row.actualAmount != null">{{ formatAmount(row.actualAmount) }}</span>
                  <span v-else-if="row.repaymentAmount != null">{{ formatAmount(row.repaymentAmount) }}</span>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag 
                    v-if="row.type === 'contract' || row.type === 'option'"
                    :type="getOrderStatusType(row.status)"
                    size="small"
                  >
                    {{ getOrderStatusText(row.status) }}
                  </el-tag>
                  <el-tag 
                    v-else-if="row.type === 'deposit' || row.type === 'withdraw'"
                    :type="getRecordStatusType(row.status)"
                    size="small"
                  >
                    {{ getRecordStatusText(row.status) }}
                  </el-tag>
                  <el-tag 
                    v-else-if="row.type === 'loan'"
                    :type="getLoanStatusType(row.status)"
                    size="small"
                  >
                    {{ getLoanStatusText(row.status) }}
                  </el-tag>
                  <el-tag 
                    v-else-if="row.type === 'financial'"
                    :type="getFinancialStatusType(row.status)"
                    size="small"
                  >
                    {{ getFinancialStatusText(row.status) }}
                  </el-tag>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="详情" min-width="200">
                <template #default="{ row }">
                  <div v-if="row.type === 'contract'">
                    交易对: {{ row.symbol }} | 方向: {{ row.side === 'BUY' ? '买入' : '卖出' }} | 数量: {{ row.quantity }}
                  </div>
                  <div v-else-if="row.type === 'option'">
                    交易对: {{ row.symbol }} | 方向: {{ row.direction === 'UP' ? '買漲' : '買跌' }} | 时长: {{ row.duration }}s
                  </div>
                  <div v-else-if="row.type === 'deposit' || row.type === 'withdraw'">
                    {{ row.typeDetail === 'digital' ? '数字货币' : '银行卡' }} | {{ row.network }}
                  </div>
                  <div v-else-if="row.type === 'loan'">
                    贷款期限: {{ row.days }}天 | 利息: {{ formatAmount(row.totalInterest) }}
                  </div>
                  <div v-else-if="row.type === 'financial'">
                    产品: {{ row.productName }}
                  </div>
                  <div v-else-if="row.type === 'transfer'">
                    {{ row.fromAccount }} → {{ row.toAccount }}
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="180">
                <template #default="{ row }">
                  {{ formatDateTime(row.createdAt || row.purchaseTime) }}
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!loadingFundDetails && allFundDetails.length === 0" description="暂无资金明细" />
          </el-tab-pane>
          
          <!-- 下单 -->
          <el-tab-pane label="下单" name="orders">
            <el-tabs>
              <el-tab-pane label="合约订单">
                <el-table :data="fundDetailsData?.contractOrders || []" border stripe max-height="500">
                  <el-table-column prop="id" label="订单ID" width="100" />
                  <el-table-column prop="symbol" label="交易对" width="120" />
                  <el-table-column prop="side" label="方向" width="80">
                    <template #default="{ row }">
                      {{ row.side === 'BUY' ? '买入' : '卖出' }}
                    </template>
                  </el-table-column>
                  <el-table-column prop="quantity" label="数量" width="100" />
                  <el-table-column prop="margin" label="保证金" width="120">
                    <template #default="{ row }">
                      {{ formatAmount(row.margin) }}
                    </template>
                  </el-table-column>
                  <el-table-column prop="profit" label="盈亏" width="120">
                    <template #default="{ row }">
                      <span :style="{ color: (row.profit || 0) >= 0 ? '#67c23a' : '#f56c6c' }">
                        {{ formatAmount(row.profit) }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="status" label="状态" width="100">
                    <template #default="{ row }">
                      <el-tag :type="getOrderStatusType(row.status)" size="small">
                        {{ getOrderStatusText(row.status) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="createdAt" label="创建时间" width="180">
                    <template #default="{ row }">
                      {{ formatDateTime(row.createdAt) }}
                    </template>
                  </el-table-column>
                </el-table>
                <el-empty v-if="!loadingFundDetails && (!fundDetailsData?.contractOrders || fundDetailsData.contractOrders.length === 0)" description="暂无合约订单" />
              </el-tab-pane>
              
              <el-tab-pane label="期权订单">
                <el-table :data="fundDetailsData?.optionOrders || []" border stripe max-height="500">
                  <el-table-column prop="id" label="订单ID" width="100" />
                  <el-table-column prop="symbol" label="交易对" width="120" />
                  <el-table-column prop="direction" label="方向" width="80">
                    <template #default="{ row }">
                      {{ row.direction === 'UP' ? '買漲' : '買跌' }}
                    </template>
                  </el-table-column>
                  <el-table-column prop="amount" label="金额" width="120">
                    <template #default="{ row }">
                      {{ formatAmount(row.amount) }}
                    </template>
                  </el-table-column>
                  <el-table-column prop="duration" label="时长(秒)" width="100" />
                  <el-table-column prop="profit" label="盈亏" width="120">
                    <template #default="{ row }">
                      <span :style="{ color: (row.profit || 0) >= 0 ? '#67c23a' : '#f56c6c' }">
                        {{ formatAmount(row.profit) }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="status" label="状态" width="100">
                    <template #default="{ row }">
                      <el-tag :type="getOrderStatusType(row.status)" size="small">
                        {{ getOrderStatusText(row.status) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column prop="createdAt" label="创建时间" width="180">
                    <template #default="{ row }">
                      {{ formatDateTime(row.createdAt) }}
                    </template>
                  </el-table-column>
                </el-table>
                <el-empty v-if="!loadingFundDetails && (!fundDetailsData?.optionOrders || fundDetailsData.optionOrders.length === 0)" description="暂无期权订单" />
              </el-tab-pane>
            </el-tabs>
          </el-tab-pane>
          
          <!-- 贷款 -->
          <el-tab-pane label="贷款" name="loans">
            <el-table :data="fundDetailsData?.loans || []" border stripe max-height="500">
              <el-table-column prop="id" label="ID" width="100" />
              <el-table-column prop="amount" label="贷款金额" width="120">
                <template #default="{ row }">
                  {{ formatAmount(row.amount) }}
                </template>
              </el-table-column>
              <el-table-column prop="days" label="期限(天)" width="100" />
              <el-table-column prop="totalInterest" label="总利息" width="120">
                <template #default="{ row }">
                  {{ formatAmount(row.totalInterest) }}
                </template>
              </el-table-column>
              <el-table-column prop="repaymentAmount" label="需还款金额" width="120">
                <template #default="{ row }">
                  {{ formatAmount(row.repaymentAmount) }}
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="getLoanStatusType(row.status)" size="small">
                    {{ getLoanStatusText(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="createdAt" label="创建时间" width="180">
                <template #default="{ row }">
                  {{ formatDateTime(row.createdAt) }}
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!loadingFundDetails && (!fundDetailsData?.loans || fundDetailsData.loans.length === 0)" description="暂无贷款记录" />
          </el-tab-pane>
          
          <!-- 理财 -->
          <el-tab-pane label="理财" name="financial">
            <el-table :data="fundDetailsData?.financialOrders || []" border stripe max-height="500">
              <el-table-column prop="id" label="订单ID" width="100" />
              <el-table-column prop="productName" label="产品名称" width="150" />
              <el-table-column prop="amount" label="购买金额" width="120">
                <template #default="{ row }">
                  {{ formatAmount(row.amount) }}
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="getFinancialStatusType(row.status)" size="small">
                    {{ getFinancialStatusText(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="purchaseTime" label="购买时间" width="180">
                <template #default="{ row }">
                  {{ formatDateTime(row.purchaseTime) }}
                </template>
              </el-table-column>
              <el-table-column prop="maturityTime" label="到期时间" width="180">
                <template #default="{ row }">
                  {{ formatDateTime(row.maturityTime) }}
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!loadingFundDetails && (!fundDetailsData?.financialOrders || fundDetailsData.financialOrders.length === 0)" description="暂无理财订单" />
          </el-tab-pane>
          
          <!-- 出金 -->
          <el-tab-pane label="出金" name="withdraws">
            <el-table :data="fundDetailsData?.withdraws || []" border stripe max-height="500">
              <el-table-column prop="id" label="ID" width="100" />
              <el-table-column prop="type" label="类型" width="100">
                <template #default="{ row }">
                  {{ row.type === 'digital' ? '数字货币' : '银行卡' }}
                </template>
              </el-table-column>
              <el-table-column prop="network" label="网络/币种" width="120" />
              <el-table-column prop="amount" label="提现金额" width="120">
                <template #default="{ row }">
                  {{ formatAmount(row.amount) }}
                </template>
              </el-table-column>
              <el-table-column prop="fee" label="手续费" width="100">
                <template #default="{ row }">
                  {{ formatAmount(row.fee) }}
                </template>
              </el-table-column>
              <el-table-column prop="actualAmount" label="实际到账" width="120">
                <template #default="{ row }">
                  {{ formatAmount(row.actualAmount) }}
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="getRecordStatusType(row.status)" size="small">
                    {{ getRecordStatusText(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="address" label="提币地址/收款账户" min-width="200" />
              <el-table-column prop="createdAt" label="创建时间" width="180">
                <template #default="{ row }">
                  {{ formatDateTime(row.createdAt) }}
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!loadingFundDetails && (!fundDetailsData?.withdraws || fundDetailsData.withdraws.length === 0)" description="暂无提现记录" />
          </el-tab-pane>
          
          <!-- 入金 -->
          <el-tab-pane label="入金" name="deposits">
            <el-table :data="fundDetailsData?.deposits || []" border stripe max-height="500">
              <el-table-column prop="id" label="ID" width="100" />
              <el-table-column prop="type" label="类型" width="100">
                <template #default="{ row }">
                  {{ row.type === 'digital' ? '数字货币' : '银行卡' }}
                </template>
              </el-table-column>
              <el-table-column prop="network" label="网络/币种" width="120" />
              <el-table-column prop="amount" label="充值金额" width="120">
                <template #default="{ row }">
                  {{ formatAmount(row.amount) }}
                </template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="getRecordStatusType(row.status)" size="small">
                    {{ getRecordStatusText(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="address" label="充值地址" min-width="200" />
              <el-table-column prop="createdAt" label="创建时间" width="180">
                <template #default="{ row }">
                  {{ formatDateTime(row.createdAt) }}
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!loadingFundDetails && (!fundDetailsData?.deposits || fundDetailsData.deposits.length === 0)" description="暂无充值记录" />
          </el-tab-pane>
        </el-tabs>
        
        <template #footer>
          <el-button @click="fundDetailsDialogVisible = false">关闭</el-button>
        </template>
      </el-dialog>

      <!-- 修改邀请码对话框 -->
      <el-dialog v-model="inviteCodeDialogVisible" title="修改邀请码" width="500px">
        <el-form label-width="100px">
          <el-form-item label="用户邮箱">
            <span>{{ inviteCodeForm.email }}</span>
          </el-form-item>
          <el-form-item label="当前邀请码">
            <span>{{ inviteCodeForm.currentInviteCode || '-' }}</span>
          </el-form-item>
          <el-form-item label="新邀请码" required>
            <el-input
              v-model="inviteCodeForm.newInviteCode"
              placeholder="请输入新邀请码"
              maxlength="32"
              show-word-limit
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <span class="dialog-footer">
            <el-button @click="inviteCodeDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="handleSaveInviteCode">保存</el-button>
          </span>
        </template>
      </el-dialog>

      <!-- 编辑备注对话框 -->
      <el-dialog v-model="remarkDialogVisible" title="编辑备注" width="500px">
        <el-form label-width="100px">
          <el-form-item label="用户邮箱">
            <span>{{ remarkForm.email }}</span>
          </el-form-item>
          <el-form-item label="当前备注">
            <span>{{ remarkForm.currentRemark || '-' }}</span>
          </el-form-item>
          <el-form-item label="新备注">
            <el-input
              v-model="remarkForm.newRemark"
              type="textarea"
              :rows="4"
              placeholder="请输入备注信息"
              maxlength="500"
              show-word-limit
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <span class="dialog-footer">
            <el-button @click="remarkDialogVisible = false">取消</el-button>
            <el-button type="primary" @click="handleSaveRemark">保存</el-button>
          </span>
        </template>
      </el-dialog>

      <!-- 用户详细对话框 -->
      <el-dialog v-model="userDetailDialogVisible" title="用户详细信息" width="800px" v-loading="loadingUserDetail">
        <el-descriptions v-if="userDetail" :column="2" border>
          <el-descriptions-item label="用户ID">{{ userDetail.id }}</el-descriptions-item>
          <el-descriptions-item label="邮箱">{{ userDetail.email }}</el-descriptions-item>
          <el-descriptions-item label="昵称">{{ userDetail.nickname || '-' }}</el-descriptions-item>
          <el-descriptions-item label="手机号">{{ userDetail.phone || '-' }}</el-descriptions-item>
          <el-descriptions-item label="用户类型">
            <el-tag v-if="userDetail.userType === 'agent'" type="warning">代理</el-tag>
            <el-tag v-else type="success">普通用户</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag v-if="userDetail.status === 'active' || userDetail.status === 'normal'" type="success">正常</el-tag>
            <el-tag v-else-if="userDetail.status === 'frozen'" type="warning">冻结</el-tag>
            <el-tag v-else-if="userDetail.status === 'banned' || userDetail.status === 'disabled'" type="danger">禁用</el-tag>
            <el-tag v-else>{{ userDetail.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="邀请码">{{ userDetail.myInviteCode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="上级用户">{{ userDetail.parentUserEmail || '-' }}</el-descriptions-item>
          <el-descriptions-item label="资金账户余额">{{ userDetail.fundBalance || 0 }}</el-descriptions-item>
          <el-descriptions-item label="合约资产余额">{{ userDetail.contractBalance || 0 }}</el-descriptions-item>
          <el-descriptions-item label="期权账户余额">{{ userDetail.optionBalance || 0 }}</el-descriptions-item>
          <el-descriptions-item label="最后登录IP">{{ userDetail.lastLoginIp || '-' }}</el-descriptions-item>
          <el-descriptions-item label="最后登录地区">{{ userDetail.lastLoginRegion || '-' }}</el-descriptions-item>
          <el-descriptions-item label="最后登录时间">{{ userDetail.lastLoginAt || '-' }}</el-descriptions-item>
          <el-descriptions-item label="注册时间">{{ userDetail.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ userDetail.updatedAt }}</el-descriptions-item>
        </el-descriptions>
        <template #footer>
          <el-button @click="userDetailDialogVisible = false">关闭</el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<style scoped>
.users-page {
  padding: 0;
}
</style>
