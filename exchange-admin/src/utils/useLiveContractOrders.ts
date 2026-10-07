import { onMounted, onBeforeUnmount, watch, type Ref } from 'vue'
import type { AccountMode } from './accountTableData'
import type { useAccountTable } from './useAccountTable'

// One batch per displayed account realm; preserve the table's array and row identities.
export function useLiveContractOrders(rows: Ref<any[]>, activeTab: Ref<string>, modes: Ref<AccountMode[]>,
  loading: Ref<boolean>, read: ReturnType<typeof useAccountTable>['read']) {
  let mounted = false, epoch = 0, timer: number | undefined, controller: AbortController | undefined
  const enabled = () => mounted && activeTab.value === 'contract' && !loading.value && !document.hidden
  const tracked = (row: any) => !row.deleted && ['OPEN', 'PENDING'].includes(row.status)
  function stop() {
    epoch++
    window.clearTimeout(timer)
    timer = undefined
    controller?.abort()
    controller = undefined
  }
  async function poll(version: number) {
    if (version !== epoch || !enabled()) return
    const page = rows.value
    const pending = page.filter(row => tracked(row) && modes.value.includes(row.accountMode || 'REAL'))
    if (!pending.length) return
    const request = new AbortController()
    controller = request
    const current = () => version === epoch && rows.value === page && enabled()
    await Promise.allSettled((['REAL', 'DEMO'] as AccountMode[]).map(async mode => {
      const group = pending.filter(row => (row.accountMode || 'REAL') === mode)
      if (!group.length) return
      try {
        const result = await read(mode, '/admin/orders/contract/live', { ids: group.map(row => row.id) }, 'POST', request.signal)
        if (!current()) return
        const updates = new Map((result.list || []).map((row: any) => [String(row.id), row]))
        for (const row of group) {
          if (!tracked(row)) continue
          const update: any = updates.get(String(row.id))
          if (!update) { row.liveAvailable = false; continue }
          for (const field of ['status', 'deleted', 'openPrice', 'closePrice', 'fee', 'margin', 'openTime', 'closeTime']) {
            if (field in update && row[field] !== update[field]) row[field] = update[field]
          }
          row.liveAvailable = update.liveAvailable !== false
          if (row.liveAvailable || row.status !== 'OPEN') {
            for (const field of ['currentPrice', 'profit', 'netProfit']) {
              if (field in update && row[field] !== update[field]) row[field] = update[field]
            }
          }
        }
      } catch {
        if (current()) for (const row of group) if (tracked(row)) row.liveAvailable = false
      }
    }))
    if (current()) {
      controller = undefined
      timer = window.setTimeout(() => void poll(version), 1000)
    }
  }
  function restart() {
    stop()
    // Defer until the same event's filter/page load has started.
    if (enabled()) timer = window.setTimeout(() => void poll(epoch), 0)
  }
  watch([rows, activeTab, loading, () => modes.value.join(',')], restart, { flush: 'sync' })
  onMounted(() => {
    mounted = true
    document.addEventListener('visibilitychange', restart)
    restart()
  })
  onBeforeUnmount(() => {
    mounted = false
    stop()
    document.removeEventListener('visibilitychange', restart)
  })
}
