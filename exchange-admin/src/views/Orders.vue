<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox, ElTabs, ElTabPane } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { usePermissions } from '@/composables/usePermissions'

const auth = useAuthStore()
const { isAgent, hasPermission } = usePermissions()

// 权限状态
const canCloseOrder = ref(true)
const canCancelOrder = ref(true)
const canSetProfit = ref(true)
const canSetLoss = ref(true)
const canClearPreset = ref(true)

// 加载权限
const loadPermissions = async () => {
  if (!isAgent.value) return
  canCloseOrder.value = await hasPermission('orders', 'close_order')
  canCancelOrder.value = await hasPermission('orders', 'cancel_order')
  canSetProfit.value = await hasPermission('orders', 'set_profit')
  canSetLoss.value = await hasPermission('orders', 'set_loss')
  canClearPreset.value = await hasPermission('orders', 'clear_preset')
}

const activeTab = ref('option')
const contractOrders = ref<any[]>([])
const optionOrders = ref<any[]>([])
const contractTotal = ref(0)
const optionTotal = ref(0)
const loading = ref(false)
const agentList = ref<any[]>([])

const contractQueryParams = ref({
  userId: '',
  userEmail: '',
  status: '',
  filterAgentId: null as number | null,
  page: 0,
  size: 20,
})

const optionQueryParams = ref({
  userId: '',
  userEmail: '',
  status: '',
  filterAgentId: null as number | null,
  page: 0,
  size: 20,
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
    contractQueryParams.value.userId = ''
    contractQueryParams.value.userEmail = ''
    contractQueryParams.value.status = ''
    contractQueryParams.value.filterAgentId = null
    contractQueryParams.value.page = 0
    loadContractOrders()
  } else {
    optionQueryParams.value.userId = ''
    optionQueryParams.value.userEmail = ''
    optionQueryParams.value.status = ''
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

const handleCloseOrder = async (row: any) => {
  try {
    const closePrice = await ElMessageBox.prompt('请输入平仓价格', '平仓', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPattern: /^\d+(\.\d+)?$/,
      inputErrorMessage: '请输入有效的价格',
      inputValue: row.currentPrice || row.openPrice || '0'
    })
    
    if (closePrice.value) {
      const res: any = await request.post(`/admin/orders/contract/${row.id}/close`, {
        closePrice: closePrice.value
      })
      
      if (res.success !== false) {
        ElMessage.success('平仓成功')
        loadContractOrders()
      } else {
        ElMessage.error(res.message || '平仓失败')
      }
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || e?.message || '平仓失败')
    }
  }
}

const handleCancelOrder = async (row: any) => {
  try {
    await ElMessageBox.confirm('确定要撤单吗？', '撤单', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    
    const res: any = await request.post(`/admin/orders/contract/${row.id}/cancel`)
    
    if (res.success !== false) {
      ElMessage.success('撤单成功')
      loadContractOrders()
    } else {
      ElMessage.error(res.message || '撤单失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || e?.message || '撤单失败')
    }
  }
}

// 判断是否是异常订单（未正常平仓的订单）
function isAbnormalOrder(row: any, type: 'contract' | 'option'): boolean {
  if (type === 'contract') {
    // 合约订单：状态为 OPEN（持仓中）但可能已经异常
    // 可以根据业务需求添加更多判断条件，比如创建时间超过一定期限等
    return row.status === 'OPEN'
  } else {
    // 期货订单：状态为 TRADING（交易中）但可能已经异常
    // 可以根据业务需求添加更多判断条件，比如创建时间超过一定期限等
    return row.status === 'TRADING'
  }
}

// 异常处理删除订单
async function handleAbnormalDelete(row: any, type: 'contract' | 'option') {
  try {
    await ElMessageBox.confirm(
      `确定要删除该异常订单吗？\n订单ID: ${row.id}\n用户ID: ${row.userId}\n\n此操作将删除订单记录和用户的订单记录，且无法恢复！`,
      '异常处理 - 确认删除',
      {
        confirmButtonText: '确定删除',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: false
      }
    )
    
    const endpoint = type === 'contract' 
      ? `/admin/orders/contract/${row.id}/abnormal-delete`
      : `/admin/orders/option/${row.id}/abnormal-delete`
    
    const res: any = await request.delete(endpoint)
    
    if (res && res.success !== false) {
      ElMessage.success('订单删除成功')
      if (type === 'contract') {
        loadContractOrders()
      } else {
        loadOptionOrders()
      }
    } else {
      ElMessage.error(res?.message || '删除失败')
    }
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error(e?.response?.data?.message || e?.message || '删除失败')
    }
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
            <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          </div>
        </div>

        <el-table :data="contractOrders" v-loading="loading" border>
          <el-table-column prop="id" label="订单ID" width="100" />
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
          <el-table-column prop="symbol" label="交易对" width="120" />
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
              {{ formatMoney(row.quantity) }}
            </template>
          </el-table-column>
          <el-table-column prop="openPrice" label="开仓价" width="120">
            <template #default="{ row }">
              {{ formatMoney(row.openPrice) }}
            </template>
          </el-table-column>
          <el-table-column prop="currentPrice" label="当前价/平仓价" width="120">
            <template #default="{ row }">
              {{ formatMoney(row.status === 'CLOSED' && row.closePrice ? row.closePrice : row.currentPrice) }}
            </template>
          </el-table-column>
          <el-table-column prop="stopLoss" label="止损" width="100">
            <template #default="{ row }">
              <span v-if="row.stopLoss">{{ formatMoney(row.stopLoss) }}</span>
              <span v-else style="color: #999;">设置</span>
            </template>
          </el-table-column>
          <el-table-column prop="takeProfit" label="止盈" width="100">
            <template #default="{ row }">
              <span v-if="row.takeProfit">{{ formatMoney(row.takeProfit) }}</span>
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
              <el-tag :type="row.status === 'OPEN' ? 'success' : row.status === 'CLOSED' ? 'info' : 'warning'">
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
                <el-button 
                  v-if="row.status === 'OPEN' && canCloseOrder" 
                  type="success" 
                  size="small" 
                  @click="handleCloseOrder(row)"
                >
                  平仓
                </el-button>
                <el-button 
                  v-if="row.status === 'PENDING' && canCancelOrder" 
                  type="warning" 
                  size="small" 
                  @click="handleCancelOrder(row)"
                >
                  撤单
                </el-button>
                <el-button 
                  v-if="isAbnormalOrder(row, 'contract')" 
                  type="danger" 
                  size="small" 
                  @click="handleAbnormalDelete(row, 'contract')"
                >
                  异常处理
                </el-button>
                <span v-if="row.status !== 'OPEN' && row.status !== 'PENDING' && !isAbnormalOrder(row, 'contract')" style="color: #999; font-size: 12px;">
                  -
                </span>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination">
          <el-pagination
            v-model:current-page="contractQueryParams.page"
            :page-size="contractQueryParams.size"
            :total="contractTotal"
            layout="total, prev, pager, next"
            @current-change="loadContractOrders"
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
            <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
            <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          </div>
        </div>

        <el-table :data="optionOrders" v-loading="loading" border>
          <el-table-column prop="id" label="订单ID" width="100" />
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
          <el-table-column prop="symbol" label="交易对" width="120" />
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
              {{ formatMoney(row.openPrice) }}
            </template>
          </el-table-column>
          <el-table-column prop="closePrice" label="平仓价" width="120">
            <template #default="{ row }">
              {{ formatMoney(row.closePrice) }}
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
              <el-tag :type="row.status === 'TRADING' ? 'success' : row.status === 'CLOSED' ? 'info' : 'warning'">
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
                <el-button 
                  v-if="row.status === 'TRADING' && canSetProfit"
                  type="success" 
                  size="small" 
                  @click="handleSetPresetProfit(row, 'PROFIT')"
                >
                  设为盈利
                </el-button>
                <el-button 
                  v-if="row.status === 'TRADING' && canSetLoss"
                  type="danger" 
                  size="small" 
                  @click="handleSetPresetProfit(row, 'LOSS')"
                >
                  设为亏损
                </el-button>
                <el-button 
                  v-if="row.status === 'TRADING' && row.presetProfitType && canClearPreset"
                  type="info" 
                  size="small" 
                  @click="handleClearPreset(row)"
                >
                  清除预设
                </el-button>
                <el-button 
                  v-if="isAbnormalOrder(row, 'option')" 
                  type="danger" 
                  size="small" 
                  @click="handleAbnormalDelete(row, 'option')"
                >
                  异常处理
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>

        <div class="pagination">
          <el-pagination
            v-model:current-page="optionQueryParams.page"
            :page-size="optionQueryParams.size"
            :total="optionTotal"
            layout="total, prev, pager, next"
            @current-change="loadOptionOrders"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
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

