package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real H2 transactions and connections. This is not a migrated-MySQL or lease-takeover proof. */
class FundingQuoteAuthorityJointTest {
    final List<String> sql = new CopyOnWriteArrayList<>();
    JdbcTemplate database;
    ControlHistoryStore store;
    FundingQuoteAuthority authority;
    TransactionTemplate transaction;
    TradingSymbol config;
    TenantContext.Scope tenant;

    @BeforeEach void setup() {
        tenant = TenantContext.open(1L);
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:joint_authority_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "") {
            @Override public Connection getConnection() throws SQLException { return recording(super.getConnection()); }
        };
        database = new JdbcTemplate(source);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        store = new ControlHistoryStore(database, manager);
        authority = new FundingQuoteAuthority(store);
        database.execute("CREATE TABLE trading_symbol(tenant_id BIGINT,id BIGINT,symbol VARCHAR(32),row_version BIGINT,is_enabled BOOLEAN,price_precision INT,market_source VARCHAR(32),source_category VARCHAR(32),quote_currency VARCHAR(16),random_market_enabled BOOLEAN,random_market_started_at BIGINT,PRIMARY KEY(tenant_id,id))");
        database.execute("CREATE TABLE market_engine_runtime(tenant_id BIGINT,symbol_id BIGINT,writer_generation BIGINT,control_revision BIGINT,snapshot_version BIGINT,quote_json VARCHAR(16000),owner_id VARCHAR(64),lease_until BIGINT,committed_at BIGINT,status_json VARCHAR(16000),PRIMARY KEY(tenant_id,symbol_id))");
        database.execute("CREATE TABLE joint_funding_checkpoint(id INT PRIMARY KEY,amount DECIMAL(32,16))");
        config = new TradingSymbol(); config.setTenantId(1L); config.setId(1L); config.setSymbol("JOINTUSD");
        config.setMarketSource("binance"); config.setSourceCategory("Crypto"); config.setQuoteCurrency("USD");
        config.setPricePrecision(4); config.setRowVersion(7L); config.setRandomMarketEnabled(false);
        database.update("INSERT INTO trading_symbol VALUES(1,1,?,7,true,4,'binance','Crypto','USD',false,NULL)", config.getSymbol());
        database.update("INSERT INTO market_engine_runtime VALUES(1,1,3,4,5,NULL,'existing-engine',?,0,'{}')", System.currentTimeMillis() + 15000);
        database.execute("ALTER TABLE market_engine_runtime ADD history_restore_revision BIGINT NOT NULL DEFAULT 0");
        publish(System.currentTimeMillis() + 60000);
        sql.clear();
    }
    @AfterEach void close() throws SQLException {
        try {
            if (database != null) try (Connection connection = database.getDataSource().getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
        finally { if (tenant != null) tenant.close(); }
    }

    /** Observe real JDBC executions without mocking transaction or database behavior. */
    Connection recording(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            Object result = invoke(connection, method, args);
            if (result instanceof Statement && (method.getName().equals("prepareStatement") || method.getName().equals("createStatement"))) {
                final String prepared = args == null || args.length == 0 ? null : String.valueOf(args[0]);
                Statement statement = (Statement) result;
                Class<?> kind = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                return Proxy.newProxyInstance(kind.getClassLoader(), new Class<?>[]{kind}, (ignored, operation, values) -> {
                    if (operation.getName().startsWith("execute")) {
                        String query = prepared != null ? prepared : values != null && values.length > 0 && values[0] instanceof String ? (String) values[0] : null;
                        if (query != null) sql.add(query);
                    }
                    return invoke(statement, operation, values);
                });
            }
            return result;
        });
    }
    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    void publish(long expiry) {
        Map<String,Object> quote = new LinkedHashMap<>();
        quote.put("price", new BigDecimal("110.1234")); quote.put("timestamp", System.currentTimeMillis());
        quote.put("tenantId", 1L); quote.put("symbolId", 1L); quote.put("writerGeneration", 3L);
        quote.put("controlRevision", 4L); quote.put("quoteVersion", 5L); quote.put("executionExpiresAt", expiry);
        quote.put("available", true); quote.put("tradeAvailable", true); FundingQuoteAuthority.stamp(quote, config);
        database.update("UPDATE market_engine_runtime SET quote_json=? WHERE tenant_id=1 AND symbol_id=1", store.encode(quote));
    }
    void reject(Map<String,Object> prepared) {
        BusinessException failure = assertThrows(BusinessException.class, () -> transaction.execute(status -> {
            authority.validate(Collections.singletonList(prepared));
            database.update("INSERT INTO joint_funding_checkpoint VALUES(99,1)");
            return null;
        }));
        assertTrue(failure.getMessage().startsWith("S3_QUOTE_REJECTED:"));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM joint_funding_checkpoint", Integer.class));
    }

    @Test void productionStampCapturesConfigurationAndNormalizesPrecision() {
        Map<String,Object> quote = new HashMap<>(); quote.put("timestamp", 123L);
        config.setPricePrecision(null); config.setRandomMarketEnabled(true); config.setRandomMarketStartedAt(456L);
        FundingQuoteAuthority.stamp(quote, config);
        assertEquals(123L, quote.get("executionSampledAt")); assertEquals(7L, quote.get("configVersion"));
        assertEquals(2, quote.get("pricePrecision")); assertEquals("binance", quote.get("configuredSource"));
        assertEquals("Crypto", quote.get("configuredCategory")); assertEquals("JOINTUSD", quote.get("configuredCode"));
        assertEquals("USD", quote.get("configuredQuoteCurrency")); assertEquals(456L, quote.get("randomSession"));
        config.setPricePrecision(99); config.setRandomMarketEnabled(false); FundingQuoteAuthority.stamp(quote, config);
        assertEquals(8, quote.get("pricePrecision")); assertEquals(0L, quote.get("randomSession"));
        config.setPricePrecision(-1); FundingQuoteAuthority.stamp(quote, config); assertEquals(0, quote.get("pricePrecision"));
    }

    @Test void existingAuthorityReadsNeverClaimLeaseOrPerformMaintenanceDml() {
        Map<String,Object> before = database.queryForMap("SELECT * FROM market_engine_runtime"); sql.clear();
        Map<String,Object> prepared = authority.prepare(config.getSymbol());
        assertEquals(1, sql.size()); assertFalse(sql.get(0).toUpperCase(Locale.ROOT).contains("FOR UPDATE"));
        assertThrows(UnsupportedOperationException.class, () -> prepared.put("price", BigDecimal.ONE));
        transaction.execute(status -> { authority.validate(Collections.singletonList(prepared)); return null; });
        List<String> authoritySql = new ArrayList<>(sql);
        assertFalse(authoritySql.isEmpty());
        for (String statement : authoritySql) assertTrue(statement.trim().toUpperCase(Locale.ROOT).startsWith("SELECT "), statement);
        assertEquals(before, database.queryForMap("SELECT * FROM market_engine_runtime"));
        System.out.println("JOINT_AUTHORITY_READ_SQL=" + authoritySql);
    }

    @Test void committedRuntimeRevisionGenerationAndSnapshotChangesReject() {
        for (String field : Arrays.asList("control_revision", "writer_generation", "snapshot_version")) {
            Map<String,Object> prepared = authority.prepare(config.getSymbol());
            database.update("UPDATE market_engine_runtime SET " + field + "=" + field + "+1 WHERE tenant_id=1 AND symbol_id=1");
            reject(prepared);
            database.update("UPDATE market_engine_runtime SET writer_generation=3,control_revision=4,snapshot_version=5 WHERE tenant_id=1 AND symbol_id=1");
        }
    }

    @Test void committedConfigurationChangesRejectBeforeFundingDml() {
        List<String> changes = Arrays.asList("row_version=8", "is_enabled=false", "price_precision=7", "market_source='yahoo'",
                "source_category='Forex'", "symbol='OTHER'", "quote_currency='EUR'", "random_market_enabled=true,random_market_started_at=99");
        for (String change : changes) {
            Map<String,Object> prepared = authority.prepare(config.getSymbol());
            database.update("UPDATE trading_symbol SET " + change + " WHERE tenant_id=1 AND id=1");
            reject(prepared);
            database.update("UPDATE trading_symbol SET row_version=7,is_enabled=true,price_precision=4,market_source='binance',source_category='Crypto',symbol='JOINTUSD',quote_currency='USD',random_market_enabled=false,random_market_started_at=NULL WHERE tenant_id=1 AND id=1");
        }
    }

    @Test void expiredDeadlineAndExpiryBeforePhysicalCommitRejectAndRollback() {
        publish(System.currentTimeMillis() - 1); reject(authority.prepare(config.getSymbol()));
        long expiry = System.currentTimeMillis() + 1500; publish(expiry);
        Map<String,Object> prepared = authority.prepare(config.getSymbol());
        assertThrows(BusinessException.class, () -> transaction.execute(status -> {
            authority.validate(Collections.singletonList(prepared)); database.update("INSERT INTO joint_funding_checkpoint VALUES(1,10)");
            while (System.currentTimeMillis() <= expiry) {
                try { Thread.sleep(10); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            return null;
        }));
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM joint_funding_checkpoint", Integer.class));
    }

    @Test void deferredOrmFlushPrecedesDeadlineAuthorizationAndItsFailureRollsBack() {
        javax.persistence.EntityManager em=org.mockito.Mockito.mock(javax.persistence.EntityManager.class);
        org.springframework.test.util.ReflectionTestUtils.setField(authority,"entityManager",em);
        long expiry=System.currentTimeMillis()+500;publish(expiry);Map<String,Object> prepared=authority.prepare(config.getSymbol());
        org.mockito.Mockito.doAnswer(invocation->{
            assertEquals(1,database.queryForObject("SELECT COUNT(*) FROM joint_funding_checkpoint",Integer.class));
            while(System.currentTimeMillis()<=expiry) Thread.sleep(5);
            return null;
        }).when(em).flush();
        assertThrows(BusinessException.class,()->transaction.execute(status->{authority.validate(Collections.singletonList(prepared));database.update("INSERT INTO joint_funding_checkpoint VALUES(1,10)");return null;}));
        org.mockito.Mockito.verify(em).flush();assertEquals(0,database.queryForObject("SELECT COUNT(*) FROM joint_funding_checkpoint",Integer.class));
        org.mockito.Mockito.reset(em);publish(System.currentTimeMillis()+60000);Map<String,Object> retry=authority.prepare(config.getSymbol());
        org.mockito.Mockito.doThrow(new IllegalStateException("injected ORM flush failure")).when(em).flush();
        assertEquals("injected ORM flush failure",assertThrows(IllegalStateException.class,()->transaction.execute(status->{authority.validate(Collections.singletonList(retry));database.update("INSERT INTO joint_funding_checkpoint VALUES(2,10)");return null;})).getMessage());
        assertEquals(0,database.queryForObject("SELECT COUNT(*) FROM joint_funding_checkpoint",Integer.class));
    }

    @Test void historyPublicationCannotRestampOldAuthorityAfterRealTakeoverOrStopRevision() {
        for(boolean stop:new boolean[]{false,true}) {
            // The real writer claim changes owner/generation; the old quote must remain fenced.
            database.update("UPDATE market_engine_runtime SET lease_until=0 WHERE tenant_id=1 AND symbol_id=1");
            String quote=database.queryForObject("SELECT quote_json FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1",String.class);
            long version=database.queryForObject("SELECT snapshot_version FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1",Long.class);
            store.locked(1,()->{if(stop) store.runtime.invalidate(1);store.runtime.historyPublished(1);return null;});
            assertEquals(quote,database.queryForObject("SELECT quote_json FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1",String.class));
            assertEquals(version,database.queryForObject("SELECT snapshot_version FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1",Long.class));
            reject(authority.prepare(config.getSymbol()));
        }
    }

    @Test void historyPublicationCannotRestampStopRevisionWhenGenerationAndVersionStillMatch() {
        database.execute("CREATE TABLE market_control_task(tenant_id BIGINT,id VARCHAR(64),symbol_id BIGINT)");
        database.execute("CREATE TABLE market_control_publication(tenant_id BIGINT,task_id VARCHAR(64),to_at BIGINT)");
        database.update("UPDATE market_engine_runtime SET lease_until=0 WHERE tenant_id=1 AND symbol_id=1");
        Map<String,Object> input=new LinkedHashMap<>(authority.prepare(config.getSymbol()));
        store.locked(1,()->{store.runtime.snapshot(1,input,Collections.emptyMap(),store.runtime.clock());return null;});
        Map<String,Object> prepared=authority.prepare(config.getSymbol());
        transaction.execute(status->{authority.validate(Collections.singletonList(prepared));return null;});
        String quote=database.queryForObject("SELECT quote_json FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1",String.class);
        long generation=database.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1",Long.class);
        long version=database.queryForObject("SELECT snapshot_version FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1",Long.class);
        store.locked(1,()->{store.runtime.invalidate(1);store.runtime.historyPublished(1);return null;});
        assertEquals(generation,database.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1",Long.class));
        assertEquals(version,database.queryForObject("SELECT snapshot_version FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1",Long.class));
        assertEquals(quote,database.queryForObject("SELECT quote_json FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1",String.class));
        reject(authority.prepare(config.getSymbol()));
    }

    @Test void authorityRequiresNoOuterPreparationAndActualFundingTransaction() {
        assertThrows(IllegalStateException.class, () -> authority.validate(Collections.singletonList(authority.prepare(config.getSymbol()))));
        assertThrows(IllegalStateException.class, () -> authority.lockRuntimeIdentity(1L));
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> authority.prepare(config.getSymbol())));
    }

    @Test void runtimeAndConfigurationLocksShareFundingConnectionAndHoldUntilCommit() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            int checkpoint = 0;
            for (String table : Arrays.asList("market_engine_runtime", "trading_symbol")) {
                final int id = ++checkpoint;
                Map<String,Object> prepared = authority.prepare(config.getSymbol());
                CountDownLatch validated = new CountDownLatch(1), release = new CountDownLatch(1), attempting = new CountDownLatch(1);
                AtomicInteger ownerSession = new AtomicInteger(), writerSession = new AtomicInteger();
                Future<?> holder = workers.submit(() -> {
                    try (TenantContext.Scope ignored = TenantContext.open(1L)) {
                        transaction.execute(status -> {
                            ownerSession.set(database.queryForObject("SELECT SESSION_ID()", Integer.class));
                            authority.validate(Collections.singletonList(prepared));
                            assertEquals(ownerSession.get(), database.queryForObject("SELECT SESSION_ID()", Integer.class));
                            database.update("INSERT INTO joint_funding_checkpoint VALUES(?,10)", id);
                            validated.countDown();
                            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                            return null;
                        });
                    }
                });
                try {
                    assertTrue(validated.await(10, TimeUnit.SECONDS));
                    Future<?> writer = workers.submit(() -> transaction.execute(status -> {
                        writerSession.set(database.queryForObject("SELECT SESSION_ID()", Integer.class)); attempting.countDown();
                        // Updating even an unchanged value must wait for the authority's existing-row lock.
                        database.update("UPDATE " + table + " SET " + (table.equals("trading_symbol") ? "row_version=row_version WHERE tenant_id=1 AND id=1" : "control_revision=control_revision WHERE tenant_id=1 AND symbol_id=1"));
                        return null;
                    }));
                    assertTrue(attempting.await(10, TimeUnit.SECONDS)); assertNotEquals(ownerSession.get(), writerSession.get());
                    assertThrows(TimeoutException.class, () -> writer.get(250, TimeUnit.MILLISECONDS));
                    assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM joint_funding_checkpoint WHERE id=?", Integer.class, id));
                    release.countDown(); holder.get(10, TimeUnit.SECONDS); writer.get(10, TimeUnit.SECONDS);
                    assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM joint_funding_checkpoint WHERE id=?", Integer.class, id));
                    System.out.println("JOINT_AUTHORITY_LOCK table=" + table + " fundingSession=" + ownerSession.get() + " writerSession=" + writerSession.get() + " blockedBeforeCommit=true");
                } finally { release.countDown(); }
            }
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
