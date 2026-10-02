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
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.control.*;
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
 private TenantContext.Scope tenantScope;
 @AfterEach void closeTenantScope(){ if(tenantScope!=null)tenantScope.close(); }
 @Configuration @EnableTransactionManagement @EnableJpaRepositories(repositoryFactoryBeanClass=com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean.class,basePackages={"com.gtcfesk.exchange.repository","com.gtcfesk.exchange.activity","com.gtcfesk.exchange.admin"})
 @Import({ActivityService.class,TrialFunds.class,KycIdentityService.class})
 static class Config {
  @Bean com.gtcfesk.exchange.tenant.TenantSecrets secrets(){return mock(com.gtcfesk.exchange.tenant.TenantSecrets.class);}
  @Bean com.gtcfesk.exchange.admin.SystemConfigService configs(){return mock(com.gtcfesk.exchange.admin.SystemConfigService.class);}
        @Bean com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound(){return mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class);}
        @Bean TenantReadinessService readiness(){return mock(TenantReadinessService.class);}

  @Bean DataSource dataSource(){
   String port=System.getProperty("activity.test.mysqlPort");
   if(port==null)return new DriverManagerDataSource("jdbc:h2:mem:activity_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000","sa","");
   if(!port.matches("[0-9]{1,5}"))throw new IllegalArgumentException("Test MySQL port required");
   // Dedicated disposable database only: this suite creates and drops its schema.
   return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"+port+"/activity_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8","root",System.getenv("ACTIVITY_TEST_MYSQL_PASSWORD"));
  }
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds){LocalContainerEntityManagerFactoryBean f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);f.setPackagesToScan("com.gtcfesk.exchange.entity","com.gtcfesk.exchange.activity","com.gtcfesk.exchange.admin");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","create-drop");p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");f.setJpaProperties(p);return f;}
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
  @Bean TenantPolicyService tenantPolicy(){return mock(TenantPolicyService.class);}
  @Bean ObjectMapper mapper(){return new ObjectMapper();}
  @Bean OperationalIssueService operationalIssues(DataSource ds,ControlAuditService audit,PlatformTransactionManager manager,ObjectMapper mapper){return new OperationalIssueService(new org.springframework.jdbc.core.JdbcTemplate(ds),audit,manager,mapper);}
  @Bean ControlAuditService materialAudit(){return mock(ControlAuditService.class);}
  @Bean com.gtcfesk.exchange.admin.AdminActivityMaterialController materialController(ActivityMaterialRepository repo,ObjectMapper mapper,ControlAuditService audit){return new com.gtcfesk.exchange.admin.AdminActivityMaterialController(repo,mapper,audit);}
  static final ForexQuoteMarketService quotes=mock(ForexQuoteMarketService.class);
  @Bean ContractOrderService contracts(KycIdentityService identity,ContractOrderRepository orders,AssetAccountRepository assets,TradingSymbolRepository symbols,PlatformTransactionManager manager){MarketCategoryService categories=mock(MarketCategoryService.class);when(categories.leverageEnabled(any())).thenReturn(true);return new ContractOrderService(identity,orders,assets,symbols,quotes,manager,categories);}
  @Bean OptionOrderService options(KycIdentityService identity,OptionOrderRepository orders,AssetAccountRepository assets,TradingSymbolRepository symbols,OptionDurationRepository durations){return new OptionOrderService(identity,orders,assets,symbols,durations,quotes);}
 }
 @Autowired ActivityService service;@Autowired TrialFunds funds;@Autowired ActivityCampaignRepository campaigns;@Autowired ActivityDeliveryRepository deliveries;
 @Autowired TrialLedgerRepository ledger;@Autowired TrialAccountRepository trials;@Autowired UserAccountRepository users;@Autowired AssetAccountRepository assets;@Autowired KycRecordRepository kycs;
 @Autowired ContractOrderService contracts;@Autowired OptionOrderService options;@Autowired ContractOrderRepository contractOrders;@Autowired TradingSymbolRepository symbols;@Autowired OptionDurationRepository durations;
 ForexQuoteMarketService quotes=Config.quotes;@Autowired PlatformTransactionManager manager;
 static <T> Callable<T> scoped(Callable<T> work){return ()->{try(TenantContext.Scope ignored=TenantContext.open(1L)){return work.call();}};}
 Long user,other;String symbol;TransactionTemplate tx;
 static BigDecimal d(String s){return new BigDecimal(s);}static void money(String n,BigDecimal actual){assertEquals(0,d(n).compareTo(actual),"Expected "+n+" actual "+actual);}
 @BeforeEach void setup(){
  tenantScope = TenantContext.open(1L);
  reset(quotes);tx=new TransactionTemplate(manager);user=user();other=user();
  TradingSymbol s=new TradingSymbol();s.setSymbol("BTC"+user);symbol=s.getSymbol();s.setName("Trial integration");s.setBaseCurrency("BTC");s.setQuoteCurrency("USDT");s.setCategory("Crypto");s.setSourceCategory("Crypto");s.setMarketSource("binance");s.setIsEnabled(true);s.setLotSize(BigDecimal.ONE);s.setFeeMultiplier(d("1"));s.setMaxLeverage(d("10"));s.setQuantityUnitType("BASE_ASSET");s.setSpecVersion(1L);s.setMinOrderQuantity(d("0.01"));s.setQuantityStep(d("0.01"));s.setMinOrderNotional(BigDecimal.ONE);symbols.saveAndFlush(s);
  when(quotes.freshPrice(symbol)).thenReturn(d("100"));
  if(!durations.findByTenantIdAndDuration(1L, 60).isPresent()){OptionDuration duration=new OptionDuration();duration.setDuration(60);duration.setLabel("60s");duration.setSortOrder(1);duration.setEnabled(true);duration.setProfitRate(d("0.8"));duration.setLossRate(BigDecimal.ONE);duration.setMinAmount(BigDecimal.ONE);duration.setMaxAmount(d("10000"));durations.saveAndFlush(duration);}
 }
 @Test void pageChangesBypassOnlySamePageThrottleAndRejectOldSequence(){
  LocalDateTime now=LocalDateTime.of(2026,10,1,0,0);
  assertEquals(1,users.reportPage(user,"contract","PC",100,now,now.minusSeconds(1)));
  assertEquals(0,users.reportPage(user,"contract","PC",101,now.plusNanos(100000000),now.minusSeconds(1)));
  assertEquals(1,users.reportPage(user,"profile","PC",102,now.plusNanos(200000000),now.minusSeconds(1)));
  assertEquals(0,users.reportPage(user,"contract","PC",101,now.plusNanos(250000000),now.minusSeconds(1)));
  assertEquals(1,users.reportPage(user,"option","PC",103,now.plusNanos(300000000),now.minusSeconds(1)));
  UserAccount saved=users.findByTenantIdAndId(1L,user).orElseThrow(AssertionError::new);
  assertEquals("option",saved.getLastPageCode());assertEquals(Long.valueOf(103),saved.getLastPageSequence());
 }
 Long user(){UserAccount u=new UserAccount();u.setEmail(UUID.randomUUID()+"@activity.test");u.setPasswordHash("test-only");u.setLastLoginAt(LocalDateTime.now());u=users.saveAndFlush(u);for(String coin:Arrays.asList("FUND","CONTRACT","OPTION")){AssetAccount a=new AssetAccount();a.setUserId(u.getId());a.setCoin(coin);a.setAvailable(d("100"));assets.saveAndFlush(a);}return u.getId();}
 ActivityCampaign campaign(){ActivityCampaign c=new ActivityCampaign();c.setName("Test reward");c.setStatus("ACTIVE");c.setTranslations("{\"zh-CN\":{\"title\":\"礼遇\",\"body\":\"领取{amount} U\",\"terms\":\"本金不可提现\"},\"en\":{\"title\":\"Gift\",\"body\":\"Claim {amount} U\",\"terms\":\"Trading only\"}}");return service.save(null,c);}
 ActivityDelivery send(ActivityCampaign c,Long u){service.send(c.getId(),Arrays.asList(u),"test-admin");return deliveries.findByTenantIdAndCampaignIdAndUserId(1L, c.getId(),u).get();}
 void grant(){ActivityDelivery a=send(campaign(),user);service.claim(user,a.getId());}
 void approved(){KycRecord k=new KycRecord();k.setUserId(user);k.setStatus("APPROVED");k.setRealName("Test");k.setIdNumber("Test-id");kycs.saveAndFlush(k);}
 BigDecimal cash(String coin){return assets.findByTenantIdAndUserIdAndCoin(1L, user,coin).get().getAvailable();}
 CreateContractOrderRequest request(String type){CreateContractOrderRequest r=new CreateContractOrderRequest();r.setSymbol(symbol);r.setType(type);r.setSide("BUY");r.setQuantity(BigDecimal.ONE);r.setPrice(d("100"));r.setLeverage(d("10"));r.setSpecVersion(1L);r.setQuantityUnitType("BASE_ASSET");r.setFundingSource("TRIAL");return r;}
 CreateOptionOrderRequest option(String amount){CreateOptionOrderRequest r=new CreateOptionOrderRequest();r.setSymbol(symbol);r.setDuration(60);r.setDirection("UP");r.setAmount(d(amount));r.setFundingSource("TRIAL");return r;}
 @Autowired ActivityMaterialRepository materials;
 @Autowired com.gtcfesk.exchange.admin.AdminActivityMaterialController materialController;
 @Autowired ControlAuditService materialAudit;
 @Test void materialLibraryPersistsAndIsTenantIsolated(){
  com.gtcfesk.exchange.admin.AdminActivityMaterialController controller=materialController;
  com.gtcfesk.exchange.admin.AdminActivityMaterialController.Input input=new com.gtcfesk.exchange.admin.AdminActivityMaterialController.Input();input.name="Library test "+user;input.nodesJson="[{\"type\":\"box\",\"motion\":\"float\"}]";
  ActivityMaterial saved=(ActivityMaterial)controller.save(input);assertEquals(input.nodesJson,materials.findByTenantIdAndId(1L,saved.getId()).get().getNodesJson());
  org.springframework.data.domain.Page<?> page=(org.springframework.data.domain.Page<?>)controller.list(0,input.name);assertEquals(1,page.getTotalElements());
  tenantScope.close();tenantScope=null;try(TenantContext.Scope otherTenant=TenantContext.open(2L)){assertFalse(materials.findByTenantIdAndId(2L,saved.getId()).isPresent());assertThrows(BusinessException.class,()->controller.delete(saved.getId()));assertEquals(0,((org.springframework.data.domain.Page<?>)controller.list(0,input.name)).getTotalElements());}finally{tenantScope=TenantContext.open(1L);}
  controller.delete(saved.getId());assertEquals(0,((org.springframework.data.domain.Page<?>)controller.list(0,input.name)).getTotalElements());assertTrue(materials.findByTenantIdAndId(1L,saved.getId()).get().isDeleted());
 }
 @Test void materialAuditFailureRollsBackCreateAndRemoval(){
  long count=materials.findAllByTenantId(1L).size();
  com.gtcfesk.exchange.admin.AdminActivityMaterialController.Input input=new com.gtcfesk.exchange.admin.AdminActivityMaterialController.Input();input.name="Audit rollback "+user;input.nodesJson="[{\"type\":\"box\"}]";
  doThrow(new IllegalStateException("fixture audit unavailable")).when(materialAudit).recordCurrent(eq("ACTIVITY_MATERIAL_CREATE"),anyString(),anyString(),isNull());
  try{assertThrows(IllegalStateException.class,()->materialController.save(input));assertEquals(count,materials.findAllByTenantId(1L).size());}finally{reset(materialAudit);}
  ActivityMaterial saved=(ActivityMaterial)materialController.save(input);
  doThrow(new IllegalStateException("fixture audit unavailable")).when(materialAudit).recordCurrent(eq("ACTIVITY_MATERIAL_REMOVE"),anyString(),anyString(),isNull());
  try{assertThrows(IllegalStateException.class,()->materialController.delete(saved.getId()));assertFalse(materials.findByTenantIdAndId(1L,saved.getId()).orElseThrow().isDeleted());}finally{reset(materialAudit);}
 }
 @Test void sendReceiptCloseReopenAndIdempotentClaim(){ActivityCampaign c=campaign();ActivityDelivery m=send(c,user);assertNull(m.getReceivedAt());assertEquals(1,service.send(c.getId(),Arrays.asList(user,user),"admin").get("duplicates"));service.event(user,m.getId(),"RECEIVED");service.event(user,m.getId(),"CLOSED");assertEquals(1L,service.stats(c.getId()).get("closedWithoutOpening"));service.event(user,m.getId(),"OPENED");assertEquals(0L,service.stats(c.getId()).get("closedWithoutOpening"));service.claim(user,m.getId());service.claim(user,m.getId());money("300",funds.available(user));money("100",cash("FUND"));assertEquals(1,campaigns.findByTenantIdAndId(1L, c.getId()).get().getClaimCount());assertEquals(1,ledger.findByTenantIdAndUserIdOrderByIdDesc(1L, user,PageRequest.of(0,50)).getTotalElements());}
 @Test void recipientOwnershipAndInvalidEvents(){ActivityDelivery m=send(campaign(),user);assertThrows(BusinessException.class,()->service.claim(other,m.getId()));assertThrows(BusinessException.class,()->service.event(other,m.getId(),"OPENED"));assertThrows(BusinessException.class,()->service.message(other,m.getId()));assertThrows(BusinessException.class,()->service.event(user,m.getId(),"CLAIMED"));money("0",funds.available(other));}
 @Test void unavailableAndScheduleRules(){for(String state:Arrays.asList("PAUSED","CLOSED","DRAFT")){ActivityCampaign c=campaign();ActivityDelivery m=send(c,user);c.setStatus(state);service.save(c.getId(),c);assertThrows(BusinessException.class,()->service.claim(user,m.getId()));}ActivityCampaign c=campaign();ActivityDelivery m=send(c,user);c.setStartsAt(LocalDateTime.now().plusDays(1));service.save(c.getId(),c);assertThrows(BusinessException.class,()->service.claim(user,m.getId()));c.setStartsAt(null);c.setEndsAt(LocalDateTime.now().minusSeconds(1));service.save(c.getId(),c);assertThrows(BusinessException.class,()->service.claim(user,m.getId()));}
 @Test void budgetAndClaimCap(){ActivityCampaign c=campaign();c.setBudget(d("300"));c.setMaxClaims(1);service.save(c.getId(),c);ActivityDelivery one=send(c,user),two=send(c,other);service.claim(user,one.getId());assertThrows(BusinessException.class,()->service.claim(other,two.getId()));money("0",funds.available(other));money("300",campaigns.findByTenantIdAndId(1L, c.getId()).get().getGranted());}
 @Test void eligibilityTemplatesAndImmutableTerms(){UserAccount u=users.findByTenantIdAndId(1L, user).get();u.setLastLoginAt(LocalDateTime.now().minusDays(4));users.saveAndFlush(u);ActivityCampaign c=campaign();assertEquals(1,service.send(c.getId(),Arrays.asList(user),"admin").get("ineligible"));ActivityDelivery m=send(c,other);c.setAmount(d("400"));assertThrows(BusinessException.class,()->service.save(c.getId(),c));c.setAmount(d("300"));UserAccount v=users.findByTenantIdAndId(1L, other).get();v.setStatus("frozen");users.saveAndFlush(v);assertThrows(BusinessException.class,()->service.claim(other,m.getId()));ActivityCampaign template=campaign();template.setTemplate(true);service.save(template.getId(),template);assertThrows(BusinessException.class,()->service.send(template.getId(),Arrays.asList(other),"admin"));}
 @Test void malformedConfigAndInvalidBatchAtomic(){ActivityCampaign c=campaign();c.setTranslations("{\"zh-CN\":{}}");assertThrows(BusinessException.class,()->service.save(c.getId(),c));assertThrows(BusinessException.class,()->service.send(c.getId(),Arrays.asList(user,999999999L),"admin"));assertEquals(0,deliveries.countByTenantIdAndCampaignId(1L, c.getId()));final ActivityCampaign invalid=campaign();invalid.setAmount(d("0.001"));assertThrows(BusinessException.class,()->service.save(invalid.getId(),invalid));}
 @Test void approvedContractProfitGoesToRealPrincipalStaysTrial(){grant();approved();ContractOrder o=contracts.createOrder(user,request("MARKET"));money("11",o.getTrialReserved());money("289",funds.available(user));money("100",cash("CONTRACT"));when(quotes.freshPrice(symbol)).thenReturn(d("110"));contracts.closeOrder(user,o.getId(),null);money("300",funds.available(user));money("109",cash("CONTRACT"));money("9",funds.snapshot(user).getProfits());assertThrows(BusinessException.class,()->contracts.closeOrder(user,o.getId(),null));}
 @Test void unverifiedTrialFundsCannotBypassKyc(){grant();assertThrows(KycRequiredException.class,()->contracts.createOrder(user,request("MARKET")));assertThrows(KycRequiredException.class,()->options.createOrder(user,option("1")));money("300",funds.available(user));money("0",funds.snapshot(user).getFrozen());money("100",cash("CONTRACT"));money("100",cash("OPTION"));}
 @Test void lossAndFeesUseTrialFirst(){grant();approved();ContractOrder o=contracts.createOrder(user,request("MARKET"));when(quotes.freshPrice(symbol)).thenReturn(d("80"));contracts.closeOrder(user,o.getId(),null);money("279",funds.available(user));money("100",cash("CONTRACT"));money("21",funds.snapshot(user).getConsumed());}
 @Test void cancellationRestoresOnlySelectedSource(){grant();approved();CreateContractOrderRequest r=request("LIMIT");r.setQuantity(d("30"));assertThrows(BusinessException.class,()->contracts.createOrder(user,r));money("300",funds.available(user));money("100",cash("CONTRACT"));r.setQuantity(d("20"));ContractOrder o=contracts.createOrder(user,r);money("220",o.getTrialReserved());money("80",funds.available(user));money("100",cash("CONTRACT"));contracts.cancelOrder(user,o.getId());money("300",funds.available(user));money("100",cash("CONTRACT"));money("0",funds.snapshot(user).getFrozen());}
 @Test void limitMatchesWithAllCreditReserved(){grant();approved();CreateContractOrderRequest r=request("LIMIT");r.setQuantity(d("20"));r.setLeverage(d("10"));ContractOrder o=contracts.createOrder(user,r);when(quotes.freshPrice(symbol)).thenReturn(d("90"));assertEquals(1,contracts.matchPendingLimitOrders());ContractOrder filled=contractOrders.findByTenantIdAndId(1L, o.getId()).get();assertEquals("OPEN",filled.getStatus());money("200",filled.getTrialReserved());money("100",funds.available(user));contracts.closeOrder(user,o.getId(),null);money("280",funds.available(user));}
 @Test void unverifiedCannotSpendRealAndDepletedNeedsKyc(){grant();assertThrows(KycRequiredException.class,()->contracts.createOrder(user,oversized()));money("300",funds.available(user));assertThrows(KycRequiredException.class,()->options.createOrder(user,option("300")));money("300",funds.available(user));money("100",cash("OPTION"));approved();OptionOrder o=options.createOrder(user,option("300"));KycRecord latest=new KycRecord();latest.setUserId(user);latest.setStatus("PENDING");latest.setRealName("Test pending");latest.setIdNumber("Test-pending-id");latest.setCreatedAt(LocalDateTime.now().plusSeconds(1));kycs.saveAndFlush(latest);assertFalse(funds.canTrade(user));when(quotes.freshPrice(symbol)).thenReturn(d("90"));options.closeOrder(user,o.getId(),null);money("0",funds.available(user));money("100",cash("OPTION"));assertThrows(KycRequiredException.class,()->options.createOrder(user,option("1")));}
 CreateContractOrderRequest oversized(){CreateContractOrderRequest r=request("MARKET");r.setQuantity(d("30"));return r;}
 @Test void optionProfitAndSingleSourceLoss(){grant();approved();OptionOrder win=options.createOrder(user,option("100"));when(quotes.freshPrice(symbol)).thenReturn(d("110"));options.closeOrder(user,win.getId(),null);money("300",funds.available(user));money("180",cash("OPTION"));assertThrows(BusinessException.class,()->options.createOrder(user,option("350")));CreateOptionOrderRequest real=option("50");real.setFundingSource("OPTION");OptionOrder loss=options.createOrder(user,real);when(quotes.freshPrice(symbol)).thenReturn(d("90"));options.closeOrder(user,loss.getId(),null);money("300",funds.available(user));money("130",cash("OPTION"));}
 @Test void concurrentDuplicateClaimCreditsOnce() throws Exception {ActivityCampaign c=campaign();ActivityDelivery m=send(c,user);ExecutorService pool=Executors.newFixedThreadPool(6);try{List<Callable<TrialAccount>> calls=new ArrayList<>();for(int i=0;i<12;i++)calls.add(scoped(()->service.claim(user,m.getId())));for(Future<TrialAccount> f:pool.invokeAll(calls))f.get(20,TimeUnit.SECONDS);money("300",funds.available(user));assertEquals(1,campaigns.findByTenantIdAndId(1L, c.getId()).get().getClaimCount());assertEquals(1,ledger.findByTenantIdAndUserIdOrderByIdDesc(1L, user,PageRequest.of(0,50)).getTotalElements());}finally{pool.shutdownNow();}}
 @Test void crossCampaignConcurrentClaimsDoNotLoseMoney() throws Exception {ActivityDelivery a=send(campaign(),user),b=send(campaign(),user);ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<?> x=pool.submit(scoped(()->service.claim(user,a.getId()))),y=pool.submit(scoped(()->service.claim(user,b.getId())));x.get(20,TimeUnit.SECONDS);y.get(20,TimeUnit.SECONDS);money("600",funds.available(user));}finally{pool.shutdownNow();}}
 @Test void concurrentOrdersCannotOverspendBonus() throws Exception {grant();approved();ExecutorService pool=Executors.newFixedThreadPool(2);try{Callable<Boolean> buy=()->{try{options.createOrder(user,option("250"));return true;}catch(BusinessException e){return false;}};Future<Boolean>a=pool.submit(scoped(buy)),b=pool.submit(scoped(buy));assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));money("50",funds.available(user));money("250",funds.snapshot(user).getFrozen());money("100",cash("OPTION"));money("400",funds.available(user).add(funds.snapshot(user).getFrozen()).add(cash("OPTION")));}finally{pool.shutdownNow();}}
 @Test void concurrentBudgetCannotOvergrant() throws Exception {ActivityCampaign c=campaign();c.setBudget(d("300"));service.save(c.getId(),c);ActivityDelivery a=send(c,user),b=send(c,other);ExecutorService pool=Executors.newFixedThreadPool(2);try{java.util.function.Supplier<Boolean> one=()->{try{service.claim(user,a.getId());return true;}catch(BusinessException e){return false;}};java.util.function.Supplier<Boolean> two=()->{try{service.claim(other,b.getId());return true;}catch(BusinessException e){return false;}};Future<Boolean>x=pool.submit(scoped(one::get)),y=pool.submit(scoped(two::get));assertNotEquals(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));money("300",funds.available(user).add(funds.available(other)));}finally{pool.shutdownNow();}}
 @Test void concurrentCloseCannotCreditProfitTwice() throws Exception {grant();approved();ContractOrder o=contracts.createOrder(user,request("MARKET"));when(quotes.freshPrice(symbol)).thenReturn(d("110"));ExecutorService pool=Executors.newFixedThreadPool(2);try{Callable<Boolean> close=()->{try{contracts.closeOrder(user,o.getId(),null);return true;}catch(BusinessException|org.springframework.dao.OptimisticLockingFailureException e){return false;}};Future<Boolean>x=pool.submit(scoped(close)),y=pool.submit(scoped(close));assertNotEquals(x.get(20,TimeUnit.SECONDS),y.get(20,TimeUnit.SECONDS));money("300",funds.available(user));money("109",cash("CONTRACT"));}finally{pool.shutdownNow();}}
 @Test void adminRecipientsExposeUserIdentityAndFilterEmailBeforePaging(){
  UserAccount owner=users.findByTenantIdAndId(1L,user).get();owner.setEmail("Alpha_%!"+user+"@activity.test");owner.setRemark("客户备注");users.saveAndFlush(owner);
  ActivityCampaign campaign=campaign();service.send(campaign.getId(),Arrays.asList(user,other),"test-admin");
  com.gtcfesk.exchange.admin.AdminActivityController admin=new com.gtcfesk.exchange.admin.AdminActivityController(service,campaigns,deliveries,funds,ledger,new com.gtcfesk.exchange.admin.AdminUserIdentity(users,new ObjectMapper().findAndRegisterModules()));
  org.springframework.data.domain.Page<?> result=(org.springframework.data.domain.Page<?>)admin.recipients(campaign.getId(),0,"ALL",null," ALPHA_%! ");
  assertEquals(1L,result.getTotalElements());Map<?,?> row=(Map<?,?>)result.getContent().get(0);assertEquals(user,((Number)row.get("userId")).longValue());assertEquals(owner.getEmail(),row.get("userEmail"));assertEquals("客户备注",row.get("userRemark"));
  assertEquals(0L,((org.springframework.data.domain.Page<?>)admin.recipients(campaign.getId(),0,"ALL",other,owner.getEmail())).getTotalElements());
  assertEquals(0L,((org.springframework.data.domain.Page<?>)admin.recipients(campaign.getId(),0,"ALL",null,"no-match")).getTotalElements());
  tenantScope.close();tenantScope=TenantContext.open(2L);assertEquals(0L,((org.springframework.data.domain.Page<?>)admin.recipients(campaign.getId(),0,"ALL",null,owner.getEmail())).getTotalElements());
 }
 @Test void onlineIdentityEmailPaginationAndAgentScope(){
  for(Long id:Arrays.asList(user,other)){UserAccount owner=users.findByTenantIdAndId(1L,id).get();owner.setCurrentToken("test-only");owner.setLastActivityAt(LocalDateTime.now());owner.setStatus("normal");owner.setParentUserId(user);owner.setEmail("online"+id+"@activity.test");owner.setRemark("在线客户备注");users.saveAndFlush(owner);}
  com.gtcfesk.exchange.user.UserActivityService activity=new com.gtcfesk.exchange.user.UserActivityService(users);
  Map<?,?> result=(Map<?,?>)activity.list(user,1,1," ONLINE ").get("data");assertEquals(2L,result.get("total"));assertEquals(1,((List<?>)result.get("items")).size());
  Map<?,?> row=(Map<?,?>)((List<?>)result.get("items")).get(0);assertEquals("在线客户备注",row.get("userRemark"));assertTrue(row.get("userEmail").toString().startsWith("online"));assertFalse(row.containsKey("currentToken"));
  assertEquals(0L,((Map<?,?>)activity.list(other,0,20,"online").get("data")).get("total"));
  tenantScope.close();tenantScope=TenantContext.open(2L);assertEquals(0L,((Map<?,?>)activity.list(null,0,20,"online").get("data")).get("total"));
 }
 @Test void principalCannotTransferAndPublicPayloadHasNoBudget(){grant();com.gtcfesk.exchange.user.TransferController c=new com.gtcfesk.exchange.user.TransferController(users,assets,mock(TransferRecordRepository.class));com.gtcfesk.exchange.user.TransferController.TransferRequest r=new com.gtcfesk.exchange.user.TransferController.TransferRequest();r.setFromAccount("TRIAL");r.setToAccount("FUND");r.setAmount(BigDecimal.ONE);org.springframework.security.core.Authentication auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user.toString(),null);assertThrows(BusinessException.class,()->c.transfer(auth,r));Map<?,?> payload=(Map<?,?>)service.inbox(user,0).getContent().get(0).get("campaign");assertFalse(payload.containsKey("budget"));assertFalse(payload.containsKey("granted"));assertFalse(payload.containsKey("name"));money("300",funds.available(user));}


 @Test void actualDesktopPageCodesReachPresetActivityTriggers() {
  ActivityCampaign c=campaign();c.setRecentLoginDays(0);c.setAutoSendEnabled(true);
  c.setPositions(Arrays.asList("AUTH_TRADE","AUTH_PROFILE"));c.setTriggerConditions(Arrays.asList("PAGE_TRADE","PAGE_PROFILE"));service.saveAutoSend(c.getId(),c);
  com.gtcfesk.exchange.user.UserActivityService activity=new com.gtcfesk.exchange.user.UserActivityService(users);
  org.springframework.test.util.ReflectionTestUtils.setField(activity,"activities",service);
  activity.report(user,"contract","PC",1);activity.report(other,"option","PC",1);
  assertEquals("AUTO:PAGE_TRADE",deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),user).get().getSentBy());
  assertEquals("AUTO:PAGE_TRADE",deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),other).get().getSentBy());
  Long personal=user();activity.report(personal,"profile","PC",1);
  assertEquals("AUTO:PAGE_PROFILE",deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),personal).get().getSentBy());
 }
 @Test void automaticDeliveryRulesToggleAndConcurrency() throws Exception {
  ActivityCampaign c=campaign();c.setTriggerConditions(Collections.singletonList("LOGIN"));c.setAutoSendEnabled(true);c.setStartsAt(LocalDateTime.now().plusDays(1));service.saveAutoSend(c.getId(),c);
  service.inbox(user,0);service.autoSendDue();service.trigger(user,"LOGIN","AUTH_HOME");assertEquals(0L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));
  c.setStartsAt(null);service.saveAutoSend(c.getId(),c);ExecutorService pool=Executors.newFixedThreadPool(2);
  try{List<Future<Integer>> jobs=pool.invokeAll(Arrays.asList(scoped(()->service.trigger(user,"LOGIN","AUTH_HOME")),scoped(()->service.trigger(user,"LOGIN","AUTH_HOME"))));for(Future<Integer> job:jobs)job.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
  assertEquals(1L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));assertEquals("AUTO:LOGIN",deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),user).get().getSentBy());money("0",funds.available(user));
  c.setAutoSendEnabled(false);service.saveAutoSend(c.getId(),c);service.trigger(other,"LOGIN","AUTH_HOME");assertFalse(deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),other).isPresent());
 }
 @Test void automaticDeliveryRejectsIneligibleExpiredAndExhaustedCampaigns(){
  ActivityCampaign c=campaign();c.setAutoSendEnabled(true);c.setTriggerConditions(Collections.singletonList("LOGIN"));service.saveAutoSend(c.getId(),c);
  UserAccount u=users.findByTenantIdAndId(1L,user).get();u.setLastLoginAt(LocalDateTime.now().minusDays(8));users.saveAndFlush(u);service.trigger(user,"LOGIN","AUTH_HOME");assertEquals(0L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));
  c.setEndsAt(LocalDateTime.now().minusSeconds(1));service.saveAutoSend(c.getId(),c);service.trigger(other,"LOGIN","AUTH_HOME");assertEquals(0L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));
  c.setEndsAt(null);c.setBudget(d("300"));c.setMaxClaims(1);service.saveAutoSend(c.getId(),c);ActivityDelivery delivered=send(c,other);service.claim(other,delivered.getId());c.setRecentLoginDays(0);service.saveAutoSend(c.getId(),c);service.trigger(user,"LOGIN","AUTH_HOME");assertEquals(1L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));service.delete(c.getId());
 }
 @Test void deleteRetainsFundsAndReceiptsAndPreventsFurtherOperations(){
  ActivityCampaign c=campaign();ActivityDelivery one=send(c,user),two=send(c,other);service.claim(user,one.getId());
  service.delete(c.getId());assertTrue(service.get(c.getId()).isDeleted());assertFalse(service.get(c.getId()).active());
  money("300",funds.available(user));assertEquals(2L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));
  assertThrows(BusinessException.class,()->service.claim(other,two.getId()));assertThrows(BusinessException.class,()->service.send(c.getId(),Arrays.asList(other),"admin"));assertThrows(BusinessException.class,()->service.save(c.getId(),c));
  assertEquals(false,service.message(other,two.getId()).get("active"));
 }
 @Test void templatesRepeatUnreadSearchAndTenantIsolation(){
  ActivityCampaign c=campaign();c.setRepeatUnread(true);service.save(c.getId(),c);ActivityDelivery delivery=send(c,user);service.event(user,delivery.getId(),"CLOSED");
  Map<?,?> view=(Map<?,?>)service.message(user,delivery.getId()).get("campaign");assertEquals(true,view.get("repeatUnread"));assertNull(deliveries.findByTenantIdAndId(1L,delivery.getId()).get().getOpenedAt());
  String email=users.findByTenantIdAndId(1L,user).get().getEmail();assertEquals(user,service.searchRecipients(email).get(0).get("id"));assertTrue(service.searchRecipients(String.valueOf(user)).stream().anyMatch(x->user.equals(x.get("id"))));assertTrue(service.searchRecipients("%").isEmpty());
  tenantScope.close();try(TenantContext.Scope ignored=TenantContext.open(98765L)){assertTrue(service.searchRecipients(email).isEmpty());assertThrows(BusinessException.class,()->service.delete(c.getId()));}finally{tenantScope=TenantContext.open(1L);}
  ActivityCampaign template=campaign();template.setTemplate(true);template.setAutoSendEnabled(true);template=service.save(template.getId(),template);assertFalse(template.isAutoSendEnabled());assertEquals("DRAFT",template.getStatus());
  final ActivityCampaign t=template;assertThrows(BusinessException.class,()->service.saveAutoSend(t.getId(),t));assertThrows(BusinessException.class,()->service.delete(t.getId()));
  ActivityCampaign copy=new ActivityCampaign();copy.setName(t.getName());copy.setTranslations(t.getTranslations());ActivityCampaign saved=service.save(null,copy);assertNotEquals(t.getId(),saved.getId());assertTrue(service.get(t.getId()).isTemplate());assertFalse(saved.isTemplate());
 }
 @Test void timeBroadcastDisabledAndEventOnly(){
  ActivityCampaign c=campaign();c.setAutoSendEnabled(true);c.setTriggerConditions(Collections.singletonList("LOGIN"));service.saveAutoSend(c.getId(),c);
  UserAccount inactive=users.findByTenantIdAndId(1L,other).get();inactive.setLastLoginAt(LocalDateTime.now().minusDays(9));users.saveAndFlush(inactive);
  service.autoSendDue();service.inbox(user,0);assertEquals(0L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));service.trigger(user,"LOGIN","AUTH_HOME");assertTrue(deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),user).isPresent());
  service.trigger(other,"LOGIN","AUTH_HOME");assertFalse(deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),other).isPresent());service.trigger(user,"LOGIN","AUTH_HOME");assertEquals(1L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));money("0",funds.available(user));
  c.setAutoSendEnabled(false);service.saveAutoSend(c.getId(),c);Long later=user();service.trigger(later,"LOGIN","AUTH_HOME");assertFalse(deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),later).isPresent());
 }
 @Autowired TrialGrantRepository metadataGrants;

 @Test void defaultRepeatClaimMetadataIsFalseAndReadsRemainSafe() {
  ActivityCampaign c = campaign();
  assertFalse(c.isAllowRepeatClaim());
  c.setAllowRepeatSend(true);
  c.setPositions(Arrays.asList("ANONYMOUS_HOME", "AUTH_HOME"));
  c = service.save(c.getId(), c);
  ActivityDelivery delivery = send(c, user);
  assertRepeatClaimMetadata(c, delivery, user, false);
  assertTrue(service.get(c.getId()).isAllowRepeatSend());
  assertFalse(service.get(c.getId()).isAllowRepeatClaim());
  assertEquals(1L, deliveries.countByTenantIdAndCampaignId(1L, c.getId()));
  assertEquals(0, service.get(c.getId()).getClaimCount());
  money("0", service.get(c.getId()).getGranted());
  assertTrue(metadataGrants.findByTenantIdAndUserIdOrderByIdAsc(1L, user).isEmpty());
  ActivityDelivery unchanged = deliveries.findByTenantIdAndId(1L, delivery.getId()).get();
  assertNull(unchanged.getReceivedAt());
  assertNull(unchanged.getOpenedAt());
 }

 @Test void explicitRepeatClaimMetadataIsIndependentOfResendAndUpdatesAllViews() {
  for (boolean resend : new boolean[]{false, true}) {
   ActivityCampaign c = campaign();
   c.setAllowRepeatSend(resend);
   c.setAllowRepeatClaim(true);
   c.setPositions(Arrays.asList("ANONYMOUS_HOME", "AUTH_HOME"));
   c = service.save(c.getId(), c);
   ActivityDelivery delivery = send(c, user);
   assertRepeatClaimMetadata(c, delivery, user, true);
   assertEquals(resend, service.get(c.getId()).isAllowRepeatSend());
   c.setAllowRepeatClaim(false);
   c = service.save(c.getId(), c);
   assertRepeatClaimMetadata(c, delivery, user, false);
   assertEquals(resend, service.get(c.getId()).isAllowRepeatSend());
  }
 }

 @Test void claimedMetadataKeepsSameKeyIdempotencyNewKeyPolicyAndGrantExpiry() {
  for (boolean repeatClaim : new boolean[]{false, true}) {
   Long recipient = user();
   ActivityCampaign c = campaign();
   c.setAllowRepeatClaim(repeatClaim);
   c.setAllowRepeatSend(!repeatClaim);
   c.setClaimValidityDays(3);
   c.setPositions(Arrays.asList("ANONYMOUS_HOME", "AUTH_HOME"));
   c = service.save(c.getId(), c);
   ActivityDelivery delivery = send(c, recipient);
   service.claim(recipient, delivery.getId(), "metadata-claim-0001");
   service.claim(recipient, delivery.getId(), "metadata-claim-0001");
   money("300", funds.available(recipient));
   assertEquals(1, service.get(c.getId()).getClaimCount());
   assertRepeatClaimMetadata(c, delivery, recipient, repeatClaim);
   service.claim(recipient, delivery.getId(), "metadata-claim-0002");
   service.claim(recipient, delivery.getId(), "metadata-claim-0002");
   money(repeatClaim ? "600" : "300", funds.available(recipient));
   ActivityCampaign saved = service.get(c.getId());
   assertEquals(repeatClaim ? 2 : 1, saved.getClaimCount());
   money(repeatClaim ? "600" : "300", saved.getGranted());
   List<TrialGrant> actualGrants = metadataGrants.findByTenantIdAndUserIdOrderByIdAsc(1L, recipient);
   assertEquals(repeatClaim ? 2 : 1, actualGrants.size());
   for (TrialGrant grant : actualGrants) {
    assertEquals(grant.getClaimedAt().plusDays(3), grant.getExpiresAt());
    assertEquals(c.getId(), grant.getCampaignId());
   }
   assertNotNull(deliveries.findByTenantIdAndId(1L, delivery.getId()).get().getClaimedAt());
   assertRepeatClaimMetadata(c, delivery, recipient, repeatClaim);
   for (String account : Arrays.asList("FUND", "CONTRACT", "OPTION"))
    money("100", assets.findByTenantIdAndUserIdAndCoin(1L, recipient, account).get().getAvailable());
  }
 }

 private void assertRepeatClaimMetadata(ActivityCampaign c, ActivityDelivery delivery, Long recipient, boolean expected) {
  org.springframework.data.domain.Page<?> publicPage = (org.springframework.data.domain.Page<?>)
    new PublicActivityController(service).list("ANONYMOUS_HOME", 0);
  Map<?,?> publicView = (Map<?,?>) publicPage.getContent().stream()
    .filter(row -> c.getId().equals(((Map<?,?>) row).get("id")))
    .findFirst().orElseThrow(AssertionError::new);
  ActivityController controller = new ActivityController(service, funds, ledger);
  org.springframework.security.core.Authentication auth =
    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(recipient.toString(), null);
  org.springframework.data.domain.Page<?> inboxPage = (org.springframework.data.domain.Page<?>) controller.inbox(auth, 0);
  Map<?,?> inboxView = inboxPage.getContent().stream()
    .map(row -> (Map<?,?>) ((Map<?,?>) row).get("campaign"))
    .filter(row -> c.getId().equals(row.get("id"))).findFirst().orElseThrow(AssertionError::new);
  Map<?,?> messageView = (Map<?,?>) ((Map<?,?>) controller.message(auth, delivery.getId())).get("campaign");
  List<Map<?,?>> views = Arrays.asList(publicView, inboxView, messageView);
  Set<String> safeKeys = new HashSet<>(Arrays.asList("id", "layoutJson", "amount", "translations", "defaultLocale",
    "startsAt", "endsAt", "status", "autoPopup", "repeatUnread", "animation", "recentLoginDays",
    "claimValidityDays", "allowRepeatClaim", "positions", "hasQuota"));
  for (int i = 0; i < views.size(); i++) {
   Map<?,?> view = views.get(i);
   Set<String> expectedKeys = new HashSet<>(safeKeys);
   if (i == 0) expectedKeys.remove("recentLoginDays");
   assertEquals(expectedKeys, view.keySet(), "Campaign public-field whitelist changed");
   assertTrue(view.get("allowRepeatClaim") instanceof Boolean);
   assertEquals(Boolean.valueOf(expected), view.get("allowRepeatClaim"));
   com.fasterxml.jackson.databind.JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(view);
   assertTrue(json.path("allowRepeatClaim").isBoolean());
   assertEquals(expected, json.path("allowRepeatClaim").booleanValue());
  }
 }

 @Test void visualDesignPersistsAndIsReturnedToRecipients(){
  ActivityCampaign c=campaign();String node="{\"type\":\"text\",\"text\":\"Custom design\"}";
  c.setLayoutJson("{\"version\":1,\"locales\":{\"zh-CN\":{\"pages\":[{\"id\":\"gift\",\"nodes\":["+node+"]},{\"id\":\"detail\",\"nodes\":["+node+"]},{\"id\":\"success\",\"nodes\":["+node+"]}]}}}");
  c=service.save(c.getId(),c);ActivityDelivery delivery=send(c,user);Map<?,?> publicView=(Map<?,?>)service.message(user,delivery.getId()).get("campaign");assertEquals(c.getLayoutJson(),publicView.get("layoutJson"));assertTrue(service.get(c.getId()).getLayoutJson().contains("Custom design"));
 }
}
