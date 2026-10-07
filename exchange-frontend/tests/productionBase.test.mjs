import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { resolveConfig } from 'vite'

const root = fileURLToPath(new URL('..', import.meta.url))
for (const [command, mode, expected] of [
  ['build', 'production', '/mobile/'],
  ['serve', 'production', '/mobile/'],
  ['serve', 'development', '/'],
]) {
  const config = await resolveConfig({ root, mode }, command)
  assert.equal(config.base, expected, `${command}/${mode}: mobile assets and history must share their entry prefix`)
}
console.log('PASS: production build/preview use /mobile/; development uses /')
