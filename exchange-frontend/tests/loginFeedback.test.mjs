import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { accountLoginMessages, classifyLoginError, loginAccountText, loginMessages, loginText } from '../src/utils/loginFeedback.ts'

test('login errors are classified without exposing backend text or account existence', () => {
  const check = (status, data, expected) => assert.equal(classifyLoginError({ response: { status, data } }), expected)
  check(400, { message: 'password wrong' }, 'credentials')
  check(400, { message: 'user not found' }, 'credentials')
  check(401, {}, 'credentials')
  check(400, { message: 'account disabled' }, 'disabled')
  check(429, {}, 'rate')
  check(403, { code: 'TENANT_BOUNDARY_REJECTED' }, 'service')
  check(503, { message: 'private stack trace' }, 'service')
  check(400, { message: 'private stack trace' }, 'failed')
  assert.equal(classifyLoginError({ request: {}, code: 'ECONNABORTED' }), 'timeout')
  assert.equal(classifyLoginError({ request: {} }), 'network')
  assert.equal(classifyLoginError(new Error('Unknown')), 'failed')
})

test('all 19 supported locales have complete login feedback and safe fallback', () => {
  assert.equal(Object.keys(loginMessages).length, 19)
  for (const [locale, messages] of Object.entries(loginMessages)) {
    assert.equal(messages.length, 6, locale)
    for (const reason of ['failed', 'credentials', 'rate', 'service', 'timeout', 'email']) {
      assert.ok(loginText(locale, reason).length > 5, `${locale}/${reason}`)
    }
  }
  assert.equal(loginText('unsupported', 'failed'), loginMessages.en[0])
})

test('all locales label sign-in as account, not email', () => {
  assert.equal(Object.keys(accountLoginMessages).length, 19)
  for (const locale of Object.keys(accountLoginMessages)) {
    for (const part of ['title', 'placeholder', 'missing']) assert.ok(loginAccountText(locale, part).length > 3, `${locale}/${part}`)
  }
  assert.equal(loginAccountText('zh-TW', 'title'), '帳號登入')
  assert.equal(loginAccountText('unsupported', 'title'), accountLoginMessages.en[0])
  assert.match(loginText('en', 'credentials'), /Account or password/)
})


test('login navigates before auth remount and ignores invalid or unmounted replies', async () => {
  const vue = readFileSync(new URL('../src/views/Login.vue', import.meta.url), 'utf8')
  const script = vue.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
  const compiled = stripTypeScriptTypes(script)
  function fixture() {
    const events = [], timers = new Map()
    let unmount, resolve, nextTimer = 0
    const response = new Promise(done => { resolve = done })
    const bindings = {
      ref: value => ({ value }), computed: get => ({ get value() { return get() } }), watch: () => {},
      onBeforeUnmount: callback => { unmount = callback },
      useRouter: () => ({ replace: async path => { events.push(['navigate', path]) } }),
      useRoute: () => ({ query: {} }),
      useLocaleStore: () => ({ loadLocale() {}, locale: 'en', t: key => key }),
      // App.vue changes the route component key on setAuth; Vue unmounts it at the next microtask.
      useAuthStore: () => ({ setAuth: (token, user) => { events.push(['auth', token, user.id]); queueMicrotask(() => { events.push(['unmount']); unmount() }) } }),
      request: { post: (path, body) => { events.push(['request', path, body]); return response } },
      loginAccountText, loginText,
      setTimeout: callback => { const id = ++nextTimer; timers.set(id, callback); return id },
      clearTimeout: id => timers.delete(id),
    }
    const view = new Function(...Object.keys(bindings), `${compiled}\nreturn { onSubmit, account, password, feedback, loading };`)(...Object.values(bindings))
    view.account.value = ' owned-user '; view.password.value = 'owned-password'
    return { view, events, resolve, unmount: () => unmount(), timers }
  }
  const reply = { token: 'server-issued-test-value', user: { id: 7, tenantId: 3 } }
  const good = fixture(), first = good.view.onSubmit()
  await good.view.onSubmit() // The in-flight guard must retain one request.
  good.resolve(reply); await first
  assert.deepEqual(good.events.map(event => event[0]), ['request', 'auth', 'navigate', 'unmount'])
  assert.deepEqual(good.events[0], ['request', '/auth/login', { account: 'owned-user', password: 'owned-password', loginType: 'account' }])
  assert.deepEqual(good.events[2], ['navigate', '/home'])
  assert.equal(good.timers.size, 0, 'Successful navigation must not depend on a component timer')
  for (const value of [{ ...reply, token: ' ' }, { ...reply, success: false }, { ...reply, user: {} }]) {
    const bad = fixture(), pending = bad.view.onSubmit(); bad.resolve(value); await pending
    assert.deepEqual(bad.events.map(event => event[0]), ['request'])
    assert.equal(bad.view.feedback.value, 'failed'); assert.equal(bad.view.loading.value, false)
  }
  const gone = fixture(), pending = gone.view.onSubmit(); gone.unmount(); gone.resolve(reply); await pending
  assert.deepEqual(gone.events.map(event => event[0]), ['request'], 'A late response must not log in or navigate an unmounted page')
})
