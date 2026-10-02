<script setup lang="ts">
import { computed } from 'vue'
import BusinessSelect from './BusinessSelect.vue'
import { currencyOptions } from '@/utils/fiatCurrencyOptions'
import { useLocaleStore } from '@/store/locale'
defineProps<{modelValue:string}>()
const emit=defineEmits<{(event:'update:modelValue',value:string):void}>()
const locale=useLocaleStore()
const options=computed(()=>currencyOptions(locale.locale))
</script>
<template><BusinessSelect :model-value="modelValue" :options="options" :label="locale.t('currency')" @update:model-value="emit('update:modelValue',String($event))" /></template>
