package com.gtcfesk.exchange.market;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import static org.junit.jupiter.api.Assertions.*;

class QuoteStateTest {
    @Test void freshnessRequiresBothTimesAndNeverMutatesSnapshot() {
        long now = System.currentTimeMillis();
        Map<String, Object> quote = new HashMap<>(); quote.put("price", 100d);
        quote.put("timestamp", now - 60000); quote.put("fetchedAt", now); quote.put("sourceAvailable", true);
        assertEquals("stale", QuoteState.view(quote, 15000).get("status"));
        assertFalse(quote.containsKey("status"));
        quote.put("timestamp", now); quote.put("fetchedAt", now - 60000);
        assertEquals("stale", QuoteState.view(quote, 15000).get("status"));
        quote.put("fetchedAt", now); quote.put("sourceAvailable", false);
        assertEquals("unavailable", QuoteState.view(quote, 15000).get("status"));
        quote.put("price", Double.NaN); assertFalse(QuoteState.valid(quote));
        quote.put("price", Double.POSITIVE_INFINITY); assertFalse(QuoteState.valid(quote));
        quote.put("price", 100d); quote.remove("timestamp"); assertFalse(QuoteState.valid(quote));
    }
    @Test void retryAfterSupportsSecondsAndHttpDate() {
        assertEquals(8000, MarketHttp.retryAfter("8"));
        long wait = MarketHttp.retryAfter(ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(60).format(DateTimeFormatter.RFC_1123_DATE_TIME));
        assertTrue(wait > 58000 && wait <= 60000);
        assertEquals(0, MarketHttp.retryAfter("bad"));
    }
    @Test void trickleResponseCannotExtendTotalBudget() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1);
        ExecutorService worker = Executors.newSingleThreadExecutor(); server.setExecutor(worker);
        server.createContext("/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                for (int i = 0; i < 50; i++) { exchange.getResponseBody().write(' '); exchange.getResponseBody().flush(); Thread.sleep(100); }
            } catch (Exception ignored) { } finally { exchange.close(); }
        });
        server.start(); MarketHttp http = new MarketHttp(); http.budget = 600;
        long start = System.currentTimeMillis();
        try {
            http.begin();
            assertThrows(MarketHttp.Failure.class, () -> http.get(URI.create("http://127.0.0.1:" + server.getAddress().getPort())));
            assertTrue(System.currentTimeMillis() - start < 1600);
        } finally { http.end(); http.stop(); server.stop(0); worker.shutdownNow(); }
    }
}
