package com.gtcfesk.exchange.activity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.user.KycIdentityService;
import com.gtcfesk.exchange.trade.*;
import com.gtcfesk.exchange.trade.dto.*;
import com.gtcfesk.exchange.market.*;
import org.junit.jupiter.api.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;
import javax.persistence.*;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(ActivityIntegrationTest.Config.class)
class ActivityIntegrationTest {
 @Configuration @EnableTransactionManagement @EnableJpaRepositories(basePackages={"com.gtcfesk.exchange.repository","com.gtcfesk.exchange.activity","com.gtcfesk.exchange.admin"})
 @Import({ActivityService.class,TrialFunds.class,KycIdentityService.class})
 static class Config {
  @Bean DataSource dataSource(){
   String port=System.getProperty("activity.test.mysqlPort");
   if(port==null)return new DriverManagerDataSource("jdbc:h2:mem:activity;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000","sa","");
   if(!port.matches("[0-9]{1,5}"))throw new IllegalArgumentException("Test MySQL port required");
   // Dedicated disposable database only: this suite creates and drops its schema.
   return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"+port+"/activity_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8","root",System.getenv("ACTIVITY_TEST_MYSQL_PASSWORD"));
  }
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds){LocalContainerEntityManagerFactoryBean f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);f.setPackagesToScan("com.gtcfesk.exchange.entity","com.gtcfesk.exchange.activity","com.gtcfesk.exchange.admin");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","create-drop");p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");f.setJpaProperties(p);return f;}
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
  @Bean ObjectMapper mapper(){return new ObjectMapper();}
  static final ForexQuoteMarketService quotes=mock(ForexQuoteMarketService.class);
  @Bean ContractOrderService contracts(KycIdentityService identity,ContractOrderRepository orders,AssetAccountRepository assets,TradingSymbolRepository symbols,PlatformTransactionManager manager){MarketCategoryService categories=mock(MarketCategoryService.class);when(categories.leverageEnabled(any())).thenReturn(true);return new ContractOrderService(identity,orders,assets,symbols,quotes,manager,categories);}
  @Bean OptionOrderService options(KycIdentityService identity,OptionOrderRepository orders,AssetAccountRepository assets,TradingSymbolRepository symbols,OptionDurationRepository durations){return new OptionOrderService(identity,orders,assets,symbols,durations,quotes);}
 }
 @Autowired ActivityService service;@Autowired TrialFunds funds;@Autowired ActivityCampaignRepository campaigns;@Autowired ActivityDeliveryRepository deliveries;
 @Autowired TrialLedgerRepository ledger;@Autowired TrialAccountRepository trials;@Autowired UserAccountRepository users;@Autowired AssetAccountRepository assets;@Autowired KycRecordRepository kycs;
 @Autowired ContractOrderService contracts;@Autowired OptionOrderService options;@Autowired ContractOrderRepository contractOrders;@Autowired TradingSymbolRepository symbols;@Autowired OptionDurationRepository durations;
 ForexQuoteMarketService quotes=Config.quotes;@Autowired PlatformTransactionManager manager;
 Long user,other;String symbol;TransactionTemplate tx;
 static BigDecimal d(String s){return new BigDecimal(s);}static void money(String n,BigDecimal actual){assertEquals(0,d(n).compareTo(actual),"Expected "+n+" actual "+actual);}
 @BeforeEach void setup(){
  reset(quotes);tx=new TransactionTemplate(manager);user=user();other=user();
  TradingSymbol s=new TradingSymbol();s.setSymbol("BTC"+user);symbol=s.getSymbol();s.setName("Trial integration");s.setBaseCurrency("BTC");s.setQuoteCurrency("USDT");s.setCategory("Crypto");s.setSourceCategory("Crypto");s.setMarketSource("binance");s.setIsEnabled(true);s.setLotSize(BigDecimal.ONE);s.setFeeMultiplier(d("1"));s.setMaxLeverage(d("10"));s.setQuantityUnitType("BASE_ASSET");s.setSpecVersion(1L);s.setMinOrderQuantity(d("0.01"));s.setQuantityStep(d("0.01"));s.setMinOrderNotional(BigDecimal.ONE);symbols.saveAndFlush(s);
  when(quotes.freshPrice(symbol)).thenReturn(d("100"));
  if(!durations.findByDuration(60).isPresent()){OptionDuration duration=new OptionDuration();duration.setDuration(60);duration.setLabel("60s");duration.setSortOrder(1);duration.setEnabled(true);duration.setProfitRate(d("0.8"));duration.setLossRate(BigDecimal.ONE);duration.setMinAmount(BigDecimal.ONE);duration.setMaxAmount(d("10000"));durations.saveAndFlush(duration);}
 }
 Long user(){UserAccount u=new UserAccount();u.setEmail(UUID.randomUUID()+"@activity.test");u.setPasswordHash("test-only");u.setLastLoginAt(LocalDateTime.now());u=users.saveAndFlush(u);for(String coin:Arrays.asList("FUND","CONTRACT","OPTION")){AssetAccount a=new AssetAccount();a.setUserId(u.getId());a.setCoin(coin);a.setAvailable(d("100"));assets.saveAndFlush(a);}return u.getId();}
 ActivityCampaign campaign(){ActivityCampaign c=new ActivityCampaign();c.setName("Test reward");c.setStatus("ACTIVE");c.setTranslations("{\"zh-CN\":{\"title\":\"礼遇\",\"body\":\"领取{amount} U\",\"terms\":\"本金不可提现\"},\"en\":{\"title\":\"Gift\",\"body\":\"Claim {amount} U\",\"terms\":\"Trading only\"}}");return service.save(null,c);}
 ActivityDelivery send(ActivityCampaign c,Long u){service.send(c.getId(),Arrays.asList(u),"test-admin");return deliveries.findByCampaignIdAndUserId(c.getId(),u).get();}
 void grant(){ActivityDelivery a=send(campaign(),user);service.claim(user,a.getId());}
 void approved(){KycRecord k=new KycRecord();k.setUserId(user);k.setStatus("APPROVED");k.setRealName("Test");k.setIdNumber("Test-id");kycs.saveAndFlush(k);}
 BigDecimal cash(String coin){return assets.findByUserIdAndCoin(user,coin).get().getAvailable();}
 CreateContractOrderRequest request(String type){CreateContractOrderRequest r=new CreateContractOrderRequest();r.setSymbol(symbol);r.setType(type);r.setSide("BUY");r.setQuantity(BigDecimal.ONE);r.setPrice(d("100"));r.setLeverage(d("10"));r.setSpecVersion(1L);r.setQuantityUnitType("BASE_ASSET");return r;}
 CreateOptionOrderRequest option(String amount){CreateOptionOrderRequest r=new CreateOptionOrderRequest();r.setSymbol(symbol);r.setDuration(60);r.setDirection("UP");r.setAmount(d(amount));return r;}
 @Test void sendReceiptCloseReopenAndIdempotentClaim(){ActivityCampaign c=campaign();ActivityDelivery m=send(c,user);assertNull(m.getReceivedAt());assertEquals(1,service.send(c.getId(),Arrays.asList(user,user),"admin").get("duplicates"));service.event(user,m.getId(),"RECEIVED");service.event(user,m.getId(),"CLOSED");assertEquals(1L,service.stats(c.getId()).get("closedWithoutOpening"));service.event(user,m.getId(),"OPENED");assertEquals(0L,service.stats(c.getId()).get("closedWithoutOpening"));service.claim(user,m.getId());service.claim(user,m.getId());money("300",funds.available(user));money("100",cash("FUND"));assertEquals(1,campaigns.findById(c.getId()).get().getClaimCount());assertEquals(1,ledger.findByUserIdOrderByIdDesc(user,PageRequest.of(0,50)).getTotalElements());}
 @Test void recipientOwnershipAndInvalidEvents(){ActivityDelivery m=send(campaign(),user);assertThrows(BusinessException.class,()->service.claim(other,m.getId()));assertThrows(BusinessException.class,()->service.event(other,m.getId(),"OPENED"));assertThrows(BusinessException.class,()->service.message(other,m.getId()));assertThrows(BusinessException.class,()->service.event(user,m.getId(),"CLAIMED"));money("0",funds.available(other));}
 @Test void unavailableAndScheduleRules(){for(String state:Arrays.asList("PAUSED","CLOSED","DRAFT")){ActivityCampaign c=campaign();ActivityDelivery m=send(c,user);c.setStatus(state);service.save(c.getId(),c);assertThrows(BusinessException.class,()->service.claim(user,m.getId()));}ActivityCampaign c=campaign();c.setStartsAt(LocalDateTime.now().plusDays(1));service.save(c.getId(),c);ActivityDelivery m=send(c,user);assertThrows(BusinessException.class,()->service.claim(user,m.getId()));c.setStartsAt(null);c.setEndsAt(LocalDateTime.now().minusSeconds(1));service.save(c.getId(),c);assertThrows(BusinessException.class,()->service.claim(user,m.getId()));}
 @Test void budgetAndClaimCap(){ActivityCampaign c=campaign();c.setBudget(d("300"));c.setMaxClaims(1);service.save(c.getId(),c);ActivityDelivery one=send(c,user),two=send(c,other);service.claim(user,one.getId());assertThrows(BusinessException.class,()->service.claim(other,two.getId()));money("0",funds.available(other));money("300",campaigns.findById(c.getId()).get().getGranted());}
 @Test void eligibilityTemplatesAndImmutableTerms(){UserAccount u=users.findById(user).get();u.setLastLoginAt(LocalDateTime.now().minusDays(4));users.saveAndFlush(u);ActivityCampaign c=campaign();assertEquals(1,service.send(c.getId(),Arrays.asList(user),"admin").get("ineligible"));ActivityDelivery m=send(c,other);c.setAmount(d("400"));assertThrows(BusinessException.class,()->service.save(c.getId(),c));c.setAmount(d("300"));UserAccount v=users.findById(other).get();v.setStatus("frozen");users.saveAndFlush(v);assertThrows(BusinessException.class,()->service.claim(other,m.getId()));ActivityCampaign template=campaign();template.setTemplate(true);service.save(template.getId(),template);assertThrows(BusinessException.class,()->service.send(template.getId(),Arrays.asList(other),"admin"));}
 @Test void malformedConfigAndInvalidBatchAtomic(){ActivityCampaign c=campaign();c.setTranslations("{\"zh-CN\":{}}");assertThrows(BusinessException.class,()->service.save(c.getId(),c));assertThrows(BusinessException.class,()->service.send(c.getId(),Arrays.asList(user,999999999L),"admin"));assertEquals(0,deliveries.countByCampaignId(c.getId()));final ActivityCampaign invalid=campaign();invalid.setAmount(d("0.001"));assertThrows(BusinessException.class,()->service.save(invalid.getId(),invalid));}
 @Test void unverifiedContractProfitGoesToRealPrincipalStaysTrial(){grant();ContractOrder o=contracts.createOrder(user,request("MARKET"));money("11",o.getTrialReserved());money("289",funds.available(user));money("100",cash("CONTRACT"));when(quotes.freshPrice(symbol)).thenReturn(d("110"));contracts.closeOrder(user,o.getId(),null);money("300",funds.available(user));money("109",cash("CONTRACT"));money("9",funds.snapshot(user).getProfits());assertThrows(BusinessException.class,()->contracts.closeOrder(user,o.getId(),null));}
 @Test void lossAndFeesUseTrialFirst(){grant();ContractOrder o=contracts.createOrder(user,request("MARKET"));when(quotes.freshPrice(symbol)).thenReturn(d("80"));contracts.closeOrder(user,o.getId(),null);money("279",funds.available(user));money("100",cash("CONTRACT"));money("21",funds.snapshot(user).getConsumed());}
 @Test void cancellationRestoresOriginalSplit(){grant();approved();CreateContractOrderRequest r=request("LIMIT");r.setQuantity(d("30"));ContractOrder o=contracts.createOrder(user,r);money("300",o.getTrialReserved());money("70",cash("CONTRACT"));contracts.cancelOrder(user,o.getId());money("300",funds.available(user));money("100",cash("CONTRACT"));money("0",funds.snapshot(user).getFrozen());}
 @Test void limitMatchesWithAllCreditReserved(){grant();CreateContractOrderRequest r=request("LIMIT");r.setQuantity(d("20"));r.setLeverage(d("10"));ContractOrder o=contracts.createOrder(user,r);when(quotes.freshPrice(symbol)).thenReturn(d("90"));assertEquals(1,contracts.matchPendingLimitOrders());ContractOrder filled=contractOrders.findById(o.getId()).get();assertEquals("OPEN",filled.getStatus());money("200",filled.getTrialReserved());money("100",funds.available(user));contracts.closeOrder(user,o.getId(),null);money("280",funds.available(user));}
 @Test void unverifiedCannotSpendRealAndDepletedNeedsKyc(){grant();assertThrows(KycRequiredException.class,()->contracts.createOrder(user,oversized()));money("300",funds.available(user));OptionOrder o=options.createOrder(user,option("300"));assertFalse(funds.canTrade(user));when(quotes.freshPrice(symbol)).thenReturn(d("90"));options.closeOrder(user,o.getId(),null);money("0",funds.available(user));money("100",cash("OPTION"));assertThrows(KycRequiredException.class,()->options.createOrder(user,option("1")));}
 CreateContractOrderRequest oversized(){CreateContractOrderRequest r=request("MARKET");r.setQuantity(d("30"));return r;}
 @Test void optionProfitAndMixedLoss(){grant();OptionOrder win=options.createOrder(user,option("100"));when(quotes.freshPrice(symbol)).thenReturn(d("110"));options.closeOrder(user,win.getId(),null);money("300",funds.available(user));money("180",cash("OPTION"));approved();OptionOrder loss=options.createOrder(user,option("350"));when(quotes.freshPrice(symbol)).thenReturn(d("90"));options.closeOrder(user,loss.getId(),null);money("0",funds.available(user));money("130",cash("OPTION"));}
 @Test void concurrentDuplicateClaimCreditsOnce() throws Exception {ActivityCampaign c=campaign();ActivityDelivery m=send(c,user);ExecutorService pool=Executors.newFixedThreadPool(6);try{List<Callable<TrialAccount>> calls=new ArrayList<>();for(int i=0;i<12;i++)calls.add(()->service.claim(user,m.getId()));for(Future<TrialAccount> f:pool.invokeAll(calls))f.get(20,TimeUnit.SECONDS);money("300",funds.available(user));assertEquals(1,campaigns.findById(c.getId()).get().getClaimCount());assertEquals(1,ledger.findByUserIdOrderByIdDesc(user,PageRequest.of(0,50)).getTotalElements());}finally{pool.shutdownNow();}}
 @Test void crossCampaignConcurrentClaimsDoNotLoseMoney() throws Exception {ActivityDelivery a=send(campaign(),user),b=send(campaign(),user);ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<?> x=pool.submit(()->service.claim(user,a.getId())),y=pool.submit(()->service.claim(user,b.getId()));x.get(20,TimeUnit.SECONDS);y.get(20,TimeUnit.SECONDS);money("600",funds.available(user));}finally{pool.shutdownNow();}}
 @Test void concurrentOrdersCannotOverspendBonus() throws Exception {grant();ExecutorService pool=Executors.newFixedThreadPool(2);try{Callable<Boolean> buy=()->{try{options.createOrder(user,option("200"));return true;}catch(KycRequiredException e){return false;}};Future<Boolean>a=pool.submit(buy),b=pool.submit(buy);assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));money("100",funds.available(user));money("200",funds.snapshot(user).getFrozen());money("100",cash("OPTION"));}finally{pool.shutdownNow();}}
 @Test void concurrentBudgetCannotOvergrant() throws Exception {ActivityCampaign c=campaign();c.setBudget(d("300"));service.save(c.getId(),c);ActivityDelivery a=send(c,user),b=send(c,other);ExecutorService pool=Executors.newFixedThreadPool(2);try{java.util.function.Supplier<Boolean> one=()->{try{service.claim(user,a.getId());return true;}catch(BusinessException e){return false;}};java.util.function.Supplier<Boolean> two=()->{try{service.claim(other,b.getId());return true;}catch(BusinessException e){return false;}};Future<Boolean>x=pool.submit(one::get),y=pool.submit(two::get);assertNotEquals(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));money("300",funds.available(user).add(funds.available(other)));}finally{pool.shutdownNow();}}
 @Test void concurrentCloseCannotCreditProfitTwice() throws Exception {grant();ContractOrder o=contracts.createOrder(user,request("MARKET"));when(quotes.freshPrice(symbol)).thenReturn(d("110"));ExecutorService pool=Executors.newFixedThreadPool(2);try{Callable<Boolean> close=()->{try{contracts.closeOrder(user,o.getId(),null);return true;}catch(BusinessException|org.springframework.dao.OptimisticLockingFailureException e){return false;}};Future<Boolean>x=pool.submit(close),y=pool.submit(close);assertNotEquals(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));money("300",funds.available(user));money("109",cash("CONTRACT"));}finally{pool.shutdownNow();}}
 @Test void principalCannotTransferAndPublicPayloadHasNoBudget(){grant();com.gtcfesk.exchange.user.TransferController c=new com.gtcfesk.exchange.user.TransferController(users,assets,mock(TransferRecordRepository.class));com.gtcfesk.exchange.user.TransferController.TransferRequest r=new com.gtcfesk.exchange.user.TransferController.TransferRequest();r.setFromAccount("TRIAL");r.setToAccount("FUND");r.setAmount(BigDecimal.ONE);org.springframework.security.core.Authentication auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.toString(),null);assertThrows(BusinessException.class,()->c.transfer(auth,r));Map<?,?> payload=(Map<?,?>)service.inbox(user,0).getContent().get(0).get("campaign");assertFalse(payload.containsKey("budget"));assertFalse(payload.containsKey("granted"));assertFalse(payload.containsKey("name"));money("300",funds.available(user));}

}
