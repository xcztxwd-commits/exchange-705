import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// Stored profit is gross; fee already includes both charged trading legs.
const records = [
  [284.2953056, 10234.5, -9950.2046944, '-9,950.20'],
  [0.149056, 5.1, -4.950944, '-4.95'],
  [28.51607, 0.70701, 27.80906, '27.81'],
  [-10.10785, 0.26835, -10.3762, '-10.38'],
  [0.3, 0.1, 0.2, '0.20'],
  [0, 3, -3, '-3.00'],
  [7, 0, 7, '7.00'],
]
for (const app of ['exchange-pc', 'exchange-frontend']) {
  const { settledContractProfit, calculateContractProfit } = await import(`../../${app}/src/utils/contract.ts`)
  const { settledShareOrder, shareReturn, shareCopy } = await import(`../../${app}/src/utils/orderShare.ts`)
  const raw = { id: 84, symbol: 'JPY=X', status: 'CLOSED', side: 'BUY', lotSize: 1000,
    openPrice: 157.465, closePrice: 157.5952, quantity: 341.15, margin: 3438.03,
    openTime: '2026-09-23T00:50:20Z', closeTime: '2026-09-23T00:53:49Z' }
  for (const [profit, fee, net, display] of records) {
    const order = Object.freeze({ ...raw, profit, fee, openCommission: fee / 2, closeCommission: fee / 2 })
    assert.equal(settledContractProfit(order), net)
    assert.equal(calculateContractProfit(order, 9999, NaN), net, 'Settled P&L must not use live prices/rates')
    assert.equal(net.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }), display)
    const poster = settledShareOrder(order, 'contract')
    assert.equal(poster.profit, net, 'History and poster use the same net amount')
    assert.equal(shareReturn(poster), net / raw.margin * 100)
    assert.equal(order.profit, profit, 'Never mutate the stored gross P&L or charge fees again')
    assert.equal(order.fee, fee)
    assert.equal(settledShareOrder({ ...order, direction: 'UP', amount: 100 }, 'option').profit, profit, 'Option P&L is already net')
  }
  assert.equal(settledContractProfit({ profit: '0.30', fee: '0.10', lotSize: 1 }), 0.2)
  assert.equal(settledContractProfit({ profit: 7, fee: 30, lotSize: null }), 7, 'Legacy reserved fees were refunded, not charged')
  assert.equal(settledContractProfit({ profit: 7 }), 7)
  for (const invalid of [null, undefined, '', 'bad', Infinity, NaN]) {
    assert.ok(Number.isNaN(settledContractProfit({ ...raw, profit: invalid, fee: 1 })))
    assert.ok(Number.isNaN(settledContractProfit({ ...raw, profit: 1, fee: invalid })))
    assert.throws(() => settledShareOrder({ ...raw, profit: 1, fee: invalid }, 'contract'))
  }
  assert.equal(calculateContractProfit({ ...raw, status: 'OPEN', quantity: 1, openPrice: 100, lotSize: 1, fee: 20 }, 101), 1)
  for (const status of ['PENDING', 'CANCELLED']) assert.equal(calculateContractProfit({ ...raw, status, profit: 7, fee: 30 }, 101), 7)
  assert.match(shareCopy('zh-TW').accounting, /淨盈虧.*不重複扣費/)
  const locale = readFileSync(new URL(`../../${app}/src/store/locale.ts`, import.meta.url), 'utf8')
  assert.match(locale, /openTimeLabel: "開倉時間"/)
  assert.doesNotMatch(locale, /開倉成交時間/)
}
const desktop = readFileSync(new URL('../src/views/DesktopTrade.vue', import.meta.url), 'utf8')
assert.match(desktop, /localeStore\.text\('已實現盈虧', 'Realized P&L'\)/)
assert.doesNotMatch(desktop, /已實現盈虧（不含手續費）|Realized P&L \(excluding fees\)/)
assert.match(desktop, /profit: calculatedProfit/)
assert.match(desktop, /order\.profit >= 0 \? 'text-\[#8cc63f\]' : 'text-\[#ff4d4f\]'/)
for (const component of ['components/TradeOrders.vue', 'advanced/components/trade/TradeOrders.vue']) {
  const source = readFileSync(new URL(`../../exchange-frontend/src/${component}`, import.meta.url), 'utf8')
  assert.match(source, /props\.mode === 'contract' && order\.status === 'CLOSED' \? settledContractProfit\(order\)/)
}
// Keep the display aligned with settlement and the account-history ledger, without DB writes.
const backend = readFileSync(new URL('../../exchange-backend/src/main/java/com/gtcfesk/exchange/trade/ContractOrderService.java', import.meta.url), 'utf8')
assert.match(backend, /order\.getLotSize\(\) == null \? profit : profit\.subtract\(order\.getFee\(\)\)/)
assert.match(backend, /order\.setProfit\(profit\)/)
console.log('PASS: PC/mobile net settled P&L, both fee legs, legacy refunds, poster/option isolation, labels and raw records')
