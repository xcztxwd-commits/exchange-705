package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.InvocationInterceptor.Invocation;
import org.springframework.security.access.AccessDeniedException;
import static com.gtcfesk.exchange.trade.FeeCalculationAuditTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CalculationTenantBoundaryTest {
    private Fixture fixture() { return new Fixture("EURUSD=X", "EUR", "USD", "1.1", "1"); }
    private void untouched(Fixture f) {
        same(f.initial, f.account.getAvailable());
        same(java.math.BigDecimal.ZERO, f.account.getFrozen());
        verify(f.accounts, never()).save(any());
        verify(f.orders, never()).save(any());
    }

    @Test void missingContextFailsWithoutWrites() {
        assertNull(TenantContext.currentTenantId());
        Fixture f = fixture();
        assertThrows(AccessDeniedException.class, () -> f.open("1", "100", "BUY"));
        untouched(f);
    }

    @Test void foreignTenantCannotUseTenantOneMocks() {
        Fixture f = fixture();
        try (TenantContext.Scope ignored = TenantContext.open(2L)) {
            assertThrows(BusinessException.class, () -> f.open("1", "100", "BUY"));
            verify(f.symbols).findByTenantIdAndSymbol(2L, "EURUSD=X");
            untouched(f);
        }
        assertNull(TenantContext.currentTenantId());
    }

    @Test void disabledPolicyStopsBeforeRepositoriesAndFunds() {
        Fixture f = fixture();
        doThrow(new AccessDeniedException("contract disabled")).when(f.tenantPolicy).requireNewBusiness("contract");
        try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            assertThrows(AccessDeniedException.class, () -> f.open("1", "100", "BUY"));
            verifyNoInteractions(f.symbols, f.accounts, f.orders);
            untouched(f);
        }
    }

    @Test void ordinaryAndDynamicInvocationsRestoreContextEvenAfterFailure() throws Throwable {
        CalculationTenantExtension extension = new CalculationTenantExtension();
        for (boolean dynamic : new boolean[]{false, true}) {
            for (boolean fail : new boolean[]{false, true}) {
                Invocation<Void> invocation = () -> {
                    assertEquals(1L, TenantContext.requireTenantId());
                    if (fail) throw new IllegalStateException("synthetic failure");
                    return null;
                };
                org.junit.jupiter.api.function.Executable execute = () -> {
                    if (dynamic) extension.interceptDynamicTest(invocation, null, null);
                    else extension.interceptTestMethod(invocation, null, null);
                };
                if (fail) assertThrows(IllegalStateException.class, execute);
                else execute.execute();
                assertNull(TenantContext.currentTenantId());
                try (TenantContext.Scope ignored = TenantContext.open(1L)) {
                    if (fail) assertThrows(IllegalStateException.class, execute);
                    else execute.execute();
                    assertEquals(1L, TenantContext.requireTenantId());
                }
                assertNull(TenantContext.currentTenantId());
            }
        }
    }
}
