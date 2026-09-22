package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.ContractOrderRepository;
import com.gtcfesk.exchange.trade.ContractOrderService;
import com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersistentPriceControlTest {
    @Test void sharedSourceAliasesRollbackTogether() {
        TradingSymbol missing = new TradingSymbol(); missing.setId(2L); missing.setSymbol("MISSING");
        long now = System.currentTimeMillis();
        assertThrows(Exception.class, () -> controls.sourceQuotes(new ArrayList<>(Arrays.asList(symbol, missing)), raw(now, true), now));
        assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM market_source_event", Integer.class));
        assertEquals(0, store.db.queryForObject("SELECT COUNT(*) FROM market_source_quote", Integer.class));
    }
    @Test void sameSourceTimestampEventsKeepExtremaAndRetryIdentity() {
        long now = System.currentTimeMillis() / 60000 * 60000 + 1000;
        Map<String,Object> first = raw(now, true); first.put("eventId", "stream-first");
        Map<String,Object> second = raw(now, true); second.put("eventId", "stream-second"); second.put("price", 110);
        controls.sourceQuote(symbol, first, now);
        controls.sourceQuote(symbol, second, now);
        controls.sourceQuote(symbol, second, now + 1);
        assertEquals(2, store.db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE symbol_id=1", Integer.class));
        assertEquals(1, store.db.queryForObject("SELECT COUNT(*) FROM market_source_tick WHERE symbol_id=1", Integer.class));
        // An older installation may have only the legacy first tick at this source time.
        store.db.update("DELETE FROM market_source_event WHERE event_id='stream-first'");
        store.locked(1, () -> { store.freeze(1, now + 2); return null; });
        Map<String,Object> minute = store.mixed(1, now / 60000 * 60000, now).get(0);
        assertEquals(0, new BigDecimal("110").compareTo(ControlHistoryStore.number(minute.get("high_price"))));
        assertEquals(0, new BigDecimal("90").compareTo(ControlHistoryStore.number(minute.get("low_price"))));
        assertEquals(0, new BigDecimal("110").compareTo(ControlHistoryStore.number(minute.get("close_price"))));
    }
    ControlHistoryStore store;
    PersistentPriceControl controls;
    ControlledKlineMerger merger;
    TradingSymbol symbol;
    @BeforeEach void setup() {
        DriverManagerDataSource data = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        store = new ControlHistoryStore(new JdbcTemplate(data), new DataSourceTransactionManager(data));
        store.db.execute("CREATE TABLE trading_symbol(id BIGINT PRIMARY KEY)"); store.db.update("INSERT INTO trading_symbol VALUES(1)");
        store.migrate(); controls = new PersistentPriceControl(store); merger = new ControlledKlineMerger(store);
        symbol = new TradingSymbol(); symbol.setId(1L); symbol.setSymbol("TEST"); symbol.setPricePrecision(2);
    }
    Map<String,Object> raw(long time, boolean available) {
        Map<String,Object> q = new HashMap<>(); q.put("price", 90); q.put("timestamp", time); q.put("sourceTimestamp", time);
        q.put("available", available); q.put("status", available ? "available" : "unavailable"); return q;
    }
    Map<String,Object> bar(long time, double open, double high, double low, double close) {
        Map<String,Object> row = new LinkedHashMap<>(); row.put("timestamp", time); row.put("open_price", open);
        row.put("high_price", high); row.put("low_price", low); row.put("close_price", close); row.put("volume", 5); return row;
    }
    void seed(long time) { store.sourceCandles(1, "1m", Collections.singletonList(bar(time, 80, 92, 79, 91)), time + 60000); }
    PersistentPriceControl.Task legacy(long start, int seconds) {
        symbol.setControlEnabled(true); symbol.setControlStartedAt(start); symbol.setControlStartPrice(BigDecimal.valueOf(90));
        symbol.setControlTargetPrice(BigDecimal.valueOf(100)); symbol.setControlDurationSeconds(seconds); symbol.setControlIntensity(1);
        symbol.setControlRandomOscillation(false); controls.importLegacy(symbol); return controls.latest(1);
    }
    long count(String table) { return store.db.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    @Test void outageStartPrefersCompletedCloseThenQuoteAndRejectsEmptyHistory() {
        assertThrows(BusinessException.class, () -> controls.start(symbol, Collections.emptyMap(), null, 10, BigDecimal.TEN, 1, false, false, null));
        long now = System.currentTimeMillis(); seed(now / 60000 * 60000 - 60000);
        PersistentPriceControl.Task task = controls.start(symbol, raw(now-90000, false), null, 10, BigDecimal.valueOf(110), 1, false, false, "request-1");
        assertEquals("COMPLETED_CANDLE", task.startSource); assertEquals(0, task.startPrice.compareTo(BigDecimal.valueOf(91)));
        assertEquals(task.id, controls.start(symbol, raw(now, false), null, 10, BigDecimal.valueOf(110), 1, false, false, "request-1").id);
        assertEquals(1, count("market_control_task")); assertEquals(1, count("market_control_sample"));
        assertThrows(BusinessException.class, () -> controls.start(symbol, raw(now, false), null, 10, BigDecimal.TEN, 1, false, false, "different"));
        store.db.update("DELETE FROM market_source_candle");
        assertEquals("LAST_VALID_QUOTE", controls.startBasis(symbol, raw(now-90000, false), null, now).get("source"));
    }
    @Test void realtimeStartUsesDisplayedPriceAndOriginalTimestamp() {
        long now = System.currentTimeMillis();
        PersistentPriceControl.Task task = controls.start(symbol, raw(now-1000, true), BigDecimal.valueOf(95), 10, BigDecimal.valueOf(110), 3, true, false, null);
        assertEquals("LIVE_DISPLAY", task.startSource); assertEquals(now-1000, task.sourceTime);
        assertEquals(0, task.startPrice.compareTo(BigDecimal.valueOf(95))); assertTrue(task.startedAt >= now);
    }
    @Test void restartBackfillIncludesEndpointAndCrossesOnlyTwoMinutes() {
        long start = 1700000035000L; // five seconds before a minute boundary
        PersistentPriceControl.Task task = legacy(start, 10);
        controls.advance(1, start+4000); assertEquals(5, count("market_control_sample"));
        PersistentPriceControl restarted = new PersistentPriceControl(store);
        restarted.advance(1, start+120000);
        task = restarted.latest(1); assertEquals("COMPLETED", task.status); assertEquals(start+10000, task.endedAt);
        assertEquals(11, count("market_control_sample")); assertEquals(2, count("market_mixed_minute"));
        assertEquals(0, store.db.queryForObject("SELECT price FROM market_control_sample WHERE generated_at=?", BigDecimal.class, start+10000).compareTo(BigDecimal.valueOf(100)));
        List<Map<String,Object>> before = store.mixed(1, 0, Long.MAX_VALUE);
        restarted.advance(1, start+999999); assertEquals(before, store.mixed(1, 0, Long.MAX_VALUE));
        Map<String,Object> waiting = restarted.display(symbol, raw(start-1000, false), start+120000);
        assertEquals("WAITING_SOURCE", waiting.get("controlState")); assertEquals(start+10000, waiting.get("timestamp"));
        assertEquals(start-1000, waiting.get("sourceTimestamp")); assertEquals(false, waiting.get("tradeAvailable"));
        assertEquals(90, restarted.display(symbol, raw(start+120000, true), start+120000).get("price"));
        assertEquals(90, restarted.display(symbol, raw(start+120000, false), start+180000).get("price"), "A later outage retains the resumed source, not an old target");
    }
    @Test void stopAndLaterTaskNeverRewritePastAndDoNotExtendToTarget() {
        long start = System.currentTimeMillis()-120000;
        PersistentPriceControl.Task first = legacy(start, 10); controls.stop(1, start+4500);
        assertEquals(5, count("market_control_sample")); assertEquals("STOPPED", controls.latest(1).status);
        assertEquals(start+4500, controls.latest(1).endedAt);
        List<Map<String,Object>> past = store.mixed(1, 0, start/60000*60000);
        controls.start(symbol, raw(System.currentTimeMillis(), true), BigDecimal.valueOf(94), 10, BigDecimal.valueOf(120), 1, false, false, null);
        assertEquals(2, count("market_control_task")); assertEquals(past, store.mixed(1, 0, start/60000*60000));
        assertEquals(5, store.db.queryForObject("SELECT COUNT(*) FROM market_control_sample WHERE task_id=?", Integer.class, first.id));
    }
    @Test void concurrentCompletionAndImportAreIdempotent() throws Exception {
        long start = 1700000035000L; legacy(start, 10);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int i=0;i<12;i++) calls.add(() -> { controls.importLegacy(symbol); controls.advance(1, start+10000); return null; });
            for (Future<Void> call : pool.invokeAll(calls)) call.get();
        } finally { pool.shutdownNow(); }
        assertEquals(1, count("market_control_task")); assertEquals(11, count("market_control_sample"));
    }
    @Test void freezePreservesKnownSnapshotAndSourceRefreshCannotOverwriteIt() {
        long start = 1700000020000L, minute = start/60000*60000;
        store.sourceCandles(1,"1m",Collections.singletonList(bar(minute,80,92,79,91)),start-1000);
        store.locked(1, () -> { store.freeze(1, start); return null; }); legacy(start, 10); controls.advance(1, start+10000);
        Map<String,Object> frozen = store.mixed(1, minute, minute).get(0);
        assertEquals(0, BigDecimal.valueOf(80).compareTo(ControlHistoryStore.number(frozen.get("open_price"))));
        assertEquals(0, BigDecimal.valueOf(79).compareTo(ControlHistoryStore.number(frozen.get("low_price"))));
        store.sourceCandles(1, "1m", Collections.singletonList(bar(minute, 1, 999, 1, 999)), start+120000);
        assertEquals(frozen, store.mixed(1, minute, minute).get(0));
        Map<String,Object> response = new HashMap<>(); response.put("ret", 200); response.put("data", Collections.singletonMap("kline_list", Collections.emptyList()));
        Map<String,Object> merged = merger.merge(1, "1m", 100, null, response, null);
        assertEquals(1, ControlHistoryStore.rows(merged).size()); assertEquals(0, ControlHistoryStore.number(ControlHistoryStore.rows(merged).get(0).get("close_price")).compareTo(BigDecimal.valueOf(100)));
        assertEquals(ControlHistoryStore.rows(merged), ControlHistoryStore.rows(merger.merge(1, "1m", 100, minute, response, null)));
        assertEquals(1, ControlHistoryStore.rows(merger.merge(1, "5m", 100, null, response, null)).size());
    }
    @Test void providerQuoteTimeTailsCannotOverwriteControlledMinuteCloseOrCreateExtraCandles() {
        long minute=1700000040000L;
        PersistentPriceControl.Task task=legacy(minute+1000,30);controls.advance(1,minute+16000);
        List<Map<String,Object>> source=Arrays.asList(bar(minute,90,95,89,94),
            bar(minute+17000,157,157,157,157),bar(minute+33000,158,158,158,158));
        store.sourceCandles(1,"1m",source,minute+34000);
        Map<String,Object> response=new HashMap<>();response.put("ret",200);
        response.put("data",Collections.singletonMap("kline_list",source));
        List<Map<String,Object>> bars=ControlHistoryStore.rows(merger.merge(1,"1m",200,null,response,null));
        assertEquals(1,bars.size());assertEquals(1,bars.get(0).get("minuteCount"));
        assertEquals(95,ControlHistoryStore.number(bars.get(0).get("close_price")).intValue());
        assertEquals(95,ControlHistoryStore.number(bars.get(0).get("high_price")).intValue());
        response.put("data",Collections.singletonMap("kline_list",Collections.emptyList()));
        List<Map<String,Object>> larger=ControlHistoryStore.rows(merger.merge(1,"5m",200,null,response,null));
        assertEquals(95,ControlHistoryStore.number(larger.get(0).get("close_price")).intValue());
        assertEquals(3,count("market_source_candle"),"Raw provider payloads remain independently preserved");
    }
    @Test void sparseRecentSourceDoesNotHideOlderControlledCandles() {
        long start = 1700000020000L, minute = start / 60000 * 60000;
        legacy(start, 10); controls.advance(1, start + 10000);
        seed(minute + 600000);
        Map<String,Object> response = new HashMap<>(); response.put("ret", 200);
        response.put("data", Collections.singletonMap("kline_list", Collections.emptyList()));
        for (String interval : Arrays.asList("1m", "5m")) {
            if ("5m".equals(interval)) store.sourceCandles(1, interval,
                Collections.singletonList(bar((minute + 600000) / 300000 * 300000, 80, 92, 79, 91)), start + 900000);
            List<Map<String,Object>> latest = ControlHistoryStore.rows(merger.merge(1, interval, 100, null, response, null));
            assertEquals(2, latest.size(), "Short source pages must still include older persisted controls: " + interval);
            assertEquals(100, ControlHistoryStore.number(latest.get(0).get("close_price")).intValue());
            List<Map<String,Object>> page = ControlHistoryStore.rows(merger.merge(1, interval, 100,
                ControlHistoryStore.time(latest.get(1)) - 1, response, null));
            assertEquals(1, page.size()); assertEquals(latest.get(0), page.get(0));
            assertEquals(Collections.singletonList(latest.get(1)),
                ControlHistoryStore.rows(merger.merge(1, interval, 1, null, response, null)));
        }
    }
    @Test void failedWriteRollsBackProgressAndCanRetry() {
        long start=1700000020000L; legacy(start,10);
        store.db.execute("ALTER TABLE market_control_sample ADD CONSTRAINT fail_sample CHECK (price < 95)");
        assertThrows(RuntimeException.class, () -> controls.advance(1,start+10000));
        assertEquals(0,count("market_control_sample")); assertEquals(0,count("market_mixed_minute"));
        assertEquals(start-1000,controls.latest(1).sampledUntil);
        store.db.execute("ALTER TABLE market_control_sample DROP CONSTRAINT fail_sample");
        controls.advance(1,start+10000); assertEquals(11,count("market_control_sample"));
    }
    @Test void laterRealQuotesExtendOnlyTheirAffectedMinuteAndKeepOriginalSource() {
        long start=1700000020000L; legacy(start,10); controls.advance(1,start+10000);
        symbol.setControlEnabled(false);
        controls.sourceQuote(symbol,raw(start+11000,true),start+11000);
        assertEquals(90,ControlHistoryStore.number(store.mixed(1,start/60000*60000,start/60000*60000).get(0).get("close_price")).intValue());
        List<Map<String,Object>> old=store.mixed(1,0,Long.MAX_VALUE);
        controls.sourceQuote(symbol,raw(start+120000,true),start+120000);
        assertEquals(old,store.mixed(1,0,Long.MAX_VALUE)); assertEquals(1,count("market_mixed_minute"));
        assertEquals(start+120000,store.lastQuote(1).get("timestamp"));
    }
    @Test @SuppressWarnings("unchecked") void marketIntegrationExecutesRunningAndHeldPricesDuringOutageUntilExplicitRestore() {
        ForexQuoteMarketService market = new ForexQuoteMarketService();
        com.gtcfesk.exchange.repository.TradingSymbolRepository repository = org.mockito.Mockito.mock(com.gtcfesk.exchange.repository.TradingSymbolRepository.class);
        symbol.setCategory("Metal"); symbol.setSourceCategory("Metal"); symbol.setMarketSource(com.gtcfesk.exchange.market.MarketInstrumentCatalog.inferredSource("Metal"));
        org.mockito.Mockito.when(repository.findById(1L)).thenReturn(Optional.of(symbol));
        org.mockito.Mockito.when(repository.findAll()).thenReturn(Collections.singletonList(symbol));
        org.mockito.Mockito.when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));
        org.springframework.test.util.ReflectionTestUtils.setField(market,"symbols",repository);
        org.springframework.test.util.ReflectionTestUtils.setField(market,"redis",org.mockito.Mockito.mock(RedisMarketService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(market,"controls",controls);
        org.springframework.test.util.ReflectionTestUtils.setField(market,"controlHistory",store);
        org.springframework.test.util.ReflectionTestUtils.setField(market,"klineMerger",merger);
        try {
            market.refreshSymbols(); long now=System.currentTimeMillis(); seed(now/60000*60000-60000);
            Map<String,Object> groups=(Map<String,Object>)org.springframework.test.util.ReflectionTestUtils.getField(market,"groups");
            Map<String,Map<String,Object>> quotes=(Map<String,Map<String,Object>>)org.springframework.test.util.ReflectionTestUtils.getField(groups.get("Metal"),"quotes");
            Map<String,Object> live=raw(now,true); live.put("fetchedAt",now); live.put("sourceAvailable",true); quotes.put("TEST",live);
            symbol.setIsEnabled(true); symbol.setQuoteCurrency("USD"); symbol.setLotSize(BigDecimal.ONE); symbol.setFeeMultiplier(BigDecimal.ZERO);
            when(repository.findBySymbol("TEST")).thenReturn(Optional.of(symbol));
            ContractOrderRepository orders=mock(ContractOrderRepository.class);
            AssetAccountRepository accounts=mock(AssetAccountRepository.class);
            AssetAccount account=new AssetAccount(); account.setAvailable(BigDecimal.valueOf(1000)); account.setFrozen(BigDecimal.ZERO);
            when(accounts.findByUserIdAndCoin(1L,"CONTRACT")).thenReturn(Optional.of(account));
            when(orders.save(any(ContractOrder.class))).thenAnswer(call -> call.getArgument(0));
            ContractOrderService trading=new ContractOrderService(orders,accounts,repository,market,null,mock(MarketCategoryService.class));
            CreateContractOrderRequest request=new CreateContractOrderRequest(); request.setSymbol("TEST"); request.setSide("BUY");
            request.setType("MARKET"); request.setQuantity(BigDecimal.ONE); request.setLeverage(BigDecimal.ONE);
            ContractOrder opened=trading.createOrder(1L,request);
            assertEquals(0,BigDecimal.valueOf(90).compareTo(opened.getOpenPrice()));
            when(orders.findById(1L)).thenReturn(Optional.of(opened));
            live.put("timestamp",now-60000); live.put("sourceTimestamp",now-60000);
            assertThrows(BusinessException.class,()->trading.closeOrder(1L,1L,BigDecimal.ONE));
            Map<String,Object> status=market.startControl(1L,10,BigDecimal.valueOf(100),1,false,"integration");
            assertEquals(true,status.get("running")); assertEquals("COMPLETED_CANDLE",status.get("startSource"));
            Map<String,Object> controlled = market.internalPrice("TEST");
            assertEquals(true, controlled.get("available")); assertEquals(true, controlled.get("tradeAvailable"));
            assertEquals(false, controlled.get("sourceAvailable")); assertEquals(false, controlled.get("stale"));
            assertEquals("available", controlled.get("status"));
            assertTrue(QuoteState.time(controlled.get("expiresAt")) > System.currentTimeMillis());
            assertEquals(now, controlled.get("fetchedAt"));
            assertEquals(now-60000, controlled.get("sourceTimestamp"));
            assertEquals(controlled.get("executionExpiresAt"), controlled.get("expiresAt"));
            assertNotNull(market.freshPrice("TEST"));
            ContractOrder closed=trading.closeOrder(1L,1L,BigDecimal.ONE);
            PersistentPriceControl.Task task=controls.latest(1);
            assertEquals(0,task.price(task.sampledUntil).compareTo(closed.getClosePrice()));
            assertEquals("CLOSED",closed.getStatus());
            assertEquals(0,closed.getClosePrice().subtract(opened.getOpenPrice()).compareTo(closed.getProfit()));
            ContractOrder duringControl=trading.createOrder(1L,request);
            task=controls.latest(1);
            assertEquals(0,task.price(task.sampledUntil).compareTo(duringControl.getOpenPrice()));
            assertFalse(ControlHistoryStore.rows(market.internalKline("TEST","1m",100)).isEmpty());
            market.stopControl(1L); assertEquals("HOLDING",market.controlStatus(1L).get("controlState"));
            BigDecimal heldPrice=market.freshPrice("TEST");
            long oldSample=System.currentTimeMillis()-60000;
            store.db.update("UPDATE market_control_hold SET generated_at=? WHERE task_id=?",oldSample,task.id);
            long samples=count("market_control_sample");
            org.springframework.test.util.ReflectionTestUtils.setField(market,"controls",new PersistentPriceControl(store));
            Map<String,Object> held=market.internalPrice("TEST");
            assertEquals(oldSample,held.get("timestamp")); assertEquals(true,held.get("tradeAvailable"));
            assertTrue(QuoteState.time(held.get("executionExpiresAt"))>System.currentTimeMillis());
            assertEquals(samples,count("market_control_sample"),"Renewing execution must not manufacture history");
            when(orders.findById(2L)).thenReturn(Optional.of(duringControl));
            assertEquals(0,heldPrice.compareTo(trading.closeOrder(1L,2L,BigDecimal.ONE).getClosePrice()));
            assertEquals(0,heldPrice.compareTo(trading.createOrder(1L,request).getOpenPrice()));
            market.stopControl(1L); assertEquals(0,heldPrice.compareTo(market.freshPrice("TEST")));
            market.manualControl(1L,false,BigDecimal.ZERO);
            assertNull(market.freshPrice("TEST"));
            live=raw(System.currentTimeMillis(),true); live.put("fetchedAt",System.currentTimeMillis()); live.put("sourceAvailable",true); quotes.put("TEST",live);
            assertEquals(0,market.freshPrice("TEST").compareTo(BigDecimal.valueOf(90)));
            assertEquals(1,controls.history(1,null).size());
            org.springframework.test.util.ReflectionTestUtils.setField(market,"virtualTrading",true);
            market.startControl(1L,30,BigDecimal.valueOf(100),1,false,"before-simulation");
            market.randomMarket(1L,true,null);
            assertTrue(PriceControlPath.running(symbol)); assertEquals("STOPPED",controls.latest(1).status);
            market.randomMarket(1L,false,null); market.refreshSymbols();
            assertFalse(PriceControlPath.running(symbol)); assertEquals(2,controls.history(1,null).size(),"Switching sources must not replay virtual history as a new ordinary task");
        } finally { market.stop(); }
    }
    @Test void sessionAnchorAndCursorStayStableAndHistoryHasNoSevenDayCutoff() {
        long anchor = 1700001000000L / 3600000 * 3600000 + 1800000;
        store.sourceCandles(1,"1h",Collections.singletonList(bar(anchor,80,999,1,90)),anchor+60000);
        legacy(anchor+600000,10); controls.advance(1,anchor+610000);
        Map<String,Object> response=new HashMap<>(); response.put("ret",503); response.put("data",Collections.singletonMap("kline_list",Collections.emptyList()));
        List<Map<String,Object>> rows=ControlHistoryStore.rows(merger.merge(1,"1h",200,null,response,null,false));
        assertEquals(1,rows.size());assertEquals(anchor,rows.get(0).get("timestamp"));
        assertEquals(0,ControlHistoryStore.number(rows.get(0).get("high_price")).compareTo(BigDecimal.valueOf(100)),"Do not mix unknown hourly extrema into controlled minutes");
        assertTrue(ControlHistoryStore.rows(merger.merge(1,"1h",200,anchor-1,response,null,false)).isEmpty(),"Paging before a session must exclude its controlled minutes");
        store.db.update("DELETE FROM market_source_candle WHERE period='1h'");
        assertTrue(ControlHistoryStore.rows(merger.merge(1,"1h",200,null,response,null,false)).isEmpty(),"Missing source session boundary must not invent an hourly anchor");
        assertFalse(ControlHistoryStore.rows(merger.merge(1,"1m",200,null,response,null)).isEmpty(),"Years-old generated minutes remain accessible");
        long previousDay=anchor/86400000*86400000-86400000;
        store.sourceCandles(1,"1d",Collections.singletonList(bar(previousDay,80,99,79,90)),previousDay+86400000);
        List<Map<String,Object>> daily=ControlHistoryStore.rows(merger.merge(1,"1d",200,null,response,null,false));
        assertEquals(1,daily.size());assertEquals(previousDay,daily.get(0).get("timestamp"),"Unknown closed-market or DST session must not acquire a guessed daily anchor");
    }
    @Test void restoreIsNewFixedTargetSegmentAndSourceLossCannotChangeItsPath() {
        long now=System.currentTimeMillis();
        PersistentPriceControl.Task first=controls.start(symbol,raw(now,true),BigDecimal.valueOf(110),10,BigDecimal.valueOf(120),1,false,false,"first");
        PersistentPriceControl.Task restore=controls.start(symbol,raw(now,true),BigDecimal.valueOf(110),10,BigDecimal.valueOf(90),1,false,true,"restore");
        assertEquals("RESTORE",restore.kind);assertNotEquals(first.id,restore.id);
        assertEquals("STOPPED",controls.history(1,null).get(1).status);
        Map<String,Object> lost=controls.display(symbol,raw(now,false),restore.startedAt+5000);
        assertEquals(0,ControlHistoryStore.number(lost.get("price")).compareTo(BigDecimal.valueOf(102)), "V2 regular wave adds 2 at the fifth second of this recovery");
        assertEquals(false,lost.get("tradeAvailable"));assertEquals("RUNNING",lost.get("controlState"));
        controls.advance(1,restore.plannedEnd);assertEquals(restore.plannedEnd,controls.latest(1).endedAt);
        assertEquals(11,store.db.queryForObject("SELECT COUNT(*) FROM market_control_sample WHERE task_id=?",Integer.class,restore.id));
        assertThrows(BusinessException.class,()->controls.start(symbol,raw(now,true),BigDecimal.valueOf(90),5,BigDecimal.valueOf(80),1,false,false,"first"));
    }
    @Test void fullDayRecoveryUsesExactEndpointWithoutExtendingTheTask() {
        long start=1700000040000L;
        legacy(start,86400);controls.advance(1,start+86400000+90000);
        assertEquals(86401,count("market_control_sample"));assertEquals(1441,count("market_mixed_minute"));
        assertEquals(start+86400000,controls.latest(1).endedAt);
        List<Map<String,Object>> endpoint=store.mixed(1,start+86400000,start+86400000);
        assertEquals(1,endpoint.size());assertEquals(0,ControlHistoryStore.number(endpoint.get(0).get("open_price")).compareTo(BigDecimal.valueOf(100)));
    }
    @Test void returningToCachedSourceSurvivesRestartAndNeverRefreshesItsTimestamp() {
        long start=1700000040000L;legacy(start,10);
        Map<String,Object> returned=controls.display(symbol,raw(start+8000,true),start+10000);
        assertEquals(90,returned.get("price"));assertEquals(start+8000,returned.get("sourceTimestamp"));
        assertEquals(1,count("market_control_resume"));
        Map<String,Object> restarted=new PersistentPriceControl(store).display(symbol,raw(start+8000,false),start+60000);
        assertEquals(90,restarted.get("price"));assertEquals(start+8000,restarted.get("timestamp"));
        assertEquals(true,restarted.get("controlSourceResumed"));assertEquals(false,restarted.get("available"));
        assertEquals(1,count("market_control_resume"));assertEquals(1,count("market_mixed_minute"));
        Map<String,Object> minute=store.mixed(1,start,start).get(0);
        assertEquals(0,ControlHistoryStore.number(minute.get("close_price")).compareTo(BigDecimal.valueOf(90)));
        assertEquals(0,ControlHistoryStore.number(minute.get("high_price")).compareTo(BigDecimal.valueOf(100)));
    }
    @Test void persistedEndpointRetainsDecimalPrecisionForTheNextHistoricalStart() {
        symbol.setPricePrecision(8);
        BigDecimal target=new BigDecimal("1000000000000.12345678");
        PersistentPriceControl.Task task=controls.start(symbol,raw(System.currentTimeMillis(),true),BigDecimal.valueOf(90),1,target,1,false,false,null);
        controls.advance(1,task.plannedEnd);
        Map<String,Object> basis=controls.startBasis(symbol,Collections.emptyMap(),null,task.plannedEnd+60000);
        assertEquals(0,target.compareTo(ControlHistoryStore.number(basis.get("price"))));
    }
    @Test void unconfirmedOpenSnapshotCannotBecomeACompletedCloseAndClosingTimeWins() {
        long hour=1700000040000L / 3600000 * 3600000;
        store.sourceCandles(1,"1m",Collections.singletonList(bar(hour,80,92,79,91)),hour+1000);
        assertTrue(controls.startBasis(symbol,Collections.emptyMap(),null,hour+60000).isEmpty());
        legacy(hour+600000,10);controls.advance(1,hour+610000);
        store.sourceCandles(1,"1h",Collections.singletonList(bar(hour,80,100,79,97)),hour+3600000);
        Map<String,Object> basis=controls.startBasis(symbol,Collections.emptyMap(),null,hour+3600000);
        assertEquals(0,BigDecimal.valueOf(97).compareTo(ControlHistoryStore.number(basis.get("price"))));
    }
    @Test void newTargetHoldsOffsetAcrossSourceChangesOutageAndRestart() {
        long now=System.currentTimeMillis();
        PersistentPriceControl.Task task=controls.start(symbol,raw(now,true),BigDecimal.valueOf(90),1,BigDecimal.valueOf(100),1,false,false,"hold");
        assertThrows(BusinessException.class,()->controls.replaceHistory(1,task.id));
        Map<String,Object> endpoint=controls.display(symbol,raw(now,true),task.plannedEnd);
        assertEquals("HOLDING",endpoint.get("controlState"));
        assertEquals(100,ControlHistoryStore.number(endpoint.get("price")).intValue());
        Map<String,Object> moved=raw(task.plannedEnd+1000,true); moved.put("price",95);
        controls.sourceQuote(symbol,moved,task.plannedEnd+1000);
        Map<String,Object> following=controls.display(symbol,moved,task.plannedEnd+2000);
        assertEquals(105,ControlHistoryStore.number(following.get("price")).intValue());
        assertEquals(task.plannedEnd+1000,following.get("sourceTimestamp"));
        long samples=count("market_control_sample");
        Map<String,Object> offline=new PersistentPriceControl(store).display(symbol,raw(now,false),task.plannedEnd+120000);
        assertEquals("HOLDING",offline.get("controlState"));assertEquals(false,offline.get("available"));
        assertEquals(105,ControlHistoryStore.number(offline.get("price")).intValue());assertEquals(samples,count("market_control_sample"));
        Map<String,Object> status=new HashMap<>();controls.status(symbol,status,raw(now,false),task.plannedEnd+120000);
        Map<?,?> basis=(Map<?,?>)status.get("startBasis");
        assertEquals("CONTROL_DISPLAY",basis.get("source"));assertEquals(105,ControlHistoryStore.number(basis.get("price")).intValue());
        Map<String,Object> recovery=raw(task.plannedEnd+180000,true);recovery.put("price",96);
        assertEquals(106,ControlHistoryStore.number(controls.display(symbol,recovery,task.plannedEnd+180000).get("price")).intValue());
        assertTrue(controls.latest(1).holding);assertEquals(task.plannedEnd,controls.latest(1).endedAt);
    }
    @Test void holdingReferenceUsesLastObservedQuoteAtEndpointAndDeduplicatesConcurrentTicks() throws Exception {
        long start=System.currentTimeMillis()-120000;
        PersistentPriceControl.Task task=legacy(start,10);new ControlHoldService(store).prepare(task,raw(start,true));
        Map<String,Object> before=raw(start+9000,true);before.put("price",92);controls.sourceQuote(symbol,before,start+9000);
        controls.advance(1,start+10000);
        Map<String,Object> after=raw(start+11000,true);after.put("price",95);
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            List<Callable<Void>> calls=new ArrayList<>();
            for(int i=0;i<12;i++) calls.add(()->{controls.sourceQuote(symbol,after,start+11000);return null;});
            for(Future<Void> call:pool.invokeAll(calls))call.get();
        } finally {pool.shutdownNow();}
        Map<String,Object> held=controls.display(symbol,after,start+12000);
        assertEquals(103,ControlHistoryStore.number(held.get("price")).intValue());
        assertEquals(8,ControlHistoryStore.number(held.get("controlOffset")).intValue());
        assertEquals(12,count("market_control_sample"));
    }
    @Test void earlyStopKeepsCurrentOffsetAcrossRestartAndFollowingSourceQuotes() {
        long start=System.currentTimeMillis()-10000;
        PersistentPriceControl.Task task=legacy(start,60);
        new ControlHoldService(store).prepare(task,raw(start,true));
        controls.advance(1,start+5000);
        BigDecimal stopped=controls.latest(1).price(start+5000);
        controls.stopAndHold(1,start+5000);
        assertEquals("STOPPED",controls.latest(1).status); assertTrue(controls.latest(1).holding);
        symbol.setControlEnabled(false);
        PersistentPriceControl restarted=new PersistentPriceControl(store);
        Map<String,Object> offline=restarted.display(symbol,raw(start,false),start+6000);
        assertEquals("HOLDING",offline.get("controlState"));
        assertEquals(0,stopped.compareTo(ControlHistoryStore.number(offline.get("price"))));
        Map<String,Object> moved=raw(start+7000,true); moved.put("price",92);
        Map<String,Object> following=restarted.display(symbol,moved,start+7000);
        assertEquals(0,stopped.add(BigDecimal.valueOf(2)).compareTo(ControlHistoryStore.number(following.get("price"))));
        long samples=count("market_control_sample");
        restarted.stopAndHold(1,start+8000);
        assertEquals(samples,count("market_control_sample")); assertTrue(restarted.latest(1).holding);
        restarted.stop(1,start+9000); assertFalse(restarted.latest(1).holding);
    }
    @Test void nextControlAndRestoreContinueHeldPriceAndReleaseOnlyFutureHolding() {
        long start=System.currentTimeMillis()-120000;
        PersistentPriceControl.Task previous=legacy(start,10);new ControlHoldService(store).prepare(previous,raw(start,true));
        controls.advance(1,start+10000);
        List<Map<String,Object>> past=store.mixed(1,0,start/60000*60000);
        symbol.setControlEnabled(false);
        PersistentPriceControl.Task next=controls.start(symbol,raw(start,false),BigDecimal.valueOf(90),10,BigDecimal.valueOf(110),1,false,false,"next-held");
        assertEquals(100,next.startPrice.intValue());assertEquals("CONTROL_DISPLAY",next.startSource);
        assertFalse(controls.history(1,null).get(1).holding);assertEquals(past,store.mixed(1,0,start/60000*60000));
        PersistentPriceControl.Task restore=controls.start(symbol,raw(System.currentTimeMillis(),true),BigDecimal.valueOf(90),1,BigDecimal.valueOf(90),1,false,true,"restore-held");
        assertEquals(100,restore.startPrice.intValue());controls.advance(1,restore.plannedEnd);
        assertFalse(controls.latest(1).holding);
        assertNotEquals("HOLDING",controls.display(symbol,raw(restore.plannedEnd,true),restore.plannedEnd).get("controlState"));
    }
    @Test void newRegularWaveVersionSurvivesRestartWithoutRewritingCommittedSamples() {
        long now=System.currentTimeMillis();
        PersistentPriceControl.Task task=controls.start(symbol,raw(now,true),BigDecimal.valueOf(90),10,BigDecimal.valueOf(110),10,false,false,"version-two");
        assertEquals(2,task.algorithmVersion);
        controls.advance(1,task.startedAt+5000);
        BigDecimal midpoint=store.db.queryForObject("SELECT price FROM market_control_sample WHERE task_id=? AND generated_at=?",BigDecimal.class,task.id,task.startedAt+5000);
        assertEquals(0,BigDecimal.valueOf(120).compareTo(midpoint));
        PersistentPriceControl restarted=new PersistentPriceControl(store);restarted.advance(1,task.plannedEnd+60000);
        assertEquals(midpoint,store.db.queryForObject("SELECT price FROM market_control_sample WHERE task_id=? AND generated_at=?",BigDecimal.class,task.id,task.startedAt+5000));
        assertEquals(11,count("market_control_sample"));assertEquals(task.plannedEnd,restarted.latest(1).endedAt);
    }
    @Test void explicitHistoryReplacementIsIdempotentAndDoesNotRewriteSourceOrLaterEvents() {
        long start=1700000035000L;
        PersistentPriceControl.Task task=legacy(start,10);controls.advance(1,start+10000);
        List<Map<String,Object>> past=store.mixed(1,0,Long.MAX_VALUE);
        PersistentPriceControl.Task published=controls.replaceHistory(1,task.id);
        assertNotNull(published.historyReplacedAt);
        assertEquals(published.historyReplacedAt,controls.replaceHistory(1,task.id).historyReplacedAt);
        assertEquals(1,count("market_control_publication"));assertEquals(past,store.mixed(1,0,Long.MAX_VALUE));
        assertThrows(BusinessException.class,()->controls.replaceHistory(1,"unknown-task"));
        seed(start/60000*60000);
        Map<String,Object> response=new HashMap<>();response.put("data",Collections.singletonMap("kline_list",Collections.emptyList()));
        for(String period:Arrays.asList("1m","5m","1h")) {
            List<Map<String,Object>> bars=ControlHistoryStore.rows(merger.merge(1,period,100,null,response,null));
            assertTrue(bars.stream().allMatch(bar->Boolean.TRUE.equals(bar.get("historyReplaced"))));
            assertEquals(100,ControlHistoryStore.number(bars.get(bars.size()-1).get("close_price")).intValue());
        }
        assertEquals(published.historyReplacedAt,new PersistentPriceControl(store).history(1,null).get(0).historyReplacedAt);
    }
}
