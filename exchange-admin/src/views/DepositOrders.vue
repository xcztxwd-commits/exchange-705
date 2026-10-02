<script setup lang="ts">
import ProtectedElementImage from '@/components/ProtectedElementImage.vue'
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAccountTable } from '@/utils/useAccountTable'
import { accountTableRequest } from '@/utils/accountTableRequest'
import AccountTypeFilter from '@/components/AccountTypeFilter.vue'
const accountTable = useAccountTable()
const accountModes = accountTable.modes
const request = accountTableRequest(accountTable)
function accountFilterChanged() { rows.value=[];total.value=0;summary.value={};page.value=1;load(true) }
import ManualDepositDialog from '@/components/ManualDepositDialog.vue'
import { depositSource, depositStatus } from '@/utils/depositOrder'
import { currencies } from '@/utils/fiatCurrency'
const permissions = ref<Record<string, boolean>>({}), rows = ref<any[]>([]), summary = ref<any>({}), detail = ref<any>(null)
const total = ref(0), page = ref(1), size = ref(10), loading = ref(false), manual = ref(false), detailOpen = ref(false)
const filters = reactive<Record<string,string>>({ userId:'', userEmail:'', orderNo:'', userRemark:'', status:'', source:'', type:'', currency:'', address:'', network:'', accountType:'', filterAgentId:'', createdFrom:'', createdTo:'', reviewedFrom:'', reviewedTo:'', creditedFrom:'', creditedTo:'' })
const columns: [string,string][] = [ ['userEmail','用户邮箱'],['userRemark','用户备注'],['source','充值来源'],['manualPurpose','手动用途'],['type','充值类型'],['originalAmount','原币数量'],['currency','货币'],['amount','实到 / 预计 USD'],['status','状态'],['feeRate','手续费率'],['feeAmount','原币手续费'],['remark','订单备注'],['reviewRemark','审核备注'],['address','地址 / 银行收款信息'],['network','地址网络'],['accountType','入账账户'],['createdByName','创建人'],['reviewedByName','审核人'],['createdAt','创建时间'],['reviewedAt','审核时间'],['creditedAt','入账时间'] ]
const customers = ref<{ userId: number; email: string }[]>([]), searchingCustomers = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | undefined, searchVersion = 0, loadVersion = 0
function searchCustomers(input: string) {
  const version = ++searchVersion, query = input.trim()
  clearTimeout(searchTimer)
  customers.value = []
  searchingCustomers.value = !!query
  if (!query) return
  searchTimer = setTimeout(async () => {
    try {
      const result: any = await request.get('/admin/deposit/orders/customers', { params: { query } })
      if (version === searchVersion) customers.value = result
    } catch (e: any) {
      if (version === searchVersion) ElMessage.error(e.message)
    } finally {
      if (version === searchVersion) searchingCustomers.value = false
    }
  }, 300)
}
function resetFilters() {
  searchCustomers('')
  Object.keys(filters).forEach(k => filters[k] = '')
  load(true)
}
onUnmounted(() => { clearTimeout(searchTimer); searchVersion++; loadVersion++ })
function display(row:any,key:string) {
  if(key==='originalAmount'&&row.originalAmount==null) return '历史按 USD 记账'
  if(key==='source') return depositSource[row.source] || '历史来源未记录'
  if(key==='status') return depositStatus[row.status]
  if(key==='manualPurpose') return ({RECEIPT:'实收补录',BONUS:'赠送',ADJUSTMENT:'补款'} as Record<string,string>)[row[key]] || '—'
  if(key==='type') return ({bank:'银行卡',digital:'数字货币渠道',manual:'后台加款'} as Record<string,string>)[row[key]] || row[key]
  if(key==='createdByName') return row.createdById == null ? '未记录' : `${row.createdByType} / ${row.createdById} / ${row.createdByName || '—'}`
  if(key==='reviewedByName' && row.source!=='ADMIN_MANUAL') return row.reviewedById == null ? '未记录' : `${row.reviewedByType} / ${row.reviewedById} / ${row.reviewedByName || '—'}`
  if(key==='reviewedAt'||key==='reviewedByName') return row.source==='ADMIN_MANUAL'?'无需审核':row[key]||'未记录'
  if(key==='amount') return row.status==='REJECTED'?'未入账':`${row.status==='PENDING'?'预计 ':''}${row.amount} USD`
  if(key==='network'&&row.type!=='digital') return '—'
  if(key==='feeRate') return row[key] == null ? '未记录' : '0%'
  return row[key] ?? '—'
}
async function load(reset=false) {
  const version = ++loadVersion
  if(reset)page.value=1
  loading.value=true
  try {const params={...filters,page:page.value,size:size.value};const [list,totals]:any[]=await Promise.all([request.get('/admin/deposit/orders/list',{params}),request.get('/admin/deposit/orders/summary',{params})]);if(version!==loadVersion)return;rows.value=list.list;total.value=list.total;summary.value=totals}
  catch(e:any){if(version===loadVersion)ElMessage.error(e.message)}finally{if(version===loadVersion)loading.value=false}
}
async function show(row:any){accountTable.selectRow(row);const id=row.id;try{detail.value=await request.get(`/admin/deposit/orders/${id}`);detailOpen.value=true}catch(e:any){ElMessage.error(e.message)}}
async function review(row:any,approve:boolean){try{const result=await ElMessageBox.prompt(approve?'审核说明（可选）':'拒绝原因（必填）',approve?'通过后入账':'拒绝充值',{inputValidator:v=>approve||!!v?.trim()||'原因必填'});await request.post(`/admin/deposit/orders/${row.id}/${approve?'approve':'reject'}`,{remark:result.value});await load()}catch(e:any){if(e!=='cancel'&&e!=='close')ElMessage.error(e.message)}}
async function exportCsv(){try{const blob:any=await request.get('/admin/deposit/orders/export',{params:filters,responseType:'blob'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='deposit-orders.csv';a.click();URL.revokeObjectURL(url)}catch(e:any){ElMessage.error(e.message)}}
async function copy(value:any){try{await navigator.clipboard.writeText(String(value));ElMessage.success('已复制')}catch{ElMessage.warning('请手动复制')}}
onMounted(async()=>{permissions.value=await request.get('/admin/deposit/orders/permissions') as any;if(permissions.value.view_deposit_orders)await load()})
</script>
<template>
 <section class="deposit-orders">
  <header><div><h2>充值详情</h2><p>统一充值台账 · USD 记账 · 历史缺失信息不追溯推算</p></div><div><el-button v-permission="'deposit_orders:export_deposit_orders'" v-if="permissions.export_deposit_orders" @click="exportCsv">导出 CSV</el-button><el-button v-permission="'deposit_orders:manual_deposit'" v-if="permissions.manual_deposit" type="primary" @click="manual=true" :disabled="accountModes.includes('DEMO') || !accountModes.length">新增手动充值</el-button></div></header>
  <el-alert v-if="!permissions.view_deposit_orders" title="没有充值详情查看权限" type="warning" :closable="false" />
  <template v-else>
   <el-form inline class="filters">
    <el-form-item label="UID / 邮箱"><el-select v-model="filters.userId" filterable remote clearable :remote-method="searchCustomers" :loading="searchingCustomers" placeholder="输入 UID 或邮箱搜索" no-data-text="未找到匹配客户" loading-text="正在查询客户…" @change="load(true)"><el-option v-for="customer in customers" :key="customer.userId" :value="String(customer.userId)" :label="`${customer.userId} · ${customer.email || '无邮箱'}`" /></el-select></el-form-item>
    <el-form-item v-for="[key,label] in ([['userEmail','用户邮箱'],['orderNo','订单号'],['userRemark','用户备注'],['address','地址前缀'],['network','网络'],['filterAgentId','代理 UID']] as [string,string][])" :key="key" :label="label"><el-input v-model="filters[key!]" clearable maxlength="200" /></el-form-item>
    <el-form-item label="来源"><el-select v-model="filters.source" clearable><el-option v-for="(label,key) in depositSource" :key="key" :label="label" :value="key" /></el-select></el-form-item>
    <el-form-item label="状态"><el-select v-model="filters.status" clearable><el-option v-for="(label,key) in depositStatus" :key="key" :label="label" :value="key" /></el-select></el-form-item>
    <el-form-item v-for="[key,label,values] in [['type','类型',['bank','digital','manual']],['currency','货币',currencies],['accountType','账户',['FUND','CONTRACT','OPTION']]] as any" :key="key" :label="label"><el-select v-model="filters[key!]" clearable><el-option v-for="value in values" :key="value" :value="value" :label="value" /></el-select></el-form-item>
    <div v-for="[prefix,label] in [['created','创建时间'],['reviewed','审核时间'],['credited','入账时间']]" :key="prefix"><el-form-item :label="label+' 起'"><el-date-picker v-model="filters[prefix+'From']" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item><el-form-item label="至（不含）"><el-date-picker v-model="filters[prefix+'To']" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item></div>
    <el-button v-permission="'deposit_orders:view'" type="primary" :loading="loading" @click="load(true)">查询</el-button><el-button v-permission="'deposit_orders:view'" @click="resetFilters">重置</el-button>
   </el-form>
   <div class="summary"><span>订单 {{ summary.count || 0 }}</span><span>待审核 {{ summary.pending || 0 }}</span><strong>已入账 {{ summary.creditedUsd || '0' }} USD</strong><span>用户审核 {{ summary.userUsd || 0 }} USD</span><span>手动 {{ summary.manualUsd || 0 }} USD</span><span>历史未知 {{ summary.legacyUsd || 0 }} USD</span><span>历史入账时间未记录 {{ summary.historicalTimeUnknown || 0 }}</span></div>
   <p class="groups" v-for="(value,key) in summary.groups" :key="key">{{ depositSource[key as string] || key }}：{{ value }} USD</p>
   <p>分组独立核算：USER_SUBMITTED 用户业绩；ADMIN_MANUAL_RECEIPT / BONUS / ADJUSTMENT 分别为实收补录 / 赠送 / 补款；历史未知不计为新用户业绩。入账时间筛选排除时间未知历史单。</p>
   <AccountTypeFilter v-model="accountModes" @change="accountFilterChanged" />
<admin-table :row-key="(row: any) => `${row.accountMode || 'REAL'}:${row.id ?? row.userId}:${row.type || ''}`" table-key="DepositOrders.1" :data="rows" v-loading="loading" stripe border style="width:100%">
<el-table-column prop="accountModeLabel" label="账户类型" width="110" fixed="left" />
    <el-table-column prop="userId" label="UID" fixed width="100"><template #default="{row}"><el-link v-permission="'deposit_orders:detail'" @click="copy(row.userId)">{{row.userId}}</el-link></template></el-table-column>
    <el-table-column label="订单号" fixed width="210"><template #default="{row}"><el-link v-permission="'deposit_orders:detail'" @click="copy(row.orderNo)">{{row.orderNo || `LEGACY-DEP-${row.id}`}}</el-link></template></el-table-column>
    <el-table-column v-for="[key,label] in columns" :key="key" :prop="key" :label="label" min-width="165" show-overflow-tooltip><template #default="{row}"><el-tag v-if="key==='source'" :type="row.source==='ADMIN_MANUAL'?'warning':row.source==='USER_SUBMITTED'?'success':'info'">{{display(row,key)}}</el-tag><span v-else>{{display(row,key)}}</span></template></el-table-column>
    <el-table-column label="充值凭证" width="100"><template #default="{row}"><ProtectedElementImage v-if="row.proofImage" :src="row.proofImage" :preview-src-list="[row.proofImage]" preview-teleported style="width:50px;height:40px" /><span v-else>—</span></template></el-table-column>
    <el-table-column label="操作" fixed="right" width="190"><template #default="{row}"><el-button v-permission="'deposit_orders:detail'" link @click="show(row)">详情</el-button><el-button v-permission="'deposit_orders:approve_deposit'" v-if="row.status==='PENDING'&&permissions.approve_deposit" link type="success" @click="review(row,true)" :disabled="accountModes.includes('DEMO') || !accountModes.length">通过</el-button><el-button v-permission="'deposit_orders:reject_deposit'" v-if="row.status==='PENDING'&&permissions.reject_deposit" link type="danger" @click="review(row,false)" :disabled="accountModes.includes('DEMO') || !accountModes.length">拒绝</el-button></template></el-table-column>
   </admin-table>
   <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10,20,50,100]" layout="total,sizes,prev,pager,next" @change="load()" />
  </template>
  <ManualDepositDialog v-if="accountModes.length === 1 && accountModes[0] === 'REAL'" v-model="manual" @success="load()" />
  <el-dialog v-model="detailOpen" title="充值详情" width="850px"><template v-if="detail"><el-descriptions :column="2" border><el-descriptions-item label="订单号">{{detail.orderNo}}</el-descriptions-item><el-descriptions-item label="UID">{{detail.userId}}</el-descriptions-item><el-descriptions-item v-for="[key,label] in columns" :key="key" :label="label">{{display(detail,key)}}</el-descriptions-item></el-descriptions><ProtectedElementImage v-if="detail.proofImage" :src="detail.proofImage" :preview-src-list="[detail.proofImage]" style="width:160px" /><h3>入账凭据</h3><el-descriptions v-if="detail.credit" border :column="2"><el-descriptions-item label="入账前 USD">{{detail.credit.balanceBefore}}</el-descriptions-item><el-descriptions-item label="入账后 USD">{{detail.credit.balanceAfter}}</el-descriptions-item><el-descriptions-item label="入账操作人">{{detail.credit.operatorType}} / {{detail.credit.operatorId}} / {{detail.credit.operatorName}}</el-descriptions-item><el-descriptions-item label="入账时间">{{detail.credit.creditedAt}}</el-descriptions-item></el-descriptions><el-alert v-else :title="detail.status==='COMPLETED'?'历史入账明细未记录':'尚未入账，无入账凭据'" :closable="false" /></template></el-dialog>
 </section>
</template>
<style scoped>
.deposit-orders{padding:24px;background:#fff;border-radius:10px}header{display:flex;justify-content:space-between;align-items:center}h2{margin:0}p{color:#64748b;font-size:13px}.filters{padding:18px 0}.filters .el-input,.filters .el-select{width:185px}.summary{display:flex;gap:24px;flex-wrap:wrap;padding:18px;background:#eef6ff;border-radius:8px}.groups{display:inline-block;margin-right:20px}.el-pagination{margin-top:20px}
</style>
