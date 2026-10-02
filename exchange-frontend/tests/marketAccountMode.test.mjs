import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'

const source = readFileSync(new URL('../src/store/market.ts', import.meta.url), 'utf8')
test('all raw mobile market requests declare their REAL/DEMO mode, including Redis aliases', () => {
  assert.match(source, /fetch\(`\$\{apiBaseUrl\}\/market\/all`, \{ headers: \{ 'X-Account-Mode': accountMode\(\) \} \}\)/)
  assert.match(source, /fetch\(`\$\{apiBaseUrl\}\/market\/redis\/price\/batch`, \{\s*method: 'POST',\s*headers: \{\s*'X-Account-Mode': accountMode\(\),/)
  assert.equal([...source.matchAll(/\bfetch\(/g)].length, 3)
})
test('K-line polling preserves Content-Type and its original account mode across retries', async () => {
  const fn = source.match(/async function fetchMarketKline[\s\S]*?\r?\n\}/)[0]
  for (const initial of ['REAL', 'DEMO']) {
    let mode = initial
    const calls = []
    const context = { Headers, accountMode: () => mode, setTimeout: action => action(),
      fetch: async (url, options) => {
        calls.push({ url, mode: options.headers.get('X-Account-Mode'), type: options.headers.get('Content-Type'), body: options.body })
        mode = initial === 'REAL' ? 'DEMO' : 'REAL'
        return new Response(JSON.stringify({ data: { pending: calls.length === 1 } }))
      } }
    runInNewContext(ts.transpileModule(fn, { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText, context)
    await context.fetchMarketKline('/owned/market/redis/kline/batch', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"symbols":["BTCUSDT"]}' })
    assert.equal(calls.length, 2)
    for (const call of calls) {
      assert.equal(call.mode, initial)
      assert.equal(call.type, 'application/json')
      assert.equal(call.body, '{"symbols":["BTCUSDT"]}')
    }
  }
})
