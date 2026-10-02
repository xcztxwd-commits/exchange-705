import { watch } from 'vue'
import { useAuthStore } from '@/store/auth'
import { imageLocation } from '../../../exchange-frontend/src/utils/imageLocation'
export function getAudioUrl(value: string | null | undefined): string {
  return imageLocation(value && !value.startsWith('/') && !/^https?:/.test(value) ? '/uploads/audio/' + value : value, location.origin)
}
export async function playProtectedAudio(value: string) {
  const auth = useAuthStore(), token = auth.token
  let src = getAudioUrl(value), owned = false
  if (!src) throw new Error('音频地址无效')
  if (src.startsWith('/api/uploads/audio/')) {
    if (!token) throw new Error('登录已失效')
    const response = await fetch(src, { headers: { Authorization: `Bearer ${token}` }, credentials:'omit', cache:'no-store', redirect:'error' })
    if (!response.ok) throw new Error('音频不可用')
    const blob = await response.blob()
    if (!blob.type.startsWith('audio/') || token !== auth.token) throw new Error('音频或会话无效')
    src = URL.createObjectURL(blob); owned = true
  }
  const audio = new Audio(src); audio.volume = 0.7
  let stopped = false, unwatch = () => {}
  const stop = () => { if(stopped)return;stopped=true;audio.pause();audio.removeAttribute('src');if(owned)URL.revokeObjectURL(src);clearTimeout(timer);unwatch() }
  const timer = setTimeout(stop, 120000)
  unwatch = watch(() => auth.token, stop)
  audio.onended = stop; audio.onerror = stop
  try { await audio.play() } catch (e) { stop(); throw e }
}
