<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import request from '@/utils/request'
import AppSelect from '@/components/AppSelect.vue'
import { useLocaleStore } from '@/store/locale'
import { callingCodeCountries } from '@/utils/callingCodeCountries'
import { visitorRegion, initVisitorRegion } from '@/utils/visitorRegion'
import { detectedProfileDefaults, parseRegistrationPolicy, registrationProfilePayload, type RegistrationPolicy } from '@/utils/registrationProfile'

defineProps<{ desktop?: boolean }>()
const locale = useLocaleStore()
const chinese = computed(() => locale.locale.startsWith('zh'))
const words = computed(() => chinese.value ? {
  income: '年收入', dial: '国际区号', currency: '年收入币种',
  unavailable: '注册字段配置不可用',
} : {
  income: locale.locale === 'ja' ? '年収' : 'Annual income', dial: 'Country calling code', currency: 'Income currency',
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
const changed = { dial: false, currency: false }
const bundledCountries = new Set([...Object.values(callingCodeCountries), 'ca', 'kz'])
const dialOptions = computed(() => [...new Set([...Object.keys(callingCodeCountries), countryCode.value])]
  .filter(code => /^[+][1-9][0-9]{0,2}$/.test(code)).map(code => {
    const country = code === visitorRegion.value.dialCode
      ? visitorRegion.value.countryCode.toLowerCase() : callingCodeCountries[code] || ''
    // ponytail: uncommon IP-derived regions use emoji; bundle their SVG if Windows coverage expands.
    const bundled = bundledCountries.has(country)
    return { value: code, label: code,
      iconSrc: bundled ? `${import.meta.env.BASE_URL}img/language-flags/${country}.svg` : undefined,
      icon: !bundled && /^[a-z]{2}$/.test(country)
        ? String.fromCodePoint(...[...country.toUpperCase()].map(char => 127397 + char.charCodeAt(0))) : undefined,
    }
  }))
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
  <div class="registration-profile" :class="{ desktop }">
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="policy?.phone.enabled" class="field">
      <label for="registration-phone">{{ locale.t('phoneNumber') }}<span v-if="policy.phone.required" class="required"> *</span></label>
      <div class="inline">
        <AppSelect class="dial" :model-value="countryCode" :options="dialOptions" :label="words.dial"
          searchable @update:model-value="selectDial" />
        <input id="registration-phone" v-model="phone" type="tel" inputmode="tel" autocomplete="tel-national"
          maxlength="32" :aria-required="policy.phone.required" :placeholder="locale.t('enterPhoneNumber')" />
      </div>
    </div>
    <div v-if="policy?.annualIncome.enabled" class="field">
      <label for="registration-income">{{ words.income }}<span v-if="policy.annualIncome.required" class="required"> *</span></label>
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
label { display: block; margin-bottom: 10px; font-size: 15px; font-weight: 700; color: #111; }
.required { color: #ef4444; }
.inline { display: flex; gap: 8px; align-items: stretch; }
.inline :deep(.app-select__trigger) { height: 100%; }
.dial { flex: 0 0 124px; }
.currency { flex: 0 0 130px; }
input { min-width: 0; width: 100%; box-sizing: border-box; padding: 14px 16px; border: 1px solid transparent;
  border-radius: 12px; background: #f6f6f6; color: #111; font: inherit; font-size: 15px; }
input::placeholder { color: #9ca3af; }
input:focus { outline: none; border-color: #85bd00; box-shadow: 0 0 0 3px rgb(133 189 0 / 10%); }
.desktop { margin-bottom: 0; }
.desktop label { color: #4b5563; font-size: 14px; font-weight: 500; margin-bottom: 8px; }
.desktop input { padding: 12px 16px; border-color: #e5e7eb; border-radius: 8px; background: #f9fafb; color: #374151; font-size: 14px; }
:global(html.dark .registration-profile .field label) { color: #fff; }
:global(html.dark .registration-profile .field input) { background: var(--night-raised, #2b2f33); color: var(--night-text, #e8ebe8); border-color: #e5e7eb; }
:global(html.dark .registration-profile .field input:focus) { border-color: var(--night-green, #9bc45b); }
[role=alert] { color: #c62828; }
@media (max-width: 390px) { .currency { flex-basis: 106px; } }
</style>
