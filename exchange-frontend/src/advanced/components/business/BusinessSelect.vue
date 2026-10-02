<script setup lang="ts">
defineProps<{ modelValue: string | number; options: { value: string | number; label: string; description?: string }[]; label?: string; placeholder?: string }>()
const emit = defineEmits<{ (event: 'update:modelValue', value: string | number): void }>()
function change(event: Event, options: { value: string | number }[]) {
  const value = (event.target as HTMLSelectElement).value
  emit('update:modelValue', options.find(option => String(option.value) === value)?.value ?? value)
}
</script>
<template><select :aria-label="label" :value="modelValue" @change="change($event, options)"><option v-if="placeholder" value="">{{ placeholder }}</option><option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}{{ option.description ? ` · ${option.description}` : '' }}</option></select></template>
