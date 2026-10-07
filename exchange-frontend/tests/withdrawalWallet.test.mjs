import test from 'node:test'
import assert from 'node:assert/strict'
import { formatWalletBalance, useWithdrawalWallet } from '../src/utils/withdrawalWallet.ts'

test('USD wallet display hides floating-point residue and uses the active locale', () => {
  assert.equal(formatWalletBalance(2410.000000000024, 'en-US'), '2,410.00')
  assert.equal(formatWalletBalance(0, 'zh-TW'), '0.00')
  assert.equal(formatWalletBalance(1234.567891234, 'de-DE'), '1.234,57')
})

test('display formatting preserves full balances used for withdrawal and transfer validation', async () => {
  const amounts = { FUND: 2.410000000000024, CONTRACT: 1234.567891234, OPTION: 0.00000001 }
  const wallet = useWithdrawalWallet(async () => ({ success: true, fundBalance: amounts.FUND, contractBalance: amounts.CONTRACT, optionBalance: amounts.OPTION }))
  await wallet.loadBalance()
  assert.equal(wallet.balanceReady.value, true)
  for (const [account, amount] of Object.entries(amounts)) {
    wallet.withdrawAccount.value = account
    formatWalletBalance(wallet.selectedBalance.value, 'en-US')
    assert.equal(wallet.selectedBalance.value, amount)
    assert.equal(wallet.balances.value[account], amount)
  }
})
