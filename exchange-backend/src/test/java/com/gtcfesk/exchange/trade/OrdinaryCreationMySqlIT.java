package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.ExchangeBackendApplication;
import com.gtcfesk.exchange.admin.AdminUserRepository;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.trade.dto.*;
import com.gtcfesk.exchange.user.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** New owned MySQL clone, actual guards/services/transactions. Only initial data and quote inputs are synthetic.
 * The synthetic ready tenant is not domain/browser acceptance; that remains a separate real provisioning gate. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,classes={ExchangeBackendApplication.class,OptionSettlementMySqlIT.Inputs.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OrdinaryCreationMySqlIT {
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r)throws Exception {OptionSettlementMySqlIT.properties(r);r.add("financial.yield.initial-delay-ms",()->"3600000");}
 @Autowired TenantRepository tenants; @Autowired TenantPolicyRepository policies; @Autowired BackendLoginRepository logins;
 @Autowired AdminUserRepository admins; @Autowired SystemConfigRepository configs; @Autowired UserAccountRepository users;
 @Autowired AssetAccountRepository assets; @Autowired KycRecordRepository kycs; @Autowired LoanPersonalInfoRepository personal;
 @Autowired TradingSymbolRepository symbols; @Autowired OptionDurationRepository durations; @Autowired FinancialProductRepository products; @Autowired LoanSettingRepository settings;
 @Autowired ContractOrderService contracts; @Autowired OptionOrderService options; @Autowired FinancialService finance; @Autowired LoanService loans;
 @Autowired DepositOrderService deposits; @Autowired DepositSettingRepository depositChannels;
 @Autowired WithdrawController withdraw; @Autowired TransferController transfer; @Autowired JdbcTemplate db; @Autowired PlatformTransactionManager manager;
 @Autowired com.gtcfesk.exchange.market.MarketOrderProcessor market; @Autowired UserDigitalAddressRepository addresses;
 @Autowired com.gtcfesk.exchange.admin.ControlledExitService exits;
 @Autowired com.gtcfesk.exchange.admin.LoanReviewService loanReview;
 @Autowired com.gtcfesk.exchange.admin.WithdrawReviewController withdrawReview;
 Fixture f; TenantContext.Scope scope;
 static BigDecimal d(String s){return new BigDecimal(s);}
 static class Fixture {long tenant,user,product,setting;String symbol;}
 @BeforeEach void setup(){market.stop();f=fixture();scope=TenantContext.open(f.tenant);assertTrue(db.queryForObject("SELECT VERSION()",String.class).startsWith("5.7."));assertFalse(org.mockito.Mockito.mockingDetails(contracts).isMock());}
 @AfterEach void clear(){if(scope!=null)scope.close();TenantContext.clear();org.springframework.security.core.context.SecurityContextHolder.clearContext();}
 Fixture fixture(){
  String suffix=UUID.randomUUID().toString().substring(0,8);Fixture result=new Fixture();
  Tenant tenant=new Tenant();tenant.setCode("receipt-"+suffix);tenant.setName("Synthetic owned creation fixture");tenant.setStatus("ACTIVE");tenant.setConfigReady(true);tenant.setDomainVerified(true);tenant.setFrontendHost("receipt"+suffix+".localhost");result.tenant=tenants.saveAndFlush(tenant).getId();
  try(TenantContext.Scope ignored=TenantContext.open(result.tenant)){
   com.gtcfesk.exchange.admin.AdminUser owner=new com.gtcfesk.exchange.admin.AdminUser();owner.setAccount("receipt-owner-"+suffix);owner.setEmail(suffix+"@example.invalid");owner.setPasswordHash("NOT-A-LOGIN");owner.setRole("super_admin");owner=admins.saveAndFlush(owner);
   BackendLogin login=new BackendLogin();login.setTenantId(result.tenant);login.setAdminUserId(owner.getId());login.setNormalizedAccount(owner.getAccount());login.setSubjectType("ADMIN");logins.saveAndFlush(login);
   for(String[] entry:new String[][]{{"site.name","Owned receipt test"},{"system.timezone","UTC"}}){SystemConfig c=new SystemConfig();c.setConfigKey(entry[0]);c.setConfigValue(entry[1]);configs.saveAndFlush(c);}
   for(String feature:Arrays.asList("contract","option","financial","loan","withdraw")){TenantPolicy p=new TenantPolicy();p.setTenantId(result.tenant);p.setKey("feature."+feature);p.setValue("true");policies.saveAndFlush(p);}
   UserAccount user=new UserAccount();user.setEmail("same-ordinary-email@example.invalid");user.setPasswordHash("NOT-A-LOGIN");result.user=users.saveAndFlush(user).getId();
   for(String coin:Arrays.asList("FUND","CONTRACT","OPTION")){AssetAccount a=new AssetAccount();a.setUserId(result.user);a.setCoin(coin);a.setAvailable(d("1000"));a.setFrozen(BigDecimal.ZERO);assets.saveAndFlush(a);}
   KycRecord k=new KycRecord();k.setUserId(result.user);k.setRealName("Synthetic receipt identity");k.setIdNumber("NOT-REAL-"+suffix);k.setStatus("APPROVED");kycs.saveAndFlush(k);
   LoanPersonalInfo info=new LoanPersonalInfo();info.setUserId(result.user);info.setRealName(k.getRealName());info.setIdNumber(k.getIdNumber());info.setPhone("+12025550100");info.setAddress("Synthetic test address");info.setHandheldImage("fixture-only-not-a-real-attachment");info.setStatus("APPROVED");personal.saveAndFlush(info);
   UserDigitalAddress addr=new UserDigitalAddress();addr.setUserId(result.user);addr.setCurrency("USDT");addr.setNetwork("USDT-TRC20");addr.setAddress("SIMULATION-NO-PAYMENT");addresses.saveAndFlush(addr);
   TradingSymbol symbol=new TradingSymbol();result.symbol="RECEIPTUSD";symbol.setSymbol(result.symbol);symbol.setName("Owned quote input");symbol.setBaseCurrency("BTC");symbol.setQuoteCurrency("USD");symbol.setCategory("Crypto");symbol.setSourceCategory("Crypto");symbol.setMarketSource("yahoo");symbol.setIsEnabled(true);symbol.setLotSize(BigDecimal.ONE);symbol.setFeeMultiplier(BigDecimal.ONE);symbol.setMaxLeverage(BigDecimal.TEN);symbols.saveAndFlush(symbol);
   OptionDuration duration=new OptionDuration();duration.setDuration(99995);duration.setLabel("Test long duration");duration.setSortOrder(0);duration.setEnabled(true);duration.setProfitRate(d("0.8"));duration.setLossRate(BigDecimal.ONE);duration.setMinAmount(BigDecimal.ONE);duration.setMaxAmount(d("1000"));durations.saveAndFlush(duration);
   FinancialProduct product=new FinancialProduct();product.setName("Owned finance input");product.setCurrency("USD");product.setDailyYieldRate(BigDecimal.ONE);product.setRentalFee(BigDecimal.ZERO);product.setMinPurchase(BigDecimal.ONE);product.setMaxPurchase(d("1000"));product.setTermDays(7);product.setPenaltyRate(BigDecimal.ZERO);result.product=products.saveAndFlush(product).getId();
   LoanSetting setting=new LoanSetting();setting.setDays(7);setting.setFreeDays(7);setting.setDailyRate(BigDecimal.ZERO);setting.setOverdueRate(BigDecimal.ZERO);setting.setMinAmount(BigDecimal.ONE);setting.setMaxAmount(d("1000"));result.setting=settings.saveAndFlush(setting).getId();
  }return result;
 }
 CreateContractOrderRequest contract(String key){CreateContractOrderRequest r=new CreateContractOrderRequest();r.setRequestId(key);r.setSymbol(f.symbol);r.setSide("BUY");r.setType("MARKET");r.setQuantity(BigDecimal.ONE);r.setLeverage(BigDecimal.TEN);return r;}
 CreateOptionOrderRequest option(String key){CreateOptionOrderRequest r=new CreateOptionOrderRequest();r.setRequestId(key);r.setSymbol(f.symbol);r.setDirection("UP");r.setAmount(d("10"));r.setDuration(99995);return r;}
 void money(Fixture fixture,String coin,String available,String frozen){AssetAccount a=assets.findByTenantIdAndUserIdAndCoin(fixture.tenant,fixture.user,coin).get();assertEquals(0,d(available).compareTo(a.getAvailable()));assertEquals(0,d(frozen).compareTo(a.getFrozen()));}
 long count(String table){return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=? AND user_id=?",Long.class,f.tenant,f.user);}
 @Test void ordinaryContractAndOptionReplayRejectDifferentContentWithoutSecondReserve(){
  CreateContractOrderRequest c=contract("ordinary-contract-key");ContractOrder order=contracts.createOrder(f.user,c);money(f,"CONTRACT","988","12");c.setCurrentPrice(d("999999"));assertEquals(order.getId(),contracts.createOrder(f.user,c).getId());assertEquals(1,count("contract_order"));money(f,"CONTRACT","988","12");
  c.setQuantity(d("2"));assertThrows(BusinessException.class,()->contracts.createOrder(f.user,c));money(f,"CONTRACT","988","12");
  CreateOptionOrderRequest r=option("ordinary-option-key");OptionOrder o=options.createOrder(f.user,r);assertEquals(o.getId(),options.createOrder(f.user,r).getId());r.setAmount(d("11"));assertThrows(BusinessException.class,()->options.createOrder(f.user,r));assertEquals(1,count("option_order"));money(f,"OPTION","990","10");
  assertNull(db.queryForObject("SELECT request_key FROM contract_order WHERE id=1",String.class)); // inherited legacy row remains unknown
 }
 @Test void financeLoanWithdrawTransferReceiptsAreDurableAndBusinessSpecific(){
  String key="ordinary-shared-business-key";FinancialOrder a=finance.purchaseProduct(f.user,f.product,d("100"),key);assertEquals(a.getId(),finance.purchaseProduct(f.user,f.product,d("100.00"),key).getId());assertThrows(BusinessException.class,()->finance.purchaseProduct(f.user,f.product,d("101"),key));money(f,"FUND","900","100");
  LoanRecord l=loans.createLoan(f.user,d("100"),f.setting,key);assertEquals(l.getId(),loans.createLoan(f.user,d("100.0"),f.setting,key).getId());assertThrows(BusinessException.class,()->loans.createLoan(f.user,d("101"),f.setting,key));assertEquals(1,count("loan_record"));money(f,"FUND","900","100");
  Map<String,Object> w=new HashMap<>();w.put("type","digital");w.put("network","USDT-TRC20");w.put("address","SIMULATION-NO-PAYMENT");w.put("amount","25");w.put("requestId",key);UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken(Long.toString(f.user),"not-a-token");
  assertEquals(200,withdraw.submitWithdraw(auth,w).getStatusCodeValue());assertEquals(200,withdraw.submitWithdraw(auth,w).getStatusCodeValue());w.put("amount","26");assertEquals(400,withdraw.submitWithdraw(auth,w).getStatusCodeValue());assertEquals(1,count("withdraw_record"));money(f,"FUND","875","125");
  TransferController.TransferRequest transferRequest=new TransferController.TransferRequest();transferRequest.setRequestId(key);transferRequest.setFromAccount("FUND");transferRequest.setToAccount("CONTRACT");transferRequest.setAmount(d("10"));assertEquals(200,transfer.transfer(auth,transferRequest).getStatusCodeValue());assertEquals(200,transfer.transfer(auth,transferRequest).getStatusCodeValue());transferRequest.setAmount(d("11"));assertThrows(BusinessException.class,()->transfer.transfer(auth,transferRequest));assertEquals(1,count("transfer_record"));money(f,"FUND","865","125");money(f,"CONTRACT","1010","0");
 }
 @Test void repeatableReadConcurrencyAndIdenticalTenantKeysCannotDoubleFreeze()throws Exception{
  scope.close();scope=null;Fixture other=fixture();scope=TenantContext.open(f.tenant);String key="ordinary-concurrent-key";CyclicBarrier snapshots=new CyclicBarrier(2);ExecutorService pool=Executors.newFixedThreadPool(2);
  Callable<Long> buy=()->{try(TenantContext.Scope ignored=TenantContext.open(f.tenant)){return new TransactionTemplate(manager).execute(status->{assertEquals(0,count("financial_order"));try{snapshots.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}return finance.purchaseProduct(f.user,f.product,d("100"),key).getId();});}finally{TenantContext.clear();}};
  try{Future<Long>a=pool.submit(buy),b=pool.submit(buy);assertEquals(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
  assertEquals(1,count("financial_order"));money(f,"FUND","900","100");
  scope.close();scope=null;try(TenantContext.Scope ignored=TenantContext.open(other.tenant)){FinancialOrder b=finance.purchaseProduct(other.user,other.product,d("100"),key);assertEquals(other.user,b.getUserId());money(other,"FUND","900","100");assertThrows(BusinessException.class,()->finance.purchaseProduct(f.user,other.product,d("100"),key));}finally{scope=TenantContext.open(f.tenant);}
  money(f,"FUND","900","100");
 }
 @Test void actualSqlFailureAfterFreezeRollsBackReceiptAndRetryCreatesOneOrder(){
  String trigger="receipt_fail_"+f.tenant;db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON financial_order FOR EACH ROW BEGIN IF NEW.tenant_id="+f.tenant+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned controlled creation failure'; END IF; END");
  try{assertThrows(RuntimeException.class,()->finance.purchaseProduct(f.user,f.product,d("100"),"ordinary-rollback-key"));assertEquals(0,count("financial_order"));money(f,"FUND","1000","0");}finally{db.execute("DROP TRIGGER "+trigger);}
  FinancialOrder o=finance.purchaseProduct(f.user,f.product,d("100"),"ordinary-rollback-key");assertEquals(o.getId(),finance.purchaseProduct(f.user,f.product,d("100"),"ordinary-rollback-key").getId());assertEquals(1,count("financial_order"));money(f,"FUND","900","100");
 }
 long controlActor(){String name="ordinary-exit-"+UUID.randomUUID();db.update("INSERT INTO control_admin(account,password_hash,enabled,mfa_enabled,session_version,row_version) VALUES(?,'NOT-A-LOGIN',1,0,0,0)",name);return db.queryForObject("SELECT id FROM control_admin WHERE account=?",Long.class,name);}
 void asControl(long actor){UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("-"+actor,null,Arrays.asList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CONTROL_ACCESS")));auth.setDetails(new ControlIdentity(actor,f.tenant,"owned-exit-"+actor));org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);}
 com.gtcfesk.exchange.admin.ControlledExitService.Input exitInput(){com.gtcfesk.exchange.admin.ControlledExitService.Input input=new com.gtcfesk.exchange.admin.ControlledExitService.Input();input.requestId=UUID.randomUUID().toString();input.reason="Owned local verified repayment; synthetic funds only";return input;}
 LoanRecord fundedLoan(String key){LoanRecord l=loans.createLoan(f.user,d("100"),f.setting,key);loans.signContract(l.getId(),f.user,"OWNED-SYNTHETIC-SIGNATURE");loanReview.approveLoan(l.getId());return l;}
 @Test void actualControlAuditFailureRollsBackLoanDebitReceiptAndAllAuditRowsThenConcurrentRetryIsSingle()throws Exception{
  LoanRecord l=fundedLoan("owned-funded-audit-loan");money(f,"FUND","1100","0");long actor=controlActor();asControl(actor);com.gtcfesk.exchange.admin.ControlledExitService.Input input=exitInput();String trigger="owned_exit_audit_"+actor;
  db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON control_audit_log FOR EACH ROW BEGIN IF NEW.actor_id="+actor+" AND NEW.action='CONTROLLED_EXIT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned controlled exit audit failure'; END IF; END");
  try{assertThrows(RuntimeException.class,()->exits.execute("loan",l.getId(),"repay",input));money(f,"FUND","1100","0");assertEquals("APPROVED",db.queryForObject("SELECT status FROM loan_record WHERE tenant_id=? AND id=?",String.class,f.tenant,l.getId()));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM balance_adjustment WHERE tenant_id=? AND request_key=?",Long.class,f.tenant,input.requestId));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE actor_id=?",Long.class,actor));}finally{db.execute("DROP TRIGGER "+trigger);}
  ExecutorService pool=Executors.newFixedThreadPool(4);try{List<Future<Map<String,Object>>> calls=new ArrayList<>();for(int i=0;i<8;i++)calls.add(pool.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(f.tenant)){asControl(actor);return exits.execute("loan",l.getId(),"repay",input);}finally{TenantContext.clear();org.springframework.security.core.context.SecurityContextHolder.clearContext();}}));for(Future<Map<String,Object>> call:calls){Map<String,Object> result=call.get(30,TimeUnit.SECONDS);assertEquals("CONTROL",result.get("actorType"));assertEquals(actor,((Number)result.get("actorId")).longValue());}}finally{pool.shutdownNow();}
  money(f,"FUND","1000","0");assertEquals("COMPLETED",db.queryForObject("SELECT status FROM loan_record WHERE id=?",String.class,l.getId()));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM balance_adjustment WHERE tenant_id=? AND request_key=?",Long.class,f.tenant,input.requestId));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE actor_id=? AND tenant_id=? AND action='CONTROLLED_EXIT'",Long.class,actor,f.tenant));input.reason="Changed repayment reason must conflict";assertThrows(org.springframework.web.server.ResponseStatusException.class,()->exits.execute("loan",l.getId(),"repay",input));money(f,"FUND","1000","0");
 }
 @Test void allTenantRiskStatesAndFeatureOffDenyNewBusinessButKeepExistingLoanExit(){
  long actor=controlActor();int sequence=0;
  for(String state:Arrays.asList("FEATURE_OFF","STOP_NEW","MAINTENANCE","DISABLED")){
   org.springframework.security.core.context.SecurityContextHolder.clearContext();LoanRecord l=fundedLoan("owned-state-loan-"+state);
   ContractOrder opened=contracts.createOrder(f.user,contract("existing-market-"+state));
   CreateContractOrderRequest pendingRequest=contract("existing-limit-"+state);pendingRequest.setType("LIMIT");pendingRequest.setPrice(d("100"));ContractOrder pending=contracts.createOrder(f.user,pendingRequest);assertEquals("PENDING",pending.getStatus());
   OptionOrder option=options.createOrder(f.user,option("existing-option-"+state));
   FinancialOrder investment=finance.purchaseProduct(f.user,f.product,d("100"),"existing-finance-"+state);
   long withdrawal=submitWithdrawal("existing-withdraw-"+state,"25");money(f,"FUND","975","125");
   if("FEATURE_OFF".equals(state))db.update("UPDATE tenant_policy SET policy_value='false' WHERE tenant_id=? AND policy_key IN('feature.contract','feature.option','feature.loan','feature.financial','feature.withdraw')",f.tenant);
   else db.update("UPDATE tenant SET status=? WHERE id=?",state,f.tenant);
   final String key="owned-new-risk-state-"+(sequence++);
   assertThrows(org.springframework.security.access.AccessDeniedException.class,()->contracts.createOrder(f.user,contract(key)));
   assertThrows(org.springframework.security.access.AccessDeniedException.class,()->options.createOrder(f.user,option(key)));
   assertThrows(org.springframework.security.access.AccessDeniedException.class,()->finance.purchaseProduct(f.user,f.product,d("10"),key));
   assertThrows(org.springframework.security.access.AccessDeniedException.class,()->loans.createLoan(f.user,d("10"),f.setting,key));
   Map<String,Object> w=new HashMap<>();w.put("type","digital");w.put("network","USDT-TRC20");w.put("address","SIMULATION-NO-PAYMENT");w.put("amount","10");w.put("requestId",key);
   assertThrows(org.springframework.security.access.AccessDeniedException.class,()->withdraw.submitWithdraw(new UsernamePasswordAuthenticationToken(Long.toString(f.user),null),w));
   assertEquals("APPROVED",recordsLoanStatus(l.getId()));money(f,"FUND","975","125");asControl(actor);
   exits.execute("contract",opened.getId(),"close",exitInput());exits.execute("contract",pending.getId(),"cancel",exitInput());
   exits.execute("option",option.getId(),"close",exitInput());exits.execute("financial",investment.getId(),"redeem",exitInput());
   com.gtcfesk.exchange.admin.WithdrawReviewController.RejectRequest rejection=new com.gtcfesk.exchange.admin.WithdrawReviewController.RejectRequest();rejection.setRemark("Owned necessary exit; no external payment");assertEquals(200,withdrawReview.rejectWithdraw(withdrawal,rejection,null).getStatusCodeValue());
   assertEquals("CLOSED",db.queryForObject("SELECT status FROM contract_order WHERE id=?",String.class,opened.getId()));assertEquals("CANCELLED",db.queryForObject("SELECT status FROM contract_order WHERE id=?",String.class,pending.getId()));assertEquals("CLOSED",db.queryForObject("SELECT status FROM option_order WHERE id=?",String.class,option.getId()));assertEquals("REDEEMED",db.queryForObject("SELECT status FROM financial_order WHERE id=?",String.class,investment.getId()));
   money(f,"CONTRACT",d("1000").subtract(BigDecimal.valueOf(sequence)).toPlainString(),"0");money(f,"OPTION",d("1000").subtract(BigDecimal.valueOf(sequence*10)).toPlainString(),"0");money(f,"FUND","1100","0");com.gtcfesk.exchange.admin.ControlledExitService.Input input=exitInput();assertEquals("CONTROL",exits.execute("loan",l.getId(),"repay",input).get("actorType"));assertEquals("COMPLETED",recordsLoanStatus(l.getId()));money(f,"FUND","1000","0");
   db.update("UPDATE tenant SET status='ACTIVE' WHERE id=?",f.tenant);db.update("UPDATE tenant_policy SET policy_value='true' WHERE tenant_id=? AND policy_key IN('feature.contract','feature.option','feature.loan','feature.financial','feature.withdraw')",f.tenant);
  }
 }
 DepositOrderRequest pendingDeposit(String key){DepositOrderRequest r=new DepositOrderRequest();r.idempotencyKey=key;r.type="digital";r.network="OWNED-INPUT";r.address="SIMULATION-NO-PAYMENT";r.currency="USD";r.amount=d("5");return r;}
 @Test void depositFourRiskStatesDenyNewReceiptsButKeepPendingCancellationAndControlledRejection(){
  TenantPolicy policy=new TenantPolicy();policy.setTenantId(f.tenant);policy.setKey("feature.deposit");policy.setValue("true");policies.saveAndFlush(policy);
  DepositSetting channel=new DepositSetting();channel.setNetwork("OWNED-INPUT");channel.setAddress("SIMULATION-NO-PAYMENT");depositChannels.saveAndFlush(channel);
  long actor=controlActor();int sequence=0;
  for(String state:Arrays.asList("FEATURE_OFF","STOP_NEW","MAINTENANCE","DISABLED")){
   org.springframework.security.core.context.SecurityContextHolder.clearContext();
   DepositRecord cancelled=deposits.submit(f.user,pendingDeposit("owned-cancel-deposit-"+state));
   DepositRecord rejected=deposits.submit(f.user,pendingDeposit("owned-reject-deposit-"+state));
   assertEquals("PENDING",cancelled.getStatus());assertEquals("PENDING",rejected.getStatus());long before=count("deposit_record");
   if("FEATURE_OFF".equals(state))db.update("UPDATE tenant_policy SET policy_value='false' WHERE tenant_id=? AND policy_key='feature.deposit'",f.tenant);
   else db.update("UPDATE tenant SET status=? WHERE id=?",state,f.tenant);
   assertThrows(org.springframework.security.access.AccessDeniedException.class,()->deposits.submit(f.user,pendingDeposit("owned-new-deposit-"+state)));
   assertEquals(before,count("deposit_record"));money(f,"FUND","1000","0");
   assertEquals("CANCELLED",deposits.cancel(f.user,cancelled.getId()).getStatus());assertEquals("CANCELLED",deposits.cancel(f.user,cancelled.getId()).getStatus());
   asControl(actor);DepositRecord terminal=deposits.review(rejected.getId(),false,"Owned necessary pending rejection; no external payment");assertEquals("REJECTED",terminal.getStatus());assertEquals("CONTROL",terminal.getReviewedByType());assertEquals(actor,terminal.getReviewedById());
   assertThrows(org.springframework.web.server.ResponseStatusException.class,()->deposits.review(cancelled.getId(),true,"Cannot credit cancelled deposit"));
   assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM deposit_credit_record WHERE tenant_id=? AND user_id=?",Long.class,f.tenant,f.user));money(f,"FUND","1000","0");money(f,"CONTRACT","1000","0");money(f,"OPTION","1000","0");
   sequence++;assertEquals(sequence*2,count("deposit_record"));
   db.update("UPDATE tenant SET status='ACTIVE' WHERE id=?",f.tenant);db.update("UPDATE tenant_policy SET policy_value='true' WHERE tenant_id=? AND policy_key='feature.deposit'",f.tenant);
  }
 }
 String recordsLoanStatus(Long id){return db.queryForObject("SELECT status FROM loan_record WHERE tenant_id=? AND id=?",String.class,f.tenant,id);}

 Map<String,Object> withdrawalInput(String key,String amount){Map<String,Object>w=new HashMap<>();w.put("type","digital");w.put("network","USDT-TRC20");w.put("address","SIMULATION-NO-PAYMENT");w.put("amount",amount);w.put("requestId",key);return w;}
 long submitWithdrawal(String key,String amount){org.springframework.http.ResponseEntity<?> result=withdraw.submitWithdraw(new UsernamePasswordAuthenticationToken(Long.toString(f.user),null),withdrawalInput(key,amount));assertEquals(200,result.getStatusCodeValue());return ((Number)((Map<?,?>)result.getBody()).get("orderId")).longValue();}
 @Test void withdrawalManualApprovalCompletionAndRejectionHaveOneMoneyEffectAndAuditFailureRollsBack(){
  long id=submitWithdrawal("owned-complete-withdrawal","25");money(f,"FUND","975","25");long actor=controlActor();asControl(actor);
  com.gtcfesk.exchange.admin.WithdrawReviewController.ApproveRequest approval=new com.gtcfesk.exchange.admin.WithdrawReviewController.ApproveRequest();approval.setRemark("Owned synthetic manual completion; no external payment");assertEquals(200,withdrawReview.approveWithdraw(id,approval,null).getStatusCodeValue());money(f,"FUND","975","25");
  String trigger="withdraw_audit_"+actor;db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON control_audit_log FOR EACH ROW BEGIN IF NEW.actor_id="+actor+" AND NEW.action='WITHDRAW_COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned withdrawal audit failure'; END IF; END");
  try{assertEquals(400,withdrawReview.completeWithdraw(id,null).getStatusCodeValue());money(f,"FUND","975","25");assertEquals("APPROVED",db.queryForObject("SELECT status FROM withdraw_record WHERE id=?",String.class,id));}finally{db.execute("DROP TRIGGER "+trigger);}
  assertEquals(200,withdrawReview.completeWithdraw(id,null).getStatusCodeValue());assertEquals(400,withdrawReview.completeWithdraw(id,null).getStatusCodeValue());money(f,"FUND","975","0");assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE actor_id=? AND action='WITHDRAW_COMPLETED'",Long.class,actor));
  org.springframework.security.core.context.SecurityContextHolder.clearContext();long rejected=submitWithdrawal("owned-rejected-withdrawal","25");asControl(actor);com.gtcfesk.exchange.admin.WithdrawReviewController.RejectRequest rejection=new com.gtcfesk.exchange.admin.WithdrawReviewController.RejectRequest();rejection.setRemark("Owned synthetic rejection");assertEquals(200,withdrawReview.rejectWithdraw(rejected,rejection,null).getStatusCodeValue());assertEquals(400,withdrawReview.rejectWithdraw(rejected,rejection,null).getStatusCodeValue());money(f,"FUND","975","0");
  Map<String,Object> wrong=withdrawalInput("owned-unbound-address","25");wrong.put("address","UNBOUND-NOT-AN-ADDRESS");org.springframework.security.core.context.SecurityContextHolder.clearContext();assertEquals(400,withdraw.submitWithdraw(new UsernamePasswordAuthenticationToken(Long.toString(f.user),null),wrong).getStatusCodeValue());assertEquals(2,count("withdraw_record"));money(f,"FUND","975","0");
 }
 @Test void transferSqlReceiptFailureRollsBackBothSidesAndEightConcurrentReplaysMoveMoneyOnce()throws Exception{
  TransferController.TransferRequest input=new TransferController.TransferRequest();input.setFromAccount("FUND");input.setToAccount("CONTRACT");input.setAmount(d("20"));input.setRequestId("owned-concurrent-transfer");UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken(Long.toString(f.user),null);
  String trigger="transfer_fail_"+f.tenant;db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON transfer_record FOR EACH ROW BEGIN IF NEW.tenant_id="+f.tenant+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned transfer receipt failure'; END IF; END");try{assertThrows(RuntimeException.class,()->transfer.transfer(auth,input));money(f,"FUND","1000","0");money(f,"CONTRACT","1000","0");assertEquals(0,count("transfer_record"));}finally{db.execute("DROP TRIGGER "+trigger);}
  ExecutorService pool=Executors.newFixedThreadPool(4);try{List<Future<Integer>> calls=new ArrayList<>();for(int i=0;i<8;i++)calls.add(pool.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(f.tenant)){return transfer.transfer(auth,input).getStatusCodeValue();}finally{TenantContext.clear();}}));for(Future<Integer> call:calls)assertEquals(200,call.get(30,TimeUnit.SECONDS));}finally{pool.shutdownNow();}money(f,"FUND","980","0");money(f,"CONTRACT","1020","0");money(f,"OPTION","1000","0");assertEquals(1,count("transfer_record"));
 }

}
