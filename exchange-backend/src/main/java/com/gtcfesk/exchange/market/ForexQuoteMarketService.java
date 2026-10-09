package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantJobRunner;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
    @Autowired private TenantJobRunner tenantJobs;
    private volatile boolean running;
    private final ThreadLocal<PersistentPriceControl.Prepared> commandPlan=new ThreadLocal<>();
    private final ConcurrentMap<Long, TenantState> tenantStates = new ConcurrentHashMap<>();
    private static class TenantState {
        final String epoch = UUID.randomUUID().toString();
        final Map<String, Map<String,Object>> published = new HashMap<>();
        final Map<String, Long> publishedAt = new HashMap<>();
        final Map<String, Group> groups = new LinkedHashMap<>();
        volatile Map<String, TradingSymbol> registry = Collections.emptyMap();
        long quoteVersion;
        final java.util.concurrent.atomic.AtomicLong snapshotSequence = new java.util.concurrent.atomic.AtomicLong();
        final Map<String, Long> publishedSequence = new HashMap<>();
        boolean started;
        int engineCursor, controlCursor;
        final ConcurrentMap<Long,Long> engineRetryAt=new ConcurrentHashMap<>();
        final ConcurrentMap<Long,Integer> engineFailures=new ConcurrentHashMap<>();
        TenantState() {
            for (String category : Arrays.asList("Crypto", "CryptoPerpetual", "Metal", "Forex", "US", "CFD", "Oil", "Other")) groups.put(category, new Group(category));
        }
    }
    private TenantState state() { return tenantStates.computeIfAbsent(TenantContext.requireTenantId(), id -> new TenantState()); }
    private void tenantJob(Long tenant, Runnable action) {
        try { tenantJobs.oneContext("market-tick",tenant, action); }
        catch (RuntimeException failure) { log.error("Tenant market job failed: tenant={}, type={}", tenant, failure.getClass().getSimpleName()); }
    }
    private void allTenants(Runnable action) {
        try { tenantJobs.eachContext("market-registry",id -> action.run()); }
        catch (RuntimeException failure) { log.error("Market tenant enumeration failed: {}", failure.getClass().getSimpleName()); }
    }
    private void startTenantGroups() {
        if (!running) return;
        TenantState state = state(); final Long tenant = TenantContext.requireTenantId();
        synchronized (state) {
            if (state.started) return;
            state.started = true;
        }
        // ponytail: bounded lane per tenant/category; consolidate executors if tenant count grows beyond this small deployment.
        for (Group group : state.groups.values()) group.executor.scheduleWithFixedDelay(() -> tenantJob(tenant, () -> tick(group)), 0, 100, TimeUnit.MILLISECONDS);
    }
    private void refreshStreamSubscriptions() {
        // Only raw public provider symbols are shared, never tenant configuration or display history.
        if (exchangeStream != null) for (String category : Arrays.asList("Crypto", "CryptoPerpetual", "Metal")) {
            Set<String> codes = new HashSet<>();
            for (TenantState tenant : tenantStates.values()) codes.addAll(tenant.groups.get(category).codes);
            exchangeStream.subscriptions(category, codes, (code, quote) -> {
                for (Long tenant : new ArrayList<>(tenantStates.keySet())) tenantJob(tenant,
                    () -> acceptQuote(code, category, quote, "ws", QuoteState.time(quote.get("fetchedAt"))));
            });
        }
        if (yahoo != null) {
            Set<String> subscribed = new HashSet<>();
            for (TenantState tenant : tenantStates.values()) for (Group item : tenant.groups.values())
                if ("Yahoo".equals(provider(item.category))) for (String code : item.codes) subscribed.add(MarketQuoteSource.mapSymbolToYahoo(code, item.category));
            yahoo.subscriptions(subscribed, subscribed, (code, quote) -> {
                for (Long tenant : new ArrayList<>(tenantStates.keySet())) tenantJob(tenant, () -> receiveYahoo(code, quote));
            });
        }
    }
    private Map<String,Object> publicStreamStatus(Map<String,Object> source) {
        Map<String,Object> safe = new LinkedHashMap<>();
        for (String key : Arrays.asList("enabled","activeProvider","connected","connectedAt","retryAt","error","lastFrame"))
            if (source.containsKey(key)) safe.put(key, source.get(key));
        return safe;
    }
    @Autowired private MarketQuoteSource source;
    @Autowired(required = false) private com.gtcfesk.exchange.admin.SystemConfigService systemConfigs;
    @Autowired(required = false) private MarketHoursService marketHours;
    @Autowired private MarketHttp http;
    @Autowired(required = false) private YahooQuoteStream yahoo;
    @Autowired(required = false) private ExchangeQuoteStream exchangeStream;
    @Autowired(required = false) private ExchangeQuoteSource exchangeSource;
    @Autowired private RedisMarketService redis;
    @Autowired private TradingSymbolRepository symbols;
    @Autowired private PersistentPriceControl controls;
    @Autowired private ControlHistoryStore controlHistory;
    @Autowired private ControlledKlineMerger klineMerger;
    @Autowired private SourceHistoryProjector sourceHistory;
    @Autowired private SourceHistoryGapRepair historyGapRepair;
    @Autowired(required=false) private HistorySourceRestore historyRestore;
    @Autowired(required=false) private com.gtcfesk.exchange.control.ControlAuditService historyGapAudit;
    @Value("${app.market.s4-source-projection-enabled:false}") private boolean sourceProjectionEnabled;
    @Value("${market.quote.max-age-ms:60000}") private long maxAgeMs = 60000;
    @Value("${market.quote.poll-ms:3000}") private long pollMs = 3000;
    private final Set<Long> engineActive=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor engines=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->new Thread(r,"market-engine"),new ThreadPoolExecutor.AbortPolicy());
    private final java.util.concurrent.atomic.AtomicLong engineRejected=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong engineDelayed=new java.util.concurrent.atomic.AtomicLong();
    private final ScheduledExecutorService metadata = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-symbols"));
    @Value("${market.virtual-trading.enabled:false}") private boolean virtualTrading;
    @Value("${market.control.v3.enabled:true}") private boolean v3Enabled = true;
    @Value("${market.control.v4.enabled:true}") private boolean v4Enabled = true;
    private static final int MAX_KLINES = 128, MAX_PENDING = 32;
    private static class KlineRequest {
        final String code, interval, key;
        final int limit;
        final Long endTime;
        final boolean sourceFeed;
        final SourceHistoryGapRepair.Window repair;
        final com.gtcfesk.exchange.control.ControlIdentity repairActor;
        int attempts;
        KlineRequest(String code, String interval, int limit) {
            this(code, interval, limit, null);
        }
        KlineRequest(String code, String interval, int limit, Long endTime) {
            this(code, interval, limit, endTime, false);
        }
        KlineRequest(String code, String interval, int limit, Long endTime, boolean sourceFeed) {
            this.code = code; this.interval = interval; this.limit = limit;
            this.endTime = endTime; this.sourceFeed = sourceFeed; this.repair = null; this.repairActor = null;
            this.key = code + ":" + interval + ":" + limit + (endTime == null ? "" : ":" + endTime);
        }
        KlineRequest(SourceHistoryGapRepair.Window repair) {
            this(repair, null);
        }
        KlineRequest(SourceHistoryGapRepair.Window repair, com.gtcfesk.exchange.control.ControlIdentity actor) {
            this.repair = repair; code = repair.code; interval = repair.period; limit = repair.limit;
            repairActor = actor;
            endTime = repair.continuous ? SourceHistoryGapRepair.end(repair.period, repair.to) - 1 : repair.to;
            sourceFeed = false; key = repair.key;
        }
        @Override public boolean equals(Object other) { return other instanceof KlineRequest && key.equals(((KlineRequest) other).key); }
        @Override public int hashCode() { return key.hashCode(); }
        boolean sameWindow(KlineRequest other) {
            return code.equals(other.code) && interval.equals(other.interval) && Objects.equals(endTime, other.endTime);
        }
        boolean covers(KlineRequest other) {
            return repair == null && other.repair == null && sameWindow(other) && limit >= other.limit;
        }
        static KlineRequest fromCacheKey(String key, KlineRequest query) {
            String prefix = query.code + ":" + query.interval + ":";
            if (!key.startsWith(prefix)) return null;
            String[] fields = key.substring(prefix.length()).split(":");
            if (fields.length < 1 || fields.length > 2) return null;
            try {
                KlineRequest candidate = new KlineRequest(query.code, query.interval, Integer.parseInt(fields[0]),
                        fields.length == 2 ? Long.parseLong(fields[1]) : null);
                return candidate.sameWindow(query) ? candidate : null;
            } catch (NumberFormatException ignored) { return null; }
        }
    }
    private static class Group {
        final String category;
        final ScheduledThreadPoolExecutor executor;
        volatile List<String> codes = Collections.emptyList();
        volatile List<String> sourceCodes = Collections.emptyList();
        int sourceCursor;
        long nextSourceKline;
        final Map<String, Map<String, Object>> quotes = new ConcurrentHashMap<>();
        final LinkedHashMap<String, KlineRequest> pending = new LinkedHashMap<>();
        final LinkedHashMap<String, Map<String, Object>> klines = new LinkedHashMap<String, Map<String, Object>>(16, .75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> e) { return size() > MAX_KLINES; }
        };
        final LinkedHashMap<String, SourceHistoryGapRepair.Receipt> repairs = new LinkedHashMap<String, SourceHistoryGapRepair.Receipt>(16, .75f, true) {
            protected boolean removeEldestEntry(Map.Entry<String, SourceHistoryGapRepair.Receipt> entry) { return size() > MAX_KLINES; }
        };
        final Object quoteLock = new Object();
        final Set<String> processingFailed = ConcurrentHashMap.newKeySet();
        final Set<String> redisRetry = ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicLong ingressSequence = new java.util.concurrent.atomic.AtomicLong();
        volatile String activeKey;
        volatile KlineRequest activeRequest;
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
    public void requestSymbolRefresh() {
        final Long tenant = TenantContext.requireTenantId();
        metadata.execute(() -> tenantJob(tenant, this::refreshSymbols));
    }
    private Group group(String category) {
        for (Group group : state().groups.values()) if (group.category.equalsIgnoreCase(category)) return group;
        return state().groups.get(category == null ? "Crypto" : "Other");
    }
    public static String sourceCategory(TradingSymbol symbol) {
        return symbol.getSourceCategory();
    }
    public static String marketCode(TradingSymbol symbol) {
        return symbol.getAlltickSymbol() == null || symbol.getAlltickSymbol().isEmpty() ? symbol.getSymbol() : symbol.getAlltickSymbol();
    }
    @Value("${market.engine.auto-start:true}") private boolean autoStart=true;
    @PostConstruct public void start() {
        if(!autoStart) return; // Explicit owned acceptance invokes writers itself; normal lifecycle remains enabled.
        running = true;
        metadata.scheduleWithFixedDelay(() -> allTenants(() -> scheduleEngine(true)), 0, 30, TimeUnit.SECONDS);
        metadata.scheduleWithFixedDelay(() -> allTenants(() -> scheduleEngine(false)), 1, 1, TimeUnit.SECONDS);
    }
    /** Two bounded engine lanes; one tenant cannot hold the orchestration thread or occupy both lanes. */
    private void scheduleEngine(boolean refresh) {
        Long tenant=TenantContext.requireTenantId();
        if(!engineActive.add(tenant)){engineDelayed.incrementAndGet();return;}
        try {engines.execute(()->{try{tenantJob(tenant,refresh?this::refreshSymbols:this::completeControls);}finally{engineActive.remove(tenant);}});}
        catch(RejectedExecutionException full){engineActive.remove(tenant);engineRejected.incrementAndGet();}
    }
    public Map<String,Object> engineMetrics() {
        Map<String,Object> metrics=new LinkedHashMap<>();
        metrics.put("engineWorkers",engines.getActiveCount());metrics.put("engineQueue",engines.getQueue().size());
        metrics.put("engineDelayed",engineDelayed.get());metrics.put("engineRejected",engineRejected.get());metrics.put("symbolsPerTurn",16);return metrics;
    }
    public void refreshSymbols() {
        TenantContext.requireTenantId();
        try {
            Map<String, TradingSymbol> updated = new HashMap<>();
            Map<Group, Set<String>> codes = new HashMap<>();
            Map<Group, Set<String>> sourceCodes = new HashMap<>();
            for (TradingSymbol loaded : symbols.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId())) {
                TradingSymbol symbol = copySymbol(loaded);
                if (controls != null && !RandomMarketPath.enabled(symbol) && PriceControlPath.running(symbol)) {
                    TradingSymbol legacy = symbol;
                    symbol = controlHistory.transaction(() -> {
                        controls.importLegacy(legacy);
                        clearControl(legacy); legacy.setControlEnabled(false); legacy.setControlPriceOffset(BigDecimal.ZERO);
                        return copySymbol(symbols.saveAndFlush(legacy));
                    });
                }
                updated.put(symbol.getSymbol(), symbol);
                if (!"CryptoPerpetual".equals(sourceCategory(symbol))) updated.putIfAbsent(marketCode(symbol), symbol);
                codes.computeIfAbsent(group(sourceCategory(symbol)), g -> new LinkedHashSet<>()).add(marketCode(symbol));
                if (sourceProjectionEnabled && Boolean.TRUE.equals(symbol.getIsEnabled())
                        && !RandomMarketPath.enabled(symbol) && !Boolean.TRUE.equals(symbol.getControlEnabled()))
                    sourceCodes.computeIfAbsent(group(sourceCategory(symbol)), g -> new LinkedHashSet<>()).add(marketCode(symbol));
                QuoteCurrencyConversion conversion=QuoteCurrencyConversion.route(symbol.getQuoteCurrency(),symbol.getMarketSource());
                if(conversion!=null) {
                    codes.computeIfAbsent(group(conversion.category),g -> new LinkedHashSet<>()).add(conversion.code);
                    if("Forex".equals(conversion.category)) codes.computeIfAbsent(group("Forex"),g -> new LinkedHashSet<>()).add(conversion.code.replace("USD=X","=X"));
                }
                if (com.gtcfesk.exchange.trade.FxContractRules.isForex(symbol)
                        && !"USD".equals(symbol.getBaseCurrency()) && !"USD".equals(symbol.getQuoteCurrency())) {
                    codes.computeIfAbsent(group("Forex"), g -> new LinkedHashSet<>()).add(symbol.getBaseCurrency()+"USD=X");
                    codes.computeIfAbsent(group("Forex"), g -> new LinkedHashSet<>()).add(symbol.getBaseCurrency()+"=X");
                }
            }
            Set<String> conversionCurrencies = new LinkedHashSet<>(com.gtcfesk.exchange.admin.SystemConfigService.conversionCurrencies(
                systemConfigs == null ? null : systemConfigs.getConfigValue("market.conversion.currencies")));
            // Keep currencies required by existing deposit/settlement flows available as well.
            conversionCurrencies.addAll(com.gtcfesk.exchange.user.FiatCurrencyService.CURRENCIES);
            for (String currency : conversionCurrencies) {
                QuoteCurrencyConversion route = QuoteCurrencyConversion.route(currency, "yahoo");
                if (route != null) codes.computeIfAbsent(group(route.category), g -> new LinkedHashSet<>()).add(route.code);
            }
            for (Group group : state().groups.values()) {
                List<String> list = new ArrayList<>(codes.getOrDefault(group, Collections.emptySet()));
                for (String code : list) if (!group.quotes.containsKey(code)) {
                    Map<String, Object> saved = redis.getPrice(group.category + ":" + code);
                    if (QuoteState.valid(saved)) {
                        saved.put("sourceAvailable", false); // A restarted process must confirm the source first.
                        group.quotes.putIfAbsent(code, saved);
                    }
                }
                group.codes = Collections.unmodifiableList(list);
                group.sourceCodes = Collections.unmodifiableList(new ArrayList<>(sourceCodes.getOrDefault(group, Collections.emptySet())));
            }

            TenantState tenantState = state();
            afterCommit(() -> {
                synchronized (tenantState) {
                    // A refresh that started before a command must not overwrite its newer revision.
                    for (Map.Entry<String,TradingSymbol> entry : tenantState.registry.entrySet()) {
                        TradingSymbol loaded = updated.get(entry.getKey());
                        if (loaded != null && entry.getValue().getRowVersion() > loaded.getRowVersion()) updated.put(entry.getKey(), entry.getValue());
                    }
                    tenantState.registry = Collections.unmodifiableMap(new HashMap<>(updated));
                    tenantState.published.keySet().retainAll(updated.keySet());
                    tenantState.publishedAt.keySet().retainAll(updated.keySet());
                    tenantState.publishedSequence.keySet().retainAll(updated.keySet());
                }
            });
            for (TradingSymbol symbol : new HashSet<>(updated.values())) conversion(symbol.getQuoteCurrency(),symbol.getMarketSource());
            for (String currency : conversionCurrencies) conversion(currency, "yahoo");
            startTenantGroups();
            refreshStreamSubscriptions();
        } catch (Exception failure) { log.warn("Market symbol refresh failed ({})", failure.getClass().getSimpleName()); }
    }
    private void tick(Group group) {
        synchronized (group.quoteLock) {
            for (String code : new ArrayList<>(group.redisRetry)) enqueuePrice(group, code, group.quotes.get(code));
        }
        long now = System.currentTimeMillis();
        if (now < group.nextAllowed) return;
        KlineRequest preferredRepair = null;
        synchronized (group) {
            if (now >= group.nextKlines) for (KlineRequest queued : group.pending.values())
                if (queued.repair != null) { preferredRepair = queued; break; }
        }
        try {
            List<String> requested = new ArrayList<>();
            for (String code : group.codes) if (!streamHealthy(group.category, code)) requested.add(code);
            if (preferredRepair == null && now >= group.nextQuotes && !requested.isEmpty()) {
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
            }
            KlineRequest request;
            if (System.currentTimeMillis() < group.nextKlines) return;
            enqueueSourceKline(group, System.currentTimeMillis());
            synchronized (group) {
                if (preferredRepair != null && group.pending.containsKey(preferredRepair.key)) {
                    request = group.pending.remove(preferredRepair.key);
                } else {
                    Iterator<KlineRequest> iterator = group.pending.values().iterator();
                    if (!iterator.hasNext()) return;
                    request = iterator.next(); iterator.remove();
                }
                group.activeKey = request.key; group.activeRequest = request;
            }
            try {
                if (request.repair != null) {
                    repairSourceWindow(group, request);
                    group.klineFailures = 0; group.klineError = null;
                    group.nextKlines = System.currentTimeMillis() + Math.max(1000, pollMs);
                    return;
                }
                http.begin();
                Map<String, Object> result = request.endTime == null
                        ? source.getKline(request.code, request.interval, request.limit, group.category)
                        : source.getKline(request.code, request.interval, request.limit, group.category, request.endTime);
                validateKline(result);
                if (request.sourceFeed && ControlHistoryStore.rows(result).size() > 500)
                    throw new MarketHttp.Failure("source_kline_response_too_large", 0);
                result.put("fetchedAt", System.currentTimeMillis());
                result.put("status", "available");
                List<Long> targets = new ArrayList<>();
                for (TradingSymbol config : new HashSet<>(state().registry.values()))
                    if (marketCode(config).equals(request.code) && group(sourceCategory(config)) == group) targets.add(config.getId());
                if (controlHistory != null) controlHistory.sourceCandles(targets, request.interval, ControlHistoryStore.rows(result), System.currentTimeMillis());
                afterCommit(() -> { synchronized (group) { group.klines.put(request.key, result); } });
                group.klineFailures = 0; group.klineError = null;
            } catch (Exception failure) {
                group.klineFailures = Math.min(16, group.klineFailures + 1);
                long delay = Math.min(30000, 2000L << Math.min(4, group.klineFailures - 1));
                if (failure instanceof MarketHttp.Failure) delay = Math.max(delay, ((MarketHttp.Failure) failure).retryAfterMs);
                group.nextKlines = System.currentTimeMillis() + delay;
                group.klineError = failure instanceof MarketHttp.Failure ? failure.getMessage() : "invalid_kline";
                if (request.repair != null) synchronized (group) {
                    if (++request.attempts < 6) group.pending.putIfAbsent(request.key, request);
                    else group.repairs.put(request.key, new SourceHistoryGapRepair.Receipt(Collections.singletonList(
                        SourceHistoryGapRepair.gap(request.repair.from, SourceHistoryGapRepair.end(request.interval, request.repair.to) - 1, "source_fetch_or_write_failed", false)), 0));
                }
                // A broken chart endpoint must not invalidate successful ticker snapshots.
                // Rate limiting still applies to both operations sharing this source lane.
                if ("http_429".equals(group.klineError)) {
                    if (request.repair == null) fail(group, failure, true);
                    else group.nextAllowed = Math.max(group.nextAllowed, group.nextKlines); // Shared rate gate, not a quote/control outage.
                }
                if (group.klineFailures == 1 || System.currentTimeMillis() - group.lastKlineLog >= 30000) {
                    log.warn("Market {} K-line unavailable: {}; retry in {}ms", group.category, group.klineError, delay);
                    group.lastKlineLog = System.currentTimeMillis();
                }
            } finally { http.end(); group.activeKey = null; group.activeRequest = null; }
        } catch (Exception failure) { fail(group, failure, true); }
    }
    /** Writer-only fair prefetch; display requests never create SOURCE work. */
    private void enqueueSourceKline(Group group, long now) {
        if (!sourceProjectionEnabled || controlHistory == null || now < group.nextSourceKline) return;
        List<String> configured = group.sourceCodes;
        if (configured.isEmpty()) return;
        synchronized (group) {
            if (group.pending.size() >= MAX_PENDING) return;
            // ponytail: reuse the single provider lane; at most 16 checks and one 500-minute request per turn.
            for (int checked = 0; checked < Math.min(16, configured.size()); checked++) {
                String code = configured.get(Math.floorMod(group.sourceCursor++, configured.size()));
                KlineRequest request = new KlineRequest(code, "1m", 500, null, true);
                Map<String,Object> saved = group.klines.get(request.key);
                if (!group.codes.contains(code) || request.key.equals(group.activeKey) || group.pending.containsKey(request.key)
                        || saved != null && now - QuoteState.time(saved.get("fetchedAt")) < 15000) continue;
                group.pending.put(request.key, request);
                group.nextSourceKline = now + Math.max(1000, pollMs);
                return;
            }
            group.nextSourceKline = now + 1000;
        }
    }
    private void receiveYahoo(String yahooSymbol, Map<String,Object> quote) {
        for (Group group : state().groups.values()) if ("Yahoo".equals(provider(group.category)))
            for (String code : group.codes) if (yahooSymbol.equals(MarketQuoteSource.mapSymbolToYahoo(code, group.category)))
                acceptQuote(code, group.category, quote, "ws", QuoteState.time(quote.get("fetchedAt")));
    }
    /** Database work never holds a Java publication monitor. */
    boolean acceptQuote(String code, String category, Map<String,Object> price, String transport, long receivedAt) {
        Group group = group(category);
        if (!group.codes.contains(code) || !QuoteState.valid(price)) return false;
        long sequence = group.ingressSequence.incrementAndGet();
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
        saved.put("ingressSequence", sequence);
        try {
            // Conversion-only subscriptions have no source-history symbol; bind raw receipts to their actual consumers.
            List<TradingSymbol> conversionTargets=conversionTargets(code,group.category);
            if(!conversionTargets.isEmpty() && !controls.conversionQuotes(conversionTargets,code,group.category,saved,receivedAt,
                    config -> controlSymbol(config.getId()))) return false;
        } catch(RuntimeException failure) {
            group.processingFailed.add(code);
            synchronized(group.quoteLock) {markUnavailable(group,code);}
            throw failure;
        }
        if (!duplicate || group.processingFailed.contains(code)) {
            try {
                List<TradingSymbol> targets = new ArrayList<>();
                for (TradingSymbol config : new HashSet<>(state().registry.values()))
                    if (controls != null && marketCode(config).equals(code) && group(sourceCategory(config)) == group && !RandomMarketPath.enabled(config)) targets.add(config);
                if (!targets.isEmpty()) {
                    long persisted = controls.sourceQuotes(targets, QuoteState.view(saved, maxAgeMs), receivedAt, config -> controlSymbol(config.getId()));
                    if (persisted <= 0) return false; // Rejected by durable ordering, including after an empty-cache restart.
                    saved.put("sourceSequence", persisted);
                }
            } catch (RuntimeException failure) {
                group.processingFailed.add(code);
                unavailable(group, code); throw failure;
            }
        }
        Map<String,Object> snapshot = Collections.unmodifiableMap(new HashMap<>(saved));
        afterCommit(() -> {
            synchronized (group.quoteLock) {
                Map<String,Object> current = group.quotes.get(code);
                if (current != null) {
                    long currentTime = QuoteState.time(current.get("timestamp"));
                    long persisted = QuoteState.time(snapshot.get("sourceSequence")), committed = QuoteState.time(current.get("sourceSequence"));
                    if (time < currentTime || time == currentTime && (persisted > 0 && committed > 0 && persisted < committed
                            || persisted == committed && sequence < QuoteState.time(current.get("ingressSequence")))) return;
                }
                group.processingFailed.remove(code);
                group.quotes.put(code, snapshot);
                // savePrice only replaces a bounded per-key pending value; its writer performs network I/O later.
                enqueuePrice(group, code, snapshot);
            }
        });
        return true;
    }
    private void enqueuePrice(Group group, String code, Map<String,Object> quote) {
        if (redis == null || quote == null) return;
        try { redis.savePrice(group.category + ":" + code, quote); group.redisRetry.remove(code); }
        catch (RuntimeException failure) {
            group.redisRetry.add(code);
            log.warn("Committed quote notification pending: {}", group.category);
        }
    }
    static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }
    private List<TradingSymbol> conversionTargets(String code,String category) {
        List<TradingSymbol> targets=new ArrayList<>();
        if(controls!=null) for(TradingSymbol config:new HashSet<>(state().registry.values()))
            if(FundingConversions.matches(config,code,category)) targets.add(config);
        return targets;
    }
    private void unavailable(Group group, String code) {
        Map<String,Object> failed;
        synchronized (group.quoteLock) {
            Map<String,Object> old = group.quotes.get(code);
            if (old != null && "ws".equals(old.get("transport")) && streamConnected(group.category)
                    && Boolean.TRUE.equals(QuoteState.view(old, maxAgeMs).get("available"))) return;
            markUnavailable(group, code);
            failed=group.quotes.get(code);
        }
        // A durable outage notification never runs under the in-memory publication monitor.
        List<TradingSymbol> targets=conversionTargets(code,group.category);
        if(failed!=null && !targets.isEmpty()) controls.conversionQuotes(targets,code,group.category,failed,
            QuoteState.time(failed.get("fetchedAt")),config -> controlSymbol(config.getId()));
    }
    private void markUnavailable(Group group, String code) {
        Map<String,Object> old = group.quotes.get(code);
        if (old == null) return;
        Map<String,Object> saved = new HashMap<>(old); saved.put("sourceAvailable", false);
        group.quotes.put(code, Collections.unmodifiableMap(saved));
        enqueuePrice(group, code, saved);
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
        // Connection health is independent of quote freshness and missing source values.
        String error = group.error;
        quote.put("sourceConnectionFailed", error != null && ("timeout".equals(error)
                || "connection_failure".equals(error) || "rate_limited".equals(error) || error.startsWith("http_"))
                && !streamHealthy(category, code));
        quote.put("retryAt", group.nextAllowed);
        return quote;
    }
    public Map<String, Map<String, Object>> getBatchPrices(List<String> codes) { return getBatchPrices(codes, "Crypto"); }
    public Map<String, Map<String, Object>> getBatchPrices(List<String> codes, String category) {
        TenantContext.requireTenantId();
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (codes != null) for (String code : codes) result.put(code, getPrice(code, category));
        return result;
    }
    public Map<String, Object> internalPrice(String code) {
        TradingSymbol config = state().registry.get(code);
        if(controls!=null && config!=null) {
            Map<String,Object> committed=controls.display(config,Collections.emptyMap(),System.currentTimeMillis());
            committed.put("symbol",code);committed.put("marketRevision",config.getRowVersion());
            if(RandomMarketPath.enabled(config) && !virtualTrading){committed.put("available",false);committed.put("tradeAvailable",false);}
            return committed;
        }
        if (RandomMarketPath.enabled(config)) {
            Map<String, Object> simulated = RandomMarketPath.quote(config, System.currentTimeMillis());
            if (durableFlow(config)) simulated = controls.display(config, simulationBaseQuote(config, System.currentTimeMillis()), System.currentTimeMillis());
            simulated.put("symbol", code); simulated.put("marketRevision", config.getRowVersion());
            if (!virtualTrading) { simulated.put("available", false); simulated.put("status", "unavailable"); }
            return applyMarketHours(config, simulated);
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
                || "RUNNING".equals(quote.get("controlState")) || "HOLDING".equals(quote.get("controlState"))
                || "RECOVERING".equals(quote.get("controlState")) || "WAITING_SOURCE".equals(quote.get("controlState")) && Boolean.TRUE.equals(quote.get("controlRunning")));
        quote.put("controlActive", controlled);
        if (controlled && QuoteState.valid(quote)) {
            quote.put("available", true); quote.put("tradeAvailable", true);
            quote.put("stale", false); quote.put("status", "available");
            quote.put("executionExpiresAt", QuoteState.time(quote.get("timestamp")) + maxAgeMs); quote.put("expiresAt", quote.get("executionExpiresAt"));
        }
        if (!QuoteState.valid(quote)) { quote.put("available", false); quote.put("status", "unavailable"); }
        quote.put("symbol", code);
        quote.put("marketRevision", config == null ? 0 : config.getRowVersion());
        return applyMarketHours(config, quote);
    }
    public void requireMarketOpen(TradingSymbol symbol) { if(marketHours!=null)marketHours.requireOpen(symbol); }
    public void requireMarketWindow(TradingSymbol symbol,int seconds) { if(marketHours!=null)marketHours.requireWindow(symbol,seconds); }
    public void requireMarketOpen(String code) {TradingSymbol symbol=state().registry.get(code);if(symbol!=null)requireMarketOpen(symbol);}
    public boolean isMarketClosed(String code) {
        TradingSymbol symbol=state().registry.get(code);return symbol!=null&&marketHours!=null&&marketHours.status(symbol).closed;
    }
    private Map<String,Object> applyMarketHours(TradingSymbol symbol,Map<String,Object> quote) {
        if(symbol==null||marketHours==null)return quote;
        MarketHoursConfig.Status status=marketHours.status(symbol);
        quote.put("marketClosed",status.closed);quote.put("marketReason",status.reason);
        quote.put("marketNextChangeAt",status.nextChangeAt);quote.put("marketHoursRevision",status.revision);
        if(status.closed){quote.put("available",false);quote.put("tradeAvailable",false);quote.put("status","closed");}
        return quote;
    }
    public Map<String,Object> conversion(String currency,String source) {
        TenantContext.requireTenantId();
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

    /** Trading P&L must use a fresh, uncontrolled rate; fiat deposits retain their fixed-rate cache. */
    public Map<String,Object> contractConversion(String currency,String source) {
        TenantContext.requireTenantId();
        if(QuoteCurrencyConversion.fixed(currency)) return conversion(currency,source);
        Map<String,Object> result=new HashMap<>();
        result.put("conversionAvailable",false);result.put("quoteToUsdRate",null);result.put("conversionExpiresAt",0L);
        QuoteCurrencyConversion route=QuoteCurrencyConversion.route(currency,source);
        if(route==null)return result;
        String inverse="Forex".equals(route.category)?route.code.replace("USD=X","=X"):null;
        String unit=route.code.replace("USD=X","");
        List<String> codes=inverse==null?Collections.singletonList(route.code)
            :Arrays.asList("EUR","GBP","AUD","NZD").contains(unit)?Arrays.asList(route.code,inverse):Arrays.asList(inverse,route.code);
        for(String code:codes) {
            Map<String,Object> raw=getPrice(code,route.category);
            Map<String,Object> fresh=QuoteState.view(raw,60000);
            if(!Boolean.TRUE.equals(fresh.get("available")) || Boolean.FALSE.equals(raw.get("available")))continue;
            BigDecimal price=new BigDecimal(raw.get("price").toString());
            BigDecimal rate=code.equals(inverse)?BigDecimal.ONE.divide(price,24,java.math.RoundingMode.HALF_UP):price;
            result.put("quoteToUsdRate",rate.multiply(route.scale));result.put("conversionAvailable",true);
            result.put("conversionExpiresAt",fresh.get("expiresAt"));result.put("conversionTimestamp",raw.get("timestamp"));
            result.put("conversionSymbol",code);result.put("conversionMode","LIVE_CONTRACT");return result;
        }
        return result;
    }
    public BigDecimal requireContractConversionRate(String currency,String source) {
        Object value=contractConversion(currency,source).get("quoteToUsdRate");
        if(value==null)throw new BusinessException("合约换算汇率暂不可用或超过60秒，请稍后重试");
        return new BigDecimal(value.toString());
    }

    /** Standard FX margin is in BASE currency; never use the hours-long settlement cache here. */
    public BigDecimal fxMarginRate(String base, String quote, BigDecimal orderPrice) {
        TenantContext.requireTenantId();
        if ("USD".equals(base)) return BigDecimal.ONE;
        if ("USD".equals(quote) && orderPrice != null && orderPrice.signum() > 0) return orderPrice;
        return requireContractConversionRate(base,"yahoo");
    }
    <T> T readSnapshot(java.util.function.Supplier<T> operation) {
        return controlHistory == null ? operation.get() : controlHistory.readConsumerSnapshot(operation);
    }
    public <T> T readOrderSnapshot(java.util.function.Supplier<T> operation) {
        return controlHistory == null ? operation.get() : controlHistory.readOrderSnapshot(operation);
    }
    public boolean knownSymbol(String symbol) { return state().registry.containsKey(symbol); }
    /** Shared, versioned display snapshot. Trading always revalidates via freshPrice(). */
    public Map<String,Object> snapshotPrice(String symbol) {
        TenantState tenantState = state();
        Map<String,TradingSymbol> registry = tenantState.registry;
        TradingSymbol config = registry.get(symbol);
        if (config == null) return internalPrice(symbol);
        long now = System.currentTimeMillis();
        long sequence = tenantState.snapshotSequence.incrementAndGet();
        boolean committedBoundary = controlHistory != null && TransactionSynchronizationManager.isActualTransactionActive()
            && TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        Map<String,Object> previous;
        synchronized (tenantState) {
            previous = tenantState.published.get(symbol);
            if (!committedBoundary && previous != null && now - tenantState.publishedAt.getOrDefault(symbol, 0L) < 100
                    && now < QuoteState.time(previous.get("expiresAt"))) return previous;
        }
        Map<String,Object> next = new HashMap<>(internalPrice(symbol));
        next.putAll(contractConversion(config.getQuoteCurrency(),config.getMarketSource()));
        if (com.gtcfesk.exchange.trade.FxContractRules.isForex(config)) {
            try {
                next.put("marginBaseToUsdRate",fxMarginRate(config.getBaseCurrency(),config.getQuoteCurrency(),new BigDecimal(String.valueOf(next.get("price")))));
                next.put("marginRateExpiresAt",now+5000);
            } catch (RuntimeException unavailable) {
                next.put("marginBaseToUsdRate",null); next.put("marginRateExpiresAt",0L);
            }
        }
        next.put("quoteCurrency",config.getQuoteCurrency());
        next.put("epoch", tenantState.epoch);
        synchronized (tenantState) {
            Map<String,Object> current = tenantState.published.get(symbol);
            if (!committedBoundary && (tenantState.registry != registry || sequence < tenantState.publishedSequence.getOrDefault(symbol, 0L)))
                return current == null ? Collections.unmodifiableMap(next) : current;
            if(controls==null) {
                next.put("quoteVersion", current == null ? 0L : current.get("quoteVersion"));
                if (current == null || !next.equals(current)) next.put("quoteVersion", ++tenantState.quoteVersion);
            }
        }
        Map<String,Object> result = Collections.unmodifiableMap(next);
        // A display RR result must not overwrite the shared newer publication cache.
        if (committedBoundary) return result;
        afterCommit(() -> {
            synchronized (tenantState) {
                if (tenantState.registry == registry && sequence >= tenantState.publishedSequence.getOrDefault(symbol, 0L)) {
                    tenantState.published.put(symbol, result); tenantState.publishedAt.put(symbol, now);
                    tenantState.publishedSequence.put(symbol, sequence);
                }
            }
        });
        return result;
    }
    public BigDecimal freshPrice(String code) {
        Map<String, Object> quote = internalPrice(code);
        return Boolean.TRUE.equals(quote.get("available")) ? BigDecimal.valueOf(((Number) quote.get("price")).doubleValue()) : null;
    }
    public Map<String, BigDecimal> freshPrices() {
        Map<String, BigDecimal> result = new HashMap<>();
        for (TradingSymbol config : state().registry.values()) {
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
        return cachedKline(code,interval,limit,category,endTime,true);
    }
    private Map<String,Object> cachedKline(String code,String interval,Integer limit,String category,Long endTime,boolean enqueue) {
        Group group = group(category);
        KlineRequest request = new KlineRequest(code, interval, Math.min(1000, Math.max(1, limit == null ? 100 : limit)), endTime);
        Map<String, Object> saved;
        Map<String, Object> data = new HashMap<>();
        String status;
        boolean pending, requestedAfterCommit = false;
        synchronized (group) {
            saved = group.klines.get(request.key);
            KlineRequest covered = request;
            long now = System.currentTimeMillis(), maxAge = endTime == null ? 15000 : 300000;
            boolean fresh = saved != null && now - QuoteState.time(saved.get("fetchedAt")) < maxAge;
            int savedRows = saved == null ? 0 : ControlHistoryStore.rows(saved).size();
            boolean full = savedRows >= request.limit, partialCoverage = false;
            for (Map.Entry<String, Map<String, Object>> entry : group.klines.entrySet()) {
                KlineRequest candidate = KlineRequest.fromCacheKey(entry.getKey(), request);
                if (candidate == null) continue;
                int candidateRows = ControlHistoryStore.rows(entry.getValue()).size();
                if (candidateRows == 0) continue;
                boolean candidateFresh = now - QuoteState.time(entry.getValue().get("fetchedAt")) < maxAge;
                if (candidateRows < request.limit || !candidate.covers(request)) {
                    if (!full && candidateRows > savedRows) {
                        saved = entry.getValue(); savedRows = candidateRows; fresh = candidateFresh;
                        partialCoverage = !candidate.equals(request);
                    }
                    continue;
                }
                if (!full || candidateFresh && !fresh || candidateFresh == fresh && candidate.limit < covered.limit) {
                    saved = entry.getValue(); covered = candidate; fresh = candidateFresh; full = true; partialCoverage = false;
                }
            }
            status = fresh ? "available" : saved == null ? "unavailable" : "stale";
            // Only configured products can create work; request traffic cannot grow the symbol registry.
            KlineRequest work = covered;
            if (enqueue && (!fresh || partialCoverage)) {
                if (group.activeRequest != null && group.activeRequest.covers(request)) work = group.activeRequest;
                else for (KlineRequest queued : group.pending.values()) if (queued.covers(request)) { work = queued; break; }
                if (group.codes.contains(code) && group.pending.size() < MAX_PENDING && !work.key.equals(group.activeKey)) {
                    if (TransactionSynchronizationManager.isActualTransactionActive()
                            && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                        KlineRequest scheduled = work;
                        // The legacy fetch notification occurs only after the display snapshot commits; no SQL or provider I/O here.
                        afterCommit(() -> { synchronized (group) {
                            boolean active = scheduled.key.equals(group.activeKey)
                                    || group.activeRequest != null && group.activeRequest.covers(scheduled);
                            boolean queued = group.pending.values().stream().anyMatch(item -> item.covers(scheduled));
                            if (!active && !queued && group.codes.contains(scheduled.code) && group.pending.size() < MAX_PENDING) {
                                group.pending.putIfAbsent(scheduled.key, scheduled); queued = true;
                            }
                            boolean healthy = group.failures == 0 && group.klineFailures == 0;
                            data.put("pending", healthy && (active || queued));
                            data.put("queueState", !healthy ? "unavailable" : active ? "running" : queued ? "queued"
                                    : !group.codes.contains(scheduled.code) ? "blocked" : group.pending.size() >= MAX_PENDING ? "queue_full" : "unavailable");
                        } });
                        requestedAfterCommit = true;
                    } else group.pending.putIfAbsent(work.key, work);
                }
            }
            pending = group.failures == 0 && group.klineFailures == 0
                    && (requestedAfterCommit || group.pending.containsKey(work.key) || work.key.equals(group.activeKey));
            if (enqueue && !pending && (!fresh || partialCoverage) && group.failures == 0 && group.klineFailures == 0
                    && group.codes.contains(code) && group.pending.size() >= MAX_PENDING) data.put("queueState", "queue_full");
        }
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (saved != null) {
            List<Map<String, Object>> sourceRows = ControlHistoryStore.rows(saved);
            for (int i = Math.max(0, sourceRows.size() - request.limit); i < sourceRows.size(); i++) rows.add(new HashMap<>(sourceRows.get(i)));
        }
        if (group.failures > 0 || group.klineFailures > 0) status = saved == null ? "unavailable" : "stale";
        data.put("code", code); data.put("kline_list", rows); data.put("status", status);
        data.put("fetchedAt", saved == null ? null : saved.get("fetchedAt"));
        data.put("retryAt", Math.max(group.nextAllowed, group.nextKlines));
        data.put("pending", pending);
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
        return readSnapshot(() -> internalKlineSnapshot(symbol, interval, limit));
    }
    private Map<String,Object> internalKlineSnapshot(String symbol, String interval, Integer limit) {
        TradingSymbol config = state().registry.get(symbol);
        Map<String,Object> projected=projectedSourceKline(config,symbol,interval,limit,null);
        if(projected!=null) return inspectLatestGaps(config, interval, limit, projected);
        if (virtualTrading && RandomMarketPath.enabled(config))
            return durableSimulationKline(config, interval, limit, null);
        Map<String, Object> result = cachedKline(config == null ? symbol : marketCode(config), interval, limit,
            config == null ? "Crypto" : sourceCategory(config),null,true);
        return inspectLatestGaps(config, interval, limit, mergeControlKline(config, symbol, interval, limit, null, result));
    }
    /** Historical source candles are not rewritten using today's configured price offset. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> historicalKline(String symbol, String interval, int limit, long endTime) {
        TradingSymbol config = state().registry.get(symbol);
        if (config == null) throw new IllegalArgumentException("Unknown symbol");
        if (historyGapRepair != null && controlHistory != null && klineMerger != null && !RandomMarketPath.enabled(config))
            return repairHistoricalKline(config, symbol, interval, limit, endTime);
        Map<String,Object> projected=projectedSourceKline(config,symbol,interval,limit,endTime);
        if(projected!=null) return projected;
        if (virtualTrading && RandomMarketPath.enabled(config))
            return durableSimulationKline(config, interval, limit, endTime);
        Map<String, Object> result = cachedKline(marketCode(config), interval, limit, sourceCategory(config), endTime,true);
        Map<String, Object> data = (Map<String, Object>) result.get("data");
        data.put("symbol", symbol);
        data.put("source", provider(sourceCategory(config)));
        return mergeControlKline(config, symbol, interval, limit, endTime, result);
    }
    /** GET only inspects committed facts; provider work is notified after the read snapshot commits. */
    @SuppressWarnings("unchecked")
    private Map<String,Object> repairHistoricalKline(TradingSymbol config, String symbol, String interval, int limit, long cursor) {
        Group group = group(sourceCategory(config));
        Map<String,Object> raw = cachedKline(marketCode(config), interval, limit, sourceCategory(config), cursor, false);
        Map<String,Object> rawData = (Map<String,Object>)raw.get("data");
        rawData.put("symbol", symbol); rawData.put("source", provider(sourceCategory(config)));
        Map<String,Object> archived = controlHistory.historyOrdering.readExact(config.getId(), interval, limit, cursor,
            ExchangeQuoteSource.supports(sourceCategory(config)), raw, false);
        if (archived != null) return archived; // Full sealed response remains byte/field authoritative, including its envelope.
        SourceHistoryGapRepair.Window window = historyGapRepair.inspect(config, interval, limit, cursor,
            provider(sourceCategory(config)), ControlHistoryStore.rows(raw), sourceProjectionEnabled);
        Map<String,Object> repair = inspectRepairWindow(window, true, false);
        List<Map<String,Object>> gaps = (List<Map<String,Object>>)repair.get("gaps");
        // Raw provider/cache rows cannot replace durable complete/partial/conflicting facts or controlled display rows.
        rawData.put("kline_list", Collections.emptyList()); rawData.put("pending", false);
        Map<String,Object> result = projectedSourceKline(config, symbol, interval, limit, cursor);
        if (result == null) result = mergeControlKline(config, symbol, interval, limit, cursor, raw);
        Map<String,Object> data = (Map<String,Object>)result.get("data");
        boolean displayPending = Boolean.TRUE.equals(data.get("pending"));
        if (window.from == 946684800000L) data.put("exhausted", true);
        data.put("historyRepair", repair); data.put("retryAt", Math.max(group.nextAllowed, group.nextKlines));
        Runnable finish = () -> {
            boolean pending = Boolean.TRUE.equals(repair.get("pending")), terminal = !pending && !gaps.isEmpty();
            if (!terminal) pending |= displayPending;
            repair.put("pending", pending); repair.put("state", pending ? "pending" : gaps.isEmpty() ? "complete" : "unrepairable");
            data.put("pending", pending);
            if (terminal) data.put("missingData", gaps.get(0).get("reason"));
        };
        finish.run(); afterCommit(finish);
        result.put("ret", 200); return result;
    }
    /** Same bounded queue and receipts for automatic requests and administrator checks. */
    private Map<String,Object> inspectRepairWindow(SourceHistoryGapRepair.Window window, boolean enqueue, boolean retry) {
        return inspectRepairWindow(window, enqueue, retry, null);
    }
    private Map<String,Object> inspectRepairWindow(SourceHistoryGapRepair.Window window, boolean enqueue, boolean retry,
            com.gtcfesk.exchange.control.ControlIdentity actor) {
        Group group = group(window.category);
        SourceHistoryGapRepair.Receipt receipt;
        synchronized (group) {
            if (retry && window.needsSource()) group.repairs.remove(window.key);
            receipt = group.repairs.get(window.key);
        }
        if (receipt != null && System.currentTimeMillis() - receipt.at >= 300000) receipt = null;
        List<Map<String,Object>> gaps = new ArrayList<>();
        boolean sourceNeeded = window.discovery && receipt == null;
        for (Map<String,Object> gap : window.gaps) {
            Map<String,Object> known = null;
            if (receipt != null && Boolean.TRUE.equals(gap.get("recoverable"))) for (Map<String,Object> checked : receipt.gaps)
                if (((Number)checked.get("from")).longValue() <= ((Number)gap.get("from")).longValue()
                        && ((Number)checked.get("to")).longValue() >= ((Number)gap.get("to")).longValue()) { known = checked; break; }
            Map<String,Object> reported = new LinkedHashMap<>(known == null ? gap : known);
            reported.put("sourceMissing", gap.get("sourceMissing")); gaps.add(reported);
            sourceNeeded |= known == null && Boolean.TRUE.equals(gap.get("recoverable"));
        }
        if (!window.continuous && receipt != null) gaps.addAll(receipt.gaps);
        boolean pending, running, queued;
        synchronized (group) {
            running = window.key.equals(group.activeKey); queued = group.pending.containsKey(window.key);
            pending = running || queued || enqueue && sourceNeeded && group.codes.contains(window.code) && group.pending.size() < MAX_PENDING;
        }
        Map<String,Object> repair = new LinkedHashMap<>(); repair.put("from", window.from); repair.put("to", window.to);
        repair.put("source", window.identity); repair.put("period", window.period); repair.put("gaps", gaps); repair.put("pending", pending);
        repair.put("state", pending ? "pending" : gaps.isEmpty() ? "complete" : "unrepairable");
        repair.put("queueState", running ? "running" : queued || pending ? "queued" : sourceNeeded ? enqueue ? "queue_full" : "recoverable" : gaps.isEmpty() ? "complete" : "blocked");
        repair.put("sourceMissing", gaps.stream().filter(g -> Boolean.TRUE.equals(g.get("sourceMissing"))).count());
        repair.put("recoverable", gaps.stream().filter(g -> Boolean.TRUE.equals(g.get("recoverable"))).count());
        repair.put("retryable", window.gaps.stream().filter(g -> Boolean.TRUE.equals(g.get("recoverable"))).count());
        repair.put("protected", gaps.stream().filter(g -> String.valueOf(g.get("reason")).matches(".*(protected|sealed|frozen|simulation|projection|control_samples|calendar_unverified|protection_window_limit).*" )).count());
        repair.put("inserted", receipt == null ? 0 : receipt.inserted);
        repair.put("nextCursor", window.from);
        repair.put("retryAt", Math.max(group.nextAllowed, group.nextKlines));
        repair.put("requestKey", window.key);
        if (enqueue && sourceNeeded && pending) {
            KlineRequest work = new KlineRequest(window, actor);
            afterCommit(() -> { synchronized (group) {
                if (group.codes.contains(work.code) && group.pending.size() < MAX_PENDING && !work.key.equals(group.activeKey))
                    group.pending.putIfAbsent(work.key, work);
                boolean active = work.key.equals(group.activeKey), accepted = active || group.pending.containsKey(work.key);
                repair.put("pending", accepted); repair.put("state", accepted ? "pending" : "unrepairable");
                repair.put("queueState", active ? "running" : accepted ? "queued" : group.codes.contains(work.code) ? "queue_full" : "blocked");
            } });
        }
        return repair;
    }
    private static void summarizeRepairWindows(Map<String,Object> result, List<Map<String,Object>> windows) {
        result.put("pending", windows.stream().anyMatch(w -> Boolean.TRUE.equals(w.get("pending"))));
        for (String field : Arrays.asList("sourceMissing", "displayMissing", "recoverable", "retryable", "protected", "inserted"))
            result.put(field, windows.stream().mapToLong(w -> ((Number)w.getOrDefault(field, 0)).longValue()).sum());
    }
    private static long displayMissing(SourceHistoryGapRepair.Window window, Map<String,Object> display) {
        Set<Long> present = new HashSet<>();
        for (Map<String,Object> row : ControlHistoryStore.rows(display)) {
            long at = ControlHistoryStore.time(row); if (at >= window.from && at <= window.to) present.add(at);
        }
        long missing = 0;
        for (long at = window.from; at <= window.to; at = SourceHistoryGapRepair.end(window.period, at)) if (!present.contains(at)) missing++;
        return missing;
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> inspectLatestGaps(TradingSymbol config, String period, Integer limit, Map<String,Object> result) {
        if (config == null || historyGapRepair == null || controlHistory == null || RandomMarketPath.enabled(config)
                || !Boolean.TRUE.equals(config.getIsEnabled()) || !SourceHistoryGapRepair.continuous(config.getSourceCategory())
                || !SourceHistoryGapRepair.automaticPeriod(period)) return result;
        int remaining = Math.min(1000, Math.max(1, limit == null ? 100 : limit));
        long cursor = System.currentTimeMillis() - 1;
        List<Map<String,Object>> windows = new ArrayList<>(), gaps = new ArrayList<>();
        while (remaining > 0) {
            SourceHistoryGapRepair.Window window = historyGapRepair.inspect(config, period, Math.min(200, remaining), cursor,
                provider(sourceCategory(config)), Collections.emptyList(), sourceProjectionEnabled);
            Map<String,Object> repair = inspectRepairWindow(window, true, false);
            repair.put("displayMissing", displayMissing(window, result));
            windows.add(repair); gaps.addAll((List<Map<String,Object>>)repair.get("gaps"));
            remaining -= window.limit; cursor = window.from - 1;
        }
        Map<String,Object> repair = new LinkedHashMap<>(windows.get(0));
        repair.put("from", windows.get(windows.size() - 1).get("from")); repair.put("nextCursor", repair.get("from"));
        repair.put("windows", windows); repair.put("gaps", gaps);
        Map<String,Object> data = (Map<String,Object>)result.get("data");
        boolean displayPending = Boolean.TRUE.equals(data.get("pending"));
        data.put("historyRepair", repair);
        // Window notifications run first; report whether the bounded queue actually accepted them.
        Runnable finish = () -> {
            summarizeRepairWindows(repair, windows);
            repair.put("state", Boolean.TRUE.equals(repair.get("pending")) ? "pending" : gaps.isEmpty() ? "complete" : "unrepairable");
            data.put("pending", displayPending || Boolean.TRUE.equals(repair.get("pending")));
        };
        finish.run(); afterCommit(finish);
        return result;
    }
    private void repairSourceWindow(Group group, KlineRequest request) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("History source fetch must be outside transactions");
        SourceHistoryGapRepair.Window window = request.repair;
        if (!Objects.equals(window.provider, provider(window.category))) throw new IllegalStateException("History provider changed");
        http.begin();
        long sourceEnd = window.continuous ? SourceHistoryGapRepair.end(window.period, window.to) - 1 : window.to;
        Map<String,Object> fetched = source.getHistoryWindow(window.code, window.period, window.limit, window.category, window.from, sourceEnd);
        SourceHistoryGapRepair.Receipt receipt = historyGapRepair.insert(window, fetched, System.currentTimeMillis());
        // Audit committed inserts before cache work, so cache failure cannot erase the insertion count.
        if (request.repairActor != null && historyGapAudit != null) {
            Map<String,Object> detail = new LinkedHashMap<>(); detail.put("from", window.from); detail.put("to", window.to);
            detail.put("period", window.period); detail.put("source", window.identity); detail.put("inserted", receipt.inserted);
            detail.put("gaps", receipt.gaps);
            detail.put("sourceInputRevision", controlHistory.db.queryForObject("SELECT source_input_revision FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?", Long.class, window.tenant, window.symbol));
            historyGapAudit.record(request.repairActor.getActorId(), window.tenant, request.repairActor.getAccessSessionId(),
                "history-gap.commit", String.valueOf(window.symbol), receipt.gaps.isEmpty() ? "COMPLETED" : "PARTIAL", controlHistory.encode(detail), null);
        }
        // Invalidation failure keeps this request pending and retries the same insert-only work.
        synchronized (group) {
            invalidateHistorySourceCache(group.klines, window);
            group.repairs.put(request.key, receipt);
        }
    }
    static void invalidateHistorySourceCache(Map<String,Map<String,Object>> cache, SourceHistoryGapRepair.Window window) {
        String prefix = window.code + ":" + window.period + ":";
        cache.entrySet().removeIf(entry -> {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) return false;
            String[] parts = key.substring(prefix.length()).split(":");
            if (parts.length != 2) return false; // Latest cache identity is never invalidated/overwritten by history repair.
            try {
                long cursor = Long.parseLong(parts[1]), from = SourceHistoryGapRepair.start(window.period, cursor);
                for (int i = 1; i < Integer.parseInt(parts[0]); i++) from = SourceHistoryGapRepair.previous(window.period, from);
                // A session page can span weekends/holidays; elapsed period count is not its actual cached coverage.
                Map<String,Object> saved = entry.getValue(); // No get(): the existing LRU is access-ordered.
                if (saved != null) for (Map<String,Object> row : ControlHistoryStore.rows(saved)) {
                    long at = ControlHistoryStore.time(row); if (at > 0) from = Math.min(from, at);
                }
                return cursor >= window.from && from <= SourceHistoryGapRepair.end(window.period, window.to) - 1;
            } catch (NumberFormatException ignored) { return false; }
        });
    }
    /** SOURCE 1m only. Other sessions/periods retain the exact pure compatibility reader. */
    private Map<String,Object> projectedSourceKline(TradingSymbol config,String symbol,String interval,Integer limit,Long cursor) {
        if(config!=null && controlHistory!=null && controlHistory.historyRestoreRevision(config.getId())>0) return null;
        if(!sourceProjectionEnabled || sourceHistory==null || config==null || !"1m".equals(interval)
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) return null;
        int count=Math.min(1000,Math.max(1,limit==null?100:limit));
        long end=MinuteHistoryProjection.minute(cursor==null?System.currentTimeMillis():cursor);
        long from=Math.max(60000,end-(count-1L)*60000);
        List<Map<String,Object>> rows=new ArrayList<>();
        boolean pending=false;long receivedCutoffMinimum=Long.MAX_VALUE;
        MinuteHistoryProjectionStore.Progress progress=null;
        for(long first=from;first<=end;first+=500*60000L) {
            long last=Math.min(end,first+499*60000L);
            MinuteHistoryProjectionStore.ReadResult page=sourceHistory.readWindow(config.getId(),first,last);
            if(!page.sourceOnly) return null;
            pending|=page.pending; progress=page.progress;
            if(page.sourceBlocked) break;
            int retained=rows.size();boolean missing=false;
            for(Map<String,Object> row:page.minutes) {
                if(ControlHistoryStore.time(row)!=from+rows.size()*60000L) {pending=true;missing=true;break;}
                rows.add(row);
            }
            if(rows.size()>retained) receivedCutoffMinimum=Math.min(receivedCutoffMinimum,page.receivedCutoffPrefixMinimum);
            // A partial/blocked page ends the requested prefix; never append a clean suffix beyond its gap.
            if(missing || page.pending || rows.size()==count) break;
        }
        Map<String,Object> data=new LinkedHashMap<>();data.put("code",marketCode(config));data.put("symbol",symbol);
        // Only the contiguous requested clean prefix is visible; dirty/stale/gapped remainder stays pending.
        data.put("kline_list",rows);
        data.put("pending",pending);data.put("status",progress==null?"pending":pending?"partial":"available");
        data.put("source",provider(sourceCategory(config)));data.put("projectionKind","SOURCE_1M");
        data.put("historyWatermark",progress==null?0:progress.watermark);
        data.put("historyGeneration",progress==null?0:progress.generation);
        data.put("historyVersion",progress==null?0:progress.version);
        data.put("sourceInputRevision",progress==null?0:progress.sourceInputRevision);
        data.put("historyReceivedCutoffMinimum",receivedCutoffMinimum==Long.MAX_VALUE?0:receivedCutoffMinimum);
        Map<String,Object> result=new LinkedHashMap<>();result.put("ret",progress==null?503:200);
        result.put("msg",data.get("status"));result.put("status",data.get("status"));result.put("data",data);return result;
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
    private Map<String,Object> durableSimulationKline(TradingSymbol config, String interval, Integer limit, Long endTime) {
        if (controls == null || controlHistory == null || klineMerger == null) return simulationKline(config, interval, limit, endTime);
        int count=Math.min(1000,Math.max(1,limit==null?100:limit));
        long end=endTime==null?System.currentTimeMillis():endTime;
        List<Map<String,Object>> captured = controlHistory.db.query("SELECT body FROM market_simulation_source_candle WHERE tenant_id="
            + TenantContext.requireTenantId() + " AND symbol_id=? AND session_at=? AND period=? AND candle_at<=? ORDER BY candle_at DESC LIMIT ?",
            (r,n) -> controlHistory.decode(r.getString(1)),config.getId(),config.getRandomMarketStartedAt(),interval,end,count);
        Map<String,Object> data=new LinkedHashMap<>();data.put("symbol",config.getSymbol());data.put("kline_list",captured);data.put("status","available");
        data.put("source","Simulation");data.put("simulated",true);data.put("simulationSession",config.getRandomMarketStartedAt());
        Map<String,Object> result=new LinkedHashMap<>();result.put("ret",200);result.put("data",data);
        // Simulation consumers read only the engine's committed session candles, never generate/save on GET.
        return klineMerger.merge(config.getId(), interval, count, endTime, result, null, true,
            (from, to) -> controlHistory.db.query("SELECT body FROM market_simulation_source_candle WHERE tenant_id=" + TenantContext.requireTenantId() + " AND symbol_id=? AND session_at=? AND period='1m' AND candle_at>=? AND candle_at<=? ORDER BY candle_at",
                (r,n) -> controlHistory.decode(r.getString(1)), config.getId(), config.getRandomMarketStartedAt(),
                Math.max(from,end-(count+2L)*RandomMarketPath.duration(interval)),to));
    }
    private String simulationHistoryKey(TradingSymbol config) {
        return "simulation-history:" + config.getId() + ":" + config.getRandomMarketStartedAt();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cachedSimulationHistory(TradingSymbol config, String interval) {
        List<Map<String, Object>> saved = redis.getKlines(simulationHistoryKey(config), interval);
        TreeMap<Long, Map<String, Object>> history = new TreeMap<>();
        if (controlHistory != null) for (Map<String,Object> row : controlHistory.db.query(
                "SELECT body FROM market_simulation_source_candle WHERE tenant_id=" + TenantContext.requireTenantId()
                    + " AND symbol_id=? AND session_at=? AND period=? AND candle_at<? ORDER BY candle_at",
                (rs,n) -> controlHistory.decode(rs.getString(1)), config.getId(), config.getRandomMarketStartedAt(), interval, config.getRandomMarketStartedAt()))
            history.put(RandomMarketPath.timestamp(row), row);
        if (saved != null) for (Map<String, Object> row : saved) history.put(RandomMarketPath.timestamp(row), row);
        Group group = group(sourceCategory(config));
        synchronized (group) {
            String prefix = marketCode(config) + ":" + interval + ":";
            for (Map.Entry<String, Map<String, Object>> entry : group.klines.entrySet()) {
                if (!entry.getKey().startsWith(prefix)) continue;
                for (Map<String, Object> row : (List<Map<String, Object>>) ((Map<?, ?>) entry.getValue().get("data")).get("kline_list")) {
                    long time = RandomMarketPath.timestamp(row);
                    if (time < config.getRandomMarketStartedAt()
                        && (RandomMarketPath.periodEnd(interval, time) <= config.getRandomMarketStartedAt()
                            || QuoteState.time(entry.getValue().get("fetchedAt")) <= config.getRandomMarketStartedAt() + 999))
                        history.putIfAbsent(time, new HashMap<>(row));
                }
            }
        }
        return new ArrayList<>(history.values());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> simulationKline(TradingSymbol config, String interval, Integer limit, Long endTime) {
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
        long anchor = history.isEmpty() ? 0 : "1M".equals(interval)
                ? RandomMarketPath.monthStart(RandomMarketPath.timestamp(history.get(history.size() - 1))) == RandomMarketPath.monthStart(start)
                    ? RandomMarketPath.timestamp(history.get(history.size() - 1)) : 0
                : RandomMarketPath.timestamp(history.get(0)) % RandomMarketPath.duration(interval);
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
        for (Group group : state().groups.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            if (yahoo != null && "Yahoo".equals(provider(group.category))) item.put("stream", publicStreamStatus(yahoo.status()));
            if (exchangeStream != null && ExchangeQuoteSource.supports(group.category)) item.put("stream", publicStreamStatus(exchangeStream.status(group.category)));
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
        return symbols.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id).orElseThrow(() -> new BusinessException("币种不存在"));
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

    private boolean durableFlow(TradingSymbol config) {
        return controls != null && controls.hasFlow(config.getId());
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
        for (TradingSymbol symbol : symbols.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", symbol.getId()); row.put("symbol", symbol.getSymbol()); row.put("name", symbol.getName());
            row.put("displayName", symbol.getDisplayName());
            row.put("isEnabled", symbol.getIsEnabled()); row.put("pricePrecision", PriceControlPath.precision(symbol));
            row.put("quoteCurrency", symbol.getQuoteCurrency()); result.add(row);
        }
        result.sort(Comparator.comparing(row -> String.valueOf(row.get("symbol"))));
        return result;
    }

    public Map<String, Object> controlStatus(Long id) {
        TradingSymbol config = controlSymbol(id);
        // S2: status queries must not schedule source/history work.
        return controlStatus(config);
    }

    private Map<String, Object> controlStatus(TradingSymbol config) {
        long now = System.currentTimeMillis();
        if(controls!=null) {
            Map<String,Object> committed=new LinkedHashMap<>();controls.status(config,committed,Collections.emptyMap(),now);
            committed.put("id",config.getId());committed.put("v3Enabled",v3Enabled);committed.put("v4Enabled",v3Enabled&&v4Enabled);
            committed.put("virtualTrading",virtualTrading);committed.put("randomMarketEnabled",RandomMarketPath.enabled(config));
            return committed;
        }
        Map<String, Object> quote = RandomMarketPath.enabled(config) ? simulationBaseQuote(config, now)
            : getPrice(marketCode(config), sourceCategory(config));
        Map<String, Object> result = new LinkedHashMap<>();
        boolean running = PriceControlPath.running(config);
        BigDecimal price = quote.get("price") instanceof Number ? controlledPrice(config, quote, now) : null;
        result.put("id", config.getId()); result.put("enabled", Boolean.TRUE.equals(config.getControlEnabled()));
        result.put("v3Enabled", v3Enabled); result.put("v4Enabled", v3Enabled && v4Enabled);
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
        if (controls != null && (!random || durableFlow(config))) controls.status(config, result, quote, now);
        return result;
    }
    public Map<String, Object> previewControl(Long id, int duration, BigDecimal target, int intensity, boolean oscillation) {
        if (duration < 1 || duration > 86400 || intensity < 1 || intensity > 10 || target == null || target.signum() <= 0)
            throw new BalancedControlPlan.Failure("INVALID_PARAMETERS", "控盘参数无效");
        TradingSymbol config = controlSymbol(id);
        if (target.stripTrailingZeros().scale() > PriceControlPath.precision(config))
            throw new BalancedControlPlan.Failure("INVALID_PARAMETERS", "目标价格超出币种精度");
        long now = System.currentTimeMillis();
        Map<String, Object> raw = RandomMarketPath.enabled(config) ? simulationBaseQuote(config, now)
                : getPrice(marketCode(config), sourceCategory(config));
        BigDecimal displayed = raw.get("price") instanceof Number ? controlledPrice(config, raw, now) : null;
        BigDecimal start = controls.previewStart(config, raw, displayed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithmVersion", 3); result.put("startPrice", start.toPlainString()); result.put("targetPrice", target.toPlainString());
        result.put("preview", true); result.put("mappingVersion", 1); result.put("v3Enabled", v3Enabled);
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            try {
                BalancedControlPlan.Parameters p = new BalancedControlPlan.Parameters(start, target, duration,
                        PriceControlPath.precision(config), i, BalancedControlPlan.DEFAULT_RATIO);
                tiers.add(BalancedControlPlan.feasibility(p));
            } catch (BalancedControlPlan.Failure failure) {
                Map<String, Object> tier = new LinkedHashMap<>(); tier.put("intensity", i);
                tier.put("feasible", false); tier.put("errorCode", failure.code); tier.put("message", failure.getMessage()); tiers.add(tier);
            }
        }
        result.put("tiers", tiers);
        try { result.putAll(controls.prepare(config, raw, displayed, duration, target, intensity, oscillation).preview()); }
        catch (BalancedControlPlan.Failure failure) {
            result.put("feasible", false); result.put("errorCode", failure.code); result.put("message", failure.getMessage());
        }
        return result;
    }

    public Map<String, Object> controlFormula(Long id) {
        controlSymbol(id); // Validates tenant-owned symbol before reading a tenant-scoped config key.
        String stored = systemConfigs == null ? null : systemConfigs.getConfigValue("market.control.step-formula." + id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stepFormula", stored == null ? TargetControlSettings.DEFAULT_FORMULA : stored);
        result.put("defaultFormula", TargetControlSettings.DEFAULT_FORMULA);
        return result;
    }
    private void resolveFormula(Long id, TargetControlOptions options) {
        if (options.getStepFormula() == null) options.setStepFormula((String) controlFormula(id).get("stepFormula"));
    }
    private void requireV4() {
        if (!v3Enabled || !v4Enabled || controls == null) throw new BalancedControlPlan.Failure("ALGORITHM_DISABLED", "V4目标轨迹已暂停；不会退回无偏差带约束的旧算法");
    }
    public Map<String, Object> previewControl(Long id, int duration, BigDecimal target, int intensity, boolean oscillation, TargetControlOptions options) {
        requireV4(); resolveFormula(id, options);
        TradingSymbol config = controlSymbol(id);
        if (target == null || target.stripTrailingZeros().scale() > PriceControlPath.precision(config)) throw new BalancedControlPlan.Failure("INVALID_PARAMETERS", "目标价格超出品种精度");
        long now = System.currentTimeMillis();
        Map<String, Object> raw = RandomMarketPath.enabled(config) ? simulationBaseQuote(config, now) : getPrice(marketCode(config), sourceCategory(config));
        BigDecimal displayed = raw.get("price") instanceof Number ? controlledPrice(config, raw, now) : null;
        BigDecimal start = controls.previewStart(config, raw, displayed);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("algorithmVersion", 4); result.put("startPrice", start.toPlainString()); result.put("targetPrice", target.toPlainString()); result.put("preview", true);
        result.put("priceTick", BigDecimal.ONE.movePointLeft(PriceControlPath.precision(config)).toPlainString());
        result.put("tiers", StabilizedControlPlan.previewTiers(start, target, duration, PriceControlPath.precision(config), options));
        try {
            TargetControlSettings settings = new TargetControlSettings(start, target, duration, PriceControlPath.precision(config), intensity, options);
            result.putAll(settings.snapshot());
            result.put("theoreticalMinAmount", settings.typical.multiply(settings.lowerFactor).toPlainString());
            result.put("theoreticalMaxAmount", settings.typical.multiply(settings.upperFactor).toPlainString());
            StabilizedControlPlan.Parameters p = new StabilizedControlPlan.Parameters(start, target, duration, PriceControlPath.precision(config), intensity, StabilizedControlPlan.DEFAULT_RATIO, settings);
            result.putAll(StabilizedControlPlan.feasibility(p));
            result.putAll(controls.prepare(config, raw, displayed, duration, target, intensity, oscillation, options).preview());
        } catch (BalancedControlPlan.Failure failure) {
            result.put("feasible", false); result.put("errorCode", failure.code); result.put("message", failure.getMessage());
        }
        return result;
    }
    public Map<String, Object> saveControlFormula(Long id, int duration, BigDecimal target, int intensity, TargetControlOptions options) {
        requireV4();
        if (options.getStepFormula() == null) throw new BalancedControlPlan.Failure("INVALID_FORMULA", "请输入单步典型幅度公式");
        TradingSymbol config = controlSymbol(id);
        long now = System.currentTimeMillis();
        Map<String, Object> raw = RandomMarketPath.enabled(config) ? simulationBaseQuote(config, now) : getPrice(marketCode(config), sourceCategory(config));
        BigDecimal displayed = raw.get("price") instanceof Number ? controlledPrice(config, raw, now) : null;
        BigDecimal start = controls.previewStart(config, raw, displayed);
        TargetControlSettings settings = new TargetControlSettings(start, target, duration, PriceControlPath.precision(config), intensity, options);
        StabilizedControlPlan.Parameters p = new StabilizedControlPlan.Parameters(start, target, duration, PriceControlPath.precision(config), intensity, StabilizedControlPlan.DEFAULT_RATIO, settings);
        Map<String, Object> result = StabilizedControlPlan.feasibility(p);
        if (!Boolean.TRUE.equals(result.get("feasible"))) throw new BalancedControlPlan.Failure((String) result.get("errorCode"), (String) result.get("message"));
        if (systemConfigs == null) throw new BusinessException("公式保存服务不可用");
        systemConfigs.saveConfig("market.control.step-formula." + id, settings.formula, "目标价格控盘单步典型幅度公式");
        return controlFormula(id); // Saves settings only; never starts or rewrites a task.
    }

    public Map<String,Object> randomMarket(Long id, boolean enabled, BigDecimal basePrice) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Simulation preparation cannot join an outer transaction");
        TradingSymbol basis = controlSymbol(id);
        long session = System.currentTimeMillis() / 1000 * 1000;
        TradingSymbol preparation = copySymbol(basis); preparation.setRandomMarketStartedAt(session);
        Map<String,List<Map<String,Object>>> histories = new LinkedHashMap<>();
        Map<String,List<String>> encoded = new LinkedHashMap<>();
        if (enabled && !RandomMarketPath.enabled(basis)) {
            for (String interval : Arrays.asList("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w", "1M")) {
                List<Map<String,Object>> rows = cachedSimulationHistory(preparation, interval);
                histories.put(interval, rows);
                List<String> bodies = new ArrayList<>();
                if (controlHistory != null) for (Map<String,Object> row : rows) bodies.add(controlHistory.encode(row));
                encoded.put(interval, bodies);
            }
        }
        java.util.function.Supplier<Map<String,Object>> save = () -> {
            TradingSymbol fresh = controlSymbol(id);
            if (fresh.getRowVersion() != basis.getRowVersion()) throw new BusinessException("行情配置已变化，请重试");
            return changeRandomMarket(fresh, enabled, basePrice, session, histories, encoded);
        };
        Map<String,Object> result = controlHistory == null ? save.get() : controls.locked(id, save);
        for (Map.Entry<String,List<Map<String,Object>>> entry : histories.entrySet()) if (!entry.getValue().isEmpty()) {
            try { redis.saveSimulationHistory(simulationHistoryKey(preparation), entry.getKey(), entry.getValue()); }
            catch (RuntimeException failure) { log.warn("Simulation history cache pending; durable source retained"); }
        }
        return result;
    }

    private Map<String,Object> changeRandomMarket(TradingSymbol config, boolean enabled, BigDecimal basePrice,
            long session, Map<String,List<Map<String,Object>>> histories, Map<String,List<String>> encoded) {
        Long id = config.getId();
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
            config.setRandomMarketStartedAt(session);
            if (controlHistory != null) for (Map.Entry<String,List<Map<String,Object>>> entry : histories.entrySet()) {
                List<Map<String,Object>> history = entry.getValue();
                for (int i = 0; i < history.size(); i++) controlHistory.db.update(
                    "INSERT INTO market_simulation_source_candle(tenant_id,symbol_id,session_at,period,candle_at,body) VALUES(" + TenantContext.requireTenantId() + ",?,?,?,?,?) ON DUPLICATE KEY UPDATE body=VALUES(body)",
                    id, session, entry.getKey(), ControlHistoryStore.time(history.get(i)), encoded.get(entry.getKey()).get(i));
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

    public Map<String, Object> startControl(Long id, int duration, BigDecimal target, int intensity, boolean randomOscillation) {
        return startControl(id, duration, target, intensity, randomOscillation, null);
    }
    public Map<String, Object> startControl(Long id, int duration, BigDecimal target, int intensity, boolean randomOscillation, String requestKey) {
        return startControl(id, duration, target, intensity, randomOscillation, requestKey, null);
    }

    public Map<String, Object> startControl(Long id, int duration, BigDecimal target, int intensity,
            boolean randomOscillation, String requestKey, RecoveryOptions options) {
        return startControl(id, duration, target, intensity, randomOscillation, requestKey, options, null);
    }
    public Map<String, Object> startControl(Long id, int duration, BigDecimal target, int intensity,
            boolean randomOscillation, String requestKey, RecoveryOptions options, TargetControlOptions targetOptions) {
        if (TransactionSynchronizationManager.isActualTransactionActive() && commandPlan.get()==null)
            throw new IllegalStateException("Control preparation cannot join an outer transaction");
        if (targetOptions != null) { requireV4(); resolveFormula(id, targetOptions); }
        if (duration < 1 || duration > 86400 || intensity < 1 || intensity > 10 || target == null
                || target.signum() <= 0 || target.compareTo(new BigDecimal("10000000000000000")) >= 0)
            throw new BusinessException("控盘参数无效");
        TradingSymbol config = controlSymbol(id);
        if (!Boolean.TRUE.equals(config.getIsEnabled())) throw new BusinessException("请先启用该币种");
        if (target.stripTrailingZeros().scale() > PriceControlPath.precision(config)) throw new BusinessException("目标价格超出币种价格精度");
        if (RandomMarketPath.enabled(config) && !virtualTrading) throw new BusinessException("随机行情仅可在虚拟交易环境使用");
        if (controls == null) {
            // Legacy virtual-only fixture; production always injects durable controls.
            if (PriceControlPath.running(config)) throw new BusinessException("自动控盘正在运行，请先停止任务");
            Map<String, Object> quote = requireControlQuote(config);
            long at = controlTime(config);
            BigDecimal start = controlledPrice(config, quote, at);
            if (start.signum() <= 0) throw new BusinessException("当前控盘价格无效，请先调整偏移");
            config.setControlStartPrice(start); config.setControlTargetPrice(target);
            config.setControlDurationSeconds(duration); config.setControlIntensity(intensity);
            config.setControlRandomOscillation(randomOscillation); config.setControlStartedAt(at);
            config.setControlCompletedAt(null); config.setControlEnabled(true); config.setControlRestoring(false);
            config.setControlPriceOffset(start.subtract(rawPrice(quote)));
            recordSimulationControl(config, at); return saveControl(config);
        }
        PersistentPriceControl.Task previous = controls.existingTarget(id, requestKey, duration, target, intensity, randomOscillation, options, targetOptions);
        if (previous != null) return controlStatus(config);
        long now = System.currentTimeMillis();
        Map<String,Object> raw = RandomMarketPath.enabled(config) ? simulationBaseQuote(config, now) : getPrice(marketCode(config), sourceCategory(config));
        Map<String,Object> view = controls.display(config, raw, now);
        BigDecimal displayed = view.get("price") instanceof Number ? ControlHistoryStore.number(view.get("price")) : null;
        PersistentPriceControl.Prepared prepared = commandPlan.get()!=null?commandPlan.get():v3Enabled ? targetOptions == null ? controls.prepare(config, raw, displayed, duration, target, intensity, randomOscillation)
                : controls.prepare(config, raw, displayed, duration, target, intensity, randomOscillation, targetOptions) : null;
        return controls.locked(id, () -> {
            TradingSymbol fresh = controlSymbol(id);
            if (fresh.getRowVersion() != config.getRowVersion() || !Boolean.TRUE.equals(fresh.getIsEnabled())
                    || RandomMarketPath.enabled(fresh) && !virtualTrading)
                throw new BalancedControlPlan.Failure("START_BASIS_CHANGED", "预计算后配置版本或启用状态已变化");
            long commitTime = System.currentTimeMillis();
            Map<String,Object> currentRaw = RandomMarketPath.enabled(fresh) ? simulationBaseQuote(fresh, commitTime)
                    : getPrice(marketCode(fresh), sourceCategory(fresh));
            Map<String,Object> currentView = controls.display(fresh, currentRaw, commitTime);
            BigDecimal currentDisplay = currentView.get("price") instanceof Number ? ControlHistoryStore.number(currentView.get("price")) : null;
            PersistentPriceControl.Task created = prepared == null
                    ? options == null ? controls.start(fresh, currentRaw, currentDisplay, duration, target, intensity, randomOscillation, false, requestKey)
                    : controls.start(fresh, currentRaw, currentDisplay, duration, target, intensity, randomOscillation, false, requestKey, options)
                    : controls.startPrepared(fresh, currentRaw, currentDisplay, duration, target, intensity, randomOscillation, requestKey, options, prepared);
            clearControl(fresh); fresh.setControlEnabled(false); fresh.setControlPriceOffset(BigDecimal.ZERO);
            if (RandomMarketPath.enabled(fresh) && SimulationControlPath.events(fresh).stream().noneMatch(e -> Objects.equals(e.planId, created.id)))
                SimulationControlPath.record(fresh, created.startedAt, created.algorithmVersion >= 3 ? created.id : null, created.algorithmVersion);
            return saveControl(fresh);
        });
    }

    TradingSymbol commandConfig(Long id){return copySymbol(controlSymbol(id));}
    public Map<String,Object> checkSourceGaps(Long id, long from, long to, String period, String timezone) {
        return sourceGapReport(commandConfig(id), from, to, period, timezone, false, null);
    }
    public Map<String,Object> queueSourceGaps(Long id, long from, long to, String period, String timezone) {
        com.gtcfesk.exchange.control.ControlIdentity actor = MarketControlCommands.operator();
        Map<String,Object> result = sourceGapReport(commandConfig(id), from, to, period, timezone, true, actor);
        if (historyGapAudit != null) historyGapAudit.record(actor.getActorId(), actor.getTenantId(), actor.getAccessSessionId(),
            "history-gap.request", String.valueOf(id), Boolean.TRUE.equals(result.get("pending")) ? "QUEUED" : "CHECKED", controlHistory.encode(result), null);
        return result;
    }
    /** Read-only check; explicit submissions only notify the existing provider lane after this snapshot commits. */
    @SuppressWarnings("unchecked")
    private Map<String,Object> sourceGapReport(TradingSymbol config, long from, long to, String period, String timezone,
            boolean enqueue, com.gtcfesk.exchange.control.ControlIdentity actor) {
        try { java.time.ZoneId.of(timezone); } catch (RuntimeException invalid) { throw new BusinessException("请选择有效时区"); }
        if (from < 946684800000L || from % 60000 != 0 || to % 60000 != 0 || to < from || (to - from) / 60000 >= 1440
                || to + 60000 > System.currentTimeMillis()) throw new BusinessException("请选择已结束的完整分钟，单次最多 1440 分钟");
        if (!Boolean.TRUE.equals(config.getIsEnabled()) || !SourceHistoryGapRepair.continuous(config.getSourceCategory())
                || !SourceHistoryGapRepair.automaticPeriod(period)) throw new BusinessException("缺口连续性检查仅支持已配置 Crypto/CryptoPerpetual 的 1m/5m/15m/30m/1h");
        long first = SourceHistoryGapRepair.start(period, from);
        if (first < from) first = SourceHistoryGapRepair.end(period, first);
        long last = SourceHistoryGapRepair.start(period, to);
        if (SourceHistoryGapRepair.end(period, last) > to + 60000) last = SourceHistoryGapRepair.previous(period, last);
        if (first > last) throw new BusinessException("所选区间没有该周期的完整时间槽位");
        final long checkedFrom = first, checkedTo = last;
        return readSnapshot(() -> {
            List<Map<String,Object>> windows = new ArrayList<>();
            int slots = (int)((checkedTo - checkedFrom) / RandomMarketPath.duration(period)) + 1;
            for (int offset = 0; offset < slots; offset += 200) {
                int count = Math.min(200, slots - offset);
                long start = checkedFrom + offset * RandomMarketPath.duration(period);
                long cursor = start + count * RandomMarketPath.duration(period) - 1;
                SourceHistoryGapRepair.Window window = historyGapRepair.inspect(config, period, count, cursor,
                    provider(sourceCategory(config)), Collections.emptyList(), sourceProjectionEnabled);
                Map<String,Object> repair = inspectRepairWindow(window, enqueue, enqueue, actor);
                Map<String,Object> raw = cachedKline(marketCode(config), period, count, sourceCategory(config), cursor, false);
                ((Map<String,Object>)raw.get("data")).put("kline_list", Collections.emptyList());
                Map<String,Object> display = RandomMarketPath.enabled(config) ? durableSimulationKline(config, period, count, cursor)
                    : projectedSourceKline(config, config.getSymbol(), period, count, cursor);
                if (display == null) display = mergeControlKline(config, config.getSymbol(), period, count, cursor, raw);
                repair.put("displayMissing", displayMissing(window, display)); windows.add(repair);
            }
            Map<String,Object> result = new LinkedHashMap<>(); result.put("from", checkedFrom); result.put("to", checkedTo);
            result.put("period", period); result.put("timezone", timezone); result.put("sourceIdentity", provider(sourceCategory(config)) + ":" + config.getSourceCategory() + ":" + marketCode(config));
            result.put("windows", windows);
            summarizeRepairWindows(result, windows);
            afterCommit(() -> summarizeRepairWindows(result, windows));
            List<Long> revision = controlHistory.db.queryForList("SELECT source_input_revision FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?", Long.class, TenantContext.requireTenantId(), config.getId());
            result.put("sourceInputRevision", revision.isEmpty() ? 0 : revision.get(0));
            return result;
        });
    }
    public Map<String,Object> historyRestoreChart(Long id,long from,long to,String timezone) { TradingSymbol config=commandConfig(id); return historyRestore.chart(config,provider(sourceCategory(config)),from,to,timezone); }
    public Map<String,Object> historyRestorePreview(Long id,long from,long to,String timezone) { TradingSymbol config=commandConfig(id); return historyRestore.preview(config,provider(sourceCategory(config)),from,to,timezone); }
    public Map<String,Object> acceptHistoryRestore(Long id,String token,String key) { TradingSymbol config=commandConfig(id); return historyRestore.accept(id,token,key,provider(sourceCategory(config))+":"+config.getSourceCategory()+":"+marketCode(config)); }
    public Map<String,Object> backfillHistoryRestore(Long id,long from,long to,String timezone) {
        TradingSymbol config=commandConfig(id); historyRestore.chart(config,provider(sourceCategory(config)),from,to,timezone);
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Source fetch cannot join a writer transaction");
        int inserted=0;
        for(long first=from;first<=to;first+=500*60000L) {
            long last=Math.min(to,first+499*60000L); http.begin();
            Map<String,Object> fetched=source.getHistoryWindow(marketCode(config),"1m",500,sourceCategory(config),first,last+59999);
            inserted+=historyRestore.insertSource(config,provider(sourceCategory(config)),first,last,fetched,System.currentTimeMillis());
        }
        Map<String,Object> result=new LinkedHashMap<>(); result.put("inserted",inserted); return result;
    }
    PersistentPriceControl.Prepared prepareCommand(Long id,int duration,BigDecimal target,int intensity,boolean oscillation,TargetControlOptions options,long seed) {
        TradingSymbol config=commandConfig(id);requireV4();resolveFormula(id,options);
        long now=System.currentTimeMillis();Map<String,Object> raw=RandomMarketPath.enabled(config)?simulationBaseQuote(config,now):getPrice(marketCode(config),sourceCategory(config));
        Map<String,Object> committed=controls.display(config,Collections.emptyMap(),now);
        BigDecimal displayed=committed.get("price") instanceof Number?ControlHistoryStore.number(committed.get("price")):null;
        ControlPlanBudget.Lease allocation=controlHistory.budget.acquire(65536L+256L*(duration+1));
        controls.commandSeed.set(seed);
        try {PersistentPriceControl.Prepared prepared=controls.prepare(config,raw,displayed,duration,target,intensity,oscillation,options);prepared.allocation=allocation;return prepared;}
        catch(RuntimeException failure){allocation.close();throw failure;}
        finally{controls.commandSeed.remove();}
    }
    Map<String,Object> activateCommand(Long id,int duration,BigDecimal target,int intensity,boolean oscillation,String key,TargetControlOptions options,PersistentPriceControl.Prepared prepared) {
        commandPlan.set(prepared);
        try{return startControl(id,duration,target,intensity,oscillation,key,options,options);}
        finally{commandPlan.remove();}
    }
    public Map<String, Object> restoreControl(Long id, int duration, int intensity, boolean randomOscillation) {
        return restoreControl(id, duration, intensity, randomOscillation, null);
    }
    public Map<String,Object> restoreControl(Long id, int duration, int intensity, boolean randomOscillation, String requestKey) {
        return controls == null ? restoreControlLocked(id, duration, intensity, randomOscillation, requestKey) : controlHistory.locked(id, () -> restoreControlLocked(id, duration, intensity, randomOscillation, requestKey));
    }
    private Map<String,Object> restoreControlLocked(Long id, int duration, int intensity, boolean randomOscillation, String requestKey) {
        if (duration < 1 || duration > 86400 || intensity < 1 || intensity > 10)
            throw new BusinessException("时长需为 1–86400 秒，波动强度需为 1–10");
        TradingSymbol config = controlSymbol(id);
        Map<String, Object> quote = requireControlQuote(config);
        if (controls != null) {
            controls.startRealtimeRestore(config, quote, controlledPrice(config, quote, System.currentTimeMillis()), duration, intensity, randomOscillation, requestKey);
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

    public Map<String,Object> manualControl(Long id, boolean enabled, BigDecimal offset) {
        return controls == null ? manualControlLocked(id, enabled, offset) : !enabled ? controlHistory.locked(id, () -> manualControlLocked(id, false, offset)) : controls.locked(id, () -> manualControlLocked(id, true, offset));
    }
    private Map<String,Object> manualControlLocked(Long id, boolean enabled, BigDecimal offset) {
        if (offset == null || offset.abs().compareTo(new BigDecimal("10000000000000000")) >= 0 || offset.stripTrailingZeros().scale() > 16)
            throw new BusinessException("偏移值无效");
        TradingSymbol config = controlSymbol(id);
        Map<String,Object> raw = enabled ? requireControlQuote(config) : RandomMarketPath.enabled(config)
            ? simulationBaseQuote(config, System.currentTimeMillis()) : getPrice(marketCode(config), sourceCategory(config));
        if (enabled && rawPrice(raw).add(offset).signum() <= 0)
            throw new BusinessException("偏移后的价格必须大于 0");
        if (controls != null && (!RandomMarketPath.enabled(config) || durableFlow(config))) {
            if(enabled)controls.stop(id,System.currentTimeMillis());else controls.emergencySource(id,System.currentTimeMillis());
        }
        long now = controlTime(config);
        BigDecimal continuation = RandomMarketPath.enabled(config)
            ? RandomMarketPath.price(config, now).subtract(RandomMarketPath.basePrice(config, now)) : BigDecimal.ZERO;
        clearControl(config);
        config.setControlEnabled(enabled); config.setControlPriceOffset(enabled ? offset : continuation);
        recordSimulationControl(config, now);
        if (controls != null && enabled) controls.recordManualPrice(config, raw, System.currentTimeMillis());
        // SOURCE records only this valid observed return price; no freeze or source-ledger scan.
        else if (controls != null && Boolean.TRUE.equals(raw.get("available")) && QuoteState.valid(raw))
            controlHistory.manualPoint(id, System.currentTimeMillis(), controlledPrice(config, raw, now));
        return saveControl(config,!enabled);
    }

    public Map<String,Object> stopControl(Long id) {
        return controls == null ? stopControlLocked(id) : controls.locked(id, () -> stopControlLocked(id));
    }
    private Map<String,Object> stopControlLocked(Long id) {
        TradingSymbol config = controlSymbol(id);
        if (controls != null && (!RandomMarketPath.enabled(config) || durableFlow(config))) {
            controls.stopAndHold(id, System.currentTimeMillis());
            return publishControl(config);
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

    private static TradingSymbol copySymbol(TradingSymbol source) {
        TradingSymbol copy = new TradingSymbol(); org.springframework.beans.BeanUtils.copyProperties(source, copy, "tenantId");
        if (source.getTenantId() != null) copy.setTenantId(source.getTenantId());
        return copy;
    }
    private Map<String,Object> saveControl(TradingSymbol config) { return saveControl(config,false); }
    private Map<String, Object> saveControl(TradingSymbol config,boolean emergencySource) {
        TradingSymbol saved = copySymbol(symbols.saveAndFlush(config));
        TenantState tenantState = state();
        afterCommit(() -> {
            synchronized (tenantState) {
                TradingSymbol current = tenantState.registry.get(saved.getSymbol());
                if (current != null && current.getRowVersion() > saved.getRowVersion()) return;
                Map<String, TradingSymbol> updated = new HashMap<>(tenantState.registry);
                updated.put(saved.getSymbol(), saved);
                String alias = marketCode(saved);
                if (!"CryptoPerpetual".equals(sourceCategory(saved)) && (!updated.containsKey(alias) || Objects.equals(updated.get(alias).getId(), saved.getId()))) updated.put(alias, saved);
                tenantState.registry = Collections.unmodifiableMap(updated);
            }
        });
        return publishControl(saved,emergencySource);
    }
    /** Called only by explicit writer commands, never by GET/status/quote readers. */
    private Map<String,Object> publishControl(TradingSymbol config) {return publishControl(config,false);}
    private Map<String,Object> publishControl(TradingSymbol config,boolean emergencySource) {
        if(controls!=null) {
            long now=controlTime(config);
            Map<String,Object> raw=RandomMarketPath.enabled(config) ? simulationBaseQuote(config,now)
                : getPrice(marketCode(config),sourceCategory(config));
            if(emergencySource)controls.pumpSource(config,raw,now,maxAgeMs);else controls.pump(config,raw,now,maxAgeMs);
        }
        return controlStatus(config);
    }

    void completeControls() {
        completeControls(System.currentTimeMillis());
    }
    void completeControls(long now) {
        List<TradingSymbol> registered=new ArrayList<>(new HashSet<>(state().registry.values()));
        registered.sort(Comparator.comparing(TradingSymbol::getId));
        List<TradingSymbol> selected=new ArrayList<>();
        if(!registered.isEmpty()){
            TenantState tenant=state();
            Set<Long> running=controls==null?Collections.emptySet():new HashSet<>(controls.runningSymbols());
            List<TradingSymbol> active=new ArrayList<>();
            for(TradingSymbol symbol:registered)if(running.contains(symbol.getId()))active.add(symbol);
            // Idle quote rotation must not look like a scheduler outage to an active recovery.
            // ponytail: 16 symbols per turn; excess active tasks rotate until engine capacity is increased.
            if(!active.isEmpty()){
                int start=Math.floorMod(tenant.controlCursor,active.size()),count=Math.min(16,active.size());
                for(int i=0;i<count;i++)selected.add(active.get((start+i)%active.size()));
                tenant.controlCursor=(start+count)%active.size();
            }
            int start=Math.floorMod(tenant.engineCursor,registered.size()),scanned=0;
            while(scanned<registered.size() && selected.size()<16){
                TradingSymbol symbol=registered.get((start+scanned++)%registered.size());
                if(!running.contains(symbol.getId()))selected.add(symbol);
            }
            tenant.engineCursor=(start+scanned)%registered.size();
        }
        if (controls != null) {
            try {
                for (TradingSymbol selectedSymbol : selected) {
                    Long id=selectedSymbol.getId();
                    if(state().engineRetryAt.getOrDefault(id,0L)>System.currentTimeMillis())continue;
                    try {
                        TradingSymbol config = copySymbol(controlSymbol(id));
                        Map<String,Object> base = RandomMarketPath.enabled(config) ? simulationBaseQuote(config, now) : getPrice(marketCode(config), sourceCategory(config));
                        if (RandomMarketPath.enabled(config)) {
                            base.put("eventId", "simulation-" + config.getRandomMarketStartedAt() + "-" + now / 1000);
                            Long committed = controlHistory.db.queryForObject(
                                "SELECT MAX(candle_at) FROM market_simulation_source_candle WHERE tenant_id=? AND symbol_id=? AND session_at=? AND period='1m'",
                                Long.class, TenantContext.requireTenantId(), id, config.getRandomMarketStartedAt());
                            // Revisit the committed last minute so a partial candle becomes complete after downtime.
                            long first = Math.max(Math.floorDiv(config.getRandomMarketStartedAt(), 60000) * 60000,
                                committed == null ? Long.MIN_VALUE : committed);
                            long blockEnd = Math.min(now, first + 512L * 60000 - 1000);
                            int minutes = (int) Math.min(512, Math.floorDiv(blockEnd, 60000) - Math.floorDiv(first, 60000) + 1);
                            List<Map<String,Object>> bars = first > now ? Collections.emptyList()
                                : ControlHistoryStore.rows(RandomMarketPath.klines(config, "1m", minutes, blockEnd, blockEnd));
                            List<String> bodies = new ArrayList<>();
                            for (Map<String,Object> bar : bars) bodies.add(controlHistory.encode(bar));
                            controls.locked(id, () -> {
                                TradingSymbol fresh = controlSymbol(id);
                                if (fresh.getRowVersion() != config.getRowVersion() || !RandomMarketPath.enabled(fresh)
                                        || !Objects.equals(fresh.getRandomMarketStartedAt(), config.getRandomMarketStartedAt())) return null;
                                controls.sourceQuote(fresh, base, now);
                                for (int i = 0; i < bars.size(); i++) controlHistory.db.update(
                                    "INSERT INTO market_simulation_source_candle(tenant_id,symbol_id,session_at,period,candle_at,body) VALUES(" + TenantContext.requireTenantId() + ",?,?,'1m',?,?) ON DUPLICATE KEY UPDATE body=VALUES(body)",
                                    id, config.getRandomMarketStartedAt(), ControlHistoryStore.time(bars.get(i)), bodies.get(i));
                                controls.pump(fresh, base, now, maxAgeMs); return null;
                            });
                        } else controls.pump(config, base, now, maxAgeMs);
                        state().engineFailures.remove(id);state().engineRetryAt.remove(id);
                    }
                    catch (Exception failure) {
                        String reason=MarketEngineFailure.normalize(failure);
                        String fence="ENGINE_FENCED".equals(reason)?controlHistory.runtime.fenceReason(id):reason;
                        int attempt=state().engineFailures.merge(id,1,(a,b)->Math.min(7,a+b));
                        long delay=Math.min(30000,250L << attempt)+ThreadLocalRandom.current().nextLong(250);
                        state().engineRetryAt.put(id,"AUTHORITY_LOST".equals(fence)?Long.MAX_VALUE:System.currentTimeMillis()+delay);
                        log.warn("control_failure tenant={} symbol={} reason={} fence={} attempt={} retryMs={}",TenantContext.requireTenantId(),id,reason,fence,attempt,delay);
                    }
                }
            } catch (Exception failure) { log.error("Cannot read persistent control tasks", failure); }
        }
        if(historyRestore!=null) historyRestore.runOne();
        Set<Long> visited = new HashSet<>();
        for (TradingSymbol snapshot : selected) {
            if (!PriceControlPath.running(snapshot) || now < PriceControlPath.endsAt(snapshot) || !visited.add(snapshot.getId())) continue;
            try {
                java.util.function.Supplier<Void> completion = () -> {
                    TradingSymbol config = controlSymbol(snapshot.getId());
                    if (!PriceControlPath.running(config) || now < PriceControlPath.endsAt(config)) return null;
                    Map<String, Object> quote = requireControlQuote(config);
                    boolean restoring = Boolean.TRUE.equals(config.getControlRestoring());
                    config.setControlPriceOffset(restoring ? BigDecimal.ZERO : config.getControlTargetPrice().subtract(
                        RandomMarketPath.enabled(config) ? RandomMarketPath.basePrice(config, (PriceControlPath.endsAt(config) + 999) / 1000 * 1000) : rawPrice(quote)));
                    if (restoring) config.setControlEnabled(false);
                    config.setControlCompletedAt(now);
                    saveControl(config); return null;
                };
                if (controls == null) completion.get(); else controls.locked(snapshot.getId(), completion);
            } catch (BusinessException unavailable) {
                // Leave the task pending until the source is healthy; no stale-price execution.
            } catch (Exception failure) {
                log.warn("Price control completion failed for {} ({})", snapshot.getSymbol(), failure.getClass().getSimpleName());
            }
        }
    }
    @PreDestroy public void stop() {
        running = false;
        if (yahoo != null) yahoo.stop(); if (exchangeStream != null) exchangeStream.stop(); metadata.shutdownNow(); engines.shutdownNow();
        for (TenantState state : tenantStates.values()) for (Group group : state.groups.values()) group.executor.shutdownNow();
    }
}
