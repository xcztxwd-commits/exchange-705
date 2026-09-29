<script setup lang="ts">
import { computed, defineComponent, h, ref, watch } from 'vue'
import { getImageUrl } from '../utils/imageUrl'
import { parseDesign, safeStyle, designText, type DesignNode } from '../utils/activityDesign'
const props=defineProps<{ design: string; locale: string; fallback: string; stage: string; amount: number|string; days: number; busy?: boolean; eligible?: boolean }>()
const emit=defineEmits<{ (e:'action',action:string,target?:string):void }>()
const pageId=ref(props.stage)
watch(()=>props.stage,value=>pageId.value=value)
const pages=computed(()=>{const d=parseDesign(props.design);return (d?.locales[props.locale]||d?.locales[props.fallback])?.pages||[]})
const page=computed(()=>pages.value.find(p=>p.id===pageId.value)||pages.value[0])
const source=(url?:string)=>getImageUrl(url)
function action(node:DesignNode){
 if(props.busy)return
 if(node.action==='claim'&&!props.eligible)return
 if(node.action==='page'&&node.target!=='success'&&pages.value.some(p=>p.id===node.target)){pageId.value=node.target!;emit('action','opened');return}
 if(['close','claim','link'].includes(node.action||''))emit('action',node.action!,node.target)
}
const Content=defineComponent({setup(){
 const render=(node:DesignNode):any=>{
  const style:any=safeStyle(node.style);if(node.backgroundSrc&&source(node.backgroundSrc))style.backgroundImage=`url("${source(node.backgroundSrc)}")`
  const text=designText(node.text,props.amount,props.days)
  const content=node.children?.length&&node.type!=='box'?node.children.map(run=>h('span',{style:{whiteSpace:'pre-wrap',...safeStyle(run.style)}},designText(run.text,props.amount,props.days))):text
  if(node.type==='image')return h('img',{src:source(node.src),alt:text,style:{maxWidth:'100%',...style},referrerpolicy:'no-referrer'})
  if(node.type==='button')return h('button',{type:'button',style,disabled:props.busy||(node.action==='claim'&&!props.eligible),onClick:()=>action(node)},content)
  return h('div',{style},node.type==='box'?(node.children||[]).map(render):content)
 }
 return ()=>h('section',{class:'design-document'},(page.value?.nodes||[]).map(render))
}})
</script>
<template><Content /></template>
<style scoped>.design-document{isolation:isolate;overflow:hidden;width:100%;overflow-wrap:anywhere}.design-document :deep(*){box-sizing:border-box}.design-document :deep(button){cursor:pointer}.design-document :deep(button:disabled){opacity:.5;cursor:not-allowed}.design-document :deep(button:focus-visible){outline:3px solid #387ae8;outline-offset:-3px}</style>
