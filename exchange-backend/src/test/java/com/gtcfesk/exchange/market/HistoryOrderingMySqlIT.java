package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.tenant.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit owned MySQL 5.7/0404 fixture only. No runtime DDL, cleanup deletes, Boot or provider mocks. */
class HistoryOrderingMySqlIT {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate db; private ControlHistoryStore store; private TenantContext.Scope tenant;
    private Path evidence; private Map<String,List<String>> before; private final Map<String,Object> results=new LinkedHashMap<>();
    @BeforeEach void open()throws Exception {
        db=new JdbcTemplate(DedicatedMysqlFixture.fromProperty("joint.mysql.fixture"));
        Map<String,Object> physical=db.queryForMap("SELECT VERSION() AS version,@@server_uuid AS server_uuid,DATABASE() AS database_name");
        assertTrue(physical.get("version").toString().startsWith("5.7."));
        assertEquals(2026100404L,db.queryForObject("SELECT MAX(version) FROM tenant_schema_version",Long.class));
        assertEquals(2026100404L,db.queryForObject("SELECT MAX(minimum_application_epoch) FROM tenant_schema_version",Long.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM tenant_schema_version WHERE business_activation_ready<>0",Integer.class));
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name IN ('market_history_ordering','market_history_response')",Integer.class));
        List<Map<String,Object>> triggers=db.queryForList("SELECT trigger_name,event_manipulation,event_object_table,action_timing,action_statement FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'joint_history_%' ORDER BY trigger_name");
        assertTrue(triggers.size()>=15,"Formal immutable/sequence/publication trigger set is absent");
        evidence=Paths.get(System.getProperty("joint.mysql.fixture")).toAbsolutePath().getParent().resolve("history-0404-real");Files.createDirectories(evidence);
        results.put("physical",physical);results.put("triggers",triggers);before=allRows();
        tenant=TenantContext.open(1L);store=new ControlHistoryStore(db,new DataSourceTransactionManager(db.getDataSource()));store.migrate();
    }
    @AfterEach void close()throws Exception {
        try {
            if(before!=null) {
                Map<String,List<String>> after=allRows();
                for(String table:before.keySet()) {
                    List<String> remaining=new ArrayList<>(after.get(table));
                    for(String row:before.get(table)) assertTrue(remaining.remove(row),"Original row changed/lost in "+table);
                }
                results.put("originalAllFieldMultisetsPreserved",true);results.put("after",after);
                Files.write(evidence.resolve("receipt-"+UUID.randomUUID()+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(results));
            }
        } finally {if(tenant!=null)tenant.close();}
    }
    private Map<String,List<String>> allRows()throws Exception {
        Map<String,List<String>> answer=new TreeMap<>();
        for(String table:db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name",String.class)) {
            assertTrue(table.matches("[A-Za-z0-9_]+"));
            assertTrue(db.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Long.class)<50000,"Use a small verified clone, not capacity DB");
            List<String> rows=new ArrayList<>();for(Map<String,Object> row:db.queryForList("SELECT * FROM `"+table+"`")) rows.add(JSON.writeValueAsString(new TreeMap<>(row)));Collections.sort(rows);answer.put(table,rows);
        }return answer;
    }
    private long symbol() {
        String name="H"+UUID.randomUUID().toString().replace("-","").substring(0,20);GeneratedKeyHolder key=new GeneratedKeyHolder();
        db.update(c->{PreparedStatement p=c.prepareStatement("INSERT INTO trading_symbol(tenant_id,symbol,base_currency,quote_currency,name,category,is_hot,is_enabled,sort_order,price_precision,volume_precision,market_source,source_category,control_enabled,row_version,created_at,updated_at) VALUES(1,?,'TEST','USD',?,'Crypto',0,1,0,16,8,'binance','Crypto',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",Statement.RETURN_GENERATED_KEYS);p.setString(1,name);p.setString(2,name);return p;},key);return key.getKey().longValue();
    }
    private Map<String,Object> external(){return Map.of("ret",503,"data",Map.of("code","TEST","source","fixture","kline_list",List.of()));}
    private long cutover(){return (store.runtime.clock()/60000+5)*60000;}
    private HistoryOrdering.ArchivedResponse archive(long symbol,String which)throws Exception {
        String hash=which.equals("83")?"b9e5a6c0e64fd7a679e4e2578d332c0eccdf46426ccaeff63de059bde38c7e4b":"68641c5893367ba787936fb9eafcc0e31f8419890ce47c40cda9000ea0002f48";
        String artifact=which.equals("83")?"a2149eee678f9c41ad1f8255c23f8a98e7b89de7df6108c6ee56d82db60af160":"5053abb3905a50a63e99931daa6ed4d7504930923d94830876a0bdea43814b2b";
        try(java.io.InputStream in=getClass().getResourceAsStream("/history-original-returns/old"+which+".json")) {assertNotNull(in);return new HistoryOrdering.ArchivedResponse(store.historyOrdering.request(symbol,"1m",5,1700000460000L,true,external()),new String(in.readAllBytes(),StandardCharsets.UTF_8),hash,artifact,"/0/expected");}
    }
    private void reject(String label,Runnable operation) {
        RuntimeException rejected=assertThrows(RuntimeException.class,operation::run,label);Throwable root=rejected;while(root.getCause()!=null)root=root.getCause();
        assertTrue(root instanceof SQLException || rejected instanceof BusinessException,label+": expected actual SQL/business rejection, got "+root);
        if(root instanceof SQLException) assertEquals("45000",((SQLException)root).getSQLState(),label+": a deadlock/permission/syntax error is not a guard pass");
        results.put(label,Map.of("type",root.getClass().getName(),"message",String.valueOf(root.getMessage())));
    }
    private void fenced(long symbol,Runnable operation){store.locked(symbol,()->{operation.run();return null;});}
    private void insertReceipt(long symbol,HistoryOrdering.ArchivedResponse receipt,String scope,long sealed,long generation) {
        db.update("INSERT INTO market_history_response(tenant_id,symbol_id,request_sha256,request_json,response_json,response_sha256,artifact_sha256,artifact_pointer,scope_sha256,sealed_at,writer_generation) VALUES(1,?,?,?,?,?,?,?,?,?,?)",symbol,HistoryOrdering.sha(receipt.request),receipt.request,receipt.body,receipt.bodyHash,receipt.artifact,receipt.pointer,scope,sealed,generation);
    }
    @Test void formalSchemaSealsPreservesAndFencesRealMysql()throws Exception {
        List<Long> owned=new ArrayList<>();
        GeneratedKeyHolder foreign=new GeneratedKeyHolder();
        db.update(c->{PreparedStatement p=c.prepareStatement("INSERT INTO tenant(code,name,status,created_at) VALUES(?,'Owned history isolation fixture','MAINTENANCE',UTC_TIMESTAMP(6))",Statement.RETURN_GENERATED_KEYS);p.setString(1,"history-"+UUID.randomUUID());return p;},foreign);
        long otherTenant=foreign.getKey().longValue();results.put("ownedForeignTenant",otherTenant);
        for(String which:List.of("83","84")) {
            long symbol=symbol();owned.add(symbol);long cut=cutover();String scope=HistoryOrdering.sha("independent-mysql-fixture-"+symbol),evidenceSha=HistoryOrdering.sha("reviewed-"+which);
            HistoryOrdering.ArchivedResponse receipt=archive(symbol,which);
            reject("noFence-"+symbol,()->db.update("INSERT INTO market_history_ordering(tenant_id,symbol_id,ordering_version,from_minute,source_sequence,scope_sha256,evidence_sha256,responses_json,sealed_at,writer_generation) VALUES(1,?,2,?,0,?,?,'{}',?,1)",symbol,cut,scope,evidenceSha,store.runtime.clock()));
            assertTrue(store.historyOrdering.seal(symbol,cut,scope,evidenceSha,List.of(receipt)));
            Map<String,Object> runtime=db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol);
            assertFalse(store.historyOrdering.seal(symbol,cut,scope,evidenceSha,List.of(receipt)));
            assertEquals(runtime,db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol));
            assertEquals(store.decode(receipt.body),new ControlledKlineMerger(store).merge(symbol,"1m",5,1700000460000L,external(),null,true));
            ExecutorService foreignWorker=Executors.newSingleThreadExecutor();
            try{foreignWorker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(otherTenant)){assertNull(store.readSnapshot(()->store.historyOrdering.readExact(symbol,"1m",5,1700000460000L,true,external(),false)));}}).get(10,TimeUnit.SECONDS);}finally{foreignWorker.shutdownNow();}
            reject("policyTenantMutation-"+symbol,()->fenced(symbol,()->db.update("UPDATE market_history_ordering SET tenant_id=? WHERE tenant_id=1 AND symbol_id=?",otherTenant,symbol)));
            reject("policyImmutable-"+symbol,()->fenced(symbol,()->db.update("UPDATE market_history_ordering SET from_minute=from_minute+60000 WHERE tenant_id=1 AND symbol_id=?",symbol)));
            reject("receiptImmutable-"+symbol,()->fenced(symbol,()->db.update("UPDATE market_history_response SET response_json='{}' WHERE tenant_id=1 AND symbol_id=?",symbol)));
            reject("receiptDelete-"+symbol,()->fenced(symbol,()->db.update("DELETE FROM market_history_response WHERE tenant_id=1 AND symbol_id=?",symbol)));
            reject("unsequencedFutureTick-"+symbol,()->fenced(symbol,()->db.update("INSERT INTO market_source_tick(tenant_id,symbol_id,source_time,received_at,price) VALUES(1,?,?,?,1)",symbol,cut+1000,cut+1000)));
            fenced(symbol,()->{assertTrue(store.quote(symbol,Map.of("eventId","a","timestamp",cut+5000,"price",new BigDecimal("83.1234567890123456")),cut+4000));assertTrue(store.quote(symbol,Map.of("eventId","b","timestamp",cut+5000,"price",new BigDecimal("84.1234567890123456")),cut+4000));});
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM market_source_tick WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol));
            reject("eventPriceImmutable-"+symbol,()->fenced(symbol,()->db.update("UPDATE market_source_event SET price=price+1 WHERE tenant_id=1 AND symbol_id=?",symbol)));
            reject("tickPriceImmutable-"+symbol,()->fenced(symbol,()->db.update("UPDATE market_source_tick SET price=price+1 WHERE tenant_id=1 AND symbol_id=?",symbol)));
            reject("sourceDelete-"+symbol,()->fenced(symbol,()->db.update("DELETE FROM market_source_event WHERE tenant_id=1 AND symbol_id=?",symbol)));
            assertEquals(store.decode(receipt.body),new ControlledKlineMerger(new ControlHistoryStore(db,new DataSourceTransactionManager(db.getDataSource()))).merge(symbol,"1m",5,1700000460000L,external(),null,true));
        }
        long empty=symbol();owned.add(empty);long cut=cutover();String scope=HistoryOrdering.sha("empty-"+empty);
        assertTrue(store.historyOrdering.seal(empty,cut,scope,HistoryOrdering.sha("empty"),List.of()));HistoryOrdering.ArchivedResponse extra=archive(empty,"83");
        Map<String,Object> p=db.queryForMap("SELECT * FROM market_history_ordering WHERE tenant_id=1 AND symbol_id=?",empty);
        reject("appendOutsideSealedManifest",()->fenced(empty,()->insertReceipt(empty,extra,scope,((Number)p.get("sealed_at")).longValue(),((Number)p.get("writer_generation")).longValue())));
        reject("foreignReceiptScope",()->fenced(empty,()->insertReceipt(empty,extra,HistoryOrdering.sha("foreign"),((Number)p.get("sealed_at")).longValue(),((Number)p.get("writer_generation")).longValue())));
        results.put("ownedSymbols",owned);results.put("scope","Formal MySQL immutable receipts/future event path; not production publication approval or real process restart");
    }
    @Test void oldPublicationCannotExtendThroughFacadeOrValidFenceSql()throws Exception {
        long symbol=symbol(),start=1700000400000L;String task=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        fenced(symbol,()->{
            for(String id:List.of(task,other)) db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,ended_at,sampled_until) VALUES(1,?,?,'TEST',2,'TARGET','COMPLETED',100,110,10,1,false,16,'LIVE',?,?,?,?,?)",id,symbol,start,start,start+10000,start+10000,start+10000);
            db.update("INSERT INTO market_control_publication(tenant_id,task_id,published_at,from_at,to_at) VALUES(1,?,?,?,?)",task,start+20000,start,start+10000);
        });
        assertTrue(store.historyOrdering.seal(symbol,cutover(),HistoryOrdering.sha("publication-"+symbol),HistoryOrdering.sha("publication-evidence"),List.of()));
        Map<String,Object> original=db.queryForMap("SELECT * FROM market_control_publication WHERE tenant_id=1 AND task_id=?",task);
        PersistentPriceControl controls=new PersistentPriceControl(store);
        assertEquals(task,controls.replaceHistory(symbol,task).id);
        ControlRecoveryFlow flow=new ControlRecoveryFlow(store,new ControlHoldService(store));
        PersistentPriceControl.Task loaded=store.db.queryForObject("SELECT symbol_id,started_at FROM market_control_task WHERE tenant_id=1 AND id=?",(rs,n)->{PersistentPriceControl.Task value=new PersistentPriceControl.Task();value.id=task;value.symbolId=rs.getLong(1);value.startedAt=rs.getLong(2);return value;},task);
        assertFalse(store.locked(symbol,()->flow.publish(loaded,start+10000)));
        reject("facadeOldPublicationExtension",()->fenced(symbol,()->flow.publish(loaded,start+11000)));
        reject("rawOldPublicationExtension",()->fenced(symbol,()->db.update("UPDATE market_control_publication SET to_at=to_at+1 WHERE tenant_id=1 AND task_id=?",task)));
        reject("rawOldPublicationTimestamp",()->fenced(symbol,()->db.update("UPDATE market_control_publication SET published_at=published_at+1 WHERE tenant_id=1 AND task_id=?",task)));
        reject("rawOldPublicationDelete",()->fenced(symbol,()->db.update("DELETE FROM market_control_publication WHERE tenant_id=1 AND task_id=?",task)));
        reject("rawNewOldPublication",()->fenced(symbol,()->db.update("INSERT INTO market_control_publication(tenant_id,task_id,published_at,from_at,to_at) VALUES(1,?,?,?,?)",other,start+20000,start,start+10000)));
        assertEquals(original,db.queryForMap("SELECT * FROM market_control_publication WHERE tenant_id=1 AND task_id=?",task));
        results.put("publication",Map.of("symbol",symbol,"task",task,"before",original));
    }
    @Test void actualReceiptStatementFailureRollsBackPolicyAndLeaseThenRetryCommits()throws Exception {
        long symbol=symbol(),cut=cutover();String scope=HistoryOrdering.sha("rollback-"+symbol),evidenceSha=HistoryOrdering.sha("rollback-evidence");HistoryOrdering.ArchivedResponse receipt=archive(symbol,"83");
        String trigger="test_history_receipt_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        boolean created=false;
        try {
            db.execute("CREATE TRIGGER "+trigger+" AFTER INSERT ON market_history_response FOR EACH ROW BEGIN IF NEW.tenant_id=1 AND NEW.symbol_id="+symbol+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='OWNED_HISTORY_RECEIPT_FAILURE'; END IF; END");
            created=true;
            reject("actualReceiptFailure",()->store.historyOrdering.seal(symbol,cut,scope,evidenceSha,List.of(receipt)));
            for(String table:List.of("market_history_ordering","market_history_response","market_engine_runtime")) assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol));
        } finally {if(created)db.execute("DROP TRIGGER "+trigger);}
        assertTrue(store.historyOrdering.seal(symbol,cut,scope,evidenceSha,List.of(receipt)));
        assertFalse(store.historyOrdering.seal(symbol,cut,scope,evidenceSha,List.of(receipt)));
        results.put("statementRollback",Map.of("symbol",symbol,"retryCount",1));
    }
    @Test void twoConcurrentRealConnectionsSealOneBundleAndRetryIsReadOnly()throws Exception {
        long symbol=symbol(),cut=cutover();HistoryOrdering.ArchivedResponse receipt=archive(symbol,"83");String scope=HistoryOrdering.sha("concurrent-"+symbol),evidenceSha=HistoryOrdering.sha("concurrent-evidence");
        CyclicBarrier afterEarlierSnapshot=new CyclicBarrier(2);Set<Long> physicalConnections=ConcurrentHashMap.newKeySet();
        org.springframework.jdbc.datasource.DelegatingDataSource observed=new org.springframework.jdbc.datasource.DelegatingDataSource(db.getDataSource()) {
            @Override public Connection getConnection()throws SQLException {
                Connection physical=super.getConnection();
                return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String
                            && ((String)args[0]).startsWith("INSERT INTO market_engine_runtime(")) {
                        assertFalse(physical.getAutoCommit());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,physical.getTransactionIsolation());
                        try(Statement query=physical.createStatement();ResultSet id=query.executeQuery("SELECT CONNECTION_ID()")){assertTrue(id.next());physicalConnections.add(id.getLong(1));}
                        // MarketRuntime's prior ordinary symbol SELECT has already established this RR snapshot.
                        // Both writers are paused before their first locking runtime INSERT, so the loser must current-read the winner's seal.
                        afterEarlierSnapshot.await(10,TimeUnit.SECONDS);
                    }
                    try{return method.invoke(physical,args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
                });
            }
        };
        ControlHistoryStore racing=new ControlHistoryStore(new JdbcTemplate(observed),new DataSourceTransactionManager(observed));
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> seal=()->{try(TenantContext.Scope ignored=TenantContext.open(1L)){return racing.historyOrdering.seal(symbol,cut,scope,evidenceSha,List.of(receipt));}};
            Future<Boolean> a=workers.submit(seal),b=workers.submit(seal);boolean first=a.get(20,TimeUnit.SECONDS),second=b.get(20,TimeUnit.SECONDS);
            assertNotEquals(first,second);assertEquals(2,physicalConnections.size());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM market_history_ordering WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM market_history_response WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol));
            Map<String,Object> beforeRetry=db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol);
            assertFalse(store.historyOrdering.seal(symbol,cut,scope,evidenceSha,List.of(receipt)));
            assertEquals(beforeRetry,db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol));
            results.put("concurrentSeal",Map.of("symbol",symbol,"results",List.of(first,second),"unknownCommitRetryNoRuntimeChange",true,"earlierRrSnapshotsOnTwoConnections",physicalConnections));
        } finally {workers.shutdownNow();}
    }
}
