<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AdvancedLayout from '@/advanced/components/AdvancedLayout.vue'
import TradeOrders from '@/advanced/components/trade/TradeOrders.vue'
import TradeSheet from '@/advanced/components/trade/TradeSheet.vue'
import { useLocaleStore } from '@/store/locale'
import request from '@/utils/request'

const route = useRoute(), locale = useLocaleStore()
const text = locale.text
const mainTab = ref<'contract' | 'term'>(route.query.tab === 'term' ? 'term' : 'contract')
const contractTab = ref(route.query.status === 'closed' ? 'history' : route.query.status === 'pending' ? 'pending' : 'positions')
const termTab = ref(route.query.status === 'closed' ? 'history' : 'active')
const catalog = ref<any[]>([]), catalogError = ref(''), showProducts = ref(false)
// Query-only navigation keeps this view mounted; history must restore its filters.
watch([() => route.query.tab, () => route.query.status], ([tab, status]) => {
  if (route.path !== '/orders') return
  mainTab.value = tab === 'term' ? 'term' : 'contract'
  contractTab.value = status === 'closed' ? 'history' : status === 'pending' ? 'pending' : 'positions'
  termTab.value = status === 'closed' ? 'history' : 'active'
  showProducts.value = false
}, { flush: 'sync' })
let disposed = false
const title = computed(() => mainTab.value === 'term' ? text('期限订单', 'Option orders') : contractTab.value === 'history' ? text('历史仓位', 'Position history') : contractTab.value === 'pending' ? text('当前委托', 'Open orders') : text('交易记录', 'Trading records'))
async function loadCatalog() {
  try {
    const result: any = await request.get('/market/all')
    if (disposed) return
    if (!Array.isArray(result.list) || result.success === false) throw new Error(result.message || text('品种资料载入失败', 'Instrument data unavailable'))
    catalog.value = result.list
    catalogError.value = ''
  } catch (error: any) { if (!disposed) catalogError.value = error.message }
}
onMounted(loadCatalog)
onUnmounted(() => { disposed = true })
</script>

<template>
  <AdvancedLayout :nav="true" :title="title">
    <template #actions><button class="product-menu" :aria-label="text('交易类型', 'Product')" @click="showProducts=true">⋯</button></template>
    <section class="orders-page">
      <nav v-if="mainTab === 'term'" class="segments product-tabs" :aria-label="text('交易类型', 'Product')">
        <button type="button" :aria-pressed="false" @click="mainTab = 'contract'">{{ text('合约', 'Contracts') }}</button>
        <button type="button" :aria-pressed="true" @click="mainTab = 'term'">{{ text('期限', 'Options') }}</button>
      </nav>
      <p v-if="catalogError" class="error" role="alert">{{ catalogError }} <button @click="loadCatalog">{{ text('重试', 'Retry') }}</button></p>
      <TradeOrders :mode="mainTab" symbol="" :catalog="catalog" :tab="mainTab === 'contract' ? contractTab : termTab" :revision="0" full-page>
        <template #tabs>
      <nav v-if="mainTab === 'contract'" class="segments" :aria-label="text('订单分类', 'Order category')">
        <button v-for="tab in ['positions', 'history', 'pending']" :key="tab" type="button" :aria-pressed="contractTab === tab" @click="contractTab = tab">{{ tab === 'positions' ? text('当前仓位', 'Positions') : tab === 'history' ? text('历史仓位', 'History') : text('当前委托', 'Pending') }}</button>
      </nav>
      <nav v-else class="segments" :aria-label="text('期限订单分类', 'Option status')">
        <button type="button" :aria-pressed="termTab === 'active'" @click="termTab = 'active'">{{ text('交易中', 'Active') }}</button>
        <button type="button" :aria-pressed="termTab === 'history'" @click="termTab = 'history'">{{ text('已结算', 'Settled') }}</button>
      </nav>
        </template>
      </TradeOrders>
    </section>
    <TradeSheet :open="showProducts" :title="text('交易类型', 'Product')" @close="showProducts=false"><nav class="segments"><button @click="mainTab='contract';showProducts=false">{{ text('合约','Contracts') }}</button><button @click="mainTab='term';showProducts=false">{{ text('期限','Options') }}</button></nav></TradeSheet>
  </AdvancedLayout>
</template>

<style scoped>
.product-menu{width:44px;height:44px;border:0;background:transparent;color:#707780;font-size:20px;cursor:pointer}.orders-page{color:#252a30;background:#fff;min-width:0;line-height:1.45}.segments{display:flex;gap:6px;margin-bottom:20px}.segments button{flex:1;min-width:0;min-height:36px;border:0;border-radius:7px;background:#f5f6f7;color:#707780;font:inherit;font-size:12px;padding:8px 4px;cursor:pointer;overflow-wrap:anywhere}.segments button[aria-pressed=true]{background:#f5f2f7;color:#736582}.error{color:#aa5363;font-size:12px;overflow-wrap:anywhere}.error button{min-height:44px;border:0;border-radius:7px;padding:8px;background:#f5f2f7;color:#736582;font:inherit}button:focus-visible{outline:2px solid #736582;outline-offset:2px}
</style>
