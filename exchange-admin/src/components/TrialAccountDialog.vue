<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import request from '@/utils/request'

const props = defineProps<{ modelValue: boolean; userId: number; userEmail: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>()
const auth = useAuthStore()
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const account = ref<any>(null), available = ref(''), enabled = ref(true)
const loading = ref(false), saving = ref(false), error = ref(''), pending = ref<any>(null)
const owner = () => `${auth.user?.tenantId}:${auth.accessSession?.id || auth.loginSessionId || auth.user?.userType}:${auth.user?.id}:${props.userId}:${auth.token}`
const storageKey = () => `trial-account-pending:${auth.user?.tenantId}:${auth.accessSession?.id || auth.loginSessionId || auth.user?.userType}:${auth.user?.id}:${props.userId}`
let generation = 0

function accept(data: any) {
  if (!data || data.success === false || typeof data.available !== 'string' || !Number.isSafeInteger(data.rowVersion) || typeof data.enabled !== 'boolean') throw new Error('体验金账户数据无效，请刷新重试')
  account.value = data
  if (!pending.value) { available.value = data.available; enabled.value = data.enabled }
}
async function load() {
  if (!open.value || !props.userId || saving.value) return
  const identity = owner(), run = ++generation
  loading.value = true; error.value = ''
  try {
    const data = await request.get(`/admin/users/${props.userId}/trial-account`)
    if (run === generation && identity === owner() && open.value) accept(data)
  } catch (e: any) { if (run === generation && identity === owner()) error.value = e?.message || '加载体验金账户失败' }
  finally { if (run === generation && identity === owner()) loading.value = false }
}
watch([() => props.modelValue, () => props.userId, () => auth.token], () => {
  generation++; account.value = null; pending.value = null; error.value = ''; available.value = ''; loading.value = false; saving.value = false
  if (!open.value || !props.userId) return
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey()) || 'null')
    if (saved && /^trial-[A-Za-z0-9_-]{16,58}$/.test(saved.requestId) && typeof saved.available === 'string' && typeof saved.enabled === 'boolean') {
      pending.value = saved; available.value = saved.available; enabled.value = saved.enabled
    }
  } catch { sessionStorage.removeItem(storageKey()) }
  void load()
}, { immediate: true })

async function save() {
  if (saving.value || loading.value || !account.value) return
  const identity = owner(), key = storageKey(), user = props.userId
  saving.value = true; error.value = ''
  try {
    let payload = pending.value
    if (!payload) {
      if (!/^(?:0|[1-9]\d{0,15})(?:\.\d{1,16})?$/.test(available.value.trim())) throw new Error('请输入非负余额，最多16位整数及16位小数')
      await ElMessageBox.confirm(`UID ${user} · ${props.userEmail}：将体验金${enabled.value ? '开启' : '关闭'}，可用余额设为 ${available.value.trim()} USD。订单占用金额保持不变。`, '确认修改体验金账户', { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '返回修改' })
      if (identity !== owner() || !open.value) return
      payload = { enabled: enabled.value, available: available.value.trim(), expectedVersion: account.value.rowVersion, requestId: `trial-${crypto.randomUUID()}`, reason: '', confirm: true }
      sessionStorage.setItem(key, JSON.stringify(payload)); pending.value = payload
    }
    const result = await request.put(`/admin/users/${user}/trial-account`, payload)
    if (identity !== owner()) return
    sessionStorage.removeItem(key); pending.value = null; accept(result)
    ElMessage.success('体验金账户已保存'); emit('saved')
  } catch (e: any) {
    if (identity !== owner() || e === 'cancel' || e === 'close') return
    if ([400, 403, 404, 409, 422].includes(e?.response?.status)) { sessionStorage.removeItem(key); pending.value = null }
    error.value = (pending.value ? '结果待确认，请点击“重试确认”核对原请求；' : '') + (e?.message || '保存失败')
  } finally { if (identity === owner()) saving.value = false }
}
function date(value: string | null) {
  if (!value) return '未设置'
  const parsed = new Date(value + (/(Z|[+-]\d{2}:\d{2})$/.test(value) ? '' : 'Z'))
  return Number.isFinite(parsed.getTime()) ? `${parsed.toLocaleString('zh-CN', { timeZone: 'UTC', hour12: false })} UTC` : '—'
}
</script>

<template>
  <el-dialog v-model="open" title="体验金账户" width="min(680px, 95vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving" class="trial-account-dialog">
    <p class="trial-account-owner">{{ userEmail }} <span>UID {{ userId }}</span></p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="pending" title="原请求结果待确认，表单已锁定。重试会使用同一请求编号，不会重复调整余额。" type="warning" :closable="false" show-icon />
    <div v-loading="loading" class="trial-account-content">
      <template v-if="account">
        <el-form label-position="top" :disabled="saving || loading || !!pending">
          <el-form-item label="体验金交易开关">
            <el-switch v-model="enabled" active-text="已开启" inactive-text="已关闭" aria-label="体验金交易开关" />
            <p class="trial-account-help">关闭后停止新的体验金交易，余额保留，已有持仓仍可正常结算和退出。</p>
          </el-form-item>
          <el-form-item label="可用体验金（USD）">
            <el-input v-model="available" inputmode="decimal" maxlength="33" aria-label="可用体验金余额" placeholder="请输入非负余额" />
            <p class="trial-account-help">仅调整可用余额。新增部分有效期为1天，已有批次保留各自到期时间。</p>
          </el-form-item>
        </el-form>
        <div class="trial-account-summary">
          <div><span>订单占用</span><strong>{{ account.frozen }} USD</strong></div>
          <div><span>最早到期</span><strong>{{ date(account.expiresAt) }}</strong></div>
        </div>
        <el-table :data="account.grants || []" size="small" empty-text="暂无体验金批次" max-height="220">
          <el-table-column prop="id" label="批次" width="70" />
          <el-table-column prop="available" label="可用（USD）" min-width="130" />
          <el-table-column prop="frozen" label="占用（USD）" min-width="110" />
          <el-table-column label="到期时间" min-width="190"><template #default="{ row }">{{ date(row.expiresAt) }}</template></el-table-column>
        </el-table>
      </template>
    </div>
    <template #footer>
      <el-button :disabled="saving" @click="open = false">关闭</el-button>
      <el-button :disabled="saving || !!pending" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading || !account" @click="save">{{ pending ? '重试确认' : '保存' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.trial-account-owner { margin: 0 0 16px; color: #25313b; overflow-wrap: anywhere; }
.trial-account-owner span { margin-inline-start: 8px; color: #87939b; font-size: 12px; }
.trial-account-content { min-height: 150px; padding-top: 16px; }
.trial-account-help { width: 100%; margin: 6px 0 0; color: #7b8494; font-size: 12px; line-height: 1.6; }
.trial-account-summary { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; padding: 12px; margin-bottom: 16px; background: #f6f8f3; border-radius: 8px; }
.trial-account-summary span { display: block; color: #7b8494; font-size: 12px; }
.trial-account-summary strong { display: block; margin-top: 4px; color: #25313b; font-size: 13px; font-weight: 600; overflow-wrap: anywhere; }
@media (max-width: 480px) { .trial-account-summary { grid-template-columns: 1fr; } }
</style>
