<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox, ElTabs, ElTabPane } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { useAccountTable } from '@/utils/useAccountTable'
import { accountTableRequest } from '@/utils/accountTableRequest'
import AccountTypeFilter from '@/components/AccountTypeFilter.vue'
const accountTable = useAccountTable()
const accountModes = accountTable.modes
const request = accountTableRequest(accountTable)
function accountFilterChanged() { contractOrders.value=[];optionOrders.value=[];contractTotal.value=0;optionTotal.value=0;contractQueryParams.value.page=0;optionQueryParams.value.page=0;loadContractOrders();loadOptionOrders() }
import ManualContractOrder from '@/components/ManualContractOrder.vue'
const manualForm = ref<InstanceType<typeof ManualContractOrder>>()
import SimpleManualContractOrder from '@/components/SimpleManualContractOrder.vue'
const simpleManualForm = ref<InstanceType<typeof SimpleManualContractOrder>>()
import { useAuthStore } from '@/store/auth'
import { usePermissions } from '@/composables/usePermissions'
import { displaySymbol } from '@/utils/displaySymbol'
import { formatPrice } from '@/utils/formatPrice'

const auth = useAuthStore()
const { isAgent, hasPermission } = usePermissions()

// 权限状态
const canCloseOrder = ref(true)
const canCancelOrder = ref(true)
const canSetProfit = ref(true)
const canSetLoss = ref(true)
const canClearPreset = ref(true)
const canDeleteOrder = ref(false)
const canRestoreOrder = ref(false)

// 加载权限
const loadPermissions = async () => {
  canDeleteOrder.value = !isAgent.value && await hasPermission('orders', 'delete_order')
  canRestoreOrder.value = !isAgent.value && await hasPermission('orders', 'restore_order')
  if (!isAgent.value) return
  canCloseOrder.value = await hasPermission('orders', 'close_order')
  canCancelOrder.value = await hasPermission('orders', 'cancel_order')
  canSetProfit.value = await hasPermission('orders', 'set_profit')
  canSetLoss.value = await hasPermission('orders', 'set_loss')
  canClearPreset.value = await hasPermission('orders', 'clear_preset')
}

const activeTab = ref('contract')
const contractOrders = ref<any[]>([])
const optionOrders = ref<any[]>([])
const contractTotal = ref(0)
const optionTotal = ref(0)
const loading = ref(false)
const agentList = ref<any[]>([])

const contractQueryParams = ref({
  binding: '',
  userId: '',
  userEmail: '',
  status: '',
  deletion: '',
  filterAgentId: null as number | null,
  page: 0,
  size: 10,
})

const optionQueryParams = ref({
  userId: '',
  userEmail: '',
  status: '',
  deletion: '',
  filterAgentId: null as number | null,
  page: 0,
  size: 10,
})

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

const loadContractOrders = async () => {
  loading.value = true
  try {
    const params: any = { ...contractQueryParams.value }
    // 如果是代理，不传递filterAgentId
    if (isAgent.value) {
      delete params.filterAgentId
    } else if (!params.filterAgentId) {
      delete params.filterAgentId
    }
    const res: any = await request.post('/admin/orders/contract/query', params)
    contractOrders.value = res.list || []
    contractTotal.value = res.total || 0
  } catch (e: any) {
    ElMessage.error(e?.message || '加载合约订单失败')
  } finally {
    loading.value = false
  }
}

const loadOptionOrders = async () => {
  loading.value = true
  try {
    const params: any = { ...optionQueryParams.value }
    // 如果是代理，不传递filterAgentId
    if (isAgent.value) {
      delete params.filterAgentId
    } else if (!params.filterAgentId) {
      delete params.filterAgentId
    }
    const res: any = await request.post('/admin/orders/option/query', params)
    optionOrders.value = res.list || []
    optionTotal.value = res.total || 0
  } catch (e: any) {
    ElMessage.error(e?.message || '加载期货订单失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  if (activeTab.value === 'contract') {
    contractQueryParams.value.page = 0
    loadContractOrders()
  } else {
    optionQueryParams.value.page = 0
    loadOptionOrders()
  }
}

const handleReset = () => {
  if (activeTab.value === 'contract') {
    contractQueryParams.value.binding = ''
    contractQueryParams.value.userId = ''
    contractQueryParams.value.userEmail = ''
    contractQueryParams.value.status = ''
    contractQueryParams.value.deletion = ''
    contractQueryParams.value.filterAgentId = null
    contractQueryParams.value.page = 0
    loadContractOrders()
  } else {
    optionQueryParams.value.userId = ''
    optionQueryParams.value.userEmail = ''
    optionQueryParams.value.status = ''
    optionQueryParams.value.deletion = ''
    optionQueryParams.value.filterAgentId = null
    optionQueryParams.value.page = 0
    loadOptionOrders()
  }
}

// 解析交易对符号，提取基础货币和计价货币
function parseSymbol(symbol: string): { baseCurrency: string; quoteCurrency: string } {
  if (!symbol) {
    return { baseCurrency: '', quoteCurrency: '' }
  }
  if (/=X$/i.test(symbol)) {
    const [baseCurrency, quoteCurrency] = displaySymbol(symbol).split('/')
    if (baseCurrency && quoteCurrency) return { baseCurrency, quoteCurrency }
  }
  
  // 常见的计价货币列表（通常是3-4个字符）
  const commonQuoteCurrencies = ['USD', 'USDT', 'EUR', 'GBP', 'JPY', 'CNY', 'BTC', 'ETH']
  
  // 尝试匹配常见的计价货币
  for (const quote of commonQuoteCurrencies) {
    if (symbol.endsWith(quote)) {
      const baseCurrency = symbol.substring(0, symbol.length - quote.length)
      return { baseCurrency, quoteCurrency: quote }
    }
  }
  
  // 如果无法匹配，尝试从末尾提取3-4个字符作为计价货币
  if (symbol.length >= 6) {
    const quoteCurrency = symbol.substring(symbol.length - 3)
    const baseCurrency = symbol.substring(0, symbol.length - 3)
    return { baseCurrency, quoteCurrency }
  }
  
  // 默认情况
  return { baseCurrency: symbol, quoteCurrency: 'USD' }
}

const handleSetPresetProfit = async (row: any, presetType: string) => {
  accountTable.selectRow(row)
  try {
    await ElMessageBox.confirm(
      `确定要将订单 ${row.id} 设置为${presetType === 'PROFIT' ? '盈利' : '亏损'}吗？倒计时结束后将按此设置自动平仓。`,
      '提示',
      {
        type: 'warning',
      }
    )
    await request.post(`/admin/orders/option/${row.id}/preset-profit`, {
      presetType: presetType
    })
    ElMessage.success('设置成功')
    loadOptionOrders()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || e?.message || '设置失败')
    }
  }
}

const handleClearPreset = async (row: any) => {
  accountTable.selectRow(row)
  try {
    await ElMessageBox.confirm(
      `确定要清除订单 ${row.id} 的预设盈亏设置吗？`,
      '提示',
      {
        type: 'warning',
      }
    )
    await request.post(`/admin/orders/option/${row.id}/clear-preset`)
    ElMessage.success('清除成功')
    loadOptionOrders()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || e?.message || '清除失败')
    }
  }
}

const formatMoney = (v: number | string | null | undefined) => {
  const n = Number(v || 0)
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const formatDate = (date: string | null) => {
  if (!date) return '-'
  return new Date(date).toLocaleString('zh-CN')
}

const exitRetries = new Map<string, {requestId: string, reason: string}>()
async function controlledContractExit(row: any, command: 'close' | 'cancel') {
  accountTable.selectRow(row)
  const key = `${row.id}:${command}`
  try {
    const input = await ElMessageBox.prompt(command === 'close' ? '使用服务端新鲜行情平仓。请输入受控处理原因（5至500字）' : '请输入受控撤单原因（5至500字）', '受控退出', {inputValue: exitRetries.get(key)?.reason || '', inputValidator: value => !!value && value.trim().length >= 5 && value.length <= 500 || '请输入5至500字原因'})
    const prior = exitRetries.get(key)
    const body = prior?.reason === input.value ? prior : {requestId: crypto.randomUUID(), reason: input.value}
    exitRetries.set(key, body)
    const res: any = await request.post(`/admin/orders/contract/${row.id}/${command}`, body)
    if (res.success === false) throw new Error(res.message || '处理失败')
    exitRetries.delete(key)
    ElMessage.success('受控退出已完成并留痕')
    await loadContractOrders()
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.response?.data?.message || e?.message || '处理失败；重试保留原幂等键')
  }
}
const handleCloseOrder = (row: any) => controlledContractExit(row, 'close')
const handleCancelOrder = (row: any) => controlledContractExit(row, 'cancel')

// Soft deletion only changes visibility; no settlement or balance mutation.
async function handleDeletion(row: any, type: 'contract' | 'option') {
  const action = row.deleted ? '恢复' : '删除'
  if (!row.deleted && !['CLOSED', 'CANCELLED'].includes(row.status)) {
    ElMessage.warning('请先平仓或撤单，再删除订单；删除不会结算或退还资金')
    return
  }
  try {
    await ElMessageBox.confirm(row.deleted
      ? `恢复订单 ${row.id}？恢复后用户可重新查看，资金及原交易状态不变。`
      : `删除订单 ${row.id}？用户端将隐藏，后台保留并可恢复，资金及原交易状态不变。`, `${action}订单`, { type: 'warning' })
    const endpoint = `/admin/orders/${type}/${row.id}`
    const res: any = row.deleted ? await request.post(`${endpoint}/restore`) : await request.delete(endpoint)
    if (res?.success === false) throw new Error(res.message || `${action}失败`)
    ElMessage.success(`${action}成功`)
    await (type === 'contract' ? loadContractOrders() : loadOptionOrders())
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.response?.data?.message || e?.message || `${action}失败`)
  }
}

onMounted(() => {
  loadAgents()
  loadContractOrders()
  loadOptionOrders()
  loadPermissions()
})
</script>

<template>
  <div class="orders-page">
    <h2>订单管理</h2>
    
    <el-tabs v-model="activeTab" @tab-change="() => {}">
      <el-tab-pane label="合约订单" name="contract">
        <el-button v-permission="'orders:manual_order'" v-if="auth.user?.isSuperAdmin || auth.user?.role === 'super_admin'" type="primary" @click="simpleManualForm?.open()" :disabled="accountModes.length !== 1 || accountModes[0] !== 'REAL'">生成订单（简版）</el-button>
        <el-button v-permission="'orders:manual_order'" v-if="auth.user?.isSuperAdmin || auth.user?.role === 'super_admin'" @click="manualForm?.open()" :disabled="accountModes.length !== 1 || accountModes[0] !== 'REAL'">高级版</el-button>
        <SimpleManualContractOrder v-if="accountModes.length === 1 && accountModes[0] === 'REAL'" ref="simpleManualForm" @created="loadContractOrders" />
        <ManualContractOrder v-if="accountModes.length === 1 && accountModes[0] === 'REAL'" ref="manualForm" @created="loadContractOrders" />
        <div class="toolbar">
          <div class="search-form">
            <!-- 代理筛选（只有管理员能看到） -->
            <el-select 
              v-if="!isAgent"
              v-model="contractQueryParams.filterAgentId" 
              placeholder="筛选代理" 
              @change="loadContractOrders" 
              clearable
              style="width: 200px; margin-right: 12px;"
            >
              <el-option label="全部代理" value="" />
              <el-option 
                v-for="agent in agentList" 
                :key="agent.id" 
                :label="agent.name" 
                :value="agent.id"
              />
            </el-select>
            <el-select v-model="contractQueryParams.binding" clearable placeholder="用户绑定状态" style="width: 150px; margin-right: 12px;"><el-option value="unbound" label="未绑定用户" /><el-option value="bound" label="已绑定用户" /></el-select>
            <el-input
              v-model="contractQueryParams.userId"
              placeholder="用户ID"
              style="width: 150px; margin-right: 12px;"
              clearable
            />
            <el-input
              v-model="contractQueryParams.userEmail"
              placeholder="用户邮箱"
              style="width: 200px; margin-right: 12px;"
              clearable
            />
            <el-select v-model="contractQueryParams.deletion" placeholder="删除状态" style="width: 150px; margin-right: 12px;" @change="handleSearch">
              <el-option label="全部记录" value="" />
              <el-option label="未删除" value="active" />
              <el-option label="已删除" value="deleted" />
            </el-select>
            <el-select
              v-model="contractQueryParams.status"
              placeholder="订单状态"
              style="width: 150px; margin-right: 12px;"
              clearable
            >
              <el-option label="全部" value="" />
              <el-option label="挂单中" value="PENDING" />
              <el-option label="持仓中" value="OPEN" />
              <el-option label="已平仓" value="CLOSED" />
              <el-option label="已取消" value="CANCELLED" />
            </el-select>
            <el-button v-permission="'orders:view'" type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button v-permission="'orders:view'" :icon="Refresh" @click="handleReset">重置</el-button>
          </div>
        </div>

<AccountTypeFilter v-model="accountModes" @change="accountFilterChanged" />
        <admin-table :row-key="(row: any) => `${row.accountMode || 'REAL'}:${row.id ?? row.userId}:${row.type || ''}`" table-key="Orders.1" :row-class-name="({ row }: { row: any }) => row.deleted ? 'deleted-order' : ''" :data="contractOrders" v-loading="loading" border>
<el-table-column prop="accountModeLabel" label="账户类型" width="110" fixed="left" />
          <el-table-column prop="id" label="订单ID" width="100" />
          <el-table-column prop="userId" label="用户ID" width="200">
            <template #default="{ row }">
              <span v-if="row.agentInfo">{{ row.userId }}({{ row.agentInfo }})</span>
              <span v-else>{{ row.userId ?? '未绑定用户' }}</span>
              <small v-if="row.orderSource === 'MANUAL_TEST'" style="margin-left: 6px; color: #909399;">模拟单</small>
            </template>
          </el-table-column>
          <el-table-column prop="userEmail" label="用户邮箱" min-width="200" show-overflow-tooltip />
        <el-table-column prop="userRemark" label="用户备注" width="150">
            <template #default="{ row }">
              {{ row.userRemark || '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="symbol" label="交易对" width="120"><template #default="{ row }">{{ displaySymbol(row) }}</template></el-table-column>
          <el-table-column prop="side" label="方向" width="80">
            <template #default="{ row }">
              <el-tag :type="row.side === 'BUY' ? 'success' : 'danger'">
                {{ row.side === 'BUY' ? '买入' : '卖出' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="80">
            <template #default="{ row }">
              {{ row.type === 'MARKET' ? '市价' : '限价' }}
            </template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="120">
            <template #default="{ row }">
              {{ row.quantity }} {{ row.quantityUnitType === 'BASE_ASSET' ? row.quantityAsset : row.quantityUnitType === 'SHARE' ? '股' : '手' }}
            </template>
          </el-table-column>
          <el-table-column prop="openPrice" label="开仓价" width="120">
            <template #default="{ row }">
              {{ formatPrice(row.openPrice) }}
            </template>
          </el-table-column>
          <el-table-column prop="currentPrice" label="当前价/平仓价" width="120">
            <template #default="{ row }">
              {{ formatPrice(row.status === 'CLOSED' ? row.closePrice : row.currentPrice) }}
            </template>
          </el-table-column>
          <el-table-column prop="stopLoss" label="止损" width="100">
            <template #default="{ row }">
              <span v-if="row.stopLoss">{{ formatPrice(row.stopLoss) }}</span>
              <span v-else style="color: #999;">设置</span>
            </template>
          </el-table-column>
          <el-table-column prop="takeProfit" label="止盈" width="100">
            <template #default="{ row }">
              <span v-if="row.takeProfit">{{ formatPrice(row.takeProfit) }}</span>
              <span v-else style="color: #999;">设置</span>
            </template>
          </el-table-column>
          <el-table-column prop="profit" label="盈亏" width="120">
            <template #default="{ row }">
              <span :style="{ color: Number(row.profit || 0) >= 0 ? '#67c23a' : '#f56c6c' }">
                {{ formatMoney(row.profit) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.deleted" type="info">已删除</el-tag>
              <el-tag v-else :type="row.status === 'OPEN' ? 'success' : row.status === 'CLOSED' ? 'info' : 'warning'">
                {{ row.status === 'PENDING' ? '挂单中' : row.status === 'OPEN' ? '持仓中' : row.status === 'CLOSED' ? '已平仓' : '已取消' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" width="180">
            <template #default="{ row }">
              {{ formatDate(row.createdAt) }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <div style="display: flex; gap: 8px; flex-wrap: wrap;">
                <el-button v-permission="'orders:manual_order'" v-if="!row.deleted && row.status === 'CLOSED' && row.userId == null && row.orderSource === 'MANUAL_TEST' && (auth.user?.isSuperAdmin || auth.user?.role === 'super_admin')" type="primary" size="small" :disabled="accountModes.length !== 1 || accountModes[0] !== 'REAL'" @click="simpleManualForm?.openBinding(row)">绑定用户</el-button>
                <el-button v-permission="'orders:close_order'"
                  v-if="!row.deleted && row.status === 'OPEN'"
                  type="success" 
                  size="small" 
                  @click="handleCloseOrder(row)"
                 :disabled="accountModes.includes('DEMO') || !accountModes.length">
                  平仓
                </el-button>
                <el-button v-permission="'orders:cancel_order'"
                  v-if="!row.deleted && row.status === 'PENDING'"
                  type="warning" 
                  size="small" 
                  @click="handleCancelOrder(row)"
                 :disabled="accountModes.includes('DEMO') || !accountModes.length">
                  撤单
                </el-button>
                <el-button v-permission="row.deleted ? 'orders:restore_order' : 'orders:delete_order'"
                  v-if="!isAgent"
                  type="danger" 
                  size="small" 
                  @click="handleDeletion(row, 'contract')"
                >
                  {{ row.deleted ? '恢复订单' : '删除订单' }}
                </el-button>
              </div>
            </template>
          </el-table-column>
        </admin-table>

        <div class="pagination">
          <el-pagination
            :current-page="contractQueryParams.page + 1"
            :page-size="contractQueryParams.size"
            :total="contractTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="(page: number) => contractQueryParams.page = page - 1"
            @size-change="(size: number) => contractQueryParams.size = size"
            @change="loadContractOrders"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane label="期货订单" name="option">
        <div class="toolbar">
          <div class="search-form">
            <!-- 代理筛选（只有管理员能看到） -->
            <el-select 
              v-if="!isAgent"
              v-model="optionQueryParams.filterAgentId" 
              placeholder="筛选代理" 
              @change="loadOptionOrders" 
              clearable
              style="width: 200px; margin-right: 12px;"
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
              v-model="optionQueryParams.userId"
              placeholder="用户ID"
              style="width: 150px; margin-right: 12px;"
              clearable
            />
            <el-input
              v-model="optionQueryParams.userEmail"
              placeholder="用户邮箱"
              style="width: 200px; margin-right: 12px;"
              clearable
            />
            <el-select v-model="optionQueryParams.deletion" placeholder="删除状态" style="width: 150px; margin-right: 12px;" @change="handleSearch">
              <el-option label="全部记录" value="" />
              <el-option label="未删除" value="active" />
              <el-option label="已删除" value="deleted" />
            </el-select>
            <el-select
              v-model="optionQueryParams.status"
              placeholder="订单状态"
              style="width: 150px; margin-right: 12px;"
              clearable
            >
              <el-option label="全部" value="" />
              <el-option label="交易中" value="TRADING" />
              <el-option label="已平仓" value="CLOSED" />
              <el-option label="已取消" value="CANCELLED" />
            </el-select>
            <el-button v-permission="'orders:view'" type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button v-permission="'orders:view'" :icon="Refresh" @click="handleReset">重置</el-button>
          </div>
        </div>

<AccountTypeFilter v-model="accountModes" @change="accountFilterChanged" />
        <admin-table :row-key="(row: any) => `${row.accountMode || 'REAL'}:${row.id ?? row.userId}:${row.type || ''}`" table-key="Orders.2" :row-class-name="({ row }: { row: any }) => row.deleted ? 'deleted-order' : ''" :data="optionOrders" v-loading="loading" border>
<el-table-column prop="accountModeLabel" label="账户类型" width="110" fixed="left" />
          <el-table-column prop="id" label="订单ID" width="100" />
          <el-table-column prop="userId" label="用户ID" width="200">
            <template #default="{ row }">
              <span v-if="row.agentInfo">{{ row.userId }}({{ row.agentInfo }})</span>
              <span v-else>{{ row.userId }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="userEmail" label="用户邮箱" min-width="200" show-overflow-tooltip />
        <el-table-column prop="userRemark" label="用户备注" width="150">
            <template #default="{ row }">
              {{ row.userRemark || '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="symbol" label="交易对" width="120"><template #default="{ row }">{{ displaySymbol(row) }}</template></el-table-column>
          <el-table-column prop="direction" label="方向" width="120">
            <template #default="{ row }">
              <el-tag :type="row.direction === 'UP' ? 'success' : 'danger'">
                {{ row.direction === 'UP' ? `买涨:${parseSymbol(row.symbol).baseCurrency}` : `买跌:${parseSymbol(row.symbol).quoteCurrency}` }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="amount" label="金额" width="120">
            <template #default="{ row }">
              {{ formatMoney(row.amount) }}
            </template>
          </el-table-column>
          <el-table-column prop="openPrice" label="开仓价" width="120">
            <template #default="{ row }">
              {{ formatPrice(row.openPrice) }}
            </template>
          </el-table-column>
          <el-table-column prop="closePrice" label="平仓价" width="120">
            <template #default="{ row }">
              {{ formatPrice(row.closePrice) }}
            </template>
          </el-table-column>
          <el-table-column prop="profit" label="盈亏" width="120">
            <template #default="{ row }">
              <span :style="{ color: Number(row.profit || 0) >= 0 ? '#67c23a' : '#f56c6c' }">
                {{ formatMoney(row.profit) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="duration" label="时长(秒)" width="100">
            <template #default="{ row }">
              {{ row.duration || '-' }}
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.deleted" type="info">已删除</el-tag>
              <el-tag v-else :type="row.status === 'TRADING' ? 'success' : row.status === 'CLOSED' ? 'info' : 'warning'">
                {{ row.status === 'TRADING' ? '交易中' : row.status === 'CLOSED' ? '已平仓' : '已取消' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="创建时间" width="180">
            <template #default="{ row }">
              {{ formatDate(row.createdAt) }}
            </template>
          </el-table-column>
          <el-table-column prop="presetProfitType" label="预设盈亏" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.presetProfitType === 'PROFIT'" type="success">盈利</el-tag>
              <el-tag v-else-if="row.presetProfitType === 'LOSS'" type="danger">亏损</el-tag>
              <span v-else style="color: #999;">未设置</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <div style="display: flex; gap: 8px; flex-wrap: wrap;">
                <el-button v-permission="'orders:set_profit'"
                  v-if="!row.deleted && row.status === 'TRADING'"
                  type="success" 
                  size="small" 
                  @click="handleSetPresetProfit(row, 'PROFIT')"
                 :disabled="accountModes.includes('DEMO') || !accountModes.length">
                  设为盈利
                </el-button>
                <el-button v-permission="'orders:set_loss'"
                  v-if="!row.deleted && row.status === 'TRADING'"
                  type="danger" 
                  size="small" 
                  @click="handleSetPresetProfit(row, 'LOSS')"
                 :disabled="accountModes.includes('DEMO') || !accountModes.length">
                  设为亏损
                </el-button>
                <el-button v-permission="'orders:clear_preset'"
                  v-if="!row.deleted && row.status === 'TRADING' && row.presetProfitType"
                  type="info" 
                  size="small" 
                  @click="handleClearPreset(row)"
                 :disabled="accountModes.includes('DEMO') || !accountModes.length">
                  清除预设
                </el-button>
                <el-button v-permission="row.deleted ? 'orders:restore_order' : 'orders:delete_order'"
                  v-if="!isAgent"
                  type="danger" 
                  size="small" 
                  @click="handleDeletion(row, 'option')"
                >
                  {{ row.deleted ? '恢复订单' : '删除订单' }}
                </el-button>
              </div>
            </template>
          </el-table-column>
        </admin-table>

        <div class="pagination">
          <el-pagination
            :current-page="optionQueryParams.page + 1"
            :page-size="optionQueryParams.size"
            :total="optionTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="(page: number) => optionQueryParams.page = page - 1"
            @size-change="(size: number) => optionQueryParams.size = size"
            @change="loadOptionOrders"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.orders-page :deep(.deleted-order) { --el-table-tr-bg-color: #f2f3f5; color: #909399; }
.orders-page :deep(.deleted-order .cell) { filter: grayscale(1); }

.orders-page {
  padding: 20px;
}

h2 {
  margin: 0 0 20px;
}

.toolbar {
  margin-bottom: 20px;
}

.search-form {
  display: flex;
  align-items: center;
}

.pagination {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}
</style>
