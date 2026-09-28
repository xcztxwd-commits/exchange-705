import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import { generationConstraints, generationRequest, generationConstraintError } from '../src/utils/manualOrderGeneration.ts'
import { manualOrderEstimate } from '../src/utils/manualOrderEstimate.ts'
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript'), vue = require('vue')
// Exercise the actual component script with Vue reactivity and deferred HTTP, without mounting a fake UI.
const source = fs.readFileSync(new URL('../src/components/ManualContractOrder.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return {promise, resolve, reject} }
const pending=[]
const users=[]
const request={get:async(_url,options)=>{if(options?.params?.userId){const d=deferred();users.push(d);return d.promise}return {users:[],symbols:[],timezone:'UTC'}}, post:()=>{const d=deferred();pending.push(d);return d.promise}}
const names=['computed','reactive','ref','watch','onBeforeUnmount','nextTick','request','generationConstraints','generationRequest','generationConstraintError','manualOrderEstimate','defineEmits','defineExpose','ElMessage']
const scope=vue.effectScope()
const component=scope.run(()=>new Function(...names,js+';return {open,generate,chooseUser,account,form,conditions,generating,busy,visible,result};')(...[vue.computed,vue.reactive,vue.ref,vue.watch,()=>{},vue.nextTick,request,generationConstraints,generationRequest,generationConstraintError,manualOrderEstimate,()=>()=>{},()=>{},{success(){},warning(){}}]))
try {
  await component.open();component.form.userId=1;component.form.symbol='FIXTUREUSD';await vue.nextTick()
  component.conditions.net='80';const old=component.generate();assert.equal(component.generating.value,true)
  component.visible.value=false;await vue.nextTick();await component.open()
  assert.equal(component.generating.value,false,'Reopening must not inherit an old in-flight generation lock')
  component.form.userId=2;component.form.symbol='FIXTUREUSD';await vue.nextTick();component.conditions.net='90'
  const next=component.generate();assert.equal(pending.length,2)
  pending[0].reject(new Error('old timeout'));await old
  assert.equal(component.generating.value,true,'Old request finally must not unlock a newer generation')
  assert.equal(component.result.value,null)
  pending[1].reject(new Error('current timeout'));await next
  assert.equal(component.generating.value,false)
  assert.equal(component.conditions.net,'90');assert.equal(component.form.userId,2)
  component.form.userId=1;const user1=component.chooseUser();component.form.userId=2;const user2=component.chooseUser()
  users[1].resolve({account:{available:-10}});await user2;users[0].resolve({account:{available:1000}});await user1
  assert.equal(component.account.value.available,-10,'Late response from previous user must not replace current negative balance')
  console.log('PASS close/reopen while generating; old timeout cannot unlock new request; fixed inputs preserved')
} finally {scope.stop()}
