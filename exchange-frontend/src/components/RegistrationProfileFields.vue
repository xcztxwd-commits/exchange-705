<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import request from '@/utils/request'
import AppSelect from '@/components/AppSelect.vue'
import { useLocaleStore } from '@/store/locale'
import { captchaText } from '@/utils/captchaText'
import { visitorRegion, initVisitorRegion } from '@/utils/visitorRegion'
import { detectedProfileDefaults, parseRegistrationPolicy, registrationProfilePayload, type RegistrationPolicy } from '@/utils/registrationProfile'

const locale = useLocaleStore()
const chinese = computed(() => locale.locale.startsWith('zh'))
const words = computed(() => chinese.value ? {
  income: '年收入', dial: '国际区号', currency: '年收入币种', manual: '手动输入区号',
  unavailable: '注册字段配置不可用',
} : {
  income: 'Annual income', dial: 'Country calling code', currency: 'Income currency', manual: 'Enter calling code manually',
  unavailable: 'Registration fields unavailable',
})
const englishErrors: Record<string, string> = {
  '请填写手机号': 'Phone number is required', '手机号格式无效': 'Invalid phone number',
  '手机号与国际区号不一致': 'Phone number does not match country calling code',
  '请填写年收入': 'Annual income is required', '年收入金额无效（最多两位小数）': 'Invalid annual income (up to 2 decimal places)',
  '年收入币种无效': 'Invalid income currency',
}
const policy = ref<RegistrationPolicy | null>(null)
const error = ref('')
const countryCode = ref('+1')
const phone = ref('')
const annualIncome = ref('')
const currency = ref('USD')
const customDial = ref(false)
const changed = { dial: false, currency: false }
const commonDials = ['+1', '+7', '+20', '+27', '+30', '+31', '+32', '+33', '+34', '+39', '+41', '+44', '+45', '+46',
  '+47', '+49', '+52', '+55', '+60', '+61', '+62', '+63', '+64', '+65', '+66', '+81', '+82', '+84', '+86',
  '+90', '+91', '+92', '+94', '+98', '+212', '+234', '+351', '+352', '+353', '+358', '+380', '+852', '+853',
  '+855', '+856', '+880', '+886', '+961', '+962', '+964', '+965', '+966', '+971', '+972', '+974', '+975']
const dialOptions = computed(() => [...new Set([...commonDials, countryCode.value])].filter(code => /^[+][1-9][0-9]{0,2}$/.test(code))
  .map(code => ({ value: code, label: code })))
const currencyOptions = computed(() => {
  const names = new Intl.DisplayNames([locale.locale || 'en'], { type: 'currency' })
  return (policy.value?.currencies || []).map(code => ({ value: code, label: code + ' · ' + (names.of(code) || code) }))
})
function detected() {
  const next = detectedProfileDefaults({ countryCode: countryCode.value, currency: currency.value }, visitorRegion.value, changed, policy.value?.currencies || ['USD'])
  countryCode.value = next.countryCode
  currency.value = next.currency
}
watch(visitorRegion, detected)
onMounted(async () => {
  try {
    policy.value = parseRegistrationPolicy(await request.get('/auth/register'))
    detected()
  } catch (e: any) { error.value = chinese.value ? (e?.message || words.value.unavailable) : words.value.unavailable }
  void initVisitorRegion()
})
function selectDial(value: string | number) { countryCode.value = String(value); changed.dial = true }
function selectCurrency(value: string | number) { currency.value = String(value); changed.currency = true }
function payload() {
  if (!policy.value) throw new Error(error.value || '注册字段配置不可用')
  try {
    return registrationProfilePayload(policy.value, {
      countryCode: countryCode.value, phone: phone.value, annualIncome: annualIncome.value, annualIncomeCurrency: currency.value,
    })
  } catch (e: any) { throw new Error(chinese.value ? e.message : (englishErrors[e.message] || words.value.unavailable)) }
}
defineExpose({ payload, ready: computed(() => !!policy.value) })
</script>

<template>
  <div class="registration-profile">
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="policy?.phone.enabled" class="field">
      <label for="registration-phone">{{ locale.t('phoneNumber') }}{{ policy.phone.required ? ' *' : ` (${captchaText(locale.locale, 'optional')})` }}</label>
      <div class="inline">
        <AppSelect class="dial" :model-value="countryCode" :options="dialOptions" :label="words.dial"
          searchable @update:model-value="selectDial" />
        <input id="registration-phone" v-model="phone" type="tel" inputmode="tel" autocomplete="tel-national"
          maxlength="32" :aria-required="policy.phone.required" :placeholder="locale.t('enterPhoneNumber')" />
      </div>
      <button type="button" class="link" @click="customDial = !customDial">{{ words.manual }}</button>
      <input v-if="customDial" v-model="countryCode" :aria-label="words.manual" placeholder="+1" maxlength="4"
        @input="changed.dial = true" />
    </div>
    <div v-if="policy?.annualIncome.enabled" class="field">
      <label for="registration-income">{{ words.income }}{{ policy.annualIncome.required ? ' *' : ` (${captchaText(locale.locale, 'optional')})` }}</label>
      <div class="inline">
        <input id="registration-income" v-model="annualIncome" type="text" inputmode="decimal" autocomplete="off"
          :aria-required="policy.annualIncome.required" placeholder="0.00" />
        <AppSelect class="currency" :model-value="currency" :options="currencyOptions" :label="words.currency"
          searchable @update:model-value="selectCurrency" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.registration-profile { margin-bottom: 18px; }
.field { margin-bottom: 18px; }
label { display:block; margin-bottom: 10px; font-size: 15px; font-weight: 700; color: #85bd00; }
.inline { display: flex; gap: 8px; align-items: stretch; }
.dial { flex: 0 0 92px; }
.currency { flex: 0 0 130px; }
input { min-width: 0; width:100%; padding: 12px 14px; border: 1px solid #dce4df; border-radius: 9px;
  background: #f6f6f6; color: #25313b; font: inherit; font-size: 15px; }
input:focus-visible { outline: 2px solid #85bd00; }
.link { margin-top: 5px; padding: 2px 0; border: 0; background: none; color: #597f00; text-decoration: underline; cursor: pointer; }
[role=alert] { color: #c62828; }
@media (max-width: 390px) { .currency { flex-basis: 106px; } }
</style>
