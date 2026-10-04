package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Whole source events use real owned MySQL; injected failure is inside the physical transaction. */
class S2SourceEventMysqlTest extends TenantMarketTestContext {
    @BeforeAll static void ownedMysql() throws Exception { S2RuntimeMysqlTest.identity(); }
    static class FailingJdbc extends JdbcTemplate {
        int batch, failAt;
        FailingJdbc() { super(S2RuntimeMysqlTest.data); }
        @Override public int update(String sql, Object... args) {
            if (sql.startsWith("INSERT INTO market_source_candle") && ++batch == failAt)
                throw new IllegalStateException("injected source candle batch " + batch);
            return super.update(sql, args);
        }
    }
    FailingJdbc db;
    ControlHistoryStore store;
    TradingSymbol first, second;
    @BeforeEach void setup() {
        db = new FailingJdbc(); store = new ControlHistoryStore(db, new DataSourceTransactionManager(S2RuntimeMysqlTest.data));
        first = symbol(1); second = symbol(1);
    }
    TradingSymbol symbol(long tenant) {
        TradingSymbol value = new TradingSymbol(); value.setTenantId(tenant); value.setId(S2RuntimeMysqlTest.ids.incrementAndGet());
        value.setSymbol("S2_EVENT_" + value.getId()); value.setPricePrecision(2); value.setIsEnabled(true);
        if (S2RuntimeMysqlTest.jointFixture()) {
            value.setBaseCurrency("TEST"); value.setName(value.getSymbol());
            value.setMarketSource("yahoo"); value.setSourceCategory(value.getCategory());
            db.update("INSERT INTO trading_symbol(id,tenant_id,symbol,base_currency,quote_currency,name,category,is_enabled,price_precision,control_enabled,row_version,market_source,source_category) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    value.getId(), value.getTenantId(), value.getSymbol(), value.getBaseCurrency(), value.getQuoteCurrency(), value.getName(),
                    value.getCategory(), value.getIsEnabled(), value.getPricePrecision(), value.getControlEnabled(), value.getRowVersion(), value.getMarketSource(), value.getSourceCategory());
        } else db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,?)", value.getId(), tenant);
        return value;
    }
    List<Map<String,Object>> candles(int count, long minute) {
        List<Map<String,Object>> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(new LinkedHashMap<>(Map.of("timestamp", minute + i * 60000L,
                "open_price", new BigDecimal("90.00"), "high_price", new BigDecimal("95.00"), "low_price", new BigDecimal("89.00"),
                "close_price", new BigDecimal("91.00"), "volume", i)));
        return result;
    }
    List<Map<String,Object>> state() {
        return new JdbcTemplate(S2RuntimeMysqlTest.data).queryForList("SELECT tenant_id,symbol_id,period,candle_at,body,received_at FROM market_source_candle WHERE tenant_id=1 AND symbol_id IN (?,?) ORDER BY symbol_id,candle_at", first.getId(), second.getId());
    }
    @Test void secondPhysicalBatchAndLateAliasFailureRollbackTheEntireEventAndRetryIsIdempotent() {
        long minute = System.currentTimeMillis() / 60000 * 60000 - 60000;
        List<Long> aliases = Arrays.asList(second.getId(), first.getId(), first.getId());
        store.sourceCandles(aliases, "1m", candles(1, minute), minute);
        List<Map<String,Object>> before = state();
        List<Map<String,Object>> event = candles(501, minute);
        for (int failure : new int[]{2, 4}) {
            db.batch = 0; db.failAt = failure;
            assertThrows(IllegalStateException.class, () -> store.sourceCandles(aliases, "1m", event, minute + 60000));
            assertEquals(failure, db.batch, "failure occurred in the expected physical statement");
            assertEquals(before, state(), "all rows and both aliases roll back together");
        }
        db.batch = 0; db.failAt = 0;
        store.sourceCandles(aliases, "1m", event, minute + 60000);
        List<Map<String,Object>> committed = state(); assertEquals(1002, committed.size()); assertEquals(4, db.batch);
        store.sourceCandles(aliases, "1m", event, minute + 60000);
        assertEquals(committed, state(), "lost acknowledgement retry cannot duplicate or change candle bodies");
    }
    Map<String,Object> quote(String event, long source, int price) {
        return new LinkedHashMap<>(Map.of("eventId", event+"-"+first.getId(), "timestamp", source, "sourceTimestamp", source,
                "price", price, "available", true, "sourceAvailable", true, "fetchedAt", source, "expiresAt", source + 60000));
    }
    @Test void lateAndOutOfOrderEventsCannotRewriteFrozenPrefixOrUnpumpedSnapshotAndCrossTenantReadIsDenied() throws Exception {
        long minute = System.currentTimeMillis() / 60000 * 60000 - 60000, cutoff = minute + 30000;
        PersistentPriceControl controls = new PersistentPriceControl(store);
        Map<String,Object> original = quote("first", minute + 1000, 90), tied = quote("tie", minute + 1000, 95);
        controls.sourceQuote(first, original, minute + 1000); controls.sourceQuote(first, tied, minute + 2000);
        store.locked(first.getId(), () -> { store.freeze(first.getId(), cutoff); return null; });
        List<Map<String,Object>> frozen = store.mixed(first.getId(), minute, minute + 60000);
        assertFalse(frozen.isEmpty());
        assertEquals(0, new BigDecimal("95").compareTo(ControlHistoryStore.number(frozen.get(0).get("high_price"))));
        controls.pump(first, tied, cutoff, 60000);
        Map<String,Object> committed = controls.display(first, Collections.emptyMap(), cutoff);
        Map<String,Object> late = quote("late-old-source", minute + 500, 120);
        controls.sourceQuote(first, late, cutoff + 1000); controls.sourceQuote(first, late, cutoff + 2000);
        store.locked(first.getId(), () -> { store.freeze(first.getId(), cutoff); return null; });
        assertEquals(frozen, store.mixed(first.getId(), minute, minute + 60000));
        assertEquals(committed, controls.display(first, Collections.emptyMap(), cutoff));
        // Existing durable ordering rejects stale source timestamps before inserting an event.
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=1 AND symbol_id=?", Integer.class, first.getId()));
        assertEquals(0, new BigDecimal("95").compareTo(ControlHistoryStore.number(store.lastQuote(first.getId()).get("price"))));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                assertNull(TenantContext.currentTenantId());
                try (TenantContext.Scope ignored = TenantContext.open(2L)) {
                    TradingSymbol foreign = symbol(2);
                    assertThrows(org.springframework.security.access.AccessDeniedException.class,
                            () -> controls.display(first, Collections.emptyMap(), cutoff));
                    Map<String,Object> own = controls.display(foreign, Collections.emptyMap(), cutoff);
                    assertEquals("engine_pending", own.get("status")); assertFalse(own.containsKey("price"));
                    assertTrue(store.mixed(first.getId(), minute, minute + 60000).isEmpty());
                }
            }).get(10, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
        assertEquals(1L, TenantContext.currentTenantId());
    }
}
