<script setup lang="ts">
import { currencyOptions } from '@/utils/fiatCurrency'
defineProps<{ modelValue: string }>()
defineEmits<{ (event: 'update:modelValue', value: string): void }>()
</script>
<template>
  <select aria-label="币种" class="currency-picker" :value="modelValue" @change="$emit('update:modelValue', ($event.target as HTMLSelectElement).value)">
    <option v-for="item in currencyOptions" :key="item.code" :value="item.code">{{ item.label }}</option>
  </select>
</template>
<style scoped>
.currency-picker { padding: 8px 12px; margin: 8px 0; border: 1px solid #dcdfe6; border-radius: 6px; background: #fff; color: #303133; max-width: 100%; font-size: 14px; }
</style>
