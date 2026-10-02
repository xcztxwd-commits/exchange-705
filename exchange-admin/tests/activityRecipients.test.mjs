import assert from 'node:assert/strict'
import { emptyRecipientFilter, recipientFilter, recipientIds, mergeActivityRecipients, recipientPage, activitySelection } from '../src/utils/activityRecipients.ts'
import { readActivityTranslations, validateActivityCopy, syncActivityCopy } from '../src/utils/activityContent.ts'
import { activitySettings, validateActivitySettings, assertActivitySettingsSaved, activityPositions, activityTriggers } from '../src/utils/activitySettings.ts'
const filter=emptyRecipientFilter()
assert.equal(recipientFilter({...filter,query:' user@example.test ',claimedCampaignIds:[3,3,4]}).query,'user@example.test')
assert.deepEqual(recipientFilter({...filter,claimedCampaignIds:[3,3,4]}).claimedCampaignIds,[3,4])
for(const fields of [{createdFrom:'2026-10-02T00:00:00',createdTo:'2026-10-01T00:00:00'},{lastLoginFrom:'invalid'}, {claimedCampaignIds:[-1]}, {claimedMode:'UNKNOWN'}, {query:'x'.repeat(129)}])assert.throws(()=>recipientFilter({...filter,...fields}))
assert.deepEqual(recipientIds([7,7,8]),[7,8])
for(const id of [-1,0,NaN,2.5,Number.MAX_SAFE_INTEGER+1])assert.throws(()=>recipientIds([id]))
const users=Array.from({length:1205},(_,i)=>({id:i+1,email:`u${i+1}@synthetic.test`}))
assert.equal(mergeActivityRecipients(users,users.slice(0,20)).length,1205,'no 1000 cap')
assert.deepEqual(mergeActivityRecipients([{id:7,email:'old'}],[{id:7,email:'updated'},{id:8,email:'new'}]).map(u=>u.email),['updated','new'])
assert.equal(recipientPage({content:users.slice(20,40),totalElements:1205}).totalElements,1205)
assert.throws(()=>recipientPage(users.slice(0,20)),/分页契约/)
assert.throws(()=>recipientPage({content:users.slice(0,2),totalElements:1}),/总数无效/)
assert.equal(recipientPage({content:[{userId:8,email:'selected'}],totalElements:1}).content[0].id,8)
assert.equal(activitySelection({selectionId:1,selected:1205}).totalElements,1205)
assert.throws(()=>activitySelection({selectionId:1,totalElements:1205}))
const copy={title:'旧标题',body:'正文',terms:'细则',open:'打开',close:'关闭',claim:'领取',success:'成功'}
const translations={'zh-CN':copy,en:{...copy,title:'English'}}
const draft=readActivityTranslations(JSON.stringify(translations),{})
draft['zh-CN'].title='未保存';assert.equal(copy.title,'旧标题')
assert.throws(()=>readActivityTranslations('bad',{}));assert.throws(()=>readActivityTranslations('[]',{}))
validateActivityCopy(translations,'zh-CN');assert.throws(()=>validateActivityCopy(translations,'missing'))
for(const value of ['', 'x'.repeat(161)])assert.throws(()=>validateActivityCopy({'zh-CN':{...copy,title:value}},'zh-CN'))
const pages=[{nodes:[{type:'text',text:'旧标题',style:{color:'#111'},children:[{type:'text',text:'旧标题',style:{'font-weight':'700'}}]},{type:'text',text:'自定义标题'}]}]
syncActivityCopy(pages,copy,{title:'新标题'})
assert.equal(copy.title,'新标题');assert.equal(pages[0].nodes[0].text,'新标题');assert.equal(pages[0].nodes[0].children[0].style['font-weight'],'700');assert.equal(pages[0].nodes[1].text,'自定义标题')
const settings=activitySettings({amount:300,budget:300000,maxClaims:1000,status:'DRAFT',autoSendEnabled:false})
assert.equal(settings.startsAt,null);assert.equal(settings.endsAt,null);assert.equal(activitySettings({}).autoSendEnabled,false);assert.equal(settings.allowRepeatClaim,false);assert.equal(activityPositions.length,5);validateActivitySettings(settings);assert.equal(activitySettings({claimValidityDays:null}).claimValidityDays,null);validateActivitySettings({...settings,claimValidityDays:null,positions:activityPositions.map(p=>p.value)})
assert.throws(()=>validateActivitySettings({...settings,autoSendEnabled:true}),/自动发送须配置/)
validateActivitySettings({...settings,autoSendEnabled:true,startsAt:'2026-10-01T00:00:00',endsAt:'2026-10-02T00:00:00',triggerConditions:[activityTriggers[0].value]})
for(const changed of [{recentLoginDays:-1},{recentLoginDays:1.5},{budget:300000.123},{claimValidityDays:0},{claimValidityDays:1.5},{positions:['evil']},{positions:[]},{triggerConditions:['API:EVIL']},{amount:-1},{budget:0},{maxClaims:0},{startsAt:'2026-10-02T00:00:00',endsAt:'2026-10-01T00:00:00'}])assert.throws(()=>validateActivitySettings({...settings,...changed}))
assertActivitySettingsSaved({amount:300,startsAt:'2026-10-01T00:00:00',positions:['AUTH_HOME','SUPPORT']},{amount:'300.00',startsAt:'2026-10-01T00:00:00Z',positions:['SUPPORT','AUTH_HOME']})
assert.throws(()=>assertActivitySettingsSaved({autoPopup:true},{autoPopup:false}),/autoPopup/)
assert.throws(()=>assertActivitySettingsSaved({amount:300},{}),/amount/)
console.log('PASS T03 pure functions: filters, include/exclude, stable dedup, 1205 recipients, strict pagination, copy isolation/validation/sync, five positions, repeat defaults, trigger required linkage')
