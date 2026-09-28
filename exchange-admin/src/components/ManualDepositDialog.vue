<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import CurrencyPicker from '@/components/CurrencyPicker.vue'
import { useFiatCurrency } from '@/utils/fiatCurrency'
import { previewUsd } from '@/utils/depositOrder'
import { useAuthStore } from '@/store/auth'
const props = defineProps<{ modelValue: boolean; userId?: number }>()
const emit = defineEmits(['update:modelValue', 'success'])
const open = computed({ get: () => props.modelValue, set: v => emit('update:modelValue', v) })
const auth = useAuthStore()
const storageKey = computed(() => `deposit-pending:${auth.user?.userType || 'admin'}:${auth.user?.id}`)
const { currency, rate } = useFiatCurrency()
const form = reactive({ userId: '', account: 'FUND', amount: '', type: 'manual', manualPurpose: 'ADJUSTMENT', remark: '', address: '', network: 'MANUAL', proofImage: '' })
const recipient = ref<any>(null), pending = ref<any>(null), busy = ref(false), querying = ref(false)
const key = ref('')
const preview = computed(() => rate.value === null ? null : previewUsd(form.amount, String(rate.value)))
watch(() => props.modelValue, async value => {
  if (!value) return
  const saved = sessionStorage.getItem(storageKey.value)
  pending.value = saved ? JSON.parse(saved) : null
  if (pending.value) { Object.assign(form, pending.value); currency.value = pending.value.currency; key.value = pending.value.idempotencyKey }
  else { Object.assign(form, { userId: props.userId ? String(props.userId) : '', account: 'FUND', amount: '', type: 'manual', manualPurpose: 'ADJUSTMENT', remark: '', address: '', network: 'MANUAL', proofImage: '' }); currency.value = 'USD'; key.value = crypto.randomUUID() }
  recipient.value = null
  if (form.userId) await lookup()
})
watch(() => form.userId, () => { recipient.value = null })
async function lookup() {
  if (!/^\d+$/.test(form.userId)) return
  querying.value = true
  try { recipient.value = await request.get(`/admin/deposit/orders/recipient/${form.userId}`) }
  catch (e: any) { recipient.value = null; ElMessage.error(e.message) }
  finally { querying.value = false }
}
async function upload(options: any) {
  if (!options.file.type.startsWith('image/') || options.file.size > 5 * 1024 * 1024) { ElMessage.error('仅支持5MB以内图片'); return }
  const data = new FormData(); data.append('file', options.file)
  try { const result: any = await request.post('/upload/image', data); form.proofImage = result.url || result.data?.url || '' }
  catch (e: any) { ElMessage.error(e.message) }
}
async function submit() {
  if (busy.value) return
  busy.value = true
  try {
    let payload = pending.value
    if (!payload) {
      if (!recipient.value || String(recipient.value.userId) !== String(form.userId) || !preview.value || !form.remark.trim()) { ElMessage.error('请查询客户并填写有效金额、备注'); return }
      if (form.manualPurpose === 'RECEIPT' && (form.type === 'manual' || !form.proofImage)) { ElMessage.error('实收补录需要渠道和凭证'); return }
      await ElMessageBox.confirm(`UID ${form.userId}；${form.account}；${form.amount} ${currency.value}；费率0%；预计 ${preview.value} USD（以服务端提交时汇率为准）；原因：${form.remark}`, '确认手动入账', { type: 'warning' })
      payload = { ...form, currency: currency.value, idempotencyKey: key.value }
      // Persist BEFORE sending: timeout, dialog close and navigation all retry the same immutable request.
      sessionStorage.setItem(storageKey.value, JSON.stringify(payload)); pending.value = payload
    }
    const result: any = await request.post('/admin/deposit/orders/manual', payload)
    sessionStorage.removeItem(storageKey.value); pending.value = null
    ElMessage.success(`已入账 ${result.amount} USD；订单 ${result.orderNo}`); open.value = false; emit('success')
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return
    const status = e.response?.status
    if ([400, 403, 404, 503].includes(status)) { sessionStorage.removeItem(storageKey.value); pending.value = null; ElMessage.error(e.message) }
    else ElMessage.warning('结果待确认；请保留原请求重试，不要另建一笔。' + (e.message || ''))
  } finally { busy.value = false }
}
</script>
<template>
  <el-dialog v-model="open" title="新增手动充值" width="640px" :close-on-click-modal="false">
    <el-alert v-if="pending" title="原请求结果待确认：表单已锁定，重试不会重复入账。关闭重开仍保留原请求。" type="warning" :closable="false" />
    <el-form label-width="110px" :disabled="busy || !!pending">
      <el-form-item label="客户 UID"><el-input v-model="form.userId" /><el-button :loading="querying" @click="lookup">查询客户</el-button></el-form-item>
      <el-alert v-if="recipient" :title="`${recipient.name}；用户备注：${recipient.remark || '—'}；余额 USD：${JSON.stringify(recipient.balances)}`" :closable="false" />
      <el-form-item label="入账账户"><el-select v-model="form.account"><el-option v-for="v in ['FUND','CONTRACT','OPTION']" :key="v" :value="v" :label="v" /></el-select></el-form-item>
      <el-form-item label="计价货币"><CurrencyPicker v-model="currency" /></el-form-item>
      <el-form-item label="原币数量"><el-input v-model="form.amount" inputmode="decimal" /></el-form-item>
      <el-alert :title="`费率0%；预计到账 ${preview || '汇率或数量无效'} USD；以服务端提交时汇率为准`" type="info" :closable="false" />
      <el-form-item label="用途"><el-select v-model="form.manualPurpose"><el-option label="补款" value="ADJUSTMENT" /><el-option label="赠送" value="BONUS" /><el-option label="实收补录" value="RECEIPT" /></el-select></el-form-item>
      <el-form-item label="充值类型"><el-select v-model="form.type"><el-option label="后台加款" value="manual" /><el-option label="银行卡" value="bank" /><el-option label="数字货币渠道" value="digital" /></el-select></el-form-item>
      <el-form-item v-if="form.type !== 'manual'" :label="form.type === 'bank' ? '收款信息' : '钱包地址'"><el-input v-model="form.address" maxlength="200" /></el-form-item>
      <el-form-item v-if="form.type === 'digital'" label="地址网络"><el-input v-model="form.network" maxlength="50" /></el-form-item>
      <el-form-item label="订单备注" required><el-input v-model="form.remark" type="textarea" maxlength="500" show-word-limit /></el-form-item>
      <el-form-item label="充值凭证"><el-upload :http-request="upload" :show-file-list="false" accept="image/*"><el-button>上传凭证</el-button></el-upload><el-image v-if="form.proofImage" :src="form.proofImage" :preview-src-list="[form.proofImage]" style="width:60px" /></el-form-item>
    </el-form>
    <template #footer><el-button @click="open = false">关闭</el-button><el-button type="primary" :loading="busy" @click="submit">{{ pending ? '重试原请求' : '确认充值' }}</el-button></template>
  </el-dialog>
</template>
