package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class YahooKlineSnapshotTest {
    @Test void quoteTimeTailCannotConsumeLatestOrHistoricalMinutePageLimit() throws Exception {
        MarketQuoteSource source = new MarketQuoteSource(); MarketHttp http = mock(MarketHttp.class);
        ReflectionTestUtils.setField(source, "http", http);
        ReflectionTestUtils.setField(source, "yahooUrl", "https://query1.finance.yahoo.com/v8/finance");
        long first = SourceHistoryGapRepairTest.M / 1000;
        Map<String,Object> quote = new LinkedHashMap<>();
        for (String field : Arrays.asList("open", "high", "low", "close", "volume")) quote.put(field, List.of(158d, 158d, 158d));
        String raw = new ObjectMapper().writeValueAsString(Map.of("chart", Map.of("result", List.of(Map.of(
            "timestamp", List.of(first, first + 60, first + 116), "indicators", Map.of("quote", List.of(quote)))))));
        when(http.get(any(URI.class))).thenReturn(ResponseEntity.ok(raw));
        List<Map<String,Object>> latest = ControlHistoryStore.rows(source.getKline("JPY=X", "1m", 2, "Forex"));
        List<Map<String,Object>> history = ControlHistoryStore.rows(source.getHistoryWindow("JPY=X", "1m", 2, "Forex", first * 1000, (first + 180) * 1000));
        for (List<Map<String,Object>> rows : List.of(latest, history)) {
            assertEquals(List.of(first * 1000, (first + 60) * 1000), rows.stream().map(ControlHistoryStore::time).collect(java.util.stream.Collectors.toList()));
        }
    }
}
