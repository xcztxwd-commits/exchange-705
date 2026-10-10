package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.OrderRequest;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.control.ControlIdentity;
import com.gtcfesk.exchange.entity.TradingSymbol;
import org.springframework.stereotype.Service;
import java.util.*;
import java.time.ZoneId;

/** Append-only display corrections. Prices, control evidence and trading authority are untouched. */
@Service
public class HistorySourceRestore {
    private final ControlHistoryStore store;
    private final ControlAuditService audit;
    public HistorySourceRestore(ControlHistoryStore store, ControlAuditService audit) { this.store=store; this.audit=audit; }
    // ponytail: one day per request; larger repairs use successive reviewed ranges, not unbounded transactions.
    static final int MAX_MINUTES=1440, BATCH=100;
    static long value(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
    private void range(long from,long to,String zone) {
        try { ZoneId.of(zone); } catch(RuntimeException invalid) { throw new BusinessException("请选择有效时区"); }
        if(from<946684800000L || from%60000!=0 || to%60000!=0 || to<from || (to-from)/60000>=MAX_MINUTES)
            throw new BusinessException("请选择完整分钟，单次最多恢复 1440 分钟");
        if(to+60000>store.runtime.clock()) throw new BusinessException("只能恢复已结束的历史分钟");
    }
    private String identity(TradingSymbol config,String provider) {
        if(RandomMarketPath.enabled(config) || !Arrays.asList("Yahoo","Binance","OKX").contains(provider)
                || !Objects.equals(MarketInstrumentCatalog.inferredSource(config.getSourceCategory()),config.getMarketSource()))
            throw new BusinessException("该品种的原始源路由不支持历史恢复");
        return provider+":"+config.getSourceCategory()+":"+ForexQuoteMarketService.marketCode(config);
    }
    private void stable(long symbol,long from,long to) {
        HistoryOrdering.Policy policy=store.historyOrdering.policyForPage(symbol);
        if(policy!=null && from<policy.fromMinute) throw new BusinessException("所选区间包含已封存历史");
        List<Integer> pending=store.db.queryForList("SELECT 1 FROM market_control_task t LEFT JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id WHERE t.tenant_id=? AND t.symbol_id=? AND t.started_at<=? AND COALESCE(f.finished_at,t.ended_at,9223372036854775807)>=? AND (t.sampled_until<LEAST(?,COALESCE(t.stop_at,t.planned_end)) OR f.history_pending_until IS NOT NULL OR f.state IN ('WAITING_SOURCE','RECOVERING')) LIMIT 1",Integer.class,ControlHistoryStore.tenant(),symbol,to+59999,from,to+59999);
        if(!pending.isEmpty()) throw new BusinessException("所选历史仍在补齐或最终化，请稍后重新预览");
    }
    private boolean canonical(Map<String,Object> saved,String identity,String provider,String configured) {
        try {
            Map<String,Object> body=store.decode((String)saved.get("body")); long at=value(saved,"candle_at");
            return at%60000==0 && ControlHistoryStore.time(body)==at && SourceHistoryGapRepair.valid(body)
                && !Boolean.TRUE.equals(body.get("partial")) && value(saved,"received_at")>=at+59999
                && (!body.containsKey("historySource") || identity.equals(body.get("historySource")))
                && (!body.containsKey("source") || Arrays.asList(provider,configured,"External").contains(body.get("source")));
        } catch(RuntimeException invalid) { return false; }
    }
    private TreeMap<Long,Map<String,Object>> source(long symbol,long from,long to,String identity,String provider,String configured) {
        TreeMap<Long,Map<String,Object>> rows=new TreeMap<>();
        for(Map<String,Object> saved:store.db.queryForList("SELECT candle_at,body,received_at FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period='1m' AND /*! BINARY */ TRIM(period)<>'1M' AND candle_at>=? AND candle_at<=? AND MOD(candle_at,60000)=0 ORDER BY candle_at LIMIT 1440",ControlHistoryStore.tenant(),symbol,from,to))
            if(canonical(saved,identity,provider,configured)) rows.put(value(saved,"candle_at"),store.decode((String)saved.get("body")));
        return rows;
    }
    private TreeMap<Long,Map<String,Object>> visible(long symbol,long from,long to,Map<Long,Map<String,Object>> source) {
        TreeMap<Long,Map<String,Object>> rows=new TreeMap<>(source);
        for(Map<String,Object> row:store.visibleMixed(symbol,from,to+59999)) rows.put(ControlHistoryStore.time(row),row);
        for(Map.Entry<Long,Map<String,Object>> row:store.historyOverrides(symbol,from,to).entrySet()) if(row.getValue()!=null) rows.put(row.getKey(),row.getValue());
        return rows;
    }
    static String prices(Map<String,Object> row) {
        if(row==null) return "missing";
        StringJoiner values=new StringJoiner(":");
        for(String key:Arrays.asList("open_price","high_price","low_price","close_price","volume"))
            values.add(row.get(key)==null?"null":ControlHistoryStore.number(row.get(key)).stripTrailingZeros().toPlainString());
        return values.toString();
    }
    public Map<String,Object> chart(TradingSymbol config,String provider,long from,long to,String zone) {
        range(from,to,zone); String identity=identity(config,provider);
        return store.readSnapshot(()-> {
            Map<Long,Map<String,Object>> originals=source(config.getId(),from,to,identity,provider,config.getMarketSource());
            Map<Long,Map<String,Object>> before=visible(config.getId(),from,to,originals);
            Map<String,Object> result=new LinkedHashMap<>(); result.put("sourceIdentity",identity);
            result.put("before",chartRows(before.values())); result.put("source",chartRows(originals.values()));
            result.put("historyRestoreRevision",store.historyRestoreRevision(config.getId())); return result;
        });
    }
    private List<Map<String,Object>> chartRows(Collection<Map<String,Object>> rows) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:rows) { Map<String,Object> candle=new LinkedHashMap<>(row); candle.put("timestamp",ControlHistoryStore.time(row)); result.add(candle); } return result;
    }
    public Map<String,Object> preview(TradingSymbol config,String provider,long from,long to,String zone) {
        range(from,to,zone); String identity=identity(config,provider); ControlIdentity actor=MarketControlCommands.operator();
        return store.transaction(()-> {
            stable(config.getId(),from,to);
            TreeMap<Long,Map<String,Object>> originals=source(config.getId(),from,to,identity,provider,config.getMarketSource());
            TreeMap<Long,Map<String,Object>> before=visible(config.getId(),from,to,originals);
            List<Long> missing=new ArrayList<>();
            for(long at=from;at<=to;at+=60000) if(!originals.containsKey(at)) missing.add(at);
            Map<String,Object> result=new LinkedHashMap<>(); result.put("missing",missing); result.put("from",from); result.put("to",to);
            result.put("sourceIdentity",identity); result.put("total",(to-from)/60000+1);
            if(!missing.isEmpty()) { result.put("state","MISSING_SOURCE"); return result; }
            List<Long> changed=new ArrayList<>(); for(Long at:originals.keySet()) if(!prices(before.get(at)).equals(prices(originals.get(at)))) changed.add(at);
            result.put("changed",changed.size());
            if(changed.isEmpty()) { result.put("state","NO_CHANGE"); return result; }
            String id=UUID.randomUUID().toString(); long now=store.runtime.clock();
            store.db.update("INSERT INTO market_history_restore_job(tenant_id,id,symbol_id,kind,state,source_identity,from_at,to_at,timezone,total,actor_id,session_id,created_at,expires_at) VALUES(?,?,?,'RESTORE','PREVIEW',?,?,?,?,?,?,?,?,?)",ControlHistoryStore.tenant(),id,config.getId(),identity,from,to,zone,changed.size(),actor.getActorId(),actor.getAccessSessionId(),now,now+300000);
            TreeMap<Long,Map<String,Object>> previous=store.historyOverrides(config.getId(),from,to);
            List<Map<String,Object>> differences=new ArrayList<>();
            for(Long at:changed) {
                Map<String,Object> original=originals.get(at), old=before.get(at);
                Map<String,Object> hash=new TreeMap<>(); hash.put("before",prices(old)); hash.put("source",prices(original));
                store.db.update("INSERT INTO market_history_restore_minute(tenant_id,job_id,symbol_id,minute_at,before_json,source_json,previous_json,checksum) VALUES(?,?,?,?,?,?,?,?)",ControlHistoryStore.tenant(),id,config.getId(),at,store.encode(old),store.encode(original),previous.get(at)==null?null:store.encode(previous.get(at)),OrderRequest.hash(hash));
                if(differences.size()<200) { Map<String,Object> row=new LinkedHashMap<>(); row.put("timestamp",at); row.put("before",old); row.put("source",original); differences.add(row); }
            }
            result.put("differences",differences); result.put("previewToken",id); result.put("state","READY"); result.put("expiresAt",now+300000); return result;
        });
    }
    public Map<String,Object> accept(long symbol,String token,String key,String expectedIdentity) {
        key=OrderRequest.required(key); final String requestKey=key; ControlIdentity actor=MarketControlCommands.operator();
        return store.transaction(()-> {
            store.db.update("INSERT INTO market_engine_tenant(tenant_id) VALUES(?) ON DUPLICATE KEY UPDATE tenant_id=VALUES(tenant_id)",ControlHistoryStore.tenant());
            store.db.queryForObject("SELECT tenant_id FROM market_engine_tenant WHERE tenant_id=? FOR UPDATE",Long.class,ControlHistoryStore.tenant());
            List<Map<String,Object>> duplicates=store.db.queryForList("SELECT * FROM market_history_restore_job WHERE tenant_id=? AND symbol_id=? AND request_key=?",ControlHistoryStore.tenant(),symbol,requestKey);
            if(!duplicates.isEmpty()) { if(!token.equals(duplicates.get(0).get("id"))) throw new BusinessException("请求键已用于另一恢复任务"); return receipt(duplicates.get(0)); }
            Map<String,Object> job=job(symbol,token,true);
            if(value(job,"actor_id")!=actor.getActorId()) throw new BusinessException("请由预览操作者确认");
            if(!"PREVIEW".equals(job.get("state"))) throw new BusinessException("预览已失效，请重新预览");
            if(store.db.queryForObject("SELECT COUNT(*) FROM market_history_restore_job WHERE tenant_id=? AND symbol_id=? AND state IN ('ACCEPTED','RUNNING')",Integer.class,ControlHistoryStore.tenant(),symbol)>0) throw new BusinessException("该品种已有恢复任务，先查询原任务");
            try {
                if(value(job,"expires_at")<store.runtime.clock() || !Objects.equals(expectedIdentity,job.get("source_identity"))) throw new BusinessException("预览已失效，请重新预览");
                stable(symbol,value(job,"from_at"),value(job,"to_at"));
                validateMinutes(job,minutes(token,0,MAX_MINUTES));
            } catch(BusinessException invalidPreview) {
                // Persist the original key's rejection so a lost HTTP response cannot strand the UI draft.
                store.db.update("UPDATE market_history_restore_job SET state='REJECTED',request_key=?,error_message=?,updated_at=? WHERE tenant_id=? AND id=?",requestKey,invalidPreview.getMessage(),store.runtime.clock(),ControlHistoryStore.tenant(),token);
                return query(symbol,token,null);
            }
            store.db.update("UPDATE market_history_restore_job SET state='ACCEPTED',request_key=? WHERE tenant_id=? AND id=?",requestKey,ControlHistoryStore.tenant(),token);
            audit.record(actor.getActorId(),ControlHistoryStore.tenant(),actor.getAccessSessionId(),"history-restore.accept",token,"ACCEPTED","{}",null);
            return query(symbol,token,null);
        });
    }
    private List<Map<String,Object>> minutes(String job,long after,int count) {
        return store.db.queryForList("SELECT * FROM market_history_restore_minute WHERE tenant_id=? AND job_id=? AND minute_at>? AND effective_version=0 ORDER BY minute_at LIMIT ?",ControlHistoryStore.tenant(),job,after,count);
    }
    private void validateMinutes(Map<String,Object> job,List<Map<String,Object>> rows) {
        if(rows.isEmpty()) return;
        long symbol=value(job,"symbol_id"),from=value(rows.get(0),"minute_at"),to=value(rows.get(rows.size()-1),"minute_at");
        Map<String,Object> route=store.db.queryForMap("SELECT source_category,market_source,COALESCE(NULLIF(alltick_symbol,''),symbol) AS code,random_market_enabled FROM trading_symbol WHERE tenant_id=? AND id=?",ControlHistoryStore.tenant(),symbol);
        String identity=(String)job.get("source_identity");
        if(!identity.substring(identity.indexOf(':')+1).equals(route.get("source_category")+":"+route.get("code"))
                || !Objects.equals(MarketInstrumentCatalog.inferredSource((String)route.get("source_category")),route.get("market_source"))
                || Boolean.TRUE.equals(route.get("random_market_enabled")) || route.get("random_market_enabled") instanceof Number && ((Number)route.get("random_market_enabled")).intValue()!=0)
            throw new BusinessException("原始源路由已改变，请重新预览");
        TreeMap<Long,Map<String,Object>> source=new TreeMap<>();
        for(Map<String,Object> saved:store.db.queryForList("SELECT candle_at,body,received_at FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period='1m' AND /*! BINARY */ TRIM(period)<>'1M' AND candle_at>=? AND candle_at<=? AND MOD(candle_at,60000)=0",ControlHistoryStore.tenant(),symbol,from,to)) {
            if("RESTORE".equals(job.get("kind")) && !canonical(saved,identity,identity.substring(0,identity.indexOf(':')),(String)route.get("market_source"))) throw new BusinessException("原始源完整性已改变，请重新预览");
            Map<String,Object> body=store.decode((String)saved.get("body")); source.put(ControlHistoryStore.time(body),body);
        }
        TreeMap<Long,Map<String,Object>> current=visible(symbol,from,to,source);
        for(Map<String,Object> row:rows) {
            long at=value(row,"minute_at");
            if(!prices(current.get(at)).equals(prices(store.decode((String)row.get("before_json"))))) throw new BusinessException("预览后历史已改变，请重新预览；已提交部分仍可查询");
            if("RESTORE".equals(job.get("kind")) && !prices(source.get(at)).equals(prices(store.decode((String)row.get("source_json"))))) throw new BusinessException("预览后原始源已改变，请重新预览");
            if("UNDO".equals(job.get("kind")) && !Objects.equals(job.get("undo_of"),store.historyOverrideOwner(symbol,at))) throw new BusinessException("该分钟已被后续修正覆盖，不能直接撤销");
        }
    }
    /** Called by the existing per-tenant market lane; restart resumes committed progress. */
    void runOne() {
        List<Map<String,Object>> queue=store.db.queryForList("SELECT * FROM market_history_restore_job WHERE tenant_id=? AND state IN ('ACCEPTED','RUNNING') ORDER BY created_at,id LIMIT 8",ControlHistoryStore.tenant());
        for(Map<String,Object> candidate:queue) {
            long symbol=value(candidate,"symbol_id"); String id=(String)candidate.get("id");
            try {
                store.locked(symbol,()-> {
                    Map<String,Object> job=job(symbol,id,true);
                    if(!Arrays.asList("ACCEPTED","RUNNING").contains(job.get("state"))) return null;
                    stable(symbol,value(job,"from_at"),value(job,"to_at"));
                    List<Map<String,Object>> batch=minutes(id,0,BATCH); validateMinutes(job,batch);
                    store.db.update("UPDATE market_engine_runtime SET history_restore_revision=history_restore_revision+1 WHERE tenant_id=? AND symbol_id=?",ControlHistoryStore.tenant(),symbol);
                    long version=store.historyRestoreRevision(symbol);
                    for(Map<String,Object> row:batch) { store.runtime.requireBudget(); store.db.update("UPDATE market_history_restore_minute SET effective_version=? WHERE tenant_id=? AND job_id=? AND minute_at=? AND effective_version=0",version,ControlHistoryStore.tenant(),id,row.get("minute_at")); }
                    long completed=value(job,"completed")+batch.size(); String state=completed==value(job,"total")?"COMPLETED":"RUNNING";
                    store.db.update("UPDATE market_history_restore_job SET state=?,completed=?,updated_at=?,error_message=NULL WHERE tenant_id=? AND id=?",state,completed,store.runtime.clock(),ControlHistoryStore.tenant(),id);
                    store.runtime.historyPublished(symbol);
                    if("COMPLETED".equals(state)) audit.record(value(job,"actor_id"),ControlHistoryStore.tenant(),(String)job.get("session_id"),"history-restore.commit",id,state,"{}",null);
                    return null;
                }); return;
            } catch(RuntimeException failure) {
                String reason=MarketEngineFailure.normalize(failure);
                if(Arrays.asList("ENGINE_BUSY","ENGINE_FENCED","ENGINE_BUDGET","ENGINE_TRANSIENT").contains(reason)) continue;
                String message=failure instanceof BusinessException?failure.getMessage():"执行失败，请查询已提交部分并重试原任务";
                store.transaction(()-> { store.db.update("UPDATE market_history_restore_job SET state='FAILED',error_message=?,updated_at=? WHERE tenant_id=? AND id=? AND state IN ('ACCEPTED','RUNNING')",message,store.runtime.clock(),ControlHistoryStore.tenant(),id); return null; });
            }
        }
    }
    private Map<String,Object> job(long symbol,String id,boolean lock) {
        List<Map<String,Object>> rows=store.db.queryForList("SELECT * FROM market_history_restore_job WHERE tenant_id=? AND symbol_id=? AND id=?"+(lock?" FOR UPDATE":""),ControlHistoryStore.tenant(),symbol,id);
        if(rows.isEmpty()) throw new BusinessException("恢复任务不存在"); return rows.get(0);
    }
    public Map<String,Object> query(long symbol,String id,String key) {
        if(key==null) return receipt(job(symbol,id,false));
        List<Map<String,Object>> rows=store.db.queryForList("SELECT * FROM market_history_restore_job WHERE tenant_id=? AND symbol_id=? AND request_key=?",ControlHistoryStore.tenant(),symbol,OrderRequest.required(key));
        if(rows.isEmpty()) { Map<String,Object> result=new HashMap<>(); result.put("state","NOT_FOUND"); return result; } return receipt(rows.get(0));
    }
    public List<Map<String,Object>> list(long symbol) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> job:store.db.queryForList("SELECT * FROM market_history_restore_job WHERE tenant_id=? AND symbol_id=? AND state<>'PREVIEW' ORDER BY created_at DESC,id DESC LIMIT 100",ControlHistoryStore.tenant(),symbol)) result.add(receipt(job)); return result;
    }
    public Map<String,Object> details(long symbol,String id) {
        Map<String,Object> result=query(symbol,id,null); List<Map<String,Object>> snapshots=new ArrayList<>();
        for(Map<String,Object> saved:store.db.queryForList("SELECT minute_at,before_json,source_json,checksum,effective_version FROM market_history_restore_minute WHERE tenant_id=? AND symbol_id=? AND job_id=? ORDER BY minute_at LIMIT 200",ControlHistoryStore.tenant(),symbol,id)) {
            Map<String,Object> row=new LinkedHashMap<>(); row.put("timestamp",saved.get("minute_at")); row.put("before",store.decode((String)saved.get("before_json"))); row.put("source",saved.get("source_json")==null?null:store.decode((String)saved.get("source_json"))); row.put("checksum",saved.get("checksum")); row.put("version",saved.get("effective_version")); snapshots.add(row);
        }
        result.put("snapshots",snapshots); result.put("snapshotLimit",200); return result;
    }
    private Map<String,Object> receipt(Map<String,Object> job) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:Arrays.asList("id","kind","state","completed","total","timezone")) result.put(key,job.get(key));
        result.put("requestKey",job.get("request_key")); result.put("from",job.get("from_at")); result.put("to",job.get("to_at")); result.put("actorId",job.get("actor_id")); result.put("createdAt",job.get("created_at")); result.put("sourceIdentity",job.get("source_identity")); result.put("error",job.get("error_message")); result.put("undoOf",job.get("undo_of")); return result;
    }
    public Map<String,Object> undoPreview(long symbol,String id) {
        ControlIdentity actor=MarketControlCommands.operator();
        return store.transaction(()-> {
            Map<String,Object> old=job(symbol,id,true);
            if(!"RESTORE".equals(old.get("kind")) || value(old,"completed")==0 || Arrays.asList("ACCEPTED","RUNNING").contains(old.get("state"))) throw new BusinessException("该任务尚不能撤销");
            List<Map<String,Object>> committed=store.db.queryForList("SELECT * FROM market_history_restore_minute WHERE tenant_id=? AND job_id=? AND effective_version>0 ORDER BY minute_at LIMIT 1440",ControlHistoryStore.tenant(),id);
            for(Map<String,Object> row:committed) if(!id.equals(store.historyOverrideOwner(symbol,value(row,"minute_at")))) throw new BusinessException("区间已被后续修正覆盖，不能直接撤销");
            String token=UUID.randomUUID().toString(); long now=store.runtime.clock();
            store.db.update("INSERT INTO market_history_restore_job(tenant_id,id,symbol_id,kind,state,source_identity,from_at,to_at,timezone,total,actor_id,session_id,created_at,expires_at,undo_of) VALUES(?,?,?,'UNDO','PREVIEW',?,?,?,?,?,?,?,?,?,?)",ControlHistoryStore.tenant(),token,symbol,old.get("source_identity"),old.get("from_at"),old.get("to_at"),old.get("timezone"),committed.size(),actor.getActorId(),actor.getAccessSessionId(),now,now+300000,id);
            for(Map<String,Object> row:committed) store.db.update("INSERT INTO market_history_restore_minute(tenant_id,job_id,symbol_id,minute_at,before_json,source_json,previous_json,checksum) VALUES(?,?,?,?,?,?,?,?)",ControlHistoryStore.tenant(),token,symbol,row.get("minute_at"),row.get("source_json"),row.get("previous_json")==null?row.get("before_json"):row.get("previous_json"),row.get("source_json"),row.get("checksum"));
            Map<String,Object> result=receipt(job(symbol,token,false)); result.put("previewToken",token); return result;
        });
    }
    public Map<String,Object> retry(long symbol,String id) {
        MarketControlCommands.operator();
        return store.transaction(()-> { Map<String,Object> job=job(symbol,id,true); if(!"FAILED".equals(job.get("state"))) return receipt(job); validateMinutes(job,minutes(id,0,MAX_MINUTES)); store.db.update("UPDATE market_history_restore_job SET state='ACCEPTED',error_message=NULL WHERE tenant_id=? AND id=?",ControlHistoryStore.tenant(),id); return query(symbol,id,null); });
    }
    /** Explicit maintenance fetch can fill absent originals in controlled history, never overwrite existing facts. */
    public int insertSource(TradingSymbol config,String provider,long from,long to,Map<String,Object> fetched,long received) {
        range(from,to,"UTC"); String identity=identity(config,provider); TreeMap<Long,String> candidates=new TreeMap<>();
        for(Map<String,Object> body:ControlHistoryStore.rows(fetched)) {
            long at=ControlHistoryStore.time(body); if(at<from || at>to) continue;
            Map<String,Object> saved=new HashMap<>(); saved.put("candle_at",at); saved.put("body",store.encode(body)); saved.put("received_at",received);
            if(canonical(saved,identity,provider,config.getMarketSource())) { Map<String,Object> checked=new LinkedHashMap<>(body); checked.put("historySource",identity); candidates.put(at,store.encode(checked)); }
        }
        return store.locked(config.getId(),()-> {
            Map<String,Object> route=store.db.queryForMap("SELECT market_source,source_category,COALESCE(NULLIF(alltick_symbol,''),symbol) AS code,random_market_enabled FROM trading_symbol WHERE tenant_id=? AND id=? FOR UPDATE",ControlHistoryStore.tenant(),config.getId());
            if(!Objects.equals(config.getMarketSource(),route.get("market_source")) || !Objects.equals(config.getSourceCategory(),route.get("source_category")) || !Objects.equals(ForexQuoteMarketService.marketCode(config),route.get("code")) || Boolean.TRUE.equals(route.get("random_market_enabled"))) throw new BusinessException("源路由已改变，请重新预览");
            int inserted=0;
            for(Map.Entry<Long,String> row:candidates.entrySet()) { store.runtime.requireBudget(); inserted+=store.db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) SELECT ?,?,'1m',?,?,? WHERE NOT EXISTS (SELECT 1 FROM market_source_candle WHERE tenant_id=? AND symbol_id=? AND period='1m' AND /*! BINARY */ TRIM(period)<>'1M' AND candle_at=?)",ControlHistoryStore.tenant(),config.getId(),row.getKey(),row.getValue(),received,ControlHistoryStore.tenant(),config.getId(),row.getKey()); }
            if(inserted>0) store.runtime.sourceChanged(config.getId(),candidates.firstKey(),candidates.lastKey()); return inserted;
        });
    }
}
