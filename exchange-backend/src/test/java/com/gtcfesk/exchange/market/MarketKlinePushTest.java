package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.control.Tenant;
import com.gtcfesk.exchange.control.TenantRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MarketKlinePushTest {
    @Test void notificationsFollowCommitAndNeverEscapeRollback() {
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:quote-commit", "sa", "")));
        tx.execute(status -> {
            MarketQuoteCommitted.publish(events, 7, "TEST");
            verify(events, never()).publishEvent(any(Object.class));
            return null;
        });
        verify(events).publishEvent(argThat((Object value) -> value instanceof MarketQuoteCommitted && ((MarketQuoteCommitted)value).tenantId == 7));
        clearInvocations(events);
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            MarketQuoteCommitted.publish(events, 7, "TEST");
            throw new IllegalStateException("rollback");
        }));
        verifyNoInteractions(events);
    }

    @Test void committedPriceAndCandlesShareTenantSnapshotAndPushWithoutWaitingForTimer() throws Exception {
        ForexQuoteMarketService market = mock(ForexQuoteMarketService.class);
        TenantRepository tenants = mock(TenantRepository.class);
        Tenant tenant = mock(Tenant.class);
        when(tenant.getFrontendHost()).thenReturn("fixture.local");
        when(tenant.isDomainVerified()).thenReturn(true);
        when(tenant.getStatus()).thenReturn("ACTIVE");
        when(tenants.findById(anyLong())).thenReturn(Optional.of(tenant));
        when(market.knownSymbol("TEST")).thenReturn(true);
        AtomicInteger price = new AtomicInteger(102), reads = new AtomicInteger();
        ThreadLocal<Boolean> snapshot = new ThreadLocal<>();
        TransactionTemplate read = new TransactionTemplate(new DataSourceTransactionManager(new DriverManagerDataSource("jdbc:h2:mem:kline-admission-commit", "sa", "")));
        read.setReadOnly(true); read.setIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        when(market.readSnapshot(any())).thenAnswer(call -> {
            assertEquals(Long.valueOf(7), TenantContext.requireTenantId());
            reads.incrementAndGet(); snapshot.set(true);
            try { return read.execute(status -> ((Supplier<?>)call.getArgument(0)).get()); } finally { snapshot.remove(); }
        });
        when(market.snapshotPrice("TEST")).thenAnswer(call -> {
            assertEquals(Boolean.TRUE, snapshot.get());
            Map<String,Object> q = new HashMap<>(); q.put("price", price.get()); q.put("quoteVersion", price.get()); q.put("epoch","fixture"); return q;
        });
        when(market.internalKline("TEST", "1m", 2)).thenAnswer(call -> {
            assertEquals(Boolean.TRUE, snapshot.get());
            Map<String,Object> bar = new HashMap<>(); bar.put("timestamp", 1700000000000L); bar.put("close_price", price.get());
            Map<String,Object> data = new HashMap<>(); data.put("kline_list", Collections.singletonList(bar)); data.put("pending", true);
            data.put("live",true); data.put("epoch","fixture"); data.put("quoteVersion",price.get()); data.put("updatedAt",1700000000000L);
            ForexQuoteMarketService.afterCommit(() -> data.put("pending", false)); // Queue filled before admission.
            return Collections.singletonMap("data", data);
        });
        MarketWebSocketHandler handler = new MarketWebSocketHandler();
        ReflectionTestUtils.setField(handler, "marketService", market);
        ReflectionTestUtils.setField(handler, "tenants", tenants);
        ObjectMapper json = new ObjectMapper();
        BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(new HashMap<String,Object>() {{ put("tenantId", 7L); put("frontendHost", "fixture.local"); }});
        doAnswer(call -> { frames.add(json.readTree(((TextMessage)call.getArgument(0)).getPayload())); return null; }).when(session).sendMessage(any());
        try {
            handler.afterConnectionEstablished(session);
            handler.handleTextMessage(session, new TextMessage("{\"action\":\"subscribeKline\",\"symbol\":\"TEST\",\"interval\":\"1m\"}"));
            JsonNode first = frames.poll(3, TimeUnit.SECONDS);
            assertNotNull(first);
            assertEquals(102, first.path("data").path("TEST").path("price").asInt());
            assertEquals(102, first.path("klines").get(0).path("bars").get(0).path("close_price").asInt());
            assertFalse(first.path("klines").get(0).path("pending").asBoolean(), "wire pending reflects actual queue admission after commit");
            Thread.sleep(30); frames.clear();
            int initialReads = reads.get();
            handler.quoteCommitted(new MarketQuoteCommitted(8, "TEST"));
            Thread.sleep(60); assertEquals(initialReads, reads.get(), "another tenant cannot wake this subscription");
            price.set(105);
            handler.quoteCommitted(new MarketQuoteCommitted(7, "TEST"));
            JsonNode changed = frames.poll(3, TimeUnit.SECONDS);
            assertNotNull(changed, "event push works without starting the periodic scheduler");
            assertEquals(105, changed.path("data").path("TEST").path("price").asInt());
            assertEquals(105, changed.path("klines").get(0).path("bars").get(0).path("close_price").asInt());
            assertEquals(initialReads + 1, reads.get());
            handler.handleTextMessage(session, new TextMessage("{\"action\":\"unsubscribeKline\",\"symbol\":\"TEST\",\"interval\":\"1m\"}"));
        } finally { handler.destroy(); }
    }
}
