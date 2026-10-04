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
    private final ThreadLocal<Map<String,Long>> context = new ThreadLocal<>();
    private final ThreadLocal<Map<Long,Integer>> sampled = new ThreadLocal<>();
    private Boolean mysql;
    MarketRuntime(ControlHistoryStore store) { this.store=store; }
    private boolean mysql() {
        if(mysql==null) mysql=store.db.execute((java.sql.Connection c)->c.getMetaData().getDatabaseProductName().equals("MySQL"));
        return mysql;
    }
    long clock() { return mysql() ? store.db.queryForObject("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",Long.class) : System.currentTimeMillis(); }
    <T> T locked(long symbol,Supplier<T> operation) {
        long tenant=ControlHistoryStore.tenant(); String key=tenant+":"+symbol;
        if(context.get()!=null && context.get().containsKey(key)) return operation.get();
        // Validate tenant ownership without locking high-frequency metadata.
        store.db.queryForObject("SELECT id FROM trading_symbol WHERE tenant_id=? AND id=?",Long.class,tenant,symbol);
        store.db.update("INSERT INTO market_engine_runtime(tenant_id,symbol_id) VALUES(?,?) ON DUPLICATE KEY UPDATE symbol_id=VALUES(symbol_id)",tenant,symbol);
        Map<String,Object> row=store.db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=? FOR UPDATE",tenant,symbol);
        long now=clock(), generation=((Number)row.get("writer_generation")).longValue();
        Long captured=generations.get(key);
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
            @Override public void afterCommit(){generations.put(key,accepted);}
        });
        if(context.get()==null) {
            context.set(new LinkedHashMap<>());
            sampled.set(new HashMap<>());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int status){
                    try { if(mysql()) store.db.execute("SET @mt705_s2_owner=NULL,@mt705_s2_fences=NULL"); }
                    finally {context.remove();sampled.remove();}
                }
            });
        }
        context.get().put(key,generation);
        // Preserve the runtime column collation; JDBC string variables otherwise use connection collation.
        if(mysql()) store.db.update("SET @mt705_s2_owner=(SELECT owner_id FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?),@mt705_s2_fences=?",tenant,symbol,store.encode(new LinkedHashMap<String,Object>(context.get())));
        return operation.get();
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
        return result;
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
