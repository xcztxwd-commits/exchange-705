package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.activity.*;
import com.gtcfesk.exchange.auth.AuthService;
import com.gtcfesk.exchange.auth.dto.LoginRequest;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.trade.ContractOrderService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.*;
import org.springframework.web.client.*;
import org.springframework.data.domain.PageRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

import static org.mockito.Mockito.doAnswer;

/** Regression: real TCP/JWT/services/MySQL/Redis; barriers after order read, before the real quote/control lock. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="MARKET_ISOLATION_TEST",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContractCloseConcurrencyTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        MarketIsolationTest.properties(r);
        r.add("market.exchange.okx-url",MarketIsolationTest::base);
        r.add("market.exchange.spot-ws",()->"ws://127.0.0.1:1");
        r.add("market.exchange.futures-ws",()->"ws://127.0.0.1:1");
        r.add("market.yahoo.url",()->"ws://127.0.0.1:1");
        r.add("spring.mail.host",()->"127.0.0.1");r.add("spring.mail.port",()->"1");
    }
    @Autowired ContractOrderService service;
    @Autowired ContractOrderRepository orders;
    @Autowired AssetAccountRepository assets;
    @Autowired UserAccountRepository users;
    @Autowired KycRecordRepository identities;
    @Autowired TradingSymbolRepository symbols;
    @Autowired TrialAccountRepository trials;
    @Autowired TrialLedgerRepository ledger;
    @Autowired AuthService auth;
    @Autowired PasswordEncoder passwords;
    @SpyBean ForexQuoteMarketService quotes;
    @Autowired TrialFunds funds;
    // Disable only the nondeterministic timer. Tests invoke its actual production methods explicitly.
    @MockBean MarketOrderProcessor backgroundTimer;
    @LocalServerPort int port;
    ExecutorService executor;
    CountDownLatch arrived,release;
    final AtomicInteger holds=new AtomicInteger();
    Long userId,orderId; String token; boolean mixed;
    static BigDecimal n(String s){return new BigDecimal(s);}
    static void eq(String expected,BigDecimal actual){assertEquals(0,n(expected).compareTo(actual));}
    @BeforeEach void setup() throws Exception {
        executor=Executors.newFixedThreadPool(2);holds.set(0);release=new CountDownLatch(0);
        doAnswer(call->{
            if((Thread.currentThread().getName().startsWith("http-nio") || Thread.currentThread().getName().equals("close-race-admin")) && holds.getAndUpdate(v->v>0?v-1:0)>0) {
                System.out.println("BARRIER after order read / before freshPrice symbol="+call.getArgument(0)+" thread="+Thread.currentThread().getName());
                arrived.countDown();
                assertTrue(release.await(15,TimeUnit.SECONDS),"barrier release timeout");
            }
            return call.callRealMethod();
        }).when(quotes).freshPrice("RACEBTC");
        if(!symbols.findByTenantIdAndSymbol(1L, "RACEBTC").isPresent()) {
            TradingSymbol s=new TradingSymbol();s.setSymbol("RACEBTC");s.setAlltickSymbol("BTCUSDT");s.setName("Synthetic race fixture");
            s.setCategory("Crypto");s.setSourceCategory("Crypto");s.setMarketSource("binance");s.setBaseCurrency("BTC");s.setQuoteCurrency("USDT");
            s.setIsEnabled(true);s.setControlEnabled(true);s.setControlPriceOffset(n("5"));symbols.saveAndFlush(s);quotes.refreshSymbols();
        }
        MarketIsolationTest.until(()->quotes.freshPrice("RACEBTC")!=null && n("105").compareTo(quotes.freshPrice("RACEBTC"))==0,12000);
    }
    void seed(boolean withTrial,boolean takeProfit) {
        mixed=withTrial;
        UserAccount u=new UserAccount();u.setEmail("race-"+UUID.randomUUID()+"@example.invalid");u.setPasswordHash(passwords.encode("synthetic-race-only"));u=users.saveAndFlush(u);userId=u.getId();
        KycRecord k=new KycRecord();k.setUserId(userId);k.setRealName("Synthetic race");k.setIdNumber("TEST-ONLY");k.setStatus("APPROVED");identities.saveAndFlush(k);
        LoginRequest login=new LoginRequest();login.setAccount(u.getEmail());login.setPassword("synthetic-race-only");token=auth.login(login).getToken();
        AssetAccount a=new AssetAccount();a.setUserId(userId);a.setCoin("CONTRACT");a.setAvailable(n(mixed?"938":"898"));a.setFrozen(n(mixed?"62":"102"));assets.saveAndFlush(a);
        TrialAccount trial=new TrialAccount();trial.setUserId(userId);trial.setGranted(n(mixed?"40":"0"));trial.setFrozen(n(mixed?"40":"0"));trials.saveAndFlush(trial);
        ContractOrder o=new ContractOrder();o.setUserId(userId);o.setSymbol("RACEBTC");o.setSide("BUY");o.setType("MARKET");o.setStatus("OPEN");
        o.setQuantity(n("1"));o.setLotSize(n("10"));o.setLeverage(n("10"));o.setOpenPrice(n("100"));o.setCurrentPrice(n("100"));o.setMargin(n("100"));o.setFee(n("2"));o.setProfit(BigDecimal.ZERO);
        o.setQuoteCurrency("USDT");o.setQuoteSource("binance");o.setMarginConversionRate(BigDecimal.ONE);o.setTrialReserved(n(mixed?"40":"0"));o.setOpenTime(LocalDateTime.now());
        if(takeProfit)o.setTakeProfit(n("104"));orderId=orders.saveAndFlush(o).getId();
    }
    void arm(int threads){arrived=new CountDownLatch(threads);release=new CountDownLatch(1);holds.set(threads);}
    ResponseEntity<String> closeHttp() {
        HttpHeaders h=new HttpHeaders();h.setBearerAuth(token);h.setContentType(MediaType.APPLICATION_JSON);
        try {return new RestTemplate().exchange("http://127.0.0.1:"+port+"/api/trade/contract/order/"+orderId+"/close",HttpMethod.POST,new HttpEntity<>("{}",h),String.class);}
        catch(HttpStatusCodeException e){return ResponseEntity.status(e.getRawStatusCode()).body(e.getResponseBodyAsString());}
    }
    void initialFunds(){AssetAccount a=assets.findByTenantIdAndUserIdAndCoin(1L, userId,"CONTRACT").get();eq(mixed?"938":"898",a.getAvailable());eq(mixed?"62":"102",a.getFrozen());eq(mixed?"40":"0",trials.findByTenantIdAndId(1L, userId).get().getFrozen());assertEquals(0,ledger.findByTenantIdAndUserIdOrderByIdDesc(1L, userId,PageRequest.of(0,20)).getTotalElements());}
    void settledOnce(){
        ContractOrder o=orders.findByTenantIdAndId(1L, orderId).get();assertEquals("CLOSED",o.getStatus());eq("50",o.getProfit());eq("105",o.getClosePrice());eq("100",o.getMargin());eq("2",o.getFee());
        AssetAccount a=assets.findByTenantIdAndUserIdAndCoin(1L, userId,"CONTRACT").get();eq("1048",a.getAvailable());eq("0",a.getFrozen());
        TrialAccount t=trials.findByTenantIdAndId(1L, userId).get();eq(mixed?"40":"0",t.getAvailable());eq("0",t.getFrozen());eq(mixed?"48":"0",t.getProfits());
        assertEquals(mixed?1:0,ledger.findByTenantIdAndUserIdOrderByIdDesc(1L, userId,PageRequest.of(0,20)).getTotalElements());
    }
    @RepeatedTest(3) @Order(1) void quoteRefreshRace() throws Exception {refreshRace(false,false); }
    @Test @Order(1) void forceCheckRefreshRace() throws Exception {refreshRace(true,false); }
    void refreshRace(boolean forceCheck,boolean withTrial) throws Exception {
        seed(withTrial,false);long before=orders.findByTenantIdAndId(1L, orderId).get().getRowVersion();arm(1);
        Future<ResponseEntity<String>> closing=executor.submit(this::closeHttp);
        try {
            assertTrue(arrived.await(10,TimeUnit.SECONDS));
            if(forceCheck)service.checkAndForceCloseOrders(Collections.emptyMap());else service.checkAndAutoCloseOrders("RACEBTC",null);
            ContractOrder refreshed=orders.findByTenantIdAndId(1L, orderId).get();assertEquals("OPEN",refreshed.getStatus());assertEquals(before+1,refreshed.getRowVersion());eq("105",refreshed.getCurrentPrice());initialFunds();
        } finally {release.countDown();}
        ResponseEntity<String> result=closing.get(15,TimeUnit.SECONDS);
        assertEquals(200,result.getStatusCodeValue(),result.getBody());settledOnce();
        assertEquals(400,closeHttp().getStatusCodeValue());settledOnce();
    }
    @Test @Order(2) void duplicateManualClose() throws Exception {
        seed(true,false);arm(2);Future<ResponseEntity<String>> one=executor.submit(this::closeHttp),two=executor.submit(this::closeHttp);
        try{assertTrue(arrived.await(10,TimeUnit.SECONDS));}finally{release.countDown();}
        ResponseEntity<String> a=one.get(15,TimeUnit.SECONDS),b=two.get(15,TimeUnit.SECONDS);
        List<Integer> statuses=Arrays.asList(a.getStatusCodeValue(),b.getStatusCodeValue());Collections.sort(statuses);assertEquals(Arrays.asList(200,400),statuses);settledOnce();assertEquals(400,closeHttp().getStatusCodeValue());settledOnce();
        System.out.println("DUPLICATE PASS mixed funds: "+a.getStatusCodeValue()+" / "+b.getStatusCodeValue()+", exactly one trial ledger; responses="+a.getBody()+" | "+b.getBody());
    }
    @Test @Order(3) void autoCloseRace() throws Exception {
        seed(true,true);arm(1);Future<ResponseEntity<String>> manual=executor.submit(this::closeHttp);
        try{assertTrue(arrived.await(10,TimeUnit.SECONDS));service.checkAndAutoCloseOrders("RACEBTC",null);settledOnce();}finally{release.countDown();}
        ResponseEntity<String> rejected=manual.get(15,TimeUnit.SECONDS);assertEquals(400,rejected.getStatusCodeValue());settledOnce();assertEquals(400,closeHttp().getStatusCodeValue());settledOnce();
        System.out.println("AUTO/MANUAL PASS mixed funds: auto committed first; manual HTTP400 "+rejected.getBody()+"; exactly one settlement");
    }
    @Test @Order(4) void duplicateWithUnrelatedReserve() throws Exception {
        seed(false,false);
        ContractOrder unrelated=new ContractOrder();org.springframework.beans.BeanUtils.copyProperties(orders.findByTenantIdAndId(1L, orderId).get(),unrelated,"id","rowVersion");unrelated=orders.saveAndFlush(unrelated);
        Long otherId=unrelated.getId();AssetAccount account=assets.findByTenantIdAndUserIdAndCoin(1L, userId,"CONTRACT").get();account.setAvailable(n("796"));account.setFrozen(n("204"));assets.saveAndFlush(account);
        arm(2);Future<ResponseEntity<String>> one=executor.submit(this::closeHttp),two=executor.submit(this::closeHttp);
        try{assertTrue(arrived.await(10,TimeUnit.SECONDS));}finally{release.countDown();}
        ResponseEntity<String> a=one.get(15,TimeUnit.SECONDS),b=two.get(15,TimeUnit.SECONDS);
        List<Integer> statuses=Arrays.asList(a.getStatusCodeValue(),b.getStatusCodeValue());Collections.sort(statuses);assertEquals(Arrays.asList(200,400),statuses);
        String failure=a.getStatusCodeValue()==400?a.getBody():b.getBody();assertTrue(failure.contains("只能平仓持仓中的订单"),failure);
        for(int check=0;check<2;check++) {
            AssetAccount result=assets.findByTenantIdAndUserIdAndCoin(1L, userId,"CONTRACT").get();eq("946",result.getAvailable());eq("102",result.getFrozen());
            ContractOrder closed=orders.findByTenantIdAndId(1L, orderId).get();assertEquals("CLOSED",closed.getStatus());eq("50",closed.getProfit());
            assertEquals("OPEN",orders.findByTenantIdAndId(1L, otherId).get().getStatus());assertEquals(0,orders.findByTenantIdAndId(1L, otherId).get().getRowVersion());
            assertEquals(0,ledger.findByTenantIdAndUserIdOrderByIdDesc(1L, userId,PageRequest.of(0,20)).getTotalElements());
            if(check==0)assertEquals(400,closeHttp().getStatusCodeValue());
        }
        System.out.println("UNRELATED_RESERVE PASS: 200/400 closed-status guard, available=946 frozen=102; other OPEN order untouched; no double settlement despite sufficient frozen balance");
    }
    @Test @Order(1) void mixedFundsDisplayRefresh() throws Exception {refreshRace(false,true);}
    @Test @Order(1) void adminCloseAfterDisplayRefresh() throws Exception {
        seed(false,false);arm(1);
        Future<ContractOrder> closing=executor.submit(()->{
            Thread.currentThread().setName("close-race-admin");
            return service.adminCloseOrder(orderId,null);
        });
        try {
            assertTrue(arrived.await(10,TimeUnit.SECONDS));
            service.checkAndAutoCloseOrders("RACEBTC",null);
            assertEquals("OPEN",orders.findByTenantIdAndId(1L, orderId).get().getStatus());initialFunds();
        } finally {release.countDown();}
        assertEquals("CLOSED",closing.get(15,TimeUnit.SECONDS).getStatus());settledOnce();
    }
    @Test @Order(5) void wrongOwnerDoesNotSettle() {
        seed(false,false);
        com.gtcfesk.exchange.common.BusinessException failure=assertThrows(
            com.gtcfesk.exchange.common.BusinessException.class,
            ()->service.closeOrder(userId+100000,orderId,null));
        assertEquals("无权操作此订单",failure.getMessage());initialFunds();
        assertEquals("OPEN",orders.findByTenantIdAndId(1L, orderId).get().getStatus());
    }
    @Test @Order(6) void missingFreshQuoteDoesNotSettle() {
        seed(false,false);
        org.mockito.Mockito.doReturn(null).when(quotes).freshPrice("RACEBTC");
        assertEquals(400,closeHttp().getStatusCodeValue());initialFunds();
        assertEquals("OPEN",orders.findByTenantIdAndId(1L, orderId).get().getStatus());
    }
    @AfterEach void cleanup() throws Exception {release.countDown();holds.set(0);executor.shutdownNow();assertTrue(executor.awaitTermination(20,TimeUnit.SECONDS));}
    @AfterAll static void stop(){MarketIsolationTest.stopMock();}
}





