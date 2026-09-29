<script setup lang="ts">
import SharedComponent from '../../../exchange-frontend/src/components/SupportThread.vue'
const props = withDefaults(defineProps<{ id: number; admin?: boolean; canReply?: boolean; canImage?: boolean; disabled?: boolean; offline?: string }>(), { admin: false, canReply: true, canImage: true, disabled: false, offline: '' })
</script>
<template><SharedComponent v-bind="props" /></template>
