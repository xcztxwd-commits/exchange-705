<script setup lang="ts">
import { ref,onMounted,onUnmounted,watch } from 'vue'
import { ElMessage,ElMessageBox } from 'element-plus'
import { api,dataRows } from './api'
const tenants=ref<any[]>([]),tenant=ref<number|'all'>('all'),rows=ref<any[]>([]),page=ref(0),error=ref(''),health=ref<any>(),busy=ref(false)
let generation=0,disposed=false,request:AbortController|undefined
async function load(reset=false){
 if(disposed)return
 const id=++generation;request?.abort();request=new AbortController();const signal=request.signal
 if(reset)page.value=0;rows.value=[];error.value='';health.value=undefined;busy.value=true
 try{if(tenant.value!=='all'&&(!Number.isSafeInteger(tenant.value)||tenant.value<=0))throw new Error('租户无效')
  const path=tenant.value==='all'?'/control/operations/issues':`/control/operations/tenants/${tenant.value}`
  const [result,h]=await Promise.all([api(`${path}?page=${page.value}`,'GET',undefined,signal),api('/control/operations/health','GET',undefined,signal)])
  if(id===generation){rows.value=dataRows(result);health.value=h.data}
 }catch(e:any){if(id===generation&&!signal.aborted)error.value=e.message}finally{if(id===generation)busy.value=false}
}
async function review(row:any){
 const target=Number(row.tenant_id),started=generation
 const current=()=>!disposed&&started===generation&&(tenant.value==='all'||tenant.value===target)
 try{if(!Number.isSafeInteger(target)||target<=0||!current())throw new Error('记录租户范围无效')
  const title=`异常复核 / ${row.tenant_name||target} / ${row.job}`
  const why=(await ElMessageBox.prompt('填写处理原因；此动作仅复核，不改余额，不触发业务修复',title,{inputPattern:/^.{5,500}$/,inputErrorMessage:'原因5至500字'})).value
  if(!current())return
  const hash=(await ElMessageBox.prompt('已实际复核报告的SHA256（64位小写十六进制）','绑定复核证据',{inputPattern:/^[a-f0-9]{64}$/,inputErrorMessage:'需要实际报告SHA256'})).value
  if(!current())return
  await ElMessageBox.confirm(`确认复核租户 ${row.tenant_name||target} 的 ${row.job}，继续保留。原幂等任务按下一调度重试；不执行清理或余额修正。`,'确认复核')
  if(!current())return
  await api(`/control/operations/tenants/${target}/review`,'POST',{job:row.job,expectedFailureId:row.latest_failure_id,reason:why,evidenceSha256:hash,result:'PRESERVED'})
  if(!current())return
  await load();ElMessage.success('真实操作者及证据已留审计；未修改业务数据')
 }catch(e:any){if(!disposed&&started===generation&&e!=='cancel'&&e!=='close')ElMessage.error(e.message)}
}
watch(tenant,()=>load(true),{flush:'sync'})
onUnmounted(()=>{disposed=true;generation++;request?.abort()})
onMounted(async()=>{const started=generation;try{const choices=dataRows(await api('/control/tenants'));if(disposed)return;tenants.value=choices;if(started===generation)await load(true)}catch(e:any){if(!disposed&&started===generation)error.value=e.message}})
</script>
<template><h2>运行异常与复核</h2><p>默认查看全部租户；复核仅作用于所选记录的租户。仅显示任务/运行元数据，不显示聊天、凭据或完整实名。原幂等引擎重试；不直接修改余额。</p><el-select v-model="tenant" style="width:220px"><el-option value="all" label="全部"/><el-option v-for="t in tenants" :key="t.id" :value="t.id" :label="t.name"/></el-select><el-button :loading="busy" @click="load()">刷新</el-button><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-alert v-if="health?.failureDelivery!=='DATABASE'&&health" :title="`异常持久化状态：${health.failureDelivery}；${health.failureDeliveryType}。本地日志待导入或落库不可用，不可视为恢复。`" type="warning" :closable="false"/><admin-table table-key="control.operations.issues" :data="rows" border><el-table-column prop="tenant_id" label="租户 ID"/><el-table-column v-if="tenant==='all'" prop="tenant_name" label="租户"/><el-table-column prop="job" label="任务 / 留存会话"/><el-table-column prop="first_seen" label="首次异常"/><el-table-column prop="last_seen" label="最近异常"/><el-table-column prop="attempts" label="失败或跳过次数"/><el-table-column label="复核状态"><template #default="s">{{s.row.last_review_id>s.row.latest_failure_id?'已复核，保留':'待处理 / 重试'}}</template></el-table-column><el-table-column label="动作"><template #default="s"><el-button link @click="review(s.row)">处理与复核留证</el-button></template></el-table-column></admin-table><el-button :disabled="page===0||busy" @click="page--;load()">上一页</el-button><el-button :disabled="rows.length<50||busy" @click="page++;load()">下一页</el-button></template>
