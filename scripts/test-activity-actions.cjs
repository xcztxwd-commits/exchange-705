const assert=require('node:assert/strict');const fs=require('node:fs');const vm=require('node:vm');const {transformSync}=require('../exchange-admin/node_modules/esbuild');
const source=fs.readFileSync(require('node:path').join(__dirname,'../exchange-frontend/src/utils/activityActions.ts'),'utf8').replace(/^import .*$/gm,'').replace(/export /g,'');const sandbox={};vm.createContext(sandbox);vm.runInContext(transformSync(source+';globalThis.api={nodeActions,validateActions,runDesignActions}',{loader:'ts',target:'es2020'}).code,sandbox);const {runDesignActions,validateActions,nodeActions}=sandbox.api;
const pages=['gift','detail','success','extra'].map(id=>({id}));
(async()=>{
 let log=[],active=true;const context=()=>({pages,current:'gift',claimed:false,eligible:true,active:()=>active,call:async(t)=>{log.push(t)},navigate:p=>log.push('page:'+p)});
 await runDesignActions([{type:'read'},{type:'claim'},{type:'page',target:'success'}],context());assert.deepEqual(log,['read','claim','page:success']);
 log=[];await assert.rejects(()=>runDesignActions([{type:'read'},{type:'claim'},{type:'page',target:'success'}],{...context(),call:async(t)=>{log.push(t);if(t==='claim')throw Error('claim failed')}}),/claim failed/);assert.deepEqual(log,['read','claim']);
 log=[];await assert.rejects(()=>runDesignActions([{type:'read'},{type:'close'}],{...context(),call:async()=>{throw Error('read failed')}}),/read failed/);assert.deepEqual(log,[]);
 log=[];await runDesignActions([{type:'next'}],context());assert.deepEqual(log,['page:detail']);log=[];await runDesignActions([{type:'next'}],{...context(),current:'detail'});assert.deepEqual(log,['page:extra']);
 log=[];await runDesignActions([{type:'previous'}],{...context(),current:'extra'});assert.deepEqual(log,['page:detail']);
 await assert.rejects(()=>runDesignActions([{type:'previous'}],context()),/第一页/);
 log=[];await runDesignActions([{type:'claim'}],{...context(),pages:pages.filter(p=>p.id!=='success')});assert.deepEqual(log,['claim','close']);
 log=[];await runDesignActions([{type:'claim'},{type:'page',target:'success'}],{...context(),claimed:true});assert.deepEqual(log,['page:success']);
 log=[];await assert.rejects(()=>runDesignActions([{type:'claim'}],{...context(),eligible:false}),/不可领取/);assert.deepEqual(log,[]);
 log=[];await assert.rejects(()=>runDesignActions([{type:'read'},{type:'claim'}],{...context(),call:async(t)=>{log.push(t);active=false}}),/已关闭/);assert.deepEqual(log,['read']);active=true;
 for(const steps of [[{type:'page',target:'success'}],[{type:'close'},{type:'claim'}],[{type:'claim'},{type:'claim'}],[{type:'fetch',target:'https://evil.test'}],[{type:'link',target:'//evil.test'}],[]])assert.throws(()=>validateActions(steps,pages));
 assert.equal(nodeActions({action:'page',target:'detail'})[0].type,'read');
 console.log('PASS action flows: sequence, API failures halt navigation, next/previous, idempotent claimed state, eligibility, cancellation, legacy actions, unsafe actions rejected');
})().catch(e=>{console.error(e);process.exitCode=1});
