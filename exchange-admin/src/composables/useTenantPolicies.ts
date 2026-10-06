import {ref,computed,watch} from 'vue'
import request from '@/utils/request'
import {useAuthStore} from '@/store/auth'
import {configEditable,type PolicySnapshot} from '@/utils/tenantPolicies'
export function useTenantPolicies(area:'settings'|'support'|'agents'|'website'|'share-templates'|'dashboard') {
 const snapshot=ref<PolicySnapshot|null>(null),policyError=ref(''),auth=useAuthStore()
 let generation=0
 watch(()=>auth.token,()=>{generation++;snapshot.value=null;policyError.value=''})
 async function reloadPolicies(){const seq=++generation;snapshot.value=null;policyError.value='';try{const data=await request.get(`/admin/tenant-policies/${area}`) as unknown as PolicySnapshot;if(seq!==generation)return;if(data.tenantId!==auth.user?.tenantId||!Array.isArray(data.configs)||!data.features)throw new Error('租户策略响应无效');snapshot.value=data}catch(e:any){if(seq===generation)policyError.value=e.message||'总控策略不可用';throw e}}
 const policyReady=computed(()=>!!snapshot.value)
 const editable=(key:string)=>configEditable(snapshot.value,key)
 const policyLabel=(key:string)=>editable(key)?'':'（总控锁定或未授权）'
 return {snapshot,policyError,policyReady,reloadPolicies,editable,policyLabel}
}
