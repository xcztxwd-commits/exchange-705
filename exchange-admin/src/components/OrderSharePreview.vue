<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import QRCode from 'qrcode'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { can } from '@/utils/access'
import { useAccountTable } from '@/utils/useAccountTable'
import { accountTableRequest } from '@/utils/accountTableRequest'
import type { AccountMode } from '@/utils/accountTableData'
import { displaySymbol } from '@/utils/displaySymbol'
import { drawSharePoster, orderTimestamp, recentShareChart, settledShareOrder, shareBackgrounds, shareCopy, shareNeedsChart, shareReturn,
  type ShareChart, type ShareKind, type ShareMode, type ShareOrder } from '@/utils/orderShare'
import { parseShareTemplateConfig, shareTemplateLanguages, type ShareTemplateRule } from '../../../exchange-frontend/src/utils/shareTemplateDesign'
import { createShareAssetLoader, fetchShareImage } from '../../../exchange-frontend/src/utils/shareTemplateAssets'
import { languageTimezones } from '../../../exchange-frontend/src/utils/displayTimezone'

const props = defineProps<{ orderId: number | string; kind: ShareKind; accountMode: AccountMode }>()
const emit = defineEmits<{ close: [] }>()
const auth = useAuthStore(), table = useAccountTable(), reader = accountTableRequest(table)
const language = ref('zh-TW'), templateId = ref(''), mode = ref<ShareMode>('both'), personal = ref(true), showQr = ref(false)
const rules = ref<ShareTemplateRule[]>([]), order = ref<ShareOrder>(), userId = ref<number>(), timezone = ref(languageTimezones[language.value] || 'UTC')
const brand = ref('EXCHANGE')
const timezoneApi = Intl as typeof Intl & { supportedValuesOf?: (key: string) => string[] }
const timezones = [...new Set(['UTC', ...Object.values(languageTimezones), ...(timezoneApi.supportedValuesOf?.('timeZone') || [])])]
const loading = ref(false), rendering = ref(false), error = ref(''), image = ref('')
const available = computed(() => rules.value.filter(rule => rule.enabled && (rule.languages.includes('*') || rule.languages.includes(language.value))))
const selected = computed(() => available.value.find(rule => rule.id === templateId.value))
const hasPersonal = computed(() => selected.value?.design?.boxes.some(box => box.visible && ['userName', 'userEmail'].includes(box.field)))
const canQr = computed(() => props.accountMode === 'REAL' && can('users:view'))
const assets = createShareAssetLoader(src => fetchShareImage(src, auth.token), () => auth.token || '')
const backgrounds = new Map<string, Promise<HTMLImageElement>>()
let revision = 0, renderRevision = 0, disposed = false, qr: HTMLImageElement | undefined, chart: ShareChart | undefined

function date(value: string) {
  const time = orderTimestamp(value)
  return Number.isFinite(time) ? new Intl.DateTimeFormat('sv-SE', { timeZone: timezone.value, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(new Date(time)) : ''
}
async function load() {
  const run = ++revision; renderRevision++; image.value = ''; error.value = ''; order.value = undefined; userId.value = undefined; rules.value = []; templateId.value = ''; qr = undefined; chart = undefined
  if (!canQr.value) showQr.value = false
  rendering.value = false; loading.value = true; table.selectRow({ accountMode: props.accountMode })
  try {
    if (!can('orders:view')) throw new Error('没有订单查看权限')
    const [raw, config]: any[] = await Promise.all([
      reader.get(`/admin/orders/${props.kind}/${props.orderId}/share-preview`),
      request.get('/admin/config/get', { params: { key: 'share.templates' } }),
    ])
    if (disposed || run !== revision) return
    if (String(raw.id) !== String(props.orderId) || raw.userId == null || raw.deleted) throw new Error('订单用户或状态已变更，请刷新订单列表')
    const templates = parseShareTemplateConfig(config.value).templates
    order.value = { ...settledShareOrder(raw, props.kind), userName: raw.userName || '', userEmail: raw.userEmail || '' }
    userId.value = raw.userId
    brand.value = typeof config.brand === 'string' && config.brand.trim() || (typeof raw.brand === 'string' && raw.brand.trim()) || 'EXCHANGE'
    rules.value = templates
    templateId.value = available.value[0]?.id || ''
    if (!canQr.value) showQr.value = false
    await render()
  } catch (e: any) { if (!disposed && run === revision) error.value = e.message || '分享图加载失败，请重试' }
  finally { if (!disposed && run === revision) loading.value = false }
}
async function background(rule: ShareTemplateRule) {
  const file = (!rule.design || rule.design.artwork) && shareBackgrounds[rule.base]
  if (!file) return undefined
  if (!backgrounds.has(file)) {
    const value = new Image(); value.src = `${import.meta.env.BASE_URL}share-templates/${file}`
    backgrounds.set(file, value.decode().then(() => value).catch(e => { backgrounds.delete(file); throw e }))
  }
  return backgrounds.get(file)
}
async function render() {
  const run = ++renderRevision, value = order.value && { ...order.value }, rule = selected.value
  image.value = ''; error.value = ''; rendering.value = true
  try {
    if (!can('orders:view')) throw new Error('没有订单查看权限')
    if (!value) return
    if (!rule) throw new Error('当前语言没有启用的分享模板')
    await document.fonts.ready
    if (disposed || run !== renderRevision) return
    let code = showQr.value ? qr : undefined
    if (showQr.value && !code) {
      if (!canQr.value || !userId.value) throw new Error('当前账户或权限不支持用户邀请二维码')
      const owner = userId.value, info: any = await request.get(`/admin/users/${owner}/invite-preview`)
      if (disposed || run !== renderRevision) return
      if (info.userId !== owner || !info.inviteCode || !info.inviteUrl) throw new Error('订单用户邀请信息无效')
      code = new Image(); code.src = await QRCode.toDataURL(info.inviteUrl, { width: 320, margin: 2, errorCorrectionLevel: 'M' }); await code.decode()
      if (disposed || run !== renderRevision) return
      qr = code
    }
    if (!rule.design && shareNeedsChart(rule.base) && !chart) {
      const result: any = await request.get(`/market/kline/${encodeURIComponent(value.symbol)}`, { params: { interval: '1m', limit: 80 } })
      if (disposed || run !== renderRevision) return
      if (result.ret !== 200 || result.data?.symbol !== value.symbol) throw new Error('行情暂不可用，请重新预览或选择其他模板')
      chart = recentShareChart(result.data.kline_list, result.data.source)
    }
    const [artwork, images] = await Promise.all([background(rule), assets.images(rule.design)])
    if (disposed || run !== renderRevision) return
    const canvas = document.createElement('canvas')
    drawSharePoster(canvas, { ...value, symbol: displaySymbol(value), openTime: date(value.openTime), closeTime: date(value.closeTime) }, {
      template: rule.base, design: rule.design, language: language.value, personal: personal.value, mode: mode.value, focus: rule.focus,
      quantity: false, capital: false, fee: false, leverage: true, orderId: false, openTime: true,
    }, shareCopy(language.value), brand.value, timezone.value, code, chart, artwork, images)
    image.value = canvas.toDataURL('image/png')
  } catch (e: any) { if (!disposed && run === renderRevision) error.value = e.message || '分享图生成失败，请重试' }
  finally { if (!disposed && run === renderRevision) rendering.value = false }
}
function save() {
  if (!image.value || loading.value || rendering.value || !can('orders:view')) return
  const link = document.createElement('a'); link.href = image.value
  link.download = `${props.accountMode}-${props.kind}-${props.orderId}-${language.value}-${templateId.value}.png`
  document.body.appendChild(link); link.click(); link.remove()
}
watch(language, () => { timezone.value = languageTimezones[language.value] || 'UTC'; templateId.value = available.value[0]?.id || '' })
watch([language, timezone, templateId, mode, personal, showQr], () => { if (order.value) void render() })
watch(() => [props.orderId, props.kind, props.accountMode, auth.token, can('orders:view'), can('users:view')], () => { void load() }, { flush: 'sync' })
onMounted(load)
onBeforeUnmount(() => { disposed = true; revision++; renderRevision++; image.value = ''; assets.dispose() })
</script>

<template>
  <el-dialog :model-value="true" title="查看分享图" width="min(960px, calc(100vw - 24px))" top="4vh" append-to-body class="order-share-preview-dialog" @update:model-value="!$event && emit('close')">
    <p class="owner-caption" v-if="order">{{ accountMode === 'DEMO' ? '模拟账户' : '真实账户' }} · 订单 {{ orderId }} · 所属用户 {{ userId }} · {{ order.userName || '未设置昵称' }} · {{ order.userEmail }}</p>
    <div class="share-preview-controls">
      <label>语言<el-select v-model="language" aria-label="分享图语言" :disabled="loading"><el-option v-for="[id, name] in shareTemplateLanguages" :key="id" :label="name" :value="id" /></el-select></label>
      <label>时区<el-select v-model="timezone" aria-label="分享图时区" filterable :disabled="loading"><el-option v-for="zone in timezones" :key="zone" :label="zone" :value="zone" /></el-select></label>
      <label>模板<el-select v-model="templateId" aria-label="分享图模板" :disabled="loading"><el-option v-for="rule in available" :key="rule.id" :label="rule.name" :value="rule.id" /></el-select></label>
      <label>预览内容<el-select v-model="mode" aria-label="分享图预览内容" :disabled="loading"><el-option label="盈亏 + 收益率" value="both" /><el-option label="盈亏金额" value="amount" /><el-option label="收益率" value="rate" :disabled="!order || shareReturn(order) === null" /></el-select></label>
    </div>
    <div class="share-preview-options"><el-checkbox v-permission="'orders:view'" v-if="hasPersonal" v-model="personal" :disabled="loading">显示该用户名字和邮箱</el-checkbox><el-checkbox v-permission="'orders:view'" v-model="showQr" :disabled="loading || !canQr" :title="canQr ? '使用订单所属用户的邀请码' : '仅真实账户且有用户查看权限时可用'">该用户邀请二维码</el-checkbox></div>
    <p class="preview-note">使用本行订单所属用户及已记录的结算盈亏，不使用后台登录账户；仅预览，不创建订单或修改资金。点击图片可放大。</p>
    <div class="order-share-stage" v-loading="loading || rendering">
      <el-image v-if="image" :src="image" :preview-src-list="[image]" preview-teleported fit="contain" :alt="`${order?.symbol} · ${selected?.name} · ${language}分享图`" />
      <p v-else-if="error" role="alert">{{ error }}</p>
    </div>
    <template #footer><el-button v-permission="'session:close'" @click="emit('close')">关闭</el-button><el-button v-permission="'orders:view'" :disabled="loading || rendering" @click="order ? render() : load()">重新预览</el-button><el-button v-permission="'orders:view'" type="primary" :disabled="!image || loading || rendering" @click="save">保存图片</el-button></template>
  </el-dialog>
</template>

<style scoped>
.owner-caption, .preview-note { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.share-preview-controls { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.share-preview-controls label { display: grid; gap: 6px; font-size: 13px; min-width: 0; }
.share-preview-options { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 8px; }
.order-share-stage { display: flex; justify-content: center; align-items: center; min-height: 180px; padding: 12px; border-radius: 8px; background: #edf0f5; }
.order-share-stage .el-image { max-width: 100%; height: min(52vh, 540px); cursor: zoom-in; }
.order-share-stage p { color: var(--el-color-danger); }
@media (max-width: 800px) { .share-preview-controls { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) { .share-preview-controls { grid-template-columns: 1fr; gap: 8px; }.order-share-stage .el-image { height: 36vh; } }
</style>
