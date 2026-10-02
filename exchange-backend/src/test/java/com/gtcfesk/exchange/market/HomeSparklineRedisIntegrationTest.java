package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(HomeSparklineFixture.class)
class HomeSparklineRedisIntegrationTest {
    static {TimeZone.setDefault(TimeZone.getTimeZone("UTC"));((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);}
    @Autowired HomeSparklineCache cache; @Autowired StringRedisTemplate redis;
    @Autowired TradingSymbolRepository symbols; @Autowired HomeSparklineFixture.Source source;
    @Autowired HomeSparklineCacheTest.Time clock; @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
    long tenant,otherTenant;
    @BeforeEach void seed() {
        tenant=Math.abs(UUID.randomUUID().getMostSignificantBits()%1000000)+70000000;otherTenant=tenant+1000001;
        clock.ms.set(java.time.Instant.parse("2026-09-30T00:00:00Z").toEpochMilli());
        jdbc.update("INSERT INTO tenant(id) VALUES(?)",tenant);jdbc.update("INSERT INTO tenant(id) VALUES(?)",otherTenant);
        in(tenant,()->{insert("EURUSD","EURUSD=X");stage(1.1,false);return null;});
        in(otherTenant,()->{insert("EURUSD","EURUSD=X");stage(8.1,false);return null;});
    }
    void insert(String code,String external) {
        new TransactionTemplate(manager).execute(tx->{
            TradingSymbol s=new TradingSymbol();s.setSymbol(code);s.setName("Synthetic "+code);s.setBaseCurrency("EUR");s.setQuoteCurrency("USD");s.setCategory("Forex");s.setSourceCategory("Forex");s.setMarketSource("yahoo");s.setAlltickSymbol(external);s.setIsHot(true);symbols.saveAndFlush(s);return null;
        });
    }
    void stage(double base,boolean empty){source.stage(symbols.findByTenantIdAndIsEnabledTrueOrderBySortOrderDesc(TenantContext.requireTenantId()),base,empty);}
    <T> T in(long tenant,java.util.function.Supplier<T> action){try(TenantContext.Scope ignored=TenantContext.open(tenant)){return action.get();}}
    Map<String,Object> read(HomeSparklineCache c,long t){return in(t,()->c.read(Collections.singletonList("EURUSD")));}
    @AfterEach void clean(){TenantContext.clear();}
    @Test void realRedisSqlZero299300MultiInstanceConcurrentAndRestart()throws Exception {
        assertEquals("empty",read(cache,tenant).get("status"));int before=source.calculations.get();
        HomeSparklineCache second=new HomeSparklineCache(redis,symbols,source.quotes,false,clock);
        ExecutorService workers=Executors.newFixedThreadPool(12);
        try{
            List<Future<?>> work=new ArrayList<>();
            for(int i=0;i<32;i++){HomeSparklineCache c=i%2==0?cache:second;work.add(workers.submit(()->in(tenant,()->c.refresh())));}
            for(Future<?> f:work)f.get(20,TimeUnit.SECONDS);
        }finally{workers.shutdownNow();}
        assertEquals(before+1,source.calculations.get(),"One calculation across two instances in same Redis bucket");
        Map<String,Object> first=read(cache,tenant);assertEquals("fresh",first.get("status"));long updated=((Number)first.get("updatedAt")).longValue();
        clock.advance(299);
        for(int i=0;i<20;i++){assertEquals(updated,read(second,tenant).get("updatedAt"));assertFalse(in(tenant,()->cache.refresh()));}
        assertEquals(before+1,source.calculations.get());
        clock.advance(1);assertEquals("stale",read(cache,tenant).get("status"));assertEquals(updated,read(cache,tenant).get("updatedAt"));
        in(tenant,()->{stage(2.1,false);return cache.refresh();});assertEquals(updated+300000,read(cache,tenant).get("updatedAt"));
        assertEquals(before+2,source.calculations.get());assertEquals(2.1,points(read(cache,tenant)).get(0));
        docker("restart");awaitRedis();
        HomeSparklineCache restarted=new HomeSparklineCache(redis,symbols,source.quotes,false,clock);
        assertEquals("fresh",read(restarted,tenant).get("status"));assertFalse(in(tenant,()->restarted.refresh()));
        assertEquals(before+2,source.calculations.get());
        System.out.println("T05_REAL_REDIS boundary=0,299,300 calculations=1/2; 32 concurrent refreshes; app+Redis restart persisted");
    }
    @Test void realRedisFailureColdAndWarmNeverCalculateAndRecoveryWaitsNextBucket()throws Exception {
        in(tenant,()->cache.refresh());Map<String,Object> before=read(cache,tenant);int count=source.calculations.get();
        docker("stop");
        try {
            assertEquals("stale",read(cache,tenant).get("status"));assertEquals("redis_unavailable",read(cache,tenant).get("reason"));
            assertEquals(before.get("updatedAt"),read(cache,tenant).get("updatedAt"));
            HomeSparklineCache cold=new HomeSparklineCache(redis,symbols,source.quotes,false,clock);
            assertEquals("empty",read(cold,tenant).get("status"));
            clock.advance(300);assertFalse(in(tenant,()->cache.refresh()));assertEquals(count,source.calculations.get());
        } finally {docker("start");awaitRedis();}
        assertFalse(in(tenant,()->cache.refresh()),"No failure retry burst in same bucket");assertEquals(count,source.calculations.get());
        clock.advance(300);assertTrue(in(tenant,()->cache.refresh()));assertEquals(count+1,source.calculations.get());
        System.out.println("T05_REAL_REDIS stop/start warm=stale cold=empty; no read calculation; no same-bucket recovery burst");
    }
    @Test void realRedisTenantRealDemoSourceUnknownEmptyAndMiss() {
        int before=source.calculations.get();in(tenant,()->cache.refresh());assertEquals("empty",read(cache,otherTenant).get("status"));
        in(otherTenant,()->cache.refresh());assertEquals(1.1,points(read(cache,tenant)).get(0));assertEquals(8.1,points(read(cache,otherTenant)).get(0));
        HomeSparklineCache demo=new HomeSparklineCache(redis,symbols,source.quotes,true,clock);
        assertEquals("empty",read(demo,tenant).get("status"));in(tenant,()->{stage(9.1,false);return demo.refresh();});
        assertEquals("DEMO",read(demo,tenant).get("mode"));assertEquals(9.1,points(read(demo,tenant)).get(0));assertEquals(1.1,points(read(cache,tenant)).get(0));
        in(tenant,()->{new TransactionTemplate(manager).execute(tx->{TradingSymbol s=symbols.findByTenantIdAndSymbol(tenant,"EURUSD").get();s.setAlltickSymbol("JPY=X");symbols.saveAndFlush(s);return null;});return null;});
        assertEquals("empty",read(cache,tenant).get("status"));
        assertThrows(ResponseStatusException.class,()->in(tenant,()->cache.read(Collections.singletonList("tenant:"+otherTenant+":secret"))));
        in(tenant,()->{stage(0,true);return cache.refresh();});assertEquals("empty",read(cache,tenant).get("status"));assertTrue(points(read(cache,tenant)).isEmpty());
        assertNull(HomeSparklineCacheTest.row(read(cache,tenant)).get("updatedAt"));
        Set<String> keys=redis.keys("tenant:"+tenant+":market:home-sparkline:v1:*");assertTrue(keys.stream().anyMatch(k->k.contains(":REAL:")));assertTrue(keys.stream().anyMatch(k->k.contains(":DEMO:")));
        int generated=source.calculations.get();for(int i=0;i<10;i++)read(cache,tenant);assertEquals(generated,source.calculations.get());
        for(String key:keys)if(!key.contains(":attempt:"))redis.delete(key);
        assertEquals("cache_miss",read(cache,tenant).get("reason"));assertEquals(generated,source.calculations.get());assertTrue(generated>before);
        System.out.println("T05_REAL_REDIS tenant/REAL-DEMO/source isolated; unknown rejected; empty/miss no calculation");
    }
    @SuppressWarnings("unchecked") List<Double> points(Map<String,Object> response){return (List<Double>)HomeSparklineCacheTest.row(response).get("points");}
    static void docker(String action)throws Exception {
        HomeSparklineFixture.requireStack();
        String name=System.getenv("T05_REDIS_CONTAINER");
        if(name==null || !name.startsWith("moddoc-t05-"))throw new IllegalStateException("T05 owned Redis only");
        Process inspect=new ProcessBuilder("docker","inspect",name).start();
        JsonNode details=new ObjectMapper().readTree(readAll(inspect.getInputStream()));assertEquals(0,inspect.waitFor());
        assertEquals("T05",details.get(0).path("Config").path("Labels").path("moddoc.task").asText());
        Process p=new ProcessBuilder("docker",action,name).redirectErrorStream(true).start();String out=new String(readAll(p.getInputStream()),StandardCharsets.UTF_8);
        assertTrue(p.waitFor(45,TimeUnit.SECONDS));assertEquals(0,p.exitValue(),out);
    }
    static byte[] readAll(java.io.InputStream stream)throws java.io.IOException {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=stream.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();
    }
    void awaitRedis()throws Exception {
        for(int i=0;i<50;i++){try{if("PONG".equals(redis.getConnectionFactory().getConnection().ping()))return;}catch(RuntimeException ignored){}Thread.sleep(200);}
        fail("Owned Redis did not recover");
    }
}
