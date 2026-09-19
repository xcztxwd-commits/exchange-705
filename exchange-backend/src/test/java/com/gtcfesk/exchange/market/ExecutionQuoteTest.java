package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionQuoteTest {
    @Test @SuppressWarnings("unchecked")
    void executableQuotesKeepAdminOffsetsButNeverReviveStaleOrInvalidPrices() {
        ForexQuoteMarketService quotes = new ForexQuoteMarketService();
        try {
            TradingSymbol symbol = new TradingSymbol();
            symbol.setSymbol("XAUUSD"); symbol.setCategory("Metal"); symbol.setAlltickSymbol("XAUUSD_SOURCE");
            symbol.setControlEnabled(true); symbol.setControlPriceOffset(new BigDecimal("5"));
            ReflectionTestUtils.setField(quotes, "registry", Collections.singletonMap(symbol.getSymbol(), symbol));
            Map<String, Object> groups = (Map<String, Object>) ReflectionTestUtils.getField(quotes, "groups");
            Map<String, Map<String, Object>> cache = (Map<String, Map<String, Object>>) ReflectionTestUtils.getField(groups.get("Metal"), "quotes");
            Map<String, Object> raw = new HashMap<>();
            raw.put("price", 100d); raw.put("timestamp", System.currentTimeMillis()); raw.put("fetchedAt", System.currentTimeMillis()); raw.put("sourceAvailable", true);
            cache.put("XAUUSD_SOURCE", raw);
            assertEquals(0, new BigDecimal("105").compareTo(quotes.freshPrice("XAUUSD")));
            assertEquals(100d, raw.get("price"));
            symbol.setControlEnabled(false);
            assertEquals(0, new BigDecimal("100").compareTo(quotes.freshPrice("XAUUSD")));
            symbol.setControlEnabled(true); symbol.setControlPriceOffset(new BigDecimal("-100"));
            assertNull(quotes.freshPrice("XAUUSD"));
            symbol.setControlPriceOffset(new BigDecimal("5"));
            raw.put("timestamp", System.currentTimeMillis() - 60000); assertNull(quotes.freshPrice("XAUUSD"));
            raw.put("timestamp", System.currentTimeMillis()); raw.put("sourceAvailable", false); assertNull(quotes.freshPrice("XAUUSD"));
            assertNull(quotes.freshPrice("UNKNOWN"));
        } finally { quotes.stop(); }
    }
}
