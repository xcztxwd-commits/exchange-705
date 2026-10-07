package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact archived returns are authoritative only for their reviewed request and logical data scope.
 * They are not minute projections, production publication approval, or a license to reorder old facts. */
final class HistoryOrdering {
    private final ControlHistoryStore store;
    HistoryOrdering(ControlHistoryStore store) { this.store=store; }
    static final class Policy {
        final long fromMinute, sourceSequence;
        final String scope, evidence, responses;
        Policy(Map<String,Object> row) {
            if (((Number)row.get("ordering_version")).intValue()!=2) throw pending("Unknown ordering version");
            fromMinute=((Number)row.get("from_minute")).longValue();
            sourceSequence=((Number)row.get("source_sequence")).longValue();
            scope=(String)row.get("scope_sha256"); evidence=(String)row.get("evidence_sha256"); responses=(String)row.get("responses_json");
        }
    }
    static final class ArchivedResponse {
        final String request, body, bodyHash, artifact, pointer;
        ArchivedResponse(String request,String body,String expectedBodyHash,String artifact,String pointer) {
            hashValue(expectedBodyHash); hashValue(artifact);
            if (!sha(body).equals(expectedBodyHash) || pointer==null || !pointer.startsWith("/") || pointer.length()>255)
                throw new IllegalArgumentException("Unverified archive bytes or JSON pointer");
            this.request=request; this.body=body; bodyHash=expectedBodyHash; this.artifact=artifact; this.pointer=pointer;
        }
    }
    private Policy policy(long symbol,boolean current) {
        List<Map<String,Object>> rows=store.db.queryForList("SELECT ordering_version,from_minute,source_sequence,scope_sha256,evidence_sha256,responses_json FROM market_history_ordering WHERE tenant_id=? AND symbol_id=?"+(current?" FOR UPDATE":""),ControlHistoryStore.tenant(),symbol);
        return rows.isEmpty()?null:new Policy(rows.get(0));
    }
    /** Package-private trusted import boundary; caller supplies externally reviewed archive bytes.
     * No endpoint and no claim that an archive hash is a production approval or digital signature. */
    boolean seal(long symbol,long fromMinute,String scope,String evidence,List<ArchivedResponse> responses) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("History import requires its own authority transaction");
        hashValue(scope);hashValue(evidence);
        if(symbol<=0 || fromMinute<=0 || fromMinute%60000!=0 || responses==null || responses.size()>1000)
            throw new IllegalArgumentException("Invalid bounded history import");
        List<ArchivedResponse> batch=Collections.unmodifiableList(new ArrayList<>(responses)); Set<String> keys=new HashSet<>();
        for(ArchivedResponse response:batch) {
            Map<String,Object> request=store.decode(response.request);
            if(!keys.add(sha(response.request)) || number(request,"tenant")!=ControlHistoryStore.tenant())
                throw new IllegalArgumentException("Duplicate or foreign archived request");
            if(number(request,"symbol")!=symbol || number(request,"cursor")>=fromMinute || number(request,"cursor")<=0
                    || number(request,"limit")<1 || number(request,"limit")>1000
                    || !(request.get("interval") instanceof String) || !(request.get("utcAnchors") instanceof Boolean)
                    || !new TreeSet<>(request.keySet()).equals(new TreeSet<>(Arrays.asList("tenant","symbol","interval","limit","cursor","utcAnchors","code","source")))
                    || !store.encodeHistoryRequest(new TreeMap<>(request)).equals(response.request))
                throw new IllegalArgumentException("Invalid exact archived request identity");
            Map<String,Object> body=store.decode(response.body);
            List<Map<String,Object>> bars=ControlHistoryStore.rows(body);
            Map<?,?> data=(Map<?,?>)body.get("data");
            if(bars==null || bars.isEmpty() || bars.size()>number(request,"limit")
                    || !Objects.equals(request.get("code"),data.get("code")) || !Objects.equals(request.get("source"),data.get("source")))
                throw new IllegalArgumentException("Invalid full response or source identity");
            long previous=Long.MIN_VALUE;
            for(Map<String,Object> bar:bars) {
                long at=ControlHistoryStore.time(bar);
                if(at<=previous || at>number(request,"cursor") || RandomMarketPath.periodEnd((String)request.get("interval"),at)>fromMinute)
                    throw new IllegalArgumentException("Archive crosses future-only cutover");
                previous=at;
            }
        }
        // Resolve a lost commit receipt without claiming/renewing a writer lease.
        if(store.readSnapshot(()->same(symbol,fromMinute,scope,evidence,batch,false))) return false;
        return store.locked(symbol,()->{
            if(same(symbol,fromMinute,scope,evidence,batch,true)) return false;
            long now=store.runtime.clock();
            if(fromMinute<=now) throw new IllegalArgumentException("Cutover must start in a future whole minute");
            long sequence=currentMaximum("SELECT event_sequence FROM market_source_event WHERE tenant_id=? AND symbol_id=? ORDER BY event_sequence DESC LIMIT 1 FOR UPDATE",symbol);
            long lastEvent=currentMaximum("SELECT received_at FROM market_source_event WHERE tenant_id=? AND symbol_id=? ORDER BY received_at DESC LIMIT 1 FOR UPDATE",symbol);
            long lastTick=currentMaximum("SELECT received_at FROM market_source_tick WHERE tenant_id=? AND symbol_id=? ORDER BY received_at DESC LIMIT 1 FOR UPDATE",symbol);
            if(lastEvent>=fromMinute || lastTick>=fromMinute) throw pending("Preexisting facts cross proposed cutover");
            Long generation=store.db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=? FOR UPDATE",Long.class,ControlHistoryStore.tenant(),symbol);
            store.db.update("INSERT INTO market_history_ordering(tenant_id,symbol_id,ordering_version,from_minute,source_sequence,scope_sha256,evidence_sha256,responses_json,sealed_at,writer_generation) VALUES(?,?,2,?,?,?,?,?,?,?)",ControlHistoryStore.tenant(),symbol,fromMinute,sequence,scope,evidence,manifest(batch),now,generation);
            for(ArchivedResponse response:batch) store.db.update("INSERT INTO market_history_response(tenant_id,symbol_id,request_sha256,request_json,response_json,response_sha256,artifact_sha256,artifact_pointer,scope_sha256,sealed_at,writer_generation) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    ControlHistoryStore.tenant(),symbol,sha(response.request),response.request,response.body,response.bodyHash,response.artifact,response.pointer,scope,now,generation);
            return true;
        });
    }
    private long currentMaximum(String sql,long symbol) {
        List<Long> rows=store.db.queryForList(sql,Long.class,ControlHistoryStore.tenant(),symbol);
        return rows.isEmpty()?0:rows.get(0);
    }
    private boolean same(long symbol,long fromMinute,String scope,String evidence,List<ArchivedResponse> batch,boolean current) {
        Policy existing=policy(symbol,current);
        if(existing==null) return false;
        if(existing.fromMinute!=fromMinute || !existing.scope.equals(scope) || !existing.evidence.equals(evidence) || !existing.responses.equals(manifest(batch))) throw pending("Conflicting immutable cutover");
        List<Map<String,Object>> rows=store.db.queryForList("SELECT request_sha256,request_json,response_json,response_sha256,artifact_sha256,artifact_pointer,scope_sha256 FROM market_history_response WHERE tenant_id=? AND symbol_id=?"+(current?" FOR UPDATE":""),ControlHistoryStore.tenant(),symbol);
        if(rows.size()!=batch.size()) throw pending("Conflicting archived response set");
        Map<String,Map<String,Object>> indexed=new HashMap<>();for(Map<String,Object> row:rows) indexed.put((String)row.get("request_sha256"),row);
        for(ArchivedResponse response:batch) {
            Map<String,Object> row=indexed.get(sha(response.request));
            if(row==null || !response.request.equals(row.get("request_json")) || !response.body.equals(row.get("response_json"))
                    || !response.bodyHash.equals(row.get("response_sha256")) || !response.artifact.equals(row.get("artifact_sha256"))
                    || !response.pointer.equals(row.get("artifact_pointer")) || !scope.equals(row.get("scope_sha256"))) throw pending("Conflicting archived response");
        }
        return true;
    }
    String request(long symbol,String interval,int limit,long cursor,boolean utcAnchors,Map<String,Object> external) {
        Map<?,?> data=(Map<?,?>)external.get("data");
        if(data==null || data.containsKey("simulationSession")) throw new IllegalArgumentException("Random/session history has no archived exact-request adapter");
        Map<String,Object> request=new TreeMap<>();
        request.put("tenant",ControlHistoryStore.tenant());request.put("symbol",symbol);request.put("interval",interval);
        request.put("limit",Math.min(1000,Math.max(1,limit)));request.put("cursor",cursor);request.put("utcAnchors",utcAnchors);
        request.put("code",data.get("code"));request.put("source",data.get("source"));
        for(String field:Arrays.asList("code","source")) if(request.get(field)!=null && !(request.get(field) instanceof String)) throw new IllegalArgumentException("Invalid history source identity");
        return store.encodeHistoryRequest(request);
    }
    /** Called only within caller's read-only RR; never stores a response on a read miss. */
    Map<String,Object> readExact(long symbol,String interval,int limit,Long cursor,boolean utcAnchors,Map<String,Object> external,boolean hasCallback) {
        if(cursor==null || hasCallback || external==null || !(external.get("data") instanceof Map)
                || ((Map<?,?>)external.get("data")).containsKey("simulationSession")) return null;
        String request=request(symbol,interval,limit,cursor,utcAnchors,external);
        List<Map<String,Object>> rows=store.db.queryForList("SELECT a.request_json,a.response_json,a.response_sha256,a.artifact_sha256,a.artifact_pointer,a.scope_sha256,p.responses_json,p.scope_sha256 AS policy_scope FROM market_history_response a JOIN market_history_ordering p ON p.tenant_id=a.tenant_id AND p.symbol_id=a.symbol_id WHERE a.tenant_id=? AND a.symbol_id=? AND a.request_sha256=? AND p.from_minute>?",
                ControlHistoryStore.tenant(),symbol,sha(request),cursor);
        if(rows.isEmpty()) return null;
        Map<String,Object> row=rows.get(0);String body=(String)row.get("response_json");
        String digest=sha(sha(request)+":"+row.get("response_sha256")+":"+row.get("artifact_sha256")+":"+row.get("artifact_pointer"));
        if(!request.equals(row.get("request_json")) || !Objects.equals(row.get("scope_sha256"),row.get("policy_scope"))
                || !sha(body).equals(row.get("response_sha256"))
                || !digest.equals(store.decode((String)row.get("responses_json")).get(sha(request)))) throw pending("Corrupt/unsealed archived response");
        return store.decode(body);
    }
    /** Missing receipts do not reject unambiguous legacy history. The original legacy tie guard stays in force. */
    Policy policyForPage(long symbol) { return policy(symbol,false); }
    void futureEvent(Policy policy,long at,long sequence) {
        if(policy!=null && at>=policy.fromMinute && sequence<=policy.sourceSequence) throw pending("Future source event lacks post-cutover sequence");
    }
    /** Publication updates share runtime authority with the producer; old ranges cannot be newly exposed. */
    boolean publicationNeeded(long symbol,String task,long from,long to) {
        Policy policy=policy(symbol,true);
        if(policy==null || from>=policy.fromMinute) return true;
        List<Map<String,Object>> rows=store.db.queryForList("SELECT from_at,to_at FROM market_control_publication WHERE tenant_id=? AND task_id=? FOR UPDATE",ControlHistoryStore.tenant(),task);
        if(rows.size()==1 && ((Number)rows.get(0).get("from_at")).longValue()<=from && ((Number)rows.get(0).get("to_at")).longValue()>=to) return false;
        throw pending("Old publication range requires its own reviewed protection");
    }
    private String manifest(List<ArchivedResponse> batch) {
        Map<String,Object> manifest=new TreeMap<>();
        for(ArchivedResponse response:batch) {String key=sha(response.request);manifest.put(key,sha(key+":"+response.bodyHash+":"+response.artifact+":"+response.pointer));}
        return store.encode(manifest);
    }
    private static long number(Map<String,Object> row,String field) {
        Object value=row.get(field);if(!(value instanceof Number)) throw new IllegalArgumentException("Missing integer "+field);
        try{return new java.math.BigDecimal(value.toString()).longValueExact();}catch(ArithmeticException invalid){throw new IllegalArgumentException("Invalid integer "+field,invalid);}
    }
    static String sha(String value) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] digits="0123456789abcdef".toCharArray(), encoded=new char[digest.length*2];
            for(int i=0;i<digest.length;i++){int b=digest[i]&255;encoded[i*2]=digits[b>>>4];encoded[i*2+1]=digits[b&15];}
            return new String(encoded);
        }
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void hashValue(String value){if(value==null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected SHA-256");}
    private static BusinessException pending(String message){return new BusinessException("HISTORY_LEGACY_ORDER_PENDING: "+message);}
}
