<script setup lang="ts">
import SharedComponent from '../../../exchange-frontend/src/components/SupportImage.vue'
const props = defineProps<{ endpoint: string; label: string }>()
</script>
<template><SharedComponent v-bind="props" /></template>
