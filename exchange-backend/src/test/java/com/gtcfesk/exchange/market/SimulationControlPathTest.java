package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulationControlPathTest {
    TradingSymbol symbol() {
        TradingSymbol s = new TradingSymbol(); s.setSymbol("VIRTUAL"); s.setPricePrecision(3);
        s.setRandomMarketEnabled(true); s.setRandomMarketStartedAt(1800000000000L);
        s.setRandomMarketBasePrice(new BigDecimal("100")); return s;
    }
    void target(TradingSymbol s, long at) {
        s.setControlStartPrice(RandomMarketPath.price(s, at)); s.setControlTargetPrice(new BigDecimal("110"));
        s.setControlStartedAt(at); s.setControlDurationSeconds(20); s.setControlIntensity(5);
        s.setControlEnabled(true); s.setControlRandomOscillation(true);
        SimulationControlPath.record(s, at);
    }
    @Test void targetCompletionAndCancellationContinueFromLastPriceAndKeepHistory() {
        TradingSymbol s = symbol(); long start = s.getRandomMarketStartedAt(), at = start + 20000;
        BigDecimal old = RandomMarketPath.price(s, start + 10000);
        target(s, at);
        assertEquals(old, RandomMarketPath.price(s, start + 10000));
        assertEquals(0, new BigDecimal("110").compareTo(RandomMarketPath.price(s, at + 20000)));
        BigDecimal drift = RandomMarketPath.basePrice(s, at + 30000).subtract(RandomMarketPath.basePrice(s, at + 20000));
        assertEquals(0, new BigDecimal("110").add(drift).compareTo(RandomMarketPath.price(s, at + 30000)));
        BigDecimal historicalTarget = RandomMarketPath.price(s, at + 10000);
        long cancelled = at + 11000;
        BigDecimal last = RandomMarketPath.price(s, cancelled);
        s.setControlStartedAt(null); s.setControlEnabled(false);
        s.setControlPriceOffset(last.subtract(RandomMarketPath.basePrice(s, cancelled)));
        SimulationControlPath.record(s, cancelled);
        assertEquals(0, last.compareTo(RandomMarketPath.price(s, cancelled)));
        assertEquals(historicalTarget, RandomMarketPath.price(s, at + 10000));
        TradingSymbol reload = symbol(); reload.setRandomMarketControls(s.getRandomMarketControls());
        assertEquals(RandomMarketPath.price(s, at + 90000), RandomMarketPath.price(reload, at + 90000));
    }
    @Test @SuppressWarnings("unchecked") void candleExtremaAndQuoteUseTheSpecifiedPerSecondPath() {
        TradingSymbol s = symbol(); long start = s.getRandomMarketStartedAt(); target(s, start + 20000);
        long now = start + 59000;
        Map<String, Object> data = (Map<String, Object>) RandomMarketPath.klines(s, "1m", 200, null, now).get("data");
        Map<String, Object> candle = ((List<Map<String, Object>>) data.get("kline_list")).get(0);
        BigDecimal high = BigDecimal.ZERO, low = new BigDecimal("9999");
        for (long tick = start; tick <= now; tick += 1000) {
            BigDecimal p = RandomMarketPath.price(s, tick); high = high.max(p); low = low.min(p);
        }
        assertEquals(high, candle.get("high_price")); assertEquals(low, candle.get("low_price"));
        assertEquals(RandomMarketPath.quote(s, now).get("price"), candle.get("close_price"));
        Object previous = RandomMarketPath.klines(s, "1m", 200, null, now);
        target(s, start + 90000);
        assertEquals(previous, RandomMarketPath.klines(s, "1m", 200, null, now));
    }
    @Test void savedUnversionedVirtualEventsRetainTheirOriginalRegularPath() {
        TradingSymbol s=symbol();long at=s.getRandomMarketStartedAt();
        s.setControlEnabled(true);s.setControlStartedAt(at);s.setControlDurationSeconds(20);
        s.setControlStartPrice(BigDecimal.valueOf(100));s.setControlTargetPrice(BigDecimal.valueOf(110));
        s.setControlIntensity(10);s.setControlRandomOscillation(false);SimulationControlPath.record(s,at);
        assertEquals(0,new BigDecimal("106.25").compareTo(RandomMarketPath.price(s,at+5000)));
        TradingSymbol old=symbol();old.setRandomMarketControls(s.getRandomMarketControls().replace("\"algorithmVersion\":2,", ""));
        assertEquals(0,new BigDecimal("102.5").compareTo(RandomMarketPath.price(old,at+5000)));
    }
    @Test void gradualRestoreEndsOnRandomBaseWithoutDisablingIt() {
        TradingSymbol s = symbol(); long at = s.getRandomMarketStartedAt() + 20000;
        s.setControlEnabled(true); s.setControlRestoring(true); s.setControlStartedAt(at);
        s.setControlDurationSeconds(10); s.setControlIntensity(1); s.setControlRandomOscillation(false);
        s.setControlStartPrice(new BigDecimal("105")); s.setControlTargetPrice(new BigDecimal("100"));
        s.setControlPriceOffset(new BigDecimal("5")); SimulationControlPath.record(s, at);
        assertEquals(0, RandomMarketPath.basePrice(s, at).add(new BigDecimal("5")).compareTo(RandomMarketPath.price(s, at)));
        assertEquals(RandomMarketPath.basePrice(s, at + 10000), RandomMarketPath.price(s, at + 10000));
        assertEquals(RandomMarketPath.basePrice(s, at + 30000), RandomMarketPath.price(s, at + 30000));
        assertTrue(s.getRandomMarketEnabled());
    }
}
