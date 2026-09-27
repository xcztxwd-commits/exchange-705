// Execute the real request interceptor with Axios/Pinia stubs; never send a request.
const fs = require('node:fs')
const assert = require('node:assert/strict')
const ts = require('../exchange-frontend/node_modules/typescript')
async function run() {
  for (const project of ['exchange-frontend', 'exchange-pc']) {
    let reject, locale = 'ja'
    const store = { get locale() { return locale }, t: key => key, text: (_zh, en) => en, backendMessage: (value, error) => error && value !== '余额不足' ? 'Unable to confirm the result. Check the relevant history or status before submitting again.' : 'localized:' + value }
    const instance = { interceptors: { request: { use() {} }, response: { use(_success, failure) { reject = failure } } } }
    const source = fs.readFileSync(`${project}/src/utils/request.ts`, 'utf8').replaceAll('import.meta.env', '({})')
    const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
    new Function('require', 'exports', js)(name => {
      if (name === 'axios') return { default: { create: () => instance } }
      if (name.endsWith('/locale')) return { useLocaleStore: () => store }
      if (name.endsWith('/auth')) return { useAuthStore: () => ({}) }
      throw Error(name)
    }, {})
    await assert.rejects(reject({ config: { method: 'post' }, message: 'timeout' }), /Unable to confirm the result/)
    await assert.rejects(reject({ config: { method: 'get' }, message: 'Network Error' }), /A network error occurred/)
    await assert.rejects(reject({ config: { method: 'post' }, response: { status: 400, data: { message: '余额不足' } } }), /localized:余额不足/)
    await assert.rejects(reject({ config: { method: 'post', url: '/withdraw/calculate' }, message: 'timeout' }), /A network error occurred/)
    for (const status of [400, 403, 404, 429, 500, 502, 503, 504]) {
      await assert.rejects(reject({ config: { method: 'post' }, response: { status, data: '' }, message: 'Request failed with status code ' + status }), new RegExp('Unable to confirm the result.*HTTP ' + status))
    }
    await assert.rejects(reject({ config: { method: 'get' }, response: { status: 500, data: { error: 'CUSTOM_DIAGNOSTIC' } }, message: 'Request failed with status code 500' }), /Unable to confirm the result/)
    await assert.rejects(reject({ config: { method: 'get' }, response: { status: 400, data: { message: '未知业务说明' } } }), /Unable to confirm the result/)
    locale = 'en'
    await assert.rejects(reject({ config: { method: 'post' }, message: 'timeout' }), /Unable to confirm the result/)
  }
  console.log('PASS: 30 request error assertions; no network traffic')
}
run().catch(error => { console.error(error); process.exitCode = 1 })
