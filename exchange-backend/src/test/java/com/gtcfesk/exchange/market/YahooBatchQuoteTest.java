package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.net.URLDecoder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class YahooBatchQuoteTest {
    final MarketQuoteSource source = new MarketQuoteSource();
    final MarketHttp http = mock(MarketHttp.class);
    final ObjectMapper json = new ObjectMapper();
    YahooBatchQuoteTest() {
        ReflectionTestUtils.setField(source, "http", http);
        ReflectionTestUtils.setField(source, "yahooUrl", "https://query1.finance.yahoo.com/v8/finance");
    }
    static List<String> codes(int size) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < size; i++) result.add("PAIR" + i + "=X");
        return result;
    }
    static List<String> requested(URI uri) throws Exception {
        String query = URLDecoder.decode(uri.getRawQuery(), "UTF-8");
        String symbols = query.substring("symbols=".length(), query.indexOf("&range="));
        return Arrays.asList(symbols.split(","));
    }
    String payload(List<String> symbols, Object baseline) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String code : symbols) {
            Map<String, Object> quote = new LinkedHashMap<>();
            quote.put("close", Arrays.asList(110, null));
            quote.put("timestamp", Arrays.asList(System.currentTimeMillis() / 1000, null));
            if (baseline != null) quote.put("previousClose", baseline);
            result.put(code, quote);
        }
        return json.writeValueAsString(result);
    }
    @Test void moreThanTwentySymbolsIncludingConversionSubscriptionsAreChunked() throws Exception {
        List<Integer> batches = new ArrayList<>();
        when(http.get(any(URI.class))).thenAnswer(call -> {
            List<String> requested = requested(call.getArgument(0));
            assertTrue(requested.size() <= 20);
            batches.add(requested.size());
            return ResponseEntity.ok(payload(requested, 100));
        });
        List<String> input = codes(43); input.add(input.get(0));
        Map<String, Map<String, Object>> quotes = source.getBatchPrices(input, "Forex");
        assertEquals(Arrays.asList(20, 20, 3), batches);
        assertEquals(43, quotes.size());
        for (String code : codes(43)) {
            Map<String, Object> quote = quotes.get(code);
            assertEquals(code, quote.get("symbol"));
            assertEquals(110.0, quote.get("price"));
            assertEquals(100.0, quote.get("previousClose"));
            assertEquals(10.0, quote.get("change24h"));
            assertEquals(10.0, ((Number) quote.get("changePct24h")).doubleValue(), 1e-9);
            assertEquals("previousClose", quote.get("changeBasis"));
            assertTrue(QuoteState.valid(quote));
        }
    }
    @Test void fifoAutomaticallyRebatchesGrowingAndShrinkingSubscriptionsWithoutManualGroups() throws Exception {
        List<List<String>> batches = new ArrayList<>();
        Thread caller = Thread.currentThread();
        when(http.get(any(URI.class))).thenAnswer(call -> {
            assertSame(caller, Thread.currentThread(), "Queued requests must remain serial on the provider lane");
            List<String> requested = requested(call.getArgument(0));
            assertTrue(requested.size() <= 20);
            batches.add(requested);
            return ResponseEntity.ok(payload(requested, 100));
        });
        for (String category : Arrays.asList("Forex", "US", "CFD", "Oil")) {
            for (int size : Arrays.asList(0, 1, 20, 21, 23, 40, 41, 65, 3)) {
                batches.clear();
                List<String> subscriptions = codes(size);
                assertEquals(size, source.getBatchPrices(subscriptions, category).size());
                assertEquals((size + 19) / 20, batches.size());
                List<String> drained = new ArrayList<>();
                for (List<String> batch : batches) drained.addAll(batch);
                assertEquals(subscriptions, drained, "FIFO must drain every current subscription exactly once");
            }
        }
    }
    @Test void aFailedFirstBatchDoesNotBlockLaterQueuedBatches() throws Exception {
        List<List<String>> batches = new ArrayList<>();
        when(http.get(any(URI.class))).thenAnswer(call -> {
            List<String> requested = requested(call.getArgument(0));
            batches.add(requested);
            if (requested.contains("PAIR0=X")) throw new MarketHttp.Failure("timeout", 0);
            return ResponseEntity.ok(payload(requested, 100));
        });
        Map<String, Map<String, Object>> quotes = source.getBatchPrices(codes(65), "Forex");
        assertEquals(Arrays.asList(20, 20, 20, 5), Arrays.asList(batches.get(0).size(), batches.get(1).size(), batches.get(2).size(), batches.get(3).size()));
        assertEquals(new LinkedHashSet<>(codes(65).subList(20, 65)), quotes.keySet());
    }
    @Test void aFailedSubsetDoesNotPoisonSuccessfulQuotesButTotalFailureStillPropagates() throws Exception {
        when(http.get(any(URI.class))).thenAnswer(call -> {
            List<String> requested = requested(call.getArgument(0));
            if (requested.size() < 20) throw new MarketHttp.Failure("http_400", 0);
            return ResponseEntity.ok(payload(requested, 100));
        });
        assertEquals(20, source.getBatchPrices(codes(23), "Forex").size());
        doThrow(new MarketHttp.Failure("http_429", 2000)).when(http).get(any(URI.class));
        MarketHttp.Failure failure = assertThrows(MarketHttp.Failure.class, () -> source.getBatchPrices(codes(23), "Forex"));
        assertEquals("http_429", failure.getMessage());
        assertEquals(2000, failure.retryAfterMs);
    }
    @Test void missingOrInvalidPreviousCloseIsUnknownWhileARealZeroRemainsZero() throws Exception {
        for (Object baseline : Arrays.asList(null, 0, -1, "NaN", "Infinity")) {
            when(http.get(any(URI.class))).thenReturn(ResponseEntity.ok(payload(codes(1), baseline)));
            Map<String, Object> quote = source.getBatchPrices(codes(1), "Forex").get("PAIR0=X");
            assertFalse(quote.containsKey("changePct24h"));
            assertFalse(quote.containsKey("change24h"));
            assertFalse(quote.containsKey("changeBasis"));
        }
        when(http.get(any(URI.class))).thenReturn(ResponseEntity.ok(payload(codes(1), 110)));
        assertEquals(0.0, source.getBatchPrices(codes(1), "Forex").get("PAIR0=X").get("changePct24h"));
        when(http.get(any(URI.class))).thenReturn(ResponseEntity.ok(payload(codes(1), 120)));
        assertEquals(-100.0 / 12, ((Number) source.getBatchPrices(codes(1), "Forex").get("PAIR0=X").get("changePct24h")).doubleValue(), 1e-9);
    }
    @Test void nestedYahooMetaAlsoComputesFromTheVerifiedPreviousClose() {
        when(http.get(any(URI.class))).thenReturn(ResponseEntity.ok("{\"spark\":{\"result\":[{\"symbol\":\"EURUSD=X\",\"response\":[{\"meta\":{\"regularMarketPrice\":1.11,\"previousClose\":1.1,\"regularMarketTime\":1700000000}}]}]}}"));
        Map<String, Object> quote = source.getBatchPrices(Collections.singletonList("EURUSD=X"), "Forex").get("EURUSD=X");
        assertEquals(100.0 / 110, ((Number) quote.get("changePct24h")).doubleValue(), 1e-9);
        assertEquals(1700000000000L, quote.get("timestamp"), "Fetching must not replace a source timestamp with the current time");
    }
}
