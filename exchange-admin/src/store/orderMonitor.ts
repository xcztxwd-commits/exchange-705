import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { useAuthStore } from './auth'
import type { AccountMode } from '@/utils/accountTableData'

export type MonitorKind = 'contract' | 'option'
export type MonitoredOrder = Record<string, any> & { id: number; symbol: string; displayName?: string; kind: MonitorKind; accountMode: AccountMode; monitorKey: string }
const keyOf = (row: any, kind: MonitorKind) => `${row.accountMode || 'REAL'}:${kind}:${row.id}`

export const useOrderMonitorStore = defineStore('orderMonitor', () => {
  const orders = ref<MonitoredOrder[]>([])
  const visible = ref(false)
  const auth = useAuthStore()
  function clear() { orders.value = []; visible.value = false }
  watch(() => auth.token, clear, { flush: 'sync' })
  function contains(row: any, kind: MonitorKind) { return orders.value.some(order => order.monitorKey === keyOf(row, kind)) }
  function add(row: any, kind: MonitorKind) {
    if (row.deleted || row.id == null) return
    if (!contains(row, kind)) orders.value.push({ ...row, kind, accountMode: row.accountMode || 'REAL', monitorKey: keyOf(row, kind), liveUpdatedAt: undefined })
    visible.value = true
  }
  function remove(key: string) { orders.value = orders.value.filter(order => order.monitorKey !== key) }
  return { orders, visible, add, remove, contains, clear }
})
