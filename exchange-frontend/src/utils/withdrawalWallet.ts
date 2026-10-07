import { computed, ref } from 'vue'

export type WalletAccount = 'FUND' | 'CONTRACT' | 'OPTION'
export type WalletBalances = Record<WalletAccount, number>

export const formatWalletBalance = (value: number, locale: string) => value.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export function useWithdrawalWallet(fetchAssets: () => Promise<any>) {
  const withdrawAccount = ref<WalletAccount>('FUND')
  const balances = ref<WalletBalances>({ FUND: 0, CONTRACT: 0, OPTION: 0 })
  const balanceReady = ref(false)
  const selectedBalance = computed(() => balances.value[withdrawAccount.value])

  async function loadBalance() {
    balanceReady.value = false
    try {
      const result = await fetchAssets()
      if (!result || result.success === false) throw new Error('Unable to load wallet balances')
      const next = {
        FUND: Number(result.fundBalance ?? 0),
        CONTRACT: Number(result.contractBalance ?? 0),
        OPTION: Number(result.optionBalance ?? 0),
      }
      if (!Object.values(next).every(Number.isFinite)) throw new Error('Invalid wallet balances')
      balances.value = next
      balanceReady.value = true
    } catch (error) {
      console.error('Unable to load wallet balances:', error)
    }
  }

  return { withdrawAccount, balances, balanceReady, selectedBalance, loadBalance }
}
