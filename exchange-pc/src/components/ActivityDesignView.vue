<script setup lang="ts">
import SharedActivityDesignView from '../../../exchange-frontend/src/components/ActivityDesignView.vue'

const props = defineProps<{ design: string; locale: string; fallback: string; stage: string; amount: number | string; days: number; busy?: boolean; eligible?: boolean; claimed?: boolean; runAction?: (action: string, target?: string) => Promise<void> }>()
const emit = defineEmits<{ (e: 'action', action: string, target?: string): void }>()
</script>

<template><SharedActivityDesignView v-bind="props" @action="(action, target) => emit('action', action, target)"/></template>
