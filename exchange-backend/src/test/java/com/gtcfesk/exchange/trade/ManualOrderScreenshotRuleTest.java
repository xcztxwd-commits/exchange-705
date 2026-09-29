package com.gtcfesk.exchange.trade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class ManualOrderScreenshotRuleTest {
    private static BigDecimal n(String value) { return new BigDecimal(value); }

    @Test void screenshotRoundsIdealLotAndKeepsAllTargetsWithinFivePercent() {
        BigDecimal available = n("43435.3098756"), lot = n("100000"), fee = n("30");
        BigDecimal open = n("157.58200073242188"), close = n("157.242");
        BigDecimal openRate = n("0.006345902569592"), closeRate = n("0.0063619301654398");
        long openMinute = 1790550060000L, closeMinute = 1790601840000L;
        NavigableMap<Long, ManualOrderGenerator.Candle> candles = new TreeMap<>();
        // USD/JPY margin is based on USD, not the rounded JPY settlement conversion.
        candles.put(openMinute, new ManualOrderGenerator.Candle(openMinute, open, openRate, BigDecimal.ONE));
        candles.put(closeMinute, new ManualOrderGenerator.Candle(closeMinute, close, closeRate, BigDecimal.ONE));

        ManualOrderGenerator.Request pins = new ManualOrderGenerator.Request();
        pins.side = "SELL";
        pins.leverage = n("100");
        pins.percent = n("68");
        assertNull(pins.quantity);
        assertNull(pins.targetNet);

        BigDecimal lower = ManualOrderCalculation.calculate("QUANTITY", n("28.67"), pins.side,
                available, open, close, lot, pins.leverage, openRate, closeRate, fee, BigDecimal.ONE).get("percent");
        BigDecimal upper = ManualOrderCalculation.calculate("QUANTITY", n("28.68"), pins.side,
                available, open, close, lot, pins.leverage, openRate, closeRate, fee, BigDecimal.ONE).get("percent");
        assertEquals(0, lower.compareTo(n("67.98639191")));
        assertEquals(0, upper.compareTo(n("68.01010534")));
        ManualOrderGenerator.Candidate adjusted = ManualOrderGenerator.solve(pins, candles,
                openMinute, closeMinute, available, lot, fee, 42);
        assertEquals(0, adjusted.calculation.get("quantity").compareTo(n("28.68")));
        assertTrue(ManualOrderGenerator.matches(pins, adjusted.calculation, adjusted.leverage));
        assertTrue(ManualOrderGenerator.withinTarget(adjusted.calculation.get("percent"), n("68")));

        pins.quantity = n("28.6757379");
        pins.targetNet = n("5343.2587067");
        ManualOrderGenerator.Candidate combined = ManualOrderGenerator.solve(pins, candles,
                openMinute, closeMinute, available, lot, fee, 42);
        assertEquals(0, combined.calculation.get("quantity").compareTo(n("28.68")));
        assertTrue(ManualOrderGenerator.matches(pins, combined.calculation, combined.leverage));
        assertTrue(ManualOrderGenerator.withinTarget(combined.calculation.get("net"), pins.targetNet));
        assertTrue(combined.calculation.get("net").subtract(n("5343.2587067")).abs().compareTo(n("0.01"))<0);
    }
}
