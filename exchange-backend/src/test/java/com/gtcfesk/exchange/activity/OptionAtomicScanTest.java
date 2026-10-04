package com.gtcfesk.exchange.activity;

import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.trade.OptionOrderService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real JPA money units; quote input alone is synthetic. Runs on H2 or the dedicated Activity MySQL fixture. */
@SpringJUnitConfig(ActivityIntegrationTest.Config.class)
class OptionAtomicScanTest {
    @Autowired OptionOrderService service;
    @Autowired OptionOrderRepository orders;
    @Autowired UserAccountRepository users;
    @Autowired AssetAccountRepository assets;
    @Autowired ControlAuditService audit;
    @Autowired PlatformTransactionManager manager;
    private TenantContext.Scope scope;
    private Long user;

    @BeforeEach void seed() {
        scope = TenantContext.open(1L);
        reset(audit, ActivityIntegrationTest.Config.quotes);
        when(ActivityIntegrationTest.Config.quotes.freshPrice(anyString())).thenReturn(new BigDecimal("110"));
        UserAccount owner = new UserAccount();
        owner.setEmail(UUID.randomUUID() + "@option-unit.invalid"); owner.setPasswordHash("not-a-login");
        user = users.saveAndFlush(owner).getId();
        AssetAccount account = new AssetAccount(); account.setUserId(user); account.setCoin("OPTION");
        account.setAvailable(new BigDecimal("90")); account.setFrozen(new BigDecimal("20"));
        assets.saveAndFlush(account);
    }
    @AfterEach void clean() { scope.close(); reset(audit, ActivityIntegrationTest.Config.quotes); }

    private OptionOrder order() {
        OptionOrder order = new OptionOrder(); order.setUserId(user); order.setSymbol("UNITUSD");
        order.setAmount(BigDecimal.TEN); order.setOpenPrice(new BigDecimal("100"));
        order.setDirection("UP"); order.setDuration(1); order.setOpenTime(LocalDateTime.now().minusMinutes(1));
        order.setStatus("TRADING"); order.setFundingSource("OPTION"); order.setTrialReserved(BigDecimal.ZERO);
        return orders.saveAndFlush(order);
    }
    private void money(String available, String frozen) {
        AssetAccount account = assets.findByTenantIdAndUserIdAndCoin(1L, user, "OPTION").orElseThrow(AssertionError::new);
        assertEquals(0, new BigDecimal(available).compareTo(account.getAvailable()));
        assertEquals(0, new BigDecimal(frozen).compareTo(account.getFrozen()));
    }

    @Test void successfulAuditFailureRollsBackOrderWalletAndRetryDoesNotCreditTwice() {
        OptionOrder order = order();
        doThrow(new IllegalStateException("audit checkpoint")).when(audit).record(isNull(), eq(1L), isNull(),
                eq("OPTION_SETTLE"), eq(order.getId().toString()), eq("SUCCESS"), anyString(), isNull());
        assertThrows(IllegalStateException.class, () -> service.closeOrder(user, order.getId(), null));
        assertEquals("TRADING", orders.findByTenantIdAndId(1L, order.getId()).orElseThrow(AssertionError::new).getStatus());
        money("90", "20");
        reset(audit);
        service.closeOrder(user, order.getId(), null); service.closeOrder(user, order.getId(), null);
        money("108", "10");
        verify(audit, times(1)).record(isNull(), eq(1L), isNull(), eq("OPTION_SETTLE"),
                eq(order.getId().toString()), eq("SUCCESS"), anyString(), isNull());
    }

    @Test void expiryScanCommitsSiblingEvenWhenFirstAuditRejects() {
        OptionOrder first = order(), second = order();
        doThrow(new IllegalStateException("first order audit checkpoint")).when(audit).record(isNull(), eq(1L), isNull(),
                eq("OPTION_SETTLE"), eq(first.getId().toString()), eq("SUCCESS"), anyString(), isNull());
        service.settleExpiredOrders(Collections.emptyMap());
        assertEquals("TRADING", orders.findByTenantIdAndId(1L, first.getId()).orElseThrow(AssertionError::new).getStatus());
        assertEquals("CLOSED", orders.findByTenantIdAndId(1L, second.getId()).orElseThrow(AssertionError::new).getStatus());
        money("108", "10"); reset(audit);
        service.settleExpiredOrders(Collections.emptyMap()); service.settleExpiredOrders(Collections.emptyMap());
        money("126", "0");
    }

    @Test void concurrentDuplicateCloseCommitsOnlyOneWalletCredit() throws Exception {
        OptionOrder order = order(); ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> work = () -> { try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            start.await(); service.closeOrder(user, order.getId(), null); return null;
        }};
        try { Future<Void> first = pool.submit(work), second = pool.submit(work); start.countDown();
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        money("108", "10");
    }

    @Test void closedReceiptReplayInExistingTransactionDoesNotCreditAgain() {
        OptionOrder order = order(); service.closeOrder(user, order.getId(), null);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            OptionOrder current = orders.findByTenantIdAndId(1L, order.getId()).orElseThrow(AssertionError::new);
            assertEquals("CLOSED", current.getStatus());
            service.closeOrder(user, order.getId(), null);
        });
        money("108", "10");
    }

    @Test void repeatableReadManagedOrderLoadedBeforeAnotherCommitIsRefreshedAfterUserLock() throws Exception {
        Assumptions.assumeTrue("33419".equals(System.getProperty("activity.test.mysqlPort")),
                "MySQL RR/current-read proof only; H2 rejects writes after a changed repeatable snapshot");
        OptionOrder order = order();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        TransactionTemplate reader = new TransactionTemplate(manager);
        reader.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            reader.executeWithoutResult(status -> {
                OptionOrder stale = orders.findByTenantIdAndId(1L, order.getId()).orElseThrow(AssertionError::new);
                assertEquals("TRADING", stale.getStatus());
                Future<?> committed = pool.submit(() -> {
                    try (TenantContext.Scope ignored = TenantContext.open(1L)) {
                        service.closeOrder(user, order.getId(), null);
                    }
                });
                try { committed.get(15, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError("Independent close must commit before this reader locks the user", failure); }
                assertEquals("TRADING", stale.getStatus());
                assertEquals("CLOSED", service.closeOrder(user, order.getId(), null).getStatus());
            });
        } finally { pool.shutdownNow(); }
        money("108", "10");
    }
}
