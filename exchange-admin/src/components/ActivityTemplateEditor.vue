<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import grapesjs, { type Editor, type Component } from 'grapesjs'
import zh from 'grapesjs/locale/zh.js'
import 'grapesjs/dist/css/grapes.min.css'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
import { can } from '@/utils/access'
import { parseDesign, defaultPages, giftArtwork, animatedGift, designMotionCss, safeStyle, pagePresets, presetPage, type DesignAction, type ActivityDesign, type DesignNode } from '../../../exchange-frontend/src/utils/activityDesign'
import ActivityDesignView from '../../../exchange-frontend/src/components/ActivityDesignView.vue'
import {actionOptions,nodeActions,validateActions} from '../../../exchange-frontend/src/utils/activityActions'
const selectedActions=ref<DesignAction[]>([]),newPagePreset=ref('blank'),presetOpen=ref(false),presetReplacing=ref(false),previewLog=ref<string[]>([])
import { validateActivityCopy, syncActivityCopy } from '@/utils/activityContent'
const props=defineProps<{ value?:string; locale:string; fallback:string; fallbackCopy:Record<string,string>; copy:Record<string,string>; translations:Record<string,Record<string,string>>; name:string; template:boolean; saving:boolean; canCreate:boolean; editing?:boolean }>()
const editorLocale=ref(props.locale), defaultLocale=ref(props.fallback), campaignName=ref(props.name)
const copies=ref<Record<string,Record<string,string>>>(JSON.parse(JSON.stringify(props.translations)))
const currentCopy=computed(()=>copies.value[editorLocale.value]!)
const languages=['zh-CN','zh-TW','en','ja','ko','fr','de','ru','es','pt','it','ar','tr','id','my','hi','cs','pl','th','vi']
const pendingCopy:Record<string,string>={}

const emit=defineEmits<{(e:'save',value:{layoutJson:string;translations:Record<string,Record<string,string>>;name:string;defaultLocale:string;asTemplate:boolean}):void;(e:'cancel'):void}>()
const layerPanel=ref<HTMLElement>()
const canvas=ref<HTMLElement>(), pageId=ref('gift'), selectedText=ref(''), selectedAction=ref('page'), selectedTarget=ref('detail'), selectedType=ref(''), uploading=ref(false), changed=ref(false)
const design:ActivityDesign=JSON.parse(JSON.stringify(parseDesign(props.value)||{version:1,locales:{}}))
if(!design.locales[defaultLocale.value])design.locales[defaultLocale.value]={pages:defaultPages(copies.value[defaultLocale.value]!,defaultLocale.value)}
if(!design.locales[editorLocale.value])design.locales[editorLocale.value]={pages:defaultPages(currentCopy.value,editorLocale.value)}
const dialogStyle=ref(design.dialog||{width:680,radius:20,backdrop:'#0d1d1875',blur:6})
const pages=ref(design.locales[editorLocale.value]!.pages)
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
 type:n.type==='image'?'image':n.type==='box'?'default':'text',tagName:n.type==='image'?'img':n.type==='button'?'button':'div',
 'design-copy-key':Object.keys(currentCopy.value).find(key=>currentCopy.value[key]&&currentCopy.value[key]===n.text),'design-copy-original':n.text,'design-motion':n.motion||'none','design-type':n.type,'design-actions':n.type==='button'?nodeActions(n):undefined,'design-action':n.action||'','design-target':n.target||'','design-background':n.backgroundSrc||'',
 attributes:{'data-design-motion':n.motion||'none',...(n.type==='image'?{src:await asset(n.src||''),alt:n.text||''}:n.type==='button'?{type:'button'}:{})},
 content:n.type==='box'||n.type==='image'?'':richContent(n),
 style:{...safeStyle(n.style),...(n.backgroundSrc?{'background-image':`url("${await asset(n.backgroundSrc)}")`}:{})},
 components:n.type==='box'?await hydrate(n.children||[]):undefined,
 resizable:true
}))) }
function serialize(c:Component):DesignNode{
 const copyKey=c.get('design-copy-key'), text=c.getEl()?.textContent
 if(copyKey&&text!=null&&text!==c.get('design-copy-original')&&!(copyKey in pendingCopy)){pendingCopy[copyKey]=text}

 const type=c.get('design-type')||(c.is('image')?'image':c.is('text')?'text':'box')
 const src=String(c.getAttributes().src||'')
 const background=String(c.getStyle()['background-image']||'').match(/^url\(["']?(.*?)["']?\)$/)?.[1]
 const backgroundSrc=background?(reverse.get(background)||background):c.get('design-background')||undefined
 return {type,motion:c.get('design-motion')||'none',text:type==='box'?undefined:type==='image'?String(c.getAttributes().alt||''):c.getEl()?.textContent||String(c.get('content')||''),src:type==='image'?(reverse.get(src)||src):undefined,backgroundSrc,actions:type==='button'?c.get('design-actions'):undefined,action:type==='button'&&!c.get('design-actions')?c.get('design-action')||'page':undefined,target:type==='button'&&!c.get('design-actions')?c.get('design-target')||'detail':undefined,style:safeStyle(c.getStyle() as Record<string,string>),children:type==='box'?c.components().map(serialize):['text','button','amount'].includes(type)?textRuns(c.getEl()):undefined}
}
function stash(){if(editor&&!loading&&hasPage){const p=pages.value.find(p=>p.id===pageId.value);if(p)p.nodes=editor.getComponents().map(serialize);syncActivityCopy(pages.value,currentCopy.value,pendingCopy);Object.keys(pendingCopy).forEach(key=>delete pendingCopy[key]);design.locales[editorLocale.value]={pages:pages.value}}}
async function selectLocale(locale:string){
 if(loading||props.saving)return
 stash();editorLocale.value=locale
 if(!copies.value[locale]){copies.value[locale]=JSON.parse(JSON.stringify(copies.value[defaultLocale.value]));changed.value=true}
 if(!design.locales[locale]){design.locales[locale]={pages:defaultPages(currentCopy.value,locale)};changed.value=true}
 pages.value=design.locales[locale]!.pages;hasPage=false;await selectPage('gift')
}
async function removeLocale(){if(editorLocale.value===defaultLocale.value||props.saving)return;const locale=editorLocale.value;await selectLocale(defaultLocale.value);delete copies.value[locale];delete design.locales[locale];changed.value=true}

async function selectPage(id:string){if(loading)return;stash();pageId.value=id;loading=true;selectedType.value='';try{editor?.setComponents(await hydrate(pages.value.find(p=>p.id===id)!.nodes));const root=editor?.getComponents().at(0);if(root?.get('design-type')==='box'){root.addStyle({position:'relative','min-height':String(root.getStyle()['min-height']||'650px')});root.set({draggable:false,resizable:false,removable:false,copyable:false})}editor?.UndoManager.clear();hasPage=true}catch(e:any){ElMessage.error(e.message)}finally{loading=false}}
function addPage(){if(loading||!hasPage)return;if(pages.value.length>=12){ElMessage.warning('每种语言最多12个页面');return}presetReplacing.value=false;presetOpen.value=true}
function replacePreset(){presetReplacing.value=true;presetOpen.value=true}
async function applyPreset(){
 if(newPagePreset.value==='success'&&(!presetReplacing.value||pageId.value!=='success')&&pages.value.some(p=>p.id==='success')){ElMessage.warning('领取成功页已存在，请切换到成功页后使用替换预设');return}
 stash()
 const next=presetPage(newPagePreset.value,currentCopy.value,editorLocale.value)
 // Presets must not introduce dangling links when optional pages were removed.
 const repair=(nodes:DesignNode[])=>nodes.forEach(n=>{if(n.action==='page'&&!pages.value.some(p=>p.id===n.target)){n.action='close';n.target=''};repair(n.children||[])})
 repair(next.nodes)
 if(presetReplacing.value){try{await ElMessageBox.confirm('替换当前页的全部内容？其他页面保持不变。','应用页面预设',{confirmButtonText:'替换',cancelButtonText:'取消'})}catch{return};const p=pages.value.find(p=>p.id===pageId.value)!;p.nodes=next.nodes;hasPage=false;await selectPage(p.id)}
 else {stash();next.id=newPagePreset.value==='success'?'success':!pages.value.some(p=>p.id===newPagePreset.value)&&['gift','detail'].includes(newPagePreset.value)?newPagePreset.value:'page-'+Date.now();pages.value.push(next);await selectPage(next.id)}
 presetOpen.value=false;changed.value=true
}

async function removePage(){
 if(loading||pageId.value==='gift')return
 try{await ElMessageBox.confirm('删除当前页面？指向它的按钮将改为关闭弹窗，请按需重新设置按钮动作。','删除页面',{confirmButtonText:'删除页面',cancelButtonText:'取消'})}catch{return}
 stash();const removed=pageId.value
 pages.value=pages.value.filter(p=>p.id!==removed)
 const repair=(nodes:DesignNode[])=>nodes.forEach(n=>{if(n.action==='page'&&n.target===removed){n.action='close';n.target=''}if(n.actions)n.actions=n.actions.map(a=>a.type==='page'&&a.target===removed?{type:'close'}:a);repair(n.children||[])})
 pages.value.forEach(p=>repair(p.nodes));hasPage=false
 await selectPage('gift');changed.value=true
}
function updateText(value:string){const c=editor?.getSelected();if(!c)return;const key=c.get('design-copy-key');if(key)pendingCopy[key]=value;if(selectedType.value==='image')c.addAttributes({alt:value});else c.components(escape(value));changed.value=true}
function updateActions(){editor?.getSelected()?.set('design-actions',JSON.parse(JSON.stringify(selectedActions.value)));changed.value=true}
function addAction(){if(selectedActions.value.length>=8)return;const index=selectedActions.value.findIndex(a=>['page','next','previous','close','link'].includes(a.type));selectedActions.value.splice(index<0?selectedActions.value.length:index,0,{type:'read'});updateActions()}
function moveAction(index:number,delta:number){const next=index+delta;if(next<0||next>=selectedActions.value.length)return;const [step]=selectedActions.value.splice(index,1);selectedActions.value.splice(next,0,step!);updateActions()}
function actionPreset(kind:string){selectedActions.value=kind==='read'?[{type:'read'}]:kind==='read-close'?[{type:'read'},{type:'close'}]:[{type:'read'},{type:'claim'},...(pages.value.some(p=>p.id==='success')?[{type:'page',target:'success'} as DesignAction]:[])];updateActions()}
const actionIssue=computed(()=>{if(selectedType.value!=='button')return '';try{validateActions(selectedActions.value,pages.value);return ''}catch(e:any){return e.message}})

async function upload(event:Event,background=false){
 if(!can('announcement:edit'))return
 const input=event.target as HTMLInputElement,file=input.files?.[0];input.value='';if(!file)return
 if(!['image/png','image/jpeg','image/gif','image/bmp'].includes(file.type)||file.size>5*1024*1024){ElMessage.error('支持 PNG/JPG/GIF/BMP，最大5MB');return}
 uploading.value=true
 try{
  const data=new FormData();data.append('file',file);const r:any=await request.post('/upload/image',data);if(!r.url)throw new Error(r.message||'上传失败')
  const nodes:DesignNode[]=[{type:'image',src:r.url,text:file.name,style:{width:'180px',height:'180px','object-fit':'contain'}}]
  await request.post('/admin/activity-materials',{name:file.name.slice(0,80),nodesJson:JSON.stringify(nodes)})
  if(background){const c=editor?.getSelected()||editor?.getComponents().at(0);if(c){c.set('design-background',r.url);c.addStyle({'background-image':`url("${await asset(r.url)}")`,'background-size':'cover','background-position':'center'})}}
  await loadLibrary();ElMessage.success('已保存到素材库，可拖入画布')
 }catch(e:any){ElMessage.error(e.message)}finally{uploading.value=false}
}
type Material={id:string;name:string;nodes:DesignNode[];builtIn?:boolean}
const leftTab=ref('materials'),query=ref(''),libraryPage=ref(0),libraryPages=ref(0),library=ref<Material[]>([]),libraryError=ref(''),libraryBusy=ref(false),preview=ref<Material>(),settingsOpen=ref(false),selectedMotion=ref('none'),previewOpen=ref(false),previewStage=ref('gift'),previewJson=ref(''),styleValues=ref<Record<string,string>>({})
let libraryRequest=0,searchTimer:ReturnType<typeof setTimeout>|undefined
const builtins:Material[]=[{id:'gift-closed',name:'闭合礼盒',nodes:[giftArtwork()],builtIn:true},{id:'gift-open',name:'打开礼盒',nodes:[giftArtwork(true)],builtIn:true},{id:'gift-animation',name:'开盖动画',nodes:[animatedGift()],builtIn:true}]
const visibleMaterials=computed(()=>[...builtins.filter(m=>m.name.includes(query.value.trim())),...library.value])
const basicMaterials:Material[]=[{id:'text',name:'文字',nodes:[{type:'text',text:'双击编辑文字',style:{width:'200px','font-size':'20px',color:'#344c25'}}]},{id:'button',name:'按钮',nodes:[{type:'button',text:'点击按钮',action:'close',style:{width:'160px',height:'48px','background-color':'#85bd00',color:'#ffffff',border:'0','border-radius':'12px','font-size':'16px'}}]},{id:'box',name:'色块 / 容器',nodes:[{type:'box',style:{width:'180px',height:'120px','background-color':'#edf5e2','border-radius':'16px'}}]},{id:'amount',name:'金额变量',nodes:[{type:'amount',text:'{amount} U',style:{width:'220px','font-size':'48px','font-weight':'700',color:'#344c25'}}]}]
const materialDesign=(nodes:DesignNode[])=>JSON.stringify({version:1,locales:{'zh-CN':{pages:[{id:'gift',name:'素材',nodes}]}}})
function detachedNodes(nodes:DesignNode[]){const copy:DesignNode[]=JSON.parse(JSON.stringify(nodes));copy.forEach(n=>{if(n.style){delete n.style.top;delete n.style.left;delete n.style.right;delete n.style.bottom;n.style.position='relative';n.style.margin='0 auto'}});return copy}
async function registerMaterial(m:Material){if(!editor)return;const content=await hydrate(detachedNodes(m.nodes));if(disposed)return;editor.BlockManager.add(m.id,{label:m.name,content});}
async function loadLibrary(){const requestId=++libraryRequest;libraryBusy.value=true;libraryError.value='';try{const r:any=await request.get('/admin/activity-materials',{params:{query:query.value.trim(),page:libraryPage.value}});if(disposed||requestId!==libraryRequest)return;library.value=(r.content||[]).map((m:any)=>({id:'saved-'+m.id,name:m.name,nodes:JSON.parse(m.nodesJson)}));libraryPages.value=r.totalPages||0;for(const m of library.value)await registerMaterial(m)}catch(e:any){if(requestId===libraryRequest)libraryError.value=e.message}finally{if(requestId===libraryRequest)libraryBusy.value=false}}
function searchLibrary(){clearTimeout(searchTimer);searchTimer=setTimeout(()=>{libraryPage.value=0;void loadLibrary()},250)}
function dragMaterial(m:Material,event:MouseEvent){const block=editor?.BlockManager.get(m.id);if(block)editor?.BlockManager.startDrag(block,event)}
async function insertMaterial(m:Material){if(!editor||loading)return;const parent=editor.getComponents().at(0);const components=await hydrate(detachedNodes(m.nodes));const added=parent?.get('design-type')==='box'?parent.append(components):editor.addComponents(components);const c=added[0];if(c){c.addStyle({position:'absolute',top:'40px',left:'30px',margin:'0'});editor.select(c)}changed.value=true}
async function saveMaterial(){if(!can('announcement:edit'))return;const c=editor?.getSelected();if(!c)return;let name:string;try{name=(await ElMessageBox.prompt('保存后可在其他活动中重复使用。页面跳转按钮会重置为关闭。','存入素材库',{confirmButtonText:'保存素材',cancelButtonText:'取消',inputPlaceholder:'素材名称',inputValidator:v=>!!v?.trim()&&v.trim().length<=80||'请输入1至80个字'})).value}catch{return}
 const nodes=detachedNodes([serialize(c)]);const clearLinks=(nodes:DesignNode[])=>nodes.forEach(n=>{if(n.action==='page'){n.action='close';n.target=''}if(n.actions)n.actions=n.actions.map(a=>['page','next','previous'].includes(a.type)?{type:'close'}:a);clearLinks(n.children||[])});clearLinks(nodes)
 try{await request.post('/admin/activity-materials',{name,nodesJson:JSON.stringify(nodes)});query.value='';libraryPage.value=0;await loadLibrary();leftTab.value='materials';ElMessage.success('已存入素材库')}catch(e:any){ElMessage.error(e.message)}
}
async function deleteMaterial(m:Material){if(!can('announcement:edit'))return;try{await ElMessageBox.confirm('从素材库移除？已使用它的活动不会改变。','移除素材');await request.delete('/admin/activity-materials/'+m.id.replace('saved-',''));await loadLibrary()}catch(e:any){if(e?.message)ElMessage.error(e.message)}}
function syncSelection(){const c=editor?.getSelected();selectedType.value=c?.get('design-type')||(c?.is('image')?'image':c?.is('text')?'text':c?'box':'');selectedText.value=c?.is('image')?c.getAttributes().alt||'':c?.getEl()?.textContent||'';selectedAction.value=c?.get('design-action')||'close';selectedTarget.value=c?.get('design-target')||'gift';selectedActions.value=c?.get('design-type')==='button'?JSON.parse(JSON.stringify(c.get('design-actions')||nodeActions(serialize(c)))):[];selectedMotion.value=c?.get('design-motion')||'none';styleValues.value={...(c?.getStyle()||editor?.getComponents().at(0)?.getStyle()||{})} as Record<string,string>}
function setStyle(key:string,value:string){const c=editor?.getSelected()||editor?.getComponents().at(0);if(!c)return;const style=safeStyle({[key]:value,...(['top','left'].includes(key)&&c!==editor?.getComponents().at(0)?{position:'absolute'}:{})});if(!Object.keys(style).length)return;c.addStyle(style);styleValues.value={...styleValues.value,...style};changed.value=true}
function setMotion(){const c=editor?.getSelected();c?.set('design-motion',selectedMotion.value);c?.addAttributes({'data-design-motion':selectedMotion.value});changed.value=true}
function layerOrder(front:boolean){const c=editor?.getSelected();if(!c)return;setStyle('z-index',String(Math.max(0,Math.min(1000,Number(c.getStyle()['z-index']||0)+(front?1:-1)))))}
function removeSelected(){const c=editor?.getSelected();if(c===editor?.getComponents().at(0)){ElMessage.info('页面画布需要保留，可在图层中选择其中的内容删除');return}c?.remove()}
function clearBackground(){const c=editor?.getSelected()||editor?.getComponents().at(0);c?.set('design-background','');c?.removeStyle('background-image');syncSelection()}
function showPreview(){previewLog.value=[];stash();design.locales[editorLocale.value]={pages:pages.value};previewJson.value=JSON.stringify(design);previewStage.value=pageId.value;previewOpen.value=true}
async function previewAction(action:string,target?:string){previewLog.value.push((actionOptions.find(a=>a.type===action)?.label||action)+(target?' '+target:''));if(action==='close'||action==='link')previewOpen.value=false}
function save(asTemplate=props.template){
 if(props.saving)return
 if(!hasPage||loading){ElMessage.warning('画布加载中，请稍候');return}
 stash()
 try{
  if(!campaignName.value.trim()||campaignName.value.length>120)throw new Error('请填写1至120字的后台活动名称')
  validateActivityCopy(copies.value,defaultLocale.value)
  for(const [locale,copy] of Object.entries(copies.value))if(!design.locales[locale])design.locales[locale]={pages:defaultPages(copy,locale)}
  for(const locale of Object.values(design.locales))for(const p of locale.pages){const check=(nodes:DesignNode[])=>nodes.forEach(n=>{if(n.type==='button')validateActions(nodeActions(n),locale.pages);check(n.children||[])});check(p.nodes)}
 }catch(e:any){ElMessage.error(e.message);return}
 design.dialog=dialogStyle.value
 emit('save',{layoutJson:JSON.stringify(design),translations:JSON.parse(JSON.stringify(copies.value)),name:campaignName.value.trim(),defaultLocale:defaultLocale.value,asTemplate})
}
async function cancel(){if(props.saving)return;if(changed.value||campaignName.value!==props.name||defaultLocale.value!==props.fallback){try{await ElMessageBox.confirm('未应用的设计将丢弃，确定退出？','退出编辑器',{confirmButtonText:'退出',cancelButtonText:'继续编辑'})}catch{return}}emit('cancel')}
onMounted(async()=>{
 // Device selection only previews width: the design schema stores shared, not breakpoint-specific styles.
 editor=grapesjs.init({container:canvas.value!,height:'100%',devicePreviewMode:true,dragMode:'absolute',storageManager:false,noticeOnUnload:false,i18n:{locale:'zh',messages:{zh}},
  panels:{defaults:[]},canvasCss:designMotionCss,
  deviceManager:{devices:[{id:'mobile',name:'手机',width:'375px'},{id:'desktop',name:'桌面',width:'680px'}]},
  layerManager:{appendTo:layerPanel.value!},styleManager:{sectors:[]},blockManager:{blocks:[]}
 })
 editor.on('component:selected component:deselected',syncSelection)
 editor.on('component:styleUpdate',syncSelection)
 editor.on('block:drag:stop',(c:Component|undefined)=>{if(!c)return;c.addStyle({position:'absolute'});editor?.select(c)})
 editor.on('component:add component:remove component:update component:styleUpdate',()=>{if(!loading)changed.value=true})
 await new Promise<void>(resolve=>editor!.on('load',()=>resolve()));if(disposed)return
 await selectPage('gift');for(const m of [...builtins,...basicMaterials])await registerMaterial(m);void loadLibrary()
})
onBeforeUnmount(()=>{disposed=true;clearTimeout(searchTimer);editor?.destroy();assets.forEach(url=>URL.revokeObjectURL(url))})
</script>
<template>
<div class="template-studio" :aria-busy="saving">
 <header class="studio-header"><strong>活动设计 <small>{{editorLocale}}</small></strong><el-select :model-value="pageId" aria-label="编辑页面" @change="selectPage"><el-option v-for="p in pages" :key="p.id" :label="p.name" :value="p.id"/></el-select><el-button v-permission="'announcement:view'" @click="settingsOpen=!settingsOpen">页面设置</el-button><div class="spacer"/><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="editor?.UndoManager.undo()">撤销</el-button><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="editor?.UndoManager.redo()">重做</el-button><el-button v-permission="'announcement:view'" @click="showPreview">预览</el-button><el-button v-permission="'session:close'" :disabled="saving" @click="cancel">取消</el-button><el-button v-permission="'announcement:create'" v-if="canCreate" :disabled="uploading||saving" @click="save(!template)">{{template?'保存为活动':'另存为模板'}}</el-button><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" type="primary" :loading="saving" :disabled="uploading" @click="save(template)">{{template?'保存模板':'保存活动'}}</el-button></header>
 <div class="content-meta"><label>后台活动名称<el-input v-model="campaignName" aria-label="后台活动名称" maxlength="120" :disabled="saving" @input="changed=true"/></label><label>编辑语言<el-select :model-value="editorLocale" aria-label="编辑语言" :disabled="saving" @change="selectLocale"><el-option v-for="code in languages" :key="code" :label="code+(copies[code]?' · 已配置':' · 新增')" :value="code"/></el-select></label><label>回退语言<el-select v-model="defaultLocale" aria-label="回退语言" :disabled="saving" @change="changed=true"><el-option v-for="(_,code) in copies" :key="code" :label="String(code)" :value="code"/></el-select></label><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" :disabled="editorLocale===defaultLocale||saving" @click="removeLocale">移除此语言</el-button><small>画布编辑内容，一次保存；预算和发送规则在公告发送设置中。</small></div>
 <div v-if="settingsOpen" class="page-settings"><el-input v-model="pages.find(p=>p.id===pageId)!.name" aria-label="页面名称" @input="changed=true"/><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="addPage">新增页面</el-button><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="replacePreset">页面预设</el-button><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" :disabled="pageId==='gift'" @click="removePage">删除页面</el-button><label>弹窗宽度<el-input-number v-model="dialogStyle.width" @change="changed=true" :min="280" :max="1200" :step="20" size="small"/></label><label>圆角<el-input-number v-model="dialogStyle.radius" @change="changed=true" :min="0" :max="60" size="small"/></label><label>遮罩<el-color-picker v-model="dialogStyle.backdrop" @change="changed=true" show-alpha color-format="hex"/></label><label>模糊<el-input-number v-model="dialogStyle.blur" @change="changed=true" :min="0" :max="30" size="small"/></label></div>
 <main class="studio-body" :inert="saving || undefined">
 <aside class="material-panel"><div class="rail-tabs"><button v-permission="'announcement:view'" :class="{active:leftTab==='materials'}" @click="leftTab='materials'">素材</button><button v-permission="'announcement:view'" :class="{active:leftTab==='layers'}" @click="leftTab='layers'">图层</button></div>
 <div v-show="leftTab==='materials'" class="library-content"><div class="quick-add"><button v-permission="editing ? 'announcement:edit' : 'announcement:create'" v-for="m in basicMaterials" :key="m.id" @click="insertMaterial(m)">{{m.name}}</button></div><p class="hint">素材拖入画布；文字双击编辑</p><label class="upload">＋ 上传图片 / GIF<input type="file" accept="image/png,image/jpeg,image/gif,image/bmp" :disabled="uploading||!can('announcement:edit')" @change="upload($event)"/></label><el-input v-model="query" placeholder="搜索素材" aria-label="搜索素材" clearable @input="searchLibrary"/>
 <p v-if="libraryError" role="alert">{{libraryError}} <button v-permission="'announcement:view'" @click="loadLibrary">重试</button></p><p v-if="libraryBusy" class="hint">加载素材中…</p>
 <div class="material-grid"><article v-for="m in visibleMaterials" :key="m.id" class="material-card" :data-material="m.id"><div class="material-thumb" @mousedown.prevent="dragMaterial(m,$event)"><div class="mini-render"><ActivityDesignView :design="materialDesign(detachedNodes(m.nodes))" locale="zh-CN" fallback="zh-CN" stage="gift" :amount="300" :days="3"/></div></div><strong>{{m.name}}</strong><div class="material-actions"><button v-permission="'announcement:view'" @click="preview=m">预览</button><button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="insertMaterial(m)">添加</button><button v-permission="'announcement:edit'" v-if="!m.builtIn" @click="deleteMaterial(m)">移除</button></div></article></div>
 <div v-if="libraryPages>1" class="pager"><button v-permission="'announcement:view'" :disabled="libraryPage===0" @click="libraryPage--;loadLibrary()">上一页</button><span>{{libraryPage+1}} / {{libraryPages}}</span><button v-permission="'announcement:view'" :disabled="libraryPage+1>=libraryPages" @click="libraryPage++;loadLibrary()">下一页</button></div></div>
 <div v-show="leftTab==='layers'" ref="layerPanel" class="layer-tree"></div></aside>
 <section class="workspace"><div class="canvas-toolbar"><span>拖动定位 · 拉角缩放</span><button v-permission="'announcement:view'" @click="editor?.setDevice('mobile')">手机</button><button v-permission="'announcement:view'" @click="editor?.setDevice('desktop')">桌面</button></div><div ref="canvas" class="studio-canvas"></div></section>
 <aside class="inspector"><h3>{{selectedType?({box:'容器',text:'文字',image:'图片',button:'按钮',amount:'金额'} as Record<string,string>)[selectedType]:'页面背景'}}</h3><p v-if="!selectedType" class="hint">点选画布内容，设置它的外观。</p>
 <template v-if="selectedType"><el-input v-if="selectedType!=='box'" v-model="selectedText" type="textarea" :rows="3" aria-label="组件文字" @change="updateText"/><div class="field-grid"><label v-for="f in [{key:'width',label:'宽度'},{key:'height',label:'高度'},{key:'left',label:'横向位置'},{key:'top',label:'纵向位置'}]" :key="f.key">{{f.label}}<el-input v-model="styleValues[f.key]" :aria-label="f.label" placeholder="自动 / 180px" @change="(v:any)=>setStyle(f.key,v)"/></label></div>
 <label class="field">缩放<el-slider :model-value="Number(styleValues.scale||1)*100" :min="25" :max="250" :step="5" @input="(v:any)=>setStyle('scale',String(Number(v)/100))"/></label>
 <div class="field-grid"><label>文字颜色<el-color-picker :model-value="styleValues.color||'#344c25'" @change="(v:any)=>v&&setStyle('color',v)"/></label><label>填充颜色<el-color-picker :model-value="styleValues['background-color']||'#ffffff'" show-alpha @change="(v:any)=>v&&setStyle('background-color',v)"/></label></div>
 <label class="field">圆角 / 按钮形状<el-slider :model-value="parseFloat(styleValues['border-radius']||'0')" :min="0" :max="100" @input="(v:any)=>setStyle('border-radius',v+'px')"/></label>
 <template v-if="selectedType!=='box'&&selectedType!=='image'"><label class="field">字号<el-input-number :model-value="parseFloat(styleValues['font-size']||'16')" :min="8" :max="160" @change="(v:any)=>setStyle('font-size',v+'px')"/></label><div class="button-row"><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="setStyle('font-weight',styleValues['font-weight']==='700'?'400':'700')">加粗</el-button><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="setStyle('text-align','left')">左对齐</el-button><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="setStyle('text-align','center')">居中</el-button></div></template>
 <label class="field">动画<el-select v-model="selectedMotion" aria-label="动画" @change="setMotion"><el-option label="无" value="none"/><el-option label="轻浮动" value="float"/><el-option label="呼吸缩放" value="pulse"/><el-option v-if="selectedMotion==='gift-open'" label="礼盒开盖" value="gift-open"/></el-select></label>
 <template v-if="selectedType==='button'"><h4>点击后执行</h4><div class="action-presets"><button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="actionPreset('read')">确认已读</button><button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="actionPreset('read-close')">已读并关闭</button><button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="actionPreset('claim')">领取并展示结果</button></div>
 <div v-for="(step,index) in selectedActions" :key="index" class="action-step"><div class="step-title"><strong>{{index+1}}</strong><button v-permission="editing ? 'announcement:edit' : 'announcement:create'" :disabled="index===0" aria-label="动作上移" @click="moveAction(index,-1)">↑</button><button v-permission="editing ? 'announcement:edit' : 'announcement:create'" :disabled="index===selectedActions.length-1" aria-label="动作下移" @click="moveAction(index,1)">↓</button><button v-permission="editing ? 'announcement:edit' : 'announcement:create'" :disabled="selectedActions.length===1" aria-label="删除动作" @click="selectedActions.splice(index,1);updateActions()">删除</button></div><el-select v-model="step.type" :aria-label="'动作 '+(index+1)" @change="step.target=undefined;updateActions()"><el-option v-for="o in actionOptions" :key="o.type" :label="o.label" :value="o.type"/></el-select><small>{{actionOptions.find(o=>o.type===step.type)?.hint}}</small><el-select v-if="step.type==='page'" v-model="step.target" aria-label="目标页面" @change="updateActions"><el-option v-for="p in pages" :key="p.id" :value="p.id" :label="p.name"/></el-select><el-input v-if="step.type==='link'" v-model="step.target" aria-label="站内路径" placeholder="/trade" @change="updateActions"/></div>
 <el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" class="wide" :disabled="selectedActions.length>=8" @click="addAction">＋ 添加动作</el-button><p v-if="actionIssue" role="alert" class="action-issue">{{actionIssue}}</p><p class="hint">从上到下执行；失败停止；跳转或关闭放最后。领取金额由后端决定。</p></template>
 <div class="button-row"><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="layerOrder(true)">上移一层</el-button><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="layerOrder(false)">下移一层</el-button></div><el-button v-permission="'announcement:edit'" class="wide" @click="saveMaterial">存入素材库</el-button><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" class="wide" type="danger" plain @click="removeSelected">删除组件</el-button></template>
 <template v-else><label class="field">背景颜色<el-color-picker :model-value="styleValues['background-color']||'#ffffff'" @change="(v:any)=>v&&setStyle('background-color',v)"/></label><label class="field">画布高度<el-input v-model="styleValues['min-height']" aria-label="画布高度" @change="(v:any)=>setStyle('min-height',v)"/></label></template>
 <details><summary>背景图片</summary><label class="upload">上传背景<input type="file" accept="image/png,image/jpeg,image/gif,image/bmp" :disabled="uploading||!can('announcement:edit')" @change="upload($event,true)"/></label><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" @click="clearBackground">清除背景图</el-button></details>
 </aside></main><footer>素材可跨活动复用。保存活动同时保存所有语言和设计。关闭或取消不发送公告、不发放体验金。</footer>
 <el-dialog :model-value="!!preview" :title="preview?.name||'素材预览'" width="460px" append-to-body @close="preview=undefined"><div v-if="preview" class="full-preview"><ActivityDesignView :design="materialDesign(detachedNodes(preview.nodes))" locale="zh-CN" fallback="zh-CN" stage="gift" :amount="300" :days="3"/></div><template #footer><el-button v-permission="'session:close'" @click="preview=undefined">关闭</el-button><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" type="primary" @click="preview&&insertMaterial(preview);preview=undefined">添加到页面</el-button></template></el-dialog>
 <el-dialog v-model="previewOpen" destroy-on-close title="页面预览（不会真实领取）" width="440px" append-to-body><ActivityDesignView :design="previewJson" :locale="editorLocale" :fallback="defaultLocale" :stage="previewStage" :amount="300" :days="3" :eligible="true" :run-action="previewAction" @action="previewAction"/><p v-for="(entry,index) in previewLog" :key="index" class="hint">{{index+1}}. {{entry}}（模拟成功）</p></el-dialog>
 <el-dialog v-model="presetOpen" :title="presetReplacing?'替换当前页预设':'选择新页面预设'" width="620px" append-to-body><div class="preset-grid"><button v-permission="editing ? 'announcement:edit' : 'announcement:create'" v-for="p in pagePresets" :key="p.id" :class="{chosen:newPagePreset===p.id}" @click="newPagePreset=p.id">{{p.name}}</button></div><div class="preset-preview"><ActivityDesignView :design="materialDesign(presetPage(newPagePreset,currentCopy,editorLocale).nodes)" locale="zh-CN" fallback="zh-CN" stage="gift" :amount="300" :days="3"/></div><template #footer><el-button v-permission="'session:close'" @click="presetOpen=false">取消</el-button><el-button v-permission="editing ? 'announcement:edit' : 'announcement:create'" type="primary" @click="applyPreset">使用此预设</el-button></template></el-dialog>
</div></template>
<style scoped>
.action-presets,.step-title{display:flex;gap:6px;flex-wrap:wrap}.action-presets button,.step-title button,.preset-grid button{border:1px solid #dfe5da;background:white;border-radius:5px;padding:5px;cursor:pointer;font-size:11px}.step-title strong{margin-right:auto}.action-step{background:#f5f7f2;padding:10px;margin:10px 0;border-radius:6px}.action-step small{display:block;overflow-wrap:anywhere;color:#829078;font-size:10px;margin:6px 0}.action-step .el-select{width:100%;margin-top:6px}.action-issue{color:#ad4b31;font-size:12px}.preset-grid{display:flex;gap:10px;margin-bottom:15px}.preset-grid .chosen{background:#edf6df;border-color:#7dac42}.preset-preview{max-height:50vh;overflow:auto;width:375px;margin:auto;pointer-events:none}
.template-studio{height:calc(100vh - 70px);display:flex;flex-direction:column;background:#f4f6f8;color:#34433b;font:13px/1.5 system-ui;min-width:0}.studio-header{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:12px 18px;background:white;border-bottom:1px solid #e4e8e3}.studio-header strong{font-size:16px;margin-right:12px}.studio-header small{font-size:11px;color:#879182;margin-left:8px}.studio-header .el-select{width:140px}.spacer{flex:1}.studio-body{display:grid;grid-template-columns:248px minmax(320px,1fr) 260px;flex:1;min-height:0}.material-panel,.inspector{background:white;overflow:auto;padding:16px;border-right:1px solid #e4e8e3}.inspector{border-left:1px solid #e4e8e3;border-right:0}.inspector h3{font-size:15px;margin:0 0 18px}.rail-tabs{display:flex;gap:20px;border-bottom:1px solid #e4e8e3;margin-bottom:16px}.rail-tabs button{padding:4px 4px 12px;border:0;border-bottom:2px solid transparent;background:none}.rail-tabs button.active{color:#5c8b29;border-color:#7dac42}.hint{color:#889285;font-size:11px;margin:12px 0}.quick-add{display:grid;grid-template-columns:1fr 1fr;gap:8px}.quick-add button{padding:12px 4px;background:#f5f7f2;border:1px solid #e6eadf;border-radius:6px;cursor:grab}.upload{display:block;cursor:pointer;text-align:center;background:#edf6df;color:#547727;padding:9px;border-radius:6px;margin:10px 0;font-size:12px}.upload input{display:none}.material-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px 8px;margin-top:16px}.material-card{min-width:0}.material-thumb{height:100px;border-radius:6px;background:#f6f8f3;overflow:hidden;cursor:grab;position:relative}.mini-render{width:200px;transform:scale(.5);transform-origin:top left;pointer-events:none;padding-top:10px}.material-card strong{display:block;font-weight:500;font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;padding-top:5px}.material-actions{display:flex;gap:8px}.material-actions button,.pager button{font-size:11px;border:0;background:none;color:#63893f;padding:4px 0;cursor:pointer}.workspace{display:flex;flex-direction:column;min-height:0;min-width:0}.canvas-toolbar{display:flex;justify-content:center;gap:12px;padding:10px;color:#7c887e;font-size:11px}.canvas-toolbar span{margin-right:auto;margin-left:16px}.canvas-toolbar button{background:white;border:1px solid #dfe5da;border-radius:4px;padding:3px 9px;cursor:pointer}.studio-canvas{flex:1;min-height:300px}.field{display:flex;flex-direction:column;gap:8px;margin:18px 0}.field-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:16px 0}.field-grid label{display:flex;flex-direction:column;gap:6px;font-size:12px}.button-row{display:flex;flex-wrap:wrap;gap:6px;margin:14px 0}.button-row .el-button{margin:0;font-size:11px;padding:7px}.wide{display:block;width:100%;margin:10px 0!important}details{border-top:1px solid #e8ece4;padding-top:14px;margin-top:18px}summary{cursor:pointer}.page-settings{display:flex;gap:10px;align-items:center;flex-wrap:wrap;padding:12px;background:white;border-bottom:1px solid #e4e8e3}.page-settings>.el-input{width:150px}.page-settings label{display:flex;gap:5px;align-items:center}.page-settings .el-input-number{width:110px}footer{font-size:11px;padding:8px 18px;color:#879182;border-top:1px solid #e4e8e3;background:white}.full-preview{min-height:200px;max-height:65vh;overflow:auto;padding:20px;background:#f5f8ef;pointer-events:none}.pager{display:flex;justify-content:space-between;margin-top:14px}.template-studio :deep(.gjs-cv-canvas){width:100%;height:100%;top:0}.template-studio :deep(.gjs-one-bg){background:#f0f3ec}.template-studio :deep(.gjs-two-color){color:#56634d}.template-studio :deep(.gjs-layer-title){padding:8px}.template-studio :deep(.gjs-pn-panels){display:none}
.content-meta{display:flex;flex-wrap:wrap;align-items:center;gap:12px;padding:12px 18px;background:white;border-bottom:1px solid #e4e8e3}.content-meta label{display:flex;align-items:center;gap:8px}.content-meta .el-input{width:240px}.content-meta .el-select{width:150px}.content-meta small{color:#879182}.studio-canvas{overflow:auto}
@media(max-width:1100px){.studio-body{grid-template-columns:180px minmax(0,1fr) 220px}.studio-header{padding:8px}.material-panel,.inspector{padding:10px}}
@media(max-width:900px){.studio-body{grid-template-columns:160px minmax(0,1fr);grid-template-rows:minmax(320px,1fr) 180px;overflow:auto}.inspector{grid-column:1/-1}.content-meta .el-input{width:190px}.template-studio{height:calc(100vh - 30px)}}
</style>
