package com.gtcfesk.exchange.user;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only persistence and the actual production service: no application startup, schedulers or external services. */
@SpringJUnitConfig(DepositOrderServiceTest.Config.class)
public class DepositOrderServiceTest {
 static { ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN); }
 @org.springframework.boot.test.context.TestConfiguration @EnableJpaRepositories(basePackages={"com.gtcfesk.exchange.repository","com.gtcfesk.exchange.admin"})
 static class Config {
  @Bean DataSource dataSource(){String url=System.getenv("DEPOSIT_TEST_JDBC");
   if(url==null)return new DriverManagerDataSource("jdbc:h2:mem:deposit_orders;MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
   if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/deposit_order_test_[a-f0-9]+[?].*"))throw new IllegalArgumentException("Unsafe integration database");
   return new DriverManagerDataSource(url,"root",System.getenv("DEPOSIT_TEST_PASSWORD"));}
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds){LocalContainerEntityManagerFactoryBean f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);f.setPackagesToScan("com.gtcfesk.exchange.entity","com.gtcfesk.exchange.admin");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
   Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","update");p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");p.setProperty("hibernate.dialect",System.getenv("DEPOSIT_TEST_JDBC")==null?"org.hibernate.dialect.H2Dialect":"org.hibernate.dialect.MySQL57Dialect");f.setJpaProperties(p);return f;}
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
 }
 @Autowired TransferRecordRepository transfers;
 @Autowired DepositRecordRepository records;@Autowired DepositCreditRecordRepository credits;
 @Autowired AssetAccountRepository assets;@Autowired UserAccountRepository users;@Autowired AdminUserRepository admins;
 @Autowired PlatformTransactionManager manager;@Autowired DataSource dataSource;
 @Autowired EntityManagerFactory entityManagerFactory;
 DepositOrderService service;FiatCurrencyService fiat;ForexQuoteMarketService market;BackendAccess access;UserAccount user;AdminUser admin;
 @BeforeEach void fixture(){
  user=new UserAccount();user.setEmail(UUID.randomUUID()+"@fixture.invalid");user.setPasswordHash("not-a-login");user=users.saveAndFlush(user);
  admin=new AdminUser();admin.setAccount(UUID.randomUUID().toString());admin.setEmail(admin.getAccount()+"@fixture.invalid");admin.setPasswordHash("not-a-login");admin.setRole("super_admin");admin=admins.saveAndFlush(admin);auth();
  market=mock(ForexQuoteMarketService.class);when(market.requireConversionRate("USD","yahoo")).thenReturn(BigDecimal.ONE);when(market.requireConversionRate("EUR","yahoo")).thenReturn(new BigDecimal("1.1"));fiat=new FiatCurrencyService(market);
  access=mock(BackendAccess.class);service=service(null);account("FUND","25");
 }
 void auth(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin.getId().toString(),"unused",Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));}
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 DepositOrderService service(String fault){return new DepositOrderService(records,credits,assets,users,admins,fiat,access,new ObjectMapper(),manager){@Override protected void checkpoint(String stage){if(stage.equals(fault))throw new IllegalStateException("injected "+stage);}};}
 void account(String coin,String balance){AssetAccount a=new AssetAccount();a.setUserId(user.getId());a.setCoin(coin);a.setAvailable(new BigDecimal(balance));a.setFrozen(new BigDecimal("3"));assets.saveAndFlush(a);}
 DepositOrderRequest request(){DepositOrderRequest r=new DepositOrderRequest();r.userId=user.getId();r.amount=new BigDecimal("100");r.remark="isolated adjustment";r.idempotencyKey=UUID.randomUUID().toString();return r;}
 BigDecimal balance(String coin){return assets.findByUserIdAndCoin(user.getId(),coin).get().getAvailable();}
 long count(){return records.findByUserIdOrderByCreatedAtDesc(user.getId()).size();}
 static void equal(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
 @Test void usdPreservesFrozenAndWritesActualReceipt(){DepositRecord d=service.manual(request());equal("125",balance("FUND"));equal("3",assets.findByUserIdAndCoin(user.getId(),"FUND").get().getFrozen());assertEquals(1,count());DepositCreditRecord c=credits.findByDepositRecordId(d.getId()).get();equal("25",c.getBalanceBefore());equal("125",c.getBalanceAfter());assertNull(d.getReviewedAt());assertEquals("ADMIN_MANUAL",d.getSource());}
 @Test void eurLocksSnapshotAndRetriesWithoutMarket(){DepositOrderRequest r=request();r.currency="EUR";DepositRecord d=service.manual(r);equal("135",balance("FUND"));equal("1.1",d.getExchangeRate());when(market.requireConversionRate("EUR","yahoo")).thenThrow(new com.gtcfesk.exchange.common.BusinessException("expired"));assertEquals(d.getId(),service.manual(r).getId());equal("135",balance("FUND"));}
 @Test void allAccountsAreIndependent(){for(String coin:Arrays.asList("CONTRACT","OPTION")){DepositOrderRequest r=request();r.account=coin;service.manual(r);equal("100",balance(coin));}equal("25",balance("FUND"));}
 @Test void userSubmitDoesNotCreditAndReviewDoesNotReprice(){DepositOrderRequest r=request();r.type="bank";r.currency="EUR";DepositRecord d=service.submit(user.getId(),r);equal("25",balance("FUND"));when(market.requireConversionRate("EUR","yahoo")).thenThrow(new com.gtcfesk.exchange.common.BusinessException("expired"));service.review(d.getId(),true,"approved");equal("135",balance("FUND"));assertThrows(RuntimeException.class,()->service.review(d.getId(),true,null));DepositOrderRequest rejected=request();rejected.type="digital";DepositRecord no=service.submit(user.getId(),rejected);service.review(no.getId(),false,"no receipt");assertFalse(credits.findByDepositRecordId(no.getId()).isPresent());equal("135",balance("FUND"));}
 @Test void changedParametersConflictAndNormalizedAmountRetries(){DepositOrderRequest r=request();DepositRecord d=service.manual(r);r.amount=new BigDecimal("100.00");assertEquals(d.getId(),service.manual(r).getId());r.remark="different";assertEquals(409,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.manual(r)).getRawStatusCode());equal("125",balance("FUND"));}
 @Test void validationHasNoSideEffects(){for(String amount:Arrays.asList("0","-1","99999999999999999","0.00000000000000001")){DepositOrderRequest r=request();r.amount=new BigDecimal(amount);assertThrows(RuntimeException.class,()->service.manual(r));}DepositOrderRequest missing=request();missing.idempotencyKey=null;assertThrows(RuntimeException.class,()->service.manual(missing));DepositOrderRequest r=request();r.currency="BTC";final DepositOrderRequest bad=r;assertThrows(RuntimeException.class,()->service.manual(bad));assertEquals(0,count());equal("25",balance("FUND"));}
 @Test void feesAndOverflow(){equal("1",DepositOrderService.fee(new BigDecimal("100"),new BigDecimal("0.01")));assertThrows(RuntimeException.class,()->fiat.toUsd(new BigDecimal("0.0000000000000001"),new BigDecimal("0.001")));DepositOrderRequest r=request();r.amount=new BigDecimal("9999999999999999");assertThrows(RuntimeException.class,()->service.manual(r));assertEquals(0,count());}
 @Test void everyWriteCheckpointRollsBackManualAndReview(){for(String point:Arrays.asList("order","account","credit","flush")){DepositOrderService failing=service(point);assertThrows(RuntimeException.class,()->failing.manual(request()));assertEquals(0,count());equal("25",balance("FUND"));}for(String point:Arrays.asList("account","credit","flush")){DepositOrderRequest r=request();r.type="bank";DepositRecord d=service.submit(user.getId(),r);assertThrows(RuntimeException.class,()->service(point).review(d.getId(),true,null));assertEquals("PENDING",records.findById(d.getId()).get().getStatus());assertFalse(credits.findByDepositRecordId(d.getId()).isPresent());equal("25",balance("FUND"));}}
 List<Boolean> race(Callable<?> a,Callable<?> b)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);try{List<Future<Boolean>> futures=new ArrayList<>();for(Callable<?> job:Arrays.asList(a,b))futures.add(pool.submit(()->{auth();gate.await();try{job.call();return true;}catch(RuntimeException e){return false;}finally{SecurityContextHolder.clearContext();}}));gate.countDown();return Arrays.asList(futures.get(0).get(30,TimeUnit.SECONDS),futures.get(1).get(30,TimeUnit.SECONDS));}finally{pool.shutdownNow();}}
 @Test void concurrentIdempotencyAndTwoCredits()throws Exception{DepositOrderRequest r=request();race(()->service.manual(r),()->service.manual(r));service.manual(r);assertEquals(1,count());equal("125",balance("FUND"));DepositOrderRequest a=request(),b=request();race(()->service.manual(a),()->service.manual(b));service.manual(a);service.manual(b);equal("325",balance("FUND"));assertEquals(3,count());}
 @Test void competingApproveRejectHasOneTerminalResult()throws Exception{DepositOrderRequest r=request();r.type="bank";DepositRecord d=service.submit(user.getId(),r);List<Boolean> result=race(()->service.review(d.getId(),true,null),()->service.review(d.getId(),false,"rejected"));assertEquals(1,result.stream().filter(Boolean::booleanValue).count());boolean done="COMPLETED".equals(records.findById(d.getId()).get().getStatus());equal(done?"125":"25",balance("FUND"));assertEquals(done,credits.findByDepositRecordId(d.getId()).isPresent());}
 @Test void accountVersionProtectsParallelOtherWriter()throws Exception{DepositOrderRequest r=request();List<Boolean> result=race(()->service.manual(r),()->new TransactionTemplate(manager).execute(s->{AssetAccount a=assets.findByUserIdAndCoin(user.getId(),"FUND").get();a.setAvailable(a.getAvailable().subtract(BigDecimal.TEN));assets.saveAndFlush(a);return true;}));equal(new BigDecimal("25").add(result.get(0)?new BigDecimal("100"):BigDecimal.ZERO).subtract(result.get(1)?BigDecimal.TEN:BigDecimal.ZERO).toPlainString(),balance("FUND"));assertEquals(result.get(0)?1:0,count());}
 @Test void historyCompletedNeverRecreditsAndMetadataStaysUnknown(){DepositRecord d=new DepositRecord();d.setUserId(user.getId());d.setType("bank");d.setNetwork("BANK");d.setAddress("");d.setAmount(BigDecimal.TEN);d.setStatus("COMPLETED");records.saveAndFlush(d);assertThrows(RuntimeException.class,()->service.review(d.getId(),true,null));assertNull(records.findById(d.getId()).get().getSource());assertFalse(credits.findByDepositRecordId(d.getId()).isPresent());equal("25",balance("FUND"));}
 @Test void userDtoHidesInternalFields(){DepositRecord d=service.manual(request());Map<String,Object> out=DepositOrderService.publicDto(d);assertNull(out.get("remark"));for(String k:Arrays.asList("createdById","reviewedByName","reviewRemark","requestHash","idempotencyKey","credit","balanceBefore","manualPurpose"))assertFalse(out.containsKey(k));assertEquals("ADMIN_MANUAL",out.get("source"));}
 @Test void csvIsQuotedAndFormulaSafe(){assertEquals("\"'=SUM(1)\"",DepositOrderController.csv("=SUM(1)"));assertEquals("\"a\"\"b\"",DepositOrderController.csv("a\"b"));}

 @Test void productionTransferAndNewAccountCreationStayAtomic()throws Exception {
  DepositOrderRequest r=request();TransferController transfer=new TransferController(users,assets,transfers);TransferController.TransferRequest t=new TransferController.TransferRequest();t.setFromAccount("FUND");t.setToAccount("CONTRACT");t.setAmount(BigDecimal.TEN);t.setRequestId(UUID.randomUUID().toString());
  List<Boolean> outcomes=race(()->service.manual(r),()->new TransactionTemplate(manager).execute(s->transfer.transfer(new UsernamePasswordAuthenticationToken(user.getId().toString(),null),t)));
  equal(new BigDecimal("25").add(outcomes.get(0)?new BigDecimal("100"):BigDecimal.ZERO).subtract(outcomes.get(1)?BigDecimal.TEN:BigDecimal.ZERO).toPlainString(),balance("FUND"));
  if(outcomes.get(1))equal("10",balance("CONTRACT"));
  DepositOrderRequest a=request(),b=request();a.account="OPTION";b.account="OPTION";race(()->service.manual(a),()->service.manual(b));service.manual(a);service.manual(b);equal("200",balance("OPTION"));
 }
 @Test void commitFailureRollsBackAllWrites(){DepositOrderService failing=new DepositOrderService(records,credits,assets,users,admins,fiat,access,new ObjectMapper(),manager){
  @Override protected void checkpoint(String stage){if(stage.equals("flush"))org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){throw new IllegalStateException("commit failure");}});}};
  assertThrows(RuntimeException.class,()->failing.manual(request()));assertEquals(0,count());equal("25",balance("FUND"));
 }

 @Test void sameKeyRejectsAllMoneyIdentityChanges(){DepositOrderRequest original=request();service.manual(original);for(String change:Arrays.asList("user","account","amount","remark")){DepositOrderRequest r=request();r.idempotencyKey=original.idempotencyKey;if(change.equals("user"))r.userId=1L;if(change.equals("account"))r.account="OPTION";if(change.equals("amount"))r.amount=new BigDecimal("101");if(change.equals("remark"))r.remark="different";assertEquals(409,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.manual(r)).getRawStatusCode());}assertEquals(1,count());equal("125",balance("FUND"));}
 @Test void simultaneousApprovalsCreditExactlyOnce()throws Exception{DepositOrderRequest r=request();r.type="bank";DepositRecord d=service.submit(user.getId(),r);List<Boolean> result=race(()->service.review(d.getId(),true,null),()->service.review(d.getId(),true,null));assertEquals(1,result.stream().filter(Boolean::booleanValue).count());equal("125",balance("FUND"));assertTrue(credits.findByDepositRecordId(d.getId()).isPresent());}

 @Test void longRemarksNonzeroFeeAndInvalidReceiptAreRejected(){DepositOrderRequest r=request();r.remark=String.join("",Collections.nCopies(501,"x"));assertThrows(RuntimeException.class,()->service.manual(r));r.remark="valid";r.manualPurpose="RECEIPT";assertThrows(RuntimeException.class,()->service.manual(r));assertThrows(IllegalArgumentException.class,()->r.validateFee(new BigDecimal("0.01")));assertEquals(0,count());equal("25",balance("FUND"));}

 @Test void ledgerListSummaryExportAndAgentScopeAgree(){
  DepositOrderRequest manual=request();manual.remark="=SUM(1)";DepositRecord manualOrder=service.manual(manual);
  DepositOrderRequest submitted=request();submitted.type="bank";submitted.amount=new BigDecimal("50");
  DepositRecord userOrder=service.submit(user.getId(),submitted);service.review(userOrder.getId(),true,"approved");
  UserAccount outsider=new UserAccount();outsider.setEmail(UUID.randomUUID()+"@fixture.invalid");outsider.setPasswordHash("not-a-login");outsider=users.saveAndFlush(outsider);
  DepositOrderRequest outside=request();outside.userId=outsider.getId();DepositRecord outsideOrder=service.manual(outside);
  EntityManager em=entityManagerFactory.createEntityManager();
  try {
   DepositOrderController ledger=new DepositOrderController(service,records,credits,users,assets,access,em,new ObjectMapper().findAndRegisterModules());
   Map<String,String> filter=new HashMap<>();filter.put("userId",user.getId().toString());
   assertEquals(2L,ledger.list(filter).get("total"));
   Map<String,Object> totals=ledger.summary(filter);assertEquals("150.0000000000000000",totals.get("creditedUsd"));
   assertEquals("100.0000000000000000",totals.get("manualUsd"));assertEquals("50.0000000000000000",totals.get("userUsd"));
   assertEquals("25.0000000000000000",((Map<?,?>)ledger.detail(manualOrder.getId()).get("credit")).get("balanceBefore"));
   String csv=new String(ledger.export(filter).getBody(),java.nio.charset.StandardCharsets.UTF_8);
   assertTrue(csv.startsWith("\ufeff"));assertTrue(csv.contains("\"'=SUM(1)\""));assertFalse(csv.contains(outsideOrder.getOrderNo()));
   filter.put("source","ADMIN_MANUAL");assertEquals(1L,ledger.list(filter).get("total"));
   assertEquals("100.0000000000000000",ledger.summary(filter).get("creditedUsd"));
   filter.remove("source");filter.put("status","INVALID");assertThrows(org.springframework.web.server.ResponseStatusException.class,()->ledger.list(filter));
   UserAccount agent=new UserAccount();agent.setEmail(UUID.randomUUID()+"@fixture.invalid");agent.setPasswordHash("not-a-login");agent=users.saveAndFlush(agent);
   user.setParentUserId(agent.getId());users.saveAndFlush(user);
   SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent-"+agent.getId(),"unused",Collections.singletonList(new SimpleGrantedAuthority("ROLE_AGENT"))));
   assertEquals(2L,ledger.list(Collections.emptyMap()).get("total"));
   assertEquals("150.0000000000000000",ledger.summary(Collections.emptyMap()).get("creditedUsd"));
   assertFalse(new String(ledger.export(Collections.emptyMap()).getBody(),java.nio.charset.StandardCharsets.UTF_8).contains(outsideOrder.getOrderNo()));
  } finally {em.close();}
 }
}
