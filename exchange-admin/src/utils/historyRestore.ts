export type HistoryCandle = { timestamp: number; open_price: number; close_price: number; low_price: number; high_price: number; volume?: number }
export function localMinute(timestamp: number, timezone: string) {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(timestamp).replace(' ', 'T')
}
// Resolve local clock inputs against IANA rules. DST gaps return no candidates; folds require an explicit offset.
export function minuteCandidates(local: string, timezone: string) {
  const anchor = Date.parse(local + ':00Z'), candidates: { timestamp: number; offset: string }[] = []
  if (!Number.isFinite(anchor)) return candidates
  for (let minutes = -14 * 60; minutes <= 14 * 60; minutes += 15) {
    const timestamp = anchor - minutes * 60000
    if (localMinute(timestamp, timezone) === local) candidates.push({ timestamp, offset: `${minutes < 0 ? '-' : '+'}${String(Math.floor(Math.abs(minutes) / 60)).padStart(2, '0')}:${String(Math.abs(minutes) % 60).padStart(2, '0')}` })
  }
  return candidates
}
export function restoreRange(rows: HistoryCandle[], first: number, last: number, width: number) {
  if (!rows.length || !Number.isFinite(first) || !Number.isFinite(last)) throw new Error('请选择已结束 K 线')
  const index = (n: number) => Math.max(0, Math.min(rows.length - 1, Math.round(n)))
  const a = rows[index(first)]!.timestamp, b = rows[index(last)]!.timestamp
  return { from: Math.min(a, b), to: Math.max(a, b) + width - 60000 }
}
export function aggregateHistory(rows: HistoryCandle[], width: number) {
  const bars = new Map<number, HistoryCandle>()
  for (const row of [...rows].sort((a, b) => a.timestamp - b.timestamp)) {
    const timestamp = Math.floor(row.timestamp / width) * width, old = bars.get(timestamp)
    if (!old) bars.set(timestamp, { ...row, timestamp })
    else { old.high_price = Math.max(old.high_price, row.high_price); old.low_price = Math.min(old.low_price, row.low_price); old.close_price = row.close_price }
  }
  return [...bars.values()]
}
