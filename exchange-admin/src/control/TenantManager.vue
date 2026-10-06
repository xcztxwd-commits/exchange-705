<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { startReadPolling } from '@/utils/readPolling'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, dataRows } from './api'
import { openTenant } from './openTenant'
import OnlineUsers from '@/components/OnlineUsers.vue'
import BackendAccounts from '@/components/BackendAccounts.vue'
const counts = ref<Record<number,number|null>>({}), totalOnline = ref<number|null>(null), countsError = ref(''), countsAt = ref('')
let countsGeneration=0, disposed=false, stopCounts:(()=>void)|undefined
async function readCounts() {
 if (document.visibilityState !== 'visible' || !tenants.value.length) return true
 const generation=++countsGeneration, ids=tenants.value.map(t=>t.id)
 // ponytail: one existing tenant-scoped count/page request per tenant (about 10 tenants); use an aggregated read projection if this grows.
 const results=await Promise.allSettled(ids.map(async id=>{const response=await api(`/control/tenants/${id}/online?page=0&size=1`);const n=Number(response.data?.total);if(!Number.isSafeInteger(n)||n<0)throw new Error('在线统计格式无效');return n}))
 if(disposed||generation!==countsGeneration)return false
 const next:Record<number,number|null>={};let sum=0,complete=true
 results.forEach((r,i)=>{const id=ids[i];if(r.status==='fulfilled'){next[id]=r.value;sum+=r.value}else{next[id]=null;complete=false}})
 counts.value=next;totalOnline.value=complete?sum:null;countsAt.value=new Date().toLocaleTimeString();countsError.value=complete?'':'部分租户统计不可用；平台总数显示未知，不按零计入。'
 return complete
}
const readiness=ref<any>(null),readinessOpen=ref(false),readinessError=ref(''),readinessTenant=ref<any>(null),readinessBusy=ref(false)
let readinessGeneration=0
async function checkReadiness(row:any){const generation=++readinessGeneration;readinessTenant.value=row;readinessOpen.value=true;readiness.value=null;readinessError.value='';readinessBusy.value=true;try{const result=await api(`/control/tenants/${row.id}/readiness`);if(generation===readinessGeneration)readiness.value=result.data}catch(e:any){if(generation===readinessGeneration)readinessError.value=e.message}finally{if(generation===readinessGeneration)readinessBusy.value=false}}
const domainOpen=ref(false),domainTenant=ref<any>(null),domainState=ref<any>(null),domainError=ref(''),domainBusy=ref(false)
const domainForm=reactive({entryHost:'',frontendHost:'',entryEnabled:false,reason:''});let domainGeneration=0
const domainCandidates=computed<any[]>(()=>domainState.value?.bindings.filter((b:any)=>['PENDING','VERIFIED'].includes(b.status))||[])
const retiredDomains=computed<any[]>(()=>domainState.value?.bindings.filter((b:any)=>b.status==='RETIRED')||[])
const domainRoles=[{role:'ENTRY',field:'entryHost',name:'入口域名'},{role:'FRONTEND',field:'frontendHost',name:'前台域名'}] as const
const preparedMatches=computed(()=>domainRoles.every(r=>domainForm[r.field]===(domainCandidates.value.find(b=>b.role===r.role)?.hostname||domainState.value?.[r.field]||'')))
function applyDomainState(state:any,preserveEntry=false){const enabled=domainForm.entryEnabled;domainState.value=state;for(const r of domainRoles)domainForm[r.field]=state.bindings.find((b:any)=>b.role===r.role&&['PENDING','VERIFIED'].includes(b.status))?.hostname||state[r.field]||'';domainForm.entryEnabled=preserveEntry?enabled:state.entryEnabled}
async function domain(row:any){
 const seq=++domainGeneration;domainTenant.value=row;domainState.value=null;domainError.value='';domainOpen.value=true;domainBusy.value=true;Object.assign(domainForm,{entryHost:'',frontendHost:'',entryEnabled:false,reason:''})
 try{const r=await api(`/control/tenants/${row.id}/domains/state`);if(seq===domainGeneration)applyDomainState(r.data)}catch(e:any){if(seq===domainGeneration)domainError.value=e.message}finally{if(seq===domainGeneration)domainBusy.value=false}
}
async function domainAction(action:'prepare'|'verify'|'activate'|'switch'|'release',binding?:any){
 if(domainBusy.value||!domainTenant.value||!domainState.value)return;const seq=domainGeneration,target=domainTenant.value.id;domainError.value='';domainBusy.value=true
 try{
  if(action!=='verify'&&domainForm.reason.trim().length<3)throw new Error('请填写至少3个字符的操作原因')
  if(['verify','activate'].includes(action)&&!preparedMatches.value)throw new Error('输入已修改，请重新准备候选后核验')
  if(action==='activate'){
   if(!domainCandidates.value.length||domainCandidates.value.some(b=>b.status!=='VERIFIED'))throw new Error('须全部候选独立核验后才能激活')
   await ElMessageBox.confirm(`将租户 ${domainTenant.value.name} 的全部已核验候选一次生效，旧域名退役。前台切换可能需要重新登录；入口自动指向同租户新前台。`,'确认原子激活',{type:'warning'});if(seq!==domainGeneration)return
  }
  if(action==='release'){await ElMessageBox.confirm(`审查释放 ${binding.hostname}（${binding.role}）后允许重新分配，历史归属仍保留。`,'确认退役域名释放',{type:'warning'});if(seq!==domainGeneration)return}
  const version={domainVersion:domainState.value.domainVersion},reason={reason:domainForm.reason}
  const endpoint={prepare:'prepare-change',verify:'verify',activate:'activate-change',switch:'entry-switch',release:'release'}[action]
  let body:any
  if(action==='prepare')body={...version,...reason,entryHost:domainForm.entryHost,frontendHost:domainForm.frontendHost}
  else if(action==='activate')body={...version,...reason,entryEnabled:domainForm.entryEnabled,candidates:domainCandidates.value.map(({hostname,role,version})=>({hostname,role,version}))}
  else if(action==='switch')body={...version,...reason,entryEnabled:domainForm.entryEnabled}
  else{if(!binding)throw new Error('请选择角色候选');body={...version,...reason,hostname:binding.hostname,role:binding.role,version:binding.version}}
  await api(`/control/tenants/${target}/domains/${endpoint}`,'POST',body);if(seq!==domainGeneration)return
  const r=await api(`/control/tenants/${target}/domains/state`);if(seq!==domainGeneration)return;applyDomainState(r.data,['prepare','verify','release'].includes(action));await load()
  if(seq===domainGeneration)ElMessage.success(action==='activate'?'全部域名已原子生效':action==='switch'?'入口开关已保存；前台会话不变':action==='verify'?'该角色HTTPS与挑战核验通过；尚未激活':'已保存；现用域名不变')
 }catch(e:any){if(seq===domainGeneration&&e!=='cancel'&&e!=='close')domainError.value=e.message}finally{if(seq===domainGeneration)domainBusy.value=false}
}
async function copyAddress(row:any,kind:'ENTRY'|'FRONTEND'|'EXTERNAL'){
 const enabled=row.entryEnabled&&row.entryHost,host=kind==='ENTRY'?row.entryHost:kind==='FRONTEND'?row.frontendHost:enabled?row.entryHost:row.frontendHost
 if(!host){ElMessage.warning('该域名未配置');return}
 try{await navigator.clipboard.writeText(`https://${host}/`);if(!enabled&&kind!=='FRONTEND')ElMessage.warning(kind==='ENTRY'?'入口已关闭，此保留地址不能访问；只能访问前台':'入口未配置或关闭；已复制前台地址，只能直访前台');else ElMessage.success('地址已复制，不含登录令牌')}catch{ElMessage.error('复制失败，请手动复制当前生效地址')}
}
const accounts = ref<any>(null)
const accountsLoad=(userEmail?:string)=>api(`/control/tenants/${accounts.value.id}/backend-accounts?${new URLSearchParams(userEmail?{userEmail}:{})}`)
const accountsCreate=(body:any)=>api(`/control/tenants/${accounts.value.id}/backend-accounts`,'POST',body)
const tenants = ref<any[]>([]), busy = ref(false), error = ref(''), editOpen = ref(false), policiesOpen = ref(false), selected = ref<any>(null), online = ref<any>(null)
const form = reactive({ id: 0, code:'', name:'', frontendHost:'', status:'DRAFT', configReady:false, reason:'' })
const policy = reactive({ key:'feature.option', value:'false', locked:true, reason:'' }), policies = ref<any[]>([])
const states: Record<string,string> = { DRAFT:'草稿', ACTIVE:'正常', STOP_NEW:'停止新增', MAINTENANCE:'维护', DISABLED:'停用' }
const policyDefinitions=ref<any[]>([]),policiesLoading=ref(false),policiesError=ref('')
const editableDefinitions=computed(()=>policyDefinitions.value.filter(row=>row.tenantEditable))
const currentDefinition=computed(()=>policyDefinitions.value.find(row=>row.key===policy.key))
const namedPolicies=computed(()=>policies.value.map(row=>({...row,name:policyDefinitions.value.find(definition=>definition.key===row.key)?.name||row.key})))
let policyGeneration=0
function choosePolicy(key:string){const row=policies.value.find(row=>row.key===key),definition=policyDefinitions.value.find(row=>row.key===key);Object.assign(policy,{key,value:row?.value??definition?.defaultValue??'',locked:row?.locked??true,reason:''})}
async function load() { countsGeneration++;counts.value={};totalOnline.value=null;try { const rows=dataRows(await api('/control/tenants'));if(disposed)return;tenants.value=rows;error.value='';await readCounts() } catch (e:any) { if(!disposed)error.value=e.message } }
function edit(row?: any) { Object.assign(form, { id:row?.id || 0, code:row?.code || '', name:row?.name || '', frontendHost:row?.frontendHost || '', status:row?.status || 'DRAFT', configReady:row?.configReady || false, reason:'' }); editOpen.value=true }
async function save() {
  if (busy.value || !form.name.trim() || !form.reason.trim()) return
  busy.value=true
  try {
    await ElMessageBox.confirm('确认保存租户配置？停用不会删除数据或取消存量结算责任。', '确认总控操作', {type:'warning'})
    await api(form.id ? `/control/tenants/${form.id}` : '/control/tenants', form.id ? 'PUT':'POST', { code:form.code, name:form.name, frontendHost:form.frontendHost || null, status:form.status, configReady:form.configReady, reason:form.reason })
    editOpen.value=false; await load(); ElMessage.success('租户配置已保存')
  } catch(e:any) { if(e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '保存失败') } finally { busy.value=false }
}
async function enter(row:any) { try { await openTenant(row.id) } catch(e:any) { ElMessage.error(e.message) } }
async function policyList(row:any) {
 const generation=++policyGeneration;selected.value=row;policies.value=[];policyDefinitions.value=[];policiesError.value='';policiesOpen.value=true;policiesLoading.value=true;Object.assign(policy,{key:'',value:'',locked:true,reason:''})
 try{const [grants,definitions]=await Promise.all([api(`/control/tenants/${row.id}/policies`),api('/control/policy-definitions')]);if(generation!==policyGeneration)return;policies.value=dataRows(grants);policyDefinitions.value=dataRows(definitions);choosePolicy(editableDefinitions.value.find(definition=>definition.key==='feature.option')?.key||editableDefinitions.value[0]?.key||'')}catch(e:any){if(generation===policyGeneration)policiesError.value=e.message}finally{if(generation===policyGeneration)policiesLoading.value=false}
}
async function savePolicy() {
 if (busy.value || policiesLoading.value || policiesError.value || !selected.value) return
 if (!currentDefinition.value?.tenantEditable) { ElMessage.error('请先在授权策略页面定义并选择可编辑策略'); return }
 if (/password|secret|token|api.?key|credential/i.test(policy.key)) { ElMessage.error('密钥策略不支持明文写入，请经租户配置加密入口设置'); return }
 busy.value=true
 const target=selected.value,generation=policyGeneration
 try { await api(`/control/tenants/${target.id}/policies`,'PUT',{...policy}); if(generation===policyGeneration)await policyList(target); ElMessage.success('策略已更新；旧页面不能覆盖锁定项') } catch(e:any) { ElMessage.error(e.message) } finally { busy.value=false }
}
const onlineLoad=(page:number,size:number,userEmail?:string)=>api(`/control/tenants/${online.value.id}/online?${new URLSearchParams({page:String(page),size:String(size),...(userEmail?{userEmail}:{})})}`)
onMounted(async()=>{await load();if(!disposed)stopCounts=startReadPolling(readCounts)});onUnmounted(()=>{disposed=true;domainGeneration++;countsGeneration++;readinessGeneration++;policyGeneration++;stopCounts?.()})
</script>
<template>
  <div class="toolbar"><div><h2>租户管理</h2><p>同一业务程序、独立租户归属。业务监管只读；进入后台后具备本租户业务写权限。</p></div><el-button type="primary" @click="edit()">创建租户</el-button><el-button @click="load">刷新</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <p>租户 {{ tenants.length }} 个 · 平台在线账号 {{ totalOnline ?? '未知' }} 个 · {{ countsAt ? `统计时点 ${countsAt}` : '等待统计' }}。按各租户账号数相加，不合并同邮箱；最近 5 分钟有效活动，默认 5 秒刷新。</p>
  <el-alert v-if="countsError" :title="countsError" type="warning" :closable="false" />
  <admin-table table-key="control.tenants.domains.v2" :data="tenants" row-key="id" border>
    <el-table-column prop="id" label="ID" width="70" /><el-table-column prop="name" label="租户" min-width="140" />
    <el-table-column prop="entryHost" label="入口域名" min-width="230"><template #default="s">{{s.row.entryHost||'未配置'}}<el-button link :disabled="!s.row.entryHost" @click="copyAddress(s.row,'ENTRY')">复制入口地址</el-button></template></el-table-column>
    <el-table-column prop="frontendHost" label="前台域名" min-width="230"><template #default="s">{{s.row.frontendHost||'未配置'}}<el-button link :disabled="!s.row.frontendHost" @click="copyAddress(s.row,'FRONTEND')">复制前台地址</el-button></template></el-table-column>
    <el-table-column label="入口状态" min-width="150"><template #default="s">{{s.row.entryEnabled?'已开启':'已关闭；只能访问前台'}}<el-button link @click="copyAddress(s.row,'EXTERNAL')">复制对外地址</el-button></template></el-table-column>
    <el-table-column label="在线账号" width="100"><template #default="s"><el-button link @click="online=s.row">{{ counts[s.row.id] ?? '未知' }}</el-button></template></el-table-column>
    <el-table-column label="状态" width="110"><template #default="s">{{ states[s.row.status] || s.row.status }}</template></el-table-column>
    <el-table-column label="开放条件" min-width="140"><template #default="s">配置{{ s.row.configReady ? '齐备':'未齐' }} / 域名{{ s.row.domainVerified ? '已核查':'未核查' }}</template></el-table-column>
    <el-table-column label="操作" min-width="430"><template #default="s"><el-button link @click="edit(s.row)">编辑</el-button><el-button link @click="domain(s.row)">域名管理</el-button><el-button link @click="checkReadiness(s.row)">配置核查</el-button><el-button link @click="policyList(s.row)">授权 / 锁定</el-button><el-button link @click="accounts=s.row">后台账号</el-button><el-button link @click="online=s.row">在线明细</el-button><el-button type="primary" @click="enter(s.row)">进入后台</el-button></template></el-table-column>
  </admin-table>
  <el-dialog v-model="domainOpen" :title="`域名管理 · ${domainTenant?.name||''}`" width="min(860px,95vw)" :close-on-click-modal="false" :close-on-press-escape="!domainBusy" :show-close="!domainBusy" @closed="domainGeneration++;domainState=null;domainError='';domainBusy=false">
    <p>入口与前台按角色分别核验；不同前缀也可以映射。准备或核验失败不改变现用域名；候选15分钟过期。全部候选核验通过后一次原子激活。前台换域可能需要重新登录，不共享跨域令牌。</p>
    <el-alert v-if="domainError" :title="domainError" type="error" :closable="false"/>
    <template v-if="domainState">
      <el-alert v-if="!domainState.entryEnabled" title="入口关闭或未配置：只能直访前台；不会退出已登录的前台会话。" type="warning" :closable="false"/>
      <p>当前配置版本：{{domainState.domainVersion}}；当前入口开关：{{domainState.entryEnabled?'开启':'关闭'}}。</p>
      <el-form label-width="110px" :disabled="domainBusy">
        <template v-for="r in domainRoles" :key="r.role">
          <p>{{r.name}}现用：{{domainState[r.field]||'未配置'}}；核验：{{(r.role==='ENTRY'?domainState.entryVerified:domainState.domainVerified)?'已核验':'未核验'}}。</p>
          <el-form-item :label="r.name+'候选'"><el-input v-model="domainForm[r.field]" maxlength="253" :placeholder="r.role==='ENTRY'?'单层子域.forex-exchange.net':'单层子域.forex-exchange.cc'"/></el-form-item>
          <el-descriptions v-if="domainCandidates.find(b=>b.role===r.role)" :column="1" border><el-descriptions-item label="候选 / 状态 / 版本">{{domainCandidates.find(b=>b.role===r.role)?.hostname}} / {{domainCandidates.find(b=>b.role===r.role)?.status}} / {{domainCandidates.find(b=>b.role===r.role)?.version}}</el-descriptions-item><el-descriptions-item label="核验到期">{{domainCandidates.find(b=>b.role===r.role)?.expiresAt}}</el-descriptions-item></el-descriptions>
          <el-button :disabled="domainBusy||!preparedMatches||domainCandidates.find(b=>b.role===r.role)?.status!=='PENDING'" @click="domainAction('verify',domainCandidates.find(b=>b.role===r.role))">核验{{r.name}}HTTPS与挑战</el-button>
        </template>
        <el-form-item label="入口开关"><el-switch v-model="domainForm.entryEnabled"/><span>开启要求两类现用域名均已核验、属于同租户且允许访问。</span></el-form-item>
        <el-form-item label="操作原因"><el-input v-model="domainForm.reason" maxlength="500"/></el-form-item>
      </el-form>
      <el-button :disabled="domainBusy||domainForm.entryEnabled===domainState.entryEnabled" @click="domainAction('switch')">仅保存入口开关</el-button>
      <p v-if="!preparedMatches">输入已修改，需重新准备；不允许用旧核验覆盖新输入。</p>
      <details v-if="retiredDomains.length"><summary>退役域名（保留归属；显式审查后才能释放）</summary><p v-for="b in retiredDomains" :key="b.hostname">{{b.role}} / {{b.hostname}} / 版本{{b.version}} <el-button link :disabled="domainBusy" @click="domainAction('release',b)">审查释放</el-button></p></details>
    </template>
    <template #footer><el-button :disabled="domainBusy" @click="domain(domainTenant)">刷新配置</el-button><el-button :disabled="!domainState" :loading="domainBusy" @click="domainAction('prepare')">1. 准备全部变更</el-button><el-button type="danger" :disabled="domainBusy||!preparedMatches||!domainCandidates.length||domainCandidates.some(b=>b.status!=='VERIFIED')" @click="domainAction('activate')">2. 全部核验后原子激活</el-button></template>
  </el-dialog>
  <el-dialog v-model="editOpen" :title="form.id ? '修改租户':'安全模板创建租户'" width="min(620px,95vw)">
    <el-form label-width="100px" @submit.prevent="save">
      <el-form-item v-if="!form.id" label="租户编号"><el-input v-model="form.code" maxlength="64" /></el-form-item>
      <el-form-item label="名称" required><el-input v-model="form.name" maxlength="128" /></el-form-item>
      <el-form-item label="前台域名"><el-input v-model="form.frontendHost" :disabled="!!form.id" placeholder="总控基础域下唯一子域名" maxlength="253" /></el-form-item>
      <el-form-item v-if="form.id" label="状态"><el-select v-model="form.status"><el-option v-for="(label,value) in states" :key="value" :value="value" :label="label" /></el-select></el-form-item>
      <el-form-item v-if="form.id" label="配置齐备"><el-switch v-model="form.configReady" /></el-form-item>
      <el-alert v-if="!form.id" title="新租户保持草稿，按授权策略默认值初始化权限；安全模板不复制用户、资金、订单、收款地址、聊天、密码或第三方密钥。" :closable="false" />
      <el-form-item label="操作原因" required><el-input v-model="form.reason" type="textarea" maxlength="500" /></el-form-item>
    </el-form>
    <template #footer><el-button @click="editOpen=false">取消</el-button><el-button type="primary" :loading="busy" @click="save">确认保存</el-button></template>
  </el-dialog>
  <el-dialog v-model="readinessOpen" :title="`配置齐备核查 / ${readinessTenant?.name || ''}`" width="min(760px,95vw)"><p>仅查询实际依赖，不修改租户状态。字段缺项不会泄露密钥值；保存 ACTIVE、配置齐备或开启功能仍由服务端再次核验。</p><el-alert v-if="readinessError" :title="readinessError" type="error" :closable="false"/><template v-if="readiness"><el-alert :title="readiness.ready?'已满足当前已授权功能的配置依赖':'配置未齐备；补齐以下项目后重新核查'" :type="readiness.ready?'success':'warning'" :closable="false"/><admin-table table-key="control.readiness" :data="readiness.missing" border><el-table-column prop="key" label="配置项"/><el-table-column prop="message" label="缺项说明"/><el-table-column prop="owner" label="配置身份"/><el-table-column prop="location" label="设置页 / 运维操作" min-width="280"/></admin-table></template><p v-else-if="readinessBusy">正在读取真实配置依赖…</p><ol><li>safe-v1 保持草稿：不复制业务、秘密，按授权策略默认值自动设置权限；配置齐备并激活后才接受新增业务。</li><li>总控创建真实后台账号；域名分别 prepare / verify / activate，候选验证不授权普通业务。</li><li>运维核查三入口、可信代理、HTTPS 信任、JWT/MFA/租户密钥的受限引用；仅记录是否齐备与指纹，不展示密钥。</li><li>使用“进入后台”建立独立目标租户访问，再按上表页面补名称、时区、出站邮件、已审核品种/产品/参数、测试资金渠道和客服。</li><li>真实/模拟使用独立 DB、Redis、文件根与服务，映射、故障拒绝及实际代理需单独验收；readiness 不是部署证明。</li><li>本地环境可显式启用指定回环邮件、短信和域名验证服务；只验证本地开通闭环，不据此声明真实供应商或生产发布已验收。</li><li>总控按业务需要逐项授权并重新核查；ACTIVE 与配置齐备仍由服务端复核。只读核查不改租户状态。</li></ol><template #footer><el-button :loading="readinessBusy" @click="checkReadiness(readinessTenant)">重新核查</el-button></template></el-dialog>
  <el-dialog v-model="policiesOpen" :title="`授权及锁定 / ${selected?.name || ''}`" width="min(940px,95vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @closed="policyGeneration++;policiesLoading=false">
    <el-alert title="关闭功能只禁止新增；历史查询、存量结算及必要资金退出仍保留。默认值会自动设置新租户和未设置项；已有值、锁定项保持不变。" type="info" :closable="false" />
    <el-alert v-if="policiesError" :title="policiesError" type="error" :closable="false" />
    <admin-table table-key="control.policies.v2" :data="namedPolicies" row-key="key" v-loading="policiesLoading"><el-table-column prop="name" label="策略名字" min-width="160" fixed="left" /><el-table-column prop="key" label="策略键" min-width="240" /><el-table-column label="值" min-width="130"><template #default="s">{{ /password|secret|token|api.?key|credential/i.test(s.row.key)?'已遮罩；密钥请在租户配置加密入口设置':s.row.value }}</template></el-table-column><el-table-column label="锁定" width="120"><template #default="s">{{ s.row.locked ? '总控锁定':'租户可配' }}</template></el-table-column><el-table-column label="操作" width="100"><template #default="s"><el-button v-if="editableDefinitions.some(definition=>definition.key===s.row.key)" link :disabled="busy" @click="choosePolicy(s.row.key)">编辑</el-button><span v-else>专页维护</span></template></el-table-column></admin-table>
    <el-form label-width="90px" :disabled="busy||policiesLoading||!!policiesError" @submit.prevent="savePolicy">
      <el-form-item label="策略键"><el-select v-model="policy.key" filterable @change="choosePolicy"><el-option v-for="definition in editableDefinitions" :key="definition.key" :value="definition.key" :label="`${definition.name} / ${definition.key}`" /></el-select></el-form-item>
      <el-form-item label="策略值"><el-select v-if="currentDefinition?.options.length" v-model="policy.value"><el-option v-for="value in currentDefinition.options" :key="value" :value="value" :label="value===''?'（空值）':value" /></el-select><el-input v-else v-model="policy.value" maxlength="8192" /></el-form-item>
      <el-form-item label="锁定"><el-switch v-model="policy.locked" /></el-form-item>
      <el-form-item label="操作原因"><el-input v-model="policy.reason" maxlength="500" placeholder="选填；不填自动记录，填写时至少3个字符" /></el-form-item>
    </el-form>
    <p>名字、选项值和默认值在左侧“授权策略”页面维护。留存策略仍在“客服监管与留存”专页确认，不通过此表单开启清理。</p>
    <template #footer><el-button :disabled="busy||policiesLoading" @click="policyList(selected)">刷新策略</el-button><el-button :loading="busy" :disabled="policiesLoading||!!policiesError||!currentDefinition?.tenantEditable" type="primary" @click="savePolicy">保存策略</el-button></template>
  </el-dialog>
  <el-dialog :model-value="!!accounts" @update:model-value="(v:boolean)=>{if(!v)accounts=null}" title="系统后台账号" width="min(800px,95vw)" destroy-on-close><BackendAccounts :control="true" v-if="accounts" :key="accounts.id" :load="accountsLoad" :create="accountsCreate" /></el-dialog>
  <el-dialog :model-value="!!online" @update:model-value="(v: boolean)=>{if(!v)online=null}" :title="`在线用户 / ${online?.name || ''}`" width="min(1300px,95vw)" destroy-on-close><OnlineUsers :control="true" v-if="online" :key="online.id" :load="onlineLoad" /></el-dialog>
</template>
