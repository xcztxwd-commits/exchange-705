<script setup lang="ts">
import {ref,onMounted,onUnmounted,watch,computed} from 'vue'
import {api,dataRows} from './api'
const props=defineProps<{kind:'kyc'|'admins'|'agents'}>()
const names={kyc:'KYC 认证',admins:'管理员',agents:'代理'}
const title=computed(()=>names[props.kind]),tenants=ref<any[]>([]),tenant=ref<number|'all'>('all'),subject=ref(''),userEmail=ref(''),status=ref(''),page=ref(1),total=ref(0),rows=ref<any[]>([]),columns=ref<string[]>([]),error=ref(''),busy=ref(false)
const labels:Record<string,string>={tenant_id:'租户 ID',tenant_name:'租户',id:'记录 ID',user_id:'用户 ID',real_name:'姓名',id_number:'证件号（脱敏）',status:'状态',review_remark:'审核备注',reviewed_by:'审核人 ID',reviewed_at:'审核时间',created_at:'创建时间',updated_at:'更新时间',account:'登录账号',email:'用户邮箱',remark:'用户备注',user_email:'用户邮箱',user_remark:'用户备注',parent_user_email:'上级用户邮箱',parent_user_remark:'上级用户备注',role:'角色',enabled:'启用',must_change_password:'必须改密',nickname:'昵称',parent_user_id:'上级用户 ID',last_login_at:'上次登录',subordinate_count:'直接下级数'}
let generation=0,disposed=false,request:AbortController|undefined
async function load(reset=false){
 if(disposed)return
 const seq=++generation;request?.abort();request=new AbortController();const signal=request.signal
 rows.value=[];columns.value=[];total.value=0;error.value='';if(reset)page.value=1;busy.value=true
 try{if(subject.value&&!/^[1-9]\d*$/.test(subject.value))throw new Error('ID 必须为正整数');if(tenant.value!=='all'&&(!Number.isSafeInteger(tenant.value)||tenant.value<=0))throw new Error('租户无效')
  const query=new URLSearchParams({page:String(page.value),size:'20'});if(userEmail.value.trim())query.set('userEmail',userEmail.value.trim());if(subject.value)query.set('subjectId',subject.value);if(status.value&&props.kind!=='admins')query.set('status',status.value)
  const path=tenant.value==='all'?'/control':`/control/tenants/${tenant.value}`
  const r=await api(`${path}/supervision/${props.kind}?${query}`,'GET',undefined,signal)
  if(seq!==generation)return;rows.value=r.data.rows;columns.value=r.data.columns;total.value=r.data.total
 }catch(e:any){if(seq===generation&&!signal.aborted)error.value=e.message}finally{if(seq===generation)busy.value=false}
}
watch(()=>[tenant.value,props.kind],()=>load(true),{flush:'sync'})
onUnmounted(()=>{disposed=true;generation++;request?.abort()})
onMounted(async()=>{const started=generation;try{const choices=dataRows(await api('/control/tenants'));if(disposed)return;tenants.value=choices;if(started===generation)await load(true)}catch(e:any){if(!disposed&&started===generation)error.value=e.message}})
</script>
<template><div class="toolbar"><div><h2>{{title}}监管 · 只读</h2><p>默认查看全部租户，可筛选单个租户。每次访问记录总控真实身份，不执行审批、不修改权限，不展示密码或会话凭据。</p></div></div><el-form inline @submit.prevent="load(true)"><el-form-item label="租户"><el-select v-model="tenant" style="width:220px"><el-option value="all" label="全部"/><el-option v-for="t in tenants" :key="t.id" :value="t.id" :label="t.name"/></el-select></el-form-item><el-form-item :label="kind==='kyc'?'用户 ID':kind==='admins'?'管理员 ID':'代理 ID'"><el-input v-model="subject" clearable/></el-form-item><el-form-item label="邮箱"><el-input v-model="userEmail" clearable maxlength="254" placeholder="邮箱"/></el-form-item><el-form-item v-if="kind!=='admins'" label="状态"><el-select v-model="status" clearable placeholder="全部"><el-option v-for="s in kind==='kyc'?['PENDING','APPROVED','REJECTED']:['normal','frozen','banned','active','disabled']" :key="s" :value="s" :label="s"/></el-select></el-form-item><el-button native-type="submit" :loading="busy">查询</el-button></el-form><el-alert v-if="error" :title="error" type="error" :closable="false"/><admin-table :table-key="`control.supervision.${kind}`" :data="rows" border v-loading="busy"><el-table-column v-for="c in columns" :key="c" :prop="c" :label="labels[c]||c" min-width="140" show-overflow-tooltip><template #default="s">{{typeof s.row[c]==='boolean'?(s.row[c]?'是':'否'):(s.row[c]??'—')}}</template></el-table-column></admin-table><el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total, prev, pager, next" @current-change="load()"/></template>
