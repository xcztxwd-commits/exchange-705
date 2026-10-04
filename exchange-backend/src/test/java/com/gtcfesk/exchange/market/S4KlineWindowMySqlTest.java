package com.gtcfesk.exchange.market;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Small real-MySQL growth probe; deliberately not the ten-million-row capacity gate. */
class S4KlineWindowMySqlTest extends TenantMarketTestContext {
    private static final long START=1700000400000L;
    private final List<Map<String,Object>> evidence=new ArrayList<>();
    private final long symbol=400_000_000L+new java.security.SecureRandom().nextInt(100_000_000);

    @Test void unrelatedHistoryGrowthDoesNotScaleSmallWindowWork() throws Exception {
        assumeTrue(System.getenv("S4_HISTORY_JDBC")!=null,"Dedicated owned MySQL fixture not configured");
        S4KlineDifferentialTest.ObservedDataSource source=new S4KlineDifferentialTest.ObservedDataSource();
        JdbcTemplate db=new JdbcTemplate(source);
        MarketSqlFixture.schema(db);
        // Separate synthetic symbol: append cohorts without removing any prior history or changing recent results.
        db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,1)",symbol);
        ControlHistoryStore store=new ControlHistoryStore(db,new DataSourceTransactionManager(source));
        S4KlineDifferentialTest.LegacyHistoryStore legacy=new S4KlineDifferentialTest.LegacyHistoryStore(db,new DataSourceTransactionManager(source));
        ControlledKlineMerger current=new ControlledKlineMerger(store);
        LegacyS4KlineReader before=new LegacyS4KlineReader(legacy);
        for(int i=0;i<100;i++) insert(store,START+i*60000L,i);
        // Nine-year sparse history remains available; it is never expired or deleted for this probe.
        long sparse=START-9*365L*86400000;
        insert(store,sparse,9);
        int inserted=0;
        Map<String,Object> fixed=null;
        List<Long> smallReads=new ArrayList<>();
        try {
            for(int total:new int[]{100,1000,10000}) {
                List<Object[]> batch=new ArrayList<>();
                for(int i=inserted;i<total;i++) {
                    long at=START-(20000L-i)*60000L;
                    batch.add(new Object[]{symbol,at,store.encode(bar(at,i)),at+59000});
                    if(batch.size()==500) { writeBatch(db,batch);batch.clear(); }
                }
                if(!batch.isEmpty())writeBatch(db,batch);
                inserted=total;
                long actualRows=db.queryForObject("SELECT COUNT(*) FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol);
                assertEquals(total+101,actualRows);
                Map<String,Object> first=compare(source,legacy,before,current,"1m",10,START+99*60000L,actualRows,"first observed warm call after cohort append");
                if(fixed==null)fixed=first;else assertEquals(fixed,first,"Unrelated older history cannot change the recent window");
                smallReads.add(readTotal((Map<?,?>)evidence.get(evidence.size()-1).get("new_metrics")));
                compare(source,legacy,before,current,"1m",10,START+99*60000L,actualRows,"repeat warm call");
                compare(source,legacy,before,current,"1m",100,START+99*60000L,actualRows,"large window warm call");
                compare(source,legacy,before,current,"5m",7,START-10000*60000L,actualRows,"deep cursor warm call");
                Map<String,Object> oldPage=compare(source,legacy,before,current,"1m",1,sparse,actualRows,"nine-year sparse occupied bucket");
                assertEquals(sparse,ControlHistoryStore.time(ControlHistoryStore.rows(oldPage).get(0)));
            }
            // Directory seek may fill one 500-row page as cohorts grow, but must not follow total history.
            assertTrue(smallReads.get(2)<=smallReads.get(0)+2000,"Small-window handlers grew with unrelated history: "+smallReads);
            Map<String,Object> largest=evidence.get(10);
            assertTrue(readTotal((Map<?,?>)largest.get("old_metrics"))>readTotal((Map<?,?>)largest.get("new_metrics"))*2,
                "Large-cohort old full-history directory must cost more than the bounded new window");
        } finally {
            Path directory=Paths.get(System.getProperty("s4.evidence.dir","target/s4-differential")).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path target=directory.resolve("mysql-window-growth-"+UUID.randomUUID()+".json"),temporary=Paths.get(target+".tmp");
            Files.write(temporary,new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence).getBytes(StandardCharsets.UTF_8));
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
        }
    }
    private static void writeBatch(JdbcTemplate db,List<Object[]> batch) {
        db.batchUpdate("INSERT INTO market_mixed_minute(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,?,?,?,?)",batch);
    }
    private static Map<String,Object> bar(long at,int index) {
        BigDecimal price=new BigDecimal("12.1234567890123456").add(BigDecimal.valueOf(index));
        Map<String,Object> row=new LinkedHashMap<>();row.put("timestamp",at);row.put("open_price",price);row.put("close_price",price);
        row.put("high_price",price.add(BigDecimal.ONE));row.put("low_price",price.subtract(BigDecimal.ONE));row.put("volume",0);
        row.put("partial",true);row.put("controlled",true);return row;
    }
    private void insert(ControlHistoryStore store,long at,int index) {
        store.db.update("INSERT INTO market_mixed_minute(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,?,?,?,?)",symbol,at,store.encode(bar(at,index)),at+59000);
    }
    private Map<String,Object> compare(S4KlineDifferentialTest.ObservedDataSource source,
            S4KlineDifferentialTest.LegacyHistoryStore legacy,LegacyS4KlineReader before,ControlledKlineMerger current,
            String period,int limit,long cursor,long total,String temperature) {
        Map<String,Object> data=new LinkedHashMap<>();data.put("kline_list",Collections.emptyList());data.put("code","TEST");
        Map<String,Object> input=new LinkedHashMap<>();input.put("ret",503);input.put("data",data);
        source.calls.clear();source.record=true;
        Map<String,Object> expected;
        try { expected=source.snapshot(()->legacy.snapshot(()->before.merge(symbol,period,limit,cursor,input,ignored->{},true))); }
        finally { source.record=false; }
        List<Map<String,Object>> oldSql=new ArrayList<>(source.calls);
        Map<String,Object> oldMetrics=new LinkedHashMap<>(source.lastMetrics);
        source.calls.clear();source.record=true;
        Map<String,Object> actual;
        try { actual=source.snapshot(()->current.merge(symbol,period,limit,cursor,input,ignored->{fail("Read enqueued state repair");},true)); }
        finally { source.record=false; }
        Map<String,Object> item=new LinkedHashMap<>();item.put("symbol_id",symbol);item.put("history_rows",total);item.put("period",period);item.put("limit",limit);item.put("cursor",cursor);
        item.put("temperature",temperature);item.put("qualification","Small synthetic MySQL5.7 fixture, warm inserts; old read first and new shadow read second. No restored snapshot or disk-cold run. Not a ten-million-row capacity or real GET/WS result. All handlers from the same pinned physical connection; method timing excludes connection identity setup and includes final metric observation. Live heap is whole-JVM noisy observation, not retained-memory proof.");
        item.put("old_queries",oldSql.size());item.put("new_queries",source.calls.size());item.put("old_sql",oldSql);item.put("new_sql",new ArrayList<>(source.calls));
        item.put("old_metrics",oldMetrics);item.put("new_metrics",new LinkedHashMap<>(source.lastMetrics));
        item.put("expected",expected);item.put("actual",actual);item.put("field_equal",expected.equals(actual));item.put("returned_rows",ControlHistoryStore.rows(actual).size());evidence.add(item);
        assertEquals(expected,actual,"MySQL growth differential "+total+" "+period+" "+limit+" "+temperature);
        for(Map<String,Object> sql:source.calls) {
            String text=sql.get("sql").toString().toUpperCase(Locale.ROOT);
            assertTrue(text.startsWith("SELECT"));assertFalse(text.contains("FOR UPDATE")||text.contains("LOCK IN SHARE MODE")||text.contains("GROUP BY"));
        }
        return actual;
    }
    private static long readTotal(Map<?,?> metrics) {
        Map<?,?> values=(Map<?,?>)metrics.get("handler_read_delta");long total=0;
        for(Object value:values.values())total+=((Number)value).longValue();return total;
    }
}
