package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.SplittableRandom;

/** A repeatable, per-second path: all readers and restarts see the same price. */
public final class PriceControlPath {
    private static final MathContext MATH = MathContext.DECIMAL128;
    private PriceControlPath() { }

    public static boolean running(TradingSymbol symbol) {
        return Boolean.TRUE.equals(symbol.getControlEnabled()) && symbol.getControlStartedAt() != null
            && symbol.getControlCompletedAt() == null && symbol.getControlStartPrice() != null
            && symbol.getControlTargetPrice() != null && symbol.getControlDurationSeconds() != null
            && symbol.getControlDurationSeconds() > 0 && symbol.getControlIntensity() != null;
    }

    public static long endsAt(TradingSymbol symbol) {
        return symbol.getControlStartedAt() + symbol.getControlDurationSeconds() * 1000L;
    }

    public static int precision(TradingSymbol symbol) {
        return Math.max(0, Math.min(8, symbol.getPricePrecision() == null ? 2 : symbol.getPricePrecision()));
    }

    public static BigDecimal price(TradingSymbol symbol, long now) {
        return interpolate(symbol, symbol.getControlStartPrice(), symbol.getControlTargetPrice(), now, true);
    }

    public static BigDecimal restoreOffset(TradingSymbol symbol, long now) {
        return interpolate(symbol, symbol.getControlPriceOffset(), BigDecimal.ZERO, now, false);
    }

    private static BigDecimal interpolate(TradingSymbol symbol, BigDecimal start, BigDecimal target, long now, boolean positive) {
        long second = Math.max(0, (now - symbol.getControlStartedAt()) / 1000);
        int duration = symbol.getControlDurationSeconds();
        if (second >= duration) return target;
        if (second == 0) return start;
        BigDecimal delta = target.subtract(start);
        BigDecimal progress = BigDecimal.valueOf(second).divide(BigDecimal.valueOf(duration), MATH);
        BigDecimal price = start.add(delta.multiply(progress, MATH));
        if (Boolean.TRUE.equals(symbol.getControlRandomOscillation())) {
            // Intensity scales noise in units of the average step; taper it to zero at both ends.
            BigDecimal step = delta.abs().divide(BigDecimal.valueOf(duration), MATH)
                .max(symbol.getControlStartPrice().min(symbol.getControlTargetPrice()).multiply(new BigDecimal("0.0001")));
            long seed = symbol.getControlStartedAt() ^ symbol.getSymbol().hashCode() ^ (second * 0x9E3779B97F4A7C15L);
            double p = progress.doubleValue();
            double noise = new SplittableRandom(seed).nextDouble(-1, 1) * 2 * symbol.getControlIntensity() * 4 * p * (1 - p);
            price = price.add(step.multiply(BigDecimal.valueOf(noise), MATH));
        }
        int precision = precision(symbol);
        if (positive) price = price.max(BigDecimal.ONE.movePointLeft(precision));
        return price.setScale(precision, RoundingMode.HALF_UP);
    }
}
