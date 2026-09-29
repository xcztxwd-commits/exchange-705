<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/store/auth'
import { useLocaleStore } from '@/store/locale'
import request from '@/utils/request'
import { accountMode } from '@/utils/accountMode'
const isDemo = accountMode() === 'DEMO'
import { useActivityCopy, activityContent, money, type ActivityItem } from '@/utils/activity'
const auth = useAuthStore(), locale = useLocaleStore(), route = useRoute(), router = useRouter(), t = useActivityCopy()
const dialog = ref<HTMLDialogElement>(), items = ref<ActivityItem[]>([]), selected = ref<ActivityItem | null>(null)
const mode = ref<'inbox'|'gift'|'detail'|'success'>('inbox'), page = ref(0), pages = ref(0), busy = ref(false), error = ref(''), loading = ref(false)
const now = ref(Date.now())
const unread = computed(() => items.value.filter(x => !x.delivery.openedAt && !x.delivery.closedAt).length)
const copy = computed(() => selected.value ? activityContent(selected.value, locale.locale) : {})
const eligible = computed(() => { const c = selected.value?.campaign; return !!selected.value?.active && !!c && c.hasQuota && (!c.endsAt || new Date(c.endsAt+'Z').getTime() > now.value) })
let timer: ReturnType<typeof setInterval> | undefined, generation = 0, disposed = false, lastAuto: number | null = null
const message = (e: any) => e?.message || t(17)
async function event(item: ActivityItem, type: string) {
 const token = auth.token
 const result: any = await request.post(`/activity/messages/${item.delivery.id}/event`, { type })
 if (token === auth.token && !disposed) item.delivery = result
}
async function load(auto = false) {
 if (isDemo || !auth.token || route.path === '/demo' || loading.value) return
 const run = generation
 loading.value = true
 try {
  const result: any = await request.get('/activity/inbox', { params: { page: page.value } })
  if (run !== generation || disposed) return
  items.value = result.content || []; pages.value = result.totalPages || 0
  // Explicit client acknowledgement distinguishes sent from actually fetched messages.
  await Promise.all(items.value.filter(x => !x.delivery.receivedAt).map(x => event(x, 'RECEIVED')))
  if (run !== generation || disposed) return
  if (auto && !dialog.value?.open && !document.querySelector('.announcement-modal-overlay')) {
   const item = items.value.find(x => x.active && x.campaign.autoPopup && !x.delivery.openedAt && !x.delivery.closedAt && !x.delivery.claimedAt && x.delivery.id !== lastAuto)
   if (item) { lastAuto = item.delivery.id; selected.value = item; mode.value = 'gift'; await show() }
  }
 } catch (e) { if (run === generation) error.value = message(e) } finally { if (run === generation) loading.value = false }
}
async function show() { error.value = ''; await nextTick(); if (!disposed && dialog.value && !dialog.value.open) dialog.value.showModal() }
async function inbox() { if(isDemo) return; selected.value = null; mode.value = 'inbox'; page.value = 0; await show(); await load() }
async function open(item: ActivityItem) {
 selected.value = item; mode.value = item.delivery.claimedAt ? 'success' : 'detail'; await show()
 try { await event(item, 'OPENED') } catch(e) { error.value = message(e) }
}
function close() {
 const item = selected.value
 if (item && mode.value !== 'inbox') void event(item, 'CLOSED').catch(() => { /* Closing stays responsive; the server retains the last successful receipt. */ })
 dialog.value?.close(); selected.value = null; page.value = 0
}
async function claim() {
 if (!selected.value || busy.value || !eligible.value) return
 busy.value = true; error.value = ''; const item = selected.value, run = generation
 try {
  await request.post(`/activity/messages/${item.delivery.id}/claim`)
  if (run !== generation || disposed) return
  item.delivery.claimedAt = new Date().toISOString(); mode.value = 'success'
  window.dispatchEvent(new Event('trial-account-changed'))
 } catch(e) { if (run === generation) error.value = message(e) } finally { if(run === generation) busy.value = false }
}
async function turn(delta: number) { page.value += delta; await load() }
function trade() { close(); void router.push('/trade') }
async function deepLink() {
 if (isDemo || !route.query.activity || !auth.token || route.path === '/demo') return
 const run=generation
 try { const item: any = await request.get(`/activity/messages/${encodeURIComponent(String(route.query.activity))}`); if(run===generation&&!disposed) await open(item) } catch(e) { if(run===generation&&!disposed) { await inbox(); error.value = message(e) } }
}
watch(() => auth.token, () => { generation++; items.value=[]; page.value=0; selected.value=null; dialog.value?.close(); busy.value=false; loading.value=false; lastAuto=null; void load(true) })
watch(() => route.query.activity, deepLink)
watch(() => route.path, path => { if(path === '/demo') { generation++; loading.value=false; dialog.value?.close(); selected.value=null } else void load(true) })
onMounted(() => { void load(true); void deepLink(); timer=setInterval(() => { now.value=Date.now(); if(document.visibilityState==='visible' && !dialog.value?.open) void load(true) },15000); window.addEventListener('activity-inbox',inbox) })
onUnmounted(() => { disposed=true; generation++; clearInterval(timer); dialog.value?.close(); window.removeEventListener('activity-inbox',inbox) })
</script>

<template>
 <button v-if="!isDemo && route.path !== '/demo'" class="activity-inbox-trigger" type="button" :aria-label="t(0)" @click="inbox">
  <svg viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="m4 7 8 6 8-6"/></svg><span class="inbox-label">{{ t(0) }}</span><b v-if="unread" class="activity-badge">{{ unread }}</b>
 </button>
 <Teleport to="body">
  <dialog ref="dialog" class="activity-dialog" :class="{ 'activity-list': mode === 'inbox' }" aria-labelledby="activity-title" @cancel.prevent="close" @click="e => { if(e.target===dialog) close() }">
   <button class="activity-x" type="button" :aria-label="t(3)" @click="close">×</button>
   <template v-if="mode==='inbox'">
    <div class="activity-inbox-heading"><span class="activity-eyebrow">{{ t(1) }}</span><h2 id="activity-title">{{ t(0) }}</h2></div>
    <p v-if="loading" role="status">{{ t(16) }}</p>
    <p v-else-if="!items.length" class="activity-empty">{{ t(13) }}</p>
    <button v-for="item in items" :key="item.delivery.id" class="activity-message" @click="open(item)">
     <span class="message-gift" aria-hidden="true">✦</span><span><strong>{{ activityContent(item,locale.locale).title }}</strong><small>{{ money(item.campaign.amount) }} U · {{ item.delivery.claimedAt ? t(15) : item.active ? t(1) : t(14) }}</small></span><span aria-hidden="true">›</span>
    </button>
    <div v-if="pages>1" class="activity-pages"><button :disabled="!page || loading" @click="turn(-1)">{{ t(18) }}</button><span>{{ page+1 }} / {{ pages }}</span><button :disabled="page+1>=pages || loading" @click="turn(1)">{{ t(19) }}</button></div>
   </template>
   <template v-else-if="selected">
    <div :key="`${selected.delivery.id}-${mode === 'success'}`" class="activity-hero" :class="{ 'gift-open': mode!=='gift', 'no-animation': selected.campaign.animation==='NONE', 'confetti-only': selected.campaign.animation==='CONFETTI' }">
     <div class="activity-orbit orbit-one"></div><div class="activity-orbit orbit-two"></div>
     <span class="activity-eyebrow">{{ t(1) }}</span>
     <div class="activity-gift-scene" aria-hidden="true">
      <i v-for="n in 18" :key="n" class="confetti" :style="{ '--i': n, '--angle': `${n*137.5}deg`, '--distance': `${60+(n%5)*15}px`, '--color': ['#73b100','#dfb462','#b0d885','#2b735c'][n%4] }"></i>
      <div class="gift-halo"></div><div class="gift-box"><div class="gift-ribbon"></div><div class="gift-lid"><i></i><b></b></div><span class="gift-star">✦</span></div>
     </div>
     <span class="activity-reward"><strong :style="money(selected.campaign.amount).length > 6 ? { fontSize: 'clamp(30px, 8vw, 46px)', letterSpacing: '-1px' } : undefined">{{ money(selected.campaign.amount) }}</strong><span>U</span></span>
     <p>{{ mode==='success' ? (copy.success || t(5)) : t(22) }}</p>
    </div>
    <section class="activity-body">
     <h2 id="activity-title">{{ mode==='success' ? t(5) : copy.title }}</h2>
     <p class="activity-subtitle">{{ mode==='gift' ? t(23) : copy.body }}</p>
     <template v-if="mode !== 'gift'">
      <div class="activity-rules"><span class="rule-mark" aria-hidden="true">✓</span><p>{{ copy.terms }}<small>{{ t(12) }}</small></p></div>
      <div v-if="selected.campaign.endsAt" class="activity-expiry">{{ t(21) }} · {{ new Date(selected.campaign.endsAt+'Z').toLocaleString(locale.locale) }}</div>
     </template>
     <div v-if="mode==='gift'" class="activity-actions"><button class="activity-secondary" @click="close">{{ copy.close || t(3) }}</button><button class="activity-primary" @click="open(selected)">{{ copy.open || t(2) }} <span aria-hidden="true">↗</span></button></div>
     <button v-else-if="mode==='success'" class="activity-primary wide" @click="trade">{{ t(11) }} <span aria-hidden="true">↗</span></button>
     <button v-else class="activity-primary wide" :disabled="busy || !eligible" @click="claim">{{ busy ? t(16) : eligible ? (copy.claim || t(4)) : t(14) }}</button>
     <p class="activity-footnote">{{ t(6) }} · U</p>
    </section>
   </template>
   <div v-if="error" class="activity-error" role="alert">{{ error }} <button @click="mode==='inbox' ? load() : (error='')">{{ t(17) }}</button></div>
  </dialog>
 </Teleport>
</template>
<style scoped>
.activity-inbox-trigger{position:relative;display:inline-flex;align-items:center;gap:7px;min-width:42px!important;min-height:40px!important;padding:8px!important;border:1px solid #d9e2d1!important;border-radius:10px!important;background:#fff!important;color:#355523!important;font:600 12px/1.3 system-ui!important;cursor:pointer}.activity-badge{position:absolute;top:-5px;right:-5px;min-width:16px;padding:2px;border-radius:20px;background:#73b100;color:white;font-size:10px}.activity-dialog{--activity-green:#85bd00;border:0;border-radius:24px;padding:0;width:min(440px,calc(100vw - 28px));max-height:calc(100dvh - 32px);overflow:auto;color:#223226;background:#fff;box-shadow:0 30px 100px #081e2545;font:14px/1.6 system-ui,sans-serif;margin:auto}.activity-dialog::backdrop{background:rgba(13,29,24,.46);backdrop-filter:blur(6px)}.activity-dialog button{font:inherit;cursor:pointer}.activity-dialog button:disabled{opacity:.5;cursor:not-allowed}.activity-dialog button:focus-visible,.activity-inbox-trigger:focus-visible{outline:3px solid #e3b14c;outline-offset:3px}.activity-x{position:absolute;right:15px;top:14px;z-index:3;width:34px;height:34px;border:1px solid #d9e2d1;border-radius:50%;background:#ffffffba;color:#385537;font-size:25px!important;line-height:1!important}.activity-hero{position:relative;overflow:hidden;text-align:center;padding:26px 24px 20px;background:radial-gradient(ellipse at 50% 60%,#f4f9d9 0,#eaf3e1 45%,#e0ecd9 100%)}.activity-eyebrow{position:relative;font-size:10px;letter-spacing:2px;text-transform:uppercase;color:#587445;font-weight:700}.activity-orbit{position:absolute;width:440px;height:440px;border:1px solid #90ab7c35;border-radius:50%;left:50%;top:18%;transform:translateX(-50%)}.orbit-two{width:350px;height:350px;top:31%}.activity-gift-scene{position:relative;width:180px;height:132px;margin:24px auto 0;perspective:600px}.gift-halo{position:absolute;inset:15px;border-radius:50%;background:#fff8b8;filter:blur(24px)}.gift-box{position:absolute;left:43px;top:49px;width:94px;height:70px;border-radius:5px 5px 12px 12px;background:linear-gradient(110deg,#9dcd50,#6c9b27);box-shadow:10px 12px 16px #46651630;transform:rotate(-7deg);animation:gift-float 3s ease-in-out infinite}.gift-ribbon{position:absolute;left:37px;height:100%;width:20px;background:linear-gradient(90deg,#d6af61,#ffe6a1,#d2a455)}.gift-lid{position:absolute;top:-9px;left:-6px;width:106px;height:21px;background:linear-gradient(100deg,#b2da6d,#78a638);border-radius:5px;box-shadow:0 3px 4px #35541d30;transition:transform .8s cubic-bezier(.2,.8,.2,1)}.gift-lid:after{content:'';position:absolute;left:43px;top:0;width:20px;height:100%;background:#edd08a}.gift-lid i,.gift-lid b{position:absolute;bottom:20px;left:30px;width:27px;height:20px;border:6px solid #e4bd6e;border-radius:22px 22px 0 22px;transform:rotate(20deg)}.gift-lid b{left:50px;transform:rotate(70deg)}.gift-star{position:absolute;right:8px;bottom:11px;color:#eafaaf;font-size:20px}.gift-open .gift-lid{transform:translate(8px,-32px) rotate(12deg)}.gift-open .gift-star{animation:star-rise .9s ease both}.confetti{position:absolute;left:50%;top:57%;width:5px;height:9px;background:var(--color);border-radius:1px;opacity:0;transform:rotate(var(--angle)) translateY(-40px)}.gift-open .confetti{animation:confetti-burst 1.3s calc(var(--i)*20ms) ease-out both}.activity-reward{position:relative;display:flex;justify-content:center;align-items:baseline;gap:8px;color:#344c25;margin-top:2px}.activity-reward strong{font-size:64px;font-weight:750;line-height:1.1;letter-spacing:-4px;font-variant-numeric:tabular-nums}.activity-reward>span{font-size:22px;font-weight:600}.activity-hero>p{margin:8px 0 0;font-size:11px;color:#61794f}.activity-body{padding:25px 30px 18px}.activity-body h2{margin:0 0 10px;font-size:23px;letter-spacing:-.5px;line-height:1.3;overflow-wrap:anywhere}.activity-subtitle{margin:0;color:#69736b;font-size:13px;white-space:pre-wrap;overflow-wrap:anywhere}.activity-rules{display:flex;gap:10px;margin:20px 0 12px;padding:12px;background:#f6f8f3;border:1px solid #edf1e7;border-radius:12px;font-size:12px;line-height:1.6}.activity-rules p{margin:0;white-space:pre-wrap;overflow-wrap:anywhere}.activity-rules small{display:block;margin-top:8px;color:#7c8579;font-size:10px}.rule-mark{color:#6e9836}.activity-expiry{font-size:10px;color:#7a8277;margin:10px 0 18px}.activity-actions{display:flex;gap:10px;margin-top:26px}.activity-primary,.activity-secondary{border-radius:10px;padding:13px 16px;border:1px solid transparent;font-weight:650!important;min-height:48px}.activity-primary{background:var(--activity-green);color:white;display:flex;align-items:center;justify-content:center;gap:20px;flex:1}.activity-secondary{background:white;color:#5c6759;border-color:#dce3d6;min-width:100px}.wide{width:100%;margin-top:18px}.activity-footnote{font-size:10px;color:#90998b;text-align:center;margin:14px 0 0}.activity-inbox-heading{padding:28px 24px 10px}.activity-inbox-heading h2{margin:5px 0 14px;font-size:26px}.activity-message{display:flex;align-items:center;gap:14px;width:100%;padding:18px 24px;text-align:left;border:0;border-top:1px solid #eef1eb;background:white;color:inherit}.activity-message:hover{background:#f8faf5}.activity-message>span:nth-child(2){flex:1;min-width:0}.activity-message strong{display:block;font-size:14px;overflow-wrap:anywhere}.activity-message small{display:block;margin-top:5px;color:#818a7b;font-size:11px}.message-gift{color:#73b100;background:#f0f7e7;border-radius:12px;padding:8px 14px;font-size:23px}.activity-empty{padding:32px;text-align:center;color:#838c7e}.activity-pages{display:flex;justify-content:space-between;align-items:center;padding:20px;gap:10px}.activity-pages button,.activity-error button{border:1px solid #d5dfcb;background:white;border-radius:7px;padding:6px 10px;color:#557639}.activity-error{margin:15px;padding:12px;border-radius:8px;background:#fff0ec;color:#a34433;font-size:12px}.confetti-only .gift-box{background:linear-gradient(135deg,#f3d785,#d0a244);border-radius:50%;width:80px;height:80px;left:50px;top:35px}.confetti-only .gift-ribbon,.confetti-only .gift-lid{display:none}.confetti-only .gift-star{font-size:40px;right:21px;bottom:8px;color:#fff1b9}.no-animation *{animation:none!important;transition:none!important}.no-animation .confetti{display:none}@keyframes gift-float{50%{transform:translateY(-5px) rotate(-3deg)}}@keyframes confetti-burst{0%{opacity:0;transform:rotate(var(--angle)) translateY(-20px)}20%{opacity:1}100%{opacity:0;transform:rotate(var(--angle)) translateY(calc(-1*var(--distance))) rotate(180deg)}}@keyframes star-rise{50%{transform:translateY(-45px) scale(1.5)}}@media(prefers-reduced-motion:reduce){.activity-dialog *{animation:none!important;transition:none!important}}@media(max-width:420px){.inbox-label{display:none}.activity-body{padding:22px}.activity-reward strong{font-size:56px}.activity-hero{padding-top:24px}.activity-gift-scene{height:124px}}
</style>
