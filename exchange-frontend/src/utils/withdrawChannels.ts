import { computed, ref, onMounted } from 'vue'
import { useDepositChannels, useDepositChannelRefresh } from './depositChannels'

export function useWithdrawChannels(fetchChannels: () => Promise<unknown>, active: () => boolean = () => true) {
  const channels = ref({ digital: false, bank: false })
  const withdrawChannelsReady = ref(false)
  const withdrawChannelsError = ref(false)
  const { depositType: withdrawType, showDepositTypeTabs: showWithdrawTypeTabs } = useDepositChannels(
    () => withdrawChannelsReady.value && channels.value.digital,
    () => withdrawChannelsReady.value && channels.value.bank
  )
  const hasWithdrawChannel = computed(() => withdrawChannelsReady.value && (channels.value.digital || channels.value.bank))
  let pending: Promise<void> | undefined
  function loadWithdrawChannels(): Promise<void> {
    if (pending) return pending
    pending = Promise.resolve().then(async () => {
      try {
        const result: any = await fetchChannels()
        if (result?.success === false || typeof result?.digital !== 'boolean' || typeof result?.bank !== 'boolean')
          throw new Error('Invalid withdrawal channels')
        channels.value = { digital: result.digital, bank: result.bank }
        withdrawChannelsReady.value = true
        withdrawChannelsError.value = false
      } catch {
        withdrawChannelsReady.value = false
        withdrawChannelsError.value = true
      } finally { pending = undefined }
    })
    return pending
  }
  onMounted(() => { if (active()) void loadWithdrawChannels() })
  useDepositChannelRefresh(loadWithdrawChannels, active)
  return { withdrawType, showWithdrawTypeTabs, hasWithdrawChannel, withdrawChannelsReady, withdrawChannelsError, loadWithdrawChannels }
}
