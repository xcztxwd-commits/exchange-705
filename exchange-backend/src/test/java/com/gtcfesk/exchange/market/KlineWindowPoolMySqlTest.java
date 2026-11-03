package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.zaxxer.hikari.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Owned synthetic MySQL 5.7 only, with physical connection/transaction observations. */
@EnabledIfEnvironmentVariable(named="S4_HISTORY_JDBC",matches="jdbc:mysql://127\\.0\\.0\\.1:33349/mt705_s4_[a-f0-9]{32}\\?.+")
class KlineWindowPoolMySqlTest extends TenantMarketTestContext {
    static final long AT=1700000400000L, WIDTH=300000L;
    HikariDataSource pool; ReplayJdbc db; ControlHistoryStore store; ControlledKlineMerger merger;
    long symbol; final List<Map<String,Object>> evidence=new ArrayList<>();

    static final class ReplayJdbc extends JdbcTemplate {
        final AtomicInteger replays=new AtomicInteger();
        final List<Integer> replayPages=Collections.synchronizedList(new ArrayList<>());
        ReplayJdbc(DataSource source){super(source);}
        @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
            List<Map<String,Object>> rows=super.queryForList(sql,args);
            if(sql.contains("UNION ALL")){replays.addAndGet(rows.size());replayPages.add(rows.size());}
            return rows;
        }
    }
    @BeforeEach void setup() {
        S4KlineDifferentialTest.ObservedDataSource source=new S4KlineDifferentialTest.ObservedDataSource();
        pool=new HikariDataSource();pool.setDataSource(source);pool.setMaximumPoolSize(4);pool.setMinimumIdle(0);
        pool.setConnectionTimeout(2000);pool.setPoolName("independent-kline-"+UUID.randomUUID());
        db=new ReplayJdbc(pool);MarketSqlFixture.schema(db);
        for(String table:List.of("market_source_event","market_source_tick")) {
            String index="qa_"+table+"_received";
            if(db.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name=?",Integer.class,table,index)==0)
                db.execute("CREATE INDEX "+index+" ON "+table+"(tenant_id,symbol_id,received_at,"+(table.endsWith("event")?"event_sequence,":"")+"source_time)");
        }
        symbol=500_000_000L+new java.security.SecureRandom().nextInt(100_000_000);
        db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,1)",symbol);
        db.update("INSERT INTO market_engine_runtime(tenant_id,symbol_id,history_restore_revision) VALUES(1,?,7)",symbol);
        store=new ControlHistoryStore(db,new DataSourceTransactionManager(pool));merger=new ControlledKlineMerger(store);
        List<Object[]> rows=new ArrayList<>();
        for(int i=0;i<1000;i++){long at=AT+i*WIDTH;rows.add(new Object[]{1L,symbol,"5m",at,store.encode(bar(at,"90")),at+WIDTH});}
        db.batchUpdate("INSERT INTO market_source_candle VALUES(?,?,?,?,?,?)",rows);
        controlled(AT+999*WIDTH+60000,true);
    }
    @AfterEach void close()throws Exception {
        try {
            if(!evidence.isEmpty()) {
                Path dir=Paths.get(System.getProperty("s4.evidence.dir","target/s4-differential"));Files.createDirectories(dir);
                Files.write(dir.resolve("independent-pool-"+UUID.randomUUID()+".json"),new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence));
            }
        } finally {if(pool!=null)pool.close();}
    }
    Map<String,Object> bar(long at,String price){return Map.of("timestamp",at,"open_price",new BigDecimal(price),"high_price",new BigDecimal(price),"low_price",new BigDecimal(price),"close_price",new BigDecimal(price),"volume",7);}
    Map<String,Object> external(){return Map.of("ret",200,"data",Map.of("kline_list",List.of(),"code","TEST"));}
    Map<String,Object> read(int n){return store.readConsumerSnapshot(()->merger.merge(symbol,"5m",n,null,external(),null));}
    void controlled(long at,boolean publish) {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,sampled_until,ended_at) VALUES(1,?,?,'TEST',1,'TARGET','DONE',10,10,10,1,false,16,'SOURCE',?,?,?,?,?)",id,symbol,at+1000,at+1000,at+3000,at+3000,at+3000);
        db.update("INSERT INTO market_control_flow(tenant_id,task_id,options_json,state,last_price,last_at,finished_at) VALUES(1,?,'{}','SOURCE',10,?,?)",id,at+3000,at+3000);
        if(publish)db.update("INSERT INTO market_control_publication VALUES(1,?,?,?,?)",id,at+3000,at+1000,at+3000);
        db.update("INSERT INTO market_control_sample VALUES(1,?,?,10)",id,at+1000);
        db.update("INSERT INTO market_mixed_minute VALUES(1,?,?,?,?)",symbol,at,store.encode(bar(at,"99999")),at+5000);
        db.update("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(1,?,?,?,?,50)",id,symbol,at+5000,at+5000);
    }
    void oldHistory(int start,int end) {
        List<Object[]> mixed=new ArrayList<>(),events=new ArrayList<>();
        for(int i=start;i<end;i++) {
            long at=AT-(i+1)*60000L;mixed.add(new Object[]{1L,symbol,at,store.encode(bar(at,"88888")),at+5000});
            for(int j=0;j<12;j++)events.add(new Object[]{1L,"old-"+symbol+"-"+i+"-"+j,symbol,at+5000+j,at+5000+j,BigDecimal.ONE});
        }
        db.batchUpdate("INSERT INTO market_mixed_minute VALUES(?,?,?,?,?)",mixed);
        db.batchUpdate("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(?,?,?,?,?,?)",events);
    }
    Map<String,Long> handlers(){Map<String,Long> result=new TreeMap<>();for(Map<String,Object> row:db.queryForList("SHOW SESSION STATUS LIKE 'Handler_read%'"))result.put(row.get("Variable_name").toString(),Long.valueOf(row.get("Value").toString()));return result;}
    Map<String,Object> measured(int n,int cohort) {
        db.replays.set(0);
        return store.readConsumerSnapshot(()->{
            assertEquals(1,db.queryForObject("SELECT @@session.tx_read_only",Integer.class));
            Map<String,Long> before=handlers();long start=System.nanoTime();Map<String,Object> result=read(n);double ms=(System.nanoTime()-start)/1e6;
            Map<String,Long> delta=handlers();for(String key:delta.keySet())delta.put(key,delta.get(key)-before.get(key));
            evidence.add(Map.of("limit",n,"unrelatedMinutes",cohort,"handlers",delta,"replayedRows",db.replays.get(),"ms",ms,"serverReadOnly",true));
            assertEquals(1,db.replays.get());assertEquals(n,ControlHistoryStore.rows(result).size());
            assertEquals(7,((Number)((Map<?,?>)result.get("data")).get("historyRestoreRevision")).intValue());return result;
        });
    }
    @Test void modernReplayAndPhysicalWorkStayBoundedAcrossTenfoldGrowth() {
        controlled(AT-2000*60000L,true);oldHistory(0,100);
        Map<Integer,Map<String,Object>> before=new HashMap<>();for(int n:List.of(1,2,20,200,1000))before.put(n,measured(n,100));
        oldHistory(100,1000);
        for(int n:List.of(1,2,20,200,1000))assertEquals(before.get(n),measured(n,1000));
        for(int i=0;i<5;i++) {
            Map<?,?> a=(Map<?,?>)evidence.get(i).get("handlers"),b=(Map<?,?>)evidence.get(i+5).get("handlers");
            long old=a.values().stream().mapToLong(v->((Number)v).longValue()).sum(),now=b.values().stream().mapToLong(v->((Number)v).longValue()).sum();
            assertTrue(now<=old+100,"Physical work followed unrelated history: "+old+" -> "+now);
        }
        evidence.add(Map.of("sourcePlan",db.queryForList("EXPLAIN SELECT * FROM market_source_event WHERE tenant_id=1 AND symbol_id=? AND received_at>=? AND received_at<? ORDER BY received_at,event_sequence,source_time LIMIT 500",symbol,AT+999*WIDTH,AT+1000*WIDTH)));
        assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
    }
    @Test void httpWsAndHomeSizedConcurrentReadsAndFailuresReleaseThePool()throws Exception {
        ExecutorService workers=Executors.newFixedThreadPool(6);AtomicInteger peak=new AtomicInteger();
        try {
            List<Future<?>> work=new ArrayList<>();
            for(int n:List.of(200,200,2,2,20,20))work.add(workers.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(1L)){
                for(int i=0;i<2;i++)store.readConsumerSnapshot(()->{peak.accumulateAndGet(pool.getHikariPoolMXBean().getActiveConnections(),Math::max);assertEquals(n,ControlHistoryStore.rows(read(n)).size());return null;});
            }}));
            for(Future<?> f:work)f.get(30,TimeUnit.SECONDS);
        } finally {workers.shutdownNow();}
        assertTrue(peak.get()>0&&peak.get()<=4);assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
        db.update("INSERT INTO market_history_ordering(tenant_id,symbol_id,ordering_version,from_minute,source_sequence,scope_sha256,evidence_sha256,responses_json,sealed_at,writer_generation) VALUES(1,?,2,?,1000000000,?,?,'{}',?,1)",symbol,AT,HistoryOrdering.sha("owned-scope"),HistoryOrdering.sha("owned-evidence"),AT);
        assertTrue(assertThrows(BusinessException.class,()->read(2)).getMessage().contains("Future source event"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->store.readConsumerSnapshot(()->db.queryForObject("SELECT independent_nonexistent_column",Integer.class)));
        assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());assertEquals(0,pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
        evidence.add(Map.of("concurrentReads",12,"poolLimit",4,"peakActive",peak.get(),"afterActive",0,"afterPending",0,"orderingFailureReleased",true,"sqlFailureReleased",true));
    }
    @Test void shortPageCanFindPublishedHistoryAfterNineHiddenSourcePeriods() {
        // Delete only native rows written by this test's fresh synthetic symbol.
        db.update("DELETE FROM market_source_candle WHERE tenant_id=1 AND symbol_id=? AND candle_at<>?",symbol,AT);
        for(int i=1;i<=11;i++)controlled(AT-i*WIDTH+60000,i>=10);
        db.replays.set(0);
        Map<String,Object> result=store.readConsumerSnapshot(()->merger.merge(symbol,"5m",3,AT,external(),null));
        List<Map<String,Object>> bars=ControlHistoryStore.rows(result);
        assertEquals(List.of(AT-11*WIDTH,AT-10*WIDTH,AT),bars.stream().map(ControlHistoryStore::time).collect(java.util.stream.Collectors.toList()));
        assertEquals(true,bars.get(0).get("historyReplaced"));assertEquals(true,bars.get(1).get("historyReplaced"));assertEquals(11,db.replays.get());
        assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
        evidence.add(Map.of("hiddenPeriods",9,"requested",3,"returned",3,"replayedRows",11,"afterActive",0));
    }
    @Test void sampleAndEqualReceiveSourceKeysetsCrossOneThousandRowsOnMysql() {
        long minute=AT+999*WIDTH+60000;
        String task=db.queryForObject("SELECT id FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",String.class,symbol);
        List<Object[]> samples=new ArrayList<>(),events=new ArrayList<>();
        for(int i=0;i<1201;i++) {
            samples.add(new Object[]{1L,task,minute+1001+i,new BigDecimal("10")});
            events.add(new Object[]{1L,"dense-"+symbol+"-"+i,symbol,minute+10000+i,minute+7000,new BigDecimal("60").add(BigDecimal.valueOf(i,3))});
        }
        db.batchUpdate("INSERT INTO market_control_sample VALUES(?,?,?,?)",samples);
        db.batchUpdate("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(?,?,?,?,?,?)",events);
        db.replays.set(0);db.replayPages.clear();
        Map<String,Object> row=ControlHistoryStore.rows(read(1)).get(0);
        assertEquals(0,new BigDecimal("61.200").compareTo(ControlHistoryStore.number(row.get("close_price"))));
        assertEquals(1202,db.replays.get());assertEquals(List.of(500,500,202),db.replayPages);
        assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());
        evidence.add(Map.of("samples",1202,"sourceEvents",1202,"replayPages",new ArrayList<>(db.replayPages),"close","61.200","afterActive",0));
    }
    @Test void clientCancellationKeepsTheReadUntilCompletionThenReleasesIt()throws Exception {
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        ExecutorService worker=Executors.newSingleThreadExecutor();server.setExecutor(worker);
        CountDownLatch acquired=new CountDownLatch(1),allowFinish=new CountDownLatch(1),done=new CountDownLatch(1);
        List<Throwable> failures=new CopyOnWriteArrayList<>();AtomicInteger disconnected=new AtomicInteger();
        server.createContext("/kline",exchange->{
            try(TenantContext.Scope ignored=TenantContext.open(1L)) {
                Map<String,Object> result=store.readConsumerSnapshot(()->{
                    assertEquals(1,db.queryForObject("SELECT @@session.tx_read_only",Integer.class));acquired.countDown();
                    try{assertTrue(allowFinish.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new IllegalStateException(e);}
                    return read(2);
                });
                byte[] body=store.encode(result).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
            }catch(java.io.IOException expectedDisconnect){disconnected.incrementAndGet();}
            catch(Throwable failure){failures.add(failure);}
            finally{exchange.close();done.countDown();}
        });server.start();
        try {
            try(Socket client=new Socket("127.0.0.1",server.getAddress().getPort())) {
                client.getOutputStream().write("GET /kline HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));client.getOutputStream().flush();
                assertTrue(acquired.await(5,TimeUnit.SECONDS));assertEquals(1,pool.getHikariPoolMXBean().getActiveConnections());client.setSoLinger(true,0);
            }
            assertEquals(1,pool.getHikariPoolMXBean().getActiveConnections(),"Client cancellation alone does not cancel the physical JDBC transaction");
            allowFinish.countDown();assertTrue(done.await(10,TimeUnit.SECONDS));assertTrue(failures.isEmpty(),failures.toString());
            assertEquals(0,pool.getHikariPoolMXBean().getActiveConnections());assertEquals(0,pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
            evidence.add(Map.of("httpClientCancelled",true,"activeBeforeFinish",1,"afterActive",0,"afterPending",0,"disconnectsObserved",disconnected.get()));
        } finally {allowFinish.countDown();server.stop(0);worker.shutdownNow();}
    }
}
