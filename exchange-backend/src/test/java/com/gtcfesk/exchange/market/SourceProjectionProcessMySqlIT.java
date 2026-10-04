package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.DedicatedMysqlFixture;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Actual process death at every SOURCE projection write shape, plus commit uncertainty; no exception-only substitution. */
class SourceProjectionProcessMySqlIT {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final AtomicLong IDS=new AtomicLong(System.currentTimeMillis()*1000);
    private static DriverManagerDataSource data;private static JdbcTemplate db;private static Path evidence,definition;
    private static Map<String,List<String>> original;private TenantContext.Scope scope;private static long tenant;
    @BeforeAll static void identifiedCurrent404()throws Exception {
        definition=Paths.get(System.getProperty("joint.s4process.fixture")).toAbsolutePath().normalize();
        data=DedicatedMysqlFixture.fromProperty("joint.s4process.fixture");db=new JdbcTemplate(data);SourceProjectionCrashChild.require404(db);
        // This direct-service test may reuse the current owned funding clone. It never starts Boot/engine/old queues.
        // Capture its current complete state; do not relabel a used database as the earlier fresh31 restore.
        TransactionTemplate read=new TransactionTemplate(new DataSourceTransactionManager(data));read.setReadOnly(true);read.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        original=read.execute(status->{try{return allRows();}catch(Exception e){throw new IllegalStateException(e);}});
        assertEquals(112,original.size());long rows=original.values().stream().mapToLong(List::size).sum();
        assertTrue(SourceDirty0403MySqlIT.verifyComplete0403(db),"Existing SOURCE schema and immutable original trigger bodies required");
        evidence=definition.getParent().resolve("s4-process-raw");Files.createDirectories(evidence);
        write("physical-identity",db.queryForMap("SELECT @@server_uuid AS server_uuid,VERSION() AS version,DATABASE() AS database_name"));
        write("current-before-all-columns",original);
        write("certified-current-start",Map.of("definitionSha256",DedicatedMysqlFixture.hash(definition),"scope","Current used owned404; not a fresh31 assertion; no autonomous engine", "originalTables",original.size(),"originalRows",rows));
    }
    @BeforeEach void tenant(){
        org.springframework.jdbc.support.GeneratedKeyHolder key=new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(c->{java.sql.PreparedStatement p=c.prepareStatement("INSERT INTO tenant(code,name,status,created_at) VALUES(?,'Owned SOURCE process fixture','MAINTENANCE',UTC_TIMESTAMP(6))",java.sql.Statement.RETURN_GENERATED_KEYS);p.setString(1,"s4-process-"+UUID.randomUUID());return p;},key);
        tenant=key.getKey().longValue();scope=TenantContext.open(tenant);
    }
    @AfterEach void originalsUnchanged(TestInfo info)throws Exception {
        try{Map<String,List<String>> now=allRows();List<String> changed=new ArrayList<>();for(Map.Entry<String,List<String>> entry:original.entrySet()){
            List<String> remaining=new ArrayList<>(now.getOrDefault(entry.getKey(),Collections.emptyList()));for(String row:entry.getValue())if(!remaining.remove(row)){changed.add(entry.getKey());break;}
        }write(info.getTestMethod().orElseThrow().getName()+"-original-multiset",Map.of("changedOriginalTables",changed));assertTrue(changed.isEmpty(),changed.toString());}
        finally{scope.close();}
    }
    @Test void actualDeathAfterEveryInitialWriteAndPhysicalCommitRestartsExactly()throws Exception {
        for(String point:Arrays.asList("PROGRESS_INSERT","MINUTE_1","MINUTE_2","PROGRESS_PUBLISH","DIRTY_CURSOR","BEFORE_COMMIT","AFTER_COMMIT")){
            Fixture fixture=new Fixture();fixture.source(2);fixture.pump();crashAndRecover(fixture,point,2);
        }
    }
    @Test void actualDeathAfterHorizonAndGenerationReplacementRestartsWithoutRawMutation()throws Exception {
        Fixture append=new Fixture();append.source(2);append.pump();assertTrue(append.project());append.sourceRange(2,2);crashAndRecover(append,"HORIZON_UPDATE",4);
        Fixture generation=new Fixture();generation.source(2);generation.pump();assertTrue(generation.project());long old=((Number)generation.runtime().get("writer_generation")).longValue();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);
        while(!db.queryForObject("SELECT lease_until<=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED) FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",Boolean.class,tenant,generation.id)){
            if(System.nanoTime()>deadline)fail("Actual runtime lease did not expire");Thread.sleep(50);
        }
        generation.store=new ControlHistoryStore(db,new DataSourceTransactionManager(data));generation.controls=new PersistentPriceControl(generation.store);generation.pump();
        assertEquals(old+1,((Number)generation.runtime().get("writer_generation")).longValue());crashAndRecover(generation,"GENERATION_UPDATE",2);
    }
    private void crashAndRecover(Fixture fixture,String point,int count)throws Exception {
        Map<String,Object> before=fixture.state();JsonNode death=child(point,fixture.id,73);assertEquals(point,death.path("reached").asText());assertFalse(death.path("autoCommit").asBoolean());
        assertEquals(1,death.path("physicalWriteConnections").size());assertTrue(death.path("writes").size()>0);
        Map<String,Object> afterDeath=fixture.state();
        assertEquals(before.get("raw"),afterDeath.get("raw"));assertEquals(before.get("ticks"),afterDeath.get("ticks"));assertEquals(before.get("events"),afterDeath.get("events"));
        boolean committed=point.equals("AFTER_COMMIT");
        if(!committed)assertEquals(before,afterDeath,"Abrupt uncommitted death must roll back body/hash/progress/dirty cursor together");
        else {assertNotEquals(before,afterDeath);fixture.assertComplete(count);}
        JsonNode successor=child("RECOVER",fixture.id,0);assertEquals(!committed,successor.path("changed").asBoolean());assertNotEquals(death.path("pid").asLong(),successor.path("pid").asLong());
        fixture.assertComplete(count);Map<String,Object> complete=fixture.state();
        if(committed){assertEquals(afterDeath,complete);assertEquals(0,successor.path("writes").size(),"Unknown commit recovery may not write a second version");}
        else {assertEquals(1,successor.path("physicalWriteConnections").size());assertTrue(successor.path("writes").size()>0);}
        JsonNode duplicate=child("RECOVER",fixture.id,0);assertFalse(duplicate.path("changed").asBoolean());assertEquals(0,duplicate.path("writes").size());assertEquals(complete,fixture.state());
        assertEquals(before.get("raw"),complete.get("raw"));assertEquals(before.get("ticks"),complete.get("ticks"));assertEquals(before.get("events"),complete.get("events"));
        write("case-"+point+"-"+fixture.id,Map.of("before",before,"afterDeath",afterDeath,"afterSuccessor",complete,"crash",death,"successor",successor,"duplicate",duplicate));
    }
    private static JsonNode child(String point,long symbol,int exit)throws Exception {
        String id=point+"-"+symbol+"-"+UUID.randomUUID();Path marker=evidence.resolve(id+".json"),log=evidence.resolve(id+".log");
        String executable=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        List<String> command=new ArrayList<>(Arrays.asList(executable,"-Xmx192m","-Djoint.s4process.fixture="+definition,"-cp",cp,SourceProjectionCrashChild.class.getName(),point,Long.toString(tenant),Long.toString(symbol),marker.toString()));
        Process child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(child.waitFor(60,TimeUnit.SECONDS),"Projection child timeout: "+log);assertEquals(exit,child.exitValue(),Files.readString(log));}
        finally{if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(5,TimeUnit.SECONDS));}}
        assertTrue(Files.isRegularFile(marker),"No durable actual point marker: "+log);return JSON.readTree(Files.readAllBytes(marker));
    }
    private static final class Fixture {
        final long id=IDS.incrementAndGet(),first=(db.queryForObject("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",Long.class)/60000-10)*60000;
        final TradingSymbol config=new TradingSymbol();ControlHistoryStore store=new ControlHistoryStore(db,new DataSourceTransactionManager(data));PersistentPriceControl controls=new PersistentPriceControl(store);
        Fixture(){config.setTenantId(tenant);config.setId(id);config.setSymbol("PC"+id);config.setName(config.getSymbol());config.setBaseCurrency("TEST");config.setQuoteCurrency("USD");config.setMarketSource("yahoo");config.setSourceCategory("Metal");config.setCategory("Metal");config.setPricePrecision(8);config.setIsEnabled(true);config.setControlEnabled(false);config.setRandomMarketEnabled(false);config.setRowVersion(0);
            db.update("INSERT INTO trading_symbol(tenant_id,id,symbol,name,base_currency,quote_currency,market_source,source_category,category,is_enabled,control_enabled,random_market_enabled,price_precision,row_version) VALUES(?,?,?,?,'TEST','USD','yahoo','Metal','Metal',1,0,0,8,0)",tenant,id,config.getSymbol(),config.getSymbol());}
        void source(int count){sourceRange(0,count);}
        void sourceRange(int start,int count){List<Map<String,Object>> rows=new ArrayList<>();for(int i=start;i<start+count;i++){
            Map<String,Object> row=new LinkedHashMap<>();row.put("timestamp",first+i*60000L);for(String key:Arrays.asList("open_price","high_price","low_price","close_price"))row.put(key,new BigDecimal("100.12345678"));row.put("volume",1);rows.add(row);
        }store.sourceCandles(id,"1m",rows,store.runtime.clock()-1);}
        void pump(){long now=store.runtime.clock();Map<String,Object> raw=new LinkedHashMap<>();raw.put("price",new BigDecimal("100.12345678"));raw.put("timestamp",now);raw.put("sourceTimestamp",now);raw.put("available",true);raw.put("expiresAt",now+60000);controls.pump(config,raw,now,60000);}
        Map<String,Object> runtime(){return db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",tenant,id);}
        boolean project(){Map<String,Object> runtime=runtime();return new SourceHistoryProjector(store,new DataSourceTransactionManager(data)).project(id,(String)runtime.get("owner_id"),((Number)runtime.get("writer_generation")).longValue(),((Number)runtime.get("control_revision")).longValue());}
        Map<String,Object> state(){Map<String,Object> state=new LinkedHashMap<>();state.put("runtime",runtime());
            state.put("raw",db.queryForList("SELECT * FROM market_source_candle WHERE tenant_id=? AND symbol_id=? ORDER BY period,candle_at",tenant,id));
            state.put("ticks",db.queryForList("SELECT * FROM market_source_tick WHERE tenant_id=? AND symbol_id=? ORDER BY source_time",tenant,id));
            state.put("events",db.queryForList("SELECT * FROM market_source_event WHERE tenant_id=? AND symbol_id=? ORDER BY event_sequence",tenant,id));
            state.put("progress",db.queryForList("SELECT * FROM s4_history_projection_progress WHERE tenant_id=? AND symbol_id=?",tenant,id));
            state.put("minutes",db.queryForList("SELECT * FROM s4_history_projection_minute WHERE tenant_id=? AND symbol_id=? ORDER BY minute_at",tenant,id));return state;}
        void assertComplete(int count){Map<String,Object> runtime=runtime();Map<String,Object> progress=db.queryForMap("SELECT * FROM s4_history_projection_progress WHERE tenant_id=? AND symbol_id=?",tenant,id);
            assertEquals(runtime.get("writer_generation"),progress.get("generation"));assertEquals(runtime.get("source_input_revision"),progress.get("input_revision"));
            assertNull(runtime.get("source_dirty_from"));assertNull(runtime.get("source_dirty_to"));assertEquals(first+count*60000L-1,((Number)progress.get("watermark")).longValue());
            assertTrue(String.valueOf(progress.get("last_hash")).matches("[a-f0-9]{64}"));
            MinuteHistoryProjectionStore.ReadResult result=new SourceHistoryProjector(store,new DataSourceTransactionManager(data)).readWindow(id,first,first+(count-1)*60000L);
            assertFalse(result.pending);assertEquals(count,result.minutes.size());
            for(int i=0;i<count;i++){Map<String,Object> row=result.minutes.get(i);assertEquals(first+i*60000L,ControlHistoryStore.time(row));for(String key:Arrays.asList("open_price","high_price","low_price","close_price"))assertEquals(0,new BigDecimal("100.12345678").compareTo(new BigDecimal(row.get(key).toString())));}
        }
    }
    private static Map<String,List<String>> allRows()throws Exception {Map<String,List<String>> result=new TreeMap<>();for(String table:db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name",String.class)){
        assertTrue(table.matches("[A-Za-z0-9_]+"));assertTrue(db.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Long.class)<50000,"Use small current fixture, not capacity database");List<String> values=new ArrayList<>();for(Map<String,Object> row:db.queryForList("SELECT * FROM `"+table+"`"))values.add(JSON.writeValueAsString(new TreeMap<>(row)));Collections.sort(values);result.put(table,values);
    }return result;}
    private static void write(String name,Object value)throws Exception{Files.write(evidence.resolve(name+"-"+UUID.randomUUID()+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),StandardOpenOption.CREATE_NEW);}
}
