<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useProtectedImages } from '../utils/useProtectedImages'

const props = withDefaults(defineProps<{ src?: string | null; name: string; size?: number }>(), { size: 48 })
const { sources, failed } = useProtectedImages(() => [props.src || ''])
const imageError = ref(false)
watch(() => props.src, () => { imageError.value = false })
const initial = computed(() => (Array.from(props.name.trim())[0] || '?').toLocaleUpperCase())
</script>
<template>
  <span class="user-avatar" :style="{ width: size + 'px', height: size + 'px', fontSize: Math.round(size * .38) + 'px' }" role="img" :aria-label="name">
    <img v-if="sources[0] && !failed && !imageError" :src="sources[0]" alt="" @error="imageError = true" />
    <span v-else aria-hidden="true">{{ initial }}</span>
  </span>
</template>
<style scoped>
.user-avatar{display:inline-flex;align-items:center;justify-content:center;flex-shrink:0;overflow:hidden;border-radius:50%;background:#ecf3e1;color:#52752c;font-weight:600;vertical-align:middle}.user-avatar img{display:block;width:100%;height:100%;object-fit:cover}
</style>
