import { ref } from 'vue'

// Only prevents confusing UI switches; authentication and ledger isolation remain server-side.
export const pendingAccountWrites = ref(0)
export function trackAccountWrite(config: any) {
  if (['get', 'head', 'options'].includes(String(config.method || 'get').toLowerCase()) || config.url === '/auth/heartbeat') return
  config.accountWritePending = true
  pendingAccountWrites.value++
}
export function finishAccountWrite(config: any) {
  if (!config?.accountWritePending) return
  config.accountWritePending = false
  pendingAccountWrites.value = Math.max(0, pendingAccountWrites.value - 1)
}
