<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElImageViewer } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import { useProtectedImages } from '../../../exchange-frontend/src/utils/useProtectedImages'
defineOptions({ inheritAttrs: false })
const props = defineProps<{ src?: string; previewSrcList?: string[]; initialIndex?: number; hideOnClickModal?: boolean; previewTeleported?: boolean }>()
const auth = useAuthStore(), previewOpen = ref(false)
const { sources, failed } = useProtectedImages(() => [props.src || ''])
const { sources: previews, failed: previewFailed } = useProtectedImages(() => previewOpen.value
  ? (props.previewSrcList || []).map(value => value === props.src ? sources.value[0] || value : value) : [])
const previewUrls = computed(() => (props.previewSrcList || []).map((value, index) => previews.value[index] || (value === props.src ? sources.value[0] || '' : '')))
watch(() => JSON.stringify([auth.token, props.src, props.previewSrcList]), () => { previewOpen.value = false })
function openPreview() { if (sources.value[0] && props.previewSrcList?.length) previewOpen.value = true }
</script>
<template>
  <el-image v-bind="$attrs" :src="sources[0] || ''" @click="openPreview">
    <template v-for="name in Object.keys($slots).filter(name => !['error', 'placeholder'].includes(name))" #[name]="scope"><slot :name="name" v-bind="scope || {}" /></template>
    <template #placeholder><slot name="placeholder"><span class="protected-image-pending" role="status">加载中…</span></slot></template>
    <template #error>
      <span v-if="!sources[0] && !failed" class="protected-image-pending" role="status">加载中…</span>
      <slot v-else name="error"><span>加载失败</span></slot>
    </template>
  </el-image>
  <ElImageViewer v-if="previewOpen" :url-list="previewUrls" :initial-index="initialIndex || 0"
    :hide-on-click-modal="hideOnClickModal" :teleported="previewTeleported" @close="previewOpen = false">
    <template #viewer-error="{ src }"><span role="status">{{ src || previewFailed ? '加载失败' : '加载中…' }}</span></template>
  </ElImageViewer>
</template>
<style scoped>
.protected-image-pending { display: flex; align-items: center; justify-content: center; width: 100%; height: 100%; color: #909399; background: #f5f7fa; }
</style>
