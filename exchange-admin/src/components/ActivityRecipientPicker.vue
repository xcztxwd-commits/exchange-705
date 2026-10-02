<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessageBox } from 'element-plus'
import { requestId } from '@/utils/support'
import { emptyRecipientFilter, recipientFilter, recipientIds, recipientPage, activitySelection, type ActivityRecipient } from '@/utils/activityRecipients'
const props=defineProps<{ request:any; campaign:any; readOnly:boolean }>()
const emit=defineEmits<{(event:'close'):void;(event:'busy',value:boolean):void}>()
const filters=ref(emptyRecipientFilter()), applied=ref<any>(), matches=ref<ActivityRecipient[]>([]), total=ref(0), page=ref(1), checked=ref<number[]>([]), searching=ref(false), searchError=ref('')
const selection=ref<any>(), chosen=ref<ActivityRecipient[]>([]), chosenPage=ref(1), choosing=ref(false), selectionError=ref('')
const campaignOptions=ref<any[]>([]), campaignPage=ref(0), campaignTotal=ref(0), campaignError=ref('')
const sending=ref(false), progress=ref<any>(), sendError=ref(''), sendId=ref('')
const locked=computed(()=>props.readOnly||choosing.value||sending.value||!!sendId.value)
const busy=computed(()=>choosing.value||sending.value)
watch(busy,value=>emit('busy',value))
let searchVersion=0, selectedVersion=0, disposed=false, pendingMutation:{signature:string;body:any}|undefined
watch(filters,()=>{++searchVersion;matches.value=[];applied.value=undefined;checked.value=[];searchError.value='';searching.value=false;total.value=0;page.value=1},{deep:true,flush:'sync'})
watch(()=>props.readOnly,value=>{if(value){++searchVersion;++selectedVersion;matches.value=[];chosen.value=[];applied.value=undefined;selection.value=undefined;pendingMutation=undefined;searching.value=false;choosing.value=false}},{flush:'sync'})
async function search(nextPage=1){
 const version=++searchVersion;matches.value=[];checked.value=[];searchError.value='';searching.value=true;applied.value=undefined
 try{
  const filter=recipientFilter(filters.value)
  const result=recipientPage(await props.request.get('/admin/activities/recipients/search',{params:{...filter,page:nextPage-1,size:20},paramsSerializer:{indexes:null}}))
  if(disposed||version!==searchVersion)return
  matches.value=result.content;total.value=result.totalElements;page.value=nextPage;applied.value=filter
 }catch(e:any){if(!disposed&&version===searchVersion)searchError.value=e.message||'搜索失败'}finally{if(version===searchVersion)searching.value=false}
}
async function loadCampaigns(){
 try{const r:any=await props.request.get('/admin/activities',{params:{template:false,page:campaignPage.value}});if(disposed)return;campaignOptions.value=[...campaignOptions.value,...(r.content||[]).filter((c:any)=>c.id!==props.campaign.id)];campaignTotal.value=r.totalElements||0;campaignPage.value++;campaignError.value=''}catch(e:any){campaignError.value=e.message}
}
async function loadChosen(nextPage=1){
 if(!selection.value)return
 const version=++selectedVersion;selectionError.value=''
 try{const data=recipientPage(await props.request.get(`/admin/activities/${props.campaign.id}/recipients/${selection.value.selectionId}/selected`,{params:{page:nextPage-1,size:20}}));if(disposed||version!==selectedVersion)return;chosen.value=data.content;selection.value.totalElements=data.totalElements;chosenPage.value=nextPage}catch(e:any){if(version===selectedVersion)selectionError.value=e.message}
}
async function changeSelection(change:{userIds?:number[];filter?:any;removeUserIds?:number[]}){
 if(locked.value)return
 choosing.value=true;selectionError.value=''
 const body={...change,selectionId:selection.value?.selectionId}, signature=JSON.stringify(body)
 if(pendingMutation?.signature!==signature)pendingMutation={signature,body:{...body,operationId:requestId()}}
 const version=++selectedVersion
 try{
  const path=!selection.value?`/admin/activities/${props.campaign.id}/recipients/select-all`:`/admin/activities/${props.campaign.id}/recipients/${selection.value.selectionId}/${change.removeUserIds?'remove':'append'}`
  const payload={...pendingMutation.body,...(change.removeUserIds?{userIds:change.removeUserIds}:{} )}
  delete payload.removeUserIds
  const result=activitySelection(await props.request.post(path,payload))
  if(disposed||version!==selectedVersion||props.readOnly)return
  if(!selection.value&&change.userIds&&result.selected!==recipientIds(change.userIds).length)throw new Error('显式选人契约不匹配，未启用发送；不会将默认全表当作已选择')
  selection.value=result;pendingMutation=undefined;checked.value=[];await loadChosen(1)
 }catch(e:any){if(!disposed&&version===selectedVersion)selectionError.value=e.message||'选择失败，请重试同一操作'}finally{choosing.value=false}
}
function addChecked(){return changeSelection({userIds:recipientIds(checked.value)})}
function addAll(){if(!applied.value||!total.value)return;return changeSelection({filter:JSON.parse(JSON.stringify(applied.value))})}
function toggle(id:number,value:any){checked.value=value?recipientIds([...checked.value,id]):checked.value.filter(n=>n!==id)}
async function send(){
 if(props.readOnly||sending.value||choosing.value||!selection.value?.totalElements)return
 if(!sendId.value){try{await ElMessageBox.confirm(`向已选择的 ${selection.value.totalElements} 位用户发送活动公告？资格、期限、重复发送和预算仍由后端校验。`,'确认批量发送',{confirmButtonText:'确认发送',cancelButtonText:'取消'})}catch{return};sendId.value=requestId()}
 sending.value=true;sendError.value=''
 try{
  let remaining=selection.value.totalElements
  do{
   const result:any=await props.request.post(`/admin/activities/${props.campaign.id}/send-selection`,{selectionId:selection.value.selectionId,sendOperationId:sendId.value})
   if(disposed||props.readOnly)return
   if(!Number.isSafeInteger(result.selected)||!Number.isSafeInteger(result.remaining)||result.remaining<0||result.remaining>result.selected)throw new Error('发送进度契约无效')
   progress.value={...result,operationId:sendId.value,total:result.selected,processed:result.selected-result.remaining,status:result.done?'COMPLETED':'RUNNING'}
   if(result.done&&result.remaining!==0)throw new Error('发送未完成，不能把部分结果当作完成')
   if(result.done)break
   if(result.remaining>=remaining)throw new Error('批次没有推进，请用同一操作重试')
   remaining=result.remaining
   await new Promise(resolve=>setTimeout(resolve,100))
  }while(!disposed&&!props.readOnly)
 }catch(e:any){sendError.value=e.message||'发送失败'}finally{sending.value=false}
}
onMounted(loadCampaigns)
onBeforeUnmount(()=>{disposed=true;++searchVersion;++selectedVersion})
</script>
<template>
 <div class="activity-recipient-picker">
  <p>{{campaign.name}} · 每人 {{campaign.amount}} U</p>
  <el-alert type="info" :closable="false" :title="campaign.allowRepeatSend?'允许重复发送公告，不代表允许重复领取；领取仍按独立开关校验。':'已发送用户按后端规则跳过；筛选确认只添加选人，不自动发送。'"/>
  <el-form label-position="top" :disabled="locked" class="recipient-search">
   <el-form-item label="用户 ID 或邮箱"><el-input v-model="filters.query" aria-label="搜索用户 ID 或邮箱" maxlength="128" clearable/></el-form-item>
   <div class="range-grid"><el-form-item v-for="field in [{key:'createdFrom',label:'创建开始时间'},{key:'createdTo',label:'创建结束时间'},{key:'lastLoginFrom',label:'最近登录开始时间'},{key:'lastLoginTo',label:'最近登录结束时间'}]" :key="field.key" :label="field.label+'（服务器时间）'"><el-date-picker v-model="(filters as any)[field.key]" :aria-label="field.label" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" clearable/></el-form-item></div>
   <div class="claim-grid"><el-form-item label="其他已领取活动（任意匹配，多选）"><el-select v-model="filters.claimedCampaignIds" multiple filterable clearable aria-label="已领取活动" placeholder="不限制已领取活动"><el-option v-for="row in campaignOptions" :key="row.id" :value="row.id" :label="row.name+' · '+row.id"/></el-select><el-button v-permission="'announcement:view'" v-if="campaignPage*50<campaignTotal" link @click="loadCampaigns">加载更多活动</el-button><span v-if="campaignError" role="alert">{{campaignError}} <el-button v-permission="'announcement:view'" link @click="loadCampaigns">重试活动列表</el-button></span></el-form-item><el-form-item label="已领取筛选语义"><el-select v-model="filters.claimedMode" aria-label="已领取筛选语义"><el-option label="包含：领过任意所选活动" value="INCLUDE"/><el-option label="排除：从未领过所选活动" value="EXCLUDE"/></el-select></el-form-item></div>
  </el-form>
  <div class="recipient-actions"><el-button v-permission="'announcement:edit'" :disabled="locked" @click="search(1)">查询用户</el-button><el-button v-permission="'announcement:edit'" :disabled="locked||!checked.length||!applied" @click="addChecked">确认添加选中 {{checked.length}} 人</el-button><el-button v-permission="'announcement:edit'" :disabled="locked||!applied||!total" @click="addAll">一键添加全部 {{total}} 位结果</el-button></div>
  <p role="status" aria-live="polite">{{searching?'搜索中…':searchError||(applied?`匹配 ${total} 人；当前第 ${page} 页，${matches.length} 人`:'填写条件后查询')}}</p><el-button v-permission="'announcement:edit'" v-if="searchError" :disabled="locked" @click="search(page)">重试查询</el-button>
  <ul class="activity-user-list" aria-label="用户查询结果"><li v-for="user in matches" :key="user.id"><el-checkbox :model-value="checked.includes(user.id)" :aria-label="'选择用户 '+user.id" :disabled="locked" @change="(value:any)=>toggle(user.id,value)"/><span>ID {{user.id}} · {{user.email||'未设置邮箱'}}</span><el-button v-permission="'announcement:edit'" :aria-label="'添加用户 '+user.id" :disabled="locked" @click="changeSelection({userIds:[user.id]})">添加</el-button></li></ul>
  <el-pagination v-if="total" :current-page="page" :page-size="20" :total="total" :disabled="locked||searching" layout="total,prev,pager,next" @current-change="search"/>
  <section class="selected-users"><h3>已选择 {{selection?.totalElements||0}} 位用户</h3><small>服务端保存完整去重集合；此处仅分页查看，可删除并继续筛选追加。未发送前可编辑。</small><p v-if="selectionError" role="alert">{{selectionError}}</p><el-button v-permission="'announcement:edit'" v-if="selectionError&&selection" :disabled="locked" @click="loadChosen(chosenPage)">重试已选择列表</el-button>
   <ul class="activity-user-list" aria-label="已选择用户"><li v-for="user in chosen" :key="user.id"><span>ID {{user.id}} · {{user.email||'未设置邮箱'}}</span><el-button v-permission="'announcement:edit'" :aria-label="'删除用户 '+user.id" :disabled="locked" @click="changeSelection({removeUserIds:[user.id]})">删除</el-button></li></ul><el-pagination v-if="selection?.totalElements" :current-page="chosenPage" :page-size="20" :total="selection.totalElements" :disabled="locked" layout="total,prev,pager,next" @current-change="loadChosen"/>
  </section>
  <div v-if="progress" role="status" aria-live="polite" class="send-progress">发送进度 {{progress.processed||0}} / {{progress.total||0}} · {{progress.status}}<el-progress :percentage="progress.total?Math.min(100,Math.round(progress.processed/progress.total*100)):0"/><p>新增发送 {{progress.sent||0}}，重复跳过 {{progress.duplicates||0}}，资格不符 {{progress.ineligible||0}}</p><small>操作 {{progress.operationId}}；重试保留相同 operationId，不重复投递。</small></div>
  <p v-if="sendError" role="alert">{{sendError}}</p><footer><el-button v-permission="'session:close'" :disabled="busy" @click="emit('close')">关闭</el-button><el-button v-permission="'announcement:edit'" type="primary" :loading="sending" :disabled="props.readOnly||choosing||!selection?.totalElements||progress?.status==='COMPLETED'" @click="send">{{sendError?'重试发送（同一操作）':'确认发送'}}</el-button></footer>
 </div>
</template>
<style scoped>
.recipient-search{margin-top:18px}.range-grid,.claim-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 16px}.range-grid :deep(.el-date-editor),.claim-grid :deep(.el-select){width:100%}.recipient-actions{display:flex;flex-wrap:wrap;gap:8px}.recipient-actions .el-button{margin:0}.activity-user-list{list-style:none;padding:0;max-height:240px;overflow:auto}.activity-user-list li{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:8px;border-bottom:1px solid #e7ece1}.activity-user-list li span{overflow-wrap:anywhere;min-width:0;flex:1}.selected-users{padding:14px;border:1px solid #e7ece1;background:#fafcf6;border-radius:10px}.selected-users small,.send-progress small{color:#6d785f}.send-progress{margin-top:18px}footer{display:flex;justify-content:flex-end;gap:10px;padding-top:20px}@media(max-width:650px){.range-grid,.claim-grid{grid-template-columns:1fr}}
</style>
