<script setup lang="ts">
import SharedComponent from '../../../exchange-frontend/src/components/SupportNotifications.vue'
const props = withDefaults(defineProps<{ admin?: boolean; enabled?: boolean; inboxTarget?: string }>(), { admin: false, enabled: true })
</script>
<template><SharedComponent v-bind="props" /></template>
