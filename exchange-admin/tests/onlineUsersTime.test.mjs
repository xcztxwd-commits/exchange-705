import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse } from '@vue/compiler-sfc'
import { transform } from 'esbuild'

const source = fs.readFileSync(new URL('../src/components/OnlineUsers.vue', import.meta.url), 'utf8')
const script = parse(source).descriptor.scriptSetup.content
const definition = script.match(/(?:const time =[^\n]+|function time\([\s\S]*?\n\})/)
assert(definition, 'test the actual shared OnlineUsers formatter')
const compiled = (await transform(definition[0], { loader: 'ts', target: 'es2022' })).code
const time = new Function(`${compiled}; return time`)()
const originalTimezone = process.env.TZ
let checks = 0
function equal(actual, expected, label) { assert.equal(actual, expected, label); checks++ }
try {
  for (const timezone of ['Asia/Singapore', 'UTC', 'America/New_York']) {
    process.env.TZ = timezone
    // LocalDateTime carries no offset: preserve the server's wall clock in every browser zone.
    equal(time('2026-09-29T16:12:00'), '2026-09-29 16:12:00', `${timezone}: no invented UTC or next-day shift`)
    equal(time('2026-09-29T16:11:59.123456789'), '2026-09-29 16:11:59', `${timezone}: Java nanosecond precision`)
    equal(time('2026-09-29T16:12'), '2026-09-29 16:12', `${timezone}: omitted zero seconds`)
    equal(time('2026-03-08T02:30:00'), '2026-03-08 02:30:00', `${timezone}: browser DST must not rewrite server wall time`)
    for (const value of ['2026-09-29T08:12:00Z', '2026-09-29T08:12:00z', '2026-09-29T16:12:00+08:00', '2026-09-29T16:12:00+0800', '2026-09-29T03:12:00-05:00']) {
      equal(time(value), new Date(value).toLocaleString(), `${timezone}: honor explicit offset ${value}`)
    }
    for (const value of [undefined, null, '', ' ', 'not-a-date', '2026-13-29T16:12:00']) {
      equal(time(value), '未知', `${timezone}: missing/invalid input`)
    }
  }
  assert.match(source, /未标时区的时间保留服务器时间/)
  assert.match(source, /已标时区的时间转换为本机时间/)
  for (const field of ['asOf', 'scope.row.lastPageSeenAt', 'scope.row.lastActiveAt']) {
    assert(source.includes(`time(${field})`), `${field} uses the same formatter`)
  }
  for (const consumer of ['control/TenantManager.vue', 'views/Layout.vue']) {
    const view = fs.readFileSync(new URL(`../src/${consumer}`, import.meta.url), 'utf8')
    assert.match(view, /import OnlineUsers from '@\/components\/OnlineUsers\.vue'/)
  }
  console.log(`PASS OnlineUsers time: ${checks} branch checks across 3 timezones; all 3 timestamps and control/admin reuse verified`)
} finally {
  if (originalTimezone === undefined) delete process.env.TZ
  else process.env.TZ = originalTimezone
}
