package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;
import java.nio.file.*;
import static com.gtcfesk.exchange.market.SourceHistoryGapRepairTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Physical InnoDB, production writer-fence triggers, owned disposable database only. */
@EnabledIfEnvironmentVariable(named="KLINE_GAP_MYSQL_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/kline_gap_.*")
class KlineGapBackfillMysqlTest extends TenantMarketTestContext {
    static final List<String> EVIDENCE = List.of("market_control_task", "market_control_plan", "market_control_flow", "market_control_sample",
        "market_control_hold", "market_control_publication", "market_mixed_minute", "market_history_restore_job", "market_history_restore_minute",
        "market_history_ordering", "market_history_response", "market_simulation_source_candle", "market_legacy_minute_snapshot", "market_source_quote", "market_source_tick", "market_source_event");
    static Map<String,Object> evidence(Fixture f) {
        Map<String,Object> result = new TreeMap<>(); for (String table : EVIDENCE) result.put(table, f.db.queryForList("SELECT * FROM " + table + " ORDER BY tenant_id")); return result;
    }
    static Map<String,Object> protectedBars(Fixture f, long at) {
        Map<String,Object> result = new TreeMap<>(); ControlledKlineMerger merger = new ControlledKlineMerger(f.store);
        for (String period : List.of("1m", "5m", "15m", "30m", "1h", "1w")) {
            long bucket = SourceHistoryGapRepair.start(period, at);
            Map<String,Object> page = merger.merge(1, period, 100, at, response(List.of()), null);
            result.put(period, ControlHistoryStore.rows(page).stream().filter(row -> ControlHistoryStore.time(row) == bucket).collect(java.util.stream.Collectors.toList()));
        }
        return result;
    }
    static Map<String,Object> invariant(Fixture f, PersistentPriceControl control, long at, long publicationAt) {
        Map<String,Object> result = new LinkedHashMap<>(); result.put("fixedNow", at);
        result.put("facts", evidence(f)); result.put("authority", f.store.runtime.read(1, false, at));
        result.put("startBasis", control.startBasis(f.config, Map.of("available", false), null, at));
        Map<String,Object> current = protectedBars(f, at), published = protectedBars(f, publicationAt);
        for (Map<String,Object> bars : List.of(current, published)) for (Map.Entry<String,Object> period : bars.entrySet())
            assertFalse(((List<?>)period.getValue()).isEmpty(), "OHLCV comparison must contain the protected period " + period.getKey());
        result.put("protectedBars", current); result.put("publishedBars", published);
        return result;
    }
    @Test void physicalWriterTransactionsConcurrencyRacesAndProtectedAuthority() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(System.getenv("KLINE_GAP_MYSQL_URL"), "root", System.getenv("KLINE_GAP_MYSQL_PASSWORD"));
        try (Fixture f = new Fixture(ds, false)) {
            assertTrue(f.db.queryForObject("SELECT VERSION()", String.class).startsWith("5.7.")); f.store.migrate();
            Path qa = Paths.get(System.getenv("KLINE_GAP_QA"));
            Files.write(qa.resolve("mysql-before.json"), f.store.encode(evidence(f)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SourceHistoryGapRepair.Window window = f.inspect("1m", 2, M + 119999);
            f.db.failInsert = true;
            assertThrows(IllegalStateException.class, () -> f.repair.insert(window, response(List.of(bar(M), bar(M + 60000))), System.currentTimeMillis()));
            assertTrue(f.raw().isEmpty()); assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM market_engine_runtime", Integer.class));
            f.db.failInsert = false; f.db.failRevision = true;
            assertThrows(IllegalStateException.class, () -> f.repair.insert(window, response(List.of(bar(M), bar(M + 60000))), System.currentTimeMillis()));
            assertTrue(f.raw().isEmpty()); assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM market_engine_runtime", Integer.class));
            f.db.failRevision = false;
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                List<Future<SourceHistoryGapRepair.Receipt>> writes = new ArrayList<>();
                for (int i = 0; i < 8; i++) writes.add(workers.submit(MarketSqlFixture.inTenant(() -> f.repair.insert(window, response(List.of(bar(M), bar(M + 60000))), System.currentTimeMillis()))));
                int inserted = 0; for (Future<SourceHistoryGapRepair.Receipt> write : writes) inserted += write.get(20, TimeUnit.SECONDS).inserted;
                assertEquals(2, inserted); assertEquals(2, f.raw().size());
                assertEquals(1L, f.db.queryForObject("SELECT source_input_revision FROM market_engine_runtime", Long.class));
                workers.submit(() -> { try (TenantContext.Scope ignored = TenantContext.open(2L)) {
                    assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> f.repair.insert(window, response(List.of(bar(M))), System.currentTimeMillis()));
                }}).get(10, TimeUnit.SECONDS);
            } finally { workers.shutdownNow(); }
            List<Map<String,Object>> committed = f.raw();
            assertEquals(0, f.repair.insert(window, response(List.of(bar(M), bar(M + 60000))), System.currentTimeMillis()).inserted);
            assertEquals(committed, f.raw());
            // Existing malformed/conflicting records remain byte-for-byte, including reception time.
            Map<String,Object> partial = bar(M + 120000); partial.put("partial", true);
            Map<String,Object> foreign = bar(M + 180000); foreign.put("historySource", "OKX:Crypto:BTCUSDT");
            f.store.sourceCandles(1, "1m", List.of(partial, foreign), System.currentTimeMillis());
            committed = f.raw(); SourceHistoryGapRepair.Window existingWindow = f.inspect("1m", 2, M + 239999);
            assertEquals(0, f.repair.insert(existingWindow, response(List.of(bar(M + 120000), bar(M + 180000))), System.currentTimeMillis()).inserted);
            assertEquals(committed, f.raw());
            // A route/version change committed during network work must prevent every candidate insert.
            SourceHistoryGapRepair.Window routeWindow = f.inspect("5m", 2, M + 599999);
            f.db.update("UPDATE trading_symbol SET row_version=1 WHERE tenant_id=1 AND id=1");
            assertThrows(IllegalStateException.class, () -> f.repair.insert(routeWindow, response(List.of(bar(M), bar(M + 300000))), System.currentTimeMillis()));
            f.db.update("UPDATE trading_symbol SET row_version=0 WHERE tenant_id=1 AND id=1");
            // Cache failure occurs after a real commit; retry cannot repeat source changes or touch latest cache.
            boolean[] failCache = { false };
            Map<String,Map<String,Object>> cache = new LinkedHashMap<String,Map<String,Object>>() {
                @Override public Set<Map.Entry<String,Map<String,Object>>> entrySet() { if (failCache[0]) { failCache[0] = false; throw new IllegalStateException("cache failure after commit"); } return super.entrySet(); }
            };
            Map<String,Object> latest = response(List.of(bar(M + 240000))); latest.put("fetchedAt", System.currentTimeMillis());
            cache.put("BTCUSDT:1m:200", latest); ReflectionTestUtils.setField(f.group, "klines", cache);
            f.supply("5m", List.of(bar(M), bar(M + 300000))); f.read("5m", 2, M + 599999); failCache[0] = true; f.tick();
            assertEquals(1, f.queue().size()); committed = f.raw(); long revision = f.db.queryForObject("SELECT source_input_revision FROM market_engine_runtime", Long.class);
            f.retryTick(); assertTrue(f.queue().isEmpty()); assertEquals(committed, f.raw()); assertSame(latest, cache.get("BTCUSDT:1m:200"));
            assertEquals(revision, f.db.queryForObject("SELECT source_input_revision FROM market_engine_runtime", Long.class));
            // A nonempty published history coexists with the running task; neither is disabled for this proof.
            long publicationAt = M + 30 * 86400000L;
            f.store.locked(1, () -> {
                f.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,ended_at,sampled_until) VALUES(1,'published-proof',1,'BTCUSDT',4,'TARGET','COMPLETED',100,110,10,1,false,2,'LIVE',?,?,?,?,?)", publicationAt, publicationAt, publicationAt + 10000, publicationAt + 10000, publicationAt + 10000);
                f.db.update("INSERT INTO market_control_flow(tenant_id,task_id,options_json,state,last_price,last_at,finished_at) VALUES(1,'published-proof','{}','SOURCE',110,?,?)", publicationAt + 10000, publicationAt + 10000);
                f.db.update("INSERT INTO market_control_publication(tenant_id,task_id,published_at,from_at,to_at) VALUES(1,'published-proof',?,?,?)", publicationAt + 11000, publicationAt, publicationAt + 10000);
                List<ControlHistoryStore.PricePoint> points = new ArrayList<>();
                for (int i = 0; i <= 10; i++) points.add(new ControlHistoryStore.PricePoint(publicationAt + i * 1000, BigDecimal.valueOf(100 + i)));
                f.store.generatedPoints("published-proof", 1, points);
                return null;
            });
            // Keep a real running control task active. Fix quote/time arguments for before/after comparisons.
            f.config.setPricePrecision(2);
            long quoteTime = System.currentTimeMillis(); Map<String,Object> quote = new LinkedHashMap<>(Map.of("price", BigDecimal.valueOf(100), "timestamp", quoteTime,
                "sourceTimestamp", quoteTime, "available", true, "fetchedAt", quoteTime, "expiresAt", quoteTime + 60000));
            PersistentPriceControl control = new PersistentPriceControl(f.store);
            PersistentPriceControl.Task task = control.start(f.config, quote, BigDecimal.valueOf(100), 300, BigDecimal.valueOf(110), 1, false, false, "isolated_running_control");
            long fixedNow = task.startedAt + 1000; control.pump(f.config, quote, fixedNow, 60000);
            Map<String,Object> authority = f.store.runtime.read(1, false, fixedNow);
            Map<String,Object> basis = control.startBasis(f.config, Map.of("available", false), null, fixedNow);
            Map<String,Object> facts = evidence(f), candles = protectedBars(f, fixedNow);
            Map<String,Object> invariantBefore = invariant(f, control, fixedNow, publicationAt);
            Files.write(qa.resolve("mysql-protected-before.json"), f.store.encode(invariantBefore).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            SourceHistoryGapRepair.Window safe = f.inspect("1m", 2, M - 1);
            assertEquals(2, f.repair.insert(safe, response(List.of(bar(M - 120000), bar(M - 60000))), System.currentTimeMillis()).inserted);
            assertEquals(authority, f.store.runtime.read(1, false, fixedNow)); assertEquals(basis, control.startBasis(f.config, Map.of("available", false), null, fixedNow));
            assertEquals(facts, evidence(f)); assertEquals(candles, protectedBars(f, fixedNow));
            Map<String,Object> invariantAfter = invariant(f, control, fixedNow, publicationAt);
            assertEquals(invariantBefore, invariantAfter);
            Files.write(qa.resolve("mysql-protected-after.json"), f.store.encode(invariantAfter).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(1, f.db.queryForObject("SELECT COUNT(*) FROM market_control_publication", Integer.class));
            assertEquals("RUNNING", f.db.queryForObject("SELECT status FROM market_control_task WHERE id=?", String.class, task.id));
            long previousMinute = task.startedAt / 60000 * 60000 - 60000;
            SourceHistoryGapRepair.Window protectedWindow = f.inspect("1m", 2, previousMinute + 59999);
            assertFalse(protectedWindow.needsSource(), "same-week/month control protection reaches neighboring missing minutes");
            assertEquals(0, f.repair.insert(protectedWindow, response(List.of(bar(previousMinute - 60000), bar(previousMinute))), System.currentTimeMillis()).inserted);
            assertEquals(facts, evidence(f)); assertEquals(candles, protectedBars(f, fixedNow));
            // New controls/mixed minutes committed while a provider request is in flight are rechecked.
            long protectedAt = M + 90 * 86400000L;
            when(f.source.getHistoryWindow(anyString(), eq("1m"), anyInt(), anyString(), anyLong(), anyLong())).thenAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                f.store.locked(1, () -> { f.store.manualPoint(1, protectedAt + 1000, BigDecimal.valueOf(105)); return null; });
                return response(List.of(bar(protectedAt), bar(protectedAt + 60000)));
            });
            f.read("1m", 2, protectedAt + 119999); f.retryTick();
            assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE candle_at IN (?,?)", Integer.class, protectedAt, protectedAt + 60000));
            assertEquals(false, f.body(f.read("1m", 2, protectedAt + 119999)).get("pending"));
            // Frozen simulation prefixes are blocked even before the session's start.
            SourceHistoryGapRepair.Window frozen = f.inspect("5m", 2, M + 1199999);
            f.db.update("UPDATE trading_symbol SET random_market_enabled=TRUE,random_market_started_at=? WHERE id=1", M + 6000000);
            assertEquals(0, f.repair.insert(frozen, response(List.of(bar(M + 600000), bar(M + 900000))), System.currentTimeMillis()).inserted);
            f.db.update("UPDATE trading_symbol SET random_market_enabled=FALSE WHERE id=1");
            // Eligibility is rechecked under the writer, never repaired around a SOURCE initial watermark.
            f.db.update("INSERT INTO s4_history_projection_progress(tenant_id,symbol_id,generation,fact_version,input_revision,initial_watermark,watermark,stop_at) VALUES(1,1,1,1,0,?,?,?)", M + 6000000, M + 6000000, M + 6000000);
            SourceHistoryGapRepair.Window projection = f.store.readConsumerSnapshot(() -> f.repair.inspect(f.config, "1m", 2, M + 599999, "Binance", List.of(), true));
            assertFalse(projection.needsSource());
            assertEquals(0, f.repair.insert(projection, response(List.of(bar(M + 480000), bar(M + 540000))), System.currentTimeMillis()).inserted);
            // Expiry inside the acquired writer is enforced by the production InnoDB trigger and rolls back.
            SourceHistoryGapRepair.Window leaseWindow = f.inspect("5m", 2, M + 1799999);
            List<Map<String,Object>> beforeLease = f.raw();
            f.db.expireLeaseBeforeInsert = true;
            RuntimeException expired = assertThrows(RuntimeException.class, () -> f.repair.insert(leaseWindow, response(List.of(bar(M + 1200000))), System.currentTimeMillis()));
            f.db.expireLeaseBeforeInsert = false;
            assertTrue(expired.toString().contains("ENGINE_FENCED")); assertEquals(beforeLease, f.raw());
            assertTrue(f.db.queryForObject("SELECT lease_until FROM market_engine_runtime", Long.class) > System.currentTimeMillis());
            // Lost writer generation is a physical trigger/transaction failure, with unchanged committed facts.
            committed = f.raw(); f.db.update("UPDATE market_engine_runtime SET writer_generation=writer_generation+1 WHERE tenant_id=1 AND symbol_id=1");
            SourceHistoryGapRepair.Window fenced = f.inspect("5m", 2, M + 1799999);
            assertThrows(com.gtcfesk.exchange.common.BusinessException.class, () -> f.repair.insert(fenced, response(List.of(bar(M + 1200000))), System.currentTimeMillis()));
            assertEquals(committed, f.raw());
            Files.write(qa.resolve("mysql-after.json"), f.store.encode(evidence(f)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Map<String,Object> proof = new LinkedHashMap<>(); proof.put("mysqlVersion", f.db.queryForObject("SELECT VERSION()", String.class));
            proof.put("sourceRows", f.raw()); proof.put("protectedBars", candles); proof.put("fixedNow", fixedNow); proof.put("authority", authority); proof.put("startBasis", basis);
            proof.put("sourceInputRevision", f.db.queryForObject("SELECT source_input_revision FROM market_engine_runtime", Long.class));
            proof.put("concurrentRequests", 8); proof.put("insertFailureRollback", true); proof.put("revisionFailureRollback", true); proof.put("cacheRetryIdempotent", true); proof.put("activeControlUnchanged", true);
            proof.put("writerLeaseExpiryRollback", true); proof.put("publicationUnchanged", true);
            Files.write(qa.resolve("mysql-proof.json"), f.store.encode(proof).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
