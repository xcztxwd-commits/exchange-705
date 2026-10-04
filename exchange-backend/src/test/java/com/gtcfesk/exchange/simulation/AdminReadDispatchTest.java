package com.gtcfesk.exchange.simulation;
import com.gtcfesk.exchange.market.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.context.annotation.Import(com.gtcfesk.exchange.tenant.BootTenantFixture.class)
@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
@org.springframework.test.context.TestPropertySource(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:multitenant-market-test.sql","platform.base-domain=mt705.test","platform.admin-origin=https://admin.mt705.test","platform.control-origin=https://control.mt705.test"})
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "simulation.enabled=true", "simulation.inspection-key=12345678901234567890123456789012", "spring.datasource.url=jdbc:h2:mem:adminread_demo;MODE=MySQL;DB_CLOSE_DELAY=-1", "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "market.exchange.stream-enabled=false", "market.depth.enabled=false", "market.exchange.spot-url=http://127.0.0.1:9", "market.exchange.futures-url=http://127.0.0.1:9", "spring.redis.port=1"})
class AdminReadDispatchTest {
 @Autowired TestRestTemplate client;
 @Autowired com.gtcfesk.exchange.repository.UserBankCardRepository cards;
 @Autowired com.gtcfesk.exchange.repository.UserAccountRepository users;
 @MockBean SimulationGateway gateway;
 @MockBean ForexQuoteMarketService quotes;
 @MockBean RedisMarketService redis;
 ResponseEntity<String> query(String method,String path,boolean key){
  AdminReadRoutes.Query q=new AdminReadRoutes.Query();q.method=method;q.path=path;q.body.put("page",0);q.body.put("size",10);
  HttpHeaders h=new HttpHeaders();h.set("X-Simulation-Tenant-Id","1");if(key)h.set("X-Simulation-Inspection-Key","12345678901234567890123456789012");
  return client.postForEntity("/api/simulation/admin-query",new HttpEntity<>(q,h),String.class);
 }
 @Test void forwardsRealMvcQueriesWithoutOpeningMutations(){
  for(String path:Arrays.asList("/api/admin/users/query","/api/admin/orders/contract/query","/api/admin/orders/option/query")){
   ResponseEntity<String> r=query("POST",path,true);assertEquals(200,r.getStatusCodeValue(),path+" "+r.getBody());assertTrue(r.getBody().contains("list"));assertEquals("DEMO",r.getHeaders().getFirst("X-Account-Environment"));
  }
  assertEquals(400,query("POST","/api/admin/users/updateBalance",true).getStatusCodeValue());
  assertEquals(403,query("POST","/api/admin/users/query",false).getStatusCodeValue());
 }
 @Test void everyAllowedListKeepsOriginalResponseContract(){
  for(String path:Arrays.asList("/api/admin/deposit/review/list","/api/admin/withdraw/list","/api/admin/loan/review/list","/api/admin/loan/personal-info/list","/api/admin/kyc/list","/api/admin/financial/orders","/api/admin/deposit/orders/list","/api/admin/deposit/orders/summary","/api/admin/deposit/orders/export","/api/admin/activities","/api/admin/support/inbox","/api/admin/statistics")){
   ResponseEntity<String> r=query("GET",path,true);assertEquals(200,r.getStatusCodeValue(),path+" "+r.getBody());
  }
 }
 @Test void readsSeededDemoWalletUsingOriginalController(){
  com.gtcfesk.exchange.entity.UserAccount owner=new com.gtcfesk.exchange.entity.UserAccount();owner.setEmail("demo-wallet-"+UUID.randomUUID()+"@example.invalid");owner.setPasswordHash("NOT_A_LOGIN_PASSWORD");owner=users.saveAndFlush(owner);assertEquals(Long.valueOf(1L),owner.getTenantId());
  com.gtcfesk.exchange.entity.UserBankCard card=new com.gtcfesk.exchange.entity.UserBankCard();card.setUserId(owner.getId());card.setCurrency("USD");card.setBankName("DEMO_ONLY_BANK");card.setRecipientName("Demo fixture");card.setRecipientAccount("DEMO123");card=cards.saveAndFlush(card);assertEquals(owner.getTenantId(),card.getTenantId());assertEquals(owner.getId(),card.getUserId());
  String wallet="/api/admin/wallet/"+owner.getId()+"/bank-cards";ResponseEntity<String> r=query("GET",wallet,true);
  assertEquals(200,r.getStatusCodeValue());assertTrue(r.getBody().contains("DEMO_ONLY_BANK"));assertTrue(r.getBody().contains("DEMO123"));
  assertEquals(400,query("POST",wallet,true).getStatusCodeValue());
 }
 @Test void detailRoutesRequireOriginalActions(){
  assertEquals("users:wallet_management",AdminReadRoutes.permission("GET","/api/admin/wallet/1/bank-cards"));
  assertEquals("financial_orders:detail",AdminReadRoutes.permission("GET","/api/admin/financial/yield/order/1"));
  assertThrows(IllegalArgumentException.class,()->AdminReadRoutes.permission("GET","/api/admin/users/1/../2"));
 }
}
