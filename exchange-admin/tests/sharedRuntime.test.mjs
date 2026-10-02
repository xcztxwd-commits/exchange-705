import assert from 'node:assert/strict'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { resolveConfig } from 'vite'

const root = fileURLToPath(new URL('../', import.meta.url))
process.chdir(root)
const host = path.join(root, 'src/main.ts')
const shared = ['SupportNotifications.vue', 'SupportThread.vue', 'SupportImage.vue']
  .map(name => path.resolve(root, '../exchange-frontend/src/components', name))
const options = { logLevel: 'silent' }

// Negative control: sibling installations create different router injection keys,
// even when both installed vue-router versions are identical.
const oldConfig = await resolveConfig(options, 'build', 'production')
const oldResolve = oldConfig.createResolver({ dedupe: ['vue'] })
assert.notEqual(await oldResolve('vue-router', host), await oldResolve('vue-router', shared[0]))
console.log('PASS negative control: shared notifications resolve another vue-router without dedupe')

let checks = 0
for (const configFile of [undefined, 'vite.config.ts', 'vite.control.config.ts']) {
  const config = await resolveConfig({ ...options, ...(configFile && { configFile }) }, 'build', 'production')
  console.log(`CONFIG ${configFile || 'default'}: ${config.configFile}; root=${config.root}`)
  const resolve = config.createResolver()
  for (const dependency of ['vue', 'vue-router', 'pinia']) {
    const expected = await resolve(dependency, host)
    assert(expected, `${dependency} must resolve from the host app`)
    for (const importer of shared) {
      assert.equal(await resolve(dependency, importer), expected,
        `${configFile || 'default'}: ${path.basename(importer)} must share host ${dependency} provider identity`)
      checks++
    }
  }
}
console.log(`PASS shared runtime: ${checks} production-resolution checks, one Vue/router/Pinia identity`)
