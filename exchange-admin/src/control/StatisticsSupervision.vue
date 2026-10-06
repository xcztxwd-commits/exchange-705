<script setup lang="ts">
import {ref,onMounted,onUnmounted,watch} from 'vue'
import {api,dataRows} from './api'
const tenants=ref<any[]>([]),tenant=ref<number|'all'>('all'),data=ref<any>(null),error=ref(''),busy=ref(false)
const labels:Record<string,string>={tenant_id:'租户 ID',tenant_name:'租户',users:'全部用户（含代理）',agents:'代理',admins:'管理员',pendingKyc:'待审核 KYC',openSupport:'未结束客服会话',coin:'账本账户类型',accounts:'账户数',available:'可用金额（USD 账本）',frozen:'冻结金额（USD 账本）',status:'状态',orders:'订单数',currency:'原始输入币种',records:'记录数',amount_usd:'金额（USD 账本）'}
const tables=[{key:'assets',name:'资金账户快照',columns:['coin','accounts','available','frozen']},{key:'contracts',name:'合约订单状态',columns:['status','orders']},{key:'options',name:'期权订单状态',columns:['status','orders']},{key:'deposits',name:'充值记录（按输入币种与状态分组）',columns:['currency','status','records','amount_usd']},{key:'withdrawals',name:'提现记录（按输入币种与状态分组）',columns:['currency','status','records','amount_usd']}]
let generation=0,disposed=false,request:AbortController|undefined
async function load(){
 if(disposed)return
 const seq=++generation;request?.abort();request=new AbortController();const signal=request.signal
 data.value=null;error.value='';busy.value=true
 try{if(tenant.value!=='all'&&(!Number.isSafeInteger(tenant.value)||tenant.value<=0))throw new Error('租户无效')
  const path=tenant.value==='all'?'/control':`/control/tenants/${tenant.value}`
  const r=await api(`${path}/supervision/statistics`,'GET',undefined,signal);if(seq===generation)data.value=r.data
 }catch(e:any){if(seq===generation&&!signal.aborted)error.value=e.message}finally{if(seq===generation)busy.value=false}
}
watch(tenant,load,{flush:'sync'})
onUnmounted(()=>{disposed=true;generation++;request?.abort()})
onMounted(async()=>{const started=generation;try{const choices=dataRows(await api('/control/tenants'));if(disposed)return;tenants.value=choices;if(started===generation)await load()}catch(e:any){if(!disposed&&started===generation)error.value=e.message}})
</script>
<template><div class="toolbar"><div><h2>租户统计监管 · 只读</h2><p>默认查看全部租户，分租户展示资金明细。真实库一致性快照，手动刷新，不触发结算或写入。不混合币种估值；充值/提现 amount 为 USD 账本金额，不是输入币种金额。</p></div><el-select v-model="tenant" style="width:220px"><el-option value="all" label="全部"/><el-option v-for="t in tenants" :key="t.id" :value="t.id" :label="t.name"/></el-select><el-button :loading="busy" @click="load">刷新快照</el-button></div><el-alert v-if="error" :title="error" type="error" :closable="false"/><template v-if="data"><p>统计时点：{{data.asOf}} · 所有状态均纳入计数，非财务收益报表。</p><el-descriptions border :column="3"><el-descriptions-item v-for="(value,key) in data.counts" :key="key" :label="labels[String(key)]||String(key)">{{value}}</el-descriptions-item></el-descriptions><section v-for="table in tables" :key="table.key" style="margin-top:24px"><h3>{{table.name}}</h3><admin-table :table-key="`control.statistics.${table.key}`" :data="data[table.key]" border><el-table-column v-for="c in tenant==='all'?['tenant_id','tenant_name',...table.columns]:table.columns" :key="c" :prop="c" :label="labels[c]" min-width="150"/></admin-table></section></template></template>
