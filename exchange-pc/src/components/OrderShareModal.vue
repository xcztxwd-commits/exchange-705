<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import QRCode from 'qrcode'
import request from '@/utils/request'
import { useLocaleStore } from '@/store/locale'
import { useAuthStore } from '@/store/auth'
import { validateShareDesign, shareProfileCopy, type ShareTemplateRule } from '../../../exchange-frontend/src/utils/shareTemplateDesign'
import { createShareAssetLoader, fetchShareImage } from '../../../exchange-frontend/src/utils/shareTemplateAssets'
import { formatDateTime, getSystemTimezone, systemTimezoneReady } from '@/utils/dateTime'
import { displaySymbol } from '@/utils/displaySymbol'
import { recentShareChart, drawSharePoster, settledShareOrder, shareCopy, shareLanguage, shareReturn, shareTemplates, shareNeedsChart, shareBackgrounds,
  type ShareChart, type ShareKind, type ShareOptions, type ShareOrder, type ShareTemplate } from '@/utils/orderShare'

const props = defineProps<{ orderId: string | number; kind: ShareKind; desktop?: boolean }>()
const emit = defineEmits<{ (event: 'close'): void }>()
const locale = useLocaleStore()
const copy = computed(() => shareCopy(locale.locale))
const dialog = ref<HTMLDialogElement>()
const order = ref<ShareOrder | null>(null)
const brand = ref('EXCHANGE')
const busy = ref(true), error = ref(''), notice = ref(''), preview = ref(''), showQr = ref(false)
const showAmount = ref(true), showRate = ref(true)
const previewRatio = ref('3 / 4')
const backgrounds = new Map<ShareTemplate, Promise<HTMLImageElement>>()
function loadBackground(template: ShareTemplate) {
  const file = shareBackgrounds[template]
  if (!file) return undefined
  if (!backgrounds.has(template)) {
    const image = new Image()
    image.src = `${import.meta.env.BASE_URL}share-templates/${file}`
    backgrounds.set(template, image.decode().then(() => image).catch(() => {
      backgrounds.delete(template)
      throw new Error(copy.value.backgroundError)
    }))
  }
  return backgrounds.get(template)
}
const options = reactive<ShareOptions>({ template: 'light', mode: 'both', focus: 'amount', quantity: false, capital: false, fee: false, leverage: false, orderId: false, openTime: false })
const templates = ref<string[]>([])
const selectedTemplate = ref('light'), definitions = ref<Record<string, ShareTemplateRule>>({}), showPersonal = ref(false)
const auth = useAuthStore()
const assets = createShareAssetLoader(src => fetchShareImage(src, auth.token), () => auth.token || '')
let profile: { userName: string; userEmail: string } | undefined
const needsProfile = computed(() => definitions.value[selectedTemplate.value]?.design?.boxes.some(box => box.visible && ['userName','userEmail'].includes(box.field)))
const profileLabel = computed(() => shareProfileCopy(shareLanguage(locale.locale))[2])
const templateBase = (id: string): ShareTemplate => definitions.value[id]?.base || id as ShareTemplate
const templateName = (id: string) => id.startsWith('custom-') ? definitions.value[id]?.name || id : copy.value[templateBase(id)]
const templateList = ref<HTMLElement>()
const templateIndex = computed(() => templates.value.indexOf(selectedTemplate.value))
let touch: { x: number; y: number; time: number } | null = null
function changeTemplate(offset: number) {
  if (busy.value || !templates.value.length) return
  selectedTemplate.value = templates.value[(templateIndex.value + offset + templates.value.length) % templates.value.length]!
}
function startSwipe(event: TouchEvent) {
  const point = event.touches[0]
  touch = event.touches.length === 1 && point ? { x: point.clientX, y: point.clientY, time: Date.now() } : null
}
function endSwipe(event: TouchEvent) {
  const start = touch, point = event.changedTouches[0]
  touch = null
  if (!start || !point || event.touches.length || Date.now() - start.time > 800) return
  const dx = point.clientX - start.x, dy = point.clientY - start.y
  if (Math.abs(dx) >= 40 && Math.abs(dx) > Math.abs(dy) * 1.3) changeTemplate(dx < 0 ? 1 : -1)
}
let blob: Blob | null = null, qrImage: HTMLImageElement | undefined, chart: ShareChart | undefined
let generation = 0, disposed = false
let timezone = '', previousFocus: HTMLElement | null = null
const canShare = ref(false)
const returnAvailable = computed(() => order.value && shareReturn(order.value) !== null)
let previousBodyOverflow = ''
function closeOnBackdrop(event: MouseEvent) {
  if (event.target !== dialog.value) return
  const rect = dialog.value.getBoundingClientRect()
  if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) emit('close')
}

async function loadOrder() {
  busy.value = true; error.value = ''; order.value = null; chart = undefined; blob = null; canShare.value = false
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''; templates.value = []; definitions.value = {}; showPersonal.value = false; profile = undefined
  const run = ++generation
  try {
    const [result, , configured] = await Promise.all([
      request.get(`/trade/${props.kind}/orders`, { params: { status: 'CLOSED' } }), systemTimezoneReady,
      request.get('/user/share-templates', { params: { locale: shareLanguage(locale.locale), details: true } }),
    ])
    if (disposed || run !== generation) return
    const config = configured as unknown as { templates: unknown; focus?: string; definitions?: ShareTemplateRule[]; brand?: string }
    brand.value = typeof config.brand === 'string' && config.brand.trim() || 'EXCHANGE'
    const enabled = Array.isArray(config) ? config : config.templates
    options.focus = config.focus === 'rate' ? 'rate' : 'amount'
    if (!Array.isArray(enabled)) throw new Error('Templates unavailable')
    if (Array.isArray(config.definitions)) for (const rule of config.definitions) {
      if (!rule || typeof rule.id !== 'string' || !shareTemplates.includes(rule.base) || typeof rule.name !== 'string' || !['amount','rate'].includes(rule.focus)) throw new Error('Templates unavailable')
      if (rule.design) validateShareDesign(rule.design)
      if (rule.id.startsWith('custom-') && !rule.design) throw new Error('Templates unavailable')
      definitions.value[rule.id] = rule
    }
    templates.value = [...new Set(enabled.filter((id): id is string => typeof id === 'string' && (shareTemplates.includes(id as ShareTemplate) || !!definitions.value[id])))]
    if (!templates.value.length) throw new Error('Templates unavailable')
    let selected = templates.value[0]!
    try {
      const saved = localStorage.getItem(`order-share-template:${shareLanguage(locale.locale)}`) as ShareTemplate
      if (templates.value.includes(saved)) selected = saved
    } catch { /* Storage is optional. */ }
    selectedTemplate.value = selected; options.template = templateBase(selected)
    await nextTick()
    if (disposed || run !== generation) return
    const response = result as unknown as { list?: Record<string, unknown>[] }
    const rows = Array.isArray(result) ? result : response.list || []
    const raw = rows.find((item: Record<string, unknown>) => String(item.id) === String(props.orderId))
    if (!raw) throw new Error('Order unavailable')
    order.value = settledShareOrder(raw, props.kind)
    timezone = getSystemTimezone()
    await render()
  } catch {
    if (!disposed && run === generation) { error.value = copy.value.error; busy.value = false }
  }
}
async function loadChart(value: ShareOrder, run: number) {
  for (let attempt = 0; attempt < 8; attempt++) {
    if (disposed || run !== generation) return undefined
    const result = await request.get(`/market/kline/${encodeURIComponent(value.symbol)}`, {
      params: { interval: '1m', limit: 80 },
    }) as unknown as { data?: { kline_list?: Record<string, unknown>[]; pending?: boolean; source?: string; symbol?: string }; ret?: number }
    if (result.data?.symbol !== value.symbol) throw new Error(copy.value.chartError)
    if (result.ret === 200 && result.data.kline_list?.length) {
      return recentShareChart(result.data.kline_list, result.data.source)
    }
    if (!result.data.pending) break
    await new Promise(resolve => setTimeout(resolve, 1500))
  }
  throw new Error(copy.value.chartError)
}
async function loadQr() {
  const info = await request.get('/user/invite/info') as unknown as { inviteCode?: string }
  if (!info.inviteCode) throw new Error(copy.value.qrError)
  const route = props.desktop ? '/?register=1&invite=' : '/register?invite='
  const url = `${window.location.origin}${window.location.pathname}#${route}${encodeURIComponent(info.inviteCode)}`
  const src = await QRCode.toDataURL(url, { width: 320, margin: 2, errorCorrectionLevel: 'M' })
  const image = new Image(); image.src = src; await image.decode()
  return image
}
async function render() {
  if (!order.value) return
  const run = ++generation
  busy.value = true; error.value = ''; notice.value = ''; blob = null; canShare.value = false
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = ''
  const value = { ...order.value }, definition = definitions.value[selectedTemplate.value]
  const settings = { ...options, template: templateBase(selectedTemplate.value), focus: definition?.focus || options.focus, design: definition?.design,
    language: shareLanguage(locale.locale), personal: showPersonal.value }
  const rateVisible = showRate.value && shareReturn(value) !== null
  settings.mode = showAmount.value ? (rateVisible ? 'both' : 'amount') : (rateVisible ? 'rate' : 'none')
  try {
    await document.fonts.ready
    if (showQr.value && !qrImage) {
      try { qrImage = await loadQr() } catch { throw new Error(copy.value.qrError) }
    }
    if (showPersonal.value && needsProfile.value) {
      if (!profile) {
        const userId = auth.user?.id || auth.user?.userId
        if (!userId) throw new Error(copy.value.error)
        const info = await request.get('/user/' + userId + '/info') as unknown as { nickname?: string; email?: string }
        if (disposed || run !== generation) return
        profile = { userName: info.nickname || '', userEmail: info.email || '' }
      }
      Object.assign(value, profile)
    }
    const background = !settings.design || settings.design.artwork ? await loadBackground(settings.template) : undefined
    const images = await assets.images(settings.design).catch(() => { throw new Error(copy.value.backgroundError) })
    if (!settings.design && shareNeedsChart(settings.template) && !chart) {
      try {
        const loaded = await loadChart(value, run)
        if (disposed || run !== generation) return
        chart = loaded
      } catch { throw new Error(copy.value.chartError) }
    }
    if (disposed || run !== generation) return
    const display = { ...value, symbol: displaySymbol(value), openTime: formatDateTime(value.openTime), closeTime: formatDateTime(value.closeTime) }
    const canvas = document.createElement('canvas')
    drawSharePoster(canvas, display, settings, copy.value, brand.value, timezone, showQr.value ? qrImage : undefined, chart, background, images)
    const result = await new Promise<Blob>((resolve, reject) => canvas.toBlob(data => data ? resolve(data) : reject(new Error(copy.value.error)), 'image/png'))
    if (disposed || run !== generation) return
    blob = result; previewRatio.value = `${canvas.width} / ${canvas.height}`; preview.value = URL.createObjectURL(result)
    canShare.value = !!navigator.canShare?.({ files: [new File([result], 'trade.png', { type: 'image/png' })] })
    try { localStorage.setItem(`order-share-template:${shareLanguage(locale.locale)}`, selectedTemplate.value) } catch { /* Storage is optional. */ }
  } catch (failure) {
    if (!disposed && run === generation) error.value = failure instanceof Error ? failure.message : copy.value.error
  } finally {
    if (!disposed && run === generation) busy.value = false
  }
}
function filename() { return `trade-${props.kind}-${displaySymbol(order.value).replace(/\//g, '-').replace(/[^a-z0-9_-]/gi, '')}-${selectedTemplate.value}.png` }
function save() {
  if (!blob || busy.value) return
  try {
    const a = document.createElement('a'); a.href = preview.value; a.download = filename()
    document.body.appendChild(a); a.click(); a.remove(); notice.value = copy.value.saved
  } catch { notice.value = copy.value.saveError }
}
async function systemShare() {
  if (!blob || busy.value) return
  if (!canShare.value) {
    save()
    notice.value = copy.value.shareFallback
    return
  }
  try {
    // The prepared file is shared directly in the click handler to retain user activation.
    await navigator.share({ files: [new File([blob], filename(), { type: 'image/png' })] })
  } catch (failure) {
    if (!(failure instanceof DOMException && failure.name === 'AbortError')) notice.value = copy.value.shareError
  }
}
function retry() { if (order.value) void render(); else void loadOrder() }
watch(() => locale.locale, () => { void loadOrder() }, { flush: 'sync' })
watch(() => auth.token, () => { void loadOrder() }, { flush: 'sync' })
watch([options, selectedTemplate, showQr, showAmount, showRate, showPersonal], () => { void render() }, { deep: true })
watch(selectedTemplate, async () => {
  await nextTick()
  const list = templateList.value, selected = list?.querySelector<HTMLElement>('[aria-pressed="true"]')
  if (list && selected) list.scrollLeft = selected.offsetLeft - (list.clientWidth - selected.clientWidth) / 2
})
onMounted(async () => {
  previousFocus = document.activeElement as HTMLElement
  previousBodyOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  await nextTick(); dialog.value?.showModal(); void loadOrder()
})
onBeforeUnmount(() => {
  disposed = true; generation++; assets.dispose(); dialog.value?.close()
  if (preview.value) URL.revokeObjectURL(preview.value)
  document.body.style.overflow = previousBodyOverflow
  previousFocus?.focus()
})
</script>

<template>
  <Teleport to="body">
    <dialog ref="dialog" class="order-share" aria-labelledby="order-share-title" @cancel.prevent="emit('close')" @click="closeOnBackdrop">
      <header class="pnl-header">
        <h2 id="order-share-title">{{ copy.title }}</h2>
        <button type="button" class="pnl-close" :aria-label="copy.close" :title="copy.close" @click="emit('close')">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
        </button>
      </header>
      <div class="pnl-content">
        <section class="pnl-preview" :aria-label="`${copy.preview} · ${templateName(selectedTemplate)}`" :aria-busy="busy" tabindex="0"
          @touchstart.passive="startSwipe" @touchend.passive="endSwipe" @touchcancel="touch = null"
          @touchmove.passive="event => { if (event.touches.length > 1) touch = null }"
          @keydown.left.prevent="changeTemplate(-1)" @keydown.right.prevent="changeTemplate(1)">
          <img v-if="preview" :src="preview" :alt="`${displaySymbol(order)} · ${templateName(selectedTemplate)}`" class="poster-preview" :style="{ aspectRatio: previewRatio }" draggable="false" />
          <div v-else class="pnl-placeholder" role="status">
            <span v-if="busy" class="pnl-spinner"></span>
            <p>{{ busy ? copy.loading : error }}</p>
            <button v-if="!busy" type="button" class="pnl-retry" @click="retry">{{ copy.retry }}</button>
          </div>
        </section>
        <section class="pnl-controls">
          <div class="pnl-template-caption"><span>{{ copy.swipe }}</span><span aria-live="polite">{{ templateIndex + 1 }} / {{ templates.length }}</span></div>
          <div class="pnl-template-picker">
          <button type="button" class="pnl-template-arrow" :aria-label="copy.previousTemplate" @click="changeTemplate(-1)"><span class="ui-chevron ui-chevron--left" aria-hidden="true"></span></button>
          <div ref="templateList" class="pnl-templates" role="group" :aria-label="copy.template">
            <button v-for="name in templates" :key="name" type="button" :aria-label="templateName(name)" :aria-pressed="selectedTemplate === name" :class="['pnl-template', { selected: selectedTemplate === name }]" @click="selectedTemplate = name">
              <span :class="['pnl-swatch', templateBase(name)]" aria-hidden="true"></span><span>{{ templateName(name) }}</span>
            </button>
          </div>
          <button type="button" class="pnl-template-arrow" :aria-label="copy.nextTemplate" @click="changeTemplate(1)"><span class="ui-chevron" aria-hidden="true"></span></button>
          </div>
          <div class="pnl-toggles">
            <label><input v-model="showQr" type="checkbox" /><span>{{ copy.qrShort }}</span></label>
            <label :class="{ unavailable: !returnAvailable }" :title="copy.rateNote"><input v-model="showRate" type="checkbox" :disabled="!returnAvailable" /><span>{{ copy.rate }}</span></label>
            <label><input v-model="showAmount" type="checkbox" /><span>{{ copy.amount }}</span></label>
          </div>
          <label v-if="needsProfile" class="pnl-personal"><input v-model="showPersonal" type="checkbox" /><span>{{ profileLabel }}</span></label>
        </section>
      </div>
      <footer class="pnl-actions">
        <p v-if="notice" class="pnl-notice" role="status">{{ notice }}</p>
        <button type="button" class="pnl-save" :disabled="busy || !preview" @click="save">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 3v12m-4-4 4 4 4-4M5 16v4h14v-4" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>{{ copy.save }}
        </button>
        <button type="button" class="pnl-share" :disabled="busy || !preview" @click="systemShare">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 15V3m-4 4 4-4 4 4M6 10H4v11h16V10h-2" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>{{ copy.shareShort }}
        </button>
      </footer>
    </dialog>
  </Teleport>
</template>

<style scoped>
.pnl-personal{display:flex;align-items:center;gap:6px;min-height:32px;font-size:11px;color:#697386;cursor:pointer}.pnl-personal input{accent-color:#709f00;flex-shrink:0}
.order-share{box-sizing:border-box;width:min(440px,calc(100vw - 32px));height:min(780px,calc(100dvh - 48px));max-width:none;max-height:none;margin:auto;padding:0;border:1px solid #e6e8ed;border-radius:24px;color:#17202c;background:#f5f7fb;box-shadow:0 28px 90px #0004;font:14px 'Microsoft YaHei',system-ui,sans-serif;overflow:hidden;overscroll-behavior:contain}
.order-share[open]{display:flex;flex-direction:column}.order-share::backdrop{background:#10151e99;backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px)}
.order-share button{font:inherit;cursor:pointer;box-sizing:border-box}.order-share button:disabled{opacity:.45;cursor:default}.order-share button:focus-visible,.order-share input:focus-visible{outline:3px solid #85bd00;outline-offset:2px}
.pnl-header{height:60px;min-height:60px;position:relative;display:flex;align-items:center;padding:0 66px 0 22px;background:#fff}.pnl-header h2{font-size:17px;line-height:1.3;font-weight:650;margin:0}.order-share .pnl-close{position:absolute;right:10px;top:8px;z-index:5;display:flex;align-items:center;justify-content:center;width:44px;height:44px;padding:0;border:1px solid #e2e7ed;border-radius:50%;background:#f0f3f7;color:#344155;visibility:visible;opacity:1}.pnl-close svg{display:block;flex-shrink:0}
.pnl-content{flex:1;min-height:0;display:flex;flex-direction:column}.pnl-preview{touch-action:pan-y pinch-zoom;outline-offset:-3px;flex:1;min-height:0;padding:16px;display:flex;align-items:center;justify-content:center;overflow:hidden}.poster-preview{display:block;width:auto;height:100%;max-width:100%;aspect-ratio:3/4;object-fit:contain;border-radius:12px;box-shadow:0 6px 22px #17202c18}.pnl-placeholder{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;padding:12px;text-align:center;color:#697386;font-size:12px;max-width:300px}.pnl-placeholder p{margin:0}.pnl-retry{background:white;border:1px solid #c8d3b4;color:#527800;padding:8px 18px;border-radius:8px}
.order-share .pnl-template-arrow{display:inline-flex;align-items:center;justify-content:center}
.pnl-controls{flex:none;padding:4px 18px 8px}.pnl-template-caption{height:24px;display:flex;align-items:center;justify-content:space-between;color:#697386;font-size:11px}.pnl-template-picker{display:flex;align-items:center;gap:5px}.order-share .pnl-template-arrow{flex:none;width:28px;height:38px;padding:0;border:0;border-radius:8px;background:#eaf0e1;color:#527800;font-size:26px}.pnl-templates{position:relative;display:flex;flex:1;min-width:0;overflow-x:auto;scrollbar-width:none;gap:6px;padding:2px;overscroll-behavior-x:contain}.pnl-templates::-webkit-scrollbar{display:none}.pnl-template{flex:0 0 auto;display:flex;align-items:center;justify-content:center;gap:6px;height:38px;padding:0 6px;border:1px solid #dfe4eb;border-radius:10px;background:#fff;color:#697386;font-size:12px!important;min-width:0}.pnl-template span:last-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.pnl-template.selected{border-color:#85bd00;background:#f4f9e9;color:#527800;box-shadow:inset 0 0 0 1px #85bd00}.pnl-swatch{width:13px;height:17px;border:1px solid #dfe4eb;border-radius:3px;background:#fff;flex-shrink:0}.pnl-swatch.dark{background:#131923;border-color:#131923}.pnl-swatch.chart{background:linear-gradient(140deg,#fff 42%,#85bd00 43%,#85bd00 57%,#eef6df 58%)}
.pnl-swatch.launch{background:linear-gradient(135deg,#121516 55%,#dda448)}.pnl-swatch.aurora{background:radial-gradient(at top right,#56e4da,#102f3b 45%,#060e17)}.pnl-swatch.racing{background:linear-gradient(145deg,#081936 40%,#397eee 48%,#081936 57%)}.pnl-swatch.receipt{background:#eeefdf;border-color:#a7b78a}.pnl-swatch.journal{background:linear-gradient(#d9ed74 30%,#f4f7ed 30%)}.pnl-swatch.voyage{background:linear-gradient(#9bb1c9,#f0c4a2 55%,#425060)}.pnl-swatch.referenceGold{background:linear-gradient(145deg,#070d10 60%,#d3af45)}.pnl-swatch.referenceWhite{background:linear-gradient(135deg,#fff 30%,#bbb 33%,#fff 48%,#bbf462 49%,#fff 60%)}.pnl-swatch.referenceTerminal{background:linear-gradient(140deg,#020b12 45%,#5ac8a4 48%,#020b12 53%)}.pnl-swatch.gold{background:linear-gradient(145deg,#141914 48%,#bc9b52 50%,#353221 70%)}.pnl-swatch.globe{background:radial-gradient(circle at 70% 70%,#99c951,#10151e 70%)}.pnl-swatch.architecture{background:linear-gradient(135deg,#fff 45%,#85bd00 46%,#85bd00 53%,#e2e5df 54%)}.pnl-swatch.city{background:linear-gradient(#bed5e5,#f4d9ba 65%,#637f83 66%)}
.pnl-toggles{height:44px;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px;align-items:center}.pnl-toggles label{display:flex;align-items:center;justify-content:center;gap:5px;min-height:38px;font-size:12px;cursor:pointer;min-width:0}.pnl-toggles input{accent-color:#709f00;width:16px;height:16px;margin:0;flex-shrink:0}.pnl-toggles span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.unavailable{opacity:.4}
.pnl-actions{position:relative;flex:none;display:flex;gap:10px;padding:12px 18px max(14px,env(safe-area-inset-bottom));border-top:1px solid #e6e8ed;background:#fff}.pnl-actions button{display:flex;justify-content:center;align-items:center;gap:8px;flex:1;min-width:0;height:44px;padding:0 10px;border:1px solid #dfe4eb;border-radius:12px;background:#fff;color:#17202c;font-weight:600}.pnl-actions .pnl-share{background:#85bd00;border-color:#85bd00;color:#132300}.pnl-notice{position:absolute;bottom:100%;left:12px;right:12px;margin:0 0 8px;padding:10px 12px;border-radius:10px;background:#17202cee;color:#fff;font-size:12px;line-height:1.4;box-shadow:0 3px 12px #0002;pointer-events:none}.pnl-spinner{width:24px;height:24px;border:3px solid #dde5cc;border-top-color:#85bd00;border-radius:50%;animation:pnl-spin .9s linear infinite}@keyframes pnl-spin{to{transform:rotate(360deg)}}
@media(max-width:680px){.order-share{width:min(400px,calc(100vw - 24px));height:min(720px,calc(100dvh - 40px));border-radius:22px}.pnl-header{height:52px;min-height:52px;padding-left:18px}.pnl-header h2{font-size:16px}.order-share .pnl-close{top:4px;right:6px}.pnl-preview{padding:10px 14px}.pnl-controls{padding:2px 12px 4px}.pnl-template{font-size:11px!important;gap:4px;height:36px}.pnl-actions{padding:10px 12px max(12px,env(safe-area-inset-bottom))}}
@media(max-height:460px) and (orientation:landscape){.order-share{width:min(700px,calc(100vw - 32px));height:calc(100dvh - 24px)}.pnl-content{flex-direction:row}.pnl-preview{width:45%;flex:1}.pnl-controls{width:48%;align-self:center}.pnl-header{height:48px;min-height:48px}.order-share .pnl-close{top:2px}.pnl-actions{padding:8px 18px}.pnl-actions button{height:40px}}
@media(prefers-reduced-motion:reduce){.pnl-spinner{animation:none}}
</style>
