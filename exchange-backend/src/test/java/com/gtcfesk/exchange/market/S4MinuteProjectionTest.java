package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated H2 or guarded S4 MySQL storage/assembly checks; no application, worker, Redis or source provider is started. */
class S4MinuteProjectionTest extends TenantMarketTestContext {
    private static final long M = 1700000400000L;
    private static final long STOP = M + 119999;
    private final List<Fixture> fixtures = new ArrayList<>();
    static final class Fixture {
        // Reuse the exact localhost/database/server UUID/stage owner guard, never a default application datasource.
        final S4KlineDifferentialTest.ObservedDataSource data = new S4KlineDifferentialTest.ObservedDataSource();
        final JdbcTemplate db = new JdbcTemplate(data);
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(data);
        final MinuteHistoryProjectionStore store = new MinuteHistoryProjectionStore(db, manager);
        Fixture() {
            new ResourceDatabasePopulator(new ClassPathResource("s4-history-projection.sql")).execute(data);
            MarketSqlFixture.schema(db);
            if (data.mysql) new org.springframework.transaction.support.TransactionTemplate(manager).execute(status -> {
                // The independently verified S4 disposable database only. No ownership marker or other stage tables are touched.
                for (String table : Arrays.asList("s4_history_projection_minute", "s4_history_projection_progress", "market_control_plan", "market_control_sample",
                        "market_control_resume", "market_control_hold", "market_control_publication", "market_control_flow", "market_legacy_minute_snapshot",
                        "market_simulation_source_candle", "market_mixed_minute", "market_source_event", "market_source_tick", "market_source_quote", "market_source_candle",
                        "market_control_task", "trading_symbol")) db.update("DELETE FROM " + table + " WHERE tenant_id IN (1,2)");
                return null;
            });
            data.calls.clear(); data.record = true;
        }
        long rows() { return db.queryForObject("SELECT COUNT(*) FROM s4_history_projection_minute", Long.class); }
    }
    private Fixture fixture() { Fixture fixture = new Fixture(); fixtures.add(fixture); return fixture; }
    @AfterEach void saveProjectionEvidence() throws Exception {
        List<Map<String,Object>> evidence = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            fixture.data.record = false;
            Map<String,Object> row = new LinkedHashMap<>(); row.put("database", fixture.data.mysql ? "guarded dedicated S4 MySQL 5.7 small transactional fixture, not capacity evidence" : "isolated H2 development fixture");
            row.put("executed_prepared_sql_and_parameters", new ArrayList<>(fixture.data.calls));
            row.put("projection_rows", fixture.db.queryForList("SELECT * FROM s4_history_projection_minute WHERE tenant_id IN (1,2) ORDER BY tenant_id,symbol_id,minute_at"));
            row.put("progress_rows", fixture.db.queryForList("SELECT * FROM s4_history_projection_progress WHERE tenant_id IN (1,2) ORDER BY tenant_id,symbol_id"));
            if (fixture.data.mysql) {
                row.put("physical_identity", fixture.db.queryForMap("SELECT @@server_uuid AS server_uuid,DATABASE() AS database_name,VERSION() AS version"));
                row.put("owner_marker", fixture.db.queryForList("SELECT owner,stage FROM s4_fixture_identity"));
                row.put("table_engine", fixture.db.queryForList("SELECT TABLE_NAME,ENGINE FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('s4_history_projection_minute','s4_history_projection_progress')"));
            }
            evidence.add(row);
        }
        if (!evidence.isEmpty()) {
            java.nio.file.Path directory = java.nio.file.Paths.get(System.getProperty("s4.evidence.dir", "target/s4-projection")).toAbsolutePath().normalize();
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Files.write(directory.resolve((fixtures.get(0).data.mysql ? "mysql" : "h2") + "-projection-" + UUID.randomUUID() + ".json"),
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence));
        }
    }
    private static Map<String,Object> bar(long minute, String close) {
        BigDecimal price = new BigDecimal(close);
        Map<String,Object> body = new LinkedHashMap<>(); body.put("timestamp", minute);
        body.put("open_price", price); body.put("high_price", price); body.put("low_price", price); body.put("close_price", price);
        body.put("volume", 0); return body;
    }
    private static MinuteHistoryProjection.Block block(long generation, long version, long expected, String one, String two) {
        return MinuteHistoryProjection.calculate(new MinuteHistoryProjection.CompletedFacts(1, generation, version, expected, STOP,
            M, M + 60000, STOP, STOP, Arrays.asList(new MinuteHistoryProjection.SourceMinute(bar(M, one), STOP),
            new MinuteHistoryProjection.SourceMinute(bar(M + 60000, two), STOP)), Collections.emptyList()));
    }
    private static List<Map<String,Object>> rows(Fixture f) { return f.store.readWindow(1, M, M + 60000).minutes; }
    private static <T> T otherThread(long tenant, Supplier<T> work) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(() -> { try (TenantContext.Scope ignored = TenantContext.open(tenant)) { return work.get(); } }).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException(cause);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
        catch (TimeoutException timeout) { throw new AssertionError("independent projection reader timed out", timeout); }
        finally { executor.shutdownNow(); }
    }

    @Test void everyBlockWriteFailureRollsBackBothResultsAndProgressAndRestartRetries() {
        // Two result writes plus the progress write are each interrupted, including the final pre-commit write.
        for (int failure = 1; failure <= 3; failure++) {
            Fixture f = fixture(); f.store.activateGeneration(1, 7, M - 1, STOP);
            assertTrue(f.store.publish(block(7, 1, M - 1, "100.1234567890123456", "101.0000000000000001")));
            List<Map<String,Object>> original = rows(f);
            MinuteHistoryProjectionStore.Progress committed = f.store.progress(1);
            final int interruptedWrite = failure;
            MinuteHistoryProjectionStore broken = new MinuteHistoryProjectionStore(f.db, f.manager, write -> {
                if (write == interruptedWrite) throw new IllegalStateException("injected projection write " + write);
            });
            MinuteHistoryProjection.Block revision = block(7, 2, STOP, "102.0000000000000001", "103.9999999999999999");
            assertThrows(IllegalStateException.class, () -> broken.publish(revision));
            assertEquals(original, rows(f)); assertEquals(2, f.rows());
            assertEquals(committed.hash, f.store.progress(1).hash); assertEquals(1, f.store.progress(1).version);
            MinuteHistoryProjectionStore restarted = new MinuteHistoryProjectionStore(f.db, f.manager);
            assertTrue(restarted.publish(revision)); assertFalse(restarted.publish(revision));
            assertEquals(2, f.rows()); assertEquals(2, restarted.progress(1).version); assertEquals(STOP, restarted.progress(1).watermark);
        }
    }
    @Test void uncommittedRowsAndProgressAreInvisibleOnAnIndependentConnection() {
        Fixture f = fixture(); f.store.activateGeneration(1, 7, M - 1, STOP);
        List<Integer> observedWrites = new ArrayList<>();
        MinuteHistoryProjectionStore writer = new MinuteHistoryProjectionStore(f.db, f.manager, write -> {
            observedWrites.add(write);
            String session = f.data.mysql ? "SELECT CONNECTION_ID()" : "SELECT SESSION_ID()";
            long writerSession = f.db.queryForObject(session, Long.class);
            CountDownLatch readerFinished = new CountDownLatch(1);
            otherThread(1, () -> {
                try (Connection raw = f.data.getConnection()) {
                    JdbcTemplate observer = new JdbcTemplate(new SingleConnectionDataSource(raw, true));
                    assertNotEquals(writerSession, observer.queryForObject(session, Long.class));
                    assertEquals(0L, observer.queryForObject("SELECT COUNT(*) FROM s4_history_projection_minute", Long.class));
                    assertEquals(M - 1, observer.queryForObject("SELECT watermark FROM s4_history_projection_progress WHERE tenant_id=1 AND symbol_id=1", Long.class));
                    assertEquals(0L, observer.queryForObject("SELECT fact_version FROM s4_history_projection_progress WHERE tenant_id=1 AND symbol_id=1", Long.class));
                    readerFinished.countDown(); return null;
                } catch (SQLException invalid) { throw new IllegalStateException(invalid); }
            });
            assertEquals(0L, readerFinished.getCount(), "writer cannot commit before independent reader observes every write point");
        });
        assertTrue(writer.publish(block(7, 1, M - 1, "100.0000000000000001", "101.0000000000000001")));
        assertEquals(Arrays.asList(1, 2, 3), observedWrites); assertEquals(2, f.rows()); assertEquals(STOP, f.store.progress(1).watermark);
    }
    @Test void unknownCommittedReplyRestartDuplicateConflictAndOldWriterFailClosedWithoutDeletingHistory() {
        Fixture f = fixture(); f.store.activateGeneration(1, 7, M - 1, STOP);
        MinuteHistoryProjection.Block first = block(7, 1, M - 1, "100.1234567890123456", "101.1234567890123456");
        f.store.publish(first); // Simulate commit followed by a lost acknowledgement, not a rollback.
        MinuteHistoryProjectionStore restarted = new MinuteHistoryProjectionStore(f.db, f.manager);
        assertFalse(restarted.publish(first)); assertEquals(2, f.rows());
        assertThrows(IllegalStateException.class, () -> restarted.publish(block(7, 1, M - 1, "900", "901")));
        restarted.publish(block(7, 2, STOP, "102.1234567890123456", "103.1234567890123456"));
        assertThrows(IllegalStateException.class, () -> restarted.publish(first));
        List<Map<String,Object>> history = rows(f);
        List<Map<String,Object>> retained = f.db.queryForList("SELECT * FROM s4_history_projection_minute ORDER BY minute_at");
        restarted.activateGeneration(1, 8, M - 1, STOP);
        assertFalse(history.isEmpty()); assertEquals(retained, f.db.queryForList("SELECT * FROM s4_history_projection_minute ORDER BY minute_at"));
        assertTrue(rows(f).isEmpty(), "takeover retains underlying history but hides minutes beyond its reset committed watermark");
        assertTrue(restarted.readWindow(1, M, M + 60000).pending);
        assertEquals("partial", restarted.readWindow(1, M, M + 60000).status);
        assertThrows(IllegalStateException.class, () -> restarted.activateGeneration(1, 7, M - 1, STOP));
        assertThrows(IllegalStateException.class, () -> restarted.publish(block(7, 3, STOP, "900", "901")));
        assertTrue(restarted.publish(block(8, 1, M - 1, "104.1234567890123456", "105.1234567890123456")));
        assertEquals(2, f.rows()); assertEquals(8, restarted.progress(1).generation);
    }
    @Test void newLowerStopAndResetWatermarkCannotExposeFutureOldGenerationMinutes() {
        Fixture f = fixture();
        assertEquals("pending", f.store.readWindow(1, M, M + 60000).status);
        f.store.activateGeneration(1, 7, M - 1, STOP); f.store.publish(block(7, 1, M - 1, "100", "101"));
        assertEquals("available", f.store.readWindow(1, M, M + 60000).status);
        f.store.activateGeneration(1, 8, M - 1, M + 20000);
        assertTrue(rows(f).isEmpty()); assertEquals(2, f.rows(), "future rows are retained, never deleted to meet visibility assertions");
        assertThrows(IllegalStateException.class, () -> f.store.activateGeneration(1, 8, M, M + 20000));
        assertThrows(IllegalStateException.class, () -> f.store.activateGeneration(1, 8, M - 1, M + 20001));
        MinuteHistoryProjection.Block stopped = MinuteHistoryProjection.calculate(new MinuteHistoryProjection.CompletedFacts(1, 8, 1, M - 1, M + 20000,
            M, M, M + 20000, M + 20000, Collections.singletonList(new MinuteHistoryProjection.SourceMinute(bar(M, "102"), M + 20000)), Collections.emptyList()));
        f.store.publish(stopped);
        MinuteHistoryProjectionStore.ReadResult result = f.store.readWindow(1, M, M + 60000);
        assertEquals(1, result.minutes.size()); assertEquals(M, ControlHistoryStore.time(result.minutes.get(0)));
        assertEquals("partial", result.status); assertTrue(result.pending); assertEquals(2, f.rows());
        f.store.activateGeneration(1, 8, M - 1, M + 20000); // Same original parameters remain idempotent after progress moves.
    }
    @Test void sameInputPreservesExistingMultiTaskPrefixPrecisionAndLateSourceCannotClobberIt() {
        Fixture f = fixture();
        ControlHistoryStore legacy = new ControlHistoryStore(f.db, f.manager);
        f.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1)");
        Map<String,Object> prefix = bar(M, "100.1234567890123456");
        prefix.put("high_price", new BigDecimal("102.0000000000000001")); prefix.put("low_price", new BigDecimal("99.0000000000000001"));
        prefix.put("controlled", true); prefix.put("partial", true); prefix.put("snapshotAt", M + 10000);
        f.db.update("INSERT INTO market_legacy_minute_snapshot(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?)", M, legacy.encode(prefix), M + 9999);
        f.db.update("INSERT INTO market_mixed_minute(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?)", M, legacy.encode(prefix), M + 9999);
        for (String task : Arrays.asList("first", "second")) {
            f.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,ended_at,sampled_until) "
                + "VALUES(1,?,1,'TEST',4,'TARGET','COMPLETED',100,110,60,5,FALSE,16,'REAL',?,?,?,?,?)", task, M, M + 10000, M + 40000, M + 40000, M + 40000);
            f.db.update("INSERT INTO market_control_flow(tenant_id,task_id,options_json,state,last_price,last_at,finished_at) VALUES(1,?,'{}','RUNNING',100,?,?)", task, M + 40000, M + 40000);
        }
        f.db.update("INSERT INTO market_control_sample(tenant_id,task_id,generated_at,price) VALUES(1,'first',?,900),(1,'first',?,103.1234567890123456),(1,'second',?,105.1234567890123456)", M + 5000, M + 20000, M + 30000);
        f.db.update("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(1,'after',1,?,?,106.1234567890123456)", M + 45000, M + 45000);
        List<Map<String,Object>> retainedTasks = f.db.queryForList("SELECT * FROM market_control_task ORDER BY id");
        List<Map<String,Object>> retainedFlows = f.db.queryForList("SELECT * FROM market_control_flow ORDER BY task_id");
        List<Map<String,Object>> retainedSamples = f.db.queryForList("SELECT * FROM market_control_sample ORDER BY task_id,generated_at");
        List<Map<String,Object>> retainedEvents = f.db.queryForList("SELECT * FROM market_source_event ORDER BY event_sequence");
        List<Map<String,Object>> expected = legacy.visibleMixed(1, M, M);
        assertEquals(1, expected.size());
        f.store.activateGeneration(1, 7, M - 1, STOP);
        Map<String,Object> lateSource = bar(M, "999.9999999999999999");
        MinuteHistoryProjection.Block visible = MinuteHistoryProjection.calculate(new MinuteHistoryProjection.CompletedFacts(1, 7, 1, M - 1, M + 59999,
            M, M, STOP, STOP, Collections.singletonList(new MinuteHistoryProjection.SourceMinute(lateSource, STOP)),
            Collections.singletonList(new MinuteHistoryProjection.VisibleMinute(expected.get(0), M + 45000))));
        f.store.publish(visible);
        assertEquals(expected, f.store.readWindow(1, M, M).minutes, "all original keys, prefix, control path, Decimal precision and receipt order survive");
        assertEquals(new BigDecimal("99.0000000000000001"), expected.get(0).get("low_price"));
        assertEquals(new BigDecimal("106.1234567890123456"), expected.get(0).get("close_price"));
        MinuteHistoryProjection.Block incompleteRevision = MinuteHistoryProjection.calculate(new MinuteHistoryProjection.CompletedFacts(1, 7, 2, M + 59999, M + 59999,
            M, M, STOP, STOP, Collections.singletonList(new MinuteHistoryProjection.SourceMinute(lateSource, STOP)), Collections.emptyList()));
        assertThrows(IllegalStateException.class, () -> f.store.publish(incompleteRevision));
        assertEquals(expected, f.store.readWindow(1, M, M).minutes); assertEquals(1, f.store.progress(1).version);
        assertEquals(3L, f.db.queryForObject("SELECT COUNT(*) FROM market_control_sample", Long.class), "projection never advances the engine or removes original facts");
        // Only this guarded disposable S4 projection cache is cleared, never retained facts or another stage's Redis.
        f.db.update("DELETE FROM s4_history_projection_minute WHERE tenant_id IN (1,2)"); f.db.update("DELETE FROM s4_history_projection_progress WHERE tenant_id IN (1,2)");
        MinuteHistoryProjectionStore rebuilt = new MinuteHistoryProjectionStore(f.db, f.manager);
        rebuilt.activateGeneration(1, 7, M - 1, STOP);
        List<Map<String,Object>> reread = legacy.visibleMixed(1, M, M);
        rebuilt.publish(MinuteHistoryProjection.calculate(new MinuteHistoryProjection.CompletedFacts(1, 7, 1, M - 1, M + 59999,
            M, M, STOP, STOP, Collections.singletonList(new MinuteHistoryProjection.SourceMinute(lateSource, STOP)),
            Collections.singletonList(new MinuteHistoryProjection.VisibleMinute(reread.get(0), M + 45000)))));
        assertEquals(expected, rebuilt.readWindow(1, M, M).minutes, "retained facts rebuild exactly after loss of the entire stage projection cache");
        assertEquals(retainedTasks, f.db.queryForList("SELECT * FROM market_control_task ORDER BY id"));
        assertEquals(retainedFlows, f.db.queryForList("SELECT * FROM market_control_flow ORDER BY task_id"));
        assertEquals(retainedSamples, f.db.queryForList("SELECT * FROM market_control_sample ORDER BY task_id,generated_at"));
        assertEquals(retainedEvents, f.db.queryForList("SELECT * FROM market_source_event ORDER BY event_sequence"));
    }
    @Test void sourceRevisionIsAllowedOnlyWithExplicitCompletedReceiveBoundaryAndExactStop() {
        Fixture f = fixture(); f.store.activateGeneration(1, 7, M - 1, STOP);
        f.store.publish(block(7, 1, M - 1, "100.0000000000000001", "101.0000000000000001"));
        MinuteHistoryProjection.Block late = MinuteHistoryProjection.calculate(new MinuteHistoryProjection.CompletedFacts(1, 7, 2, STOP, STOP,
            M, M, STOP, STOP + 60000, Collections.singletonList(new MinuteHistoryProjection.SourceMinute(bar(M, "102.0000000000000001"), STOP + 60000)), Collections.emptyList()));
        assertTrue(f.store.publish(late)); assertEquals(new BigDecimal("102.0000000000000001"), rows(f).get(0).get("close_price"));
        assertEquals(new BigDecimal("101.0000000000000001"), rows(f).get(1).get("close_price"));
        assertEquals(STOP, f.store.progress(1).watermark);
        assertThrows(IllegalArgumentException.class, () -> MinuteHistoryProjection.calculate(new MinuteHistoryProjection.CompletedFacts(1, 7, 3, STOP, STOP + 1,
            M, M + 60000, STOP, STOP + 1, Collections.emptyList(), Collections.emptyList())));
        assertThrows(IllegalArgumentException.class, () -> MinuteHistoryProjection.calculate(new MinuteHistoryProjection.CompletedFacts(1, 7, 3, STOP, STOP,
            M, M, STOP, STOP, Collections.singletonList(new MinuteHistoryProjection.SourceMinute(bar(M, "103"), STOP + 1)), Collections.emptyList())));
        assertThrows(IllegalArgumentException.class, () -> MinuteHistoryProjection.calculate(new MinuteHistoryProjection.CompletedFacts(1, 7, 3, STOP, STOP,
            M, M + 500 * 60000L, STOP, STOP, Collections.emptyList(), Collections.emptyList())));
        Map<String,Object> invalid = bar(M, "103"); invalid.put("high_price", new BigDecimal("102"));
        assertThrows(IllegalArgumentException.class, () -> MinuteHistoryProjection.calculate(new MinuteHistoryProjection.CompletedFacts(1, 7, 3, STOP, STOP,
            M, M, STOP, STOP, Collections.singletonList(new MinuteHistoryProjection.SourceMinute(invalid, STOP)), Collections.emptyList())));
        assertThrows(IllegalStateException.class, () -> f.store.publish(block(7, 3, M - 1, "103", "104")));
        assertEquals(2, f.store.progress(1).version); assertEquals(2, f.rows());
    }
    @Test void generationWritesAreAtomicAndTenantWindowsRemainSeparated() {
        Fixture f = fixture();
        MinuteHistoryProjectionStore broken = new MinuteHistoryProjectionStore(f.db, f.manager, write -> { throw new IllegalStateException("activation failure"); });
        assertThrows(IllegalStateException.class, () -> broken.activateGeneration(1, 7, M - 1, STOP)); assertNull(f.store.progress(1));
        f.store.activateGeneration(1, 7, M - 1, STOP); f.store.publish(block(7, 1, M - 1, "100", "101"));
        assertThrows(IllegalStateException.class, () -> broken.activateGeneration(1, 8, M - 1, STOP)); assertEquals(7, f.store.progress(1).generation);
        List<Map<String,Object>> tenantOne = rows(f);
        otherThread(2, () -> {
            assertNull(f.store.progress(1)); assertTrue(rows(f).isEmpty());
            f.store.activateGeneration(1, 7, M - 1, STOP); f.store.publish(block(7, 1, M - 1, "200", "201"));
            assertEquals(new BigDecimal("200"), ControlHistoryStore.number(rows(f).get(0).get("close_price")));
            return null;
        });
        assertEquals(tenantOne, rows(f)); assertEquals(4, f.rows());
    }
}
