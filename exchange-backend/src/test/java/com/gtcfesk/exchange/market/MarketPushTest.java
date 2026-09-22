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
        when(market.knownSymbol("EURUSD")).thenReturn(true);
        Map<String,Object> quote = new HashMap<>(); quote.put("price", 1.1); quote.put("quoteVersion", 1L);
        when(market.snapshotPrice("EURUSD")).thenReturn(quote);
        try {
            for (int i = 0; i < 25; i++) {
                WebSocketSession session = mock(WebSocketSession.class); when(session.isOpen()).thenReturn(true);
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
