package com.gtcfesk.exchange.common;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Human-facing label; the provider symbol remains the identifier for quotes and orders. */
public final class ForexDisplayName {
    private static final Pattern PAIR = Pattern.compile("\\b([A-Z]{3})\\s*/\\s*([A-Z]{3})\\b");
    private static final Set<String> CURRENCIES = new HashSet<>(Arrays.asList(
        "USD", "EUR", "GBP", "JPY", "CHF", "AUD", "NZD", "CAD", "HKD", "CNY", "SGD",
        "SEK", "NOK", "DKK", "MXN", "ZAR", "TRY", "INR", "KRW", "TWD", "THB", "RUB",
        "BRL", "PLN", "CZK", "HUF", "ILS", "AED", "SAR", "IDR", "MYR", "PHP"
    ));

    private ForexDisplayName() {}

    public static String of(String symbol) {
        return of(symbol, null, null, null, null);
    }

    public static String of(String symbol, String category, String base, String quote, String name) {
        if (symbol == null) return "";
        String upper = symbol.toUpperCase(Locale.ROOT);
        boolean yahooForex = upper.endsWith("=X");
        String code = yahooForex ? upper.substring(0, upper.length() - 2) : upper;
        boolean fiatPair = code.matches("[A-Z]{6}")
            && CURRENCIES.contains(code.substring(0, 3)) && CURRENCIES.contains(code.substring(3));
        if (!yahooForex && !"Forex".equalsIgnoreCase(category) && !(category == null && fiatPair)) return symbol;
        if (yahooForex && code.matches("[A-Z]{3}") && !"USD".equals(code)) return "USD/" + code;
        if (code.matches("[A-Z]{6}")) return code.substring(0, 3) + "/" + code.substring(3);
        if (name != null) {
            Matcher match = PAIR.matcher(name.toUpperCase(Locale.ROOT));
            if (match.find()) return match.group(1) + "/" + match.group(2);
        }
        if (base != null && quote != null && base.matches("(?i)[A-Z]{3}") && quote.matches("(?i)[A-Z]{3}") && !base.equalsIgnoreCase(quote))
            return base.toUpperCase(Locale.ROOT) + "/" + quote.toUpperCase(Locale.ROOT);
        return code;
    }
}
