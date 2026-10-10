package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketPushTest {
    @Test void deltaDeliversCalendarChangesWithoutChangingTheCommittedQuoteVersion() throws Exception {
        MarketWebSocketHandler handler = new MarketWebSocketHandler();
        ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
        when(market.readSnapshot(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
        when(market.knownSymbol("EURUSD")).thenReturn(true);
        Map<String,Object> quote = new HashMap<>();
        quote.put("price", 1.1); quote.put("quoteVersion", 1L); quote.put("status", "available");
        quote.put("marketClosed", false); quote.put("marketHoursRevision", 7L);
        when(market.snapshotPrice("EURUSD")).thenAnswer(call -> new HashMap<>(quote));
        ReflectionTestUtils.setField(handler, "marketService", market);
        ReflectionTestUtils.setField(handler, "delta", true);
        com.gtcfesk.exchange.control.Tenant tenant = new com.gtcfesk.exchange.control.Tenant();
        tenant.setId(2L); tenant.setFrontendHost("tenant-a.test"); tenant.setDomainVerified(true); tenant.setStatus("ACTIVE");
        com.gtcfesk.exchange.control.TenantRepository tenants = mock(com.gtcfesk.exchange.control.TenantRepository.class);
        when(tenants.findById(2L)).thenReturn(Optional.of(tenant));
        ReflectionTestUtils.setField(handler, "tenants", tenants);
        WebSocketSession session = mock(WebSocketSession.class); when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of("tenantId", 2L, "frontendHost", "tenant-a.test"));
        BlockingQueue<com.fasterxml.jackson.databind.JsonNode> frames = new LinkedBlockingQueue<>();
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        doAnswer(call -> {
            com.fasterxml.jackson.databind.JsonNode frame = json.readTree(((TextMessage)call.getArgument(0)).getPayload());
            if ("price".equals(frame.path("type").asText())) frames.add(frame);
            return null;
        }).when(session).sendMessage(any());
        try {
            handler.afterConnectionEstablished(session);
            handler.handleTextMessage(session, new TextMessage("{\"action\":\"subscribe\",\"symbols\":[\"EURUSD\"]}"));
            ((ScheduledExecutorService)ReflectionTestUtils.getField(handler, "scheduler")).submit(() -> {}).get(5, TimeUnit.SECONDS);
            Object client = ((Map<?,?>)ReflectionTestUtils.getField(handler, "clients")).get(session);
            awaitDelivery(client);
            if (frames.isEmpty()) handler.push();
            assertNotNull(frames.poll(5, TimeUnit.SECONDS));
            for (int change = 0; change < 3; change++) {
                awaitDelivery(client);
                quote.put("status", change < 2 ? "closed" : "available");
                quote.put("marketClosed", change < 2);
                if (change == 1) quote.put("marketHoursRevision", 8L);
                ReflectionTestUtils.setField(client, "lastListPush", 0L);
                handler.push();
                com.fasterxml.jackson.databind.JsonNode frame = frames.poll(5, TimeUnit.SECONDS);
                assertNotNull(frame, "Calendar changes must reach an idle list subscriber");
                assertEquals(quote.get("status"), frame.path("data").path("EURUSD").path("status").asText());
                assertEquals(1L, frame.path("data").path("EURUSD").path("quoteVersion").asLong());
            }
            awaitDelivery(client);
            ReflectionTestUtils.setField(client, "lastListPush", 0L);
            handler.push();
            assertNull(frames.poll(100, TimeUnit.MILLISECONDS), "Unchanged snapshots remain suppressed");
        } finally { handler.destroy(); }
    }
    private void awaitDelivery(Object client) throws Exception {
        java.util.concurrent.atomic.AtomicBoolean sending = (java.util.concurrent.atomic.AtomicBoolean)ReflectionTestUtils.getField(client, "sending");
        long deadline = System.currentTimeMillis() + 5000;
        while (sending.get() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertFalse(sending.get(), "WebSocket delivery did not finish");
    }
    @Test void subscribersShareOneCalculationPerPush() throws Exception {
        MarketWebSocketHandler handler = new MarketWebSocketHandler();
        ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
        when(market.readSnapshot(any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(0)).get());
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
