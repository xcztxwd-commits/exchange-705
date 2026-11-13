package com.gtcfesk.exchange.user;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.tenant.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic users only, on a separately identified MySQL 5.7 instance with an imported schema-only fixture. */
@EnabledIfSystemProperty(named="equity.encoding.fixture", matches=".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssetEquityEncodingMysqlTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static JdbcTemplate db;
    static DriverManagerDataSource source;
    static AssetEquityStore store;

    @BeforeAll static void identifyFixtureBeforeAnyWrites() throws Exception {
        JsonNode config = JSON.readTree(Files.readAllBytes(Paths.get(System.getProperty("equity.encoding.fixture"))));
        String url = config.path("url").asText();
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:33419/mt705_equity_encoding_[a-zA-Z0-9_]+\\?.*"));
        source = new DriverManagerDataSource(url, config.path("username").asText(), config.path("password").asText());
        db = new JdbcTemplate(source);
        assertEquals(config.path("serverUuid").asText(), db.queryForObject("SELECT @@server_uuid", String.class));
        assertTrue(db.queryForObject("SELECT VERSION()", String.class).startsWith("5.7."));
        assertEquals(config.path("marker").asText(), db.queryForObject("SELECT marker FROM sqlfix_fixture_marker", String.class));
        assertEquals("latin1", db.queryForObject("SELECT character_set_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='asset_history_quote_batch' AND column_name='evidence'", String.class));
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM user_account", Long.class), "An empty, exclusive synthetic fixture is required");
        db.update("INSERT INTO tenant(id,code,name,status,created_at) VALUES(1,'encoding_a','encoding_a','ACTIVE',UTC_TIMESTAMP()),(2,'encoding_b','encoding_b','ACTIVE',UTC_TIMESTAMP())");
        db.update("INSERT INTO user_account(tenant_id,id,email,password_hash,row_version) VALUES(1,101,'encoding101@test.invalid','not-a-login',0),(1,102,'encoding102@test.invalid','not-a-login',0),(2,201,'encoding201@test.invalid','not-a-login',0)");
        db.update("INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version) VALUES(1,101,'FUND',100,10,0),(1,102,'FUND',75,0,0),(2,201,'FUND',45,0,0)");
        db.update("INSERT INTO contract_order(tenant_id,user_id,symbol,side,quantity,open_price,status,type,created_at,updated_at,limit_match_enabled,row_version,lot_size,leverage,fee,quote_currency,quote_source) VALUES(1,101,'JPY=X','BUY',1,100,'OPEN','MARKET',UTC_TIMESTAMP(),UTC_TIMESTAMP(),0,0,1,1,0,'JPY','yahoo')");
        store = new AssetEquityStore(source, JSON);
    }

    @Test @Order(1) void originalUnescapedEvidenceReproduces1366AndRollsBack() throws Exception {
        Map<String,Object> evidence = Collections.singletonMap("marketReason", "外汇周末休市");
        try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            DataAccessException failure = assertThrows(DataAccessException.class, () -> store.locked("encoding-baseline", s ->
                s.db.update("INSERT INTO asset_history_quote_batch(tenant_id,batch_id,prepared_at,evidence) VALUES(1,?,?,?)", "encoding-baseline", 1L, raw(evidence))));
            assertTrue(failure.getRootCause() instanceof SQLException);
            SQLException sql = (SQLException) failure.getRootCause();
            assertEquals(1366, sql.getErrorCode());
            assertEquals("HY000", sql.getSQLState());
            assertTrue(sql.getMessage().contains("evidence"));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM asset_history_quote_batch WHERE batch_id='encoding-baseline'", Integer.class));
            assertEquals(1, db.queryForObject("SELECT IS_FREE_LOCK('tenant_1_equity_v1_encoding-baseline')", Integer.class));
        }
    }

    @Test @Order(2) void realStoragePreservesUnicodeEvidenceAmountsAndIdempotence() throws Exception {
        long observed = System.currentTimeMillis() - 120000;
        EquityValuationService.Batch batch = new EquityValuationService.Batch();
        batch.quotes.put("JPY=X", Collections.singletonMap("marketReason", "外汇周末休市 \uD83D\uDE80"));
        EquityValuationService.Value value = new EquityValuationService.Value(102, observed);
        value.amounts.put("wallet_balance", new BigDecimal("75.1234567890123456"));
        value.evidence.put("备注", "原始值保留");
        value.finish();
        try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            store.locked("encoding-storage", s -> store.saveMinutes(s.db, batch, Collections.singletonList(value)));
            String saved = db.queryForObject("SELECT evidence FROM asset_history_quote_batch WHERE tenant_id=1 AND batch_id=?", String.class, batch.id);
            assertTrue(saved.chars().allMatch(c -> c < 128));
            assertEquals("外汇周末休市 \uD83D\uDE80", JSON.readTree(saved).path("quotes").path("JPY=X").path("marketReason").asText());
            String detail = db.queryForObject("SELECT valuation_evidence FROM asset_history_1m WHERE tenant_id=1 AND user_id=102", String.class);
            assertEquals("原始值保留", JSON.readTree(detail).path("备注").asText());
            assertEquals(0, new BigDecimal("75.1234567890123456").compareTo(db.queryForObject("SELECT net_equity FROM asset_history_1m WHERE tenant_id=1 AND user_id=102", BigDecimal.class)));
            value.amounts.put("wallet_balance", BigDecimal.ZERO); value.finish();
            store.locked("encoding-repeat", s -> store.saveMinutes(s.db, new EquityValuationService.Batch(), Collections.singletonList(value)));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM asset_history_1m WHERE tenant_id=1 AND user_id=102", Integer.class));
            assertEquals(0, new BigDecimal("75.1234567890123456").compareTo(db.queryForObject("SELECT net_equity FROM asset_history_1m WHERE tenant_id=1 AND user_id=102", BigDecimal.class)));
        }
    }

    @Test @Order(3) void actualMinuteWorkerAdvancesAllTenantsWithoutChangingMoneyOrOrders() throws Exception {
        ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
        when(market.snapshotPrice("JPY=X")).thenAnswer(call -> {
            Map<String,Object> quote = new LinkedHashMap<>();
            quote.put("marketReason", "外汇周末休市"); quote.put("available", false);
            quote.put("timestamp", System.currentTimeMillis()); return quote;
        });
        when(market.contractConversion("JPY", "yahoo")).thenReturn(Collections.singletonMap("conversionAvailable", false));
        AssetEquityJobs jobs = new AssetEquityJobs(store, new EquityValuationService(db, market));
        ReflectionTestUtils.setField(jobs, "tenantJobs", new TenantJobRunner(db, new DataSourceTransactionManager(source)));
        ReflectionTestUtils.setField(jobs, "enabled", true);
        List<Map<String,Object>> money = db.queryForList("SELECT * FROM asset_account ORDER BY tenant_id,user_id");
        List<Map<String,Object>> orders = db.queryForList("SELECT * FROM contract_order ORDER BY tenant_id,id");
        long now = System.currentTimeMillis();
        if (now % 60000 > 59000) Thread.sleep(60020 - now % 60000);
        long boundary = AssetHistoryBucket.floor(System.currentTimeMillis(), 60000);
        try {
            jobs.minuteTick();
            awaitProgress(boundary);
            long observed = db.queryForObject("SELECT MIN(observed_at) FROM asset_history_1m WHERE bucket_start=?", Long.class, boundary - 60000);
            assertTrue(observed >= boundary && observed < boundary + 60000);
            assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM asset_history_1m WHERE bucket_start=?", Integer.class, boundary - 60000));
            assertEquals("INCOMPLETE", db.queryForObject("SELECT valuation_status FROM asset_history_1m WHERE tenant_id=1 AND user_id=101 AND bucket_start=?", String.class, boundary - 60000));
            assertNull(db.queryForObject("SELECT net_equity FROM asset_history_1m WHERE tenant_id=1 AND user_id=101 AND bucket_start=?", BigDecimal.class, boundary - 60000), "A closed market must not turn missing P&L into zero");
            assertEquals(0, new BigDecimal("45").compareTo(db.queryForObject("SELECT net_equity FROM asset_history_1m WHERE tenant_id=2 AND user_id=201 AND bucket_start=?", BigDecimal.class, boundary - 60000)));
            awaitIdle(jobs); jobs.minuteTick(); awaitIdle(jobs);
            assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM asset_history_1m WHERE bucket_start=?", Integer.class, boundary - 60000));
            assertEquals(money, db.queryForList("SELECT * FROM asset_account ORDER BY tenant_id,user_id"));
            assertEquals(orders, db.queryForList("SELECT * FROM contract_order ORDER BY tenant_id,id"));
            assertEquals(1, db.queryForObject("SELECT IS_FREE_LOCK('tenant_1_equity_v1_capture')", Integer.class));
        } finally { jobs.stop(); }
    }

    static String raw(Object value) { try { return JSON.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
    static void awaitProgress(long boundary) throws Exception {
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            if (db.queryForObject("SELECT COUNT(*) FROM asset_history_job_state WHERE task_name='capture' AND watermark=?", Integer.class, boundary) == 2) return;
            Thread.sleep(30);
        }
        fail("Both synthetic tenant capture watermarks must advance, including the Unicode quote tenant");
    }
    static void awaitIdle(AssetEquityJobs jobs) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean busy = (java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(jobs, "collecting");
        long deadline = System.currentTimeMillis() + 10000;
        while (busy.get() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertFalse(busy.get());
    }
}
