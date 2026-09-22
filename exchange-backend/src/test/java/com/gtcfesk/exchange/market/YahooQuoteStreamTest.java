package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.market.yahoo.YahooPricing.PricingData;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class YahooQuoteStreamTest {
    ForexQuoteMarketService market;
    PersistentPriceControl controls;
    YahooQuoteStream stream;
    @BeforeEach void setup() {
        market = new ForexQuoteMarketService(); stream = new YahooQuoteStream();
        controls = mock(PersistentPriceControl.class);
        TradingSymbol symbol = new TradingSymbol(); symbol.setId(1L); symbol.setSymbol("EURUSD"); symbol.setCategory("Forex"); symbol.setSourceCategory("Forex"); symbol.setMarketSource(com.gtcfesk.exchange.market.MarketInstrumentCatalog.inferredSource("Forex"));
        TradingSymbolRepository repository = mock(TradingSymbolRepository.class);
        when(repository.findAll()).thenReturn(Collections.singletonList(symbol));
        ReflectionTestUtils.setField(market, "symbols", repository);
        ReflectionTestUtils.setField(market, "redis", mock(RedisMarketService.class));
        ReflectionTestUtils.setField(market, "controls", controls);
        ReflectionTestUtils.setField(market, "yahoo", stream);
        market.refreshSymbols();
    }
    @AfterEach void stop() { market.stop(); stream.stop(); }
    Map<String,Object> quote(long time, double price) {
        Map<String,Object> q = new HashMap<>(); q.put("price", price); q.put("timestamp", time); return q;
    }
    @Test void realProtocolPreservesTimeAndOptionalFieldPresence() throws Exception {
        long now = System.currentTimeMillis();
        PricingData data = PricingData.newBuilder().setId("EURUSD=X").setPrice(1.1f).setTime(now).build();
        String frame = "{\"type\":\"pricing\",\"message\":\"" + Base64.getEncoder().encodeToString(data.toByteArray()) + "\"}";
        Map<String,Object> result = YahooQuoteStream.decode(frame);
        assertEquals(1.1d, result.get("price")); assertEquals(now, result.get("timestamp"));
        assertFalse(result.containsKey("change24h")); assertFalse(result.containsKey("previousClose"));
        assertThrows(Exception.class, () -> YahooQuoteStream.decode("{\"type\":\"pricing\",\"message\":\"invalid!\"}"));
        assertNull(YahooQuoteStream.decode("{\"type\":\"heartbeat\"}"));
    }
    @Test void sharedIngressRejectsOlderQuotesAndDeduplicatesHistory() {
        long now = System.currentTimeMillis();
        assertTrue(market.acceptQuote("EURUSD", "Forex", quote(now, 1.1), "http", now));
        assertTrue(market.acceptQuote("EURUSD", "Forex", quote(now, 1.1), "ws", now + 1));
        verify(controls, times(1)).sourceQuotes(anyList(), anyMap(), anyLong());
        assertFalse(market.acceptQuote("EURUSD", "Forex", quote(now - 1, 2), "http", now + 2));
        assertTrue(market.acceptQuote("EURUSD", "Forex", quote(now, 1.2), "ws", now + 3));
        verify(controls, times(2)).sourceQuotes(anyList(), anyMap(), anyLong());
        assertEquals(1.2d, market.getPrice("EURUSD", "Forex").get("price"));
    }
    @Test void websocketWinsSameTimeWhileConnectedButHttpCanRecoverDisconnectedSource() {
        long now = System.currentTimeMillis();
        WebSocketSession socket = mock(WebSocketSession.class); when(socket.isOpen()).thenReturn(true);
        ReflectionTestUtils.setField(stream, "session", socket);
        market.acceptQuote("EURUSD", "Forex", quote(now, 1.2), "ws", now);
        assertFalse(market.acceptQuote("EURUSD", "Forex", quote(now, 1.1), "http", now + 2));
        when(socket.isOpen()).thenReturn(false);
        assertFalse((boolean) market.getPrice("EURUSD", "Forex").get("available"));
        assertTrue(market.acceptQuote("EURUSD", "Forex", quote(now, 1.1), "http", now + 3));
        assertTrue((boolean) market.getPrice("EURUSD", "Forex").get("available"));
    }
    @Test void oldDataRemainsStaleAndHistoryFailureCannotPublishNewPrice() {
        long now = System.currentTimeMillis();
        market.acceptQuote("EURUSD", "Forex", quote(now - 20000, 1.1), "http", now);
        assertEquals("stale", market.getPrice("EURUSD", "Forex").get("status"));
        doThrow(new IllegalStateException("db unavailable")).when(controls).sourceQuotes(anyList(), anyMap(), anyLong());
        assertThrows(IllegalStateException.class, () -> market.acceptQuote("EURUSD", "Forex", quote(now, 1.3), "http", now));
        assertEquals(1.1d, market.getPrice("EURUSD", "Forex").get("price"));
        assertFalse((boolean) market.getPrice("EURUSD", "Forex").get("available"));
    }
    @Test void shadowCannotDisableHttpAndHealthySelectionIsPerSymbol() {
        long now = System.currentTimeMillis();
        Map<String,Object> q = quote(now, 1.1); q.put("symbol", "EURUSD=X"); q.put("fetchedAt", now);
        WebSocketSession socket = mock(WebSocketSession.class); when(socket.isOpen()).thenReturn(true);
        ReflectionTestUtils.setField(stream, "session", socket); ReflectionTestUtils.setField(stream, "connectedAt", now - 31000);
        ReflectionTestUtils.setField(stream, "enabledSymbols", "EURUSD=X,USDJPY=X");
        ReflectionTestUtils.setField(stream, "mode", "shadow"); stream.observe(q);
        assertFalse(stream.healthy("EURUSD=X"));
        ReflectionTestUtils.setField(stream, "mode", "ws_preferred");
        assertTrue(stream.healthy("EURUSD=X")); assertFalse(stream.healthy("USDJPY=X"));
        q.put("timestamp", now - 20000); q.put("symbol", "USDJPY=X"); stream.observe(q);
        assertFalse(stream.healthy("USDJPY=X"));
    }
}
