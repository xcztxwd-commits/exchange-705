<script setup lang="ts">
import { ref, watch } from 'vue'
import { apiFile, controlSession } from './api'
const props=defineProps<{path:string}>(),url=ref(''),error=ref(''),opened=ref(false)
watch(()=>[props.path,controlSession.value?.token],()=>{opened.value=false})
watch(()=>[props.path,controlSession.value?.token,opened.value],(_,old,onCleanup)=>{
 const controller=new AbortController();let objectUrl='';url.value='';error.value=''
 onCleanup(()=>{controller.abort();if(objectUrl)URL.revokeObjectURL(objectUrl)})
 if(!opened.value||!controlSession.value)return
 apiFile(props.path,controller.signal).then(({blob})=>{if(controller.signal.aborted)return;if(blob.type!=='image/png')throw new Error('附件格式无效');objectUrl=URL.createObjectURL(blob);url.value=objectUrl}).catch(e=>{if(!controller.signal.aborted)error.value=e.message})
},{immediate:true})
</script>
<template><el-button v-if="!opened" link @click="opened=true">鉴权查看图片</el-button><template v-else><p v-if="error" role="alert">{{error}}</p><img v-else-if="url" :src="url" alt="客服证据附件" style="max-width:240px;max-height:240px;object-fit:contain"/><span v-else>校验并加载附件…</span></template></template>
