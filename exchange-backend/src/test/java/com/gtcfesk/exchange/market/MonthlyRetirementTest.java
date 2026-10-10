package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.control.Tenant;
import com.gtcfesk.exchange.control.TenantRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Retirement replaces monthly support; original failing/acceptance evidence stays in the before snapshot. */
class MonthlyRetirementTest {
    static final List<String> MONTHS = Arrays.asList("1M", " m ", "M", "mo", "1mo", "1month", "MONTH", "月", "月线", "10", "type10");

    @Test void allHttpRoutesRejectMonthsBeforeSchedulingAndKeepMinutes() {
        ForexQuoteMarketService service = mock(ForexQuoteMarketService.class);
        MarketKlineController controller = new MarketKlineController();
        ReflectionTestUtils.setField(controller, "marketService", service);
        for (String period : MONTHS) {
            assertEquals(400, controller.getKline("TEST", period, 100, "Crypto").getStatusCodeValue());
            assertEquals(400, controller.history("TEST", period, System.currentTimeMillis() - 1, 100).getStatusCodeValue());
            Map<String,Object> batch = new HashMap<>(); batch.put("symbols", Collections.singletonList("TEST")); batch.put("interval", period);
            assertEquals(400, controller.getBatchKline(batch).getStatusCodeValue());
        }
        verifyNoInteractions(service);
        assertFalse(KlineIntervals.retired("1m")); assertEquals(60000, RandomMarketPath.duration("1m"));
        assertEquals("1m", ExchangeQuoteSource.interval("1m", false));
    }

    @Test void sharedSourceCacheAndImportBoundariesRejectEvenEmptyMonthlyWrites() {
        ForexQuoteMarketService service = new ForexQuoteMarketService();
        RedisMarketService redis = new RedisMarketService();
        ControlHistoryStore store = mock(ControlHistoryStore.class, CALLS_REAL_METHODS);
        ControlledKlineMerger merger = new ControlledKlineMerger(store);
        HistoryOrdering archive = new HistoryOrdering(store);
        for (String period : MONTHS) {
            assertThrows(IllegalArgumentException.class, () -> service.internalKline("TEST", period, 2));
            assertThrows(IllegalArgumentException.class, () -> service.getKline("TEST", period, 2));
            assertThrows(IllegalArgumentException.class, () -> service.historicalKline("TEST", period, 2, 1700000000000L));
            assertThrows(IllegalArgumentException.class, () -> store.sourceCandles(Collections.emptyList(), period, Collections.emptyList(), 0));
            assertThrows(IllegalArgumentException.class, () -> merger.merge(1, period, 2, null, Collections.emptyMap(), null));
            assertThrows(IllegalArgumentException.class, () -> archive.request(1, period, 2, 1700000000000L, true, Collections.emptyMap()));
            assertThrows(IllegalArgumentException.class, () -> archive.readExact(1, period, 2, null, true, Collections.emptyMap(), false));
            assertThrows(IllegalArgumentException.class, () -> redis.saveKlines("TEST", period, Collections.emptyList()));
            assertThrows(IllegalArgumentException.class, () -> redis.saveSimulationHistory("TEST", period, Collections.emptyList()));
            assertThrows(IllegalArgumentException.class, () -> redis.getKlines("TEST", period));
            assertThrows(MarketHttp.Failure.class, () -> new MarketQuoteSource().getKline("TEST", period, 2, "Forex"));
            assertThrows(MarketHttp.Failure.class, () -> ExchangeQuoteSource.interval(period, true));
        }
        assertEquals(0, mockingDetails(store).getInvocations().stream().filter(call -> call.getMethod().getName().equals("locked")).count());
    }

    @Test void archivedMonthlyImportIsRejectedBeforeAnyAuthorityOrSqlWrite() {
        ControlHistoryStore store = mock(ControlHistoryStore.class);
        when(store.decode("request")).thenReturn(Collections.singletonMap("interval", "1M"));
        String proof = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        HistoryOrdering.ArchivedResponse response = new HistoryOrdering.ArchivedResponse("request", "{}", HistoryOrdering.sha("{}"), proof, "/fixture");
        HistoryOrdering archive = new HistoryOrdering(store);
        try (TenantContext.Scope ignored = TenantContext.open(1L)) {
            assertThrows(IllegalArgumentException.class, () -> archive.seal(1, 1700000040000L, proof, proof, Collections.singletonList(response)));
        }
        verify(store, never()).locked(anyLong(), any());
    }

    @Test void websocketReportsRetirementWithoutInstallingSubscription() throws Exception {
        ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
        TenantRepository tenants = mock(TenantRepository.class); Tenant tenant = mock(Tenant.class);
        when(tenant.getFrontendHost()).thenReturn("fixture.local"); when(tenant.isDomainVerified()).thenReturn(true); when(tenant.getStatus()).thenReturn("ACTIVE");
        when(tenants.findById(7L)).thenReturn(Optional.of(tenant));
        MarketWebSocketHandler handler = new MarketWebSocketHandler();
        ReflectionTestUtils.setField(handler, "marketService", market); ReflectionTestUtils.setField(handler, "tenants", tenants);
        WebSocketSession session = mock(WebSocketSession.class); when(session.isOpen()).thenReturn(true);
        Map<String,Object> attributes = new HashMap<>(); attributes.put("tenantId", 7L); attributes.put("frontendHost", "fixture.local"); when(session.getAttributes()).thenReturn(attributes);
        BlockingQueue<Map<?,?>> replies = new LinkedBlockingQueue<>(); ObjectMapper json = new ObjectMapper();
        doAnswer(call -> { replies.add(json.readValue(((TextMessage)call.getArgument(0)).getPayload(), Map.class)); return null; }).when(session).sendMessage(any());
        try {
            handler.afterConnectionEstablished(session);
            for (String period : MONTHS) {
                Map<String,Object> request = new HashMap<>(); request.put("action", "subscribeKline"); request.put("symbol", "TEST"); request.put("interval", period);
                handler.handleTextMessage(session, new TextMessage(json.writeValueAsString(request)));
                Map<?,?> reply = replies.poll(3, TimeUnit.SECONDS); assertNotNull(reply); assertEquals("klineError", reply.get("type")); assertEquals("invalid_parameters", reply.get("reason"));
            }
            verifyNoInteractions(market);
        } finally { handler.destroy(); }
    }
}
