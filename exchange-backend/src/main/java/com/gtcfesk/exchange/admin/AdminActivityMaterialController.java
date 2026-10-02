package com.gtcfesk.exchange.admin;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.gtcfesk.exchange.activity.*;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.*;
import java.util.*;
@RestController @RequestMapping("/api/admin/activity-materials") @RequiredArgsConstructor
public class AdminActivityMaterialController {
 private final ActivityMaterialRepository materials;
 private final ObjectMapper mapper;
 private final com.gtcfesk.exchange.control.ControlAuditService audit;
 @GetMapping @AdminPermission(menu="announcement",action="")
 public Object list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String query){
  if(query.length()>80)throw new BusinessException("搜索词过长");
  return materials.findAllByTenantId(TenantContext.requireTenantId(),(r,q,cb)->cb.and(cb.isFalse(r.get("deleted")),cb.like(r.get("name"),"%"+query.replace("!","!!").replace("%","!%").replace("_","!_")+"%",'!')),PageRequest.of(Math.max(0,page),30,Sort.by(Sort.Direction.DESC,"id")));
 }
 public static class Input {public String name;public String nodesJson;}
 @org.springframework.transaction.annotation.Transactional
 @PostMapping @AdminPermission(menu="announcement",action="edit")
 public Object save(@RequestBody Input input){
  if(input.name==null||input.name.trim().isEmpty()||input.name.trim().length()>80)throw new BusinessException("素材名称应为1至80个字");
  if(input.nodesJson==null||input.nodesJson.length()>200000)throw new BusinessException("素材内容过大或为空");
  try{
   JsonNode nodes=mapper.readTree(input.nodesJson);
   if(!nodes.isArray()||nodes.size()==0)throw new BusinessException("素材不能为空");
   ObjectNode design=mapper.createObjectNode();design.put("version",1);
   ObjectNode page=design.putObject("locales").putObject("zh-CN").putArray("pages").addObject();page.put("id","gift");page.put("name","素材");page.set("nodes",nodes);
   // Reuse the same trust boundary as published templates. Library buttons cannot depend on another page.
   ActivityDesignValidator.validate(design.toString(),"zh-CN",mapper);
   ActivityMaterial item=new ActivityMaterial();item.setName(input.name.trim());item.setNodesJson(mapper.writeValueAsString(nodes));
   materials.saveAndFlush(item);
   audit.recordCurrent("ACTIVITY_MATERIAL_CREATE",String.valueOf(item.getId()),"validated template tree; no executable markup",null);
   return item;
  }catch(java.io.IOException e){throw new BusinessException("素材格式错误");}
 }
 @org.springframework.transaction.annotation.Transactional
 @DeleteMapping("/{id}") @AdminPermission(menu="announcement",action="edit")
 public Object delete(@PathVariable Long id){ActivityMaterial item=materials.lock(id).orElseThrow(()->new BusinessException("素材不存在"));item.setDeleted(true);materials.saveAndFlush(item);audit.recordCurrent("ACTIVITY_MATERIAL_REMOVE",String.valueOf(id),"soft removal; files and published copies preserved",null);return Collections.singletonMap("deleted",true);}
}
