<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useLocaleStore } from '@/store/locale'
import { leverageChoices } from '@/utils/contract'

const props = defineProps<{ modelValue: number; max: number; disabled?: boolean; symbol: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: number] }>()
const locale = useLocaleStore()
const dialog = ref<HTMLDialogElement>()
const draft = ref<number | string>(props.modelValue)
const choices = computed(() => leverageChoices(props.max))
const valid = computed(() => Number.isInteger(Number(draft.value)) && Number(draft.value) >= 1 && Number(draft.value) <= props.max)
const title = computed(() => locale.locale === 'zh-TW' ? '調整槓桿' : 'Adjust leverage')
let previousOverflow = ''
let isOpen = false
function open() {
  draft.value = props.modelValue
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  isOpen = true
  dialog.value?.showModal()
}
function restoreScroll() {
  if (isOpen) document.body.style.overflow = previousOverflow
  isOpen = false
}
function close() { dialog.value?.close(); restoreScroll() }
function step(delta: number) { draft.value = Math.max(1, Math.min(props.max, (Number(draft.value) || 1) + delta)) }
function confirm() {
  if (!valid.value) return
  emit('update:modelValue', Number(draft.value))
  close()
}
watch(() => [props.symbol, props.max, props.disabled], close)
onUnmounted(restoreScroll)
</script>

<template>
  <button type="button" class="leverage-trigger" :disabled="disabled || max === 1" aria-haspopup="dialog" @click="open">
    {{ locale.t('leverage') }} <strong>{{ modelValue }}×</strong><span aria-hidden="true">⌄</span>
  </button>
  <Teleport to="body">
    <dialog ref="dialog" class="leverage-dialog" aria-labelledby="leverage-title" @close="restoreScroll" @click="event => { if (event.target === dialog) close() }">
      <form class="leverage-content" @submit.prevent="confirm">
        <div class="sheet-handle" aria-hidden="true"></div>
        <header><h2 id="leverage-title">{{ title }}</h2><button type="button" class="close-button" :aria-label="locale.t('cancelText')" @click="close">×</button></header>
        <p class="range-hint">{{ symbol }} · 1–{{ max }}×</p>
        <div class="leverage-number">
          <button type="button" :disabled="Number(draft) <= 1" :aria-label="`${locale.t('leverage')} −`" @click="step(-1)">−</button>
          <label><input id="contract-leverage" v-model="draft" type="number" inputmode="numeric" min="1" :max="max" step="1" :aria-label="title" autofocus /><span>×</span></label>
          <button type="button" :disabled="Number(draft) >= max" :aria-label="`${locale.t('leverage')} +`" @click="step(1)">+</button>
        </div>
        <div class="leverage-presets">
          <button v-for="value in choices" :key="value" type="button" :aria-pressed="Number(draft) === value" @click="draft = value">{{ value }}×</button>
        </div>
        <p v-if="!valid" class="input-error" role="status">{{ locale.locale === 'zh-TW' ? '請輸入範圍內的整數' : 'Enter a whole number within the range' }} (1–{{ max }})</p>
        <footer><button type="button" class="cancel" @click="close">{{ locale.t('cancelText') }}</button><button type="submit" class="confirm" :disabled="!valid">{{ locale.t('confirmBtnText') }}</button></footer>
      </form>
    </dialog>
  </Teleport>
</template>

<style scoped>
.leverage-trigger { display: inline-flex; align-items: center; justify-content: center; gap: 6px; flex-shrink: 0; min-height: 40px; padding: 8px 12px; border: 1px solid #dce3d3; border-radius: 8px; background: #f8fbf3; color: #263315; font: inherit; font-size: 13px; white-space: nowrap; cursor: pointer; }
.leverage-trigger strong { font-variant-numeric: tabular-nums; }
.leverage-trigger:hover { border-color: #8cc63f; }
button:disabled { cursor: not-allowed; opacity: .45; }
button:focus-visible, input:focus-visible { outline: 2px solid #78aa00; outline-offset: 3px; }
.leverage-dialog { box-sizing: border-box; position: fixed; inset: 0; margin: auto; padding: 0; width: min(440px, calc(100% - 32px)); max-height: calc(100dvh - 32px); border: 0; border-radius: 20px; background: #fff; color: #17202e; box-shadow: 0 20px 80px #0003; }
.leverage-dialog::backdrop { background: #10182880; }
.leverage-content { padding: 24px; }
.leverage-content header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
h2 { font-size: 20px; font-weight: 700; margin: 0; }
.close-button { border: 0; background: none; color: inherit; font-size: 28px; width: 36px; height: 36px; cursor: pointer; }
.range-hint { margin: 6px 0 20px; color: #7b8494; font-size: 13px; }
.leverage-number { display: flex; height: 64px; border: 1px solid #e1e5eb; background: #f7f8fa; border-radius: 10px; overflow: hidden; }
.leverage-number button { width: 58px; flex-shrink: 0; background: transparent; border: 0; color: inherit; font-size: 26px; cursor: pointer; }
.leverage-number label { display: flex; flex: 1; min-width: 0; justify-content: center; align-items: center; border-inline: 1px solid #e1e5eb; font-size: 28px; font-weight: 700; }
.leverage-number input { box-sizing: border-box; width: 80px; min-width: 0; border: 0; background: transparent; color: inherit; text-align: center; font: inherit; appearance: textfield; }
.leverage-number input::-webkit-inner-spin-button { appearance: none; }
.leverage-presets { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin: 16px 0 24px; }
.leverage-presets button { min-height: 40px; border: 1px solid #e1e5eb; border-radius: 8px; background: #f7f8fa; color: inherit; font-size: 14px; cursor: pointer; }
.leverage-presets button[aria-pressed="true"] { border-color: #8cc63f; background: #eff7df; color: #4b7100; font-weight: 700; }
footer { display: flex; gap: 12px; }
footer button { flex: 1; min-height: 48px; border: 0; border-radius: 10px; font: inherit; font-weight: 600; cursor: pointer; }
.cancel { background: #f0f2f5; color: inherit; }.confirm { background: #8cc63f; color: #fff; }.input-error { color: #c33b3b; font-size: 13px; }
.sheet-handle { display: none; }
:global(.dark .leverage-trigger) { background: #202b20; color: #d9edbb; border-color: #3f5231; }
:global(.dark .leverage-dialog) { background: #181c27; color: #edf0f4; }
:global(.dark .leverage-number), :global(.dark .leverage-presets button), :global(.dark .cancel) { background: #242a36; border-color: #3a4250; }
:global(.dark .leverage-number label) { border-color: #3a4250; }
:global(.dark .leverage-presets button[aria-pressed="true"]) { color: #bce582; border-color: #8cc63f; background: #2d3e22; }
@media (max-width: 640px) {
  .leverage-dialog { inset: auto 0 0; width: 100%; max-width: 100%; max-height: 90dvh; margin: 0; border-radius: 20px 20px 0 0; }
  .leverage-content { padding: 12px 20px max(24px, env(safe-area-inset-bottom)); }
  .sheet-handle { display: block; width: 44px; height: 5px; background: #c8cdd4; border-radius: 3px; margin: 0 auto 14px; }
  .leverage-trigger { padding-inline: 10px; font-size: 12px; }
}
</style>
