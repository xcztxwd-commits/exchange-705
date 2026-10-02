import { ref, watch } from 'vue'
import { useAuthStore } from '@/store/auth'
import { imageLocation, privateImagePath } from './imageLocation'

/** Per-component object URLs; never persist a private image or fall back to its unprotected URL. */
export function useProtectedImages(input: () => string[]) {
  const auth = useAuthStore(), sources = ref<string[]>([]), failed = ref(false)
  watch(() => [auth.token, ...input()], async (_, __, onCleanup) => {
    const controller = new AbortController(), owned: string[] = [], token = auth.token
    let active = true
    sources.value = []; failed.value = false
    onCleanup(() => { active = false; controller.abort(); owned.forEach(url => URL.revokeObjectURL(url)); sources.value = [] })
    try {
      const urls = await Promise.all(input().map(async value => {
        const normalized = imageLocation(value, location.origin)
        const path = privateImagePath(normalized, location.origin)
        if (!path) return normalized
        // Anonymous reads are still server-authorized: only actual public references may succeed.
        const response = await fetch(path, { headers: token ? { Authorization: `Bearer ${token}` } : {}, credentials: 'omit', cache: 'no-store', redirect: 'error', signal: controller.signal })
        if (!response.ok) throw new Error('私有附件不可用')
        const blob = await response.blob()
        if (!/^image\/(png|jpeg|gif|webp|bmp)$/i.test(blob.type)) throw new Error('附件不是受支持图片')
        if (!active || auth.token !== token) return ''
        const url = URL.createObjectURL(blob); owned.push(url); return url
      }))
      if (active && auth.token === token) sources.value = urls
    } catch { if (active) failed.value = true }
  }, { immediate: true })
  return { sources, failed }
}
