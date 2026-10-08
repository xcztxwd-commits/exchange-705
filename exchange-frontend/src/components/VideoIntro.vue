<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import request from '@/utils/request'
import { useLocaleStore } from '@/store/locale'

const emit = defineEmits<{ (event: 'close'): void }>()
const locale = useLocaleStore()
const dialog = ref<HTMLDialogElement>(), video = ref<HTMLVideoElement>()
const url = ref(''), loading = ref(true), failed = ref(false)
let generation = 0
async function load() {
  const current = ++generation
  video.value?.pause(); url.value = ''; loading.value = true; failed.value = false
  try {
    const result: any = await request.get('/user/video-intro', { params: { language: locale.locale } })
    if (current !== generation) return
    const source = String(result.url || '')
    if (source && !source.startsWith('/api/uploads/videos/') && !/^https?:\/\//i.test(source)) throw new Error('Invalid video URL')
    url.value = source
  } catch { if (current === generation) failed.value = true }
  finally { if (current === generation) loading.value = false }
}
watch(() => locale.locale, load)
onMounted(() => { dialog.value?.showModal(); void load() })
onBeforeUnmount(() => { generation++; video.value?.pause() })
</script>

<template>
  <dialog ref="dialog" class="video-intro" :aria-label="locale.t('videoIntro')" @cancel.prevent="emit('close')"
    @click="event => { if (event.target === event.currentTarget) emit('close') }">
    <section>
      <header><h2>{{ locale.t('videoIntro') }}</h2><button type="button" :aria-label="locale.t('cancel')" @click="emit('close')">×</button></header>
      <p v-if="loading" role="status">{{ locale.t('loading') }}</p>
      <p v-else-if="failed" role="alert">{{ locale.t('loadFailed') }}</p>
      <video v-else-if="url" ref="video" :key="url" :src="url" controls playsinline preload="metadata" @error="failed = true" />
      <p v-else>{{ locale.t('noData') }}</p>
    </section>
  </dialog>
</template>

<style scoped>
.video-intro { padding: 0; border: 0; border-radius: 14px; width: min(840px, 92vw); max-height: 90vh; color: #25313d; background: #fff; }
.video-intro::backdrop { background: #0009; }
section { padding: 20px; }
header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
h2 { margin: 0; font-size: 18px; }
button { border: 0; background: transparent; color: inherit; cursor: pointer; font-size: 28px; padding: 0 8px; }
video { display: block; width: 100%; max-height: 70vh; background: #000; }
p { padding: 24px 0; text-align: center; }
</style>
