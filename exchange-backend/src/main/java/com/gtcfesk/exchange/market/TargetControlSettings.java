package com.gtcfesk.exchange.market;

import java.math.*;
import java.util.*;
import static com.gtcfesk.exchange.market.BalancedControlPlan.Failure;

/** Freeze formula output and a start-price percentage corridor exactly once per target task. */
public final class TargetControlSettings {
    public static final String DEFAULT_FORMULA = "start * 0.00001 * intensity";
    public static final BigDecimal BAND_PERCENT_PER_INTENSITY = new BigDecimal("0.004");
    public final String formula, bandMode;
    public final BigDecimal typical, band, bandPercent, basisPrice;
    public final BigDecimal lowerFactor = new BigDecimal("0.9"), upperFactor = new BigDecimal("1.1");
    public final BigInteger bandTicks, low = BigInteger.valueOf(97), high = BigInteger.valueOf(103);
    public final int balancePercent = 3, feedbackPercent = 5, shortWindow = 10, longWindow = 30, searchRounds = 32, searchSeconds = 5;

    public TargetControlSettings(BigDecimal start, BigDecimal target, int duration, int precision, int intensity, TargetControlOptions options) {
        if (start == null || target == null || start.signum() <= 0 || target.signum() <= 0 || start.compareTo(BigDecimal.TEN.pow(16)) >= 0
                || target.compareTo(BigDecimal.TEN.pow(16)) >= 0 || precision < 0 || precision > 8 || duration < 1 || duration > 86400 || intensity < 1 || intensity > 10)
            throw new Failure("INVALID_PARAMETERS", "控盘参数无效");
        this.basisPrice = start;
        formula = options == null || options.getStepFormula() == null ? DEFAULT_FORMULA : options.getStepFormula().trim();
        bandMode = options == null ? "AUTO" : options.getDeviationBandMode();
        if (!"AUTO".equals(bandMode) && !"MANUAL".equals(bandMode)) throw new Failure("INVALID_PARAMETERS", "偏差带模式必须为AUTO或MANUAL");
        BigDecimal supplied = options == null ? null : options.getDeviationBandPercent();
        if (supplied != null && (supplied.signum() <= 0 || supplied.compareTo(new BigDecimal("100")) > 0 || supplied.stripTrailingZeros().scale() > 8))
            throw new Failure("INVALID_PARAMETERS", "偏差带百分比须大于0且不超过100，最多8位小数");
        if ("MANUAL".equals(bandMode) && supplied == null) throw new Failure("INVALID_PARAMETERS", "手动偏差带需要填写百分比");
        bandPercent = ("AUTO".equals(bandMode) ? BAND_PERCENT_PER_INTENSITY.multiply(BigDecimal.valueOf(intensity)) : supplied).stripTrailingZeros();
        // Round inward to the instrument tick; never enlarge the requested percentage corridor.
        band = start.multiply(bandPercent).movePointLeft(2).setScale(precision, RoundingMode.FLOOR);
        bandTicks = band.movePointRight(precision).toBigIntegerExact();
        if (bandTicks.signum() <= 0) throw new Failure("CORRIDOR_PRECISION_UNREPRESENTABLE", "偏差带小于品种最小跳动，无法生成；不会自动扩大");
        Map<String, BigDecimal> variables = new LinkedHashMap<>();
        variables.put("start", start); variables.put("target", target); variables.put("gap", target.subtract(start).abs());
        variables.put("duration", BigDecimal.valueOf(duration)); variables.put("intensity", BigDecimal.valueOf(intensity));
        variables.put("tick", BigDecimal.ONE.movePointLeft(precision));
        try { typical = evaluate(formula, variables); }
        catch (IllegalArgumentException invalid) { throw new Failure("INVALID_FORMULA", "单步幅度" + invalid.getMessage()); }
        if (typical.signum() <= 0 || typical.compareTo(BigDecimal.TEN.pow(16)) >= 0)
            throw new Failure("INVALID_FORMULA", "单步典型幅度必须大于0且小于10^16，单位为价格/秒");
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("stepFormula", formula); values.put("deviationBandMode", bandMode);
        values.put("deviationBandPercent", bandPercent.toPlainString()); values.put("bandBasisPrice", basisPrice.toPlainString());
        values.put("typicalAmount", typical.stripTrailingZeros().toPlainString());
        values.put("lowerFactor", lowerFactor.toPlainString()); values.put("upperFactor", upperFactor.toPlainString());
        values.put("corridorAmount", band.toPlainString()); values.put("corridorTicks", bandTicks.toString());
        values.put("balancePercent", balancePercent); values.put("feedbackPercent", feedbackPercent);
        values.put("shortWindow", shortWindow); values.put("longWindow", longWindow);
        values.put("searchRounds", searchRounds); values.put("searchSeconds", searchSeconds);
        return values;
    }

    static Map<String, Object> identity(Map<String, Object> snapshot) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("stepFormula", snapshot.get("stepFormula")); identity.put("deviationBandMode", snapshot.get("deviationBandMode"));
        if ("MANUAL".equals(snapshot.get("deviationBandMode"))) identity.put("deviationBandPercent", snapshot.get("deviationBandPercent"));
        return identity;
    }
    static Map<String, Object> identity(TargetControlOptions options) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("stepFormula", options.getStepFormula() == null ? DEFAULT_FORMULA : options.getStepFormula().trim());
        identity.put("deviationBandMode", options.getDeviationBandMode());
        if ("MANUAL".equals(options.getDeviationBandMode())) identity.put("deviationBandPercent", options.getDeviationBandPercent() == null ? null : options.getDeviationBandPercent().stripTrailingZeros().toPlainString());
        return identity;
    }
    public static BigDecimal evaluate(String formula, Map<String, BigDecimal> variables) {
        if (formula == null || formula.trim().isEmpty() || formula.length() > 256)
            throw new IllegalArgumentException("公式长度必须为1–256字符");
        Parser parser = new Parser(formula.replace('×', '*').replace('÷', '/').replace('−', '-'), variables);
        BigDecimal result = parser.expression(); parser.space();
        if (parser.at != formula.length()) throw parser.error("不支持的符号");
        return result;
    }

    // ponytail: small arithmetic whitelist, not a programming language. Division uses DECIMAL128.
    private static final class Parser {
        final String text; final Map<String, BigDecimal> variables;
        int at, depth, operations;
        Parser(String text, Map<String, BigDecimal> variables) { this.text = text; this.variables = variables; }
        IllegalArgumentException error(String message) { return new IllegalArgumentException("公式第" + (at + 1) + "字符：" + message); }
        void space() { while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++; }
        boolean take(char symbol) { space(); if (at < text.length() && text.charAt(at) == symbol) { at++; return true; } return false; }
        BigDecimal bounded(BigDecimal number) {
            if (++operations > 128 || number.precision() > 128 || Math.abs(number.scale()) > 128
                    || number.abs().compareTo(BigDecimal.TEN.pow(40)) > 0) throw error("计算复杂度或数值过大");
            return number;
        }
        BigDecimal expression() {
            BigDecimal value = term();
            while (true) {
                if (take('+')) value = bounded(value.add(term()));
                else if (take('-')) value = bounded(value.subtract(term()));
                else return value;
            }
        }
        BigDecimal term() {
            BigDecimal value = atom();
            while (true) {
                if (take('*')) value = bounded(value.multiply(atom()));
                else if (take('/')) {
                    BigDecimal divisor = atom(); if (divisor.signum() == 0) throw error("不能除以0");
                    value = bounded(value.divide(divisor, MathContext.DECIMAL128));
                } else return value;
            }
        }
        BigDecimal atom() {
            if (++depth > 24) throw error("嵌套超过24层");
            try {
                if (take('+')) return atom();
                if (take('-')) return bounded(atom().negate());
                if (take('(')) { BigDecimal value = expression(); if (!take(')')) throw error("缺少右括号"); return value; }
                space(); int begin = at;
                while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) at++;
                if (at > begin) {
                    String number = text.substring(begin, at);
                    if (!number.matches("[0-9]+(?:\\.[0-9]+)?")) throw error("使用普通十进制数，例如0.00001");
                    return bounded(new BigDecimal(number));
                }
                while (at < text.length() && Character.isLetter(text.charAt(at))) at++;
                String name = text.substring(begin, at);
                if (variables.containsKey(name)) return variables.get(name);
                if (Arrays.asList("abs", "min", "max").contains(name) && take('(')) {
                    BigDecimal first = expression(), result;
                    if (name.equals("abs")) result = first.abs();
                    else { if (!take(',')) throw error("min/max需要两个参数"); BigDecimal second = expression(); result = name.equals("min") ? first.min(second) : first.max(second); }
                    if (!take(')')) throw error("缺少函数右括号"); return bounded(result);
                }
                throw error("未知变量或函数；允许start/target/gap/duration/intensity/tick及abs/min/max");
            } finally { depth--; }
        }
    }
}
