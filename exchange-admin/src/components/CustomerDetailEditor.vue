<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import request from '@/utils/request'
import { can } from '@/utils/access'
import { useAccountTable } from '@/utils/useAccountTable'
import { startReadPolling } from '@/utils/readPolling'
import { balancesChanged, customerProfileFields, customerProfilePatch, totalCustomerBalance } from '@/utils/customerProfile'
import { getImageUrl } from '@/utils/imageUrl'
import ProtectedElementImage from '@/components/ProtectedElementImage.vue'
import UserAvatar from '../../../exchange-frontend/src/components/UserAvatar.vue'

const props = defineProps<{ modelValue: boolean; row: any; section?: string; readOnly?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: []; wallet: [row: any]; funds: [row: any]; subordinates: [row: any] }>()
const router = useRouter(), table = useAccountTable()
const loading = ref(false), saving = ref(false), error = ref(''), live = ref<any>(null), baseline = ref<any>(null)
const profile = ref<Record<string, any>>({}), identity = ref<Record<string, any>>({}), balances = ref<Record<string, string>>({})
const password = ref(''), reason = ref(''), section = ref('profile'), lastRefreshed = ref(''), uploading = ref('')
const pending = ref<any>(null)
const accounts = [{ coin: 'FUND', label: '资金账户', field: 'fundBalance' }, { coin: 'CONTRACT', label: '合约账户', field: 'contractBalance' }, { coin: 'OPTION', label: '期权账户', field: 'optionBalance' }]
const identityFields = ['realName', 'idNumber', 'idFrontImage', 'idBackImage', 'status', 'reviewRemark']
let stopPolling: (() => void) | undefined, generation = 0
const simulated = computed(() => props.row?.accountMode === 'DEMO')
const readOnly = computed(() => simulated.value || props.readOnly === true)
const permissions = computed(() => readOnly.value ? {} : live.value?.permissions || {})
const initialBalances = (data: any) => Object.fromEntries(accounts.map(a => [a.coin, String(data?.user?.[a.field] ?? '0')]))
const profilePatch = computed(() => customerProfilePatch(baseline.value?.user || {}, profile.value))
const identityChanged = computed(() => identityFields.some(key => (baseline.value?.kyc?.[key] ?? (key === 'status' ? 'NOT_VERIFIED' : '')) !== (identity.value[key] ?? '')))
const balanceChanged = computed(() => balancesChanged(initialBalances(baseline.value), balances.value))
const dirty = computed(() => !!Object.keys(profilePatch.value).length || identityChanged.value || balanceChanged.value || !!password.value)
const serverChanged = computed(() => !!baseline.value && !!live.value && (
  baseline.value.user.rowVersion !== live.value.user.rowVersion || baseline.value.kyc?.updatedAt !== live.value.kyc?.updatedAt ||
  balancesChanged(initialBalances(baseline.value), initialBalances(live.value))))
const canSave = computed(() => !readOnly.value && dirty.value && !serverChanged.value && !pending.value && !saving.value)
const previewImages = computed(() => ['idFrontImage', 'idBackImage'].map(key => identity.value[key]).filter(Boolean).map(url => getImageUrl(url, props.row?.accountMode)))
const amount = (value: unknown) => Number(value ?? 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 8 })
const frozen = (coin: string) => live.value?.accounts?.find((account: any) => account.coin === coin)?.frozen ?? '0'

function apply(data: any) {
  baseline.value = JSON.parse(JSON.stringify(data))
  profile.value = Object.fromEntries(customerProfileFields.map(key => [key, data.user[key] ?? '']))
  profile.value.parentUserId = data.user.parentUserId ?? undefined
  profile.value.annualIncome = data.user.annualIncome ?? undefined
  identity.value = Object.fromEntries(identityFields.map(key => [key, data.kyc?.[key] ?? (key === 'status' ? 'NOT_VERIFIED' : '')]))
  balances.value = initialBalances(data)
  password.value = ''; reason.value = ''; pending.value = null
}

async function readProfile() {
  if (!simulated.value) {
    const result: any = await request.get(`/admin/users/${props.row.id}/profile`)
    if (result?.success === false || !result?.data?.user) throw new Error(result?.message || '客户详情响应无效')
    return result.data
  }
  const user = await table.read('DEMO', `/admin/users/${props.row.id}`)
  return { user, totalBalance: totalCustomerBalance(user), accounts: [], kyc: null, permissions: {} }
}

async function refresh(reset = false) {
  const current = generation
  try {
    const data = await readProfile()
    if (current !== generation || !props.modelValue) return false
    const untouched = !dirty.value && !pending.value && !saving.value
    live.value = data; error.value = ''; lastRefreshed.value = new Date().toLocaleTimeString('zh-CN')
    if (reset || !baseline.value || untouched) apply(data)
    return true
  } catch (failure: any) {
    if (current === generation) error.value = failure.message || '客户详情加载失败'
    return false
  } finally { if (current === generation) loading.value = false }
}

watch(() => props.modelValue, open => {
  generation++; stopPolling?.(); stopPolling = undefined
  if (!open || !props.row) return
  live.value = null; baseline.value = null; error.value = ''; pending.value = null
  profile.value = {}; identity.value = {}; balances.value = {}; password.value = ''; reason.value = ''
  section.value = props.section || 'profile'; loading.value = true
  stopPolling = startReadPolling(() => refresh())
})
onBeforeUnmount(() => { generation++; stopPolling?.() })

async function discard() {
  if (saving.value || pending.value) return
  try {
    if (dirty.value) await ElMessageBox.confirm('放弃未保存的修改，重新加载客户资料？', '重新加载', { type: 'warning', confirmButtonText: '重新加载', cancelButtonText: '继续编辑' })
    loading.value = true; await refresh(true)
  } catch { /* Continue editing. */ }
}

async function close(done: () => void) {
  if (saving.value || pending.value) { ElMessage.warning('保存结果尚未确认，请先重试原请求'); return }
  try {
    if (dirty.value) await ElMessageBox.confirm('有未保存的修改，确定关闭？', '关闭客户详情', { confirmButtonText: '放弃修改', cancelButtonText: '继续编辑', type: 'warning' })
    done(); emit('update:modelValue', false)
  } catch { /* Continue editing. */ }
}

async function upload(event: Event, key: string) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!file.type.startsWith('image/') || file.size > 5 * 1024 * 1024) { ElMessage.error('请选择 5MB 以内的图片'); return }
  const current = generation
  uploading.value = key
  try {
    const form = new FormData(); form.append('file', file)
    const result: any = await request.post('/upload/image', form)
    if (current !== generation) return
    if (!result.url || result.success === false) throw new Error(result.message || '上传失败')
    if (key === 'avatarUrl') profile.value.avatarUrl = result.url
    else identity.value[key] = result.url
    ElMessage.success('图片已上传，保存后生效')
  } catch (failure: any) { if (current === generation) ElMessage.error(failure.message || '上传失败') }
  finally { if (current === generation) uploading.value = '' }
}

async function save() {
  if (saving.value || uploading.value || readOnly.value) return
  if (!pending.value) {
    if (!canSave.value) return
    if (password.value && password.value.length < 6) { section.value = 'profile'; ElMessage.warning('新密码至少 6 位'); return }
    if (balanceChanged.value && !Object.values(balances.value).every(value => /^\d+(\.\d{1,16})?$/.test(value))) {
      section.value = 'assets'; ElMessage.warning('请填写有效的非负余额'); return
    }
    const payload: any = { rowVersion: baseline.value.user.rowVersion, profile: profilePatch.value, idempotencyKey: crypto.randomUUID() }
    if (identityChanged.value) payload.kyc = { ...identity.value, id: baseline.value.kyc?.id, updatedAt: baseline.value.kyc?.updatedAt }
    if (password.value) payload.newPassword = password.value
    if (balanceChanged.value) Object.assign(payload, { balances: { ...balances.value }, expectedBalances: initialBalances(baseline.value), reason: reason.value.trim() })
    pending.value = payload
  }
  saving.value = true
  const current = generation
  try {
    const result: any = await request.post(`/admin/users/${props.row.id}/profile`, pending.value)
    if (current !== generation) return
    if (result?.success === false || !result?.data?.user) throw new Error(result?.message || '保存结果未确认，请重试原请求')
    live.value = result.data; apply(result.data); error.value = ''
    lastRefreshed.value = new Date().toLocaleTimeString('zh-CN')
    ElMessage.success('客户资料已保存'); emit('saved')
  } catch (failure: any) {
    if (current !== generation) return
    if ([400, 403, 404, 409].includes(failure.response?.status)) {
      pending.value = null
      if (failure.response?.status === 409) { await refresh(); error.value = '资料已变化，请重新加载后编辑，未保存内容仍保留。' }
    }
    ElMessage.error(failure.message || '保存结果未确认，请重试原请求')
  } finally { if (current === generation) saving.value = false }
}

function verify() {
  if (!identity.value.realName?.trim() || !identity.value.idNumber?.trim()) { ElMessage.warning('请先填写真实姓名和证件号码'); return }
  identity.value.status = 'APPROVED'
  void save()
}
</script>

<template>
  <el-drawer :model-value="modelValue" :title="`${section === 'identity' ? '实名信息' : '客户详情'} · UID ${row?.id || ''}`"
    size="min(1060px, 96vw)" :before-close="close" destroy-on-close class="customer-detail-drawer" @update:model-value="emit('update:modelValue', $event)">
    <div v-loading="loading" class="customer-detail">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <template v-if="live">
        <div class="customer-summary">
          <UserAvatar :src="live.user.avatarUrl" :name="live.user.nickname || live.user.email" :size="44" />
          <div class="customer-summary-name"><strong>{{ live.user.nickname || live.user.email }}</strong><span>{{ live.user.email }} · {{ live.user.phone || '未填写手机号' }}</span></div>
          <div class="customer-total"><span>总余额（USD）</span><strong>{{ amount(live.totalBalance) }}</strong></div>
        </div>
        <div class="customer-live"><span>自动更新 · {{ lastRefreshed || '加载中' }}</span><el-button link type="primary" :disabled="saving || !!pending" @click="discard">重新加载</el-button></div>
        <el-alert v-if="readOnly" title="当前账户筛选仅供查看，请切回真实账户后编辑" type="info" :closable="false" />
        <el-alert v-if="serverChanged && dirty && !pending" title="客户资料或余额已更新。当前编辑内容已保留，请重新加载后再保存。" type="warning" :closable="false" show-icon />
        <el-alert v-if="pending" title="保存结果待确认，请点击“重试原请求”。相同请求不会重复调整余额。" type="warning" :closable="false" show-icon />
        <el-tabs v-model="section" class="customer-tabs">
          <el-tab-pane label="基本资料" name="profile">
            <el-form label-position="top" :disabled="saving || !!pending" class="customer-form">
              <div class="customer-fields">
                <el-form-item label="登录邮箱"><el-input v-model="profile.email" :disabled="!permissions.edit" maxlength="128" /></el-form-item>
                <el-form-item label="昵称"><el-input v-model="profile.nickname" :disabled="!permissions.edit" maxlength="50" /></el-form-item>
                <el-form-item label="客户状态"><el-select v-model="profile.status" :disabled="!permissions.edit"><el-option label="正常" value="normal" /><el-option label="冻结" value="frozen" /><el-option label="禁用" value="banned" /></el-select></el-form-item>
                <el-form-item label="国家 / 区号"><el-input v-model="profile.countryCode" :disabled="!permissions.edit" maxlength="32" /></el-form-item>
                <el-form-item label="手机号码"><el-input v-model="profile.phone" :disabled="!permissions.edit" maxlength="32" /></el-form-item>
                <el-form-item label="用户类型"><el-select v-model="profile.userType" :disabled="!permissions.edit"><el-option label="普通客户" value="normal" /><el-option label="代理" value="agent" /></el-select></el-form-item>
                <el-form-item label="邀请码"><el-input v-model="profile.myInviteCode" :disabled="!permissions.edit" maxlength="32" /></el-form-item>
                <el-form-item label="上级用户 ID"><el-input-number v-model="profile.parentUserId" :disabled="!permissions.edit" :min="1" :precision="0" :controls="false" placeholder="无上级" /></el-form-item>
                <el-form-item label="上级用户邮箱"><el-input :model-value="live.user.parentUserEmail || '无上级'" readonly /></el-form-item>
                <el-form-item label="年收入"><el-input-number v-model="profile.annualIncome" :disabled="!permissions.edit" :min="0" :precision="2" :controls="false" /></el-form-item>
                <el-form-item label="收入币种"><el-input v-model="profile.annualIncomeCurrency" :disabled="!permissions.edit" maxlength="3" placeholder="如 USD" /></el-form-item>
                <el-form-item label="设置新登录密码"><el-input v-model="password" :disabled="!permissions.resetPassword" type="password" show-password autocomplete="new-password" placeholder="留空保持原密码" maxlength="128" /></el-form-item>
                <el-form-item label="头像" class="customer-wide"><div class="customer-avatar-edit"><UserAvatar :src="profile.avatarUrl" :name="profile.nickname || profile.email" :size="48" /><el-input v-model="profile.avatarUrl" :disabled="!permissions.edit" maxlength="300" placeholder="头像地址，或上传替换" /><label class="customer-upload" :class="{ disabled: !permissions.edit || !!uploading }"><input type="file" accept="image/*" :disabled="!permissions.edit || !!uploading" @change="upload($event, 'avatarUrl')" />{{ uploading === 'avatarUrl' ? '上传中…' : '上传头像' }}</label></div></el-form-item>
                <el-form-item label="客户备注" class="customer-full"><el-input v-model="profile.remark" :disabled="!permissions.edit" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
              </div>
              <details class="customer-more"><summary>登录与注册信息</summary><div class="customer-fields">
                <el-form-item label="最后登录 IP"><el-input v-model="profile.lastLoginIp" :disabled="!permissions.edit" maxlength="64" /></el-form-item>
                <el-form-item label="最后登录地区"><el-input v-model="profile.lastLoginRegion" :disabled="!permissions.edit" maxlength="128" /></el-form-item>
                <el-form-item label="最后登录域名"><el-input v-model="profile.lastLoginDomain" :disabled="!permissions.edit" maxlength="255" /></el-form-item>
                <el-form-item label="最后登录时间"><el-date-picker v-model="profile.lastLoginAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :disabled="!permissions.edit" /></el-form-item>
                <el-form-item label="注册使用的邀请码"><span>{{ live.user.inviteCode || '未填写' }}</span></el-form-item>
                <el-form-item label="注册时间"><span>{{ live.user.createdAt || '—' }}</span></el-form-item>
                <el-form-item label="资料更新时间"><span>{{ live.user.updatedAt || '—' }}</span></el-form-item>
                <el-form-item label="最近活动"><span>{{ live.user.lastActivityAt || '—' }}</span></el-form-item>
                <el-form-item label="最近页面 / 设备"><span>{{ live.user.lastPageCode || '—' }} / {{ live.user.lastDeviceType || '—' }}</span></el-form-item>
              </div></details>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="账户余额" name="assets">
            <div class="customer-account-heading"><strong>账户余额（USD）</strong><span>修改可用余额后保存，原因选填。</span></div>
            <div class="customer-accounts">
              <div v-for="account in accounts" :key="account.coin" class="customer-account-row">
                <strong>{{ account.label }}</strong><div><span>最新可用余额</span><b>{{ amount(live.user[account.field]) }}</b></div><div><span>冻结金额</span><b>{{ amount(frozen(account.coin)) }}</b></div>
                <el-form-item label="修改可用余额"><el-input v-model="balances[account.coin]" :disabled="!permissions.modifyBalance || saving || !!pending" inputmode="decimal" :aria-label="`${account.label}可用余额`" /></el-form-item>
              </div>
            </div>
            <p class="customer-help">冻结金额随订单占用和释放，自动同步。余额修改会记录为后台入金 / 后台出金。</p>
            <el-form-item label="修改原因（选填）" class="customer-reason"><el-input v-model="reason" :disabled="!permissions.modifyBalance || saving || !!pending" maxlength="500" placeholder="选填，便于核对资金记录" /></el-form-item>
            <div v-if="live.accounts?.some((account: any) => !accounts.some(a => a.coin === account.coin))" class="customer-extra-accounts">
              <strong>其他资产账户</strong><div v-for="account in live.accounts.filter((item: any) => !accounts.some(a => a.coin === item.coin))" :key="account.coin">{{ account.coin }}：可用 {{ amount(account.available) }} / 冻结 {{ amount(account.frozen) }}</div>
            </div>
            <el-button v-if="can('users:fund_details')" @click="emit('funds', row)">查看客户资金记录</el-button>
            <el-button v-if="can('funds_ledger:view')" @click="router.push({ path: '/funds-ledger', query: { userId: String(row.id) } })">完整资金明细</el-button>
          </el-tab-pane>
          <el-tab-pane label="实名信息" name="identity">
            <el-form label-position="top" :disabled="saving || !!pending" class="customer-form">
              <div class="customer-identity-actions"><strong>{{ identity.status === 'APPROVED' ? '已实名' : identity.status === 'PENDING' ? '待审核' : identity.status === 'REJECTED' ? '已拒绝' : '未实名' }}</strong><el-button type="success" :disabled="!permissions.editKyc || saving || !!pending || serverChanged" @click="verify">一键实名</el-button></div>
              <div class="customer-fields">
                <el-form-item label="真实姓名"><el-input v-model="identity.realName" :disabled="!permissions.editKyc" maxlength="100" /></el-form-item>
                <el-form-item label="证件号码"><el-input v-model="identity.idNumber" :disabled="!permissions.editKyc" maxlength="50" /></el-form-item>
                <el-form-item label="实名状态"><el-select v-model="identity.status" :disabled="!permissions.editKyc"><el-option label="未实名" value="NOT_VERIFIED" /><el-option label="待审核" value="PENDING" /><el-option label="已实名" value="APPROVED" /><el-option label="已拒绝" value="REJECTED" /></el-select></el-form-item>
                <el-form-item label="审核备注" class="customer-full"><el-input v-model="identity.reviewRemark" :disabled="!permissions.editKyc" maxlength="500" type="textarea" :rows="2" /></el-form-item>
              </div>
              <div class="customer-identity-images">
                <div v-for="(label, key) in { idFrontImage: '证件正面', idBackImage: '证件反面' }" :key="key" class="customer-identity-image">
                  <strong>{{ label }}</strong>
                  <ProtectedElementImage v-if="identity[key]" :src="getImageUrl(identity[key], row?.accountMode)" :preview-src-list="previewImages" :initial-index="key === 'idBackImage' && identity.idFrontImage ? 1 : 0" preview-teleported hide-on-click-modal fit="contain" class="customer-document-image" />
                  <div v-else class="customer-document-empty">未上传{{ label }}</div>
                  <el-input v-model="identity[key]" :disabled="!permissions.editKyc" maxlength="500" placeholder="图片地址，或上传替换" />
                  <label class="customer-upload" :class="{ disabled: !permissions.editKyc || !!uploading }"><input type="file" accept="image/*" :disabled="!permissions.editKyc || !!uploading" @change="upload($event, key)" />{{ uploading === key ? '上传中…' : `上传 / 替换${label}` }}</label>
                </div>
              </div>
              <p class="customer-help">点击证件图片可放大查看。修改姓名、证件及照片后，点击保存。</p>
            </el-form>
          </el-tab-pane>
          <el-tab-pane label="关联资料" name="related">
            <div class="customer-related"><div><strong>银行卡与数字货币地址</strong><span>查看、编辑客户绑定的收款信息。</span></div><el-button :disabled="!can('users:wallet_management')" @click="emit('wallet', row)">管理收款信息</el-button></div>
            <div class="customer-related"><div><strong>下级客户</strong><span>查看客户的邀请关系和下级资料。</span></div><el-button :disabled="!can('users:view_subordinates')" @click="emit('subordinates', row)">查看下级客户</el-button></div>
            <div class="customer-related"><div><strong>订单与资金记录</strong><span>合约、期权、充值、提现、贷款和理财记录。</span></div><el-button :disabled="!can('users:fund_details')" @click="emit('funds', row)">查看关联记录</el-button></div>
          </el-tab-pane>
        </el-tabs>
      </template>
      <el-empty v-else-if="!loading" description="暂时无法加载客户资料"><el-button @click="loading = true; refresh(true)">重新加载</el-button></el-empty>
    </div>
    <template #footer><div class="customer-footer"><span>{{ pending ? '保存结果待确认' : dirty ? '有未保存的修改' : '资料已同步' }}</span><el-button :disabled="saving || !!pending" @click="close(() => emit('update:modelValue', false))">关闭</el-button><el-button v-if="pending" type="warning" :loading="saving" @click="save">重试原请求</el-button><el-button v-else type="primary" :loading="saving" :disabled="!canSave || !!uploading" @click="save">保存修改</el-button></div></template>
  </el-drawer>
</template>

<style scoped>
.customer-detail { min-height: 220px; }.customer-detail :deep(.el-alert) { margin-bottom: 12px; }.customer-summary { display: flex; align-items: center; gap: 12px; }.customer-summary-name { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 5px; }.customer-summary-name span,.customer-total>span,.customer-live,.customer-help { color: #859096; font-size: 12px; }.customer-summary-name strong { font-size: 16px; }.customer-total { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; }.customer-total strong { font-size: 24px; font-variant-numeric: tabular-nums; color: #28392b; }.customer-live { display: flex; justify-content: space-between; align-items: center; margin: 12px 0 0; }.customer-tabs :deep(.el-tabs__header) { margin-bottom: 18px; }.customer-fields { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 0 16px; }.customer-form :deep(.el-form-item) { margin-bottom: 15px; }.customer-form :deep(.el-form-item__label) { line-height: 18px; padding: 0; margin-bottom: 6px; font-size: 12px; color: #667468; }.customer-fields :deep(.el-input-number),.customer-fields :deep(.el-date-editor),.customer-fields :deep(.el-select) { width: 100%; }.customer-wide { grid-column: span 3; }.customer-full { grid-column: 1 / -1; }.customer-avatar-edit { display: flex; align-items: center; gap: 10px; width: 100%; }.customer-avatar-edit :deep(.el-input) { flex: 1; }.customer-upload { display: inline-flex; align-items: center; justify-content: center; padding: 0 12px; height: 32px; border: 1px solid #d8ddd7; border-radius: 4px; color: #5f792f; font-size: 12px; cursor: pointer; white-space: nowrap; }.customer-upload input { position: absolute; width: 1px; height: 1px; opacity: 0; }.customer-upload:focus-within { outline: 2px solid var(--el-color-primary); }.customer-upload.disabled { opacity: .5; cursor: not-allowed; }.customer-more { border-top: 1px solid #e9eee7; padding-top: 12px; }.customer-more summary { cursor: pointer; color: #647247; font-size: 13px; padding-bottom: 16px; }.customer-account-heading { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; margin: 4px 0 16px; }.customer-account-heading>span { color: #859096; font-size: 12px; }.customer-account-row { display: grid; grid-template-columns: 1fr 1fr 1fr 1.2fr; align-items: center; gap: 18px; padding: 16px 10px; border-bottom: 1px solid #e9eee7; }.customer-account-row>div:not(.el-form-item) { display: flex; flex-direction: column; gap: 5px; }.customer-account-row span { color: #859096; font-size: 12px; }.customer-account-row b { font-weight: 500; font-variant-numeric: tabular-nums; }.customer-account-row :deep(.el-form-item) { margin: 0; }.customer-account-row :deep(.el-form-item__label) { font-size: 12px; }.customer-help { line-height: 1.8; margin: 12px 0 18px; }.customer-reason { margin-bottom: 22px; }.customer-extra-accounts { padding: 14px; background: #f7f9f5; font-size: 13px; line-height: 2; margin-bottom: 18px; }.customer-identity-actions { display: flex; align-items: center; justify-content: space-between; padding-bottom: 18px; }.customer-identity-images { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }.customer-identity-image { display: flex; flex-direction: column; align-items: flex-start; gap: 10px; }.customer-identity-image strong { font-size: 13px; }.customer-document-image,.customer-document-empty { width: 100%; height: 180px; border: 1px solid #e4e9e0; border-radius: 5px; background: #f7f9f5; }.customer-document-image { cursor: zoom-in; }.customer-document-empty { display: flex; align-items: center; justify-content: center; color: #9ba494; font-size: 13px; }.customer-related { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 18px 0; border-bottom: 1px solid #e9eee7; }.customer-related>div { display: flex; flex-direction: column; gap: 7px; }.customer-related strong { font-size: 14px; }.customer-related span { font-size: 12px; color: #859096; }.customer-footer { display: flex; align-items: center; gap: 10px; }.customer-footer>span { flex: 1; text-align: left; font-size: 12px; color: #859096; }.customer-footer :deep(.el-button+.el-button) { margin-left: 0; }
@media(max-width:700px) { .customer-fields { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 0 12px; }.customer-wide { grid-column: span 2; }.customer-account-row { grid-template-columns: 1fr 1fr; gap: 12px; }.customer-account-heading { flex-direction: column; }.customer-total strong { font-size: 18px; }.customer-summary-name span { overflow-wrap: anywhere; font-size: 11px; }.customer-identity-images { grid-template-columns: 1fr; }.customer-related { align-items: flex-start; }.customer-avatar-edit { flex-wrap: wrap; } }
@media(max-width:420px) { .customer-fields { grid-template-columns: 1fr; }.customer-wide { grid-column: span 1; }.customer-total>span { font-size: 10px; }.customer-summary { gap: 8px; }.customer-summary-name strong { font-size: 14px; } }
</style>
