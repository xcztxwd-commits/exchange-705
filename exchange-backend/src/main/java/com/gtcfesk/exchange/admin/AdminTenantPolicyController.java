package com.gtcfesk.exchange.admin;
import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Policy metadata only. No stored values, credentials, secrets or other tenant policies. */
@RestController @RequiredArgsConstructor @RequestMapping("/api/admin/tenant-policies")
public class AdminTenantPolicyController {
 private final TenantRepository tenants;private final TenantPolicyRepository policies;private final AdminPermissionService permissions;
 @GetMapping("/settings") @AdminPermission(menu="settings",action="") public Object settings(){permissions.require("settings","");return snapshot(null);}
 @GetMapping("/share-templates") @AdminPermission(menu="share_templates",action="") public Object shareTemplates(){permissions.require("share_templates","");return snapshot(Arrays.asList("share.templates","share.materials"));}
 @GetMapping("/support") @AdminPermission(menu="support_settings",action="") public Object support(){permissions.require("support_settings","");return snapshot(Arrays.asList("support.settings","support.channel","customer.service.link"));}
 @GetMapping("/agents") @AdminPermission(menu="agents",action="defaults") public Object agents(){permissions.require("agents","defaults");return snapshot(Collections.singletonList("agent.default.permissions"));}
 @GetMapping("/website") @AdminPermission(menu="website_security",action="") public Object website(){permissions.require("website_security","");return snapshot(Collections.singletonList("security.registration.v1"));}
 private Object snapshot(List<String> keys){
  Long id=TenantContext.requireTenantId();Tenant t=tenants.findById(id).orElseThrow(()->new IllegalArgumentException("租户不存在"));
  Map<String,Boolean> features=new LinkedHashMap<>();for(String f:TenantPolicyService.FEATURES)features.put(f,false);
  List<Map<String,Object>> locks=new ArrayList<>();String channel=null;
  for(TenantPolicy p:policies.findByTenantId(id)){
   if(p.getKey().startsWith("feature.")){String key=p.getKey().substring(8);if(features.containsKey(key))features.put(key,"true".equals(p.getValue()));}
   if(p.getKey().equals("config.support.channel")&&p.isLocked()&&Arrays.asList("internal","external","off").contains(p.getValue()))channel=p.getValue();
   if(!p.getKey().startsWith("config."))continue;String key=p.getKey().substring(7);if(keys!=null&&!keys.contains(key))continue;
   Map<String,Object> row=new LinkedHashMap<>();row.put("key",key);row.put("locked",p.isLocked());row.put("denied","DENY".equals(p.getValue()));row.put("version",p.getVersion());locks.add(row);
  }
  Map<String,Object> out=new LinkedHashMap<>();out.put("tenantId",id);out.put("tenantName",t.getName());out.put("status",t.getStatus());out.put("policyVersion",t.getPolicyVersion());out.put("features",features);out.put("configs",locks);out.put("supportChannel",channel);return out;
 }
}
