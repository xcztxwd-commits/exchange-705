package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.common.BusinessException;
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
        metadata.scheduleWithFixedDelay(this::completeControls, 1, 1, TimeUnit.SECONDS);
        for (Group group : groups.values())
            group.executor.scheduleWithFixedDelay(() -> tick(group), 0, 100, TimeUnit.MILLISECONDS);
    }
    synchronized void refreshSymbols() {
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
        long now = System.currentTimeMillis();
        if (config != null && quote.get("price") instanceof Number) {
            quote.put("price", controlledPrice(config, quote, now).doubleValue());
            // Keep sourceTimestamp/expiresAt intact: a control task cannot revive stale source data.
            if (Boolean.TRUE.equals(quote.get("available")) && config.getUpdatedAt() != null)
                quote.put("timestamp", Math.max(QuoteState.time(quote.get("timestamp")),
                    config.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
            if (PriceControlPath.running(config) && Boolean.TRUE.equals(quote.get("available")))
                quote.put("timestamp", Math.max(QuoteState.time(quote.get("timestamp")),
                    config.getControlStartedAt() + Math.max(0, (now - config.getControlStartedAt()) / 1000) * 1000));
        }
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
            Map<String, Object> quote = getPrice(marketCode(config), config.getCategory());
            double offset = quote.get("price") instanceof Number
                ? controlledPrice(config, quote, System.currentTimeMillis()).doubleValue() - ((Number) quote.get("price")).doubleValue()
                : config.getControlPriceOffset().doubleValue();
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

    private static BigDecimal rawPrice(Map<String, Object> quote) {
        return BigDecimal.valueOf(((Number) quote.get("price")).doubleValue());
    }

    static BigDecimal controlledPrice(TradingSymbol config, Map<String, Object> quote, long now) {
        if (!Boolean.TRUE.equals(config.getControlEnabled())) return rawPrice(quote);
        if (PriceControlPath.running(config)) {
            if (Boolean.TRUE.equals(config.getControlRestoring()))
                return rawPrice(quote).add(PriceControlPath.restoreOffset(config, now)).max(BigDecimal.ONE.movePointLeft(PriceControlPath.precision(config)));
            return PriceControlPath.price(config, now);
        }
        return rawPrice(quote).add(config.getControlPriceOffset() == null ? BigDecimal.ZERO : config.getControlPriceOffset());
    }

    private TradingSymbol controlSymbol(Long id) {
        return symbols.findById(id).orElseThrow(() -> new BusinessException("币种不存在"));
    }

    private Map<String, Object> requireControlQuote(TradingSymbol config) {
        Map<String, Object> quote = getPrice(marketCode(config), config.getCategory());
        if (!Boolean.TRUE.equals(quote.get("available"))) throw new BusinessException("行情暂不可用或已过期，请稍后重试");
        return quote;
    }

    public List<Map<String, Object>> controlSymbols() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TradingSymbol symbol : symbols.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", symbol.getId()); row.put("symbol", symbol.getSymbol()); row.put("name", symbol.getName());
            row.put("isEnabled", symbol.getIsEnabled()); row.put("pricePrecision", PriceControlPath.precision(symbol));
            row.put("quoteCurrency", symbol.getQuoteCurrency()); result.add(row);
        }
        result.sort(Comparator.comparing(row -> String.valueOf(row.get("symbol"))));
        return result;
    }

    public synchronized Map<String, Object> controlStatus(Long id) {
        return controlStatus(controlSymbol(id));
    }

    private Map<String, Object> controlStatus(TradingSymbol config) {
        long now = System.currentTimeMillis();
        Map<String, Object> quote = getPrice(marketCode(config), config.getCategory());
        Map<String, Object> result = new LinkedHashMap<>();
        boolean running = PriceControlPath.running(config);
        BigDecimal price = quote.get("price") instanceof Number ? controlledPrice(config, quote, now) : null;
        result.put("id", config.getId()); result.put("enabled", Boolean.TRUE.equals(config.getControlEnabled()));
        result.put("running", running); result.put("available", Boolean.TRUE.equals(quote.get("available")) && price != null && price.signum() > 0);
        result.put("rawPrice", quote.get("price")); result.put("currentPrice", price);
        result.put("offset", price == null ? config.getControlPriceOffset() : price.subtract(rawPrice(quote)));
        result.put("startPrice", config.getControlStartPrice()); result.put("targetPrice", config.getControlTargetPrice());
        result.put("durationSeconds", config.getControlDurationSeconds()); result.put("intensity", config.getControlIntensity());
        result.put("randomOscillation", Boolean.TRUE.equals(config.getControlRandomOscillation()));
        result.put("startedAt", config.getControlStartedAt()); result.put("completedAt", config.getControlCompletedAt());
        result.put("restoring", Boolean.TRUE.equals(config.getControlRestoring()));
        result.put("remainingSeconds", running ? Math.max(0, (PriceControlPath.endsAt(config) - now + 999) / 1000) : 0);
        return result;
    }

    public synchronized Map<String, Object> startControl(Long id, int duration, BigDecimal target, int intensity, boolean randomOscillation) {
        if (duration < 1 || duration > 86400 || intensity < 1 || intensity > 10 || target == null
                || target.signum() <= 0 || target.compareTo(new BigDecimal("10000000000000000")) >= 0)
            throw new BusinessException("时长需为 1–86400 秒，波动强度需为 1–10，目标价格必须大于 0");
        TradingSymbol config = controlSymbol(id);
        if (!Boolean.TRUE.equals(config.getIsEnabled())) throw new BusinessException("请先启用该币种");
        if (target.stripTrailingZeros().scale() > PriceControlPath.precision(config)) throw new BusinessException("目标价格超出币种价格精度");
        if (PriceControlPath.running(config)) throw new BusinessException("自动控盘正在运行，请先停止任务");
        Map<String, Object> quote = requireControlQuote(config);
        long now = System.currentTimeMillis();
        BigDecimal start = controlledPrice(config, quote, now);
        if (start.signum() <= 0) throw new BusinessException("当前控盘价格无效，请先调整偏移");
        config.setControlStartPrice(start); config.setControlTargetPrice(target);
        config.setControlDurationSeconds(duration); config.setControlIntensity(intensity);
        config.setControlRandomOscillation(randomOscillation);
        config.setControlStartedAt(now); config.setControlCompletedAt(null); config.setControlEnabled(true);
        config.setControlRestoring(false);
        config.setControlPriceOffset(start.subtract(rawPrice(quote)));
        return saveControl(config);
    }

    public synchronized Map<String, Object> restoreControl(Long id, int duration, int intensity, boolean randomOscillation) {
        if (duration < 1 || duration > 86400 || intensity < 1 || intensity > 10)
            throw new BusinessException("时长需为 1–86400 秒，波动强度需为 1–10");
        TradingSymbol config = controlSymbol(id);
        Map<String, Object> quote = requireControlQuote(config);
        long now = System.currentTimeMillis();
        BigDecimal start = controlledPrice(config, quote, now);
        if (start.signum() <= 0) throw new BusinessException("当前控盘价格无效，请一键恢复原始行情");
        BigDecimal offset = start.subtract(rawPrice(quote));
        if (offset.signum() == 0) return manualControl(id, false, BigDecimal.ZERO);
        config.setControlStartPrice(start); config.setControlTargetPrice(rawPrice(quote));
        config.setControlPriceOffset(offset); config.setControlEnabled(true); config.setControlRestoring(true);
        config.setControlDurationSeconds(duration); config.setControlIntensity(intensity);
        config.setControlRandomOscillation(randomOscillation);
        config.setControlStartedAt(now); config.setControlCompletedAt(null);
        return saveControl(config);
    }

    public synchronized Map<String, Object> manualControl(Long id, boolean enabled, BigDecimal offset) {
        if (offset == null || offset.abs().compareTo(new BigDecimal("10000000000000000")) >= 0 || offset.stripTrailingZeros().scale() > 16)
            throw new BusinessException("偏移值无效");
        TradingSymbol config = controlSymbol(id);
        if (enabled && rawPrice(requireControlQuote(config)).add(offset).signum() <= 0)
            throw new BusinessException("偏移后的价格必须大于 0");
        clearControl(config);
        config.setControlEnabled(enabled); config.setControlPriceOffset(enabled ? offset : BigDecimal.ZERO);
        return saveControl(config);
    }

    public synchronized Map<String, Object> stopControl(Long id) {
        TradingSymbol config = controlSymbol(id);
        if (!PriceControlPath.running(config)) return controlStatus(config);
        Map<String, Object> quote = requireControlQuote(config);
        config.setControlPriceOffset(controlledPrice(config, quote, System.currentTimeMillis()).subtract(rawPrice(quote)));
        clearControl(config);
        return saveControl(config);
    }

    private static void clearControl(TradingSymbol config) {
        config.setControlStartedAt(null); config.setControlCompletedAt(null);
        config.setControlStartPrice(null); config.setControlTargetPrice(null);
        config.setControlDurationSeconds(null); config.setControlIntensity(null);
        config.setControlRandomOscillation(false);
        config.setControlRestoring(false);
    }

    private Map<String, Object> saveControl(TradingSymbol config) {
        TradingSymbol saved = symbols.saveAndFlush(config);
        // ponytail: one backend owns these snapshots; add shared invalidation before deploying replicas.
        Map<String, TradingSymbol> updated = new HashMap<>(registry);
        updated.put(saved.getSymbol(), saved);
        String alias = marketCode(saved);
        if (!updated.containsKey(alias) || Objects.equals(updated.get(alias).getId(), saved.getId())) updated.put(alias, saved);
        registry = Collections.unmodifiableMap(updated);
        return controlStatus(saved);
    }

    synchronized void completeControls() {
        long now = System.currentTimeMillis();
        Set<Long> visited = new HashSet<>();
        for (TradingSymbol snapshot : registry.values()) {
            if (!PriceControlPath.running(snapshot) || now < PriceControlPath.endsAt(snapshot) || !visited.add(snapshot.getId())) continue;
            try {
                TradingSymbol config = controlSymbol(snapshot.getId());
                if (!PriceControlPath.running(config) || now < PriceControlPath.endsAt(config)) continue;
                Map<String, Object> quote = requireControlQuote(config);
                boolean restoring = Boolean.TRUE.equals(config.getControlRestoring());
                config.setControlPriceOffset(restoring ? BigDecimal.ZERO : config.getControlTargetPrice().subtract(rawPrice(quote)));
                if (restoring) config.setControlEnabled(false);
                config.setControlCompletedAt(now);
                saveControl(config);
            } catch (BusinessException unavailable) {
                // Leave the task pending until the source is healthy; no stale-price execution.
            } catch (Exception failure) {
                log.warn("Price control completion failed for {} ({})", snapshot.getSymbol(), failure.getClass().getSimpleName());
            }
        }
    }
    @PreDestroy public void stop() { metadata.shutdownNow(); for (Group group : groups.values()) group.executor.shutdownNow(); }
}

