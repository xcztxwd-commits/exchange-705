package com.gtcfesk.exchange.simulation;

import com.gtcfesk.exchange.market.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full application/filter/controller smoke test. Only real-identity gateway and market feed are fixtures. */
@org.springframework.context.annotation.Import(com.gtcfesk.exchange.tenant.BootTenantFixture.class)
@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
@org.springframework.test.context.TestPropertySource(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:multitenant-market-test.sql","security.trusted-proxies=127.0.0.1/32", "platform.base-domain=mt705.test","platform.admin-origin=https://admin.mt705.test","platform.control-origin=https://control.mt705.test"})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "simulation.enabled=true", "spring.datasource.url=jdbc:h2:mem:boot_demo;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "market.exchange.stream-enabled=false", "market.depth.enabled=false", "market.exchange.spot-url=http://127.0.0.1:9", "market.exchange.futures-url=http://127.0.0.1:9", "spring.redis.port=1"})
class SimulationApplicationTest {
 @Autowired TestRestTemplate client;
 @MockBean SimulationGateway gateway;
 @MockBean ForexQuoteMarketService quotes;
 @MockBean RedisMarketService redis;
 @BeforeEach void setup() {
  when(gateway.authenticate("Bearer simulation-fixture")).thenReturn(8001L);
  Map<String,Object> snapshot=new LinkedHashMap<>();for(String table:SimulationCatalogController.TABLES)snapshot.put(table,Collections.emptyList());snapshot.put("system_config",Collections.emptyList());
  when(gateway.get("/catalog","Bearer simulation-fixture")).thenReturn(snapshot);
 }
 ResponseEntity<Map> get(String path,String mode,boolean signed) {
  HttpHeaders headers=new HttpHeaders();headers.set("X-Account-Mode",mode);headers.set("X-Forwarded-Host",com.gtcfesk.exchange.tenant.BootTenantFixture.FRONT);if(signed)headers.setBearerAuth("simulation-fixture");
  return client.exchange(path,HttpMethod.GET,new HttpEntity<>(headers),Map.class);
 }
 @Test void loginSessionCreatesIndependentAccountAndKycRemainsUnverified() {
  ResponseEntity<Map> session=get("/api/simulation/session","DEMO",true);
  assertEquals(200,session.getStatusCodeValue());assertEquals("DEMO",session.getBody().get("environment"));
  Map kyc=get("/api/kyc/status","DEMO",true).getBody();assertEquals(true,kyc.get("simulationExempt"));assertEquals("NOT_VERIFIED",kyc.get("kycStatus"));assertEquals(true,kyc.get("canTrade"));
  ResponseEntity<Map> assets=get("/api/user/assets","DEMO",true);assertEquals(200,assets.getStatusCodeValue());assertEquals("DEMO",assets.getHeaders().getFirst("X-Account-Environment"));
 }
 @Test void headerCannotReplaceLoginAndRealModeCannotAccessDemo() {
  assertEquals(401,get("/api/user/assets","DEMO",false).getStatusCodeValue());
  assertEquals(409,get("/api/user/assets","REAL",true).getStatusCodeValue());
 }
 @Test void unauthenticatedLocalRegistrationIsDisabled() {
  HttpHeaders headers=new HttpHeaders();headers.set("X-Account-Mode","DEMO");headers.set("X-Forwarded-Host",com.gtcfesk.exchange.tenant.BootTenantFixture.FRONT);
  ResponseEntity<Map> response=client.exchange("/api/auth/register",HttpMethod.POST,new HttpEntity<>(Collections.emptyMap(),headers),Map.class);
  assertEquals(409,response.getStatusCodeValue());
 }
}
