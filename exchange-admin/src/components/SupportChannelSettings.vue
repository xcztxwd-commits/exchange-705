<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { can } from '@/utils/access'
import { useTenantPolicies } from '@/composables/useTenantPolicies'
import { saveSupportSettings, type SupportChannelConfig } from '@/utils/supportSettings'
import TenantPolicyNotice from './TenantPolicyNotice.vue'

const { snapshot, policyError, reloadPolicies, editable, policyLabel } = useTenantPolicies('support')
const auth = useAuthStore(), settings = ref<SupportChannelConfig | null>(null)
const loading = ref(false), busy = ref(false), error = ref('')
const canEdit = computed(() => can('support_settings:save') && editable('support.settings') && !!settings.value && !busy.value)
const channelEditable = computed(() => canEdit.value && editable('support.channel'))
let revision = 0
async function load() {
  const run = ++revision
  settings.value = null; error.value = ''; busy.value = false; loading.value = false
  if (!can('support_settings:view')) return
  loading.value = true
  try {
    await reloadPolicies()
    const value = await request.get('/admin/support/settings') as unknown as SupportChannelConfig
    if (run === revision) settings.value = { mode: value.mode, inboxEnabled: value.inboxEnabled, capacity: value.capacity }
  } catch (e: any) {
    if (run === revision) error.value = e.message || '服务渠道加载失败，请重试'
  } finally {
    if (run === revision) loading.value = false
  }
}
async function save() {
  if (!canEdit.value || !settings.value) return
  if (!Number.isInteger(settings.value.capacity) || settings.value.capacity < 1 || settings.value.capacity > 50) {
    ElMessage.warning('每位客服同时接待上限请输入 1–50 的整数'); return
  }
  const run = revision, changes = { ...settings.value }
  busy.value = true
  try {
    await saveSupportSettings(changes)
    if (run === revision) ElMessage.success('服务渠道已保存，客户端将在下次刷新时生效')
  } catch (e: any) {
    if (run === revision) ElMessage.error(e.message || '服务渠道保存失败')
  } finally {
    if (run === revision) busy.value = false
  }
}
watch(() => auth.token, () => { void load() }, { immediate: true })
onBeforeUnmount(() => { revision++ })
</script>

<template>
  <section v-loading="loading" class="support-channel-settings" aria-labelledby="support-channel-title">
    <div class="channel-heading">
      <h2 id="support-channel-title">01 / 服务渠道</h2>
      <el-button v-permission="'support_settings:save'" type="primary" :disabled="!canEdit" :loading="busy" @click="save">保存服务渠道</el-button>
    </div>
    <TenantPolicyNotice :snapshot="snapshot" :error="policyError" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button v-permission="'support_settings:view'" v-if="error" @click="load">重新加载服务渠道</el-button>
    <el-form v-if="settings" label-position="top" :disabled="!canEdit" @submit.prevent="save">
      <el-form-item :label="'客服模式' + policyLabel('support.channel')">
        <el-radio-group v-model="settings.mode" :disabled="!channelEditable" aria-label="客服模式">
          <el-radio-button value="off" label="off">关闭客服</el-radio-button>
          <el-radio-button value="external" label="external" :disabled="!channelEditable || snapshot?.features.external_support !== true">外部客服</el-radio-button>
          <el-radio-button value="internal" label="internal" :disabled="!channelEditable || snapshot?.features.support !== true">站内客服</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <p class="hint">外部地址沿用「系统配置 → 客服配置 → 客服链接」。切换渠道不会删除历史会话；关闭站内客服后禁止创建和回复。</p>
      <el-form-item label="站内信">
        <el-switch v-permission="'support_settings:save'" v-model="settings.inboxEnabled" :disabled="!canEdit || snapshot?.features.inbox !== true" aria-label="开放站内信入口及发送" active-text="开放站内信入口及发送" inactive-text="关闭" />
      </el-form-item>
      <el-form-item label="每位客服同时接待上限">
        <el-input-number v-model="settings.capacity" :min="1" :max="50" :precision="0" aria-label="每位客服同时接待上限" />
      </el-form-item>
    </el-form>
    <p class="hint save-hint">本模块独立保存，仅保存客服模式、站内信开关与接待上限，不会保存其他未提交的配置。</p>
  </section>
</template>

<style scoped>
.support-channel-settings{background:#fff;border:1px solid #e8edf3;border-radius:14px;padding:25px;margin-bottom:18px;color:#29374b}
.channel-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap;margin-bottom:23px}
.channel-heading h2{margin:0;font-size:14px;font-weight:600;color:#657c98}
.hint{font-size:12px;color:#8e9aac;line-height:1.9;margin:0 0 20px}.save-hint{margin-bottom:0}
.support-channel-settings :deep(.el-form-item__label){font-size:12px}
@media(max-width:700px){.support-channel-settings{padding:18px}}
</style>
