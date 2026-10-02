package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionQuoteTest extends TenantMarketTestContext {
    @Test @SuppressWarnings("unchecked")
    void connectionFailureIsSeparateFromStaleOrMissingQuotesAndClearsOnRecovery() {
        ForexQuoteMarketService quotes = new ForexQuoteMarketService();
        try {
            Map<String, Object> groups = (Map<String, Object>) ReflectionTestUtils.getField(marketState(quotes), "groups");
            Object group = groups.get("Forex");
            Map<String, Map<String, Object>> cache = (Map<String, Map<String, Object>>) ReflectionTestUtils.getField(group, "quotes");
            Map<String, Object> raw = new HashMap<>();
            raw.put("price", 157d); raw.put("timestamp", System.currentTimeMillis() - 61000);
            raw.put("fetchedAt", System.currentTimeMillis()); cache.put("JPY=X", raw);
            assertEquals("stale", quotes.getPrice("JPY=X", "Forex").get("status"));
            for (String error : Arrays.asList(null, "partial_response", "invalid_or_missing_quote")) {
                ReflectionTestUtils.setField(group, "error", error);
                assertEquals(false, quotes.getPrice("JPY=X", "Forex").get("sourceConnectionFailed"));
            }
            for (String error : Arrays.asList("timeout", "connection_failure", "http_429", "http_503", "rate_limited")) {
                ReflectionTestUtils.setField(group, "error", error);
                assertEquals(true, quotes.getPrice("JPY=X", "Forex").get("sourceConnectionFailed"));
            }
            cache.clear();
            assertEquals(true, quotes.getPrice("JPY=X", "Forex").get("sourceConnectionFailed"));
            ReflectionTestUtils.setField(group, "error", null);
            assertEquals(false, quotes.getPrice("JPY=X", "Forex").get("sourceConnectionFailed"));
        } finally { quotes.stop(); }
    }

    @Test @SuppressWarnings("unchecked")
    void controlsKeepOffsetsExecutableDuringOutagesButPlainAndInvalidQuotesRemainBlocked() {
        ForexQuoteMarketService quotes = new ForexQuoteMarketService();
        try {
            TradingSymbol symbol = new TradingSymbol();
            symbol.setSymbol("XAUUSD"); symbol.setCategory("Metal"); symbol.setSourceCategory("Metal"); symbol.setMarketSource(com.gtcfesk.exchange.market.MarketInstrumentCatalog.inferredSource("Metal")); symbol.setAlltickSymbol("XAUUSD_SOURCE");
            symbol.setControlEnabled(true); symbol.setControlPriceOffset(new BigDecimal("5"));
            ReflectionTestUtils.setField(marketState(quotes), "registry", Collections.singletonMap(symbol.getSymbol(), symbol));
            Map<String, Object> groups = (Map<String, Object>) ReflectionTestUtils.getField(marketState(quotes), "groups");
            Map<String, Map<String, Object>> cache = (Map<String, Map<String, Object>>) ReflectionTestUtils.getField(groups.get("Metal"), "quotes");
            Map<String, Object> raw = new HashMap<>();
            raw.put("price", 100d); raw.put("timestamp", System.currentTimeMillis()); raw.put("fetchedAt", System.currentTimeMillis()); raw.put("sourceAvailable", true);
            cache.put("XAUUSD_SOURCE", raw);
            assertEquals(0, new BigDecimal("105").compareTo(quotes.freshPrice("XAUUSD")));
            assertEquals(100d, raw.get("price"));
            assertEquals(true, quotes.internalPrice("XAUUSD").get("controlActive"));
            symbol.setControlEnabled(false);
            assertEquals(false, quotes.internalPrice("XAUUSD").get("controlActive"));
            assertEquals(0, new BigDecimal("100").compareTo(quotes.freshPrice("XAUUSD")));
            symbol.setControlEnabled(true); symbol.setControlPriceOffset(new BigDecimal("-100"));
            assertNull(quotes.freshPrice("XAUUSD"));
            symbol.setControlPriceOffset(new BigDecimal("5"));
            symbol.setControlEnabled(false);
            raw.put("timestamp", System.currentTimeMillis() - 59000);
            assertEquals(0, new BigDecimal("100").compareTo(quotes.freshPrice("XAUUSD")));
            symbol.setControlEnabled(true);
            raw.put("timestamp", System.currentTimeMillis() - 61000);
            assertEquals(0, new BigDecimal("105").compareTo(quotes.freshPrice("XAUUSD")));
            assertEquals(raw.get("timestamp"), quotes.internalPrice("XAUUSD").get("timestamp"));
            assertEquals(raw.get("timestamp"), quotes.internalPrice("XAUUSD").get("sourceTimestamp"));
            symbol.setControlEnabled(false); assertNull(quotes.freshPrice("XAUUSD"));
            raw.put("timestamp", System.currentTimeMillis()); raw.put("sourceAvailable", false);
            assertNull(quotes.freshPrice("XAUUSD"));
            symbol.setControlEnabled(true);
            assertEquals(0, new BigDecimal("105").compareTo(quotes.freshPrice("XAUUSD")));
            raw.put("price", Double.NaN); assertNull(quotes.freshPrice("XAUUSD"));
            assertNull(quotes.freshPrice("UNKNOWN"));
        } finally { quotes.stop(); }
    }
}
