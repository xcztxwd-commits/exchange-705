package com.gtcfesk.exchange.control;
import com.gtcfesk.exchange.simulation.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.MediaType;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Exercises native demo SQL against disposable H2 and remote identity protocol with a mock server. */
class SimulationTenantIsolationTest {
 static { ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN); }
 @AfterEach void cleanup(){TenantContext.clear();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
 @Test void identityResponseMustMatchServerResolvedTenantAndForwardExactHost(){
  TenantContext.open(1L);TenantRepository tenants=mock(TenantRepository.class);Tenant t=new Tenant();t.setId(1L);t.setFrontendHost("a.example.com");t.setDomainVerified(true);when(tenants.findById(1L)).thenReturn(Optional.of(t));
  SimulationGateway gateway=new SimulationGateway();ReflectionTestUtils.setField(gateway,"tenants",tenants);ReflectionTestUtils.setField(gateway,"identityUrl","https://identity.invalid/api/simulation");
  MockRestServiceServer server=MockRestServiceServer.createServer((RestTemplate)ReflectionTestUtils.getField(gateway,"http"));
  server.expect(requestTo("https://identity.invalid/api/simulation/session")).andExpect(header("X-Forwarded-Host","a.example.com")).andExpect(header("Authorization","Bearer sample")).andRespond(withSuccess("{\"tenantId\":2,\"userId\":7,\"environment\":\"REAL\"}",MediaType.APPLICATION_JSON));
  assertThrows(IllegalStateException.class,()->gateway.authenticate("Bearer sample"));server.verify();
 }
 @Test void catalogCannotDisableForeignProductsAndMismatchRollsBack(){
  Fixture f=new Fixture();TenantContext.open(1L);f.jdbc.update("insert into trading_symbol values(10,1,true),(20,2,true)");
  when(f.gateway.get("/catalog","Bearer sample")).thenReturn(f.catalog());f.service.catalog("Bearer sample");assertFalse(f.jdbc.queryForObject("select is_enabled from trading_symbol where id=10",Boolean.class));assertTrue(f.jdbc.queryForObject("select is_enabled from trading_symbol where id=20",Boolean.class));
  Fixture mismatch=new Fixture();mismatch.jdbc.update("insert into trading_symbol values(10,1,true),(20,2,true)");Map<String,Object> snapshot=mismatch.catalog();Map<String,Object> row=new LinkedHashMap<>();row.put("id",20L);row.put("tenant_id",2L);row.put("is_enabled",false);snapshot.put("trading_symbol",Collections.singletonList(row));when(mismatch.gateway.get("/catalog","Bearer sample")).thenReturn(snapshot);
  assertThrows(IllegalArgumentException.class,()->mismatch.service.catalog("Bearer sample"));assertTrue(mismatch.jdbc.queryForObject("select is_enabled from trading_symbol where id=10",Boolean.class));assertTrue(mismatch.jdbc.queryForObject("select is_enabled from trading_symbol where id=20",Boolean.class));
 }
 @Test void simulationNewBusinessUsesLiveRealTenantAuthorizationNotDemoPolicyCache(){
  TenantContext.open(1L);SimulationEnvironment environment=mock(SimulationEnvironment.class);when(environment.enabled()).thenReturn(true);SimulationGateway gateway=mock(SimulationGateway.class);
  TenantPolicyService policy=new TenantPolicyService(mock(TenantRepository.class),mock(TenantPolicyRepository.class));ReflectionTestUtils.setField(policy,"simulation",environment);ReflectionTestUtils.setField(policy,"gateway",gateway);
  org.springframework.mock.web.MockHttpServletRequest request=new org.springframework.mock.web.MockHttpServletRequest();request.addHeader("Authorization","Bearer sample");org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
  Map<String,Object> snapshot=new HashMap<>();Map<String,Boolean> features=new HashMap<>();features.put("simulation",true);features.put("option",true);snapshot.put("features",features);snapshot.put("acceptNewBusiness",true);when(gateway.get("/session","Bearer sample")).thenReturn(snapshot);
  assertDoesNotThrow(()->policy.requireNewBusiness("option"));features.put("simulation",false);assertThrows(RuntimeException.class,()->policy.requireNewBusiness("option"));features.put("simulation",true);snapshot.put("acceptNewBusiness",false);assertThrows(RuntimeException.class,()->policy.requireNewBusiness("option"));
 }
 @Test void demoSeedIsIdempotentAndCannotReuseForeignUserId(){
  Fixture f=new Fixture();TenantContext.open(1L);f.service.user(7L);f.service.user(7L);assertEquals(3,f.jdbc.queryForObject("select count(*) from asset_account where tenant_id=1",Integer.class));assertEquals(1,f.jdbc.queryForObject("select count(*) from simulation_seed",Integer.class));
  TenantContext.clear();TenantContext.open(2L);assertThrows(RuntimeException.class,()->f.service.user(7L));assertEquals(0,f.jdbc.queryForObject("select count(*) from asset_account where tenant_id=2",Integer.class));assertEquals(1L,f.jdbc.queryForObject("select tenant_id from user_account where id=7",Long.class));
 }
 static class Fixture {
  DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:simulation_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");JdbcTemplate jdbc=new JdbcTemplate(ds);SimulationGateway gateway=mock(SimulationGateway.class);SimulationProvisioner service;
  Fixture(){SimulationEnvironment environment=mock(SimulationEnvironment.class);when(environment.enabled()).thenReturn(true);service=new SimulationProvisioner(environment,gateway,jdbc,new DataSourceTransactionManager(ds),mock(ForexQuoteMarketService.class));
   jdbc.execute("create table trading_symbol(id bigint primary key,tenant_id bigint not null,is_enabled boolean)");for(String table:Arrays.asList("option_duration","loan_setting","financial_product"))jdbc.execute("create table "+table+"(id bigint primary key,tenant_id bigint not null,enabled boolean)");
   jdbc.execute("create table system_config(id bigint auto_increment primary key,tenant_id bigint not null,config_key varchar(128),config_value varchar(1000),description varchar(1000),created_at timestamp,updated_at timestamp)");
   jdbc.execute("create table deposit_setting(id bigint auto_increment primary key,tenant_id bigint not null,type varchar(32),network varchar(32),address varchar(255),bank_name varchar(255),bank_account varchar(255),account_name varchar(255),enabled boolean,created_at timestamp,updated_at timestamp)");
   jdbc.execute("create table user_account(id bigint primary key,tenant_id bigint not null,row_version bigint,email varchar(255),password_hash varchar(255),nickname varchar(255),status varchar(32),user_type varchar(32),kyc_level int,kyc_status varchar(32),created_at timestamp,updated_at timestamp)");
   jdbc.execute("create table asset_account(id bigint auto_increment primary key,tenant_id bigint not null,user_id bigint,coin varchar(32),available decimal(32,16),frozen decimal(32,16),row_version bigint,created_at timestamp,updated_at timestamp,unique(tenant_id,user_id,coin))");
   jdbc.execute("create table simulation_seed(tenant_id bigint not null,user_id bigint,amount_per_wallet decimal(32,16),created_at timestamp,primary key(tenant_id,user_id))");
  }
  Map<String,Object> catalog(){Map<String,Object> c=new LinkedHashMap<>();c.put("tenantId",1L);for(String t:Arrays.asList("trading_symbol","option_duration","loan_setting","financial_product","system_config"))c.put(t,Collections.emptyList());return c;}
 }
}
