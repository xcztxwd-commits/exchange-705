<script setup lang="ts">
import { useProtectedImages } from '../utils/useProtectedImages'
defineOptions({ inheritAttrs: false })
const props = defineProps<{ src?: string }>()
const { sources, failed } = useProtectedImages(() => [props.src || ''])
</script>
<template><img v-bind="$attrs" :src="sources[0] || undefined" :aria-busy="!sources.length && !failed" :data-image-unavailable="failed || undefined" /></template>
