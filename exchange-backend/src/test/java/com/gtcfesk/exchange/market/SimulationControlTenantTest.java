package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import static org.junit.jupiter.api.Assertions.*;

class SimulationControlTenantTest {
    private TradingSymbol symbol(long tenant) {
        TradingSymbol s = new TradingSymbol(); s.setTenantId(tenant);
        s.setRandomMarketControls("[{\"at\":1,\"mode\":\"target\",\"symbol\":\"SAME\",\"start\":100,\"target\":110}]");
        return s;
    }

    @Test void sameJsonDoesNotShareEventsListsOrMutablePlansAcrossTenants() {
        SimulationControlPath.Event a;
        TradingSymbol ownedA;
        try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            ownedA = symbol(1);
            List<SimulationControlPath.Event> events = SimulationControlPath.events(ownedA);
            a = events.get(0);
            assertThrows(UnsupportedOperationException.class, events::clear);
            a.target = BigDecimal.valueOf(999);
            TradingSymbol plan = a.plan(); plan.setControlTargetPrice(BigDecimal.ZERO);
            assertEquals(BigDecimal.valueOf(999), a.plan().getControlTargetPrice());
            assertEquals(BigDecimal.valueOf(110), SimulationControlPath.events(symbol(1)).get(0).target);
            assertEquals(1L, plan.getTenantId());
        }
        try (TenantContext.Scope ignored = TenantContext.open(2L)) {
            SimulationControlPath.Event b = SimulationControlPath.events(symbol(2)).get(0);
            assertNotSame(a, b);
            assertEquals(BigDecimal.valueOf(110), b.target);
            assertEquals(2L, b.plan().getTenantId());
            assertThrows(AccessDeniedException.class, a::plan);
            assertThrows(AccessDeniedException.class, () -> SimulationControlPath.events(ownedA));
        }
        assertThrows(AccessDeniedException.class, () -> SimulationControlPath.events(ownedA));
    }

    @Test void emptyHistoryStillRequiresVerifiedOwner() {
        TradingSymbol s;
        try (TenantContext.Scope ignored = TenantContext.open(1L)) { s = symbol(1); s.setRandomMarketControls(null); }
        assertThrows(AccessDeniedException.class, () -> SimulationControlPath.events(s));
        try (TenantContext.Scope ignored = TenantContext.open(2L)) {
            assertThrows(AccessDeniedException.class, () -> SimulationControlPath.events(s));
        }
    }
}
