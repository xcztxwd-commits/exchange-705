import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from '@/store/auth'
import request from '@/utils/request'
import { accountMode } from './accountMode'
import { createServerClock, trialState, countdown } from './trialLifecycle'

export const useTrialWallet = defineStore('trial-wallet', () => {
  const auth = useAuthStore(), snapshot = ref<any>(null), tick = ref(performance.now()), loadedAt = ref(0), error = ref('')
  const clock = createServerClock()
  let generation = 0, pending: Promise<void> | null = null, controller: AbortController | null = null
  const identity = () => `${auth.user?.tenantId}:${auth.user?.id}:${auth.token}:${accountMode()}`
  const serverNow = computed(() => clock.now(tick.value))
  const state = computed(() => trialState(snapshot.value, serverNow.value, accountMode()))
  const remaining = computed(() => countdown(state.value.expiresAt, serverNow.value))
  const ready = computed(() => !!auth.token && loadedAt.value > 0 && tick.value - loadedAt.value < 30000)
  function reset() {
    generation++; controller?.abort(); controller = null; pending = null; snapshot.value = null
    clock.reset(); loadedAt.value = 0; error.value = ''; tick.value = performance.now()
  }
  function accept(data: any, started: number, ended = performance.now()) {
    if (data?.success === false || !data || !clock.sync(data.serverNow, started, ended)) throw Error('Invalid wallet snapshot')
    snapshot.value = data; loadedAt.value = ended; tick.value = ended; error.value = ''
  }
  async function refresh() {
    if (!auth.token || !auth.user?.id || document.visibilityState !== 'visible') return
    if (pending) return pending
    const owner = identity(), run = generation, started = performance.now(), abort = new AbortController()
    controller = abort
    pending = (async () => {
      try {
        // Existing wallet request carries eligibility; no expiry endpoint or expiry poller.
        const data: any = await request.get('/user/assets', { signal: abort.signal })
        if (run === generation && owner === identity()) accept(data, started)
      } catch (e: any) {
        if (run === generation && owner === identity()) { loadedAt.value = 0; error.value = e.message || 'Wallet unavailable' }
      } finally { if (run === generation) { pending = null; controller = null } }
    })()
    return pending
  }
  return { snapshot, tick, loadedAt, serverNow, state, remaining, ready, error, reset, refresh, accept }
})
