<script setup lang="ts">
import { ref, watch, nextTick, onUnmounted } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useRoute, useRouter } from 'vue-router'
import request from '@/utils/request'
import { supportText, supportUrl } from '@/utils/support'
import { inboxState } from '../utils/unifiedInbox'
const props = withDefaults(defineProps<{ admin?: boolean; enabled?: boolean; inboxTarget?: string; mobile?: boolean }>(), {
  admin: false,
  enabled: true,
})
const auth = useAuthStore(),
  router = useRouter(),
  route = useRoute()
const state = ref<any>({}),
  audioEnabled = ref(false),
  audioError = ref('')
const t = (zh: string, en: string) => supportText(zh, en, props.admin)
let timer: ReturnType<typeof setTimeout> | undefined,
  generation = 0,
  audio: HTMLAudioElement | undefined
// Initial router state may be '/' while a lazy /inbox route is still resolving.
// Mount Teleport only after its real header exists; a missing target crashes Vue updates.
const headerTarget = ref<Element | null>(null)
let targetGeneration = 0
watch(() => [props.inboxTarget, route.fullPath, auth.token], async () => {
  const run = ++targetGeneration
  headerTarget.value = null
  if (!props.inboxTarget || props.mobile || props.admin) return
  await router.isReady()
  await nextTick()
  if (run === targetGeneration && props.inboxTarget) headerTarget.value = document.querySelector(props.inboxTarget)
}, { immediate: true })
let watermark = { latest: 0, queueLatest: 0, inboxLatest: 0 },
  initialized = false
async function sound() {
  if (!state.value.sound || !audio) return
  audio.src = supportUrl(state.value.sound)
  audio.volume = 0.65
  audio.currentTime = 0
  try {
    await audio.play()
    audioError.value = ''
  } catch {
    audioEnabled.value = false
    audioError.value = t('浏览器已暂停声音，请再次启用', 'Sound was blocked. Tap to enable again.')
  }
}
async function enableAudio() {
  if (audioEnabled.value) {
    audioEnabled.value = false
    audio?.pause()
    return
  }
  audio ||= new Audio()
  audioEnabled.value = true
  await sound()
}
async function poll(version: number) {
  if (version !== generation || !auth.token || !props.enabled) return
  try {
    const data: any = await request.get(`/${props.admin ? 'admin' : 'user'}/support/notifications`)
    if (version !== generation) return
    let newInbox = false
    if (!props.admin) {
      inboxState.chatUnread = data.chatUnread || 0
      data.inboxUnread = inboxState.unread
      try {
        const language = localStorage.getItem('locale') || 'en'
        const inbox: any = await request.get('/user/support/unified-inbox', { params: { language, size: 1 } })
        if (version !== generation || language !== (localStorage.getItem('locale') || 'en')) return
        newInbox = inbox.unread > inboxState.unread
        data.inboxUnread = inbox.unread
        inboxState.unread = inbox.unread
      } catch { /* Inbox failures must not disable live customer-service notifications. */ }
      data.inboxEnabled = true
    }
    if (version !== generation) return
    state.value = data
    const incoming = newInbox || (['latest', 'queueLatest', 'inboxLatest'] as const).some(
      (key) => data[key] > watermark[key],
    )
    if (initialized && incoming && audioEnabled.value) await sound()
    for (const key of ['latest', 'queueLatest', 'inboxLatest'] as const)
      watermark[key] = Math.max(watermark[key], data[key] || 0)
    initialized = true
  } catch {
    /* Next poll retries; do not turn network failures into an unread reset. */
  } finally {
    if (version === generation) timer = setTimeout(() => poll(version), 5000)
  }
}
watch(
  () => [auth.token, props.enabled],
  () => {
    clearTimeout(timer)
    const current = ++generation
    state.value = {}
    if (!props.admin) { inboxState.unread = 0; inboxState.chatUnread = 0 }
    initialized = false
    watermark = { latest: 0, queueLatest: 0, inboxLatest: 0 }
    audioEnabled.value = false
    audio?.pause()
    void poll(current)
  },
  { immediate: true },
)
const refresh = () => { clearTimeout(timer); void poll(++generation) }
window.addEventListener('unified-inbox-changed', refresh)
onUnmounted(() => {
  ++targetGeneration
  window.removeEventListener('unified-inbox-changed', refresh)
  ++generation
  clearTimeout(timer)
  audio?.pause()
})
</script>
<template>
  <Teleport v-if="auth.token && enabled && !admin && !mobile && headerTarget" :to="headerTarget" defer>
    <button class="header-inbox-button" type="button" :aria-label="t('站内信', 'Inbox')" :title="t('站内信', 'Inbox')" @click="router.push('/inbox')">
      <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="m4 7 8 6 8-6"/></svg>
      <b v-if="state.inboxUnread">{{ state.inboxUnread > 99 ? '99+' : state.inboxUnread }}</b>
    </button>
  </Teleport>
  <nav
    v-if="
      auth.token && enabled && route.path !== '/inbox' && (state.mode === 'internal' || state.inboxEnabled) && (!mobile || state.chatUnread)
    "
    class="support-notifications"
    :class="{ floating: !admin, detail: ['/customer-service', '/inbox'].includes(route.path) }"
    :aria-label="t('消息通知', 'Message notifications')"
  >
    <button
      v-if="
        state.mode === 'internal' &&
        (admin || (state.chatUnread && !['/customer-service', '/inbox'].includes(route.path)))
      "
      @click="router.push(admin ? '/support' : '/customer-service')"
    >
      {{ admin ? '客服' : t('客服回复', 'Support')
      }}<b v-if="state.waiting + state.chatUnread">{{ state.waiting + state.chatUnread }}</b>
    </button>
    <button
      v-if="!admin && !mobile && !inboxTarget && state.inboxEnabled && !['/customer-service', '/inbox'].includes(route.path)"
      @click="router.push('/inbox')"
    >
      {{ t('站内信', 'Inbox') }}<b v-if="state.inboxUnread">{{ state.inboxUnread }}</b>
    </button>
    <button
      v-if="state.sound && !mobile"
      :aria-pressed="audioEnabled"
      :title="
        audioError ||
        t(
          '网页开启且完成声音授权后可提示；锁屏不保证播放',
          'Requires an open page and sound permission; not guaranteed on a locked screen',
        )
      "
      @click="enableAudio"
    >
      {{ audioEnabled ? t('静音', 'Mute') : t('开启提示音', 'Enable sound') }}
    </button>
    <span v-if="audioError && !mobile" role="status">{{ audioError }}</span>
  </nav>
  <Teleport v-if="mobile && auth.token && enabled && state.sound && ['/inbox', '/announcements'].includes(route.path)" to="#inbox-sound" defer>
    <button type="button" class="inbox-sound-button" :aria-pressed="audioEnabled" :title="audioError || t('网页开启且完成声音授权后可提示；锁屏不保证播放', 'Requires an open page and sound permission; not guaranteed on a locked screen')" @click="enableAudio">
      {{ audioEnabled ? t('静音', 'Mute') : t('开启提示音', 'Enable sound') }}
    </button>
    <span v-if="audioError" role="status">{{ audioError }}</span>
  </Teleport>
</template>
<style scoped>
.inbox-sound-button{padding:8px 12px;border:1px solid #e5e9e1;border-radius:8px;background:#f6f8f3;color:#50633c;cursor:pointer;font:inherit}
.header-inbox-button{position:relative;display:flex;align-items:center;justify-content:center;width:36px;height:36px;padding:6px;border:0;background:transparent;color:inherit;cursor:pointer;border-radius:6px}.header-inbox-button:hover{color:#73b100}.header-inbox-button:focus-visible{outline:2px solid #73b100;outline-offset:2px}.header-inbox-button b{position:absolute;top:-3px;right:-5px;min-width:16px;padding:0 4px;border-radius:10px;background:#739f32;color:white;font:11px/16px sans-serif}

.support-notifications {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
}
.support-notifications button {
  border: 1px solid #e5eade;
  border-radius: 20px;
  background: #fff;
  color: #527526;
  font-size: 11px;
  padding: 6px 10px;
  cursor: pointer;
}
.support-notifications b {
  display: inline-block;
  background: #739f32;
  color: #fff;
  min-width: 16px;
  border-radius: 9px;
  padding: 0 3px;
  margin-left: 5px;
}
.support-notifications.floating {
  position: fixed;
  right: 16px;
  bottom: 86px;
  z-index: 35;
  max-width: calc(100vw - 32px);
  filter: drop-shadow(0 3px 8px #22341114);
}
.support-notifications.floating.detail {
  bottom: auto;
  top: 90px;
  max-width: 130px;
}
.support-notifications span {
  font-size: 10px;
  background: white;
  padding: 4px;
  color: #a24c37;
}
@media (max-width: 600px) {
  .support-notifications.floating.detail {
    top: 78px;
    right: 14px;
  }
}
</style>
