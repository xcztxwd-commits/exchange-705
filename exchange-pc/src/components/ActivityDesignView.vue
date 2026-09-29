<script setup lang="ts">
import { computed, defineComponent, h, ref, watch, onBeforeUnmount } from 'vue'
import { getImageUrl } from '../utils/imageUrl'
import { parseDesign, safeStyle, designText, designMotionCss, type DesignNode } from '../utils/activityDesign'
import {nodeActions,runDesignActions} from '../utils/activityActions'
const props=defineProps<{ design: string; locale: string; fallback: string; stage: string; amount: number|string; days: number; busy?: boolean; eligible?: boolean; claimed?:boolean; runAction?:(action:string,target?:string)=>Promise<void> }>()
const emit=defineEmits<{ (e:'action',action:string,target?:string):void }>()
const pageId=ref(props.stage)
watch(()=>props.stage,value=>pageId.value=value)
const pages=computed(()=>{const d=parseDesign(props.design);return (d?.locales[props.locale]||d?.locales[props.fallback])?.pages||[]})
// With no success page, close only after the server-confirmed success stage.
watch([()=>props.stage,pages],()=>{if(props.stage==='success'&&!pages.value.some(p=>p.id==='success'))emit('action','close')},{immediate:true})
const page=computed(()=>pages.value.find(p=>p.id===pageId.value)||pages.value[0])
const source=(url?:string)=>getImageUrl(url)
const running=ref(false),actionError=ref(''),localClaimed=ref(false)
let alive=true
onBeforeUnmount(()=>{alive=false})
async function action(node:DesignNode){
 if(props.busy||running.value)return
 running.value=true;actionError.value=''
 try{await runDesignActions(nodeActions(node),{pages:pages.value,current:page.value!.id,claimed:!!props.claimed||localClaimed.value,eligible:!!props.eligible,active:()=>alive,
  call:async(type,target)=>{if(props.runAction)await props.runAction(type,target);else if(type==='claim')throw Error('领取接口未连接');else emit('action',type==='read'?'opened':type,target);if(type==='claim')localClaimed.value=true},
  navigate:id=>{pageId.value=id}
 })}catch(e:any){if(alive)actionError.value=e.message||'执行失败，请重试'}finally{running.value=false}
}

const Content=defineComponent({setup(){
 const render=(node:DesignNode):any=>{
  const motion={'data-design-motion':node.motion||'none'};const style:any=safeStyle(node.style);if(node.backgroundSrc&&source(node.backgroundSrc))style.backgroundImage=`url("${source(node.backgroundSrc)}")`
  const text=designText(node.text,props.amount,props.days)
  const content=node.children?.length&&node.type!=='box'?node.children.map(run=>h('span',{style:{whiteSpace:'pre-wrap',...safeStyle(run.style)}},designText(run.text,props.amount,props.days))):text
  if(node.type==='image')return h('img',{...motion,src:source(node.src),alt:text,style:{maxWidth:'100%',...style},referrerpolicy:'no-referrer'})
  if(node.type==='button')return h('button',{...motion,type:'button',style,disabled:props.busy||running.value||(nodeActions(node).some(a=>a.type==='claim')&&(!props.eligible&&!props.claimed&&!localClaimed.value)),onClick:()=>action(node)},content)
  return h('div',{...motion,style},node.type==='box'?(node.children||[]).map(render):content)
 }
 return ()=>h('section',{class:'design-document'},[h('style',designMotionCss),...(page.value?.nodes||[]).map(render)])
}})
</script>
<template><Content /><p v-if="actionError" role="alert" class="design-action-error">{{actionError}}</p></template>
<style scoped>.design-action-error{padding:12px;color:#a34433;background:#fff0ec;font-size:13px}.design-document{isolation:isolate;overflow:hidden;width:100%;overflow-wrap:anywhere}.design-document :deep(*){box-sizing:border-box}.design-document :deep(button){cursor:pointer}.design-document :deep(button:disabled){opacity:.5;cursor:not-allowed}.design-document :deep(button:focus-visible){outline:3px solid #387ae8;outline-offset:-3px}</style>
