// Read-only Yahoo index sampling. Node 18+. Each sampled event is counted once; this is not a full-tick percentile.
// Usage: node docker/market-index-audit.cjs [durationSeconds=900] [outputDirectory] [baseUrl]
const fs = require('node:fs'), path = require('node:path')
const seconds = Number(process.argv[2] || 900)
if (!Number.isFinite(seconds) || seconds < 5) throw new Error('durationSeconds must be at least 5')
const directory = process.argv[3] || path.join(process.env.TEMP || '/tmp', 'market-index-audit-' + Date.now())
const base = process.argv[4] || 'http://127.0.0.1:17050'
fs.mkdirSync(directory, { recursive: true })
;(async () => {
  const events = {}, rows = [], end = Date.now() + seconds * 1000
  while (Date.now() < end) {
    let row
    try {
      const response = await fetch(base + '/api/market/status', { signal: AbortSignal.timeout(4000) })
      if (!response.ok) throw new Error('HTTP ' + response.status)
      const stream = (await response.json()).find(x => x.provider === 'Yahoo')?.stream
      row = { at: Date.now(), connected: stream?.connected, subscriptions: stream?.subscriptions?.filter(x => x.startsWith('^')),
        observations: Object.fromEntries(Object.entries(stream?.observations || {}).filter(([symbol]) => symbol.startsWith('^'))) }
      for (const [symbol, quote] of Object.entries(row.observations)) {
        const samples = events[symbol] ||= new Map()
        samples.set(quote.eventId, quote.fetchedAt - quote.timestamp)
      }
    } catch (error) { row = { at: Date.now(), error: error.message } }
    rows.push(row); fs.appendFileSync(path.join(directory, 'observations.jsonl'), JSON.stringify(row) + '\n')
    await new Promise(resolve => setTimeout(resolve, Math.min(5000, Math.max(0, end - Date.now()))))
  }
  const indices = {}
  for (const [symbol, values] of Object.entries(events)) {
    const sorted = [...values.values()].sort((a, b) => a - b)
    indices[symbol] = { uniqueSampledEvents: sorted.length, minMs: sorted[0], p50Ms: sorted[Math.floor(sorted.length * .5)],
      p95Ms: sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * .95))], maxMs: sorted.at(-1) }
  }
  const summary = { start: new Date(rows[0].at).toISOString(), end: new Date(rows.at(-1).at).toISOString(), polls: rows.length, indices }
  fs.writeFileSync(path.join(directory, 'summary.json'), JSON.stringify(summary, null, 2))
  console.log(JSON.stringify(summary, null, 2))
})().catch(error => { console.error(error); process.exitCode = 1 })
