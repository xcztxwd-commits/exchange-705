package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import java.math.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StabilizedControlPlanTest {
    private static final BigDecimal START = new BigDecimal("100000.00"), TARGET = new BigDecimal("100300.00");
    static TargetControlOptions options(String formula, String mode, String percent) {
        TargetControlOptions value = new TargetControlOptions(); value.setStepFormula(formula); value.setDeviationBandMode(mode);
        value.setDeviationBandPercent(percent == null ? null : new BigDecimal(percent)); return value;
    }
    static StabilizedControlPlan.Parameters parameters(BigDecimal start, BigDecimal target, int duration, int precision, int intensity, TargetControlOptions options) {
        return new StabilizedControlPlan.Parameters(start, target, duration, precision, intensity, StabilizedControlPlan.DEFAULT_RATIO,
                new TargetControlSettings(start, target, duration, precision, intensity, options));
    }
    @Test void adaptiveBaseUsesPriceGapDurationAndPrecisionWhileManualBandStaysFixed() {
        for (int intensity = 1; intensity <= 10; intensity++) {
            TargetControlSettings auto = new TargetControlSettings(START, TARGET, 300, 2, intensity, null);
            assertEquals(new BigDecimal("1.01"), auto.baseAmount);
            assertEquals(0, auto.typical.compareTo(auto.baseAmount.multiply(BigDecimal.valueOf(intensity))));
            assertEquals(0, auto.band.compareTo(auto.typical.multiply(new BigDecimal("4"))));
            TargetControlSettings faster = new TargetControlSettings(START, new BigDecimal("100900.00"), 100, 2, intensity, null);
            TargetControlSettings slower = new TargetControlSettings(START, new BigDecimal("100900.00"), 3600, 2, intensity, null);
            assertTrue(faster.baseAmount.compareTo(auto.baseAmount) > 0);
            assertTrue(slower.baseAmount.compareTo(faster.baseAmount) < 0);
            assertEquals(new BigDecimal("1.00"), slower.baseAmount);
            TargetControlSettings manual = new TargetControlSettings(START, TARGET, 300, 2, intensity, options(null, "MANUAL", "0.02"));
            assertEquals(new BigDecimal("20.00"), manual.band);
            assertEquals(auto.typical, manual.typical);
        }
        TargetControlSettings custom = new TargetControlSettings(START, TARGET, 300, 2, 5, options("gap / duration + 4", "AUTO", null));
        assertEquals(0, custom.typical.compareTo(new BigDecimal("5")));
        assertEquals(new BigDecimal("20.00"), custom.band);
    }
    @Test void previewTiersMatchForexTicksWithoutChangingTargetTimePrecisionOrManualBand() {
        BigDecimal start = new BigDecimal("157.575"), target = new BigDecimal("157.588");
        TargetControlOptions legacy = options(TargetControlSettings.LEGACY_FORMULA, "AUTO", null);
        List<Map<String, Object>> tiers = StabilizedControlPlan.previewTiers(start, target, 10, 3, legacy);
        assertEquals(10, tiers.size());
        assertEquals("AMPLITUDE_PRECISION_UNREPRESENTABLE", tiers.get(0).get("errorCode"));
        assertEquals("TARGET_AMPLITUDE_INFEASIBLE", tiers.get(1).get("errorCode"));
        Map<String, Object> chosen = tiers.stream().filter(tier -> Boolean.TRUE.equals(tier.get("feasible"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals(7, chosen.get("intensity"));
        StabilizedControlPlan.Parameters p = parameters(start, target, 10, 3, 7, legacy);
        assertEquals(10, p.duration); assertEquals(3, p.precision);
        assertEquals(start, p.amount(p.start)); assertEquals(target, p.amount(p.target));
        assertPath(p, StabilizedControlPlan.generate(p, 60));
        TargetControlOptions manual = options(null, "MANUAL", "0.004");
        for (Map<String, Object> tier : StabilizedControlPlan.previewTiers(start, target, 10, 3, manual))
            if (tier.containsKey("corridorAmount")) assertEquals("0.006", tier.get("corridorAmount"));
        assertEquals(new BigDecimal("0.004"), manual.getDeviationBandPercent());
        assertTrue(StabilizedControlPlan.previewTiers(start, target, 1, 3, null).stream().noneMatch(tier -> Boolean.TRUE.equals(tier.get("feasible"))));
        assertTrue(StabilizedControlPlan.previewTiers(start, target, 10, 3, options("start / 0", "AUTO", null)).stream()
            .allMatch(tier -> "INVALID_FORMULA".equals(tier.get("errorCode"))));
    }
    @Test void adaptiveTiersStartAtOneAcrossPricesAndPrecisionsAndStillRequireAFullValidPath() {
        String[][] instruments = {{"157.575", "157.588", "10", "3"}, {"1.08456", "1.08756", "300", "5"},
            {"100000.00", "100300.00", "300", "2"}, {"0.00001000", "0.00001010", "60", "8"}, {"10000", "10100", "60", "0"}};
        for (String[] instrument : instruments) {
            BigDecimal start = new BigDecimal(instrument[0]), target = new BigDecimal(instrument[1]);
            int duration = Integer.parseInt(instrument[2]), precision = Integer.parseInt(instrument[3]);
            BigDecimal previous = BigDecimal.ZERO;
            for (Map<String,Object> tier : StabilizedControlPlan.previewTiers(start, target, duration, precision, null)) {
                assertNotEquals("AMPLITUDE_PRECISION_UNREPRESENTABLE", tier.get("errorCode"));
                assertEquals(4, tier.get("mappingVersion"));
                BigDecimal typical = new BigDecimal((String) tier.get("typicalAmount"));
                assertTrue(typical.compareTo(previous) > 0); assertDoesNotThrow(() -> typical.movePointRight(precision).toBigIntegerExact());
                previous = typical;
            }
            StabilizedControlPlan.Parameters p = parameters(start, target, duration, precision, 1, null);
            assertEquals(Boolean.TRUE, StabilizedControlPlan.feasibility(p).get("feasible"));
            for (long seed = 60; seed < 63; seed++) assertPath(p, StabilizedControlPlan.generate(p, seed));
        }
        StabilizedControlPlan.Parameters narrow = parameters(new BigDecimal("157.575"), new BigDecimal("157.588"), 10, 3, 1, options(null, "MANUAL", "0.004"));
        assertEquals(new BigDecimal("0.006"), narrow.settings.band);
        assertEquals("CORRIDOR_STEP_INFEASIBLE", assertThrows(BalancedControlPlan.Failure.class, () -> StabilizedControlPlan.generate(narrow, 60)).code);
    }
    @Test void legacyMappingAndChecksumRemainByteForByteCompatible() {
        StabilizedControlPlan.Parameters p = parameters(START, TARGET, 300, 2, 10, options(TargetControlSettings.LEGACY_FORMULA, "AUTO", null));
        StabilizedControlPlan plan = StabilizedControlPlan.generate(p, 60);
        assertEquals(3, plan.snapshot().get("mappingVersion")); assertFalse(plan.snapshot().containsKey("amplitudeMode"));
        assertEquals("c21e54d9ebadf572ff91efa563e2673f0af0248a0e6a8576702288fe838827cd", plan.checksum());
        assertEquals(plan.checksum(), StabilizedControlPlan.restore(p, plan.prices()).checksum());
    }
    @Test void twentySeedsStayInsideFixedPercentageBandAndReplayExactly() {
        StabilizedControlPlan.Parameters p = parameters(START, TARGET, 300, 2, 10, null);
        for (long seed = 60; seed < 80; seed++) {
            StabilizedControlPlan plan = StabilizedControlPlan.generate(p, seed);
            assertEquals(plan.checksum(), StabilizedControlPlan.restore(p, plan.prices()).checksum());
            assertEquals(plan.prices(), StabilizedControlPlan.generate(p, seed).prices());
            assertPath(p, plan);
        }
    }
    static void assertPath(StabilizedControlPlan.Parameters p, StabilizedControlPlan plan) {
        assertEquals(p.duration + 1, plan.prices().size());
        assertEquals(p.amount(p.start), plan.price(0, 0)); assertEquals(p.amount(p.target), plan.price(0, p.duration * 1000L));
        BigInteger last = p.start, n = BigInteger.valueOf(p.duration);
        for (int i = 1; i <= p.duration; i++) {
            BigInteger current = new BigInteger(plan.prices().get(i)), change = current.subtract(last).abs();
            assertTrue(change.compareTo(p.minStep) >= 0 && change.compareTo(p.maxStep) <= 0);
            BigInteger residual = current.subtract(p.start).multiply(n).subtract(p.delta().multiply(BigInteger.valueOf(i))).abs();
            assertTrue(residual.compareTo(p.settings.bandTicks.multiply(n)) <= 0, "corridor at " + i);
            last = current;
        }
    }
    @Test void instrumentPrecisionRoundsBandInwardAndCustomFormulaKeepsIndependentBand() {
        BigDecimal start = new BigDecimal("1.08456"), target = new BigDecimal("1.08756");
        StabilizedControlPlan.Parameters forex = parameters(start, target, 300, 5, 10, options(TargetControlSettings.LEGACY_FORMULA, "AUTO", null));
        assertEquals(new BigDecimal("0.00043"), forex.settings.band);
        assertTrue(forex.settings.band.compareTo(start.multiply(new BigDecimal("0.0004"))) <= 0);
        assertPath(forex, StabilizedControlPlan.generate(forex, 60));
        StabilizedControlPlan.Parameters custom = parameters(START, TARGET, 300, 2, 10, options("5", "MANUAL", "0.02"));
        assertEquals(new BigDecimal("20.00"), custom.settings.band);
        assertPath(custom, StabilizedControlPlan.generate(custom, 60));
    }
    @Test void invalidOrInfeasibleInputsNeverEnlargeBandOrExecuteCode() {
        for (String formula : Arrays.asList("start / 0", "-1", "0", "Math.random()", "start=10", "__import__('os')", "unknown", ""))
            assertEquals("INVALID_FORMULA", assertThrows(BalancedControlPlan.Failure.class,
                    () -> parameters(START, TARGET, 300, 2, 10, options(formula, "AUTO", null))).code);
        assertThrows(BalancedControlPlan.Failure.class, () -> parameters(START, TARGET, 300, 2, 10, options(null, "MANUAL", null)));
        assertThrows(BalancedControlPlan.Failure.class, () -> parameters(START, TARGET, 300, 2, 10, options(null, "MANUAL", "-1")));
        StabilizedControlPlan.Parameters tooNarrow = parameters(START, TARGET, 300, 2, 10, options(null, "MANUAL", "0.004"));
        assertEquals("CORRIDOR_STEP_INFEASIBLE", assertThrows(BalancedControlPlan.Failure.class, () -> StabilizedControlPlan.generate(tooNarrow, 60)).code);
        assertEquals(new BigDecimal("4.00"), tooNarrow.settings.band);
        assertEquals("CORRIDOR_PRECISION_UNREPRESENTABLE", assertThrows(BalancedControlPlan.Failure.class,
                () -> parameters(new BigDecimal("0.00001"), new BigDecimal("0.00002"), 300, 8, 10, options(null, "MANUAL", "0.004"))).code);
        StabilizedControlPlan.Parameters p = parameters(START, TARGET, 300, 2, 10, null);
        List<String> damaged = new ArrayList<>(StabilizedControlPlan.generate(p, 60).prices()); damaged.set(1, p.start.add(BigInteger.valueOf(100000)).toString());
        assertEquals("PLAN_CORRUPTED", assertThrows(BalancedControlPlan.Failure.class, () -> StabilizedControlPlan.restore(p, damaged)).code);
    }
    @Test void longSearchIsBoundedAndEveryAcceptedPathMeetsTheSameBand() {
        for (int duration : new int[] {3600, 86400}) {
            StabilizedControlPlan.Parameters p = parameters(START, START, duration, 2, 10, null);
            long began = System.nanoTime();
            assertPath(p, StabilizedControlPlan.generate(p, 60));
            assertTrue(System.nanoTime() - began < 10_000_000_000L, "bounded search");
        }
    }
}
