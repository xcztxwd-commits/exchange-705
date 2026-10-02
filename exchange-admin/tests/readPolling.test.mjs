import assert from 'node:assert/strict'
import { startReadPolling } from '../src/utils/readPolling.ts'
import fs from 'node:fs'
const realSet = globalThis.setTimeout, realClear = globalThis.clearTimeout
let nextId=0, timers=new Map(), calls=0, resolve
const delays=[]
globalThis.setTimeout=(fn,ms)=>{ const id=++nextId; timers.set(id,fn);delays.push(ms);return id }
globalThis.clearTimeout=id=>timers.delete(id)
const settle=async()=>{await Promise.resolve();await Promise.resolve()}
const tick=async()=>{ assert.equal(timers.size,1);const [id,fn]=timers.entries().next().value;timers.delete(id);fn();await settle() }
try {
  const stop=startReadPolling(()=>{calls++;return new Promise(r=>resolve=r)})
  assert.equal(calls,1);assert.equal(timers.size,0,'slow request cannot overlap')
  resolve(true);await settle();assert.equal(delays.at(-1),5000)
  await tick();assert.equal(calls,2);assert.equal(timers.size,0)
  resolve(false);await settle();assert.equal(delays.at(-1),10000)
  for(const delay of [20000,40000,60000,60000]){await tick();resolve(false);await settle();assert.equal(delays.at(-1),delay)}
  await tick();resolve(true);await settle();assert.equal(delays.at(-1),5000,'success resets backoff')
  await tick();stop();resolve(true);await settle();assert.equal(timers.size,0,'unmount during request must not rearm')
  const stop2=startReadPolling(async()=>true);await settle();assert.equal(timers.size,1);stop2();assert.equal(timers.size,0)
  const layout=fs.readFileSync(new URL('../src/views/Layout.vue',import.meta.url),'utf8')
  assert.match(layout,/startReadPolling\(loadPendingCounts\)/)
  assert.match(layout,/startReadPolling\(loadOnlineUserCount\)/)
  assert.doesNotMatch(layout,/updateTimer = window.setInterval/)
  assert.match(layout,/permissionTimer = window.setInterval/,'permission refresh cadence untouched')
  assert.match(layout,/token !== auth.token/,'late results do not cross sessions')
  console.log('PASS read polling: single flight, 5s cadence, failure backoff/cap, recovery, unmount; GET-only integration')
} finally { globalThis.setTimeout=realSet;globalThis.clearTimeout=realClear }
