import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'
const require = createRequire(new URL('../package.json', import.meta.url))
const { build } = require('esbuild')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')
const result = await build({ entryPoints: [fileURLToPath(new URL('../src/utils/customerProfile.ts', import.meta.url))], bundle: true, write: false, format: 'cjs', platform: 'node', target: 'es2022' })
const module = { exports: {} }
new Function('module', 'exports', result.outputFiles[0].text)(module, module.exports)
const { totalCustomerBalance, customerProfilePatch, balancesChanged } = module.exports
assert.equal(totalCustomerBalance({ fundBalance: '0.1', contractBalance: '0.2', optionBalance: '0.03' }), '0.33')
assert.equal(totalCustomerBalance({ totalBalance: '150.25', fundBalance: '100' }), '150.25', 'server total includes frozen funds')
assert.equal(totalCustomerBalance({}), '0')
assert.deepEqual(customerProfilePatch({ nickname: '客户', remark: '原备注' }, { nickname: '客户', remark: '新备注' }), { remark: '新备注' })
assert.deepEqual(customerProfilePatch({ parentUserId: 123, annualIncome: '10' }, { parentUserId: '', annualIncome: '' }), { parentUserId: null, annualIncome: null })
assert.deepEqual(customerProfilePatch({ parentUserId: 123 }, { parentUserId: undefined }), { parentUserId: null }, 'clearing a numeric field must survive JSON serialization')
assert.deepEqual(customerProfilePatch({ id: 1, passwordHash: 'secret' }, { id: 2, passwordHash: 'bad' }), {}, 'read-only and secret fields cannot enter the profile patch')
assert.equal(balancesChanged({ FUND: '1.00', CONTRACT: 0, OPTION: '0' }, { FUND: '1', CONTRACT: '0.000', OPTION: 0 }), false)
assert.equal(balancesChanged({ FUND: '1.0000000000000001' }, { FUND: '1.0000000000000002' }), true, 'do not lose monetary precision')
assert.equal(balancesChanged({ FUND: '1000' }, { FUND: '100' }), true)
for (const file of ['Users.vue', '../components/CustomerDetailEditor.vue']) {
  const source = readFileSync(new URL(`../src/views/${file}`, import.meta.url), 'utf8')
  const parsed = parse(source)
  assert.deepEqual(parsed.errors, [])
  compileScript(parsed.descriptor, { id: file })
  const template = compileTemplate({ source: parsed.descriptor.template.content, filename: file, id: file })
  assert.deepEqual(template.errors, [], `${file} template must compile`)
}
console.log('PASS: customer total, edit patch, exact balance comparison and Vue component compilation')
