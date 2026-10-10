package com.gtcfesk.exchange.market;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Candle periods are case-sensitive: 1m is a minute; the retired 1M is a month. */
public final class KlineIntervals {
    private KlineIntervals() { }
    public static final List<String> PUBLIC = Collections.unmodifiableList(
        Arrays.asList("1m", "5m", "15m", "30m", "1h", "1d", "1w"));

    public static boolean retired(String interval) {
        if (interval == null) return false;
        String value = interval.trim();
        if ("1M".equals(value)) return true;
        return Arrays.asList("m", "mo", "1mo", "month", "1month", "monthly", "月", "月线", "1月", "10", "type10", "type=10")
            .contains(value.toLowerCase(Locale.ROOT));
    }

    public static void rejectRetired(String interval) {
        if (retired(interval)) throw new IllegalArgumentException("行情月线已退役");
    }
}
