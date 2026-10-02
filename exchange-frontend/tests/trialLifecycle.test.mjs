import test from 'node:test'
import assert from 'node:assert/strict'
import { utcMillis, createServerClock, trialState, reconcileFunding, selectedAvailable, fundingPositions, countdown, activityPosition, matchesPosition } from '../src/utils/trialLifecycle.ts'
import { beginClaim, resumableClaim, readClaim, saveClaim, cancelClaim, claimEndpoint, CLAIM_TTL } from '../src/utils/claimContinuation.ts'
const now=Date.parse('2026-09-30T13:00:00Z')
const data={trialEligible:true,trialExpiresAt:'2026-09-30T13:00:02',serverNow:'2026-09-30T13:00:00',trialAvailable:300,trialFrozen:20,contractBalance:17,optionBalance:11,totalAssets:99999,fundingSources:['TRIAL','CONTRACT','OPTION'],grants:[{id:1,active:true,available:300,frozen:20,expiresAt:'2026-09-30T13:00:02'}]}
test('suffix-less grants parse as UTC, never host local time',()=>{
 assert.equal(utcMillis('2026-09-30T13:00:00'),now)
 assert.equal(utcMillis('2026-09-30T13:00:00.123456789'),now+123)
 assert.equal(utcMillis('2026-09-30T21:00:00+08:00'),now)
 assert.ok(Number.isNaN(utcMillis('garbage')))
})
test('server clock, conservative round trip, exact deadline and local clock changes',()=>{
 const clock=createServerClock();clock.sync(data.serverNow,100,200)
 assert.equal(clock.now(200),now+100)
 const original=Date.now;Date.now=()=>1
 try{assert.equal(clock.now(2100),now+2000);assert.equal(trialState(data,clock.now(2100)).eligible,false)}finally{Date.now=original}
 assert.equal(trialState(data,now+1999).eligible,true)
 clock.sync('2026-09-30T12:59:00',2100,2200);assert.ok(clock.now(2200)>=now+2100)
 clock.reset();assert.ok(Number.isNaN(clock.now(2300)))
 const slow=createServerClock();slow.sync(data.serverNow,0,2500);assert.equal(trialState(data,slow.now(2500)).eligible,false)
})
test('partial expiration preserves later grant, legacy no-expiry and frozen eligibility',()=>{
 const rows={...data,trialAvailable:500,grants:[...data.grants,{id:2,active:true,available:200,frozen:0,expiresAt:'2026-10-03T13:00:00'}]}
 assert.equal(trialState(rows,now+2000).available,200)
 assert.equal(trialState({...data,trialAvailable:0,grants:[{active:true,frozen:20,available:0,expiresAt:null}]},now+10000).eligible,true)
 assert.equal(trialState({...data,trialEligible:false},now).eligible,false)
 assert.equal(trialState(data,now,'DEMO').eligible,false)
 assert.equal(trialState({...data,serverNow:null},NaN).eligible,false)
})
test('default trial; explicit cash persists; expiry switches safely; modes independent',()=>{
 const live=trialState(data,now),dead=trialState(data,now+2000)
 assert.deepEqual(reconcileFunding({source:'CONTRACT',manual:false},'CONTRACT',live),{source:'TRIAL',manual:false})
 assert.deepEqual(reconcileFunding({source:'CONTRACT',manual:true},'CONTRACT',live),{source:'CONTRACT',manual:true})
 assert.deepEqual(reconcileFunding({source:'TRIAL',manual:true},'OPTION',dead),{source:'OPTION',manual:true})
 assert.equal(reconcileFunding({source:'TRIAL',manual:false},'CONTRACT',live,'DEMO').source,'CONTRACT')
 assert.equal(selectedAvailable(data,'TRIAL',live),300);assert.equal(selectedAvailable(data,'CONTRACT',live),17);assert.equal(selectedAvailable(data,'OPTION',live),11)
 assert.ok(Number.isNaN(selectedAvailable({totalAssets:9999},'CONTRACT',live)))
 assert.deepEqual(fundingPositions([{id:1,status:'OPEN',fundingSource:'TRIAL'},{id:2,status:'OPEN',fundingSource:'CONTRACT'},{id:3,status:'OPEN',fundingSource:null}], 'TRIAL').map(x=>x.id),[1])
 assert.equal(countdown(now+2000,now),'00:00:02');assert.equal(countdown(now+2000,now+2000),'00:00:00')
})
test('exactly five placements with legacy home fallback; inbox reading has no event',()=>{
 assert.equal(activityPosition('/home',false),'ANONYMOUS_HOME');assert.equal(activityPosition('/home',true),'AUTH_HOME')
 assert.equal(activityPosition('/trade',true),'AUTH_TRADE');assert.equal(activityPosition('/profile',true),'AUTH_PROFILE');assert.equal(activityPosition('/customer-service',true),'SUPPORT')
 assert.equal(activityPosition('/login',false),null);assert.equal(activityPosition('/inbox',true),null)
 assert.equal(matchesPosition({},'AUTH_HOME'),true);assert.equal(matchesPosition({positions:['AUTH_TRADE']},'AUTH_HOME'),false)
})
function storage(){const map=new Map();return {getItem:k=>map.get(k)||null,setItem:(k,v)=>map.set(k,v),removeItem:k=>map.delete(k)}}
test('anonymous login/registration retry and refresh reuse key, deliberate repeat separate',()=>{
 const s=storage(),a=beginClaim(s,1,2,1000,true,false,'aaaaaaaaaaaaaaaa')
 assert.equal(beginClaim(s,1,2,1100,true,false,'bbbbbbbbbbbbbbbb').actionId,a.actionId)
 assert.equal(resumableClaim(s,1,9,'REAL',1200).actionId,a.actionId)
 a.user=9;a.state='failed';saveClaim(s,a)
 assert.equal(resumableClaim(s,1,9,'REAL',1300).actionId,a.actionId)
 assert.equal(resumableClaim(s,1,10,'REAL',1300),null)
 assert.equal(resumableClaim(s,1,9,'DEMO',1300),null)
 assert.equal(resumableClaim(s,2,9,'REAL',1300),null)
 assert.equal(claimEndpoint(a),'/activity/campaigns/2/claim')
 a.state='done';saveClaim(s,a)
 assert.equal(resumableClaim(s,1,9,'REAL',1400),null)
 assert.equal(beginClaim(s,1,2,1400,true,false,'bbbbbbbbbbbbbbbb').actionId,a.actionId)
 assert.equal(beginClaim(s,1,2,1400,true,true,'bbbbbbbbbbbbbbbb').actionId,'bbbbbbbbbbbbbbbb')
})
test('cancel, deadline, backwards clock, owner mismatch and message endpoint fail closed',()=>{
 const s=storage(),a=beginClaim(s,1,2,1000,true,false,'aaaaaaaaaaaaaaaa')
 assert.equal(readClaim(s,1,2,1000+CLAIM_TTL),null);assert.equal(readClaim(s,1,2,999),null)
 cancelClaim(s,1,2);assert.equal(resumableClaim(s,1,9,'REAL',1200),null)
 const m=beginClaim(s,1,3,2000,false,false,'cccccccccccccccc')
 assert.equal(claimEndpoint(m,8),'/activity/messages/8/claim');assert.throws(()=>claimEndpoint(m))
 assert.equal(resumableClaim(s,1,9,'REAL',2100),null)
})
