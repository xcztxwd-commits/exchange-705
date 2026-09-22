package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Reproducible virtual quotes: every reader sees the same price for a given second. */
public final class RandomMarketPath {
    private RandomMarketPath() { }
    private static final long[] STEPS = {5, 53, 307};
    private static final double[] AMPLITUDES = {.0002, .001, .004};
    private static final long DAY = 86400000L;

    public static boolean enabled(TradingSymbol symbol) {
        return symbol != null && Boolean.TRUE.equals(symbol.getRandomMarketEnabled())
            && symbol.getRandomMarketStartedAt() != null && symbol.getRandomMarketBasePrice() != null
            && symbol.getRandomMarketBasePrice().signum() > 0;
    }

    private static double noise(long seed, long index) {
        long n = seed + index * 0x9E3779B97F4A7C15L;
        n = (n ^ (n >>> 30)) * 0xBF58476D1CE4E5B9L;
        n = (n ^ (n >>> 27)) * 0x94D049BB133111EBL;
        n ^= n >>> 31;
        return (n >>> 11) * 0x1.0p-53 * 2 - 1;
    }

    private static double value(TradingSymbol symbol, long time) {
        long second = Math.max(0, (time - symbol.getRandomMarketStartedAt()) / 1000);
        long seed = symbol.getRandomMarketStartedAt() ^ symbol.getSymbol().hashCode();
        double base = symbol.getRandomMarketBasePrice().doubleValue(), delta = 0;
        for (int i = 0; i < STEPS.length; i++) {
            long index = second / STEPS[i], channel = seed + i * 7919;
            double progress = (second % STEPS[i]) / (double) STEPS[i];
            double v = noise(channel, index) * (1 - progress) + noise(channel, index + 1) * progress;
            delta += (v - noise(channel, 0)) * AMPLITUDES[i];
        }
        return base * (1 + delta);
    }

    public static BigDecimal price(TradingSymbol symbol, long time) {
        return SimulationControlPath.price(symbol, time);
    }

    public static BigDecimal basePrice(TradingSymbol symbol, long time) {
        int precision = PriceControlPath.precision(symbol);
        return BigDecimal.valueOf(value(symbol, time)).setScale(precision, RoundingMode.HALF_UP)
            .max(BigDecimal.ONE.movePointLeft(precision));
    }

    public static Map<String, Object> quote(TradingSymbol symbol, long now) {
        long tick = now / 1000 * 1000;
        Map<String, Object> result = new HashMap<>();
        result.put("price", price(symbol, tick));
        result.put("timestamp", tick); result.put("fetchedAt", now);
        result.put("sourceTimestamp", tick); result.put("expiresAt", tick + 15000);
        result.put("source", "Simulation"); result.put("simulated", true);
        result.put("simulationSession", symbol.getRandomMarketStartedAt());
        result.put("available", true); result.put("stale", false); result.put("status", "available");
        BigDecimal previous = price(symbol, Math.max(symbol.getRandomMarketStartedAt(), tick - DAY));
        BigDecimal change = price(symbol, tick).subtract(previous);
        result.put("change24h", change);
        result.put("changePct24h", change.divide(previous, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)));
        return result;
    }

    public static long timestamp(Map<String, Object> row) {
        long time = ((Number) row.get("timestamp")).longValue();
        return time < 10000000000L ? time * 1000 : time;
    }

    /** Existing candles stay intact; only the candle containing the start can extend. */
    @SuppressWarnings("unchecked")
    public static void mergeHistory(Map<String, Object> response, List<Map<String, Object>> history,
            long startedAt, int limit, Long endTime) {
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        TreeMap<Long, Map<String, Object>> merged = new TreeMap<>();
        for (Map<String, Object> row : history) {
            long time = timestamp(row);
            if (time >= startedAt || endTime != null && time > endTime) continue;
            Map<String, Object> copy = new HashMap<>(row); copy.put("timestamp", time);
            copy.put("source", "External"); merged.putIfAbsent(time, copy);
        }
        for (Map<String, Object> row : (List<Map<String, Object>>) data.get("kline_list")) {
            long time = timestamp(row);
            Map<String, Object> copy = new HashMap<>(row), previous = merged.get(time);
            if (previous != null) {
                copy.put("open_price", previous.get("open_price"));
                copy.put("high_price", Math.max(((Number) previous.get("high_price")).doubleValue(), ((Number) row.get("high_price")).doubleValue()));
                copy.put("low_price", Math.min(((Number) previous.get("low_price")).doubleValue(), ((Number) row.get("low_price")).doubleValue()));
                copy.put("volume", previous.getOrDefault("volume", 0));
            }
            copy.put("source", "Simulation"); merged.put(time, copy);
        }
        while (merged.size() > limit) merged.pollFirstEntry();
        data.put("kline_list", new ArrayList<>(merged.values()));
    }

    public static long duration(String interval) {
        switch (interval) {
            case "1m": return 60000;
            case "5m": return 300000;
            case "15m": return 900000;
            case "30m": return 1800000;
            case "1h": return 3600000;
            case "4h": return 14400000;
            case "1d": return DAY;
            case "1w": return DAY * 7;
            default: throw new IllegalArgumentException("Unsupported simulation interval");
        }
    }

    public static Map<String, Object> klines(TradingSymbol symbol, String interval, Integer limit, Long endTime, long now) {
        return klines(symbol, interval, limit, endTime, now, 0);
    }

    public static Map<String, Object> klines(TradingSymbol symbol, String interval, Integer limit, Long endTime, long now, long anchor) {
        long duration = duration(interval), session = symbol.getRandomMarketStartedAt();
        long last = now / 1000 * 1000;
        long latestBucket = Math.floorDiv(last - anchor, duration) * duration + anchor;
        if (endTime != null) latestBucket = Math.min(latestBucket, Math.floorDiv(endTime - anchor, duration) * duration + anchor);
        int count = Math.min(1000, Math.max(1, limit == null ? 100 : limit));
        // ponytail: reconstruct at most seven days; durable candle storage is needed for longer simulations.
        long first = Math.max(Math.floorDiv(session - anchor, duration) * duration + anchor, Math.max(Math.floorDiv(last - 7 * DAY - anchor, duration) * duration + anchor,
            latestBucket - (count - 1L) * duration));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long bucket = first; bucket <= latestBucket; bucket += duration) {
            long start = Math.max(bucket, session), end = Math.min(bucket + duration - 1000, last);
            if (start > end) continue;
            BigDecimal open = price(symbol, start), close = price(symbol, end), high = open.max(close), low = open.min(close);
            // The path is piecewise linear. Extrema occur at endpoints or a channel's knots.
            for (long step : STEPS) {
                long width = step * 1000;
                long knot = session + ((start - session) / width + 1) * width;
                for (; knot <= end; knot += width) {
                    BigDecimal p = price(symbol, knot); high = high.max(p); low = low.min(p);
                }
            }
            // Specified trajectories may have per-second noise. Include their exact
            // samples and transition edges rather than rewriting the candle afterward.
            for (SimulationControlPath.Event event : SimulationControlPath.events(symbol)) {
                for (long edge : new long[] {event.at - 1000, event.at, event.endsAt()}) {
                    if (edge >= start && edge <= end) {
                        BigDecimal p = price(symbol, edge); high = high.max(p); low = low.min(p);
                    }
                }
                if (!"offset".equals(event.mode)) {
                    for (long tick = Math.max(start, event.at); tick <= Math.min(end, event.endsAt()); tick += 1000) {
                        BigDecimal p = price(symbol, tick); high = high.max(p); low = low.min(p);
                    }
                }
            }
            Map<String, Object> row = new HashMap<>();
            row.put("timestamp", bucket); row.put("open_price", open); row.put("close_price", close);
            row.put("high_price", high); row.put("low_price", low); row.put("volume", 0);
            rows.add(row);
        }
        Map<String, Object> data = new HashMap<>(), response = new HashMap<>();
        data.put("symbol", symbol.getSymbol()); data.put("code", symbol.getSymbol()); data.put("kline_list", rows);
        data.put("source", "Simulation"); data.put("simulated", true); data.put("simulationSession", session);
        data.put("status", "available"); data.put("pending", false); data.put("fetchedAt", now);
        response.put("ret", 200); response.put("msg", "ok"); response.put("status", "available"); response.put("data", data);
        return response;
    }
}
