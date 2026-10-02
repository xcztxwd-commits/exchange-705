package com.gtcfesk.exchange.user;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class AssetEquityHistoryService {
    private final AssetEquityStore store;
    private final EquityValuationService valuation;
    public Map<String,Object> history(Long user,String range){
        AssetHistoryService.rangeMillis(range); // Validate before quote/database work.
        EquityValuationService.Batch quotes=valuation.prepare(Collections.singletonList(user));
        return history(user,range,quotes,System.currentTimeMillis());
    }
    // Fixed clock/quote seam for real-database acceptance; not an HTTP parameter.
    Map<String,Object> history(Long user,String range,EquityValuationService.Batch quotes,long asOf){
        long from=asOf-AssetHistoryService.rangeMillis(range);
        int level=Arrays.asList("1D","1W","1M","1Y").indexOf(range);
        return store.read(db->{
            EquityValuationService.Value live=valuation.read(db,Collections.singletonList(user),quotes,asOf).get(user);
            List<AssetHistoryBucket> buckets=store.window(db,level,user,from,asOf+1,asOf);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("userId",user);
            result.put("schemaVersion",2);result.put("basisVersion",EquityValuationService.BASIS);result.put("basis","net_equity_option_cost");
            result.put("optionValuationMethod","PRINCIPAL_COST_NOT_FAIR_VALUE");result.put("sourceTable",AssetEquityStore.TABLES[level]);
            result.put("carryIn",carryIn(db,user,from));
            result.put("points",buckets.stream().map(AssetHistoryBucket::api).collect(Collectors.toList()));
            Map<String,Object> livePoint=live.api();livePoint.put("time",asOf);livePoint.put("value",AssetHistoryBucket.decimal(live.amounts.get("net_equity")));
            result.put("live",livePoint);result.put("components",live.api());result.put("total",livePoint.get("value"));
            result.put("valuationStatus",live.status());result.put("asOf",asOf);result.put("from",from);result.put("intervalMs",AssetEquityStore.INTERVALS[level]);
            result.put("hasHistory",!buckets.isEmpty());result.put("currency","USD");result.put("bucketTimezone","UTC");
            AssetHistoryBucket extremes=new AssetHistoryBucket(user,from,asOf+1);buckets.forEach(extremes::merge);
            Map<String,Object> extrema=new LinkedHashMap<>();extrema.put("high",extreme(extremes.highAt,extremes.high));extrema.put("low",extreme(extremes.lowAt,extremes.low));result.put("extrema",extrema);
            List<Map<String,Object>> missing=new ArrayList<>();long through=from;
            for(AssetHistoryBucket b:buckets){
                if(b.start>through)missing.add(gap(through,b.start,"NO_OBSERVATION"));
                if(b.invalid>0 || b.valid+b.invalid<b.expected)missing.add(gap(Math.max(from,b.start),Math.min(asOf,b.end),b.invalid>0?"INCOMPLETE_VALUATION":"PARTIAL_OBSERVATION"));
                through=Math.max(through,b.end);
            }
            if(through<asOf)missing.add(gap(through,asOf,"NO_OBSERVATION"));result.put("missingIntervals",missing);
            List<String> zones=db.query("select config_value from system_config where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and config_key='system.timezone'",(rs,n)->rs.getString(1));
            ZoneId zone;try{zone=ZoneId.of(zones.isEmpty()?"Europe/London":zones.get(0));}catch(RuntimeException e){zone=ZoneId.of("Europe/London");}
            long incomeFrom=AssetHistoryService.periodStart(range,Instant.ofEpochMilli(asOf),zone);
            BigDecimal income=new AssetHistoryService(db).income(user,incomeFrom,asOf);
            Map<String,Object> opening=opening(db,user,incomeFrom,asOf);
            BigDecimal base=opening==null?null:new BigDecimal(opening.get("value").toString());
            result.put("income",income.toPlainString());result.put("incomeOpening",AssetHistoryBucket.decimal(base));result.put("incomeOpeningSource",opening);
            result.put("incomePercent",base==null?null:AssetHistoryService.incomePercent(income,base));result.put("incomeFrom",incomeFrom);
            result.put("incomeBasis","settled_net_profit_and_paid_yield");result.put("timezone",zone.getId());
            return result;
        });
    }
    /** Display seed only. Never merge this into OHLC, sample counts, income or missing metadata. */
    Map<String,Object> carryIn(JdbcTemplate db,long user,long from){
        List<Map<String,Object>> rows=db.query("select coalesce(effective_at,observed_at),net_equity from asset_history_1m where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and basis_version=? and bucket_start<=? and coalesce(effective_at,observed_at)<? and net_equity is not null order by bucket_start desc limit 1",
                (rs,n)->extreme(rs.getLong(1),rs.getBigDecimal(2)),user,EquityValuationService.BASIS,AssetHistoryBucket.floor(from,60000),from);
        return rows.isEmpty()?null:rows.get(0);
    }
    Map<String,Object> opening(JdbcTemplate db,long user,long start,long now){
        List<Map<String,Object>> found=db.query("select net_equity,coalesce(effective_at,observed_at) from asset_history_1m where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and basis_version=? and bucket_start>=? and bucket_start<=? and coalesce(effective_at,observed_at)<=? order by bucket_start desc limit 1",
                (rs,n)->extreme(rs.getLong(2),rs.getBigDecimal(1)),user,EquityValuationService.BASIS,AssetHistoryBucket.floor(start-120000,60000),AssetHistoryBucket.floor(start,60000),Math.min(start,now));
        if(!found.isEmpty() && found.get(0)!=null && new BigDecimal(found.get(0).get("value").toString()).signum()>0){found.get(0).put("source","PERIOD_OPENING");return found.get(0);}
        found=db.query("select first_positive,first_positive_at from asset_history_baseline where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and basis_version=? and first_positive>0 and first_positive_at<=?",
                (rs,n)->extreme(rs.getLong(2),rs.getBigDecimal(1)),user,EquityValuationService.BASIS,now);
        if(found.isEmpty())return null;found.get(0).put("source","EARLIEST_POSITIVE_NET_EQUITY");return found.get(0);
    }
    static Map<String,Object> extreme(Long at,BigDecimal value){
        if(at==null || value==null)return null;
        Map<String,Object> p=new LinkedHashMap<>();p.put("time",at);p.put("value",value.toPlainString());p.put("basisVersion",EquityValuationService.BASIS);return p;
    }
    static Map<String,Object> gap(long from,long to,String reason){Map<String,Object> m=new LinkedHashMap<>();m.put("from",from);m.put("to",to);m.put("reason",reason);return m;}
}
