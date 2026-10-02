package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** New-business gate only: no cash/order/settlement arithmetic or production fixture writes. */
class TenantPolicyLifecycleTest {
 private Tenant tenant;private TenantPolicy grant;private TenantPolicyService policy;private TenantReadinessService readiness;
 @BeforeEach void setup(){
  TenantContext.open(2L);tenant=new Tenant();tenant.setId(2L);tenant.setStatus("ACTIVE");tenant.setConfigReady(true);tenant.setDomainVerified(true);
  TenantRepository tenants=mock(TenantRepository.class);TenantPolicyRepository grants=mock(TenantPolicyRepository.class);readiness=mock(TenantReadinessService.class);
  grant=new TenantPolicy();grant.setTenantId(2L);grant.setValue("false");when(tenants.findById(2L)).thenReturn(Optional.of(tenant));when(grants.findByTenantIdAndKey(eq(2L),anyString())).thenReturn(Optional.of(grant));
  policy=new TenantPolicyService(tenants,grants);ReflectionTestUtils.setField(policy,"readiness",readiness);
 }
 @AfterEach void clear(){TenantContext.clear();}
 @Test void everyDisabledFeatureDeniesNewBusinessBeforeReadinessOrMutation(){
  for(String feature:TenantPolicyService.FEATURES)assertThrows(AccessDeniedException.class,()->policy.requireNewBusiness(feature),feature);
  verifyNoInteractions(readiness);assertThrows(IllegalArgumentException.class,()->policy.requireNewBusiness("unknown"));
 }
 @Test void allClosedTenantStatesAndIncompleteReadinessDenyEvenGrantedFeature(){
  grant.setValue("true");for(String status:Arrays.asList("DRAFT","STOP_NEW","MAINTENANCE","DISABLED")){tenant.setStatus(status);assertThrows(AccessDeniedException.class,()->policy.requireNewBusiness("contract"),status);}
  tenant.setStatus("ACTIVE");tenant.setConfigReady(false);assertThrows(AccessDeniedException.class,()->policy.requireNewBusiness("contract"));tenant.setConfigReady(true);tenant.setDomainVerified(false);assertThrows(AccessDeniedException.class,()->policy.requireNewBusiness("contract"));verifyNoInteractions(readiness);
 }
 @Test void grantChangesAreRecheckedAndMissingContextNeverUsesDefaultTenant(){
  grant.setValue("true");assertDoesNotThrow(()->policy.requireNewBusiness("contract"));verify(readiness).requireFeatureReady(2L,"contract");
  grant.setValue("false");assertThrows(AccessDeniedException.class,()->policy.requireNewBusiness("contract"));verifyNoMoreInteractions(readiness);
  TenantContext.clear();assertThrows(AccessDeniedException.class,()->policy.requireNewBusiness("contract"));
 }
}
