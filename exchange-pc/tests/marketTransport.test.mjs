import assert from 'node:assert/strict'
import { test } from 'node:test'

for (const app of ['exchange-pc', 'exchange-frontend']) {
  test(`${app}: shared connection, owner isolation, versions, restart and HTTP fallback`, async () => {
    const originals = Object.fromEntries(['window','document','location','WebSocket','fetch'].map(key => [key, globalThis[key]]))
    const sockets = [], requests = []
    class Socket {
      static OPEN = 1
      readyState = 0
      sent = []
      constructor() { sockets.push(this) }
      send(message) { this.sent.push(JSON.parse(message)) }
      close() { this.readyState = 3; this.onclose?.() }
      open() { this.readyState = 1; this.onopen?.() }
      price(data) { this.onmessage?.({ data: JSON.stringify({ type: 'price', data }) }) }
    }
    globalThis.window = new EventTarget()
    globalThis.document = Object.assign(new EventTarget(), { visibilityState: 'visible' })
    globalThis.location = { protocol: 'https:', host: 'test.local', hostname: 'test.local' }
    globalThis.WebSocket = Socket
    globalThis.fetch = async (url, options) => {
      requests.push({ url, symbols: JSON.parse(options.body).symbols })
      return { ok: true, json: async () => ({ ret: 200, data: {} }) }
    }
    const { default: market } = await import(`../../${app}/src/utils/marketWebSocket.ts`)
    const seen = []
    const stop = market.onPriceUpdate(prices => seen.push(prices))
    try {
      const connection = market.connect()
      assert.equal(market.connect(), connection, 'simultaneous callers await the actual connection')
      market.subscribe(['AAPL'], 'list'); market.subscribe(['AAPL'], 'trade')
      assert.equal(sockets.length, 1)
      sockets[0].open(); await connection
      const now = Date.now(), quote = { price: 100, timestamp: now, fetchedAt: now, epoch: 'one', quoteVersion: 2 }
      sockets[0].price({ AAPL: quote })
      assert.equal(market.isHealthy, true)
      sockets[0].price({ AAPL: { ...quote, price: 50, quoteVersion: 1 } })
      assert.equal(seen.length, 1, 'late snapshots cannot roll back the price')
      sockets[0].price({ AAPL: { status: 'unavailable', epoch: 'one', quoteVersion: 3 } })
      assert.equal(seen.at(-1).AAPL.status, 'unavailable', 'status-only frames are delivered')
      market.release('list')
      assert.equal(sockets[0].sent.some(message => message.action === 'unsubscribe' && message.symbols.includes('AAPL')), false)
      sockets[0].price({ AAPL: { ...quote, epoch: 'two', quoteVersion: 1 } })
      sockets[0].price({ AAPL: { ...quote, epoch: 'one', quoteVersion: 999 } })
      assert.equal(seen.at(-1).AAPL.epoch, 'two')
      market.release('trade')
      assert.equal(sockets[0].sent.at(-1).action, 'unsubscribe')
      assert.ok(requests.every(request => request.url.includes('/market/price/batch')))
    } finally {
      stop(); market.disconnect()
      for (const [key,value] of Object.entries(originals)) {
        if (value === undefined) delete globalThis[key]; else globalThis[key] = value
      }
    }
  })
}
