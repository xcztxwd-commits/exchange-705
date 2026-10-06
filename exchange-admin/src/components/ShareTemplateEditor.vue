<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { ElMessage, ElMessageBox, type AutocompleteFetchSuggestionsCallback } from 'element-plus'
import QRCode from 'qrcode'
import request from '@/utils/request'
import { can } from '@/utils/access'
import { displaySymbol } from '@/utils/displaySymbol'
import { useAuthStore } from '@/store/auth'
import ProtectedElementImage from './ProtectedElementImage.vue'
import { createShareAssetLoader, fetchShareImage, prepareShareImage } from '../../../exchange-frontend/src/utils/shareTemplateAssets'
import { drawSharePoster, shareBackgrounds, shareCopy, shareReturn, type ShareFocus } from '@/utils/orderShare'
import { applyShareFocus, createShareDesign, previewShareOrder, requiredShareFields, shareFields, shareTemplateCatalog, shareTemplateLanguages,
  validateShareDesign, createShareShape, shareDesignLayers, shareShapes, shareShapeSvg, validateShareDecoration,
  type ShareBox, type ShareDecoration, type ShareField, type ShareLayer, type ShareTemplateRule } from '../../../exchange-frontend/src/utils/shareTemplateDesign'

const props = defineProps<{ rule: ShareTemplateRule; editable: boolean; language: string; materialsEditable?: boolean }>()
const emit = defineEmits<{ apply: [rule: ShareTemplateRule]; close: [] }>()
const draft = reactive<ShareTemplateRule>(JSON.parse(JSON.stringify(props.rule)))
draft.design ||= createShareDesign(draft.base, draft.focus)
const design = computed(() => draft.design!)
const language = ref(props.language), universal = ref(draft.languages.includes('*'))
const tab = ref('template'), selectedKey = ref('box:rate')
const layers = computed(() => shareDesignLayers(design.value)), selectedLayer = computed(() => layers.value.find(layer => layer.key === selectedKey.value))
const selected = computed(() => selectedLayer.value?.item)
const selectedBox = computed(() => selectedLayer.value?.kind === 'field' ? selectedLayer.value.item : undefined)
const selectedDecoration = computed(() => selectedLayer.value?.kind === 'decoration' ? selectedLayer.value.item : undefined)
const selectedField = computed({ get: () => selectedBox.value?.field, set: (field: ShareField | undefined) => { if (field) selectedKey.value = `box:${field}` } })
const layerName = (layer: ShareLayer) => layer.kind === 'field' ? shareFields[layer.item.field] : layer.item.name
const auth = useAuthStore()
const assets = createShareAssetLoader(src => fetchShareImage(src, auth.token), () => auth.token || '')
type Material = { id: string; name: string; layer: ShareDecoration }
const materials = ref<Material[]>([]), materialQuery = ref(''), materialPage = ref(1), materialsLoading = ref(false), materialBusy = ref(false), materialError = ref('')
const uploadInput = ref<HTMLInputElement>(), canSaveMaterial = computed(() => props.editable && props.materialsEditable !== false && !materialBusy.value)
const matchedMaterials = computed(() => materials.value.filter(item => item.name.toLowerCase().includes(materialQuery.value.toLowerCase())))
const pageMaterials = computed(() => matchedMaterials.value.slice((materialPage.value - 1) * 12, materialPage.value * 12))
const shapeIcons = Object.fromEntries(Object.keys(shareShapes).map(type => [type, shareShapeSvg(createShareShape(type as keyof typeof shareShapes))]))
const sample = reactive({ symbol: 'BTCUSDT', buy: true, leverage: 10, openPrice: 60000, closePrice: 63000,
  openTime: '2026-09-30T09:00', closeTime: '2026-09-30T10:00', userName: '示例用户', userEmail: 'preview@example.com' })
const brand = ref('EXCHANGE'), timezone = ref('UTC')
const order = computed(() => { try { return previewShareOrder(sample) } catch { return null } })
const rate = computed(() => order.value ? shareReturn(order.value) : null)
const image = ref(''), rendering = ref(false), error = ref(''), stage = ref<HTMLElement>(), propertiesPanel = ref<HTMLElement>(), scale = ref(1)
const users = ref<any[]>([]), userId = ref<number>(), searching = ref(false)
const qrImage = shallowRef<HTMLImageElement>(), inviteUrl = ref(''), inviteLoading = ref(false), inviteError = ref('')
let revision = 0, symbolRevision = 0, userRevision = 0, inviteRevision = 0, observer: ResizeObserver | undefined, disposed = false
const backgrounds = new Map<string, Promise<HTMLImageElement>>()
async function background() {
  const file = design.value.artwork && shareBackgrounds[draft.base]
  if (!file) return undefined
  if (!backgrounds.has(file)) {
    const image = new Image(); image.src = `${import.meta.env.BASE_URL}share-templates/${file}`
    backgrounds.set(file, image.decode().then(() => image).catch(e => { backgrounds.delete(file); throw e }))
  }
  return backgrounds.get(file)
}
async function render() {
  const run = ++revision; rendering.value = true; error.value = ''
  if (inviteLoading.value || inviteError.value) { image.value = ''; rendering.value = inviteLoading.value; return }
  try {
    const value = previewShareOrder(sample), layout = JSON.parse(JSON.stringify(design.value))
    await document.fonts.ready
    const [artwork, images] = await Promise.all([background(), assets.images(layout)])
    if (disposed || run !== revision) return
    const canvas = document.createElement('canvas')
    drawSharePoster(canvas, value, { template: draft.base, design: layout, language: language.value, personal: true,
      mode: 'both', focus: draft.focus, quantity: false, capital: false, fee: false, leverage: true, orderId: false, openTime: true },
    shareCopy(language.value), brand.value, timezone.value, qrImage.value, undefined, artwork, images)
    image.value = canvas.toDataURL('image/png')
  } catch (e: any) { if (run === revision) { error.value = e.message || '图片生成失败，请重试'; image.value = '' } }
  finally { if (run === revision) rendering.value = false }
}
function searchSymbols(keyword: string, callback: AutocompleteFetchSuggestionsCallback) {
  if (disposed) return
  const run = ++symbolRevision, query = keyword.trim()
  if (!query) { callback([]); return }
  request.get('/market/search', { params: { keyword: query } }).then((result: any) => {
    if (!disposed && run === symbolRevision) callback(query === sample.symbol.trim() ? (result.list || []).map((symbol: any) => ({
      value: symbol.symbol, label: [displaySymbol(symbol), symbol.name || symbol.nameEn].filter(Boolean).join(' · '),
    })) : [])
  }).catch((e: any) => {
    if (!disposed && run === symbolRevision) {
      callback([])
      if (query === sample.symbol.trim()) ElMessage.error(e.message || '品种查询失败，请重试')
    }
  })
}
async function searchUsers(keyword: string) {
  if (!can('users:view')) return
  const run = ++userRevision, query = keyword.trim(); users.value = []
  if (!query) { searching.value = false; return }
  searching.value = true
  try {
    const filter = /^\d+$/.test(query) ? { userId: query } : { keyword: query }
    const result: any = await request.get('/admin/users', { params: { page: 1, size: 20, ...filter } })
    if (!disposed && run === userRevision) users.value = result.list || []
  } catch (e: any) { if (run === userRevision) ElMessage.error(e.message || '用户查询失败') }
  finally { if (run === userRevision) searching.value = false }
}
async function chooseUser(id?: number) {
  const run = ++inviteRevision
  revision++; image.value = ''; qrImage.value = undefined; inviteUrl.value = ''; inviteError.value = ''; inviteLoading.value = !!id
  const user = users.value.find(user => user.id === id)
  sample.userName = user?.nickname || ''; sample.userEmail = user?.email || ''
  if (!id) { await render(); return }
  try {
    if (!can('users:view')) throw new Error('没有用户查询权限')
    const info = await request.get(`/admin/users/${id}/invite-preview`) as unknown as { userId: number; nickname?: string; email?: string; inviteCode: string; inviteUrl: string }
    if (disposed || run !== inviteRevision) return
    if (info.userId !== id || !info.inviteCode || !info.inviteUrl) throw new Error('用户邀请信息无效，请重新选择用户')
    const src = await QRCode.toDataURL(info.inviteUrl, { width: 320, margin: 2, errorCorrectionLevel: 'M' })
    const qr = new Image(); qr.src = src; await qr.decode()
    if (disposed || run !== inviteRevision) return
    sample.userName = info.nickname || ''; sample.userEmail = info.email || ''
    inviteUrl.value = info.inviteUrl; qrImage.value = qr
  } catch (e: any) {
    if (!disposed && run === inviteRevision) inviteError.value = e.message || '用户邀请二维码加载失败，请重试'
  } finally {
    if (!disposed && run === inviteRevision) { inviteLoading.value = false; await render() }
  }
}
function changeFocus(value: ShareFocus) {
  if (!props.editable || draft.focus === value) return
  applyShareFocus(design.value); draft.focus = value
}
function clamp(box: ShareBox | ShareDecoration) {
  const min = 'field' in box ? 20 : 2
  box.width = Math.min(design.value.width, Math.max(min, Number(box.width) || min)); box.height = Math.min(design.value.height, Math.max(min, Number(box.height) || min))
  box.x = Math.min(design.value.width - box.width, Math.max(0, Number(box.x) || 0)); box.y = Math.min(design.value.height - box.height, Math.max(0, Number(box.y) || 0))
  if ('field' in box) box.fontSize = Math.min(200, Math.max(8, Number(box.fontSize) || 8))
}
function resizeCanvas(value: string) {
  const [width, height] = value.split('x').map(Number)
  const ratioX = width! / design.value.width, ratioY = height! / design.value.height
  for (const { item: box } of layers.value) { box.x = Math.round(box.x * ratioX); box.y = Math.round(box.y * ratioY); box.width = Math.round(box.width * ratioX); box.height = Math.round(box.height * ratioY); if ('field' in box) box.fontSize = Math.round(box.fontSize * Math.min(ratioX, ratioY)) }
  design.value.width = width!; design.value.height = height!
  for (const { item: box } of layers.value) clamp(box)
  nextTick(measure)
}
function measure() { if (stage.value) scale.value = stage.value.clientWidth / design.value.width }
let drag: { box: ShareBox | ShareDecoration; x: number; y: number; width: number; height: number; fontSize: number; clientX: number; clientY: number; scale: number; resize: boolean } | null = null
function start(event: PointerEvent, layer: ShareLayer, resize = false) {
  selectedKey.value = layer.key
  if (!props.editable || event.button !== 0) return
  const box = layer.item
  event.preventDefault(); (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  drag = { box, x: box.x, y: box.y, width: box.width, height: box.height, fontSize: 'field' in box ? box.fontSize : 0, clientX: event.clientX, clientY: event.clientY, scale: scale.value, resize }
}
function move(event: PointerEvent) {
  if (!drag || !props.editable) return
  const dx = (event.clientX - drag.clientX) / drag.scale, dy = (event.clientY - drag.clientY) / drag.scale
  if (drag.resize) {
    const min = 'field' in drag.box ? 20 : 2
    drag.box.width = Math.round(Math.min(design.value.width - drag.x, Math.max(min, drag.width + dx)))
    drag.box.height = Math.round(Math.min(design.value.height - drag.y, Math.max(min, drag.height + dy)))
    if ('field' in drag.box) drag.box.fontSize = Math.round(Math.min(200, Math.max(8, drag.fontSize * Math.min(drag.box.width / drag.width, drag.box.height / drag.height))))
  } else { drag.box.x = Math.round(drag.x + dx); drag.box.y = Math.round(drag.y + dy) }
  clamp(drag.box)
}
function nudge(event: KeyboardEvent, box: ShareBox | ShareDecoration) {
  if (!props.editable || !['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) return
  event.preventDefault(); const step = event.shiftKey ? 10 : 1
  box.x += event.key === 'ArrowLeft' ? -step : event.key === 'ArrowRight' ? step : 0
  box.y += event.key === 'ArrowUp' ? -step : event.key === 'ArrowDown' ? step : 0; clamp(box)
}
function boxStyle(box: ShareBox | ShareDecoration) { return { left: `${box.x / design.value.width * 100}%`, top: `${box.y / design.value.height * 100}%`, width: `${box.width / design.value.width * 100}%`, height: `${box.height / design.value.height * 100}%` } }
function orderKeys() { return design.value.layers ||= layers.value.map(layer => layer.key) }
function reorder(target: 'up' | 'down' | 'top' | 'bottom') {
  if (!props.editable || !selected.value) return
  const keys = orderKeys(), index = keys.indexOf(selectedKey.value)
  const next = target === 'top' ? keys.length - 1 : target === 'bottom' ? 0 : Math.min(keys.length - 1, Math.max(0, index + (target === 'up' ? 1 : -1)))
  keys.splice(index, 1); keys.splice(next, 0, selectedKey.value)
}
function canAdd(type: ShareDecoration['type']) {
  if (!props.editable) return false
  if ((design.value.decorations?.length || 0) >= 40 || (type === 'image' && (design.value.decorations || []).filter(item => item.type === 'image').length >= 16)) { ElMessage.warning('最多 40 个装饰图层，其中图片最多 16 张'); return false }
  return true
}
function insert(layer: ShareDecoration) {
  if (!canAdd(layer.type)) return
  const keys = orderKeys(), copy = JSON.parse(JSON.stringify(layer)) as ShareDecoration
  copy.id = `layer-${crypto.randomUUID()}`; copy.visible = true; clamp(copy)
  ;(design.value.decorations ||= []).push(copy)
  // New decorations sit below the data by default; the layer list can bring them forward.
  const firstField = keys.findIndex(key => key.startsWith('box:'))
  keys.splice(firstField < 0 ? keys.length : firstField, 0, copy.id); selectedKey.value = copy.id; tab.value = 'layers'
}
function addShape(type: keyof typeof shareShapes) { insert(createShareShape(type)) }
function removeLayer() {
  if (!props.editable || !selectedDecoration.value) return
  const id = selectedDecoration.value.id
  design.value.layers = orderKeys().filter(key => key !== id); design.value.decorations = design.value.decorations?.filter(layer => layer.id !== id); selectedKey.value = 'box:rate'
}
async function loadMaterials() {
  materialsLoading.value = true; materialError.value = ''
  try {
    const result = await request.get('/admin/share-materials') as unknown as Material[]
    if (!Array.isArray(result) || result.length > 100) throw new Error('素材库格式无效')
    for (const item of result) validateShareDecoration(item.layer)
    if (!disposed) materials.value = result
  } catch (e: any) { if (!disposed) materialError.value = e.message || '素材库加载失败' }
  finally { if (!disposed) materialsLoading.value = false }
}
async function persistMaterial(layer: ShareDecoration, name: string) {
  const saved = await request.post('/admin/share-materials', { name, layer: { ...layer, x: 0, y: 0 } }) as unknown as Material
  validateShareDecoration(saved.layer)
  materials.value.unshift(saved); materialPage.value = 1
  return saved
}
async function saveMaterial() {
  if (!canSaveMaterial.value || !selectedDecoration.value) return
  materialBusy.value = true
  try { await persistMaterial(selectedDecoration.value, selectedDecoration.value.name.trim()); ElMessage.success('已保存到素材库，可在其他模板复用') }
  catch (e: any) { ElMessage.error(e.message || '素材保存失败') }
  finally { materialBusy.value = false }
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''
  if (!file || !canSaveMaterial.value || !canAdd('image')) return
  materialBusy.value = true
  try {
    const prepared = await prepareShareImage(file), form = new FormData(); form.append('file', prepared.file)
    const result: any = await request.post('/upload/image', form)
    const ratio = Math.min(1, 600 / prepared.width, 600 / prepared.height)
    const layer: ShareDecoration = { id: `layer-${crypto.randomUUID()}`, name: file.name.replace(/\.[^.]+$/, '').slice(0, 80) || '图片素材', type: 'image', x: 0, y: 0,
      width: Math.max(2, Math.round(prepared.width * ratio)), height: Math.max(2, Math.round(prepared.height * ratio)), fill: 'none', stroke: 'none', strokeWidth: 0, radius: 0, opacity: 1, visible: true, src: result.url, fit: 'contain' }
    validateShareDecoration(layer)
    const saved = await persistMaterial(layer, layer.name)
    if (!disposed) { insert(saved.layer); ElMessage.success('素材已入库并放入画布') }
  } catch (e: any) { ElMessage.error(e.message || '素材上传失败') }
  finally { materialBusy.value = false }
}
async function removeMaterial(item: Material) {
  if (!canSaveMaterial.value) return
  try {
    await ElMessageBox.confirm(`从素材库移除「${item.name}」？已放入模板的副本不受影响。`, '移除素材')
    materialBusy.value = true; await request.delete(`/admin/share-materials/${item.id}`)
    materials.value = materials.value.filter(row => row.id !== item.id); materialPage.value = 1
  } catch (e: any) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message || '素材移除失败') }
  finally { materialBusy.value = false }
}
function apply() {
  if (!props.editable) return
  try {
    previewShareOrder(sample); validateShareDesign(design.value)
    if (!draft.name.trim() || !draft.languages.length) throw new Error('请填写模板名称并绑定至少一种语言')
    emit('apply', JSON.parse(JSON.stringify({ ...draft, name: draft.name.trim() })))
  } catch (e: any) { ElMessage.error(e.message) }
}
function download() {
  if (!image.value || rendering.value) return
  const link = document.createElement('a'); link.href = image.value; link.download = `preview-${draft.id}-${language.value}.png`; link.click()
}
watch(universal, value => { draft.languages = value ? ['*'] : draft.languages.filter(code => code !== '*') })
watch(tab, value => { if (value === 'materials') void loadMaterials() })
watch(materialQuery, () => { materialPage.value = 1 })
watch(selectedKey, () => { nextTick(() => { if (propertiesPanel.value) propertiesPanel.value.scrollTop = 0 }) })
watch(() => auth.token, () => {
  symbolRevision++; userRevision++; inviteRevision++; users.value = []; userId.value = undefined; searching.value = false
  qrImage.value = undefined; inviteUrl.value = ''; inviteError.value = ''; inviteLoading.value = false
  sample.userName = ''; sample.userEmail = ''; revision++; image.value = ''; void render()
})
watch([design, sample, language, brand, timezone, () => draft.base, () => auth.token], render, { deep: true, immediate: true })
onMounted(() => { observer = new ResizeObserver(measure); if (stage.value) observer.observe(stage.value); measure() })
onBeforeUnmount(() => { disposed = true; revision++; symbolRevision++; userRevision++; inviteRevision++; observer?.disconnect(); assets.dispose(); drag = null })
</script>

<template>
  <div class="share-designer">
    <header class="designer-toolbar">
      <div><h2>{{ editable ? '模板排版编辑' : '模板排版预览' }}</h2><p>数据盒子、形状与图片分层编辑；拖动移动，右下角缩放。方向键微调，Shift 加速。</p><p v-if="!rule.design">内置模板使用可编辑基础布局；应用并保存后才替换原排版，关闭不修改。</p></div>
      <div><el-button v-permission="'session:close'" @click="emit('close')">关闭</el-button><el-button v-permission="'share_templates:save'" type="primary" :disabled="!editable || !!error || rendering" @click="apply">应用排版</el-button></div>
    </header>
    <div class="designer-columns">
      <aside class="designer-panel">
        <div class="panel-tabs" role="tablist" aria-label="编辑面板"><button v-permission="'share_templates:view'" v-for="[id,name] in [['template','模板'],['layers','图层'],['materials','素材库']]" :key="id" type="button" role="tab" :aria-selected="tab===id" @click="tab=id!">{{ name }}</button></div>
        <template v-if="tab==='template'">
        <h3>模板设置</h3>
        <el-form label-position="top" :disabled="!editable">
          <el-form-item label="模板名称"><el-input v-model="draft.name" maxlength="80" aria-label="模板名称" /></el-form-item>
          <el-form-item label="底版 / 背景素材"><el-select v-model="draft.base" aria-label="模板底版"><el-option v-for="[id,name] in shareTemplateCatalog" :key="id" :value="id" :label="name" /></el-select></el-form-item>
          <el-form-item label="绑定语言"><el-checkbox v-model="universal">全语言通用</el-checkbox><el-select v-if="!universal" v-model="draft.languages" multiple aria-label="模板绑定语言"><el-option v-for="[id,name] in shareTemplateLanguages" :key="id" :value="id" :label="name" /></el-select></el-form-item>
          <el-form-item label="本模板展示重心"><el-radio-group :model-value="draft.focus" @update:model-value="changeFocus($event as ShareFocus)"><el-radio-button value="amount">盈亏</el-radio-button><el-radio-button value="rate">收益率</el-radio-button></el-radio-group></el-form-item>
          <el-form-item label="画布尺寸"><el-select :model-value="`${design.width}x${design.height}`" aria-label="画布尺寸" @change="resizeCanvas"><el-option value="1080x1440" label="竖图 1080 × 1440" /><el-option value="1080x1080" label="方图 1080 × 1080" /><el-option value="1440x900" label="横图 1440 × 900" /></el-select></el-form-item>
          <el-form-item label="背景颜色"><input v-model="design.background" type="color" aria-label="背景颜色" :disabled="!editable" /><el-checkbox v-model="design.artwork">使用底版图片</el-checkbox></el-form-item>
        </el-form>
        <h3>固定内容盒子</h3>
        <div v-for="box in design.boxes" :key="box.field" :class="['field-row',{active:selectedField===box.field}]">
          <el-checkbox v-model="box.visible" :disabled="!editable || requiredShareFields.includes(box.field)" :aria-label="`显示${shareFields[box.field]}`" />
          <button v-permission="'share_templates:view'" type="button" @click="selectedField=box.field">{{ shareFields[box.field] }}<small>{{ box.field }}</small></button>
        </div>
        <p class="panel-hint">交易字段和杠杆必须保留。用户姓名 / 邮箱仅在客户主动勾选后导出。</p>
        </template>
        <template v-else-if="tab==='layers'">
          <h3>图层顺序 <small>上方在前</small></h3>
          <div class="layer-actions"><el-button v-permission="'share_templates:save'" size="small" :disabled="!editable || !selected || layers[layers.length-1]?.key===selectedKey" aria-label="图层上移" @click="reorder('up')">上移</el-button><el-button v-permission="'share_templates:save'" size="small" :disabled="!editable || !selected || layers[0]?.key===selectedKey" aria-label="图层下移" @click="reorder('down')">下移</el-button><el-button v-permission="'share_templates:save'" size="small" :disabled="!editable || !selected" aria-label="图层置顶" @click="reorder('top')">置顶</el-button><el-button v-permission="'share_templates:save'" size="small" :disabled="!editable || !selected" aria-label="图层置底" @click="reorder('bottom')">置底</el-button></div>
          <div v-for="layer in layers.slice().reverse()" :key="layer.key" :class="['field-row','layer-row',{active:selectedKey===layer.key}]" :data-layer-key="layer.key">
            <el-checkbox v-model="layer.item.visible" :disabled="!editable || (layer.kind==='field' && requiredShareFields.includes(layer.item.field))" :aria-label="`显示${layerName(layer)}`" />
            <button v-permission="'share_templates:view'" type="button" :aria-label="`选择图层${layerName(layer)}`" @click="selectedKey=layer.key"><span>{{ layerName(layer) }}</span><small>{{ layer.kind==='field'?'数据':layer.item.type==='image'?'图片':'形状' }}</small></button>
          </div>
          <div v-if="selectedDecoration" class="layer-actions"><el-button v-permission="'share_templates:save'" size="small" :disabled="!editable" @click="insert({...selectedDecoration,x:selectedDecoration.x+20,y:selectedDecoration.y+20})">复制图层</el-button><el-button v-permission="'share_templates:save'" size="small" type="danger" :disabled="!editable" @click="removeLayer">删除图层</el-button><el-button v-permission="'share_templates:save'" size="small" :disabled="!canSaveMaterial" :loading="materialBusy" @click="saveMaterial">存入素材库</el-button></div>
          <p class="panel-hint">图层顺序同时作用于预览与客户导出。背景和底部来源标注保留，固定交易盒子不可删除。</p>
        </template>
        <template v-else>
          <h3>模板素材库</h3>
          <el-input v-model="materialQuery" placeholder="搜索素材名称" aria-label="搜索素材" maxlength="80" clearable />
          <input ref="uploadInput" type="file" accept="image/png,image/jpeg,image/gif,image/webp,image/svg+xml,.svg" hidden aria-label="上传素材文件" @change="upload" />
          <div class="layer-actions"><el-button v-permission="'share_templates:save'" type="primary" size="small" :disabled="!canSaveMaterial" :loading="materialBusy" @click="uploadInput?.click()">上传素材</el-button><el-button v-permission="'share_templates:view'" size="small" :disabled="materialsLoading" @click="loadMaterials">刷新</el-button></div>
          <p class="panel-hint">支持 PNG / JPG / GIF / WebP，≤ 5 MB。静态 SVG 安全转为 PNG；不接受脚本、外链和嵌入内容。素材库立即保存；排版需应用并发布。</p>
          <p v-if="materialsEditable===false" class="panel-hint">素材库被租户策略锁定，仍可使用已有素材。</p>
          <el-alert v-if="materialError" :title="materialError" type="error" :closable="false" />
          <p v-if="materialsLoading" role="status">加载素材中…</p>
          <p v-else-if="!pageMaterials.length" class="panel-hint">{{ materialQuery?'没有匹配素材':'暂无素材，上传图片或将形状存入素材库。' }}</p>
          <div class="material-grid">
            <div v-for="item in pageMaterials" :key="item.id" class="material-card">
              <button v-permission="'share_templates:save'" type="button" :disabled="!editable" :aria-label="`插入素材${item.name}`" @click="insert(item.layer)">
                <ProtectedElementImage v-if="item.layer.type==='image'" :src="item.layer.src" fit="contain" /><img v-else :src="shareShapeSvg(item.layer)" alt="矢量素材" /><span>{{ item.name }}</span>
              </button>
              <el-button v-permission="'share_templates:save'" size="small" link type="danger" :disabled="!canSaveMaterial" :aria-label="`移除素材${item.name}`" @click="removeMaterial(item)">移除</el-button>
            </div>
          </div>
          <div v-if="matchedMaterials.length>12" class="material-pagination"><el-button v-permission="'share_templates:view'" size="small" :disabled="materialPage===1" @click="materialPage--">上一页</el-button><span>{{ materialPage }} / {{ Math.ceil(matchedMaterials.length/12) }}</span><el-button v-permission="'share_templates:view'" size="small" :disabled="materialPage*12>=matchedMaterials.length" @click="materialPage++">下一页</el-button></div>
        </template>
      </aside>
      <main class="designer-preview">
        <div class="preview-toolbar"><el-select v-model="language" aria-label="预览语言"><el-option v-for="[id,name] in shareTemplateLanguages" :key="id" :value="id" :label="name" /></el-select><el-button v-permission="'share_templates:view'" :disabled="!image || rendering" @click="download">下载预览图</el-button></div>
        <div class="shape-toolbar"><span>添加</span><button v-permission="'share_templates:save'" v-for="(name,type) in shareShapes" :key="type" type="button" :disabled="!editable" :aria-label="`添加${name}`" @click="addShape(type)"><img :src="shapeIcons[type]" alt="" />{{ name }}</button><button v-permission="'share_templates:view'" type="button" @click="tab='materials'">素材库</button></div>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <div ref="stage" class="designer-stage" :style="{aspectRatio:`${design.width}/${design.height}`,background:design.background}" aria-label="分享模板画布">
          <img v-if="image" :src="image" alt="模板实时预览，示例数据" draggable="false" />
          <template v-for="(layer,index) in layers" :key="layer.key">
            <div v-if="layer.item.visible" :class="['content-box',{selected:selectedKey===layer.key}]" :style="{...boxStyle(layer.item),zIndex:selectedKey===layer.key?layers.length+1:index+1}" role="button" tabindex="0"
              :aria-label="`移动${layerName(layer)}盒子`" :data-field="layer.kind==='field'?layer.item.field:undefined" :data-layer="layer.key" @pointerdown="start($event,layer)" @pointermove="move" @pointerup="drag=null" @pointercancel="drag=null" @lostpointercapture="drag=null" @keydown="nudge($event,layer.item)" @focus="selectedKey=layer.key">
              <span class="box-caption">{{ layerName(layer) }}</span>
              <button v-permission="'share_templates:save'" v-if="editable && selectedKey===layer.key" class="resize-handle" type="button" :aria-label="`缩放${layerName(layer)}盒子`"
                @pointerdown.stop="start($event,layer,true)" @pointermove.stop="move" @pointerup.stop="drag=null" @pointercancel.stop="drag=null" @lostpointercapture="drag=null" />
            </div>
          </template>
        </div>
        <p class="panel-hint">{{ design.width }} × {{ design.height }} PNG · 内容与标签跟随预览 / 客户页面语言。</p>
      </main>
      <aside ref="propertiesPanel" class="designer-panel">
        <template v-if="selected">
          <h3>{{ selectedLayer && layerName(selectedLayer) }} · {{ selectedBox?'盒子样式':'图层样式' }}</h3>
          <el-input v-if="selectedDecoration" v-model="selectedDecoration.name" maxlength="80" :disabled="!editable" aria-label="图层名称" class="layer-name" />
          <el-form label-position="top" :disabled="!editable" class="geometry-form">
            <el-form-item label="X 坐标"><el-input-number v-model="selected.x" :min="0" :max="design.width-selected.width" :precision="0" aria-label="盒子X坐标" @change="clamp(selected!)" /></el-form-item>
            <el-form-item label="Y 坐标"><el-input-number v-model="selected.y" :min="0" :max="design.height-selected.height" :precision="0" aria-label="盒子Y坐标" @change="clamp(selected!)" /></el-form-item>
            <el-form-item label="宽度"><el-input-number v-model="selected.width" :min="selectedBox?20:2" :max="design.width-selected.x" :precision="0" aria-label="盒子宽度" @change="clamp(selected!)" /></el-form-item>
            <el-form-item label="高度"><el-input-number v-model="selected.height" :min="selectedBox?20:2" :max="design.height-selected.y" :precision="0" aria-label="盒子高度" @change="clamp(selected!)" /></el-form-item>
            <template v-if="selectedBox">
              <el-form-item label="字号"><el-input-number v-model="selectedBox.fontSize" :min="8" :max="200" aria-label="盒子字号" /></el-form-item>
              <el-form-item label="文字颜色"><input v-model="selectedBox.color" type="color" aria-label="盒子文字颜色" :disabled="!editable" /></el-form-item>
              <el-form-item label="对齐" class="span-two"><el-radio-group v-model="selectedBox.align" aria-label="盒子文字对齐"><el-radio-button value="left">左</el-radio-button><el-radio-button value="center">中</el-radio-button><el-radio-button value="right">右</el-radio-button></el-radio-group></el-form-item>
              <el-form-item label="字重"><el-select v-model="selectedBox.weight" aria-label="盒子字重"><el-option :value="400" label="常规" /><el-option :value="500" label="中等" /><el-option :value="600" label="半粗" /><el-option :value="700" label="粗体" /><el-option :value="800" label="特粗" /></el-select></el-form-item>
              <el-form-item label="标签"><el-checkbox v-model="selectedBox.label">显示字段名</el-checkbox></el-form-item>
            </template>
            <template v-if="selectedDecoration">
              <template v-if="selectedDecoration.type!=='image'">
                <el-form-item label="填充颜色"><input type="color" :value="selectedDecoration.fill==='none'?'#ffffff':selectedDecoration.fill" :disabled="!editable || selectedDecoration.fill==='none'" aria-label="形状填充颜色" @input="selectedDecoration.fill=($event.target as HTMLInputElement).value" /><el-checkbox :model-value="selectedDecoration.fill==='none'" @update:model-value="selectedDecoration.fill=$event?'none':'#dce9df'">无填充</el-checkbox></el-form-item>
                <el-form-item label="描边颜色"><input type="color" :value="selectedDecoration.stroke==='none'?'#17804c':selectedDecoration.stroke" :disabled="!editable || selectedDecoration.stroke==='none'" aria-label="形状描边颜色" @input="selectedDecoration.stroke=($event.target as HTMLInputElement).value" /><el-checkbox :model-value="selectedDecoration.stroke==='none'" @update:model-value="selectedDecoration.stroke=$event?'none':'#17804c'">无描边</el-checkbox></el-form-item>
                <el-form-item label="描边宽度"><el-input-number v-model="selectedDecoration.strokeWidth" :min="0" :max="100" aria-label="形状描边宽度" /></el-form-item>
                <el-form-item v-if="selectedDecoration.type==='roundRect'" label="圆角半径"><el-input-number v-model="selectedDecoration.radius" :min="0" :max="1080" aria-label="形状圆角半径" /></el-form-item>
              </template>
              <el-form-item v-else label="图片适配" class="span-two"><el-select v-model="selectedDecoration.fit" aria-label="图片适配"><el-option value="contain" label="完整显示" /><el-option value="cover" label="裁切填满" /><el-option value="stretch" label="拉伸" /></el-select></el-form-item>
              <el-form-item label="不透明度" class="span-two"><el-slider v-model="selectedDecoration.opacity" :min="0" :max="1" :step="0.01" aria-label="图层不透明度" show-input /></el-form-item>
            </template>
          </el-form>
        </template>
        <h3>预览数据</h3>
        <el-form label-position="top" class="preview-data-form">
          <el-form-item label="品种">
            <el-autocomplete :key="auth.token" v-model="sample.symbol" :fetch-suggestions="searchSymbols" :debounce="250" :trigger-on-focus="false" clearable fit-input-width placeholder="输入品种代码或名称搜索" aria-label="预览品种" maxlength="80">
              <template #default="{ item }">{{ item.label }}</template>
            </el-autocomplete>
          </el-form-item>
          <div class="geometry-form"><el-form-item label="方向"><el-select v-model="sample.buy" aria-label="预览方向"><el-option :value="true" label="做多" /><el-option :value="false" label="做空" /></el-select></el-form-item><el-form-item label="杠杆"><el-input-number v-model="sample.leverage" :min="1" :max="10000" aria-label="预览杠杆" /></el-form-item></div>
          <el-form-item label="开仓价"><el-input-number v-model="sample.openPrice" :min="0.00000001" :max="1e15" :controls="false" aria-label="预览开仓价" /></el-form-item>
          <el-form-item label="平仓价"><el-input-number v-model="sample.closePrice" :min="0.00000001" :max="1e15" :controls="false" aria-label="预览平仓价" /></el-form-item>
          <el-form-item label="开仓时间"><input v-model="sample.openTime" type="datetime-local" aria-label="预览开仓时间" /></el-form-item>
          <el-form-item label="平仓时间"><input v-model="sample.closeTime" type="datetime-local" aria-label="预览平仓时间" /></el-form-item>
          <el-form-item v-if="can('users:view')" label="选择用户（仅用于本次预览）"><el-select v-model="userId" filterable remote clearable :remote-method="searchUsers" :loading="searching" placeholder="搜索用户 ID / 邮箱" aria-label="预览用户" @change="chooseUser"><el-option v-for="user in users" :key="user.id" :value="user.id" :label="`ID ${user.id} · ${user.nickname || ''} · ${user.email || ''}`" /></el-select></el-form-item>
          <p v-if="inviteLoading" class="panel-hint" role="status">正在加载用户邀请二维码…</p>
          <template v-if="inviteError"><el-alert :title="inviteError" type="error" :closable="false" /><el-button v-permission="'users:view'" @click="chooseUser(userId)">重试二维码</el-button></template>
          <el-form-item v-if="qrImage" label="用户邀请二维码"><img :src="qrImage.src" alt="所选用户邀请二维码" width="120" height="120" /><el-input :model-value="inviteUrl" readonly aria-label="预览邀请链接" /><small class="panel-hint">绑定用户 ID {{ userId }}；扫码进入该用户的邀请注册页面。清除用户同时移除二维码。</small></el-form-item>
          <el-form-item label="用户名字"><el-input v-model="sample.userName" aria-label="预览用户名字" maxlength="80" /></el-form-item>
          <el-form-item label="用户邮箱"><el-input v-model="sample.userEmail" aria-label="预览用户邮箱" maxlength="254" /></el-form-item>
          <el-form-item label="品牌"><el-input v-model="brand" aria-label="预览品牌" maxlength="80" /></el-form-item>
          <el-form-item label="时间标注"><el-input v-model="timezone" aria-label="预览时间标注" maxlength="40" /></el-form-item>
          <div class="return-preview"><span>自动收益率</span><strong>{{ rate===null?'—':`${rate>0?'+':''}${rate.toFixed(2)}%` }}</strong></div>
          <p class="panel-hint">预览：价格涨跌幅 × 杠杆（做空反向），未计手续费，保证金固定为 1,000 USD。客户分享仍采用实际结算盈亏 ÷ 保证金。</p>
          <el-button v-permission="'share_templates:view'" type="primary" :loading="rendering" @click="render">生成预览图</el-button>
        </el-form>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.panel-tabs{display:flex;gap:4px;margin:-2px -2px 18px;border-bottom:1px solid #e0e5ec}.panel-tabs button{flex:1;padding:8px 2px;border:0;background:none;font:inherit;font-size:13px;color:#697386;cursor:pointer;border-bottom:2px solid transparent}.panel-tabs button[aria-selected=true]{color:#17804c;border-color:#17804c;font-weight:600}.layer-actions{display:flex;flex-wrap:wrap;gap:6px;margin:12px 0}.layer-actions .el-button+.el-button{margin:0}.layer-row{border:1px solid #edf0f4;margin:4px 0}.layer-row button>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:120px}.designer-panel h3 small{color:#77808e;font-weight:400;font-size:11px;margin-left:8px}.shape-toolbar{display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin:0 0 12px;font-size:12px;color:#697386}.shape-toolbar button{display:flex;align-items:center;gap:4px;border:1px solid #ccd5df;border-radius:5px;padding:5px 8px;background:#fff;color:#344155;font:inherit;cursor:pointer}.shape-toolbar button:disabled{opacity:.5;cursor:not-allowed}.shape-toolbar img{width:22px;height:18px;object-fit:contain}.material-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.material-card{border:1px solid #e0e5ec;border-radius:6px;padding:6px;min-width:0;text-align:center}.material-card>button:first-child{border:0;background:none;width:100%;padding:0;cursor:pointer;color:inherit}.material-card>button:disabled{cursor:default}.material-card img,.material-card .el-image{width:100%;height:70px;object-fit:contain;background:repeating-conic-gradient(#eef0f3 0% 25%,#fff 0% 50%) 0/12px 12px}.material-card span{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px;margin-top:6px}.material-pagination{display:flex;gap:6px;align-items:center;justify-content:center;font-size:12px;margin-top:14px}.layer-name{margin-bottom:14px}
.share-designer{color:#263445}.designer-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:18px}.designer-toolbar h2{margin:0;font-size:20px}.designer-toolbar p,.panel-hint{font-size:12px;color:#77808e;line-height:1.65}.designer-toolbar p{margin:6px 0 0}.designer-columns{display:grid;grid-template-columns:240px minmax(300px,1fr) 286px;gap:18px;align-items:start}.designer-panel{padding:16px;background:#fff;border:1px solid #e0e5ec;border-radius:10px;max-height:78vh;overflow-y:auto}.designer-panel h3{margin:0 0 14px;font-size:14px}.designer-panel h3:not(:first-child){margin-top:24px}.designer-panel .el-select,.designer-panel .el-autocomplete,.designer-panel .el-input-number{width:100%}.designer-panel input[type=datetime-local]{box-sizing:border-box;width:100%;min-width:0;border:1px solid #dcdfe6;border-radius:4px;padding:8px;font:inherit}.designer-panel input[type=color]{width:40px;height:30px;padding:2px;border:1px solid #dcdfe6;border-radius:4px;margin-right:8px}.field-row{display:flex;gap:8px;align-items:center;padding:3px 8px;border-radius:6px}.field-row.active{background:#edf5e6}.field-row button{display:flex;justify-content:space-between;align-items:center;gap:5px;flex:1;border:0;background:none;cursor:pointer;text-align:left;font:inherit;font-size:12px;color:inherit}.field-row small{font-size:10px;color:#8b95a5}.designer-preview{padding:16px;background:#e6ebf1;border:1px solid #dbe2ea;border-radius:10px;min-width:0}.preview-toolbar{display:flex;gap:12px;justify-content:space-between;margin-bottom:14px}.preview-toolbar .el-select{width:160px}.designer-stage{position:relative;max-width:680px;margin:auto;overflow:hidden;touch-action:none}.designer-stage>img{display:block;width:100%;height:100%;position:absolute;inset:0;pointer-events:none}.content-box{position:absolute;border:1px dashed #8793a280;box-sizing:border-box;cursor:move;touch-action:none;outline:none}.content-box.selected,.content-box:focus-visible{border:2px solid #409eff;background:#409eff08}.box-caption{position:absolute;left:0;top:0;background:#263445d9;color:#fff;font-size:10px;padding:2px 4px;white-space:nowrap;opacity:0;pointer-events:none}.content-box.selected .box-caption,.content-box:hover .box-caption{opacity:1}.resize-handle{position:absolute;width:14px;height:14px;right:-1px;bottom:-1px;border:2px solid #fff;background:#409eff;cursor:nwse-resize;touch-action:none}.geometry-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 10px}.span-two{grid-column:span 2}.return-preview{padding:12px;background:#f1f7ec;border-radius:6px;display:flex;align-items:center;justify-content:space-between}.return-preview strong{font-size:20px;color:#17804c}@media(max-width:1100px){.designer-columns{grid-template-columns:210px minmax(240px,1fr)}.designer-columns>aside:last-child{grid-column:1/-1;max-height:none}.preview-data-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px}.designer-panel{max-height:none}}@media(max-width:680px){.designer-columns{display:flex;flex-direction:column}.designer-panel,.designer-preview{box-sizing:border-box;width:100%}.designer-preview{order:-1}.designer-toolbar{align-items:flex-start;flex-direction:column}.preview-data-form{display:block}.designer-toolbar h2{font-size:18px}}
/* Desktop sidebars follow the preview height, without letting long forms enlarge the grid row. */
@media(min-width:1101px){.designer-columns{align-items:stretch}.designer-panel{contain:size;min-height:0;max-height:none}}
</style>
