package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;

class MarketPushTest {
    @Test void subscribersShareOneCalculationPerPush() throws Exception {
        MarketWebSocketHandler handler = new MarketWebSocketHandler();
        ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
        ReflectionTestUtils.setField(handler, "marketService", market);
        com.gtcfesk.exchange.control.Tenant tenant = new com.gtcfesk.exchange.control.Tenant();
        tenant.setId(2L); tenant.setFrontendHost("tenant-a.test"); tenant.setDomainVerified(true); tenant.setStatus("ACTIVE");
        com.gtcfesk.exchange.control.TenantRepository tenants = mock(com.gtcfesk.exchange.control.TenantRepository.class);
        when(tenants.findById(2L)).thenReturn(Optional.of(tenant));
        ReflectionTestUtils.setField(handler, "tenants", tenants);
        when(market.knownSymbol("EURUSD")).thenReturn(true);
        Map<String,Object> quote = new HashMap<>(); quote.put("price", 1.1); quote.put("quoteVersion", 1L);
        when(market.snapshotPrice("EURUSD")).thenReturn(quote);
        try {
            for (int i = 0; i < 25; i++) {
                WebSocketSession session = mock(WebSocketSession.class); when(session.isOpen()).thenReturn(true);
                when(session.getAttributes()).thenReturn(Map.of("tenantId", 2L, "frontendHost", "tenant-a.test"));
                handler.afterConnectionEstablished(session);
                handler.handleTextMessage(session, new TextMessage("{\"action\":\"subscribe\",\"symbols\":[\"EURUSD\"],\"fastSymbols\":[\"EURUSD\"]}"));
            }
            // Wait for the initial subscription tasks; periodic scheduling is not started in this test.
            ((ScheduledExecutorService) ReflectionTestUtils.getField(handler, "scheduler")).submit(() -> {}).get(5, TimeUnit.SECONDS);
            clearInvocations(market);
            handler.push();
            verify(market, times(1)).snapshotPrice("EURUSD");
        } finally { handler.destroy(); }
    }
}
