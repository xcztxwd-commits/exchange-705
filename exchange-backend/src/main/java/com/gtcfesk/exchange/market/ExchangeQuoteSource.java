package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import java.net.*;
import java.util.*;
import javax.annotation.PostConstruct;

/** Public market data only. Exactly one exchange is active; no implicit cross-exchange failover. */
@Component
public class ExchangeQuoteSource {
    @Value("${market.exchange.provider:binance}") String provider = "binance";
    @Value("${market.exchange.spot-url:https://data-api.binance.vision}") String spotUrl = "https://data-api.binance.vision";
    @Value("${market.exchange.futures-url:https://fapi.binance.com}") String futuresUrl = "https://fapi.binance.com";
    @Value("${market.exchange.okx-url:https://www.okx.com}") String okxUrl = "https://www.okx.com";
    @Autowired MarketHttp http;
    static final ObjectMapper JSON = new ObjectMapper();
    @PostConstruct void validate() { if (!Arrays.asList("binance", "okx").contains(provider)) throw new IllegalArgumentException("Invalid market.exchange.provider"); }
    String name() { return "okx".equals(provider) ? "OKX" : "Binance"; }
    static boolean supports(String category) { return category == null || "Crypto".equalsIgnoreCase(category) || perpetual(category); }
    static boolean perpetual(String category) { return "CryptoPerpetual".equalsIgnoreCase(category) || "Metal".equalsIgnoreCase(category); }
    static String symbol(String code, String category, boolean okx) {
        String value = code.toUpperCase(Locale.ROOT);
        if ("Metal".equalsIgnoreCase(category)) {
            if (!Arrays.asList("XAUUSD", "XAUUSDT", "XAGUSD", "XAGUSDT").contains(value)) throw new MarketHttp.Failure("unsupported_instrument", 0);
            return okx ? value.substring(0,3) + "-USDT-SWAP" : value.substring(0,3) + "USDT";
        }
        // Internal aliases are resolved by marketCode before this boundary. Never substitute a different asset.
        if (!okx && value.matches("[A-Z0-9]{2,32}")) return value;
        if (!value.matches("[A-Z0-9]+USDT")) throw new MarketHttp.Failure("unsupported_instrument", 0);
        return okx ? value.substring(0, value.length()-4) + "-USDT" + (perpetual(category) ? "-SWAP" : "") : value;
    }
    static String interval(String interval, boolean okx) {
        String value = interval == null ? "1m" : interval;
        if (Arrays.asList("m", "mo", "1mo").contains(value)) value = "1M";
        if ("d".equals(value)) value = "1d";
        if ("w".equals(value)) value = "1w";
        if ("60m".equals(value)) value = "1h";
        if (!Arrays.asList("1m","3m","5m","15m","30m","1h","2h","4h","6h","12h","1d","1w","1M").contains(value))
            throw new MarketHttp.Failure("unsupported_interval", 0);
        return !okx ? value : Arrays.asList("6h","12h").contains(value) ? value.toUpperCase(Locale.ROOT) + "utc" : value.endsWith("h") ? value.toUpperCase(Locale.ROOT)
            : value.endsWith("d") || value.endsWith("w") || value.endsWith("M") ? value.toUpperCase(Locale.ROOT) + "utc" : value;
    }
    private JsonNode get(String url) {
        try { JsonNode result = JSON.readTree(http.get(URI.create(url)).getBody());
            if ("okx".equals(provider)) { if (!"0".equals(result.path("code").asText())) throw new MarketHttp.Failure("provider_error", 0); return result.path("data"); }
            if (result.has("code")) throw new MarketHttp.Failure("provider_error", 0);
            return result;
        } catch (MarketHttp.Failure failure) { throw failure; }
        catch (Exception failure) { throw new MarketHttp.Failure("invalid_response", 0); }
    }
    Map<String,Map<String,Object>> prices(List<String> codes, String category) {
        boolean okx = "okx".equals(provider), perpetual = perpetual(category);
        Map<String,Map<String,Object>> result = new HashMap<>();
        // Bounded by the existing category HTTP deadline; partial valid results survive a missing instrument.
        MarketHttp.Failure lastFailure = null;
        for (String code : codes) try {
            String external = symbol(code, category, okx);
            String url = okx ? okxUrl + "/api/v5/market/ticker?instId=" + external
                : (perpetual ? futuresUrl + "/fapi/v1" : spotUrl + "/api/v3") + "/ticker/24hr?symbol=" + external;
            JsonNode row = get(url); if (okx) row = row.path(0);
            Map<String,Object> quote = ticker(row, okx, false);
            if (!external.equals(quote.get("symbol"))) throw new MarketHttp.Failure("instrument_mismatch", 0);
            quote.put("marketType", perpetual ? "perpetual" : "spot"); quote.put("quoteCurrency", "USDT");
            quote.put("source", name()); result.put(code, quote);
        } catch (MarketHttp.Failure failure) { lastFailure = failure; }
        if (result.isEmpty() && lastFailure != null) throw lastFailure;
        return result;
    }
    static Map<String,Object> ticker(JsonNode row, boolean okx, boolean ws) {
        Map<String,Object> result = new HashMap<>();
        result.put("symbol", row.path(okx ? "instId" : ws ? "s" : "symbol").asText());
        result.put("price", row.path(okx ? "last" : ws ? "c" : "lastPrice").asDouble(Double.NaN));
        result.put("timestamp", row.path(okx ? "ts" : ws ? "C" : "closeTime").asLong());
        if (!QuoteState.valid(result) || ((String) result.get("symbol")).isEmpty()) throw new MarketHttp.Failure("invalid_quote", 0);
        double open = row.path(okx ? "open24h" : ws ? "o" : "openPrice").asDouble();
        if (Double.isFinite(open) && open > 0) {
            double change = ((Number)result.get("price")).doubleValue() - open;
            result.put("change24h", change); result.put("changePct24h", change / open * 100);
        }
        result.put("changeBasis", "rolling24h");
        return result;
    }
    Map<String,Object> kline(String code, String interval, int limit, String category, Long endTime) {
        boolean okx = "okx".equals(provider), perpetual = perpetual(category);
        String external = symbol(code, category, okx), bar = interval(interval, okx);
        String url = okx ? okxUrl + "/api/v5/market/" + (endTime == null ? "candles" : "history-candles") + "?instId=" + external + "&bar=" + bar + "&limit=" + Math.min(limit,300)
            : (perpetual ? futuresUrl + "/fapi/v1" : spotUrl + "/api/v3") + "/klines?symbol=" + external + "&interval=" + bar + "&limit=" + Math.min(limit,1000);
        if (endTime != null) url += (okx ? "&after=" : "&endTime=") + endTime;
        JsonNode raw = get(url);
        if (!raw.isArray()) throw new MarketHttp.Failure("invalid_kline", 0);
        List<Map<String,Object>> rows = new ArrayList<>();
        for (JsonNode item : raw) {
            if (!item.isArray() || item.size() < 8) throw new MarketHttp.Failure("invalid_kline", 0);
            Map<String,Object> row = new HashMap<>(); row.put("timestamp", item.path(0).asLong()/1000);
            String[] keys = {"open_price","high_price","low_price","close_price","volume"};
            for (int i=0;i<keys.length;i++) row.put(keys[i], item.path(i+1).asDouble(Double.NaN));
            row.put("turnover", item.path(7).asDouble()); rows.add(row);
        }
        rows.sort(Comparator.comparingLong(row -> QuoteState.time(row.get("timestamp"))));
        Map<String,Object> result = new HashMap<>(), data = new HashMap<>();
        data.put("code", code); data.put("kline_list", rows); result.put("ret",200); result.put("msg","ok"); result.put("data",data);
        ForexQuoteMarketService.validateKline(result); return result;
    }
}
