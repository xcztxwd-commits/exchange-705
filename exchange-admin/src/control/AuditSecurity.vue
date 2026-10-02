<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, dataRows, clearSession } from './api'
import ControlAccounts from './ControlAccounts.vue'
const mfa=reactive({password:'',totp:'',newSecret:'',newTotp:''}),mfaOpen=ref(false)
async function changeMfa(){try{await api('/control/security/mfa','POST',{...mfa});Object.assign(mfa,{password:'',totp:'',newSecret:'',newTotp:''});clearSession();ElMessage.success('MFA 已更新，请重新登录')}catch(e:any){ElMessage.error(e.message)}}
const props=defineProps<{security?:boolean}>()
const rows=ref<any[]>([]),error=ref(''),page=ref(1),total=ref(0)
async function load(){try{const response=await api(`/control/${props.security?'access-sessions':'audit'}?page=${page.value-1}&size=20`);const data=response.data||response;rows.value=dataRows(response);total.value=data.totalElements??data.total??rows.value.length;error.value=''}catch(e:any){error.value=e.message}}
async function revoke(row:any){try{await ElMessageBox.confirm(`撤销访问会话 ${row.id}？立即终止该会话的租户权限。`,'确认撤销',{type:'warning'});await api(`/control/access-sessions/${encodeURIComponent(row.id)}/revoke`,'POST');await load();ElMessage.success('已撤销')}catch(e:any){if(e!=='cancel'&&e!=='close')ElMessage.error(e.message)}}
onMounted(load)
</script>
<template>
  <div class="toolbar"><div><h2>{{ security?'总控访问会话':'总控专用审计' }}</h2><p>{{ security?'访问限时、可撤销。生产总控登录需 MFA。':'仅总控可见；真实操作者与结果留痕，正文不可编辑或删除。' }}</p></div><el-button v-if="security" @click="mfaOpen=true">更换 MFA</el-button><el-button @click="load">刷新</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <admin-table :table-key="security ? 'control.access-sessions' : 'control.audit'" :data="rows" border><el-table-column prop="id" label="记录 ID" min-width="130" /><el-table-column prop="actorId" label="真实操作者 ID" width="130" /><el-table-column prop="tenantId" label="目标租户" width="100" /><el-table-column prop="createdAt" label="创建时间" min-width="180" />
    <template v-if="security"><el-table-column prop="expiresAt" label="绝对到期" min-width="180" /><el-table-column prop="lastActivityAt" label="最近交互" min-width="180" /><el-table-column label="状态" width="110"><template #default="s">{{ s.row.revoked?'已撤销':s.row.consumed?'已交换':'待交换' }}</template></el-table-column><el-table-column label="操作" width="100"><template #default="s"><el-button :disabled="s.row.revoked" type="danger" link @click="revoke(s.row)">撤销</el-button></template></el-table-column></template>
    <template v-else><el-table-column prop="action" label="动作" min-width="160" /><el-table-column prop="objectRef" label="对象" min-width="100" /><el-table-column prop="outcome" label="结果" width="110" /><el-table-column prop="reason" label="原因" min-width="200" /><el-table-column prop="detail" label="脱敏变化" min-width="200" /></template>
  </admin-table>
  <el-dialog v-model="mfaOpen" title="更换总控 MFA" width="min(550px,95vw)" @closed="Object.assign(mfa,{password:'',totp:'',newSecret:'',newTotp:''})"><p>需验证当前与新设备。首次 MFA 通过受控部署引导配置，不提供无验证的网页绑定。成功后撤销全部总控会话。</p><el-form label-width="120px"><el-form-item label="当前密码"><el-input v-model="mfa.password" type="password" autocomplete="current-password"/></el-form-item><el-form-item label="当前动态码"><el-input v-model="mfa.totp" inputmode="numeric" maxlength="6"/></el-form-item><el-form-item label="新 TOTP 密钥"><el-input v-model="mfa.newSecret" type="password" autocomplete="off"/></el-form-item><el-form-item label="新设备动态码"><el-input v-model="mfa.newTotp" inputmode="numeric" maxlength="6"/></el-form-item></el-form><template #footer><el-button type="danger" @click="changeMfa">验证并更换</el-button></template></el-dialog>
  <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total,prev,pager,next" @current-change="load" />
  <ControlAccounts v-if="security" style="margin-top:36px" />
</template>
