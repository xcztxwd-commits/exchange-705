export type DesignAction = { type:'read'|'claim'|'page'|'next'|'previous'|'close'|'link'; target?:string }
export type DesignNode = { type: 'box'|'text'|'image'|'button'|'amount'; text?: string; motion?: 'none'|'float'|'pulse'|'gift-open'; src?: string; backgroundSrc?: string; action?: string; target?: string; actions?: DesignAction[]; style?: Record<string,string>; children?: DesignNode[] }
export type DesignPage = { id: string; name: string; nodes: DesignNode[] }
export type ActivityDesign = { version: 1; dialog?: {width:number; radius:number; backdrop:string; blur:number}; locales: Record<string, { pages: DesignPage[] }> }
export const styleProperties = ['scale','z-index','display','flex-direction','flex-wrap','justify-content','align-items','align-self','flex-grow','flex-shrink','flex-basis','gap','width','height','min-height','max-height','min-width','max-width','padding','padding-top','padding-right','padding-bottom','padding-left','margin','margin-top','margin-right','margin-bottom','margin-left','background-color','background-image','background-size','background-position','background-repeat','color','font-family','font-size','font-weight','font-style','line-height','letter-spacing','text-align','text-decoration','border','border-width','border-style','border-color','border-radius','box-shadow','opacity','object-fit','object-position','overflow','position','top','right','bottom','left','transform']
export function safeStyle(style: Record<string,string> = {}) { return Object.fromEntries(Object.entries(style).filter(([key,value]) => styleProperties.includes(key) && typeof value==='string' && value.length<=300 && !/[;{}<>@\\]|url\s*\(|expression\s*\(/i.test(value) && !(key==='position'&&value==='fixed'))) }
export function parseDesign(value?: string): ActivityDesign | null { try { const d=JSON.parse(value||'null');return d?.version===1&&d.locales ? d : null } catch { return null } }
export function designText(text: string|undefined, amount: string|number, days: number) { return String(text||'').split('{amount}').join(String(Number(amount))).split('{days}').join(String(days)) }
// Legacy gift artwork rebuilt from editable layout nodes, not a flattened image.
export function giftArtwork(open=false): DesignNode {
 const box=(style:Record<string,string>,children:DesignNode[]=[]):DesignNode=>({type:'box',style,children})
 const bow=(left:string,angle:string)=>box({position:'absolute',bottom:'20px',left,width:'27px',height:'20px',border:'6px solid #e4bd6e','border-radius':'22px 22px 0 22px',transform:`rotate(${angle})`})
 return box({position:'relative',width:'180px',height:'150px',margin:'12px auto'},[
  box({position:'absolute',top:'10px',left:'0',width:'180px',height:'130px','border-radius':'50%','background-image':'radial-gradient(ellipse, #fff8b8, #fff8b800)'}),
  box({position:'absolute',left:'43px',top:'65px',width:'94px',height:'70px','border-radius':'5px 5px 12px 12px','background-image':'linear-gradient(110deg,#9dcd50,#6c9b27)','box-shadow':'10px 12px 16px #46651630',transform:'rotate(-7deg)'},[
   box({position:'absolute',left:'37px',height:'100%',width:'20px','background-image':'linear-gradient(90deg,#d6af61,#ffe6a1,#d2a455)'}),
   box({position:'absolute',top:'-9px',left:'-6px',width:'106px',height:'21px','background-image':'linear-gradient(100deg,#b2da6d,#78a638)','border-radius':'5px','box-shadow':'0 3px 4px #35541d30',transform:open?'translate(8px,-32px) rotate(12deg)':'none'},[
    box({position:'absolute',left:'43px',top:'0',width:'20px',height:'100%','background-color':'#edd08a'}),bow('30px','20deg'),bow('50px','70deg')]),
   {type:'text',text:'✦',style:{position:'absolute',right:'8px',bottom:'11px',color:'#eafaaf','font-size':'20px'}}])])
}
export function defaultPages(copy: Record<string,string>, locale='zh-CN'): DesignPage[] {
 const zh=locale.startsWith('zh'), text=(value:string,size='16px'):DesignNode=>({type:'text',text:value,style:{'font-size':size,'line-height':'1.7','margin':'0','color':'#43533b'}})
 const button=(value:string,action:string,target=''):DesignNode=>({type:'button',text:value,action,target,style:{'background-color':'#85bd00','color':'#ffffff','border':'0','border-radius':'12px','padding':'16px','width':'100%','font-size':'16px','font-weight':'600'}})
 const hero:DesignNode={type:'box',style:{'background-image':'linear-gradient(135deg, #f1f8e8, #deedcd)','padding':'32px','text-align':'center','display':'flex','flex-direction':'column','gap':'12px'},children:[text(zh?'专属礼遇':'EXCLUSIVE REWARD','12px'),giftArtwork(),{type:'amount',text:'{amount} USD',style:{'font-size':'56px','font-weight':'700','color':'#344c25'}},text(zh?'一份心意，开启更多可能':'A gift for your next opportunity','13px')]}
 const page=(id:string,name:string,content:DesignNode[]):DesignPage=>({id,name,nodes:[{type:'box',style:{'background-color':'#ffffff','border-radius':'20px','overflow':'hidden','font-family':'system-ui','width':'100%'},children:[{...JSON.parse(JSON.stringify(hero)),children:hero.children!.map((n,i)=>i===1?giftArtwork(id!=='gift'):JSON.parse(JSON.stringify(n)))},{type:'box',style:{'padding':'28px','display':'flex','flex-direction':'column','gap':'18px'},children:content}]}]})
 return [page('gift',zh?'首屏':'Cover',[text(copy.title||'', '26px'),text(zh?'打开查看活动详情':'Open to see the details'),button(copy.open||(zh?'打开礼遇':'Open gift'),'page','detail'),button(copy.close||(zh?'关闭':'Close'),'close')]),page('detail',zh?'详情页':'Details',[text(copy.title||'','26px'),text(copy.body||''),text(copy.terms||'','13px'),button(copy.claim||(zh?'领取 {amount} USD':'Claim {amount} USD'),'claim')]),page('success',zh?'领取成功页':'Success',[text(copy.success||(zh?'领取成功':'Claim successful'),'26px'),text(copy.terms||'','13px'),button(zh?'去交易':'Trade now','link','/trade')])]
}

export const designMotionCss = `
[data-design-motion="float"] { animation: activity-float 3s ease-in-out infinite; }
[data-design-motion="pulse"] { animation: activity-pulse 2s ease-in-out infinite; }
[data-design-motion="gift-open"] { animation: activity-gift-open 3s ease-in-out infinite; }
@keyframes activity-float { 50% { translate: 0 -8px; } }
@keyframes activity-pulse { 50% { scale: 1.06; } }
@keyframes activity-gift-open { 0%,15%,100% { transform: translate(0,0) rotate(0); } 45%,75% { transform: translate(8px,-32px) rotate(12deg); } }
@media (prefers-reduced-motion: reduce) { [data-design-motion] { animation: none !important; } }
`
export function animatedGift(): DesignNode {
 const gift=giftArtwork();gift.children![1]!.children![1]!.motion='gift-open';return gift
}

export const pagePresets = [
 {id:'gift',name:'礼盒首屏'},{id:'detail',name:'公告详情'},{id:'success',name:'领取成功'},
 {id:'poster',name:'纯图片海报'},{id:'blank',name:'空白画布'}
]
export function presetPage(preset:string,copy:Record<string,string>,locale:string):DesignPage {
 const existing=defaultPages(copy,locale).find(p=>p.id===preset)
 if(existing)return existing
 return {id:'gift',name:preset==='poster'?'图片海报':'空白画布',nodes:[{type:'box',style:{position:'relative','min-height':'650px',width:'100%','background-color':'#ffffff'},children:preset==='poster'?[{type:'image',src:'',text:'上传或从素材库添加图片',style:{width:'100%',height:'500px','object-fit':'contain'}}]:[]}]}
}
