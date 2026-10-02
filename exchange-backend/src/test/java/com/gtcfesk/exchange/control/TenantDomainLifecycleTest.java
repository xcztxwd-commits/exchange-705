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
 void successfulProbe(){when(outbound.routingCheck(eq(newHost),anyString())).thenAnswer(c->("{\"tenantId\":"+id+",\"challenge\":\""+c.getArgument(1)+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
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
}
