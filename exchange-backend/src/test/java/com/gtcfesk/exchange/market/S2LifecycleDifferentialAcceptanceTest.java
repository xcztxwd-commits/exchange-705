package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.*;
import javax.sql.DataSource;
import javax.tools.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

/** Before is an independent unchanged core/schema oracle, never a writer to the S2 database.
 * Same seeded task/plan/source and explicit logical clock; actual startup/202 is separately tested.
 */
class S2LifecycleDifferentialAcceptanceTest {
    @TempDir Path compiled;
    static final String PREFIX="com.gtcfesk.exchange.market.";
    static final BigDecimal START=new BigDecimal("100000.00"), TARGET=new BigDecimal("100300.00");
    static final long SEED=1701L;
    static final long TRACE_AT=1700000000000L; // 2023-11-14T22:13:20Z: minute boundary + 20 seconds.
    static final int DURATION=300;
    final ObjectMapper json=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    final Map<String,Object> evidence=new LinkedHashMap<>();
    final List<Object> scenarios=new ArrayList<>();
    URLClassLoader oracle;

    @BeforeAll static void ownedMysql() throws Exception { S2RuntimeMysqlTest.identity(); }

    @Test void unchangedBeforeLifecycleAndCurrentMysqlMatchEveryBusinessField() throws Exception {
        String baseline=System.getenv("S2_BASELINE_DIR");assertNotNull(baseline,"Explicit immutable before tree required; no current oracle fallback");
        String output=System.getenv("S2_ACCEPTANCE_OUTPUT");assertNotNull(output,"Actual-run evidence directory required");
        Path before=Path.of(baseline).toRealPath();
        compileOracle(before);
        evidence.put("scope","unchanged before core on its original H2 schema; current core on identified owned MySQL; identical seeded input and explicit logical clock, not full old application boot");
        evidence.put("mysqlServerUuid",new JdbcTemplate(S2RuntimeMysqlTest.data).queryForObject("SELECT @@server_uuid",String.class));
        evidence.put("schemaEpoch",2026100305L);
        evidence.put("intentionalDifferences",Map.of(
            "newOnly","202 receipts/fence/generation/quoteVersion/snapshot/lag/execution lease/durable stop_at (same ended_at cutoff is compared) are S2 additions, validated by existing S2 command/runtime/SQL tests",
            "publication.published_at","audit wall-clock of each actual write, independently bounded by scenario wall-clock, while publication from_at/to_at are exactly compared",
            "source_event.event_sequence","global database identity differs across owned MySQL scenarios; ordered event identity/time/price rows preserve and compare actual sequence tie order"));
        evidence.put("scenarios",scenarios);
        boolean passed=false;
        try(URLClassLoader loader=oracle) {
            for(int version=1;version<=4;version++) for(boolean random:List.of(false,true)) for(boolean earlyStop:List.of(false,true))
                scenario(before,version,random,earlyStop);
            passed=true;
        } finally {
            evidence.put("passed",passed);
            Path path=Path.of(output).resolve("lifecycle-differential.json");Files.createDirectories(path.getParent());
            Path tmp=path.resolveSibling(path.getFileName()+".tmp");json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(),evidence);
            Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }
    }

    void compileOracle(Path before) throws Exception {
        Path source=before.resolve("exchange-backend/src/main/java/com/gtcfesk/exchange/market");
        Map<String,Path> files=new TreeMap<>();try(java.util.stream.Stream<Path> stream=Files.list(source)){stream.filter(p->p.toString().endsWith(".java")).forEach(p->files.put(p.getFileName().toString().replace(".java",""),p));}
        Set<String> closed=new TreeSet<>(List.of("PersistentPriceControl","ControlHistoryStore","ControlHoldService","ControlRecoveryFlow","ControlledKlineMerger","RandomMarketPath"));
        Pattern names=Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b");
        for(boolean changed=true;changed;) {
            Set<String> next=new TreeSet<>(closed);
            for(String name:closed){assertTrue(files.containsKey(name));Matcher matches=names.matcher(Files.readString(files.get(name)));while(matches.find())if(files.containsKey(matches.group()))next.add(matches.group());}
            changed=!next.equals(closed);closed=next;
        }
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-classpath",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),"-processor","lombok.launch.AnnotationProcessorHider$AnnotationProcessor","-d",compiled.toString()));
        Map<String,String> hashes=new TreeMap<>();
        for(String name:closed){Path file=files.get(name);args.add(file.toString());hashes.put(file.toString(),hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));}
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler);assertEquals(0,compiler.run(null,null,null,args.toArray(new String[0])),"compile unchanged before core closure");
        Set<String> independent=Set.copyOf(closed);
        oracle=new URLClassLoader(new java.net.URL[]{compiled.toUri().toURL()},getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                String simple=name.startsWith(PREFIX)?name.substring(PREFIX.length()).split("\\$",2)[0]:"";
                if(!independent.contains(simple))return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)){Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;}
            }
        };
        evidence.put("unchangedBeforeSources",hashes);
        Path schema=before.resolve("exchange-backend/src/test/resources/multitenant-market-test.sql");
        assertTrue(Files.isRegularFile(schema));evidence.put("unchangedBeforeSchema",Map.of("path",schema.toString(),"sha256",hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(schema)))));
    }

    void scenario(Path before,int version,boolean random,boolean earlyStop)throws Exception {
        S2RuntimeMysqlTest current=new S2RuntimeMysqlTest();current.setup();
        try {
            long at=TRACE_AT;
            assertEquals(20000,Math.floorMod(at,60000L));
            long lastFiveMinuteEnd=Math.floorDiv(at+318000,300000L)*300000L+300000L;
            assertTrue(lastFiveMinuteEnd<System.currentTimeMillis(),"All 5m aggregation buckets must have completed before actual wall clock");
            TradingSymbol config=current.symbol;config.setSymbol("LIFECYCLE");config.setRowVersion(0);config.setPricePrecision(version==2?6:version==3?4:2);
            config.setRandomMarketEnabled(random);config.setRandomMarketStartedAt(at-120000);config.setRandomMarketBasePrice(START);
            DriverManagerDataSource data=new DriverManagerDataSource("jdbc:h2:mem:before_lifecycle_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
            new ResourceDatabasePopulator(new FileSystemResource(before.resolve("exchange-backend/src/test/resources/multitenant-market-test.sql"))).execute(data);
            JdbcTemplate oldDb=new JdbcTemplate(data);oldDb.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,1)",config.getId());
            Object oldStore=oracle.loadClass(PREFIX+"ControlHistoryStore").getConstructor(JdbcTemplate.class,org.springframework.transaction.PlatformTransactionManager.class).newInstance(oldDb,new DataSourceTransactionManager(data));
            assertSame(oracle,oldStore.getClass().getClassLoader());
            Side old=new Side(oldStore,oldDb,construct("PersistentPriceControl",oldStore));
            Side fresh=new Side(current.store,current.store.db,current.controls);
            bootClock(old,at-1);bootClock(fresh,at-1);
            Map<String,Object> row=new LinkedHashMap<>();row.put("algorithmVersion",version);row.put("sourceMode",random?"RANDOM_FIXED_INPUT":"REAL_SOURCE_FIXED_INPUT");row.put("earlyStop",earlyStop);row.put("precision",config.getPricePrecision());row.put("clockStart",at);row.put("logicalClock",Map.of("traceAt",at,"warmBootAt",at-1,"coldBootAt",at+(earlyStop?165000:317000),"lastFiveMinuteBucketEnd",lastFiveMinuteEnd,"dbLeaseClock","actual MySQL DB clock; never overridden"));row.put("sameSymbolId",config.getId());
            List<Object> stages=new ArrayList<>();row.put("stages",stages);scenarios.add(row);long wall=System.currentTimeMillis();
            Map<String,Object> input=raw(config,at-10000,random,0,true);
            config.setControlEnabled(true);call(old.controls,"sourceQuote",config,input,at-10000);call(fresh.controls,"sourceQuote",config,input,at-10000);config.setControlEnabled(false);
            List<Map<String,Object>> bars=baseBars(config,at,random);
            call(old.store,"sourceCandles",config.getId(),"1m",bars,at-1000);call(fresh.store,"sourceCandles",config.getId(),"1m",bars,at-1000);
            String task=UUID.randomUUID().toString();
            seed(old,config,task,version,at,START,TARGET,DURATION,input,true,!earlyStop);
            seed(fresh,config,task,version,at,START,TARGET,DURATION,input,true,!earlyStop);
            for(long offset:new long[]{0,1000,15000,149000})checkpoint(old,fresh,config,raw(config,at+offset,random,offset,true),at+offset,wall,stages,"running-"+offset);
            if(earlyStop) {
                long stop=at+150500;call(old.controls,"stopAndHold",config.getId(),stop);call(fresh.controls,"stopAndHold",config.getId(),stop);
                checkpoint(old,fresh,config,raw(config,stop,random,150500,true),stop,wall,stages,"exact-stop");
                Map<String,Object> held=raw(config,at+151000,random,151000,true);
                checkpoint(old,fresh,config,held,at+151000,wall,stages,"hold");
                // Same exact event retries and a strictly older provider event must not alter frozen facts.
                checkpoint(old,fresh,config,held,at+151000,wall,stages,"duplicate");
                Map<String,Object> late=raw(config,at+130000,random,130000,true);late.put("eventId","late-"+task);
                checkpoint(old,fresh,config,late,at+152000,wall,stages,"out-of-order-late");
                List<Map<String,Object>> corrected=new ArrayList<>();for(Map<String,Object> bar:bars){Map<String,Object>b=new LinkedHashMap<>(bar);b.put("high_price",new BigDecimal("999999"));corrected.add(b);}
                call(old.store,"sourceCandles",config.getId(),"1m",corrected,at+152500);call(fresh.store,"sourceCandles",config.getId(),"1m",corrected,at+152500);
                checkpoint(old,fresh,config,raw(config,at+153000,random,153000,true),at+153000,wall,stages,"late-candle-frozen-prefix");
                BigDecimal restoreStart=ControlHistoryStore.number(((Map<?,?>)call(old.controls,"display",config,raw(config,at+153000,random,153000,true),at+153000)).get("price"));
                release(old,config,task,at+154000);release(fresh,config,task,at+154000);
                Map<String,Object> restoreRaw=raw(config,at+154000,random,154000,true);
                String restore=UUID.randomUUID().toString();BigDecimal target=ControlHistoryStore.number(restoreRaw.get("price"));
                seed(old,config,restore,2,at+154000,restoreStart,target,10,restoreRaw,false,false);
                seed(fresh,config,restore,2,at+154000,restoreStart,target,10,restoreRaw,false,false);
                for(long offset:new long[]{154000,159000,164000,165000})checkpoint(old,fresh,config,raw(config,at+offset,random,offset,true),at+offset,wall,stages,"explicit-restore-"+offset);
            } else {
                checkpoint(old,fresh,config,raw(config,at+300000,random,300000,false),at+300000,wall,stages,"endpoint-outage-wait");
                for(long offset:new long[]{301000,304000,305000,310000,316000,317000})checkpoint(old,fresh,config,raw(config,at+offset,random,offset,offset!=305000),at+offset,wall,stages,"recovery-pause-resume-"+offset);
            }
            // New cold readers never acquire or steal the current writer lease.
            Object coldOldStore=oracle.loadClass(PREFIX+"ControlHistoryStore").getConstructor(JdbcTemplate.class,org.springframework.transaction.PlatformTransactionManager.class).newInstance(oldDb,new DataSourceTransactionManager(data));
            Side coldOld=new Side(coldOldStore,oldDb,construct("PersistentPriceControl",coldOldStore));
            ControlHistoryStore cold=new ControlHistoryStore(new JdbcTemplate(S2RuntimeMysqlTest.data),new DataSourceTransactionManager(S2RuntimeMysqlTest.data));
            Side coldNew=new Side(cold,cold.db,new PersistentPriceControl(cold));
            long end=at+(earlyStop?165000:317000);bootClock(coldOld,end);bootClock(coldNew,end);Map<String,Object> lastRaw=raw(config,end,random,end-at,true);
            compare(coldOld,coldNew,config,(Map<String,Object>)call(coldOld.controls,"display",config,lastRaw,end),((PersistentPriceControl)coldNew.controls).display(config,lastRaw,end),lastRaw,end,wall,stages,"cold-read-only");
            Object oldTask=call(coldOld.controls,"latest",config.getId()),newTask=call(coldNew.controls,"latest",config.getId());
            assertEquals(normalize(call(oldTask,"price",end)),normalize(call(newTask,"price",end)),"cold task decode");
            if(version>=3){Object oldPlan=call(coldOld.store,"plan",task),newPlan=call(coldNew.store,"plan",task);for(int second=0;second<=DURATION;second++)assertEquals(normalize(call(oldPlan,"price",at,at+second*1000L)),normalize(call(newPlan,"price",at,at+second*1000L)),"cold independent plan point "+second);row.put("coldPlanPoints",DURATION+1);}
            current.scope.close(); // Leave tenant1 legitimately before the tenant2 rejection probe.
            try(TenantContext.Scope other=TenantContext.open(2L)) {
                assertThrows(org.springframework.security.access.AccessDeniedException.class,()->call(coldOld.controls,"display",config,input,end));
                assertThrows(org.springframework.security.access.AccessDeniedException.class,()->call(coldNew.controls,"display",config,input,end));
                assertEquals(0,coldOld.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=2 AND symbol_id=?",Integer.class,config.getId()));
                assertEquals(0,coldNew.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=2 AND symbol_id=?",Integer.class,config.getId()));
            }
            row.put("crossTenantDeniedBoth",true);row.put("passed",true);
        } finally {current.close();}
    }

    static final class Side {
        final Object store,controls; final JdbcTemplate db;
        Side(Object store,JdbcTemplate db,Object controls){this.store=store;this.db=db;this.controls=controls;}
    }

    void seed(Side side,TradingSymbol config,String id,int version,long at,BigDecimal start,BigDecimal target,int seconds,Map<String,Object> raw,boolean hold,boolean recovery)throws Exception {
        locked(side,config.getId(),()->{
            call(side.store,"freeze",config.getId(),at);call(side.store,"captureLegacyMinute",config.getId(),at);
            side.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,sampled_until,request_key) VALUES(1,?,?,?,?,?,'RUNNING',?,?,?,?,?,?,?,?,?,?,?,?)",id,config.getId(),config.getSymbol(),version,hold?"TARGET":"RESTORE",start,target,seconds,10,false,config.getPricePrecision(),"LIVE_DISPLAY",QuoteState.time(raw.get("sourceTimestamp")),at,at+seconds*1000L,at-1000,id);
            if(version>=3) {
                Object plan=plan(side,version,start,target,seconds,config.getPricePrecision());
                Object encoded=call(side.store,"encodePlan",plan);call(side.store,"savePlan",id,SEED,encoded);call(side.store,"rememberPlan",id,plan);
            }
            Object task=call(side.controls,"latest",config.getId());
            if(hold)call(field(side.controls,"holds"),"prepare",task,raw);
            if(hold){Object options=side.store.getClass().getClassLoader()==oracle?oracle.loadClass(PREFIX+"RecoveryOptions").getConstructor().newInstance():new RecoveryOptions();call(options,"setAutoRestore",recovery);call(options,"setRestoreRandomOscillation",false);call(options,"setRestoreDurationSeconds",10);call(options,"setAutoReplaceHistory",true);call(field(side.controls,"flows"),"create",task,options);}
            return null;
        });
    }

    Object plan(Side side,int version,BigDecimal start,BigDecimal target,int seconds,int precision)throws Exception {
        ClassLoader loader=side.store.getClass().getClassLoader()==oracle?oracle:getClass().getClassLoader();
        String name=PREFIX+(version==3?"BalancedControlPlan":"StabilizedControlPlan");Class<?> type=loader.loadClass(name),parameters=loader.loadClass(name+"$Parameters");Object p;
        if(version==3)p=parameters.getConstructor(BigDecimal.class,BigDecimal.class,int.class,int.class,int.class,BigDecimal.class).newInstance(start,target,seconds,precision,10,new BigDecimal("0.00001"));
        else {Class<?> options=loader.loadClass(PREFIX+"TargetControlOptions"),settings=loader.loadClass(PREFIX+"TargetControlSettings");Object o=options.getConstructor().newInstance();Object s=settings.getConstructor(BigDecimal.class,BigDecimal.class,int.class,int.class,int.class,options).newInstance(start,target,seconds,precision,10,o);p=parameters.getConstructor(BigDecimal.class,BigDecimal.class,int.class,int.class,int.class,BigDecimal.class,settings).newInstance(start,target,seconds,precision,10,new BigDecimal("0.00001"),s);}
        return type.getMethod("generate",parameters,long.class).invoke(null,p,SEED);
    }

    void release(Side s,TradingSymbol config,String id,long at)throws Exception {
        locked(s,config.getId(),()->{Object task=call(s.controls,"latest",config.getId());call(field(s.controls,"flows"),"cancel",task,at,false);call(field(s.controls,"holds"),"release",id,at);return null;});
    }

    @SuppressWarnings("unchecked") void checkpoint(Side old,Side fresh,TradingSymbol config,Map<String,Object> raw,long at,long wall,List<Object> stages,String label)throws Exception {
        call(old.controls,"sourceQuote",config,raw,at);call(fresh.controls,"sourceQuote",config,raw,at);
        Map<String,Object> oldQuote=(Map<String,Object>)call(old.controls,"display",config,raw,at);
        ((PersistentPriceControl)fresh.controls).pump(config,raw,at,60000);
        Map<String,Object> newQuote=((PersistentPriceControl)fresh.controls).display(config,raw,at);
        compare(old,fresh,config,oldQuote,newQuote,raw,at,wall,stages,label);
    }
    void compare(Side old,Side fresh,TradingSymbol config,Map<String,Object> oldQuote,Map<String,Object> newQuote,Map<String,Object> raw,long at,long wall,List<Object> stages,String label)throws Exception {
        Map<String,Object> left=projection(old,config,oldQuote,at,wall),right=projection(fresh,config,newQuote,at,wall);
        Map<String,Object> stage=new LinkedHashMap<>();stage.put("name",label);stage.put("logicalAt",at);stage.put("sameInput",raw);stage.put("before",left);stage.put("currentMysql",right);stage.put("s2LeaseObservation",Map.of("oldAvailable",Boolean.TRUE.equals(oldQuote.get("available")),"currentAvailable",Boolean.TRUE.equals(newQuote.get("available")),"quoteVersion",newQuote.getOrDefault("quoteVersion",0),"engineLag",newQuote.getOrDefault("engineLag",false)));stages.add(stage);
        assertEquals(left,right,"independent before/current business fields at "+label);
        if(label.startsWith("running-")||label.equals("explicit-restore-154000")||label.equals("explicit-restore-159000"))assertEquals("RUNNING",newQuote.get("controlState"),"trace must actually be running");
        if(List.of("exact-stop","hold","duplicate","out-of-order-late","late-candle-frozen-prefix").contains(label))assertEquals("HOLDING",newQuote.get("controlState"),"trace must actually preserve hold");
        if(label.equals("endpoint-outage-wait")||label.equals("recovery-pause-resume-305000"))assertEquals("WAITING_SOURCE",newQuote.get("controlState"),"trace must actually pause without source");
        if(label.equals("recovery-pause-resume-301000")||label.equals("recovery-pause-resume-304000")||label.equals("recovery-pause-resume-310000")||label.equals("recovery-pause-resume-316000"))assertEquals("RECOVERING",newQuote.get("controlState"),"trace must actually recover");
        if(label.equals("recovery-pause-resume-317000")||label.equals("explicit-restore-165000"))assertEquals("SOURCE",newQuote.get("controlState"),"trace must actually resume source");
        if(!label.startsWith("running-")&&!label.startsWith("explicit-restore-")&&!label.equals("cold-read-only"))assertFalse(((List<?>)right.get("publicationbusinessintervals")).isEmpty(),"stop/completion must publish actual interval");
    }

    Map<String,Object> projection(Side s,TradingSymbol config,Map<String,Object> quote,long at,long wall)throws Exception {
        Map<String,Object> result=new TreeMap<>();Map<String,Object> q=new TreeMap<>();
        for(String k:List.of("price","timestamp","sourceTimestamp","generatedAt","sourceAvailable","sourceStatus","controlState","controlOffset","controlHolding","controlRunning","controlSourceResumed","controlHistory","controlTaskId"))q.put(k,quote.get(k));result.put("quote",q);
        Map<String,String> tables=new LinkedHashMap<>();
        tables.put("task","SELECT tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,sampled_until,ended_at,request_key FROM market_control_task WHERE tenant_id=1 AND symbol_id=? ORDER BY started_at,id");
        tables.put("sourceQuote","SELECT * FROM market_source_quote WHERE tenant_id=1 AND symbol_id=?");
        tables.put("sourceTicks","SELECT * FROM market_source_tick WHERE tenant_id=1 AND symbol_id=? ORDER BY source_time,received_at");
        tables.put("sourceEvents","SELECT tenant_id,event_id,symbol_id,source_time,received_at,price FROM market_source_event WHERE tenant_id=1 AND symbol_id=? ORDER BY received_at,event_sequence");
        tables.put("sourceCandles","SELECT * FROM market_source_candle WHERE tenant_id=1 AND symbol_id=? ORDER BY period,candle_at");
        tables.put("mixedMinutes","SELECT * FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=? ORDER BY minute_at");
        tables.put("frozenPrefix","SELECT * FROM market_legacy_minute_snapshot WHERE tenant_id=1 AND symbol_id=? ORDER BY minute_at");
        for(Map.Entry<String,String> entry:tables.entrySet())result.put(entry.getKey(),s.db.queryForList(entry.getValue(),config.getId()));
        for(String table:List.of("market_control_plan","market_control_sample","market_control_hold","market_control_flow","market_control_resume")) {
            String order=table.equals("market_control_sample")?" ORDER BY task_id,generated_at":" ORDER BY task_id";
            result.put(table,s.db.queryForList("SELECT * FROM "+table+" WHERE tenant_id=1 AND task_id IN (SELECT id FROM market_control_task WHERE tenant_id=1 AND symbol_id=?)"+order,config.getId()));
        }
        List<Map<String,Object>> publications=s.db.queryForList("SELECT * FROM market_control_publication WHERE tenant_id=1 AND task_id IN (SELECT id FROM market_control_task WHERE tenant_id=1 AND symbol_id=?) ORDER BY task_id",config.getId());
        for(Map<String,Object> p:publications){long value=((Number)p.remove("published_at")).longValue();assertTrue(value>=wall&&value<=System.currentTimeMillis(),"actual publication audit time bounded, not hidden");}
        result.put("publicationBusinessIntervals",publications);
        Object merger=construct(s.store.getClass().getClassLoader()==oracle?oracle:getClass().getClassLoader(),"ControlledKlineMerger",s.store);
        for(String period:List.of("1m","5m"))result.put("kline:"+period,call(merger,"merge",config.getId(),period,20,at,Map.of("data",Map.of("kline_list",List.of())),null));
        return (Map<String,Object>)normalize(result);
    }

    Map<String,Object> raw(TradingSymbol config,long at,boolean random,long offset,boolean available)throws Exception {
        BigDecimal price=random?(BigDecimal)oracle.loadClass(PREFIX+"RandomMarketPath").getMethod("basePrice",TradingSymbol.class,long.class).invoke(null,config,at):START.add(new BigDecimal("0.1234567890123456")).add(BigDecimal.valueOf(offset/1000.0).multiply(new BigDecimal("0.01")));
        if(random)assertEquals(0,price.compareTo(RandomMarketPath.basePrice(config,at)),"same immutable random input generator");
        Map<String,Object> q=new LinkedHashMap<>();q.put("price",price);q.put("timestamp",at);q.put("sourceTimestamp",at);q.put("fetchedAt",at);q.put("expiresAt",at+60000);q.put("available",available);q.put("status",available?"available":"unavailable");q.put("eventId","trace-"+at);return q;
    }

    @SuppressWarnings("unchecked") List<Map<String,Object>> baseBars(TradingSymbol config,long at,boolean random)throws Exception {
        if(random){Map<String,Object> q=(Map<String,Object>)oracle.loadClass(PREFIX+"RandomMarketPath").getMethod("klines",TradingSymbol.class,String.class,Integer.class,Long.class,long.class).invoke(null,config,"1m",8,at,at);List<Map<String,Object>> bars=ControlHistoryStore.rows(q);assertEquals(normalize(bars),normalize(ControlHistoryStore.rows(RandomMarketPath.klines(config,"1m",8,at,at))));return bars;}
        List<Map<String,Object>> bars=new ArrayList<>();for(int i=-2;i<=0;i++){Map<String,Object>b=new LinkedHashMap<>();b.put("timestamp",(at/60000+i)*60000);b.put("open_price",START);b.put("high_price",START.add(BigDecimal.ONE));b.put("low_price",START.subtract(BigDecimal.ONE));b.put("close_price",START);b.put("volume",BigDecimal.TEN);bars.add(b);}return bars;
    }

    Object normalize(Object value)throws Exception {
        if(value instanceof Map<?,?>){Map<?,?> map=(Map<?,?>)value;Map<String,Object> result=new TreeMap<>();for(Map.Entry<?,?> e:map.entrySet()){String k=e.getKey().toString().toLowerCase(Locale.ROOT);Object v=e.getValue();if(v instanceof String&&(k.equals("body")||k.endsWith("_json")))v=json.readValue((String)v,Object.class);result.put(k,normalize(v));}return result;}
        if(value instanceof Collection<?>){List<Object> r=new ArrayList<>();for(Object x:(Collection<?>)value)r.add(normalize(x));return r;}
        if(value instanceof Number)return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        return value;
    }
    Object construct(String name,Object arg)throws Exception{return construct(oracle,name,arg);}
    Object construct(ClassLoader loader,String name,Object arg)throws Exception {Class<?> c=loader.loadClass(PREFIX+name);for(Constructor<?> x:c.getDeclaredConstructors())if(x.getParameterCount()==1&&x.getParameterTypes()[0].isInstance(arg)){x.setAccessible(true);return x.newInstance(arg);}throw new NoSuchMethodException(name);}
    static void bootClock(Side side,long at)throws Exception {
        Object flow=field(side.controls,"flows");Field clock=flow.getClass().getDeclaredField("openedAt");
        clock.setAccessible(true);clock.setLong(flow,at);assertEquals(at,clock.getLong(flow),"Explicit identical process boot clock fixture");
    }
    static Object field(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static Object call(Object target,String name,Object... args)throws Exception {
        for(Method method:target.getClass().getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length) {
            Class<?>[] types=method.getParameterTypes();boolean match=true;
            for(int i=0;i<args.length;i++)if(args[i]!=null){Class<?> t=types[i];if(t.isPrimitive())t=t==long.class?Long.class:t==int.class?Integer.class:t==boolean.class?Boolean.class:t;if(!t.isInstance(args[i])){match=false;break;}}
            if(match){method.setAccessible(true);try{return method.invoke(target,args);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw failure;}}
        }
        throw new NoSuchMethodException(target.getClass()+"."+name+"/"+args.length);
    }
    interface Work {Object run()throws Exception;}
    static Object locked(Side s,long symbol,Work work)throws Exception {return call(s.store,"locked",symbol,(Supplier<Object>)()->{try{return work.run();}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}});}
    static String hex(byte[] b){return java.util.HexFormat.of().formatHex(b);}
}
