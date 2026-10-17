<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import request from '@/utils/request'
import { useAuthStore } from '@/store/auth'
import { supportText } from '@/utils/support'
const auth = useAuthStore()
const props = defineProps<{ endpoint: string; label: string; admin?: boolean }>()
const src = ref(''),
  failed = ref(false)
let generation = 0
function clear() {
  if (src.value) URL.revokeObjectURL(src.value)
  src.value = ''
}
watch(
  () => [props.endpoint, auth.token],
  async ([endpoint]) => {
    const version = ++generation
    clear()
    failed.value = false
    try {
      const blob: any = await request.get(endpoint || '', { responseType: 'blob' })
      if (version === generation) src.value = URL.createObjectURL(blob)
    } catch {
      if (version === generation) failed.value = true
    }
  },
  { immediate: true },
)
onUnmounted(() => {
  ++generation
  clear()
})
</script>
<template>
  <a v-if="src" :href="src" target="_blank" rel="noopener" :aria-label="label"
    ><img :src="src" :alt="label" loading="lazy"
  /></a>
  <span v-else role="status">{{ failed ? supportText('图片暂不可用', 'Image unavailable', admin) : '…' }}</span>
</template>
<style scoped>
img {
  display: block;
  max-width: 100%;
  max-height: 280px;
  border-radius: 12px;
  object-fit: contain;
}
a {
  display: block;
}
</style>
