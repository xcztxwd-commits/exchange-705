import assert from 'node:assert/strict'
import test from 'node:test'
import { currencies as mobileCodes, currencyOptions as mobileOptions } from '../../exchange-frontend/src/utils/fiatCurrencyOptions.ts'
import { currencies as desktopCodes, currencyOptions as desktopOptions } from '../src/utils/fiatCurrencyOptions.ts'

test('fiat codes stay stable while labels follow the selected language', () => {
  assert.deepEqual(mobileCodes, desktopCodes)
  for (const options of [mobileOptions, desktopOptions]) {
    const english = options('en')
    const traditional = options('zh-TW')
    assert.deepEqual(english.map(option => option.value), traditional.map(option => option.value))
    assert.deepEqual(english.map(option => option.value), mobileCodes)
    assert.equal(english[0].label, 'USD · US Dollar')
    assert.match(traditional.find(option => option.value === 'CNY').label, /人民幣/)
    assert.notEqual(options('ja')[0].label, english[0].label)
    assert.notEqual(options('ko')[0].label, english[0].label)
  }
})
