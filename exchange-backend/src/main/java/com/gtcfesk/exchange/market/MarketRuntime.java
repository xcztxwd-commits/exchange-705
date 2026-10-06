package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Database-clock lease, immutable captured generation and committed read model. */
final class MarketRuntime {
    private final ControlHistoryStore store;
    final String owner = UUID.randomUUID().toString();
    private final Map<String,Long> generations = new ConcurrentHashMap<>();
    private final Map<String,Long> uncertainGenerations = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String,Long>> context = new ThreadLocal<>();
    private final ThreadLocal<Map<Long,Integer>> sampled = new ThreadLocal<>();
    private final ThreadLocal<Long> began = new ThreadLocal<>();
    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MarketRuntime.class);
    private Boolean mysql;
    boolean hasBudget(long reserveMs) { return began.get()==null || (System.nanoTime()-began.get())/1000000 < 4500-reserveMs; }
    void requireBudget() { if(!hasBudget(0)) throw new BusinessException("ENGINE_BUDGET: 写事务时间预算耗尽"); }
    <T> T phase(long symbol,String phase,Supplier<T> operation) {
        long at=System.nanoTime();
        try{return operation.get();} finally {
            log.info("control_phase tenant={} symbol={} phase={} ms={} remainingMs={} owner={} traceId={} requestKey={} generation={}",ControlHistoryStore.tenant(),symbol,phase,(System.nanoTime()-at)/1000000,began.get()==null?4500:Math.max(0,4500-(System.nanoTime()-began.get())/1000000),owner,org.slf4j.MDC.get("traceId"),org.slf4j.MDC.get("requestKey"),context.get()==null?null:context.get().get(ControlHistoryStore.tenant()+":"+symbol));
        }
    }
    /** Caller has current-read locked a previously committed runtime+command owner/generation pair.
     * This is a confirmed fact even if the retry receipt's later COMMIT acknowledgement is lost.
     */
    void rememberCommittedGeneration(long symbol,long generation) {
        String key=ControlHistoryStore.tenant()+":"+symbol;
        generations.putIfAbsent(key,generation);uncertainGenerations.remove(key,generation);
    }
    /** Read only, after rollback. An old captured generation is never re-authorized. */
    String fenceReason(long symbol) {
        List<Map<String,Object>> rows=store.db.queryForList("SELECT owner_id,writer_generation,lease_until FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",ControlHistoryStore.tenant(),symbol);
        Long captured=generations.get(ControlHistoryStore.tenant()+":"+symbol);
        if(rows.isEmpty() || captured==null)return "UNKNOWN";
        Map<String,Object> row=rows.get(0);
        if(!owner.equals(row.get("owner_id")) || captured!=null && captured.longValue()!=((Number)row.get("writer_generation")).longValue())return "AUTHORITY_LOST";
        return ((Number)row.get("lease_until")).longValue()<=clock()?"LEASE_EXPIRED":"UNKNOWN";
    }
    MarketRuntime(ControlHistoryStore store) { this.store=store; }
    private boolean mysql() {
        if(mysql==null) mysql=store.db.execute((java.sql.Connection c)->c.getMetaData().getDatabaseProductName().equals("MySQL"));
        return mysql;
    }
    long clock() { return mysql() ? store.db.queryForObject("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",Long.class) : System.currentTimeMillis(); }
    <T> T locked(long symbol,Supplier<T> operation) {
        long tenant=ControlHistoryStore.tenant(); String key=tenant+":"+symbol;
        if(context.get()!=null && context.get().containsKey(key)) return operation.get();
        if(began.get()==null) {
            final java.sql.Connection connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(store.db.getDataSource());
            began.set(System.nanoTime());
            final Integer[] lockTimeout={null};
            // Register cleanup before any connection/session call can fail on a pooled worker.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int status){
                    // Bypass JdbcTemplate's already expired transaction timeout during session cleanup.
                    try(java.sql.Statement statement=connection.createStatement()) {
                        if(lockTimeout[0]!=null)statement.execute("SET SESSION innodb_lock_wait_timeout="+lockTimeout[0]);
                        if(Boolean.TRUE.equals(mysql))statement.execute("SET @mt705_s2_owner=NULL,@mt705_s2_fences=NULL");
                    } catch(java.sql.SQLException cleanupFailure) {
                        log.warn("control_session_cleanup owner={} reason={}",owner,cleanupFailure.getSQLState());
                        try{discardConnection(store.db.getDataSource(),connection);}catch(java.sql.SQLException | RuntimeException discardFailure){log.error("control_session_discard_failed owner={} reason={}",owner,discardFailure.getClass().getSimpleName());}
                    } finally{began.remove();}
                }
            });
            if(mysql()) {
                lockTimeout[0]=store.db.queryForObject("SELECT @@session.innodb_lock_wait_timeout",Integer.class);
                store.db.execute("SET SESSION innodb_lock_wait_timeout=2");
            }
        }
        requireBudget();
        // Validate tenant ownership without locking high-frequency metadata.
        store.db.queryForObject("SELECT id FROM trading_symbol WHERE tenant_id=? AND id=?",Long.class,tenant,symbol);
        store.db.update("INSERT INTO market_engine_runtime(tenant_id,symbol_id) VALUES(?,?) ON DUPLICATE KEY UPDATE symbol_id=VALUES(symbol_id)",tenant,symbol);
        Map<String,Object> row=phase(symbol,"runtime_lock",()->store.db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=? FOR UPDATE",tenant,symbol));
        requireBudget();
        long now=clock(), generation=((Number)row.get("writer_generation")).longValue();
        Long captured=generations.get(key), uncertain=uncertainGenerations.get(key);
        if(captured==null && uncertain!=null && generation>=uncertain) {
            // UNKNOWN never permits a late old turn to claim a newer owner/generation.
            // Equal generation owned elsewhere is conservatively fenced even if our claim rolled back.
            captured=generations.putIfAbsent(key,uncertain);if(captured==null)captured=uncertain;
        }
        if(uncertain!=null)uncertainGenerations.remove(key,uncertain); // Lower generation proves the first claim rolled back.
        if(captured!=null && (captured!=generation || !owner.equals(row.get("owner_id"))))
            throw new BusinessException("ENGINE_FENCED: 引擎代次已失效，旧工作不能重领租约");
        if(!owner.equals(row.get("owner_id"))) {
            if(((Number)row.get("lease_until")).longValue()>now) throw new BusinessException("ENGINE_BUSY: 已有行情写者");
            generation++;
        }
        store.db.update("UPDATE market_engine_runtime SET owner_id=?,writer_generation=?,lease_until=? WHERE tenant_id=? AND symbol_id=?",owner,generation,now+15000,tenant,symbol);
        final long accepted=generation;
        // Publish ownership only after commit. Failed claims must not poison the next retry.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){generations.putIfAbsent(key,accepted);uncertainGenerations.remove(key,accepted);}
            @Override public void afterCompletion(int status){if(status==STATUS_UNKNOWN)uncertainGenerations.putIfAbsent(key,accepted);}
        });
        if(context.get()==null) {
            context.set(new LinkedHashMap<>());
            sampled.set(new HashMap<>());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int status){
                    context.remove();sampled.remove();
                }
            });
        }
        context.get().put(key,generation);
        // Preserve the runtime column collation; JDBC string variables otherwise use connection collation.
        if(mysql()) store.db.update("SET @mt705_s2_owner=(SELECT owner_id FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?),@mt705_s2_fences=?",tenant,symbol,store.encode(new LinkedHashMap<String,Object>(context.get())));
        return phase(symbol,"writer",()->{T result=operation.get();requireBudget();return result;});
    }
    /** Hikari close recycles sessions: explicitly evict after failed fence/session cleanup. */
    static void discardConnection(javax.sql.DataSource source,java.sql.Connection connection) throws java.sql.SQLException {
        try {
            if(connection instanceof com.zaxxer.hikari.pool.ProxyConnection && source.isWrapperFor(com.zaxxer.hikari.HikariDataSource.class))
                source.unwrap(com.zaxxer.hikari.HikariDataSource.class).evictConnection(connection);
            else connection.abort(Runnable::run);
        } finally {connection.close();}
    }
    /** Nested source/pump calls share the physical transaction's shared work limit. */
    int sampleAllowance(long symbol) {
        if(sampled.get()==null) throw new IllegalStateException("Sampling requires an engine transaction");
        return Math.max(0,512-sampled.get().values().stream().mapToInt(Integer::intValue).sum());
    }
    void sampled(long symbol,int count) { sampled.get().merge(symbol,count,Integer::sum); }
    long revision(long symbol) {
        List<Long> values=store.db.queryForList("SELECT control_revision FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",Long.class,ControlHistoryStore.tenant(),symbol);
        return values.isEmpty()?0:values.get(0);
    }
    /** Called after changed 1m facts, inside their existing real writer transaction. */
    void sourceChanged(long symbol,long from,long to) {
        long tenant=ControlHistoryStore.tenant();
        Long captured=context.get()==null?null:context.get().get(tenant+":"+symbol);
        if(captured==null) throw new BusinessException("ENGINE_FENCED: Source revision needs the real writer");
        if(from<0 || from>to || from%60000!=0 || to%60000!=0) throw new IllegalArgumentException("invalid source dirty window");
        long now=clock();
        int changed=store.db.update("UPDATE market_engine_runtime SET source_input_revision=source_input_revision+1,source_dirty_from=CASE WHEN source_dirty_from IS NULL THEN ? ELSE LEAST(source_dirty_from,?) END,source_dirty_to=CASE WHEN source_dirty_to IS NULL THEN ? ELSE GREATEST(source_dirty_to,?) END WHERE tenant_id=? AND symbol_id=? AND owner_id=? AND writer_generation=? AND lease_until>?",
            from,from,to,to,tenant,symbol,owner,captured,now);
        if(changed!=1) throw new BusinessException("ENGINE_FENCED: Source revision writer expired");
    }
    void invalidate(long symbol) {
        store.db.update("UPDATE market_engine_runtime SET control_revision=control_revision+1 WHERE tenant_id=? AND symbol_id=?",ControlHistoryStore.tenant(),symbol);
    }
    Map<String,Object> read(long symbol,boolean status,long now) {
        List<Map<String,Object>> rows=store.db.queryForList("SELECT quote_json,status_json,writer_generation,control_revision,snapshot_version FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",ControlHistoryStore.tenant(),symbol);
        Map<String,Object> row=rows.isEmpty()?Collections.emptyMap():rows.get(0);
        Object body=row.get(status?"status_json":"quote_json");
        Map<String,Object> result=body==null?new LinkedHashMap<>():store.decode(String.valueOf(body));
        boolean authorityChanged=!rows.isEmpty() && (QuoteState.time(result.get("writerGeneration"))!=QuoteState.time(row.get("writer_generation"))
            || QuoteState.time(result.get("controlRevision"))!=QuoteState.time(row.get("control_revision"))
            || QuoteState.time(result.get("quoteVersion"))!=QuoteState.time(row.get("snapshot_version")));
        long expiry=QuoteState.time(result.get("executionExpiresAt"));
        if(!status && (authorityChanged || expiry<=now || !QuoteState.valid(result))) {
            result.put("available",false);result.put("tradeAvailable",false);result.put("stale",true);
            result.put("status",rows.isEmpty()?"engine_pending":"engine_lag");
        }
        if(!status && row.get("status_json")!=null) {
            Map<String,Object> health=store.decode(String.valueOf(row.get("status_json")));
            long lag=controlLag(health,now);
            result.put("controlLagMillis",lag);
            if(controlStalled(health,now)) {
                result.put("available",false);result.put("tradeAvailable",false);result.put("stale",true);
                result.put("engineLag",true);result.put("status","engine_lag");
            }
        }
        if(status) {
            String phase=String.valueOf(result.get("controlState"));
            long expected=QuoteState.time(result.get("sampledUntil"));
            if("TARGET".equals(phase) || "RUNNING".equals(phase)) {
                long end=QuoteState.time(result.get("plannedEnd")),stop=QuoteState.time(result.get("stopAt"));
                long cutoff=Math.min(now,stop>0?Math.min(end,stop):end);
                long start=QuoteState.time(result.get("startedAt"));
                expected=start>0?start+Math.max(0,(cutoff-start)/1000)*1000:expected;
            } else if("RECOVERING".equals(phase)) expected=Math.min(now,QuoteState.time(result.get("recoveryExpectedEnd")));
            long lag=Math.max(0,expected-QuoteState.time(result.get("sampledUntil")));
            if("RECOVERING".equals(phase))lag=Math.max(0,expected-QuoteState.time(result.get("controlProgressWatermark")));
            long expectedEnd="RECOVERING".equals(phase)?QuoteState.time(result.get("recoveryExpectedEnd")):QuoteState.time(result.get("plannedEnd"));
            if("TARGET".equals(phase) || "RUNNING".equals(phase) || "RECOVERING".equals(phase))result.put("remainingSeconds",Math.max(0,(expectedEnd-now+999)/1000));
            boolean waiting="WAITING_SOURCE".equals(phase),sourceUnavailable=Arrays.asList("SOURCE","HOLDING","RECOVERING","MANUAL").contains(phase) && (!Boolean.TRUE.equals(result.get("sourceAvailable")) || expiry<=now),stalled=controlStalled(result,now);
            result.put("expectedSampledUntil",expected);result.put("controlLagMillis",lag);
            result.put("degraded",waiting || sourceUnavailable || stalled || authorityChanged);
            result.put("progressStatus",waiting?"WAITING_SOURCE":sourceUnavailable?"WAITING_VALID_SOURCE":authorityChanged?"AUTHORITY_CHANGED":stalled?"ENGINE_LAG":"HEALTHY");
            // Snapshot/funding commits do not count as control progress or renew execution validity.
            boolean executable=!authorityChanged && expiry>now && Boolean.TRUE.equals(result.get("available")) && !stalled;
            result.put("available",executable);result.put("tradeAvailable",executable);
        }
        return result;
    }
    private static boolean controlStalled(Map<String,Object> status,long now) {
        String phase=String.valueOf(status.get("controlState"));
        if(controlLag(status,now)>2000)return true;
        long end="RECOVERING".equals(phase)?QuoteState.time(status.get("recoveryExpectedEnd")):QuoteState.time(status.get("plannedEnd"));
        long stop=QuoteState.time(status.get("stopAt"));if(stop>0)end=Math.min(end,stop);
        // A stuck final second (or a committed endpoint with no lifecycle commit) has small/zero
        // sample lag forever. Completion latency must be checked independently of sample distance.
        return Arrays.asList("TARGET","RUNNING","RECOVERING").contains(phase) && end>0 && now>end+2000;
    }
    private static long controlLag(Map<String,Object> status,long now) {
        String phase=String.valueOf(status.get("controlState"));
        if("RECOVERING".equals(phase))return Math.max(0,Math.min(now,QuoteState.time(status.get("recoveryExpectedEnd")))-QuoteState.time(status.get("controlProgressWatermark")));
        if(!Arrays.asList("TARGET","RUNNING").contains(phase))return 0;
        long end=QuoteState.time(status.get("plannedEnd")),stop=QuoteState.time(status.get("stopAt")),start=QuoteState.time(status.get("startedAt"));
        long cutoff=Math.min(now,stop>0?Math.min(end,stop):end);
        long expected=start>0?start+Math.max(0,(cutoff-start)/1000)*1000:QuoteState.time(status.get("sampledUntil"));
        return Math.max(0,expected-QuoteState.time(status.get("sampledUntil")));
    }
    /** Publication changes cache identity without resampling prices or extending their deadline. */
    void historyPublished(long symbol) {
        Map<String,Object> row=store.db.queryForMap("SELECT quote_json,status_json,writer_generation,control_revision,snapshot_version FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",ControlHistoryStore.tenant(),symbol);
        if(row.get("quote_json")==null) return;
        Map<String,Object> quote=store.decode((String)row.get("quote_json"));
        // Chart publication cannot re-authorize a quote invalidated by STOP or a real writer takeover.
        if(QuoteState.time(quote.get("writerGeneration"))!=QuoteState.time(row.get("writer_generation"))
                || QuoteState.time(quote.get("controlRevision"))!=QuoteState.time(row.get("control_revision"))
                || QuoteState.time(quote.get("quoteVersion"))!=QuoteState.time(row.get("snapshot_version"))) return;
        Map<String,Object> status=row.get("status_json")==null ? new LinkedHashMap<>() : store.decode((String)row.get("status_json"));
        Map<String,Object> publications=store.db.queryForMap("SELECT COUNT(*) AS n,COALESCE(SUM(p.to_at),0) AS total FROM market_control_publication p JOIN market_control_task t ON t.tenant_id=p.tenant_id AND t.id=p.task_id WHERE t.tenant_id=? AND t.symbol_id=?",ControlHistoryStore.tenant(),symbol);
        quote.put("controlHistoryRevision",quote.get("controlTaskId")+":"+quote.get("configVersion")+":"+publications.get("n")+":"+publications.get("total"));
        snapshot(symbol,quote,status,clock());
    }
    void snapshot(long symbol,Map<String,Object> quote,Map<String,Object> status,long now) {
        requireBudget();
        long tenant=ControlHistoryStore.tenant();
        Long captured=context.get()==null?null:context.get().get(tenant+":"+symbol);
        if(captured==null) throw new BusinessException("ENGINE_FENCED: 快照缺少当前事务写者代次");
        Map<String,Object> row=store.db.queryForMap("SELECT writer_generation,control_revision,snapshot_version FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",ControlHistoryStore.tenant(),symbol);
        quote=new LinkedHashMap<>(quote);status=new LinkedHashMap<>(status);
        quote.put("tenantId",ControlHistoryStore.tenant());quote.put("symbolId",symbol);
        for(Map<String,Object> value:Arrays.asList(quote,status)) {
            value.put("writerGeneration",row.get("writer_generation"));value.put("controlRevision",row.get("control_revision"));
            value.put("quoteVersion",((Number)row.get("snapshot_version")).longValue()+1);value.put("committedAt",now);
        }
        String expiry=mysql()?"CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)":"?";
        List<Object> parameters=new ArrayList<>(Arrays.asList(store.encode(quote),store.encode(status),now,tenant,symbol,owner,captured));
        if(!mysql()) parameters.add(clock());
        int changed=store.db.update("UPDATE market_engine_runtime SET snapshot_version=snapshot_version+1,quote_json=?,status_json=?,committed_at=? WHERE tenant_id=? AND symbol_id=? AND owner_id=? AND writer_generation=? AND lease_until>"+expiry,parameters.toArray());
        if(changed!=1) throw new BusinessException("ENGINE_FENCED: 快照写者或租期已失效");
    }
}
