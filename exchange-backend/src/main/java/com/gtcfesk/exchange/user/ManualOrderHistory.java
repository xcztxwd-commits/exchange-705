package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.trade.ManualOrderCalculation;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;

/** Uses the caller's transaction connection; never commits or advances global watermarks. */
@Repository @RequiredArgsConstructor
public class ManualOrderHistory {
    private final AssetEquityStore store;
    public static final int MAX_ROWS=10000;
    private static final String BASIS=EquityValuationService.BASIS;
    public Map<String,Object> inspect(JdbcTemplate db,long user,long minute) {
        // Bound the full day needed by the reducer, not just the directly modified suffix.
        List<Long> rows=db.query("select bucket_start from asset_history_1m where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and basis_version=? and bucket_start>=? order by bucket_start limit 10001",
                (r,n)->r.getLong(1),user,BASIS,AssetHistoryBucket.floor(minute,86400000));
        List<Map<String,Object>> target=db.queryForList("select * from asset_history_1m where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and basis_version=? and bucket_start=?",user,BASIS,minute);
        if(rows.size()+(target.isEmpty()?1:0)>MAX_ROWS) throw new BusinessException("历史范围超出同步处理上限（含边界日最多 10000 点）");
        BigDecimal base=target.isEmpty()?null:(BigDecimal)target.get(0).get("net_equity");
        String source="EXISTING";Long sourceMinute=minute;
        if(base==null) {
            List<Map<String,Object>> previous=db.queryForList("select bucket_start,net_equity from asset_history_1m where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and basis_version=? and bucket_start>=? and bucket_start<? and net_equity is not null order by bucket_start desc limit 1",user,BASIS,minute-86400000,minute);
            source=previous.isEmpty()?"ZERO":"CARRY_24H";
            base=previous.isEmpty()?BigDecimal.ZERO:(BigDecimal)previous.get(0).get("net_equity");
            sourceMinute=previous.isEmpty()?null:((Number)previous.get(0).get("bucket_start")).longValue();
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("base",base);result.put("baseSource",source);result.put("sourceMinute",sourceMinute);
        result.put("original",target.isEmpty()?null:target.get(0));result.put("from",minute);
        result.put("through",rows.isEmpty()?minute:Math.max(minute,rows.get(rows.size()-1)));
        result.put("existingValidCount",db.queryForObject("select count(*) from asset_history_1m where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and basis_version=? and bucket_start>=? and net_equity is not null",Long.class,user,BASIS,minute));
        result.put("insertOrRepair",!"EXISTING".equals(source));
        List<Map<String,Object>> periods=new ArrayList<>();long now=System.currentTimeMillis();
        for(int level=1;level<=3;level++) {
            long size=AssetEquityStore.INTERVALS[level],start=AssetHistoryBucket.floor(minute,size);
            long limit=AssetHistoryBucket.floor(now-new long[]{0,135000,270000,525000}[level],size);
            if(level>1)limit=Math.min(limit,store.state(db,"rollup_"+(level-1),0)[0]);
            Map<String,Object> period=new LinkedHashMap<>();period.put("table",AssetEquityStore.TABLES[level]);period.put("start",start);period.put("end",start+size);period.put("eligible",start+size<=limit);periods.add(period);
        }
        result.put("closePeriods",periods);
        return result;
    }
    public Map<String,Object> apply(JdbcTemplate db,long user,long minute,BigDecimal net,long now,Consumer<String> checkpoint) {
        Map<String,Object> result=inspect(db,user,minute);
        // Strict DECIMAL overflow checks also apply to stored historical adjustments.
        db.query("select net_equity,manual_adjustment from asset_history_1m where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and basis_version=? and bucket_start>=? and net_equity is not null",r->{
            ManualOrderCalculation.money(r.getBigDecimal(1).add(net));ManualOrderCalculation.money(r.getBigDecimal(2).add(net));
        },user,BASIS,minute);
        int changed=db.update("update asset_history_1m set net_equity=net_equity+?,manual_adjustment=manual_adjustment+? where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and basis_version=? and bucket_start>=? and net_equity is not null",net,net,user,BASIS,minute);
        if(Boolean.TRUE.equals(result.get("insertOrRepair"))) {
            BigDecimal value=ManualOrderCalculation.money(((BigDecimal)result.get("base")).add(net));
            String origin="ZERO".equals(result.get("baseSource"))?"MANUAL_ZERO":"MANUAL_CARRY";
            if(result.get("original")==null) {
                db.update("insert into asset_history_1m(tenant_id,user_id,basis_version,bucket_start,effective_at,net_equity,manual_adjustment,valuation_status,reason_code,valuation_evidence,origin,created_at) values("+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+",?,?,?,?,?,?,'ESTIMATED','MANUAL_ESTIMATE',?,?,?)",
                        user,BASIS,minute,minute+60000,value,net,store.json(result),origin,now);
            } else {
                // Keep original observation time, components, failure reason and evidence intact.
                db.update("update asset_history_1m set net_equity=?,manual_adjustment=manual_adjustment+?,effective_at=?,origin=?,valuation_status='ESTIMATED' where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and basis_version=? and bucket_start=?",
                        value,net,minute+60000,origin,user,BASIS,minute);
            }
            changed++;
        }
        result.put("changedMinutes",changed);checkpoint.accept("minutes");
        List<Long> affected=db.query("select bucket_start from asset_history_1m where tenant_id="+com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()+" and user_id=? and basis_version=? and bucket_start>=? and net_equity is not null order by bucket_start",(r,n)->r.getLong(1),user,BASIS,minute);
        List<String> deferred=new ArrayList<>();int parents=0;
        for(int level=1;level<=3;level++) {
            long size=AssetEquityStore.INTERVALS[level];
            long limit=AssetHistoryBucket.floor(now-new long[]{0,135000,270000,525000}[level],size);
            if(level>1) limit=Math.min(limit,store.state(db,"rollup_"+(level-1),0)[0]);
            Set<Long> starts=new TreeSet<>();for(long at:affected)starts.add(AssetHistoryBucket.floor(at,size));
            for(long start:starts) {
                if(start+size>limit) {deferred.add(level+":"+start);continue;}
                AssetHistoryBucket b=new AssetHistoryBucket(user,start,start+size);
                for(AssetHistoryBucket child:store.source(db,level-1,Collections.singletonList(user),start,start+size,now,true))b.merge(child);
                if(b.sourceCount>0) {b.finalized=true;store.saveBucket(db,level,b,now);parents++;checkpoint.accept("parent");}
            }
        }
        result.put("repairedParents",parents);result.put("deferredPeriods",deferred);return result;
    }
}
