import test from 'node:test'
import assert from 'node:assert/strict'
import { uiEditionKey, readUiEdition, writeUiEdition } from '../src/utils/uiEdition.ts'
import { build } from 'esbuild'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
const storage = () => { const data = new Map(); return { getItem: key => data.get(key) ?? null, setItem: (key, value) => data.set(key, value) } }
test('UI preference defaults classic, separates origin/tenant/user and never stores credentials or account mode', () => {
  const s = storage(), key = uiEditionKey({ tenantId: 7, id: 9 }, 'https://tenant.invalid')
  assert.equal(readUiEdition(s, key), 'classic')
  assert.equal(writeUiEdition(s, key, 'advanced'), true)
  assert.equal(readUiEdition(s, key), 'advanced')
  for (const other of [uiEditionKey({ tenantId: 8, id: 9 }, 'https://tenant.invalid'), uiEditionKey({ tenantId: 7, id: 10 }, 'https://tenant.invalid'), uiEditionKey({ tenantId: 7, id: 9 }, 'https://other.invalid')]) assert.equal(readUiEdition(s, other), 'classic')
  assert.equal(uiEditionKey(null, 'https://tenant.invalid'), '')
  assert.equal(uiEditionKey({ id: 9 }, 'https://tenant.invalid'), '')
  assert.equal(writeUiEdition(s, '', 'advanced'), false)
  s.setItem(key, 'unknown'); assert.equal(readUiEdition(s, key), 'classic')
  assert.equal(writeUiEdition(s, key, 'REAL'), false)
  const denied = { getItem() { throw Error('denied') }, setItem() { throw Error('denied') } }
  assert.equal(readUiEdition(denied, key), 'classic'); assert.equal(writeUiEdition(denied, key, 'advanced'), false)
})

test('actual store blocks in-flight writes and resets on login, logout, user and tenant changes', async () => {
  const root = fileURLToPath(new URL('../', import.meta.url)), s = storage()
  globalThis.localStorage = s; globalThis.location = { origin: 'https://tenant.invalid' }
  const authSource = `import { defineStore } from 'pinia';import { ref } from 'vue';export const useAuthStore = defineStore('auth',()=>({token:ref('fixture'),user:ref({tenantId:7,id:9})}));`
  const result = await build({
    stdin: { contents: `export { useUiEditionStore } from './src/store/uiEdition.ts';export { pendingAccountWrites } from './src/utils/accountRequests.ts';export { useAuthStore } from 'auth-fixture';export { createPinia,setActivePinia,nextTick } from 'vue-fixture';`, resolveDir: root },
    bundle: true, write: false, format: 'esm', platform: 'node', define: { 'process.env.NODE_ENV': '"production"' },
    plugins: [{ name: 'test-auth', setup(b) {
      b.onResolve({ filter: /^(auth-fixture|\.\/auth)$/ }, () => ({ path: 'auth', namespace: 'fixture' }))
      b.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ contents: authSource, resolveDir: root }))
      b.onResolve({ filter: /^vue-fixture$/ }, () => ({ path: 'vue', namespace: 'fixture-vue' }))
      b.onLoad({ filter: /.*/, namespace: 'fixture-vue' }, () => ({ contents: `export { createPinia,setActivePinia } from 'pinia';export { nextTick } from 'vue';`, resolveDir: root }))
      b.onResolve({ filter: /^@\// }, args => ({ path: path.join(root, 'src', args.path.slice(2) + '.ts') }))
    } }],
  })
  const module = await import('data:text/javascript;base64,' + Buffer.from(result.outputFiles[0].text).toString('base64'))
  module.setActivePinia(module.createPinia())
  const auth = module.useAuthStore(), ui = module.useUiEditionStore()
  assert.equal(ui.edition, 'classic')
  module.pendingAccountWrites.value = 1
  assert.equal(await ui.switchTo('advanced'), false); assert.equal(ui.edition, 'classic')
  module.pendingAccountWrites.value = 0
  assert.equal(await ui.switchTo('advanced'), true); assert.equal(ui.edition, 'advanced')
  assert.equal(auth.token, 'fixture'); assert.deepEqual(auth.user, { tenantId: 7, id: 9 })
  auth.user = { tenantId: 8, id: 9 }; assert.equal(ui.edition, 'classic')
  auth.user = { tenantId: 7, id: 10 }; assert.equal(ui.edition, 'classic')
  auth.user = { tenantId: 7, id: 9 }; assert.equal(ui.edition, 'advanced')
  auth.token = ''; assert.equal(ui.edition, 'classic'); assert.equal(await ui.switchTo('advanced'), false)
  auth.token = 'fixture'; assert.equal(ui.edition, 'advanced')
  assert.equal(await ui.switchTo('REAL'), false)
  delete globalThis.localStorage; delete globalThis.location
})
