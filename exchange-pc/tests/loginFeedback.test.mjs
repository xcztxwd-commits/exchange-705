import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import test from 'node:test'
import { classifyLoginError } from '../../exchange-frontend/src/utils/loginFeedback.ts'

function interceptors() {
  let success, failure, logouts = 0
  const instance = { interceptors: { request: { use() {} }, response: { use(ok, bad) { success = ok; failure = bad } } } }
  const source = readFileSync(new URL('../src/utils/request.ts', import.meta.url), 'utf8')
    .replace(/^import .*$/gm, '')
    .replace('import.meta.env.VITE_API_BASE_URL', 'undefined')
    .replace('export default instance', '')
  const bindings = {
    axios: { create: () => instance }, classifyLoginError,
    useAuthStore: () => ({ token: null, logout() { logouts++ } }),
    useLocaleStore: () => ({ backendMessage: value => value }),
    window: { location: { pathname: '/login' } },
    finishAccountWrite() {},
  }
  new Function(...Object.keys(bindings), stripTypeScriptTypes(source))(...Object.values(bindings))
  return { success, failure, logouts: () => logouts }
}

test('PC login preserves specific reasons before generic translation and session redirects', async () => {
  const api = interceptors()
  for (const [status, message, reason] of [
    [400, 'password wrong', 'credentials'], [400, 'user not found', 'credentials'],
    [401, 'Unauthorized', 'credentials'], [400, 'account disabled', 'disabled'],
    [429, 'Too many attempts', 'rate'], [503, 'private server details', 'service'],
    [403, 'Tenant unavailable', 'service'], [400, 'Unknown internal details', 'failed'],
  ]) {
    await assert.rejects(api.failure({ config: { url: '/auth/login' }, response: { status, data: { message } } }), error => {
      assert.equal(error.loginReason, reason)
      assert.equal(error.message, 'Login failed', 'Raw backend text must not become UI copy')
      return true
    })
  }
  assert.equal(api.logouts(), 0, 'A rejected login must stay inside the login form')
})

test('PC login distinguishes timeout and network failure', async () => {
  const api = interceptors()
  for (const [code, reason] of [['ECONNABORTED', 'timeout'], ['ETIMEDOUT', 'timeout'], ['ERR_NETWORK', 'network']]) {
    await assert.rejects(api.failure({ config: { url: '/auth/login' }, request: {}, code }), error => error.loginReason === reason)
  }
})

test('HTTP 200 login rejection is classified while successful payload is preserved', () => {
  const api = interceptors()
  assert.throws(() => api.success({ config: { url: '/auth/login' }, status: 200, data: { success: false, message: 'password wrong' } }), error => error.loginReason === 'credentials')
  const data = { token: 'test-session', user: { id: 7 } }
  assert.equal(api.success({ config: { url: '/auth/login' }, status: 200, data }), data)
})

test('protected endpoints retain their expired-session handling', async () => {
  const api = interceptors()
  await assert.rejects(api.failure({ config: { url: '/user/profile' }, response: { status: 401, data: {} } }))
  assert.equal(api.logouts(), 1)
})
