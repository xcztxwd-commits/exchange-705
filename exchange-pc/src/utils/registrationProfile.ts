export type FieldPolicy = { enabled: boolean; required: boolean }
export type RegistrationPolicy = { phone: FieldPolicy; annualIncome: FieldPolicy; currencies: string[]; maxAnnualIncome: string }
export type RegistrationProfile = { countryCode: string; phone: string; annualIncome: string; annualIncomeCurrency: string }

export function parseRegistrationPolicy(value: any): RegistrationPolicy {
  const valid = (field: any) => field && typeof field.enabled === 'boolean'
    && typeof field.required === 'boolean' && (!field.required || field.enabled)
  if (!valid(value?.phone) || !valid(value?.annualIncome) || !Array.isArray(value.currencies)
    || !value.currencies.includes('USD') || value.currencies.some((c: unknown) => typeof c !== 'string' || !/^[A-Z]{3}$/.test(c))
    || String(value.maxAnnualIncome) !== '999999999999.99') throw new Error('注册字段配置不可用')
  return { phone: value.phone, annualIncome: value.annualIncome, currencies: value.currencies, maxAnnualIncome: String(value.maxAnnualIncome) }
}

export function registrationProfilePayload(policy: RegistrationPolicy, input: RegistrationProfile) {
  const result: Record<string, string> = {}
  const phone = input.phone.trim()
  if (policy.phone.enabled) {
    if (phone) {
      let dial = input.countryCode.trim()
      if (/^[1-9][0-9]{0,2}$/.test(dial)) dial = '+' + dial
      if (!/^[+][1-9][0-9]{0,2}$/.test(dial) || !/^[+0-9 ()-]{4,32}$/.test(phone)) throw new Error('手机号格式无效')
      let digits = phone.replace(/[ ()-]/g, '')
      if (digits.startsWith('+')) {
        if (!digits.startsWith(dial)) throw new Error('手机号与国际区号不一致')
        digits = digits.slice(dial.length)
      }
      if (!/^[0-9]{4,14}$/.test(digits) || dial.length - 1 + digits.length < 7 || dial.length - 1 + digits.length > 15)
        throw new Error('手机号格式无效')
      result.countryCode = dial
      result.phone = digits
    } else if (policy.phone.required) throw new Error('请填写手机号')
  }
  const income = input.annualIncome.trim()
  if (policy.annualIncome.enabled) {
    if (income) {
      if (!/^(0|[1-9][0-9]{0,11})(\.[0-9]{1,2})?$/.test(income)
        || Number(income) > Number(policy.maxAnnualIncome)) throw new Error('年收入金额无效（最多两位小数）')
      if (!policy.currencies.includes(input.annualIncomeCurrency)) throw new Error('年收入币种无效')
      result.annualIncome = income
      result.annualIncomeCurrency = input.annualIncomeCurrency
    } else if (policy.annualIncome.required) throw new Error('请填写年收入')
  }
  return result
}

// Only a manual choice blocks late IP defaults; the two selectors are independent.
export function detectedProfileDefaults(current: { countryCode: string; currency: string }, detected: { dialCode: string; currency: string },
  changed: { dial: boolean; currency: boolean }, allowed: string[]) {
  return {
    countryCode: changed.dial ? current.countryCode : detected.dialCode,
    currency: changed.currency ? current.currency : allowed.includes(detected.currency) ? detected.currency : 'USD',
  }
}
