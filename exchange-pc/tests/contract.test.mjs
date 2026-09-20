import assert from 'node:assert/strict'

for (const app of ['exchange-pc', 'exchange-frontend']) {
  const { contractMargin, calculateContractProfit, contractEquity, leverageLimit, leverageChoices } = await import(`../../${app}/src/utils/contract.ts`)
  assert.equal(leverageLimit(null), 100)
  assert.deepEqual(leverageChoices(30), [1, 2, 5, 10, 20, 25, 30])
  assert.equal(contractMargin(0.01, 1000, 100, 100), 10)
  assert.equal(contractMargin(0.01, 1000, 100, 20), 50)
  assert.equal(contractMargin(0.01, 1000, 105, 100), 10.5)
  assert.equal(contractMargin(0.01, 1000, 100, 0), 0)
  const order = { status: 'OPEN', side: 'BUY', openPrice: 100, quantity: 0.01, leverage: 100, lotSize: 1000, margin: 10, fee: 0.3 }
  assert.equal(calculateContractProfit(order, 101), 10)
  assert.equal(calculateContractProfit({ ...order, leverage: 20 }, 101), 10)
  assert.equal(calculateContractProfit({ ...order, side: 'SELL' }, 101), -10)
  assert.equal(calculateContractProfit({ ...order, lotSize: null, leverage: 10 }, 101), 0.1)
  assert.equal(calculateContractProfit({ ...order, status: 'PENDING' }, 101), 0)
  assert.equal(calculateContractProfit({ ...order, status: 'CLOSED', profit: 7 }, 101), 7)
  assert.equal(contractEquity(1, [{ ...order, profit: -11 }]), 0)
  assert.equal(contractEquity(1, [{ ...order, lotSize: null, profit: -11 }]), 0.30000000000000004)
}
console.log('PC/mobile contract margin, P&L, leverage and equity checks passed')
