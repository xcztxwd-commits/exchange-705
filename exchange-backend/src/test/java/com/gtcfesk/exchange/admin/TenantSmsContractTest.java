package com.gtcfesk.exchange.admin;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.security.*;
import com.gtcfesk.exchange.tenant.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.*;
import javax.sql.DataSource;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real loopback Redis scripts, real tenant JPA, file publication and DB-rejected audit; no external provider. */
@EnabledIfSystemProperty(named="security.test.redis.port",matches="[0-9]+")
@SpringJUnitConfig(TenantSmsContractTest.Config.class)
class TenantSmsContractTest {
 @Configuration @EnableJpaRepositories(basePackages="com.gtcfesk.exchange",repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class)
 @org.springframework.transaction.annotation.EnableTransactionManagement(proxyTargetClass=true)
 @Import({TenantSmsService.class,ControlAuditService.class,RegistrationSecurity.class,SystemConfigService.class,TenantPolicyService.class,TenantSecrets.class})
 static class Config {
  @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:sms_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000","sa","");}
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds){LocalContainerEntityManagerFactoryBean f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);f.setPackagesToScan("com.gtcfesk.exchange");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","create-drop");p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");f.setJpaProperties(p);return f;}
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
  @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
  @Bean ObjectMapper json(){return new ObjectMapper();}
  @Bean com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound(){return mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class);}
  @Bean TenantReadinessService readiness(){return mock(TenantReadinessService.class);}
  @Bean AdminPermissionService permissions(){return mock(AdminPermissionService.class);}
  @Bean WebsiteSecuritySettings settings(){return mock(WebsiteSecuritySettings.class);}
  @Bean OperationalIssueService issues(){return mock(OperationalIssueService.class);}
  @Bean(destroyMethod="destroy") LettuceConnectionFactory connection(){RedisStandaloneConfiguration c=new RedisStandaloneConfiguration("127.0.0.1",Integer.parseInt(System.getProperty("security.test.redis.port")));String password=System.getenv("MT705_TEST_REDIS_PASSWORD");if(password!=null&&!password.isBlank())c.setPassword(password);return new LettuceConnectionFactory(c);}
  @Bean StringRedisTemplate redis(LettuceConnectionFactory c){return new StringRedisTemplate(c);}
 }
 @Autowired TenantSmsService sms;@Autowired SystemConfigService configs;@Autowired AdminPermissionService permissions;
 @Autowired com.gtcfesk.exchange.control.TenantRepository tenants;@Autowired AdminUserRepository admins;@Autowired StringRedisTemplate redis;@Autowired ObjectMapper json;
 @Autowired PlatformTransactionManager manager;@Autowired JdbcTemplate db;@Autowired ControlAdminRepository controls;
 Long tenant,other,admin,control;Path root;String recipient;static java.util.concurrent.atomic.AtomicInteger sequence=new java.util.concurrent.atomic.AtomicInteger();
 @BeforeEach void setup()throws Exception{reset(permissions);Tenant t=new Tenant();t.setCode("sms"+UUID.randomUUID());t.setName("Synthetic SMS");tenant=tenants.saveAndFlush(t).getId();t=new Tenant();t.setCode("smsb"+UUID.randomUUID());t.setName("Other SMS");other=tenants.saveAndFlush(t).getId();TenantContext.open(tenant);configs.saveConfig("sms.provider","local-sink","Synthetic SMS fixture");TenantContext.clear();try(TenantContext.Scope scope=TenantContext.open(other)){configs.saveConfig("sms.provider","local-sink","Other synthetic SMS fixture");}TenantContext.open(tenant);AdminUser a=new AdminUser();a.setAccount(UUID.randomUUID().toString());a.setPasswordHash("unused");a.setEmail(a.getAccount()+"@fixture.invalid");a.setRole("super_admin");admin=admins.saveAndFlush(a).getId();ControlAdmin c=new ControlAdmin();c.setAccount(UUID.randomUUID().toString());c.setPasswordHash("unused");control=controls.saveAndFlush(c).getId();ordinary();String base=System.getenv("MT705_SMS_TEST_DIRECTORY");assertNotNull(base,"Explicit ACL-restricted ASCII SMS fixture root required");root=Paths.get(base).resolve(UUID.randomUUID().toString());Files.createDirectory(root);ReflectionTestUtils.setField(sms,"directory",root.toString());ReflectionTestUtils.setField(sms,"enabled",true);ReflectionTestUtils.setField(sms,"encryptionKey",Base64.getEncoder().encodeToString(new byte[32]));recipient=String.format(Locale.ROOT,"+120255501%02d",sequence.incrementAndGet()%100);}
 @AfterEach void clear(){TenantContext.clear();SecurityContextHolder.clearContext();}
 void ordinary(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin.toString(),null,List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));}
 void access(Long target){UsernamePasswordAuthenticationToken a=new UsernamePasswordAuthenticationToken("-"+control,null,List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),new SimpleGrantedAuthority("ROLE_CONTROL_ACCESS")));a.setDetails(new ControlIdentity(control,target,"sms-contract"));SecurityContextHolder.getContext().setAuthentication(a);}
 Map<String,Object> send(){return sms.send(recipient,"CONFIG_TEST");}
 String code(String id)throws Exception{return json.readTree(Files.readAllBytes(root.resolve("tenant-"+tenant).resolve(id+".json"))).path("code").asText();}
 long audits(){return db.queryForObject("select count(*) from operation_log where tenant_id=? and operation_type='短信配置测试'",Long.class,tenant);}
 @Test void localDeliveryPurposeExpirySingleUseAndMaskedAudit()throws Exception{Map<String,Object> receipt=send();String id=receipt.get("requestId").toString(),code=code(id);assertFalse(json.writeValueAsString(receipt).contains(code));String key="security:{sms}:tenant:"+tenant+":CONFIG_TEST:"+id;String stored=redis.opsForValue().get(key);assertTrue(stored.startsWith("A|"));assertFalse(stored.contains(code));assertTrue(redis.getExpire(key)>0&&redis.getExpire(key)<=180);assertThrows(IllegalArgumentException.class,()->sms.consume(id,recipient,"LOGIN",code));assertNotNull(redis.opsForValue().get(key));assertEquals(Boolean.TRUE,sms.consume(id,recipient,"CONFIG_TEST",code).get("verified"));assertEquals("SMS_CODE_INVALID",assertThrows(SecurityFailure.class,()->sms.consume(id,recipient,"CONFIG_TEST",code)).code);assertEquals(2,audits());String details=db.queryForObject("select request_params from operation_log where tenant_id=? and operation_action='SMS_LOCAL_PREPARED'",String.class,tenant);assertFalse(details.contains(code));assertFalse(details.contains(recipient));}
 @Test void sameControlActorCannotConsumeAcrossTenantOrAnotherPurpose()throws Exception{access(tenant);String id=send().get("requestId").toString(),code=code(id);TenantContext.clear();try(TenantContext.Scope scope=TenantContext.open(other)){access(other);assertEquals("SMS_CODE_INVALID",assertThrows(SecurityFailure.class,()->sms.consume(id,recipient,"CONFIG_TEST",code)).code);}TenantContext.open(tenant);access(tenant);assertEquals(Boolean.TRUE,sms.consume(id,recipient,"CONFIG_TEST",code).get("verified"));assertEquals(2,db.queryForObject("select count(*) from control_audit_log where actor_id=? and tenant_id=? and access_session_id='sms-contract'",Integer.class,control,tenant));}
 @Test void concurrentConsumptionHasExactlyOneEffect()throws Exception{String id=send().get("requestId").toString(),code=code(id);ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> results=new ArrayList<>();try{for(int n=0;n<8;n++)results.add(pool.submit(()->{try(TenantContext.Scope scope=TenantContext.open(tenant)){ordinary();start.await();try{sms.consume(id,recipient,"CONFIG_TEST",code);return true;}catch(SecurityFailure e){return false;}}finally{SecurityContextHolder.clearContext();}}));start.countDown();int passed=0;for(Future<Boolean> result:results)if(result.get(10,TimeUnit.SECONDS))passed++;assertEquals(1,passed);assertEquals(2,audits());}finally{pool.shutdownNow();}}
 @Test void expiryAndRecipientRateLimitsRejectWithoutSecondDelivery()throws Exception{String id=send().get("requestId").toString(),code=code(id);assertEquals(429,assertThrows(SecurityFailure.class,this::send).status);assertEquals(1,audits());try(java.util.stream.Stream<Path> files=Files.list(root.resolve("tenant-"+tenant))){assertEquals(1,files.count());}redis.expire("security:{sms}:tenant:"+tenant+":CONFIG_TEST:"+id,Duration.ofMillis(5));Thread.sleep(30);assertEquals("SMS_CODE_INVALID",assertThrows(SecurityFailure.class,()->sms.consume(id,recipient,"CONFIG_TEST",code)).code);assertEquals(1,audits());}
 @Test void rejectedAuditNeverPublishesUsableCodeOrLeavesSinkFile(){String constraint="sms_audit_"+UUID.randomUUID().toString().replace("-","");db.execute("alter table operation_log add constraint "+constraint+" check(tenant_id<>"+tenant+" or operation_action<>'SMS_LOCAL_PREPARED')");try{assertEquals(503,assertThrows(SecurityFailure.class,this::send).status);assertEquals(0,audits());assertTrue(redis.keys("security:{sms}:tenant:"+tenant+":CONFIG_TEST:*").isEmpty());try(java.util.stream.Stream<Path> files=Files.list(root.resolve("tenant-"+tenant))){assertEquals(0,files.count());}catch(java.io.IOException e){throw new RuntimeException(e);}}finally{db.execute("alter table operation_log drop constraint "+constraint);}}
 @Test void disabledOrUnspecifiedSupplierAgentAndRevokedPermissionDoNotDeliver(){configs.saveConfig("sms.provider","external-blocked","Synthetic provider unavailable");assertEquals("BLOCKED",sms.status().get("status"));assertEquals("SMS_PROVIDER_BLOCKED",assertThrows(SecurityFailure.class,this::send).code);configs.saveConfig("sms.provider","local-sink","Synthetic SMS fixture");SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin.toString(),null,List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));assertThrows(org.springframework.security.access.AccessDeniedException.class,this::send);ordinary();doThrow(new org.springframework.security.access.AccessDeniedException("revoked")).when(permissions).require("settings","save");assertThrows(org.springframework.security.access.AccessDeniedException.class,this::send);assertEquals(0,audits());assertFalse(Files.exists(root.resolve("tenant-"+tenant)));}
 @Test void redisFailureFailsClosedAndTenantSecretUsesExistingAead(){StringRedisTemplate unavailable=mock(StringRedisTemplate.class);ReflectionTestUtils.setField(sms,"redis",unavailable);try{assertEquals(503,assertThrows(SecurityFailure.class,this::send).status);assertEquals(0,audits());assertFalse(Files.exists(root.resolve("tenant-"+tenant)));}finally{ReflectionTestUtils.setField(sms,"redis",redis);}TenantSecrets secrets=new TenantSecrets();ReflectionTestUtils.setField(secrets,"key",Base64.getEncoder().encodeToString(new byte[32]));assertTrue(TenantSecrets.secret("sms.api_key"));String stored=secrets.encrypt("sms.api_key","synthetic-provider-secret");assertFalse(stored.contains("synthetic-provider-secret"));assertEquals("synthetic-provider-secret",secrets.decrypt("sms.api_key",stored));TenantContext.clear();try(TenantContext.Scope scope=TenantContext.open(other)){assertThrows(IllegalStateException.class,()->secrets.decrypt("sms.api_key",stored));}TenantContext.open(tenant);}
}
