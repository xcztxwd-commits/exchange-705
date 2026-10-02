<template>
  <aside v-if="affected.length" class="quote-status" role="status" aria-live="polite">
    <details>
      <summary>{{ locale.text('部分行情來源連線失敗（{count}）', 'Some price feeds are unavailable ({count})', { count: affected.length }) }}</summary>
      <div v-for="item in affected" :key="item.symbol">
        {{ locale.text('{symbol}：行情來源連線失敗，正在重試', '{symbol}: price feed unavailable; retrying', { symbol: displaySymbol(item.symbol) }) }}
      </div>
    </details>
  </aside>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { useLocaleStore } from '@/store/locale'
const locale = useLocaleStore()
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
@media (max-width: 1199px) {
  .quote-status { position: relative; top: auto; left: auto; transform: none; width: auto; max-width: none; margin: 8px 12px; z-index: auto; }
}
</style>
