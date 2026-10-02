package com.gtcfesk.exchange.activity;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.user.FinancialService;
import com.gtcfesk.exchange.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig({ActivityIntegrationTest.Config.class,ControlledExitIntegrationTest.Config.class})
@org.junit.jupiter.api.extension.ExtendWith(TenantOneFixture.class)
class ControlledExitIntegrationTest {
 @Configuration @Import({ControlledExitService.class,FinancialService.class,com.gtcfesk.exchange.user.LoanService.class,com.gtcfesk.exchange.user.LoanPersonalInfoService.class}) static class Config {
  @Bean AdminPermissionService permissions(){return mock(AdminPermissionService.class);}
 }
 @Autowired ControlledExitService exits;@Autowired ContractOrderRepository orders;@Autowired AssetAccountRepository assets;
 @Autowired UserAccountRepository users;@Autowired AdminUserRepository admins;@Autowired OperationLogRepository logs;
 @Autowired BalanceAdjustmentRepository receipts;@Autowired KycRecordRepository kycs;@Autowired ControlAuditService audit;
 @Autowired AdminPermissionService permissions;@Autowired FinancialOrderRepository financialOrders;
 @Autowired TenantPolicyService policy;@Autowired LoanRecordRepository loans;
 Long user,admin,id;
 @BeforeEach void fixture(){reset(audit,permissions,policy);UserAccount u=new UserAccount();u.setEmail(UUID.randomUUID()+"@exit.test");u.setPasswordHash("test-only");user=users.saveAndFlush(u).getId();AdminUser a=new AdminUser();a.setAccount(UUID.randomUUID().toString());a.setEmail(a.getAccount()+"@exit.test");a.setRole("super_admin");a.setPasswordHash("test-only");admin=admins.saveAndFlush(a).getId();
  AssetAccount account=new AssetAccount();account.setUserId(user);account.setCoin("CONTRACT");account.setAvailable(new BigDecimal("89"));account.setFrozen(new BigDecimal("11"));assets.saveAndFlush(account);
  ContractOrder o=new ContractOrder();o.setUserId(user);o.setSymbol("exit-test");o.setSide("BUY");o.setType("LIMIT");o.setQuantity(BigDecimal.ONE);o.setStatus("PENDING");o.setMargin(BigDecimal.TEN);o.setFee(BigDecimal.ONE);id=orders.saveAndFlush(o).getId();
  KycRecord pending=new KycRecord();pending.setUserId(user);pending.setStatus("PENDING");pending.setRealName("Fixture pending");pending.setIdNumber("Fixture pending id");kycs.saveAndFlush(pending);asAdmin();
 }
 @AfterEach void clear(){SecurityContextHolder.clearContext();reset(audit,permissions,policy);}
 void asAdmin(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin.toString(),null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));}
 void asControl(Long tenant){UsernamePasswordAuthenticationToken a=new UsernamePasswordAuthenticationToken("-7",null,Arrays.asList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),new SimpleGrantedAuthority("ROLE_CONTROL_ACCESS")));a.setDetails(new ControlIdentity(7L,tenant,"fixture-access"));SecurityContextHolder.getContext().setAuthentication(a);}
 ControlledExitService.Input input(){ControlledExitService.Input b=new ControlledExitService.Input();b.requestId=UUID.randomUUID().toString();b.reason="Customer verified controlled exit request";return b;}
 void money(String a){assertEquals(0,new BigDecimal(a).compareTo(assets.findByTenantIdAndUserIdAndCoin(1L,user,"CONTRACT").orElseThrow().getAvailable()));}
 @Test void ordinaryAdminActualAuditAndIdempotency(){ControlledExitService.Input b=input();Map<String,Object> first=exits.execute("contract",id,"cancel",b);assertEquals("ADMIN",first.get("actorType"));assertEquals(admin,first.get("actorId"));money("100");assertEquals(0,assets.findByTenantIdAndUserIdAndCoin(1L,user,"CONTRACT").orElseThrow().getFrozen().signum());assertEquals("PENDING",kycs.findFirstByTenantIdAndUserIdOrderByCreatedAtDesc(1L,user).orElseThrow().getStatus());assertEquals(1,logs.findByTenantIdAndAdminIdOrderByCreatedAtDesc(1L,admin,org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements());assertEquals("cancel",exits.execute("contract",id,"cancel",b).get("command"));b.reason="Changed command requires different key";assertEquals(409,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->exits.execute("contract",id,"cancel",b)).getRawStatusCode());money("100");}
 @Test void auditFailureRollsBackThenControlRetryKeepsRealActor(){asControl(1L);ControlledExitService.Input b=input();long adminsBefore=admins.findAllByTenantId(1L).size();doThrow(new IllegalStateException("fixture audit unavailable")).when(audit).record(eq(7L),eq(1L),eq("fixture-access"),eq("CONTROLLED_EXIT"),anyString(),eq("SUCCESS"),anyString(),anyString());assertThrows(IllegalStateException.class,()->exits.execute("contract",id,"cancel",b));money("89");assertEquals("PENDING",orders.findByTenantIdAndId(1L,id).orElseThrow().getStatus());assertFalse(receipts.findByTenantIdAndRequestKey(1L,b.requestId).isPresent());reset(audit);Map<String,Object> out=exits.execute("contract",id,"cancel",b);assertEquals("CONTROL",out.get("actorType"));assertEquals(7L,out.get("actorId"));money("100");assertEquals(adminsBefore,admins.findAllByTenantId(1L).size());verify(audit).record(eq(7L),eq(1L),eq("fixture-access"),eq("CONTROLLED_EXIT"),anyString(),eq("SUCCESS"),anyString(),eq(b.reason));}
 @Test void scopeRoleReasonAndPermissionDenialsHaveNoSideEffects(){ControlledExitService.Input b=input();asControl(2L);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->exits.execute("contract",id,"cancel",b));asAdmin();b.reason="x";assertThrows(IllegalArgumentException.class,()->exits.execute("contract",id,"cancel",b));b.reason="Verified service exit request";final ControlledExitService.Input request=b;doThrow(new org.springframework.security.access.AccessDeniedException("fixture revoked permission")).when(permissions).require("orders","cancel_order");assertThrows(org.springframework.security.access.AccessDeniedException.class,()->exits.execute("contract",id,"cancel",request));money("89");assertFalse(receipts.findByTenantIdAndRequestKey(1L,b.requestId).isPresent());assertEquals("PENDING",orders.findByTenantIdAndId(1L,id).orElseThrow().getStatus());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent-1",null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_AGENT"))));assertThrows(org.springframework.security.access.AccessDeniedException.class,()->exits.execute("contract",id,"cancel",request));}
 @Test void financialRedeemUsesSavedPenaltyAndRealReceiptExactlyOnce(){AssetAccount a=new AssetAccount();a.setUserId(user);a.setCoin("FUND");a.setAvailable(BigDecimal.ZERO);a.setFrozen(new BigDecimal("100"));assets.saveAndFlush(a);FinancialOrder o=new FinancialOrder();o.setUserId(user);o.setProductId(999L);o.setProductName("test disabled historical product");o.setPurchaseAmount(new BigDecimal("100"));o.setPenaltyRate(new BigDecimal("3"));o.setDailyYieldRate(BigDecimal.ONE);o.setDailyYield(BigDecimal.ONE);o.setTotalYield(BigDecimal.TEN);o.setTermDays(10);o=financialOrders.saveAndFlush(o);ControlledExitService.Input b=input();Map<String,Object> first=exits.execute("financial",o.getId(),"redeem",b);assertEquals("REDEEMED",((Map<?,?>)first.get("after")).get("status"));assertEquals("redeem",exits.execute("financial",o.getId(),"redeem",b).get("command"));assertEquals(0,new BigDecimal("97").compareTo(assets.findByTenantIdAndUserIdAndCoin(1L,user,"FUND").orElseThrow().getAvailable()));assertEquals(0,assets.findByTenantIdAndUserIdAndCoin(1L,user,"FUND").orElseThrow().getFrozen().signum());}

 @Test void controlledLoanRepaymentAuditsAndReplaysWithoutASecondDebit(){
  AssetAccount a=new AssetAccount();a.setUserId(user);a.setCoin("FUND");a.setAvailable(new BigDecimal("100"));a.setFrozen(BigDecimal.ZERO);assets.saveAndFlush(a);
  LoanRecord l=new LoanRecord();l.setUserId(user);l.setAmount(new BigDecimal("100"));l.setDays(7);l.setFreeDays(7);l.setDailyRate(BigDecimal.ZERO);l.setOverdueRate(BigDecimal.ZERO);l.setTotalInterest(BigDecimal.ZERO);l.setRepaymentAmount(new BigDecimal("100"));l.setStatus("APPROVED");l.setApprovedAt(java.time.LocalDateTime.now());final Long loanId=loans.saveAndFlush(l).getId();
  asControl(1L);ControlledExitService.Input b=input();
  doThrow(new IllegalStateException("controlled loan audit unavailable")).when(audit).record(eq(7L),eq(1L),eq("fixture-access"),eq("CONTROLLED_EXIT"),eq("loan:"+loanId),eq("SUCCESS"),anyString(),anyString());
  assertThrows(IllegalStateException.class,()->exits.execute("loan",loanId,"repay",b));
  assertEquals("APPROVED",loans.findByTenantIdAndId(1L,loanId).orElseThrow().getStatus());assertFalse(receipts.findByTenantIdAndRequestKey(1L,b.requestId).isPresent());
  assertEquals(0,new BigDecimal("100").compareTo(assets.findByTenantIdAndUserIdAndCoin(1L,user,"FUND").orElseThrow().getAvailable()));
  reset(audit);doThrow(new org.springframework.security.access.AccessDeniedException("new loans disabled")).when(policy).requireNewBusiness(anyString());
  Map<String,Object> result=exits.execute("loan",loanId,"repay",b);assertEquals("CONTROL",result.get("actorType"));assertEquals("COMPLETED",((Map<?,?>)result.get("after")).get("status"));
  assertEquals("repay",exits.execute("loan",loanId,"repay",b).get("command"));assertEquals(0,assets.findByTenantIdAndUserIdAndCoin(1L,user,"FUND").orElseThrow().getAvailable().signum());
  verify(policy,never()).requireNewBusiness(anyString());verify(permissions,atLeastOnce()).require("loan_review","controlled_exit");
  verify(audit,times(1)).record(eq(7L),eq(1L),eq("fixture-access"),eq("CONTROLLED_EXIT"),eq("loan:"+loanId),eq("SUCCESS"),anyString(),eq(b.reason));
 }

 @Test void disabledNewBusinessGuardDoesNotBlockHistoricalQueryOrExistingCancellation(){
  doThrow(new org.springframework.security.access.AccessDeniedException("feature.contract disabled")).when(policy).requireNewBusiness(anyString());
  assertThrows(org.springframework.security.access.AccessDeniedException.class,()->policy.requireNewBusiness("contract"));clearInvocations(policy);
  assertEquals("PENDING",orders.findByTenantIdAndId(1L,id).orElseThrow().getStatus());
  Map<String,Object> result=exits.execute("contract",id,"cancel",input());assertEquals("cancel",result.get("command"));assertEquals("CANCELLED",orders.findByTenantIdAndId(1L,id).orElseThrow().getStatus());
  verify(policy,never()).requireNewBusiness(anyString()); // Entry-gate boundary only; complete cash arithmetic/concurrency belongs to stage2.
 }
}
