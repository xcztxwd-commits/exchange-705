<template>
  <aside v-if="affected.length" class="quote-status" role="status" aria-live="polite">
    <details>
      <summary>部分行情暂不可用或报价已过期（{{ affected.length }}）</summary>
      <div v-for="item in affected" :key="item.symbol">
        {{ item.symbol }}：{{ item.status === 'stale' ? '报价已过期' : '行情暂不可用' }}
      </div>
    </details>
  </aside>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useMarketStore } from '@/store/market'
const market = useMarketStore()
const now = ref(Date.now())
const timer = window.setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => window.clearInterval(timer))
const affected = computed(() => Object.keys(market.quoteStatusMap)
  .map(symbol => ({ symbol, status: market.getQuoteStatus(symbol, now.value) }))
  .filter(item => item.status !== 'available'))
</script>
<style scoped>
.quote-status { position: fixed; top: 0; left: 50%; transform: translateX(-50%); z-index: 10000; max-width: 94vw; width: max-content; max-height: 28vh; overflow: auto; padding: 6px 12px; border: 1px solid #d69e2e; border-radius: 4px; background: #fff4cc; color: #613c00; font-size: 12px; }
summary { cursor: pointer; }
</style>
