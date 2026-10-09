package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.math.BigDecimal;
import static com.gtcfesk.exchange.market.SourceHistoryGapRepairTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KlineGapBackfillTest extends TenantMarketTestContext {
    static void admin(Fixture f) {
        TradingSymbolRepository symbols = mock(TradingSymbolRepository.class);
        when(symbols.findByTenantIdAndId(1L, 1L)).thenAnswer(call -> Optional.of(f.config));
        ReflectionTestUtils.setField(f.market, "symbols", symbols);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("9", "", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    }
    @Test void latestSparseWindowUsesSlotCoverageAndTheSharedInsertOnlyQueue() {
        try (Fixture f = new Fixture()) {
            long last = SourceHistoryGapRepair.start("5m", System.currentTimeMillis()) - 300000;
            List<Map<String,Object>> sparse = new ArrayList<>(), complete = new ArrayList<>();
            sparse.add(bar(last - 200 * 300000L));
            for (int i = 0; i < 200; i++) {
                Map<String,Object> row = bar(last - (199 - i) * 300000L); complete.add(row);
                if (i != 101) sparse.add(row);
            }
            f.store.sourceCandles(1, "5m", sparse, System.currentTimeMillis());
            Map<String,Object> cached = response(sparse); cached.put("fetchedAt", System.currentTimeMillis());
            f.cache().put("BTCUSDT:5m:200", cached);
            List<Map<String,Object>> before = f.raw(); f.db.writes.clear();
            Map<String,Object> first = f.market.internalKline("BTCUSDT", "5m", 200);
            assertEquals(1L, f.metadata(first).get("sourceMissing")); assertEquals(1, f.queue().size());
            assertTrue(f.db.writes.isEmpty()); verifyNoInteractions(f.source);
            f.supply("5m", complete); f.tick();
            assertEquals(201, f.raw().size()); assertSame(cached, f.cache().get("BTCUSDT:5m:200"));
            assertEquals("complete", f.metadata(f.market.internalKline("BTCUSDT", "5m", 200)).get("state"));
            for (Map<String,Object> row : before) assertTrue(f.raw().contains(row));
            assertTrue(f.db.writes.stream().filter(sql -> sql.startsWith("INSERT INTO market_source_candle")).noneMatch(sql -> sql.contains("ON DUPLICATE")));
            assertEquals(1L, f.db.queryForObject("SELECT source_input_revision FROM market_engine_runtime", Long.class));
            f.market.internalKline("BTCUSDT", "5m", 200); assertTrue(f.queue().isEmpty());
            verify(f.source, times(1)).getHistoryWindow(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyLong());
        }
    }
    @Test void administratorCheckIsReadOnlyAndRepairPartitionsIntoSerialProviderWindows() {
        try (Fixture f = new Fixture()) {
            admin(f); f.db.writes.clear();
            Map<String,Object> check = f.market.checkSourceGaps(1L, M, M + 400 * 60000L, "1m", "Asia/Singapore");
            assertEquals(401L, check.get("sourceMissing")); assertEquals(401L, check.get("displayMissing"));
            assertEquals(false, check.get("pending")); assertTrue(f.queue().isEmpty()); assertTrue(f.db.writes.isEmpty());
            verifyNoInteractions(f.source);
            when(f.source.getHistoryWindow(anyString(), eq("1m"), anyInt(), anyString(), anyLong(), anyLong())).thenAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                long from = call.getArgument(4), to = call.getArgument(5); int limit = call.getArgument(2);
                assertTrue(limit <= 200); assertTrue(to - from < 200 * 60000L);
                List<Map<String,Object>> rows = new ArrayList<>(); for (long at = from; at <= to; at += 60000) rows.add(bar(at));
                return response(rows);
            });
            assertEquals(true, f.market.queueSourceGaps(1L, M, M + 400 * 60000L, "1m", "UTC").get("pending"));
            assertEquals(3, f.queue().size()); for (int i = 0; i < 3; i++) f.retryTick();
            check = f.market.checkSourceGaps(1L, M, M + 400 * 60000L, "1m", "UTC");
            assertEquals(0L, check.get("sourceMissing")); assertEquals(0L, check.get("displayMissing"));
            assertEquals(401L, check.get("inserted")); assertEquals(3L, check.get("sourceInputRevision"));
            assertTrue(f.raw().stream().allMatch(row -> ((String)row.get("body")).contains("\"historyOnly\":true")));
            f.market.queueSourceGaps(1L, M, M + 400 * 60000L, "1m", "UTC"); assertTrue(f.queue().isEmpty());
            verify(f.source, times(3)).getHistoryWindow(anyString(), anyString(), anyInt(), anyString(), anyLong(), anyLong());
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void queueCapacityChangedAtCommitIsReportedAsRejectedRatherThanAccepted() {
        try (Fixture f = new Fixture()) {
            Map<String,Object> cached = response(List.of(bar(SourceHistoryGapRepair.start("5m", System.currentTimeMillis()) - 300000)));
            cached.put("fetchedAt", System.currentTimeMillis()); f.cache().put("BTCUSDT:5m:401", cached);
            Map<String,Object> result = f.store.readConsumerSnapshot(() -> {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { synchronized (f.group) {
                            for (int i = 0; i < 32; i++) f.queue().put("occupied-" + i, new Object());
                        } }
                    });
                return f.market.internalKline("BTCUSDT", "5m", 401);
            });
            assertEquals(false, f.body(result).get("pending")); assertEquals(false, f.metadata(result).get("pending"));
            assertEquals(401L, f.metadata(result).get("sourceMissing"));
            List<Map<String,Object>> windows = (List<Map<String,Object>>)f.metadata(result).get("windows");
            assertEquals(3, windows.size()); assertTrue(windows.stream().allMatch(w -> "queue_full".equals(w.get("queueState"))));
            assertEquals(windows.get(2).get("from"), f.metadata(result).get("nextCursor"));
            verifyNoInteractions(f.source); assertTrue(f.raw().isEmpty());
        }
    }
    @Test void committedAuditKeepsTheRealCountWhenPostCommitCacheWorkFails() {
        try (Fixture f = new Fixture()) {
            admin(f);
            com.gtcfesk.exchange.control.ControlAuditService audit = mock(com.gtcfesk.exchange.control.ControlAuditService.class);
            ReflectionTestUtils.setField(f.market, "historyGapAudit", audit);
            boolean[] fail = { false };
            Map<String,Map<String,Object>> cache = new LinkedHashMap<String,Map<String,Object>>() {
                @Override public Set<Map.Entry<String,Map<String,Object>>> entrySet() {
                    if (fail[0]) { fail[0] = false; throw new IllegalStateException("cache failure after commit"); }
                    return super.entrySet();
                }
            };
            ReflectionTestUtils.setField(f.group, "klines", cache);
            f.market.checkSourceGaps(1L, M, M + 60000, "1m", "UTC"); verifyNoInteractions(audit);
            f.supply("1m", List.of(bar(M), bar(M + 60000)));
            f.market.queueSourceGaps(1L, M, M + 60000, "1m", "UTC"); fail[0] = true; f.retryTick();
            assertEquals(2, f.raw().size()); assertEquals(1, f.queue().size());
            verify(audit).record(eq(9L), eq(1L), isNull(), eq("history-gap.commit"), eq("1"), eq("COMPLETED"),
                argThat(detail -> ((Number)f.store.decode(detail).get("inserted")).intValue() == 2), isNull());
            f.retryTick(); assertTrue(f.queue().isEmpty()); assertEquals(2, f.raw().size());
            assertEquals(1L, f.db.queryForObject("SELECT source_input_revision FROM market_engine_runtime", Long.class));
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void nativePeriodsRemainIndependentAndFutureOpenMinutesAreRejected() throws Exception {
        try (Fixture f = new Fixture()) {
            admin(f);
            for (String period : List.of("1m", "5m", "15m", "30m", "1h")) {
                long first = SourceHistoryGapRepair.start(period, M), width = RandomMarketPath.duration(period);
                SourceHistoryGapRepair.Window window = f.inspect(period, 2, first + 2 * width - 1);
                Map<String,Object> seconds = bar(first); seconds.put("timestamp", first / 1000);
                assertEquals(2, f.repair.insert(window, response(List.of(bar(first + width), seconds)), System.currentTimeMillis()).inserted);
                assertFalse(f.inspect(period, 2, first + 2 * width - 1).needsSource());
            }
            assertEquals(10, f.raw().size());
            assertThrows(RuntimeException.class, () -> f.market.checkSourceGaps(1L, M, M + 60000, "1w", "UTC"));
            long now = System.currentTimeMillis() / 60000 * 60000;
            assertThrows(RuntimeException.class, () -> f.market.checkSourceGaps(1L, now, now, "1m", "UTC"));
            java.util.concurrent.CompletableFuture.runAsync(() -> { try (TenantContext.Scope ignored = TenantContext.open(2L)) {
                assertThrows(RuntimeException.class, () -> f.market.checkSourceGaps(1L, M, M + 60000, "1m", "UTC"));
            }}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void manualRepairCannotBypassRestoreOrSimulatedFrozenPrefixes() {
        try (Fixture f = new Fixture()) {
            admin(f);
            f.store.locked(1, () -> {
                f.db.update("INSERT INTO market_history_restore_minute(tenant_id,job_id,symbol_id,minute_at,before_json,source_json,checksum) VALUES(1,'prepared',1,?,'{}','{}',?)", M, "0".repeat(64)); return null;
            });
            Map<String,Object> result = f.market.queueSourceGaps(1L, M, M, "1m", "UTC");
            assertEquals(1L, result.get("protected")); assertEquals(false, result.get("pending")); assertTrue(f.queue().isEmpty());
            f.config.setRandomMarketEnabled(true); f.config.setRandomMarketStartedAt(M + 600000); f.config.setRandomMarketBasePrice(BigDecimal.valueOf(100));
            f.db.update("UPDATE trading_symbol SET random_market_enabled=TRUE,random_market_started_at=? WHERE tenant_id=1 AND id=1", M + 600000);
            SourceHistoryGapRepair.Window window = f.inspect("5m", 2, M + 86400000 + 599999);
            assertFalse(window.needsSource()); assertEquals("simulation_history", window.gaps.get(0).get("reason"));
            assertEquals(0, f.repair.insert(window, response(List.of(bar(M + 86400000), bar(M + 86400000 + 300000))), System.currentTimeMillis()).inserted);
            assertTrue(f.raw().isEmpty()); verifyNoInteractions(f.source);
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void invalidDuplicateAndForeignResponseCannotSneakInAValidCompanion() {
        try (Fixture f = new Fixture()) {
            SourceHistoryGapRepair.Window window = f.inspect("1m", 2, M + 119999);
            Map<String,Object> invalid = bar(M); invalid.put("partial", true);
            assertEquals(0, f.repair.insert(window, response(List.of(bar(M), invalid)), System.currentTimeMillis()).inserted);
            Map<String,Object> foreign = response(List.of(bar(M))); ((Map<String,Object>)foreign.get("data")).put("code", "ETHUSDT");
            assertThrows(MarketHttp.Failure.class, () -> f.repair.insert(window, foreign, System.currentTimeMillis()));
            assertTrue(f.raw().isEmpty());
        }
    }
}
