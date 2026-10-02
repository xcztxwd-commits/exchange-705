package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;

/** Legacy single-tenant unit fixtures now enter an explicit scope, not a production default. */
abstract class TenantMarketTestContext {
    private TenantContext.Scope scope;
    @BeforeEach final void openTenantScope() { scope = TenantContext.open(1L); }
    @AfterEach final void closeTenantScope() { scope.close(); }
    static Object marketState(Object market) { return ReflectionTestUtils.invokeMethod(market, "state"); }
}
