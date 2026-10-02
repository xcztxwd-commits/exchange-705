/** Completion-driven polling for read-only status endpoints. Never use for writes. */
export function startReadPolling(read: () => Promise<boolean>) {
  let stopped = false
  let timer: ReturnType<typeof setTimeout> | undefined
  let delay = 5000
  const run = async () => {
    let success = false
    try { success = await read() } catch (error) { console.error('状态轮询失败:', error) }
    if (stopped) return
    delay = success ? 5000 : Math.min(60000, delay * 2)
    timer = setTimeout(run, delay)
  }
  void run()
  return () => { stopped = true; if (timer !== undefined) clearTimeout(timer) }
}
