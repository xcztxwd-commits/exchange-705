<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Refresh, ArrowDown, ArrowUp } from '@element-plus/icons-vue'
import UserLookup from '@/components/UserLookup.vue'
import request from '@/utils/request'
import { can } from '@/utils/access'
import { useDebouncedSearch } from '@/composables/useDebouncedSearch'

const route = useRoute(), router = useRouter()
const rows = ref<any[]>([]), types = ref<{ value: string; label: string }[]>([])
const loading = ref(false), error = ref(''), moreFilters = ref(false)
const page = ref(1), size = ref(25), total = ref(0)
const summary = ref({ income: '0', expense: '0', net: '0', count: 0, currency: 'USD' })
const range = ref<[string, string] | null>(null)
const filters = reactive({ userId: typeof route.query.userId === 'string' ? route.query.userId : '', keyword: '', accountType: '', type: '', direction: '', amountMin: '', amountMax: '', currency: '' })
const accounts: Record<string, string> = { FUND: '资金账户', CONTRACT: '合约账户', OPTION: '期权账户' }
const activeFilters = computed(() => Object.values(filters).filter(Boolean).length + (range.value ? 1 : 0))
let version = 0

function money(value: unknown): string {
  if (value == null || value === '') return '—'
  const text = String(value)
  if (!/^-?\d+(\.\d+)?$/.test(text)) return '—'
  const [whole, raw = ''] = text.split('.')
  const fraction = raw.replace(/0+$/, '').padEnd(2, '0')
  return whole!.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + fraction
}
function positive(value: unknown) { return !String(value).startsWith('-') && !/^0(?:\.0*)?$/.test(String(value)) }
function negative(value: unknown) { return String(value).startsWith('-') && !/^-0(?:\.0*)?$/.test(String(value)) }
function time(value: unknown) { return value ? String(value).replace('T', ' ').slice(0, 19) : '未记录' }

async function load(reset = false) {
  if (reset) page.value = 1
  const current = ++version
  loading.value = true
  error.value = ''
  try {
    filters.currency = filters.currency.trim().toUpperCase()
    for (const value of [filters.amountMin, filters.amountMax]) {
      if (value && !/^\d+(\.\d{1,16})?$/.test(value)) throw new Error('金额请输入非负数字，最多16位小数')
    }
    const params = Object.fromEntries(Object.entries({ ...filters, page: page.value - 1, size: size.value, startTime: range.value?.[0], endTime: range.value?.[1] }).filter(([, value]) => value !== '' && value != null))
    const result: any = await request.get('/admin/funds/ledger', { params })
    if (current !== version) return
    if (result.success === false) throw new Error(result.message || '资金明细加载失败')
    rows.value = result.list || []
    total.value = Number(result.total || 0)
    summary.value = result.summary || { income: '0', expense: '0', net: '0', count: 0, currency: 'USD' }
    types.value = result.types || types.value
  } catch (failure: any) {
    if (current !== version) return
    rows.value = []; total.value = 0
    summary.value = { income: '0', expense: '0', net: '0', count: 0, currency: 'USD' }
    error.value = failure.message || '资金明细加载失败'
    ElMessage.error(error.value)
  } finally { if (current === version) loading.value = false }
}
function search() { cancel(); void load(true) }
const { schedule, cancel } = useDebouncedSearch(search)
function reset() {
  cancel()
  Object.keys(filters).forEach(key => { filters[key as keyof typeof filters] = '' })
  range.value = null
  void load(true)
}
function showCustomer(row: any) { void router.push({ path: '/users', query: { userId: String(row.userId) } }) }
onMounted(() => { void load() })
onUnmounted(() => { version++; cancel() })
</script>

<template>
  <section class="funds-ledger">
    <header class="page-heading">
      <div><h2>资金明细</h2><p>所有客户的资金变动，按时间汇总查询</p></div>
      <el-button :icon="Refresh" :loading="loading" @click="load()">刷新</el-button>
    </header>

    <el-form class="ledger-filters" label-position="top" @submit.prevent="search">
      <el-form-item label="客户">
        <UserLookup v-model="filters.userId" scope="funds_ledger" :account-modes="['REAL']" placeholder="搜索客户 ID / 邮箱" @change="search" />
      </el-form-item>
      <el-form-item label="变动类型"><el-select v-model="filters.type" clearable filterable placeholder="全部类型" @change="search"><el-option v-for="item in types" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
      <el-form-item label="资金账户"><el-select v-model="filters.accountType" clearable placeholder="全部账户" @change="search"><el-option v-for="(label, value) in accounts" :key="value" :label="label" :value="value" /></el-select></el-form-item>
      <el-form-item label="收支方向"><el-select v-model="filters.direction" clearable placeholder="全部收支" @change="search"><el-option label="入金 / 收入" value="IN" /><el-option label="出金 / 支出" value="OUT" /></el-select></el-form-item>
      <el-form-item label="变动时间" class="date-filter"><el-date-picker v-model="range" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss" start-placeholder="开始时间" end-placeholder="结束时间" range-separator="至" clearable @change="search" /></el-form-item>
      <el-form-item label="模糊查询" class="keyword-filter"><el-input v-model="filters.keyword" clearable placeholder="客户、单号、备注" :prefix-icon="Search" @input="schedule" @keyup.enter="search" /></el-form-item>
      <div class="filter-actions"><el-button type="primary" :icon="Search" :loading="loading" native-type="submit">查询</el-button><el-button :icon="Refresh" @click="reset">重置</el-button><el-button link @click="moreFilters = !moreFilters">{{ moreFilters ? '收起筛选' : '更多筛选' }}<el-icon><ArrowUp v-if="moreFilters" /><ArrowDown v-else /></el-icon></el-button></div>
      <template v-if="moreFilters">
        <el-form-item label="最小变动金额（USD）"><el-input v-model="filters.amountMin" inputmode="decimal" clearable placeholder="不限" @keyup.enter="search" /></el-form-item>
        <el-form-item label="最大变动金额（USD）"><el-input v-model="filters.amountMax" inputmode="decimal" clearable placeholder="不限" @keyup.enter="search" /></el-form-item>
        <el-form-item label="原币币种"><el-input v-model="filters.currency" clearable placeholder="例如 USD、USDT" @keyup.enter="search" /></el-form-item>
      </template>
    </el-form>

    <div class="ledger-summary" aria-live="polite">
      <span>符合条件 <strong>{{ loading ? '…' : total.toLocaleString() }}</strong> 笔<span v-if="activeFilters" class="filter-count"> · {{ activeFilters }} 项筛选</span></span>
      <span>收入 <strong class="income">{{ loading ? '…' : money(summary.income) }}</strong> USD</span>
      <span>支出 <strong class="expense">{{ loading ? '…' : money(summary.expense) }}</strong> USD</span>
      <span>净变动 <strong :class="{ income: positive(summary.net), expense: negative(summary.net) }">{{ loading ? '…' : money(summary.net) }}</strong> USD</span>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />

    <admin-table table-key="funds-ledger" v-loading="loading" :data="rows" row-key="key" stripe border empty-text="当前条件下没有资金变动" size="small" max-height="max(280px, calc(100dvh - 420px))">
      <el-table-column column-key="customer" label="客户" min-width="205" fixed="left"><template #default="{ row }"><div class="customer-cell"><el-button v-if="can('users:view')" link type="primary" @click="showCustomer(row)">{{ row.nickname || row.email || `客户 ${row.userId}` }}</el-button><strong v-else>{{ row.nickname || row.email || `客户 ${row.userId}` }}</strong><span>ID {{ row.userId }}<template v-if="row.nickname && row.email"> · {{ row.email }}</template></span></div></template></el-table-column>
      <el-table-column prop="typeLabel" label="变动类型" min-width="120" />
      <el-table-column prop="amount" label="变动金额（USD）" min-width="175" align="right"><template #default="{ row }"><strong class="amount" :class="{ income: positive(row.amount), expense: negative(row.amount) }">{{ positive(row.amount) ? '+' : '' }}{{ money(row.amount) }}</strong></template></el-table-column>
      <el-table-column prop="accountType" label="资金账户" width="115"><template #default="{ row }">{{ accounts[row.accountType] || row.accountType || '—' }}</template></el-table-column>
      <el-table-column prop="balanceBefore" label="变动前余额" min-width="145" align="right"><template #default="{ row }">{{ money(row.balanceBefore) }}</template></el-table-column>
      <el-table-column prop="balanceAfter" label="变动后余额" min-width="145" align="right"><template #default="{ row }">{{ money(row.balanceAfter) }}</template></el-table-column>
      <el-table-column prop="time" label="变动时间" width="170"><template #default="{ row }">{{ time(row.time) }}</template></el-table-column>
      <el-table-column prop="reference" label="关联单号" min-width="155" show-overflow-tooltip />
      <el-table-column column-key="original" label="原币金额" min-width="140" align="right"><template #default="{ row }">{{ row.originalAmount == null ? '—' : `${money(row.originalAmount)} ${row.originalCurrency || ''}` }}</template></el-table-column>
      <el-table-column prop="remark" label="备注" min-width="200" show-overflow-tooltip />
      <el-table-column prop="operator" label="操作人 / 来源" min-width="135" show-overflow-tooltip />
    </admin-table>
    <footer class="ledger-footer"><span>金额统一折算 USD；“—”表示历史未记录。划转按转入、转出分别展示。</span><el-pagination v-model:current-page="page" v-model:page-size="size" :page-sizes="[25, 50, 100]" :total="total" layout="total, sizes, prev, pager, next" size="small" @current-change="load()" @size-change="load(true)" /></footer>
  </section>
</template>

<style scoped>
.funds-ledger { padding: 14px; border: 1px solid #e4e7ed; border-radius: 6px; background: #fff; min-width: 0; }
.page-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.page-heading h2 { font-size: 18px; line-height: 26px; color: #303133; }
.page-heading p { margin-top: 2px; color: #909399; font-size: 12px; }
.ledger-filters { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 10px 14px; align-items: end; }
.ledger-filters :deep(.el-form-item) { margin: 0; min-width: 0; }
.ledger-filters :deep(.el-form-item__label) { height: auto; line-height: 20px; margin-bottom: 4px; color: #606266; font-size: 12px; }
.ledger-filters :deep(.el-select), .ledger-filters :deep(.el-date-editor) { width: 100%; min-width: 0; }
.date-filter { grid-column: span 2; }
.filter-actions { display: flex; align-items: center; gap: 6px; min-height: 32px; flex-wrap: wrap; }
.filter-actions .el-button + .el-button { margin-left: 0; }
.ledger-summary { display: flex; flex-wrap: wrap; gap: 12px 28px; padding: 12px 0; margin-top: 12px; border-top: 1px solid #ebeef5; color: #606266; font-size: 12px; }
.ledger-summary strong { color: #303133; font-size: 15px; font-variant-numeric: tabular-nums; margin: 0 3px; }
.filter-count { color: #909399; }
.income, .ledger-summary .income { color: #14804a; }
.expense, .ledger-summary .expense { color: #d74747; }
.amount { font-variant-numeric: tabular-nums; }
.customer-cell { display: flex; flex-direction: column; align-items: flex-start; min-width: 0; line-height: 19px; }
.customer-cell > :first-child { max-width: 100%; font-size: 13px; }
.customer-cell > span { color: #909399; font-size: 11px; overflow-wrap: anywhere; }
.ledger-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-top: 12px; color: #909399; font-size: 12px; }
.ledger-footer :deep(.el-pagination) { margin-top: 0; }
@media (max-width: 1050px) { .ledger-filters { grid-template-columns: repeat(3, minmax(150px, 1fr)); } .filter-actions { grid-column: span 2; } }
@media (max-width: 700px) { .funds-ledger { padding: 10px; } .ledger-filters { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; } .date-filter, .keyword-filter, .filter-actions { grid-column: 1 / -1; } .ledger-footer :deep(.el-pagination) { max-width: 100%; flex-wrap: wrap; gap: 8px 0; } .ledger-summary { gap: 8px 16px; } }
</style>
