import type { DesignAction, DesignNode, DesignPage } from './activityDesign'
export const actionOptions = [
 {type:'read',label:'接口：标记已读',hint:'POST /activity/messages/{当前消息}/event · OPENED'},
 {type:'claim',label:'接口：领取体验金',hint:'POST /activity/messages/{当前消息}/claim · 后端核定金额'},
 {type:'page',label:'事件：指定页面',hint:'接口全部成功后跳转'},
 {type:'next',label:'事件：下一页',hint:'按页面列表顺序跳转'},
 {type:'previous',label:'事件：上一页',hint:'按页面列表顺序返回'},
 {type:'close',label:'事件：关闭弹窗',hint:'关闭并记录关闭回执'},
 {type:'link',label:'事件：站内跳转',hint:'仅支持 / 开头的站内地址'}
] as const
export function nodeActions(node:DesignNode):DesignAction[]{
 if(node.actions)return node.actions
 if(node.action==='page')return [{type:'read'},{type:'page',target:node.target}]
 if(['claim','close','link'].includes(node.action||''))return [{type:node.action as DesignAction['type'],target:node.target}]
 return [{type:'close'}]
}
export function validateActions(steps:DesignAction[],pages:DesignPage[]){
 if(!Array.isArray(steps)||steps.length<1||steps.length>8)throw Error('每个按钮支持1至8个动作')
 let claimed=false
 steps.forEach((s,i)=>{
  if(!s||!actionOptions.some(a=>a.type===s.type))throw Error('未知按钮动作')
  if(s.type==='claim'){if(claimed)throw Error('领取接口只能绑定一次');claimed=true}
  if(['page','next','previous','close','link'].includes(s.type)&&i!==steps.length-1)throw Error('跳转或关闭事件必须放在最后')
  if(s.type==='page'&&(!pages.some(p=>p.id===s.target)||(s.target==='success'&&!claimed)))throw Error('目标页面不存在，或成功页前缺少领取接口')
  if(s.type==='link'&&(!/^\/(?!\/)[a-zA-Z0-9/_?=&%.-]*$/.test(s.target||'')||(s.target||'').length>300))throw Error('请输入有效站内路径')
 })
}
export async function runDesignActions(steps:DesignAction[],context:{pages:DesignPage[];current:string;claimed:boolean;eligible:boolean;active:()=>boolean;call:(type:string,target?:string)=>Promise<void>;navigate:(page:string)=>void}){
 validateActions(steps,context.pages)
 if(steps.some(s=>s.type==='claim')&&!context.claimed&&!context.eligible)throw Error('当前不可领取体验金')
 let claimed=context.claimed,navigated=false
 for(const step of steps){
  if(!context.active())throw Error('页面已关闭，操作已停止')
  if(step.type==='read')await context.call('read')
  else if(step.type==='claim'){if(!claimed)await context.call('claim');claimed=true}
  else if(step.type==='page'){if(step.target==='success'&&!claimed)throw Error('请先完成领取');context.navigate(step.target!);navigated=true}
  else if(step.type==='next'||step.type==='previous'){
   const pages=context.pages.filter(p=>claimed||p.id!=='success'),index=pages.findIndex(p=>p.id===context.current)
   const next=pages[index+(step.type==='next'?1:-1)];if(!next)throw Error(step.type==='next'?'已经是最后一页':'已经是第一页')
   context.navigate(next.id);navigated=true
  }else {await context.call(step.type,step.target);navigated=true}
 }
 if(!context.active())return
 if(steps.some(s=>s.type==='claim')&&!navigated){if(context.pages.some(p=>p.id==='success'))context.navigate('success');else await context.call('close')}
}
