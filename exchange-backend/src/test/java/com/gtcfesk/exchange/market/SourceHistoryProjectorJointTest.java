package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real SOURCE-only adapter and physical transactions; not evidence for MySQL triggers/session variables. */
class SourceHistoryProjectorJointTest extends TenantMarketTestContext {
    private static final long MINUTE = 1700000400000L;
    private static final String OWNER = "joint-source-owner";

    @Test void sourcePublicationAndRetryPreserveFactsAndRuntimeInOnePhysicalTransaction() {
        Fixture f = new Fixture(2);
        List<Map<String,Object>> facts = f.facts();
        Map<String,Object> runtime = f.runtime();
        f.db.arm(ignored -> {});
        assertTrue(f.project());
        assertEquals(2, f.count("s4_history_projection_minute"));
        Map<String,Object> progress = f.progress();
        assertEquals(7L, number(progress, "generation"));
        assertEquals(1L, number(progress, "fact_version"));
        assertEquals(MINUTE - 1, number(progress, "initial_watermark"));
        assertEquals(MINUTE + 119999, number(progress, "watermark"));
        assertEquals(MINUTE + 119999, number(progress, "stop_at"));
        assertEquals(64, String.valueOf(progress.get("last_hash")).length());
        assertNotNull(f.db.authoritySession);
        assertFalse(f.db.writeSessions.isEmpty());
        for (Long session : f.db.writeSessions) assertEquals(f.db.authoritySession, session);
        assertEquals(facts, f.facts());
        assertEquals(runtime, f.runtime(), "Projection must not renew leases, allocate generations or publish quotes");
        List<Map<String,Object>> publication = f.publication();
        f.db.arm(ignored -> {});
        assertFalse(f.project());
        assertEquals(0, f.db.writes);
        assertEquals(progress, f.progress());
        assertEquals(publication, f.publication());
    }

    @Test void capturedOwnerGenerationAndRevisionCannotPublishAfterAuthorityChanges() {
        String[] changes = {
            "UPDATE market_engine_runtime SET writer_generation=8 WHERE tenant_id=1 AND symbol_id=1",
            "UPDATE market_engine_runtime SET owner_id='successor' WHERE tenant_id=1 AND symbol_id=1",
            "UPDATE market_engine_runtime SET control_revision=5 WHERE tenant_id=1 AND symbol_id=1"
        };
        for (String change : changes) {
            Fixture f = new Fixture(2);
            f.db.update(change);
            f.db.arm(ignored -> {});
            IllegalStateException error = assertThrows(IllegalStateException.class, f::project);
            assertEquals("Projection authority fenced", error.getMessage());
            f.assertUnpublished();
            assertEquals(0, f.db.writes);
        }
        Fixture f = new Fixture(2);
        assertThrows(IllegalStateException.class, () -> f.adapter.project(1, OWNER, 0, 4));
        f.assertUnpublished();
    }

    @Test void controlRandomTasksAndMixedMinutesFailClosedWithoutProjectionWrites() {
        for (int input = 0; input < 4; input++) {
            Fixture f = new Fixture(2);
            if (input == 0) f.db.update("UPDATE trading_symbol SET control_enabled=TRUE WHERE tenant_id=1 AND id=1");
            if (input == 1) f.db.update("UPDATE trading_symbol SET random_market_enabled=TRUE WHERE tenant_id=1 AND id=1");
            if (input == 2) f.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,sampled_until) VALUES(1,'control-history',1,'TEST',4,'TARGET','COMPLETED',100,100,1,1,FALSE,16,'SOURCE',?,?,?,?)", MINUTE, MINUTE, MINUTE + 1000, MINUTE + 1000);
            if (input == 3) f.db.update("INSERT INTO market_mixed_minute(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?)", MINUTE, f.store.encode(bar(MINUTE)), MINUTE + 1000);
            List<Map<String,Object>> facts = f.facts();
            Map<String,Object> runtime = f.runtime();
            List<Map<String,Object>> mixed = f.db.queryForList("SELECT * FROM market_mixed_minute ORDER BY tenant_id,symbol_id,minute_at");
            List<Map<String,Object>> tasks = f.db.queryForList("SELECT * FROM market_control_task ORDER BY tenant_id,id");
            f.db.arm(ignored -> {});
            assertFalse(f.project(), "Input variant " + input);
            f.assertUnpublished();
            assertEquals(0, f.db.writes);
            assertEquals(facts, f.facts());
            assertEquals(runtime, f.runtime());
            assertEquals(mixed, f.db.queryForList("SELECT * FROM market_mixed_minute ORDER BY tenant_id,symbol_id,minute_at"));
            assertEquals(tasks, f.db.queryForList("SELECT * FROM market_control_task ORDER BY tenant_id,id"));
        }
    }

    @Test void eachPhysicalWriteFailureRollsBackResultProgressAndReceiptTogether() {
        Fixture baseline = new Fixture(2);
        baseline.db.arm(ignored -> {});
        assertTrue(baseline.project());
        int writes = baseline.db.writes;
        assertTrue(writes >= 4, "Activation, each result row and progress receipt must be observed");
        for (int failure = 1; failure <= writes; failure++) {
            Fixture f = new Fixture(2);
            List<Map<String,Object>> facts = f.facts();
            Map<String,Object> runtime = f.runtime();
            final int failAt = failure;
            f.db.arm(write -> { if (write == failAt) throw new IllegalStateException("joint failure after write " + write); });
            IllegalStateException error = assertThrows(IllegalStateException.class, f::project);
            assertEquals("joint failure after write " + failure, error.getMessage());
            f.assertUnpublished();
            assertEquals(facts, f.facts());
            assertEquals(runtime, f.runtime());
            f.db.arm(ignored -> {});
            assertTrue(f.project(), "Rollback must leave retry possible at write " + failure);
            Map<String,Object> receipt = f.progress();
            List<Map<String,Object>> publication = f.publication();
            assertFalse(f.project());
            assertEquals(receipt, f.progress());
            assertEquals(publication, f.publication());
        }
    }

    @Test void independentConnectionCannotSeeMinuteOrProgressBeforeAuthorityTransactionCommits() throws Exception {
        Fixture f = new Fixture(2);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            f.db.arm(write -> {
                if (!f.db.lastWrite.contains("INSERT INTO s4_history_projection_minute")) return;
                Future<long[]> view = worker.submit(MarketSqlFixture.inTenant(() -> {
                    try (Connection connection = f.source.getConnection()) {
                        JdbcTemplate independent = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                        return new long[]{independent.queryForObject("SELECT SESSION_ID()", Long.class),
                            independent.queryForObject("SELECT COUNT(*) FROM s4_history_projection_minute", Long.class),
                            independent.queryForObject("SELECT COUNT(*) FROM s4_history_projection_progress", Long.class)};
                    }
                }));
                try {
                    long[] values = view.get(3, TimeUnit.SECONDS);
                    assertNotEquals(f.db.authoritySession.longValue(), values[0]);
                    assertEquals(0, values[1]);
                    assertEquals(0, values[2]);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue(f.project());
            assertEquals(2, f.count("s4_history_projection_minute"));
            assertEquals(1, f.count("s4_history_projection_progress"));
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void realRuntimeTakeoverWaitsForPublicationAndThenFencesOldWorker() throws Exception {
        Fixture f = new Fixture(2);
        CountDownLatch attempt = new CountDownLatch(1);
        ObservedJdbc successorDb = new ObservedJdbc(f.source);
        successorDb.runtimeAttempt = attempt;
        ControlHistoryStore successor = new ControlHistoryStore(successorDb, f.manager);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Long>> takeover = new AtomicReference<>();
        try {
            f.db.arm(write -> {
                if (!f.db.lastWrite.contains("INSERT INTO s4_history_projection_minute") || takeover.get() != null) return;
                takeover.set(worker.submit(MarketSqlFixture.inTenant(() -> successor.locked(1, () -> {
                    successor.runtime.invalidate(1);
                    return successorDb.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1", Long.class);
                }))));
                try {
                    assertTrue(attempt.await(2, TimeUnit.SECONDS), "Successor must reach the real runtime claim");
                    assertThrows(TimeoutException.class, () -> takeover.get().get(150, TimeUnit.MILLISECONDS),
                        "Takeover must wait for the authority lock protecting projection publication");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            });
            assertTrue(f.project());
            assertNotNull(takeover.get());
            assertEquals(8L, takeover.get().get(3, TimeUnit.SECONDS).longValue());
            assertEquals(successor.runtime.owner, f.runtime().get("owner_id"));
            assertEquals(5L, number(f.runtime(), "control_revision"));
            List<Map<String,Object>> publication = f.publication();
            Map<String,Object> receipt = f.progress();
            assertThrows(IllegalStateException.class, f::project);
            assertEquals(publication, f.publication());
            assertEquals(receipt, f.progress());
            f.db.arm(ignored -> {});
            assertTrue(f.adapter.project(1, successor.runtime.owner, 8, 5));
            assertEquals(8L, number(f.progress(), "generation"));
            assertEquals(2, f.count("s4_history_projection_minute"));
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void partialCandleFetchedBeforeMinuteCloseDoesNotPublishOrAdvance() {
        Fixture f = new Fixture(2);
        f.db.update("UPDATE market_source_candle SET received_at=? WHERE tenant_id=1 AND symbol_id=1 AND candle_at=?", MINUTE + 59998, MINUTE);
        List<Map<String,Object>> facts = f.facts();
        f.db.arm(ignored -> {});
        assertFalse(f.project());
        f.assertUnpublished();
        assertEquals(0, f.db.writes);
        assertEquals(facts, f.facts());
    }

    @Test void futureReceiveIsRejectedBeforeAnyProjectionWrite() {
        Fixture f = new Fixture(2);
        f.db.update("UPDATE market_source_candle SET received_at=? WHERE tenant_id=1 AND symbol_id=1 AND candle_at=?", System.currentTimeMillis() + 60000, MINUTE);
        f.db.arm(ignored -> {});
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, f::project);
        assertEquals("source candle is outside receive cutoff", error.getMessage());
        f.assertUnpublished();
        assertEquals(0, f.db.writes);
    }

    @Test void scannerRejectsOuterTransactionAndKeepsOtherTenantFactsPrivate() {
        Fixture f = new Fixture(2);
        f.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(2,2)");
        f.db.update("INSERT INTO market_engine_runtime(tenant_id,symbol_id,writer_generation,owner_id,control_revision) VALUES(2,2,7,?,4)", OWNER);
        f.db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES(2,2,'1m',?,?,?)", MINUTE, f.store.encode(bar(MINUTE)), MINUTE + 59999);
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> f.store.transaction(() -> f.adapter.projectNextPage(16)));
        assertEquals("Projection scan requires context only", error.getMessage());
        f.assertUnpublished();
        assertEquals(1, f.adapter.projectNextPage(16));
        assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM s4_history_projection_progress WHERE tenant_id=2", Integer.class).intValue());
        assertEquals(0, f.db.queryForObject("SELECT COUNT(*) FROM s4_history_projection_minute WHERE tenant_id=2", Integer.class).intValue());
        assertEquals(1, f.db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE tenant_id=2", Integer.class).intValue());
    }

    @Test void missingMiddleMinuteStopsCertifiedPrefixAndLateGapCanResume() {
        Fixture f=new Fixture(3);
        f.db.update("DELETE FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 AND candle_at=?",MINUTE+60000);
        assertTrue(f.project());assertEquals(MINUTE+59999,number(f.progress(),"watermark"));
        MinuteHistoryProjectionStore reader=new MinuteHistoryProjectionStore(f.db,f.manager);
        assertEquals("partial",reader.readWindow(1,MINUTE,MINUTE+120000).status);
        assertEquals(1,reader.readWindow(1,MINUTE,MINUTE+120000).minutes.size());
        assertFalse(f.project());assertEquals(MINUTE+59999,number(f.progress(),"watermark"));
        f.store.sourceCandles(1,"1m",Collections.singletonList(bar(MINUTE+60000)),MINUTE+119999);
        // sourceCandles is a real writer claim; its generation becomes the next projection authority.
        Map<String,Object> runtime=f.runtime();
        assertTrue(f.adapter.project(1,String.valueOf(runtime.get("owner_id")),number(runtime,"writer_generation"),number(runtime,"control_revision")));
        assertEquals("available",reader.readWindow(1,MINUTE,MINUTE+120000).status);
        assertEquals(3,reader.readWindow(1,MINUTE,MINUTE+120000).minutes.size());
    }
    @Test void partialMiddleMinuteAllowsOnlyTheCompletedPrefix() {
        Fixture f=new Fixture(3);Map<String,Object> partial=bar(MINUTE+60000);partial.put("partial",true);
        f.db.update("UPDATE market_source_candle SET body=? WHERE tenant_id=1 AND symbol_id=1 AND candle_at=?",f.store.encode(partial),MINUTE+60000);
        assertTrue(f.project());assertEquals(MINUTE+59999,number(f.progress(),"watermark"));assertEquals(1,f.count("s4_history_projection_minute"));
        assertFalse(f.project());
    }
    @Test void readCoverageCannotCallSparseMissingOrOldGenerationRowsAvailable() {
        Fixture f=new Fixture(3);assertTrue(f.project());
        MinuteHistoryProjectionStore reader=new MinuteHistoryProjectionStore(f.db,f.manager);
        assertEquals("available",reader.readWindow(1,MINUTE,MINUTE+120000).status);
        f.db.update("DELETE FROM s4_history_projection_minute WHERE tenant_id=1 AND symbol_id=1 AND minute_at=?",MINUTE+60000);
        assertEquals("partial",reader.readWindow(1,MINUTE,MINUTE+120000).status);
        assertEquals("partial",reader.readWindow(1,MINUTE-60000,MINUTE).status);
        f.db.update("UPDATE s4_history_projection_progress SET generation=8 WHERE tenant_id=1 AND symbol_id=1");
        assertEquals("partial",reader.readWindow(1,MINUTE,MINUTE+120000).status);
        assertTrue(reader.readWindow(1,MINUTE,MINUTE+120000).minutes.isEmpty());
    }

    private static long number(Map<String,Object> row, String column) { return ((Number)row.get(column)).longValue(); }
    private static Map<String,Object> bar(long at) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("timestamp", at);
        row.put("open_price", new BigDecimal("100.1234567890123456"));
        row.put("high_price", new BigDecimal("101.1234567890123456"));
        row.put("low_price", new BigDecimal("99.1234567890123456"));
        row.put("close_price", new BigDecimal("100.1234567890123456"));
        row.put("volume", new BigDecimal("7.0000000000000001"));
        return row;
    }

    private static final class Fixture {
        final DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:joint_source_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000", "sa", "");
        final ObservedJdbc db = new ObservedJdbc(source);
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        final ControlHistoryStore store;
        final SourceHistoryProjector adapter;
        Fixture(int minutes) {
            MarketSqlFixture.schema(db);
            db.execute("ALTER TABLE trading_symbol ADD random_market_enabled BOOLEAN NOT NULL DEFAULT FALSE");
            db.execute("ALTER TABLE trading_symbol ADD control_enabled BOOLEAN NOT NULL DEFAULT FALSE");
            new ResourceDatabasePopulator(new ClassPathResource("s4-history-projection.sql")).execute(source);
            store = new ControlHistoryStore(db, manager);
            adapter = new SourceHistoryProjector(store, manager);
            db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1)");
            db.update("INSERT INTO market_engine_runtime(tenant_id,symbol_id,writer_generation,owner_id,lease_until,control_revision,snapshot_version,quote_json,status_json,committed_at) VALUES(1,1,7,?,0,4,9,'{}','{}',?)", OWNER, MINUTE);
            for (int i = 0; i < minutes; i++) {
                long at = MINUTE + i * 60000L;
                db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES(1,1,'1m',?,?,?)", at, store.encode(bar(at)), at + 59999);
            }
        }
        boolean project() { return adapter.project(1, OWNER, 7, 4); }
        int count(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
        Map<String,Object> runtime() { return db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1"); }
        Map<String,Object> progress() { return db.queryForMap("SELECT * FROM s4_history_projection_progress WHERE tenant_id=1 AND symbol_id=1"); }
        List<Map<String,Object>> facts() { return db.queryForList("SELECT * FROM market_source_candle ORDER BY tenant_id,symbol_id,period,candle_at"); }
        List<Map<String,Object>> publication() { return db.queryForList("SELECT * FROM s4_history_projection_minute ORDER BY tenant_id,symbol_id,minute_at"); }
        void assertUnpublished() { assertEquals(0, count("s4_history_projection_progress")); assertEquals(0, count("s4_history_projection_minute")); }
    }

    /** Observes/injects after real SQL writes, never substitutes runtime ownership or transaction behavior. */
    private static final class ObservedJdbc extends JdbcTemplate {
        boolean recording;
        int writes;
        Long authoritySession;
        String lastWrite;
        final List<Long> writeSessions = new ArrayList<>();
        IntConsumer afterWrite = ignored -> {};
        CountDownLatch runtimeAttempt;
        ObservedJdbc(DriverManagerDataSource source) { super(source); }
        void arm(IntConsumer callback) {
            recording = true; writes = 0; authoritySession = null; lastWrite = null;
            writeSessions.clear(); afterWrite = callback;
        }
        @Override public Map<String,Object> queryForMap(String sql, Object... arguments) {
            Map<String,Object> result = super.queryForMap(sql, arguments);
            if (recording && sql.contains("market_engine_runtime") && sql.endsWith("FOR UPDATE")) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                authoritySession = super.queryForObject("SELECT SESSION_ID()", Long.class);
            }
            return result;
        }
        @Override public int update(String sql, Object... arguments) {
            if (runtimeAttempt != null && sql.startsWith("INSERT INTO market_engine_runtime")) runtimeAttempt.countDown();
            int changed = super.update(sql, arguments);
            if (recording && changed > 0 && sql.contains("s4_history_projection_")
                    && (sql.startsWith("INSERT") || sql.startsWith("UPDATE"))) {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                lastWrite = sql;
                writeSessions.add(super.queryForObject("SELECT SESSION_ID()", Long.class));
                afterWrite.accept(++writes);
            }
            return changed;
        }
    }
}
