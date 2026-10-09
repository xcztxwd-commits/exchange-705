package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.trade.ManualOrderPrices;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring transactions and the real conversion cache/queue; no network or financial writes. */
class ManualOrderHistoryLoadingTransactionTest extends TenantMarketTestContext {
    private static final long WINDOW = 720 * 60000L;
    private final long windowEnd = Math.floorDiv(System.currentTimeMillis(), WINDOW) * WINDOW - 1;
    private final long open = windowEnd - 119999, close = open + 60000;

    private class Fixture implements AutoCloseable {
        final ForexQuoteMarketService market = spy(new ForexQuoteMarketService());
        final TradingSymbol symbol = new TradingSymbol();
        final DataSourceTransactionManager manager = new DataSourceTransactionManager(
            new DriverManagerDataSource("jdbc:h2:mem:manual_loading_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        final ManualOrderPrices direct = new ManualOrderPrices(market), transactional;
        final Object group;
        Fixture() {
            symbol.setSymbol("JPY=X"); symbol.setSourceCategory("Forex"); symbol.setMarketSource("yahoo");
            symbol.setBaseCurrency("USD"); symbol.setQuoteCurrency("JPY");
            ReflectionTestUtils.setField(market, "controlHistory", new ControlHistoryStore(new JdbcTemplate(manager.getDataSource()), manager));
            Map<?, ?> groups = (Map<?, ?>) ReflectionTestUtils.getField(marketState(market), "groups");
            group = groups.get("Forex");
            ReflectionTestUtils.setField(group, "codes", Collections.singletonList("JPYUSD=X"));
            doReturn(response("158.13")).when(market).historicalKline(anyString(), anyString(), anyInt(), anyLong());
            ProxyFactory proxy = new ProxyFactory(direct); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            transactional = (ManualOrderPrices) proxy.getProxy();
        }
        Map<String, Object> response(String price) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (long time : new long[] {open, close}) {
                Map<String, Object> row = new LinkedHashMap<>(); row.put("timestamp", time);
                for (String field : Arrays.asList("open_price", "low_price", "high_price", "close_price")) row.put(field, price);
                rows.add(row);
            }
            Map<String, Object> out = new HashMap<>(); out.put("kline_list", rows); out.put("fetchedAt", System.currentTimeMillis());
            return Collections.singletonMap("data", out);
        }
        Map<?, ?> queue() { return (Map<?, ?>) ReflectionTestUtils.getField(group, "pending"); }
        TransactionTemplate read() {
            TransactionTemplate read = new TransactionTemplate(manager); read.setReadOnly(true);
            read.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ); return read;
        }
        public void close() { market.stop(); }
    }

    @Test void selectedMinutesQueueTheMissingConversionAndDeduplicateRepeatedLoadingRequests() {
        try (Fixture f = new Fixture()) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                BusinessException error = assertThrows(BusinessException.class,
                    () -> f.transactional.selectedCandles(f.symbol, open, close));
                assertEquals(425, error.getCode()); assertTrue(error.getMessage().contains("JPYUSD=X"));
                assertEquals(1, f.queue().size(), "Loading must commit the notification and retain one deduplicated task");
                System.out.println("MANUAL_LOADING selected attempt=" + attempt + " code=" + error.getCode() + " queued=" + f.queue().size());
            }
            verify(f.market, times(3)).getKline("JPYUSD=X", "1m", 720, "Forex", windowEnd);
        }
    }

    @Test void automaticSearchQueuesMissingConversionWorkOn425() {
        try (Fixture f = new Fixture()) {
            assertEquals(425, assertThrows(BusinessException.class,
                () -> f.transactional.simpleCandles(f.symbol, open, close + 60000)).getCode());
            assertEquals(1, f.queue().size());
            System.out.println("MANUAL_LOADING automatic code=425 queued=" + f.queue().size());
        }
    }

    @Test void directCallsAlsoCommitTheReadBeforeReportingMissingConversion() {
        try (Fixture f = new Fixture()) {
            assertEquals(425, assertThrows(BusinessException.class,
                () -> f.direct.selectedCandles(f.symbol, open, close)).getCode());
            assertEquals(1, f.queue().size());
            System.out.println("MANUAL_LOADING without-transaction code=425 queued=" + f.queue().size());
        }
    }

    @Test void catchingLoadingInsideTheReadTransactionAllowsTheQueueNotificationToCommit() {
        try (Fixture f = new Fixture()) {
            Integer code = f.read().execute(status -> {
                try { f.direct.selectedCandles(f.symbol, open, close); return 200; }
                catch (BusinessException loading) { return loading.getCode(); }
            });
            assertEquals(Integer.valueOf(425), code); assertEquals(1, f.queue().size());
            System.out.println("MANUAL_LOADING caught-before-commit code=" + code + " queued=" + f.queue().size());
        }
    }

    @Test void chartPrewarmingCommitsAndAlreadyQueuedWorkSurvivesAGenerationFailure() {
        try (Fixture f = new Fixture()) {
            Map<String, Object> chart = f.transactional.chart(f.symbol, "UTC", windowEnd, 200);
            assertEquals("available", chart.get("status")); assertEquals(false, chart.get("pending"));
            assertEquals(1, f.queue().size());
            assertEquals(425, assertThrows(BusinessException.class,
                () -> f.transactional.selectedCandles(f.symbol, open, close)).getCode());
            assertEquals(1, f.queue().size(), "A task queued by a successful chart read is not lost");
            System.out.println("MANUAL_LOADING chart=available conversion=missing generation=425 queued=" + f.queue().size());
        }
    }

    @Test void missingPrimaryHistoryQueuesRepairWorkWithTheRealDatabaseReader() {
        try (SourceHistoryGapRepairTest.Fixture f = new SourceHistoryGapRepairTest.Fixture()) {
            f.config.setQuoteCurrency("USD"); f.config.setBaseCurrency("BTC");
            ProxyFactory proxy = new ProxyFactory(new ManualOrderPrices(f.market)); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(f.manager, new AnnotationTransactionAttributeSource()));
            ManualOrderPrices prices = (ManualOrderPrices) proxy.getProxy();
            BusinessException error = assertThrows(BusinessException.class,
                () -> prices.selectedCandles(f.config, open, close));
            assertEquals(425, error.getCode()); assertTrue(error.getMessage().contains("BTCUSDT"));
            assertEquals(2, f.queue().size(), "Both selected exact-minute repairs must be admitted after the read commits");
            assertTrue(f.raw().isEmpty()); verifyNoInteractions(f.source);
            System.out.println("MANUAL_LOADING real-H2-primary code=425 queued=" + f.queue().size() + " provider-reads=0");
        }
    }

    @Test void completeConversionCacheAllowsTheSameSelectedMinutesToSucceed() {
        try (Fixture f = new Fixture()) {
            Map<String, Map<String, Object>> cache = (Map<String, Map<String, Object>>) ReflectionTestUtils.getField(f.group, "klines");
            Map<String, Object> received = new HashMap<>(f.response("0.0064"));
            received.put("fetchedAt", System.currentTimeMillis());
            cache.put("JPYUSD=X:1m:720:" + windowEnd, received);
            assertEquals(2, f.transactional.selectedCandles(f.symbol, open, close).size());
            assertTrue(f.queue().isEmpty());
            System.out.println("MANUAL_LOADING complete-conversion candles=2 queued=0");
        }
    }

    @Test void authoritativeRangeQuoteAlsoCommitsItsLoadingNotification() {
        try (Fixture f = new Fixture()) {
            BusinessException error = assertThrows(BusinessException.class, () -> f.transactional.rangeQuote(
                f.symbol, open, close, new java.math.BigDecimal("158.13"), new java.math.BigDecimal("158.13")));
            assertEquals(425, error.getCode()); assertEquals(1, f.queue().size());
            System.out.println("MANUAL_LOADING range-quote code=425 queued=" + f.queue().size());
        }
    }

    @Test void invalidCandleIsRejectedAfterTheReadCompletes() {
        try (Fixture f = new Fixture()) {
            Map<String, Object> invalid = f.response("158.13");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<?, ?>) invalid.get("data")).get("kline_list");
            for (Map<String, Object> row : rows) row.put("low_price", "159");
            doReturn(invalid).when(f.market).historicalKline(anyString(), anyString(), anyInt(), anyLong());
            BusinessException error = assertThrows(BusinessException.class,
                () -> f.transactional.selectedCandles(f.symbol, open, close));
            assertEquals(400, error.getCode()); assertTrue(error.getMessage().contains("分钟OHLC行情无效"));
            assertEquals(1, f.queue().size());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            System.out.println("MANUAL_LOADING invalid-candle code=400 queued=1 read-completed=true");
        }
    }

    @Test void unexpectedRuntimeFailureStillRollsBackQueuedNotifications() {
        try (Fixture f = new Fixture()) {
            doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("fixture failure after notification"); })
                .when(f.market).getKline(anyString(), anyString(), anyInt(), anyString(), anyLong());
            assertThrows(IllegalStateException.class, () -> f.transactional.selectedCandles(f.symbol, open, close));
            assertTrue(f.queue().isEmpty());
            System.out.println("MANUAL_LOADING unexpected-failure queued=0");
        }
    }

    private void occupyQueue(Fixture f) {
        try {
            java.lang.reflect.Constructor<?> constructor = Class.forName(ForexQuoteMarketService.class.getName() + "$KlineRequest")
                .getDeclaredConstructor(String.class, String.class, int.class, Long.class);
            constructor.setAccessible(true);
            Map<String, Object> queue = (Map<String, Object>) f.queue();
            for (int i = 0; i < 32; i++) queue.put("occupied-" + i,
                constructor.newInstance("JPYUSD=X", "1m", 720, windowEnd - (i + 1L) * WINDOW));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    @Test void queueFilledBeforeCommitReportsBusyInsteadOfPretendingToLoad() {
        try (Fixture f = new Fixture()) {
            java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                if (reads.incrementAndGet() == 1) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { occupyQueue(f); }
                });
                return f.response("158.13");
            }).when(f.market).historicalKline(anyString(), anyString(), anyInt(), anyLong());
            BusinessException busy = assertThrows(BusinessException.class,
                () -> f.direct.selectedCandles(f.symbol, open, close));
            assertEquals(400, busy.getCode()); assertTrue(busy.getMessage().contains("补齐队列已满"));
            assertEquals(32, f.queue().size()); assertFalse(f.queue().containsKey("JPYUSD=X:1m:720:" + windowEnd));
            System.out.println("MANUAL_LOADING queue-full code=400 conversion-admitted=false");
        }
    }

    @Test void automaticSearchAlsoReportsBusyAfterQueueAdmissionFails() {
        try (Fixture f = new Fixture()) {
            doAnswer(call -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { occupyQueue(f); }
                });
                return f.response("158.13");
            }).when(f.market).historicalKline(anyString(), anyString(), anyInt(), anyLong());
            BusinessException busy = assertThrows(BusinessException.class,
                () -> f.direct.simpleCandles(f.symbol, open, close + 60000));
            assertEquals(400, busy.getCode()); assertTrue(busy.getMessage().contains("补齐队列已满"));
            assertEquals(32, f.queue().size());
        }
    }

    @Test void queueAlreadyFullReportsBusyForBothSelectedAndAutomaticReads() {
        try (Fixture f = new Fixture()) {
            occupyQueue(f);
            BusinessException selected = assertThrows(BusinessException.class,
                () -> f.direct.selectedCandles(f.symbol, open, close));
            assertEquals(400, selected.getCode()); assertTrue(selected.getMessage().contains("补齐队列已满"));
            BusinessException automatic = assertThrows(BusinessException.class,
                () -> f.direct.simpleCandles(f.symbol, open, close + 60000));
            assertEquals(400, automatic.getCode()); assertTrue(automatic.getMessage().contains("补齐队列已满"));
            assertEquals(32, f.queue().size());
        }
    }

    @Test void callerWriteTransactionStillRollsBackWhenGenerationIsNotReady() {
        try (Fixture f = new Fixture()) {
            JdbcTemplate db = new JdbcTemplate(f.manager.getDataSource());
            db.execute("CREATE TABLE funds_probe(id INT PRIMARY KEY)");
            BusinessException loading = assertThrows(BusinessException.class, () -> new TransactionTemplate(f.manager).execute(status -> {
                db.update("INSERT INTO funds_probe VALUES(1)");
                return f.direct.selectedCandles(f.symbol, open, close);
            }));
            assertEquals(425, loading.getCode()); assertEquals(1, f.queue().size());
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM funds_probe", Integer.class));
            System.out.println("MANUAL_LOADING caller-write-rolled-back=true queued=1");
        }
    }

    @Test void directReadsKeepOneDatabaseSnapshotAcrossConcurrentHistoryUpdates() throws Exception {
        try (SourceHistoryGapRepairTest.Fixture f = new SourceHistoryGapRepairTest.Fixture()) {
            f.config.setQuoteCurrency("USD"); f.config.setBaseCurrency("BTC");
            f.store.sourceCandles(1, "1m", Arrays.asList(SourceHistoryGapRepairTest.bar(open), SourceHistoryGapRepairTest.bar(close)), System.currentTimeMillis());
            ForexQuoteMarketService market = spy(f.market);
            java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();
            java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> {
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                Object result = call.callRealMethod();
                if (reads.incrementAndGet() == 1) writer.submit(() -> {
                    try (com.gtcfesk.exchange.tenant.TenantContext.Scope ignored = com.gtcfesk.exchange.tenant.TenantContext.open(1L)) {
                        f.store.transaction(() -> {
                            for (long time : new long[] {open, close}) {
                                Map<String, Object> row = new LinkedHashMap<>(SourceHistoryGapRepairTest.bar(time));
                                for (String field : Arrays.asList("open_price", "low_price", "high_price", "close_price")) row.put(field, 190);
                                f.db.update("UPDATE market_source_candle SET body=? WHERE tenant_id=1 AND symbol_id=1 AND period='1m' AND candle_at=?", f.store.encode(row), time);
                            }
                            return null;
                        });
                    }
                }).get(5, java.util.concurrent.TimeUnit.SECONDS);
                return result;
            }).when(market).historicalKline(anyString(), anyString(), anyInt(), anyLong());
            try {
                NavigableMap<Long, com.gtcfesk.exchange.trade.ManualOrderGenerator.Candle> result = new ManualOrderPrices(market).selectedCandles(f.config, open, close);
                assertEquals(0, result.get(open).price.compareTo(new java.math.BigDecimal("90")));
                assertEquals(0, result.get(close).price.compareTo(new java.math.BigDecimal("90")));
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                for (Map<String, Object> row : f.raw()) assertEquals(0,
                    new java.math.BigDecimal(f.store.decode(row.get("body").toString()).get("open_price").toString()).compareTo(new java.math.BigDecimal("190")));
                System.out.println("MANUAL_LOADING snapshot open=90 close=90 concurrent-commit=190");
            } finally { writer.shutdownNow(); assertTrue(writer.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)); market.stop(); }
        }
    }
}
