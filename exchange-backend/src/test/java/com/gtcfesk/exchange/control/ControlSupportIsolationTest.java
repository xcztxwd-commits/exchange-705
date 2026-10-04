package com.gtcfesk.exchange.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.support.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.*;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real disposable H2 queries and transactions; not a MySQL migration acceptance test. */
@SpringJUnitConfig(ControlSupportIsolationTest.Config.class)
class ControlSupportIsolationTest {
 static { ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN); }
 @Configuration @EnableTransactionManagement(proxyTargetClass=true)
 @Import({SupportService.class,ControlAuditService.class,com.gtcfesk.exchange.common.PublishedTenantFiles.class,com.gtcfesk.exchange.simulation.SimulationEnvironment.class})
 static class Config {
  @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:control_support_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds){
   LocalContainerEntityManagerFactoryBean f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);f.setPackagesToScan("com.gtcfesk.exchange.entity","com.gtcfesk.exchange.admin","com.gtcfesk.exchange.support","com.gtcfesk.exchange.control","com.gtcfesk.exchange.activity","com.gtcfesk.exchange.insights");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
   Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","create-drop");p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");f.setJpaProperties(p);return f;
  }
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
  @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
  @Bean SupportSettings settings(){return mock(SupportSettings.class);}
  @Bean SystemConfigService configs(){return mock(SystemConfigService.class);}
  @Bean com.gtcfesk.exchange.tenant.TenantSecrets secrets(){return new com.gtcfesk.exchange.tenant.TenantSecrets();}
  @Bean com.gtcfesk.exchange.repository.SystemConfigRepository configRepository(){return mock(com.gtcfesk.exchange.repository.SystemConfigRepository.class);}
  @Bean TenantReadinessService readiness(){return mock(TenantReadinessService.class);}
  @Bean com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound(){return mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class);}
  @Bean TenantPolicyService policy(){return mock(TenantPolicyService.class);}
  @Bean TenantRepository tenants(){return mock(TenantRepository.class);}
  @Bean AdminPermissionService permissions(){AdminPermissionService p=mock(AdminPermissionService.class);when(p.isSuper()).thenReturn(true);when(p.can(anyString(),anyString())).thenReturn(true);return p;}
  @Bean ControlAuditLogRepository auditRepository(){return mock(ControlAuditLogRepository.class);}
 }
 @Autowired SupportService service;
 @Autowired com.gtcfesk.exchange.common.PublishedTenantFiles published;
 @Autowired SupportSettings settings;
 @Autowired ControlAuditLogRepository auditRepository;
 @Autowired PlatformTransactionManager manager;
 @PersistenceContext EntityManager em;
 Long user,actor;
 @BeforeEach void setup(){
  TenantContext.clear();TenantContext.open(1L);
  SupportSettings.Settings s=new SupportSettings.Settings();s.mode="internal";s.inboxEnabled=true;when(settings.get()).thenReturn(s);when(settings.welcome(any(),anyString(),isNull())).thenReturn("welcome");
  reset(auditRepository);when(auditRepository.save(any())).thenAnswer(i->{ControlAuditLog log=i.getArgument(0);em.persist(log);return log;});
  tx(()->{UserAccount u=new UserAccount();u.setEmail(UUID.randomUUID()+"@test.invalid");u.setPasswordHash("unused");em.persist(u);user=u.getId();ControlAdmin a=new ControlAdmin();a.setAccount(UUID.randomUUID().toString());a.setPasswordHash("unused");em.persist(a);actor=a.getId();});
 }
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();TenantContext.clear();}
 void tx(Runnable r){new TransactionTemplate(manager).execute(s->{r.run();return null;});}
 void asUser(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(),null,Collections.singleton(new SimpleGrantedAuthority("ROLE_USER"))));}
 void asControl(){UsernamePasswordAuthenticationToken a=new UsernamePasswordAuthenticationToken("-"+actor,null,Arrays.asList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),new SimpleGrantedAuthority("ROLE_CONTROL_ACCESS")));a.setDetails(new ControlIdentity(actor,TenantContext.requireTenantId(),"access-test"));SecurityContextHolder.getContext().setAuthentication(a);}
 long start(){asUser();return service.start("192.0.2.1").getId();}
 String key(){return UUID.randomUUID().toString();}
 long count(String table){return new TransactionTemplate(manager).execute(s->em.createQuery("select count(x) from "+table+" x",Long.class).getSingleResult());}
 @Test void supervisorDoesNotChangeReadFlagsOrCreateHiddenTenantStaff(){
  long id=start();SupportMessage message=service.send(id,false,key(),"help",null);asControl();
  service.detail(id,true,0);service.read(id,true,message.getId());service.presence(true);
  tx(()->{SupportConversation c=em.find(SupportConversation.class,id);assertEquals(0,c.getAdminReadId());assertNull(c.getAdminId());assertNull(c.getControlActorId());});
  assertEquals(0,count("SupportPresence"));assertEquals(0,count("AdminUser"));
  assertTrue(count("ControlAuditLog")>0);
 }
 @Test void controlClaimReplyAndInboxKeepRealActorAndTenant(){
  long id=start();asControl();SupportConversation c=service.claim(id);assertNull(c.getAdminId());assertEquals(actor,c.getControlActorId());
  SupportMessage m=service.send(id,true,key(),"reply",null);assertEquals("CONTROL",m.getSender());assertEquals(actor,m.getSenderId());assertEquals("总控管理",m.getSenderName());assertEquals(1L,m.getTenantId());
  String request=key();assertEquals(1,service.sendLetters(request,Collections.singletonList(user),"notice","body"));assertEquals(0,service.sendLetters(request,Collections.singletonList(user),"notice","body"));
  tx(()->{InboxLetter l=em.createQuery("from InboxLetter where requestId=:request",InboxLetter.class).setParameter("request",request).getSingleResult();assertNull(l.getAdminId());assertEquals(actor,l.getControlActorId());assertEquals(1L,l.getTenantId());});
  asUser();assertEquals(2L,service.notifications(false).get("chatUnread"));
 }
 @Test void foreignConversationAndRecipientAreRejectedEvenForControl(){
  long id=start();TenantContext.clear();TenantContext.open(2L);asControl();
  assertThrows(RuntimeException.class,()->service.detail(id,true,0));
  assertThrows(RuntimeException.class,()->service.sendLetters(key(),Collections.singletonList(user),"notice","body"));
 }
 @Test void failedAuditRollsBackBusinessClaim(){
  long id=start();asControl();doThrow(new IllegalStateException("audit unavailable")).when(auditRepository).save(any());
  assertThrows(IllegalStateException.class,()->service.claim(id));
  tx(()->{SupportConversation c=em.find(SupportConversation.class,id);assertEquals("WAITING",c.getStatus());assertNull(c.getControlActorId());});
 }
 @Test void onlyActivePublicReferencesPublishStaffFilesAndNeverUserDocuments(){
  String file="1/staff/9/picture-"+UUID.randomUUID()+".png";
  assertFalse(published.allows(file,false,false));
  com.gtcfesk.exchange.entity.FinancialProduct product=new com.gtcfesk.exchange.entity.FinancialProduct();product.setName("disabled-test");product.setImageUrl("/api/uploads/images/"+file);product.setEnabled(false);product.setDailyYieldRate(java.math.BigDecimal.ZERO);product.setRentalFee(java.math.BigDecimal.ZERO);product.setMinPurchase(java.math.BigDecimal.ONE);product.setMaxPurchase(java.math.BigDecimal.TEN);product.setTermDays(1);tx(()->em.persist(product));
  assertFalse(published.allows(file,false,false));tx(()->em.find(com.gtcfesk.exchange.entity.FinancialProduct.class,product.getId()).setEnabled(true));assertTrue(published.allows(file,false,false));
  assertFalse(published.allows(file.replace("1/","2/"),false,true));assertFalse(published.allows(file.replace("staff","user"),false,true));
  String qr="1/staff/9/qr-"+UUID.randomUUID()+".png";com.gtcfesk.exchange.entity.DepositSetting deposit=new com.gtcfesk.exchange.entity.DepositSetting();deposit.setQrCode("/api/uploads/images/"+qr);tx(()->em.persist(deposit));assertFalse(published.allows(qr,false,false));assertTrue(published.allows(qr,false,true));
  String audio="1/staff/9/reply-"+UUID.randomUUID()+".wav";SupportSettings.Settings config=settings.get();config.userSound="/api/uploads/audio/"+audio;assertTrue(published.allows(audio,true,false));config.mode="off";assertFalse(published.allows(audio,true,false));
 }
 @Test void privateReviewImagesRequireMatchingModuleUserAndTenantReference(){
  String file="1/user/"+user+"/kyc-"+UUID.randomUUID()+".png";com.gtcfesk.exchange.entity.KycRecord record=new com.gtcfesk.exchange.entity.KycRecord();record.setUserId(user);record.setRealName("test");record.setIdNumber("test-identity");record.setIdFrontImage("/api/uploads/images/"+file);record.setIdBackImage("not-requested");tx(()->em.persist(record));
  assertTrue(published.allowsReview(file,user,Collections.singleton("kyc_review")));assertFalse(published.allowsReview(file,user,Collections.singleton("deposit_review")));assertFalse(published.allowsReview(file,user+1,Collections.singleton("kyc_review")));
  assertFalse(published.allowsReview(file,user,Collections.singleton("loan_review")));assertFalse(published.allowsReview(file,user,Collections.singleton("loan_personal_info_review")));
  TenantContext.clear();TenantContext.open(2L);assertFalse(published.allowsReview(file,user,Collections.singleton("kyc_review")));
 }
 @Test void forcedInternalSuppressesExternalLinkAndLockedEdits(){
  SystemConfigService configs=mock(SystemConfigService.class);TenantPolicyService policy=mock(TenantPolicyService.class);
  SupportSettings actual=new SupportSettings(configs,new ObjectMapper());ReflectionTestUtils.setField(actual,"policy",policy);
  when(configs.getConfigValue(SupportSettings.KEY)).thenReturn("{\"mode\":\"external\",\"inboxEnabled\":true}");when(configs.getConfigValue("customer.service.link")).thenReturn("https://external.example");when(policy.effectiveConfig("support.channel",null)).thenReturn("internal");when(policy.featureEnabled("support")).thenReturn(true);
  assertEquals("internal",actual.get().mode);assertFalse(actual.get().inboxEnabled);assertEquals("",actual.externalLink());
  doThrow(new org.springframework.security.access.AccessDeniedException("locked")).when(policy).requireConfigChange("support.channel","external");
  SupportSettings.Settings altered=new SupportSettings.Settings();assertThrows(RuntimeException.class,()->actual.save(altered));verify(configs,never()).saveConfig(anyString(),anyString(),anyString());
 }
}
