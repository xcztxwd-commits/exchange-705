package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real market service + durable H2 flows; only repository/provider/Redis are isolated fixtures. */
class ControlFlowMarketIntegrationTest {
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
        market.refreshSymbols();
    }
    @Test void backendTimerRestoresRandomBaseWithoutChangingSwitch() throws Exception {
        market.randomMarket(1L, true, null);
        Long session = saved.get().getRandomMarketStartedAt();
        RecoveryOptions options = new RecoveryOptions(); options.setRestoreMode("QUICK");
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
        market.startControl(1L, 1, new BigDecimal("120"), 1, false, "cancel-flow", new RecoveryOptions());
        market.manualControl(1L, false, BigDecimal.ZERO);
        Thread.sleep(1100); market.completeControls();
        assertEquals("SOURCE", market.internalPrice("TEST").get("controlState"));
        assertEquals(1, database.count("market_control_task"));
        assertEquals(0, database.store.db.queryForObject("SELECT COUNT(*) FROM market_control_flow WHERE recovery_started_at IS NOT NULL", Integer.class));
    }
}
