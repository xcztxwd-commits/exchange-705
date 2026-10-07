package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.config.JwtFilter;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import javax.servlet.FilterChain;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TenantJwtBoundaryTest {
 static { ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN); }
 @AfterEach void cleanup(){TenantContext.clear();SecurityContextHolder.clearContext();}
 @Test void legacyUserAndCrossTenantClaimsFailClosed() throws Exception {
  JwtUtil jwt=mock(JwtUtil.class);UserAccountRepository users=mock(UserAccountRepository.class);JwtFilter filter=new JwtFilter(jwt,users,mock(AdminUserRepository.class));
  TenantPolicyService policy=mock(TenantPolicyService.class);ReflectionTestUtils.setField(filter,"policy",policy);
  DefaultClaims claims=new DefaultClaims();claims.setSubject("user-7");claims.setExpiration(new Date(System.currentTimeMillis()+60000));claims.put("userType","user");when(jwt.parse("test")).thenReturn(claims);
  try(TenantContext.Scope ignored=TenantContext.open(1L)){
   MockHttpServletRequest r=new MockHttpServletRequest("GET","/api/user/info");r.addHeader("Authorization","Bearer test");MockHttpServletResponse s=new MockHttpServletResponse();FilterChain chain=mock(FilterChain.class);filter.doFilter(r,s,chain);assertEquals(401,s.getStatus());verifyNoInteractions(chain,users);assertEquals(1L,TenantContext.requireTenantId());
   claims.put("tenantId",2L);r=new MockHttpServletRequest("GET","/api/user/info");r.addHeader("Authorization","Bearer test");s=new MockHttpServletResponse();filter.doFilter(r,s,chain);assertEquals(401,s.getStatus());verifyNoInteractions(chain,users);assertEquals(1L,TenantContext.requireTenantId());
  }
 }
 @Test void validBackendScopeIsBoundThroughHandlerAndRemovedAfterException() throws Exception {
  JwtUtil jwt=mock(JwtUtil.class);AdminUserRepository admins=mock(AdminUserRepository.class);JwtFilter filter=new JwtFilter(jwt,mock(UserAccountRepository.class),admins);TenantPolicyService policy=mock(TenantPolicyService.class);BackendLoginRegistry registry=mock(BackendLoginRegistry.class);ReflectionTestUtils.setField(filter,"policy",policy);ReflectionTestUtils.setField(filter,"logins",registry);
  Tenant tenant=new Tenant();tenant.setId(2L);when(policy.current()).thenReturn(tenant);
  AdminUser admin=new AdminUser();admin.setId(8L);admin.setCurrentToken("session");admin.setPasswordHash("hash");admin.setEnabled(true);admin.setRole("admin");when(admins.findByTenantIdAndId(2L,8L)).thenReturn(Optional.of(admin));when(registry.active("ADMIN",8L)).thenReturn(true);when(jwt.credentialKey("hash")).thenReturn("credential");
  DefaultClaims claims=new DefaultClaims();claims.setSubject("admin-8");claims.setExpiration(new Date(System.currentTimeMillis()+60000));claims.put("userType","admin");claims.put("tenantId",2L);claims.put("tenantVersion",0L);claims.put("sid","session");claims.put("credential","credential");when(jwt.parse("test")).thenReturn(claims);
  MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/admin/users");request.addHeader("Authorization","Bearer test");
  assertThrows(javax.servlet.ServletException.class,()->filter.doFilter(request,new MockHttpServletResponse(),(r,s)->{assertEquals(2L,TenantContext.requireTenantId());assertEquals("8",SecurityContextHolder.getContext().getAuthentication().getName());throw new javax.servlet.ServletException("handler failed");}));
  assertNull(TenantContext.currentTenantId());
 }
 @Test void failedRealUserRequestDoesNotUpdateOnlineActivity() throws Exception {
  JwtUtil jwt=mock(JwtUtil.class);UserAccountRepository users=mock(UserAccountRepository.class);JwtFilter filter=new JwtFilter(jwt,users,mock(AdminUserRepository.class));TenantPolicyService policy=mock(TenantPolicyService.class);com.gtcfesk.exchange.user.UserActivityService activity=mock(com.gtcfesk.exchange.user.UserActivityService.class);ReflectionTestUtils.setField(filter,"policy",policy);ReflectionTestUtils.setField(filter,"activity",activity);
  Tenant tenant=new Tenant();tenant.setId(1L);when(policy.current()).thenReturn(tenant);UserAccount user=new UserAccount();user.setId(7L);user.setCurrentToken("session");user.setPasswordHash("hash");when(users.findByTenantIdAndId(1L,7L)).thenReturn(Optional.of(user));when(jwt.credentialKey("hash")).thenReturn("credential");
  DefaultClaims claims=new DefaultClaims();claims.setSubject("user-7");claims.setExpiration(new Date(System.currentTimeMillis()+60000));claims.put("userType","user");claims.put("tenantId",1L);claims.put("tenantVersion",0L);claims.put("sid","session");claims.put("credential","credential");when(jwt.parse("test")).thenReturn(claims);
  try(TenantContext.Scope ignored=TenantContext.open(1L)){
   MockHttpServletRequest request=new MockHttpServletRequest("GET","/api/user/info");request.addHeader("Authorization","Bearer test");filter.doFilter(request,new MockHttpServletResponse(),(r,s)->((javax.servlet.http.HttpServletResponse)s).setStatus(403));verifyNoInteractions(activity);
  }
 }

 @Test void backendAndControlAccessTokensCannotReplaceFrontendHostContextOnSharedRoutes()throws Exception{
  JwtUtil jwt=mock(JwtUtil.class);AdminUserRepository admins=mock(AdminUserRepository.class);JwtFilter filter=new JwtFilter(jwt,mock(UserAccountRepository.class),admins);
  ControlService control=mock(ControlService.class);ControlAuditService audit=mock(ControlAuditService.class);ReflectionTestUtils.setField(filter,"control",control);ReflectionTestUtils.setField(filter,"audit",audit);
  for(String type:Arrays.asList("admin","agent","control_access")){
   DefaultClaims claims=new DefaultClaims();claims.setSubject(type+"-8");claims.setExpiration(new Date(System.currentTimeMillis()+60000));claims.put("userType",type);claims.put("tenantId",1L);when(jwt.parse("test")).thenReturn(claims);
   try(TenantContext.Scope ignored=TenantContext.open(2L)){
    for(String path:Arrays.asList("/api/market/currencies","/api/upload/image","/api/user/support/config","/api/user/system/timezone","/api/market/search","/api/market/kline/JPY%3DX")){
     MockHttpServletRequest request=new MockHttpServletRequest("GET",path);request.addHeader("Authorization","Bearer test");MockHttpServletResponse response=new MockHttpServletResponse();FilterChain chain=mock(FilterChain.class);
     filter.doFilter(request,response,chain);assertEquals(401,response.getStatus(),type+" "+path);assertEquals(2L,TenantContext.requireTenantId());verifyNoInteractions(chain,admins,control);
    }
   }
  }
 }
}
