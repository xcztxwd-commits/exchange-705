<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api, controlSession, clearSession, login, logout } from './api'
import TenantManager from './TenantManager.vue'
import PolicyDefinitions from './PolicyDefinitions.vue'
import AuditSecurity from './AuditSecurity.vue'
import BusinessSupervision from './BusinessSupervision.vue'
import ChatSupervision from './ChatSupervision.vue'
import Operations from './Operations.vue'
import SupervisionRecords from './SupervisionRecords.vue'
import StatisticsSupervision from './StatisticsSupervision.vue'
const tab=ref('tenants'),busy=ref(false),error=ref(''),checking=ref(!!controlSession.value)
const form=reactive({account:'',password:'',totp:''})
async function submit(){if(busy.value)return;busy.value=true;error.value='';try{await login(form.account,form.password,form.totp);form.password='';form.totp=''}catch(e:any){error.value=e.message}finally{busy.value=false}}
async function exit(){try{await logout();tab.value='tenants'}catch(e:any){ElMessage.error(e.message)}}
onMounted(async()=>{if(!controlSession.value)return;try{await api('/control/auth/me')}catch(e:any){clearSession();error.value=e.message}finally{checking.value=false}})
</script>
<template>
  <main v-if="!controlSession" class="control-login"><h1>平台总控</h1><p>独立总控身份 · 真实操作留痕</p><el-alert v-if="error" :title="error" type="error" :closable="false" /><el-form @submit.prevent="submit"><el-form-item label="账号"><el-input v-model="form.account" autocomplete="username" /></el-form-item><el-form-item label="密码"><el-input v-model="form.password" type="password" autocomplete="current-password" show-password /></el-form-item><el-form-item label="动态码"><el-input v-model="form.totp" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="MFA 动态验证码" /></el-form-item><el-button native-type="submit" type="primary" :loading="busy">登录总控</el-button></el-form></main>
  <p v-else-if="checking">正在验证总控会话…</p>
  <div v-else class="control-shell"><header><h1>平台总控</h1><span>{{ controlSession.user.account }}</span><el-button @click="exit">退出并撤销访问</el-button></header><div class="control-body"><aside><button :class="{active:tab==='tenants'}" @click="tab='tenants'">租户 / 授权 / 在线</button><button :class="{active:tab==='policies'}" @click="tab='policies'">授权策略</button><button :class="{active:tab==='business'}" @click="tab='business'">业务监管 · 只读</button><button v-for="(label,kind) in {kyc:'KYC 认证监管',admins:'管理员监管',agents:'代理监管',statistics:'统计监管'}" :key="kind" :class="{active:tab===kind}" @click="tab=kind">{{label}}</button><button :class="{active:tab==='chat'}" @click="tab='chat'">客服监管与留存</button><button :class="{active:tab==='operations'}" @click="tab='operations'">运行异常与复核</button><button :class="{active:tab==='audit'}" @click="tab='audit'">总控审计</button><button :class="{active:tab==='security'}" @click="tab='security'">安全与访问会话</button></aside><section class="control-content"><TenantManager v-if="tab==='tenants'"/><PolicyDefinitions v-else-if="tab==='policies'"/><BusinessSupervision v-else-if="tab==='business'"/><SupervisionRecords v-else-if="tab==='kyc'||tab==='admins'||tab==='agents'" :key="tab" :kind="tab"/><StatisticsSupervision v-else-if="tab==='statistics'"/><ChatSupervision v-else-if="tab==='chat'"/><Operations v-else-if="tab==='operations'"/><AuditSecurity v-else :key="tab" :security="tab==='security'" /></section></div></div>
</template>
<style>
.control-login{max-width:440px;margin:12vh auto;padding:32px;background:white;border:1px solid #e2e8f0;border-radius:12px}.control-login h1{margin-bottom:12px}.control-login p{margin-bottom:28px;color:#64748b}.control-login .el-form{margin-top:20px}.control-shell{min-height:100vh;background:#f5f7fa;color:#263445}.control-shell header{height:70px;display:flex;align-items:center;gap:22px;padding:0 28px;background:white;border-bottom:1px solid #e2e8f0}.control-shell h1{font-size:22px;flex:1}.control-body{display:flex;min-height:calc(100vh - 70px)}.control-body aside{width:220px;flex:none;background:#304156;padding:24px 12px}.control-body aside button{display:block;width:100%;text-align:left;border:0;border-radius:6px;background:transparent;color:#d6e0ec;padding:14px 16px;margin-bottom:8px;font:inherit;cursor:pointer}.control-body aside button.active{background:#85bd00;color:white}.control-content{min-width:0;flex:1;padding:28px}.toolbar{display:flex;align-items:center;gap:12px;margin-bottom:22px}.toolbar>div{flex:1}.toolbar h2{font-size:20px;margin-bottom:8px}.toolbar p,.control-content>p{font-size:14px;color:#64748b;line-height:1.6}.el-pagination{margin-top:20px}.el-alert{margin-bottom:18px}.el-dialog .el-form{margin-top:20px}@media(max-width:800px){.control-body{display:block}.control-body aside{width:100%;display:flex;overflow:auto;padding:8px}.control-body aside button{white-space:nowrap;margin:0}.control-content{padding:16px}.toolbar{flex-wrap:wrap}.toolbar>div{flex-basis:100%}.control-shell header{padding:0 16px}.control-login{margin:8vh 16px}}
</style>
