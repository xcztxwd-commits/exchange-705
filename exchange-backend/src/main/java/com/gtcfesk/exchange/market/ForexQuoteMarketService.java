package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    @Autowired(required = false) private com.gtcfesk.exchange.admin.SystemConfigService systemConfigs;
    @Autowired private MarketHttp http;
    @Autowired(required = false) private YahooQuoteStream yahoo;
    @Autowired(required = false) private ExchangeQuoteStream exchangeStream;
    @Autowired(required = false) private ExchangeQuoteSource exchangeSource;
    private final String epoch = UUID.randomUUID().toString();
    private final Map<String, Map<String,Object>> published = new HashMap<>();
    private final Map<String, Long> publishedAt = new HashMap<>();
    private long quoteVersion;
    @Autowired private RedisMarketService redis;
    @Autowired private TradingSymbolRepository symbols;
    @Autowired private PersistentPriceControl controls;
    @Autowired private ControlHistoryStore controlHistory;
    @Autowired private ControlledKlineMerger klineMerger;
    @Value("${market.quote.max-age-ms:15000}") private long maxAgeMs = 15000;
    @Value("${market.quote.poll-ms:3000}") private long pollMs = 3000;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private volatile Map<String, TradingSymbol> registry = Collections.emptyMap();
    private final ScheduledExecutorService metadata = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-symbols"));
    @Value("${market.virtual-trading.enabled:false}") private boolean virtualTrading;
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
        final Object quoteLock = new Object();
        final Set<String> processingFailed = new HashSet<>();
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
        for (String category : Arrays.asList("Crypto", "CryptoPerpetual", "Metal", "Forex", "US", "CFD", "Oil", "Other"))
            groups.put(category, new Group(category));
    }
    private Group group(String category) {
        for (Group group : groups.values()) if (group.category.equalsIgnoreCase(category)) return group;
        return groups.get(category == null ? "Crypto" : "Other");
    }
    public static String sourceCategory(TradingSymbol symbol) {
        return symbol.getSourceCategory();
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
    public synchronized void refreshSymbols() {
        try {
            Map<String, TradingSymbol> updated = new HashMap<>();
            Map<Group, Set<String>> codes = new HashMap<>();
            for (TradingSymbol symbol : symbols.findAll()) {
                if (controls != null && !RandomMarketPath.enabled(symbol) && PriceControlPath.running(symbol)) {
                    controls.importLegacy(symbol);
                    clearControl(symbol); symbol.setControlEnabled(false); symbol.setControlPriceOffset(BigDecimal.ZERO);
                    symbol = symbols.saveAndFlush(symbol);
                }
                updated.put(symbol.getSymbol(), symbol);
                if (!"CryptoPerpetual".equals(sourceCategory(symbol))) updated.putIfAbsent(marketCode(symbol), symbol);
                codes.computeIfAbsent(group(sourceCategory(symbol)), g -> new LinkedHashSet<>()).add(marketCode(symbol));
                QuoteCurrencyConversion conversion=QuoteCurrencyConversion.route(symbol.getQuoteCurrency(),symbol.getMarketSource());
                if(conversion!=null) codes.computeIfAbsent(group(conversion.category),g -> new LinkedHashSet<>()).add(conversion.code);
            }
            Set<String> conversionCurrencies = new LinkedHashSet<>(com.gtcfesk.exchange.admin.SystemConfigService.conversionCurrencies(
                systemConfigs == null ? null : systemConfigs.getConfigValue("market.conversion.currencies")));
            // Keep currencies required by existing deposit/settlement flows available as well.
            conversionCurrencies.addAll(com.gtcfesk.exchange.user.FiatCurrencyService.CURRENCIES);
            for (String currency : conversionCurrencies) {
                QuoteCurrencyConversion route = QuoteCurrencyConversion.route(currency, "yahoo");
                if (route != null) codes.computeIfAbsent(group(route.category), g -> new LinkedHashSet<>()).add(route.code);
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
            if (exchangeStream != null) for (String category : Arrays.asList("Crypto", "CryptoPerpetual", "Metal"))
                exchangeStream.subscriptions(category, new HashSet<>(groups.get(category).codes),
                    (code, quote) -> acceptQuote(code, category, quote, "ws", QuoteState.time(quote.get("fetchedAt"))));
            registry = Collections.unmodifiableMap(updated);
            for (TradingSymbol symbol : new HashSet<>(updated.values())) conversion(symbol.getQuoteCurrency(),symbol.getMarketSource());
            for (String currency : conversionCurrencies) conversion(currency, "yahoo");
            published.keySet().retainAll(updated.keySet()); publishedAt.keySet().retainAll(updated.keySet());
            if (yahoo != null) {
                Set<String> subscribed = new HashSet<>();
                for (Group item : groups.values()) if ("Yahoo".equals(provider(item.category)))
                    for (String code : item.codes) subscribed.add(MarketQuoteSource.mapSymbolToYahoo(code, item.category));
                yahoo.subscriptions(subscribed, this::receiveYahoo);
            }
        } catch (Exception failure) { log.warn("Market symbol refresh failed ({})", failure.getClass().getSimpleName()); }
    }
    private void tick(Group group) {
        long now = System.currentTimeMillis();
        if (now < group.nextAllowed) return;
        try {
            List<String> requested = new ArrayList<>();
            for (String code : group.codes) if (!streamHealthy(group.category, code)) requested.add(code);
            if (now >= group.nextQuotes && !requested.isEmpty()) {
                group.nextQuotes = now + Math.max(1000, pollMs);
                http.begin();
                Map<String, Map<String, Object>> prices;
                try { prices = source.getBatchPrices(requested, group.category); }
                finally { http.end(); }
                boolean incomplete = false;
                int accepted = 0;
                for (String code : requested) {
                    Map<String,Object> price = prices.get(code);
                    if (!QuoteState.valid(price)) { unavailable(group, code); incomplete = true; continue; }
                    acceptQuote(code, group.category, price, "http", System.currentTimeMillis());
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
                for (TradingSymbol config : new HashSet<>(registry.values()))
                    if (controlHistory != null && marketCode(config).equals(request.code) && group(sourceCategory(config)) == group)
                        controlHistory.sourceCandles(config.getId(), request.interval, ControlHistoryStore.rows(result), System.currentTimeMillis());
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
    private void receiveYahoo(String yahooSymbol, Map<String,Object> quote) {
        for (Group group : groups.values()) if ("Yahoo".equals(provider(group.category)))
            for (String code : group.codes) if (yahooSymbol.equals(MarketQuoteSource.mapSymbolToYahoo(code, group.category)))
                acceptQuote(code, group.category, quote, "ws", QuoteState.time(quote.get("fetchedAt")));
    }
    /** HTTP and stream events share ordering and history. HTTP I/O never holds this lock. */
    boolean acceptQuote(String code, String category, Map<String,Object> price, String transport, long receivedAt) {
        Group group = group(category);
        if (!group.codes.contains(code) || !QuoteState.valid(price)) return false;
        synchronized (group.quoteLock) {
            Map<String,Object> previous = group.quotes.get(code);
            long time = QuoteState.time(price.get("timestamp"));
            if (previous != null && (time < QuoteState.time(previous.get("timestamp"))
                    || time == QuoteState.time(previous.get("timestamp")) && "ws".equals(previous.get("transport")) && "http".equals(transport) && streamConnected(group.category))) return false;
            boolean duplicate = previous != null && time == QuoteState.time(previous.get("timestamp"))
                && Double.compare(((Number) price.get("price")).doubleValue(), ((Number) previous.get("price")).doubleValue()) == 0;
            Map<String,Object> saved = previous == null ? new HashMap<>() : new HashMap<>(previous);
            saved.putAll(price); saved.put("symbol", code); saved.put("transport", transport);
            saved.put("source", provider(category)); saved.put("fetchedAt", receivedAt); saved.put("sourceAvailable", true);
            saved.put("eventId", price.getOrDefault("eventId", UUID.randomUUID().toString()));
            if (!duplicate || group.processingFailed.contains(code)) {
                try {
                    List<TradingSymbol> targets = new ArrayList<>();
                    for (TradingSymbol config : new HashSet<>(registry.values()))
                        if (controls != null && marketCode(config).equals(code) && group(sourceCategory(config)) == group && !RandomMarketPath.enabled(config))
                            targets.add(config);
                    if (!targets.isEmpty()) controls.sourceQuotes(targets, QuoteState.view(saved, maxAgeMs), receivedAt);
                } catch (RuntimeException failure) {
                    group.processingFailed.add(code);
                    markUnavailable(group, code); throw failure;
                }
            }
            group.processingFailed.remove(code);
            group.quotes.put(code, Collections.unmodifiableMap(saved));
            if (redis != null) redis.savePrice(category + ":" + code, saved);
            return true;
        }
    }
    private void unavailable(Group group, String code) {
        synchronized (group.quoteLock) {
            Map<String,Object> old = group.quotes.get(code);
            if (old != null && "ws".equals(old.get("transport")) && streamConnected(group.category)
                    && Boolean.TRUE.equals(QuoteState.view(old, maxAgeMs).get("available"))) return;
            markUnavailable(group, code);
        }
    }
    private void markUnavailable(Group group, String code) {
        Map<String,Object> old = group.quotes.get(code);
        if (old == null) return;
        Map<String,Object> saved = new HashMap<>(old); saved.put("sourceAvailable", false);
        group.quotes.put(code, Collections.unmodifiableMap(saved));
        if (redis != null) redis.savePrice(group.category + ":" + code, saved);
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
    private boolean streamConnected(String category) {
        return ExchangeQuoteSource.supports(category) ? exchangeStream != null && exchangeStream.connected(category) : yahoo != null && yahoo.connected();
    }
    private boolean streamHealthy(String category, String code) {
        return ExchangeQuoteSource.supports(category) ? exchangeStream != null && exchangeStream.healthy(category, code)
            : yahoo != null && "Yahoo".equals(provider(category)) && yahoo.healthy(MarketQuoteSource.mapSymbolToYahoo(code, category));
    }
    String provider(String category) {
        return ExchangeQuoteSource.supports(category) ? exchangeSource == null ? "Binance" : exchangeSource.name() : "Other".equals(category) ? "Alltick" : "Yahoo";
    }
    public Map<String, Object> getPrice(String code) { return getPrice(code, "Crypto"); }
    public Map<String, Object> getPrice(String code, String category) {
        Group group = group(category);
        Map<String, Object> quote = QuoteState.view(group.quotes.get(code), maxAgeMs);
        quote.put("symbol", code);
        if ("ws".equals(quote.get("transport")) && !streamConnected(group.category)) {
            quote.put("sourceAvailable", false); quote.put("available", false); quote.put("tradeAvailable", false); quote.put("status", "unavailable");
        }
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
        if (RandomMarketPath.enabled(config)) {
            Map<String, Object> simulated = RandomMarketPath.quote(config, System.currentTimeMillis());
            simulated.put("symbol", code); simulated.put("marketRevision", config.getRowVersion());
            if (!virtualTrading) { simulated.put("available", false); simulated.put("status", "unavailable"); }
            return simulated;
        }
        Map<String, Object> quote = config == null ? QuoteState.view(null, maxAgeMs) : getPrice(marketCode(config), sourceCategory(config));
        long now = System.currentTimeMillis();
        if (config != null && controls != null) quote = controls.display(config, quote, now);
        if (config != null && (!Boolean.TRUE.equals(quote.get("controlHistory"))
                || Boolean.TRUE.equals(config.getControlEnabled()) && !Boolean.TRUE.equals(quote.get("controlRunning")))
                && QuoteState.valid(quote)) {
            quote.put("price", controlledPrice(config, quote, now).doubleValue());
            // Keep sourceTimestamp/expiresAt intact: a control task cannot revive stale source data.
            if (PriceControlPath.running(config) && Boolean.TRUE.equals(quote.get("available")))
                quote.put("timestamp", Math.max(QuoteState.time(quote.get("timestamp")),
                    config.getControlStartedAt() + Math.max(0, (now - config.getControlStartedAt()) / 1000) * 1000));
        }
        // Active controls and held offsets remain executable during a provider outage.
        // Renew only the execution lease; source/sample timestamps and historical candles stay unchanged.
        boolean controlled = config != null && (Boolean.TRUE.equals(config.getControlEnabled())
                || "RUNNING".equals(quote.get("controlState")) || "HOLDING".equals(quote.get("controlState")));
        if (controlled && QuoteState.valid(quote)) {
            quote.put("available", true); quote.put("tradeAvailable", true);
            quote.put("stale", false); quote.put("status", "available");
            quote.put("executionExpiresAt", now + maxAgeMs); quote.put("expiresAt", now + maxAgeMs);
        }
        if (!QuoteState.valid(quote)) { quote.put("available", false); quote.put("status", "unavailable"); }
        quote.put("symbol", code);
        quote.put("marketRevision", config == null ? 0 : config.getRowVersion());
        return quote;
    }
    public Map<String,Object> conversion(String currency,String source) {
        Map<String,Object> result=new HashMap<>();
        if(QuoteCurrencyConversion.fixed(currency)) {
            result.put("quoteToUsdRate",BigDecimal.ONE);result.put("conversionAvailable",true);result.put("conversionExpiresAt",Long.MAX_VALUE);return result;
        }
        QuoteCurrencyConversion route=QuoteCurrencyConversion.route(currency,source);
        Map<String,Object> raw=route==null?Collections.emptyMap():getPrice(route.code,route.category);
        Map<String,Object> cached=route==null || redis==null?null:redis.conversionQuote(source,route.category,route.code,raw);
        raw=cached==null?Collections.emptyMap():cached;
        boolean available=QuoteState.valid(cached) && QuoteState.time(cached.get("expiresAt"))>System.currentTimeMillis();
        result.put("quoteToUsdRate",available?new BigDecimal(raw.get("price").toString()).multiply(route.scale):null);
        result.put("conversionAvailable",available);result.put("conversionExpiresAt",raw.getOrDefault("expiresAt",0L));
        result.put("conversionSymbol",route==null?null:route.code);result.put("conversionTimestamp",raw.get("timestamp"));
        return result;
    }
    public BigDecimal requireConversionRate(String currency,String source) {
        Object value=conversion(currency,source).get("quoteToUsdRate");
        if(value==null)throw new BusinessException("结算汇率暂不可用或已过期，请稍后重试");
        return new BigDecimal(value.toString());
    }
    public boolean knownSymbol(String symbol) { return registry.containsKey(symbol); }
    /** Shared, versioned display snapshot. Trading always revalidates via freshPrice(). */
    public synchronized Map<String,Object> snapshotPrice(String symbol) {
        if (!knownSymbol(symbol)) return internalPrice(symbol);
        long now = System.currentTimeMillis();
        Map<String,Object> previous = published.get(symbol);
        if (previous != null && now - publishedAt.getOrDefault(symbol, 0L) < 100
                && now < QuoteState.time(previous.get("expiresAt"))) return previous;
        Map<String,Object> next = new HashMap<>(internalPrice(symbol));
        TradingSymbol config=registry.get(symbol);
        next.putAll(conversion(config.getQuoteCurrency(),config.getMarketSource()));
        next.put("quoteCurrency",config.getQuoteCurrency());
        next.put("epoch", epoch);
        next.put("quoteVersion", previous == null ? 0L : previous.get("quoteVersion"));
        if (previous == null || !next.equals(previous)) next.put("quoteVersion", ++quoteVersion);
        Map<String,Object> result = Collections.unmodifiableMap(next);
        published.put(symbol, result); publishedAt.put(symbol, now); return result;
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
        if (virtualTrading && RandomMarketPath.enabled(config))
            return simulationKline(config, interval, limit, null);
        Map<String, Object> result = getKline(config == null ? symbol : marketCode(config), interval, limit, config == null ? "Crypto" : sourceCategory(config));
        return mergeControlKline(config, symbol, interval, limit, null, result);
    }
    /** Historical source candles are not rewritten using today's configured price offset. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> historicalKline(String symbol, String interval, int limit, long endTime) {
        TradingSymbol config = registry.get(symbol);
        if (config == null) throw new IllegalArgumentException("Unknown symbol");
        if (virtualTrading && RandomMarketPath.enabled(config))
            return simulationKline(config, interval, limit, endTime);
        Map<String, Object> result = getKline(marketCode(config), interval, limit, sourceCategory(config), endTime);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        data.put("symbol", symbol);
        data.put("source", provider(sourceCategory(config)));
        return mergeControlKline(config, symbol, interval, limit, endTime, result);
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeControlKline(TradingSymbol config, String symbol, String interval, Integer limit, Long endTime, Map<String, Object> result) {
        if (config != null && klineMerger != null)
            result = klineMerger.merge(config.getId(), interval, Math.min(1000, Math.max(1, limit == null ? 100 : limit)), endTime, result,
                cursor -> getKline(marketCode(config), "1m", 1000, sourceCategory(config), cursor),
                ExchangeQuoteSource.supports(sourceCategory(config)));
        ((Map<String, Object>) result.get("data")).put("symbol", symbol);
        return result;
    }
    private String simulationHistoryKey(TradingSymbol config) {
        return "simulation-history:" + config.getId() + ":" + config.getRandomMarketStartedAt();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cachedSimulationHistory(TradingSymbol config, String interval) {
        List<Map<String, Object>> saved = redis.getKlines(simulationHistoryKey(config), interval);
        TreeMap<Long, Map<String, Object>> history = new TreeMap<>();
        if (saved != null) for (Map<String, Object> row : saved) history.put(RandomMarketPath.timestamp(row), row);
        Group group = group(sourceCategory(config));
        String prefix = marketCode(config) + ":" + interval + ":";
        synchronized (group) {
            for (Map.Entry<String, Map<String, Object>> entry : group.klines.entrySet()) {
                if (!entry.getKey().startsWith(prefix)) continue;
                for (Map<String, Object> row : (List<Map<String, Object>>) ((Map<?, ?>) entry.getValue().get("data")).get("kline_list")) {
                    long time = RandomMarketPath.timestamp(row);
                    if (time < config.getRandomMarketStartedAt()
                        && (time + RandomMarketPath.duration(interval) <= config.getRandomMarketStartedAt()
                            || QuoteState.time(entry.getValue().get("fetchedAt")) <= config.getRandomMarketStartedAt() + 999))
                        history.putIfAbsent(time, new HashMap<>(row));
                }
            }
        }
        return new ArrayList<>(history.values());
    }

    @SuppressWarnings("unchecked")
    private synchronized Map<String, Object> simulationKline(TradingSymbol config, String interval, Integer limit, Long endTime) {
        int count = Math.min(1000, Math.max(1, limit == null ? 100 : limit));
        long start = config.getRandomMarketStartedAt();
        List<Map<String, Object>> history = cachedSimulationHistory(config, interval);
        long cursor = Math.min(start - 1, endTime == null ? Long.MAX_VALUE : endTime);
        Map<String, Object> external = getKline(marketCode(config), interval, count, sourceCategory(config), cursor);
        external = mergeControlKline(config, config.getSymbol(), interval, count, cursor, external);
        Map<String, Object> externalData = (Map<String, Object>) external.get("data");
        // The cursor is fixed at activation, so later external candles cannot replace simulated ones.
        TreeMap<Long, Map<String, Object>> frozen = new TreeMap<>();
        for (Map<String, Object> row : history) frozen.put(RandomMarketPath.timestamp(row), row);
        for (Map<String, Object> row : (List<Map<String, Object>>) externalData.get("kline_list")) {
            long time = RandomMarketPath.timestamp(row);
            if (time < start) frozen.putIfAbsent(time, row);
        }
        history = new ArrayList<>(frozen.values());
        if (!history.isEmpty()) redis.saveSimulationHistory(simulationHistoryKey(config), interval, history);
        long anchor = history.isEmpty() ? 0 : RandomMarketPath.timestamp(history.get(0)) % RandomMarketPath.duration(interval);
        Map<String, Object> result = RandomMarketPath.klines(config, interval, count, endTime, System.currentTimeMillis(), anchor);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        RandomMarketPath.mergeHistory(result, history, start, count, endTime);
        boolean needsHistory = ((List<?>) data.get("kline_list")).size() < count;
        data.put("pending", needsHistory && Boolean.TRUE.equals(externalData.get("pending")));
        if (needsHistory && !Integer.valueOf(200).equals(external.get("ret")) && history.isEmpty()) {
            result.put("ret", 503); data.put("status", "unavailable");
        }
        return result;
    }

    public List<Map<String, Object>> sourceStatus() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Group group : groups.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (yahoo != null && "Yahoo".equals(provider(group.category))) item.put("stream", yahoo.status());
            if (exchangeStream != null && ExchangeQuoteSource.supports(group.category)) item.put("stream", exchangeStream.status(group.category));
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
        if (RandomMarketPath.enabled(config)) return RandomMarketPath.price(config, now);
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
        if (RandomMarketPath.enabled(config)) {
            if (!virtualTrading) throw new BusinessException("随机行情仅可在虚拟交易环境使用");
            return simulationBaseQuote(config, System.currentTimeMillis());
        }
        Map<String, Object> quote = getPrice(marketCode(config), sourceCategory(config));
        if (!Boolean.TRUE.equals(quote.get("available"))) throw new BusinessException("行情暂不可用或已过期，请稍后重试");
        return quote;
    }

    private Map<String, Object> simulationBaseQuote(TradingSymbol config, long now) {
        Map<String, Object> quote = RandomMarketPath.quote(config, now);
        quote.put("price", RandomMarketPath.basePrice(config, now / 1000 * 1000));
        return quote;
    }

    private long controlTime(TradingSymbol config) {
        long now = System.currentTimeMillis();
        return RandomMarketPath.enabled(config) ? now / 1000 * 1000 : now;
    }

    private void recordSimulationControl(TradingSymbol config, long now) {
        if (RandomMarketPath.enabled(config)) SimulationControlPath.record(config, now);
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
        TradingSymbol config = controlSymbol(id);
        if (controls != null && !RandomMarketPath.enabled(config)) getKline(marketCode(config), "1m", 200, sourceCategory(config));
        return controlStatus(config);
    }

    private Map<String, Object> controlStatus(TradingSymbol config) {
        long now = System.currentTimeMillis();
        Map<String, Object> quote = RandomMarketPath.enabled(config) ? simulationBaseQuote(config, now)
            : getPrice(marketCode(config), sourceCategory(config));
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
        boolean random = RandomMarketPath.enabled(config);
        result.put("virtualTrading", virtualTrading); result.put("randomMarketEnabled", random);
        result.put("randomMarketBasePrice", config.getRandomMarketBasePrice());
        if (random) {
            Map<String, Object> simulated = RandomMarketPath.quote(config, now);
            result.put("currentPrice", simulated.get("price")); result.put("available", virtualTrading);
            result.put("source", "Simulation"); result.put("simulated", true);
        }
        result.put("remainingSeconds", running ? Math.max(0, (PriceControlPath.endsAt(config) - now + 999) / 1000) : 0);
        if (!random && controls != null) controls.status(config, result, quote, now);
        return result;
    }

    @Transactional
    public synchronized Map<String, Object> randomMarket(Long id, boolean enabled, BigDecimal basePrice) {
        TradingSymbol config = controlSymbol(id);
        if (enabled) {
            if (!virtualTrading) throw new BusinessException("随机行情仅可在虚拟交易环境启用");
            if (!Boolean.TRUE.equals(config.getIsEnabled())) throw new BusinessException("请先启用该币种");
            if (RandomMarketPath.enabled(config)) return controlStatus(config);
            Object last = getPrice(marketCode(config), sourceCategory(config)).get("price");
            basePrice = last instanceof Number ? BigDecimal.valueOf(((Number) last).doubleValue()) : config.getCurrentPrice();
            if (basePrice == null || basePrice.signum() <= 0 || basePrice.compareTo(new BigDecimal("10000000000000000")) >= 0)
                throw new BusinessException("没有有效历史报价，暂时无法开启随机行情");
            basePrice = basePrice.setScale(PriceControlPath.precision(config), java.math.RoundingMode.HALF_UP);
            if (basePrice.signum() <= 0) throw new BusinessException("起始价格低于币种最小价格单位");
            if (controls != null) {
                PersistentPriceControl.Task task = controls.latest(id);
                if (task != null && task.running() && System.currentTimeMillis() < task.plannedEnd) {
                    // Switching to the existing virtual engine affects only the subsequent segment.
                    config.setControlStartPrice(task.startPrice); config.setControlTargetPrice(task.targetPrice);
                    config.setControlStartedAt(task.startedAt); config.setControlCompletedAt(null);
                    config.setControlDurationSeconds(task.durationSeconds); config.setControlIntensity(task.intensity);
                    config.setControlRandomOscillation(task.oscillation); config.setControlRestoring(false);
                    config.setControlPriceOffset(BigDecimal.ZERO); config.setControlEnabled(true);
                }
                controls.stop(id, System.currentTimeMillis());
            }
            config.setRandomMarketControls(null);
            config.setRandomMarketBasePrice(basePrice);
            config.setRandomMarketStartedAt(System.currentTimeMillis() / 1000 * 1000);
            for (String interval : Arrays.asList("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w")) {
                List<Map<String, Object>> history = cachedSimulationHistory(config, interval);
                if (!history.isEmpty()) redis.saveSimulationHistory(simulationHistoryKey(config), interval, history);
            }
        }
        if (!enabled && RandomMarketPath.enabled(config)) {
            // Closing the virtual source ends its current rule; never import its past segment
            // as a new ordinary task on the next metadata refresh.
            clearControl(config); config.setControlEnabled(false); config.setControlPriceOffset(BigDecimal.ZERO);
            recordSimulationControl(config, controlTime(config));
        }
        config.setRandomMarketEnabled(enabled);
        if (enabled && Boolean.TRUE.equals(config.getControlEnabled())) recordSimulationControl(config, config.getRandomMarketStartedAt());
        return saveControl(config);
    }

    @Transactional
    public synchronized Map<String, Object> startControl(Long id, int duration, BigDecimal target, int intensity, boolean randomOscillation) {
        return startControl(id, duration, target, intensity, randomOscillation, null);
    }
    @Transactional
    public synchronized Map<String, Object> startControl(Long id, int duration, BigDecimal target, int intensity, boolean randomOscillation, String requestKey) {
        if (duration < 1 || duration > 86400 || intensity < 1 || intensity > 10 || target == null
                || target.signum() <= 0 || target.compareTo(new BigDecimal("10000000000000000")) >= 0)
            throw new BusinessException("时长需为 1–86400 秒，波动强度需为 1–10，目标价格必须大于 0");
        TradingSymbol config = controlSymbol(id);
        if (!Boolean.TRUE.equals(config.getIsEnabled())) throw new BusinessException("请先启用该币种");
        if (target.stripTrailingZeros().scale() > PriceControlPath.precision(config)) throw new BusinessException("目标价格超出币种价格精度");
        if (!RandomMarketPath.enabled(config) && controls != null) {
            Map<String, Object> raw = getPrice(marketCode(config), sourceCategory(config));
            BigDecimal displayed = raw.get("price") instanceof Number ? controlledPrice(config, raw, System.currentTimeMillis()) : null;
            controls.start(config, raw, displayed,
                duration, target, intensity, randomOscillation, false, requestKey);
            clearControl(config); config.setControlEnabled(false); config.setControlPriceOffset(BigDecimal.ZERO);
            return saveControl(config);
        }
        if (PriceControlPath.running(config)) throw new BusinessException("自动控盘正在运行，请先停止任务");
        Map<String, Object> quote = requireControlQuote(config);
        long now = controlTime(config);
        BigDecimal start = controlledPrice(config, quote, now);
        if (start.signum() <= 0) throw new BusinessException("当前控盘价格无效，请先调整偏移");
        config.setControlStartPrice(start); config.setControlTargetPrice(target);
        config.setControlDurationSeconds(duration); config.setControlIntensity(intensity);
        config.setControlRandomOscillation(randomOscillation);
        config.setControlStartedAt(now); config.setControlCompletedAt(null); config.setControlEnabled(true);
        config.setControlRestoring(false);
        config.setControlPriceOffset(start.subtract(rawPrice(quote)));
        recordSimulationControl(config, now);
        return saveControl(config);
    }

    @Transactional
    public synchronized Map<String, Object> restoreControl(Long id, int duration, int intensity, boolean randomOscillation) {
        return restoreControl(id, duration, intensity, randomOscillation, null);
    }
    @Transactional
    public synchronized Map<String, Object> restoreControl(Long id, int duration, int intensity, boolean randomOscillation, String requestKey) {
        if (duration < 1 || duration > 86400 || intensity < 1 || intensity > 10)
            throw new BusinessException("时长需为 1–86400 秒，波动强度需为 1–10");
        TradingSymbol config = controlSymbol(id);
        Map<String, Object> quote = requireControlQuote(config);
        if (!RandomMarketPath.enabled(config) && controls != null) {
            controls.start(config, quote, controlledPrice(config, quote, System.currentTimeMillis()), duration, rawPrice(quote), intensity, randomOscillation, true, requestKey);
            clearControl(config); config.setControlEnabled(false); config.setControlPriceOffset(BigDecimal.ZERO);
            return saveControl(config);
        }
        long now = controlTime(config);
        BigDecimal start = controlledPrice(config, quote, now);
        if (start.signum() <= 0) throw new BusinessException("当前控盘价格无效，请一键恢复原始行情");
        BigDecimal offset = start.subtract(rawPrice(quote));
        if (offset.signum() == 0) return manualControl(id, false, BigDecimal.ZERO);
        config.setControlStartPrice(start); config.setControlTargetPrice(rawPrice(quote));
        config.setControlPriceOffset(offset); config.setControlEnabled(true); config.setControlRestoring(true);
        config.setControlDurationSeconds(duration); config.setControlIntensity(intensity);
        config.setControlRandomOscillation(randomOscillation);
        config.setControlStartedAt(now); config.setControlCompletedAt(null);
        recordSimulationControl(config, now);
        return saveControl(config);
    }

    @Transactional
    public synchronized Map<String, Object> manualControl(Long id, boolean enabled, BigDecimal offset) {
        if (offset == null || offset.abs().compareTo(new BigDecimal("10000000000000000")) >= 0 || offset.stripTrailingZeros().scale() > 16)
            throw new BusinessException("偏移值无效");
        TradingSymbol config = controlSymbol(id);
        if (enabled && rawPrice(requireControlQuote(config)).add(offset).signum() <= 0)
            throw new BusinessException("偏移后的价格必须大于 0");
        if (!RandomMarketPath.enabled(config) && controls != null) controls.stop(id, System.currentTimeMillis());
        long now = controlTime(config);
        BigDecimal continuation = RandomMarketPath.enabled(config)
            ? RandomMarketPath.price(config, now).subtract(RandomMarketPath.basePrice(config, now)) : BigDecimal.ZERO;
        clearControl(config);
        config.setControlEnabled(enabled); config.setControlPriceOffset(enabled ? offset : continuation);
        recordSimulationControl(config, now);
        return saveControl(config);
    }

    @Transactional
    public synchronized Map<String, Object> stopControl(Long id) {
        TradingSymbol config = controlSymbol(id);
        if (!RandomMarketPath.enabled(config) && controls != null) {
            controls.stopAndHold(id, System.currentTimeMillis());
            return controlStatus(config);
        }
        if (!PriceControlPath.running(config)) return controlStatus(config);
        Map<String, Object> quote = requireControlQuote(config);
        long now = controlTime(config);
        config.setControlPriceOffset(controlledPrice(config, quote, now).subtract(rawPrice(quote)));
        clearControl(config);
        recordSimulationControl(config, now);
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
        Runnable publish = () -> {
            synchronized (this) {
                TradingSymbol current = registry.get(saved.getSymbol());
                if (current != null && current.getRowVersion() > saved.getRowVersion()) return;
                // Publish only committed settings; a failed transaction must not change execution prices.
                Map<String, TradingSymbol> updated = new HashMap<>(registry);
                updated.put(saved.getSymbol(), saved);
                String alias = marketCode(saved);
                if (!"CryptoPerpetual".equals(sourceCategory(saved)) && (!updated.containsKey(alias) || Objects.equals(updated.get(alias).getId(), saved.getId()))) updated.put(alias, saved);
                if (exchangeStream != null) for (String category : Arrays.asList("Crypto", "CryptoPerpetual", "Metal"))
                exchangeStream.subscriptions(category, new HashSet<>(groups.get(category).codes),
                    (code, quote) -> acceptQuote(code, category, quote, "ws", QuoteState.time(quote.get("fetchedAt"))));
            registry = Collections.unmodifiableMap(updated);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else publish.run();
        return controlStatus(saved);
    }

    synchronized void completeControls() {
        long now = System.currentTimeMillis();
        if (controls != null) {
            try {
                for (Long id : controls.runningSymbols()) {
                    try {
                        controls.advance(id, now);
                        TradingSymbol config = controlSymbol(id);
                        controls.display(config, getPrice(marketCode(config), sourceCategory(config)), now);
                    }
                    catch (Exception failure) { log.error("Persistent control sampling failed for {}", id, failure); }
                }
            } catch (Exception failure) { log.error("Cannot read persistent control tasks", failure); }
        }
        Set<Long> visited = new HashSet<>();
        for (TradingSymbol snapshot : registry.values()) {
            if (!PriceControlPath.running(snapshot) || now < PriceControlPath.endsAt(snapshot) || !visited.add(snapshot.getId())) continue;
            try {
                TradingSymbol config = controlSymbol(snapshot.getId());
                if (!PriceControlPath.running(config) || now < PriceControlPath.endsAt(config)) continue;
                Map<String, Object> quote = requireControlQuote(config);
                boolean restoring = Boolean.TRUE.equals(config.getControlRestoring());
                config.setControlPriceOffset(restoring ? BigDecimal.ZERO : config.getControlTargetPrice().subtract(
                    RandomMarketPath.enabled(config) ? RandomMarketPath.basePrice(config, (PriceControlPath.endsAt(config) + 999) / 1000 * 1000) : rawPrice(quote)));
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
    @PreDestroy public void stop() { if (yahoo != null) yahoo.stop(); if (exchangeStream != null) exchangeStream.stop(); metadata.shutdownNow(); for (Group group : groups.values()) group.executor.shutdownNow(); }
}

