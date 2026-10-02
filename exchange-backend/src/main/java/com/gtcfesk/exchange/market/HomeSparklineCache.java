package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.beans.factory.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Homepage-only snapshots. Reads never call the quote/K-line service, including cold misses. */
@Service
public class HomeSparklineCache {
    public static final long REFRESH_MS = 300000L, RETAIN_MS = 86400000L;
    private final StringRedisTemplate redis;
    private final TradingSymbolRepository symbols;
    private final ForexQuoteMarketService quotes;
    private final Clock clock;
    private final String mode;
    private final ObjectMapper json = new ObjectMapper();
    // ponytail: bounded process fallback, not a second authority; Redis owns cross-process publication.
    private final Map<String, Snapshot> fallback = boundedMap(128);
    private final Map<String, Long> attempted = boundedMap(128);
    private static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>(
        "if redis.call('GET',KEYS[1])~=ARGV[1] then return 0 end " +
        "local old=redis.call('GET',KEYS[2]); if old then local ok,v=pcall(cjson.decode,old); " +
        "if ok and tonumber(v.bucket)>tonumber(ARGV[4]) then return 0 end end " +
        "redis.call('SET',KEYS[2],ARGV[2],'EX',ARGV[3]); return 1", Long.class);

    @Autowired
    public HomeSparklineCache(StringRedisTemplate redis, TradingSymbolRepository symbols,
            ForexQuoteMarketService quotes, @Value("${simulation.enabled:false}") boolean simulation) {
        this(redis, symbols, quotes, simulation, Clock.systemUTC());
    }
    HomeSparklineCache(StringRedisTemplate redis, TradingSymbolRepository symbols,
            ForexQuoteMarketService quotes, boolean simulation, Clock clock) {
        this.redis=redis; this.symbols=symbols; this.quotes=quotes; this.clock=clock;
        this.mode=simulation ? "DEMO" : "REAL";
    }
    private static <V> Map<String,V> boundedMap(final int limit) {
        return Collections.synchronizedMap(new LinkedHashMap<String,V>(16,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,V> item) { return size()>limit; }
        });
    }
    public static class Row {
        public List<Double> points = Collections.emptyList();
        public Long updatedAt;
        public String sourceStatus = "unavailable";
        public String reason = "source_empty";
    }
    public static class Snapshot {
        public long bucket, updatedAt;
        public Map<String,Row> items = new LinkedHashMap<>();
    }
    private static class Catalog {
        final Map<String,TradingSymbol> items = new TreeMap<>();
        final String key;
        Catalog(List<TradingSymbol> list, String mode) {
            StringBuilder identity = new StringBuilder();
            for (TradingSymbol s : list) items.put(s.getSymbol(), s);
            for (TradingSymbol s : items.values()) identity.append(Arrays.asList(s.getSymbol(), s.getMarketSource(),
                s.getSourceCategory(), ForexQuoteMarketService.marketCode(s), s.getRandomMarketEnabled(),
                s.getRandomMarketStartedAt()).toString()).append('\n');
            key="tenant:"+TenantContext.requireTenantId()+":market:home-sparkline:v1:"+mode+":"+digest(identity.toString());
        }
    }
    private Catalog catalog() {
        return new Catalog(symbols.findByTenantIdAndIsEnabledTrueOrderBySortOrderDesc(TenantContext.requireTenantId()), mode);
    }
    private static String digest(String value) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder(); for(byte b:bytes) hex.append(String.format("%02x",b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private Snapshot remembered(String key) {
        Snapshot s=fallback.get(key);
        return s!=null && clock.millis()-s.updatedAt<RETAIN_MS ? s : null;
    }
    private Snapshot load(String key) throws Exception {
        String value=redis.opsForValue().get(key);
        if(value==null) return null;
        Snapshot s=json.readValue(value, Snapshot.class);
        if(s.items==null || clock.millis()-s.updatedAt>=RETAIN_MS) return null;
        if(s.updatedAt>clock.millis()+5000 || s.bucket>Math.floorDiv(clock.millis(),REFRESH_MS))
            throw new IllegalStateException("Invalid homepage snapshot time");
        for(Row row:s.items.values()) if(row==null || row.points==null || row.points.size()>20 ||
                row.points.stream().anyMatch(point->point==null || !Double.isFinite(point) || point<=0))
            throw new IllegalStateException("Invalid homepage snapshot points");
        fallback.put(key,s); return s;
    }

    /** Called by the controlled job only. One attempt per tenant/mode/source catalog per UTC bucket. */
    public boolean refresh() {
        Catalog c=catalog(); long now=clock.millis(), bucket=Math.floorDiv(now,REFRESH_MS);
        synchronized(attempted) {
            if(Objects.equals(attempted.get(c.key),bucket)) return false;
            attempted.put(c.key,bucket);
        }
        String attemptKey=c.key+":attempt:"+bucket, token=UUID.randomUUID().toString();
        try {
            // Keep the claim for three buckets: a crash/empty source never triggers request-driven retries.
            if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(attemptKey,token,900,TimeUnit.SECONDS))) return false;
            Snapshot previous=load(c.key), next=new Snapshot();
            next.bucket=bucket;
            for(TradingSymbol s:c.items.values()) {
                Row row=new Row();
                try {
                    Map<String,Object> result=quotes.internalKline(s.getSymbol(),"5m",20);
                    Object raw=result.get("data");
                    if(raw instanceof Map) {
                        Map<?,?> data=(Map<?,?>)raw;
                        Object status=data.containsKey("status") ? data.get("status") : result.get("status");
                        row.sourceStatus=String.valueOf(status);
                        Object rows=data.get("kline_list");
                        if(rows instanceof List) {
                            List<Double> points=new ArrayList<>();
                            for(Object item:(List<?>)rows) if(item instanceof Map) {
                                Object close=((Map<?,?>)item).get("close");
                                if(close==null) close=((Map<?,?>)item).get("close_price");
                                try { double p=Double.parseDouble(String.valueOf(close)); if(Double.isFinite(p)&&p>0) points.add(p); }
                                catch(NumberFormatException ignored) { }
                            }
                            row.points=new ArrayList<>(points.subList(Math.max(0,points.size()-20),points.size()));
                        }
                    }
                } catch(RuntimeException unavailable) { row.reason="source_unavailable"; }
                if(!row.points.isEmpty()) {
                    row.updatedAt=clock.millis(); row.reason="available".equals(row.sourceStatus) ? null : "source_stale";
                } else if(previous!=null && previous.items.containsKey(s.getSymbol()) && !previous.items.get(s.getSymbol()).points.isEmpty()
                        && previous.items.get(s.getSymbol()).updatedAt!=null
                        && clock.millis()-previous.items.get(s.getSymbol()).updatedAt<RETAIN_MS) {
                    Row old=previous.items.get(s.getSymbol());
                    row.points=old.points; row.updatedAt=old.updatedAt; row.sourceStatus="stale"; row.reason="source_empty";
                }
                next.items.put(s.getSymbol(),row);
            }
            next.updatedAt=clock.millis();
            Long published=redis.execute(PUBLISH,Arrays.asList(attemptKey,c.key),token,json.writeValueAsString(next),"86400",String.valueOf(bucket));
            if(Long.valueOf(1).equals(published)) { fallback.put(c.key,next); return true; }
        } catch(Exception unavailable) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Homepage snapshot refresh deferred until next 300-second bucket: {}",unavailable.getClass().getSimpleName());
        }
        return false;
    }

    public Map<String,Object> read(List<String> requested) {
        Catalog c=catalog();
        if(requested==null || requested.isEmpty() || requested.size()>512 ||
                requested.stream().anyMatch(s->s==null || !c.items.containsKey(s)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown or disabled homepage symbol");
        Snapshot saved; String reason=null;
        try { saved=load(c.key); if(saved==null) reason="cache_miss"; }
        catch(Exception unavailable) { saved=null; reason="redis_unavailable"; }
        if(saved==null) saved=remembered(c.key);
        long now=clock.millis(), bucket=Math.floorDiv(now,REFRESH_MS);
        boolean fresh=saved!=null && saved.bucket==bucket && reason==null;
        List<Map<String,Object>> items=new ArrayList<>(); boolean anyPoints=false, allFresh=true;
        for(String name:new LinkedHashSet<>(requested)) {
            TradingSymbol symbol=c.items.get(name); Row row=saved==null ? null : saved.items.get(name);
            if(row!=null && row.updatedAt!=null && now-row.updatedAt>=RETAIN_MS) row=null;
            List<Double> points=row==null ? Collections.emptyList() : row.points;
            String status=points.isEmpty() ? "empty" : fresh && row.reason==null ? "fresh" : "stale";
            anyPoints|=!points.isEmpty(); allFresh&="fresh".equals(status);
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("symbol",name); item.put("points",points); item.put("status",status);
            item.put("updatedAt",row==null ? null : row.updatedAt);
            item.put("source",symbol.getMarketSource()); item.put("sourceCategory",symbol.getSourceCategory());
            item.put("reason",reason!=null ? reason : !fresh ? "refresh_due" : row==null ? "source_empty" : row.reason);
            items.add(item);
        }
        Map<String,Object> response=new LinkedHashMap<>();
        response.put("mode",mode); response.put("status",!anyPoints ? "empty" : allFresh ? "fresh" : "stale");
        response.put("reason",reason!=null ? reason : !fresh ? "refresh_due" : null);
        response.put("updatedAt",saved==null ? null : saved.updatedAt); response.put("serverNow",now);
        response.put("bucketStartAt",bucket*REFRESH_MS); response.put("nextRefreshAt",(bucket+1)*REFRESH_MS);
        response.put("items",items);
        return response;
    }
}
