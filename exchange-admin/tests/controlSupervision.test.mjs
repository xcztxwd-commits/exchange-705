import assert from 'node:assert/strict'
import { configEditable } from '../src/utils/tenantPolicies.ts'
const policy={tenantId:2,tenantName:'A',status:'ACTIVE',policyVersion:1,features:{external_support:true},supportChannel:null,configs:[]}
assert.equal(configEditable(null,'mail.host'),false)
assert.equal(configEditable(policy,'mail.host'),true)
assert.equal(configEditable({...policy,configs:[{key:'mail.host',locked:true,denied:false}]},'mail.host'),false)
assert.equal(configEditable({...policy,configs:[{key:'mail.host',locked:false,denied:true}]},'mail.host'),false)
assert.equal(configEditable({...policy,supportChannel:'internal'},'customer.service.link'),false)
assert.equal(configEditable({...policy,features:{external_support:false}},'customer.service.link'),false)
assert.equal(configEditable({...policy,supportChannel:'internal'},'complaint.email'),true)
const values=new Map()
globalThis.sessionStorage={getItem:k=>values.get(k)||null,setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)}
globalThis.window={opener:null}
const {controlSession,api,apiFile}=await import('../src/control/api.ts')
const session=token=>({token,user:{account:'control'},expiresAt:Date.now()+60000})
controlSession.value=session('A')
let destination,options
globalThis.fetch=async (path,opts)=>{destination=path;options=opts;return new Response(new Uint8Array([137,80,78,71]),{headers:{'Content-Type':'image/png'}})}
const result=await apiFile('/control/tenants/2/support/conversations/3/images/4')
assert.equal(destination,'/api/control/tenants/2/support/conversations/3/images/4')
assert.equal(options.headers.Authorization,'Bearer A');assert.equal(options.credentials,'omit');assert.equal(options.redirect,'error');assert.equal(options.cache,'no-store');assert.equal(result.blob.type,'image/png')
for(const path of ['https://foreign.test/api/control/tenants','/admin/auth/me','/control/../admin/auth/me','/control/\\evil'])await assert.rejects(()=>apiFile(path))
globalThis.fetch=async()=>{controlSession.value=session('B');return new Response('data',{headers:{'Content-Type':'image/png'}})}
await assert.rejects(()=>apiFile('/control/tenants/2/support/conversations/3/images/4'),/已变更/)
assert.equal(controlSession.value.token,'B')
globalThis.fetch=async()=>new Response('{}',{status:401,headers:{'Content-Type':'application/json'}})
await assert.rejects(()=>api('/control/auth/me'));assert.equal(controlSession.value,null)
controlSession.value={...session('expired'),expiresAt:Date.now()-1}
let calls=0;globalThis.fetch=async()=>{calls++;throw Error('must not call')}
await assert.rejects(()=>apiFile('/control/tenants/2/support/conversations/3/images/4'));assert.equal(calls,0);assert.equal(controlSession.value,null)
console.log('PASS control attachments: same-origin bearer, no cookies/redirects/cache, stale token discard, expiry fail-closed; policy locks and forced internal channel')

controlSession.value={...session('timer'),expiresAt:Date.now()+15};await new Promise(resolve=>setTimeout(resolve,40));assert.equal(controlSession.value,null,'absolute expiry must clear visible authenticated content without another request');console.log('PASS control absolute expiry auto-clears session')
