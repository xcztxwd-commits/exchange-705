<script setup lang="ts">
import ProtectedImage from '@/components/ProtectedImage.vue'
import { computed, nextTick, onBeforeUnmount, ref, useId, watch, type CSSProperties } from 'vue'

type Option = { value: string | number; label: string; description?: string; icon?: string; iconSrc?: string }

const props = withDefaults(defineProps<{
  modelValue: string | number
  options: Option[]
  label: string
  displayLabel?: string
  placeholder?: string
  searchPlaceholder?: string
  emptyText?: string
  searchable?: boolean
  disabled?: boolean
  compact?: boolean
  dark?: boolean
  minMenuWidth?: number
}>(), { placeholder: '—', emptyText: '—', minMenuWidth: 200 })
const emit = defineEmits<{
  (event: 'update:modelValue', value: string | number): void
  (event: 'change', value: string | number): void
}>()

const root = ref<HTMLElement>()
const trigger = ref<HTMLButtonElement>()
const menu = ref<HTMLElement>()
const searchInput = ref<HTMLInputElement>()
const open = ref(false)
const query = ref('')
const activeIndex = ref(-1)
const menuStyle = ref<CSSProperties>({})
const id = useId()
const portalTarget = computed(() => root.value?.closest('dialog') ?? 'body')
const selectedIndex = computed(() => props.options.findIndex(option => option.value === props.modelValue))
const selected = computed(() => props.options[selectedIndex.value])
let disposed = false
const visibleOptions = computed(() => props.searchable && query.value.trim()
  ? props.options.filter(option => `${option.label} ${option.description ?? ''}`.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase()))
  : props.options)

function positionMenu() {
  if (!trigger.value) return
  const bounds = trigger.value.getBoundingClientRect()
  const width = Math.min(Math.max(bounds.width, props.minMenuWidth), window.innerWidth - 24)
  const left = Math.max(12, Math.min(bounds.left, window.innerWidth - width - 12))
  const below = window.innerHeight - bounds.bottom - 16
  const above = bounds.top - 16
  const placeAbove = below < 200 && above > below
  menuStyle.value = {
    left: `${left}px`,
    width: `${width}px`,
    maxHeight: `${Math.min(320, Math.max(96, placeAbove ? above - 8 : below - 8))}px`,
    ...(placeAbove ? { bottom: `${window.innerHeight - bounds.top + 8}px` } : { top: `${bounds.bottom + 8}px` }),
  }
}

function outside(event: PointerEvent) {
  const target = event.target as Node
  if (!root.value?.contains(target) && !menu.value?.contains(target)) close()
}

function close(restoreFocus = false) {
  if (!open.value) return
  open.value = false
  query.value = ''
  document.removeEventListener('pointerdown', outside, true)
  window.removeEventListener('resize', positionMenu)
  window.removeEventListener('scroll', positionMenu, true)
  if (restoreFocus) trigger.value?.focus()
}

async function show() {
  if (props.disabled || open.value) return
  open.value = true
  query.value = ''
  activeIndex.value = Math.max(0, visibleOptions.value.findIndex(option => option.value === props.modelValue))
  await nextTick()
  if (disposed || !open.value) return
  positionMenu()
  document.addEventListener('pointerdown', outside, true)
  window.addEventListener('resize', positionMenu)
  window.addEventListener('scroll', positionMenu, true)
  if (props.searchable) searchInput.value?.focus()
}

function choose(option: Option) {
  emit('update:modelValue', option.value)
  emit('change', option.value)
  close(true)
}

function move(step: number) {
  const count = visibleOptions.value.length
  if (!count) return
  activeIndex.value = (activeIndex.value + step + count) % count
  nextTick(() => document.getElementById(`${id}-${activeIndex.value}`)?.scrollIntoView({ block: 'nearest' }))
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Tab') { close(); return }
  if (event.key === 'Escape' && open.value) { event.preventDefault(); event.stopPropagation(); close(true); return }
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault(); event.stopPropagation()
    if (!open.value) void show()
    move(event.key === 'ArrowDown' ? 1 : -1)
  } else if (open.value && (event.key === 'Home' || event.key === 'End')) {
    event.preventDefault(); event.stopPropagation()
    activeIndex.value = event.key === 'Home' ? 0 : visibleOptions.value.length - 1
  } else if (open.value && (event.key === 'Enter' || (event.key === ' ' && event.target === trigger.value))) {
    event.preventDefault(); event.stopPropagation()
    const option = visibleOptions.value[activeIndex.value]
    if (option) choose(option)
  }
}

watch(query, () => { activeIndex.value = 0 })
watch(() => props.disabled, disabled => { if (disabled) close() })
onBeforeUnmount(() => { disposed = true; close() })
</script>

<template>
  <div ref="root" class="app-select" :class="{ 'is-compact': compact, 'is-dark': dark, 'is-open': open }">
    <button
      ref="trigger"
      type="button"
      class="app-select__trigger"
      role="combobox"
      aria-autocomplete="none"
      aria-haspopup="listbox"
      :aria-label="label"
      :aria-expanded="open"
      :aria-controls="open ? id : undefined"
      :aria-activedescendant="open && !searchable && activeIndex >= 0 ? `${id}-${activeIndex}` : undefined"
      :disabled="disabled"
      @click="open ? close() : show()"
      @keydown="onKeydown"
    >
      <span class="app-select__value" :class="{ 'is-placeholder': !selected }"><ProtectedImage v-if="selected?.iconSrc" class="app-select__icon-image" :src="selected.iconSrc" alt=""/><span v-else-if="selected?.icon" class="app-select__icon">{{ selected.icon }}</span>{{ selected ? (displayLabel ?? selected.label) : placeholder }}</span>
      <svg class="app-select__chevron" viewBox="0 0 20 20" aria-hidden="true"><path d="m5 7.5 5 5 5-5" /></svg>
    </button>
    <Teleport :to="portalTarget">
      <div v-if="open" ref="menu" class="app-select__menu" :class="{ 'is-dark': dark }" :style="menuStyle" @keydown="onKeydown">
        <div v-if="searchable" class="app-select__search">
          <input ref="searchInput" v-model="query" type="search" :placeholder="searchPlaceholder" :aria-label="searchPlaceholder || label" :aria-controls="id" :aria-activedescendant="activeIndex >= 0 ? `${id}-${activeIndex}` : undefined">
        </div>
        <div :id="id" role="listbox" :aria-label="label">
        <button
          v-for="(option, index) in visibleOptions"
          :id="`${id}-${index}`"
          :key="`${option.value}-${index}`"
          type="button"
          class="app-select__option"
          :class="{ 'is-active': index === activeIndex, 'is-selected': option === selected }"
          role="option"
          :aria-selected="option === selected"
          tabindex="-1"
          @mouseenter="activeIndex = index"
          @click="choose(option)"
        >
          <ProtectedImage v-if="option.iconSrc" class="app-select__icon-image" :src="option.iconSrc" alt=""/><span v-else-if="option.icon" class="app-select__icon">{{ option.icon }}</span>
          <span class="app-select__option-text"><strong>{{ option.label }}</strong><small v-if="option.description">{{ option.description }}</small></span>
          <span v-if="option === selected" class="app-select__check" aria-hidden="true">✓</span>
        </button>
        <div v-if="!visibleOptions.length" class="app-select__empty">{{ emptyText }}</div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.app-select { display: inline-block; width: 100%; min-width: 0; vertical-align: middle; }
.app-select__trigger { display: flex; align-items: center; justify-content: space-between; gap: 12px; width: 100%; min-height: 44px; padding: 9px 12px 9px 14px; border: 1px solid #dce4df; border-radius: 11px; background: #fff; color: #25313b; box-shadow: 0 1px 2px #17231b0a; font: inherit; font-size: 14px; font-weight: 500; line-height: 1.35; text-align: left; cursor: pointer; transition: border-color .18s, box-shadow .18s, background .18s; }
.app-select__trigger:hover { border-color: #a8c87b; background: #fcfef9; }
.app-select__trigger:focus-visible, .is-open .app-select__trigger { outline: none; border-color: #85bd00; box-shadow: 0 0 0 3px #85bd0026; }
.app-select__trigger:disabled { opacity: .55; cursor: not-allowed; }
.app-select__value { display: flex; align-items: center; gap: 8px; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.app-select__value.is-placeholder { color: #87939b; }
.app-select__chevron { flex: none; width: 17px; height: 17px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.7; transition: transform .18s; }
.is-open .app-select__chevron { transform: rotate(180deg); }
.is-compact .app-select__trigger { min-height: 30px; padding: 4px 7px; border-radius: 7px; font-size: 12px; }
.app-select__menu { position: fixed; z-index: 5000; overflow-y: auto; overscroll-behavior: contain; padding: 6px; border: 1px solid #e3e9e3; border-radius: 14px; background: #fff; color: #25313b; box-shadow: 0 18px 48px #192b2733, 0 2px 8px #192b2714; }
.app-select__option { display: flex; align-items: center; gap: 10px; width: 100%; min-height: 42px; padding: 9px 11px; border: 0; border-radius: 9px; background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.app-select__option:hover, .app-select__option.is-active { background: #f2f7ea; }
.app-select__option.is-selected { color: #527f00; background: #eef7df; }
.app-select__option-text { display: flex; flex: 1; flex-direction: column; gap: 2px; min-width: 0; }
.app-select__option-text strong { overflow: hidden; text-overflow: ellipsis; font-size: 14px; font-weight: 600; white-space: nowrap; }
.app-select__option-text small { color: #738079; font-size: 12px; line-height: 1.35; }
.app-select__icon { flex: none; font-size: 18px; line-height: 1; }
.app-select__icon-image { flex: none; width: 20px; height: 16px; border-radius: 3px; object-fit: contain; }
.app-select__check { flex: none; font-size: 17px; font-weight: 700; }
.app-select__search { position: sticky; top: -6px; z-index: 1; padding: 4px 4px 8px; background: inherit; }
.app-select__search input { width: 100%; padding: 9px 11px; border: 1px solid #dce4df; border-radius: 9px; background: inherit; color: inherit; font: inherit; outline: none; }
.app-select__search input:focus { border-color: #85bd00; box-shadow: 0 0 0 3px #85bd0026; }
.app-select__empty { padding: 14px; color: #87939b; text-align: center; }
.is-dark.app-select .app-select__trigger, .is-dark.app-select__menu, :global(.dark) .app-select__trigger, :global(.dark) .app-select__menu { border-color: #394352; background: #1c2430; color: #e4e9ed; }
.is-dark.app-select__menu .app-select__option:hover, .is-dark.app-select__menu .app-select__option.is-active, :global(.dark) .app-select__option:hover, :global(.dark) .app-select__option.is-active { background: #303d31; }
.is-dark.app-select__menu .app-select__option.is-selected, :global(.dark) .app-select__option.is-selected { background: #33452b; color: #bce270; }
</style>
