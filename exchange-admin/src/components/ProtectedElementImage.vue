<script setup lang="ts">
import { useProtectedImages } from '../../../exchange-frontend/src/utils/useProtectedImages'
defineOptions({ inheritAttrs: false })
const props = defineProps<{ src?: string; previewSrcList?: string[] }>()
const { sources } = useProtectedImages(() => [props.src || '', ...(props.previewSrcList || [])])
</script>
<template><el-image v-bind="$attrs" :src="sources[0] || ''" :preview-src-list="sources.slice(1).filter(Boolean)"><template v-for="(_,name) in $slots" #[name]="scope"><slot :name="name" v-bind="scope||{}" /></template></el-image></template>
