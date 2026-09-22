<script setup lang="ts">
import { computed } from 'vue'
import { useLocaleStore } from '@/store/locale'
const props = defineProps<{ percent: number; disabled: boolean; buy: number | null; sell: number | null; precision?: number }>()
const emit = defineEmits<{ change: [value: number] }>()
const locale = useLocaleStore()
const chinese = computed(() => locale.locale === 'zh-TW')
const title = computed(() => chinese.value ? '倉位比例' : 'Position allocation')
const format = (value: number | null) => value == null ? '—' : value.toLocaleString('en-US', { minimumFractionDigits: props.precision ?? 2, maximumFractionDigits: props.precision ?? 2 })
</script>

<template>
  <section class="position-sizing">
    <div class="allocation-label"><label for="position-allocation">{{ title }}</label><output for="position-allocation">{{ percent }}%</output></div>
    <input id="position-allocation" type="range" min="0" max="100" step="1" :value="percent" :disabled="disabled" :aria-label="title" :aria-valuetext="`${percent}%`" :style="{ '--allocation': `${percent}%` }" @input="emit('change', Number(($event.target as HTMLInputElement).value))" />
    <div class="allocation-presets"><button v-for="value in [0, 25, 50, 75, 100]" :key="value" type="button" :disabled="disabled" :aria-pressed="percent === value" @click="emit('change', value)">{{ value }}%</button></div>
    <p class="sizing-hint">{{ chinese ? '按可用資金計算，含保證金及手續費' : 'Share of available funds, including margin and fees' }}</p>
    <slot />
    <div class="liquidation-estimates">
      <div><span>{{ chinese ? '預計強平價（買入）' : 'Est. liquidation (Buy)' }}</span><strong data-testid="liquidation-buy">{{ format(buy) }}</strong></div>
      <div><span>{{ chinese ? '預計強平價（賣出）' : 'Est. liquidation (Sell)' }}</span><strong data-testid="liquidation-sell">{{ format(sell) }}</strong></div>
    </div>
    <p class="sizing-hint">{{ chinese ? '按帳戶整體權益估算；假設其他品種價格及匯率不變。' : 'Account equity estimate; other markets and FX rates held constant.' }}<br v-if="buy == null || sell == null" /><span v-if="buy == null || sell == null">{{ chinese ? '—：資料不足、數量無效或無正值強平價。' : '—: missing data, invalid size or no positive liquidation price.' }}</span></p>
  </section>
</template>

<style scoped>
.position-sizing { color: #273142; font-size: 13px; margin-top: 16px; }
.allocation-label { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; font-size: 14px; font-weight: 600; }
.allocation-label output { color: #65962d; font-variant-numeric: tabular-nums; }
input[type="range"] { width: 100%; height: 32px; margin: 0; padding: 0; cursor: pointer; appearance: none; background: transparent; touch-action: pan-y; }
input[type="range"]::-webkit-slider-runnable-track { height: 5px; border-radius: 5px; background: linear-gradient(to right, #8cc63f var(--allocation), #e5e8ec var(--allocation)); }
input[type="range"]::-webkit-slider-thumb { appearance: none; width: 20px; height: 20px; margin-top: -7.5px; border-radius: 50%; border: 3px solid #8cc63f; background: #fff; box-shadow: 0 1px 3px #0002; }
input[type="range"]::-moz-range-track { height: 5px; border-radius: 5px; background: #e5e8ec; }
input[type="range"]::-moz-range-progress { height: 5px; background: #8cc63f; }
input[type="range"]::-moz-range-thumb { width: 15px; height: 15px; border: 3px solid #8cc63f; border-radius: 50%; background: #fff; }
.allocation-presets { display: flex; justify-content: space-between; gap: 4px; }
.allocation-presets button { min-height: 32px; padding: 0 4px; border: 0; background: transparent; color: #7b8494; font: inherit; font-size: 12px; cursor: pointer; }
.allocation-presets button[aria-pressed="true"] { color: #65962d; font-weight: 700; }
.sizing-hint { margin: 6px 0 14px; color: #7b8494; font-size: 11px; line-height: 1.65; }
.liquidation-estimates { padding: 14px 12px; margin-top: 12px; border-radius: 10px; background: #f7f8fa; }
.liquidation-estimates div { display: flex; justify-content: space-between; align-items: baseline; gap: 10px; line-height: 1.6; }
.liquidation-estimates div + div { margin-top: 8px; }
.liquidation-estimates strong { flex-shrink: 0; font-variant-numeric: tabular-nums; font-weight: 600; }
button:disabled, input:disabled { cursor: not-allowed; opacity: .4; }
button:focus-visible, input:focus-visible { outline: 2px solid #78aa00; outline-offset: 3px; }
:global(.dark .position-sizing) { color: #d7dce5; }
:global(.dark .liquidation-estimates) { background: #181c27; }
:global(.dark .allocation-label output), :global(.dark .allocation-presets button[aria-pressed="true"]) { color: #8cc63f; }
</style>
