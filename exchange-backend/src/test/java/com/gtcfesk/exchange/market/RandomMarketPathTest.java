package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RandomMarketPathTest {
    TradingSymbol symbol() {
        TradingSymbol s = new TradingSymbol();
        s.setSymbol("USDJPY"); s.setPricePrecision(3);
        s.setRandomMarketEnabled(true); s.setRandomMarketStartedAt(1800000017000L);
        s.setRandomMarketBasePrice(new BigDecimal("156.855"));
        return s;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows(TradingSymbol s, String interval, int count, Long before, long now) {
        return (List<Map<String, Object>>) ((Map<?, ?>) RandomMarketPath.klines(s, interval, count, before, now).get("data")).get("kline_list");
    }

    @Test void everyCandleExactlyAggregatesTheSamePerSecondPricesAndSurvivesReload() {
        TradingSymbol s = symbol(); long start = s.getRandomMarketStartedAt(), now = start + 620000;
        Set<BigDecimal> prices = new HashSet<>();
        for (String interval : Arrays.asList("1m", "5m", "15m", "30m", "1h", "1d")) {
            List<Map<String, Object>> candles = rows(s, interval, 200, null, now);
            assertFalse(candles.isEmpty());
            for (Map<String, Object> candle : candles) {
                long bucket = (long) candle.get("timestamp");
                long from = Math.max(start, bucket), to = Math.min(now, bucket + RandomMarketPath.duration(interval) - 1000);
                BigDecimal high = BigDecimal.ZERO, low = new BigDecimal("999999");
                for (long time = from; time <= to; time += 1000) {
                    BigDecimal p = RandomMarketPath.price(s, time); prices.add(p);
                    assertTrue(p.signum() > 0);
                    assertEquals(p, RandomMarketPath.price(symbol(), time + 999));
                    high = high.max(p); low = low.min(p);
                }
                assertEquals(RandomMarketPath.price(s, from), candle.get("open_price"));
                assertEquals(RandomMarketPath.price(s, to), candle.get("close_price"));
                assertEquals(high, candle.get("high_price")); assertEquals(low, candle.get("low_price"));
            }
            assertEquals(RandomMarketPath.quote(s, now).get("price"), candles.get(candles.size() - 1).get("close_price"));
        }
        assertTrue(prices.size() > 20, "The sequence must actually move");
    }

    @Test void minuteBoundariesHistoryCursorAndUnchangedClosedCandles() {
        TradingSymbol s = symbol(); long now = s.getRandomMarketStartedAt() + 120000;
        long boundary = now / 60000 * 60000;
        List<Map<String, Object>> previous = rows(s, "1m", 200, null, boundary - 1);
        List<Map<String, Object>> next = rows(s, "1m", 200, null, boundary);
        assertEquals(previous.size() + 1, next.size());
        assertEquals(previous, next.subList(0, next.size() - 1));
        assertEquals(previous, rows(s, "1m", 200, boundary - 1, boundary + 50000));
        assertTrue(rows(s, "1m", 200, s.getRandomMarketStartedAt() / 60000 * 60000 - 1, now).isEmpty());
        assertEquals(1, rows(s, "1m", 1, null, now).size());
    }

    Map<String, Object> historical(long time) {
        Map<String, Object> bar = new HashMap<>();
        bar.put("timestamp", time); bar.put("open_price", 150d); bar.put("high_price", 170d);
        bar.put("low_price", 140d); bar.put("close_price", 156.855); bar.put("volume", 123);
        return bar;
    }

    @Test @SuppressWarnings("unchecked") void historySurvivesWithNoWeekendBackfillOrDuplicateBoundary() {
        TradingSymbol s = symbol(); long start = s.getRandomMarketStartedAt(), bucket = start / 60000 * 60000;
        Map<String, Object> friday = historical((bucket - 2 * 86400000L) / 1000);
        Map<String, Object> overlap = historical(bucket);
        List<Map<String, Object>> history = Arrays.asList(friday, overlap, historical(bucket + 60000));
        Map<String, Object> result = RandomMarketPath.klines(s, "1m", 200, null, start + 65000);
        RandomMarketPath.mergeHistory(result, history, start, 200, null);
        List<Map<String, Object>> merged = (List<Map<String, Object>>) ((Map<?, ?>) result.get("data")).get("kline_list");
        assertEquals(3, merged.size(), "The weekend gap must stay empty");
        assertEquals(bucket - 2 * 86400000L, merged.get(0).get("timestamp"));
        assertEquals(friday.get("close_price"), merged.get(0).get("close_price"));
        assertEquals(150d, merged.get(1).get("open_price"));
        assertEquals(170d, merged.get(1).get("high_price"));
        assertEquals(140d, merged.get(1).get("low_price"));
        assertEquals(123, merged.get(1).get("volume"));
        assertEquals(156.855, overlap.get("close_price"), "Source snapshots cannot be mutated");
        Map<String, Object> older = RandomMarketPath.klines(s, "1m", 200, bucket - 1, start + 65000);
        RandomMarketPath.mergeHistory(older, history, start, 200, bucket - 1);
        assertEquals(1, ((List<?>) ((Map<?, ?>) older.get("data")).get("kline_list")).size());
    }

    @Test @SuppressWarnings("unchecked") void hourlySessionOffsetIsRetainedAcrossPeriodSwitches() {
        TradingSymbol s = symbol(); long start = s.getRandomMarketStartedAt(), offset = 1800000;
        long bucket = Math.floorDiv(start - offset, 3600000) * 3600000 + offset;
        Map<String, Object> result = RandomMarketPath.klines(s, "1h", 200, null, start + 1000, offset);
        RandomMarketPath.mergeHistory(result, Arrays.asList(historical(bucket)), start, 200, null);
        List<Map<String, Object>> merged = (List<Map<String, Object>>) ((Map<?, ?>) result.get("data")).get("kline_list");
        assertEquals(1, merged.size()); assertEquals(bucket, merged.get(0).get("timestamp"));
        assertEquals(150d, merged.get(0).get("open_price"));
    }
}
