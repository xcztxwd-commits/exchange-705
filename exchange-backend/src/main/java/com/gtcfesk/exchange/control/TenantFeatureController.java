package com.gtcfesk.exchange.control;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
/** Host-scoped public capability snapshot; never exposes configuration values or internal policy rows. */
@RestController @RequiredArgsConstructor
public class TenantFeatureController {
 private final TenantPolicyService policy;
 @GetMapping("/api/tenant/features") public Map<String,Object> current(){
  Tenant tenant=policy.current();Map<String,Object> features=new LinkedHashMap<>();for(String feature:TenantPolicyService.FEATURES)features.put(feature,policy.featureEnabled(feature));
  Map<String,Object> out=new LinkedHashMap<>();out.put("tenantId",tenant.getId());out.put("tenantName",tenant.getName());out.put("status",tenant.getStatus());out.put("policyVersion",tenant.getPolicyVersion());out.put("acceptNewBusiness","ACTIVE".equals(tenant.getStatus())&&tenant.isConfigReady()&&tenant.isDomainVerified());out.put("features",features);return out;
 }
}
