<script setup lang="ts">
import { computed } from 'vue'
import AppSelect from '@/components/AppSelect.vue'
import { useLocaleStore } from '@/store/locale'
import { currencyOptions } from '@/utils/fiatCurrencyOptions'
defineProps<{ modelValue: string }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: string): void }>()
const locale = useLocaleStore()
const options = computed(() => currencyOptions(locale.locale))
</script>
<template>
  <AppSelect class="currency-picker" :model-value="modelValue" :options="options" :label="locale.t('currency')" :min-menu-width="240" @update:model-value="emit('update:modelValue', String($event))" />
</template>
<style scoped>
.currency-picker { width: min(100%, 248px); margin: 8px 0; }
</style>
