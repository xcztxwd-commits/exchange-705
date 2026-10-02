<script setup lang="ts">
import { ref, computed } from 'vue'
import request from '@/utils/request'
import { formatPrice } from '@/utils/formatPrice'
import { can } from '@/utils/access'
import { useAuthStore } from '@/store/auth'
const auth = useAuthStore()
const visible = ref(false), mode = ref('DEMO'), kind = ref('wallets'), userId = ref(''), userEmail = ref(''), status = ref('')
const page = ref(1), total = ref(0), loading = ref(false), error = ref(''), rows = ref<any[]>([]), columns = ref<string[]>([])
const categories = [
  ['users', '用户', 'users'], ['wallets', '钱包余额', 'users'], ['contracts', '合约订单', 'orders'],
  ['options', '期权订单', 'orders'], ['deposits', '充值记录', 'deposit_orders'], ['withdrawals', '提现记录', 'withdraw_review'],
  ['transfers', '资金划转', 'users'], ['loans', '贷款与还款', 'loan_review'], ['financial', '理财订单', 'financial_orders'], ['equity', '资产历史', 'users'],
]
const allowed = computed(() => categories.filter(item => can(`${item[2]}:view`)))
const enabled = computed(() => can('users:view') && (auth.user?.userType === 'admin' || auth.user?.isSuperAdmin))
const hasStatus = computed(() => !['wallets', 'transfers', 'equity'].includes(kind.value))
const labels: Record<string, string> = { id:'记录 ID',user_id:'用户 UID',email:'用户邮箱',remark:'用户备注',user_email:'用户邮箱',user_remark:'用户备注',nickname:'昵称',status:'状态',kyc_status:'实名状态',coin:'钱包类型',available:'可用余额',frozen:'冻结余额',updated_at:'更新时间',created_at:'创建时间',symbol:'品种',side:'方向',type:'类型',quantity:'数量',open_price:'开仓价',close_price:'平仓价',profit:'盈亏',margin:'保证金',fee:'手续费',direction:'方向',amount:'金额',duration:'周期（秒）',currency:'币种',account_type:'账户类型',actual_amount:'到账金额',from_account:'转出账户',to_account:'转入账户',days:'借款天数',total_interest:'总利息',overdue_fee:'逾期费用',repayment_amount:'应还金额',contract_signed:'合同签署',repayment_date:'到期日',actual_repayment_at:'实际还款时间',product_name:'产品',purchase_amount:'申购金额',daily_yield:'每日收益',total_yield:'总收益',purchase_time:'申购时间',end_time:'结束时间',redeem_time:'赎回时间',total:'资产总额',captured_at:'采样时间戳' }
let generation = 0
async function load(reset = true) {
  if (reset) page.value = 1
  const current = ++generation
  rows.value = []; columns.value = []; total.value = 0; error.value = ''; loading.value = true
  if (userId.value && !/^[1-9]\d*$/.test(userId.value)) { error.value = '请输入有效用户 UID'; loading.value = false; return }
  const selectedMode = mode.value
  try {
    const data: any = await request.get('/admin/account-inspection', { params: { mode: selectedMode, kind: kind.value, userId: userId.value || undefined, userEmail: userEmail.value.trim() || undefined, status: hasStatus.value ? status.value || undefined : undefined, page: page.value, size: 20 } })
    if (current !== generation) return
    if (data.environment !== selectedMode) throw new Error('账户环境不匹配，已拒绝显示')
    rows.value = data.rows; columns.value = data.columns; total.value = data.total
  } catch (e: any) { if (current === generation) error.value = e.message || '查询失败，请重试' }
  finally { if (current === generation) loading.value = false }
}
function open() { visible.value = true; load() }
function changeKind() { status.value = ''; load() }
</script>

<template>
  <el-button v-permission="'users:view'" v-if="enabled" @click="open">账户筛选查看</el-button>
  <el-dialog v-model="visible" title="账户数据查看" fullscreen @closed="generation++; rows = []; columns = []">
    <el-alert :closable="false" :type="mode === 'DEMO' ? 'warning' : 'info'" :title="mode === 'DEMO' ? '模拟账户 · 独立数据库 · 全部为虚拟资金 · 只读查看' : '真实账户 · 真实资金数据 · 只读查看'" />
    <el-form inline style="margin-top:20px" @submit.prevent="load()">
      <el-form-item label="账户类型"><el-select v-model="mode" style="width:150px" @change="load()"><el-option label="模拟账户" value="DEMO"/><el-option label="真实账户" value="REAL"/></el-select></el-form-item>
      <el-form-item label="数据分类"><el-select v-model="kind" style="width:160px" @change="changeKind"><el-option v-for="item in allowed" :key="item[0]" :label="item[1]" :value="item[0]"/></el-select></el-form-item>
      <el-form-item label="用户 UID"><el-input v-model="userId" placeholder="留空查看全部" clearable /></el-form-item>
      <el-form-item label="用户邮箱"><el-input v-model="userEmail" placeholder="用户邮箱" clearable maxlength="254" /></el-form-item>
      <el-form-item v-if="hasStatus" label="状态"><el-input v-model="status" placeholder="例如 OPEN / COMPLETED" clearable /></el-form-item>
      <el-form-item><el-button v-permission="'users:view'" native-type="submit" :loading="loading">查询 / 刷新</el-button></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <admin-table :table-key="`AccountInspection.${mode}.${kind}`" v-loading="loading" :data="rows" border stripe empty-text="当前筛选下没有记录" style="width:100%" height="60vh">
      <el-table-column v-for="column in columns" :key="column" :prop="column" :label="labels[column] || column" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ ['open_price', 'close_price'].includes(column) ? formatPrice(row[column]) : row[column] }}</template>
      </el-table-column>
    </admin-table>
    <el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total, prev, pager, next" style="margin-top:20px" @current-change="load(false)" />
    <p>只展示已授权的数据分类。切换不迁移资金，不改变现有管理页面的数据范围；无权限或服务异常时不会自动显示另一类账户。</p>
  </el-dialog>
</template>
