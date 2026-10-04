package com.gtcfesk.exchange.activity;

import com.gtcfesk.exchange.entity.OptionOrder;
import com.gtcfesk.exchange.tenant.TenantJobRunner;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig({ActivityIntegrationTest.Config.class, OrderPromotionRecoveryTest.Config.class})
class OrderPromotionRecoveryTest extends ActivityFeatureFixture {
    @Configuration @Import(OrderPromotionEvents.class)
    static class Config {
        @Bean TenantJobRunner eventTenants() { return mock(TenantJobRunner.class); }
    }
    @Autowired OrderPromotionEvents events;
    @SpyBean ActivityService dispatch;
    private ActivityCampaign automatic;

    @BeforeEach void eventCampaign() {
        automatic = campaign(); automatic.setAutoSendEnabled(true);
        automatic.setTriggerConditions(Collections.singletonList("API_OPTION_ORDER"));
        automatic.setPositions(Collections.singletonList("AUTH_TRADE"));
        automatic.setAllowRepeatSend(true);
        service.saveAutoSend(automatic.getId(), automatic); approved(user);
    }
    @AfterEach void resetDispatcher() { reset(dispatch); }

    @Test void eventAndOrderRollbackTogetherThenCommitSurvivesProcessStyleRecoveryAndReplay() {
        long before = optionOrders.countByTenantId(1L);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            options.createOrder(user, option("10", "OPTION"));
            throw new IllegalStateException("transaction failed before commit");
        }));
        assertEquals(before, optionOrders.countByTenantId(1L)); money("100", cash(user, "OPTION"));
        events.drain(); assertEquals(0L, deliveries.countByTenantIdAndCampaignId(1L, automatic.getId()));
        OptionOrder order = options.createOrder(user, option("10", "OPTION"));
        assertTrue(optionOrders.findByTenantIdAndId(1L, order.getId()).orElseThrow(AssertionError::new).isPromotionPending());
        assertEquals(0L, deliveries.countByTenantIdAndCampaignId(1L, automatic.getId()));
        events.drain(); events.drain();
        assertFalse(optionOrders.findByTenantIdAndId(1L, order.getId()).orElseThrow(AssertionError::new).isPromotionPending());
        assertEquals(1L, deliveries.countByTenantIdAndCampaignId(1L, automatic.getId()));
        verify(dispatch, times(1)).trigger(user, "API_OPTION_ORDER", "AUTH_TRADE");
    }

    @Test void failureAfterDeliveryKeepsEventPendingAndRollsBackDelivery() {
        OptionOrder order = options.createOrder(user, option("10", "OPTION"));
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("after delivery checkpoint"); })
                .when(dispatch).trigger(eq(user), eq("API_OPTION_ORDER"), eq("AUTH_TRADE"));
        events.drain();
        assertTrue(optionOrders.findByTenantIdAndId(1L, order.getId()).orElseThrow(AssertionError::new).isPromotionPending());
        assertEquals(0L, deliveries.countByTenantIdAndCampaignId(1L, automatic.getId()));
        reset(dispatch); events.drain(); events.drain();
        assertEquals(1L, deliveries.countByTenantIdAndCampaignId(1L, automatic.getId()));
    }

    @Test void parallelRecoveryCannotRepeatEvenAnAllowRepeatCampaign() throws Exception {
        options.createOrder(user, option("10", "OPTION"));
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        Callable<Void> work = scoped(() -> { start.await(); events.drain(); return null; });
        try { Future<Void> first = pool.submit(work), second = pool.submit(work); start.countDown();
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(1L, deliveries.countByTenantIdAndCampaignId(1L, automatic.getId()));
        verify(dispatch, times(1)).trigger(user, "API_OPTION_ORDER", "AUTH_TRADE");
    }

    @Test void dispatchRefusesToBorrowInsideExistingMoneyTransaction() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> events.drain()));
    }

    @Test void moreThanOnePageOfPendingOrdersDrainsWithoutStarvation() {
        automatic.setAutoSendEnabled(false);
        service.saveAutoSend(automatic.getId(), automatic);
        OptionOrder last = null;
        for (int i = 0; i < 101; i++) {
            OptionOrder order = new OptionOrder();
            order.setUserId(user);
            order.setSymbol("BTCUSDT");
            order.setDirection("UP");
            order.setAmount(BigDecimal.ONE);
            order.setStatus("TRADING");
            order.setPromotionPending(true);
            last = optionOrders.saveAndFlush(order);
        }
        events.drain();
        assertFalse(optionOrders.findByTenantIdAndId(1L, last.getId()).orElseThrow(AssertionError::new).isPromotionPending());
    }
}
