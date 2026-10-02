import assert from 'node:assert/strict'
import { parseRegistrationPolicy, registrationProfilePayload, detectedProfileDefaults } from '../src/utils/registrationProfile.ts'
import { regionDefaults, currencyForCountry } from '../src/utils/visitorRegion.ts'
import { parseRegistrationPolicy as pcPolicy, registrationProfilePayload as pcPayload } from '../../exchange-pc/src/utils/registrationProfile.ts'

const policy = parseRegistrationPolicy({
  phone: { enabled: true, required: true }, annualIncome: { enabled: true, required: true },
  currencies: ['USD', 'EUR', 'SGD', 'JPY'], maxAnnualIncome: 999999999999.99,
})
const form = { countryCode: '+65', phone: '8123-4567', annualIncome: '1234.50', annualIncomeCurrency: 'SGD' }
assert.deepEqual(registrationProfilePayload(policy, form), { countryCode: '+65', phone: '81234567', annualIncome: '1234.50', annualIncomeCurrency: 'SGD' })
assert.deepEqual(pcPayload(pcPolicy(policy), form), registrationProfilePayload(policy, form))
for (const altered of [
  { phone: '' }, { countryCode: '+000' }, { phone: '12' }, { phone: '+4481234567' },
  { annualIncome: '-1' }, { annualIncome: '1.234' }, { annualIncome: '1000000000000' }, { annualIncomeCurrency: 'ZZZ' },
]) assert.throws(() => registrationProfilePayload(policy, { ...form, ...altered }))
assert.throws(() => parseRegistrationPolicy({ ...policy, phone: { enabled: false, required: true } }))
assert.deepEqual(registrationProfilePayload({ ...policy, phone: { enabled: false, required: false }, annualIncome: { enabled: false, required: false } }, form), {})
assert.equal(currencyForCountry('FR'), 'EUR')
assert.equal(regionDefaults({ success: true, country_code: 'SG', calling_code: '65' }).currency, 'SGD')
assert.equal(regionDefaults({ success: true, country_code: 'JP', calling_code: '81', currency: { code: 'JPY' } }).dialCode, '+81')
assert.equal(regionDefaults({ success: false, country_code: 'JP' }), null)
assert.deepEqual(detectedProfileDefaults({ countryCode: '+44', currency: 'GBP' }, { dialCode: '+65', currency: 'SGD' },
  { dial: true, currency: true }, ['USD', 'GBP', 'SGD']), { countryCode: '+44', currency: 'GBP' })
assert.deepEqual(detectedProfileDefaults({ countryCode: '+1', currency: 'USD' }, { dialCode: '+65', currency: 'SGD' },
  { dial: false, currency: false }, ['USD', 'SGD']), { countryCode: '+65', currency: 'SGD' })
console.log('registrationFields.test.mjs PASS')
