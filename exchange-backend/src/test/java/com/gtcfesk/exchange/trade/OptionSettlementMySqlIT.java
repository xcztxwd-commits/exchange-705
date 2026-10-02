package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.ExchangeBackendApplication;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Current migrated MySQL and real services. Only quote INPUTS and initial test funds are synthetic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {ExchangeBackendApplication.class, OptionSettlementMySqlIT.Inputs.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OptionSettlementMySqlIT {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        DriverManagerDataSource verified = DedicatedMysqlFixture.fromProperty("stage2.mysql.fixture");
        Path privateDirectory = Paths.get(System.getProperty("stage2.mysql.fixture")).getParent();
        Properties configuration = new Properties();
        try (java.io.Reader input = Files.newBufferedReader(privateDirectory.resolve("application.properties"))) {
            configuration.load(input);
        }
        configuration.forEach((key, value) -> registry.add(key.toString(), () -> value));
        registry.add("spring.datasource.url", verified::getUrl);
        registry.add("spring.datasource.username", verified::getUsername);
        registry.add("spring.datasource.password", verified::getPassword);
        registry.add("server.port", () -> "0");
        registry.add("platform.bootstrap.enabled", () -> "false");
        registry.add("spring.jmx.enabled", () -> "false");
        registry.add("logging.level.root", () -> "WARN");
        registry.add("logging.level.org.springframework.boot.autoconfigure.security", () -> "ERROR");
    }

    @TestConfiguration static class Inputs {
        @Bean @Primary QuoteInput settlementQuoteInput() { return new QuoteInput(); }
    }
    static class QuoteInput extends ForexQuoteMarketService {
        @Autowired com.gtcfesk.exchange.market.PersistentPriceControl actualControls;
        @Autowired TradingSymbolRepository actualSymbols;
        @Autowired JdbcTemplate actualDatabase;
        @Override public void start() { }
        @Override public void stop() { }
        @Override public void requestSymbolRefresh() { }
        @Override public synchronized void refreshSymbols() { }
        @Override public BigDecimal freshPrice(String symbol) {
            if(symbol.startsWith("OWNED_LOCK_")) {
                TradingSymbol config=actualSymbols.findByTenantIdAndSymbol(TenantContext.requireTenantId(),symbol).get();
                Map<String,Object> input=new HashMap<>();input.put("price",new BigDecimal("110"));input.put("available",true);input.put("timestamp",System.currentTimeMillis());input.put("fetchedAt",System.currentTimeMillis());
                actualDatabase.execute("SET SESSION innodb_lock_wait_timeout=2");
                Map<String,Object> actual=actualControls.display(config,input,System.currentTimeMillis());
                return new BigDecimal(actual.get("price").toString());
            }
            return new BigDecimal("110");
        }
        @Override public Map<String, BigDecimal> freshPrices() {
            Map<String,BigDecimal> result=new HashMap<>();
            for(String symbol:actualDatabase.queryForList("SELECT symbol FROM trading_symbol WHERE tenant_id=? AND symbol LIKE 'OWNED_LOCK_%'",String.class,TenantContext.requireTenantId()))result.put(symbol,freshPrice(symbol));
            return result;
        }
    }

    @Autowired TradingSymbolRepository symbols;
    @Autowired OptionOrderService service;
    @Autowired OptionOrderRepository orders;
    @Autowired UserAccountRepository users;
    @Autowired AssetAccountRepository assets;
    @Autowired JdbcTemplate database;
    @Autowired TenantJobRunner jobs;
    @Autowired com.gtcfesk.exchange.market.MarketOrderProcessor marketTasks;
    TenantContext.Scope scope;

    @BeforeEach void tenant() {
        marketTasks.stop(); // Run the real task deliberately, not concurrently with fixture initialization.
        scope = TenantContext.open(1L);
        assertTrue(database.queryForObject("SELECT VERSION()", String.class).startsWith("5.7."));
        assertTrue(database.queryForObject("SELECT DATABASE()", String.class).startsWith("mt705_probe_"));
        assertFalse(org.mockito.Mockito.mockingDetails(service).isMock());
    }
    @AfterEach void clear() { if (scope != null) scope.close(); TenantContext.clear(); }

    long user() {
        UserAccount user = new UserAccount();
        user.setEmail("stage2-settlement-" + UUID.randomUUID() + "@example.invalid");
        user.setPasswordHash("not-a-login");
        return users.saveAndFlush(user).getId();
    }
    AssetAccount account(long user, String frozen) {
        AssetAccount account = new AssetAccount();
        account.setUserId(user); account.setCoin("OPTION");
        account.setAvailable(new BigDecimal("90")); account.setFrozen(new BigDecimal(frozen));
        return assets.saveAndFlush(account);
    }
    OptionOrder order(long user) {
        OptionOrder order = new OptionOrder();
        order.setUserId(user); order.setSymbol("STAGE2USD"); order.setDirection("UP");
        order.setAmount(BigDecimal.TEN); order.setOpenPrice(new BigDecimal("100"));
        // This synthetic legacy snapshot deliberately exercises the documented fallback rates.
        assertEquals(0L, database.queryForObject("SELECT COUNT(*) FROM option_duration WHERE tenant_id=? AND duration=99997", Long.class, TenantContext.requireTenantId()));
        order.setDuration(99997); order.setOpenTime(LocalDateTime.now().minusSeconds(100000));
        order.setFundingSource("OPTION"); order.setTrialReserved(BigDecimal.ZERO); order.setStatus("TRADING");
        return orders.saveAndFlush(order);
    }
    String state(long id) { return orders.findByTenantIdAndId(TenantContext.requireTenantId(), id).get().getStatus(); }
    void money(long user, String available, String frozen) {
        AssetAccount account = assets.findByTenantIdAndUserIdAndCoin(TenantContext.requireTenantId(), user, "OPTION").get();
        assertEquals(0, new BigDecimal(available).compareTo(account.getAvailable()));
        assertEquals(0, new BigDecimal(frozen).compareTo(account.getFrozen()));
    }
    void tick() { service.settleExpiredOrders(Collections.emptyMap()); }

    @Test void missingAccountRollsBackOrderAndSiblingStillSettlesThenRetryIsSingle() {
        long broken = user(), good = user(); account(good, "10");
        OptionOrder failure = order(broken), success = order(good);
        tick();
        assertEquals("TRADING", state(failure.getId()));
        assertEquals("CLOSED", state(success.getId())); money(good, "108", "0");
        account(broken, "10"); tick(); tick();
        assertEquals("CLOSED", state(failure.getId())); money(broken, "108", "0"); money(good, "108", "0");
    }

    @Test void corruptHistoricalAllocationDoesNotCommitCashOrderOrTrialCreation() {
        long broken = user(); account(broken, "5"); OptionOrder order = order(broken);
        order.setFundingSource(null); order.setTrialReserved(new BigDecimal("5"));
        order.setTrialAllocations("invalid-allocation"); orders.saveAndFlush(order);
        tick(); tick();
        assertEquals("TRADING", state(order.getId())); money(broken, "90", "5");
        assertEquals(0L, database.queryForObject("SELECT COUNT(*) FROM trial_account WHERE tenant_id=1 AND user_id=?", Long.class, broken));
        assertEquals(0L, database.queryForObject("SELECT COUNT(*) FROM trial_ledger WHERE tenant_id=1 AND user_id=?", Long.class, broken));
    }

    @Test void realSqlFailureAfterWalletMutationRollsBackAndDoesNotBlockSibling() {
        long broken = user(), good = user(); account(broken, "10"); account(good, "10");
        OptionOrder failure = order(broken), success = order(good);
        String trigger = "stage2_option_" + failure.getId();
        database.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON option_order FOR EACH ROW BEGIN IF NEW.id="
                + failure.getId() + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='stage2 controlled settlement failure'; END IF; END");
        try { tick(); assertEquals("TRADING", state(failure.getId())); money(broken, "90", "10");
            assertEquals("CLOSED", state(success.getId())); money(good, "108", "0");
        } finally { database.execute("DROP TRIGGER " + trigger); }
        tick(); assertEquals("CLOSED", state(failure.getId())); money(broken, "108", "0");
    }

    @Test void sameOrderAndSameAccountDifferentOrdersCannotDoubleCreditOrLoseUpdates() throws Exception {
        long user = user(); account(user, "20"); OptionOrder first = order(user), second = order(user);
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        Callable<Void> settle = () -> { try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            start.await(); tick(); return null;
        } finally { assertNull(TenantContext.currentTenantId()); } };
        try {
            Future<Void> a = executor.submit(settle), b = executor.submit(settle); start.countDown();
            a.get(30, TimeUnit.SECONDS); b.get(30, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertEquals("CLOSED", state(first.getId())); assertEquals("CLOSED", state(second.getId()));
        money(user, "126", "0");
        tick(); money(user, "126", "0"); // A separate replay check cannot repair the concurrency assertions above.
    }

    @Test void tenantFailureIsolationAndRetryKeepOwnersAndClearContext() {
        long a = user(); OptionOrder failed = order(a);
        scope.close(); scope = null;
        long b; OptionOrder good;
        try (TenantContext.Scope ignored = TenantContext.open(2L)) { b = user(); account(b, "10"); good = order(b); }
        jobs.each("stage2-option-settle", tenant -> tick());
        assertNull(TenantContext.currentTenantId());
        try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            assertEquals("TRADING", state(failed.getId()));
            assertFalse(orders.findByTenantIdAndId(1L, good.getId()).isPresent()); account(a, "10");
        }
        try (TenantContext.Scope ignored = TenantContext.open(2L)) { assertEquals("CLOSED", state(good.getId())); money(b, "108", "0"); }
        jobs.each("stage2-option-restart", tenant -> tick()); jobs.each("stage2-option-replay", tenant -> tick());
        assertNull(TenantContext.currentTenantId());
        try (TenantContext.Scope ignored = TenantContext.open(1L)) { money(a, "108", "0"); }
        try (TenantContext.Scope ignored = TenantContext.open(2L)) { money(b, "108", "0"); }
    }
    @Test void actualScheduledEntrySettlesWithDurableDisplaySymbolLocksWithoutOuterSelfBlocking(){
        long user=user();account(user,"10");TradingSymbol symbol=new TradingSymbol();symbol.setSymbol("OWNED_LOCK_"+UUID.randomUUID().toString().substring(0,8));symbol.setName("Owned durable quote-lock input");symbol.setBaseCurrency("BTC");symbol.setQuoteCurrency("USD");symbol.setCategory("Crypto");symbol.setSourceCategory("Crypto");symbol.setMarketSource("binance");symbol.setIsEnabled(true);symbol=symbols.saveAndFlush(symbol);
        OptionOrder option=order(user);option.setSymbol(symbol.getSymbol());orders.saveAndFlush(option);scope.close();scope=null;
        marketTasks.settleOptions();marketTasks.settleOptions();assertNull(TenantContext.currentTenantId());
        try(TenantContext.Scope ignored=TenantContext.open(1L)){assertEquals("CLOSED",state(option.getId()));money(user,"108","0");}
    }

}
