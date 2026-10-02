package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.tenant.TenantContext;
import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.*;

/** Calculation-only mocks use tenant 1. Scope each execution, not the factory stream. */
class CalculationTenantExtension implements InvocationInterceptor {
    @Override public void interceptTestMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> method, ExtensionContext context) throws Throwable {
        inTenant(invocation);
    }

    @Override public void interceptDynamicTest(Invocation<Void> invocation,
            DynamicTestInvocationContext test, ExtensionContext context) throws Throwable {
        inTenant(invocation);
    }

    private static void inTenant(Invocation<Void> invocation) throws Throwable {
        try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            invocation.proceed();
        }
    }
}
