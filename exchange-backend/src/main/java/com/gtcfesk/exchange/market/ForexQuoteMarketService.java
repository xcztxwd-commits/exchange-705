package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/** One bounded lane per provider/category. Request threads only read snapshots or enqueue K-line work. */
@Service
public class ForexQuoteMarketService {
    private static final Logger log = LoggerFactory.getLogger(ForexQuoteMarketService.class);
    @Autowired private MarketQuoteSource source;
    @Autowired private MarketHttp http;
    @Autowired private RedisMarketService redis;
    @Autowired private TradingSymbolRepository symbols;
    @Value("${market.quote.max-age-ms:15000}") private long maxAgeMs = 15000;
    @Value("${market.quote.poll-ms:3000}") private long pollMs = 3000;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private volatile Map<String, TradingSymbol> registry = Collections.emptyMap();
    private final ScheduledExecutorService metadata = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-symbols"));
    private static final int MAX_KLINES = 128, MAX_PENDING = 32;
    private static class KlineRequest {
        final String code, interval, key;
        final int limit;
        final Long endTime;
        KlineRequest(String code, String interval, int limit) {
            this(code, interval, limit, null);
        }
        KlineRequest(String code, String interval, int limit, Long endTime) {
            this.code = code; this.interval = interval; this.limit = limit;
            this.endTime = endTime;
            this.key = code + ":" + interval + ":" + limit + (endTime == null ? "" : ":" + endTime);
        }
    }
    private static class Group {
        final String category;
        final ScheduledThreadPoolExecutor executor;
        volatile List<String> codes = Collections.emptyList();
        final Map<String, Map<String, Object>> quotes = new ConcurrentHashMap<>();
        final LinkedHashMap<String, KlineRequest> pending = new LinkedHashMap<>();
        final LinkedHashMap<String, Map<String, Object>> klines = new LinkedHashMap<String, Map<String, Object>>(16, .75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> e) { return size() > MAX_KLINES; }
        };
        volatile String activeKey;
        volatile long nextAllowed, nextQuotes, lastLog;
        volatile int failures;
        volatile String error;
        volatile int klineFailures;
        volatile long nextKlines, lastKlineLog;
        volatile String klineError;
        Group(String category) {
            this.category = category;
            executor = new ScheduledThreadPoolExecutor(1, r -> new Thread(r, "market-" + category));
            executor.setRemoveOnCancelPolicy(true);
        }
    }
    public ForexQuoteMarketService() {
        for (String category : Arrays.asList("Crypto", "Metal", "Forex", "US", "CFD", "Oil", "Other"))
            groups.put(category, new Group(category));
    }
    private Group group(String category) {
        for (Group group : groups.values()) if (group.category.equalsIgnoreCase(category)) return group;
        return groups.get(category == null ? "Crypto" : "Other");
    }
    public static String marketCode(TradingSymbol symbol) {
        return symbol.getAlltickSymbol() == null || symbol.getAlltickSymbol().isEmpty() ? symbol.getSymbol() : symbol.getAlltickSymbol();
    }
    @PostConstruct public void start() {
        metadata.scheduleWithFixedDelay(this::refreshSymbols, 0, 30, TimeUnit.SECONDS);
        for (Group group : groups.values())
            group.executor.scheduleWithFixedDelay(() -> tick(group), 0, 100, TimeUnit.MILLISECONDS);
    }
    void refreshSymbols() {
        try {
            Map<String, TradingSymbol> updated = new HashMap<>();
            Map<Group, Set<String>> codes = new HashMap<>();
            for (TradingSymbol symbol : symbols.findAll()) {
                updated.put(symbol.getSymbol(), symbol);
                updated.putIfAbsent(marketCode(symbol), symbol);
                codes.computeIfAbsent(group(symbol.getCategory()), g -> new LinkedHashSet<>()).add(marketCode(symbol));
            }
            for (Group group : groups.values()) {
                List<String> list = new ArrayList<>(codes.getOrDefault(group, Collections.emptySet()));
                for (String code : list) if (!group.quotes.containsKey(code)) {
                    Map<String, Object> saved = redis.getPrice(group.category + ":" + code);
                    if (QuoteState.valid(saved)) {
                        saved.put("sourceAvailable", false); // A restarted process must confirm the source first.
                        group.quotes.putIfAbsent(code, saved);
                    }
                }
                group.codes = Collections.unmodifiableList(list);
            }
            registry = Collections.unmodifiableMap(updated);
        } catch (Exception failure) { log.warn("Market symbol refresh failed ({})", failure.getClass().getSimpleName()); }
    }
    private void tick(Group group) {
        long now = System.currentTimeMillis();
        if (now < group.nextAllowed) return;
        try {
            if (now >= group.nextQuotes && !group.codes.isEmpty()) {
                group.nextQuotes = now + Math.max(1000, pollMs);
                http.begin();
                Map<String, Map<String, Object>> prices;
                try { prices = source.getBatchPrices(group.codes, group.category); }
                finally { http.end(); }
                boolean incomplete = false;
                int accepted = 0;
                for (String code : group.codes) {
                    Map<String, Object> price = prices.get(code);
                    Map<String, Object> previous = group.quotes.get(code);
                    if (!QuoteState.valid(price) || previous != null && QuoteState.time(price.get("timestamp")) < QuoteState.time(previous.get("timestamp"))) {
                        unavailable(group, code); incomplete = true; continue;
                    }
                    Map<String, Object> saved = new HashMap<>(price);
                    saved.put("fetchedAt", System.currentTimeMillis());
                    saved.put("sourceAvailable", true);
                    saved.put("source", provider(group.category));
                    group.quotes.put(code, Collections.unmodifiableMap(saved));
                    redis.savePrice(group.category + ":" + code, saved);
                    accepted++;
                }
                if (accepted == 0) { fail(group, new MarketHttp.Failure("invalid_or_missing_quote", 0), false); return; }
                if (group.failures > 0) log.info("Market {} recovered", group.category);
                group.failures = 0; group.error = incomplete ? "partial_response" : null;
                // Missing/invalid items retain their own unavailable state. Healthy items in the
                // same batch must not inherit their backoff; one batch still serves the whole group.
                return;
            }
            KlineRequest request;
            if (System.currentTimeMillis() < group.nextKlines) return;
            synchronized (group) {
                Iterator<KlineRequest> iterator = group.pending.values().iterator();
                if (!iterator.hasNext()) return;
                request = iterator.next(); iterator.remove(); group.activeKey = request.key;
            }
            try {
                http.begin();
                Map<String, Object> result = request.endTime == null
                        ? source.getKline(request.code, request.interval, request.limit, group.category)
                        : source.getKline(request.code, request.interval, request.limit, group.category, request.endTime);
                validateKline(result);
                result.put("fetchedAt", System.currentTimeMillis());
                result.put("status", "available");
                synchronized (group) { group.klines.put(request.key, result); }
                group.klineFailures = 0; group.klineError = null;
            } catch (Exception failure) {
                group.klineFailures = Math.min(16, group.klineFailures + 1);
                long delay = Math.min(30000, 2000L << Math.min(4, group.klineFailures - 1));
                if (failure instanceof MarketHttp.Failure) delay = Math.max(delay, ((MarketHttp.Failure) failure).retryAfterMs);
                group.nextKlines = System.currentTimeMillis() + delay;
                group.klineError = failure instanceof MarketHttp.Failure ? failure.getMessage() : "invalid_kline";
                // A broken chart endpoint must not invalidate successful ticker snapshots.
                // Rate limiting still applies to both operations sharing this source lane.
                if ("http_429".equals(group.klineError)) fail(group, failure, true);
                if (group.klineFailures == 1 || System.currentTimeMillis() - group.lastKlineLog >= 30000) {
                    log.warn("Market {} K-line unavailable: {}; retry in {}ms", group.category, group.klineError, delay);
                    group.lastKlineLog = System.currentTimeMillis();
                }
            } finally { http.end(); group.activeKey = null; }
        } catch (Exception failure) { fail(group, failure, true); }
    }
    private void unavailable(Group group, String code) {
        Map<String, Object> old = group.quotes.get(code);
        if (old == null) return;
        Map<String, Object> saved = new HashMap<>(old);
        saved.put("sourceAvailable", false);
        group.quotes.put(code, Collections.unmodifiableMap(saved));
        redis.savePrice(group.category + ":" + code, saved);
    }
    private void fail(Group group, Exception failure, boolean all) {
        group.failures = Math.min(16, group.failures + 1);
        long delay = Math.min(30000, 2000L << Math.min(4, group.failures - 1));
        if (failure instanceof MarketHttp.Failure) delay = Math.max(delay, ((MarketHttp.Failure) failure).retryAfterMs);
        group.nextAllowed = System.currentTimeMillis() + delay;
        group.error = failure instanceof MarketHttp.Failure ? failure.getMessage() : "invalid_response";
        if (all) for (String code : group.codes) unavailable(group, code);
        if (group.failures == 1 || System.currentTimeMillis() - group.lastLog >= 30000) {
            log.warn("Market {} unavailable: {}; retry in {}ms", group.category, group.error, delay);
            group.lastLog = System.currentTimeMillis();
        }
    }
    static void validateKline(Map<String, Object> result) {
        if (!Integer.valueOf(200).equals(result.get("ret")) || !(result.get("data") instanceof Map)) throw new MarketHttp.Failure("invalid_kline", 0);
        Object list = ((Map<?, ?>) result.get("data")).get("kline_list");
        if (!(list instanceof List) || ((List<?>) list).isEmpty()) throw new MarketHttp.Failure("invalid_kline", 0);
        for (Object row : (List<?>) list) {
            if (!(row instanceof Map)) throw new MarketHttp.Failure("invalid_kline", 0);
            Map<?, ?> candle = (Map<?, ?>) row;
            if (QuoteState.time(candle.get("timestamp")) <= 0) throw new MarketHttp.Failure("invalid_kline", 0);
            for (String field : Arrays.asList("open_price", "high_price", "low_price", "close_price")) {
                Object value = candle.get(field);
                if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue()) || ((Number) value).doubleValue() <= 0)
                    throw new MarketHttp.Failure("invalid_kline", 0);
            }
        }
    }
    static String provider(String category) {
        return "Crypto".equals(category) || "Metal".equals(category) ? "Bitget" : "Other".equals(category) ? "Alltick" : "Yahoo";
    }
    public Map<String, Object> getPrice(String code) { return getPrice(code, "Crypto"); }
    public Map<String, Object> getPrice(String code, String category) {
        Group group = group(category);
        Map<String, Object> quote = QuoteState.view(group.quotes.get(code), maxAgeMs);
        quote.put("symbol", code);
        quote.put("retryAt", group.nextAllowed);
        return quote;
    }
    public Map<String, Map<String, Object>> getBatchPrices(List<String> codes) { return getBatchPrices(codes, "Crypto"); }
    public Map<String, Map<String, Object>> getBatchPrices(List<String> codes, String category) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (codes != null) for (String code : codes) result.put(code, getPrice(code, category));
        return result;
    }
    public Map<String, Object> internalPrice(String code) {
        TradingSymbol config = registry.get(code);
        Map<String, Object> quote = config == null ? QuoteState.view(null, maxAgeMs) : getPrice(marketCode(config), config.getCategory());
        if (config != null && Boolean.TRUE.equals(config.getControlEnabled()) && config.getControlPriceOffset() != null && quote.get("price") instanceof Number)
            quote.put("price", ((Number) quote.get("price")).doubleValue() + config.getControlPriceOffset().doubleValue());
        if (!QuoteState.valid(quote)) { quote.put("available", false); quote.put("status", "unavailable"); }
        quote.put("symbol", code);
        return quote;
    }
    public BigDecimal freshPrice(String code) {
        Map<String, Object> quote = internalPrice(code);
        return Boolean.TRUE.equals(quote.get("available")) ? BigDecimal.valueOf(((Number) quote.get("price")).doubleValue()) : null;
    }
    public Map<String, BigDecimal> freshPrices() {
        Map<String, BigDecimal> result = new HashMap<>();
        for (TradingSymbol config : registry.values()) {
            BigDecimal price = freshPrice(config.getSymbol());
            if (price != null) result.put(config.getSymbol(), price);
        }
        return result;
    }
    public Map<String, Object> getKline(String code, String interval, Integer limit) { return getKline(code, interval, limit, "Crypto"); }
    @SuppressWarnings("unchecked")
    public Map<String, Object> getKline(String code, String interval, Integer limit, String category) {
        return getKline(code, interval, limit, category, null);
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> getKline(String code, String interval, Integer limit, String category, Long endTime) {
        Group group = group(category);
        KlineRequest request = new KlineRequest(code, interval, Math.min(1000, Math.max(1, limit == null ? 100 : limit)), endTime);
        Map<String, Object> saved;
        String status;
        synchronized (group) {
            saved = group.klines.get(request.key);
            boolean fresh = saved != null && System.currentTimeMillis() - QuoteState.time(saved.get("fetchedAt")) < (endTime == null ? 15000 : 300000);
            status = fresh ? "available" : saved == null ? "unavailable" : "stale";
            // Only configured products can create work; request traffic cannot grow the symbol registry.
            if (!fresh && group.codes.contains(code) && group.pending.size() < MAX_PENDING && !request.key.equals(group.activeKey))
                group.pending.putIfAbsent(request.key, request);
        }
        Map<String, Object> result = new HashMap<>();
        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (saved != null) for (Map<String, Object> row : (List<Map<String, Object>>) ((Map<?, ?>) saved.get("data")).get("kline_list")) rows.add(new HashMap<>(row));
        if (group.failures > 0 || group.klineFailures > 0) status = saved == null ? "unavailable" : "stale";
        data.put("code", code); data.put("kline_list", rows); data.put("status", status);
        data.put("fetchedAt", saved == null ? null : saved.get("fetchedAt"));
        data.put("retryAt", Math.max(group.nextAllowed, group.nextKlines));
        synchronized (group) { data.put("pending", group.failures == 0 && group.klineFailures == 0 && (group.pending.containsKey(request.key) || request.key.equals(group.activeKey))); }
        result.put("ret", saved == null ? 503 : 200); result.put("msg", status);
        result.put("status", status); result.put("data", data);
        return result;
    }
    public Map<String, Object> getBatchKline(List<String> codes, String interval, Integer limit) { return getBatchKline(codes, interval, limit, "Crypto"); }
    public Map<String, Object> getBatchKline(List<String> codes, String interval, Integer limit, String category) {
        List<Object> data = new ArrayList<>();
        for (String code : codes) data.add(getKline(code, interval, limit, category).get("data"));
        Map<String, Object> result = new HashMap<>(); result.put("ret", 200); result.put("msg", "ok"); result.put("data", data); return result;
    }
    @SuppressWarnings("unchecked")
    public Map<String, Object> internalKline(String symbol, String interval, Integer limit) {
        TradingSymbol config = registry.get(symbol);
        Map<String, Object> result = getKline(config == null ? symbol : marketCode(config), interval, limit, config == null ? "Crypto" : config.getCategory());
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        data.put("symbol", symbol);
        if (config != null && Boolean.TRUE.equals(config.getControlEnabled()) && config.getControlPriceOffset() != null) {
            double offset = config.getControlPriceOffset().doubleValue();
            for (Map<String, Object> row : (List<Map<String, Object>>) data.get("kline_list"))
                for (String key : Arrays.asList("open_price", "high_price", "low_price", "close_price"))
                    row.put(key, ((Number) row.get(key)).doubleValue() + offset);
        }
        return result;
    }
    /** Historical source candles are not rewritten using today's configured price offset. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> historicalKline(String symbol, String interval, int limit, long endTime) {
        TradingSymbol config = registry.get(symbol);
        if (config == null) throw new IllegalArgumentException("Unknown symbol");
        Map<String, Object> result = getKline(marketCode(config), interval, limit, config.getCategory(), endTime);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        data.put("symbol", symbol);
        data.put("source", provider(config.getCategory()));
        return result;
    }
    public List<Map<String, Object>> sourceStatus() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Group group : groups.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("provider", provider(group.category)); item.put("category", group.category); item.put("symbols", group.codes.size());
            item.put("failures", group.failures); item.put("error", group.error); item.put("retryAt", group.nextAllowed);
            item.put("klineFailures", group.klineFailures); item.put("klineError", group.klineError); item.put("klineRetryAt", group.nextKlines);
            item.put("threads", group.executor.getPoolSize()); item.put("schedulerQueue", group.executor.getQueue().size());
            synchronized (group) { item.put("pendingKlines", group.pending.size()); }
            long fresh = group.codes.stream().filter(code -> Boolean.TRUE.equals(getPrice(code, group.category).get("available"))).count();
            item.put("freshQuotes", fresh); result.add(item);
        }
        return result;
    }
    @PreDestroy public void stop() { metadata.shutdownNow(); for (Group group : groups.values()) group.executor.shutdownNow(); }
}

