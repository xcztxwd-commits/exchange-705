package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BalancedControlPlanTest {
    private BalancedControlPlan.Parameters p(String start, String target, int seconds, int precision, int intensity) {
        return new BalancedControlPlan.Parameters(new BigDecimal(start), new BigDecimal(target), seconds, precision, intensity,
                BalancedControlPlan.DEFAULT_RATIO);
    }
    @Test void benchmarkAndReproducibility() {
        BalancedControlPlan.Parameters p = p("100000", "100060", 60, 2, 10);
        assertEquals("900", p.minStep.toString()); assertEquals("1100", p.maxStep.toString());
        BalancedControlPlan a = BalancedControlPlan.generate(p, 42), b = BalancedControlPlan.generate(p, 42);
        assertEquals(a.prices(), b.prices()); assertEquals("100060.00", a.price(1000, 61000).toPlainString());
        assertEquals("100000.00", a.price(1000, 1999).toPlainString());
        assertEquals(61, a.prices().size()); assertEquals(a.summary(), BalancedControlPlan.validate(p, a.prices().toArray(new String[0])));
        assertNotEquals(a.checksum(), BalancedControlPlan.generate(p, 43).checksum());
    }
    @Test void downwardAndSameTarget() {
        for (String target : new String[] {"99940", "100000"}) {
            BalancedControlPlan.Parameters p = p("100000", target, 60, 2, 10);
            BalancedControlPlan plan = BalancedControlPlan.generate(p, 7);
            assertEquals(new BigDecimal(target).setScale(2), plan.price(0, 60000));
        }
    }
    @Test void smallModelCountsMatchExhaustiveAmounts() {
        for (int n = 2; n <= 6; n++) {
            Set<Integer> feasibleDeltas = new HashSet<>();
            enumerateAmounts(n, 0, 0, 0, 0, 0, feasibleDeltas);
            for (int delta = -n * 11; delta <= n * 11; delta++) {
                BalancedControlPlan.Parameters p = new BalancedControlPlan.Parameters(
                        BigDecimal.valueOf(1000), BigDecimal.valueOf(1000 + delta), n, 0, 1, new BigDecimal("0.01"));
                assertEquals(feasibleDeltas.contains(delta), BalancedControlPlan.feasibility(p).get("feasible"),
                        "N=" + n + " delta=" + delta);
            }
        }
    }
    private void enumerateAmounts(int n, int step, int delta, int total, int ups, int downs, Set<Integer> feasible) {
        if (step == n) {
            if (ups > 0 && downs > 0 && total * 100 >= 97 * n * 10 && total * 100 <= 103 * n * 10)
                feasible.add(delta);
            return;
        }
        for (int amount = 9; amount <= 11; amount++) {
            enumerateAmounts(n, step + 1, delta + amount, total + amount, ups + 1, downs, feasible);
            enumerateAmounts(n, step + 1, delta - amount, total + amount, ups, downs + 1, feasible);
        }
    }
    @Test void explicitFailuresAndTamperDetection() {
        assertEquals("TARGET_AMPLITUDE_INFEASIBLE", assertThrows(BalancedControlPlan.Failure.class,
                () -> BalancedControlPlan.generate(p("100000", "100000", 1, 2, 1), 1)).code);
        assertEquals("TARGET_AMPLITUDE_INFEASIBLE", assertThrows(BalancedControlPlan.Failure.class,
                () -> BalancedControlPlan.generate(p("100000", "100000", 3, 0, 1), 1)).code);
        assertEquals(3, BalancedControlPlan.generate(p("100000", "100000", 2, 0, 1), 1).prices().size());
        assertEquals("AMPLITUDE_PRECISION_UNREPRESENTABLE", assertThrows(BalancedControlPlan.Failure.class,
                () -> p("0.00000001", "0.00000002", 60, 8, 1)).code);
        BalancedControlPlan.Parameters p = p("100000", "100060", 60, 2, 10);
        List<String> prices = new ArrayList<>(BalancedControlPlan.generate(p, 42).prices());
        prices.set(60, "10006001");
        assertEquals("PLAN_CORRUPTED", assertThrows(BalancedControlPlan.Failure.class,
                () -> BalancedControlPlan.restore(p, prices)).code);
        List<String> unbalanced = new ArrayList<>();
        BigInteger current = p.start; unbalanced.add(current.toString());
        for (int step = 0; step < 60; step++) {
            boolean up = step < 10 ? step % 2 == 0 : step < 38;
            BigInteger magnitude = BigInteger.valueOf(step < 10 ? 1100 : 1000);
            current = current.add(up ? magnitude : magnitude.negate()); unbalanced.add(current.toString());
        }
        assertEquals(p.target, current);
        assertEquals("PLAN_CORRUPTED", assertThrows(BalancedControlPlan.Failure.class,
                () -> BalancedControlPlan.restore(p, unbalanced)).code);
    }
    @Test void longPlanFitsBoundedBudget() {
        BalancedControlPlan.Parameters p = p("100000", "100000", 3600, 2, 10);
        assertEquals(3601, BalancedControlPlan.generate(p, 12).prices().size());
    }
    @Test void fullDayPlanUsesExactTicks() {
        BalancedControlPlan.Parameters p = p("100000", "100000", 86400, 2, 10);
        assertEquals(86401, BalancedControlPlan.generate(p, 13).prices().size());
    }
    @Test void tickTotalsBeyondLongDoNotOverflow() {
        BalancedControlPlan.Parameters p = p("5000000000000000", "5000000000000000", 60, 8, 10);
        assertTrue(p.start.bitLength() > 63);
        BalancedControlPlan plan = BalancedControlPlan.generate(p, 91);
        assertEquals(new BigDecimal("5000000000000000.00000000"), plan.price(0, 60000));
    }
    @Test void storedPriceRangeIsNeverExceeded() {
        BalancedControlPlan.Parameters p = p("9999999999999999", "9999999999999999", 2, 8, 1);
        BigInteger middleStep = p.minStep.add(p.maxStep).divide(BigInteger.valueOf(2));
        assertTrue(p.start.add(middleStep).compareTo(p.priceLimit) >= 0);
        assertEquals("PLAN_CORRUPTED", assertThrows(BalancedControlPlan.Failure.class,
                () -> BalancedControlPlan.restore(p, java.util.Arrays.asList(p.start.toString(),
                        p.start.add(middleStep).toString(), p.start.toString()))).code);
    }
    @Test void thousandSeededPlansHaveNoInvalidSuccesses() {
        int cases = Integer.getInteger("control.v3.batch", 1000);
        int success = 0, infeasible = 0, exhausted = 0;
        double early = 0, late = 0; long observations = 0, firstBound = 0, lastBound = 0;
        for (int i = 0; i < cases; i++) {
            int seconds = i % 2 == 0 ? 60 : 300, intensity = i % 10 + 1;
            BigDecimal target = new BigDecimal("100000").add(BigDecimal.valueOf((i % 7 - 3L) * intensity));
            BalancedControlPlan.Parameters p = new BalancedControlPlan.Parameters(new BigDecimal("100000"), target,
                    seconds, 2, intensity, BalancedControlPlan.DEFAULT_RATIO);
            try {
                BalancedControlPlan plan = BalancedControlPlan.generate(p, i);
                BalancedControlPlan.validate(p, plan.prices().toArray(new String[0])); success++;
                List<String> prices = plan.prices();
                BigInteger first = new BigInteger(prices.get(1)).subtract(new BigInteger(prices.get(0))).abs();
                BigInteger last = new BigInteger(prices.get(seconds)).subtract(new BigInteger(prices.get(seconds - 1))).abs();
                if (first.equals(p.minStep) || first.equals(p.maxStep)) firstBound++;
                if (last.equals(p.minStep) || last.equals(p.maxStep)) lastBound++;
                for (int step = 1; step <= seconds / 5; step++) {
                    early += new BigInteger(prices.get(step)).subtract(new BigInteger(prices.get(step - 1))).abs().doubleValue();
                    late += new BigInteger(prices.get(seconds - step + 1)).subtract(new BigInteger(prices.get(seconds - step))).abs().doubleValue();
                    observations++;
                }
            } catch (BalancedControlPlan.Failure failure) {
                if ("TARGET_AMPLITUDE_INFEASIBLE".equals(failure.code)) infeasible++;
                else if ("PLAN_SEARCH_EXHAUSTED".equals(failure.code)) exhausted++;
                else fail("Unexpected failure " + failure.code);
            }
        }
        System.out.println("V3 " + cases + " plans: success=" + success + " infeasible=" + infeasible + " exhausted=" + exhausted
                + " earlyLateDifferencePercent=" + String.format("%.3f", 200 * Math.abs(early - late) / (early + late))
                + " firstBoundary=" + firstBound + " lastBoundary=" + lastBound + " observations=" + observations);
        assertEquals(cases, success + infeasible + exhausted);
        assertTrue(success >= cases * .99, "Neutral feasible set should meet the 99% initial target");
    }
}
