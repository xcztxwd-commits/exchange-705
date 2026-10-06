import { computed, ref, onMounted, onUnmounted } from 'vue'

export type DepositChannel = 'digital' | 'bank'

// Settings endpoints already return enabled channels for the current tenant.
export function useDepositChannels(hasDigital: () => boolean, hasBank: () => boolean) {
  const requestedChannel = ref<DepositChannel>('digital')
  const showDepositTypeTabs = computed(() => hasDigital() && hasBank())
  const depositType = computed({
    get: (): DepositChannel => !hasDigital() ? 'bank' : !hasBank() ? 'digital' : requestedChannel.value,
    set: (channel: DepositChannel) => { requestedChannel.value = channel },
  })
  return { depositType, showDepositTypeTabs }
}

// Deposit pages stay open while an administrator changes channels on another device.
// Refresh only visible, idle deposit forms; never restart or submit a business operation.
export function useDepositChannelRefresh(refresh: () => Promise<unknown>, active: () => boolean = () => true) {
  let pending = false
  let timer: ReturnType<typeof setInterval> | undefined
  const refreshVisible = async () => {
    if (document.visibilityState !== 'visible' || !active() || pending) return
    pending = true
    try { await refresh() }
    catch (error) { console.error('Unable to refresh deposit channels', error) }
    finally { pending = false }
  }
  onMounted(() => {
    timer = setInterval(refreshVisible, 15000)
    window.addEventListener('focus', refreshVisible)
    window.addEventListener('pageshow', refreshVisible)
    document.addEventListener('visibilitychange', refreshVisible)
  })
  onUnmounted(() => {
    clearInterval(timer)
    window.removeEventListener('focus', refreshVisible)
    window.removeEventListener('pageshow', refreshVisible)
    document.removeEventListener('visibilitychange', refreshVisible)
  })
}
