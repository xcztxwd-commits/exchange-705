import assert from 'node:assert/strict'

for (const app of ['exchange-pc', 'exchange-frontend']) {
  const { contractMargin, calculateContractProfit, contractEquity, leverageLimit, leverageChoices, quantityFromAllocation, estimateLiquidationPrice } = await import(`../../${app}/src/utils/contract.ts`)
  assert.equal(leverageLimit(null), 100)
  assert.equal(leverageLimit(100, false), 1)
  assert.deepEqual(leverageChoices(100), [1, 5, 10, 20, 50, 100])
  assert.deepEqual(leverageChoices(30), [1, 5, 10, 20, 30])
  assert.equal(contractMargin(0.01, 1000, 100, 100), 10)
  assert.equal(contractMargin(0.01, 1000, 100, 20), 50)
  assert.equal(contractMargin(0.01, 1000, 105, 100), 10.5)
  assert.equal(contractMargin(0.01, 1000, 100, 0), 0)
  assert.equal(contractMargin(2, 10, 150, 10, 0.01), 3)
  assert.ok(Number.isNaN(contractMargin(2, 10, 150, 10, NaN)))
  assert.equal(quantityFromAllocation(10000, 50, 2500, 0), 2)
  assert.equal(quantityFromAllocation(10000, 100, 2500, 30), 3.95)
  assert.equal(quantityFromAllocation(10000, 0, 2500, 30), 0)
  assert.equal(quantityFromAllocation(1, 100, 2500, 30), 0)
  assert.equal(quantityFromAllocation(100, 50, NaN, 30), 0)
  assert.equal(quantityFromAllocation(100, 50, 1, -1), 0)
  for (const available of [0.3, 1, 7.77, 99, 10000]) {
    for (const margin of [0.1, 7.3, 100, 333.33333333]) {
      const quantity = quantityFromAllocation(available, 100, margin, 0.3)
      assert.ok(quantity * margin + quantity * 0.3 <= available)
    }
  }
  const draft = { symbol: 'BTCUSDT', side: 'BUY', quantity: 2, lotSize: 1, price: 50000, fee: 0, conversionRate: 1 }
  assert.equal(estimateLiquidationPrice(10000, [], draft), 45000)
  assert.equal(estimateLiquidationPrice(10000, [], { ...draft, side: 'SELL' }), 55000)
  assert.equal(estimateLiquidationPrice(10000, [], { ...draft, quantity: 4 }), 47500)
  assert.equal(estimateLiquidationPrice(10000, [], { ...draft, fee: 60 }), 45030)
  const position = { symbol: 'BTCUSDT', side: 'BUY', quantity: 1, lotSize: 1, leverage: 20, openPrice: 49000, currentPrice: 50000, margin: 1000, fee: 30, conversionRate: 1 }
  assert.equal(estimateLiquidationPrice(9000, [position], draft), 50000 - 11000 / 3)
  assert.equal(estimateLiquidationPrice(9000, [{ ...position, symbol: 'OTHER' }], draft), 44500)
  assert.equal(estimateLiquidationPrice(9000, [{ ...position, side: 'SELL', quantity: 2 }], draft), null)
  assert.equal(estimateLiquidationPrice(10000, [], { ...draft, quantity: 0 }), null)
  assert.equal(estimateLiquidationPrice(10000, [], { ...draft, price: 10 }), null)
  assert.equal(estimateLiquidationPrice(10000, [{ ...position, currentPrice: NaN }], draft), null)
  assert.equal(estimateLiquidationPrice(10000, [], { ...draft, conversionRate: NaN }), null)
  assert.equal(estimateLiquidationPrice(10000, [], { ...draft, conversionRate: 2 }), 47500)
  // Legacy orders retain refundable fees and use their leverage as contract units.
  assert.equal(estimateLiquidationPrice(9000, [{ ...position, lotSize: null, leverage: 1 }], draft), 50000 - 11030 / 3)
  const order = { status: 'OPEN', side: 'BUY', openPrice: 100, quantity: 0.01, leverage: 100, lotSize: 1000, margin: 10, fee: 0.3 }
  assert.equal(calculateContractProfit(order, 101), 10)
  assert.equal(calculateContractProfit(order, 101, 100), 1000)
  assert.ok(Number.isNaN(calculateContractProfit(order, 101, NaN)))
  assert.ok(Number.isNaN(contractEquity(100, [{ ...order, profit: NaN }])))
  assert.equal(calculateContractProfit({ ...order, leverage: 20 }, 101), 10)
  assert.equal(calculateContractProfit({ ...order, side: 'SELL' }, 101), -10)
  assert.equal(calculateContractProfit({ ...order, lotSize: null, leverage: 10 }, 101), 0.1)
  assert.equal(calculateContractProfit({ ...order, status: 'PENDING' }, 101), 0)
  assert.equal(calculateContractProfit({ ...order, status: 'CLOSED', profit: 7 }, 101), 7)
  assert.equal(contractEquity(1, [{ ...order, profit: -11 }]), 0)
  assert.equal(contractEquity(1, [{ ...order, lotSize: null, profit: -11 }]), 0.30000000000000004)
}
console.log('PC/mobile contract margin, P&L, leverage and equity checks passed')
