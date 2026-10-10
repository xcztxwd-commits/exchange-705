package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** Bounded SOURCE-only adapter. Control/random/manual histories remain on the pure compatibility reader. */
@Service
public class SourceHistoryProjector {
    private final ControlHistoryStore facts;
    private final MinuteHistoryProjectionStore projection;
    private final Map<Long,Long> scanAfter = new java.util.concurrent.ConcurrentHashMap<>();
    public SourceHistoryProjector(ControlHistoryStore facts, PlatformTransactionManager manager) {
        this.facts=facts;
        projection=new MinuteHistoryProjectionStore(facts.db,manager,ignored->{},true);
    }
    /** Read delegate only; no project, generation claim, pump, or source ingestion. */
    public MinuteHistoryProjectionStore.ReadResult readWindow(long symbol,long fromMinute,long toMinute) {
        return projection.readSourceWindow(symbol,fromMinute,toMinute);
    }
    public int projectNextPage(int symbols) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Projection scan requires context only");
        long tenant=TenantContext.requireTenantId();int changed=0;
        // Key ordering, not a second lease/generation allocator. All rows are rechecked after the runtime lock.
        List<Map<String,Object>> routes=facts.db.queryForList("SELECT symbol_id,owner_id,writer_generation,control_revision FROM market_engine_runtime WHERE tenant_id=? AND symbol_id>? ORDER BY symbol_id LIMIT ?",tenant,scanAfter.getOrDefault(tenant,0L),Math.max(1,Math.min(16,symbols)));
        if(routes.isEmpty()) {scanAfter.remove(tenant);return 0;}
        for(Map<String,Object> route:routes) {
            long symbol=((Number)route.get("symbol_id")).longValue();scanAfter.put(tenant,symbol);
            if(project(symbol,String.valueOf(route.get("owner_id")),((Number)route.get("writer_generation")).longValue(),((Number)route.get("control_revision")).longValue())) changed++;
        }
        return changed;
    }
    boolean project(long symbol,String owner,long generation,long revision) {
        return Boolean.TRUE.equals(facts.transaction(()->{
            String qualificationRead=Boolean.TRUE.equals(facts.db.execute((java.sql.Connection c)->c.getMetaData().getDatabaseProductName().equals("MySQL"))) ? " LOCK IN SHARE MODE" : "";
            long tenant=TenantContext.requireTenantId();
            Map<String,Object> runtime=facts.db.queryForMap("SELECT owner_id,writer_generation,control_revision,source_input_revision,source_dirty_from,source_dirty_to FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=? FOR UPDATE",tenant,symbol);
            if(generation<=0 || !Objects.equals(owner,runtime.get("owner_id")) || generation!=((Number)runtime.get("writer_generation")).longValue()
                    || revision!=((Number)runtime.get("control_revision")).longValue()) throw new IllegalStateException("Projection authority fenced");
            Map<String,Object> config=facts.db.queryForMap("SELECT random_market_enabled,control_enabled FROM trading_symbol WHERE tenant_id=? AND id=? FOR UPDATE",tenant,symbol);
            if(on(config.get("random_market_enabled")) || on(config.get("control_enabled"))
                    || !facts.db.queryForList("SELECT id FROM market_control_task WHERE tenant_id=? AND symbol_id=? LIMIT 1"+qualificationRead,tenant,symbol).isEmpty()
                    || !facts.db.queryForList("SELECT minute_at FROM market_mixed_minute WHERE tenant_id=? AND symbol_id=? LIMIT 1"+qualificationRead,tenant,symbol).isEmpty()) return false;
            String currentRead=Boolean.TRUE.equals(facts.db.execute((java.sql.Connection c)->c.getMetaData().getDatabaseProductName().equals("MySQL"))) ? " LOCK IN SHARE MODE" : "";
            long now=facts.runtime.clock();
            // Two indexed edge seeks avoid a full MIN/MAX aggregate over every SOURCE row per turn.
            List<Long> firstRows=facts.db.queryForList("SELECT candle_at FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period='1m' AND /*! BINARY */ TRIM(period)<>'1M' AND candle_at>0 AND MOD(candle_at,60000)=0 AND candle_at<? ORDER BY candle_at LIMIT 1"+currentRead,Long.class,tenant,symbol,MinuteHistoryProjection.minute(now));
            if(firstRows.isEmpty()) return false;
            long first=firstRows.get(0),horizon=facts.db.queryForObject("SELECT candle_at FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period='1m' AND /*! BINARY */ TRIM(period)<>'1M' AND candle_at>0 AND MOD(candle_at,60000)=0 AND candle_at<? ORDER BY candle_at DESC LIMIT 1"+currentRead,Long.class,tenant,symbol,MinuteHistoryProjection.minute(now))+59999;
            MinuteHistoryProjectionStore.Progress progress=projection.progressCurrent(symbol);
            if(progress!=null && progress.generation>generation) throw new IllegalStateException("Projection generation regressed");
            long initial=progress!=null && progress.generation==generation ? progress.initialWatermark : first-1;
            long expected=progress!=null && progress.generation==generation ? progress.watermark : initial;
            Long dirtyFrom=(Long)runtime.get("source_dirty_from"),dirtyTo=(Long)runtime.get("source_dirty_to");
            long inputRevision=((Number)runtime.get("source_input_revision")).longValue();
            boolean current=progress!=null && progress.generation==generation;
            // A new real S2 generation must revalidate its entire starting prefix, not inherit an old dirty cursor.
            long append=expected<horizon ? MinuteHistoryProjection.minute(expected+1) : Long.MAX_VALUE;
            if(current && dirtyFrom!=null && dirtyFrom<=initial)
                throw new IllegalStateException("SOURCE_BEFORE_INITIAL_PENDING: Earlier coverage needs separate review");
            long from=current && dirtyFrom!=null ? Math.min(append,dirtyFrom) : append;
            if(from==Long.MAX_VALUE || from>MinuteHistoryProjection.minute(horizon)) return false;
            boolean replay=from<append;
            long end=replay ? Math.min(dirtyTo,MinuteHistoryProjection.minute(expected)) : MinuteHistoryProjection.minute(horizon);
            long to=Math.min(end,from+499*60000L);
            long next=from;
            List<MinuteHistoryProjection.SourceMinute> source=new ArrayList<>();
            for(Map<String,Object> row:facts.db.queryForList("SELECT candle_at,body,received_at FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period='1m' AND /*! BINARY */ TRIM(period)<>'1M' AND candle_at>0 AND MOD(candle_at,60000)=0 AND candle_at>=? AND candle_at<=? ORDER BY candle_at LIMIT 500"+currentRead,tenant,symbol,from,to)) {
                // An in-flight candle is not a completed minute, even when the wall clock has moved on.
                long minute=((Number)row.get("candle_at")).longValue(),received=((Number)row.get("received_at")).longValue();
                if(received>now) throw new IllegalArgumentException("source candle is outside receive cutoff");
                Map<String,Object> body=facts.decode((String)row.get("body"));
                // A watermark certifies every minute in its prefix, not just MIN/MAX of sparse facts.
                if(minute!=next || received<minute+59999 || Boolean.TRUE.equals(body.get("partial"))) break;
                source.add(new MinuteHistoryProjection.SourceMinute(body,received)); next+=60000;
            }
            if(source.isEmpty()) return false;
            to=next-60000;long watermark=Math.max(expected,to+59999);
            MinuteHistoryProjection.Block block=MinuteHistoryProjection.calculate(new MinuteHistoryProjection.CompletedFacts(symbol,generation,
                progress!=null && progress.generation==generation ? progress.version+1 : 1,expected,watermark,from,to,horizon,now,source,Collections.emptyList(),inputRevision));
            boolean mysql=Boolean.TRUE.equals(facts.db.execute((java.sql.Connection c)->c.getMetaData().getDatabaseProductName().equals("MySQL")));
            if(mysql){
                facts.db.update("SET @mt705_s4_tenant=?,@mt705_s4_symbol=?,@mt705_s4_generation=?,@mt705_s4_revision=?",tenant,symbol,generation,revision);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void afterCompletion(int status){facts.db.execute("SET @mt705_s4_tenant=NULL,@mt705_s4_symbol=NULL,@mt705_s4_generation=NULL,@mt705_s4_revision=NULL");}
                });
            }
            if(progress!=null && progress.generation==generation && horizon!=progress.stopAt)
                facts.db.update("UPDATE s4_history_projection_progress SET stop_at=? WHERE tenant_id=? AND symbol_id=? AND generation=?",horizon,tenant,symbol,generation);
            // SOURCE-only qualification was checked under the runtime lock; horizon is not a control STOP cutoff.
            projection.activateGeneration(symbol,generation,initial,horizon);
            boolean published=projection.publish(block);
            if(dirtyFrom!=null && from<=dirtyFrom && to>=dirtyFrom) {
                Long remaining=to>=dirtyTo ? null : to+60000;
                // Cursor movement, body/hash receipt and committed progress are one authority transaction.
                int cleared=facts.db.update("UPDATE market_engine_runtime SET source_dirty_from=?,source_dirty_to=? WHERE tenant_id=? AND symbol_id=? AND owner_id=? AND writer_generation=? AND control_revision=? AND source_input_revision=? AND source_dirty_from=? AND source_dirty_to=?",
                    remaining,remaining==null?null:dirtyTo,tenant,symbol,owner,generation,revision,inputRevision,dirtyFrom,dirtyTo);
                if(cleared!=1) throw new IllegalStateException("Source dirty cursor CAS lost");
            }
            return published;
        }));
    }
    private static boolean on(Object value){return Boolean.TRUE.equals(value) || value instanceof Number && ((Number)value).intValue()!=0;}
}
