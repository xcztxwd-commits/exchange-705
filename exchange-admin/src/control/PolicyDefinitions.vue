<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api, dataRows } from './api'
const rows=ref<any[]>([]),busy=ref(false),loading=ref(false),error=ref(''),open=ref(false),existing=ref(false),fixedValues=ref(false),note=ref('')
const form=reactive({key:'',name:'',options:[] as string[],defaultValue:'',version:null as number|null,reason:''})
async function load(){loading.value=true;try{rows.value=dataRows(await api('/control/policy-definitions'));error.value=''}catch(e:any){error.value=e.message}finally{loading.value=false}}
function edit(row?:any){Object.assign(form,{key:row?.key||'',name:row?.name||'',options:[...(row?.options||[])],defaultValue:row?.defaultValue??'',version:row?.version??null,reason:''});existing.value=!!row;fixedValues.value=!!row?.fixedValues;note.value=row?.note||'';open.value=true}
function changeOptions(){if(form.options.length&&!form.options.includes(form.defaultValue))form.defaultValue=form.options[0]!}
async function save(){
 if(busy.value)return
 if(!form.key.trim()||!form.name.trim()){ElMessage.warning('请填写策略键和策略名字');return}
 if(form.options.length&&!form.options.includes(form.defaultValue)){ElMessage.warning('默认值必须在选项值中');return}
 busy.value=true
 try{const result=await api('/control/policy-definitions','PUT',{...form,key:form.key.trim(),name:form.name.trim()});open.value=false;await load();ElMessage.success(`策略已保存：默认值自动设置 ${result.data.appliedTenants} 个租户，保留 ${result.data.retainedTenants} 个已有值`)}catch(e:any){ElMessage.error(e.message)}finally{busy.value=false}
}
onMounted(load)
</script>
<template>
 <div class="toolbar"><div><h2>授权策略</h2><p>编辑策略名字、可选值和默认值。默认值自动设置新租户及尚未设置该策略的租户，按总控锁定保存；已有明确值和锁定项不覆盖。未就绪的正常租户会拒绝整次保存，草稿租户仍须配置齐备后激活。</p></div><el-button type="primary" @click="edit()">新增配置策略</el-button><el-button :loading="loading" @click="load">刷新</el-button></div>
 <el-alert v-if="error" :title="error" type="error" :closable="false"/>
 <admin-table table-key="control.policy-definitions" :data="rows" row-key="key" border v-loading="loading">
  <el-table-column prop="name" label="策略名字" min-width="170" fixed="left"/>
  <el-table-column prop="key" label="策略键" min-width="250"/>
  <el-table-column label="选项值" min-width="180"><template #default="s">{{s.row.options.length?s.row.options.map((value:string)=>value===''?'（空值）':value).join(' / '):'自由输入'}}</template></el-table-column>
  <el-table-column prop="defaultValue" label="默认值" min-width="130"/>
  <el-table-column label="操作" width="90"><template #default="s"><el-button link @click="edit(s.row)">编辑</el-button></template></el-table-column>
 </admin-table>
 <el-dialog v-model="open" :title="existing?'编辑授权策略':'新增配置策略'" width="min(680px,95vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
  <el-alert v-if="note" :title="note" type="info" :closable="false"/>
  <el-form label-width="100px" :disabled="busy" @submit.prevent="save">
   <el-form-item label="策略名字" required><el-input v-model="form.name" maxlength="128"/></el-form-item>
   <el-form-item label="策略键" required><el-input v-model="form.key" :disabled="existing" maxlength="128" placeholder="config.已有业务配置键"/></el-form-item>
   <el-form-item label="选项值"><el-select v-model="form.options" multiple filterable allow-create default-first-option :disabled="fixedValues" placeholder="输入选项值后回车；非功能配置可留空，自由输入" @change="changeOptions"><el-option v-for="value in form.options" :key="value" :label="value===''?'（空值）':value" :value="value"/></el-select></el-form-item>
   <el-form-item label="默认值" required><el-select v-if="form.options.length" v-model="form.defaultValue" :disabled="fixedValues"><el-option v-for="value in form.options" :key="value" :label="value===''?'（空值）':value" :value="value"/></el-select><el-input v-else v-model="form.defaultValue" maxlength="8192" :disabled="fixedValues"/></el-form-item>
   <el-form-item label="操作原因"><el-input v-model="form.reason" maxlength="500" placeholder="选填；不填自动记录，填写时至少3个字符"/></el-form-item>
  </el-form>
  <p class="policy-help">功能值仅允许 true/false，并保留 false；客服渠道保留 off。新增配置定义不会实现新的业务功能；密码、密钥等不得作为策略保存。</p>
  <template #footer><el-button :disabled="busy" @click="open=false">取消</el-button><el-button type="primary" :loading="busy" @click="save">保存策略定义</el-button></template>
 </el-dialog>
</template>
<style scoped>
.el-select{width:100%}.policy-help{color:#64748b;font-size:13px;line-height:1.6}
</style>
