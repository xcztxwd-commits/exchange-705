// Exercise both actual client components with synthetic claim responses.
import fs from 'node:fs'
import vm from 'node:vm'
import assert from 'node:assert/strict'
import ts from 'typescript'
import { ref, computed, reactive } from 'vue'
import { activityPosition, matchesPosition } from '../src/utils/trialLifecycle.ts'
import * as continuation from '../src/utils/claimContinuation.ts'

for (const app of ['exchange-frontend', 'exchange-pc']) {
  const source = fs.readFileSync(new URL(`../../${app}/src/components/ActivityCenter.vue`, import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const auth = reactive({ token: 'synthetic', user: { id: 7, tenantId: 1 } })
  const route = reactive({ path: '/home', query: {} }), watchers = [], stored = new Map()
  const row = (id, claimedAt, positions = ['AUTH_HOME']) => ({ active: true, delivery: { id, claimedAt }, campaign: { id, positions, amount: 300, hasQuota: true, allowRepeatClaim: true } })
  let rows = [row(1), row(2, '2026-10-08T00:00:00Z'), row(3, undefined, ['AUTH_TRADE']), { ...row(4), active: false }]
  let rejectDetail, failed = false, deferred = false, finishClaim, writes = 0
  const sandbox = { ...continuation, ref, computed, reactive, activityPosition, matchesPosition, console, Event, AbortController,
    defineProps: () => ({}), useRoute: () => route, useRouter: () => ({ push() {} }), useAuthStore: () => auth,
    useLocaleStore: () => ({ locale: 'en', text: (_, en) => en }), useActivityCopy: () => () => '',
    tenantFeatures: ref({ tenantId: 1 }), accountMode: () => 'REAL', realApiBase: () => '/api',
    watch: (get, callback) => watchers.push(callback), onMounted() {}, onUnmounted() {}, nextTick: async () => {},
    localStorage: { getItem: key => stored.get(key) ?? null, setItem: (key, value) => stored.set(key, value), removeItem: key => stored.delete(key) },
    window: { dispatchEvent() {} }, document: { visibilityState: 'visible', querySelector: () => null }, navigator: {},
    request: { get: async url => url.includes('/messages/') ? new Promise((_, reject) => { rejectDetail = reject }) : { content: structuredClone(rows) }, post: async () => ({}) },
    fetch: async () => { writes++; if (deferred) await new Promise(resolve => { finishClaim = resolve }); return { ok: !failed, status: failed ? 500 : 200, json: async () => failed ? { message: 'synthetic failure' } : {} } },
  }
  vm.runInNewContext(ts.transpileModule(script + '\nglobalThis.subject={items,publicItems,selected,claim,close,load,busy,error}', { compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.None } }).outputText, sandbox)
  const s = sandbox.subject
  await s.load()
  assert.deepEqual(Array.from(s.publicItems.value, item => item.campaign.id), [1], 'claimed, inactive and other placements stay out of banners')
  assert.equal(s.items.value.length, 4, 'claimed inbox history remains available')
  s.selected.value = s.publicItems.value[0]
  const claiming = s.claim()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(typeof rejectDetail, 'function', s.error.value)
  assert.equal(s.publicItems.value.length, 0, 'confirmed claim hides before delivery refresh finishes')
  rejectDetail(Error('synthetic detail failure'))
  await claiming
  s.close(); await s.load()
  assert.equal(s.publicItems.value.length, 0, 'closing and stale reload cannot restore a confirmed banner')
  assert.equal(writes, 1, 'visibility never submits another claim')

  rows = [row(1)]; auth.token = 'next-account'; auth.user.id = 8
  watchers[0]([auth.token, 1, false], ['synthetic', 1, false])
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(s.publicItems.value.length, 1, 'account reset clears local confirmation state')
  rows = [row(5)]; await s.load()
  failed = true; s.selected.value = s.publicItems.value[0]; await s.claim()
  assert.equal(s.publicItems.value.length, 1, 'failed claim keeps its banner')

  rows = [row(6)]; await s.load(); s.selected.value = s.publicItems.value[0]
  failed = false; deferred = true; const switched = s.claim()
  await new Promise(resolve => setImmediate(resolve))
  auth.token = 'third-account'; auth.user.id = 9
  watchers[0]([auth.token, 1, false], ['next-account', 1, false])
  finishClaim(); await switched; await new Promise(resolve => setImmediate(resolve))
  assert.equal(s.publicItems.value.length, 1, 'late response cannot hide a new account banner')
  console.log(`PASS ${app}: claimed filtering, immediate hide, detail failure, stale reload, failed claim and account isolation`)
}
