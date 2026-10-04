package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.admin.SystemConfigService;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import javax.annotation.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared anonymous depth cache; visibility and enablement are always tenant scoped. */
@Service
public class MarketDepthService {
    public static final String ENABLED_KEY="market.depth.enabled";
    @Autowired TradingSymbolRepository symbols;
    @Autowired SystemConfigService configs;
    @Autowired ExchangeDepthSource source;
    @Autowired ExchangeDepthStream stream;
    @Value("${market.depth.enabled:true}") boolean globalEnabled=true;
    @Value("${market.depth.max-age-ms:15000}") long maxAgeMs=15000;
    @Value("${market.depth.poll-ms:5000}") long pollMs=5000;
    @Value("${market.depth.idle-ms:60000}") long idleMs=60000;
    private volatile boolean stopped;
    private final Map<String,Entry> cache=new LinkedHashMap<>();
    private final Map<String,Lease> leases=new LinkedHashMap<>();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"depth-cache");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"depth-rest");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    static final class Target {
        final Long tenantId,instrumentId;final String symbol,external,type,reason;
        Target(Long tenant,TradingSymbol s,String external,String type,String reason){tenantId=tenant;instrumentId=s==null?null:s.getId();symbol=s==null?null:s.getSymbol();this.external=external;this.type=type;this.reason=reason;}
        String key(){return type+":"+external;}
    }
    static final class Entry {
        final String key,external,type;
        volatile ExchangeDepthSource.Spec spec;
        volatile DepthBook book;
        volatile String error;
        volatile boolean pending,unsupported;
        volatile long nextPoll,restRequests,cacheHits,lastSuccess,metadataCheckedAt;
        Entry(Target t){key=t.key();external=t.external;type=t.type;}
    }
    private static final class Lease {final Target target;final long until;Lease(Target target,long until){this.target=target;this.until=until;}}
    @PostConstruct void start(){timer.scheduleWithFixedDelay(this::tick,0,1000,TimeUnit.MILLISECONDS);}
    @PreDestroy void stop(){stopped=true;timer.shutdownNow();workers.shutdownNow();synchronized(this){leases.clear();cache.clear();}stream.subscriptions(Collections.emptyList());}
    public boolean enabled(){return globalEnabled&&!"false".equalsIgnoreCase(configs.getConfigValue(ENABLED_KEY));}
    static String marketType(String value){if(value==null||value.isEmpty())return null;if("perpetual".equals(value))return "swap";if(!Arrays.asList("spot","swap").contains(value))throw new IllegalArgumentException("marketType must be spot or swap");return value;}
    private Target target(String symbol,String marketType){
        if(symbol==null||!symbol.matches("[A-Za-z0-9_.=-]{1,64}"))throw new IllegalArgumentException("Invalid symbol");
        Long tenant=TenantContext.requireTenantId();TradingSymbol s=symbols.findByTenantIdAndSymbol(tenant,symbol).orElse(null);
        if(s==null||!Boolean.TRUE.equals(s.getIsEnabled()))return new Target(tenant,s,null,marketType,"unknown_or_disabled_instrument");
        String category=ForexQuoteMarketService.sourceCategory(s),type=ExchangeQuoteSource.perpetual(category)?"swap":"spot";
        if(!Arrays.asList("Crypto","CryptoPerpetual","Metal").contains(category))return new Target(tenant,s,null,type,"unsupported_category");
        if(marketType!=null&&!marketType.equals(type))return new Target(tenant,s,null,type,"market_type_mismatch");
        try{return new Target(tenant,s,ExchangeQuoteSource.symbol(ForexQuoteMarketService.marketCode(s),category,source.okx()),type,null);}
        catch(MarketHttp.Failure e){return new Target(tenant,s,null,type,"unsupported_instrument");}
    }
    public Map<String,Object> read(String symbol,int levels,String type,String owner){
        if(levels<1||levels>20)throw new IllegalArgumentException("levels must be between 1 and 20");
        Target t=target(symbol,marketType(type));Map<String,Object> view=base(t,symbol,levels);boolean active=enabled();view.put("enabled",active);
        if(t.reason!=null){release(owner);view.put("status","UNSUPPORTED");view.put("reason",t.reason);return view;}
        if(!active){release(owner);view.put("status","DISABLED");view.put("reason","depth_disabled");return view;}
        Entry entry;boolean hit;
        synchronized(this){
            if(stopped){view.put("status","ERROR");view.put("reason","service_stopped");return view;}
            long tenantRest=leases.entrySet().stream().filter(e->e.getKey().startsWith("rest:")&&t.tenantId.equals(e.getValue().target.tenantId)).count();
            if(!leases.containsKey(owner)&&(leases.size()>=512||owner.startsWith("rest:")&&tenantRest>=16||!cache.containsKey(t.key())&&cache.size()>=32)){
                view.put("status","ERROR");view.put("reason","subscription_limit");return view;}
            leases.put(owner,new Lease(t,System.currentTimeMillis()+idleMs));entry=cache.get(t.key());hit=entry!=null&&entry.book!=null;
            if(entry==null){entry=new Entry(t);cache.put(t.key(),entry);}if(hit)entry.cacheHits++;
        }
        return project(view,entry,levels,hit);
    }
    private Map<String,Object> base(Target t,String symbol,int levels){Map<String,Object> r=new LinkedHashMap<>();r.put("instrumentId",t.instrumentId==null?null:t.instrumentId.toString());r.put("symbol",symbol);
        r.put("externalSymbol",t.external);r.put("provider",source.exchange.provider);r.put("marketType",t.type);r.put("quantityUnit",null);r.put("quantityCurrency",null);r.put("quoteCurrency",null);
        r.put("requestedLevels",levels);r.put("externalReference",true);r.put("bids",Collections.emptyList());r.put("asks",Collections.emptyList());
        Map<String,Integer> zero=new LinkedHashMap<>();zero.put("bids",0);zero.put("asks",0);r.put("displayedLevels",zero);r.put("availableLevels",zero);
        for(String key:Arrays.asList("spread","bidQuantity","askQuantity","bidRatio","askRatio","sequence","sourceAsOf","receivedAt"))r.put(key,null);
        r.put("sourceTimeBasis","NOT_PROVIDED");r.put("refreshMethod","NONE");r.put("refreshIntervalMs",null);r.put("reason",null);r.put("cacheHit",false);return r;
    }
    private Map<String,Object> project(Map<String,Object> r,Entry e,int levels,boolean hit){
        if(e.spec!=null)r.putAll(e.spec.view());DepthBook book=e.book;long now=System.currentTimeMillis();
        if(book!=null){r.putAll(book.view(levels));boolean old=now-book.receivedAt>maxAgeMs||book.sourceAsOf!=null&&now-book.sourceAsOf>maxAgeMs;
            r.put("status",old?"STALE":"LIVE");r.put("refreshIntervalMs","REST_POLL".equals(book.transport)?Math.max(5000,pollMs):"okx".equals(source.exchange.provider)?100:"swap".equals(e.type)?500:1000);
            r.put("latencyMs",book.sourceAsOf==null?null:Math.max(0,book.receivedAt-book.sourceAsOf));
        }else r.put("status",e.unsupported?"UNSUPPORTED":e.error==null?"SYNCING":"ERROR");
        r.put("reason",e.error);r.put("cacheHit",hit);return r;
    }
    public synchronized void release(String owner){leases.remove(owner);prune(System.currentTimeMillis());}
    public synchronized void disableTenant(Long tenant){leases.entrySet().removeIf(e->tenant.equals(e.getValue().target.tenantId));prune(System.currentTimeMillis());}
    private void prune(long now){leases.entrySet().removeIf(e->e.getValue().until<now);
        Set<String> wanted=new HashSet<>();for(Lease l:leases.values())wanted.add(l.target.key());cache.keySet().retainAll(wanted);
        List<ExchangeDepthSource.Spec> specs=new ArrayList<>();for(Entry e:cache.values())if(e.spec!=null&&!e.unsupported)specs.add(e.spec);stream.subscriptions(specs);
    }
    void tick(){
        try{
            synchronized(this){
                // Lease expiry must still run if tenant configuration reads temporarily fail.
                prune(System.currentTimeMillis());
                Map<Long,Boolean> active=new HashMap<>();
                for(Lease l:leases.values())if(!active.containsKey(l.target.tenantId))try(TenantContext.Scope ignored=TenantContext.open(l.target.tenantId)){active.put(l.target.tenantId,enabled());}
                leases.entrySet().removeIf(e->!Boolean.TRUE.equals(active.get(e.getValue().target.tenantId)));prune(System.currentTimeMillis());
                for(Entry e:cache.values()){
                    DepthBook ws=e.spec==null?null:stream.latest(e.spec);
                    if(ws!=null&&System.currentTimeMillis()-ws.receivedAt<=maxAgeMs){e.book=ws;e.lastSuccess=ws.receivedAt;e.error=null;}
                    boolean metadataDue=System.currentTimeMillis()-e.metadataCheckedAt>=3600000;
                    if(e.unsupported&&!metadataDue||e.pending||System.currentTimeMillis()<e.nextPoll||!metadataDue&&ws!=null&&System.currentTimeMillis()-ws.receivedAt<=maxAgeMs)continue;
                    e.pending=true;e.nextPoll=System.currentTimeMillis()+Math.max(5000,pollMs);
                    try{workers.execute(()->fetch(e));}catch(RejectedExecutionException full){e.pending=false;e.error="sync_capacity";}
                }
            }
        }catch(RuntimeException ignored){/* fail closed for configuration/DB outages; existing snapshots expire naturally */}
    }
    private void fetch(Entry e){try{
        ExchangeDepthSource.Spec spec=e.spec==null||System.currentTimeMillis()-e.metadataCheckedAt>=3600000?source.spec(e.external,e.type):e.spec;
        synchronized(this){if(cache.get(e.key)!=e||stopped)return;e.spec=spec;e.metadataCheckedAt=System.currentTimeMillis();e.unsupported=false;e.restRequests++;}
        DepthBook book=source.snapshot(spec);
        synchronized(this){if(cache.get(e.key)!=e||stopped)return;
            DepthBook ws=stream.latest(spec);
            // An in-flight REST response must never overwrite a newer live WebSocket snapshot.
            if(ws==null||System.currentTimeMillis()-ws.receivedAt>maxAgeMs){e.book=book;e.lastSuccess=book.receivedAt;e.error=null;}
        }
    }catch(MarketHttp.Failure failure){synchronized(this){if(cache.get(e.key)==e){e.error=failure.getMessage();e.unsupported="unsupported_instrument".equals(e.error);
        if(e.unsupported){e.book=null;e.spec=null;e.metadataCheckedAt=System.currentTimeMillis();}
        e.nextPoll=System.currentTimeMillis()+Math.max(10000,failure.retryAfterMs);}}}
    catch(RuntimeException failure){e.error="sync_failure";}finally{e.pending=false;}}
    public Map<String,Object> status(){Long tenant=TenantContext.requireTenantId();Map<String,Object> r=new LinkedHashMap<>();r.put("enabled",enabled());r.put("globalEnabled",globalEnabled);
        r.put("provider",source.exchange.provider);r.put("externalReference",true);r.put("pollIntervalMs",Math.max(5000,pollMs));r.put("maxAgeMs",maxAgeMs);r.put("idleMs",idleMs);
        r.put("streamEnabled",stream.enabled);r.put("maxActiveInstruments",32);r.put("maxTenantRestInstruments",16);r.put("maxDepthPerConnection",1);
        List<Map<String,Object>> rows=new ArrayList<>();
        for(TradingSymbol s:symbols.findByTenantIdAndIsEnabledTrueOrderBySortOrderDesc(tenant)){
            Target t=target(s.getSymbol(),null);Map<String,Object> row=base(t,s.getSymbol(),20);Entry e;
            synchronized(this){e=cache.get(t.key());}
            if(!enabled()){row.put("status","DISABLED");row.put("reason","depth_disabled");}
            else if(t.reason!=null){row.put("status","UNSUPPORTED");row.put("reason",t.reason);}
            else if(e==null){row.put("status","SYNCING");row.put("reason","not_requested");}
            else {project(row,e,20,e.book!=null);row.put("lastSuccessAt",e.lastSuccess==0?null:e.lastSuccess);row.put("restRequests",e.restRequests);row.put("cacheHits",e.cacheHits);}
            row.put("enabled",enabled());row.put("stream",t.type==null?null:stream.status(t.type));
            row.put("supportVerified",e!=null&&e.spec!=null&&!e.unsupported);rows.add(row);
        }
        r.put("instruments",rows);synchronized(this){r.put("activeReferences",leases.values().stream().filter(l->tenant.equals(l.target.tenantId)).count());}
        return r;
    }
}
