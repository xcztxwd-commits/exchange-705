package com.gtcfesk.exchange.market;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A complete, immutable V3 target path. All monetary checks use integer price ticks. */
public final class BalancedControlPlan {
    public static final int VERSION = 3;
    public static final BigDecimal DEFAULT_RATIO = new BigDecimal("0.00001");
    private static final BigInteger HUNDRED = BigInteger.valueOf(100), LOW = BigInteger.valueOf(97), HIGH = BigInteger.valueOf(103);
    private final Parameters parameters;
    private final String[] prices;
    private final Map<String, Object> summary;

    public static final class Failure extends RuntimeException {
        public final String code;
        Failure(String code, String message) { super(message); this.code = code; }
    }

    public static final class Parameters {
        public final BigInteger start, target, minStep, maxStep, priceLimit;
        public final int duration, precision, intensity;
        public final BigDecimal ratio;
        public Parameters(BigDecimal start, BigDecimal target, int duration, int precision, int intensity, BigDecimal ratio) {
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
            this.duration = duration; this.precision = precision; this.intensity = intensity; this.ratio = ratio;
            BigDecimal typical = start.multiply(ratio).multiply(BigDecimal.valueOf(intensity)).movePointRight(precision);
            minStep = typical.multiply(new BigDecimal("0.9")).setScale(0, RoundingMode.CEILING).toBigIntegerExact();
            maxStep = typical.multiply(new BigDecimal("1.1")).setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
            if (maxStep.signum() < 1 || minStep.compareTo(maxStep) > 0)
                throw new Failure("AMPLITUDE_PRECISION_UNREPRESENTABLE", "该价格精度无法表示此强度的单秒幅度");
        }
        public BigDecimal amount(BigInteger ticks) { return new BigDecimal(ticks).movePointLeft(precision); }
        public BigInteger delta() { return target.subtract(start); }
        public Map<String, Object> snapshot() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("algorithmVersion", VERSION); m.put("mappingVersion", 1); m.put("schemaVersion", 1);
            m.put("start", start.toString()); m.put("target", target.toString()); m.put("duration", duration);
            m.put("precision", precision); m.put("intensity", intensity); m.put("ratio", ratio.toPlainString());
            m.put("minStep", minStep.toString()); m.put("maxStep", maxStep.toString()); m.put("balancePercent", 3); m.put("feedbackPercent", 5);
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
            throw new Failure("TARGET_AMPLITUDE_INFEASIBLE", "目标、时长与固定幅度不兼容，无法同时保持双向和均衡");
        int first = firstTick.intValueExact(), last = lastTick.intValueExact();
        Counts best = null; BigInteger bestScore = null;
        for (int up = first; up <= last; up++) {
            int down = p.duration - up;
            BigInteger u = BigInteger.valueOf(up), d = BigInteger.valueOf(down);
            BigInteger lower = max(u.multiply(p.minStep).shiftLeft(1).subtract(delta), d.multiply(p.minStep).shiftLeft(1).add(delta),
                    ceil(LOW.multiply(n).multiply(sum), BigInteger.valueOf(200)));
            BigInteger upper = min(u.multiply(p.maxStep).shiftLeft(1).subtract(delta), d.multiply(p.maxStep).shiftLeft(1).add(delta),
                    floor(HIGH.multiply(n).multiply(sum), BigInteger.valueOf(200)));
            BigInteger variation = parityAtLeast(n.multiply(sum).divide(BigInteger.valueOf(2)).max(lower), delta);
            if (variation.compareTo(upper) > 0) {
                variation = parityAtLeast(lower, delta);
                if (variation.compareTo(upper) > 0) continue;
            }
            BigInteger score = BigInteger.valueOf(2L * up - p.duration).multiply(sum).subtract(delta.shiftLeft(1)).abs()
                    .add(variation.shiftLeft(1).subtract(n.multiply(sum)).abs());
            if (bestScore == null || score.compareTo(bestScore) < 0) { bestScore = score; best = new Counts(up, down, variation); }
        }
        if (best == null) throw new Failure("TARGET_AMPLITUDE_INFEASIBLE", "目标、时长与固定幅度不兼容，无法同时保持双向和均衡");
        return best;
    }
    public static Map<String, Object> feasibility(Parameters p) {
        Map<String, Object> result = new LinkedHashMap<>(p.snapshot());
        result.put("minAmount", p.amount(p.minStep).toPlainString()); result.put("maxAmount", p.amount(p.maxStep).toPlainString());
        result.put("amountRandom", !p.minStep.equals(p.maxStep));
        try { Counts c = counts(p); result.put("feasible", true); result.put("upSteps", c.up); result.put("downSteps", c.down); }
        catch (Failure failure) { result.put("feasible", false); result.put("errorCode", failure.code); result.put("message", failure.getMessage()); }
        return result;
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
    private static ArrayList<BigInteger> pool(int count, BigInteger sum, BigInteger low, BigInteger high, SplittableRandom random) {
        BigInteger[] qr = sum.divideAndRemainder(BigInteger.valueOf(count));
        ArrayList<BigInteger> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(qr[0].add(i < qr[1].intValue() ? BigInteger.ONE : BigInteger.ZERO));
        for (int i = count - 1; i > 0; i--) Collections.swap(values, i, random.nextInt(i + 1));
        for (int k = 0; k < count * 3; k++) {
            int from = random.nextInt(count), to = random.nextInt(count);
            if (from == to) continue;
            BigInteger capacity = values.get(from).subtract(low).min(high.subtract(values.get(to)));
            if (capacity.signum() < 1) continue;
            BigInteger transfer = randomBelow(capacity, random).add(BigInteger.ONE);
            values.set(from, values.get(from).subtract(transfer)); values.set(to, values.get(to).add(transfer));
        }
        return values;
    }
    private static boolean within(BigInteger window, int width, BigInteger total, int duration) {
        BigInteger actual = window.multiply(HUNDRED).multiply(BigInteger.valueOf(duration));
        BigInteger expected = total.multiply(BigInteger.valueOf(width));
        return actual.compareTo(expected.multiply(LOW)) >= 0 && actual.compareTo(expected.multiply(HIGH)) <= 0;
    }
    private static String[] order(Parameters p, Counts c, SplittableRandom random) {
        ArrayList<BigInteger> up = pool(c.up, c.variation.add(p.delta()).divide(BigInteger.valueOf(2)), p.minStep, p.maxStep, random);
        ArrayList<BigInteger> down = pool(c.down, c.variation.subtract(p.delta()).divide(BigInteger.valueOf(2)), p.minStep, p.maxStep, random);
        String[] prices = new String[p.duration + 1]; prices[0] = p.start.toString();
        BigInteger[] prefix = new BigInteger[p.duration + 1]; prefix[0] = BigInteger.ZERO;
        BigInteger current = p.start;
        int edge1 = p.duration / 3, edge2 = p.duration * 2 / 3;
        for (int t = 0; t < p.duration; t++) {
            int left = p.duration - t;
            BigInteger error = current.subtract(p.start).multiply(BigInteger.valueOf(p.duration))
                    .subtract(p.delta().multiply(BigInteger.valueOf(t)));
            double normalized = error.doubleValue() / Math.max(1, p.minStep.add(p.maxStep).doubleValue() * 5 * p.duration);
            double correction = .05 * Math.max(-1, Math.min(1, normalized));
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
                    BigInteger cumulative = prefix[t].add(magnitude);
                    boolean valid = true;
                    for (int width : new int[] {10, 30}) if (t + 1 >= width && !within(cumulative.subtract(prefix[t + 1 - width]), width, c.variation, p.duration)) valid = false;
                    if (t + 1 == edge1 && edge1 > 0 && !within(cumulative, edge1, c.variation, p.duration)) valid = false;
                    if (t + 1 == edge2 && edge2 > edge1 && !within(cumulative.subtract(prefix[edge1]), edge2 - edge1, c.variation, p.duration)) valid = false;
                    if (t + 1 == p.duration && !within(cumulative.subtract(prefix[edge2]), p.duration - edge2, c.variation, p.duration)) valid = false;
                    if (!valid) continue;
                    double score = random.nextDouble() + (direction == 0 ? 0 : .1);
                    if (score < bestScore) { bestScore = score; chosen = magnitude; chosenIndex = index; chosenUp = rising; }
                }
            }
            if (chosen == null) return null;
            ArrayList<BigInteger> selected = chosenUp ? up : down;
            selected.set(chosenIndex, selected.get(selected.size() - 1)); selected.remove(selected.size() - 1);
            current = current.add(chosenUp ? chosen : chosen.negate());
            prefix[t + 1] = prefix[t].add(chosen); prices[t + 1] = current.toString();
        }
        return prices;
    }
    public static BalancedControlPlan generate(Parameters p, long seed) {
        Counts c = counts(p);
        long deadline = System.nanoTime() + 5_000_000_000L;
        SplittableRandom random = new SplittableRandom(seed);
        for (int attempt = 0; attempt < 32 && System.nanoTime() < deadline; attempt++) {
            String[] candidate = order(p, c, random.split());
            if (candidate == null) continue;
            try { return new BalancedControlPlan(p, candidate); }
            catch (Failure rejected) { /* Independently checked; try another pool/order. */ }
        }
        throw new Failure("PLAN_SEARCH_EXHAUSTED", "计算预算内未找到满足窗口均衡和正价格的轨迹");
    }
    private BalancedControlPlan(Parameters p, String[] prices) { this.parameters = p; this.prices = prices.clone(); this.summary = Collections.unmodifiableMap(validate(p, this.prices)); }
    public static BalancedControlPlan restore(Parameters p, List<String> prices) { return new BalancedControlPlan(p, prices.toArray(new String[0])); }
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
        BigInteger highest = p.start, lowest = p.start, biggest = BigInteger.ZERO;
        int rising = 0, falling = 0;
        BigInteger previous = p.start;
        for (int i = 1; i <= p.duration; i++) {
            BigInteger current;
            try { current = new BigInteger(prices[i]); } catch (RuntimeException invalid) { throw new Failure("PLAN_CORRUPTED", "计划价格刻度无效"); }
            BigInteger change = current.subtract(previous), magnitude = change.abs();
            if (current.signum() <= 0 || current.compareTo(p.priceLimit) >= 0
                    || magnitude.compareTo(p.minStep) < 0 || magnitude.compareTo(p.maxStep) > 0)
                throw new Failure("PLAN_CORRUPTED", "计划含越界价格或单秒幅度");
            if (change.signum() > 0) rising++; else falling++;
            prefix[i] = prefix[i - 1].add(magnitude);
            highest = highest.max(current); lowest = lowest.min(current); biggest = biggest.max(magnitude); previous = current;
        }
        if (rising == 0 || falling == 0) throw new Failure("PLAN_CORRUPTED", "计划缺少双向波动");
        BigInteger total = prefix[p.duration], centerTwice = p.minStep.add(p.maxStep).multiply(BigInteger.valueOf(p.duration));
        if (total.multiply(BigInteger.valueOf(200)).compareTo(centerTwice.multiply(LOW)) < 0
                || total.multiply(BigInteger.valueOf(200)).compareTo(centerTwice.multiply(HIGH)) > 0)
            throw new Failure("PLAN_CORRUPTED", "全段平均幅度不均衡");
        Map<String, Object> windows = new LinkedHashMap<>();
        for (int width : new int[] {10, 30}) {
            BigInteger windowMin = null, windowMax = null;
            for (int i = width; i <= p.duration; i++) {
                BigInteger value = prefix[i].subtract(prefix[i - width]);
                if (!within(value, width, total, p.duration)) throw new Failure("PLAN_CORRUPTED", "滚动窗口幅度不均衡");
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
                if (!within(value, edges[i + 1] - edges[i], total, p.duration))
                    throw new Failure("PLAN_CORRUPTED", "前中后三段幅度不均衡");
                segments.add(average(p, value, edges[i + 1] - edges[i]));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("points", prices.length); result.put("startPrice", p.amount(p.start).toPlainString()); result.put("targetPrice", p.amount(p.target).toPlainString());
        result.put("minPrice", p.amount(lowest).toPlainString()); result.put("maxPrice", p.amount(highest).toPlainString()); result.put("maxStep", p.amount(biggest).toPlainString());
        result.put("averageStep", p.amount(total).divide(BigDecimal.valueOf(p.duration), 8, RoundingMode.HALF_UP).toPlainString());
        result.put("upSteps", rising); result.put("downSteps", falling); result.put("balancePercent", 3);
        result.put("segmentAverages", segments); result.putAll(windows);
        return result;
    }
    private static String average(Parameters p, BigInteger ticks, int width) {
        return p.amount(ticks).divide(BigDecimal.valueOf(width), 8, RoundingMode.HALF_UP).toPlainString();
    }
}
