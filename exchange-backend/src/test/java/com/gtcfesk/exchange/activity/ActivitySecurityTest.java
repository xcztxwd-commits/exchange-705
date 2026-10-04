package com.gtcfesk.exchange.activity;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.config.*;
import com.gtcfesk.exchange.common.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.user.*;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.control.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.util.ReflectionTestUtils;
import javax.servlet.Filter;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(ActivitySecurityTest.Config.class) @WebAppConfiguration
@org.springframework.test.context.TestPropertySource(properties={"jwt.secret=activity-test-only-not-a-production-key","jwt.expireSeconds=60","platform.base-domain=mt705.test","platform.admin-origin=https://admin.mt705.test","platform.control-origin=https://control.mt705.test"})
class ActivitySecurityTest {
 private TenantContext.Scope tenantScope;
 @AfterEach void closeTenantScope(){ if(tenantScope!=null)tenantScope.close(); }
 static ActivityService service=mock(ActivityService.class);static TrialFunds funds=mock(TrialFunds.class);static KycIdentityService identity=mock(KycIdentityService.class);static AssetAccountRepository assets=mock(AssetAccountRepository.class);
 @Configuration @EnableWebMvc @org.springframework.transaction.annotation.EnableTransactionManagement @Import({SecurityConfig.class,JwtFilter.class,GlobalExceptionHandler.class,ActivityPrivacy.class,com.gtcfesk.exchange.demo.DemoModeBoundary.class,TenantHostService.class,TenantRequestFilter.class})
 static class Config {
  @Bean javax.sql.DataSource dataSource(){return new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:activity_security_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000","sa","");}
  @Bean org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean entityManagerFactory(javax.sql.DataSource ds){org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean f=new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);f.setPackagesToScan("com.gtcfesk.exchange.entity","com.gtcfesk.exchange.activity","com.gtcfesk.exchange.admin");f.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","create-drop");p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");p.setProperty("hibernate.jdbc.time_zone","UTC");f.setJpaProperties(p);return f;}
  @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(javax.persistence.EntityManagerFactory f){return new org.springframework.orm.jpa.JpaTransactionManager(f);}
  @Bean com.gtcfesk.exchange.repository.SystemConfigRepository configRepository(){return mock(com.gtcfesk.exchange.repository.SystemConfigRepository.class);}
  @Bean com.gtcfesk.exchange.tenant.TenantSecrets secrets(){return mock(com.gtcfesk.exchange.tenant.TenantSecrets.class);}
  @Bean com.gtcfesk.exchange.admin.SystemConfigService configs(){return mock(com.gtcfesk.exchange.admin.SystemConfigService.class);}
        @Bean com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound(){return mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class);}
        @Bean TenantReadinessService readiness(){return mock(TenantReadinessService.class);}
        @Bean TenantRepository tenants(){return mock(TenantRepository.class);}
        @Bean TenantDomainVerification domains(){return mock(TenantDomainVerification.class);}

        @Bean ControlService control(){return mock(ControlService.class);}
        @Bean TenantPolicyService tenantPolicy(){TenantPolicyService policy=mock(TenantPolicyService.class);Tenant tenant=new Tenant();tenant.setId(1L);when(policy.current()).thenReturn(tenant);return policy;}
        @Bean BackendLoginRegistry logins(){BackendLoginRegistry registry=mock(BackendLoginRegistry.class);when(registry.active(anyString(),anyLong())).thenReturn(true);return registry;}
        @Bean ControlAuditService audit(){return mock(ControlAuditService.class);}

  @Bean JwtUtil jwt(){return mock(JwtUtil.class);}
  @Bean KycIdentityService identity(){return ActivitySecurityTest.identity;}
  @Bean UserAccountRepository users(){return mock(UserAccountRepository.class);}
  @Bean AdminUserRepository admins(){return mock(AdminUserRepository.class);}
  @Bean ActivityController activity(){return new ActivityController(service,funds,mock(TrialLedgerRepository.class));}
  @Bean AdminActivityController admin(){return new AdminActivityController(service,mock(ActivityCampaignRepository.class),mock(ActivityDeliveryRepository.class),funds,mock(TrialLedgerRepository.class),mock(AdminUserIdentity.class));}
  @Bean WithdrawController withdraw(){WithdrawController c=new WithdrawController(mock(WithdrawRecordRepository.class),assets,mock(UserDigitalAddressRepository.class),mock(UserBankCardRepository.class),mock(FiatCurrencyService.class));ReflectionTestUtils.setField(c,"identityService",identity);return c;}
 }
 @Autowired WebApplicationContext context;@Autowired @Qualifier("springSecurityFilterChain") Filter security;@Autowired JwtUtil jwt;@Autowired UserAccountRepository users;@Autowired AdminUserRepository admins;@Autowired TenantRequestFilter tenantFilter;@Autowired TenantRepository tenants;
 MockMvc mvc;
 @BeforeEach void setup(){
  tenantScope = TenantContext.open(1L);
  reset(service,funds,identity,assets,jwt,users,admins);
  Tenant tenant=new Tenant();tenant.setId(1L);tenant.setFrontendHost(com.gtcfesk.exchange.tenant.BootTenantFixture.FRONT);tenant.setDomainVerified(true);tenant.setStatus("ACTIVE");when(tenants.findByFrontendHost(tenant.getFrontendHost())).thenReturn(Optional.of(tenant));
  mvc=MockMvcBuilders.webAppContextSetup(context).defaultRequest(get("/").with(r->{r.addHeader("Host",r.getRequestURI().startsWith("/api/admin/")?com.gtcfesk.exchange.tenant.BootTenantFixture.ADMIN:com.gtcfesk.exchange.tenant.BootTenantFixture.FRONT);return r;})).addFilters(new com.gtcfesk.exchange.tenant.BootTenantFixture.RestoreFixtureScope(),tenantFilter,security).build();
  UserAccount u=new UserAccount();u.setId(7L);u.setStatus("normal");u.setUserType("agent");u.setCurrentToken("session");u.setPasswordHash("hash");when(users.findByTenantIdAndId(1L, 7L)).thenReturn(Optional.of(u));when(jwt.credentialKey("hash")).thenReturn("credential");
  AdminUser admin=new AdminUser();admin.setId(7L);admin.setEnabled(true);admin.setRole("super_admin");admin.setCurrentToken("session");admin.setPasswordHash("hash");when(admins.findByTenantIdAndId(1L, 7L)).thenReturn(Optional.of(admin));
  for(String type:Arrays.asList("user","agent","admin")){DefaultClaims c=new DefaultClaims();c.setSubject(type+"-7");c.setExpiration(new Date(System.currentTimeMillis()+60000));c.put("tenantId",1L);c.put("tenantVersion",0L);c.put("userType",type);c.put("sid","session");c.put("credential","credential");when(jwt.parse(type)).thenReturn(c);}
 }
 @Test void anonymousCannotReadOrClaim() throws Exception {mvc.perform(get("/api/activity/inbox")).andExpect(status().isUnauthorized());mvc.perform(post("/api/activity/messages/1/claim")).andExpect(status().isUnauthorized());verifyNoInteractions(service);}
 @Test void userAndAgentCannotControlCampaigns() throws Exception {for(String token:Arrays.asList("user","agent")){org.springframework.test.web.servlet.ResultActions denied=mvc.perform(post("/api/admin/activities/1/send").header("Authorization","Bearer "+token).contentType("application/json").content("[7]"));if("user".equals(token))denied.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));else denied.andExpect(status().isForbidden());}verifyNoInteractions(service);}
 @Test void usersAndAgentsCannotReadOrWriteMaterialLibrary()throws Exception{for(String token:Arrays.asList("user","agent")){for(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r:Arrays.asList(get("/api/admin/activity-materials"),post("/api/admin/activity-materials").contentType("application/json").content("{}"))){org.springframework.test.web.servlet.ResultActions denied=mvc.perform(r.header("Authorization","Bearer "+token));if("user".equals(token))denied.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));else denied.andExpect(status().isForbidden());}}verifyNoInteractions(service);}
 @Test void claimAlwaysUsesAuthenticatedOwnerNotBody() throws Exception {when(service.claim(7L,1L,null)).thenReturn(new TrialAccount());mvc.perform(post("/api/activity/messages/1/claim").header("Authorization","Bearer user").contentType("application/json").content("{\"userId\":99,\"amount\":999999}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));verify(service).claim(7L,1L,null);}
 @Test void demoCannotClaimRealPromotionalCredit() throws Exception {mvc.perform(post("/api/activity/messages/1/claim").header("Authorization","Bearer user").header("X-Account-Mode","DEMO")).andExpect(status().isConflict());verifyNoInteractions(service);}
 @Test void trialCreditNeverBypassesWithdrawalKyc() throws Exception {doThrow(new KycRequiredException("NOT_VERIFIED")).when(identity).requireApproved(7L);mvc.perform(post("/api/withdraw/submit").header("Authorization","Bearer user").contentType("application/json").content("{\"amount\":10,\"type\":\"digital\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("KYC_REQUIRED"));verifyNoInteractions(assets,funds);}
 @Test void adminControlsUsePermissionAnnotations(){for(java.lang.reflect.Method m:AdminActivityController.class.getDeclaredMethods()){if(java.lang.reflect.Modifier.isPublic(m.getModifiers())){AdminPermission permission=m.getAnnotation(AdminPermission.class);assertNotNull(permission,m.getName());assertEquals("announcement",permission.menu());}}}
}
