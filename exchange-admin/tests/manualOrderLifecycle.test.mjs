import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import { generationConstraints, generationRequest, generationConstraintError } from '../src/utils/manualOrderGeneration.ts'
import { manualOrderEstimate } from '../src/utils/manualOrderEstimate.ts'
const require = createRequire(new URL('../package.json', import.meta.url))
const ts = require('typescript'), vue = require('vue')
// Exercise the actual component script with Vue reactivity and deferred HTTP, without mounting a fake UI.
const file = fs.readFileSync(new URL('../src/components/ManualContractOrder.vue', import.meta.url), 'utf8')
const source = file.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return {promise, resolve, reject} }
const pending=[]
const users=[]
const request={get:async(_url,options)=>{if(options?.params?.userId){const d=deferred();users.push(d);return d.promise}return {users:[],symbols:[],timezone:'UTC'}}, post:()=>{const d=deferred();pending.push(d);return d.promise}}
const names=['computed','reactive','ref','watch','onBeforeUnmount','nextTick','request','generationConstraints','generationRequest','generationConstraintError','manualOrderEstimate','defineEmits','defineExpose','ElMessage']
const scope=vue.effectScope()
const component=scope.run(()=>new Function(...names,js+';return {open,generate,chooseUser,account,form,conditions,generating,busy,visible,result,verifiedKey,basis,basisKey,quoteKey,quantityInput,percentInput,percent,netInput,drive,symbols,unitLabel,preview,historyLoading,error,clearFields};')(...[vue.computed,vue.reactive,vue.ref,vue.watch,()=>{},vue.nextTick,request,generationConstraints,generationRequest,generationConstraintError,manualOrderEstimate,()=>()=>{},()=>{},{success(){},warning(){}}]))
try {
  await component.open();assert.equal(component.form.leverage,'100');assert.equal(component.conditions.leverage,'100');component.form.userId=1;component.form.symbol='FIXTUREUSD';await vue.nextTick()
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
  component.conditions.net=''
  component.result.value={request:{leverage:component.form.leverage},calculation:{quantity:'26.84',percent:'67.94',net:'-42620.85906232614'}}
  component.verifiedKey.value=JSON.stringify(component.form)
  assert.equal(component.netInput.value,'-42620.85906232614','Calculated net must appear in input without losing decimal digits')
  assert.equal(component.quantityInput.value,'26.84','Calculated quantity must appear in input')
  assert.equal(component.percentInput.value,'67.94','Calculated percent must appear in input')
  assert.equal(component.percent.value,67.94,'Slider must follow calculated percent')
  assert.equal(generationRequest(component.form,component.conditions).targetNet,undefined,'Automatic display must not fix generation target')
  component.drive('NET','123')
  assert.equal(component.netInput.value,'123','Manual target must override automatic value')
  assert.equal(generationRequest(component.form,component.conditions).targetNet,'123')
  component.drive('NET','')
  assert.equal(component.netInput.value,'','Clearing target must clear input')
  assert.equal(generationRequest(component.form,component.conditions).targetNet,undefined)
  Object.assign(component.form,{driver:'PERCENT',input:'141',side:'SELL',leverage:'100'})
  await vue.nextTick()
  component.basis.value={quotes:{openPrice:157.582,closePrice:157.242,openRate:1,closeRate:1},lotSize:1000,feePerLot:30,walletBefore:43435.3098756}
  component.basisKey.value=component.quoteKey()
  const estimated=component.netInput.value
  assert.equal(estimated,String(manualOrderEstimate(component.form,component.basis.value).net),'Live estimate must fill input')
  assert.equal(component.quantityInput.value,String(manualOrderEstimate(component.form,component.basis.value).quantity))
  assert.equal(component.percentInput.value,String(manualOrderEstimate(component.form,component.basis.value).percent))
  component.form.input='142'
  assert.notEqual(component.netInput.value,estimated,'Input must follow changed allocation')
  component.drive('PERCENT','68')
  component.drive('NET','5000')
  assert.equal(component.conditions.percent,'68','Editing net must preserve the allocation target')
  assert.equal(component.netInput.value,'5000')
  assert.equal(component.quantityInput.value,String(manualOrderEstimate(component.form,component.basis.value).quantity))
  assert.equal(component.percentInput.value,'68')
  assert.equal(component.percent.value,Number(component.percentInput.value))
  assert.equal(generationRequest(component.form,component.conditions).percent,'68')
  assert.equal(generationRequest(component.form,component.conditions).targetNet,'5000')
  component.drive('QUANTITY','2.5')
  assert.equal(component.conditions.net,'5000','Editing quantity must preserve the net target')
  assert.equal(component.quantityInput.value,'2.5')
  assert.equal(component.percentInput.value,'68')
  assert.equal(component.netInput.value,'5000')
  assert.equal(generationRequest(component.form,component.conditions).quantity,'2.5')
  assert.equal(generationRequest(component.form,component.conditions).targetNet,'5000')
  component.drive('QUANTITY','')
  assert.equal(component.quantityInput.value,'')
  assert.equal(component.percentInput.value,'68')
  assert.equal(component.netInput.value,'5000')
  assert.equal(component.conditions.net,'5000','Clearing quantity must not clear the net target')
  Object.assign(component.conditions,generationConstraints())
  component.conditions.closePrice='112'
  assert.equal(generationRequest(component.form,component.conditions).targetClosePrice,'112')
  assert.equal(generationRequest(component.form,component.conditions).closeLocal,undefined)
  assert.match(file,/aria-label="目标平仓价"/)
  assert.match(file,/:aria-label="'数量（' \+ unitLabel \+ '）'"/)
  component.symbols.value.push({symbol:'BTCUSDT',quantity_unit_type:'BASE_ASSET',base_currency:'BTC'},{symbol:'AAPL',quantity_unit_type:'SHARE'},{symbol:'EURUSD=X',quantity_unit_type:'LOT'})
  for(const [symbol,unit] of [['BTCUSDT','BTC'],['AAPL','股'],['EURUSD=X','手']]) {
    component.form.symbol=symbol;await vue.nextTick();assert.equal(component.unitLabel.value,unit)
    assert.match(file,new RegExp(`:label="'数量（' \\+ unitLabel \\+ '）'"`))
  }
  for (const [input,label] of [['percentInput','仓位比例'],['netInput','目标净收益']])
    assert.match(file,new RegExp(`<el-input :model-value="${input}"[^>]*aria-label="${label}"`))
  // Reproduce the user's first allocation edit without any preceding generation or preview.
  await component.open();component.form.userId=7000017;component.form.symbol='JPY=X';await vue.nextTick()
  Object.assign(component.form,{openLocal:'2026-09-29T01:01',closeLocal:'2026-09-29T20:04',side:'SELL'})
  const first=pending.length;component.drive('PERCENT','47');await vue.nextTick()
  await new Promise(resolve=>setTimeout(resolve,450))
  assert.equal(pending.length,first+1,'Default 100x must allow the first allocation edit to preview automatically')
  pending[first].reject(Object.assign(new Error('历史行情正在加载，请稍后重新预览'),{response:{data:{code:425}}}))
  await new Promise(resolve=>setTimeout(resolve,0))
  assert.equal(component.historyLoading.value,true);assert.equal(component.error.value,'');assert.equal(component.busy.value,true)
  await new Promise(resolve=>setTimeout(resolve,1550))
  assert.equal(pending.length,first+2,'Historical conversion loading must retry without another click')
  const basis={quotes:{openPrice:'157.50599670410156',closePrice:'157.41299438476562',openRate:'0.006349',closeRate:'0.006352',marginRate:'1'},lotSize:'100000',feePerLot:'30',walletBefore:'248596.03034752337'}
  const actual=manualOrderEstimate(component.form,basis)
  pending[first+1].resolve({...basis,request:{...component.form},previewToken:'ready',calculation:actual})
  await new Promise(resolve=>setTimeout(resolve,0));await vue.nextTick()
  assert.equal(component.historyLoading.value,false);assert.equal(component.busy.value,false)
  assert.equal(component.netInput.value,String(actual.net))
  component.drive('PERCENT','60');await vue.nextTick()
  assert.notEqual(component.netInput.value,String(actual.net),'Dragging allocation must update net immediately using the current quotes')
  assert.equal(component.form.leverage,'100','Allocation must never change leverage')
  component.visible.value=false;await vue.nextTick()
  await component.open();component.form.userId=1;component.form.symbol='FIXTUREUSD';await vue.nextTick()
  Object.assign(component.form,{openLocal:'2026-09-29T01:01',closeLocal:'2026-09-29T20:04',side:'BUY'})
  component.drive('PERCENT','50');await vue.nextTick()
  const closed=pending.length,oldPreview=component.preview()
  pending[closed].reject(Object.assign(new Error('loading'),{response:{data:{code:425}}}))
  await new Promise(resolve=>setTimeout(resolve,0));assert.equal(component.historyLoading.value,true)
  component.visible.value=false;await vue.nextTick();await oldPreview
  assert.equal(pending.length,closed+1,'Closing must cancel pending retries and never request or create an order')
  assert.equal(component.historyLoading.value,false)
  await component.open();component.clearFields();assert.equal(component.form.leverage,'100');assert.equal(component.conditions.leverage,'100')
  console.log('PASS lifecycle; fixed default 100x; loading retries and cancellation; first-edit preview and immediate net updates; numeric targets coexist')
} finally {scope.stop()}
