package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.admin.AdminUserRepository;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.config.*;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.servlet.Filter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual Host boundary, Spring Security chain and controller; no production credentials or DB writes. */
@SpringJUnitConfig(ControlExchangeHttpTest.Config.class) @WebAppConfiguration
@TestPropertySource(properties={"jwt.secret=control-exchange-test-only-not-a-production-key","jwt.expireSeconds=60","platform.base-domain=example.test","platform.admin-origin=https://admin.example.test","platform.control-origin=https://control.example.test"})
class ControlExchangeHttpTest {
 @Configuration @EnableWebMvc
 @Import({SecurityConfig.class,JwtFilter.class,TenantHostService.class,TenantRequestFilter.class,ControlExchangeController.class,GlobalExceptionHandler.class})
 static class Config {
  @Bean javax.persistence.EntityManagerFactory entityManagerFactory(){return mock(javax.persistence.EntityManagerFactory.class);}
  @Bean com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound(){return mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class);}
  @Bean TenantReadinessService readiness(){return mock(TenantReadinessService.class);}
  @Bean TenantRepository tenants(){return mock(TenantRepository.class);}
  @Bean TenantDomainVerification domains(){return mock(TenantDomainVerification.class);}
  @Bean ControlService control(){return mock(ControlService.class);}
  @Bean TenantPolicyService policy(){return mock(TenantPolicyService.class);}
  @Bean BackendLoginRegistry logins(){return mock(BackendLoginRegistry.class);}
  @Bean ControlAuditService audit(){return mock(ControlAuditService.class);}
  @Bean JwtUtil jwt(){return mock(JwtUtil.class);}
  @Bean UserAccountRepository users(){return mock(UserAccountRepository.class);}
  @Bean AdminUserRepository admins(){return mock(AdminUserRepository.class);}
 }
 @Autowired WebApplicationContext context;@Autowired @Qualifier("springSecurityFilterChain") Filter security;
 @Autowired TenantRequestFilter hostBoundary;@Autowired ControlService service;@Autowired JwtUtil jwt;
 MockMvc mvc;
 @BeforeEach void setup(){reset(service,jwt);mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(hostBoundary,security).build();}
 @AfterEach void cleanup(){TenantContext.clear();SecurityContextHolder.clearContext();}
 @Test void anonymousConfigReturnsOnlyCanonicalPublicOriginsWithoutCacheOrTokenParsing()throws Exception{
  mvc.perform(get("/api/admin/auth/control-exchange-config").header("Host","admin.example.test").header("Authorization","Bearer stale-copy"))
   .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
   .andExpect(jsonPath("$.adminOrigin").value("https://admin.example.test")).andExpect(jsonPath("$.controlOrigin").value("https://control.example.test"))
   .andExpect(jsonPath("$.length()").value(2));
  verifyNoInteractions(service,jwt);
 }
 @Test void wrongHostOriginAndUntrustedForwardingCannotReadConfig()throws Exception{
  for(String host:new String[]{"control.example.test","evil.example.test","admin.example.test.attacker.test"})
   mvc.perform(get("/api/admin/auth/control-exchange-config").header("Host",host)).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/auth/control-exchange-config").header("Host","admin.example.test").header("Origin","https://evil.example.test")).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/auth/control-exchange-config").header("Host","admin.example.test").header("X-Forwarded-Host","admin.example.test")).andExpect(status().isForbidden());
  verifyNoInteractions(service,jwt);
 }
 @Test void publicGetDoesNotExposeOtherAdminOrControlOperations()throws Exception{
  for(String path:new String[]{"/api/admin/auth/control-activity","/api/admin/auth/control-exit","/api/admin/auth/control-exchange-config"})
   mvc.perform(post(path).header("Host","admin.example.test")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/admin/users").header("Host","admin.example.test")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/control/tenants/2/access-ticket").header("Host","control.example.test")).andExpect(status().isUnauthorized());
  verifyNoInteractions(service);
 }
 @Test void missingRuntimeOriginsFailClosedAndNeverIssueAccess() {
  TenantHostService hosts=new TenantHostService(mock(TenantRepository.class),"example.test","","","");
  ControlExchangeController controller=new ControlExchangeController(service,hosts);
  assertThrows(IllegalStateException.class,()->controller.config(new MockHttpServletResponse()));verifyNoInteractions(service);
 }
 @Test void configIgnoresClientTenantAndOriginSelectors()throws Exception{
  mvc.perform(get("/api/admin/auth/control-exchange-config").header("Host","admin.example.test").param("tenantId","999").param("controlOrigin","https://evil.example.test"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.controlOrigin").value("https://control.example.test"));verifyNoInteractions(service);
 }
}
