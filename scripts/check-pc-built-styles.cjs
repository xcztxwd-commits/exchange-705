const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const directory = path.join(__dirname, '../exchange-pc/dist/assets')
const css = fs.readdirSync(directory, { recursive: true }).filter(name => name.endsWith('.css')).map(name => fs.readFileSync(path.join(directory, name), 'utf8')).join('\n')
for (const selector of ['.flex{', '.items-center{', '.w-6{', '.h-screen{']) {
  assert.ok(css.includes(selector), `Missing Tailwind utility ${selector}; verify PostCSS config resolution`)
}
assert.ok(!css.includes('@tailwind'), 'Unprocessed Tailwind directive')
console.log('PC production Tailwind utility check passed')
