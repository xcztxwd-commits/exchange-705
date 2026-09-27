package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KlineSchedulingTest {
    @Test void quotePriorityDoesNotStarveKlinesAndLargerCacheCoversSmallerRequest() {
        ForexQuoteMarketService market = new ForexQuoteMarketService();
        MarketQuoteSource source = mock(MarketQuoteSource.class);
        TradingSymbolRepository repository = mock(TradingSymbolRepository.class);
        TradingSymbol symbol = new TradingSymbol();
        symbol.setId(1L); symbol.setSymbol("BABA"); symbol.setAlltickSymbol("BABA");
        symbol.setCategory("US"); symbol.setSourceCategory("US"); symbol.setMarketSource("yahoo");
        when(repository.findAll()).thenReturn(Collections.singletonList(symbol));
        long now = System.currentTimeMillis();
        Map<String, Object> quote = new HashMap<>(); quote.put("timestamp", now); quote.put("price", 81d);
        when(source.getBatchPrices(anyList(), eq("US"))).thenReturn(Collections.singletonMap("BABA", quote));
        List<Map<String, Object>> candles = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            Map<String, Object> bar = new HashMap<>();
            bar.put("timestamp", now - (499 - i) * 60000L);
            bar.put("open_price", 80d); bar.put("high_price", 82d); bar.put("low_price", 79d); bar.put("close_price", 81d);
            candles.add(bar);
        }
        Map<String, Object> response = new HashMap<>(); response.put("ret", 200);
        response.put("data", Collections.singletonMap("kline_list", candles));
        when(source.getKline("BABA", "1m", 500, "US")).thenReturn(response);
        ReflectionTestUtils.setField(market, "symbols", repository);
        ReflectionTestUtils.setField(market, "redis", mock(RedisMarketService.class));
        ReflectionTestUtils.setField(market, "source", source);
        ReflectionTestUtils.setField(market, "http", mock(MarketHttp.class));
        market.refreshSymbols();
        try {
            market.getKline("BABA", "1m", 500, "US");
            Map<String, Object> coveredPending = market.getKline("BABA", "1m", 200, "US");
            assertEquals(503, coveredPending.get("ret"));
            assertEquals(true, ((Map<?, ?>) coveredPending.get("data")).get("pending"));
            assertEquals(1, ((Number) market.sourceStatus().stream().filter(item -> "US".equals(item.get("category")))
                    .findFirst().get().get("pendingKlines")).intValue(), "a covering in-flight page is shared");
            Map<?, ?> groups = (Map<?, ?>) ReflectionTestUtils.getField(market, "groups");
            ReflectionTestUtils.invokeMethod(market, "tick", groups.get("US"));
            verify(source).getBatchPrices(anyList(), eq("US"));
            verify(source).getKline("BABA", "1m", 500, "US");
            Map<String, Object> smaller = market.getKline("BABA", "1m", 200, "US");
            assertEquals(200, smaller.get("ret"));
            assertEquals(200, ControlHistoryStore.rows(smaller).size());
            assertEquals(candles.get(300).get("timestamp"), ControlHistoryStore.rows(smaller).get(0).get("timestamp"));
            assertEquals(false, ((Map<?, ?>) smaller.get("data")).get("pending"));
            verify(source, times(1)).getKline(anyString(), anyString(), anyInt(), anyString());
            Map<String, Object> uncovered = market.getKline("BABA", "1m", 600, "US");
            assertEquals(200, uncovered.get("ret"), "partial cached coverage remains visible");
            assertEquals(500, ControlHistoryStore.rows(uncovered).size());
            assertEquals(true, ((Map<?, ?>) uncovered.get("data")).get("pending"));
        } finally { market.stop(); }
    }
}
