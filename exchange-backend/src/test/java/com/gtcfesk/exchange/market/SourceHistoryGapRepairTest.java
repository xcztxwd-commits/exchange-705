package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executed local H2 transactions and real queue/GET adapters; never a production or external source connection. */
class SourceHistoryGapRepairTest extends TenantMarketTestContext {
    static final long M = 1700000400000L;
    static Map<String,Object> bar(long at) { return new LinkedHashMap<>(Map.of("timestamp", at, "open_price", 90d,
        "high_price", 95d, "low_price", 89d, "close_price", 91d, "volume", 7d)); }
    static Map<String,Object> response(List<Map<String,Object>> rows) {
        return new LinkedHashMap<>(Map.of("ret", 200, "data", new LinkedHashMap<>(Map.of("kline_list", rows))));
    }
    static final class ObservedJdbc extends JdbcTemplate {
        boolean failInsert, failRevision, expireLeaseBeforeInsert;
        final List<String> writes = Collections.synchronizedList(new ArrayList<>());
        ObservedJdbc(DriverManagerDataSource data) { super(data); }
        @Override public int update(String sql, Object... args) {
            if (expireLeaseBeforeInsert && sql.startsWith("INSERT INTO market_source_candle"))
                super.update("UPDATE market_engine_runtime SET lease_until=0 WHERE tenant_id=1 AND symbol_id=1");
            writes.add(sql); int changed = super.update(sql, args);
            if (failInsert && sql.startsWith("INSERT INTO market_source_candle")) throw new IllegalStateException("injected source insert failure");
            if (failRevision && sql.startsWith("UPDATE market_engine_runtime SET source_input_revision=")) throw new IllegalStateException("injected source revision failure");
            return changed;
        }
    }
    static final class Fixture implements AutoCloseable {
        final DriverManagerDataSource data;
        final ObservedJdbc db;
        final DataSourceTransactionManager manager;
        final ControlHistoryStore store;
        final SourceHistoryGapRepair repair;
        final ForexQuoteMarketService market = new ForexQuoteMarketService();
        final MarketQuoteSource source = mock(MarketQuoteSource.class);
        final TradingSymbol config = new TradingSymbol();
        final Object group;
        Fixture() {
            this(new DriverManagerDataSource("jdbc:h2:mem:gap_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""), true);
        }
        Fixture(DriverManagerDataSource data, boolean initialize) {
            this.data = data; db = new ObservedJdbc(data); manager = new DataSourceTransactionManager(data);
            store = new ControlHistoryStore(db, manager); repair = new SourceHistoryGapRepair(store);
            if (initialize) {
            MarketSqlFixture.schema(db);
            for (String column : new String[]{"symbol VARCHAR(32) DEFAULT 'BTCUSDT'", "alltick_symbol VARCHAR(64)", "market_source VARCHAR(16) DEFAULT 'binance'", "source_category VARCHAR(32) DEFAULT 'Crypto'", "row_version BIGINT DEFAULT 0", "random_market_enabled BOOLEAN DEFAULT FALSE", "random_market_started_at BIGINT", "control_enabled BOOLEAN DEFAULT FALSE"}) db.execute("ALTER TABLE trading_symbol ADD COLUMN " + column);
            db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1)");
            }
            config.setTenantId(1L); config.setId(1L); config.setSymbol("BTCUSDT"); config.setSourceCategory("Crypto");
            config.setCategory("Crypto"); config.setMarketSource("binance"); config.setIsEnabled(true);
            ReflectionTestUtils.setField(marketState(market), "registry", Map.of(config.getSymbol(), config));
            group = ((Map<?,?>)ReflectionTestUtils.getField(marketState(market), "groups")).get("Crypto");
            ReflectionTestUtils.setField(group, "codes", List.of("BTCUSDT"));
            ReflectionTestUtils.setField(group, "nextQuotes", Long.MAX_VALUE);
            ReflectionTestUtils.setField(market, "controlHistory", store);
            ReflectionTestUtils.setField(market, "klineMerger", new ControlledKlineMerger(store));
            ReflectionTestUtils.setField(market, "historyGapRepair", repair);
            ReflectionTestUtils.setField(market, "source", source);
            ReflectionTestUtils.setField(market, "http", mock(MarketHttp.class));
        }
        SourceHistoryGapRepair.Window inspect(String period, int limit, long cursor) {
            return store.readConsumerSnapshot(() -> repair.inspect(config, period, limit, cursor, "Binance", List.of(), false));
        }
        Map<String,Object> read(String period, int limit, long cursor) {
            return store.readConsumerSnapshot(() -> market.historicalKline("BTCUSDT", period, limit, cursor));
        }
        @SuppressWarnings("unchecked") Map<String,Object> metadata(Map<String,Object> result) { return (Map<String,Object>)((Map<?,?>)result.get("data")).get("historyRepair"); }
        @SuppressWarnings("unchecked") Map<String,Object> body(Map<String,Object> result) { return (Map<String,Object>)result.get("data"); }
        @SuppressWarnings("unchecked") Map<String,Map<String,Object>> cache() { return (Map<String,Map<String,Object>>)ReflectionTestUtils.getField(group, "klines"); }
        @SuppressWarnings("unchecked") Map<String,Object> queue() { return (Map<String,Object>)ReflectionTestUtils.getField(group, "pending"); }
        void tick() { ReflectionTestUtils.invokeMethod(market, "tick", group); }
        void retryTick() { ReflectionTestUtils.setField(group, "nextKlines", 0L); ReflectionTestUtils.setField(group, "nextAllowed", 0L); tick(); }
        List<Map<String,Object>> raw() { return db.queryForList("SELECT * FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 ORDER BY period,candle_at"); }
        void supply(String period, List<Map<String,Object>> rows) {
            when(source.getHistoryWindow(eq("BTCUSDT"), eq(period), anyInt(), eq("Crypto"), anyLong(), anyLong())).thenAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "no source fetch in GET/write transaction");
                return response(rows);
            });
        }
        @Override public void close() { market.stop(); }
    }
    @Test void fullSparseFreshPageTriggersFetchWithoutTtlAndGetDoesNotWrite() {
        try (Fixture f = new Fixture()) {
            List<Map<String,Object>> sparse = new ArrayList<>(); sparse.add(bar(M - 300000));
            for (int i = 0; i < 200; i++) if (i != 101) sparse.add(bar(M + i * 300000L));
            long cursor = M + 200 * 300000L - 1;
            f.store.sourceCandles(1, "5m", sparse, M + 201 * 300000L);
            Map<String,Object> cached = response(sparse); cached.put("fetchedAt", System.currentTimeMillis());
            f.cache().put("BTCUSDT:5m:200:" + cursor, cached);
            List<Map<String,Object>> before = f.raw(); f.db.writes.clear();
            Map<String,Object> page = f.read("5m", 200, cursor);
            assertEquals(200, ControlHistoryStore.rows(page).size(), "row count is full but not complete");
            assertTrue((Boolean)f.metadata(page).get("pending")); assertEquals(1, f.queue().size());
            assertTrue(f.db.writes.isEmpty(), "consuming GET is read only"); assertEquals(before, f.raw());
            verifyNoInteractions(f.source);
            List<Map<String,Object>> complete = new ArrayList<>(); for (int i = 0; i < 200; i++) complete.add(bar(M + i * 300000L));
            f.supply("5m", complete); f.tick();
            assertEquals(201, f.raw().size());
            assertEquals("complete", f.metadata(f.read("5m", 200, cursor)).get("state"));
            assertEquals(1, f.metadata(f.read("5m", 200, cursor)).get("inserted"));
            for (Map<String,Object> row : before) assertTrue(f.raw().contains(row), "existing body/received_at remain exact");
            assertFalse(f.cache().containsKey("BTCUSDT:5m:200:" + cursor));
        }
    }
    @Test void fullOldTailCannotCertifyCursorAdjacentWindow() {
        try (Fixture f = new Fixture()) {
            List<Map<String,Object>> old = new ArrayList<>(); for (int i = 0; i < 200; i++) old.add(bar(M + i * 300000L));
            f.store.sourceCandles(1, "5m", old, M + 201 * 300000L);
            SourceHistoryGapRepair.Window window = f.inspect("5m", 200, M + 203 * 300000L - 1);
            assertEquals(3, window.gaps.size()); assertTrue(window.needsSource());
            assertEquals(M + 200 * 300000L, window.gaps.get(0).get("from"));
        }
    }
    @Test void repeatedConcurrentGetsShareQueueAndConcurrentWritersInsertOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> requests = new ArrayList<>();
                for (int i = 0; i < 20; i++) requests.add(workers.submit(MarketSqlFixture.inTenant(() -> f.read("1m", 2, M + 119999))));
                for (Future<?> request : requests) request.get(10, TimeUnit.SECONDS);
                assertEquals(1, f.queue().size());
                SourceHistoryGapRepair.Window window = f.inspect("1m", 2, M + 119999);
                Callable<SourceHistoryGapRepair.Receipt> write = MarketSqlFixture.inTenant(() -> f.repair.insert(window, response(List.of(bar(M), bar(M + 60000))), System.currentTimeMillis()));
                Future<SourceHistoryGapRepair.Receipt> a = workers.submit(write), b = workers.submit(write);
                assertEquals(2, a.get(10, TimeUnit.SECONDS).inserted + b.get(10, TimeUnit.SECONDS).inserted);
                assertEquals(2, f.raw().size()); assertEquals(1L, f.db.queryForObject("SELECT source_input_revision FROM market_engine_runtime", Long.class));
            } finally { workers.shutdownNow(); }
        }
    }
    @Test void partialInvalidAndConflictingRecordsAreReportedNotRewritten() {
        try (Fixture f = new Fixture()) {
            Map<String,Object> partial = bar(M); partial.put("partial", true);
            Map<String,Object> conflict = bar(M + 60000); conflict.put("historySource", "OKX:Crypto:BTCUSDT");
            Map<String,Object> invalid = bar(M + 120000); invalid.put("high_price", 80d);
            f.store.sourceCandles(1, "1m", List.of(partial, conflict, invalid, bar(M + 180000)), M + 239999);
            List<Map<String,Object>> before = f.raw();
            SourceHistoryGapRepair.Window w = f.inspect("1m", 4, M + 239999);
            assertEquals(List.of("existing_partial_or_invalid", "source_conflict", "existing_partial_or_invalid"), w.gaps.stream().map(g -> g.get("reason")).collect(java.util.stream.Collectors.toList()));
            assertFalse(w.needsSource());
            SourceHistoryGapRepair.Receipt receipt = f.repair.insert(w, response(List.of(bar(M), bar(M + 60000), bar(M + 120000), bar(M + 180000))), System.currentTimeMillis());
            assertEquals(0, receipt.inserted); assertEquals(before, f.raw());
        }
    }
    @Test void completedMinutesUpdateSourceRevisionButNeverQuotesControlsOrMoney() {
        try (Fixture f = new Fixture()) {
            long now = System.currentTimeMillis();
            new PersistentPriceControl(f.store).pump(f.config, new LinkedHashMap<>(Map.of("price", BigDecimal.valueOf(90), "timestamp", now,
                "sourceTimestamp", now, "available", true, "expiresAt", now + 60000)), now, 60000);
            Map<String,Object> runtime = f.db.queryForMap("SELECT * FROM market_engine_runtime");
            f.supply("1m", List.of(bar(M), bar(M + 60000))); f.read("1m", 2, M + 119999); f.tick();
            Map<String,Object> after = f.db.queryForMap("SELECT * FROM market_engine_runtime");
            for (String field : List.of("quote_json", "status_json", "control_revision", "snapshot_version", "writer_generation", "committed_at")) assertEquals(runtime.get(field), after.get(field));
            assertEquals(1L, after.get("source_input_revision")); assertEquals(M, after.get("source_dirty_from")); assertEquals(M + 60000, after.get("source_dirty_to"));
            for (String table : List.of("market_control_task", "market_control_sample", "market_source_quote", "market_source_event", "market_source_tick", "market_control_resume"))
                assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
            verify(f.source, never()).getBatchPrices(anyList(), anyString());
            verify(f.source, times(1)).getHistoryWindow(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyLong());
        }
    }
    private static void task(Fixture f, long at) {
        f.store.locked(1, () -> {
            f.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,ended_at,sampled_until) VALUES(1,'protected',1,'BTCUSDT',4,'TARGET','COMPLETED',100,110,10,1,false,2,'LIVE',?,?,?,?,?)", at, at, at + 10000, at + 10000, at + 10000);
            f.db.update("INSERT INTO market_control_flow(tenant_id,task_id,options_json,state,last_price,last_at,finished_at) VALUES(1,'protected','{}','SOURCE',110,?,?)", at + 10000, at + 10000);
            f.db.update("INSERT INTO market_control_publication(tenant_id,task_id,published_at,from_at,to_at) VALUES(1,'protected',?,?,?)", at + 11000, at, at + 10000);
            return null;
        });
    }
    private static Map<String,Object> controlledBars(Fixture f, long cursor) {
        Map<String,Object> result = new TreeMap<>();
        ControlledKlineMerger merger = new ControlledKlineMerger(f.store);
        for (String period : KlineIntervals.PUBLIC) {
            List<Map<String,Object>> bars = ControlHistoryStore.rows(merger.merge(1, period, 1000, cursor, response(List.of()), null, SourceHistoryGapRepair.continuous(f.config.getSourceCategory())));
            List<Map<String,Object>> controlled = bars.stream().filter(row -> Boolean.TRUE.equals(row.get("controlled"))).collect(java.util.stream.Collectors.toList());
            assertFalse(controlled.isEmpty(), period);
            result.put(period, controlled);
        }
        return result;
    }
    @Test void forexQuoteSnapshotsNeverBecomeMinutesOrFalseHistoryGapsAndKeepControls() {
        try (Fixture f = new Fixture()) { verifyForexQuoteSnapshots(f); }
    }
    static void verifyForexQuoteSnapshots(Fixture f) {
        f.config.setSourceCategory("Forex"); f.config.setMarketSource("yahoo");
        for (String period : KlineIntervals.PUBLIC) f.store.sourceCandles(1, period, List.of(bar(SourceHistoryGapRepair.start(period, M))), System.currentTimeMillis());
        Map<String,Object> oldSnapshot = bar(M + 15000);
        f.store.locked(1, () -> {
            f.db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES(1,1,'1m',?,?,?)",
                M + 15000, f.store.encode(oldSnapshot), M + 16000); return null;
        });
        SourceHistoryGapRepair.Window window = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, "1m", 200,
            M + 119999, "Yahoo", List.of(oldSnapshot, bar(M + 25000)), false));
        assertTrue(window.gaps.isEmpty(), "quote-time snapshots are not missing/partial period candles");
        task(f, M + 60000);
        f.store.locked(1, () -> {
            f.store.generatedPoints("protected", 1, List.of(new ControlHistoryStore.PricePoint(M + 60000, BigDecimal.valueOf(105))));
            return null;
        });
        Map<String,Object> controls = controlledBars(f, M + 119999);
        List<Map<String,Object>> prior = f.raw();
        List<Map<String,Object>> facts = f.db.queryForList("SELECT * FROM market_control_sample ORDER BY generated_at");
        Map<String,Object> changed = bar(M + 15000); changed.put("close_price", 93d);
        f.store.sourceCandles(List.of(1L), "1m", List.of(changed, bar(M + 25000)), System.currentTimeMillis(), Map.of(1L, f.config));
        assertEquals(prior, f.raw(), "old snapshots remain untouched; new quote snapshots are not inserted");
        assertEquals(controls, controlledBars(f, M + 119999));
        assertEquals(facts, f.db.queryForList("SELECT * FROM market_control_sample ORDER BY generated_at"));
        Map<String,Object> refreshed = bar(M); refreshed.put("close_price", 94d); refreshed.put("volume", 999d);
        f.store.sourceCandles(List.of(1L), "1m", List.of(refreshed, bar(M + 120000)), System.currentTimeMillis(), Map.of(1L, f.config));
        assertEquals(controls, controlledBars(f, M + 179999), "legal neighboring minutes and refreshed old source must preserve all controlled periods");
        for (Map<String,Object> old : prior) assertTrue(f.raw().contains(old), "existing source body and received_at stay exact");
        Map<String,Object> saved = f.store.decode(f.db.queryForObject("SELECT body FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 AND period='1m' AND candle_at=?", String.class, M + 120000));
        assertEquals(new TreeSet<>(List.of("5m", "15m", "30m", "1h", "1d", "1w")), new TreeSet<>((List<String>)saved.get(SourceHistoryGapRepair.AGGREGATE_EXCLUSIONS)));
        long unrelated = M + 30 * 86400000L;
        for (String period : List.of("1h", "1d", "1w")) {
            long far = SourceHistoryGapRepair.start(period, unrelated);
            f.store.locked(1, () -> {
                assertTrue(f.repair.blocksNewSourcePeriod(f.config, period, M + 120000), "nearby coarse anchors stay protected");
                assertFalse(f.repair.blocksNewSourcePeriod(f.config, period, far), "distant same-phase anchors remain allowed");
                assertTrue(f.repair.blocksNewSourcePeriod(f.config, period, far + 30 * 60000L), "distant phase changes remain protected");
                return null;
            });
        }
        f.store.sourceCandles(List.of(1L), "1m", List.of(bar(unrelated)), unrelated + 60000, Map.of(1L, f.config));
        assertEquals(1, f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 AND period='1m' AND candle_at=?", Integer.class, unrelated), "unrelated future minutes still ingest");
    }
    @Test void forexNativeWeeksCrossUtcMondayAndOverlappingHourAnchorsKeepAllControls() {
        long monday = SourceHistoryGapRepair.start("1w", M);
        for (int scenario = 0; scenario < 2; scenario++) try (Fixture f = new Fixture()) {
            f.config.setSourceCategory("Forex"); f.config.setMarketSource("yahoo");
            long control = monday + (scenario == 0 ? 10 : 55) * 60000L;
            long candidate = monday + (scenario == 0 ? -1 : 65) * 60000L;
            for (String period : KlineIntervals.PUBLIC) {
                long anchor = SourceHistoryGapRepair.start(period, control);
                if (scenario == 0 && List.of("1d", "1w").contains(period)) anchor = monday - 3 * 3600000L;
                if (scenario == 1 && "1h".equals(period)) anchor = monday + 30 * 60000L;
                f.store.sourceCandles(1, period, List.of(bar(anchor)), System.currentTimeMillis());
            }
            f.store.sourceCandles(1, "1h", List.of(bar(scenario == 0 ? monday - 3600000L : monday + 3600000L)), System.currentTimeMillis());
            task(f, control);
            f.store.locked(1, () -> { f.store.generatedPoints("protected", 1, List.of(new ControlHistoryStore.PricePoint(control, BigDecimal.valueOf(105)))); return null; });
            Map<String,Object> before = controlledBars(f, control + 2 * 3600000L);
            List<Map<String,Object>> prior = f.raw();
            f.store.sourceCandles(List.of(1L), "1m", List.of(bar(candidate)), System.currentTimeMillis(), Map.of(1L, f.config));
            if (scenario == 0) {
                Map<String,Object> saved = f.store.decode(f.db.queryForObject("SELECT body FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 AND period='1m' AND candle_at=?", String.class, candidate));
                assertTrue(((List<?>)saved.get(SourceHistoryGapRepair.AGGREGATE_EXCLUSIONS)).contains("1w"));
            } else assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 AND period='1m' AND candle_at=?", Integer.class, candidate), "ambiguous hourly phase cannot certify a page-dependent fallback");
            assertEquals(before, controlledBars(f, control + 2 * 3600000L));
            for (Map<String,Object> row : prior) assertTrue(f.raw().contains(row));
        }
    }
    @Test void forexMissingRelevantNativeAnchorRefusesOnlyRelatedMinutes() {
        try (Fixture f = new Fixture()) {
            f.config.setSourceCategory("Forex"); f.config.setMarketSource("yahoo");
            for (String period : KlineIntervals.PUBLIC) if (!"1h".equals(period))
                f.store.sourceCandles(1, period, List.of(bar(SourceHistoryGapRepair.start(period, M))), System.currentTimeMillis());
            task(f, M + 60000);
            List<Map<String,Object>> prior = f.raw();
            long unrelated = M + 30 * 86400000L;
            f.store.sourceCandles(List.of(1L), "1m", List.of(bar(M + 120000), bar(unrelated)), System.currentTimeMillis(), Map.of(1L, f.config));
            assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 AND period='1m' AND candle_at=?", Integer.class, M + 120000));
            assertEquals(1, f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 AND period='1m' AND candle_at=?", Integer.class, unrelated));
            for (Map<String,Object> row : prior) assertTrue(f.raw().contains(row));
        }
    }
    @Test void distantNewHourlyPhaseCannotMoveExistingControlFallback() {
        try (Fixture f = new Fixture()) {
            f.config.setSourceCategory("Forex"); f.config.setMarketSource("yahoo");
            long hour = SourceHistoryGapRepair.start("1h", M), control = hour + 2 * 86400000L + 55 * 60000L;
            f.store.sourceCandles(1, "1h", List.of(bar(hour)), System.currentTimeMillis());
            task(f, control);
            f.store.locked(1, () -> { f.store.generatedPoints("protected", 1, List.of(new ControlHistoryStore.PricePoint(control, BigDecimal.valueOf(105)))); return null; });
            ControlledKlineMerger merger = new ControlledKlineMerger(f.store);
            Map<String,Object> before = merger.merge(1, "1h", 100, control + 60000L, response(List.of()), null, false);
            assertTrue(ControlHistoryStore.rows(before).stream().anyMatch(row -> Boolean.TRUE.equals(row.get("controlled"))));
            List<Map<String,Object>> prior = f.raw();
            long candidate = hour - 30 * 60000L;
            f.store.sourceCandles(List.of(1L), "1h", List.of(bar(candidate)), System.currentTimeMillis(), Map.of(1L, f.config));
            assertEquals(before, merger.merge(1, "1h", 100, control + 60000L, response(List.of()), null, false));
            assertEquals(prior, f.raw());
            assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 AND period='1h' AND candle_at=?", Integer.class, candidate));
            long samePhase = hour - 3600000L;
            f.store.sourceCandles(List.of(1L), "1h", List.of(bar(samePhase)), System.currentTimeMillis(), Map.of(1L, f.config));
            assertEquals(1, f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 AND period='1h' AND candle_at=?", Integer.class, samePhase));
            assertEquals(ControlHistoryStore.rows(before).stream().filter(row -> Boolean.TRUE.equals(row.get("controlled"))).collect(java.util.stream.Collectors.toList()),
                ControlHistoryStore.rows(merger.merge(1, "1h", 100, control + 60000L, response(List.of()), null, false)).stream().filter(row -> Boolean.TRUE.equals(row.get("controlled"))).collect(java.util.stream.Collectors.toList()));
        }
    }
    @Test void ordinaryNewNativeCoarseAnchorsCannotRelocateExistingControlHistory() {
        try (Fixture f = new Fixture()) {
            f.config.setSourceCategory("Forex"); f.config.setMarketSource("yahoo");
            long hour = SourceHistoryGapRepair.start("1h", M), control = hour + 55 * 60000L;
            for (String period : KlineIntervals.PUBLIC) f.store.sourceCandles(1, period,
                List.of(bar("1h".equals(period) ? hour + 30 * 60000L : SourceHistoryGapRepair.start(period, control))), System.currentTimeMillis());
            task(f, control);
            f.store.locked(1, () -> { f.store.generatedPoints("protected", 1, List.of(new ControlHistoryStore.PricePoint(control, BigDecimal.valueOf(105)))); return null; });
            Map<String,Object> before = controlledBars(f, control + 60000);
            List<Map<String,Object>> prior = f.raw();
            for (String period : List.of("1h", "1d", "1w")) {
                long candidate = "1h".equals(period) ? hour + 45 * 60000L : SourceHistoryGapRepair.start(period, control) + 30 * 60000L;
                f.store.sourceCandles(List.of(1L), period, List.of(bar(candidate)), System.currentTimeMillis(), Map.of(1L, f.config));
                assertEquals(before, controlledBars(f, control + 60000));
                assertEquals(prior, f.raw());
            }
            long unrelated = control + 30 * 86400000L;
            for (String period : List.of("1h", "1d", "1w")) {
                long candidate = SourceHistoryGapRepair.start(period, unrelated) + ("1h".equals(period) ? 30 * 60000L : 0);
                f.store.sourceCandles(List.of(1L), period, List.of(bar(candidate)), System.currentTimeMillis(), Map.of(1L, f.config));
                assertEquals(1, f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 AND period=? AND candle_at=?", Integer.class, period, candidate));
            }
        }
    }
    @Test void sameWeek133MinuteGapFillsWithoutChangingControlsOrExistingPartialDuringOrdinaryRefresh() {
        try (Fixture f = new Fixture()) { verifySameWeek133MinuteGap(f); }
    }
    static void verifySameWeek133MinuteGap(Fixture f) {
        long first = M + 86400000L, cursor = first + 133 * 60000L - 1;
        for (String period : KlineIntervals.PUBLIC) f.store.sourceCandles(1, period, List.of(bar(SourceHistoryGapRepair.start(period, M))), System.currentTimeMillis());
        Map<String,Object> partial = bar(first - 60000); partial.put("partial", true);
        f.store.sourceCandles(1, "1m", List.of(partial), first - 1000);
        task(f, M + 60000);
        f.store.locked(1, () -> {
            List<ControlHistoryStore.PricePoint> points = new ArrayList<>();
            for (int i = 0; i <= 10; i++) points.add(new ControlHistoryStore.PricePoint(M + 60000 + i * 1000L, BigDecimal.valueOf(105)));
            f.store.generatedPoints("protected", 1, points); return null;
        });
        List<Map<String,Object>> partialBefore = f.db.queryForList("SELECT * FROM market_source_candle WHERE candle_at=? AND period='1m'", first - 60000);
        Map<String,Object> before = controlledBars(f, cursor);
        List<Map<String,Object>> complete = new ArrayList<>();
        for (int i = 0; i < 133; i++) {
            Map<String,Object> row = bar(first + i * 60000L); row.put("high_price", 900d); row.put("close_price", 800d); complete.add(row);
        }
        f.supply("1m", complete);
        assertEquals(true, f.metadata(f.read("1m", 200, cursor)).get("pending"));
        f.retryTick();
        assertEquals(133, f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE period='1m' AND candle_at>=? AND candle_at<=?", Integer.class, first, cursor));
        assertEquals(before, controlledBars(f, cursor));
        Map<String,Object> inserted = f.store.decode(f.db.queryForObject("SELECT body FROM market_source_candle WHERE period='1m' AND candle_at=?", String.class, first));
        assertEquals(List.of("1w"), inserted.get("controlledAggregateExcludedPeriods"));
        assertEquals(133, ControlHistoryStore.rows(f.read("1m", 200, cursor)).stream()
            .filter(row -> ControlHistoryStore.time(row) >= first && ControlHistoryStore.time(row) <= cursor).count());
        List<Map<String,Object>> refreshed = new ArrayList<>(complete); refreshed.add(bar(first - 60000));
        when(f.source.getKline("BTCUSDT", "1m", 200, "Crypto")).thenReturn(response(refreshed));
        f.market.getKline("BTCUSDT", "1m", 200, "Crypto"); f.retryTick();
        assertEquals(before, controlledBars(f, cursor));
        assertEquals(partialBefore, f.db.queryForList("SELECT * FROM market_source_candle WHERE candle_at=? AND period='1m'", first - 60000));
        assertEquals(List.of("1w"), f.store.decode(f.db.queryForObject("SELECT body FROM market_source_candle WHERE period='1m' AND candle_at=?", String.class, first)).get("controlledAggregateExcludedPeriods"));
        long openAt = cursor + 1;
        Map<String,Object> open = bar(openAt); open.put("high_price", 1200d); open.put("partial", true);
        ReflectionTestUtils.invokeMethod(f.store, "sourceCandles", List.of(1L), "1m", List.of(open), openAt + 1000, Map.of(1L, f.config));
        assertEquals(before, controlledBars(f, cursor));
        ReflectionTestUtils.invokeMethod(f.store, "sourceCandles", List.of(1L), "1m", List.of(bar(openAt)), openAt + 60001, Map.of(1L, f.config));
        assertEquals(before, controlledBars(f, cursor));
        // A later control in another period can still use the newly repaired source minutes.
        f.store.locked(1, () -> { f.store.manualPoint(1, first + 61000, BigDecimal.valueOf(110)); return null; });
        Map<String,Object> later = new ControlledKlineMerger(f.store).merge(1, "5m", 1000, cursor, response(List.of()), null);
        Map<String,Object> newControl = ControlHistoryStore.rows(later).stream().filter(row -> ControlHistoryStore.time(row) == first).findFirst().orElseThrow();
        assertEquals(5, newControl.get("minuteCount"));
        Map<String,Object> changed = bar(first); changed.put("high_price", 1600d); changed.put("close_price", 1400d);
        ReflectionTestUtils.invokeMethod(f.store, "sourceCandles", List.of(1L), "1m", List.of(changed), System.currentTimeMillis(), Map.of(1L, f.config));
        Map<String,Object> retained = new ControlledKlineMerger(f.store).merge(1, "5m", 1000, cursor, response(List.of()), null);
        assertEquals(newControl, ControlHistoryStore.rows(retained).stream().filter(row -> ControlHistoryStore.time(row) == first).findFirst().orElseThrow());
        f.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(2,1)");
        TradingSymbol alias = new TradingSymbol(); alias.setId(2L); alias.setTenantId(1L); alias.setSymbol("BTCUSDT");
        alias.setSourceCategory("Crypto"); alias.setMarketSource("binance");
        Map<String,Object> aliased = bar(openAt + 60000); aliased.put("controlledAggregateExcludedPeriods", List.of("5m"));
        ReflectionTestUtils.invokeMethod(f.store, "sourceCandles", List.of(2L, 1L), "1m", List.of(aliased), System.currentTimeMillis(), Map.of(1L, f.config, 2L, alias));
        assertEquals(List.of("1w"), f.store.decode(f.db.queryForObject("SELECT body FROM market_source_candle WHERE symbol_id=1 AND period='1m' AND candle_at=?", String.class, openAt + 60000)).get("controlledAggregateExcludedPeriods"));
        assertFalse(f.store.decode(f.db.queryForObject("SELECT body FROM market_source_candle WHERE symbol_id=2 AND period='1m' AND candle_at=?", String.class, openAt + 60000)).containsKey("controlledAggregateExcludedPeriods"));
    }
    @Test void nonUtcLegacyCoarseAnchorKeepsRepairBlockedInsteadOfChangingItsControlBucket() {
        try (Fixture f = new Fixture()) {
            task(f, M + 60000);
            f.store.sourceCandles(1, "1h", List.of(bar(SourceHistoryGapRepair.start("1h", M) + 1800000)), System.currentTimeMillis());
            SourceHistoryGapRepair.Window window = f.inspect("1m", 2, M - 1);
            assertFalse(window.needsSource());
            assertEquals("source_period_anchor_unverified", window.gaps.get(0).get("reason"));
            assertEquals(2L, f.metadata(f.read("1m", 2, M - 1)).get("protected"));
            assertEquals(0, f.repair.insert(window, response(List.of(bar(M - 120000), bar(M - 60000))), System.currentTimeMillis()).inserted);
            ReflectionTestUtils.invokeMethod(f.store, "sourceCandles", List.of(1L), "1m", List.of(bar(M - 60000)), M - 59000, Map.of(1L, f.config));
            assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE period='1m'", Integer.class));
        }
    }
    @Test void anExistingOpenMinuteCannotChangeAControlPublishedAfterItsFirstSourceSnapshot() {
        try (Fixture f = new Fixture()) {
            long openAt = M + 86400000L;
            for (String period : KlineIntervals.PUBLIC) f.store.sourceCandles(1, period, List.of(bar(SourceHistoryGapRepair.start(period, M))), System.currentTimeMillis());
            f.store.sourceCandles(1, "1m", List.of(bar(openAt)), openAt + 1000);
            task(f, M + 60000);
            f.store.locked(1, () -> { f.store.manualPoint(1, M + 61000, BigDecimal.valueOf(105)); return null; });
            Map<String,Object> before = controlledBars(f, openAt + 59999);
            Map<String,Object> changed = bar(openAt); changed.put("high_price", 1500d); changed.put("close_price", 1400d);
            ReflectionTestUtils.invokeMethod(f.store, "sourceCandles", List.of(1L), "1m", List.of(changed), openAt + 2000, Map.of(1L, f.config));
            assertEquals(before, controlledBars(f, openAt + 59999));
        }
    }
    @Test void cryptoSourceMinutesArePrefetchedWithProjectionDisabledAndWithoutAnyChartRequest() {
        try (Fixture f = new Fixture()) {
            com.gtcfesk.exchange.repository.TradingSymbolRepository repository = mock(com.gtcfesk.exchange.repository.TradingSymbolRepository.class);
            when(repository.findAllByTenantId(1L)).thenReturn(List.of(f.config));
            ReflectionTestUtils.setField(f.market, "symbols", repository);
            ReflectionTestUtils.setField(f.market, "redis", mock(RedisMarketService.class));
            when(f.source.getKline("BTCUSDT", "1m", 500, "Crypto")).thenReturn(response(List.of(bar(M), bar(M + 60000))));
            assertEquals(false, ReflectionTestUtils.getField(f.market, "sourceProjectionEnabled"));
            f.market.refreshSymbols(); f.retryTick();
            verify(f.source).getKline("BTCUSDT", "1m", 500, "Crypto");
            assertEquals(2, f.raw().size());
            f.retryTick(); verify(f.source, times(1)).getKline("BTCUSDT", "1m", 500, "Crypto");
        }
    }
    @Test void controlSampleLossFrozenAndSealedFactsAreNeverReconstructed() {
        try (Fixture f = new Fixture()) {
            task(f, M + 60000);
            f.db.update("INSERT INTO market_legacy_minute_snapshot(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?)", M, f.store.encode(bar(M)), M);
            Map<String,List<Map<String,Object>>> before = new TreeMap<>();
            for (String table : List.of("market_control_task", "market_control_flow", "market_control_publication", "market_control_sample", "market_legacy_minute_snapshot", "market_mixed_minute")) before.put(table, f.db.queryForList("SELECT * FROM " + table));
            SourceHistoryGapRepair.Window w = f.inspect("1m", 3, M + 179999);
            assertTrue(w.gaps.stream().anyMatch(g -> "missing_control_samples".equals(g.get("reason"))));
            assertTrue(w.needsSource()); assertEquals(1, f.repair.insert(w, response(List.of(bar(M), bar(M + 60000), bar(M + 120000))), System.currentTimeMillis()).inserted);
            for (String table : before.keySet()) assertEquals(before.get(table), f.db.queryForList("SELECT * FROM " + table));
            assertEquals(1, f.raw().size());
        }
        try (Fixture f = new Fixture()) {
            long cutover = (System.currentTimeMillis() / 60000 + 3) * 60000;
            f.store.historyOrdering.seal(1, cutover, HistoryOrdering.sha("gap-scope"), HistoryOrdering.sha("gap-evidence"), List.of());
            List<Map<String,Object>> sealed = f.db.queryForList("SELECT * FROM market_history_ordering");
            SourceHistoryGapRepair.Window w = f.inspect("5m", 2, M + 599999);
            assertFalse(w.needsSource()); assertEquals("sealed_history", w.gaps.get(0).get("reason"));
            assertEquals(0, f.repair.insert(w, response(List.of(bar(M), bar(M + 300000))), System.currentTimeMillis()).inserted);
            assertEquals(sealed, f.db.queryForList("SELECT * FROM market_history_ordering"));
        }
    }
    @Test void neighboringSourceMinuteCannotRebuildControlledCoarseBoundary() {
        try (Fixture f = new Fixture()) {
            f.store.sourceCandles(1, "5m", List.of(bar(M)), M + 599999);
            f.store.locked(1, () -> { f.store.manualPoint(1, M + 61000, BigDecimal.valueOf(105)); return null; });
            Map<String,Object> external = response(List.of());
            ControlledKlineMerger merger = new ControlledKlineMerger(f.store);
            Map<String,Object> before = merger.merge(1, "5m", 2, M + 299999, external, null);
            SourceHistoryGapRepair.Window w = f.inspect("1m", 5, M + 299999);
            assertTrue(w.needsSource());
            assertEquals(2, f.repair.insert(w, response(List.of(bar(M), bar(M + 120000))), System.currentTimeMillis()).inserted);
            assertEquals(before, merger.merge(1, "5m", 2, M + 299999, external, null));
        }
    }
    @Test void fetchInsertAndRevisionFailuresBackOffRollbackThenRetrySafely() {
        try (Fixture f = new Fixture()) {
            when(f.source.getHistoryWindow(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyLong())).thenThrow(new MarketHttp.Failure("timeout", 0));
            f.read("1m", 2, M + 119999); f.tick();
            assertEquals(1, f.queue().size()); assertTrue(f.raw().isEmpty());
            assertTrue(((Number)ReflectionTestUtils.getField(f.group, "nextKlines")).longValue() > System.currentTimeMillis());
            f.supply("1m", List.of(bar(M), bar(M + 60000)));
            f.db.failInsert = true; f.retryTick(); assertTrue(f.raw().isEmpty());
            f.db.failInsert = false; f.db.failRevision = true; f.retryTick(); assertTrue(f.raw().isEmpty());
            assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM market_engine_runtime", Integer.class));
            f.db.failRevision = false; f.retryTick(); assertEquals(2, f.raw().size());
            List<Map<String,Object>> committed = f.raw(); f.read("1m", 2, M + 119999); f.retryTick(); assertEquals(committed, f.raw());
        }
    }
    @Test void windowCacheFailureAfterCommitRetriesWithoutRewritingFactsAndNeverTouchesLatest() {
        try (Fixture f = new Fixture()) {
            f.supply("1m", List.of(bar(M), bar(M + 60000)));
            boolean[] fail = {false};
            Map<String,Map<String,Object>> cache = new LinkedHashMap<String,Map<String,Object>>() {
                @Override public Set<Map.Entry<String,Map<String,Object>>> entrySet() {
                    if (fail[0]) { fail[0] = false; throw new IllegalStateException("cache invalidation failed"); } return super.entrySet();
                }
            };
            Map<String,Object> latest = response(List.of(bar(M + 120000))); latest.put("fetchedAt", System.currentTimeMillis());
            cache.put("BTCUSDT:1m:200", latest);
            ReflectionTestUtils.setField(f.group, "klines", cache);
            f.read("1m", 2, M + 119999); fail[0] = true; f.tick();
            assertEquals(2, f.raw().size()); assertEquals(1, f.queue().size());
            List<Map<String,Object>> committed = f.raw();
            f.retryTick(); assertEquals(committed, f.raw()); assertSame(latest, cache.get("BTCUSDT:1m:200"));
            assertEquals(1L, f.db.queryForObject("SELECT source_input_revision FROM market_engine_runtime", Long.class));
        }
    }
    @Test void noDataInvalidUpstreamAndOpenCandleAreTerminalNeverFabricated() {
        try (Fixture f = new Fixture()) {
            f.supply("5m", List.of()); f.read("5m", 2, M + 599999); f.tick();
            Map<String,Object> result = f.read("5m", 2, M + 599999);
            assertEquals(false, f.body(result).get("pending")); assertEquals("upstream_no_data", f.body(result).get("missingData"));
            assertEquals(0, f.queue().size()); assertTrue(f.raw().isEmpty());
            long current = SourceHistoryGapRepair.start("5m", System.currentTimeMillis());
            SourceHistoryGapRepair.Window w = f.inspect("5m", 2, current + 299999);
            assertTrue(w.to < current);
            Map<String,Object> bad = bar(w.to); bad.put("volume", -1d);
            SourceHistoryGapRepair.Receipt receipt = f.repair.insert(w, response(List.of(bad, bar(current))), System.currentTimeMillis());
            assertEquals(0, receipt.inserted); assertTrue(receipt.gaps.stream().anyMatch(g -> "invalid_upstream_candle".equals(g.get("reason"))));
        }
    }
    @Test void periodSourceRouteAndTenantAreIsolatedAndWriterAuthorityStillFences() throws Exception {
        try (Fixture f = new Fixture()) {
            SourceHistoryGapRepair.Window w = f.inspect("1m", 2, M + 119999);
            f.repair.insert(w, response(List.of(bar(M), bar(M + 60000))), System.currentTimeMillis());
            assertTrue(f.inspect("5m", 2, M + 599999).needsSource(), "1m is not the native 5m source table");
            f.db.update("UPDATE trading_symbol SET market_source='changed',row_version=1 WHERE id=1");
            assertThrows(IllegalStateException.class, () -> f.repair.insert(w, response(List.of(bar(M))), System.currentTimeMillis()));
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try { worker.submit(() -> { try (TenantContext.Scope ignored = TenantContext.open(2L)) {
                assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> f.repair.insert(w, response(List.of(bar(M))), System.currentTimeMillis()));
            }}).get(10, TimeUnit.SECONDS); } finally { worker.shutdownNow(); }
            f.db.update("UPDATE trading_symbol SET market_source='binance',row_version=0 WHERE id=1");
            f.db.update("UPDATE market_engine_runtime SET writer_generation=writer_generation+1 WHERE tenant_id=1 AND symbol_id=1");
            assertThrows(com.gtcfesk.exchange.common.BusinessException.class, () -> f.repair.insert(w, response(List.of(bar(M))), System.currentTimeMillis()));
        }
    }
    // Requirement changed: retain Monday weeks, reject retired months; old assertions remain in before.
    @Test void calendarWeeksRemainAndMonthlyRepairIsRejected() {
        long monday = java.time.Instant.parse("2026-02-02T00:00:00Z").toEpochMilli();
        assertEquals(monday, SourceHistoryGapRepair.start("1w", monday + 6 * 86400000L));
        assertThrows(IllegalArgumentException.class, () -> SourceHistoryGapRepair.previous("1M", monday));
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalArgumentException.class, () -> f.inspect("1M", 2, monday));
        }
    }

    @Test void protectionsCommittedWhileNetworkIsInFlightAreRecheckedBeforeInserting() {
        try (Fixture f = new Fixture()) {
            when(f.source.getHistoryWindow(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyLong())).thenAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                f.store.locked(1, () -> { f.store.manualPoint(1, M + 1000, BigDecimal.valueOf(110)); return null; });
                return response(List.of(bar(M), bar(M + 60000)));
            });
            f.read("1m", 2, M + 119999); f.tick();
            assertEquals(1, f.raw().size());
            assertEquals(M + 60000, ((Number)f.raw().get(0).get("candle_at")).longValue());
            assertEquals("protected_mixed_minute", f.body(f.read("1m", 2, M + 119999)).get("missingData"));
            assertEquals(1, f.db.queryForObject("SELECT COUNT(*) FROM market_mixed_minute", Integer.class));
        }
    }
    @Test void nativeSourceAndCompletedFlagAreCheckedAndDuplicateConflictsAreReported() {
        try (Fixture f = new Fixture()) {
            SourceHistoryGapRepair.Window w = f.inspect("1m", 3, M + 179999);
            Map<String,Object> foreign = bar(M); foreign.put("source", "OKX");
            Map<String,Object> partial = bar(M + 60000); partial.put("partial", true);
            Map<String,Object> conflict = bar(M + 120000); conflict.put("close_price", 92d);
            assertEquals(0, f.repair.insert(w, response(List.of(foreign, partial)), System.currentTimeMillis()).inserted);
            SourceHistoryGapRepair.Receipt receipt = f.repair.insert(w, response(List.of(bar(M + 120000), conflict)), System.currentTimeMillis());
            assertEquals(0, receipt.inserted); assertTrue(receipt.gaps.stream().anyMatch(g -> "upstream_conflict".equals(g.get("reason"))));
        }
    }
    @Test void sessionClosuresUseActualSourceCalendarBarsRatherThanPhantomUtcSlots() {
        try (Fixture f = new Fixture()) {
            f.config.setSourceCategory("US"); f.config.setCategory("US"); f.config.setMarketSource("yahoo");
            f.db.update("UPDATE trading_symbol SET source_category='US',market_source='yahoo' WHERE tenant_id=1 AND id=1");
            long friday = java.time.Instant.parse("2026-10-02T19:55:00Z").toEpochMilli();
            long monday = java.time.Instant.parse("2026-10-05T13:30:00Z").toEpochMilli();
            List<Map<String,Object>> actual = List.of(bar(friday), bar(monday));
            f.store.sourceCandles(1, "5m", actual, monday + 600000);
            SourceHistoryGapRepair.Window w = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, "5m", 2, monday + 299999, "Yahoo", actual, false));
            assertTrue(w.gaps.isEmpty(), "weekends, holidays and overnight closures are not synthetic source gaps");
            List<Map<String,Object>> before = f.raw();
            assertEquals(0, f.repair.insert(w, response(actual), System.currentTimeMillis()).inserted);
            assertEquals(before, f.raw());
            SourceHistoryGapRepair.Window daily = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, "1d", 2, monday, "Yahoo", List.of(), false));
            assertFalse(daily.needsSource()); assertEquals("source_calendar_unverified", daily.gaps.get(0).get("reason"));
        }
    }
    @Test void projectionDirtyNotificationCompletesExistingHoleWithoutWideningInitialEligibility() {
        try (Fixture f = new Fixture()) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("s4-history-projection.sql")).execute(f.data);
            SourceHistoryProjector projector = new SourceHistoryProjector(f.store, f.manager);
            f.store.sourceCandles(1, "1m", List.of(bar(M), bar(M + 120000)), System.currentTimeMillis() - 1000);
            Map<String,Object> runtime = f.db.queryForMap("SELECT * FROM market_engine_runtime");
            assertTrue(projector.project(1, (String)runtime.get("owner_id"), ((Number)runtime.get("writer_generation")).longValue(), ((Number)runtime.get("control_revision")).longValue()));
            ReflectionTestUtils.setField(f.market, "sourceProjectionEnabled", true);
            ReflectionTestUtils.setField(f.market, "sourceHistory", projector);
            f.supply("1m", List.of(bar(M), bar(M + 60000), bar(M + 120000)));
            Map<String,Object> pending = f.read("1m", 3, M + 179999); assertEquals(true, f.body(pending).get("pending"));
            f.tick(); assertEquals(M + 60000, f.db.queryForObject("SELECT source_dirty_from FROM market_engine_runtime", Long.class));
            assertTrue(projector.project(1, (String)runtime.get("owner_id"), ((Number)runtime.get("writer_generation")).longValue(), ((Number)runtime.get("control_revision")).longValue()));
            Map<String,Object> ready = f.read("1m", 3, M + 179999);
            assertEquals(false, f.body(ready).get("pending")); assertEquals("SOURCE_1M", f.body(ready).get("projectionKind"));
            assertEquals(3, ControlHistoryStore.rows(ready).size());
            SourceHistoryGapRepair.Window earlier = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, "1m", 2, M - 1, "Binance", List.of(), true));
            assertFalse(earlier.needsSource()); assertEquals("projection_before_initial", earlier.gaps.get(0).get("reason"));
        }
    }
    @Test void exactArchivedConsumerReturnStaysFieldEqualAndDoesNotEvenEnqueueRepair() {
        try (Fixture f = new Fixture()) {
            long cursor = M + 599999, cutover = (System.currentTimeMillis() / 60000 + 3) * 60000;
            Map<String,Object> external = response(List.of());
            @SuppressWarnings("unchecked") Map<String,Object> data = (Map<String,Object>)external.get("data");
            data.put("code", "BTCUSDT"); data.put("source", "Binance");
            Map<String,Object> archived = response(List.of(bar(M), bar(M + 300000)));
            @SuppressWarnings("unchecked") Map<String,Object> savedData = (Map<String,Object>)archived.get("data");
            savedData.put("code", "BTCUSDT"); savedData.put("source", "Binance"); savedData.put("pending", false);
            String body = f.store.encode(archived);
            HistoryOrdering.ArchivedResponse receipt = new HistoryOrdering.ArchivedResponse(f.store.historyOrdering.request(1, "5m", 2, cursor, true, external), body,
                HistoryOrdering.sha(body), HistoryOrdering.sha("test-artifact"), "/0/response");
            f.store.historyOrdering.seal(1, cutover, HistoryOrdering.sha("seal-scope"), HistoryOrdering.sha("seal-evidence"), List.of(receipt));
            List<Map<String,Object>> sealed = f.db.queryForList("SELECT * FROM market_history_response");
            Map<String,Object> runtime = f.db.queryForMap("SELECT * FROM market_engine_runtime"); f.db.writes.clear();
            assertEquals(f.store.decode(body), f.read("5m", 2, cursor));
            assertTrue(f.queue().isEmpty()); assertTrue(f.db.writes.isEmpty());
            assertEquals(runtime, f.db.queryForMap("SELECT * FROM market_engine_runtime"));
            assertEquals(sealed, f.db.queryForList("SELECT * FROM market_history_response"));
        }
    }
    @Test void failedReadSnapshotDoesNotDispatchSourceNotification() {
        try (Fixture f = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> f.store.readConsumerSnapshot(() -> {
                f.market.historicalKline("BTCUSDT", "1m", 2, M + 119999); throw new IllegalStateException("rolled back GET snapshot");
            }));
            assertTrue(f.queue().isEmpty()); verifyNoInteractions(f.source); assertTrue(f.raw().isEmpty());
        }
    }
    @Test void history429OnlySharesRateGateNeverInvalidatesOrNotifiesLiveQuote() {
        try (Fixture f = new Fixture()) {
            long now = System.currentTimeMillis();
            @SuppressWarnings("unchecked") Map<String,Map<String,Object>> quotes = (Map<String,Map<String,Object>>)ReflectionTestUtils.getField(f.group, "quotes");
            Map<String,Object> live = new LinkedHashMap<>(Map.of("price", 100d, "timestamp", now, "fetchedAt", now, "sourceAvailable", true));
            quotes.put("BTCUSDT", live);
            when(f.source.getHistoryWindow(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyLong())).thenThrow(new MarketHttp.Failure("http_429", 5000));
            f.read("5m", 2, M + 599999); f.tick();
            assertSame(live, quotes.get("BTCUSDT"));
            assertEquals(0, ReflectionTestUtils.getField(f.group, "failures")); assertEquals(1, f.queue().size());
            verify(f.source, never()).getBatchPrices(anyList(), anyString());
        }
    }
    @Test void oneExistingControlSampleCannotHideMissingSamplesInsideItsClaimedHistory() {
        try (Fixture f = new Fixture()) {
            task(f, M);
            f.db.update("INSERT INTO market_control_sample(tenant_id,task_id,generated_at,price) VALUES(1,'protected',?,110)", M);
            SourceHistoryGapRepair.Window window = f.inspect("1m", 2, M + 119999);
            assertTrue(window.needsSource()); assertEquals("missing_control_samples", window.gaps.get(0).get("reason"));
            assertEquals(false, window.gaps.get(0).get("recoverable"));
        }
    }
    @Test void delistedLiveTickerDoesNotStarveAvailableHistoricalRepair() {
        try (Fixture f = new Fixture()) {
            ReflectionTestUtils.setField(f.group, "nextQuotes", 0L);
            when(f.source.getBatchPrices(anyList(), anyString())).thenThrow(new MarketHttp.Failure("http_404", 0));
            f.supply("5m", List.of(bar(M), bar(M + 300000)));
            f.read("5m", 2, M + 599999); f.tick();
            assertEquals(2, f.raw().size()); verify(f.source, never()).getBatchPrices(anyList(), anyString());
            assertEquals("complete", f.metadata(f.read("5m", 2, M + 599999)).get("state"));
        }
    }

    @Test void historyOnlyPricesCannotChangeControlStartBasisOrDisplaceItsLatestRowLimit() {
        try (Fixture f = new Fixture()) {
            f.store.sourceCandles(1, "1m", List.of(bar(M - 60000)), System.currentTimeMillis() - 1000);
            PersistentPriceControl control = new PersistentPriceControl(f.store);
            Map<String,Object> before = control.startBasis(f.config, Map.of("available", false), null, System.currentTimeMillis());
            Map<String,Object> candle = f.store.lastClose(1, System.currentTimeMillis());
            List<Map<String,Object>> newer = new ArrayList<>();
            for (int i = 0; i < 200; i++) { Map<String,Object> row = bar(M + i * 60000L); row.put("close_price", 94d); newer.add(row); }
            f.supply("1m", newer); f.read("1m", 200, M + 200 * 60000L - 1); f.tick();
            assertEquals(201, f.raw().size());
            assertEquals(candle, f.store.lastClose(1, System.currentTimeMillis()));
            assertEquals(before, control.startBasis(f.config, Map.of("available", false), null, System.currentTimeMillis()));
            assertTrue(f.raw().stream().filter(row -> ((Number)row.get("candle_at")).longValue() >= M)
                .allMatch(row -> ((String)row.get("body")).contains("\"historyOnly\":true")));
        }
    }
    @Test void presentNativeBarsStillReportMissingOriginalControlSamples() {
        try (Fixture f = new Fixture()) {
            f.store.sourceCandles(1, "5m", List.of(bar(M), bar(M + 300000)), System.currentTimeMillis() - 1000);
            task(f, M);
            SourceHistoryGapRepair.Window window = f.inspect("5m", 2, M + 599999);
            assertFalse(window.needsSource()); assertEquals("missing_control_samples", window.gaps.get(0).get("reason"));
            Map<String,Object> result = f.read("5m", 2, M + 599999);
            assertEquals(false, f.body(result).get("pending")); assertEquals("missing_control_samples", f.body(result).get("missingData"));
            assertTrue(f.queue().isEmpty()); verifyNoInteractions(f.source);
        }
    }
    @Test void alreadyPresentSourceBeforeInitialProjectionIsTerminalRatherThanWaitingForever() {
        try (Fixture f = new Fixture()) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("s4-history-projection.sql")).execute(f.data);
            SourceHistoryProjector projector = new SourceHistoryProjector(f.store, f.manager);
            f.store.sourceCandles(1, "1m", List.of(bar(M), bar(M + 60000)), System.currentTimeMillis() - 1000);
            Map<String,Object> runtime = f.db.queryForMap("SELECT * FROM market_engine_runtime");
            assertTrue(projector.project(1, (String)runtime.get("owner_id"), ((Number)runtime.get("writer_generation")).longValue(), ((Number)runtime.get("control_revision")).longValue()));
            f.store.sourceCandles(1, "1m", List.of(bar(M - 120000), bar(M - 60000)), System.currentTimeMillis() - 1000);
            ReflectionTestUtils.setField(f.market, "sourceProjectionEnabled", true); ReflectionTestUtils.setField(f.market, "sourceHistory", projector);
            Map<String,Object> result = f.read("1m", 2, M - 1);
            assertEquals(false, f.body(result).get("pending")); assertEquals("projection_before_initial", f.body(result).get("missingData"));
            assertEquals("unrepairable", f.metadata(result).get("state")); assertTrue(f.queue().isEmpty()); verifyNoInteractions(f.source);
            assertTrue(ControlHistoryStore.rows(result).isEmpty(), "no compatibility fallback bypass of SOURCE eligibility");
        }
    }

    @Test void completedTargetHoldRemainsProtectedBeyondItsTaskEndEvenWithoutMixedMinutes() {
        try (Fixture f = new Fixture()) {
            task(f, M);
            f.db.update("INSERT INTO market_control_hold(tenant_id,task_id,reference_price,reference_time,last_price,generated_at,source_time,activated_at) VALUES(1,'protected',100,?,110,?,?,?)", M, M + 10000, M, M + 10000);
            List<Map<String,Object>> held = f.db.queryForList("SELECT * FROM market_control_hold");
            SourceHistoryGapRepair.Window window = f.inspect("5m", 2, M + 899999);
            assertFalse(window.needsSource()); assertEquals("protected_control_hold", window.gaps.get(0).get("reason"));
            assertEquals(0, f.repair.insert(window, response(List.of(bar(M + 300000), bar(M + 600000))), System.currentTimeMillis()).inserted);
            assertEquals(held, f.db.queryForList("SELECT * FROM market_control_hold")); assertTrue(f.raw().isEmpty());
        }
    }

    @Test void sessionPartialSourceRecordIsReportedEvenWithAnEmptyRawCache() {
        try (Fixture f = new Fixture()) {
            f.config.setSourceCategory("US"); f.config.setCategory("US"); f.config.setMarketSource("yahoo");
            f.db.update("UPDATE trading_symbol SET source_category='US',market_source='yahoo' WHERE tenant_id=1 AND id=1");
            long cursor = java.time.Instant.parse("2026-10-05T13:35:00Z").toEpochMilli() - 1;
            Map<String,Object> partial = bar(cursor - 299999); partial.put("partial", true);
            f.store.sourceCandles(1, "5m", List.of(partial), System.currentTimeMillis() - 1000);
            List<Map<String,Object>> before = f.raw();
            SourceHistoryGapRepair.Window window = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, "5m", 2, cursor, "Yahoo", List.of(), false));
            assertEquals("existing_partial_or_invalid", window.gaps.get(0).get("reason"));
            assertEquals(0, f.repair.insert(window, response(List.of()), System.currentTimeMillis()).inserted);
            assertEquals(before, f.raw());
        }
    }
    @Test void forexMinuteRepairFetchesNewTailWhilePreservingOldControlsAndNativeAggregates() {
        try (Fixture f = new Fixture()) {
            f.config.setSourceCategory("Forex"); f.config.setCategory("Forex"); f.config.setMarketSource("yahoo");
            f.db.update("UPDATE trading_symbol SET source_category='Forex',market_source='yahoo' WHERE tenant_id=1 AND id=1");
            for (String period : KlineIntervals.PUBLIC)
                f.store.sourceCandles(1, period, List.of(bar(SourceHistoryGapRepair.start(period, M))), System.currentTimeMillis());
            task(f, M);
            f.store.locked(1, () -> { f.store.generatedPoints("protected", 1, List.of(new ControlHistoryStore.PricePoint(M, BigDecimal.valueOf(105)))); return null; });
            long tail = M + 120000, cursor = tail + 59999;
            Map<String,Object> controls = controlledBars(f, cursor);
            List<Map<String,Object>> prior = f.raw();
            SourceHistoryGapRepair.Window window = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, "1m", 200, cursor, "Yahoo", List.of(), false));
            assertTrue(window.discovery, "old control minutes must not block fetching the newer session tail");
            Map<String,Object> changed = bar(M); changed.put("high_price", 900d);
            Object forexGroup = ((Map<?,?>)ReflectionTestUtils.getField(marketState(f.market), "groups")).get("Forex");
            ReflectionTestUtils.setField(forexGroup, "codes", List.of("BTCUSDT"));
            ReflectionTestUtils.setField(forexGroup, "nextQuotes", Long.MAX_VALUE);
            when(f.source.getHistoryWindow(eq("BTCUSDT"), eq("1m"), anyInt(), eq("Forex"), anyLong(), anyLong()))
                .thenAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return response(List.of(changed, bar(tail))); });
            assertEquals(true, f.body(f.read("1m", 200, cursor)).get("pending"));
            ReflectionTestUtils.invokeMethod(f.market, "tick", forexGroup);
            Map<String,Object> ready = f.read("1m", 200, cursor);
            assertEquals(false, f.body(ready).get("pending"));
            assertEquals(1, f.metadata(ready).get("inserted"));
            assertEquals(tail, ControlHistoryStore.rows(ready).stream().mapToLong(ControlHistoryStore::time).max().orElseThrow());
            assertEquals(controls, controlledBars(f, cursor), "all controlled native periods remain unchanged");
            for (Map<String,Object> row : prior) assertTrue(f.raw().contains(row), "existing source body and received_at remain exact");
            assertEquals(1, f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE period='1m' AND candle_at=?", Integer.class, tail));
            Map<String,Object> inserted = f.store.decode(f.db.queryForObject("SELECT body FROM market_source_candle WHERE period='1m' AND candle_at=?", String.class, tail));
            assertTrue(((List<?>)inserted.get(SourceHistoryGapRepair.AGGREGATE_EXCLUSIONS)).contains("1w"));
            for (int refresh = 1; refresh <= 2; refresh++) {
                long next = tail + refresh * 60000L;
                when(f.source.getHistoryWindow(eq("BTCUSDT"), eq("1m"), anyInt(), eq("Forex"), anyLong(), anyLong()))
                    .thenReturn(response(List.of(bar(next))));
                assertEquals(true, f.body(f.read("1m", 200, next + 59999)).get("pending"));
                ReflectionTestUtils.setField(forexGroup, "nextKlines", 0L);
                ReflectionTestUtils.invokeMethod(f.market, "tick", forexGroup);
                assertEquals(next, ControlHistoryStore.rows(f.read("1m", 200, next + 59999)).stream().mapToLong(ControlHistoryStore::time).max().orElseThrow());
                assertEquals(controls, controlledBars(f, next + 59999));
            }
            Map<String,Object> userChart = f.store.readConsumerSnapshot(() -> f.market.internalKline("BTCUSDT", "1m", 200));
            Map<String,Object> orderChart = f.read("1m", 200, tail + 179999);
            Map<String,Object> userLatest = ControlHistoryStore.rows(userChart).stream().max(Comparator.comparingLong(ControlHistoryStore::time)).orElseThrow();
            Map<String,Object> orderLatest = ControlHistoryStore.rows(orderChart).stream().max(Comparator.comparingLong(ControlHistoryStore::time)).orElseThrow();
            assertEquals(userLatest, orderLatest, "latest completed OHLC must match the customer chart reader");
        }
    }
    @Test void forexMinuteRepairIsolatesNewMinutesWhenLegacyNativeAnchorsAreUnverified() {
        try (Fixture f = new Fixture()) {
            f.config.setSourceCategory("Forex"); f.config.setCategory("Forex"); f.config.setMarketSource("yahoo");
            f.db.update("UPDATE trading_symbol SET source_category='Forex',market_source='yahoo' WHERE tenant_id=1 AND id=1");
            for (String period : KlineIntervals.PUBLIC) if (!"1h".equals(period))
                f.store.sourceCandles(1, period, List.of(bar(SourceHistoryGapRepair.start(period, M))), System.currentTimeMillis());
            task(f, M);
            f.store.locked(1, () -> { f.store.generatedPoints("protected", 1, List.of(new ControlHistoryStore.PricePoint(M, BigDecimal.valueOf(105)))); return null; });
            List<Map<String,Object>> prior = f.raw();
            ControlledKlineMerger merger = new ControlledKlineMerger(f.store);
            Map<String,Object> before = new TreeMap<>();
            List<String> coarsePeriods = new ArrayList<>(KlineIntervals.PUBLIC); coarsePeriods.remove("1m"); coarsePeriods.add("4h");
            for (String period : coarsePeriods)
                before.put(period, merger.merge(1, period, 1000, M + 179999, response(List.of()), null, false));
            SourceHistoryGapRepair.Window window = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, "1m", 200, M + 179999, "Yahoo", List.of(), false));
            assertTrue(window.discovery);
            SourceHistoryGapRepair.Receipt receipt = f.repair.insert(window, response(List.of(bar(M + 120000))), System.currentTimeMillis());
            assertEquals(1, receipt.inserted);
            for (Map<String,Object> row : prior) assertTrue(f.raw().contains(row));
            Map<String,Object> saved = f.store.decode(f.db.queryForObject("SELECT body FROM market_source_candle WHERE period='1m' AND candle_at=?", String.class, M + 120000));
            assertEquals(new TreeSet<>(coarsePeriods), new TreeSet<>((List<String>)saved.get(SourceHistoryGapRepair.AGGREGATE_EXCLUSIONS)));
            for (String period : coarsePeriods)
                assertEquals(before.get(period), merger.merge(1, period, 1000, M + 179999, response(List.of()), null, false), period);
            assertTrue(f.repair.sourceMinuteProtection(f.config, M + 120000, M + 120000).blocksSourceWrite(M + 120000, true), "ordinary ingestion keeps its existing anchor safety rule");
        }
    }
    @Test void largeProtectedMinuteHistoryDoesNotFreezeFutureForexMinutes() {
        for (String table : List.of("market_mixed_minute", "market_legacy_minute_snapshot")) try (Fixture f = new Fixture()) {
            f.config.setSourceCategory("Forex"); f.config.setCategory("Forex"); f.config.setMarketSource("yahoo");
            f.db.update("UPDATE trading_symbol SET source_category='Forex',market_source='yahoo' WHERE tenant_id=1 AND id=1");
            List<Object[]> protectedRows = new ArrayList<>();
            for (int i = 0; i < 600; i++) {
                long at = M + i * 60000L;
                protectedRows.add(new Object[]{at, f.store.encode(bar(at)), at + 59999});
            }
            f.db.batchUpdate("INSERT INTO " + table + "(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?)", protectedRows);
            List<Map<String,Object>> before = f.db.queryForList("SELECT * FROM " + table + " ORDER BY minute_at");
            long tail = M + 600 * 60000L;
            SourceHistoryGapRepair.Window window = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, "1m", 200, tail + 59999, "Yahoo", List.of(), false));
            assertTrue(window.discovery, "an overflow of old protected rows must not freeze the future");
            SourceHistoryGapRepair.Receipt receipt = f.repair.insert(window, response(List.of(bar(tail - 60000), bar(tail))), System.currentTimeMillis());
            assertEquals(1, receipt.inserted);
            assertEquals("protection_window_limit", receipt.gaps.get(0).get("reason"));
            assertEquals(before, f.db.queryForList("SELECT * FROM " + table + " ORDER BY minute_at"));
            assertEquals(tail, f.db.queryForObject("SELECT MAX(candle_at) FROM market_source_candle WHERE period='1m'", Long.class));
        }
    }
    @Test void sessionMinuteRepairCannotWidenAnUnverifiedContinuousSourceProjectionCalendar() {
        try (Fixture f = new Fixture()) {
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("s4-history-projection.sql")).execute(f.data);
            f.config.setSourceCategory("US"); f.config.setCategory("US"); f.config.setMarketSource("yahoo");
            SourceHistoryGapRepair.Window window = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, "1m", 2, M + 119999, "Yahoo", List.of(), true));
            assertFalse(window.needsSource()); assertEquals("source_calendar_unverified", window.gaps.get(0).get("reason"));
        }
    }

    @Test void exhaustedRetriesCoverTheEntireLastCandleAndCannotImmediatelyRequeueTheSameWindow() {
        try (Fixture f = new Fixture()) {
            when(f.source.getHistoryWindow(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyLong())).thenThrow(new MarketHttp.Failure("timeout", 0));
            f.read("5m", 2, M + 599999); f.tick(); for (int i = 1; i < 6; i++) f.retryTick();
            assertTrue(f.queue().isEmpty());
            for (int i = 0; i < 4; i++) {
                Map<String,Object> failed = f.read("5m", 2, M + 599999);
                assertEquals(false, f.body(failed).get("pending")); assertEquals("source_fetch_or_write_failed", f.body(failed).get("missingData"));
                assertEquals("unrepairable", f.metadata(failed).get("state")); assertTrue(f.queue().isEmpty());
            }
            verify(f.source, times(6)).getHistoryWindow(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyLong());
            @SuppressWarnings("unchecked") Map<String,SourceHistoryGapRepair.Receipt> receipts = (Map<String,SourceHistoryGapRepair.Receipt>)ReflectionTestUtils.getField(f.group, "repairs");
            ReflectionTestUtils.setField(receipts.values().iterator().next(), "at", System.currentTimeMillis() - 300001);
            assertEquals(true, f.body(f.read("5m", 2, M + 599999)).get("pending")); assertEquals(1, f.queue().size());
        }
    }
    @Test void aFutureHistoryCursorFetchesOnlyTheCompletedWindowNotTheCurrentOpenSourceBar() {
        try (Fixture f = new Fixture()) {
            long cursor = System.currentTimeMillis() + 3600000;
            SourceHistoryGapRepair.Window window = f.inspect("5m", 2, cursor);
            when(f.source.getHistoryWindow(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyLong())).thenAnswer(call -> {
                assertEquals(SourceHistoryGapRepair.end("5m", window.to) - 1, ((Number)call.getArgument(5)).longValue());
                assertTrue(((Number)call.getArgument(5)).longValue() < System.currentTimeMillis());
                return response(List.of(bar(window.from), bar(window.to)));
            });
            f.read("5m", 2, cursor); f.tick();
            assertEquals(2, f.raw().size()); assertEquals("complete", f.metadata(f.read("5m", 2, cursor)).get("state"));
        }
    }

    @Test void anEndedTaskWithNoSamplingProgressReportsMissingSamplesRatherThanImplyingNoHistoryWasRequired() {
        try (Fixture f = new Fixture()) {
            task(f, M); f.db.update("UPDATE market_control_task SET sampled_until=? WHERE tenant_id=1 AND id='protected'", M - 1000);
            SourceHistoryGapRepair.Window window = f.inspect("5m", 2, M + 599999);
            assertEquals("missing_control_samples", window.gaps.get(0).get("reason"));
            // Only the first coarse bucket overlaps controls. Its unprotected successor may still be repaired.
            assertTrue(window.needsSource());
            assertEquals(1, f.repair.insert(window, response(List.of(bar(M), bar(M + 300000))), System.currentTimeMillis()).inserted);
            assertEquals(M + 300000, f.raw().get(0).get("candle_at"));
            assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM market_control_sample", Integer.class));
        }
    }

    @Test void nativeMondayWeekBeforeTheControlWindowCannotReanchorLegacyProtectedWeeks() {
        try (Fixture f = new Fixture()) {
            f.store.locked(1, () -> { f.store.manualPoint(1, M + 1000, BigDecimal.valueOf(105)); return null; });
            ControlledKlineMerger merger = new ControlledKlineMerger(f.store);
            Map<String,Object> before = merger.merge(1, "1w", 2, M + 299999, response(List.of()), null);
            long cursor = SourceHistoryGapRepair.start("1w", M) - 1;
            SourceHistoryGapRepair.Window window = f.inspect("1w", 2, cursor);
            assertFalse(window.needsSource()); assertEquals("protected_source_period_anchor", window.gaps.get(0).get("reason"));
            assertEquals(0, f.repair.insert(window, response(List.of(bar(window.from), bar(window.to))), System.currentTimeMillis()).inserted);
            assertEquals(before, merger.merge(1, "1w", 2, M + 299999, response(List.of()), null));
        }
    }
    @Test void aSessionAnchorOutsideTheRequestedPageCannotRebuildAnAnchorlessControlledHour() {
        try (Fixture f = new Fixture()) {
            f.config.setSourceCategory("US"); f.config.setCategory("US"); f.config.setMarketSource("yahoo");
            f.db.update("UPDATE trading_symbol SET source_category='US',market_source='yahoo' WHERE tenant_id=1 AND id=1");
            f.store.locked(1, () -> { f.store.manualPoint(1, M + 1800000, BigDecimal.valueOf(105)); return null; });
            ControlledKlineMerger merger = new ControlledKlineMerger(f.store);
            Map<String,Object> before = merger.merge(1, "1h", 2, M + 1800000, response(List.of()), null, false);
            SourceHistoryGapRepair.Window window = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, "1h", 2, M - 1, "Yahoo", List.of(), false));
            assertFalse(window.needsSource()); assertEquals("protected_source_period_anchor", window.gaps.get(0).get("reason"));
            assertEquals(0, f.repair.insert(window, response(List.of(bar(M - 3600000))), System.currentTimeMillis()).inserted);
            assertEquals(before, merger.merge(1, "1h", 2, M + 1800000, response(List.of()), null, false));
        }
    }

    @Test void sourceWindowInvalidationUsesActualSessionSpanAndStillPreservesLatestOtherPeriodsAndOtherCodes() {
        try (Fixture f = new Fixture()) {
            f.config.setSourceCategory("US"); f.config.setCategory("US"); f.config.setMarketSource("yahoo");
            long friday = java.time.Instant.parse("2026-10-02T19:55:00Z").toEpochMilli();
            long monday = java.time.Instant.parse("2026-10-05T13:30:00Z").toEpochMilli();
            SourceHistoryGapRepair.Window window = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, "5m", 2, friday + 299999, "Yahoo", List.of(), false));
            Map<String,Map<String,Object>> cache = new LinkedHashMap<>(16, .75f, true); // Match the real access-ordered LRU.
            Map<String,Object> cached = response(List.of(bar(friday), bar(monday)));
            String affected = "BTCUSDT:5m:2:" + (monday + 299999);
            cache.put(affected, cached); cache.put("BTCUSDT:5m:2", cached);
            cache.put("BTCUSDT:1m:2:" + (monday + 299999), cached); cache.put("ETHUSDT:5m:2:" + (monday + 299999), cached);
            ForexQuoteMarketService.invalidateHistorySourceCache(cache, window);
            assertFalse(cache.containsKey(affected), "Friday repair overlaps a Monday two-row session page across the weekend");
            assertEquals(3, cache.size()); assertSame(cached, cache.get("BTCUSDT:5m:2"));
            assertTrue(cache.containsKey("BTCUSDT:1m:2:" + (monday + 299999))); assertTrue(cache.containsKey("ETHUSDT:5m:2:" + (monday + 299999)));
        }
    }
    @Test void unsupportedYahooNativePeriodsCannotBeRelabeledFromSmallerSourceCandles() {
        try (Fixture f = new Fixture()) {
            f.config.setSourceCategory("US"); f.config.setCategory("US"); f.config.setMarketSource("yahoo");
            f.db.update("UPDATE trading_symbol SET source_category='US',market_source='yahoo' WHERE tenant_id=1 AND id=1");
            for (String period : List.of("4h")) { // This period is supported internally; public GET already rejects 2h/3m/6h/12h.
                SourceHistoryGapRepair.Window window = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, period, 2, M + 86400000L, "Yahoo", List.of(), false));
                assertFalse(window.needsSource()); assertEquals("unsupported_source_period", window.gaps.get(0).get("reason"));
                assertEquals(0, f.repair.insert(window, response(List.of(bar(M))), System.currentTimeMillis()).inserted,
                    "1m or 60m bars cannot become native " + period + " source facts");
            }
            assertTrue(f.raw().isEmpty());
        }
    }
}
