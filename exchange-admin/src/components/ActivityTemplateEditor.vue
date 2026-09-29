<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import grapesjs, { type Editor, type Component } from 'grapesjs'
import zh from 'grapesjs/locale/zh.js'
import 'grapesjs/dist/css/grapes.min.css'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { parseDesign, defaultPages, safeStyle, type ActivityDesign, type DesignNode } from '../../../exchange-frontend/src/utils/activityDesign'
const props=defineProps<{ value?:string; locale:string; fallback:string; fallbackCopy:Record<string,string>; copy:Record<string,string> }>()
const emit=defineEmits<{(e:'save',value:string):void;(e:'cancel'):void}>()
const canvas=ref<HTMLElement>(), pageId=ref('gift'), selectedText=ref(''), selectedAction=ref('page'), selectedTarget=ref('detail'), selectedType=ref(''), uploading=ref(false), changed=ref(false)
const design:ActivityDesign=JSON.parse(JSON.stringify(parseDesign(props.value)||{version:1,locales:{}}))
if(!design.locales[props.fallback])design.locales[props.fallback]={pages:defaultPages(props.fallbackCopy,props.fallback)}
if(!design.locales[props.locale])design.locales[props.locale]={pages:defaultPages(props.copy,props.locale)}
const dialogStyle=ref(design.dialog||{width:680,radius:20,backdrop:'#0d1d1875',blur:6})
const pages=ref(design.locales[props.locale]!.pages)
let editor:Editor|undefined, loading=false, disposed=false, hasPage=false
const assets=new Map<string,string>(), reverse=new Map<string,string>()
const escape=(s:string)=>s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')
async function asset(url:string){
 if(assets.has(url))return assets.get(url)!
 if(!url.startsWith('/api/uploads/images/'))return url
 const blob:any=await request.get(url.replace(/^\/api/,''),{responseType:'blob'});const object=URL.createObjectURL(blob)
 if(disposed){URL.revokeObjectURL(object);return ''}assets.set(url,object);reverse.set(object,url);return object
}
function richContent(n:DesignNode){return n.children?.length?n.children.map(run=>`<span style="${escape(Object.entries(safeStyle(run.style)).map(([k,v])=>k+':'+v).join(';'))}">${escape(run.text||'')}</span>`).join(''):escape(n.text||'')}
function textRuns(element:HTMLElement|undefined):DesignNode[]{
 const runs:DesignNode[]=[]
 const walk=(node:Node,style:Record<string,string>)=>{if(node.nodeType===3){if(node.textContent)runs.push({type:'text',text:node.textContent,style});return}if(!(node instanceof node.ownerDocument!.defaultView!.HTMLElement))return;const el=node as HTMLElement;let next={...style,...safeStyle(Object.fromEntries(Array.from(el.style).map(key=>[key,el.style.getPropertyValue(key)])))};if(['B','STRONG'].includes(el.tagName))next['font-weight']='700';if(['I','EM'].includes(el.tagName))next['font-style']='italic';if(el.tagName==='U')next['text-decoration']='underline';if(el.tagName==='BR'){runs.push({type:'text',text:'\n'});return}el.childNodes.forEach(child=>walk(child,next))}
 element?.childNodes.forEach(child=>walk(child,{}));return runs
}
async function hydrate(nodes:DesignNode[]):Promise<any[]>{return Promise.all(nodes.map(async n=>({
 type:n.type==='image'?'image':n.type==='box'?'default':'text',tagName:n.type==='button'?'button':'div',
 'design-type':n.type,'design-action':n.action||'','design-target':n.target||'','design-background':n.backgroundSrc||'',
 attributes:n.type==='image'?{src:await asset(n.src||''),alt:n.text||''}:n.type==='button'?{type:'button'}:{},
 content:n.type==='box'||n.type==='image'?'':richContent(n),
 style:{...safeStyle(n.style),...(n.backgroundSrc?{'background-image':`url("${await asset(n.backgroundSrc)}")`}:{})},
 components:n.type==='box'?await hydrate(n.children||[]):undefined,
 resizable:true
}))) }
function serialize(c:Component):DesignNode{
 const type=c.get('design-type')||(c.is('image')?'image':c.is('text')?'text':'box')
 const src=String(c.getAttributes().src||'')
 const background=String(c.getStyle()['background-image']||'').match(/^url\(["']?(.*?)["']?\)$/)?.[1]
 const backgroundSrc=background?(reverse.get(background)||background):c.get('design-background')||undefined
 return {type,text:type==='box'?undefined:type==='image'?String(c.getAttributes().alt||''):c.getEl()?.textContent||String(c.get('content')||''),src:type==='image'?(reverse.get(src)||src):undefined,backgroundSrc,action:type==='button'?c.get('design-action')||'page':undefined,target:type==='button'?c.get('design-target')||'detail':undefined,style:safeStyle(c.getStyle() as Record<string,string>),children:type==='box'?c.components().map(serialize):['text','button','amount'].includes(type)?textRuns(c.getEl()):undefined}
}
function stash(){if(editor&&!loading&&hasPage){const p=pages.value.find(p=>p.id===pageId.value);if(p)p.nodes=editor.getComponents().map(serialize)}}
async function selectPage(id:string){if(loading)return;stash();pageId.value=id;loading=true;selectedType.value='';try{editor?.setComponents(await hydrate(pages.value.find(p=>p.id===id)!.nodes));editor?.UndoManager.clear();hasPage=true}catch(e:any){ElMessage.error(e.message)}finally{loading=false}}
async function addPage(){if(loading||!hasPage)return;if(pages.value.length>=12){ElMessage.warning('每种语言最多12个页面');return}const id='page-'+Date.now();stash();pages.value.push({id,name:'新页面',nodes:[{type:'box',style:{padding:'28px','background-color':'#ffffff'},children:[{type:'text',text:'双击编辑文字'}]}]});await selectPage(id);changed.value=true}
async function removePage(){if(['gift','detail','success'].includes(pageId.value))return;await ElMessageBox.confirm('删除当前页面？请同时调整指向该页面的按钮。','删除页面');pages.value=pages.value.filter(p=>p.id!==pageId.value);await selectPage('gift');changed.value=true}
function updateText(){const c=editor?.getSelected();if(!c)return;if(selectedType.value==='image')c.addAttributes({alt:selectedText.value});else c.components(escape(selectedText.value));changed.value=true}
function updateAction(){editor?.getSelected()?.set({'design-action':selectedAction.value,'design-target':selectedTarget.value});changed.value=true}
async function upload(event:Event,background=false){
 const input=event.target as HTMLInputElement,file=input.files?.[0];input.value='';if(!file)return
 if(!['image/png','image/jpeg','image/gif','image/bmp'].includes(file.type)||file.size>5*1024*1024){ElMessage.error('支持 PNG/JPG/GIF/BMP，最大5MB；GIF保留动画');return}
 uploading.value=true
 try{const data=new FormData();data.append('file',file);const r:any=await request.post('/upload/image',data);if(!r.url)throw new Error(r.message||'上传失败');const preview=await asset(r.url);if(disposed)return;editor?.AssetManager.add({src:preview});const c=editor?.getSelected();if(background){if(!c)throw new Error('请先选择背景容器');c.set('design-background',r.url);c.addStyle({'background-image':`url("${preview}")`,'background-size':'cover','background-position':'center'})}else if(c?.is('image'))c.addAttributes({src:preview});else editor?.addComponents({type:'image',attributes:{src:preview},'design-type':'image',style:{width:'100%','object-fit':'contain'}});changed.value=true;ElMessage.success('素材已上传')}catch(e:any){ElMessage.error(e.message)}finally{uploading.value=false}
}
function save(){if(!hasPage||loading){ElMessage.warning('画布加载中，请稍候');return}stash();design.dialog=dialogStyle.value;design.locales[props.locale]={pages:pages.value};emit('save',JSON.stringify(design))}
async function cancel(){if(changed.value){try{await ElMessageBox.confirm('未应用的设计将丢弃，确定退出？','退出编辑器',{confirmButtonText:'退出',cancelButtonText:'继续编辑'})}catch{return}}emit('cancel')}
onMounted(async()=>{
 editor=grapesjs.init({container:canvas.value!,height:'100%',storageManager:false,noticeOnUnload:false,i18n:{locale:'zh',messages:{zh}},
  deviceManager:{devices:[{id:'mobile',name:'手机',width:'375px'},{id:'desktop',name:'桌面',width:'680px'}]},
  blockManager:{blocks:[{id:'box',label:'容器 / 图文布局',content:{type:'default','design-type':'box',style:{display:'flex','flex-direction':'column',gap:'16px',padding:'24px','min-height':'80px'}}},{id:'text',label:'文字',content:{type:'text','design-type':'text',content:'双击修改文字',style:{'font-size':'18px',padding:'8px'}}},{id:'image',label:'图片 / GIF',content:{type:'image','design-type':'image',style:{width:'100%','min-height':'80px','object-fit':'contain'}}},{id:'button',label:'按钮 / 跳转',content:{type:'text',tagName:'button','design-type':'button','design-action':'page','design-target':'detail',content:'打开详情',style:{padding:'16px','background-color':'#85bd00',color:'#ffffff',border:'0','border-radius':'10px'}}},{id:'amount',label:'真实金额变量',content:{type:'text','design-type':'amount',content:'{amount} U',style:{'font-size':'52px','font-weight':'700'}}}]},
  styleManager:{sectors:[{name:'布局 / 图文比例',open:true,buildProps:['display','flex-direction','justify-content','align-items','flex-wrap','gap','width','height','min-height','max-width','flex-basis','flex-grow','flex-shrink','object-fit']},{name:'文字 / 格式',open:true,buildProps:['font-family','font-size','font-weight','font-style','color','line-height','letter-spacing','text-align','text-decoration']},{name:'背景 / 边框 / 圆角',open:true,buildProps:['background-color','background-image','background-size','background-position','border','border-radius','box-shadow','opacity']},{name:'间距 / 定位',open:false,buildProps:['margin','padding','position','top','left','right','bottom','overflow','transform']}]}
 })
 editor.on('component:selected',c=>{selectedType.value=c.get('design-type')||(c.is('image')?'image':c.is('text')?'text':'box');selectedText.value=c.getEl()?.textContent||c.getAttributes().alt||'';selectedAction.value=c.get('design-action')||'page';selectedTarget.value=c.get('design-target')||'detail'})
 editor.on('update',()=>{if(!loading)changed.value=true});await new Promise<void>(resolve=>editor!.on('load',()=>resolve()));if(disposed)return;await selectPage('gift');editor.Panels.getButton('views','open-blocks')?.set('active',true)
})
onBeforeUnmount(()=>{disposed=true;editor?.destroy();assets.forEach(url=>URL.revokeObjectURL(url))})
</script>
<template><div class="template-studio">
 <header><strong>活动模板编辑器 · {{locale}}</strong><span>拖动添加组件 · 双击编辑文字 · 右侧调整样式 · 首屏/详情/成功页独立编辑</span><el-button v-permission="'announcement:edit'" @click="cancel">取消</el-button><el-button v-permission="'announcement:edit'" type="primary" :disabled="uploading" @click="save">应用设计</el-button></header>
 <nav><el-select :model-value="pageId" aria-label="编辑页面" @change="selectPage"><el-option v-for="page in pages" :key="page.id" :value="page.id" :label="page.name+' · '+page.id"/></el-select><el-input v-model="pages.find(p=>p.id===pageId)!.name" aria-label="页面名称" style="width:160px"/><el-button v-permission="'announcement:edit'" @click="addPage">新增页面</el-button><el-button v-permission="'announcement:edit'" :disabled="['gift','detail','success'].includes(pageId)" @click="removePage">删除页面</el-button><el-button v-permission="'announcement:edit'" @click="editor?.UndoManager.undo()">撤销</el-button><el-button v-permission="'announcement:edit'" @click="editor?.UndoManager.redo()">重做</el-button><label class="upload">上传图片 / GIF<input type="file" accept="image/png,image/jpeg,image/gif,image/bmp" :disabled="uploading" @change="upload($event)"/></label><label class="upload">上传背景<input type="file" accept="image/png,image/jpeg,image/gif,image/bmp" :disabled="uploading" @change="upload($event,true)"/></label></nav>
 <div class="selection-bar"><label>弹窗宽度 <el-input-number v-model="dialogStyle.width" :min="280" :max="1200" :step="20" size="small"/></label><label>圆角 <el-input-number v-model="dialogStyle.radius" :min="0" :max="60" size="small"/></label><label>遮罩 <el-color-picker v-model="dialogStyle.backdrop" show-alpha color-format="hex"/></label><label>遮罩模糊 <el-input-number v-model="dialogStyle.blur" :min="0" :max="30" size="small"/></label></div>
 <div class="selection-bar"><template v-if="selectedType"><span>已选：{{selectedType}}</span><el-input v-if="selectedType!=='box'" v-model="selectedText" aria-label="组件文字" placeholder="支持 {amount} / {days}" style="width:300px" @change="updateText"/><template v-if="selectedType==='button'"><el-select v-model="selectedAction" aria-label="按钮动作" style="width:150px" @change="updateAction"><el-option label="跳转设计页面" value="page"/><el-option label="领取体验金" value="claim"/><el-option label="关闭弹窗" value="close"/><el-option label="站内跳转" value="link"/></el-select><el-select v-if="selectedAction==='page'" v-model="selectedTarget" aria-label="目标页面" @change="updateAction"><el-option v-for="page in pages.filter(p=>p.id!=='success')" :key="page.id" :value="page.id" :label="page.name"/></el-select><el-input v-if="selectedAction==='link'" v-model="selectedTarget" aria-label="站内路径" placeholder="/trade" style="width:200px" @change="updateAction"/></template><el-button v-permission="'announcement:edit'" @click="editor?.getSelected()?.remove()">删除组件</el-button><el-button v-permission="'announcement:edit'" @click="editor?.getSelected()?.set('design-background','');editor?.getSelected()?.removeStyle('background-image')">清除背景图</el-button></template><span v-else>选择画布组件后编辑内容。图文占比使用宽度、Flex 比例；背景可用渐变或上传图片。领取成功页仅在后端确认到账后显示。</span></div>
 <div ref="canvas" class="studio-canvas"></div>
 <footer>应用设计后，还需在活动编辑窗口保存。当前语言整页回退，不再混入客户端固定文案。安全关闭按钮和接口错误提示由系统保留。</footer>
 </div></template>
<style scoped>.template-studio{height:calc(100vh - 70px);display:flex;flex-direction:column;background:#f4f6f8}.template-studio header,.template-studio nav,.selection-bar{display:flex;align-items:center;gap:10px;padding:12px;background:white;flex-wrap:wrap;border-bottom:1px solid #dde2e7}.template-studio header span{flex:1;color:#687681;font-size:12px}.template-studio nav>.el-select{width:220px}.selection-bar{min-height:52px;font-size:12px}.selection-bar>.el-select{width:180px}.studio-canvas{flex:1;min-height:300px}.upload{cursor:pointer;background:#edf6df;color:#547727;padding:8px;border-radius:6px;font-size:12px}.upload input{display:none}footer{font-size:12px;padding:8px;color:#607080}.template-studio :deep(.gjs-pn-panel){position:absolute}.template-studio :deep(.gjs-one-bg){background:#283544}.template-studio :deep(.gjs-two-color){color:#d8e1e9}</style>
