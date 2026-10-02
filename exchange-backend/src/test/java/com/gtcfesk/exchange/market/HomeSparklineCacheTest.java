package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HomeSparklineCacheTest {
    static class Time extends Clock {
        final AtomicLong ms=new AtomicLong(Instant.parse("2026-09-30T00:00:00Z").toEpochMilli());
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(ms.get());} public long millis(){return ms.get();}
        void advance(long seconds){ms.addAndGet(seconds*1000);}
    }
    final Time time=new Time();
    StringRedisTemplate redis; ValueOperations<String,String> values;
    TradingSymbolRepository symbols; ForexQuoteMarketService quotes; HomeSparklineCache cache;
    final Map<String,String> storage=new ConcurrentHashMap<>();
    TradingSymbol symbol;
    @SuppressWarnings("unchecked") @BeforeEach void setup() {
        redis=mock(StringRedisTemplate.class); values=mock(ValueOperations.class); symbols=mock(TradingSymbolRepository.class); quotes=mock(ForexQuoteMarketService.class);
        symbol=new TradingSymbol(); symbol.setSymbol("EURUSD");symbol.setMarketSource("yahoo");symbol.setSourceCategory("Forex");symbol.setAlltickSymbol("EURUSD=X");
        when(symbols.findByTenantIdAndIsEnabledTrueOrderBySortOrderDesc(anyLong())).thenReturn(Collections.singletonList(symbol));
        when(redis.opsForValue()).thenReturn(values); when(values.get(anyString())).thenAnswer(i->storage.get(i.getArgument(0)));
        when(values.setIfAbsent(anyString(),anyString(),anyLong(),eq(TimeUnit.SECONDS))).thenAnswer(i->storage.putIfAbsent(i.getArgument(0),i.getArgument(1))==null);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(),any(),any(),any())).thenAnswer(i->{
            List<String> keys=i.getArgument(1); Object[] args=Arrays.copyOfRange(i.getArguments(),2,6);
            if(!Objects.equals(storage.get(keys.get(0)),args[0]))return 0L;
            storage.put(keys.get(1),String.valueOf(args[1]));return 1L;
        });
        when(quotes.internalKline(anyString(),eq("5m"),eq(20))).thenReturn(result(1,2,3));
        cache=new HomeSparklineCache(redis,symbols,quotes,false,time);
    }
    static Map<String,Object> result(double... points){
        List<Map<String,Object>> rows=new ArrayList<>();for(double p:points)rows.add(Collections.singletonMap("close_price",p));
        Map<String,Object> data=new HashMap<>();data.put("status","available");data.put("kline_list",rows);
        return Collections.singletonMap("data",data);
    }
    <T> T in(long id,java.util.function.Supplier<T> f){try(TenantContext.Scope ignored=TenantContext.open(id)){return f.get();}}
    Map<String,Object> read(){return in(51L,()->cache.read(Collections.singletonList("EURUSD")));}
    @SuppressWarnings("unchecked") static Map<String,Object> row(Map<String,Object> data){return ((List<Map<String,Object>>)data.get("items")).get(0);}
    @Test void zero299300AndConcurrentReadsNeverCalculate()throws Exception {
        assertEquals("empty",read().get("status")); verifyNoInteractions(quotes);
        assertTrue(in(51,()->cache.refresh())); long first=time.millis();
        assertEquals("fresh",read().get("status"));assertEquals(first,row(read()).get("updatedAt"));
        time.advance(299);
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try {List<Future<?>> tasks=new ArrayList<>();for(int i=0;i<40;i++)tasks.add(pool.submit(()->{assertEquals("fresh",read().get("status"));assertFalse(in(51,()->cache.refresh()));}));for(Future<?> f:tasks)f.get(10,TimeUnit.SECONDS);}
        finally{pool.shutdownNow();}
        verify(quotes,times(1)).internalKline("EURUSD","5m",20);
        time.advance(1);assertEquals("stale",read().get("status"));assertEquals(first,row(read()).get("updatedAt"));
        assertTrue(in(51,()->cache.refresh()));assertEquals(first+300000,row(read()).get("updatedAt"));
        verify(quotes,times(2)).internalKline("EURUSD","5m",20);
    }
    @Test void missesFailuresAndCorruptRedisDoNotGenerate() {
        in(51,()->cache.refresh());long at=time.millis();when(values.get(anyString())).thenThrow(new IllegalStateException("fixture redis offline"));
        for(int i=0;i<5;i++){assertEquals("stale",read().get("status"));assertEquals("redis_unavailable",read().get("reason"));assertEquals(at,row(read()).get("updatedAt"));}
        HomeSparklineCache cold=new HomeSparklineCache(redis,symbols,quotes,false,time);
        assertEquals("empty",in(51,()->cold.read(Collections.singletonList("EURUSD"))).get("status"));
        verify(quotes,times(1)).internalKline(anyString(),anyString(),anyInt());
        doReturn("{broken").when(values).get(anyString());assertEquals("stale",read().get("status"));
    }
    @Test void emptySourcePreservesOldTimestampWithoutInventingPoints() {
        in(51,()->cache.refresh());long at=time.millis();time.advance(300);when(quotes.internalKline(anyString(),anyString(),anyInt())).thenReturn(result(0,-1,Double.NaN));
        assertTrue(in(51,()->cache.refresh()));assertEquals("stale",row(read()).get("status"));assertEquals(at,row(read()).get("updatedAt"));
        storage.clear();HomeSparklineCache cold=new HomeSparklineCache(redis,symbols,quotes,false,time);in(51,()->cold.refresh());
        Map<String,Object> r=in(51,()->cold.read(Collections.singletonList("EURUSD")));assertEquals("empty",r.get("status"));assertEquals(Collections.emptyList(),row(r).get("points"));assertNull(row(r).get("updatedAt"));
    }
    @Test void staleRowsExpireDespiteRepeatedEmptyRefreshes() {
        long initial=time.millis();in(51,()->cache.refresh());
        when(quotes.internalKline(anyString(),anyString(),anyInt())).thenReturn(result());
        time.advance(300);in(51,()->cache.refresh());assertEquals(initial,row(read()).get("updatedAt"));
        time.ms.set(initial+HomeSparklineCache.RETAIN_MS);in(51,()->cache.refresh());
        assertEquals("empty",read().get("status"));assertNull(row(read()).get("updatedAt"));
    }
    @Test void tenantModeSourceAndUnknownSymbolsAreBounded() {
        in(51,()->cache.refresh());assertEquals("empty",in(52,()->cache.read(Collections.singletonList("EURUSD"))).get("status"));
        HomeSparklineCache demo=new HomeSparklineCache(redis,symbols,quotes,true,time);assertEquals("empty",in(51,()->demo.read(Collections.singletonList("EURUSD"))).get("status"));
        assertEquals("DEMO",in(51,()->demo.read(Collections.singletonList("EURUSD"))).get("mode"));
        symbol.setAlltickSymbol("JPY=X");assertEquals("empty",read().get("status"));
        assertThrows(ResponseStatusException.class,()->in(51,()->cache.read(Collections.singletonList("tenant:52:secret"))));
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->cache.read(Collections.singletonList("EURUSD")));
        verify(quotes,times(1)).internalKline(anyString(),anyString(),anyInt());
        HomeSparklineController controller=new HomeSparklineController(cache);
        assertEquals(400,controller.read(Collections.singletonMap("symbols",Collections.emptyList())).getStatusCodeValue());
        assertEquals(400,controller.read(Collections.singletonMap("symbols",Arrays.asList(1,2))).getStatusCodeValue());
    }
    @Test void jobUsesFiveMinuteUtcScheduleAndTenantRunner()throws Exception {
        org.springframework.scheduling.annotation.Scheduled s=HomeSparklineRefreshJob.class.getMethod("refresh").getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertEquals("0 */5 * * * *",s.cron());assertEquals("UTC",s.zone());
        TenantJobRunner jobs=mock(TenantJobRunner.class);new HomeSparklineRefreshJob(jobs,cache).refresh();
        verify(jobs).each(eq("home-sparkline"),any());
    }
}
