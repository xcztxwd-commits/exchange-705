import { onUnmounted, ref } from 'vue'
import original from '@/utils/request'
import { useLocaleStore } from '@/store/locale'

// Private presentation lifecycle: reuse request interceptors and ledger rules unchanged.
export function useBusinessLifecycle() {
  const locale = useLocaleStore()
  let disposed = false
  const reads = new Set<AbortController>()
  const failures = new Map<string, string>()
  const timeouts = new Set<number>(), intervals = new Set<number>()
  const error = ref(''), writing = ref(false)
  const pending = new Set<string>()
  function setTimeout(callback: () => void, delay = 0) {
    if (disposed) return 0
    const id = window.setTimeout(() => { timeouts.delete(id); if (!disposed) callback() }, delay)
    timeouts.add(id)
    return id
  }
  function setInterval(callback: () => void, delay = 0) {
    if (disposed) return 0
    const id = window.setInterval(() => { if (!disposed) callback() }, delay)
    intervals.add(id)
    return id
  }
  async function call(method: 'get' | 'post' | 'put' | 'delete', args: any[]) {
    if (disposed) throw new Error('This view was closed; no new request was sent.')
    const url = String(args[0])
    const write = method !== 'get' && url !== '/withdraw/calculate'
    if (write && /^\/(?:deposit\/submit|withdraw\/submit|transfer\/submit|financial\/(?:purchase|redeem)|loan\/(?:apply|repay))/.test(url) && (reads.size || failures.size)) throw new Error(locale.text('账户数据尚未就绪，请重试查询后再提交。', 'Account data is not ready. Refresh before submitting.'))
    if (write && pending.has(url)) throw new Error('A request is already in progress. Check its result before trying again.')
    if (write) { pending.add(url); writing.value = true }
    const controller = write ? null : new AbortController()
    if (controller) {
      reads.add(controller)
      const index = method === 'get' ? 1 : 2
      args[index] = { ...args[index], signal: controller.signal }
    }
    try {
      const result: any = await (original[method] as any)(...args)
      if (disposed) throw new Error('This view was closed; discarded stale response.')
      if (result?.success === false) throw new Error(result.message || locale.text('服务未接受请求，请重试查询。', 'The service rejected this request.'))
      failures.delete(url)
      error.value = [...failures.values()][0] || ''
      return result
    } catch (e: any) {
      if (!disposed) {
        error.value = e.message || locale.text('数据加载失败，请重试。', 'Unable to load data. Please retry.')
        if (!write) failures.set(url, error.value)
      }
      throw e
    } finally {
      if (controller) reads.delete(controller)
      if (write) { pending.delete(url); writing.value = pending.size > 0 }
    }
  }
  onUnmounted(() => { disposed = true; reads.forEach(controller => controller.abort()); reads.clear(); timeouts.forEach(window.clearTimeout); intervals.forEach(window.clearInterval) })
  return { error, writing, setTimeout, setInterval,
    request: { get: (...args: any[]) => call('get', args), post: (...args: any[]) => call('post', args), put: (...args: any[]) => call('put', args), delete: (...args: any[]) => call('delete', args) } }
}
