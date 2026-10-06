package com.gtcfesk.exchange.control;
import com.gtcfesk.exchange.admin.AdminUser;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.security.OutboundEndpointPolicy;
import com.gtcfesk.exchange.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
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
 Long tenant;
 @BeforeEach void setup(){reset(outbound);Tenant t=new Tenant();t.setCode("t"+UUID.randomUUID().toString().replace("-",""));t.setName("Test");t.setFrontendHost("t"+System.nanoTime()+".example.com");t.setDomainVerified(true);t.setConfigReady(true);t.setStatus("ACTIVE");tenant=tenants.saveAndFlush(t).getId();TenantContext.open(tenant);config("site.name","Fixture Site");config("system.timezone","UTC");tx(()->{AdminUser admin=new AdminUser();admin.setAccount("test"+UUID.randomUUID());admin.setEmail("test@example.com");admin.setPasswordHash("unused");admin.setRole("super_admin");em.persist(admin);em.flush();BackendLogin login=new BackendLogin();login.setTenantId(tenant);login.setAdminUserId(admin.getId());login.setSubjectType("ADMIN");login.setNormalizedAccount(admin.getAccount());em.persist(login);});}
 @AfterEach void cleanup(){TenantContext.clear();}
 void tx(Runnable r){new TransactionTemplate(manager).execute(s->{r.run();return null;});}
 void config(String key,String value){tx(()->{SystemConfig c=configs.findByTenantIdAndConfigKey(tenant,key).orElseGet(SystemConfig::new);c.setConfigKey(key);c.setConfigValue(value);configs.save(c);});}
 void feature(String name){TenantPolicy p=new TenantPolicy();p.setTenantId(tenant);p.setKey("feature."+name);p.setValue("true");policies.saveAndFlush(p);}
 Set<String> keys(TenantReadinessService.Report r){Set<String>s=new HashSet<>();for(TenantReadinessService.Missing m:r.missing)s.add(m.key);return s;}
 @Test void readinessIsDerivedAndTenantScopedNotTrustedFlag(){assertTrue(readiness.report(tenant).ready);config("site.name","未配置平台");Tenant t=tenants.findById(tenant).get();assertTrue(t.isConfigReady());assertFalse(readiness.report(tenant).ready);assertTrue(keys(readiness.report(tenant)).contains("site.name"));TenantContext.clear();Tenant other=new Tenant();other.setCode("other"+UUID.randomUUID());other.setName("Other");other=tenants.saveAndFlush(other);assertTrue(keys(readiness.report(other.getId())).contains("backend.admin"));assertNull(TenantContext.currentTenantId());}
 @Test void prospectiveFeatureAndRuntimeRecheckRequireRealProducts(){assertThrows(RuntimeException.class,()->readiness.requireFeatureReady(tenant,"financial"));feature("financial");TenantPolicyService live=new TenantPolicyService(tenants,policies);ReflectionTestUtils.setField(live,"readiness",readiness);assertThrows(RuntimeException.class,()->live.requireNewBusiness("financial"));FinancialProduct product=new FinancialProduct();product.setName("Test");product.setTermDays(7);product.setDailyYieldRate(BigDecimal.ONE);product.setRentalFee(BigDecimal.ZERO);product.setMinPurchase(BigDecimal.ONE);product.setMaxPurchase(BigDecimal.TEN);product.setPenaltyRate(BigDecimal.ZERO);tx(()->em.persist(product));assertDoesNotThrow(()->live.requireNewBusiness("financial"));tx(()->em.find(FinancialProduct.class,product.getId()).setEnabled(false));assertThrows(RuntimeException.class,()->live.requireNewBusiness("financial"));}
 @Test void allBusinessConfigurationQueriesParseAndReportMissing(){for(String feature:TenantPolicyService.FEATURES)feature(feature);doThrow(new com.gtcfesk.exchange.common.BusinessException("SMTP 目标未获平台出站授权")).when(outbound).smtp(any(),any());doThrow(new com.gtcfesk.exchange.common.BusinessException("外部地址未获平台出站授权")).when(outbound).https(any(),any());Set<String> missing=keys(readiness.report(tenant));assertTrue(missing.containsAll(Arrays.asList("mail.endpoint","mail.username","mail.password","mail.from","market.symbol","option.duration","financial.product","loan.setting","deposit.setting","customer.service.link","support.settings")));}
 @Test void smtpChecksNeverReturnCredentialValues(){feature("registration");config("mail.username","sender@example.com");config("mail.password","test-sensitive-value");config("mail.from","sender@example.com");doThrow(new com.gtcfesk.exchange.common.BusinessException("SMTP 目标未获平台出站授权")).when(outbound).smtp(any(),any());TenantReadinessService.Report report=readiness.report(tenant);assertFalse(report.ready);assertEquals(Collections.singleton("mail.endpoint"),keys(report));assertFalse(report.missing.get(0).message.contains("test-sensitive-value"));}
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
