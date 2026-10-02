package com.gtcfesk.exchange.activity;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.trade.*;
import com.gtcfesk.exchange.trade.dto.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

abstract class ActivityFeatureFixture {
 @Autowired ActivityService service; @Autowired TrialFunds funds; @Autowired TrialGrantRepository grants;
 @Autowired ActivityCampaignRepository campaigns; @Autowired ActivityDeliveryRepository deliveries;
 @Autowired ActivitySelectionRepository selections; @Autowired ActivitySelectionMemberRepository members;
 @Autowired TrialAccountRepository trials; @Autowired UserAccountRepository users;
 @Autowired AssetAccountRepository assets; @Autowired KycRecordRepository kycs;
 @Autowired ContractOrderService contracts; @Autowired OptionOrderService options;
 @Autowired ContractOrderRepository contractOrders; @Autowired OptionOrderRepository optionOrders;
 @Autowired TradingSymbolRepository symbols; @Autowired OptionDurationRepository durations;
 @Autowired PlatformTransactionManager manager;
 ForexQuoteMarketService quotes=ActivityIntegrationTest.Config.quotes;
 TenantContext.Scope scope; Long user; String symbol; TransactionTemplate tx;
 static BigDecimal d(String s){return new BigDecimal(s);}
 static void money(String expected,BigDecimal actual){assertEquals(0,d(expected).compareTo(actual),expected+" != "+actual);}
 static <T> Callable<T> scoped(Callable<T> call){return ()->{try(TenantContext.Scope ignored=TenantContext.open(1L)){return call.call();}};}
 @BeforeEach void start(){scope=TenantContext.open(1L);tx=new TransactionTemplate(manager);reset(quotes);user=newUser();
  TradingSymbol s=new TradingSymbol();s.setSymbol("T02"+user);symbol=s.getSymbol();s.setName("Trial features");s.setBaseCurrency("BTC");s.setQuoteCurrency("USDT");s.setCategory("Crypto");s.setSourceCategory("Crypto");s.setMarketSource("binance");s.setIsEnabled(true);s.setLotSize(BigDecimal.ONE);s.setFeeMultiplier(BigDecimal.ONE);s.setMaxLeverage(d("10"));s.setQuantityUnitType("BASE_ASSET");s.setSpecVersion(1L);s.setMinOrderQuantity(d("0.01"));s.setQuantityStep(d("0.01"));s.setMinOrderNotional(BigDecimal.ONE);symbols.saveAndFlush(s);when(quotes.freshPrice(symbol)).thenReturn(d("100"));
  if(!durations.findByTenantIdAndDuration(1L,60).isPresent()){OptionDuration duration=new OptionDuration();duration.setDuration(60);duration.setLabel("60s");duration.setSortOrder(1);duration.setEnabled(true);duration.setProfitRate(d("0.8"));duration.setLossRate(BigDecimal.ONE);duration.setMinAmount(BigDecimal.ONE);duration.setMaxAmount(d("10000"));durations.saveAndFlush(duration);}
 }
 @AfterEach void stop(){ReflectionTestUtils.setField(funds,"clock",Clock.systemUTC());if(scope!=null)scope.close();}
 Long newUser(){UserAccount u=new UserAccount();u.setEmail(UUID.randomUUID()+"@activity.test");u.setPasswordHash("test-only");u.setStatus("normal");u.setLastLoginAt(LocalDateTime.now());u=users.saveAndFlush(u);for(String coin:Arrays.asList("FUND","CONTRACT","OPTION")){AssetAccount a=new AssetAccount();a.setUserId(u.getId());a.setCoin(coin);a.setAvailable(d("100"));assets.saveAndFlush(a);}return u.getId();}
 ActivityCampaign campaign(){ActivityCampaign c=new ActivityCampaign();c.setName("Feature gift");c.setStatus("ACTIVE");c.setRecentLoginDays(0);c.setClaimValidityDays(3);c.setTranslations("{\"zh-CN\":{\"title\":\"Gift\",\"body\":\"body\",\"terms\":\"terms\"}}");return service.save(null,c);}
 ActivityDelivery send(ActivityCampaign c,Long recipient){service.send(c.getId(),Collections.singletonList(recipient),"test-admin");return deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),recipient).orElseThrow(AssertionError::new);}
 void claim(ActivityCampaign c,Long recipient,String key){service.claim(recipient,send(c,recipient).getId(),key);}
 void clock(String iso){ReflectionTestUtils.setField(funds,"clock",Clock.fixed(Instant.parse(iso),ZoneOffset.UTC));}
 void approved(Long id){KycRecord k=new KycRecord();k.setUserId(id);k.setStatus("APPROVED");k.setRealName("Trial test");k.setIdNumber("Trial test "+id);kycs.saveAndFlush(k);}
 BigDecimal cash(Long id,String coin){return assets.findByTenantIdAndUserIdAndCoin(1L,id,coin).orElseThrow(AssertionError::new).getAvailable();}
 CreateContractOrderRequest contract(String type,String source){CreateContractOrderRequest r=new CreateContractOrderRequest();r.setSymbol(symbol);r.setType(type);r.setSide("BUY");r.setQuantity(BigDecimal.ONE);r.setPrice(d("100"));r.setLeverage(d("10"));r.setSpecVersion(1L);r.setQuantityUnitType("BASE_ASSET");r.setFundingSource(source);return r;}
 CreateOptionOrderRequest option(String amount,String source){CreateOptionOrderRequest r=new CreateOptionOrderRequest();r.setSymbol(symbol);r.setDuration(60);r.setDirection("UP");r.setAmount(d(amount));r.setFundingSource(source);return r;}
}

@SpringJUnitConfig(ActivityIntegrationTest.Config.class)
class TrialExpiryTest extends ActivityFeatureFixture {
 @Test void exactBoundaryRejectsNewTrialButNotCash(){
  clock("2030-01-01T00:00:00Z");ActivityCampaign c=campaign();claim(c,user,"claim-boundary-0001");approved(user);
  TrialGrant g=grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).get(0);assertEquals(LocalDateTime.of(2030,1,4,0,0),g.getExpiresAt());
  clock("2030-01-03T23:59:59Z");money("300",funds.available(user));assertTrue((Boolean)funds.status(user).get("trialEligible"));
  clock("2030-01-04T00:00:00Z");money("0",funds.available(user));money("300",funds.snapshot(user).getExpired());assertFalse((Boolean)funds.status(user).get("trialEligible"));
  assertThrows(BusinessException.class,()->contracts.createOrder(user,contract("MARKET","TRIAL")));
  ContractOrder cashOrder=contracts.createOrder(user,contract("MARKET","CONTRACT"));assertEquals("CONTRACT",cashOrder.getFundingSource());
 }
 @Test void newClaimNeverExtendsOldGrant(){
  clock("2030-01-01T00:00:00Z");ActivityCampaign first=campaign();claim(first,user,"first-grant-00001");
  clock("2030-01-03T00:00:00Z");ActivityCampaign second=campaign();claim(second,user,"second-grant-0001");money("600",funds.available(user));
  clock("2030-01-04T00:00:00Z");money("300",funds.available(user));money("300",funds.snapshot(user).getExpired());
  List<TrialGrant> rows=grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user);assertFalse(rows.get(0).isActive());assertTrue(rows.get(1).isActive());assertEquals(LocalDateTime.of(2030,1,6,0,0),rows.get(1).getExpiresAt());
 }
 @Test void expiryCancelsPendingButOpenPositionCanExit(){
  clock("2030-01-01T00:00:00Z");claim(campaign(),user,"claim-position-001");approved(user);
  ContractOrder pending=contracts.createOrder(user,contract("LIMIT","TRIAL"));ContractOrder open=contracts.createOrder(user,contract("MARKET","TRIAL"));
  clock("2030-01-04T00:00:00Z");funds.snapshot(user);
  assertEquals("CANCELLED",contractOrders.findByTenantIdAndId(1L,pending.getId()).orElseThrow(AssertionError::new).getStatus());
  assertEquals("OPEN",contractOrders.findByTenantIdAndId(1L,open.getId()).orElseThrow(AssertionError::new).getStatus());money("0",funds.available(user));
  when(quotes.freshPrice(symbol)).thenReturn(d("110"));contracts.closeOrder(user,open.getId(),null);
  money("300",funds.snapshot(user).getExpired());money("109",cash(user,"CONTRACT"));money("0",funds.snapshot(user).getFrozen());
 }
}

@SpringJUnitConfig(ActivityIntegrationTest.Config.class)
class AccountFundingSourceTest extends ActivityFeatureFixture {
 @Test void contractAndOptionNeverMixPrincipal(){
  claim(campaign(),user,"claim-sources-0001");approved(user);
  CreateContractOrderRequest tooBig=contract("MARKET","TRIAL");tooBig.setQuantity(d("30"));assertThrows(BusinessException.class,()->contracts.createOrder(user,tooBig));money("300",funds.available(user));money("100",cash(user,"CONTRACT"));
  CreateContractOrderRequest cashTooBig=contract("MARKET",null);cashTooBig.setQuantity(d("20"));assertThrows(BusinessException.class,()->contracts.createOrder(user,cashTooBig));money("300",funds.available(user));
  ContractOrder real=contracts.createOrder(user,contract("MARKET",null));assertEquals("CONTRACT",real.getFundingSource());money("300",funds.available(user));money("89",cash(user,"CONTRACT"));
  OptionOrder promo=options.createOrder(user,option("250","TRIAL"));assertEquals("TRIAL",promo.getFundingSource());money("50",funds.available(user));money("100",cash(user,"OPTION"));
  assertThrows(BusinessException.class,()->options.createOrder(user,option("100","TRIAL")));money("50",funds.available(user));money("100",cash(user,"OPTION"));
 }
 @Test void liquidationGroupsTrialAndCashIndependently(){
  claim(campaign(),user,"claim-risk-groups-01");approved(user);
  ContractOrder promo=contracts.createOrder(user,contract("MARKET","TRIAL"));
  ContractOrder real=contracts.createOrder(user,contract("MARKET","CONTRACT"));
  when(quotes.freshPrices()).thenReturn(new HashMap<>());when(quotes.freshPrice(symbol)).thenReturn(d("1"));
  contracts.checkAndForceCloseOrders(Collections.emptyMap());
  assertEquals("OPEN",contractOrders.findByTenantIdAndId(1L,promo.getId()).orElseThrow(AssertionError::new).getStatus());
  assertEquals("CLOSED",contractOrders.findByTenantIdAndId(1L,real.getId()).orElseThrow(AssertionError::new).getStatus());
  money("289",funds.available(user));money("11",funds.snapshot(user).getFrozen());money("0",cash(user,"CONTRACT"));
 }
 @Test void legacyMixedOrderUsesRecordedSplit(){
  claim(campaign(),user,"claim-legacy-0001");approved(user);
  tx.executeWithoutResult(status->{funds.lock(user);AssetAccount a=assets.findByTenantIdAndUserIdAndCoin(1L,user,"CONTRACT").orElseThrow(AssertionError::new);
   funds.reserve(user,a,d("200"),"TRIAL","CONTRACT","LEGACY_TEST");funds.reserve(user,a,d("100"),"CONTRACT","CONTRACT","LEGACY_TEST");
   funds.settle(user,a,d("300"),d("200"),null,null,d("-50"),"LEGACY_SETTLE_TEST");});
  money("250",funds.available(user));money("100",cash(user,"CONTRACT"));money("50",funds.snapshot(user).getConsumed());money("0",funds.snapshot(user).getFrozen());
 }
}

@SpringJUnitConfig(ActivityIntegrationTest.Config.class)
class ActivityConditionDeliveryTest extends ActivityFeatureFixture {
 @Test void businessEventIsRequiredAndReadsNeverSend(){
  ActivityCampaign c=campaign();c.setAutoSendEnabled(true);c.setTriggerConditions(Collections.singletonList("API_CONTRACT_ORDER"));c.setPositions(Collections.singletonList("AUTH_TRADE"));service.saveAutoSend(c.getId(),c);approved(user);
  service.inbox(user,0);service.autoSendDue();assertEquals(0L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));
  assertThrows(BusinessException.class,()->service.trigger(user,"FAKE_EVENT","AUTH_TRADE"));service.trigger(user,"LOGIN","AUTH_HOME");assertFalse(deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),user).isPresent());
  contracts.createOrder(user,contract("MARKET","CONTRACT"));assertEquals(1L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));
  service.inbox(user,0);assertEquals(1L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));
 }
 @Test void anonymousListShowsOnlyEligiblePublicCampaigns(){
  ActivityCampaign privateCampaign=campaign();ActivityCampaign publicCampaign=campaign();publicCampaign.setPositions(Collections.singletonList("ANONYMOUS_HOME"));publicCampaign.setBudget(d("300"));publicCampaign.setMaxClaims(1);service.save(publicCampaign.getId(),publicCampaign);
  assertTrue(service.publicActivities("ANONYMOUS_HOME",0).getContent().stream().anyMatch(row->publicCampaign.getId().equals(row.get("id"))));
  assertFalse(service.publicActivities("ANONYMOUS_HOME",0).getContent().stream().anyMatch(row->privateCampaign.getId().equals(row.get("id"))));
  service.claimPublic(user,publicCampaign.getId(),"public-claim-0001");
  assertFalse(service.publicActivities("ANONYMOUS_HOME",0).getContent().stream().anyMatch(row->publicCampaign.getId().equals(row.get("id"))));
 }
 @Test void triggerRespectsWindowAndTenant(){
  ActivityCampaign c=campaign();c.setAutoSendEnabled(true);c.setTriggerConditions(Collections.singletonList("LOGIN"));c.setStartsAt(LocalDateTime.now().plusDays(1));service.saveAutoSend(c.getId(),c);
  assertEquals(0,service.trigger(user,"LOGIN","AUTH_HOME"));c.setStartsAt(null);service.saveAutoSend(c.getId(),c);
  scope.close();scope=null;try(TenantContext.Scope other=TenantContext.open(2L)){assertThrows(BusinessException.class,()->service.trigger(user,"LOGIN","AUTH_HOME"));}finally{scope=TenantContext.open(1L);}
  assertEquals(1,service.trigger(user,"LOGIN","AUTH_HOME"));assertEquals(0,service.trigger(user,"LOGIN","AUTH_HOME"));
 }
}

@SpringJUnitConfig(ActivityIntegrationTest.Config.class)
class ActivityRepeatClaimTest extends ActivityFeatureFixture {
 @Test void repeatClaimIsSeparateFromRepeatSendAndKeysAreStable(){
  ActivityCampaign c=campaign();c.setAllowRepeatSend(true);service.save(c.getId(),c);ActivityDelivery d=send(c,user);
  service.claim(user,d.getId(),"claim-repeat-00001");service.claim(user,d.getId(),"claim-repeat-00002");money("300",funds.available(user));assertEquals(1,campaigns.findByTenantIdAndId(1L,c.getId()).orElseThrow(AssertionError::new).getClaimCount());
  c.setAllowRepeatClaim(true);service.save(c.getId(),c);service.claim(user,d.getId(),"claim-repeat-00002");money("600",funds.available(user));
  service.claim(user,d.getId(),"claim-repeat-00002");money("600",funds.available(user));assertEquals(2,campaigns.findByTenantIdAndId(1L,c.getId()).orElseThrow(AssertionError::new).getClaimCount());
  assertEquals(2,grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).size());
 }
 @Test void explicitSendOperationDoesNotRepeatEvenWhenResendAllowed(){
  ActivityCampaign c=campaign();c.setAllowRepeatSend(true);service.save(c.getId(),c);String key="send-repeat-op-0001";
  assertEquals(1,service.send(c.getId(),Collections.singletonList(user),"admin",key).get("sent"));
  LocalDateTime first=deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),user).orElseThrow(AssertionError::new).getSentAt();
  assertEquals(1,service.send(c.getId(),Collections.singletonList(user),"admin",key).get("sent"));
  assertEquals(first,deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),user).orElseThrow(AssertionError::new).getSentAt());
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.send(c.getId(),Collections.singletonList(newUser()),"admin",key));
  assertEquals(0,campaigns.findByTenantIdAndId(1L,c.getId()).orElseThrow(AssertionError::new).getClaimCount());
 }
 @Test void retryAfterCampaignEndsReturnsOriginalGrantOnly(){
  ActivityCampaign c=campaign();c.setPositions(Collections.singletonList("ANONYMOUS_HOME"));service.save(c.getId(),c);
  String key="public-replay-0001";service.claimPublic(user,c.getId(),key);money("300",funds.available(user));
  c.setEndsAt(LocalDateTime.now().minusSeconds(1));service.save(c.getId(),c);
  service.claimPublic(user,c.getId(),key);money("300",funds.available(user));
  assertThrows(BusinessException.class,()->service.claimPublic(user,c.getId(),"public-new-000001"));
 }
 @Test void concurrentExplicitSendKeyDoesNotResend() throws Exception {
  ActivityCampaign c=campaign();c.setAllowRepeatSend(true);service.save(c.getId(),c);ActivityDelivery original=send(c,user);long version=original.getRowVersion();String key="send-concurrent-0001";
  ExecutorService pool=Executors.newFixedThreadPool(2);try{
   Callable<Map<String,Object>> call=()->service.send(c.getId(),Collections.singletonList(user),"admin",key);
   Future<Map<String,Object>> first=pool.submit(scoped(call)),second=pool.submit(scoped(call));
   assertEquals(1,first.get(20,TimeUnit.SECONDS).get("sent"));assertEquals(1,second.get(20,TimeUnit.SECONDS).get("sent"));
  }finally{pool.shutdownNow();}
  ActivityDelivery after=deliveries.findByTenantIdAndCampaignIdAndUserId(1L,c.getId(),user).orElseThrow(AssertionError::new);
  assertEquals(version+1,after.getRowVersion());assertEquals(1L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));
 }
 @Test void concurrentSameKeyCreditsOnceAndBudgetLocks() throws Exception {
  ActivityCampaign c=campaign();c.setAllowRepeatClaim(true);c.setBudget(d("300"));service.save(c.getId(),c);ActivityDelivery d=send(c,user);
  ExecutorService pool=Executors.newFixedThreadPool(2);try{
   Callable<Boolean> action=()->{service.claim(user,d.getId(),"concurrent-key-0001");return true;};
   Future<Boolean> first=pool.submit(scoped(action)),second=pool.submit(scoped(action));assertTrue(first.get(20,TimeUnit.SECONDS));assertTrue(second.get(20,TimeUnit.SECONDS));
  }finally{pool.shutdownNow();}
  money("300",funds.available(user));assertEquals(1,grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).size());
  assertThrows(BusinessException.class,()->service.claim(user,d.getId(),"another-key-00001"));money("300",funds.available(user));
 }
}

@SpringJUnitConfig(ActivityIntegrationTest.Config.class)
class ActivityRecipientFilterTest extends ActivityFeatureFixture {
 @Test void filtersIncludeAndExcludeClaimedCampaign(){
  Long second=newUser();ActivityCampaign c=campaign();claim(c,user,"claimed-filter-0001");RecipientFilter f=new RecipientFilter();f.setClaimedCampaignIds(Collections.singletonList(c.getId()));
  f.setClaimedMode("INCLUDE");assertEquals(1L,service.searchRecipients(f).getTotalElements());
  f.setClaimedMode("EXCLUDE");assertTrue(service.searchRecipients(f).getContent().stream().noneMatch(r->user.equals(r.get("id"))));
  f.setLastLoginFrom(LocalDateTime.now().plusDays(1));assertEquals(0L,service.searchRecipients(f).getTotalElements());
  f.setLastLoginFrom(null);f.setCreatedFrom(LocalDateTime.now().plusDays(1));assertEquals(0L,service.searchRecipients(f).getTotalElements());
  assertNotEquals(user,second);
 }
 @Test void contentPatchPreservesDeliverySettingsAndRejectsStaleVersion(){
  ActivityCampaign c=campaign();c.setAutoSendEnabled(true);c.setAllowRepeatSend(true);c.setAllowRepeatClaim(true);c.setBudget(d("9000"));c.setPositions(Collections.singletonList("AUTH_TRADE"));c.setTriggerConditions(Collections.singletonList("LOGIN"));c=service.save(c.getId(),c);
  long version=c.getRowVersion();ActivityContentPatch patch=new ActivityContentPatch();patch.setRowVersion(version);patch.setName("New content");patch.setTranslations(c.getTranslations());patch.setDefaultLocale(c.getDefaultLocale());patch.setLayoutJson(c.getLayoutJson());
  ActivityCampaign saved=service.saveContent(c.getId(),patch);assertEquals("New content",saved.getName());money("9000",saved.getBudget());assertTrue(saved.isAutoSendEnabled());assertTrue(saved.isAllowRepeatSend());assertTrue(saved.isAllowRepeatClaim());assertEquals(Collections.singletonList("AUTH_TRADE"),saved.getPositions());assertEquals(Collections.singletonList("LOGIN"),saved.getTriggerConditions());
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.saveContent(saved.getId(),patch));
 }
 @Test void explicitInitialSelectionIsStableAndRejectsOtherUsers(){
  ActivityCampaign c=campaign();Long second=newUser();String key="selection-explicit-0001";
  Map<String,Object> first=service.selectAll(c.getId(),key,null,Arrays.asList(user,second,user));
  Long id=((Number)first.get("selectionId")).longValue();assertEquals(2L,first.get("selected"));
  assertEquals(id,((Number)service.selectAll(c.getId(),key,null,Arrays.asList(second,user)).get("selectionId")).longValue());
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.selectAll(c.getId(),key,null,Collections.singletonList(user)));
  assertThrows(BusinessException.class,()->service.selectAll(c.getId(),"selection-empty-0001",null,null));
  Long alien;scope.close();scope=null;try(TenantContext.Scope other=TenantContext.open(2L)){UserAccount u=new UserAccount();u.setEmail(UUID.randomUUID()+"@activity.test");u.setPasswordHash("test");u.setStatus("normal");alien=users.saveAndFlush(u).getId();}finally{scope=TenantContext.open(1L);}
  assertThrows(BusinessException.class,()->service.selectAll(c.getId(),"selection-alien-0001",null,Collections.singletonList(alien)));
  assertEquals(2L,service.selectionMembers(c.getId(),id,0,20).getTotalElements());
 }
 @Test void deliverySettingsPersistWithoutOverwritingContent(){
  ActivityCampaign c=campaign();String originalName=c.getName(),originalTranslations=c.getTranslations();
  ActivityCampaign settings=new ActivityCampaign();settings.setStatus("PAUSED");settings.setAnimation("NONE");settings.setAmount(d("400"));settings.setBudget(d("9000"));settings.setAutoPopup(false);settings.setRepeatUnread(true);settings.setAutoSendEnabled(true);
  settings.setPositions(Collections.singletonList("AUTH_TRADE"));settings.setTriggerConditions(Collections.singletonList("LOGIN"));
  ActivityCampaign saved=service.saveAutoSend(c.getId(),settings);assertEquals("PAUSED",saved.getStatus());money("400",saved.getAmount());money("9000",saved.getBudget());assertFalse(saved.isAutoPopup());assertTrue(saved.isRepeatUnread());assertTrue(saved.isAutoSendEnabled());assertEquals("NONE",saved.getAnimation());assertEquals(originalName,saved.getName());assertEquals(originalTranslations,saved.getTranslations());
  settings.setStatus("ACTIVE");service.saveAutoSend(c.getId(),settings);send(saved,user);
  settings.setAmount(d("500"));assertThrows(BusinessException.class,()->service.saveAutoSend(c.getId(),settings));
  settings.setAmount(d("400"));settings.setAnimation("BAD");assertThrows(BusinessException.class,()->service.saveAutoSend(c.getId(),settings));
  settings.setAnimation("NONE");settings.setBudget(d("399"));assertThrows(BusinessException.class,()->service.saveAutoSend(c.getId(),settings));
 }
 @Test void largeSelectionCanReviewUnionRemoveResumeAndNotResend() {
  ActivityCampaign c=campaign();c.setAllowRepeatSend(true);service.save(c.getId(),c);
  List<UserAccount> batch=new ArrayList<>();for(int i=0;i<1205;i++){UserAccount u=new UserAccount();u.setEmail("bulk-"+i+"-"+UUID.randomUUID()+"@activity.test");u.setPasswordHash("test-only");u.setStatus("normal");u.setLastLoginAt(LocalDateTime.now());batch.add(u);}users.saveAll(batch);users.flush();
  RecipientFilter all=new RecipientFilter();all.setQuery("bulk-");String selectionKey="selection-large-0001";
  Map<String,Object> selected=service.selectAll(c.getId(),selectionKey,all);Long id=((Number)selected.get("selectionId")).longValue();assertEquals(1205L,selected.get("selected"));
  assertEquals(id,((Number)service.selectAll(c.getId(),selectionKey,all).get("selectionId")).longValue());assertEquals(1205L,service.selectionMembers(c.getId(),id,0,100).getTotalElements());
  RecipientFilter overlap=new RecipientFilter();overlap.setQuery("bulk-1");Map<String,Object> union=service.appendSelection(c.getId(),id,Arrays.asList(batch.get(0).getId(),batch.get(1).getId()),overlap);assertEquals(0L,union.get("added"));
  List<UserAccount> later=new ArrayList<>();for(int i=0;i<15;i++){UserAccount u=new UserAccount();u.setEmail("later-"+i+"-"+UUID.randomUUID()+"@activity.test");u.setPasswordHash("test-only");u.setStatus("normal");u.setLastLoginAt(LocalDateTime.now());later.add(u);}users.saveAll(later);users.flush();
  RecipientFilter fresh=new RecipientFilter();fresh.setQuery("later-");assertEquals(15L,service.appendSelection(c.getId(),id,null,fresh).get("added"));
  Long extra=newUser();Map<String,Object> appended=service.appendSelection(c.getId(),id,Arrays.asList(extra,batch.get(0).getId()),null);assertEquals(1L,appended.get("added"));
  Map<String,Object> removed=service.removeSelection(c.getId(),id,Arrays.asList(batch.get(0).getId(),batch.get(1).getId()));assertEquals(2L,removed.get("removed"));assertEquals(1219L,removed.get("selected"));
  String sendKey="send-selection-0001";Map<String,Object> progress=null;for(int i=0;i<4;i++){progress=service.sendSelection(c.getId(),id,sendKey,"admin");if(Boolean.TRUE.equals(progress.get("done")))break;}
  assertNotNull(progress);assertEquals(true,progress.get("done"));assertEquals(1219L,progress.get("sent"));assertEquals(0L,progress.get("duplicates"));assertEquals(1219L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));
  Map<String,Object> retry=service.sendSelection(c.getId(),id,sendKey,"admin");assertEquals(1219L,retry.get("sent"));assertEquals(1219L,deliveries.countByTenantIdAndCampaignId(1L,c.getId()));
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.appendSelection(c.getId(),id,Collections.singletonList(user),null));
 }
}
