package com.gtcfesk.exchange.control;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import com.gtcfesk.exchange.security.OutboundEndpointPolicy;
import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringJUnitConfig(TenantDomainLifecycleTest.Config.class)
class TenantDomainLifecycleTest{
 @Configuration @EnableTransactionManagement @EnableJpaRepositories(basePackages="com.gtcfesk.exchange.control") @Import(TenantDomainVerification.class)
 static class Config{
  @Bean DataSource ds(){return new DriverManagerDataSource("jdbc:h2:mem:domain_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds){LocalContainerEntityManagerFactoryBean f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);f.setPackagesToScan("com.gtcfesk.exchange.control");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","create-drop");p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");f.setJpaProperties(p);return f;}
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
  @Bean ControlAuditService audit(){return mock(ControlAuditService.class);}
  @Bean OutboundEndpointPolicy outbound(){return mock(OutboundEndpointPolicy.class);}
  @Bean TenantHostService hosts(TenantRepository t){return new TenantHostService(t,"example.test","https://admin.example.test","https://control.example.test","");}
 }
 @Autowired TenantDomainVerification domains;@Autowired TenantRepository tenants;@Autowired TenantDomainBindingRepository bindings;@Autowired ControlAuditService audit;@Autowired OutboundEndpointPolicy outbound;
 Long id;String oldHost,newHost;
 @BeforeEach void setup(){reset(audit,outbound);UsernamePasswordAuthenticationToken a=new UsernamePasswordAuthenticationToken("51",null,Collections.singleton(new SimpleGrantedAuthority("ROLE_CONTROL")));a.setDetails(new ControlIdentity(51L,null,null));SecurityContextHolder.getContext().setAuthentication(a);String code="t"+UUID.randomUUID().toString().replace("-","");oldHost=code+".example.test";newHost="n"+code+".example.test";Tenant t=new Tenant();t.setCode(code);t.setName(code);t.setFrontendHost(oldHost);t.setDomainVerified(true);t.setConfigReady(true);t.setStatus("ACTIVE");id=tenants.saveAndFlush(t).getId();TenantDomainBinding b=new TenantDomainBinding();b.setHostname(oldHost);b.setTenantId(id);b.setStatus("ACTIVE");bindings.saveAndFlush(b);}
 @AfterEach void clean(){SecurityContextHolder.clearContext();}
 long version(Map<String,Object> m){return ((Number)m.get("version")).longValue();}
 void successfulProbe(){when(outbound.routingCheck(eq(newHost),anyString())).thenAnswer(c->("{\"tenantId\":"+id+",\"challenge\":\""+c.getArgument(1)+"\",\"role\":\"FRONTEND\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 @Test void failedProbeLeavesActiveHostAndActivationIsAtomicAndIdempotent(){
  Map<String,Object> p=domains.prepare(id,newHost,"change hostname");assertEquals(oldHost,tenants.findById(id).orElseThrow().getFrontendHost());when(outbound.routingCheck(eq(newHost),anyString())).thenThrow(new IllegalArgumentException("TLS fixture failure"));assertThrows(RuntimeException.class,()->domains.verify(id,newHost,version(p)));assertEquals(oldHost,tenants.findById(id).orElseThrow().getFrontendHost());assertEquals("ACTIVE",tenants.findById(id).orElseThrow().getStatus());assertThrows(RuntimeException.class,()->domains.activate(id,newHost,version(p),"premature activate"));
  successfulProbe();Map<String,Object> v=domains.verify(id,newHost,version(p));assertEquals("VERIFIED",v.get("status"));assertEquals(oldHost,tenants.findById(id).orElseThrow().getFrontendHost());Map<String,Object> activated=domains.activate(id,newHost,version(v),"approved local switch");assertEquals("ACTIVE",activated.get("status"));assertEquals(newHost,tenants.findById(id).orElseThrow().getFrontendHost());assertEquals("RETIRED",bindings.findById(oldHost).orElseThrow().getStatus());assertEquals(activated,domains.activate(id,newHost,version(v),"retry lost response"));
 }
 @Test void challengeVersionExpiryAndTenantOwnerCannotBeReplayed(){
  Map<String,Object> p=domains.prepare(id,newHost,"prepare candidate");String nonce=bindings.findById(newHost).orElseThrow().getChallenge();assertEquals(id,domains.routing(newHost,nonce).get("tenantId"));assertThrows(RuntimeException.class,()->domains.routing(newHost,"0".repeat(32)));Map<String,Object> newer=domains.prepare(id,newHost,"replace challenge");assertThrows(RuntimeException.class,()->domains.verify(id,newHost,version(p)));assertThrows(RuntimeException.class,()->domains.routing(newHost,nonce));assertThrows(RuntimeException.class,()->domains.verify(id+1,newHost,version(newer)));
  TenantDomainBinding b=bindings.findById(newHost).orElseThrow();b.setExpiresAt(Instant.now().minusSeconds(1));bindings.saveAndFlush(b);assertThrows(RuntimeException.class,()->domains.verify(id,newHost,b.getVersion()));assertEquals(oldHost,tenants.findById(id).orElseThrow().getFrontendHost());
 }
 @Test void auditFailureRollsBackSwitchAndRetiredReuseRequiresExplicitReview(){
  Map<String,Object> p=domains.prepare(id,newHost,"prepare candidate");successfulProbe();Map<String,Object> v=domains.verify(id,newHost,version(p));doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(),any(),any(),eq("DOMAIN_ACTIVATE"),any(),any(),any(),any());assertThrows(RuntimeException.class,()->domains.activate(id,newHost,version(v),"activate candidate"));assertEquals(oldHost,tenants.findById(id).orElseThrow().getFrontendHost());assertEquals("VERIFIED",bindings.findById(newHost).orElseThrow().getStatus());reset(audit);domains.activate(id,newHost,version(v),"activate candidate");assertThrows(RuntimeException.class,()->domains.prepare(id,oldHost,"reuse without review"));TenantDomainBinding retired=bindings.findById(oldHost).orElseThrow();domains.release(id,oldHost,retired.getVersion(),"reviewed retired host");assertEquals("PENDING",domains.prepare(id,oldHost,"reuse reviewed host").get("status"));
 }
 @Test void independentIdentityAndGlobalOccupationRequired(){Map<String,Object> p=domains.prepare(id,newHost,"reserve candidate");Tenant t=new Tenant();t.setCode("other"+id);t.setName("Other");Long other=tenants.saveAndFlush(t).getId();assertThrows(RuntimeException.class,()->domains.prepare(other,newHost,"cannot take reserved"));UsernamePasswordAuthenticationToken a=new UsernamePasswordAuthenticationToken("-51",null,Collections.singleton(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")));a.setDetails(new ControlIdentity(51L,id,"access"));SecurityContextHolder.getContext().setAuthentication(a);assertThrows(RuntimeException.class,()->domains.prepare(id,"x.example.test","access not control"));assertThrows(RuntimeException.class,()->domains.activate(id,newHost,version(p),"not independent"));}

 long domainVersion(){return tenants.findById(id).orElseThrow().getDomainVersion();}
 String entry(){return "entry"+id+".forex-exchange.net";}
 void probeBoth(){doAnswer(c->{TenantDomainBinding b=bindings.findById(c.getArgument(0)).orElseThrow();return ("{\"tenantId\":"+b.getTenantId()+",\"challenge\":\""+c.getArgument(1)+"\",\"role\":\""+b.getRole()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);}).when(outbound).routingCheck(anyString(),anyString());}
 void markOldVerified(){TenantDomainBinding b=bindings.findById(oldHost).orElseThrow();b.setVerifiedAt(Instant.now());bindings.saveAndFlush(b);}
 java.util.List<TenantDomainVerification.Selection> selections(){java.util.List<TenantDomainVerification.Selection> all=new java.util.ArrayList<>();for(Map<String,Object> b:domains.candidates(id)){TenantDomainVerification.Selection s=new TenantDomainVerification.Selection();s.role=(String)b.get("role");s.hostname=(String)b.get("hostname");s.version=((Number)b.get("version")).longValue();all.add(s);}return all;}
 void verifyBoth(){for(TenantDomainVerification.Selection s:selections())domains.verify(id,s.role,s.hostname,s.version,domainVersion());}
 @Test void roleCandidatesNeverOverwriteEachOtherAndChallengeBindsRole(){
  domains.prepare(id,"ENTRY",entry(),domainVersion(),"prepare entry");Map<String,Object> front=domains.prepare(id,newHost,"prepare frontend");assertEquals(2,domains.candidates(id).size());
  domains.prepare(id,"ENTRY","nextentry"+id+".forex-exchange.net",domainVersion(),"replace entry");assertEquals("PENDING",bindings.findById(newHost).orElseThrow().getStatus());assertEquals("RETIRED",bindings.findById(entry()).orElseThrow().getStatus());
  when(outbound.routingCheck(eq(newHost),anyString())).thenAnswer(c->("{\"tenantId\":"+id+",\"challenge\":\""+c.getArgument(1)+"\",\"role\":\"ENTRY\"}").getBytes());
  assertThrows(RuntimeException.class,()->domains.verify(id,"FRONTEND",newHost,version(front),domainVersion()));assertFalse(tenants.findById(id).orElseThrow().isEntryVerified());assertEquals("PENDING",bindings.findById(newHost).orElseThrow().getStatus());
 }
 @Test void allCandidatesMustVerifyBeforeAtomicActivationAndMappingUsesCurrentFrontend(){
  markOldVerified();probeBoth();domains.prepareChange(id,entry(),newHost,domainVersion(),"both changed");TenantDomainVerification.Selection e=selections().stream().filter(b->b.role.equals("ENTRY")).findFirst().orElseThrow();domains.verify(id,e.role,e.hostname,e.version,domainVersion());
  assertThrows(RuntimeException.class,()->domains.activateChange(id,domainVersion(),selections(),true,"partial denied"));assertEquals(oldHost,tenants.findById(id).orElseThrow().getFrontendHost());assertNull(tenants.findById(id).orElseThrow().getEntryHost());
  TenantDomainVerification.Selection f=selections().stream().filter(b->b.role.equals("FRONTEND")).findFirst().orElseThrow();domains.verify(id,f.role,f.hostname,f.version,domainVersion());
  assertThrows(RuntimeException.class,()->domains.activate(id,newHost,bindings.findById(newHost).orElseThrow().getVersion(),"old API partial denied"));
  long before=domainVersion();java.util.List<TenantDomainVerification.Selection> selected=selections();domains.activateChange(id,before,selected,true,"atomic switch");assertEquals(newHost,domains.entryTarget(entry()));assertEquals("RETIRED",bindings.findById(oldHost).orElseThrow().getStatus());assertThrows(RuntimeException.class,()->domains.activateChange(id,before,selected,true,"stale activation"));
  long session=tenants.findById(id).orElseThrow().getSessionVersion();domains.setEntryEnabled(id,false,domainVersion(),"close entry");assertEquals(session,tenants.findById(id).orElseThrow().getSessionVersion());assertTrue(tenants.findById(id).orElseThrow().isDomainVerified());assertThrows(RuntimeException.class,()->domains.entryTarget(entry()));
 }
 @Test void entryOnlyAndFrontendOnlySwitchPreserveOtherRoleAndAutomaticallyRetarget(){
  markOldVerified();probeBoth();domains.prepareChange(id,entry(),oldHost,domainVersion(),"entry only");verifyBoth();domains.activateChange(id,domainVersion(),selections(),true,"entry active");assertEquals(oldHost,domains.entryTarget(entry()));
  domains.prepareChange(id,entry(),newHost,domainVersion(),"frontend only");verifyBoth();domains.activateChange(id,domainVersion(),selections(),null,"frontend active");assertEquals(newHost,domains.entryTarget(entry()));assertEquals("ACTIVE",bindings.findById(entry()).orElseThrow().getStatus());
  String otherEntry="otherentry"+id+".forex-exchange.net";domains.prepareChange(id,otherEntry,newHost,domainVersion(),"entry change");verifyBoth();domains.activateChange(id,domainVersion(),selections(),null,"new entry active");assertEquals(newHost,domains.entryTarget(otherEntry));assertThrows(RuntimeException.class,()->domains.entryTarget(entry()));
 }
 @Test void auditFailuresAndUnverifiedTargetCannotOpenOrChangeLiveConfiguration(){
  markOldVerified();probeBoth();domains.prepareChange(id,entry(),newHost,domainVersion(),"both changed");verifyBoth();long v=domainVersion();
  doThrow(new IllegalStateException("audit failed")).when(audit).record(any(),any(),any(),eq("DOMAIN_ACTIVATE"),any(),any(),any(),any());assertThrows(RuntimeException.class,()->domains.activateChange(id,v,selections(),true,"audit rollback"));assertEquals(v,domainVersion());assertEquals(oldHost,tenants.findById(id).orElseThrow().getFrontendHost());assertNull(tenants.findById(id).orElseThrow().getEntryHost());assertEquals("ACTIVE",bindings.findById(oldHost).orElseThrow().getStatus());assertEquals(2,domains.candidates(id).size());reset(audit);
  assertThrows(RuntimeException.class,()->domains.setEntryEnabled(id,true,v,"target incomplete"));domains.activateChange(id,v,selections(),false,"safe closed activation");
  Tenant t=tenants.findById(id).orElseThrow();t.setStatus("DISABLED");tenants.saveAndFlush(t);assertThrows(RuntimeException.class,()->domains.setEntryEnabled(id,true,domainVersion(),"disabled target"));
 }
 @Test void expiryTlsChallengeAndStaleConfigurationKeepOriginalHosts(){
  probeBoth();domains.prepareChange(id,entry(),newHost,domainVersion(),"prepare both");java.util.List<TenantDomainVerification.Selection> before=selections();long v=domainVersion();
  when(outbound.routingCheck(eq(entry()),anyString())).thenThrow(new IllegalArgumentException("TLS failure"));TenantDomainVerification.Selection e=before.stream().filter(b->b.role.equals("ENTRY")).findFirst().orElseThrow();assertThrows(RuntimeException.class,()->domains.verify(id,e.role,e.hostname,e.version,v));
  probeBoth();verifyBoth();TenantDomainBinding expired=bindings.findById(entry()).orElseThrow();expired.setExpiresAt(Instant.now().minusSeconds(1));bindings.saveAndFlush(expired);assertThrows(RuntimeException.class,()->domains.activateChange(id,v,selections(),true,"expired denied"));assertEquals(oldHost,tenants.findById(id).orElseThrow().getFrontendHost());assertNull(tenants.findById(id).orElseThrow().getEntryHost());
  domains.setEntryEnabled(id,false,v,"config changed");assertThrows(RuntimeException.class,()->domains.activateChange(id,v,selections(),false,"stale denied"));
 }
 @Test void foreignReservationsRetirementAndReleaseAreRoleScoped(){
  domains.prepare(id,"ENTRY",entry(),domainVersion(),"reserve entry");Tenant other=new Tenant();other.setCode("owner"+id);other.setName("other");Long foreign=tenants.saveAndFlush(other).getId();assertThrows(RuntimeException.class,()->domains.prepare(foreign,"ENTRY",entry(),0L,"foreign denied"));
  domains.prepare(id,"ENTRY","newentry"+id+".forex-exchange.net",domainVersion(),"retire candidate");TenantDomainBinding retired=bindings.findById(entry()).orElseThrow();assertThrows(RuntimeException.class,()->domains.prepare(foreign,"ENTRY",entry(),0L,"retired denied"));assertThrows(RuntimeException.class,()->domains.release(id,"FRONTEND",entry(),retired.getVersion(),"wrong role"));
  domains.release(id,"ENTRY",entry(),retired.getVersion(),"explicit review");domains.prepare(foreign,"ENTRY",entry(),0L,"reuse released");assertEquals(foreign,bindings.findById(entry()).orElseThrow().getTenantId());assertTrue(tenants.findById(id).orElseThrow().isDomainVerified());assertFalse(tenants.findById(id).orElseThrow().isEntryEnabled());
 }
 @Test void lateNetworkReplyCannotVerifyRepreparedOrConcurrentConfiguration()throws Exception{
  probeBoth();Map<String,Object> p=domains.prepare(id,"ENTRY",entry(),domainVersion(),"reserve entry");long v=domainVersion();java.util.concurrent.CountDownLatch probing=new java.util.concurrent.CountDownLatch(1),resume=new java.util.concurrent.CountDownLatch(1);
  when(outbound.routingCheck(eq(entry()),anyString())).thenAnswer(c->{probing.countDown();assertTrue(resume.await(10,java.util.concurrent.TimeUnit.SECONDS));return ("{\"tenantId\":"+id+",\"challenge\":\""+c.getArgument(1)+"\",\"role\":\"ENTRY\"}").getBytes();});
  org.springframework.security.core.Authentication auth=SecurityContextHolder.getContext().getAuthentication();java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newSingleThreadExecutor();
  try{java.util.concurrent.Future<?> future=pool.submit(()->{SecurityContextHolder.getContext().setAuthentication(auth);try{domains.verify(id,"ENTRY",entry(),version(p),v);}finally{SecurityContextHolder.clearContext();}});assertTrue(probing.await(10,java.util.concurrent.TimeUnit.SECONDS));domains.prepare(id,newHost,"concurrent frontend candidate");resume.countDown();assertThrows(java.util.concurrent.ExecutionException.class,()->future.get(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals("PENDING",bindings.findById(entry()).orElseThrow().getStatus());assertEquals(oldHost,tenants.findById(id).orElseThrow().getFrontendHost());}finally{resume.countDown();pool.shutdownNow();}
 }
 @Test void concurrentActivationHasOneWinnerAndNeverMixesTwoConfigurations()throws Exception{
  markOldVerified();probeBoth();domains.prepareChange(id,entry(),newHost,domainVersion(),"both candidates");verifyBoth();java.util.List<TenantDomainVerification.Selection> selected=selections();long v=domainVersion();org.springframework.security.core.Authentication auth=SecurityContextHolder.getContext().getAuthentication();java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
  java.util.concurrent.Callable<Boolean> activate=()->{SecurityContextHolder.getContext().setAuthentication(auth);try{start.await();domains.activateChange(id,v,selected,true,"concurrent activate");return true;}catch(IllegalArgumentException expected){return false;}finally{SecurityContextHolder.clearContext();}};
  try{java.util.concurrent.Future<Boolean> one=pool.submit(activate),two=pool.submit(activate);start.countDown();assertNotEquals(one.get(10,java.util.concurrent.TimeUnit.SECONDS),two.get(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(newHost,domains.entryTarget(entry()));assertEquals(v+1,domainVersion());}finally{pool.shutdownNow();}
 }
 @Test void auditFailureRollsBackPreparationVerificationAndEntrySwitch(){
  markOldVerified();probeBoth();long initial=domainVersion();
  doThrow(new IllegalStateException("audit failed")).when(audit).record(any(),any(),any(),eq("DOMAIN_PREPARE"),any(),any(),any(),any());
  assertThrows(RuntimeException.class,()->domains.prepareChange(id,entry(),newHost,initial,"prepare rollback"));assertEquals(initial,domainVersion());assertTrue(domains.candidates(id).isEmpty());assertEquals(oldHost,tenants.findById(id).orElseThrow().getFrontendHost());
  reset(audit);domains.prepareChange(id,entry(),oldHost,initial,"entry only");TenantDomainVerification.Selection e=selections().get(0);long prepared=domainVersion();
  doThrow(new IllegalStateException("audit failed")).when(audit).record(any(),any(),any(),eq("DOMAIN_CANDIDATE_VERIFIED"),any(),any(),any(),any());
  assertThrows(RuntimeException.class,()->domains.verify(id,e.role,e.hostname,e.version,prepared));assertEquals("PENDING",bindings.findById(entry()).orElseThrow().getStatus());assertEquals(prepared,domainVersion());assertNull(tenants.findById(id).orElseThrow().getEntryHost());
  reset(audit);verifyBoth();domains.activateChange(id,domainVersion(),selections(),true,"entry active");long active=domainVersion(),session=tenants.findById(id).orElseThrow().getSessionVersion();
  doThrow(new IllegalStateException("audit failed")).when(audit).record(any(),any(),any(),eq("DOMAIN_ENTRY_SWITCH"),any(),any(),any(),any());
  assertThrows(RuntimeException.class,()->domains.setEntryEnabled(id,false,active,"switch rollback"));assertEquals(active,domainVersion());assertEquals(session,tenants.findById(id).orElseThrow().getSessionVersion());assertEquals(oldHost,domains.entryTarget(entry()));
 }

}
