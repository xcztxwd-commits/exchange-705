package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.DedicatedMysqlFixture;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Migrated, guard-certified clone only. Cash checkpoint is not complete order/ledger service certification. */
class JointAuthorityAndProjectionMySqlIT {
    private static final long TENANT = 1;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    private static RecordingSource source;
    private static JdbcTemplate plain;
    private static Map<String,List<String>> originalRows;
    private static Map<String,Object> identity;
    private final List<Trace> traces = new CopyOnWriteArrayList<>();
    private TenantContext.Scope tenant;

    @BeforeAll static void identifiedMigratedClone() throws Exception {
        // No alternative URL, old S4 guard, schema creation or seed cleanup is permitted.
        DriverManagerDataSource verified = DedicatedMysqlFixture.fromProperty("joint.mysql.fixture");
        source = new RecordingSource(verified); plain = new JdbcTemplate(source);
        identity = plain.queryForMap("SELECT @@server_uuid AS server_uuid,@@port AS server_port,VERSION() AS server_version,DATABASE() AS database_name");
        assertTrue(String.valueOf(identity.get("server_version")).startsWith("5.7."));
        assertEquals(1, plain.queryForObject("SELECT COUNT(*) FROM tenant_schema_version WHERE version=2026100402", Integer.class).intValue());
        assertEquals(6, plain.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name IN ('joint_s4_progress_insert','joint_s4_progress_update','joint_s4_progress_delete','joint_s4_minute_insert','joint_s4_minute_update','joint_s4_minute_delete')", Integer.class).intValue());
        originalRows = rows();
        assertEquals(110, originalRows.size(), "Certified migrated clone must retain all 110 original tables");
        assertEquals(31, originalRows.values().stream().mapToInt(List::size).sum(), "Certified fresh clone must retain its exact 31 original full-column rows before appending test facts");
    }

    @BeforeEach void enterTenant() { tenant = TenantContext.open(TENANT); }
    @AfterEach void preserveSeedAndEvidence(TestInfo test) throws Exception {
        try {
            source.clear();
            Map<String,List<String>> after = rows();
            List<String> changed = new ArrayList<>();
            for (Map.Entry<String,List<String>> entry : originalRows.entrySet()) {
                List<String> remaining = new ArrayList<>(after.getOrDefault(entry.getKey(), Collections.emptyList()));
                for (String original : entry.getValue()) if (!remaining.remove(original)) { changed.add(entry.getKey()); break; }
            }
            Map<String,Object> evidence = new LinkedHashMap<>();
            evidence.put("qualification", "Real migrated MySQL authority plus synthetic cash checkpoint; not complete ContractOrderService/OptionOrderService/ledger/audit service acceptance. No HTTP or Redis client is constructed. No original rows are deleted or rewritten.");
            evidence.put("identity", identity); evidence.put("test", test.getDisplayName());
            evidence.put("original_seed_rows", originalRows.values().stream().mapToInt(List::size).sum());
            evidence.put("changed_original_tables", changed);
            List<Map<String,Object>> executions = new ArrayList<>();
            for (Trace trace : traces) executions.add(trace.evidence());
            evidence.put("executions", executions);
            Path directory = Paths.get(System.getProperty("joint.evidence.dir", "target/joint-authority-projection")).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path target = directory.resolve(test.getTestMethod().orElseThrow(IllegalStateException::new).getName() + "-" + UUID.randomUUID() + ".json");
            Path temporary = directory.resolve(target.getFileName() + ".tmp");
            Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence), StandardOpenOption.CREATE_NEW);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            assertTrue(changed.isEmpty(), "Original seed rows changed: " + changed);
        } finally { tenant.close(); }
    }

    @Test void cashPhysicalTransactionHoldsCurrentAuthorityUntilCommitAndRealStopWaits() throws Exception {
        Fixture f = new Fixture(); f.startAndPublish();
        Trace preparedTrace = trace("prepare-outside-funding");
        Map<String,Object> prepared;
        try { prepared = f.authority.prepare(f.config.getSymbol()); } finally { source.clear(); }
        assertEquals(1, preparedTrace.connections);
        assertTrue(preparedTrace.sql().stream().allMatch(sql -> sql.startsWith("SELECT ")));
        long revision = number(f.runtime(), "control_revision");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch attempting = new CountDownLatch(1);
        Trace stopTrace = new Trace("real-stop-writer"); stopTrace.runtimeAttempt = attempting; traces.add(stopTrace);
        Trace cashTrace = trace("cash-current-lock-and-update");
        final Future<?>[] stop = new Future<?>[1];
        try {
            f.store.transaction(() -> {
                long connection = f.db.queryForObject("SELECT CONNECTION_ID()", Long.class);
                f.db.queryForObject("SELECT available FROM asset_account WHERE tenant_id=? AND user_id=? AND coin='CONTRACT' FOR UPDATE", BigDecimal.class, TENANT, f.user);
                f.authority.validate(Collections.singletonList(prepared));
                assertEquals(connection, f.db.queryForObject("SELECT CONNECTION_ID()", Long.class).longValue());
                assertEquals(1, f.db.update("UPDATE asset_account SET available=available-1,row_version=row_version+1 WHERE tenant_id=? AND user_id=? AND coin='CONTRACT'", TENANT, f.user));
                stop[0] = worker.submit(() -> {
                    try (TenantContext.Scope ignored = TenantContext.open(TENANT)) {
                        source.start(stopTrace);
                        try { f.controls.stop(f.config.getId(), System.currentTimeMillis()); }
                        finally { source.clear(); }
                    }
                });
                try {
                    assertTrue(attempting.await(5, TimeUnit.SECONDS), "Real STOP must reach runtime claim SQL");
                    assertThrows(TimeoutException.class, () -> stop[0].get(250, TimeUnit.MILLISECONDS), "STOP must remain blocked until physical cash commit");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                return null;
            });
            source.clear(); stop[0].get(10, TimeUnit.SECONDS);
            assertEquals(1, cashTrace.connections, "Authority validation and beforeCommit must not open another physical connection");
            assertEquals(1, cashTrace.connectionIds.size());
            assertTrue(cashTrace.sql().stream().anyMatch(sql -> sql.contains("market_engine_runtime") && sql.endsWith("FOR UPDATE")));
            assertTrue(cashTrace.sql().stream().anyMatch(sql -> sql.contains("trading_symbol") && sql.endsWith("FOR UPDATE")));
            assertFalse(cashTrace.connectionIds.contains(stopTrace.runtimeConnection));
            assertEquals(0, f.cash().compareTo(new BigDecimal("999")));
            assertEquals(revision + 1, number(f.runtime(), "control_revision"));
            assertEquals("STOPPED", f.controls.latest(f.config.getId()).status);
            assertTrue(cashTrace.commitAt > 0);
            assertTrue(stopTrace.firstRevisionWriteAt >= cashTrace.commitStartedAt, "STOP was blocked before commit was requested and cannot revise authority beforehand");
        } finally { source.clear(); worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test void preparedQuoteIsRejectedAfterRealStopWithNoCashOrderLedgerOrAuditDml() throws Exception {
        Fixture f = new Fixture(); f.startAndPublish();
        Map<String,Object> prepared = f.authority.prepare(f.config.getSymbol());
        long revision = number(f.runtime(), "control_revision");
        f.controls.stop(f.config.getId(), System.currentTimeMillis());
        assertEquals(revision + 1, number(f.runtime(), "control_revision"));
        Map<String,List<String>> moneyBefore = moneyRows();
        Trace rejected = trace("prepared-before-stop-rejected-current");
        try {
            BusinessException error = assertThrows(BusinessException.class, () -> f.store.transaction(() -> {
                f.db.queryForObject("SELECT available FROM asset_account WHERE tenant_id=? AND user_id=? AND coin='CONTRACT' FOR UPDATE", BigDecimal.class, TENANT, f.user);
                f.authority.validate(Collections.singletonList(prepared));
                f.db.update("UPDATE asset_account SET available=available-1,row_version=row_version+1 WHERE tenant_id=? AND user_id=? AND coin='CONTRACT'", TENANT, f.user);
                fail("Rejected quote must never reach cash/order/ledger/audit mutation");
                return null;
            }));
            assertTrue(error.getMessage().startsWith("S3_QUOTE_REJECTED:"));
        } finally { source.clear(); }
        assertEquals(1, rejected.connections);
        assertTrue(rejected.sql().stream().allMatch(sql -> sql.startsWith("SELECT ")), "No DML may execute before rejection");
        assertEquals(moneyBefore, moneyRows(), "Actual cash, order, ledger and audit rows remain identical");
        assertEquals(0, f.cash().compareTo(new BigDecimal("1000")));
    }

    @Test void realSourceProjectionTriggersRollbackCleanupAndNaturalLeaseTakeoverFenceOldGeneration() throws Exception {
        Fixture f = new Fixture(); f.publishSource();
        SourceHistoryProjector projector = new SourceHistoryProjector(f.store, f.manager);
        Map<String,Object> route = f.runtime();
        String owner = String.valueOf(route.get("owner_id"));
        long generation = number(route, "writer_generation"), revision = number(route, "control_revision");
        assertTrue(generation > 0, "Generation must have been allocated by real sourceCandles/pump writer");
        Trace rollback = trace("projection-actual-minute-write-rollback"); f.db.cleanupTrace = rollback; f.db.failProjectionMinute = true;
        try {
            assertThrows(IllegalStateException.class, () -> projector.project(f.config.getId(), owner, generation, revision));
        } finally { source.clear(); }
        assertEquals(0, f.projectionCount("s4_history_projection_progress"));
        assertEquals(0, f.projectionCount("s4_history_projection_minute"));
        assertEquals(TransactionSynchronization.STATUS_ROLLED_BACK, rollback.cleanupStatus);
        assertEquals(1, rollback.cleanupCleared);
        assertEquals(1, rollback.connections);
        Trace published = trace("projection-authority-result-progress-receipt-commit"); f.db.cleanupTrace = published;
        try { assertTrue(projector.project(f.config.getId(), owner, generation, revision)); }
        finally { source.clear(); }
        assertEquals(1, published.connections);
        assertEquals(1, published.connectionIds.size());
        assertEquals(TransactionSynchronization.STATUS_COMMITTED, published.cleanupStatus);
        assertEquals(1, published.cleanupCleared);
        assertTrue(published.sql().stream().anyMatch(sql -> sql.contains("market_engine_runtime") && sql.endsWith("FOR UPDATE")));
        assertTrue(published.sql().stream().anyMatch(sql -> sql.startsWith("INSERT INTO s4_history_projection_minute")));
        assertTrue(published.sql().stream().anyMatch(sql -> sql.startsWith("UPDATE s4_history_projection_progress SET fact_version")));
        assertEquals(2, f.projectionCount("s4_history_projection_minute"));
        Map<String,Object> receipt = f.progress(); List<Map<String,Object>> minutes = f.minutes();
        assertEquals(generation, number(receipt, "generation"));
        assertEquals(64, String.valueOf(receipt.get("last_hash")).length());
        assertFalse(projector.project(f.config.getId(), owner, generation, revision));
        assertEquals(receipt, f.progress()); assertEquals(minutes, f.minutes());
        Trace denied = trace("direct-six-unfenced-projection-dml-denied");
        try {
            directDenied(() -> f.db.update("INSERT INTO s4_history_projection_progress(tenant_id,symbol_id,generation,fact_version,initial_watermark,watermark,stop_at,last_hash) VALUES(?,?,?,0,?,?,?,NULL)", TENANT, f.config.getId(), generation, f.first - 1, f.first - 1, f.first + 119999));
            directDenied(() -> f.db.update("UPDATE s4_history_projection_progress SET watermark=watermark WHERE tenant_id=? AND symbol_id=?", TENANT, f.config.getId()));
            directDenied(() -> f.db.update("DELETE FROM s4_history_projection_progress WHERE tenant_id=? AND symbol_id=?", TENANT, f.config.getId()));
            directDenied(() -> f.db.update("INSERT INTO s4_history_projection_minute(tenant_id,symbol_id,minute_at,generation,fact_version,body,received_cutoff,protected_mixed) VALUES(?,?,?,?,1,?,?,0)", TENANT, f.config.getId(), f.first, generation, f.store.encode(bar(f.first)), System.currentTimeMillis()));
            directDenied(() -> f.db.update("UPDATE s4_history_projection_minute SET body=body WHERE tenant_id=? AND symbol_id=? AND minute_at=?", TENANT, f.config.getId(), f.first));
            directDenied(() -> f.db.update("DELETE FROM s4_history_projection_minute WHERE tenant_id=? AND symbol_id=? AND minute_at=?", TENANT, f.config.getId(), f.first));
        } finally { source.clear(); }
        assertEquals(6, denied.failures);
        assertEquals(receipt, f.progress()); assertEquals(minutes, f.minutes());
        // Wait for the real database-clock lease; do not forge a generation, owner, expiry or revision.
        long wait = number(f.runtime(), "lease_until") - f.store.runtime.clock() + 100;
        if (wait > 0) Thread.sleep(wait);
        Trace takeover = trace("natural-lease-expiry-real-source-writer-takeover");
        ControlHistoryStore successor = new ControlHistoryStore(f.db, f.manager);
        try { successor.sourceCandles(f.config.getId(), "1m", Arrays.asList(bar(f.first), bar(f.first + 60000)), successor.runtime.clock() - 1000); }
        finally { source.clear(); }
        Map<String,Object> current = f.runtime();
        assertEquals(generation + 1, number(current, "writer_generation"));
        assertEquals(successor.runtime.owner, current.get("owner_id"));
        assertNotEquals(owner, current.get("owner_id"));
        Trace fenced = trace("old-generation-projector-and-real-writer-fenced");
        try {
            assertThrows(IllegalStateException.class, () -> projector.project(f.config.getId(), owner, generation, revision));
            assertThrows(BusinessException.class, () -> f.store.sourceCandles(f.config.getId(), "1m", Collections.singletonList(bar(f.first)), f.store.runtime.clock() - 1000));
        } finally { source.clear(); }
        assertEquals(receipt, f.progress()); assertEquals(minutes, f.minutes());
        assertFalse(fenced.sql().stream().anyMatch(sql -> sql.startsWith("INSERT INTO s4_history_projection_") || sql.startsWith("UPDATE s4_history_projection_")));
        Trace successorPublication = trace("current-real-generation-publication"); f.db.cleanupTrace = successorPublication;
        try { assertTrue(projector.project(f.config.getId(), successor.runtime.owner, generation + 1, number(current, "control_revision"))); }
        finally { source.clear(); }
        assertEquals(generation + 1, number(f.progress(), "generation"));
        assertEquals(1, successorPublication.cleanupCleared);
    }

    private Trace trace(String name) { Trace trace = new Trace(name); traces.add(trace); source.start(trace); return trace; }
    private static void directDenied(Runnable dml) {
        RuntimeException error = assertThrows(RuntimeException.class, dml::run);
        Throwable root = error; while (root.getCause() != null) root = root.getCause();
        assertTrue(root.getMessage().contains("Projection runtime authority fenced"), root.getMessage());
    }
    private static long number(Map<String,Object> row, String column) { return ((Number)row.get(column)).longValue(); }
    private static Map<String,Object> bar(long at) {
        Map<String,Object> row = new LinkedHashMap<>(); row.put("timestamp", at);
        for (String field : Arrays.asList("open_price", "high_price", "low_price", "close_price")) row.put(field, new BigDecimal("100.12345678"));
        row.put("volume", BigDecimal.ONE); return row;
    }
    private static Map<String,List<String>> rows() throws Exception {
        Map<String,List<String>> snapshot = new TreeMap<>();
        for (String table : plain.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name", String.class)) {
            assertTrue(table.matches("[A-Za-z0-9_]+")); List<String> entries = new ArrayList<>();
            for (Map<String,Object> row : plain.queryForList("SELECT * FROM `" + table + "`")) entries.add(JSON.writeValueAsString(new TreeMap<>(row)));
            Collections.sort(entries); snapshot.put(table, entries);
        }
        return snapshot;
    }
    private static Map<String,List<String>> moneyRows() throws Exception {
        Map<String,List<String>> snapshot = rows();
        snapshot.entrySet().removeIf(entry -> !(entry.getKey().equals("asset_account") || entry.getKey().contains("order") || entry.getKey().contains("ledger") || entry.getKey().contains("audit") || entry.getKey().contains("yield_record")));
        return snapshot;
    }

    private static final class Fixture {
        final long user = IDS.incrementAndGet();
        final TradingSymbol config = new TradingSymbol();
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        final ObservedJdbc db = new ObservedJdbc();
        final ControlHistoryStore store = new ControlHistoryStore(db, manager);
        final PersistentPriceControl controls = new PersistentPriceControl(store);
        final FundingQuoteAuthority authority = new FundingQuoteAuthority(store);
        final long first = System.currentTimeMillis() / 60000 * 60000 - 180000;
        Fixture() {
            config.setTenantId(TENANT); config.setId(IDS.incrementAndGet()); config.setSymbol("JA" + config.getId());
            config.setBaseCurrency("TEST"); config.setQuoteCurrency("USD"); config.setName(config.getSymbol());
            config.setMarketSource("yahoo"); config.setSourceCategory("Metal"); config.setCategory("Metal");
            config.setPricePrecision(8); config.setIsEnabled(true); config.setControlEnabled(false); config.setRandomMarketEnabled(false); config.setRowVersion(0);
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM trading_symbol WHERE id=?", Integer.class, config.getId()).intValue());
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM user_account WHERE id=?", Integer.class, user).intValue());
            db.update("INSERT INTO trading_symbol(tenant_id,id,symbol,name,base_currency,quote_currency,market_source,source_category,category,is_enabled,control_enabled,random_market_enabled,price_precision,row_version) VALUES(?,?,?,?,?,?,?,?,?,1,0,0,8,0)", TENANT, config.getId(), config.getSymbol(), config.getName(), "TEST", "USD", "yahoo", "Metal", "Metal");
            db.update("INSERT INTO user_account(tenant_id,id,email,password_hash,row_version) VALUES(?,?,?,'not-a-login',0)", TENANT, user, "joint-" + user + "@fixture.invalid");
            db.update("INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version) VALUES(?,?,'CONTRACT',1000,0,0)", TENANT, user);
        }
        Map<String,Object> raw(long now) {
            Map<String,Object> raw = new LinkedHashMap<>(); raw.put("price", new BigDecimal("100.12345678"));
            raw.put("timestamp", now); raw.put("sourceTimestamp", now); raw.put("fetchedAt", now); raw.put("expiresAt", now + 60000);
            raw.put("available", true); raw.put("sourceAvailable", true); raw.put("status", "available"); return raw;
        }
        void publishSource() {
            store.sourceCandles(config.getId(), "1m", Arrays.asList(bar(first), bar(first + 60000)), store.runtime.clock() - 1000);
            long now = store.runtime.clock(); controls.pump(config, raw(now), now, 60000);
        }
        void startAndPublish() {
            publishSource(); long now = store.runtime.clock();
            controls.start(config, raw(now), new BigDecimal("100.12345678"), 60, new BigDecimal("101.12345678"), 1, false, false, "joint-" + UUID.randomUUID());
            now = store.runtime.clock(); controls.pump(config, raw(now), now, 60000);
        }
        Map<String,Object> runtime() { return db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?", TENANT, config.getId()); }
        BigDecimal cash() { return db.queryForObject("SELECT available FROM asset_account WHERE tenant_id=? AND user_id=? AND coin='CONTRACT'", BigDecimal.class, TENANT, user); }
        int projectionCount(String table) { return db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND symbol_id=?", Integer.class, TENANT, config.getId()); }
        Map<String,Object> progress() { return db.queryForMap("SELECT * FROM s4_history_projection_progress WHERE tenant_id=? AND symbol_id=?", TENANT, config.getId()); }
        List<Map<String,Object>> minutes() { return db.queryForList("SELECT * FROM s4_history_projection_minute WHERE tenant_id=? AND symbol_id=? ORDER BY minute_at", TENANT, config.getId()); }
    }

    private static final class ObservedJdbc extends JdbcTemplate {
        Trace cleanupTrace; boolean failProjectionMinute;
        ObservedJdbc() { super(source); }
        @Override public int update(String sql, Object... arguments) {
            int result = super.update(sql, arguments);
            if (result > 0 && sql.startsWith("INSERT INTO s4_history_projection_minute") && cleanupTrace != null && !cleanupTrace.cleanupRegistered) {
                final Trace trace = cleanupTrace; trace.cleanupRegistered = true;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        trace.cleanupStatus = status;
                        trace.cleanupCleared = queryForObject("SELECT IF(@mt705_s4_tenant IS NULL AND @mt705_s4_symbol IS NULL AND @mt705_s4_generation IS NULL AND @mt705_s4_revision IS NULL,1,0)", Integer.class);
                        trace.cleanupConnection = queryForObject("SELECT CONNECTION_ID()", Long.class);
                    }
                });
                if (failProjectionMinute) { failProjectionMinute = false; throw new IllegalStateException("joint rollback after real projection minute write"); }
            }
            return result;
        }
    }

    private static final class Trace {
        final String name; final List<Map<String,Object>> events = new CopyOnWriteArrayList<>();
        final Set<Long> connectionIds = new ConcurrentSkipListSet<>();
        int connections, failures, cleanupStatus = -1, cleanupCleared = -1;
        boolean cleanupRegistered; long commitStartedAt, commitAt, firstRevisionWriteAt; Long runtimeConnection, cleanupConnection;
        CountDownLatch runtimeAttempt;
        Trace(String name) { this.name = name; }
        void event(long connection, String kind, String sql, Map<Integer,Object> binds) {
            Map<String,Object> event = new LinkedHashMap<>(); event.put("connection_id", connection); event.put("kind", kind); event.put("at_nanos", System.nanoTime());
            if (sql != null) { event.put("sql", sql); event.put("arguments", new TreeMap<>(binds)); }
            events.add(event);
        }
        List<String> sql() {
            List<String> sql = new ArrayList<>(); for (Map<String,Object> event : events) if (event.get("kind").equals("execute")) sql.add(String.valueOf(event.get("sql"))); return sql;
        }
        Map<String,Object> evidence() {
            Map<String,Object> evidence = new LinkedHashMap<>(); evidence.put("name", name); evidence.put("opened_physical_connections", connections);
            evidence.put("connection_ids", connectionIds); evidence.put("statement_failures", failures); evidence.put("commit_requested_at_nanos", commitStartedAt); evidence.put("commit_ack_at_nanos", commitAt);
            evidence.put("first_stop_revision_write_at_nanos", firstRevisionWriteAt); evidence.put("cleanup_status", cleanupStatus);
            evidence.put("cleanup_variables_null", cleanupCleared == 1); evidence.put("cleanup_connection_id", cleanupConnection); evidence.put("events", events); return evidence;
        }
    }

    /** Records real Statements and CONNECTION_ID; never substitutes connection, locking, writer or guard behavior. */
    private static final class RecordingSource extends AbstractDataSource {
        final DriverManagerDataSource verified; final ThreadLocal<Trace> current = new ThreadLocal<>();
        RecordingSource(DriverManagerDataSource verified) { this.verified = verified; }
        void start(Trace trace) { current.set(trace); } void clear() { current.remove(); }
        @Override public Connection getConnection() throws SQLException { return recording(verified.getConnection()); }
        @Override public Connection getConnection(String username, String password) throws SQLException { return recording(verified.getConnection(username, password)); }
        Connection recording(Connection raw) throws SQLException {
            Trace trace = current.get(); if (trace == null) return raw;
            long id;
            try (Statement statement = raw.createStatement(); ResultSet result = statement.executeQuery("SELECT CONNECTION_ID()")) { assertTrue(result.next()); id = result.getLong(1); }
            trace.connections++; trace.connectionIds.add(id); trace.event(id, "connection-open", "SELECT CONNECTION_ID()", Collections.emptyMap());
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("commit")) { trace.commitStartedAt = System.nanoTime(); trace.event(id, "commit-call", null, Collections.emptyMap()); }
                Object result = invoke(raw, method, args);
                if (method.getName().equals("commit")) { trace.commitAt = System.nanoTime(); trace.event(id, "commit", null, Collections.emptyMap()); }
                if (method.getName().equals("rollback")) trace.event(id, "rollback", null, Collections.emptyMap());
                if (result instanceof Statement && (method.getName().equals("prepareStatement") || method.getName().equals("createStatement"))) {
                    Statement statement = (Statement) result; String prepared = method.getName().equals("prepareStatement") ? String.valueOf(args[0]) : null;
                    Map<Integer,Object> binds = new TreeMap<>(); Class<?> kind = prepared == null ? Statement.class : PreparedStatement.class;
                    return Proxy.newProxyInstance(kind.getClassLoader(), new Class<?>[]{kind}, (ignored, operation, values) -> {
                        if (operation.getName().startsWith("set") && values != null && values.length >= 2 && values[0] instanceof Integer) binds.put((Integer) values[0], values[1]);
                        boolean execute = operation.getName().startsWith("execute");
                        String sql = prepared == null && execute && values != null && values.length > 0 ? String.valueOf(values[0]) : prepared;
                        if (execute && sql != null) {
                            trace.event(id, "execute", sql, binds);
                            if (sql.startsWith("INSERT INTO market_engine_runtime") && trace.runtimeAttempt != null) { trace.runtimeConnection = id; trace.runtimeAttempt.countDown(); }
                        }
                        try {
                            Object value = invoke(statement, operation, values);
                            if (execute && sql != null) {
                                if (sql.startsWith("UPDATE market_engine_runtime SET control_revision=control_revision+1") && trace.firstRevisionWriteAt == 0) trace.firstRevisionWriteAt = System.nanoTime();
                                trace.event(id, "execute-return", sql, binds);
                            }
                            return value;
                        } catch (Throwable failure) {
                            if (execute) { trace.failures++; trace.event(id, "execute-failed", sql, binds); }
                            throw failure;
                        }
                    });
                }
                return result;
            });
        }
    }
    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); } catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
