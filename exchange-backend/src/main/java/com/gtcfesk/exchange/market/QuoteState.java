package com.gtcfesk.exchange.market;

import java.util.*;

/** Quote timestamps are milliseconds. A successful fetch does not make old source data fresh. */
public final class QuoteState {
    private QuoteState() {}
    public static long time(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }
    public static boolean valid(Map<String, Object> quote) {
        if (quote == null || !(quote.get("price") instanceof Number)) return false;
        double price = ((Number) quote.get("price")).doubleValue();
        long timestamp = time(quote.get("timestamp"));
        return Double.isFinite(price) && price > 0 && timestamp > 0
            && timestamp <= System.currentTimeMillis() + 5000;
    }
    public static Map<String, Object> view(Map<String, Object> saved, long maxAgeMs) {
        Map<String, Object> result = saved == null ? new HashMap<>() : new HashMap<>(saved);
        long now = System.currentTimeMillis();
        boolean expired = !valid(result) || now - time(result.get("timestamp")) > maxAgeMs
            || now - time(result.get("fetchedAt")) > maxAgeMs;
        boolean available = !expired && !Boolean.FALSE.equals(result.get("sourceAvailable"));
        result.put("available", available);
        result.put("tradeAvailable", available);
        result.put("displayAvailable", valid(result));
        result.put("stale", expired);
        result.put("status", !valid(result) ? "unavailable" : expired ? "stale" : available ? "available" : "unavailable");
        result.put("sourceTimestamp", result.get("timestamp"));
        result.put("expiresAt", Math.min(time(result.get("timestamp")), time(result.get("fetchedAt"))) + maxAgeMs);
        return result;
    }
}
