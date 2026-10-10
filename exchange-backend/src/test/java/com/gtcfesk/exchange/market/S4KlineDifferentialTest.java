package com.gtcfesk.exchange.market;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed historical clocks, frozen S1 oracle, real H2 SQL; no application boot or provider access. */
class S4KlineDifferentialTest extends TenantMarketTestContext {
    private static final long START = 1700000400000L;
    private ObservedDataSource source;
    private ControlHistoryStore store;
    private LegacyHistoryStore legacyStore;
    private ControlledKlineMerger current;
    private LegacyS4KlineReader before;
    private int comparisons;
    private final List<Map<String,Object>> evidence = new ArrayList<>();

    @BeforeEach void setup() {
        source = new ObservedDataSource();
        JdbcTemplate db = new JdbcTemplate(source);
        MarketSqlFixture.schema(db);
        if(source.mysql) {
            // Dedicated S4 scratch rows only; the ownership marker and all other tables remain untouched.
            new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
                for(String table:Arrays.asList("market_control_plan","market_control_sample","market_control_resume","market_control_hold","market_control_publication","market_control_flow",
                    "market_legacy_minute_snapshot","market_simulation_source_candle","market_mixed_minute","market_source_event","market_source_tick","market_source_quote","market_source_candle","market_control_task","trading_symbol"))
                    db.update("DELETE FROM "+table+" WHERE tenant_id IN (1,2)");
                return null;
            });
        }
        db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1),(2,2)");
        store = new ControlHistoryStore(db, new DataSourceTransactionManager(source));
        legacyStore = new LegacyHistoryStore(db, new DataSourceTransactionManager(source));
        current = new ControlledKlineMerger(store);
        before = new LegacyS4KlineReader(legacyStore);
    }

    @AfterEach void saveDifferenceEvidence() throws Exception {
        if(evidence.isEmpty())return;
        writeEvidence((source.mysql?"mysql":"h2")+"-differences",evidence);
    }
    private void writeEvidence(String prefix,Object value) throws java.io.IOException {
        java.nio.file.Path directory=java.nio.file.Paths.get(System.getProperty("s4.evidence.dir","target/s4-differential")).toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(directory);
        String filename=prefix+"-"+UUID.randomUUID()+".json";
        java.nio.file.Path temporary=directory.resolve(filename+".tmp");
        java.nio.file.Files.write(temporary,new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(value).getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.CREATE_NEW);
        java.nio.file.Files.move(temporary,directory.resolve(filename),java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private Map<String,Object> bar(long at, BigDecimal price, int volume) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("timestamp", at); row.put("open_price", price); row.put("close_price", price.add(new BigDecimal("0.0000000000000001")));
        row.put("high_price", price.add(BigDecimal.ONE)); row.put("low_price", price.subtract(BigDecimal.ONE)); row.put("volume", volume);
        return row;
    }
    private void candle(String period, Map<String,Object> row) {
        store.db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES(1,1,?,?,?,?) ON DUPLICATE KEY UPDATE body=VALUES(body),received_at=VALUES(received_at)",
            period, ControlHistoryStore.time(row), store.encode(row), ControlHistoryStore.time(row)+60000);
    }
    private void mixed(Map<String,Object> row) {
        row = new LinkedHashMap<>(row); row.put("controlled", true); row.put("partial", true);
        store.db.update("INSERT INTO market_mixed_minute(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?) ON DUPLICATE KEY UPDATE body=VALUES(body),last_event=VALUES(last_event)",
            ControlHistoryStore.time(row), store.encode(row), ControlHistoryStore.time(row)+59000);
    }
    private Map<String,Object> external(List<Map<String,Object>> rows) {
        Map<String,Object> data = new LinkedHashMap<>(); data.put("kline_list", rows); data.put("code", "TEST"); data.put("source", "fixture");
        Map<String,Object> result = new LinkedHashMap<>(); result.put("ret",503); result.put("data",data); return result;
    }
    private Map<String,Object> expectedWithFixtureRevision(Map<String,Object> legacy) {
        // No restore is effective in this fixture. Extend the envelope contract without changing the frozen reader or bars.
        Map<String,Object> data=new LinkedHashMap<>((Map<String,Object>)legacy.get("data"));
        assertFalse(data.containsKey("historyRestoreRevision"));data.put("historyRestoreRevision",0L);
        Map<String,Object> expected=new LinkedHashMap<>(legacy);expected.put("data",data);return expected;
    }
    private Map<String,Object> compare(String period, int limit, long cursor, boolean utc, List<Map<String,Object>> rows) {
        Map<String,Object> input = external(rows);
        source.calls.clear(); source.record = true;
        long oldStart = System.nanoTime();
        Map<String,Object> expected;
        try { expected = source.snapshot(()->legacyStore.snapshot(()->before.merge(1,period,limit,cursor,input,ignored->{},utc))); }
        finally { source.record=false; }
        Map<String,Object> rawLegacyExpected=expected;expected=expectedWithFixtureRevision(expected);
        int oldQueries = source.calls.size();
        double oldMillis=(System.nanoTime()-oldStart)/1e6;
        List<Map<String,Object>> oldSql = new ArrayList<>(source.calls);
        Map<String,Object> oldMetrics=new LinkedHashMap<>(source.lastMetrics);
        source.calls.clear(); source.record = true;
        long newStart=System.nanoTime();
        Map<String,Object> actual;
        try { actual = source.snapshot(()->current.merge(1,period,limit,cursor,input,ignored->{},utc)); }
        finally { source.record=false; }
        Map<String,Object> difference=new LinkedHashMap<>();
        difference.put("database",source.mysql?"MySQL5.7 dedicated small S4 fixture, not million/ten-million capacity or disk-cold evidence":"H2 development fixture, not MySQL capacity evidence");
        difference.put("period",period);difference.put("limit",limit);difference.put("cursor",cursor);difference.put("utcAnchors",utc);
        difference.put("old_method_ms",oldMillis);difference.put("new_method_ms",(System.nanoTime()-newStart)/1e6);
        difference.put("old_method_metrics",oldMetrics);difference.put("new_method_metrics",new LinkedHashMap<>(source.lastMetrics));
        difference.put("expected_rows",ControlHistoryStore.rows(expected).size());difference.put("actual_rows",ControlHistoryStore.rows(actual).size());
        difference.put("measurement_qualification","Synthetic fixture inserts warm data. Legacy is read first; new result is a subsequent shadow read, never a cold first call. No snapshot restore, disk-cold restart or OS-cache clearing. full_method_ms excludes connection identity/pin setup and initial counter read, includes final counter-observation overhead; outer_method_ms includes connection and observer overhead. Not real GET/WS end-to-end latency.");
        difference.put("old_sql",oldSql);difference.put("new_sql",new ArrayList<>(source.calls));
        difference.put("raw_legacy_expected",rawLegacyExpected);difference.put("fixture_contract_extension",Collections.singletonMap("data.historyRestoreRevision",0L));
        difference.put("expected",expected);difference.put("actual",actual);difference.put("field_equal",expected.equals(actual));evidence.add(difference);
        if(!expected.equals(actual))try {writeEvidence((source.mysql?"mysql":"h2")+"-failed-difference",difference);}catch(java.io.IOException failure){throw new IllegalStateException("Cannot durably preserve failed S4 difference",failure);}
        assertEquals(expected, actual, period+" limit="+limit+" cursor="+cursor+" utc="+utc);
        assertReadOnly(source.calls);
        comparisons++;
        if (comparisons==1 || comparisons%25==0) System.out.println("S4_DIFFERENTIAL period="+period+" cursor="+cursor+" limit="+limit+" old_queries="+oldQueries+" new_queries="+source.calls.size()+" exact_fields=true");
        if (Boolean.getBoolean("s4.sql.trace")) {
            System.out.println("S4_LEGACY_SQL "+oldSql); System.out.println("S4_CURRENT_SQL "+source.calls);
        }
        return actual;
    }
    private void preserveDirectFailure(long minute,List<Map<String,Object>> expected,List<Map<String,Object>> actual,
                                       List<Map<String,Object>> oldSql,List<Map<String,Object>> newSql) {
        if(expected.equals(actual))return;
        Map<String,Object> failure=new LinkedHashMap<>();
        failure.put("route","direct-visibleMixed-precheck");failure.put("frozen_input","53979a503926e53f5bd88e66b1d0618702f69bbe");
        failure.put("minute",minute);failure.put("expected",expected);failure.put("actual",actual);failure.put("field_equal",false);
        failure.put("old_sql",oldSql);failure.put("new_sql",newSql);
        failure.put("connection_qualification","Original direct read order and original connection/transaction strategy; no snapshot pin or added REPEATABLE_READ wrapper. SQL observation only. Fixture facts are read after both original reads, on failure only.");
        failure.put("handler_read_qualification","No single-physical-method Handler counters or performance measurements for this direct precheck. Performance evidence belongs only to the separately pinned compare/growth observations.");
        failure.put("database",source.mysql?"MySQL5.7 dedicated small S4 fixture; not cold or capacity evidence":"H2 development fixture");
        if(source.mysql){failure.put("server_uuid",source.expectedUuid);failure.put("fixture_database",source.expectedDatabase);failure.put("fixture_owner",source.owner);}
        Map<String,Object> facts=new LinkedHashMap<>();
        // Failure-only forensic reads follow both comparisons; never prewarm or change their query topology.
        for(String tableAndOrder:Arrays.asList("market_control_task|id","market_control_flow|task_id","market_control_publication|task_id",
            "market_control_sample|task_id,generated_at","market_legacy_minute_snapshot|symbol_id,minute_at","market_source_event|event_sequence",
            "market_source_tick|symbol_id,source_time","market_mixed_minute|symbol_id,minute_at","market_source_candle|symbol_id,period,candle_at")) {
            String[] parts=tableAndOrder.split("\\|");
            facts.put(parts[0],store.db.queryForList("SELECT * FROM "+parts[0]+" WHERE tenant_id=1 ORDER BY "+parts[1]));
        }
        failure.put("fixed_fixture_facts_after_direct_reads",facts);
        try {writeEvidence((source.mysql?"mysql":"h2")+"-direct-failed-difference",failure);}
        catch(java.io.IOException cause){throw new IllegalStateException("Cannot durably preserve direct S4 difference",cause);}
    }
    private static void assertReadOnly(List<Map<String,Object>> calls) {
        assertFalse(calls.isEmpty());
        for(Map<String,Object> call:calls) {
            String sql=call.get("sql").toString().toUpperCase(Locale.ROOT);
            assertTrue(sql.startsWith("SELECT"), "Kline read DML: "+sql);
            assertFalse(sql.contains("FOR UPDATE") || sql.contains("LOCK IN SHARE MODE") || sql.contains("GET_LOCK"), "Kline locking read: "+sql);
        }
    }

    @Test void denseSparseEmptyPrecisionAndDeepCursorPagesAreExactlyEqual() {
        Random random = new Random(4704);
        for(int i=0;i<360;i++) {
            long minute = START+i*60000L;
            BigDecimal value = new BigDecimal("12.1234567890123456").add(BigDecimal.valueOf(random.nextInt(500)));
            candle("1m",bar(minute,value,i));
            if(i%3==0) mixed(bar(minute,value.add(BigDecimal.TEN),i));
        }
        // Sparse, genuinely old occupied minutes cannot disappear behind a fixed retention window.
        for(int years:new int[]{1,3,9}) mixed(bar(START-years*365L*86400000,BigDecimal.valueOf(years),years));
        for(String period:Arrays.asList("1m","5m","15m","1h","4h","1d","1w")) {
            long width=RandomMarketPath.duration(period);
            for(int limit:new int[]{1,7,31}) for(long cursor:new long[]{START-9*365L*86400000-1,START-1,START,START+179*60000L,START+360*60000L})
                compare(period,limit,cursor,true,Collections.emptyList());
            long cursor=START+360*60000L; Set<Long> visited = new HashSet<>();
            for(int page=0;page<6;page++) {
                List<Map<String,Object>> rows=ControlHistoryStore.rows(compare(period,3,cursor,true,Collections.emptyList()));
                if(rows.isEmpty()) break;
                for(Map<String,Object> row:rows) assertTrue(visited.add(ControlHistoryStore.time(row)),"Deep keyset page repeated "+period);
                cursor=ControlHistoryStore.time(rows.get(0))-1;
            }
            assertTrue(width>=60000);
        }
        assertTrue(comparisons>=105);
        compare("1m",200,START+360*60000L,true,Collections.emptyList());
        Map<String,Object> full=evidence.get(evidence.size()-1);
        int oldQueries=((List<?>)full.get("old_sql")).size(),newQueries=((List<?>)full.get("new_sql")).size();
        assertTrue(newQueries<oldQueries/2,"Batch read must remove per-bucket candle/publication queries: old="+oldQueries+" new="+newQueries);
        long publicationReads=source.calls.stream().filter(call->call.get("sql").toString().contains("FROM market_control_publication")).count();
        assertTrue(publicationReads<=1,"At most one publication read for this <=200 bucket window");
        System.out.println("S4_DIFFERENTIAL cases="+comparisons+" full_response_map_equality=true precision=16 history_years=9");
    }

    @Test void providerAnchorsClosuresDstWeekAndCursorBoundariesStayStable() {
        long day=Instant.parse("2024-03-09T14:30:00Z").toEpochMilli();
        long nextDay=day+23*3600000L; // Explicit provider DST anchor; never guessed from UTC.
        candle("1d",bar(day,new BigDecimal("80.125"),10));
        candle("1d",bar(nextDay,new BigDecimal("81.125"),10));
        mixed(bar(nextDay-60000,new BigDecimal("90.00001"),1));
        mixed(bar(nextDay+60000,new BigDecimal("91.00001"),1));
        mixed(bar(nextDay+2*86400000L,new BigDecimal("99.00001"),1)); // Closure has no new provider anchor.
        for(long cursor:new long[]{day-1,day, nextDay-1,nextDay,nextDay+3*86400000L}) compare("1d",10,cursor,false,Collections.emptyList());
        assertEquals(day,ControlHistoryStore.time(ControlHistoryStore.rows(compare("1d",1,nextDay-1,false,Collections.emptyList())).get(0)));
        long hour=day+1800000L;
        candle("1h",bar(hour,new BigDecimal("100.123456"),1)); mixed(bar(hour+60000,new BigDecimal("110.123456"),1));
        compare("1h",5,hour-1,false,Collections.emptyList()); compare("1h",5,hour+3600000,false,Collections.emptyList());
        // Requirement changed: monthly differential is replaced by explicit rejection; original case is preserved in before.
        assertThrows(IllegalArgumentException.class, () -> current.merge(1,"1M",3,System.currentTimeMillis(),external(Collections.emptyList()),null,false));
        long week=Instant.parse("2024-03-04T00:00:00Z").toEpochMilli();
        candle("1w",bar(week,new BigDecimal("70.0001"),1)); mixed(bar(week+60000,new BigDecimal("75.0001"),1));
        for(long cursor:new long[]{week-1,week,week+7*86400000L}) compare("1w",3,cursor,true,Collections.emptyList());
    }

    private void task(String id,int version,long from,long to,String state) {
        store.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,ended_at,sampled_until) VALUES(1,?,1,'TEST',?,'TARGET','COMPLETED',100,120,60,1,false,16,'LIVE',?,?,?,?,?)",
            id,version,from,from,to,to,to);
        if(state!=null) store.db.update("INSERT INTO market_control_flow(tenant_id,task_id,options_json,state,last_price,last_at,finished_at) VALUES(1,?,'{}',?,120,?,?)",id,state,to,to);
    }
    private void sample(String task,long at,String price) {
        store.db.update("INSERT INTO market_control_sample(tenant_id,task_id,generated_at,price) VALUES(1,?,?,?)",task,at,new BigDecimal(price));
    }
    private void event(String id,long sourceAt,long receivedAt,String price,boolean tick) {
        store.db.update("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(1,?,1,?,?,?)",id,sourceAt,receivedAt,new BigDecimal(price));
        if(tick) store.db.update("INSERT INTO market_source_tick(tenant_id,symbol_id,source_time,received_at,price) VALUES(1,1,?,?,?) ON DUPLICATE KEY UPDATE received_at=VALUES(received_at),price=VALUES(price)",sourceAt,receivedAt,new BigDecimal(price));
    }

    @Test void ambiguousLegacyTicksAreRejectedAndOriginalFactsRemainUnchanged() {
        com.gtcfesk.exchange.common.BusinessException failure=assertThrows(com.gtcfesk.exchange.common.BusinessException.class,
            this::frozenPrefixMultiTaskLateEventsDedupAndPublicationAreFieldEqual);
        assertTrue(failure.getMessage().contains("HISTORY_LEGACY_ORDER_PENDING"));
        assertReadOnly(source.calls);
        Map<String,Object> facts=diagnosticFacts();
        for(int i=0;i<3;i++) {
            source.calls.clear();source.record=true;
            try {
                assertTrue(assertThrows(com.gtcfesk.exchange.common.BusinessException.class,
                    ()->store.readSnapshot(()->store.visibleMixed(1,START,START))).getMessage().contains("HISTORY_LEGACY_ORDER_PENDING"));
            } finally {source.record=false;}
            assertReadOnly(source.calls);assertEquals(facts,diagnosticFacts());
        }
        assertEquals(2,store.db.queryForObject("SELECT COUNT(*) FROM market_source_tick WHERE tenant_id=1 AND symbol_id=1 AND received_at=?",Integer.class,START+57000));
    }
    @Test void unambiguousFrozenPrefixMultiTaskLateEventsDedupAndPublicationAreFieldEqual() {
        frozenPrefixMultiTaskLateEventsDedupAndPublicationAreFieldEqual(false);
    }
    // Existing protection/archived-return tests call the original ambiguous fixture and still receive its rejection.
    void frozenPrefixMultiTaskLateEventsDedupAndPublicationAreFieldEqual() {
        frozenPrefixMultiTaskLateEventsDedupAndPublicationAreFieldEqual(true);
    }
    private void frozenPrefixMultiTaskLateEventsDedupAndPublicationAreFieldEqual(boolean ambiguous) {
        long minute=START;
        Map<String,Object> prefix=bar(minute,new BigDecimal("80.1234567890123456"),7);
        prefix.put("snapshotAt",minute+10000); prefix.put("controlled",true); prefix.put("partial",true);
        mixed(prefix);
        store.db.update("INSERT INTO market_legacy_minute_snapshot(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?)",minute,store.encode(prefix),minute+10000);
        task("a-v4",4,minute+10000,minute+30000,"SOURCE");
        task("b-v2",2,minute+31000,minute+45000,"RECOVERING");
        sample("a-v4",minute+10000,"100.1234567890123456"); sample("a-v4",minute+15000,"110.1234567890123456"); sample("a-v4",minute+25000,"120.1234567890123456");
        sample("b-v2",minute+31000,"95.1234567890123456"); sample("b-v2",minute+45000,"105.1234567890123456");
        store.db.update("INSERT INTO market_control_publication(tenant_id,task_id,published_at,from_at,to_at) VALUES(1,'a-v4',?,?,?)",minute+70000,minute+10000,minute+20000);
        event("prefix-duplicate",minute+1000,minute+5000,"1.1234567890123456",true);
        event("unpublished-source",minute+24000,minute+25000,"88.1234567890123456",true);
        event("late-source",minute-60000,minute+50000,"89.1234567890123456",false);
        event("same-time-first",minute+53000,minute+55000,"86.1234567890123456",false);
        event("same-time-second",minute+53000,minute+55000,"87.1234567890123456",false);
        store.db.update("INSERT INTO market_source_tick(tenant_id,symbol_id,source_time,received_at,price) VALUES(1,1,?,?,?)",minute+56000,minute+56000,new BigDecimal("85.1234567890123456"));
        store.db.update("INSERT INTO market_source_tick(tenant_id,symbol_id,source_time,received_at,price) VALUES(1,1,?,?,?),(1,1,?,?,?)",
            minute+57000,minute+(ambiguous?57000:59000),new BigDecimal("84.1234567890123456"),minute+58000,minute+57000,new BigDecimal("83.1234567890123456"));
        // Later OHLC is still retained as source but cannot erase frozen prefix or published control extrema.
        candle("1m",bar(minute,new BigDecimal("999.1234567890123456"),100));
        source.calls.clear();source.record=true;
        List<Map<String,Object>> directExpected;
        try { directExpected=legacyStore.visibleMixed(1,minute,minute); } finally { source.record=false; }
        List<Map<String,Object>> directOldSql=new ArrayList<>(source.calls);
        source.calls.clear();source.record=true;
        List<Map<String,Object>> directActual;
        try { directActual=store.visibleMixed(1,minute,minute); } finally { source.record=false; }
        preserveDirectFailure(minute,directExpected,directActual,directOldSql,new ArrayList<>(source.calls));
        assertEquals(directExpected,directActual);
        List<Map<String,Object>> visible=store.visibleMixed(1,minute,minute);
        assertEquals(1,visible.size()); assertEquals(prefix.get("open_price"),visible.get(0).get("open_price"));
        assertEquals(0,new BigDecimal("110.1234567890123456").compareTo(ControlHistoryStore.number(visible.get(0).get("high_price"))));
        assertEquals(0,new BigDecimal("84.1234567890123456").compareTo(ControlHistoryStore.number(visible.get(0).get("close_price"))));
        for(String period:Arrays.asList("1m","5m","1h","1d","1w")) {
            Map<String,Object> result=compare(period,5,minute+60000,true,Collections.emptyList());
            assertTrue(ControlHistoryStore.rows(result).stream().allMatch(row->Boolean.TRUE.equals(row.get("partial"))));
            assertTrue(ControlHistoryStore.rows(result).stream().anyMatch(row->Boolean.TRUE.equals(row.get("historyReplaced"))));
        }
        Map<String,Object> state=store.db.queryForMap("SELECT body,last_event FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=1 AND minute_at=?",minute);
        for(int i=0;i<3;i++) compare("1m",5,minute+60000,true,Collections.emptyList());
        assertEquals(state,store.db.queryForMap("SELECT body,last_event FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=1 AND minute_at=?",minute));
    }

    @Test void allAlgorithmVersionsRemainHistoricalFactsAndProviderTailNeverCreatesPeriod() {
        for(int version=1;version<=4;version++) {
            long at=START+version*60000L;
            mixed(bar(at,BigDecimal.valueOf(100+version),version));
            task("v"+version,version,at+1000,at+40000,version<3?null:"RECOVERING");
            sample("v"+version,at+1000,"100.1234567890123456"); sample("v"+version,at+40000,"120.1234567890123456");
        }
        Map<String,Object> tail=bar(START+17000,new BigDecimal("999"),99);
        for(String period:Arrays.asList("1m","5m","1h")) compare(period,20,START+5*60000L,true,Collections.singletonList(tail));
        assertEquals(4,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task",Integer.class));
    }

    @Test void suppliedRandomSessionMinutesMatchFrozenReaderAndReadNeverQueuesSourceWork() {
        long session=START;
        List<Map<String,Object>> base=new ArrayList<>();
        for(int i=0;i<15;i++) { Map<String,Object> row=bar(session+i*60000,new BigDecimal("90.125").add(BigDecimal.valueOf(i)),i); base.add(row); if(i%4==0)mixed(row); }
        java.util.function.BiFunction<Long,Long,List<Map<String,Object>>> minutes=(from,to)->{
            List<Map<String,Object>> selected=new ArrayList<>(); for(Map<String,Object> row:base)if(ControlHistoryStore.time(row)>=from&&ControlHistoryStore.time(row)<=to)selected.add(row); return selected;
        };
        for(String period:Arrays.asList("1m","5m","1h")) {
            List<Long> oldRequests=new ArrayList<>(),newRequests=new ArrayList<>();
            Map<String,Object> expected=source.snapshot(()->legacyStore.snapshot(()->before.merge(1,period,20,session+15*60000,external(Collections.emptyList()),oldRequests::add,true,minutes)));
            expected=expectedWithFixtureRevision(expected);
            source.calls.clear();source.record=true;
            Map<String,Object> actual;
            try{actual=source.snapshot(()->current.merge(1,period,20,session+15*60000,external(Collections.emptyList()),newRequests::add,true,minutes));}finally{source.record=false;}
            assertEquals(expected,actual); assertReadOnly(source.calls);
            assertTrue(newRequests.isEmpty(),"S4 reads cannot trigger provider work; old callback is only an oracle side effect");
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="S4_HISTORY_JDBC",matches="jdbc:mysql://127\\.0\\.0\\.1:33349/mt705_s4_[a-f0-9]{32}\\?.+")
    void legacySameReceiveTickOrderingDiagnosticDoesNotApproveChangedResults() throws Exception {
        long minute=START;
        Map<String,Object> prefix=bar(minute,new BigDecimal("80.1234567890123456"),7);mixed(prefix);
        store.db.update("INSERT INTO market_legacy_minute_snapshot(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?)",minute,store.encode(prefix),minute+10000);
        task("tie-v3",3,minute+10000,minute+30000,"SOURCE");sample("tie-v3",minute+15000,"110.1234567890123456");
        store.db.update("INSERT INTO market_control_publication(tenant_id,task_id,published_at,from_at,to_at) VALUES(1,'tie-v3',?,?,?)",minute+70000,minute+10000,minute+20000);
        store.db.update("INSERT INTO market_source_tick(tenant_id,symbol_id,source_time,received_at,price) VALUES(1,1,?,?,?),(1,1,?,?,?)",
            minute+57000,minute+57000,new BigDecimal("84.1234567890123456"),minute+58000,minute+57000,new BigDecimal("83.1234567890123456"));
        candle("1m",bar(minute,new BigDecimal("999.1234567890123456"),100));
        Map<String,Object> facts=diagnosticFacts();
        List<Map<String,Object>> replays=new ArrayList<>();Set<String> legacyCloses=new TreeSet<>(),newCloses=new TreeSet<>();int rejected=0;
        List<Map<String,Object>> plans=new ArrayList<>();Set<String> seenPlans=new HashSet<>();
        for(int i=0;i<6;i++)for(String route:Arrays.asList("old-direct-autocommit","old-direct-snapshot","old-merger-snapshot","new-merger-snapshot")) {
            source.calls.clear();source.record=true;List<Map<String,Object>> rows;String rejection=null;
            try {
                if(route.equals("old-direct-autocommit"))rows=legacyStore.visibleMixed(1,minute,minute);
                else if(route.equals("old-direct-snapshot"))rows=source.snapshot(()->legacyStore.snapshot(()->legacyStore.visibleMixed(1,minute,minute)));
                else if(route.equals("old-merger-snapshot"))rows=ControlHistoryStore.rows(source.snapshot(()->legacyStore.snapshot(()->before.merge(1,"1m",5,minute+60000,external(Collections.emptyList()),ignored->{},true))));
                else {
                    com.gtcfesk.exchange.common.BusinessException failure=assertThrows(com.gtcfesk.exchange.common.BusinessException.class,
                        ()->source.snapshot(()->current.merge(1,"1m",5,minute+60000,external(Collections.emptyList()),ignored->{},true)));
                    rejection=failure.getMessage();assertTrue(rejection.contains("HISTORY_LEGACY_ORDER_PENDING"));rows=Collections.emptyList();rejected++;
                }
            } finally {source.record=false;}
            if(route.startsWith("old")) {assertEquals(1,rows.size());legacyCloses.add(rows.get(0).get("close_price").toString());}
            else {assertTrue(rows.isEmpty());assertReadOnly(source.calls);}
            Map<String,Object> replay=new LinkedHashMap<>();replay.put("iteration",i);replay.put("route",route);replay.put("rows",rows);replay.put("executed_sql",new ArrayList<>(source.calls));
            if(rejection!=null)replay.put("rejection",rejection);
            replay.put("single_connection",!route.equals("old-direct-autocommit"));replays.add(replay);
        }
        // EXPLAIN is deliberately after every business replay. These diagnostics cannot prewarm a claimed first call.
        for(Map<String,Object> replay:replays)for(Object raw:(List<?>)replay.get("executed_sql")) {
            @SuppressWarnings("unchecked") Map<String,Object> call=(Map<String,Object>)raw;
            String sql=call.get("sql").toString();
            if(!sql.contains("FROM market_source_tick")||!seenPlans.add(sql))continue;
            @SuppressWarnings("unchecked") Map<Integer,Object> arguments=(Map<Integer,Object>)call.get("arguments");
            Map<String,Object> plan=new LinkedHashMap<>();plan.put("sql",sql);plan.put("arguments",arguments);
            plan.put("explain_after_replays",store.db.queryForList("EXPLAIN "+sql,arguments.values().toArray()));plans.add(plan);
        }
        Map<String,Object> diagnostic=new LinkedHashMap<>();diagnostic.put("qualification","Diagnostic only; never approval for a changed close or replacement for strict differential assertion. Synthetic warm facts; not cold or GET latency.");
        diagnostic.put("fixed_facts_before",facts);diagnostic.put("fixed_facts_after",diagnosticFacts());diagnostic.put("replays",replays);diagnostic.put("explain_after_all_business_replays",plans);
        diagnostic.put("legacy_close_values",legacyCloses);diagnostic.put("new_close_values",newCloses);diagnostic.put("legacy_nondeterministic_tie_observed",legacyCloses.size()>1);
        diagnostic.put("new_rejection_count",rejected);diagnostic.put("legacy_equivalence_approved",false);
        writeEvidence("mysql-legacy-tie-diagnostic",diagnostic);
        assertEquals(facts,diagnosticFacts(),"Diagnostic reads must not modify any source/sample/prefix/candle fact");
        assertTrue(newCloses.isEmpty(),"Ambiguous legacy facts must never produce an invented close");assertEquals(6,rejected);
    }
    private Map<String,Object> diagnosticFacts() {
        Map<String,Object> facts=new LinkedHashMap<>();
        facts.put("ticks",store.db.queryForList("SELECT * FROM market_source_tick WHERE tenant_id=1 AND symbol_id=1 ORDER BY source_time"));
        facts.put("samples",store.db.queryForList("SELECT * FROM market_control_sample WHERE tenant_id=1 ORDER BY task_id,generated_at"));
        facts.put("prefix",store.db.queryForList("SELECT * FROM market_legacy_minute_snapshot WHERE tenant_id=1 AND symbol_id=1 ORDER BY minute_at"));
        facts.put("mixed",store.db.queryForList("SELECT * FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=1 ORDER BY minute_at"));
        facts.put("candles",store.db.queryForList("SELECT * FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 ORDER BY period,candle_at"));return facts;
    }

    /** SQL and bind values are captured at execution, not reconstructed from JdbcTemplate call sites. */
    static final class ObservedDataSource extends DriverManagerDataSource {
        boolean record;
        final boolean mysql;
        private final String expectedUuid,expectedDatabase,owner;
        private final ThreadLocal<Connection> pinned=new ThreadLocal<>();
        Map<String,Object> lastMetrics=Collections.emptyMap();
        final List<Map<String,Object>> calls = new ArrayList<>();
        ObservedDataSource() {
            String url=System.getenv("S4_HISTORY_JDBC");mysql=url!=null;
            if(!mysql) { setUrl("jdbc:h2:mem:s4_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");setUsername("sa");setPassword("");expectedUuid=null;expectedDatabase=null;owner=null;return; }
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("jdbc:mysql://127\\.0\\.0\\.1:33349/(mt705_s4_[a-f0-9]{32})\\?[^\\r\\n]+").matcher(url);
            if(!match.matches())throw new IllegalArgumentException("Only exact dedicated S4 loopback URL permitted");
            expectedDatabase=match.group(1);expectedUuid=required("S4_HISTORY_UUID");owner=required("S4_HISTORY_OWNER");
            if(!expectedUuid.matches("[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}")||!owner.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Exact S4 UUID and owner required");
            setUrl(url);setUsername(required("S4_HISTORY_USER"));setPassword(required("S4_HISTORY_PASSWORD"));
        }
        private static String required(String name) { String value=System.getenv(name);if(value==null||value.trim().isEmpty())throw new IllegalArgumentException("Required dedicated S4 fixture field missing: "+name);return value; }
        @Override public Connection getConnection() throws SQLException { return pinned.get()!=null?pinned.get():checked(super.getConnection()); }
        @Override public Connection getConnection(String user,String password) throws SQLException { return pinned.get()!=null?pinned.get():checked(super.getConnection(user,password)); }
        private Connection checked(Connection raw) throws SQLException {
            try {
                if(mysql) {
                    try(Statement statement=raw.createStatement();ResultSet row=statement.executeQuery("SELECT @@server_uuid,DATABASE(),VERSION(),@@port")) {
                        if(!row.next()||!expectedUuid.equals(row.getString(1))||!expectedDatabase.equals(row.getString(2))||!row.getString(3).startsWith("5.7.")||row.getInt(4)!=3306)
                            throw new SQLException("Dedicated S4 physical server identity changed; no writes permitted");
                    }
                    try(PreparedStatement statement=raw.prepareStatement("SELECT owner,stage FROM s4_fixture_identity")) {
                        try(ResultSet rows=statement.executeQuery()) { if(!rows.next()||!owner.equals(rows.getString(1))||!"S4".equals(rows.getString(2))||rows.next())throw new SQLException("Dedicated S4 ownership marker mismatch; no writes permitted"); }
                    }
                }
                return connection(raw);
            } catch(SQLException|RuntimeException failure) { raw.close();throw failure; }
        }
        <T> T snapshot(java.util.function.Supplier<T> work) {
            if(pinned.get()!=null)return work.get();
            try(Connection connection=getConnection()) {
                pinned.set(connection);
                try {
                Map<String,Long> before=mysql?handlers(connection):Collections.emptyMap();
                long start=System.nanoTime(),liveBefore=liveBytes();
                try { return work.get(); }
                finally {
                    Map<String,Long> after=mysql?handlers(connection):Collections.emptyMap(),delta=new TreeMap<>();
                    for(Map.Entry<String,Long> entry:after.entrySet())delta.put(entry.getKey(),entry.getValue()-before.get(entry.getKey()));
                    Map<String,Object> measured=new LinkedHashMap<>();measured.put("full_method_ms",(System.nanoTime()-start)/1e6);
                    measured.put("handler_read_before",before);measured.put("handler_read_after",after);measured.put("handler_read_delta",delta);
                    measured.put("live_bytes_before",liveBefore);measured.put("live_bytes_after",liveBytes());measured.put("memory_note","Whole JVM live-memory observation; noisy delta, not retained-heap proof");
                    measured.put("single_physical_connection",true);if(mysql){measured.put("server_uuid",expectedUuid);measured.put("database",expectedDatabase);measured.put("fixture_owner",owner);}
                    lastMetrics=measured;
                }
                } finally {pinned.remove();}
            } catch(SQLException failure) { throw new IllegalStateException("Dedicated S4 connection observation failed",failure); }
        }
        private static long liveBytes() { return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(); }
        private Map<String,Long> handlers(Connection connection) throws SQLException {
            boolean old=record;record=false;
            try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SHOW SESSION STATUS LIKE 'Handler_read%'")) {
                Map<String,Long> result=new TreeMap<>();while(rows.next())result.put(rows.getString(1),Long.parseLong(rows.getString(2)));return result;
            } finally {record=old;}
        }
        private Connection connection(Connection raw) {
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("close")&&pinned.get()==proxy)return null;
                Object result=invoke(method,raw,args);
                if(method.getName().equals("prepareStatement")) return statement((PreparedStatement)result,args[0].toString());
                if(method.getName().equals("createStatement")) return statement((Statement)result,null);
                return result;
            });
        }
        private Statement statement(Statement raw,String preparedSql) {
            Map<Integer,Object> binds=new TreeMap<>();
            return (Statement)Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{preparedSql==null?Statement.class:PreparedStatement.class},(proxy,method,args)->{
                if(method.getName().startsWith("set")&&args!=null&&args.length>=2&&args[0] instanceof Integer) binds.put((Integer)args[0],args[1]);
                if(record&&method.getName().startsWith("execute")) {
                    Map<String,Object> call=new LinkedHashMap<>();call.put("sql",preparedSql==null?String.valueOf(args[0]):preparedSql);call.put("arguments",new TreeMap<>(binds));calls.add(call);
                }
                return invoke(method,raw,args);
            });
        }
        private static Object invoke(Method method,Object target,Object[] args) throws Throwable {
            try { return method.invoke(target,args); } catch(InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    /** Frozen 53979a SQL, including visibility reconstruction; never calls new read algorithms. */
    static final class LegacyHistoryStore extends ControlHistoryStore {
        private final org.springframework.transaction.support.TransactionTemplate snapshots;
        LegacyHistoryStore(JdbcTemplate db,DataSourceTransactionManager manager) {
            super(db,manager); snapshots=new org.springframework.transaction.support.TransactionTemplate(manager);
            snapshots.setReadOnly(true);snapshots.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        }
        <T> T snapshot(java.util.function.Supplier<T> work) { return snapshots.execute(status->work.get()); }
        @Override List<Map<String,Object>> candles(long symbol,String period,long from,long to) {
            List<Map<String,Object>> rows=db.query("SELECT body FROM market_source_candle WHERE tenant_id="+tenant()+" AND symbol_id=? AND period=? AND candle_at>=? AND candle_at<=? ORDER BY candle_at",(rs,n)->decode(rs.getString(1)),symbol,period,from,to);
            rows.removeIf(row->!periodCandle(row,period)); return rows;
        }
        @Override List<Map<String,Object>> mixed(long symbol,long from,long to) {
            return db.query("SELECT body FROM market_mixed_minute WHERE tenant_id="+tenant()+" AND symbol_id=? AND minute_at>=? AND minute_at<=? ORDER BY minute_at",(rs,n)->decode(rs.getString(1)),symbol,from,to);
        }
        @Override List<Map<String,Object>> visibleMixed(long symbol,long from,long to) {
            List<Map<String,Object>> original=mixed(symbol,from,to);
            if(original.isEmpty())return original;
            long first=time(original.get(0)),end=time(original.get(original.size()-1))+60000;
            Set<Long> modern=new HashSet<>(db.queryForList("SELECT DISTINCT FLOOR(s.generated_at/60000)*60000 FROM market_control_sample s JOIN market_control_task t ON t.tenant_id=s.tenant_id AND t.id=s.task_id JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id WHERE t.tenant_id="+tenant()+" AND t.symbol_id=? AND s.generated_at>=? AND s.generated_at<?",Long.class,symbol,first,end));
            if(modern.isEmpty())return original;
            Map<Long,List<Map<String,Object>>> events=new HashMap<>();Map<Long,Map<String,Object>> prefixes=new HashMap<>();
            for(Map<String,Object> prefix:db.queryForList("SELECT minute_at,body,last_event FROM market_legacy_minute_snapshot WHERE tenant_id="+tenant()+" AND symbol_id=? AND minute_at>=? AND minute_at<?",symbol,first,end)) {
                long at=((Number)prefix.get("minute_at")).longValue();prefixes.put(at,prefix);events.put(at,new ArrayList<>());
            }
            List<Map<String,Object>> samples=db.queryForList("SELECT s.generated_at,s.price FROM market_control_sample s JOIN market_control_task t ON t.tenant_id=s.tenant_id AND t.id=s.task_id LEFT JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id LEFT JOIN market_control_publication p ON p.tenant_id=t.tenant_id AND p.task_id=t.id WHERE t.tenant_id="+tenant()+" AND t.symbol_id=? AND s.generated_at>=? AND s.generated_at<? AND (f.task_id IS NULL OR f.state<>'SOURCE' OR (s.generated_at>=p.from_at AND s.generated_at<=p.to_at)) ORDER BY s.generated_at,t.id",symbol,first,end);
            for(Map<String,Object> sample:samples)events.computeIfAbsent(((Number)sample.get("generated_at")).longValue()/60000*60000,ignored->new ArrayList<>()).add(sample);
            String windowEvents="SELECT symbol_id,received_at,price,event_sequence FROM market_source_event WHERE tenant_id="+tenant()+" AND symbol_id=? AND received_at>=? AND received_at<? UNION ALL "
                +"SELECT t.symbol_id,t.received_at,t.price,0 AS event_sequence FROM market_source_tick t WHERE t.tenant_id="+tenant()+" AND t.symbol_id=? AND t.received_at>=? AND t.received_at<? "
                +"AND NOT EXISTS (SELECT 1 FROM market_source_event e WHERE e.tenant_id=t.tenant_id AND e.symbol_id=t.symbol_id AND e.source_time=t.source_time AND e.received_at=t.received_at AND e.price=t.price)";
            List<Map<String,Object>> ticks=db.queryForList("SELECT e.received_at AS generated_at,e.price,e.event_sequence FROM ("+windowEvents+") e WHERE NOT EXISTS (SELECT 1 FROM market_control_task t LEFT JOIN market_control_flow f ON f.tenant_id=t.tenant_id AND f.task_id=t.id LEFT JOIN market_control_publication p ON p.tenant_id=t.tenant_id AND p.task_id=t.id WHERE t.tenant_id="+tenant()+" AND t.symbol_id=e.symbol_id AND e.received_at>=t.started_at AND e.received_at<=COALESCE(f.finished_at, CASE WHEN f.task_id IS NOT NULL THEN ? ELSE t.ended_at END) AND (f.task_id IS NULL OR f.state<>'SOURCE' OR (e.received_at>=p.from_at AND e.received_at<=p.to_at))) ORDER BY e.received_at,e.event_sequence",symbol,first,end,symbol,first,end,Long.MAX_VALUE);
            for(Map<String,Object> tick:ticks) { List<Map<String,Object>> minute=events.get(((Number)tick.get("generated_at")).longValue()/60000*60000);if(minute!=null)minute.add(0,tick); }
            List<Map<String,Object>> result=new ArrayList<>();
            for(Map<String,Object> minute:original) {
                long at=time(minute);if(!modern.contains(at)){result.add(minute);continue;}
                List<Map<String,Object>> points=events.get(at);if(points==null)continue;
                points.sort(Comparator.<Map<String,Object>>comparingLong(row->((Number)row.get("generated_at")).longValue()).thenComparingLong(row->row.get("event_sequence") instanceof Number?((Number)row.get("event_sequence")).longValue():Long.MAX_VALUE));
                Map<String,Object> prefix=prefixes.get(at);Map<String,Object> value=prefix==null?new LinkedHashMap<>():decode((String)prefix.get("body"));
                long prefixEnd=prefix==null?Long.MIN_VALUE:((Number)prefix.get("last_event")).longValue();
                for(Map<String,Object> point:points)if(((Number)point.get("generated_at")).longValue()>prefixEnd)addPrice(value,at,number(point.get("price")));
                value.put("sourceCoverage","observed_events_only");value.put("partial",true);result.add(value);
            }
            return result;
        }
        private static void addPrice(Map<String,Object> bar,long minute,BigDecimal price) {
            if(bar.isEmpty()) { bar.put("timestamp",minute);bar.put("open_price",price);bar.put("high_price",price);bar.put("low_price",price);bar.put("volume",0);bar.put("partial",true);bar.put("controlled",true); }
            else { bar.put("high_price",number(bar.get("high_price")).max(price));bar.put("low_price",number(bar.get("low_price")).min(price)); }
            bar.put("close_price",price);
        }
    }
}
