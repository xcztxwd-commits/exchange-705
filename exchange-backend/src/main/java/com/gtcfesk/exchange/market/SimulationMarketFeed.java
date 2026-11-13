package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.simulation.SimulationEnvironment;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantJobRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/** The simulation owns its money ledger, but consumes the real environment's committed market. */
@Component
public class SimulationMarketFeed {
    private static volatile SimulationMarketFeed current;
    private static final long MIRROR_LEASE_MS = 3000;
    private final SimulationEnvironment environment;
    private final TenantJobRunner jobs;
    private final TradingSymbolRepository symbols;
    private final ControlHistoryStore store;
    private final ApplicationEventPublisher events;
    private final RestTemplate http;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "simulation-market-feed"); thread.setDaemon(true); return thread;
    });
    @Value("${simulation.identity-url:http://backend:8080/api/simulation}") private String identityUrl;
    @Value("${simulation.inspection-key:}") private String key;
    private final Map<Long,Map<String,TradingSymbol>> registries = new ConcurrentHashMap<>();
    private final Map<Long,Map<String,Map<String,Object>>> raw = new ConcurrentHashMap<>();
    private final Map<String,String> versions = new ConcurrentHashMap<>();
    private final Map<String,Long> publishedAt = new ConcurrentHashMap<>();
    private volatile long lastError;

    public SimulationMarketFeed(SimulationEnvironment environment, TenantJobRunner jobs,
            TradingSymbolRepository symbols, ControlHistoryStore store, ApplicationEventPublisher events) {
        this.environment=environment; this.jobs=jobs; this.symbols=symbols; this.store=store; this.events=events;
        SimpleClientHttpRequestFactory transport=new SimpleClientHttpRequestFactory();
        transport.setConnectTimeout(1000); transport.setReadTimeout(2000); http=new RestTemplate(transport);
        for(org.springframework.http.converter.HttpMessageConverter<?> converter:http.getMessageConverters())
            if(converter instanceof org.springframework.http.converter.json.MappingJackson2HttpMessageConverter)
                ((org.springframework.http.converter.json.MappingJackson2HttpMessageConverter)converter).getObjectMapper()
                        .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    @PostConstruct public void start() {
        if (!environment.enabled()) return;
        if (key == null || key.length()<32) throw new IllegalStateException("Simulation market service key is required");
        current=this;
        worker.scheduleWithFixedDelay(() -> {
            try { jobs.eachContext("simulation-market-feed", ignored -> refresh()); }
            catch (RuntimeException failure) { failed(failure); }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }
    @PreDestroy public void stop() { worker.shutdownNow(); if(current==this)current=null; }
    public static boolean enabled() { return current!=null; }
    public static SimulationMarketFeed instance() {
        SimulationMarketFeed feed=current;
        if(feed==null)throw new IllegalStateException("Simulation market feed is not running");
        return feed;
    }
    private void failed(RuntimeException failure) {
        if(System.currentTimeMillis()-lastError>30000) {
            lastError=System.currentTimeMillis();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Canonical simulation market unavailable: {}",failure.getClass().getSimpleName());
        }
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> request(Map<String,Object> command) {
        long tenant=TenantContext.requireTenantId();
        HttpHeaders headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Simulation-Inspection-Key",key); headers.set("X-Simulation-Tenant-Id",Long.toString(tenant));
        Map<String,Object> result=http.exchange(URI.create(identityUrl+"/market-feed"),HttpMethod.POST,
                new HttpEntity<>(command,headers),Map.class).getBody();
        if(result==null || !"REAL".equals(result.get("environment")) || QuoteState.time(result.get("tenantId"))!=tenant)
            throw new IllegalStateException("Canonical market tenant/environment mismatch");
        return result;
    }
    private static void route(Map<String,Map<String,Object>> routes,String code,String category) {
        if(code==null || category==null)return;
        Map<String,Object> row=new LinkedHashMap<>();row.put("code",code);row.put("category",category);
        routes.put(category+"\n"+code,row);
    }
    private static void currencyRoutes(Map<String,Map<String,Object>> routes,String currency,String source) {
        QuoteCurrencyConversion route=QuoteCurrencyConversion.route(currency,source);
        if(route!=null) {
            route(routes,route.code,route.category);
            if("Forex".equals(route.category))route(routes,route.code.replace("USD=X","=X"),route.category);
        }
    }
    @SuppressWarnings("unchecked")
    void refresh() {
        try {
            long tenant=TenantContext.requireTenantId();
            Map<String,TradingSymbol> registry=new LinkedHashMap<>();
            Map<String,Map<String,Object>> routes=new LinkedHashMap<>();
            for(TradingSymbol config:symbols.findAllByTenantId(tenant)) {
                registry.put(config.getSymbol(),config);
                route(routes,ForexQuoteMarketService.marketCode(config),config.getSourceCategory());
                currencyRoutes(routes,config.getQuoteCurrency(),config.getMarketSource());
                currencyRoutes(routes,config.getBaseCurrency(),"yahoo");
            }
            if(registry.size()>512 || routes.size()>512)throw new IllegalStateException("Simulation market batch exceeds limit");
            Map<String,Object> command=new LinkedHashMap<>();command.put("op","snapshot");
            command.put("symbols",new ArrayList<>(registry.keySet()));command.put("routes",new ArrayList<>(routes.values()));
            Map<String,Object> result=request(command);
            if(!(result.get("records") instanceof List) || !(result.get("raw") instanceof Map))throw new IllegalStateException("Incomplete canonical market");
            List<Map<String,Object>> records=(List<Map<String,Object>>)result.get("records");
            Set<String> seen=new HashSet<>();
            for(Map<String,Object> record:records) {
                TradingSymbol config=registry.get(record.get("symbol"));
                Map<String,Object> quote=(Map<String,Object>)record.get("quote");
                if(config==null || !seen.add(config.getSymbol()) || QuoteState.time(record.get("id"))!=config.getId()
                        || !(record.get("status") instanceof Map) || quote==null
                        || QuoteState.time(quote.get("tenantId"))!=tenant || QuoteState.time(quote.get("symbolId"))!=config.getId())
                    throw new IllegalStateException("Canonical market symbol identity mismatch");
            }
            for(TradingSymbol config:registry.values())if(!seen.contains(config.getSymbol())) {
                Map<String,Object> quote=new HashMap<>();quote.put("tenantId",tenant);quote.put("symbolId",config.getId());quote.put("available",false);
                Map<String,Object> record=new HashMap<>();record.put("id",config.getId());record.put("symbol",config.getSymbol());record.put("quote",quote);record.put("status",Collections.emptyMap());records.add(record);
            }
            long receivedAt=System.currentTimeMillis();
            Map<String,Map<String,Object>> rawSnapshot=new HashMap<>();
            ((Map<String,Map<String,Object>>)result.get("raw")).forEach((code,quote)->{
                if(!routes.containsKey(code))throw new IllegalStateException("Unexpected raw market route");
                Map<String,Object> copy=new HashMap<>(quote);copy.put("mirrorReceivedAt",receivedAt);rawSnapshot.put(code,copy);
            });
            raw.put(tenant,Collections.unmodifiableMap(rawSnapshot));
            Map<String,TradingSymbol> aliases=new HashMap<>(registry);
            for(TradingSymbol config:registry.values())aliases.putIfAbsent(ForexQuoteMarketService.marketCode(config),config);
            registries.put(tenant,aliases);
            for(Map<String,Object> record:records) {
                TradingSymbol config=registry.get(record.get("symbol"));
                Map<String,Object> quote=(Map<String,Object>)record.get("quote");
                String id=tenant+":"+config.getId();
                String version=quote.get("writerGeneration")+":"+quote.get("quoteVersion")+":"+quote.get("controlRevision")
                        +":"+quote.get("available")+":"+quote.get("marketClosed")+":"+quote.get("marketHoursRevision")+":"+config.getRowVersion();
                if(version.equals(versions.get(id)) && receivedAt-publishedAt.getOrDefault(id,0L)<1000)continue;
                publish(config,quote,(Map<String,Object>)record.get("status"),receivedAt);
                versions.put(id,version);publishedAt.put(id,receivedAt);
            }
        } catch(RuntimeException failure) { failed(failure); }
    }
    /** Reissue only local authority/configuration; preserve the canonical price, source times and candles. */
    void publish(TradingSymbol config,Map<String,Object> canonical,Map<String,Object> canonicalStatus,long receivedAt) {
        Map<String,Object> quote=localQuote(config,canonical,receivedAt);
        Map<String,Object> status=new LinkedHashMap<>(canonicalStatus);status.put("executionExpiresAt",quote.get("executionExpiresAt"));
        store.locked(config.getId(),()->{
            long symbolId=config.getId();
            if(QuoteState.time(quote.get("liveQuoteVersion"))>0
                    && QuoteState.time(quote.get("liveQuoteVersion"))==QuoteState.time(quote.get("canonicalQuoteVersion")))
                quote.put("liveQuoteVersion",store.db.queryForObject("SELECT snapshot_version FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",Long.class,TenantContext.requireTenantId(),symbolId)+1);
            store.runtime.snapshot(symbolId,quote,status,receivedAt);
            MarketQuoteCommitted.publish(events,TenantContext.requireTenantId(),config.getSymbol());return null;
        });
    }
    static Map<String,Object> localQuote(TradingSymbol config,Map<String,Object> canonical,long receivedAt) {
        TenantContext.require(config.getTenantId());
        if(QuoteState.time(canonical.get("tenantId"))!=config.getTenantId() || QuoteState.time(canonical.get("symbolId"))!=config.getId())
            throw new IllegalArgumentException("Canonical quote identity mismatch");
        Map<String,Object> quote=new LinkedHashMap<>(canonical);
        for(String field:Arrays.asList("quoteVersion","writerGeneration","controlRevision","configVersion","committedAt"))
            quote.put("canonical"+Character.toUpperCase(field.charAt(0))+field.substring(1),canonical.get(field));
        FundingQuoteAuthority.stamp(quote,config);
        long expiry=Math.min(QuoteState.time(canonical.get("executionExpiresAt")),receivedAt+MIRROR_LEASE_MS);
        quote.put("executionExpiresAt",expiry);quote.put("expiresAt",expiry);quote.put("mirrorReceivedAt",receivedAt);
        boolean available=Boolean.TRUE.equals(canonical.get("available")) && Boolean.TRUE.equals(config.getIsEnabled())
                && !Boolean.TRUE.equals(canonical.get("marketClosed")) && expiry>receivedAt && QuoteState.valid(quote);
        quote.put("available",available);quote.put("tradeAvailable",available);
        return quote;
    }
    public Map<String,Object> price(String code) {
        TradingSymbol config=registries.getOrDefault(TenantContext.requireTenantId(),Collections.emptyMap()).get(code);
        Map<String,Object> quote=config==null?new HashMap<>():store.runtime.read(config.getId(),false,System.currentTimeMillis());
        quote.remove(LiveKline.KEY);quote.put("symbol",code);return quote;
    }
    public Map<String,Object> rawPrice(String code,String category) {
        Map<String,Object> saved=raw.getOrDefault(TenantContext.requireTenantId(),Collections.emptyMap()).get(category+"\n"+code);
        Map<String,Object> result=QuoteState.view(saved,60000);
        if(saved==null || Boolean.FALSE.equals(saved.get("available")) || System.currentTimeMillis()-QuoteState.time(saved.get("mirrorReceivedAt"))>MIRROR_LEASE_MS) {
            result.put("available",false);result.put("tradeAvailable",false);result.put("status","canonical_market_unavailable");
        }
        return result;
    }
    public Map<String,Object> kline(String symbol,String interval,int limit,Long endTime) {
        KlineIntervals.rejectRetired(interval);
        Map<String,Object> command=new LinkedHashMap<>();command.put("op","kline");command.put("symbol",symbol);
        command.put("interval",interval);command.put("limit",Math.min(1000,Math.max(1,limit)));
        if(endTime!=null)command.put("endTime",endTime);
        return (Map<String,Object>)request(command).get("result");
    }
}
