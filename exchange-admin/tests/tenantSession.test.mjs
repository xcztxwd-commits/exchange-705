import assert from 'node:assert/strict'
import fs from 'node:fs'
import axios from 'axios'
import { imageLocation, privateImagePath } from '../../exchange-frontend/src/utils/imageLocation.ts'
import { validSession, readSession, clearAdminSession, ADMIN_SESSION_KEY, exactOrigin, matchesExchangeMessage, assertAdminRequestTarget } from '../src/utils/adminSession.ts'
import { pageCodeForPath, safePageCode, createPageReporter } from '../../exchange-frontend/src/utils/pageActivity.ts'
import { assertLocalCountdownSafety, readCountdownSources } from './localCountdownSafety.mjs'
class Storage {
  getItem(key) { return this[key] ?? null }
  setItem(key,value) { this[key] = String(value) }
  removeItem(key) { delete this[key] }
}
const a=new Storage(),b=new Storage(),now=Date.now()
const session=(tenantId,token)=>({token,user:{id:-1,tenantId,userType:'control'},mode:'control',accessSession:{id:`session-${tenantId}`,tenantId,tenantName:`租户${tenantId}`,expiresAt:now+60000}})
a.setItem(ADMIN_SESSION_KEY,JSON.stringify(session(2,'a-token')));b.setItem(ADMIN_SESSION_KEY,JSON.stringify(session(3,'b-token')))
assert.equal(readSession(a).token,'a-token');assert.equal(readSession(b).token,'b-token')
clearAdminSession(a);assert.equal(readSession(a),null);assert.equal(readSession(b).token,'b-token','closing A cannot change B')
const expired=session(2,'expired');expired.accessSession.expiresAt=now-1;a.setItem(ADMIN_SESSION_KEY,JSON.stringify(expired));a.setItem('admin_token','legacy-token')
assert.equal(readSession(a),null,'never fall back to legacy or another identity')
assert.equal(validSession({...session(2,'a'),accessSession:session(3,'b').accessSession}),false)
assert.equal(validSession({token:'old',user:{id:1},mode:'admin'}),false,'tenant required')
assert.equal(validSession({token:'normal',user:{id:1,tenantId:2,userType:'admin'},mode:'admin'}),true)
assert.equal(validSession({...session(2,'a'),mode:'admin'}),false)
a.setItem(ADMIN_SESSION_KEY,'invalid json');assert.equal(readSession(a),null)
a.setItem('deposit-pending:2:control:1','money');a.setItem('locale','zh');clearAdminSession(a);assert.equal(a.getItem('deposit-pending:2:control:1'),null);assert.equal(a.getItem('locale'),'zh')
assert.equal(exactOrigin('https://control.example.com'),'https://control.example.com')
for(const value of ['https://control.example.com.evil/path','https://user:pass@control.example.com','javascript:alert(1)','http://control.example.com','https://control.example.com/?token=secret']) assert.throws(()=>exactOrigin(value))
assert.equal(exactOrigin('http://localhost:5177'),'http://localhost:5177')
const opener={},origin='https://control.example.com',challenge='challenge'
const data={type:'control-exchange-ticket',challenge,ticket:'one-use-ticket',browserBinding:'a'.repeat(64),tenantId:2}
assert(matchesExchangeMessage({source:opener,origin,data},opener,origin,challenge))
assert(!matchesExchangeMessage({source:{},origin,data},opener,origin,challenge),'wrong source')
assert(!matchesExchangeMessage({source:opener,origin:origin+'.evil',data},opener,origin,challenge),'origin suffix')
assert(!matchesExchangeMessage({source:opener,origin,data:{...data,challenge:'old'}},opener,origin,challenge),'old handshake')
assert(!matchesExchangeMessage({source:opener,origin,data:{...data,tenantId:0}},opener,origin,challenge))
assert.equal(pageCodeForPath('/assets?otp=123&token=private'),'assets')
assert.equal(pageCodeForPath('/unknown/customer/123'),'unknown')
assert.equal(safePageCode('support?message=private'),'unknown')
let visible=false;const calls=[];const report=createPageReporter(async payload=>calls.push(payload),'MOBILE',()=>visible)
await report('assets');assert.equal(calls.length,0,'hidden pages cannot manufacture activity')
visible=true;await report();assert.equal(calls[0].pageCode,'assets');assert.deepEqual(Object.keys(calls[0]).sort(),['deviceType','pageCode','sequence'])
await report('support');assert(calls[1].sequence>calls[0].sequence)
for(const app of ['exchange-frontend','exchange-pc']) {
 const source=fs.readFileSync(new URL(`../../${app}/src/App.vue`,import.meta.url),'utf8')
 assertLocalCountdownSafety(readCountdownSources(app))
 assert.match(source,/visibilitychange/);assert.match(source,/route.path/);assert.doesNotMatch(source,/route\.fullPath|route\.query(?!\.edition\b)/)
}
const auth=fs.readFileSync(new URL('../src/store/auth.ts',import.meta.url),'utf8')
assert.doesNotMatch(auth,/localStorage\.getItem\('admin_token'\)/)
assert.match(auth,/if \(exchangeOpener \|\|/,'opener copy cleared before session initialization')
const exchange=fs.readFileSync(new URL('../src/views/ControlExchange.vue',import.meta.url),'utf8')
assert.match(exchange,/consumed = true/);assert.match(exchange,/expectedTenantId/)
console.log('PASS tenant frontend: tab separation, opener cleanup, expiry/no fallback, tenant mismatch, exact source/origin/challenge, page whitelist, hidden-page suppression, no permanent heartbeat')

const gateway=axios.create({baseURL:'/api'}),site='https://admin.example.test'
for(const url of ['https://evil.test/steal','//evil.test/steal','/../control/tenants']) {
 const target=new URL(gateway.getUri({url}),site)
 assert.throws(()=>assertAdminRequestTarget(target,site))
}
assert.doesNotThrow(()=>assertAdminRequestTarget(new URL(gateway.getUri({url:'/admin/users'}),site),site))
for(const url of ['https://evil.test/uploads/images/2/user/1/a.png','/uploads/images/2/user/1/a.png?token=secret']) assert.equal(imageLocation(url,site),'')
assert.equal(privateImagePath('/uploads/images/2/user/1/a.png',site),'/api/uploads/images/2/user/1/a.png')
assert.equal(imageLocation('/uploads/images/2/user/1/a.png',site,'DEMO'),'/demo-uploads/images/2/user/1/a.png')
a.setItem('balance-pending:2:control:1','money');clearAdminSession(a);assert.equal(a.getItem('balance-pending:2:control:1'),null)
console.log('PASS bearer destination and private image origin restrictions; balance request opener cleanup')

const { allowsNewBusiness, simulationSessionMatches } = await import('../../exchange-frontend/src/utils/tenantCapabilities.ts')
const active = {tenantId:2,status:'ACTIVE',acceptNewBusiness:true,features:{contract:true,option:false,simulation:true}}
assert(allowsNewBusiness(active,'contract'))
for (const snapshot of [null,{...active,tenantId:0},{...active,status:'STOP_NEW'},{...active,status:'MAINTENANCE'},{...active,acceptNewBusiness:false}]) assert(!allowsNewBusiness(snapshot,'contract'))
assert(!allowsNewBusiness(active,'option'));assert(!allowsNewBusiness(active,'unknown'))
assert(simulationSessionMatches({tenantId:2,userId:7,environment:'DEMO'},'account-mode:2:7'))
assert(!simulationSessionMatches({tenantId:3,userId:7,environment:'DEMO'},'account-mode:2:7'))
assert(!simulationSessionMatches({tenantId:2,userId:7,environment:'REAL'},'account-mode:2:7'))
for(const app of ['exchange-frontend','exchange-pc']) {
 const source=fs.readFileSync(new URL(`../../${app}/src/components/AccountModeSwitch.vue`,import.meta.url),'utf8')
 assert.match(source,/demo \|\| canStartBusiness/,'must never hide exit from a disabled simulation account')
 assert.match(source,/simulationSessionMatches/,'switch validates tenant and user, not only user ID')
}
console.log('PASS feature snapshot fail-closed, new-business status gate and tenant-bound simulation switch')

assert.equal(privateImagePath('/demo-uploads/images/2/user/1/a.png',site),'/demo-uploads/images/2/user/1/a.png')
assert.equal(imageLocation('https://evil.test/demo-uploads/images/2/user/1/a.png',site),'')
assert.equal(imageLocation('/demo-uploads/images/2/user/1/a.png?token=secret',site),'')
console.log('PASS demo uploads use authenticated fetch and reject remote/query credential paths')

const { transform } = await import('esbuild')
const { parse } = await import('@vue/compiler-sfc')
const managerSource=fs.readFileSync(new URL('../src/control/TenantManager.vue',import.meta.url),'utf8')
const block=parse(managerSource).descriptor.scriptSetup.content
const readCounts=block.match(/async function readCounts\(\) \{[\s\S]*?\n\}/)[0]
const compiled=(await transform(readCounts,{loader:'ts',target:'es2022'})).code
const runCounts=new Function('api','document','tenants','counts','totalOnline','countsAt','countsError',`let countsGeneration=0,disposed=false;${compiled};return readCounts()`)
const value=()=>({value:null}), onlineCounts=value(), onlineTotal=value(), onlineAt=value(), onlineError=value()
await runCounts(async path=>({data:{total:path.includes('/2/')?2:3}}),{visibilityState:'visible'},{value:[{id:2},{id:3}]},onlineCounts,onlineTotal,onlineAt,onlineError)
assert.equal(onlineTotal.value,5);assert.deepEqual(onlineCounts.value,{2:2,3:3})
await runCounts(async path=>{if(path.includes('/3/'))throw new Error('unavailable');return {data:{total:2}}},{visibilityState:'visible'},{value:[{id:2},{id:3}]},onlineCounts,onlineTotal,onlineAt,onlineError)
assert.equal(onlineTotal.value,null,'unknown tenant must never be counted as zero');assert.deepEqual(onlineCounts.value,{2:2,3:null});assert(onlineError.value)
let hiddenCalls=0
await runCounts(async()=>{hiddenCalls++;return {data:{total:4}}},{visibilityState:'hidden'},{value:[{id:2}]},onlineCounts,onlineTotal,onlineAt,onlineError)
assert.equal(hiddenCalls,0)
console.log('PASS actual control overview counter: per-tenant sum, failure unknown, hidden suppression')
