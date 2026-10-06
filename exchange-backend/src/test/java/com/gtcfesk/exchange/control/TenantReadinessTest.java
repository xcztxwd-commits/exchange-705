package com.gtcfesk.exchange.control;
import com.gtcfesk.exchange.admin.AdminUser;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.security.OutboundEndpointPolicy;
import com.gtcfesk.exchange.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringJUnitConfig(TenantReadinessTest.Config.class)
class TenantReadinessTest {
 @Configuration @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass=true)
 @org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages="com.gtcfesk.exchange",repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class)
 @Import({TenantReadinessService.class,TenantSecrets.class,TenantSafeTemplate.class}) static class Config {
  @Bean javax.sql.DataSource dataSource(){return new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:readiness_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
  @Bean org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean entityManagerFactory(javax.sql.DataSource ds){org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean f=new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);f.setPackagesToScan("com.gtcfesk.exchange");f.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","create-drop");p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");f.setJpaProperties(p);return f;}
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new org.springframework.orm.jpa.JpaTransactionManager(f);}
  @Bean OutboundEndpointPolicy outbound(){return mock(OutboundEndpointPolicy.class);}
 }
 @Autowired TenantSafeTemplate template;@Autowired TradingSymbolRepository symbols;@Autowired FinancialProductRepository products;@Autowired AdminRoleRepository roles;
 @Autowired TenantReadinessService readiness;@Autowired TenantRepository tenants;@Autowired TenantPolicyRepository policies;@Autowired SystemConfigRepository configs;@Autowired PlatformTransactionManager manager;@Autowired OutboundEndpointPolicy outbound;@PersistenceContext EntityManager em;
 @Autowired ControlPolicyDefinitionRepository definitions;@Autowired ControlAuditLogRepository auditLogs;
 Long tenant;
 @BeforeEach void setup(){reset(outbound);Tenant t=new Tenant();t.setCode("t"+UUID.randomUUID().toString().replace("-",""));t.setName("Test");t.setFrontendHost("t"+System.nanoTime()+".example.com");t.setDomainVerified(true);t.setConfigReady(true);t.setStatus("ACTIVE");tenant=tenants.saveAndFlush(t).getId();TenantContext.open(tenant);config("site.name","Fixture Site");config("system.timezone","UTC");tx(()->{AdminUser admin=new AdminUser();admin.setAccount("test"+UUID.randomUUID());admin.setEmail("test@example.com");admin.setPasswordHash("unused");admin.setRole("super_admin");em.persist(admin);em.flush();BackendLogin login=new BackendLogin();login.setTenantId(tenant);login.setAdminUserId(admin.getId());login.setSubjectType("ADMIN");login.setNormalizedAccount(admin.getAccount());em.persist(login);});}
 @AfterEach void cleanup(){TenantContext.clear();SecurityContextHolder.clearContext();}
 void tx(Runnable r){new TransactionTemplate(manager).execute(s->{r.run();return null;});}
 void config(String key,String value){tx(()->{SystemConfig c=configs.findByTenantIdAndConfigKey(tenant,key).orElseGet(SystemConfig::new);c.setConfigKey(key);c.setConfigValue(value);configs.save(c);});}
 void feature(String name){TenantPolicy p=new TenantPolicy();p.setTenantId(tenant);p.setKey("feature."+name);p.setValue("true");policies.saveAndFlush(p);}
 Set<String> keys(TenantReadinessService.Report r){Set<String>s=new HashSet<>();for(TenantReadinessService.Missing m:r.missing)s.add(m.key);return s;}
 @Test void readinessIsDerivedAndTenantScopedNotTrustedFlag(){assertTrue(readiness.report(tenant).ready);config("site.name","未配置平台");Tenant t=tenants.findById(tenant).get();assertTrue(t.isConfigReady());assertFalse(readiness.report(tenant).ready);assertTrue(keys(readiness.report(tenant)).contains("site.name"));TenantContext.clear();Tenant other=new Tenant();other.setCode("other"+UUID.randomUUID());other.setName("Other");other=tenants.saveAndFlush(other);assertTrue(keys(readiness.report(other.getId())).contains("backend.admin"));assertNull(TenantContext.currentTenantId());}
 @Test void prospectiveFeatureAndRuntimeRecheckRequireRealProducts(){assertThrows(RuntimeException.class,()->readiness.requireFeatureReady(tenant,"financial"));feature("financial");TenantPolicyService live=new TenantPolicyService(tenants,policies);ReflectionTestUtils.setField(live,"readiness",readiness);assertThrows(RuntimeException.class,()->live.requireNewBusiness("financial"));FinancialProduct product=new FinancialProduct();product.setName("Test");product.setTermDays(7);product.setDailyYieldRate(BigDecimal.ONE);product.setRentalFee(BigDecimal.ZERO);product.setMinPurchase(BigDecimal.ONE);product.setMaxPurchase(BigDecimal.TEN);product.setPenaltyRate(BigDecimal.ZERO);tx(()->em.persist(product));assertDoesNotThrow(()->live.requireNewBusiness("financial"));tx(()->em.find(FinancialProduct.class,product.getId()).setEnabled(false));assertThrows(RuntimeException.class,()->live.requireNewBusiness("financial"));}
 @Test void allBusinessConfigurationQueriesParseAndReportMissing(){for(String feature:TenantPolicyService.FEATURES)feature(feature);doThrow(new BusinessException("外部地址未获平台出站授权")).when(outbound).https(any(),any());Set<String> missing=keys(readiness.report(tenant));assertTrue(missing.containsAll(Arrays.asList("market.symbol","option.duration","financial.product","loan.setting","deposit.setting","customer.service.link","support.settings")));assertTrue(missing.stream().noneMatch(key->key.startsWith("mail.")));verify(outbound,never()).smtp(any(),any());}
 @Test void registrationReadinessDoesNotRequireUnusedSmtpConfiguration(){
  assertDoesNotThrow(()->readiness.requireFeatureReady(tenant,"registration")); // Prospective grant, no mail settings.
  feature("registration");assertTrue(readiness.report(tenant).ready);assertDoesNotThrow(()->readiness.requireReady(tenant));
  // Broken or unapproved mail configuration belongs to sending mail, not captcha-only registration.
  config("mail.host","unapproved.example.com");config("mail.port","invalid");config("mail.username","");config("mail.password","test-sensitive-value");config("mail.from","not-an-email");
  assertDoesNotThrow(()->readiness.requireFeatureReady(tenant,"registration"));assertTrue(readiness.report(tenant).ready);verifyNoInteractions(outbound);
 }
 @Test void registrationRuntimeStillRequiresItsGrantAndRealTenantReadiness(){
  TenantPolicyService live=new TenantPolicyService(tenants,policies);ReflectionTestUtils.setField(live,"readiness",readiness);
  assertThrows(AccessDeniedException.class,()->live.requireNewBusiness("registration"));
  feature("registration");assertDoesNotThrow(()->live.requireNewBusiness("registration"));
  config("site.name","未配置平台");assertThrows(BusinessException.class,()->live.requireNewBusiness("registration"));config("site.name","Fixture Site");
  tx(()->policies.findByTenantIdAndKey(tenant,"feature.registration").get().setValue("false"));assertThrows(AccessDeniedException.class,()->live.requireNewBusiness("registration"));
  tx(()->policies.findByTenantIdAndKey(tenant,"feature.registration").get().setValue("true"));
  for(String status:Arrays.asList("DRAFT","STOP_NEW","MAINTENANCE","DISABLED")){
   tx(()->em.find(Tenant.class,tenant).setStatus(status));assertThrows(AccessDeniedException.class,()->live.requireNewBusiness("registration"));
  }
  tx(()->{Tenant t=em.find(Tenant.class,tenant);t.setStatus("ACTIVE");t.setConfigReady(false);});assertThrows(AccessDeniedException.class,()->live.requireNewBusiness("registration"));
  tx(()->{Tenant t=em.find(Tenant.class,tenant);t.setConfigReady(true);t.setDomainVerified(false);});assertThrows(AccessDeniedException.class,()->live.requireNewBusiness("registration"));
  tx(()->em.find(Tenant.class,tenant).setDomainVerified(true));assertDoesNotThrow(()->live.requireNewBusiness("registration"));verifyNoInteractions(outbound);
 }
 @SuppressWarnings("unchecked") @Test void everyFeatureDefaultPolicyEndpointAndActivationUseRealReadinessWithoutSmtp(){
  // Real tenant-owned dependencies, but no provider credentials, mail settings or outgoing requests.
  config("support.settings","{\"mode\":\"internal\",\"inboxEnabled\":true}");config("customer.service.link","https://support.example.com");
  tx(()->{
   TradingSymbol symbol=new TradingSymbol();symbol.setSymbol("JPY=X");symbol.setName("Test USD/JPY");symbol.setBaseCurrency("USD");symbol.setQuoteCurrency("JPY");symbol.setMarketSource("YAHOO");symbol.setSourceCategory("Forex");em.persist(symbol);
   OptionDuration duration=new OptionDuration();duration.setDuration(60);duration.setLabel("60s");duration.setSortOrder(0);duration.setEnabled(true);duration.setProfitRate(new BigDecimal("0.8"));duration.setLossRate(BigDecimal.ONE);duration.setMinAmount(BigDecimal.ONE);duration.setMaxAmount(BigDecimal.TEN);em.persist(duration);
   FinancialProduct product=new FinancialProduct();product.setName("In-memory QA");product.setTermDays(7);product.setDailyYieldRate(BigDecimal.ZERO);product.setRentalFee(BigDecimal.ZERO);product.setMinPurchase(BigDecimal.ONE);product.setMaxPurchase(BigDecimal.TEN);product.setPenaltyRate(BigDecimal.ZERO);em.persist(product);
   LoanSetting loan=new LoanSetting();loan.setDays(7);loan.setDailyRate(BigDecimal.ZERO);loan.setOverdueRate(BigDecimal.ZERO);loan.setMinAmount(BigDecimal.ONE);loan.setMaxAmount(BigDecimal.TEN);em.persist(loan);
   DepositSetting deposit=new DepositSetting();deposit.setType("bank");deposit.setBankName("In-memory QA only");deposit.setBankAccount("NOT-A-REAL-ACCOUNT");deposit.setAccountName("In-memory QA");em.persist(deposit);
  });
  TenantContext.clear();UsernamePasswordAuthenticationToken identity=new UsernamePasswordAuthenticationToken("operator",null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_CONTROL")));identity.setDetails(new ControlIdentity(7L,null,null));SecurityContextHolder.getContext().setAuthentication(identity);
  ControlPolicyDefinition anchor=new ControlPolicyDefinition();anchor.setKey("feature.registration");anchor.setName("用户注册");anchor.setOptionsJson("[\"false\",\"true\"]");anchor.setDefaultValue("false");definitions.saveAndFlush(anchor);
  ControlAuditService audit=new ControlAuditService(auditLogs);ControlPolicyDefinitionService catalog=new ControlPolicyDefinitionService(definitions,audit);
  TenantManagementService management=new TenantManagementService(tenants,policies,mock(TenantDomainHistoryRepository.class),mock(TenantHostService.class),audit,template,mock(TenantDomainVerification.class));
  ReflectionTestUtils.setField(management,"definitions",catalog);ReflectionTestUtils.setField(management,"readiness",readiness);ReflectionTestUtils.setField(management,"outbound",outbound);
  // Limit fanout to this fixture; all grant, readiness, lock and audit operations use real JPA.
  TenantRepository targets=mock(TenantRepository.class);when(targets.findAll()).thenReturn(Collections.singletonList(tenants.findById(tenant).get()));
  org.springframework.beans.factory.ObjectProvider<TenantManagementService> provider=mock(org.springframework.beans.factory.ObjectProvider.class);when(provider.getObject()).thenReturn(management);ReflectionTestUtils.setField(catalog,"tenants",targets);ReflectionTestUtils.setField(catalog,"management",provider);
  for(String feature:TenantPolicyService.FEATURES){
   ControlPolicyDefinitionService.Input change=new ControlPolicyDefinitionService.Input();change.key="feature."+feature;change.name=feature;change.options=Arrays.asList("false","true");change.defaultValue="true";change.version=definitions.findById(change.key).map(ControlPolicyDefinition::getVersion).orElse(null);
   tx(()->{ControlPolicyDefinitionService.View result=catalog.save(change);assertEquals(1,result.appliedTenants,feature);assertEquals(0,result.retainedTenants,feature);});
   TenantPolicy saved=policies.findByTenantIdAndKey(tenant,change.key).get();assertEquals("true",saved.getValue(),feature);assertTrue(saved.isLocked(),feature);
  }
  assertEquals((long)TenantPolicyService.FEATURES.size(),tenants.findById(tenant).get().getPolicyVersion());assertTrue(readiness.report(tenant).ready);assertNull(TenantContext.currentTenantId());
  tx(()->em.find(Tenant.class,tenant).setStatus("DRAFT"));ControlController.TenantInput activation=new ControlController.TenantInput();activation.status="ACTIVE";activation.configReady=true;activation.reason="测试全部功能基础配置激活";tx(()->management.update(tenant,activation));assertEquals("ACTIVE",tenants.findById(tenant).get().getStatus());
  try(TenantContext.Scope scope=TenantContext.open(tenant)){
   assertTrue(configs.findAllByTenantId(tenant).stream().noneMatch(c->c.getConfigKey().startsWith("mail.")));
   config("mail.host","unapproved.example.com");config("mail.port","invalid");config("mail.from","not-an-email");config("mail.password","enc:v1:invalid");
  }
  ControlController controller=new ControlController(mock(ControlService.class),mock(ControlAdminRepository.class),management,tenants,policies,auditLogs,mock(ControlAccessSessionRepository.class));MockMvc http=MockMvcBuilders.standaloneSetup(controller).build();
  TenantPolicyService live=new TenantPolicyService(tenants,policies);ReflectionTestUtils.setField(live,"readiness",readiness);
  for(String feature:TenantPolicyService.FEATURES)for(boolean locked:new boolean[]{true,false})for(String value:Arrays.asList("true","false")){
   String body="{\"key\":\"feature."+feature+"\",\"value\":\""+value+"\",\"locked\":"+locked+",\"reason\":\"\"}";
   tx(()->assertDoesNotThrow(()->http.perform(put("/api/control/tenants/{id}/policies",tenant).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.value").value(value)).andExpect(jsonPath("$.data.locked").value(locked)),feature+"/"+value+"/"+locked));
   assertNull(TenantContext.currentTenantId());TenantPolicy saved=policies.findByTenantIdAndKey(tenant,"feature."+feature).get();assertEquals(tenant,saved.getTenantId());assertEquals(value,saved.getValue());assertEquals(locked,saved.isLocked());
   try(TenantContext.Scope scope=TenantContext.open(tenant)){if("true".equals(value))assertDoesNotThrow(()->live.requireNewBusiness(feature),feature);else assertThrows(AccessDeniedException.class,()->live.requireNewBusiness(feature),feature);}
  }
  // Ordinary configuration locks also use full readiness; unrelated SMTP must not block them.
  for(String key:Arrays.asList("config.example.mode","config.support.channel")){
   ControlController.PolicyInput config=new ControlController.PolicyInput();config.key=key;config.value=key.endsWith("channel")?"internal":"normal";config.locked=true;tx(()->management.policy(tenant,config));config.locked=false;tx(()->management.policy(tenant,config));
  }
  long expectedWrites=5L*TenantPolicyService.FEATURES.size()+4;
  assertEquals(expectedWrites+1,tenants.findById(tenant).get().getPolicyVersion());tx(()->assertEquals(expectedWrites,em.createQuery("SELECT COUNT(a) FROM ControlAuditLog a WHERE a.tenantId=:tenant AND a.action='POLICY_UPDATE'",Long.class).setParameter("tenant",tenant).getSingleResult()));
  try(TenantContext.Scope scope=TenantContext.open(tenant)){config("site.name","未配置平台");}
  ControlController.PolicyInput grant=new ControlController.PolicyInput();grant.key="feature.registration";grant.value="true";grant.locked=true;
  assertThrows(BusinessException.class,()->tx(()->management.policy(tenant,grant)));assertEquals("false",policies.findByTenantIdAndKey(tenant,grant.key).get().getValue());assertEquals(expectedWrites+1,tenants.findById(tenant).get().getPolicyVersion());
  verify(outbound,never()).smtp(any(),any());
 }
 @Test void externalLinkCanBeStagedButForcedInternalStillRejectsIt(){TenantPolicyService live=new TenantPolicyService(tenants,policies);ReflectionTestUtils.setField(live,"outbound",outbound);assertDoesNotThrow(()->live.requireConfigChange("customer.service.link","https://support.example.com"));verify(outbound).validateConfig("customer.service.link","https://support.example.com");TenantPolicy lock=new TenantPolicy();lock.setTenantId(tenant);lock.setKey("config.support.channel");lock.setValue("internal");lock.setLocked(true);policies.saveAndFlush(lock);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->live.requireConfigChange("customer.service.link","https://support.example.com"));}

 @Test void everyMissingDependencyHasAnExplicitIdentityAndFixedLocator(){for(String feature:TenantPolicyService.FEATURES)feature(feature);config("site.name","未配置平台");TenantReadinessService.Report r=readiness.report(tenant);for(TenantReadinessService.Missing m:r.missing){assertTrue(Arrays.asList("CONTROL","TENANT_ADMIN").contains(m.owner),m.key);assertFalse(m.location.isBlank(),m.key);assertFalse(m.location.contains("test-sensitive-value"));}assertEquals("CONTROL",TenantReadinessService.location("frontendHost")[0]);assertEquals("OPERATIONS",TenantReadinessService.location("future-unknown")[0]);}
 @Test void safeTemplateIsDisabledIdempotentAndPreservesExistingSettings(){template.initialize(tenant);template.initialize(tenant);assertEquals(2,roles.countByTenantId(tenant));assertEquals(1,symbols.countByTenantId(tenant));assertEquals(1,products.countByTenantId(tenant));TradingSymbol symbol=symbols.findByTenantIdAndSymbol(tenant,"BTCUSD").get();assertFalse(symbol.getIsEnabled());assertFalse(symbol.getControlEnabled());FinancialProduct product=products.findAllByTenantId(tenant).get(0);assertFalse(product.getEnabled());assertEquals(0,product.getDailyYieldRate().signum());assertEquals("Fixture Site",configs.findByTenantIdAndConfigKey(tenant,"site.name").get().getConfigValue());assertTrue(configs.findAllByTenantId(tenant).stream().noneMatch(c->TenantSecrets.secret(c.getConfigKey())));assertThrows(RuntimeException.class,()->readiness.requireFeatureReady(tenant,"financial"));}
 @Test void failedCreationTransactionDoesNotKeepHalfTemplate(){TenantContext.clear();Tenant fresh=new Tenant();fresh.setCode("fresh"+UUID.randomUUID());fresh.setName("Fresh");Long target=tenants.saveAndFlush(fresh).getId();TenantContext.open(target);assertThrows(IllegalStateException.class,()->tx(()->{template.initialize(target);throw new IllegalStateException("creation failed");}));assertEquals(0,roles.countByTenantId(target));assertEquals(0,configs.countByTenantId(target));assertEquals(0,symbols.countByTenantId(target));assertEquals(0,products.countByTenantId(target));template.initialize(target);assertEquals(1,products.countByTenantId(target));assertEquals("DRAFT",tenants.findById(target).get().getStatus());assertFalse(tenants.findById(target).get().isConfigReady());}


 @Test void openingRequiresEnabledOwnerAndItsEnabledGlobalLoginNotOnlyAnEmployee(){
  tx(()->em.createQuery("UPDATE AdminUser SET role='admin' WHERE tenantId=:tenant").setParameter("tenant",tenant).executeUpdate());
  assertTrue(keys(readiness.report(tenant)).contains("backend.admin"));assertThrows(RuntimeException.class,()->readiness.requireReady(tenant));
  tx(()->em.createQuery("UPDATE AdminUser SET role='super_admin' WHERE tenantId=:tenant").setParameter("tenant",tenant).executeUpdate());assertTrue(readiness.report(tenant).ready);
  tx(()->em.createQuery("UPDATE BackendLogin SET enabled=false WHERE tenantId=:tenant").setParameter("tenant",tenant).executeUpdate());assertTrue(keys(readiness.report(tenant)).contains("backend.admin"));
  tx(()->{em.createQuery("UPDATE BackendLogin SET enabled=true WHERE tenantId=:tenant").setParameter("tenant",tenant).executeUpdate();em.createQuery("UPDATE AdminUser SET enabled=false WHERE tenantId=:tenant").setParameter("tenant",tenant).executeUpdate();});assertTrue(keys(readiness.report(tenant)).contains("backend.admin"));
 }
 @Test void unconfiguredOrClosedEntryDoesNotAffectFrontendReadiness(){
  Tenant t=tenants.findById(tenant).orElseThrow();assertNull(t.getEntryHost());assertFalse(t.isEntryEnabled());assertTrue(readiness.report(tenant).ready);
  t.setEntryHost("optional.forex-exchange.net");t.setEntryEnabled(false);t.setEntryVerified(false);tenants.saveAndFlush(t);assertTrue(readiness.report(tenant).ready);readiness.requireReady(tenant);
 }
}
