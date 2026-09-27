<template>
  <aside v-if="affected.length" class="quote-status" role="status" aria-live="polite">
    <details>
      <summary>部分数据源连接失败（{{ affected.length }}）</summary>
      <div v-for="item in affected" :key="item.symbol">
        {{ displaySymbol(item.symbol) }}：数据源连接失败，正在重试
      </div>
    </details>
  </aside>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { useMarketStore } from '@/store/market'
import { showSourceConnectionWarning } from '@/utils/marketWebSocket'
import { displaySymbol } from '@/utils/displaySymbol'
const market = useMarketStore()
const affected = computed(() => Object.keys(market.quoteStatusMap)
  .filter(symbol => showSourceConnectionWarning(market.quoteStatusMap[symbol]))
  .map(symbol => ({ symbol })))
</script>
<style scoped>
.quote-status { position: fixed; top: 0; left: 50%; transform: translateX(-50%); z-index: 10000; max-width: 94vw; width: max-content; max-height: 28vh; overflow: auto; padding: 6px 12px; border: 1px solid #d69e2e; border-radius: 4px; background: #fff4cc; color: #613c00; font-size: 12px; }
summary { cursor: pointer; }
</style>
