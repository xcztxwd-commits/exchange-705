package com.gtcfesk.exchange.activity;
import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.util.*;

/** Structured templates only: no executable HTML, scripts, arbitrary attributes or CSS URLs. */
public final class ActivityDesignValidator {
 private static final Set<String> STYLES=new HashSet<>(Arrays.asList("scale","z-index","display","flex-direction","flex-wrap","justify-content","align-items","align-self","flex-grow","flex-shrink","flex-basis","gap","width","height","min-height","max-height","min-width","max-width","padding","padding-top","padding-right","padding-bottom","padding-left","margin","margin-top","margin-right","margin-bottom","margin-left","background-color","background-image","background-size","background-position","background-repeat","color","font-family","font-size","font-weight","font-style","line-height","letter-spacing","text-align","text-decoration","border","border-width","border-style","border-color","border-radius","box-shadow","opacity","object-fit","object-position","overflow","position","top","right","bottom","left","transform"));
 private ActivityDesignValidator(){}
 public static String validate(String value,String fallback,ObjectMapper mapper){
  if(value==null||value.trim().isEmpty())return null;
  try{
   if(value.length()>2000000)throw new IllegalArgumentException("模板超过2MB");
   JsonNode root=mapper.readTree(value),locales=root.path("locales");
   if(root.path("version").asInt()!=1||!locales.isObject()||locales.size()>30||!locales.has(fallback))throw new IllegalArgumentException("模板缺少默认语言");
   JsonNode dialog=root.path("dialog");if(!dialog.isMissingNode()){
    if(!dialog.isObject()||!dialog.path("width").canConvertToInt()||dialog.path("width").asInt()<280||dialog.path("width").asInt()>1200||dialog.path("radius").asInt(-1)<0||dialog.path("radius").asInt()>60||dialog.path("blur").asInt(-1)<0||dialog.path("blur").asInt()>30||!dialog.path("backdrop").asText().matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))throw new IllegalArgumentException("弹窗外观设置无效");
   }
   Iterator<Map.Entry<String,JsonNode>> entries=locales.fields();
   while(entries.hasNext()){
    Map.Entry<String,JsonNode> entry=entries.next();if(!entry.getKey().matches("[a-z]{2}(-[A-Za-z]{2,4})?"))throw new IllegalArgumentException("模板语言无效");
    JsonNode pages=entry.getValue().path("pages");if(!pages.isArray()||pages.size()<1||pages.size()>12)throw new IllegalArgumentException("每种语言支持1至12个页面");
    Set<String> ids=new HashSet<>();for(JsonNode p:pages){String id=p.path("id").asText();if(!id.matches("[a-zA-Z0-9-]{1,60}")||!ids.add(id)||p.path("name").asText().length()>120)throw new IllegalArgumentException("页面标识重复或无效");}
    if(!ids.contains("gift"))throw new IllegalArgumentException("请保留首屏");
    int[] count={0};for(JsonNode p:pages)nodes(p.path("nodes"),ids,0,count);
   }
   return mapper.writeValueAsString(root);
  }catch(Exception e){throw new BusinessException("模板设计无效："+(e instanceof IllegalArgumentException?e.getMessage():"JSON格式错误"));}
 }
 private static void nodes(JsonNode list,Set<String> pages,int depth,int[] count){
  if(!list.isArray()||depth>15)throw new IllegalArgumentException("组件嵌套过深或格式错误");
  for(JsonNode n:list){
   if(++count[0]>500||!n.isObject())throw new IllegalArgumentException("每种语言最多500个组件");
   if(n.has("motion")&&!Arrays.asList("none","float","pulse","gift-open").contains(n.path("motion").asText()))throw new IllegalArgumentException("不支持的动画");
   String type=n.path("type").asText();if(!Arrays.asList("box","text","image","button","amount").contains(type))throw new IllegalArgumentException("不支持的组件");
   if(n.path("text").asText().length()>10000)throw new IllegalArgumentException("组件文字过长");
   for(String key:Arrays.asList("src","backgroundSrc"))if(n.hasNonNull(key))image(n.get(key).asText());
   JsonNode style=n.path("style");if(!style.isMissingNode()){
    if(!style.isObject())throw new IllegalArgumentException("样式格式错误");
    Iterator<Map.Entry<String,JsonNode>> fields=style.fields();while(fields.hasNext()){
     Map.Entry<String,JsonNode> field=fields.next();String v=field.getValue().asText();
     if(!STYLES.contains(field.getKey())||!field.getValue().isTextual()||v.length()>300||v.matches("(?is).*[;{}<>@\\\\].*")||v.matches("(?is).*(url|expression)\\s*\\(.*")||("position".equals(field.getKey())&&"fixed".equals(v)))throw new IllegalArgumentException("不支持或不安全的样式");
    }
   }
   if("button".equals(type)&&n.has("actions")){actions(n.get("actions"),pages);}
   else if("button".equals(type)){
    String action=n.path("action").asText(),target=n.path("target").asText();
    if(!Arrays.asList("page","claim","close","link").contains(action))throw new IllegalArgumentException("按钮动作无效");
    if("page".equals(action)&&(!pages.contains(target)||"success".equals(target)))throw new IllegalArgumentException("跳转页面不存在，或尝试跳过真实领取");
    if("link".equals(action)&&(!target.matches("/(?!/)[a-zA-Z0-9/_?=&%.-]*")||target.length()>300))throw new IllegalArgumentException("仅支持站内路径跳转");
   }
   if(n.has("children"))nodes(n.get("children"),pages,depth+1,count);
  }
 }
 private static void actions(JsonNode steps,Set<String> pages){
  if(!steps.isArray()||steps.size()<1||steps.size()>8)throw new IllegalArgumentException("每个按钮支持1至8个动作");
  boolean claimed=false;
  for(int i=0;i<steps.size();i++){
   JsonNode step=steps.get(i);String type=step.path("type").asText(),target=step.path("target").asText();
   if(!step.isObject()||!Arrays.asList("read","claim","page","next","previous","close","link").contains(type))throw new IllegalArgumentException("未知按钮动作");
   Iterator<String> fields=step.fieldNames();while(fields.hasNext())if(!Arrays.asList("type","target").contains(fields.next()))throw new IllegalArgumentException("动作不支持自定义接口地址、金额或请求参数");
   if("claim".equals(type)){if(claimed)throw new IllegalArgumentException("领取接口只能绑定一次");claimed=true;}
   if(Arrays.asList("page","next","previous","close","link").contains(type)&&i!=steps.size()-1)throw new IllegalArgumentException("跳转或关闭事件必须放在最后");
   if("page".equals(type)&&(!pages.contains(target)||("success".equals(target)&&!claimed)))throw new IllegalArgumentException("目标页面不存在，或成功页前缺少领取接口");
   if("link".equals(type)&&(!target.matches("/(?!/)[a-zA-Z0-9/_?=&%.-]*")||target.length()>300))throw new IllegalArgumentException("仅支持站内路径跳转");
  }
 }
 private static void image(String url){
  if(url.isEmpty())return;
  // Private uploads must use canonical, tenant-checked relative references, never an absolute alias.
  if(url.startsWith("https://") && java.net.URI.create(url).getPath().contains("/uploads/"))throw new IllegalArgumentException("私有上传图片须使用当前租户的相对路径");
  String tenant=String.valueOf(TenantContext.requireTenantId());
  if(!url.matches("/api/uploads/images/"+tenant+"/staff/(?:agent-)?-?[0-9]{1,19}/[a-zA-Z0-9_.-]+")&&!url.matches("https://[a-zA-Z0-9.-]+(?::[0-9]+)?/[^\\s<>\"\\\\]{1,1000}"))throw new IllegalArgumentException("图片须为当前租户上传素材或HTTPS图片");
 }
}
