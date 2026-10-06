import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import { compileTemplate } from '@vue/compiler-sfc'
import { transform } from 'esbuild'
import { createSSRApp, defineComponent, h } from 'vue'
import { renderToString } from '@vue/server-renderer'
import { mergeColumns } from '../src/utils/tablePreferences.ts'

const source = fs.readFileSync(new URL('../src/components/OnlineUsers.vue', import.meta.url), 'utf8')
const column = source.match(/<el-table-column prop="lastLoginIp"[\s\S]*?<\/el-table-column>/)?.[0]
assert(column, 'shared online users table exposes a stable IP column')
assert.match(column, /label="IP（地址）"/)
assert.match(source, /IP\/地区为最近登录记录/)
const template = compileTemplate({ source: column, filename: 'OnlineUsers.vue', id: 'online-users-ip' })
assert.deepEqual(template.errors, [])
const compiled = await transform(template.code, { format: 'cjs', target: 'es2022' })
const module = { exports: {} }
new Function('require', 'module', 'exports', compiled.code)(createRequire(import.meta.url), module, module.exports)
async function cell(row) {
  const app = createSSRApp({ render: module.exports.render })
  app.component('ElTableColumn', defineComponent({
    props: ['prop', 'label', 'minWidth', 'showOverflowTooltip'],
    setup(_, { slots }) { return () => h('div', slots.default?.({ row })) },
  }))
  return renderToString(app)
}
for (const ip of ['203.0.113.17', '2001:db8::19']) {
  const html = await cell({ lastLoginIp: ip, lastLoginRegion: '新加坡' })
  assert(html.includes(ip))
  assert(html.includes('新加坡'))
}
for (const row of [{}, { lastLoginIp: null, lastLoginRegion: null }, { lastLoginIp: '', lastLoginRegion: '' }]) {
  assert.equal((await cell(row)).match(/>-</g)?.length, 2, 'missing IP and region use placeholders')
}
const noRegion = await cell({ lastLoginIp: '203.0.113.17' })
assert(noRegion.includes('203.0.113.17'))
assert.equal(noRegion.match(/>-</g)?.length, 1)
const escaped = await cell({ lastLoginIp: '<script>bad</script>', lastLoginRegion: '<img src=x>' })
assert(escaped.includes('&lt;script&gt;'))
assert(escaped.includes('&lt;img'))
assert.doesNotMatch(escaped, /<script>|<img /)
const defaults = ['id', 'userRemark', 'lastLoginIp'].map(id => ({ id, label: id, visible: true, fixed: '' }))
const merged = mergeColumns(defaults, [{ id: 'userRemark', visible: false, fixed: '' }, { id: 'id', visible: true, fixed: '' }])
assert.equal(merged.find(item => item.id === 'userRemark').visible, false)
assert.equal(merged.find(item => item.id === 'lastLoginIp').visible, true, 'new column appears without resetting saved preferences')
console.log('PASS OnlineUsers IP: IPv4/IPv6, stored region, missing values, escaped text and saved-column compatibility')
