package com.gtcfesk.exchange.market;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.gtcfesk.exchange.market.BalancedControlPlan.Failure;

/** V4 target path: fixed step scale, exact endpoint, rolling balance and a fixed percentage corridor. */
public final class StabilizedControlPlan implements TargetControlPlan {
    public static final int VERSION = 4;
    public static final BigDecimal DEFAULT_RATIO = new BigDecimal("0.00001");
    private static final BigInteger HUNDRED = BigInteger.valueOf(100);
    private final Parameters parameters;
    private final String[] prices;
    private final Map<String, Object> summary;

    public static final class Parameters {
        public final BigInteger start, target, minStep, maxStep, priceLimit;
        public final int duration, precision, intensity;
        public final BigDecimal ratio;
        public final TargetControlSettings settings;
        public Parameters(BigDecimal start, BigDecimal target, int duration, int precision, int intensity, BigDecimal ratio) {
            this(start, target, duration, precision, intensity, ratio, new TargetControlSettings(start, target, duration, precision, intensity, null));
        }
        public Parameters(BigDecimal start, BigDecimal target, int duration, int precision, int intensity, BigDecimal ratio, TargetControlSettings settings) {
            if (start == null || target == null || ratio == null || start.signum() <= 0 || target.signum() <= 0
                    || ratio.signum() <= 0 || duration < 1 || duration > 86400 || precision < 0 || precision > 8 || intensity < 1 || intensity > 10)
                throw new Failure("INVALID_PARAMETERS", "控盘参数无效");
            try {
                this.start = start.movePointRight(precision).toBigIntegerExact();
                this.target = target.movePointRight(precision).toBigIntegerExact();
            } catch (ArithmeticException invalid) { throw new Failure("INVALID_PARAMETERS", "价格超出币种精度"); }
            priceLimit = BigInteger.TEN.pow(16 + precision); // Matches the existing < 10^16 quote/task input limit.
            if (this.start.compareTo(priceLimit) >= 0 || this.target.compareTo(priceLimit) >= 0)
                throw new Failure("INVALID_PARAMETERS", "价格超出任务存储范围");
            this.duration = duration; this.precision = precision; this.intensity = intensity; this.ratio = ratio; this.settings = Objects.requireNonNull(settings);
            BigDecimal typical = settings.typical.movePointRight(precision);
            minStep = typical.multiply(settings.lowerFactor).setScale(0, RoundingMode.CEILING).toBigIntegerExact();
            maxStep = typical.multiply(settings.upperFactor).setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
            if (maxStep.signum() < 1 || minStep.compareTo(maxStep) > 0)
                throw new Failure("AMPLITUDE_PRECISION_UNREPRESENTABLE", "当前强度没有合法单秒幅度；请增加波动强度或恢复自适应默认公式后重新预览");
        }
        public BigDecimal amount(BigInteger ticks) { return new BigDecimal(ticks).movePointLeft(precision); }
        public BigInteger delta() { return target.subtract(start); }
        public Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("algorithmVersion", VERSION); m.put("mappingVersion", settings.adaptive ? 4 : 3); m.put("schemaVersion", 1);
            m.put("start", start.toString()); m.put("target", target.toString()); m.put("duration", duration);
            m.put("precision", precision); m.put("intensity", intensity); m.put("ratio", ratio.toPlainString());
            m.put("minStep", minStep.toString()); m.put("maxStep", maxStep.toString()); m.put("balancePercent", settings.balancePercent); m.put("feedbackPercent", settings.feedbackPercent); m.putAll(settings.snapshot());
            return m;
        }
    }

    private static final class Counts {
        int up, down;
        BigInteger variation;
        Counts(int up, int down, BigInteger variation) { this.up = up; this.down = down; this.variation = variation; }
    }
    private static BigInteger floor(BigInteger n, BigInteger d) {
        BigInteger[] qr = n.divideAndRemainder(d);
        return n.signum() < 0 && qr[1].signum() != 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }
    private static BigInteger ceil(BigInteger n, BigInteger d) { return floor(n.negate(), d).negate(); }
    private static BigInteger max(BigInteger... values) { BigInteger result = values[0]; for (BigInteger v : values) result = result.max(v); return result; }
    private static BigInteger min(BigInteger... values) { BigInteger result = values[0]; for (BigInteger v : values) result = result.min(v); return result; }
    private static BigInteger parityAtLeast(BigInteger value, BigInteger parity) {
        return value.testBit(0) == parity.testBit(0) ? value : value.add(BigInteger.ONE);
    }
    private static Counts counts(Parameters p) {
        BigInteger n = BigInteger.valueOf(p.duration), delta = p.delta(), sum = p.minStep.add(p.maxStep);
        BigInteger firstTick = max(BigInteger.ONE, ceil(delta.add(n.multiply(p.minStep)), sum));
        BigInteger lastTick = min(n.subtract(BigInteger.ONE), floor(delta.add(n.multiply(p.maxStep)), sum));
        if (firstTick.compareTo(lastTick) > 0)
            throw new Failure("TARGET_AMPLITUDE_INFEASIBLE", "目标价差、时长与当前幅度不兼容，无法保持涨跌和均衡；请增加执行时间或调整波动强度后重新预览");
        int first = firstTick.intValueExact(), last = lastTick.intValueExact();
        Counts best = null; BigInteger bestScore = null;
        for (int up = first; up <= last; up++) {
            int down = p.duration - up;
            BigInteger u = BigInteger.valueOf(up), d = BigInteger.valueOf(down);
            BigInteger lower = max(u.multiply(p.minStep).shiftLeft(1).subtract(delta), d.multiply(p.minStep).shiftLeft(1).add(delta),
                    ceil(p.settings.low.multiply(n).multiply(sum), BigInteger.valueOf(200)));
            BigInteger upper = min(u.multiply(p.maxStep).shiftLeft(1).subtract(delta), d.multiply(p.maxStep).shiftLeft(1).add(delta),
                    floor(p.settings.high.multiply(n).multiply(sum), BigInteger.valueOf(200)));
            BigInteger variation = parityAtLeast(n.multiply(sum).divide(BigInteger.valueOf(2)).max(lower), delta);
            if (variation.compareTo(upper) > 0) {
                variation = parityAtLeast(lower, delta);
                if (variation.compareTo(upper) > 0) continue;
            }
            BigInteger score = BigInteger.valueOf(2L * up - p.duration).multiply(sum).subtract(delta.shiftLeft(1)).abs()
                    .add(variation.shiftLeft(1).subtract(n.multiply(sum)).abs());
            if (bestScore == null || score.compareTo(bestScore) < 0) { bestScore = score; best = new Counts(up, down, variation); }
        }
        if (best == null) throw new Failure("TARGET_AMPLITUDE_INFEASIBLE", "目标价差、时长与当前幅度不兼容，无法保持涨跌和均衡；请增加执行时间或调整波动强度后重新预览");
        return best;
    }
    public static Map<String, Object> feasibility(Parameters p) {
        Map<String, Object> result = new LinkedHashMap<>(p.snapshot());
        result.put("minAmount", p.amount(p.minStep).toPlainString()); result.put("maxAmount", p.amount(p.maxStep).toPlainString());
        result.put("amountRandom", !p.minStep.equals(p.maxStep));
        try { checkCorridor(p); Counts c = counts(p); result.put("feasible", true); result.put("upSteps", c.up); result.put("downSteps", c.down); }
        catch (Failure failure) { result.put("feasible", false); result.put("errorCode", failure.code); result.put("message", failure.getMessage()); }
        return result;
    }

    /** Fast, read-only screening; the selected tier must still pass full plan generation. */
    public static List<Map<String, Object>> previewTiers(BigDecimal start, BigDecimal target, int duration, int precision, TargetControlOptions options) {
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (int intensity = 1; intensity <= 10; intensity++) {
            Map<String, Object> tier = new LinkedHashMap<>(); tier.put("intensity", intensity);
            try {
                TargetControlSettings settings = new TargetControlSettings(start, target, duration, precision, intensity, options);
                tier.putAll(settings.snapshot());
                tier.putAll(feasibility(new Parameters(start, target, duration, precision, intensity, DEFAULT_RATIO, settings)));
            } catch (Failure failure) {
                tier.put("feasible", false); tier.put("errorCode", failure.code); tier.put("message", failure.getMessage());
            }
            tiers.add(tier);
        }
        return tiers;
    }

    private static BigInteger randomBelow(BigInteger bound, SplittableRandom random) {
        if (bound.equals(BigInteger.ONE)) return BigInteger.ZERO;
        byte[] bytes = new byte[(bound.bitLength() + 7) / 8];
        for (int i = 0; i < bytes.length; i += 8) {
            long word = random.nextLong();
            for (int j = i; j < Math.min(i + 8, bytes.length); j++) { bytes[j] = (byte) word; word >>>= 8; }
        }
        return new BigInteger(1, bytes).mod(bound);
    }
    private static ArrayList<BigInteger> pool(int count, BigInteger sum, BigInteger low, BigInteger high, SplittableRandom random, int mixingPasses) {
        BigInteger[] qr = sum.divideAndRemainder(BigInteger.valueOf(count));
        ArrayList<BigInteger> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(qr[0].add(i < qr[1].intValue() ? BigInteger.ONE : BigInteger.ZERO));
        for (int i = count - 1; i > 0; i--) Collections.swap(values, i, random.nextInt(i + 1));
        for (int k = 0; k < count * mixingPasses; k++) {
            int from = random.nextInt(count), to = random.nextInt(count);
            if (from == to) continue;
            BigInteger capacity = values.get(from).subtract(low).min(high.subtract(values.get(to)));
            if (capacity.signum() < 1) continue;
            BigInteger transfer = randomBelow(capacity, random).add(BigInteger.ONE);
            values.set(from, values.get(from).subtract(transfer)); values.set(to, values.get(to).add(transfer));
        }
        return values;
    }
    private static boolean within(BigInteger window, int width, BigInteger total, int duration, Parameters p) {
        BigInteger actual = window.multiply(HUNDRED).multiply(BigInteger.valueOf(duration));
        BigInteger expected = total.multiply(BigInteger.valueOf(width));
        return actual.compareTo(expected.multiply(p.settings.low)) >= 0 && actual.compareTo(expected.multiply(p.settings.high)) <= 0;
    }
    private static String[] order(Parameters p, Counts c, SplittableRandom random, long deadline, int mixingPasses) {
        ArrayList<BigInteger> up = pool(c.up, c.variation.add(p.delta()).divide(BigInteger.valueOf(2)), p.minStep, p.maxStep, random, mixingPasses);
        ArrayList<BigInteger> down = pool(c.down, c.variation.subtract(p.delta()).divide(BigInteger.valueOf(2)), p.minStep, p.maxStep, random, mixingPasses);
        String[] prices = new String[p.duration + 1]; prices[0] = p.start.toString();
        BigInteger[] prefix = new BigInteger[p.duration + 1]; prefix[0] = BigInteger.ZERO;
        BigInteger current = p.start;
        BigInteger[] usedAmounts = new BigInteger[p.duration];
        boolean[] usedDirections = new boolean[p.duration];
        int backtracks = 0;
        int edge1 = p.duration / 3, edge2 = p.duration * 2 / 3;
        for (int t = 0; t < p.duration; t++) {
            if ((t & 255) == 0 && System.nanoTime() >= deadline) return null;
            int left = p.duration - t;
            BigInteger error = current.subtract(p.start).multiply(BigInteger.valueOf(p.duration))
                    .subtract(p.delta().multiply(BigInteger.valueOf(t)));
            double normalized = error.doubleValue() / Math.max(1, p.minStep.add(p.maxStep).doubleValue() * 5 * p.duration);
            double correction = (p.settings.feedbackPercent / 100.0) * Math.max(-1, Math.min(1, normalized));
            double pUp = Math.max(0, Math.min(1, (double) up.size() / left - correction));
            boolean preferredUp = random.nextDouble() < pUp;
            BigInteger chosen = null; int chosenIndex = -1; boolean chosenUp = false;
            double bestScore = Double.POSITIVE_INFINITY;
            for (int direction = 0; direction < 2; direction++) {
                boolean rising = direction == 0 ? preferredUp : !preferredUp;
                ArrayList<BigInteger> options = rising ? up : down;
                if (options.isEmpty()) continue;
                int tries = Math.min(24, options.size());
                for (int trial = 0; trial < tries; trial++) {
                    int index = options.size() <= tries ? trial : random.nextInt(options.size());
                    BigInteger magnitude = options.get(index), next = current.add(rising ? magnitude : magnitude.negate());
                    if (next.signum() <= 0 || next.compareTo(p.priceLimit) >= 0) continue;
                    BigInteger residual = next.subtract(p.start).multiply(BigInteger.valueOf(p.duration))
                            .subtract(p.delta().multiply(BigInteger.valueOf(t + 1)));
                    BigInteger corridor = p.settings.bandTicks.multiply(BigInteger.valueOf(p.duration));
                    if (residual.abs().compareTo(corridor) > 0) continue;
                    BigInteger cumulative = prefix[t].add(magnitude);
                    boolean valid = true;
                    for (int width : new int[] {p.settings.shortWindow, p.settings.longWindow}) if (t + 1 >= width && !within(cumulative.subtract(prefix[t + 1 - width]), width, c.variation, p.duration, p)) valid = false;
                    if (t + 1 == edge1 && edge1 > 0 && !within(cumulative, edge1, c.variation, p.duration, p)) valid = false;
                    if (t + 1 == edge2 && edge2 > edge1 && !within(cumulative.subtract(prefix[edge1]), edge2 - edge1, c.variation, p.duration, p)) valid = false;
                    if (t + 1 == p.duration && !within(cumulative.subtract(prefix[edge2]), p.duration - edge2, c.variation, p.duration, p)) valid = false;
                    if (!valid) continue;
                    double score = random.nextDouble() + (direction == 0 ? 0 : .1);
                    if (score < bestScore) { bestScore = score; chosen = magnitude; chosenIndex = index; chosenUp = rising; }
                }
                if (chosen != null) break; // Prefer the sampled direction; fall back only when it has no legal candidate.
            }
            if (chosen == null) {
                if (t == 0 || backtracks++ >= 128 || System.nanoTime() >= deadline) return null;
                int rollback = Math.min(64, t);
                for (int undo = 0; undo < rollback; undo++) {
                    t--;
                    (usedDirections[t] ? up : down).add(usedAmounts[t]);
                }
                current = new BigInteger(prices[t]);
                t--; // The for-loop increment resumes at the restored index.
                continue;
            }
            usedAmounts[t] = chosen;
            usedDirections[t] = chosenUp;
            ArrayList<BigInteger> selected = chosenUp ? up : down;
            selected.set(chosenIndex, selected.get(selected.size() - 1)); selected.remove(selected.size() - 1);
            current = current.add(chosenUp ? chosen : chosen.negate());
            prefix[t + 1] = prefix[t].add(chosen); prices[t + 1] = current.toString();
        }
        return prices;
    }
    public static void checkCorridor(Parameters p) {
        BigInteger n = BigInteger.valueOf(p.duration), e = p.settings.bandTicks.multiply(n);
        BigInteger low = p.delta().subtract(e), high = p.delta().add(e);
        BigInteger a = p.minStep.multiply(n), b = p.maxStep.multiply(n);
        if (!(a.compareTo(high) <= 0 && b.compareTo(low) >= 0)
                && !(b.negate().compareTo(high) <= 0 && a.negate().compareTo(low) >= 0))
            throw new Failure("CORRIDOR_STEP_INFEASIBLE", "偏差带过窄，无法容纳当前波动；请增加执行时间、降低波动强度或手动放宽偏差带后重新预览，不会自动扩大手动范围");
    }
    // ponytail: two concurrent bounded searches per process; a shared work queue is unnecessary at current scale.
    private static final java.util.concurrent.Semaphore SEARCH_SLOTS = new java.util.concurrent.Semaphore(2);
    public static StabilizedControlPlan generate(Parameters p, long seed) {
        if (!SEARCH_SLOTS.tryAcquire()) throw new Failure("PLAN_COMPUTE_BUSY", "轨迹计算繁忙，请稍后重试");
        try { return generateLocked(p, seed); } finally { SEARCH_SLOTS.release(); }
    }
    private static StabilizedControlPlan generateLocked(Parameters p, long seed) {
        Counts c = counts(p);
        checkCorridor(p);
        long deadline = System.nanoTime() + p.settings.searchSeconds * 1_000_000_000L;
        SplittableRandom random = new SplittableRandom(seed);
        for (int attempt = 0; attempt < p.settings.searchRounds && System.nanoTime() < deadline; attempt++) {
            // Alternate dispersed pools with tightly balanced pools. Both retain seeded random directions,
            // exact endpoint and the same step/corridor/window constraints; never widen a failed band.
            String[] candidate = order(p, c, random.split(), deadline, attempt % 2 == 0 ? 3 : 0);
            if (candidate == null) continue;
            try { return new StabilizedControlPlan(p, candidate); }
            catch (Failure rejected) { /* Independently checked; try another pool/order. */ }
        }
        throw new Failure("PLAN_SEARCH_EXHAUSTED", "计算预算内未找到满足均衡和正价格的轨迹；请增加执行时间或调整波动强度、偏差带后重新预览");
    }
    private StabilizedControlPlan(Parameters p, String[] prices) { this.parameters = p; this.prices = prices.clone(); this.summary = Collections.unmodifiableMap(validate(p, this.prices)); }
    public static StabilizedControlPlan restore(Parameters p, List<String> prices) { return new StabilizedControlPlan(p, prices.toArray(new String[0])); }
    public int version() { return VERSION; }
    public int precision() { return parameters.precision; }
    public Map<String, Object> snapshot() { return parameters.snapshot(); }
    public Map<String, Object> preview() { return feasibility(parameters); }
    public Parameters parameters() { return parameters; }
    public List<String> prices() { return Collections.unmodifiableList(Arrays.asList(prices)); }
    public Map<String, Object> summary() { return summary; }
    public BigDecimal price(long startedAt, long now) {
        int index = (int) Math.max(0, Math.min(parameters.duration, Math.floorDiv(now - startedAt, 1000)));
        return parameters.amount(new BigInteger(prices[index]));
    }
    public String checksum() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : parameters.snapshot().values()) { digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8)); digest.update((byte) '\n'); }
            for (String value : prices) { digest.update(value.getBytes(StandardCharsets.UTF_8)); digest.update((byte) '\n'); }
            StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255)); return hex.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    /** Independent acceptance check; no generator state is trusted. */
    public static Map<String, Object> validate(Parameters p, String[] prices) {
        if (prices.length != p.duration + 1 || !p.start.toString().equals(prices[0]) || !p.target.toString().equals(prices[p.duration]))
            throw new Failure("PLAN_CORRUPTED", "计划起终点或点数不正确");
        BigInteger[] prefix = new BigInteger[p.duration + 1]; prefix[0] = BigInteger.ZERO;
        BigInteger highest = p.start, lowest = p.start, biggest = BigInteger.ZERO, peakResidual = BigInteger.ZERO;
        int rising = 0, falling = 0;
        BigInteger previous = p.start;
        for (int i = 1; i <= p.duration; i++) {
            BigInteger current;
            try { current = new BigInteger(prices[i]); } catch (RuntimeException invalid) { throw new Failure("PLAN_CORRUPTED", "计划价格刻度无效"); }
            BigInteger change = current.subtract(previous), magnitude = change.abs();
            BigInteger residual = current.subtract(p.start).multiply(BigInteger.valueOf(p.duration))
                    .subtract(p.delta().multiply(BigInteger.valueOf(i)));
            BigInteger corridor = p.settings.bandTicks.multiply(BigInteger.valueOf(p.duration));
            peakResidual = peakResidual.max(residual.abs());
            if (residual.abs().compareTo(corridor) > 0)
                throw new Failure("PLAN_CORRUPTED", "计划偏离参考路径超出固定偏差带");
            if (current.signum() <= 0 || current.compareTo(p.priceLimit) >= 0
                    || magnitude.compareTo(p.minStep) < 0 || magnitude.compareTo(p.maxStep) > 0)
                throw new Failure("PLAN_CORRUPTED", "计划含越界价格或单秒幅度");
            if (change.signum() > 0) rising++; else falling++;
            prefix[i] = prefix[i - 1].add(magnitude);
            highest = highest.max(current); lowest = lowest.min(current); biggest = biggest.max(magnitude); previous = current;
        }
        if (rising == 0 || falling == 0) throw new Failure("PLAN_CORRUPTED", "计划缺少双向波动");
        BigInteger total = prefix[p.duration], centerTwice = p.minStep.add(p.maxStep).multiply(BigInteger.valueOf(p.duration));
        if (total.multiply(BigInteger.valueOf(200)).compareTo(centerTwice.multiply(p.settings.low)) < 0
                || total.multiply(BigInteger.valueOf(200)).compareTo(centerTwice.multiply(p.settings.high)) > 0)
            throw new Failure("PLAN_CORRUPTED", "全段平均幅度不均衡");
        Map<String, Object> windows = new LinkedHashMap<>();
        for (int width : new int[] {p.settings.shortWindow, p.settings.longWindow}) {
            BigInteger windowMin = null, windowMax = null;
            for (int i = width; i <= p.duration; i++) {
                BigInteger value = prefix[i].subtract(prefix[i - width]);
                if (!within(value, width, total, p.duration, p)) throw new Failure("PLAN_CORRUPTED", "滚动窗口幅度不均衡");
                windowMin = windowMin == null ? value : windowMin.min(value);
                windowMax = windowMax == null ? value : windowMax.max(value);
            }
            if (windowMin != null) {
                windows.put("rolling" + width + "Min", average(p, windowMin, width));
                windows.put("rolling" + width + "Max", average(p, windowMax, width));
            }
        }
        List<String> segments = new ArrayList<>();
        if (p.duration >= 3) {
            int[] edges = {0, p.duration / 3, p.duration * 2 / 3, p.duration};
            for (int i = 0; i < 3; i++) {
                BigInteger value = prefix[edges[i + 1]].subtract(prefix[edges[i]]);
                if (!within(value, edges[i + 1] - edges[i], total, p.duration, p))
                    throw new Failure("PLAN_CORRUPTED", "前中后三段幅度不均衡");
                segments.add(average(p, value, edges[i + 1] - edges[i]));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("points", prices.length); result.put("startPrice", p.amount(p.start).toPlainString()); result.put("targetPrice", p.amount(p.target).toPlainString());
        result.put("minPrice", p.amount(lowest).toPlainString()); result.put("maxPrice", p.amount(highest).toPlainString()); result.put("maxStep", p.amount(biggest).toPlainString());
        result.put("averageStep", p.amount(total).divide(BigDecimal.valueOf(p.duration), 8, RoundingMode.HALF_UP).toPlainString());
        result.put("upSteps", rising); result.put("downSteps", falling); result.put("balancePercent", p.settings.balancePercent);
        result.put("maxDeviation", p.amount(peakResidual).divide(BigDecimal.valueOf(p.duration), p.precision + 8, RoundingMode.HALF_UP).toPlainString());
        result.put("corridorAmount", p.settings.band.toPlainString()); result.put("deviationBandPercent", p.settings.bandPercent.toPlainString());
        result.put("segmentAverages", segments); result.putAll(windows);
        return result;
    }
    private static String average(Parameters p, BigInteger ticks, int width) {
        return p.amount(ticks).divide(BigDecimal.valueOf(width), 8, RoundingMode.HALF_UP).toPlainString();
    }
}
