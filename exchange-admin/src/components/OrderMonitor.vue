<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Close } from '@element-plus/icons-vue'
import { useOrderMonitorStore } from '@/store/orderMonitor'
import { useAccountTable } from '@/utils/useAccountTable'
import { useLiveContractOrders } from '@/utils/useLiveContractOrders'
import { access, can } from '@/utils/access'
import { displaySymbol } from '@/utils/displaySymbol'
import { formatPrice } from '@/utils/formatPrice'
import type { AccountMode } from '@/utils/accountTableData'

const monitor = useOrderMonitorStore(), accountTable = useAccountTable()
const allowed = computed(() => can('orders:view'))
const paused = computed(() => !monitor.visible || !allowed.value)
const modes = ref<AccountMode[]>(['REAL', 'DEMO'])
useLiveContractOrders(computed(() => monitor.orders.filter(order => order.kind === 'contract')), ref('contract'), modes, paused, accountTable.read)
useLiveContractOrders(computed(() => monitor.orders.filter(order => order.kind === 'option')), ref('option'), modes, paused, accountTable.read, 'option')
watch(allowed, value => { if (!value && access.loaded && !access.error) monitor.clear() })

const panel = ref<HTMLElement>()
const position = ref({ x: Math.max(8, window.innerWidth - 336), y: window.innerHeight - 8 })
let drag: { id: number; x: number; y: number; left: number; top: number } | undefined
function moveTo(x: number, y: number) {
  const bounds = panel.value?.getBoundingClientRect()
  position.value = {
    x: Math.max(8, Math.min(x, window.innerWidth - (bounds?.width || 320) - 8)),
    y: Math.max(8, Math.min(y, window.innerHeight - (bounds?.height || 64) - 8)),
  }
}
const fit = () => moveTo(position.value.x, position.value.y)
function startDrag(event: PointerEvent) {
  if (event.button !== 0 || (event.target as HTMLElement).closest('button')) return
  drag = { id: event.pointerId, x: event.clientX, y: event.clientY, left: position.value.x, top: position.value.y }
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  event.preventDefault()
}
function moveDrag(event: PointerEvent) {
  if (drag?.id === event.pointerId) moveTo(drag.left + event.clientX - drag.x, drag.top + event.clientY - drag.y)
}
function keyboardMove(event: KeyboardEvent) {
  const shifts: Record<string, [number, number]> = { ArrowLeft: [-20, 0], ArrowRight: [20, 0], ArrowUp: [0, -20], ArrowDown: [0, 20] }
  const shift = shifts[event.key]
  if (!shift || event.target !== event.currentTarget) return
  event.preventDefault(); moveTo(position.value.x + shift[0], position.value.y + shift[1])
}
function close() {
  monitor.visible = false; drag = undefined
  document.querySelector<HTMLButtonElement>('.view-order-monitor')?.focus()
}
watch(() => [monitor.visible, monitor.orders.length], async () => {
  await nextTick(); fit()
  if (monitor.visible) panel.value?.focus({ preventScroll: true })
})
onMounted(() => window.addEventListener('resize', fit))
onBeforeUnmount(() => window.removeEventListener('resize', fit))

const statusText = (row: any) => row.deleted ? '已删除' : ({ OPEN: '持仓中', PENDING: '挂单中', TRADING: '交易中', CLOSED: '已平仓', CANCELLED: '已取消' } as Record<string, string>)[row.status] || row.status
const active = (row: any) => !row.deleted && ['OPEN', 'PENDING', 'TRADING'].includes(row.status)
const money = (value: any) => value == null || value === '' || !Number.isFinite(Number(value)) ? '—' : Number(value).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const profitClass = (row: any, value: any) => row.liveAvailable === false || value == null ? 'muted' : Number(value) >= 0 ? 'profit' : 'loss'
const updatedAt = (row: any) => row.liveUpdatedAt ? new Date(row.liveUpdatedAt).toLocaleTimeString('zh-CN', { hour12: false }) : '等待刷新'
</script>

<template>
  <Teleport to="body">
    <section v-if="monitor.visible && allowed" ref="panel" class="order-monitor" role="dialog" aria-modal="false" aria-labelledby="order-monitor-title" tabindex="-1"
      :style="{ left: `${position.x}px`, top: `${position.y}px` }" @keydown.esc.stop="close">
      <header class="monitor-header" tabindex="0" aria-label="移动订单监控窗口，使用方向键或拖动标题"
        @pointerdown="startDrag" @pointermove="moveDrag" @pointerup="drag = undefined" @pointercancel="drag = undefined" @lostpointercapture="drag = undefined" @keydown="keyboardMove">
        <h3 id="order-monitor-title">订单监控 <span>{{ monitor.orders.length }}</span></h3>
        <span class="monitor-live" title="每秒刷新，拖动标题移动">实时</span>
        <el-button v-permission="'orders:view'" :icon="Close" circle text size="small" aria-label="关闭监控窗口" title="关闭窗口，保留监控列表" @pointerdown.stop @click="close" />
      </header>
      <div class="monitor-body">
        <div v-if="!monitor.orders.length" class="monitor-empty"><p>暂无监控订单</p><span>在订单操作栏点击“监控”添加</span></div>
        <article v-for="row in monitor.orders" :key="row.monitorKey" class="monitor-order" :data-monitor-key="row.monitorKey">
          <div class="order-heading"><strong>{{ displaySymbol(row) }}</strong><span class="realm" :class="{ demo: row.accountMode === 'DEMO' }">{{ row.accountMode === 'DEMO' ? '模拟' : '真实' }}</span><span class="order-status">{{ statusText(row) }}</span></div>
          <div class="order-meta"><span>{{ row.kind === 'contract' ? '合约' : '期货' }} #{{ row.id }} · {{ row.kind === 'contract' ? (row.side === 'BUY' ? '买入' : '卖出') : (row.direction === 'UP' ? '买涨' : '买跌') }}</span><span class="order-owner" :title="row.userEmail">{{ row.userEmail || (row.userId == null ? '未绑定用户' : `用户 ${row.userId}`) }}</span></div>
          <dl class="order-prices">
            <div><dt>开仓价</dt><dd>{{ formatPrice(row.openPrice) }}</dd></div>
            <div><dt>当前价</dt><dd>{{ formatPrice(row.status === 'CLOSED' ? row.closePrice : row.currentPrice) }}</dd></div>
            <div><dt>平仓价</dt><dd>{{ formatPrice(row.closePrice) }}</dd></div>
            <div class="order-profit"><dt>{{ row.kind === 'option' && active(row) ? '预计盈亏 (USD)' : '当前盈亏 (USD)' }}</dt><dd :class="profitClass(row, row.profit)">{{ money(row.profit) }}</dd></div>
          </dl>
          <div v-if="row.kind === 'contract'" class="order-costs"><span>净盈亏 <b :class="profitClass(row, row.netProfit)">{{ money(row.netProfit) }}</b></span><span>手续费 {{ money(row.fee) }}</span></div>
          <p v-if="active(row) && row.liveAvailable === false" class="unavailable" role="status">行情暂不可用，显示最后获取值</p>
          <div class="order-footer"><span>{{ active(row) ? updatedAt(row) : '已结束' }}</span><el-button v-permission="'orders:view'" link type="danger" size="small" @click="monitor.remove(row.monitorKey)">关闭监控</el-button></div>
        </article>
      </div>
    </section>
  </Teleport>
</template>

<style scoped>
.order-monitor { position: fixed; z-index: 2800; width: 320px; max-width: calc(100vw - 16px); max-height: min(420px, calc(100vh - 16px)); display: flex; flex-direction: column; color: #303133; background: #fff; border: 1px solid #dce3ea; border-radius: 8px; box-shadow: 0 8px 24px rgb(29 46 65 / 16%); overflow: hidden; outline: none; }
.monitor-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 6px 10px; flex-shrink: 0; background: #f5f8fc; border-bottom: 1px solid #e6eaf0; cursor: move; user-select: none; touch-action: none; }
.monitor-header:focus-visible { outline: 2px solid #409eff; outline-offset: -2px; }
.monitor-header h3 { margin: 0; font-size: 14px; }
.monitor-header h3 span { margin-left: 6px; color: #409eff; }
.monitor-live { margin-left: auto; color: #329761; font-size: 11px; }
.monitor-body { padding: 6px; overflow-y: auto; min-height: 0; }
.monitor-empty { padding: 16px 6px; text-align: center; color: #909399; font-size: 12px; }
.monitor-empty p { color: #606266; font-size: 13px; }
.monitor-order { padding: 8px; border: 1px solid #e6eaf0; border-radius: 5px; }
.monitor-order + .monitor-order { margin-top: 6px; }
.order-heading { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.order-heading strong { font-size: 13px; }
.realm { font-size: 10px; padding: 2px 4px; border-radius: 3px; color: #35755a; background: #edf8f2; }
.realm.demo { color: #9b6a20; background: #fff5e5; }
.order-status { margin-left: auto; color: #606266; font-size: 11px; }
.order-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; color: #909399; font-size: 11px; white-space: nowrap; }
.order-owner { min-width: 0; color: #606266; overflow: hidden; text-overflow: ellipsis; }
.order-prices { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin: 10px 0 6px; }
.order-prices dt { color: #909399; font-size: 11px; margin-bottom: 3px; }
.order-prices dd { margin: 0; font-size: 13px; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.order-prices .order-profit { grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.order-profit dt { margin-bottom: 0; }
.order-profit dd { font-size: 15px; }
.order-costs { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 6px; color: #909399; font-size: 11px; overflow-wrap: anywhere; }
.order-costs b { font-weight: 500; }
.profit { color: #329761; } .loss { color: #e45454; } .muted { color: #909399; }
.unavailable { margin: 0 0 6px; font-size: 11px; color: #b88230; }
.order-footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding-top: 5px; border-top: 1px solid #f0f2f5; }
.order-footer span { color: #909399; font-size: 10px; }
</style>
