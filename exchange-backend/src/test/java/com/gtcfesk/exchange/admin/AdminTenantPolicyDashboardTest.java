package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.config.AdminPermission;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminTenantPolicyDashboardTest {
 @Test @SuppressWarnings("unchecked") void dashboardReadsCurrentTenantMetadataOnlyAndNeverWrites() {
  TenantRepository tenants=mock(TenantRepository.class);TenantPolicyRepository policies=mock(TenantPolicyRepository.class);AdminPermissionService permissions=mock(AdminPermissionService.class);
  AdminTenantPolicyController controller=new AdminTenantPolicyController(tenants,policies,permissions);
  for(long id:new long[]{71,72}) {
   Tenant tenant=new Tenant();tenant.setId(id);tenant.setName("tenant-"+id);tenant.setStatus("ACTIVE");tenant.setPolicyVersion(id);when(tenants.findById(id)).thenReturn(Optional.of(tenant));
   TenantPolicy flag=new TenantPolicy();flag.setTenantId(id);flag.setKey("feature.contract");flag.setValue(id==71?"true":"false");
   TenantPolicy secret=new TenantPolicy();secret.setTenantId(id);secret.setKey("config.mail.password");secret.setValue("never-expose-this-secret");secret.setLocked(true);secret.setVersion(3);
   when(policies.findByTenantId(id)).thenReturn(Arrays.asList(flag,secret));
   try(TenantContext.Scope ignored=TenantContext.open(id)) {
    Map<String,Object> result=(Map<String,Object>)controller.dashboard();assertEquals(id,result.get("tenantId"));assertEquals(id,result.get("policyVersion"));
    Map<String,Boolean> features=(Map<String,Boolean>)result.get("features");assertEquals(TenantPolicyService.FEATURES.size(),features.size());assertEquals(id==71,features.get("contract"));assertFalse(features.get("registration"));
    Map<String,Object> config=((List<Map<String,Object>>)result.get("configs")).get(0);assertEquals(new HashSet<>(Arrays.asList("key","locked","denied","version")),config.keySet());assertFalse(result.toString().contains("never-expose-this-secret"));
   }
   verify(tenants).findById(id);verify(policies).findByTenantId(id);
  }
  verify(permissions,times(2)).require("dashboard","");verifyNoMoreInteractions(tenants,policies,permissions);
 }
 @Test void dashboardUsesItsOwnPermissionAndDeniesBeforeReading() throws Exception {
  TenantRepository tenants=mock(TenantRepository.class);TenantPolicyRepository policies=mock(TenantPolicyRepository.class);AdminPermissionService permissions=mock(AdminPermissionService.class);
  doThrow(new AccessDeniedException("no dashboard grant")).when(permissions).require("dashboard","");
  AdminTenantPolicyController controller=new AdminTenantPolicyController(tenants,policies,permissions);
  assertThrows(AccessDeniedException.class,controller::dashboard);verifyNoInteractions(tenants,policies);
  AdminPermission guard=AdminTenantPolicyController.class.getMethod("dashboard").getAnnotation(AdminPermission.class);assertEquals("dashboard",guard.menu());assertEquals("",guard.action());
 }
}
