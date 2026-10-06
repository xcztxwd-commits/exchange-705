import { computed, ref } from 'vue'

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
