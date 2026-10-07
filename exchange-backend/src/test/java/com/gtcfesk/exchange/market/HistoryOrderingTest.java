package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** H2 contract tests only. Archive returns remain two independent fixture scopes, not production approvals. */
class HistoryOrderingTest extends TenantMarketTestContext {
    private static final long OLD=1700000400000L, CURSOR=1700000460000L;
    private static final String HASH83="b9e5a6c0e64fd7a679e4e2578d332c0eccdf46426ccaeff63de059bde38c7e4b";
    private static final String HASH84="68641c5893367ba787936fb9eafcc0e31f8419890ce47c40cda9000ea0002f48";
    private static final String ART83="a2149eee678f9c41ad1f8255c23f8a98e7b89de7df6108c6ee56d82db60af160";
    private static final String ART84="5053abb3905a50a63e99931daa6ed4d7504930923d94830876a0bdea43814b2b";
    private ControlHistoryStore fixture(boolean ambiguous) {
        // All S4 MySQL opt-ins must be absent; this candidate does not authorize its old destructive scratch cleanup.
        assertNull(System.getenv("S4_HISTORY_JDBC"),"Run only isolated H2 contract tests");
        S4KlineDifferentialTest fixture=new S4KlineDifferentialTest();fixture.setup();
        if(ambiguous) assertTrue(assertThrows(BusinessException.class,
                fixture::frozenPrefixMultiTaskLateEventsDedupAndPublicationAreFieldEqual).getMessage().contains("HISTORY_LEGACY_ORDER_PENDING"));
        return (ControlHistoryStore)ReflectionTestUtils.getField(fixture,"store");
    }
    private Map<String,Object> external() {
        Map<String,Object> data=new LinkedHashMap<>();data.put("code","TEST");data.put("source","fixture");data.put("kline_list",List.of());
        return new LinkedHashMap<>(Map.of("ret",503,"data",data));
    }
    private String body(String file)throws Exception {
        try(java.io.InputStream input=getClass().getResourceAsStream("/history-original-returns/"+file)) {
            assertNotNull(input);return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private long future(){return (System.currentTimeMillis()/60000+3)*60000;}
    private HistoryOrdering.ArchivedResponse archive(ControlHistoryStore store,String file,String hash,String artifact,String pointer)throws Exception {
        return new HistoryOrdering.ArchivedResponse(store.historyOrdering.request(1,"1m",5,CURSOR,true,external()),body(file),hash,artifact,pointer);
    }
    private Map<String,Object> read(ControlHistoryStore store,int limit,String interval){return new ControlledKlineMerger(store).merge(1,interval,limit,CURSOR,external(),ignored->{},true);}
    private List<Map<String,Object>> rows(ControlHistoryStore store,String table){return store.db.queryForList("SELECT * FROM "+table+" ORDER BY tenant_id,symbol_id");}
    @Test void twoOriginalReturnsStayExactInSeparateScopesAndNeverBecomeMinutes()throws Exception {
        for(String which:List.of("83","84")) {
            ControlHistoryStore store=fixture(true);long cut=future();
            boolean first=which.equals("83");
            HistoryOrdering.ArchivedResponse archive=archive(store,"old"+which+".json",first?HASH83:HASH84,first?ART83:ART84,"/0/expected");
            String scope=HistoryOrdering.sha("independent-fixed-fixture-"+which),evidence=HistoryOrdering.sha("reviewed-archive-"+which);
            List<Map<String,Object>> ticks=rows(store,"market_source_tick");
            assertTrue(store.historyOrdering.seal(1,cut,scope,evidence,List.of(archive)));
            List<Map<String,Object>> runtime=rows(store,"market_engine_runtime");
            assertFalse(store.historyOrdering.seal(1,cut,scope,evidence,List.of(archive)));
            assertEquals(runtime,rows(store,"market_engine_runtime"),"Lost-commit retry must not renew a lease");
            assertEquals(store.decode(archive.body),read(store,5,"1m"));
            ControlHistoryStore reopened=new ControlHistoryStore(store.db,new DataSourceTransactionManager(store.db.getDataSource()));
            assertEquals(store.decode(archive.body),read(reopened,5,"1m"));
            assertEquals(runtime,rows(store,"market_engine_runtime"),"Read/reopened object cannot write or renew");
            assertEquals(ticks,rows(store,"market_source_tick"));
            assertThrows(BusinessException.class,()->read(store,4,"1m"));
            assertThrows(BusinessException.class,()->read(store,5,"5m"));
            assertThrows(BusinessException.class,()->new ControlledKlineMerger(store).merge(1,"1m",5,CURSOR,external(),null,true,(from,to)->List.of()));
            HistoryOrdering.ArchivedResponse other=archive(store,"old"+(first?"84":"83")+".json",first?HASH84:HASH83,first?ART84:ART83,"/0/expected");
            assertThrows(BusinessException.class,()->store.historyOrdering.seal(1,cut,scope,evidence,List.of(other)));
            assertEquals(store.decode(archive.body),read(store,5,"1m"));
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try { worker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(2L)) {
                assertNull(reopened.readSnapshot(()->reopened.historyOrdering.readExact(1,"1m",5,CURSOR,true,external(),false)));
            }}).get(10,TimeUnit.SECONDS); } finally {worker.shutdownNow();}
        }
    }
    @Test void legacyUnicodeArchivedRequestAndOriginalBodyHashesSurviveAsciiStorageUpgrade()throws Exception {
        ControlHistoryStore store=fixture(false);long cut=future();
        Map<String,Object> external=external();Map<String,Object> data=(Map<String,Object>)external.get("data");data.put("code","测试🚀");data.put("source","历史来源");
        Map<String,Object> oldBody=store.decode(body("old83.json"));Map<String,Object> oldData=(Map<String,Object>)oldBody.get("data");oldData.put("code",data.get("code"));oldData.put("source",data.get("source"));
        com.fasterxml.jackson.databind.ObjectMapper legacy=new com.fasterxml.jackson.databind.ObjectMapper();
        String body=legacy.writeValueAsString(oldBody);
        Map<String,Object> identity=new TreeMap<>();identity.put("tenant",1);identity.put("symbol",1);identity.put("interval","1m");identity.put("limit",5);identity.put("cursor",CURSOR);identity.put("utcAnchors",true);identity.put("code",data.get("code"));identity.put("source",data.get("source"));
        String originalRequest=legacy.writeValueAsString(identity),originalHash=HistoryOrdering.sha(originalRequest),originalBodyHash=HistoryOrdering.sha(body);
        assertEquals(originalRequest,store.historyOrdering.request(1,"1m",5,CURSOR,true,external));assertNotEquals(originalRequest,store.encode(identity));
        HistoryOrdering.ArchivedResponse archive=new HistoryOrdering.ArchivedResponse(originalRequest,body,originalBodyHash,HistoryOrdering.sha("legacy-unicode-fixture-artifact"),"/legacy");
        String scope=HistoryOrdering.sha("legacy-unicode-fixture-scope"),evidence=HistoryOrdering.sha("legacy-unicode-fixture-evidence");
        assertTrue(store.historyOrdering.seal(1,cut,scope,evidence,List.of(archive)));
        Map<String,Object> oldReceipt=store.db.queryForMap("SELECT * FROM market_history_response WHERE tenant_id=1 AND symbol_id=1");
        assertEquals(originalHash,oldReceipt.get("request_sha256"));assertEquals(originalBodyHash,oldReceipt.get("response_sha256"));assertEquals(body,oldReceipt.get("response_json"));
        assertFalse(store.historyOrdering.seal(1,cut,scope,evidence,List.of(archive)));
        ControlHistoryStore reopened=new ControlHistoryStore(store.db,new DataSourceTransactionManager(store.db.getDataSource()));
        assertEquals(oldBody,reopened.readSnapshot(()->reopened.historyOrdering.readExact(1,"1m",5,CURSOR,true,external,false)));
        assertEquals(oldReceipt,store.db.queryForMap("SELECT * FROM market_history_response WHERE tenant_id=1 AND symbol_id=1"),"Never rewrite old bytes/hashes to mask identity regression");
    }
    @Test void receiptFailureRollsBackWholeSealThenRetryCommitsOnce()throws Exception {
        ControlHistoryStore store=fixture(false);long cut=future();String scope=HistoryOrdering.sha("failure-fixture"), evidence=HistoryOrdering.sha("failure-evidence");
        store.db.execute("ALTER TABLE market_history_response ADD CONSTRAINT injected_receipt_failure CHECK (artifact_pointer<>'/reject')");
        HistoryOrdering.ArchivedResponse rejected=archive(store,"old83.json",HASH83,ART83,"/reject");
        assertThrows(RuntimeException.class,()->store.historyOrdering.seal(1,cut,scope,evidence,List.of(rejected)));
        for(String table:List.of("market_history_ordering","market_history_response","market_engine_runtime")) assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        HistoryOrdering.ArchivedResponse accepted=archive(store,"old83.json",HASH83,ART83,"/0/expected");
        assertTrue(store.historyOrdering.seal(1,cut,scope,evidence,List.of(accepted)));
        assertFalse(store.historyOrdering.seal(1,cut,scope,evidence,List.of(accepted)));
        assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_history_response",Integer.class));
        String sealedManifest=store.db.queryForObject("SELECT responses_json FROM market_history_ordering WHERE tenant_id=1 AND symbol_id=1",String.class);
        assertEquals(1,store.decode(sealedManifest).size());
        assertThrows(IllegalArgumentException.class,()->new HistoryOrdering.ArchivedResponse(accepted.request,accepted.body+" ",HASH83,ART83,"/0/expected"));
        Map<String,Object> malformed=store.decode(accepted.request);malformed.put("tenant",new BigDecimal("1.1"));
        HistoryOrdering.ArchivedResponse fractional=new HistoryOrdering.ArchivedResponse(store.encode(new TreeMap<>(malformed)),accepted.body,HASH83,ART83,"/0/expected");
        assertThrows(IllegalArgumentException.class,()->store.historyOrdering.seal(1,cut,scope,evidence,List.of(fractional)));
    }
    @Test void emptySealedManifestRejectsH2InjectedUnlistedReceiptAtRead()throws Exception {
        // H2 has no formal MySQL triggers: deliberately bypass the Java importer to test the read-side leaf check.
        ControlHistoryStore store=fixture(false);long cut=future();String scope=HistoryOrdering.sha("empty-fixture");
        assertTrue(store.historyOrdering.seal(1,cut,scope,HistoryOrdering.sha("empty-evidence"),List.of()));
        HistoryOrdering.ArchivedResponse extra=archive(store,"old83.json",HASH83,ART83,"/0/expected");
        Map<String,Object> policy=store.db.queryForMap("SELECT * FROM market_history_ordering WHERE tenant_id=1 AND symbol_id=1");
        assertEquals("{}",policy.get("responses_json"));
        store.db.update("INSERT INTO market_history_response(tenant_id,symbol_id,request_sha256,request_json,response_json,response_sha256,artifact_sha256,artifact_pointer,scope_sha256,sealed_at,writer_generation) VALUES(1,1,?,?,?,?,?,?,?,?,?)",
                HistoryOrdering.sha(extra.request),extra.request,extra.body,extra.bodyHash,extra.artifact,extra.pointer,scope,policy.get("sealed_at"),policy.get("writer_generation"));
        assertThrows(BusinessException.class,()->read(store,5,"1m"));
    }
    @Test void futureSequenceIgnoresSourceTimeOrderWhileUnambiguousOldHistorySurvives() {
        ControlHistoryStore store=fixture(false);long cut=future();
        Map<String,Object> old=new LinkedHashMap<>(Map.of("timestamp",OLD,"open_price",BigDecimal.TEN,"close_price",BigDecimal.TEN,"high_price",BigDecimal.TEN,"low_price",BigDecimal.TEN,"volume",0));
        store.db.update("INSERT INTO market_mixed_minute(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?)",OLD,store.encode(old),OLD+1000);
        List<Map<String,Object>> beforeOld=store.readSnapshot(()->store.visibleMixed(1,OLD,OLD));
        assertTrue(store.historyOrdering.seal(1,cut,HistoryOrdering.sha("future-fixture"),HistoryOrdering.sha("approved-future-only"),List.of()));
        assertEquals(beforeOld,store.readSnapshot(()->store.visibleMixed(1,OLD,OLD)));
        store.locked(1,()->{
            store.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,ended_at,sampled_until) VALUES(1,'future',1,'TEST',4,'TARGET','COMPLETED',100,110,10,1,false,16,'LIVE',?,?,?,?,?)",cut+10000,cut+10000,cut+20000,cut+20000,cut+20000);
            store.db.update("INSERT INTO market_control_flow(tenant_id,task_id,options_json,state,last_price,last_at,finished_at) VALUES(1,'future','{}','SOURCE',110,?,?)",cut+20000,cut+20000);
            store.db.update("INSERT INTO market_control_publication(tenant_id,task_id,published_at,from_at,to_at) VALUES(1,'future',?,?,?)",cut+21000,cut+10000,cut+20000);
            store.generatedPoints("future",1,List.of(new ControlHistoryStore.PricePoint(cut+10000,new BigDecimal("110"))));
            assertTrue(store.quote(1,Map.of("eventId","first","timestamp",cut+55000,"price",new BigDecimal("83.1234567890123456")),cut+50000));
            assertTrue(store.quote(1,Map.of("eventId","second","timestamp",cut+54000,"price",new BigDecimal("84.1234567890123456")),cut+50000));
            assertFalse(store.quote(1,Map.of("eventId","first","timestamp",cut+55000,"price",new BigDecimal("83.1234567890123456")),cut+50000));
            return null;
        });
        List<Map<String,Object>> future=store.readSnapshot(()->store.visibleMixed(1,cut,cut));
        assertEquals(1,future.size());assertEquals(new BigDecimal("84.1234567890123456"),future.get(0).get("close_price"));
        HistoryOrdering.Policy policy=store.historyOrdering.policyForPage(1);
        assertThrows(BusinessException.class,()->store.historyOrdering.futureEvent(policy,cut+50000,policy.sourceSequence));
        assertDoesNotThrow(()->store.historyOrdering.futureEvent(policy,cut-1,0));
        assertEquals(beforeOld,store.readSnapshot(()->store.visibleMixed(1,OLD,OLD)));
    }
}
