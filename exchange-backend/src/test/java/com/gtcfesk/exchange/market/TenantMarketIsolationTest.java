package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.control.Tenant;
import com.gtcfesk.exchange.control.TenantRepository;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit checks, not a substitute for the isolated MySQL migration rehearsal. */
class TenantMarketIsolationTest {
    @Test void sameSymbolHasSeparateRegistryAndPublishedState() {
        ForexQuoteMarketService market = new ForexQuoteMarketService();
        try {
            Object a, b;
            try (TenantContext.Scope ignored = TenantContext.open(2L)) {
                a = ReflectionTestUtils.invokeMethod(market, "state");
                TradingSymbol symbol = new TradingSymbol(); symbol.setId(11L); symbol.setSymbol("SAME");
                ReflectionTestUtils.setField(a, "registry", Collections.singletonMap("SAME", symbol));
                assertTrue(market.knownSymbol("SAME"));
            }
            try (TenantContext.Scope ignored = TenantContext.open(3L)) {
                b = ReflectionTestUtils.invokeMethod(market, "state");
                assertFalse(market.knownSymbol("SAME"));
            }
            assertNotSame(a, b);
            for (String field : Arrays.asList("groups", "published", "publishedAt", "epoch"))
                assertNotSame(ReflectionTestUtils.getField(a, field), ReflectionTestUtils.getField(b, field));
            assertThrows(RuntimeException.class, () -> market.knownSymbol("SAME"));
            assertThrows(RuntimeException.class, market::refreshSymbols);
            assertThrows(RuntimeException.class, () -> market.getBatchPrices(Collections.emptyList()));
            assertThrows(RuntimeException.class, () -> market.contractConversion("UNKNOWN", "Yahoo"));
            assertThrows(RuntimeException.class, () -> market.conversion("USD", "Yahoo"));
        } finally { market.stop(); }
    }

    @Test void deferredRedisWritesCaptureTenantBeforeThreadExit() {
        RedisMarketService redis = new RedisMarketService();
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String,String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        ReflectionTestUtils.setField(redis, "redisTemplate", template);
        Map<String,Object> quote = Map.of("price", 12.0, "timestamp", System.currentTimeMillis());
        try {
            try (TenantContext.Scope ignored = TenantContext.open(2L)) { redis.savePrice("SAME", quote); }
            try (TenantContext.Scope ignored = TenantContext.open(3L)) { redis.savePrice("SAME", quote); }
            redis.flushPrices(); // No request context on the deferred writer.
            verify(values).set(eq("tenant:2:market:price:SAME"), anyString());
            verify(values).set(eq("tenant:3:market:price:SAME"), anyString());
            assertThrows(RuntimeException.class, () -> redis.savePrice("SAME", quote));
            assertThrows(RuntimeException.class, () -> redis.getBatchPrices(Collections.emptyList()));
        } finally { redis.stopPriceWriter(); }
    }

    @Test void websocketPayloadAndRevocationStayBoundToHandshakeTenant() throws Exception {
        MarketWebSocketHandler handler = new MarketWebSocketHandler();
        ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
        TenantRepository tenants = mock(TenantRepository.class);
        ReflectionTestUtils.setField(handler, "marketService", market);
        ReflectionTestUtils.setField(handler, "tenants", tenants);
        Tenant a = tenant(2L, "a.test"), b = tenant(3L, "b.test");
        when(tenants.findById(2L)).thenReturn(Optional.of(a));
        when(tenants.findById(3L)).thenReturn(Optional.of(b));
        when(market.knownSymbol("SAME")).thenAnswer(call -> TenantContext.requireTenantId() > 0);
        when(market.snapshotPrice("SAME")).thenAnswer(call -> Map.of("price", TenantContext.requireTenantId(), "quoteVersion", 1L));
        WebSocketSession sa = session(2L, "a.test"), sb = session(3L, "b.test");
        List<String> outA = new CopyOnWriteArrayList<>(), outB = new CopyOnWriteArrayList<>();
        doAnswer(call -> { outA.add(((TextMessage)call.getArgument(0)).getPayload()); return null; }).when(sa).sendMessage(any());
        doAnswer(call -> { outB.add(((TextMessage)call.getArgument(0)).getPayload()); return null; }).when(sb).sendMessage(any());
        try {
            handler.afterConnectionEstablished(sa); handler.afterConnectionEstablished(sb);
            handler.handleTextMessage(sa, new TextMessage("{\"action\":\"subscribe\",\"symbols\":[\"SAME\"]}"));
            handler.handleTextMessage(sb, new TextMessage("{\"action\":\"subscribe\",\"symbols\":[\"SAME\"]}"));
            ((ScheduledExecutorService)ReflectionTestUtils.getField(handler, "scheduler")).submit(() -> {}).get(3, TimeUnit.SECONDS);
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline && (outA.stream().noneMatch(s -> s.contains("\"price\":2")) || outB.stream().noneMatch(s -> s.contains("\"price\":3")))) {
                handler.push(); Thread.sleep(20);
            }
            assertTrue(outA.stream().anyMatch(s -> s.contains("\"price\":2")));
            assertTrue(outB.stream().anyMatch(s -> s.contains("\"price\":3")));
            assertFalse(outA.stream().anyMatch(s -> s.contains("\"price\":3")));
            assertFalse(outB.stream().anyMatch(s -> s.contains("\"price\":2")));
            a.setFrontendHost("rebound.test"); handler.push(); verify(sa).close(CloseStatus.POLICY_VIOLATION);
            WebSocketSession missing = mock(WebSocketSession.class); when(missing.getAttributes()).thenReturn(Collections.emptyMap());
            handler.afterConnectionEstablished(missing); verify(missing).close(CloseStatus.POLICY_VIOLATION);
        } finally { handler.destroy(); }
    }
    private static Tenant tenant(Long id, String host) {
        Tenant t = new Tenant(); t.setId(id); t.setFrontendHost(host); t.setDomainVerified(true); t.setStatus("ACTIVE"); return t;
    }
    private static WebSocketSession session(Long id, String host) {
        WebSocketSession session = mock(WebSocketSession.class); when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of("tenantId", id, "frontendHost", host)); return session;
    }
}
