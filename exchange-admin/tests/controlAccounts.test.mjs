import assert from 'node:assert/strict'
import fs from 'node:fs'
import { transform } from 'esbuild'
import { parse } from '@vue/compiler-sfc'
import { generateTotpSecret } from '../src/control/totp.ts'

const secrets = new Set()
for (let i = 0; i < 100; i++) {
  const secret = generateTotpSecret()
  assert.match(secret, /^[A-Z2-7]{32}$/)
  secrets.add(secret)
}
assert.equal(secrets.size, 100, 'each account receives an independent random secret')
const generator = fs.readFileSync(new URL('../src/control/totp.ts', import.meta.url), 'utf8')
assert.match(generator, /crypto\.getRandomValues/)
assert.doesNotMatch(generator, /Math\.random/)

for (const [file, stateName] of [['ControlAccounts.vue', 'form'], ['AuditSecurity.vue', 'mfa']]) {
  const source = fs.readFileSync(new URL(`../src/control/${file}`, import.meta.url), 'utf8')
  assert.match(source, /@click="generateSecret"/)
  assert.match(source, /随机生成/)
  assert.match(source, /type="password" show-password/)
  const script = parse(source).descriptor.scriptSetup.content
  const handler = script.match(/function generateSecret\(\)\{[^\n]*\}/)[0]
  const compiled = (await transform(handler, { loader: 'ts', target: 'es2022' })).code
  const state = { newSecret: 'old-secret', newTotp: '123456' }
  const messages = []
  new Function(stateName, 'generateTotpSecret', 'ElMessage', `${compiled};generateSecret()`)(state, generateTotpSecret, { success: text => messages.push(text) })
  assert.match(state.newSecret, /^[A-Z2-7]{32}$/)
  assert.equal(state.newTotp, '', 'a replacement secret invalidates the previously entered code')
  assert.equal(messages.length, 1)
}
const accounts = fs.readFileSync(new URL('../src/control/ControlAccounts.vue', import.meta.url), 'utf8')
assert.match(accounts, /minlength="6" maxlength="128"/)
assert.match(accounts, /允许纯数字/)
assert.doesNotMatch(accounts, /form\.reason|reason:|label="原因"|16 至 128/)
assert.match(accounts, /当前操作者密码/)
assert.match(accounts, /当前操作者动态码/)
assert.match(accounts, /新账号动态码/)
console.log('PASS control accounts: secure independent Base32 secrets, regenerate clears stale code, six-character hint, no reason field, MFA verification retained')
