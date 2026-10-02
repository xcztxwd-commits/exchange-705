<script setup lang="ts">
import protectionBack from '@/advanced/assets/orders-trade/protection-back.svg'
import { useLocaleStore } from '@/store/locale'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
const locale = useLocaleStore()
const props = defineProps<{ open: boolean; title: string; busy?: boolean; page?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
const viewportHeight = ref(0)
const viewportTop = ref(0)
let previousOverflow = ''
let locked = false
let disposed = false
let resizeFrame = 0
function resize() {
  viewportHeight.value = window.visualViewport?.height ?? window.innerHeight
  viewportTop.value = window.visualViewport?.offsetTop ?? 0
  cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(() => {
    const active = document.activeElement as HTMLElement | null
    if (dialog.value?.open && active && dialog.value.contains(active) && active.matches('input, textarea, select')) {
      active.scrollIntoView({ block: 'center', behavior: 'auto' })
    }
  })
}
function unlock() {
  if (locked) document.body.style.overflow = previousOverflow
  locked = false
}
watch(() => props.open, async open => {
  await nextTick()
  if (disposed) return
  if (open) {
    resize()
    previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    locked = true
    dialog.value?.showModal()
  } else { dialog.value?.close(); unlock() }
}, { immediate: true })
onMounted(() => {
  window.visualViewport?.addEventListener('resize', resize)
  window.visualViewport?.addEventListener('scroll', resize)
})
onBeforeUnmount(() => {
  disposed = true
  cancelAnimationFrame(resizeFrame)
  dialog.value?.close()
  unlock()
  window.visualViewport?.removeEventListener('resize', resize)
  window.visualViewport?.removeEventListener('scroll', resize)
})
</script>

<template>
  <Teleport to="body">
    <dialog ref="dialog" class="advanced-ui trade-sheet" :class="{'page-sheet':page}" :aria-label="title" :style="{ '--viewport-height': viewportHeight + 'px', '--viewport-top': viewportTop + 'px' }" @cancel.prevent="!busy && emit('close')" @click="event => event.target === dialog && !busy && emit('close')">
      <div v-if="!page" class="sheet-handle"></div>
      <header><h2>{{ title }}</h2><button type="button" :aria-label="locale.text('關閉', 'Close')" :disabled="busy" @click="emit('close')"><img v-if="page" :src="protectionBack" alt="" /><span v-else>×</span></button></header>
      <div class="sheet-body" :inert="busy || undefined"><slot /></div>
      <footer v-if="$slots.footer"><slot name="footer" /></footer>
    </dialog>
  </Teleport>
</template>

<style scoped>
.trade-sheet { --line: #e9edef; --advanced-text: #252a30; --advanced-accent: #736582; --advanced-muted: #707780; box-sizing: border-box; position: fixed; top: auto; left: 0; right: 0; bottom: max(0px, calc(100dvh - var(--viewport-height) - var(--viewport-top))); margin: 0 auto; width: min(100%, 560px); max-width: 100%; max-height: min(88dvh, calc(var(--viewport-height) - 12px)); border: 0; padding: 10px 16px max(16px, env(safe-area-inset-bottom)); border-radius: 12px 12px 0 0; background: #fff; color: #252a30; overflow: hidden; }
.trade-sheet[open] { display: flex; flex-direction: column; }
.trade-sheet::backdrop { background: #17202e70; }
.sheet-handle { flex-shrink: 0; width: 44px; height: 5px; border-radius: 8px; background: #d5d9e0; margin: 0 auto 4px; }
header { display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; border-bottom: 1px solid #e6e8ed; margin-bottom: 12px; }
h2 { margin: 0; font-size: 17px; }
header button { border: 0; background: transparent; font-size: 28px; width: 44px; height: 44px; cursor: pointer; }
.sheet-body { min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding-bottom: 8px; }
footer { flex-shrink: 0; padding-top: 10px; border-top: 1px solid #e6e8ed; }
.page-sheet{top:var(--viewport-top);bottom:auto;height:calc(var(--viewport-height) - 72px - env(safe-area-inset-bottom));max-height:calc(var(--viewport-height) - 72px - env(safe-area-inset-bottom));border-radius:0;padding-top:0}.page-sheet::backdrop{background:transparent}.page-sheet header{position:relative;height:56px;margin-bottom:20px;border:0;justify-content:center}.page-sheet h2{font-weight:500;font-size:17px}.page-sheet header button{position:absolute;left:-10px;display:grid;place-items:center}.page-sheet header img{width:24px;height:24px}.page-sheet footer{border:0;padding-top:0}.page-sheet .sheet-body{padding-bottom:0}
</style>
