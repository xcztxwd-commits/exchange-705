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
        boolean failInsert, failRevision;
        final List<String> writes = Collections.synchronizedList(new ArrayList<>());
        ObservedJdbc(DriverManagerDataSource data) { super(data); }
        @Override public int update(String sql, Object... args) {
            writes.add(sql); int changed = super.update(sql, args);
            if (failInsert && sql.startsWith("INSERT INTO market_source_candle")) throw new IllegalStateException("injected source insert failure");
            if (failRevision && sql.startsWith("UPDATE market_engine_runtime SET source_input_revision=")) throw new IllegalStateException("injected source revision failure");
            return changed;
        }
    }
    static final class Fixture implements AutoCloseable {
        final DriverManagerDataSource data = new DriverManagerDataSource("jdbc:h2:mem:gap_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        final ObservedJdbc db = new ObservedJdbc(data);
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(data);
        final ControlHistoryStore store = new ControlHistoryStore(db, manager);
        final SourceHistoryGapRepair repair = new SourceHistoryGapRepair(store);
        final ForexQuoteMarketService market = new ForexQuoteMarketService();
        final MarketQuoteSource source = mock(MarketQuoteSource.class);
        final TradingSymbol config = new TradingSymbol();
        final Object group;
        Fixture() {
            MarketSqlFixture.schema(db);
            for (String column : new String[]{"symbol VARCHAR(32) DEFAULT 'BTCUSDT'", "alltick_symbol VARCHAR(64)", "market_source VARCHAR(16) DEFAULT 'binance'", "source_category VARCHAR(32) DEFAULT 'Crypto'", "row_version BIGINT DEFAULT 0", "random_market_enabled BOOLEAN DEFAULT FALSE", "random_market_started_at BIGINT", "control_enabled BOOLEAN DEFAULT FALSE"}) db.execute("ALTER TABLE trading_symbol ADD COLUMN " + column);
            db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1)");
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
    private void task(Fixture f, long at) {
        f.store.locked(1, () -> {
            f.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,ended_at,sampled_until) VALUES(1,'protected',1,'BTCUSDT',4,'TARGET','COMPLETED',100,110,10,1,false,2,'LIVE',?,?,?,?,?)", at, at, at + 10000, at + 10000, at + 10000);
            f.db.update("INSERT INTO market_control_flow(tenant_id,task_id,options_json,state,last_price,last_at,finished_at) VALUES(1,'protected','{}','SOURCE',110,?,?)", at + 10000, at + 10000);
            f.db.update("INSERT INTO market_control_publication(tenant_id,task_id,published_at,from_at,to_at) VALUES(1,'protected',?,?,?)", at + 11000, at, at + 10000);
            return null;
        });
    }
    @Test void controlSampleLossFrozenAndSealedFactsAreNeverReconstructed() {
        try (Fixture f = new Fixture()) {
            task(f, M + 60000);
            f.db.update("INSERT INTO market_legacy_minute_snapshot(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?)", M, f.store.encode(bar(M)), M);
            Map<String,List<Map<String,Object>>> before = new TreeMap<>();
            for (String table : List.of("market_control_task", "market_control_flow", "market_control_publication", "market_control_sample", "market_legacy_minute_snapshot", "market_mixed_minute")) before.put(table, f.db.queryForList("SELECT * FROM " + table));
            SourceHistoryGapRepair.Window w = f.inspect("1m", 3, M + 179999);
            assertTrue(w.gaps.stream().anyMatch(g -> "missing_control_samples".equals(g.get("reason"))));
            assertFalse(w.needsSource()); assertEquals(0, f.repair.insert(w, response(List.of(bar(M), bar(M + 60000), bar(M + 120000))), System.currentTimeMillis()).inserted);
            for (String table : before.keySet()) assertEquals(before.get(table), f.db.queryForList("SELECT * FROM " + table));
            assertTrue(f.raw().isEmpty());
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
            assertFalse(w.needsSource());
            assertEquals(0, f.repair.insert(w, response(List.of(bar(M), bar(M + 120000))), System.currentTimeMillis()).inserted);
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
    @Test void calendarPeriodsUseMondayAndNaturalMonthsNotFixedDurations() {
        long monday = java.time.Instant.parse("2026-02-02T00:00:00Z").toEpochMilli();
        assertEquals(monday, SourceHistoryGapRepair.start("1w", monday + 6 * 86400000L));
        long march = java.time.Instant.parse("2026-03-01T00:00:00Z").toEpochMilli();
        assertEquals(java.time.Instant.parse("2026-02-01T00:00:00Z").toEpochMilli(), SourceHistoryGapRepair.previous("1M", march));
        try (Fixture f = new Fixture()) {
            SourceHistoryGapRepair.Window window = f.inspect("1M", 2, march - 1);
            assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), window.from);
            assertEquals(java.time.Instant.parse("2026-02-01T00:00:00Z").toEpochMilli(), window.to);
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
            assertTrue(f.raw().isEmpty());
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
            assertFalse(window.needsSource()); assertEquals("missing_control_samples", window.gaps.get(0).get("reason"));
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
