import test from 'node:test'
import assert from 'node:assert/strict'
import { displaySymbol as pc } from '../src/utils/displaySymbol.ts'
import { displaySymbol as mobile } from '../../exchange-frontend/src/utils/displaySymbol.ts'
import { displaySymbol as admin } from '../../exchange-admin/src/utils/displaySymbol.ts'

for (const format of [pc, mobile, admin]) {
  test(`${format === pc ? 'pc' : format === mobile ? 'mobile' : 'admin'} forex labels leave provider codes intact`, () => {
    assert.equal(format('JPY=X'), 'USD/JPY')
    assert.equal(format('HKD=X'), 'USD/HKD')
    assert.equal(format('CAD=X'), 'USD/CAD')
    assert.equal(format('EURUSD=X'), 'EUR/USD')
    assert.equal(format('GBPUSD=X'), 'GBP/USD')
    assert.equal(format('USDJPY=X'), 'USD/JPY')
    assert.equal(format('USDJPY'), 'USD/JPY')
    assert.equal(format('EURGBP=X'), 'EUR/GBP')
    const instrument = { symbol: 'JPY=X', category: 'Forex', baseCurrency: 'JPY', quoteCurrency: 'JPY' }
    assert.equal(format(instrument), 'USD/JPY')
    assert.equal(instrument.symbol, 'JPY=X')
    assert.equal(format({ symbol: 'JPY=X', displayName: 'USD/JPY' }), 'USD/JPY')
    assert.equal(format({ symbol: 'CUSTOM', category: 'Forex', displayName: 'EUR/CHF' }), 'EUR/CHF')
    assert.equal(format({ symbol: 'JPY=X', displayName: 'JPY=X' }), 'USD/JPY')
    assert.equal(format({ symbol: 'USDJPY', category: 'Forex' }), 'USD/JPY')
    assert.equal(format('BTCUSDT'), 'BTCUSDT')
    assert.equal(format('CL=F'), 'CL=F')
  })
}
