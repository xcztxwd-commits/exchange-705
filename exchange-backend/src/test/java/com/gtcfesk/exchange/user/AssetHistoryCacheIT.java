package com.gtcfesk.exchange.user;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.config.AssetHistoryRedisConfig;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.io.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** No mock database, Redis, valuation or history service. External quotes are unnecessary for cash-only fixtures. */
@TestMethodOrder(MethodOrderer.MethodName.class)
class AssetHistoryCacheIT {
    static final ObjectMapper json=new ObjectMapper();
    static final long DAY=86400000L, NOW=Instant.parse("2026-09-28T03:23:45Z").toEpochMilli(), START=AssetHistoryBucket.floor(NOW,DAY)-2*DAY;
    static final String[] RANGES={"1D","1W","1M","1Y"};
    static DriverManagerDataSource physical;static Counting source;static JdbcTemplate db;
    static AssetEquityStore store;static EquityValuationService valuation;static AssetEquityHistoryService api;
    static AssetHistoryRedisConfig redisConfig;static StringRedisTemplate redis;static AssetHistoryCache cache;
    static Path report;static List<Map<String,Object>> evidence=new ArrayList<>();
    static class Counting extends DelegatingDataSource {
        final Map<String,Long> counts=new TreeMap<>();long nanos;
        Counting(javax.sql.DataSource ds){super(ds);}
        synchronized void reset(){counts.clear();nanos=0;}
        synchronized void record(String sql,long duration){
            String s=sql.toLowerCase(Locale.ROOT),kind=s.contains("user_account")?"auth":s.contains("asset_history_revision")?"revision":s.contains("asset_history_1m")?"minute/carry/opening":s.matches("(?s).*asset_history_(1h|4h|1d).*" )?"parent-body":s.contains("asset_history_baseline")?"baseline":s.contains("system_config")?"config":s.contains("sum(")?"income":"live/preparation";
            counts.merge(kind,1L,Long::sum);nanos+=duration;
        }
        @Override public Connection getConnection()throws SQLException{
            Connection c=super.getConnection();return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                try{
                    Object result=m.invoke(c,a);
                    if(m.getName().equals("prepareStatement")){
                        String sql=(String)a[0];PreparedStatement statement=(PreparedStatement)result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(q,method,args)->{
                            long begin=System.nanoTime();try{return method.invoke(statement,args);}catch(InvocationTargetException e){throw e.getCause();}
                            finally{if(method.getName().startsWith("execute"))record(sql,System.nanoTime()-begin);}
                        });
                    }
                    if(m.getName().equals("createStatement")){
                        Statement statement=(Statement)result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Statement.class},(q,method,args)->{
                            long begin=System.nanoTime();try{return method.invoke(statement,args);}catch(InvocationTargetException e){throw e.getCause();}
                            finally{if(method.getName().startsWith("execute")&&args!=null&&args.length>0&&args[0] instanceof String)record((String)args[0],System.nanoTime()-begin);}
                        });
                    }return result;
                }catch(InvocationTargetException e){throw e.getCause();}
            });
        }
    }
    @BeforeAll static void setup()throws Exception{
        physical=com.gtcfesk.exchange.tenant.DedicatedMysqlFixture.fromProperty("cache.mysql.fixture");
        report=Paths.get(Objects.requireNonNull(System.getenv("CACHE_TEST_REPORT")));Files.createDirectories(report);
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.springframework")).setLevel(ch.qos.logback.classic.Level.WARN);
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("io.lettuce")).setLevel(ch.qos.logback.classic.Level.ERROR);
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("io.netty")).setLevel(ch.qos.logback.classic.Level.ERROR);
        source=new Counting(physical);db=new JdbcTemplate(source);
        assertTrue(db.queryForObject("select version()",String.class).startsWith("5.7."));assertTrue(db.queryForObject("select database()",String.class).startsWith("mt705_probe_"));
        db.update("insert into user_account(tenant_id,id,email,password_hash,status,row_version) values(2,1,'cache@fixture.invalid','not-a-login','normal',0),(2,2,'cache-other@fixture.invalid','not-a-login','normal',0) on duplicate key update row_version=row_version");
        assertEquals(2L,db.queryForObject("select count(*) from user_account where tenant_id=2 and id in(1,2)",Long.class));
        RedisProperties properties=new RedisProperties();properties.setHost("127.0.0.1");properties.setPort(Integer.parseInt(System.getenv("CACHE_TEST_REDIS_PORT")));
        redisConfig=new AssetHistoryRedisConfig();redis=redisConfig.assetHistoryRedis(properties,200);redis.afterPropertiesSet();
        try(org.springframework.data.redis.connection.RedisConnection connection=redis.getConnectionFactory().getConnection()){
            assertTrue(connection.info("server").getProperty("redis_version").startsWith("7."));
        }
        store=new AssetEquityStore(source,json);valuation=new EquityValuationService(db,null);api=new AssetEquityHistoryService(store,valuation);
    }
    @BeforeEach void seed(){
        com.gtcfesk.exchange.tenant.TenantContext.open(2L);
        cache=new AssetHistoryCache(redis,json);cache.enabled=true;cache.namespace="705:cache-test:"+UUID.randomUUID();store.cache=cache;
        for(String table:Arrays.asList("contract_order","option_order","financial_yield_record","loan_record","asset_account","asset_history_1m","asset_history_1h","asset_history_4h","asset_history_1d","asset_history_baseline","asset_history_job_state","asset_history_quote_batch","asset_history_revision"))db.update("delete from "+table+" where tenant_id=2");
        db.update("insert into asset_account(tenant_id,user_id,coin,available,frozen,row_version) values(2,1,'CONTRACT',1000,0,0),(2,2,'FUND',777,0,0)");
        List<Object[]> minutes=new ArrayList<>();for(int i=0;i<2880;i++)minutes.add(new Object[]{START+i*60000,START+i*60000+5000,i%60==10?null:i%60==11?"-5.1234567890123456":i%60==12?"0":"1000.1234567890123456"});
        db.batchUpdate("insert into asset_history_1m(tenant_id,user_id,basis_version,bucket_start,observed_at,net_equity,valuation_status,reason_code,valuation_evidence,origin,created_at) values(2,1,'net_equity_v1',?,?,?,'COMPLETE','','{}','OBSERVED',0)",minutes);
        AssetEquityJobs jobs=new AssetEquityJobs(store,valuation);
        try{store.locked("cache_seed",s->{for(int level=1;level<=3;level++){long size=AssetEquityStore.INTERVALS[level];for(long t=START;t<START+2*DAY;t+=size)jobs.reduce(s.db,level,Collections.singletonList(1L),t,t+size,NOW,true);store.progress(s.db,"rollup_"+level,START+2*DAY,0,NOW);}});}finally{jobs.stop();}
        db.update("insert into asset_history_baseline(tenant_id,user_id,basis_version,capture_from,first_positive,first_positive_at) values(2,1,'net_equity_v1',?,1000,?) on duplicate key update capture_from=values(capture_from),first_positive=1000,first_positive_at=values(first_positive_at)",START,START);
    }
    @AfterEach void clearTenant(){com.gtcfesk.exchange.tenant.TenantContext.clear();}
    static <T> Callable<T> scoped(Callable<T> work){return ()->{try(com.gtcfesk.exchange.tenant.TenantContext.Scope scope=com.gtcfesk.exchange.tenant.TenantContext.open(2L)){return work.call();}};}
    @AfterAll static void finish()throws Exception{if(report!=null)json.writerWithDefaultPrettyPrinter().writeValue(report.resolve("cache-evidence.json").toFile(),evidence);if(redisConfig!=null)redisConfig.close();}
    static Map<String,Object> response(long user,String range){return api.history(user,range,new EquityValuationService.Batch(),NOW);}
    static String encoded(Object value)throws Exception{return json.writeValueAsString(value);}
    static void equal(Object a,Object b)throws Exception{assertEquals(encoded(a),encoded(b));}
    static void record(String id,String details){Map<String,Object> row=new LinkedHashMap<>();row.put("case",id);row.put("details",details);row.put("at",Instant.now().toString());evidence.add(row);}
    static long revision(int level,long user){return db.queryForObject("select coalesce(max(revision),0) from asset_history_revision where tenant_id=2 and user_id=? and basis_version='net_equity_v1' and level=?",Long.class,user,level);}
    static void adjust(JdbcTemplate tx,int level,long user,BigDecimal delta){
        for(AssetHistoryBucket b:store.source(tx,level,Collections.singletonList(user),START,START+2*DAY,NOW,true)){
            if(b.close!=null)b.close=b.close.add(delta);if(b.high!=null)b.high=b.high.add(delta);store.saveBucket(tx,level,b,NOW);
        }
    }
    @Test void C01_coldWarmDisabledEquivalent()throws Exception{
        for(String range:RANGES){cache.enabled=false;Map<String,Object> off=response(1,range);cache.enabled=true;Map<String,Object> cold=response(1,range);equal(off,cold);long hits=cache.hits.sum();Map<String,Object> warm=response(1,range);equal(off,warm);if(!range.equals("1D"))assertTrue(cache.hits.sum()>hits);
            Map<String,Object> comparison=new LinkedHashMap<>();comparison.put("off",off);comparison.put("cold",cold);comparison.put("warm",warm);json.writeValue(report.resolve("equivalence-"+range+".json").toFile(),comparison);
        }
        source.reset();response(1,"1W");assertNull(source.counts.get("parent-body"));record("C01","Complete response equality all four ranges; warm 1W parent-body SQL=0; "+source.counts);
    }
    @Test void C02_realRollupCommitAllLevelsAndNoop()throws Exception{
        AssetEquityJobs jobs=new AssetEquityJobs(store,valuation);
        try{for(int level=1;level<=3;level++){
            final int l=level;response(1,RANGES[l]);long before=revision(l,1),size=AssetEquityStore.INTERVALS[l],t=START+2*DAY;
            // Build a real source minute and recursively reduce it through the real production method.
            db.update("insert into asset_history_1m(tenant_id,user_id,basis_version,bucket_start,observed_at,net_equity,valuation_status,reason_code,valuation_evidence,origin,created_at) values(2,1,'net_equity_v1',?,?,1500,'COMPLETE','','{}','OBSERVED',0) on duplicate key update net_equity=1500",t,t+5000);
            store.locked("cache_rollup",s->{for(int k=1;k<=l;k++)jobs.reduce(s.db,k,Collections.singletonList(1L),t,t+AssetEquityStore.INTERVALS[k],NOW+DAY,true);});
            assertTrue(revision(l,1)>before);long committed=revision(l,1);
            store.locked("cache_rollup",s->jobs.reduce(s.db,l,Collections.singletonList(1L),t,t+size,NOW+DAY+1000,true));assertEquals(committed,revision(l,1),"updated_at alone must not bump revision");
            cache.enabled=false;Object off=api.history(1L,RANGES[l],new EquityValuationService.Batch(),NOW+DAY);cache.enabled=true;equal(off,api.history(1L,RANGES[l],new EquityValuationService.Batch(),NOW+DAY));
        }}finally{jobs.stop();}record("C02","Production reduce, 1h/4h/1d commit visible; repeated equivalent writes do not bump revisions");
    }
    @Test void C03_uncommittedRollbackAndBatchCommit()throws Exception{
        Object old=response(1,"1W");long rev=revision(1,1);CountDownLatch written=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        try{Future<?> task=pool.submit(scoped(()->assertThrows(IllegalStateException.class,()->store.locked("cache_rollback",s->{adjust(s.db,1,1,BigDecimal.TEN);written.countDown();await(release);throw new IllegalStateException("rollback fixture");}))));
            assertTrue(written.await(10,TimeUnit.SECONDS));equal(old,response(1,"1W"));release.countDown();task.get(10,TimeUnit.SECONDS);assertEquals(rev,revision(1,1));equal(old,response(1,"1W"));
        }finally{release.countDown();pool.shutdownNow();}
        assertThrows(IllegalStateException.class,()->store.locked("cache_batches",s->{adjust(s.db,1,1,BigDecimal.ONE);s.commit();adjust(s.db,1,1,BigDecimal.TEN);throw new IllegalStateException("second batch rollback");}));
        assertNotEquals(encoded(old),encoded(response(1,"1W")));assertEquals(new BigDecimal("1001.1234567890123456"),db.queryForObject("select close_value from asset_history_1h where tenant_id=2 and user_id=1 order by bucket_start limit 1",BigDecimal.class));record("C03","Barrier: uncommitted invisible; rollback history+revision unchanged; committed batch survives later rollback");
    }
    static void await(CountDownLatch latch){try{if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    @Test void C04_oldReaderCannotOverwriteNewRevision()throws Exception{
        CountDownLatch read=new CountDownLatch(1),release=new CountDownLatch(1);String ns=cache.namespace;
        AssetHistoryCache delayed=new AssetHistoryCache(redis,json){boolean first=true;@Override List<AssetHistoryBucket> read(JdbcTemplate tx,int level,long user,long start,long end,Supplier<List<AssetHistoryBucket>> loader){return super.read(tx,level,user,start,end,()->{List<AssetHistoryBucket> rows=loader.get();if(first){first=false;read.countDown();await(release);}return rows;});}};
        delayed.enabled=true;delayed.namespace=ns;AssetEquityStore aStore=new AssetEquityStore(source,json);aStore.cache=delayed;AssetEquityHistoryService aApi=new AssetEquityHistoryService(aStore,valuation);ExecutorService pool=Executors.newSingleThreadExecutor();
        try{Future<Object> a=pool.submit(scoped(()->aApi.history(1L,"1W",new EquityValuationService.Batch(),NOW)));assertTrue(read.await(10,TimeUnit.SECONDS));long v=revision(1,1);store.locked("cache_race",s->adjust(s.db,1,1,BigDecimal.TEN));assertTrue(revision(1,1)>v);Object b=response(1,"1W");release.countDown();Object old=a.get(10,TimeUnit.SECONDS);assertNotEquals(encoded(old),encoded(b));equal(b,response(1,"1W"));record("C04","A read V="+v+" paused; writer commit V="+revision(1,1)+"; B new; A old refill; C equals B");}finally{release.countDown();pool.shutdownNow();}
    }
    static void redisContainer(String action)throws Exception{
        String name=System.getenv("CACHE_TEST_REDIS_CONTAINER");assertNotNull(name);assertTrue(name.matches("ahc-[a-f0-9]+-redis"));
        Process inspect=new ProcessBuilder("docker","inspect",name).start();JsonNode inspection=json.readTree(readAll(inspect.getInputStream()));assertEquals(0,inspect.waitFor());assertEquals("true",inspection.get(0).path("Config").path("Labels").path("asset-history-cache-fixture").asText());
        Process process=new ProcessBuilder("docker",action,name).redirectErrorStream(true).start();String output=new String(readAll(process.getInputStream()),"UTF-8");assertEquals(0,process.waitFor(),output);
    }
    static byte[] readAll(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];for(int n;(n=in.read(b))!=-1;)out.write(b,0,n);return out.toByteArray();}
    @Test void C05_committedMysqlDuringRedisOutage()throws Exception{
        Object old=response(1,"1W");long v=revision(1,1);redisContainer("pause");try{store.locked("cache_offline_commit",s->adjust(s.db,1,1,BigDecimal.TEN));assertTrue(revision(1,1)>v);assertNotEquals(encoded(old),encoded(response(1,"1W")));}finally{redisContainer("unpause");}
        cache.enabled=false;Object expected=response(1,"1W");cache.enabled=true;equal(expected,response(1,"1W"));equal(expected,response(1,"1W"));record("C05","MySQL commits with Redis paused; no after-commit DEL required; recovery reads new DB revision before TTL");
    }
    @Test void C06_manualHistoryAllLevelsAndRollback()throws Exception{
        Map<String,String> before=new TreeMap<>(),other=new TreeMap<>();for(int l=1;l<=3;l++){before.put(RANGES[l],encoded(response(1,RANGES[l])));other.put(RANGES[l],encoded(response(2,RANGES[l])));}
        ManualOrderHistory manual=new ManualOrderHistory(store);long minute=START+23*3600000+59*60000;
        long shifted=minute+120000+7*DAY;
        Object oldCarry=api.history(1L,"1W",new EquityValuationService.Batch(),shifted).get("carryIn");
        store.locked("cache_manual",s->manual.apply(s.db,1,minute,new BigDecimal("-20"),NOW,stage->{}));
        assertNotEquals(encoded(oldCarry),encoded(api.history(1L,"1W",new EquityValuationService.Batch(),shifted).get("carryIn")));
        for(int l=1;l<=3;l++){assertNotEquals(before.get(RANGES[l]),encoded(response(1,RANGES[l])));assertEquals(other.get(RANGES[l]),encoded(response(2,RANGES[l])));cache.enabled=false;Object expected=response(1,RANGES[l]);cache.enabled=true;equal(expected,response(1,RANGES[l]));}
        Object fixed=response(1,"1W");long v=revision(1,1);assertThrows(IllegalStateException.class,()->store.locked("cache_manual_fail",s->manual.apply(s.db,1,minute,BigDecimal.TEN,NOW,stage->{if(stage.equals("parent"))throw new IllegalStateException("manual rollback");})));
        equal(fixed,response(1,"1W"));assertEquals(v,revision(1,1));
        // Actual missing-minute backfill, not just an update; crosses UTC day / hour / four-hour boundary.
        store.locked("cache_manual_insert",s->manual.apply(s.db,1,START-60000,BigDecimal.ONE,NOW,stage->{}));assertEquals("MANUAL_ZERO",db.queryForObject("select origin from asset_history_1m where tenant_id=2 and user_id=1 and bucket_start=?",String.class,START-60000));
        for(int l=1;l<=3;l++){cache.enabled=false;Object expected=response(1,RANGES[l]);cache.enabled=true;equal(expected,response(1,RANGES[l]));}
        record("C06","Real ManualOrderHistory.apply: correction + missing-minute backfill across day; three levels, other user, rollback; minute carry/opening are uncached");
    }
    @Test void C07_warmCacheRecalculatesLive()throws Exception{
        Object before=response(1,"1W");long hit=cache.hits.sum(),revision=revision(1,1);db.update("update asset_account set available=available+50,row_version=row_version+1 where tenant_id=2 and user_id=1");Object after=response(1,"1W");assertEquals(revision,revision(1,1));assertTrue(cache.hits.sum()>hit);assertNotEquals(encoded(before),encoded(after));assertEquals("1050.0000000000000000",((Map<?,?>)after).get("total"));record("C07","Real committed wallet change: warm history hit, total/live changes 1000 to 1050; revision unchanged");
    }
    @Test void C08_clockBoundariesAndLateRollup()throws Exception{
        for(int level=0;level<=3;level++){long size=AssetEquityStore.INTERVALS[level],boundary=AssetHistoryBucket.floor(NOW,size)+size;for(long at:new long[]{boundary-1,boundary,boundary+1,boundary+60001}){
            cache.enabled=false;Object off=api.history(1L,RANGES[level],new EquityValuationService.Batch(),at);cache.enabled=true;equal(off,api.history(1L,RANGES[level],new EquityValuationService.Batch(),at));
            assertEquals(at,((Map<?,?>)off).get("asOf"));assertEquals(at-AssetHistoryService.rangeMillis(RANGES[level]),((Map<?,?>)off).get("from"));
        }}
        AssetHistoryBucket unfinished=new AssetHistoryBucket(1,AssetHistoryBucket.floor(NOW,3600000),AssetHistoryBucket.floor(NOW,3600000)+3600000);store.saveBucket(db,1,unfinished,NOW);assertFalse(encoded(response(1,"1W")).contains("\"bucketStart\":"+unfinished.start));record("C08","Minute/hour/four-hour/day before/on/after boundary: full equality, request from/asOf fresh, unfinished parent excluded");
    }
    @Test void C09_emptyInvalidCarryAndNoDatabaseZero()throws Exception{
        for(int l=1;l<=3;l++){assertTrue(((List<?>)response(2,RANGES[l]).get("points")).isEmpty());AssetHistoryBucket b=new AssetHistoryBucket(2,START,START+AssetEquityStore.INTERVALS[l]);b.finalized=true;store.saveBucket(db,l,b,NOW);assertFalse(((List<?>)response(2,RANGES[l]).get("points")).isEmpty());}
        assertNull(((Map<?,?>)((List<?>)response(2,"1W").get("points")).get(0)).get("value"));
        long minutes=db.queryForObject("select count(*) from asset_history_1m where tenant_id=2",Long.class);for(String range:RANGES)response(2,range);assertEquals(minutes,db.queryForObject("select count(*) from asset_history_1m where tenant_id=2",Long.class));
        long from=NOW-7*DAY;db.update("insert into asset_history_1m(tenant_id,user_id,basis_version,bucket_start,observed_at,net_equity,valuation_status,reason_code,valuation_evidence,origin,created_at) values(2,2,'net_equity_v1',?,?, -3,'COMPLETE','','{}','OBSERVED',0)",AssetHistoryBucket.floor(from-DAY,60000),from-DAY);
        assertEquals("-3.0000000000000000",((Map<?,?>)response(2,"1W").get("carryIn")).get("value"));record("C09","Empty cache replaced after first bucket; invalid values stay NULL; negative carry retained; GET creates no minute or inferred-zero rows (display checks in frontend suite)");
        db.update("insert into option_order(tenant_id,user_id,amount,direction,status,symbol,created_at,updated_at,row_version) values(2,2,0,'UP','TRADING','INVALID_FIXTURE',NOW(),NOW(),0)");
        assertNull(response(2,"1W").get("total"));assertEquals("INCOMPLETE",response(2,"1W").get("valuationStatus"));
    }
    @Test void C10_isolationCorruptionExpiryAndFreshInstance()throws Exception{
        Object expected=response(1,"1W");long size=3600000,start=AssetHistoryBucket.floor(NOW-7*DAY,size),end=AssetHistoryBucket.floor(NOW,size);String key=cache.key(1,1,start,end,revision(1,1));String valid=redis.opsForValue().get(key);assertNotNull(valid);
        for(String invalid:new String[]{"{broken",valid.replace("equity:history:v2:","equity:history:v99:"),valid.replace("1000.1234567890123456","not-money"),valid.replace("net_equity_v1","other_basis")}){redis.opsForValue().set(key,invalid);long fallbacks=cache.fallbacks.sum();equal(expected,response(1,"1W"));assertTrue(cache.fallbacks.sum()>fallbacks);}
        redis.opsForValue().set(key,valid,1,TimeUnit.MILLISECONDS);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime()<deadline)Thread.yield();assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));equal(expected,response(1,"1W"));
        AssetHistoryCache fresh=new AssetHistoryCache(redis,json);fresh.enabled=true;fresh.namespace=cache.namespace;store.cache=fresh;equal(expected,response(1,"1W"));assertTrue(fresh.hits.sum()>0);
        ProcessBuilder restart=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java.exe").toString(),"-Dcache.mysql.fixture="+System.getProperty("cache.mysql.fixture"),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),AssetHistoryCacheProcessProbe.class.getName());
        restart.environment().put("CACHE_TEST_NAMESPACE",cache.namespace);restart.redirectErrorStream(true).redirectOutput(report.resolve("fresh-process.log").toFile());Process child=restart.start();assertTrue(child.waitFor(30,TimeUnit.SECONDS));assertEquals(0,child.exitValue());
        JsonNode restarted=json.readTree(report.resolve("fresh-process.json").toFile());assertEquals(json.readTree(encoded(expected)),restarted.get("response"));assertTrue(restarted.get("hits").asLong()>0);
        for(int l=1;l<=3;l++){
            Object original=response(1,RANGES[l]);long originalRevision=revision(l,1);
            db.update("insert into "+AssetEquityStore.TABLES[l]+"(tenant_id,user_id,basis_version,bucket_start,bucket_end,open_value,high_value,low_value,close_value,open_at,high_at,low_at,close_at,source_count,valid_sample_count,invalid_sample_count,expected_sample_count,finalized,quality,source_through,updated_at) select tenant_id,user_id,'other_basis',bucket_start,bucket_end,99999,99999,99999,99999,open_at,high_at,low_at,close_at,source_count,valid_sample_count,invalid_sample_count,expected_sample_count,finalized,quality,source_through,updated_at from "+AssetEquityStore.TABLES[l]+" where tenant_id=2 and user_id=1 and basis_version='net_equity_v1'");
            assertEquals(originalRevision,revision(l,1));equal(original,response(1,RANGES[l]));assertNotEquals(cache.key(l,1,0,1,1),cache.key(l,2,0,1,1));assertTrue(((List<?>)response(2,RANGES[l]).get("points")).isEmpty());
        }
        record("C10","Two users/three levels, schema+basis tampering, broken JSON, malformed decimal, real Redis TTL expiration, fresh cache/store-independent revision access");
    }
    @Test void C11_refusedTimeoutRecoveryAndConcurrentCold()throws Exception{
        cache.enabled=false;Object expected=response(1,"1W");cache.enabled=true;int port;try(ServerSocket socket=new ServerSocket(0)){port=socket.getLocalPort();}
        RedisProperties properties=new RedisProperties();properties.setHost("127.0.0.1");properties.setPort(port);AssetHistoryRedisConfig bad=new AssetHistoryRedisConfig();
        try{StringRedisTemplate absent=bad.assetHistoryRedis(properties,200);absent.afterPropertiesSet();AssetHistoryCache disconnected=new AssetHistoryCache(absent,json);disconnected.enabled=true;store.cache=disconnected;long t=System.nanoTime();equal(expected,response(1,"1W"));assertTrue(System.nanoTime()-t<TimeUnit.SECONDS.toNanos(5));}finally{bad.close();store.cache=cache;}
        redisContainer("pause");long t=System.nanoTime();try{equal(expected,response(1,"1W"));assertTrue(System.nanoTime()-t<TimeUnit.SECONDS.toNanos(5));}finally{redisContainer("unpause");}
        long count=db.queryForObject("select count(*) from asset_history_1h where tenant_id=2",Long.class);ExecutorService pool=Executors.newFixedThreadPool(10);cache.namespace+="cold";
        try{List<Future<Object>> futures=new ArrayList<>();for(int i=0;i<10;i++)futures.add(pool.submit(scoped(()->response(1,"1W"))));for(Future<Object> future:futures)equal(expected,future.get(15,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
        equal(expected,response(1,"1W"));assertEquals(count,db.queryForObject("select count(*) from asset_history_1h where tenant_id=2",Long.class));record("C11","Real TCP refusal + Docker-paused Redis timeout <5s including JDBC; recovery equality; 10 concurrent cold requests no history writes or retries");
    }
    @Test void S01_productionSpringRedisWiringAndOffSwitch(){
        for(boolean enabled:new boolean[]{false,true}){
            try(org.springframework.context.annotation.AnnotationConfigApplicationContext context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()){
                Map<String,Object> properties=new HashMap<>();properties.put("asset.history.cache.enabled",Boolean.toString(enabled));properties.put("asset.history.cache.namespace","705:wiring");
                context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("fixture",properties));
                context.registerBean(ObjectMapper.class,()->json);
                context.registerBean(RedisProperties.class,()->{RedisProperties p=new RedisProperties();p.setHost("127.0.0.1");p.setPort(Integer.parseInt(System.getenv("CACHE_TEST_REDIS_PORT")));return p;});
                context.registerBean("redisConnectionFactory",org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class,()->new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory("127.0.0.1",Integer.parseInt(System.getenv("CACHE_TEST_REDIS_PORT"))));
                context.register(com.gtcfesk.exchange.config.RedisConfig.class,AssetHistoryRedisConfig.class,AssetHistoryCache.class);context.refresh();
                assertSame(context.getBean("stringRedisTemplate"),context.getBean(StringRedisTemplate.class));
                assertEquals(enabled,context.containsBean("assetHistoryRedis"));assertEquals(enabled,!context.getBeansOfType(AssetHistoryCache.class).isEmpty());
                if(enabled){StringRedisTemplate dedicated=(StringRedisTemplate)context.getBean("assetHistoryRedis");assertNotSame(dedicated,context.getBean(StringRedisTemplate.class));dedicated.opsForValue().set("705:wiring:probe","ok",1,TimeUnit.SECONDS);assertEquals("ok",dedicated.opsForValue().get("705:wiring:probe"));}
            }
        }record("S01","Actual @Configuration beans: primary existing Redis unchanged; qualified bounded client/cache only exist with enabled=true");
    }
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.boot.autoconfigure.domain.EntityScan("com.gtcfesk.exchange")
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(repositoryFactoryBeanClass=com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean.class,basePackages={"com.gtcfesk.exchange.repository","com.gtcfesk.exchange.admin","com.gtcfesk.exchange.control"})
    @org.springframework.context.annotation.Import({com.gtcfesk.exchange.config.SecurityConfig.class,com.gtcfesk.exchange.config.JwtFilter.class,com.gtcfesk.exchange.common.JwtUtil.class,com.gtcfesk.exchange.auth.AuthController.class,com.gtcfesk.exchange.auth.AuthService.class,com.gtcfesk.exchange.security.RegistrationSecurity.class,com.gtcfesk.exchange.security.WebsiteSecuritySettings.class,com.gtcfesk.exchange.admin.SystemConfigService.class,com.gtcfesk.exchange.service.EmailService.class,AssetHistoryController.class,com.gtcfesk.exchange.control.ControlService.class,com.gtcfesk.exchange.control.ControlMfa.class,com.gtcfesk.exchange.control.ControlAuditService.class,com.gtcfesk.exchange.control.TenantPolicyService.class,com.gtcfesk.exchange.control.TenantReadinessService.class,com.gtcfesk.exchange.control.TenantHostService.class,com.gtcfesk.exchange.control.TenantRequestFilter.class,com.gtcfesk.exchange.control.TenantDomainVerification.class,com.gtcfesk.exchange.control.BackendLoginRegistry.class,com.gtcfesk.exchange.tenant.TenantSecrets.class,com.gtcfesk.exchange.security.OutboundEndpointPolicy.class,com.gtcfesk.exchange.tenant.TenantJobRunner.class,com.gtcfesk.exchange.control.OperationalIssueService.class,com.gtcfesk.exchange.tenant.SchemaPackageGuard.class})
    static class HttpFixture {
        @org.springframework.context.annotation.Bean javax.sql.DataSource dataSource(){return source;}
        @org.springframework.context.annotation.Bean JdbcTemplate jdbc(){return db;}
        @org.springframework.context.annotation.Bean AssetEquityHistoryService equity(){return api;}
        @org.springframework.context.annotation.Bean AssetHistoryService history(){return new AssetHistoryService(db);}
        @org.springframework.context.annotation.Bean StringRedisTemplate redisTemplate(){return redis;}
        @org.springframework.context.annotation.Bean org.springframework.security.crypto.password.PasswordEncoder encoder(){return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();}
    }
    @Test void C12_browserAndAuthenticatedHttp()throws Exception{
        db.update("update tenant set frontend_host='127.0.0.1',domain_verified=1 where id=2"); // Preverified synthetic loopback route only; this is not TLS/domain acceptance.
        db.update("insert into user_account(tenant_id,id,email,password_hash,status,row_version) values(2,1,'cache@fixture.invalid',?,'normal',0) on duplicate key update email=values(email),password_hash=values(password_hash)",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("cache-fixture-only"));
        org.springframework.boot.SpringApplication application=new org.springframework.boot.SpringApplication(HttpFixture.class);
        try(org.springframework.context.ConfigurableApplicationContext context=application.run(
                "--spring.config.location=optional:classpath:/asset-cache-empty.yml","--server.address=127.0.0.1","--server.port=0",
                "--spring.jpa.hibernate.ddl-auto=none","--spring.jpa.show-sql=false","--spring.jpa.open-in-view=false",
                "--spring.jpa.database-platform=org.hibernate.dialect.MySQL57Dialect","--logging.level.root=WARN",
                "--platform.base-domain=mt705.test","--platform.admin-origin=https://admin.mt705.test","--platform.control-origin=https://control.mt705.test",
                "--jwt.secret=Y2FjaGUtZml4dHVyZS1vbmx5LXNpZ25pbmcta2V5LW5vdC1mb3ItcHJvZHVjdGlvbg==","--jwt.expireSeconds=3600","--asset.history.equity.read-enabled=true")){
            int port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)context).getWebServer().getPort();
            org.springframework.web.client.RestTemplate http=new org.springframework.web.client.RestTemplate();String base="http://127.0.0.1:"+port;
            Map<String,String> login=new HashMap<>();login.put("account","cache@fixture.invalid");login.put("password","cache-fixture-only");
            Map<?,?> signed=http.postForObject(base+"/api/auth/login",login,Map.class);assertNotNull(signed.get("token"));
            org.springframework.http.HttpHeaders headers=new org.springframework.http.HttpHeaders();headers.setBearerAuth(signed.get("token").toString());
            List<Map<String,Object>> measurements=new ArrayList<>();
            for(String mode:new String[]{"off","cold","warm"}){
                cache.enabled=!mode.equals("off");cache.namespace="705:http:"+UUID.randomUUID();http.exchange(base+"/api/user/asset-history?range=1W",org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(headers),String.class);
                source.reset();long commands=cache.commands.sum();List<Long> times=new ArrayList<>();
                for(int i=0;i<100;i++){if(mode.equals("cold"))cache.namespace="705:http:"+UUID.randomUUID();long t=System.nanoTime();org.springframework.http.ResponseEntity<String> r=http.exchange(base+"/api/user/asset-history?range=1W",org.springframework.http.HttpMethod.GET,new org.springframework.http.HttpEntity<>(headers),String.class);times.add(System.nanoTime()-t);assertEquals(200,r.getStatusCodeValue());assertEquals("1000.0000000000000000",json.readTree(r.getBody()).path("total").asText());}
                Collections.sort(times);Map<String,Object> row=new LinkedHashMap<>();row.put("mode",mode);row.put("samples",100);row.put("http_p50_ms",times.get(49)/1e6);row.put("http_p95_ms",times.get(94)/1e6);row.put("sql_total_by_category",new TreeMap<>(source.counts));row.put("redis_commands",cache.commands.sum()-commands);row.put("fixture","2880 minutes, real login/JWT; variable wall clock, cash-only; separate from fixed-service equality");measurements.add(row);
            }
            json.writerWithDefaultPrettyPrinter().writeValue(report.resolve("performance-http.json").toFile(),measurements);
            String challenge=UUID.randomUUID().toString();Map<String,Object> request=new LinkedHashMap<>();request.put("api",base);request.put("challenge",challenge);request.put("requiredBrowserDriver","cua_repl");json.writeValue(report.resolve("browser-request.json").toFile(),request);
            String browserProof=System.getProperty("cache.browser.proof");assertNotNull(browserProof,"Real current-server browser receipt required through available UI tool; not a skipped case");
            Path receipt=Paths.get(browserProof);long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(6);while(!Files.isRegularFile(receipt)&&System.nanoTime()<deadline)Thread.sleep(250);assertTrue(Files.isRegularFile(receipt),"Current-server UI evidence missing; mandatory C12 fails");
            JsonNode verified=json.readTree(receipt.toFile());assertEquals("PASS",verified.path("result").asText());assertEquals(base,verified.path("api").asText());assertEquals(challenge,verified.path("challenge").asText());
            for(String check:new String[]{"enter-default-1W","idle-60s-no-fetch","focus-visibility-resize-privacy-locale-canvas-keyboard","same-period-no-fetch","period-1M-fetch","refresh-inflight-dedup","reenter-once-1W","late-response-cannot-replace-latest","no-page-errors","screenshot"})assertTrue(verified.path("checks").path(check).asBoolean(),check);

        }
        record("C12","Real production AuthService login and JWT filter, loopback Tomcat, actual Vue component in Chromium; browser-results.json and performance-http.json");
    }
    @Test void P01_performance()throws Exception{
        if(!"true".equals(System.getenv("CACHE_TEST_PERFORMANCE"))){record("P01","Performance not requested");return;}
        try(Connection c=physical.getConnection()){
            c.createStatement().execute("SET @fixture_rows=100000");org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,new org.springframework.core.io.ClassPathResource("asset-cache-performance.sql"));
        }
        // Full representative parent windows without manufacturing minute observations.
        store.locked("cache_perf_history",s->{for(int l=1;l<=3;l++){long size=AssetEquityStore.INTERVALS[l];long end=AssetHistoryBucket.floor(NOW,size);long start=AssetHistoryBucket.floor(NOW-AssetHistoryService.rangeMillis(RANGES[l]),size);
            for(long at=start;at<end;at+=size){AssetHistoryBucket b=new AssetHistoryBucket(1,at,at+size);b.open=b.high=b.low=b.close=new BigDecimal("1000.1234567890123456");b.openAt=b.highAt=b.lowAt=b.closeAt=at+5000;b.valid=b.expected;b.sourceCount=l==1?60: l==2?4:6;b.through=at+5000;b.finalized=true;store.saveBucket(s.db,l,b,NOW);}}});
        List<Map<String,Object>> results=new ArrayList<>();
        for(String range:RANGES)for(String mode:new String[]{"off","cold","warm"}){
            cache.enabled=!mode.equals("off");cache.namespace="705:perf:"+UUID.randomUUID();Object expected=response(1,range);source.reset();long commands=cache.commands.sum(),hits=cache.hits.sum(),fallbacks=cache.fallbacks.sum();List<Long> times=new ArrayList<>();long bytes=0;
            for(int i=0;i<100;i++){if(mode.equals("cold"))cache.namespace="705:perf:"+UUID.randomUUID();long begin=System.nanoTime();Object result=response(1,range);times.add(System.nanoTime()-begin);equal(expected,result);bytes=encoded(result).getBytes("UTF-8").length;}
            Collections.sort(times);Map<String,Object> row=new LinkedHashMap<>();row.put("range",range);row.put("mode",mode);row.put("samples",100);row.put("service_p50_ms",times.get(49)/1e6);row.put("service_p95_ms",times.get(94)/1e6);row.put("jdbc_execute_total_ms",source.nanos/1e6);row.put("sql_total_by_category",new TreeMap<>(source.counts));row.put("auth_sql",0);row.put("redis_commands",cache.commands.sum()-commands);row.put("hits",cache.hits.sum()-hits);row.put("fallbacks",cache.fallbacks.sum()-fallbacks);row.put("payload_bytes",bytes);results.add(row);
            if(mode.equals("warm")&&!range.equals("1D"))assertNull(source.counts.get("parent-body"));
        }
        json.writerWithDefaultPrettyPrinter().writeValue(report.resolve("performance-service.json").toFile(),results);record("P01","100 serialized service requests per mode/range. Not HTTP latency. Auth SQL=0 because service boundary.");
        cache.enabled=true;cache.namespace="705:perf-fault:"+UUID.randomUUID();Object expected=response(1,"1W");source.reset();long commands=cache.commands.sum(),fallbacks=cache.fallbacks.sum();List<Long> times=new ArrayList<>();
        redisContainer("pause");try{for(int i=0;i<100;i++){long begin=System.nanoTime();equal(expected,response(1,"1W"));times.add(System.nanoTime()-begin);}}finally{redisContainer("unpause");}
        Collections.sort(times);Map<String,Object> fault=new LinkedHashMap<>();fault.put("mode","Redis paused timeout fallback");fault.put("range","1W");fault.put("samples",100);fault.put("service_p50_ms",times.get(49)/1e6);fault.put("service_p95_ms",times.get(94)/1e6);fault.put("sql_total_by_category",new TreeMap<>(source.counts));fault.put("redis_commands",cache.commands.sum()-commands);fault.put("fallbacks",cache.fallbacks.sum()-fallbacks);fault.put("jdbc_execute_total_ms",source.nanos/1e6);json.writerWithDefaultPrettyPrinter().writeValue(report.resolve("performance-fault.json").toFile(),fault);
    }
}
