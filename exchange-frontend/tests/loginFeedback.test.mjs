import { test } from 'node:test'
import assert from 'node:assert/strict'
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
