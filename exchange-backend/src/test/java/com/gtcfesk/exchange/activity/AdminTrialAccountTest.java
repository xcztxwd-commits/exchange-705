package com.gtcfesk.exchange.activity;

import com.gtcfesk.exchange.admin.AdminTrialAccountService;
import com.gtcfesk.exchange.admin.AdminTrialAccountController;
import com.gtcfesk.exchange.admin.dto.UpdateTrialAccountRequest;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.repository.BalanceAdjustmentRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig({ActivityIntegrationTest.Config.class,AdminTrialAccountService.class})
class AdminTrialAccountTest extends ActivityFeatureFixture {
 @Autowired AdminTrialAccountService admin;
 @Autowired BalanceAdjustmentRepository receipts;
 @Autowired ControlAuditService audit;
 @BeforeEach void administrator(){authenticate("ROLE_SUPER_ADMIN");reset(audit);clock("2030-01-01T00:00:00Z");}
 @AfterEach void clearAuthentication(){SecurityContextHolder.clearContext();reset(audit);}
 static void authenticate(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1","test-only",Collections.singletonList(new SimpleGrantedAuthority(role))));}
 UpdateTrialAccountRequest input(String amount,boolean enabled){
  UpdateTrialAccountRequest r=new UpdateTrialAccountRequest();r.setAvailable(d(amount));r.setEnabled(enabled);r.setExpectedVersion(((Number)admin.account(user).get("rowVersion")).longValue());r.setRequestId("trial-"+UUID.randomUUID());r.setReason("admin trial test");r.setConfirm(true);return r;
 }
 void gift(){claim(campaign(),user,"admin-test-claim-"+user);}
 void accountMoney(String expected,String field){money(expected,new BigDecimal(admin.account(user).get(field).toString()));}

 @Test void httpEndpointsBindUserAndPreserveExactDecimalStrings() throws Exception {
  com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new AdminTrialAccountController(admin)).setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
  String url="/api/admin/users/"+user+"/trial-account";
  org.springframework.test.web.servlet.MvcResult view=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)).andReturn();assertEquals(200,view.getResponse().getStatus());
  long version=json.readTree(view.getResponse().getContentAsString()).get("rowVersion").asLong();
  String body="{\"enabled\":false,\"available\":\"5.0000000000000001\",\"expectedVersion\":"+version+",\"requestId\":\"trial-"+UUID.randomUUID()+"\",\"confirm\":true}";
  org.springframework.test.web.servlet.MvcResult saved=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url).contentType("application/json").content(body)).andReturn();assertEquals(200,saved.getResponse().getStatus());
  com.fasterxml.jackson.databind.JsonNode result=json.readTree(saved.getResponse().getContentAsString());assertEquals("5.0000000000000001",result.get("available").asText());assertFalse(result.get("enabled").asBoolean());
 }

 @Test void increaseCreatesOneDayGrantWithoutChangingCash(){
  UpdateTrialAccountRequest r=input("300.1234567890123456",true);
  Map<String,Object> result=admin.update(user,r);
  accountMoney("300.1234567890123456","available");accountMoney("300.1234567890123456","granted");accountMoney("0","frozen");
  for(String coin:Arrays.asList("FUND","CONTRACT","OPTION"))money("100",cash(user,coin));
  TrialGrant grant=grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).get(0);
  assertEquals(funds.now().plusDays(1),grant.getExpiresAt());assertTrue((Boolean)result.get("enabled"));
  assertEquals(1,receipts.findByTenantIdAndRequestKey(1L,r.getRequestId()).map(v->1).orElse(0));
  verify(audit).record(eq(1L),eq(1L),isNull(),eq("TRIAL_ACCOUNT_UPDATE"),eq("user:"+user),eq("SUCCESS"),contains("300.1234567890123456"),eq("admin trial test"));
 }
 @Test void lowerBalancePreservesFrozenPrincipalAndOpenPositionCanCloseWhenDisabled(){
  gift();approved(user);ContractOrder order=contracts.createOrder(user,contract("MARKET","TRIAL"));
  accountMoney("289","available");accountMoney("11","frozen");
  admin.update(user,input("100",false));accountMoney("100","available");accountMoney("11","frozen");accountMoney("189","expired");
  assertFalse((Boolean)funds.status(user).get("trialEligible"));assertFalse(((List<?>)funds.status(user).get("fundingSources")).contains("TRIAL"));
  assertThrows(BusinessException.class,()->contracts.createOrder(user,contract("MARKET","TRIAL")));
  assertThrows(BusinessException.class,()->options.createOrder(user,option("1","TRIAL")));
  when(quotes.freshPrice(symbol)).thenReturn(d("110"));contracts.closeOrder(user,order.getId(),null);
  accountMoney("111","available");accountMoney("0","frozen");money("109",cash(user,"CONTRACT"));
  assertFalse((Boolean)admin.account(user).get("enabled"));assertEquals("CLOSED",contractOrders.findByTenantIdAndId(1L,order.getId()).get().getStatus());
 }
 @Test void disabledAccountRetainsBalanceAndDoesNotBecomeEnabledByAnotherClaim(){
  gift();admin.update(user,input("300",false));
  claim(campaign(),user,"admin-second-claim-"+user);
  accountMoney("600","available");assertFalse((Boolean)admin.account(user).get("enabled"));assertFalse((Boolean)funds.status(user).get("trialEligible"));
  admin.update(user,input("600",true));assertTrue((Boolean)funds.status(user).get("trialEligible"));
 }
 @Test void disablingPreventsPendingTrialOrderFromFillingWithoutCancellingIt(){
  gift();approved(user);ContractOrder pending=contracts.createOrder(user,contract("LIMIT","TRIAL"));
  admin.update(user,input("289",false));
  contracts.matchPendingLimitOrders();
  assertEquals("PENDING",contractOrders.findByTenantIdAndId(1L,pending.getId()).get().getStatus());accountMoney("11","frozen");
  assertThrows(BusinessException.class,()->tx.execute(status->{funds.lock(user);return funds.resize(user,assets.findByTenantIdAndUserIdAndCoin(1L,user,"CONTRACT").get(),d("11"),d("11"),d("11"),pending.getTrialAllocations(),"TRIAL","CONTRACT","test");}));
 }
 @Test void disabledGrantsStillExpireAndEnablingNeverRevivesThem(){
  admin.update(user,input("100",false));clock("2030-01-02T00:00:00Z");
  accountMoney("0","available");accountMoney("100","expired");
  admin.update(user,input("0",true));assertFalse((Boolean)funds.status(user).get("trialEligible"));
 }
 @Test void receiptReplayDoesNotOverwriteLaterBalanceOrSwitchChanges(){
  UpdateTrialAccountRequest original=input("100",true);admin.update(user,original);
  admin.update(user,input("50",false));long version=((Number)admin.account(user).get("rowVersion")).longValue();
  assertTrue((Boolean)admin.update(user,original).get("replayed"));accountMoney("50","available");assertFalse((Boolean)admin.account(user).get("enabled"));
  assertEquals(version,((Number)admin.account(user).get("rowVersion")).longValue());
  original.setAvailable(d("101"));assertThrows(IllegalArgumentException.class,()->admin.update(user,original));accountMoney("50","available");
 }
 @Test void staleVersionRejectsAnotherAdministratorWithoutChangingAnything(){
  UpdateTrialAccountRequest first=input("100",true),stale=input("200",false);
  admin.update(user,first);assertThrows(ResponseStatusException.class,()->admin.update(user,stale));accountMoney("100","available");
  assertFalse(receipts.findByTenantIdAndRequestKey(1L,stale.getRequestId()).isPresent());
 }
 @Test void invalidAmountsConfirmationAndKeysAreRejected(){
  for(String value:Arrays.asList("-1","0.00000000000000001","10000000000000000")){UpdateTrialAccountRequest r=input("0",true);r.setAvailable(d(value));assertThrows(IllegalArgumentException.class,()->admin.update(user,r));}
  UpdateTrialAccountRequest r=input("1",true);r.setConfirm(false);assertThrows(IllegalArgumentException.class,()->admin.update(user,r));
  r.setConfirm(true);r.setRequestId("invalid");assertThrows(IllegalArgumentException.class,()->admin.update(user,r));accountMoney("0","available");
 }
 @Test void ordinaryAdministratorAndOtherTenantCannotReadOrAdjust(){
  gift();UpdateTrialAccountRequest r=input("100",true);authenticate("ROLE_ADMIN");
  assertThrows(AccessDeniedException.class,()->admin.account(user));assertThrows(AccessDeniedException.class,()->admin.update(user,r));
  authenticate("ROLE_SUPER_ADMIN");scope.close();scope=null;
  try(TenantContext.Scope ignored=TenantContext.open(2L)){assertThrows(BusinessException.class,()->admin.account(user));assertThrows(BusinessException.class,()->admin.update(user,r));}
  finally{scope=TenantContext.open(1L);}accountMoney("300","available");
 }
 @Test void auditFailureRollsBackAccountGrantLedgerAndReceiptTogether(){
  long grantsBefore=grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).size();UpdateTrialAccountRequest r=input("100",false);
  doThrow(new IllegalStateException("audit unavailable")).when(audit).record(any(),any(),any(),eq("TRIAL_ACCOUNT_UPDATE"),any(),any(),any(),any());
  assertThrows(IllegalStateException.class,()->admin.update(user,r));accountMoney("0","available");assertTrue((Boolean)admin.account(user).get("enabled"));
  assertEquals(grantsBefore,grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).size());assertFalse(receipts.findByTenantIdAndRequestKey(1L,r.getRequestId()).isPresent());
 }
 @Test void concurrentIdenticalRequestsApplyOnlyOnce() throws Exception {
  UpdateTrialAccountRequest r=input("100",true);ExecutorService pool=Executors.newFixedThreadPool(2);
  Callable<Map<String,Object>> operation=()->{try(TenantContext.Scope ignored=TenantContext.open(1L)){authenticate("ROLE_SUPER_ADMIN");return admin.update(user,r);}finally{SecurityContextHolder.clearContext();}};
  try{Future<?> a=pool.submit(operation),b=pool.submit(operation);a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
  accountMoney("100","available");assertEquals(1,grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).size());
  verify(audit,times(1)).record(any(),any(),any(),eq("TRIAL_ACCOUNT_UPDATE"),any(),any(),any(),any());
 }
}
