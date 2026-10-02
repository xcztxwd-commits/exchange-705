<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import request from '@/utils/request'
import { useLocaleStore } from '@/store/locale'
import { captchaText } from '@/utils/captchaText'

const props = defineProps<{ disabled?: boolean; desktop?: boolean }>()
const emit = defineEmits<{ (event: 'ready', ready: boolean): void }>()
const locale = useLocaleStore()
const t = (key: Parameters<typeof captchaText>[1]) => captchaText(locale.locale, key)
const image = ref(''), id = ref(''), answer = ref(''), busy = ref(false)
const error = ref<'invalid' | 'unavailable' | 'limited' | 'expired' | ''>('')
const now = ref(Date.now()), expiresAt = ref(0), blockedUntil = ref(0)
let session = '', generation = 0, controller: AbortController | undefined
let timer: ReturnType<typeof setInterval> | undefined
const wait = computed(() => Math.max(0, Math.ceil((blockedUntil.value - now.value) / 1000)))
const expired = computed(() => !!image.value && expiresAt.value <= now.value)
const ready = computed(() => !!id.value && !busy.value && !expired.value && wait.value === 0 && /^[A-Za-z0-9]{4}$/.test(answer.value))
watch(ready, value => emit('ready', value), { immediate: true })
const message = computed(() => wait.value ? `${t('limited')} (${wait.value}s)` : error.value ? t(error.value) : expired.value ? t('expired') : '')

function initializeSession() {
  // Per-tab opaque challenge namespace, not an authentication credential.
  // Never store an answer. Network/IP limits still apply if an attacker rotates this identifier.
  try { session = sessionStorage.getItem('registration-captcha-session') || '' } catch { /* memory-only fallback */ }
  if (!/^[a-f0-9]{32}$/.test(session)) {
    session = Array.from(crypto.getRandomValues(new Uint8Array(16)), n => n.toString(16).padStart(2, '0')).join('')
    try { sessionStorage.setItem('registration-captcha-session', session) } catch { /* memory-only fallback */ }
  }
}
function cooldown(e: any) {
  const seconds = Number(e?.retryAfter)
  blockedUntil.value = Date.now() + (Number.isFinite(seconds) && seconds > 0 ? seconds : 60) * 1000
  now.value = Date.now()
  error.value = 'limited'
}
async function refresh() {
  if (busy.value || wait.value || props.disabled) return
  await load()
}
async function load() {
  const version = ++generation
  controller?.abort()
  controller = new AbortController()
  busy.value = true; id.value = ''; answer.value = ''; image.value = ''; error.value = ''
  try {
    if (!session) initializeSession()
    const result: any = await request.post('/auth/captcha', { captchaSession: session }, { signal: controller.signal })
    if (version !== generation) return
    if (!/^[a-f0-9]{32}$/.test(result.captchaId) || !result.image?.startsWith('data:image/png;base64,') || result.expiresIn !== 120) throw new Error('Invalid CAPTCHA response')
    id.value = result.captchaId; image.value = result.image
    expiresAt.value = Date.now() + result.expiresIn * 1000; now.value = Date.now()
  } catch (e: any) {
    if (version !== generation) return
    if (e.status === 429) cooldown(e)
    else error.value = 'unavailable'
  } finally { if (version === generation) busy.value = false }
}
function submission() {
  if (!ready.value) { error.value = expired.value ? 'expired' : 'invalid'; return null }
  const fields = { captchaSession: session, captchaId: id.value, captchaCode: answer.value.toUpperCase() }
  id.value = '' // Cannot submit the same challenge twice, even during the redirect delay.
  return fields
}
async function registrationFailed(e: any) {
  id.value = ''; answer.value = ''
  if (e.status === 429) { cooldown(e); e.message = t('limited'); return }
  if (e.status === 503) { error.value = 'unavailable'; e.message = t('unavailable'); return }
  if (e.code === 'CAPTCHA_INVALID' || e.code === 'CAPTCHA_REPLACED') e.message = t('invalid')
  await load() // No registration auto-retry; preserve every other form field.
}
onMounted(() => { void load(); timer = setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => { generation++; controller?.abort(); if (timer) clearInterval(timer) })
defineExpose({ submission, registrationFailed })
</script>

<template>
  <div class="captcha-group" :class="{ desktop }">
    <label for="registration-captcha-code" class="captcha-label">{{ t('label') }}</label>
    <div class="captcha-row">
      <input id="registration-captcha-code" v-model="answer" :disabled="disabled || busy || !id || expired || wait > 0" :placeholder="t('placeholder')" maxlength="4" autocomplete="off" autocapitalize="characters" :spellcheck="false" :aria-invalid="error === 'invalid'" aria-describedby="registration-captcha-message" @input="error = ''" />
      <button type="button" class="captcha-image" :disabled="disabled || busy || wait > 0" :aria-label="t('refresh')" @click="refresh">
        <img v-if="image" :src="image" :alt="t('label')" width="120" height="52" @error="id = ''; error = 'unavailable'" />
        <span v-else>{{ busy ? t('loading') : t('retry') }}</span>
      </button>
    </div>
    <button type="button" class="captcha-refresh" :disabled="disabled || busy || wait > 0" @click="refresh">{{ t('refresh') }}</button>
    <div v-if="message" id="registration-captcha-message" class="captcha-message" role="status">{{ message }}</div>
  </div>
</template>

<style lang="scss" scoped>
@import '@/styles/variables.scss';
.captcha-group { margin-bottom: 18px; }
.captcha-label { display: block; color: $primary-color; font-size: 15px; font-weight: 700; margin-bottom: 10px; }
.captcha-row { display: grid; grid-template-columns: minmax(0, 1fr) 120px; gap: 10px; }
.captcha-row input { width: 100%; min-width: 0; height: 52px; box-sizing: border-box; background: #f6f6f6; border: 1px solid transparent; border-radius: $radius-sm; padding: 14px 16px; font: inherit; font-size: 16px; color: $text-color; }
.captcha-row input:focus { outline: none; border-color: $primary-color; box-shadow: 0 0 0 3px rgba(133,189,0,.1); }
.captcha-row input[aria-invalid=true] { border-color: #c33434; }
.captcha-image { padding: 0; height: 52px; overflow: hidden; border: 1px solid #e3e7d8; border-radius: $radius-sm; background: #f6f8ef; color: $subtext-color; cursor: pointer; }
.captcha-image img { display: block; width: 100%; height: 100%; object-fit: contain; }
.captcha-refresh { display: block; margin-left: auto; padding: 8px 0; border: 0; background: transparent; color: $subtext-color; font-size: 12px; cursor: pointer; }
button:disabled { cursor: not-allowed; opacity: .6; }
.captcha-message { font-size: 13px; line-height: 1.5; color: #c33434; }
@media (max-width: 360px) { .captcha-row { grid-template-columns: minmax(0, 1fr) 110px; } }
@media (pointer: coarse) { .captcha-refresh { min-height: 44px; } }
.desktop { margin-bottom: 0; }
.desktop .captcha-label { color: #4b5563; font-size: 14px; font-weight: 500; margin-bottom: 8px; }
.desktop .captcha-row input { background: #f9fafb; border-color: #e5e7eb; border-radius: 8px; color: #374151; height: 46px; font-size: 14px; }
.desktop .captcha-image { height: 46px; border-radius: 8px; }
.desktop .captcha-row input:focus { border-color: #8cc63f; }
/* Keep the entire ancestor selector global: Vue otherwise drops the descendant selector. */
:global(html.dark .captcha-group.desktop .captcha-label) { color: #c6ccc6; }
:global(html.dark .captcha-group.desktop .captcha-row input) { background: var(--night-raised, #2b2f33); color: var(--night-text, #e8ebe8); border-color: #e5e7eb; }
:global(html.dark .captcha-group.desktop .captcha-row input::placeholder) { color: #9ca3af; }
:global(html.dark .captcha-group.desktop .captcha-row input:focus) { border-color: var(--night-green, #9bc45b); }
:global(html.dark .captcha-group.desktop .captcha-row input[aria-invalid=true]) { border-color: #c33434; }
:global(html.dark .captcha-group.desktop .captcha-refresh) { color: var(--night-muted, #929b94); }
</style>
