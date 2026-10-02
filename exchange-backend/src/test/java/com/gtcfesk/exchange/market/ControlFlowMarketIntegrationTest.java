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
}
