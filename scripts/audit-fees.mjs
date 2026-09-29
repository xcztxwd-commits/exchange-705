// Offline audit of captured public configuration. Does not place orders or change settings.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const directory = resolve(process.argv[2] || resolve(root, 'reports/fee-audit-20260928'))
const read = name => JSON.parse(readFileSync(resolve(directory, name), 'utf8').replace(/^\uFEFF/, ''))
const symbols = read('symbols-api.json')
const quotes = new Map(read('quotes-api.json').map(row => [row.symbol, row.quote.data]))
const apps = await Promise.all(['exchange-frontend', 'exchange-pc'].map(async app => ({
  app, ...await import(new URL(`../${app}/src/utils/contract.ts`, import.meta.url)),
})))
// JS previews use doubles; cancellation in cross-rate price differences needs a sub-cent tolerance.
const close = (a, b) => assert.ok(Math.abs(a - b) <= Math.max(1, Math.abs(b)) * 1e-9, `${a} != ${b}`)

// Deterministic, non-live rates. Test the arithmetic, not provider availability for these currencies.
const rates = { USD: 1, EUR: 1.1, GBP: 1.25, AUD: 0.65, NZD: 0.6, CAD: 1 / 1.25,
  CHF: 1 / 0.9, JPY: 1 / 150, CNH: 1 / 7.2, SGD: 1 / 1.35, HKD: 1 / 8,
  MXN: 1 / 18, ZAR: 1 / 18, NOK: 1 / 10, SEK: 1 / 10, PLN: 1 / 4,
  DKK: 1 / 7, TRY: 1 / 35, THB: 1 / 35 }
let checks = 0
for (const app of apps) for (const base of Object.keys(rates)) for (const quote of Object.keys(rates)) {
  const price = rates[base] / rates[quote]
  close(app.contractMargin(0.37, 100000, price, 50, rates[quote]), 0.37 * 100000 / 50 * rates[base])
  for (const side of ['BUY', 'SELL']) {
    const order = { status: 'OPEN', quantity: 0.37, lotSize: 100000, leverage: 50, openPrice: price, side }
    close(app.calculateContractProfit(order, price + 0.001, rates[quote]), (side === 'BUY' ? 1 : -1) * 0.001 * 0.37 * 100000 * rates[quote])
  }
  checks += 3
}

const observations = symbols.map(symbol => {
  const quote = quotes.get(symbol.symbol)
  assert.ok(quote && Number(quote.price) > 0 && Number(quote.quoteToUsdRate) > 0, `Missing captured quote: ${symbol.symbol}`)
  const lots = 0.01, leverage = 100
  const margin = apps[0].contractMargin(lots, Number(symbol.lotSize), quote.price, leverage, quote.quoteToUsdRate)
  close(margin, apps[1].contractMargin(lots, Number(symbol.lotSize), quote.price, leverage, quote.quoteToUsdRate))
  const fx = symbol.sourceCategory === 'Forex'
  const row = {
    symbol: symbol.symbol, category: symbol.category, unitsPerLot: symbol.lotSize,
    capturedPrice: quote.price, quoteToUsdRate: quote.quoteToUsdRate,
    lots, leverage, marginUsd: margin,
    recordedSingleFeeUsd: lots * Number(symbol.feeMultiplier),
    contractStepEnforcedByServer: false,
  }
  if (fx) Object.assign(row, {
    referenceStandardLotUnits: 100000, standardLotMatches: Number(symbol.lotSize) === 100000,
    referenceRawOpenFeeUsd: lots * 3.5, referenceRawCloseFeeUsd: lots * 3.5,
    referenceRawRoundTripFeeUsd: lots * 7,
    currentFeePer100000BaseUnitsUsd: Number(symbol.feeMultiplier) * 100000 / Number(symbol.lotSize),
    referenceMarginUsd: lots * 100000 / leverage * (symbol.baseCurrency === 'USD' ? 1 : quote.price),
  })
  return row
})
console.log(JSON.stringify({ note: 'Characterization only; passing arithmetic does not certify pricing policy. Snapshots are not current quotes.',
  frontendArithmeticChecks: checks, observedSymbolCount: symbols.length, observations }, null, 2))
