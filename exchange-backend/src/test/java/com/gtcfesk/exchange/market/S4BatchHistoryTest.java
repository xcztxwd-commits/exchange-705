package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline SQL-shape and page-bound tests. This is not real MySQL capacity evidence. */
class S4BatchHistoryTest extends TenantMarketTestContext {
    RecordingJdbc db;
    ControlHistoryStore store;
    final long minute = 1700000400000L;
    @BeforeEach void setup() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:s4batch_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        db = new RecordingJdbc(source); MarketSqlFixture.schema(db);
        store = new ControlHistoryStore(db, new DataSourceTransactionManager(source));
    }
    Map<String,Object> bar(long at, String price) {
        Map<String,Object> row = new LinkedHashMap<>(); row.put("timestamp", at);
        for (String field : Arrays.asList("open_price", "high_price", "low_price", "close_price")) row.put(field, new BigDecimal(price));
        row.put("volume", 7); return row;
    }
    void task(long tenant, long symbol, String id, long start, long end, boolean modern) {
        db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,sampled_until,ended_at) VALUES(?,?,?,'TEST',1,'TARGET','DONE',10,10,10,1,false,16,'SOURCE',?,?,?,?,?)",
            tenant, id, symbol, start, start, end, end, end);
        if (modern) db.update("INSERT INTO market_control_flow(tenant_id,task_id,options_json,state,last_price,last_at,finished_at) VALUES(?,?,'{}','SOURCE',10,?,?)", tenant, id, end, end);
    }
    @Test void candleAndMixedPagesRetainAllRowsAndBoundEveryJdbcPage() {
        List<Object[]> candles = new ArrayList<>(), mixed = new ArrayList<>();
        for (int i=0; i<1501; i++) {
            long at = minute + i*60000L; String body = store.encode(bar(at,"10.1234567890123456"));
            candles.add(new Object[]{1L, 1L, "1m", at, body, at+60000}); mixed.add(new Object[]{1L, 1L, at, body, at});
        }
        db.batchUpdate("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES(?,?,?,?,?,?)", candles);
        db.batchUpdate("INSERT INTO market_mixed_minute(tenant_id,symbol_id,minute_at,body,last_event) VALUES(?,?,?,?,?)", mixed);
        // A different owner/symbol and outside window must not leak into either reader.
        db.update("INSERT INTO market_source_candle VALUES(2,1,'1m',?,?,?)", minute, store.encode(bar(minute,"999")), minute);
        db.update("INSERT INTO market_mixed_minute VALUES(1,2,?,?,?)", minute, store.encode(bar(minute,"999")), minute);
        db.clear();
        List<Map<String,Object>> output = new ArrayList<>(); List<Integer> sizes = new ArrayList<>();
        store.candlePages(1,"1m",minute,minute+1500*60000L,page->{sizes.add(page.size());output.addAll(page);});
        assertEquals(Arrays.asList(500,500,500,1),sizes); assertEquals(1501,output.size()); assertEquals(4,db.calls.size());
        assertEquals(0,new BigDecimal("10.1234567890123456").compareTo(ControlHistoryStore.number(output.get(0).get("open_price"))));
        db.clear(); output.clear(); sizes.clear();
        store.visibleMixedPages(1,minute,minute+1500*60000L,page->{sizes.add(page.size());output.addAll(page);});
        assertEquals(Arrays.asList(500,500,500,1),sizes); assertEquals(1501,output.size()); assertEquals(12,db.calls.size());
        assertEquals(4,db.calls.stream().filter(call->call.sql.equals(RecordingJdbc.POLICY_QUERY)).count());
        assertEquals(minute+1500*60000L,ControlHistoryStore.time(output.get(1500)));
        db.assertReadOnlyAndBounded();
        db.clear(); store.candlePages(1,"1m",minute+1,minute,page->fail()); store.visibleMixedPages(1,minute+1,minute,page->fail());
        assertTrue(db.calls.isEmpty());
    }
    @Test void denseSamplesAndSourceEventsCrossKeysetsWithoutLosingPrecisionOrFrozenPrefix() {
        task(1,1,"published",minute+2000,minute+3000,true);
        task(1,1,"hidden",minute+2000,minute+3000,true);
        db.update("INSERT INTO market_control_publication VALUES(1,'published',?,?,?)",minute+3000,minute+2000,minute+3500);
        String frozen = store.encode(bar(minute,"250.1234567890123456"));
        db.update("INSERT INTO market_mixed_minute VALUES(1,1,?,?,?)",minute,frozen,minute+3500);
        db.update("INSERT INTO market_legacy_minute_snapshot VALUES(1,1,?,?,?)",minute,frozen,minute+1000);
        List<Object[]> samples = new ArrayList<>(), ticks = new ArrayList<>(), events = new ArrayList<>();
        for (int i=0;i<1501;i++) samples.add(new Object[]{1L,"published",minute+2000+i,new BigDecimal("100.1234567890123456").add(BigDecimal.valueOf(i,6))});
        samples.add(new Object[]{1L,"hidden",minute+2500,new BigDecimal("9999.1")});
        db.batchUpdate("INSERT INTO market_control_sample VALUES(?,?,?,?)",samples);
        for (int i=0;i<1201;i++) {
            long at = minute+10000+i; BigDecimal price = new BigDecimal("80.1234567890123456").add(BigDecimal.valueOf(i,6));
            events.add(new Object[]{1L,"e"+i,1L,at,at,price});
            if(i<100) ticks.add(new Object[]{1L,1L,at,at,price});
        }
        for (int i=0;i<600;i++) ticks.add(new Object[]{1L,1L,minute+20000+i,minute+20000+i,new BigDecimal("50.1234567890123456").add(BigDecimal.valueOf(i,6))});
        db.batchUpdate("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(?,?,?,?,?,?)",events);
        db.batchUpdate("INSERT INTO market_source_tick VALUES(?,?,?,?,?)",ticks);
        // These facts are outside the requested window and may not alter its extrema.
        db.update("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(1,'future',1,?,?,99999)",minute+60000,minute+60000);
        db.clear(); Map<String,Object> row = store.readSnapshot(()->store.visibleMixed(1,minute,minute)).get(0);
        assertEquals(0,new BigDecimal("250.1234567890123456").compareTo(ControlHistoryStore.number(row.get("open_price"))));
        assertEquals(0,new BigDecimal("250.1234567890123456").compareTo(ControlHistoryStore.number(row.get("high_price"))));
        assertEquals(0,new BigDecimal("50.1234567890123456").compareTo(ControlHistoryStore.number(row.get("low_price"))));
        assertEquals(0,new BigDecimal("50.1240557890123456").compareTo(ControlHistoryStore.number(row.get("close_price"))));
        assertEquals(7,((Number)row.get("volume")).intValue()); assertEquals(true,row.get("partial"));
        assertEquals(12,db.calls.size()); db.assertReadOnlyAndBounded();
        assertEquals(1,db.calls.stream().filter(call->call.sql.equals(RecordingJdbc.POLICY_QUERY)).count());
        long unionCount = db.calls.stream().filter(call->call.sql.contains("UNION ALL")).count(); assertEquals(4,unionCount);
        for (Call call:db.calls) if(call.sql.contains("UNION ALL")) {
            assertEquals(2,count(call.sql,"e.tenant_id=? AND e.symbol_id=? AND e.received_at>=? AND e.received_at<?"));
            assertEquals(3,count(call.sql,"LIMIT ?")); assertFalse(call.sql.contains("source_event_time")); // received-time reads must not force the source-time index.
        }
    }
    @Test void publicationWindowsAreBatchFilteredWithoutPerBucketQueries() {
        task(1,1,"p",minute,minute+60000,false); task(2,1,"other-owner",minute,minute+60000,false); task(1,2,"other-symbol",minute,minute+60000,false);
        db.update("INSERT INTO market_control_publication VALUES(1,'p',?,?,?)",minute,minute,minute+60000);
        db.update("INSERT INTO market_control_publication VALUES(2,'other-owner',?,?,?)",minute,minute+120000,minute+180000);
        db.update("INSERT INTO market_control_publication VALUES(1,'other-symbol',?,?,?)",minute,minute+120000,minute+180000);
        Map<Long,Long> windows = new LinkedHashMap<>();
        for(int i=0;i<1201;i++) windows.put(minute+i*60000L,minute+(i+1)*60000L);
        db.clear(); Set<Long> published = store.publishedBuckets(1,windows);
        assertEquals(new HashSet<>(Arrays.asList(minute,minute+60000)),published); assertEquals(3,db.calls.size());
        for(Call call:db.calls) {
            assertFalse(call.sql.contains("UNION")); assertTrue(call.sql.contains("p.tenant_id=? AND t.tenant_id=? AND t.symbol_id=?"));
            assertTrue(count(call.sql,"p.from_at<? AND p.to_at>=?")<=500);
        }
        db.assertReadOnlyAndBounded(); db.clear(); assertTrue(store.publishedBuckets(1,Collections.emptyMap()).isEmpty()); assertTrue(db.calls.isEmpty());
        assertThrows(IllegalArgumentException.class,()->store.publishedBuckets(1,Collections.singletonMap(minute,minute)));
    }
    @Test void publicationKeysetKeepsLateTaskAndReadSnapshotIsRepeatableRead() throws Exception {
        List<Object[]> publications = new ArrayList<>();
        for(int i=0;i<501;i++) {
            String id = String.format("p%04d",i); task(1,1,id,minute,minute+60000,false);
            publications.add(new Object[]{1L,id,minute,minute,minute+500});
        }
        task(1,1,"zz-last",minute,minute+60000,false); publications.add(new Object[]{1L,"zz-last",minute,minute+60000,minute+60001});
        db.batchUpdate("INSERT INTO market_control_publication VALUES(?,?,?,?,?)",publications);
        Map<Long,Long> windows = new LinkedHashMap<>(); windows.put(minute,minute+1000); windows.put(minute+60000,minute+61000);
        db.clear(); assertEquals(windows.keySet(),store.publishedBuckets(1,windows)); assertEquals(2,db.calls.size());
        assertTrue(db.calls.get(1).sql.contains("p.task_id>?")); db.assertReadOnlyAndBounded();
        store.readSnapshot(()->{assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly()); assertEquals(Integer.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ),TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()); return null;});
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try { assertTrue(executor.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(2L)){return store.publishedBuckets(1,windows);}}).get().isEmpty()); }
        finally { executor.shutdownNow(); }
    }
    @Test void sparseMixedAtExcludesGapFactsFromEveryPageAndRejectsOversizedKeys() {
        List<Long> keys = Arrays.asList(minute,minute+600000);
        for (long at : Arrays.asList(minute,minute+300000,minute+600000)) {
            String id = "t" + at; task(1,1,id,at+1000,at+3000,true);
            db.update("INSERT INTO market_control_publication VALUES(1,?,?,?,?)",id,at+3000,at+1000,at+3000);
            db.update("INSERT INTO market_control_sample VALUES(1,?,?,10)",id,at+1000);
            db.update("INSERT INTO market_mixed_minute VALUES(1,1,?,?,?)",at,store.encode(bar(at,"99999")),at+5000);
            db.update("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(1,?,1,?,?,50)",id,at+5000,at+5000);
        }
        long gap = minute+300000;
        db.update("INSERT INTO market_legacy_minute_snapshot VALUES(1,1,?,?,?)",gap,store.encode(bar(gap,"99999")),gap);
        db.clear(); List<Map<String,Object>> rows = store.visibleMixedAt(1,keys);
        assertEquals(2,rows.size()); assertEquals(keys,Arrays.asList(ControlHistoryStore.time(rows.get(0)),ControlHistoryStore.time(rows.get(1))));
        for(Map<String,Object> row:rows) assertEquals(0,new BigDecimal("50").compareTo(ControlHistoryStore.number(row.get("high_price"))));
        for(Call call:db.calls) {
            if(call.sql.startsWith("SELECT minute_at,body,last_event")) assertEquals(0,call.rows,"Gap prefix must not be fetched");
            if(call.sql.startsWith("SELECT s.generated_at,s.price")) assertEquals(2,call.rows,"Gap sample must not be fetched");
            if(call.sql.contains("UNION ALL")) {assertEquals(2,call.rows,"Gap source event must not be fetched");assertTrue(call.sql.contains(" OR (e.received_at>=? AND e.received_at<?)"));}
        }
        db.assertReadOnlyAndBounded(); db.clear(); assertTrue(store.visibleMixedAt(1,Collections.emptyList()).isEmpty()); assertTrue(db.calls.isEmpty());
        assertThrows(IllegalArgumentException.class,()->store.visibleMixedAt(1,Collections.nCopies(501,minute)));
    }
    static int count(String text,String token){int result=0,at=0;while((at=text.indexOf(token,at))>=0){result++;at+=token.length();}return result;}
    static final class Call {
        final String sql; final Object[] arguments; final int rows;
        Call(String sql,Object[] arguments,int rows){this.sql=sql;this.arguments=arguments.clone();this.rows=rows;}
    }
    static final class RecordingJdbc extends JdbcTemplate {
        static final String POLICY_QUERY="SELECT ordering_version,from_minute,source_sequence,scope_sha256,evidence_sha256,responses_json FROM market_history_ordering WHERE tenant_id=? AND symbol_id=?";
        final List<Call> calls = new ArrayList<>();
        RecordingJdbc(DriverManagerDataSource source){super(source);}
        @Override public List<Map<String,Object>> queryForList(String sql,Object... arguments){List<Map<String,Object>> rows=super.queryForList(sql,arguments);calls.add(new Call(sql,arguments,rows.size()));return rows;}
        void clear(){calls.clear();}
        void assertReadOnlyAndBounded(){for(Call call:calls){
            assertTrue(call.sql.startsWith("SELECT "));assertFalse(call.sql.contains("FOR UPDATE"));assertFalse(call.sql.contains(" OFFSET "));
            if(call.sql.equals(POLICY_QUERY)) {
                assertArrayEquals(new Object[]{TenantContext.requireTenantId(),1L},call.arguments);
                assertTrue(call.rows<=1,"The complete tenant/symbol primary key bounds policy metadata to one row");
            } else {
                assertTrue(call.sql.endsWith("LIMIT ?"));assertTrue(Arrays.asList(1,500).contains(call.arguments[call.arguments.length-1]));assertTrue(call.rows<=500);
            }
        }}
    }
}
