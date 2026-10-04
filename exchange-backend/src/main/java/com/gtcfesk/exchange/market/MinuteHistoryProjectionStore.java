package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.IntConsumer;

/** Derived storage; S2 supplies generation. The qualified SOURCE adapter joins its authority transaction. */
public final class MinuteHistoryProjectionStore {
    private final JdbcTemplate db;
    private final TransactionTemplate writes, reads, sourceReads;
    private final IntConsumer afterWrite;
    private final boolean joinAuthorityTransaction;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public MinuteHistoryProjectionStore(JdbcTemplate db, PlatformTransactionManager manager) { this(db, manager, ignored -> {}); }
    MinuteHistoryProjectionStore(JdbcTemplate db, PlatformTransactionManager manager, IntConsumer afterWrite) {
        this(db,manager,afterWrite,false);
    }
    MinuteHistoryProjectionStore(JdbcTemplate db,PlatformTransactionManager manager,IntConsumer afterWrite,boolean joinAuthorityTransaction) {
        this.db = db; this.afterWrite = afterWrite; this.joinAuthorityTransaction=joinAuthorityTransaction;
        writes = new TransactionTemplate(manager); writes.setPropagationBehavior(joinAuthorityTransaction ? TransactionDefinition.PROPAGATION_REQUIRED : TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads = new TransactionTemplate(manager); reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setReadOnly(true); reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        sourceReads = new TransactionTemplate(manager);
        sourceReads.setReadOnly(true); sourceReads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public static final class Progress {
        public final long generation, version, initialWatermark, watermark, stopAt, sourceInputRevision;
        public final String hash;
        Progress(long generation, long version, long initialWatermark, long watermark, long stopAt, String hash,long sourceInputRevision) {
            this.sourceInputRevision=sourceInputRevision;
            this.generation = generation; this.version = version; this.initialWatermark = initialWatermark;
            this.watermark = watermark; this.stopAt = stopAt; this.hash = hash;
        }
    }
    public static final class ReadResult {
        public final Progress progress;
        public final List<Map<String,Object>> minutes;
        public final boolean pending, sourceOnly, sourceBlocked;
        public final long receivedCutoffMinimum, receivedCutoffPrefixMinimum;
        public final String status;
        ReadResult(Progress progress, List<Map<String,Object>> minutes, long fromMinute, long toMinute, long requestedEnd,boolean dirty,boolean sourceOnly,long receivedCutoffMinimum,long receivedCutoffPrefixMinimum) {
            this.progress = progress; this.minutes = minutes; this.sourceOnly=sourceOnly; this.sourceBlocked=sourceOnly && dirty;
            this.receivedCutoffMinimum=receivedCutoffMinimum;this.receivedCutoffPrefixMinimum=receivedCutoffPrefixMinimum;
            boolean complete=minutes.size()==(toMinute-fromMinute)/60000+1;
            for(int i=0;complete && i<minutes.size();i++) complete=ControlHistoryStore.time(minutes.get(i))==fromMinute+i*60000L;
            pending = progress == null || progress.watermark < requestedEnd || progress.stopAt < requestedEnd
                || fromMinute<=progress.initialWatermark || !complete || dirty;
            status = progress == null ? "pending" : pending ? "partial" : "available";
        }
    }
    /** Explicit adapter call; the caller must validate S2 ownership in the same physical transaction. */
    public void activateGeneration(long symbol, long generation, long initialWatermark, long stopAt) {
        if (symbol <= 0 || generation <= 0 || initialWatermark < 0 || stopAt < initialWatermark)
            throw new IllegalArgumentException("invalid projection generation");
        long owner = TenantContext.requireTenantId();
        writes.execute(status -> {
            TenantContext.require(owner);
            int inserted = db.update("INSERT IGNORE INTO s4_history_projection_progress(tenant_id,symbol_id,generation,fact_version,initial_watermark,watermark,stop_at,last_hash) VALUES(?,?,?,0,?,?,?,NULL)",
                owner, symbol, generation, initialWatermark, initialWatermark, stopAt);
            if (inserted > 0) afterWrite.accept(1);
            Progress current = progress(owner, symbol, true);
            if (current.generation > generation) throw new IllegalStateException("stale generation");
            if (current.generation == generation) {
                if (current.stopAt != stopAt || current.initialWatermark != initialWatermark)
                    throw new IllegalStateException("generation initial/STOP boundary changed");
                return null;
            }
            db.update("UPDATE s4_history_projection_progress SET generation=?,fact_version=0,input_revision=0,initial_watermark=?,watermark=?,stop_at=?,last_hash=NULL WHERE tenant_id=? AND symbol_id=?",
                generation, initialWatermark, initialWatermark, stopAt, owner, symbol);
            afterWrite.accept(1); return null;
        });
    }
    /** Returns false for an identical retry of the latest committed block. All changed/stale versions fail closed. */
    public boolean publish(MinuteHistoryProjection.Block block) {
        Objects.requireNonNull(block, "projection block");
        long owner = TenantContext.requireTenantId();
        return Boolean.TRUE.equals(writes.execute(status -> {
            TenantContext.require(owner);
            Progress current = progress(owner, block.symbol, true);
            if (current == null || current.generation != block.generation) throw new IllegalStateException("stale/unactivated generation");
            if (current.version == block.version && Objects.equals(current.hash, block.hash)) return false;
            if (block.version <= current.version) throw new IllegalStateException("stale/conflicting block version");
            if(block.sourceInputRevision<current.sourceInputRevision) throw new IllegalStateException("source input revision regressed");
            if(joinAuthorityTransaction && block.sourceInputRevision!=db.queryForObject("SELECT source_input_revision FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=? FOR UPDATE",Long.class,owner,block.symbol))
                throw new IllegalStateException("source input revision fenced");
            if (block.expectedWatermark != current.watermark || block.watermark < current.watermark || block.watermark > current.stopAt
                    || block.stopAt != current.stopAt) throw new IllegalStateException("committed/STOP progress changed");
            Map<Long,Boolean> protectedMinutes = new HashMap<>();
            db.query("SELECT minute_at,protected_mixed FROM s4_history_projection_minute WHERE tenant_id=? AND symbol_id=? AND minute_at>=? AND minute_at<=?",
                rs -> { protectedMinutes.put(rs.getLong(1), rs.getBoolean(2)); }, owner, block.symbol, block.fromMinute, block.toMinute);
            int write = 0;
            for (MinuteHistoryProjection.Minute minute : block.minutes) {
                if (!minute.mixed && Boolean.TRUE.equals(protectedMinutes.get(minute.minuteAt)))
                    throw new IllegalStateException("source-only revision cannot replace frozen/control minute");
                db.update("INSERT INTO s4_history_projection_minute(tenant_id,symbol_id,minute_at,generation,fact_version,body,received_cutoff,protected_mixed) VALUES(?,?,?,?,?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE generation=VALUES(generation),fact_version=VALUES(fact_version),body=VALUES(body),received_cutoff=VALUES(received_cutoff),protected_mixed=VALUES(protected_mixed)",
                    owner, block.symbol, minute.minuteAt, block.generation, block.version, minute.body, block.receivedCutoff, minute.mixed);
                afterWrite.accept(++write);
            }
            int updated = db.update("UPDATE s4_history_projection_progress SET fact_version=?,watermark=?,last_hash=?,input_revision=? WHERE tenant_id=? AND symbol_id=? AND generation=? AND fact_version=? AND watermark=?",
                block.version, block.watermark, block.hash,block.sourceInputRevision, owner, block.symbol, block.generation, current.version, current.watermark);
            if (updated != 1) throw new IllegalStateException("projection progress CAS lost");
            afterWrite.accept(++write); return true;
        }));
    }
    /** Progress and rows share a read-only MVCC snapshot; no request-time repairs, locks, or engine advancement. */
    public ReadResult readWindow(long symbol,long fromMinute,long toMinute) {
        return readWindow(symbol,fromMinute,toMinute,false);
    }
    /** Real SOURCE consumer only; generic frozen/control publication tests retain readWindow semantics. */
    public ReadResult readSourceWindow(long symbol,long fromMinute,long toMinute) {
        return readWindow(symbol,fromMinute,toMinute,true);
    }
    private ReadResult readWindow(long symbol, long fromMinute, long toMinute,boolean requireSourceAuthority) {
        if (symbol <= 0) throw new IllegalArgumentException("invalid symbol");
        MinuteHistoryProjection.window(fromMinute, toMinute);
        long owner = TenantContext.requireTenantId();
        if (requireSourceAuthority && org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            ControlHistoryStore.requireConsumerSnapshot(db);
        return (requireSourceAuthority ? sourceReads : reads).execute(status -> {
            TenantContext.require(owner);
            if (requireSourceAuthority) ControlHistoryStore.requireConsumerSnapshot(db);
            Progress progress = progress(owner, symbol, false);
            // This is a consistent read in this same physical RR, not a locking/current read or another connection.
            List<Map<String,Object>> runtimeRows=requireSourceAuthority ? db.queryForList("SELECT r.writer_generation,r.source_dirty_from,r.source_dirty_to,s.control_enabled,s.random_market_enabled,"
                +"EXISTS(SELECT 1 FROM market_control_task t WHERE t.tenant_id=s.tenant_id AND t.symbol_id=s.id LIMIT 1) AS has_task,"
                +"EXISTS(SELECT 1 FROM market_mixed_minute m WHERE m.tenant_id=s.tenant_id AND m.symbol_id=s.id LIMIT 1) AS has_mixed "
                +"FROM trading_symbol s LEFT JOIN market_engine_runtime r ON r.tenant_id=s.tenant_id AND r.symbol_id=s.id WHERE s.tenant_id=? AND s.id=?"
                ,owner,symbol) : Collections.emptyList();
            Map<String,Object> runtime=runtimeRows.isEmpty()?Collections.emptyMap():runtimeRows.get(0);
            boolean sourceOnly=requireSourceAuthority && !runtime.isEmpty()
                && !on(runtime.get("control_enabled")) && !on(runtime.get("random_market_enabled"))
                && !on(runtime.get("has_task")) && !on(runtime.get("has_mixed"));
            boolean stale=requireSourceAuthority && (!sourceOnly || progress==null || runtime.get("writer_generation")==null
                || ((Number)runtime.get("writer_generation")).longValue()!=progress.generation);
            long upper = progress == null || stale ? -1 : Math.min(toMinute, MinuteHistoryProjection.minute(Math.min(progress.watermark, progress.stopAt)));
            long[] cutoff={Long.MAX_VALUE},prefixCutoff={Long.MAX_VALUE};int[] prefixCount={0};boolean[] prefix={true};
            List<Map<String,Object>> rows = upper < fromMinute ? Collections.emptyList()
                : db.query("SELECT "+(requireSourceAuthority?"body,received_cutoff":"body")+" FROM s4_history_projection_minute WHERE tenant_id=? AND symbol_id=? AND generation=? AND minute_at>=? AND minute_at<=? ORDER BY minute_at",
                    (rs, number) -> {
                        Map<String,Object> row=decode(rs.getString(1));
                        if(requireSourceAuthority) {
                            long received=rs.getLong(2);cutoff[0]=Math.min(cutoff[0],received);
                            if(prefix[0] && ControlHistoryStore.time(row)==fromMinute+prefixCount[0]*60000L) {prefixCutoff[0]=Math.min(prefixCutoff[0],received);prefixCount[0]++;}
                            else prefix[0]=false;
                        }
                        return row;
                    }, owner, symbol, progress.generation, fromMinute, upper);
            long requestedEnd = toMinute > Long.MAX_VALUE - 59999 ? Long.MAX_VALUE : toMinute + 59999;
            boolean dirty=runtime.get("source_dirty_from")!=null
                && ((Number)runtime.get("source_dirty_from")).longValue()<=upper
                && ((Number)runtime.get("source_dirty_to")).longValue()>=fromMinute;
            return new ReadResult(progress, rows, fromMinute, toMinute, requestedEnd,dirty || stale,sourceOnly,cutoff[0]==Long.MAX_VALUE?0:cutoff[0],prefixCutoff[0]==Long.MAX_VALUE?0:prefixCutoff[0]);
        });
    }
    /** SOURCE writer uses a current progress row after holding runtime; generic reads remain MVCC. */
    Progress progressCurrent(long symbol) {
        if(symbol<=0) throw new IllegalArgumentException("invalid symbol");
        return progress(TenantContext.requireTenantId(),symbol,true);
    }
    public Progress progress(long symbol) {
        if (symbol <= 0) throw new IllegalArgumentException("invalid symbol");
        return progress(TenantContext.requireTenantId(), symbol, false);
    }
    private Progress progress(long owner, long symbol, boolean lock) {
        List<Progress> rows = db.query("SELECT generation,fact_version,initial_watermark,watermark,stop_at,last_hash,input_revision FROM s4_history_projection_progress WHERE tenant_id=? AND symbol_id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, number) -> new Progress(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getString(6),rs.getLong(7)), owner, symbol);
        return rows.isEmpty() ? null : rows.get(0);
    }
    private static boolean on(Object value){return Boolean.TRUE.equals(value) || value instanceof Number && ((Number)value).intValue()!=0;}
    private Map<String,Object> decode(String body) {
        try { return json.readValue(body, new TypeReference<Map<String,Object>>() {}); }
        catch (Exception invalid) { throw new IllegalStateException("invalid projected minute", invalid); }
    }
}
