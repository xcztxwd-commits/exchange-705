<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { useTenantPolicies } from '@/composables/useTenantPolicies'
import TenantPolicyNotice from '@/components/TenantPolicyNotice.vue'
const {snapshot,policyError,policyReady,reloadPolicies,editable,policyLabel}=useTenantPolicies('website')
const loading = ref(false), saving = ref(false), loaded = ref(false)
const form = reactive({ captchaIpPerMinute: 30, captchaSessionPerMinute: 10, captchaGlobalPerMinute: 600, registerIpPerMinute: 10, registerSessionPerMinute: 5, registerGlobalPerMinute: 120 })
const rows = [
  { key: 'captchaIpPerMinute', label: '验证码 · 单 IP', max: 300 },
  { key: 'captchaSessionPerMinute', label: '验证码 · 单页面会话', max: 60 },
  { key: 'captchaGlobalPerMinute', label: '验证码 · 全站', max: 10000 },
  { key: 'registerIpPerMinute', label: '注册 · 单 IP', max: 100 },
  { key: 'registerSessionPerMinute', label: '注册 · 单页面会话', max: 30 },
  { key: 'registerGlobalPerMinute', label: '注册 · 全站', max: 3000 },
] as const
async function load() {
  loading.value = true; loaded.value = false
  try { await reloadPolicies(); Object.assign(form, await request.get('/admin/website-security')); loaded.value = true }
  catch (e: any) { ElMessage.error(e.message || '加载失败，请重试') }
  finally { loading.value = false }
}
async function save() {
  if (!loaded.value || saving.value || !editable('security.registration.v1')) return
  if (rows.some(row => !Number.isInteger(form[row.key]) || form[row.key] < 1 || form[row.key] > row.max)) { ElMessage.error('请输入范围内的整数，不能关闭限流'); return }
  saving.value = true
  try { Object.assign(form, await request.put('/admin/website-security', { ...form })); ElMessage.success('网站安全配置已保存，下次请求生效') }
  catch (e: any) { ElMessage.error(e.message || '保存失败，请重试') }
  finally { saving.value = false }
}
onMounted(load)
</script>
<template>
  <div class="website-security">
    <h2>网站安全</h2><TenantPolicyNotice :snapshot="snapshot" :error="policyError"/>
    <p>注册防刷与图形验证码。仅超级管理员可以修改，保存操作进入后台操作日志。</p>
    <el-card shadow="never">
      <template #header>图形验证码</template>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="内容">4 位字符，每张同时包含字母和数字，不区分大小写</el-descriptions-item>
        <el-descriptions-item label="随机干扰">3–9 条线段、1–5 个圆圈、0–1 层轻度扭曲</el-descriptions-item>
        <el-descriptions-item label="有效期与校验">120 秒；刷新立即作废旧图；正确或错误都只允许校验一次</el-descriptions-item>
        <el-descriptions-item label="故障策略">Redis、配置存储或图片生成故障时阻止注册，绝不跳过校验</el-descriptions-item>
      </el-descriptions>
    </el-card>
    <el-card shadow="never" class="rate-card" v-loading="loading">
      <template #header>限流设置</template>
      <el-alert title="每个维度独立计数，从首次请求起计时 60 秒；成功和失败的请求均占用配额。多个后端共享 Redis，限流不随页面刷新重置。" type="info" :closable="false" />
      <el-form v-if="loaded" label-position="top" :disabled="!loaded || saving || !editable('security.registration.v1')" class="limit-form">
        <el-form-item v-for="row in rows" :key="row.key" :label="row.label + '（次 / 60 秒）'">
          <el-input-number v-model="form[row.key]" :min="1" :max="row.max" :precision="0" :step="1" controls-position="right" />
          <span class="range">1–{{ row.max }}</span>
        </el-form-item>
      </el-form>
      <el-button v-permission="'website_security:save'" type="primary" :loading="saving" :disabled="!loaded || loading || !editable('security.registration.v1')" @click="save">保存安全配置</el-button>
      <el-button v-permission="'website_security:view'" :disabled="saving || loading" @click="load">重新加载</el-button>
    </el-card>
  </div>
</template>
<style scoped>
.website-security { max-width: 1000px; }
h2 { margin-top: 0; }
p,.range { color: #606266; line-height: 1.7; }
.rate-card { margin-top: 20px; }
.limit-form { margin-top: 24px; display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 0 24px; }
.range { margin-left: 12px; font-size: 12px; }
@media(max-width:700px) { .limit-form { grid-template-columns: 1fr; } }
</style>
