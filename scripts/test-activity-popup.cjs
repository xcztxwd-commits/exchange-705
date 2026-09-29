const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { transformSync } = require('../exchange-admin/node_modules/esbuild')

// Exercise the actual SFC script for both clients, without a running backend.
async function check(app) {
  const source = fs.readFileSync(path.join(__dirname, '..', app, 'src/components/ActivityCenter.vue'), 'utf8')
    .match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const watchers = [], route = { path: '/home', query: {} }, auth = { token: 'test' }
  let rows = [], popups = 0
  const dialog = { open: false, showModal() { this.open = true; popups++ }, close() { this.open = false } }
  const sandbox = {
    ref: value => ({ value }), computed: getter => ({ get value() { return getter() } }),
    watch: (get, callback) => watchers.push({ get, callback }), onMounted() {}, onUnmounted() {}, nextTick: async () => {},
    defineProps() {}, useRoute: () => route, useRouter: () => ({}), useAuthStore: () => auth,
    useLocaleStore: () => ({ locale: 'en' }), accountMode: () => 'REAL', useActivityCopy: () => () => '',
    document: { querySelector: () => null }, window: {}, setInterval, clearInterval,
    request: {
      async get() { return { content: JSON.parse(JSON.stringify(rows)), totalPages: 1 } },
      async post(url, body) {
        const item = rows.find(x => url.includes('/' + x.delivery.id + '/'))
        if (body.type === 'CLOSED') item.delivery.closedAt = 'now'
        if (body.type === 'OPENED') item.delivery.openedAt = 'now'
        return { ...item.delivery }
      }
    }
  }
  vm.createContext(sandbox)
  vm.runInContext(transformSync(source + '\nglobalThis.subject={load,close,open,dialog,selected,unread}', { loader: 'ts', target: 'es2020' }).code, sandbox)
  const subject = sandbox.subject
  subject.dialog.value = dialog
  const item = (id, repeatUnread = true) => ({ delivery: { id, receivedAt: 'now' }, campaign: { autoPopup: true, repeatUnread }, active: true })
  const navigate = async () => {
    const watcher = watchers.find(x => x.get() === route.path)
    route.path = route.path === '/home' ? '/trade' : '/home'
    watcher.callback(route.path)
    await new Promise(resolve => setImmediate(resolve))
  }
  rows = [item(1), item(2)]
  await subject.load(true); assert.equal(popups, 1); subject.close()
  await subject.load(true); assert.equal(popups, 2); subject.close()
  await subject.load(true); assert.equal(popups, 2, 'no polling loop for multiple unread messages')
  await navigate(); assert.equal(popups, 3, 'unread close repeats on next page')
  await subject.open(subject.selected.value); subject.close()
  rows[1].delivery.claimedAt = 'now'
  await navigate(); assert.equal(popups, 3, 'opened and claimed messages do not repeat')
  rows = [item(3, false)]; await navigate(); assert.equal(popups, 4); subject.close()
  await navigate(); assert.equal(popups, 4, 'repeat disabled respects closed receipt')
  rows = [item(4)]; rows[0].active = false; await navigate(); assert.equal(popups, 4, 'inactive never pops up')
  console.log(`PASS ${app}: unread retry, same-page suppression, read/claim/disabled guards`)
}
;(async () => { for (const app of ['exchange-frontend', 'exchange-pc']) await check(app) })().catch(error => { console.error(error); process.exitCode = 1 })
