<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
const props=defineProps<{load:(userEmail?:string)=>Promise<any>;create:(body:any)=>Promise<any>;control?:boolean}>()
const rows=ref<any[]>([]),email=ref(''),error=ref(''),busy=ref(false)
const form=reactive({type:'ADMIN',account:'',email:'',password:'',subjectId:'',role:'admin',reason:''})
let generation=0,active=true
onUnmounted(()=>{active=false;generation++})
async function reload(){const id=++generation;try{const r=await props.load(email.value.trim()||undefined);if(active&&id===generation){rows.value=r.data||[];error.value=''}}catch(e:any){if(active&&id===generation)error.value=e.message}}
async function create(){if(busy.value)return;busy.value=true;try{await props.create({...form,subjectId:form.type==='AGENT'?Number(form.subjectId):undefined});form.password='';form.account='';await reload();ElMessage.success('后台账号已开通')}catch(e:any){ElMessage.error(e.message)}finally{busy.value=false}}
onMounted(reload)
</script>
<template>
 <p>后台登录名全局唯一；冲突只提示不可用。代理绑定本租户已有代理用户，不按前台邮箱猜测身份。</p><el-alert v-if="error" :title="error" type="error" :closable="false" />
 <el-form inline @submit.prevent="reload"><el-form-item label="用户邮箱"><el-input v-model="email" placeholder="用户邮箱" clearable maxlength="254" @clear="reload" /></el-form-item><el-button v-permission="control ? 'session:self' : 'admin_list:view'" native-type="submit">搜索</el-button></el-form>
 <admin-table table-key="backend-accounts" :data="rows"><el-table-column prop="normalizedAccount" label="后台登录名"/><el-table-column prop="subjectType" label="身份类型"/><el-table-column prop="subjectId" label="主体 ID"/><el-table-column prop="userEmail" label="用户邮箱" min-width="200" show-overflow-tooltip/><el-table-column prop="userRemark" label="用户备注" min-width="150" show-overflow-tooltip><template #default="{row}">{{row.userRemark||'-'}}</template></el-table-column><el-table-column prop="enabled" label="启用"/></admin-table>
 <el-form label-width="100px"><el-form-item label="身份类型"><el-select v-model="form.type"><el-option value="ADMIN" label="管理员 / 客服"/><el-option value="AGENT" label="现有代理"/></el-select></el-form-item><el-form-item label="后台账号" required><el-input v-model="form.account" autocomplete="off"/></el-form-item><template v-if="form.type==='ADMIN'"><el-form-item label="邮箱" required><el-input v-model="form.email" type="email"/></el-form-item><el-form-item label="初始密码" required><el-input v-model="form.password" type="password" autocomplete="new-password" show-password placeholder="至少 6 位，允许纯数字"/></el-form-item><el-form-item label="基础身份"><el-select v-model="form.role"><el-option value="admin" label="员工（随后分配角色）"/><el-option value="super_admin" label="租户负责人"/></el-select></el-form-item></template><el-form-item v-else label="代理用户 ID" required><el-input v-model="form.subjectId" inputmode="numeric" /></el-form-item><el-form-item label="原因" required><el-input v-model="form.reason" maxlength="500"/></el-form-item><el-form-item><el-button v-permission="control ? 'session:self' : 'admin_list:create'" type="primary" :loading="busy" @click="create">开通账号</el-button></el-form-item></el-form>
</template>
