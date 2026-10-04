package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gtcfesk.exchange.market.MarketSqlFixture.inTenant;

/** Real market service + durable H2 flows; only repository/provider/Redis are isolated fixtures. */
class ControlFlowMarketIntegrationTest extends TenantMarketTestContext {
    private static RecoveryOptions enabledRecovery() {
        RecoveryOptions options = new RecoveryOptions(); options.setAutoRestore(true); return options;
    }

    final PriceControlTest fixture = new PriceControlTest();
    final ForexQuoteMarketService market = fixture.market;
    final java.util.concurrent.atomic.AtomicReference<com.gtcfesk.exchange.entity.TradingSymbol> saved = fixture.saved;
    ControlRecoveryFlowTest database;
    @AfterEach void stop() { fixture.stop(); }
    @BeforeEach void attachDurableStore() {
        fixture.setup();
        database = new ControlRecoveryFlowTest(); database.setup();
        saved.get().setIsEnabled(true); saved.get().setPricePrecision(2);
        ReflectionTestUtils.setField(market, "controls", database.controls);
        ReflectionTestUtils.setField(market, "controlHistory", database.store);
        ReflectionTestUtils.setField(market, "klineMerger", database.merger);
        ReflectionTestUtils.setField(market, "virtualTrading", true);
        ReflectionTestUtils.setField(market, "v3Enabled", false); // Existing one-second V2 recovery fixture.
        market.refreshSymbols();
    }
    @Test void backendTimerRestoresRandomBaseWithoutChangingSwitch() throws Exception {
        market.randomMarket(1L, true, null);
        Long session = saved.get().getRandomMarketStartedAt();
        RecoveryOptions options = enabledRecovery(); options.setRestoreMode("QUICK");
        market.startControl(1L, 1, new BigDecimal("120"), 1, false, "random-flow", options);
        Thread.sleep(1100);
        market.completeControls();
        Map<String,Object> quote = market.internalPrice("TEST");
        assertEquals("SOURCE", quote.get("controlState"));
        assertEquals(true, saved.get().getRandomMarketEnabled()); assertEquals(session, saved.get().getRandomMarketStartedAt());
        assertEquals(0, RandomMarketPath.basePrice(saved.get(), System.currentTimeMillis()/1000*1000).compareTo(ControlHistoryStore.number(quote.get("price"))));
        assertEquals(1, database.count("market_control_publication"));
    }
    @Test void manualOneClickCancelsPendingAutomaticRecovery() throws Exception {
        market.startControl(1L, 1, new BigDecimal("120"), 1, false, "cancel-flow", enabledRecovery());
        market.manualControl(1L, false, BigDecimal.ZERO);
        Thread.sleep(1100); market.completeControls();
        assertEquals("SOURCE", market.internalPrice("TEST").get("controlState"));
        assertEquals(1, database.count("market_control_task"));
        assertEquals(0, database.store.db.queryForObject("SELECT COUNT(*) FROM market_control_flow WHERE recovery_started_at IS NOT NULL", Integer.class));
    }
    @Test void virtualV3QuoteExecutionAndHistoryUseCommittedPlan() {
        ReflectionTestUtils.setField(market, "v3Enabled", true);
        saved.get().setPricePrecision(8);
        market.refreshSymbols();
        market.randomMarket(1L, true, null);
        BigDecimal start = RandomMarketPath.basePrice(saved.get(), System.currentTimeMillis());
        BigDecimal target = start.add(new BigDecimal("0.05400000"));
        RecoveryOptions options = new RecoveryOptions(); options.setAutoRestore(false);
        market.startControl(1L, 60, target, 10, true, "virtual-v3", options);
        PersistentPriceControl.Task task = database.controls.latest(1);
        assertEquals(3, task.algorithmVersion);
        assertEquals(1, database.count("market_control_plan"));
        assertEquals(task.id, SimulationControlPath.events(saved.get()).get(SimulationControlPath.events(saved.get()).size() - 1).planId);
        Map<String, Object> quote = market.internalPrice("TEST");
        assertEquals(0, task.price(task.sampledUntil).compareTo(ControlHistoryStore.number(quote.get("price"))));
        assertEquals(0, task.price(task.sampledUntil).compareTo(market.freshPrice("TEST")));
        List<Map<String, Object>> candles = ControlHistoryStore.rows(market.internalKline("TEST", "1m", 2));
        assertFalse(candles.isEmpty());
        assertEquals(0, task.price(task.sampledUntil).compareTo(ControlHistoryStore.number(candles.get(candles.size() - 1).get("close_price"))));
    }

    void samePrice(String expected, Object actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(ControlHistoryStore.number(actual)));
    }
    void closePrice(String expected) {
        List<Map<String,Object>> candles = ControlHistoryStore.rows(market.internalKline("TEST", "1m", 2));
        assertFalse(candles.isEmpty());
        samePrice(expected, candles.get(candles.size() - 1).get("close_price"));
    }
    @Test void manualOffsetAfterStoppedTargetKeepsStatusExecutionAndReloadConsistent() {
        market.startControl(1L, 60, new BigDecimal("120"), 1, false, "manual-after-target", new RecoveryOptions());
        market.stopControl(1L);
        Map<String,Object> status = market.manualControl(1L, true, new BigDecimal("5"));
        assertEquals(true, status.get("enabled")); assertEquals(false, status.get("running"));
        samePrice("95", status.get("currentPrice")); samePrice("95", market.freshPrice("TEST"));
        closePrice("95");
        market.refreshSymbols();
        ReflectionTestUtils.setField(market, "controls", new PersistentPriceControl(database.store));
        assertEquals(true, market.controlStatus(1L).get("enabled")); samePrice("95", market.freshPrice("TEST"));
        fixture.raw(92);
        samePrice("97", market.freshPrice("TEST"));
        status = market.manualControl(1L, true, new BigDecimal("-2"));
        samePrice("90", status.get("currentPrice")); samePrice("90", market.freshPrice("TEST")); closePrice("90");
        status = market.manualControl(1L, false, BigDecimal.ZERO);
        assertEquals(false, status.get("enabled")); samePrice("92", market.freshPrice("TEST")); closePrice("92");
    }
    @Test void manualPricesSurviveUnpublishedTargetAndLaterPublicationWithoutRewritingSource() throws Exception {
        RecoveryOptions options = new RecoveryOptions(); options.setAutoReplaceHistory(false);
        market.startControl(1L, 1, new BigDecimal("120"), 1, false, "unpublished-before-manual", options);
        Thread.sleep(1100); market.completeControls();
        String task = database.controls.latest(1L).id;
        market.manualControl(1L, true, new BigDecimal("5"));
        Thread.sleep(5); fixture.raw(91);
        long now = System.currentTimeMillis(), minute = now / 60000 * 60000;
        Map<String,Object> raw = market.getPrice("SOURCE", "Metal"); raw.put("eventId", "manual-source-event");
        database.controls.sourceQuote(saved.get(), raw, now);
        database.controls.sourceQuote(saved.get(), raw, now + 1); // Retrying a source event must not duplicate it.
        samePrice("96", market.freshPrice("TEST")); closePrice("96");
        List<Map<String,Object>> visible = database.store.visibleMixed(1L, minute, minute);
        samePrice("96", visible.get(0).get("close_price"));
        assertTrue(ControlHistoryStore.number(visible.get(0).get("high_price")).compareTo(new BigDecimal("120")) < 0,
            "An unpublished target must not become visible through a later manual offset");
        samePrice("91", database.store.lastQuote(1L).get("price"));
        samePrice("91", database.store.db.queryForObject("SELECT price FROM market_source_event WHERE event_id='manual-source-event'", BigDecimal.class));
        Object revision = market.internalPrice("TEST").get("controlHistoryRevision");
        database.controls.replaceHistory(1L, task);
        assertNotEquals(revision, market.internalPrice("TEST").get("controlHistoryRevision"),
            "Publishing an earlier target during manual mode must invalidate the chart cache");
        closePrice("96");
        ReflectionTestUtils.setField(market, "klineMerger", new ControlledKlineMerger(database.store));
        closePrice("96");
    }
    @Test void randomManualAfterTargetUsesTheSameQuoteAndRecordsSubsequentTicks() throws Exception {
        market.randomMarket(1L, true, null);
        market.startControl(1L, 60, new BigDecimal("120"), 1, false, "random-before-manual", new RecoveryOptions());
        market.stopControl(1L);
        Map<String,Object> status = market.manualControl(1L, true, new BigDecimal("5"));
        assertEquals(true, status.get("enabled")); assertEquals(true, status.get("randomMarketEnabled"));
        Map<String,Object> quote = market.internalPrice("TEST");
        BigDecimal expected = RandomMarketPath.basePrice(saved.get(), QuoteState.time(quote.get("timestamp"))).add(new BigDecimal("5"));
        assertEquals(0, expected.compareTo(ControlHistoryStore.number(quote.get("price"))));
        assertEquals(0, expected.compareTo(market.freshPrice("TEST")));
        closePrice(expected.toPlainString());
        long before = database.count("market_source_event");
        Thread.sleep(1100); market.completeControls();
        assertTrue(database.count("market_source_event") > before, "Manual simulation must keep recording actual generated ticks");
        quote = market.internalPrice("TEST"); closePrice(ControlHistoryStore.number(quote.get("price")).toPlainString());
        BigDecimal previous = market.freshPrice("TEST");
        status = market.manualControl(1L, false, BigDecimal.ZERO);
        assertEquals(false, status.get("enabled")); assertEquals(true, status.get("randomMarketEnabled"));
        assertEquals(0, previous.compareTo(ControlHistoryStore.number(status.get("currentPrice"))));
        assertEquals(0, previous.compareTo(market.freshPrice("TEST")));
    }
    @Test void manualOffsetCanGraduallyRestoreFromItsActualDisplayPrice() throws Exception {
        market.manualControl(1L, true, new BigDecimal("5"));
        Map<String,Object> status = market.restoreControl(1L, 1, 1, false, "restore-manual-offset");
        assertEquals(true, status.get("restoring"));
        samePrice("95", database.controls.latest(1L).startPrice);
        Thread.sleep(1100); fixture.raw(91); market.completeControls();
        samePrice("91", market.freshPrice("TEST"));
        assertEquals(false, market.controlStatus(1L).get("enabled"));
    }
}
