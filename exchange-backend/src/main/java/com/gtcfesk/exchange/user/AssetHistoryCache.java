package com.gtcfesk.exchange.user;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Only closed bucket projections. Revision and loader use the caller's REPEATABLE READ snapshot. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="asset.history.cache",name="enabled",havingValue="true")
public class AssetHistoryCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    @Value("${asset.history.cache.enabled:false}") boolean enabled;
    @Value("${asset.history.cache.namespace}") String namespace="705:local";
    @Value("${asset.history.cache.hour-ttl-seconds:7200}") long hourTtl=7200;
    @Value("${asset.history.cache.four-hour-ttl-seconds:28800}") long fourHourTtl=28800;
    @Value("${asset.history.cache.day-ttl-seconds:172800}") long dayTtl=172800;
    public final LongAdder hits=new LongAdder(),misses=new LongAdder(),fallbacks=new LongAdder(),commands=new LongAdder();
    public AssetHistoryCache(@Qualifier("assetHistoryRedis") StringRedisTemplate redis,ObjectMapper json){this.redis=redis;this.json=json;}
    @javax.annotation.PostConstruct void validate(){
        if(!namespace.matches("[A-Za-z0-9:_-]{1,128}") || hourTtl<1 || fourHourTtl<1 || dayTtl<1 || Math.max(dayTtl,Math.max(hourTtl,fourHourTtl))>604800)
            throw new IllegalArgumentException("History cache requires an environment-specific namespace and TTLs between 1 and 604800 seconds");
    }
    String key(int level,long user,long start,long end,long revision){
        return namespace+":tenant:"+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+":equity:history:v2:"+EquityValuationService.BASIS+":"+user+":"+level+":"+start+":"+end+":"+revision;
    }
    List<AssetHistoryBucket> read(JdbcTemplate db,int level,long user,long start,long end,Supplier<List<AssetHistoryBucket>> loader){
        // Never catch MySQL errors here. No Redis invalidation/notification is needed after commit.
        List<Long> revisions=db.query("select revision from asset_history_revision where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and basis_version=? and level=?",(r,n)->r.getLong(1),user,EquityValuationService.BASIS,level);
        long revision=revisions.isEmpty()?0:revisions.get(0);
        String key=key(level,user,start,end,revision);boolean reachable=true;
        String raw=null;
        try {commands.increment();raw=redis.opsForValue().get(key);}
        catch(Exception failure){reachable=false;fallbacks.increment();}
        if(raw!=null)try {List<AssetHistoryBucket> rows=decode(raw,key,level,user,start,end);hits.increment();return rows;}
        catch(Exception corrupt){fallbacks.increment();}
        misses.increment();List<AssetHistoryBucket> rows=loader.get();
        // A stale reader may fill only its immutable old revision key, never the newer key.
        if(reachable)try {
            ObjectNode payload=json.createObjectNode();payload.put("key",key);ArrayNode data=payload.putArray("rows");
            for(AssetHistoryBucket b:rows){ObjectNode row=json.valueToTree(b.api());row.put("userId",b.userId);data.add(row);}
            commands.increment();redis.opsForValue().set(key,json.writeValueAsString(payload),Math.max(1,level==1?hourTtl:level==2?fourHourTtl:dayTtl),TimeUnit.SECONDS);
        }catch(Exception failure){fallbacks.increment();}
        return rows;
    }
    private long number(JsonNode row,String name){JsonNode v=row.get(name);if(v==null || !v.isIntegralNumber() || !v.canConvertToLong())throw new IllegalArgumentException("Invalid cache integer");return v.longValue();}
    private Long time(JsonNode row,String name){JsonNode v=row.get(name);if(v==null)throw new IllegalArgumentException("Missing cache time");return v.isNull()?null:number(row,name);}
    private BigDecimal money(JsonNode row,String name){JsonNode v=row.get(name);if(v==null || !(v.isNull() || v.isTextual()))throw new IllegalArgumentException("Invalid cache amount");return v.isNull()?null:new BigDecimal(v.textValue());}
    private List<AssetHistoryBucket> decode(String raw,String key,int level,long user,long start,long end)throws Exception{
        if(raw.length()>2000000)throw new IllegalArgumentException("Oversized history cache");
        JsonNode p=json.readTree(raw);if(!key.equals(p.path("key").asText()) || !p.path("rows").isArray())throw new IllegalArgumentException("Unknown cache envelope");
        if(p.get("rows").size()>2000)throw new IllegalArgumentException("Too many history buckets");
        List<AssetHistoryBucket> out=new ArrayList<>();long previous=Long.MIN_VALUE;
        for(JsonNode row:p.get("rows")){
            AssetHistoryBucket b=new AssetHistoryBucket(number(row,"userId"),number(row,"bucketStart"),number(row,"bucketEnd"));
            if(b.userId!=user || b.start<start || b.end>end || b.end-b.start!=AssetEquityStore.INTERVALS[level] || b.start%AssetEquityStore.INTERVALS[level]!=0 || b.start<=previous || !row.path("finalized").isBoolean() || !row.get("finalized").booleanValue())throw new IllegalArgumentException("Invalid cache coverage");
            previous=b.start;b.finalized=true;
            b.open=money(row,"open");b.high=money(row,"high");b.low=money(row,"low");b.close=money(row,"close");
            b.openAt=time(row,"openAt");b.highAt=time(row,"highAt");b.lowAt=time(row,"lowAt");b.closeAt=time(row,"closeAt");
            b.sourceCount=number(row,"sourceCount");b.valid=number(row,"validSampleCount");b.invalid=number(row,"invalidSampleCount");b.expected=number(row,"expectedSampleCount");b.through=number(row,"sourceThrough");
            if(b.sourceCount<0 || b.valid<0 || b.invalid<0 || b.expected<0)throw new IllegalArgumentException("Invalid cache counts");
            ObjectNode expected=json.valueToTree(b.api());expected.put("userId",user);
            if(!json.readTree(json.writeValueAsString(expected)).equals(row))throw new IllegalArgumentException("Invalid cache projection");
            out.add(b);
        }return out;
    }
}
