package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Differential final-state and rollback checks against the pre-optimization writer. */
class SourceCandlesBatchRegressionTest {
    ControlHistoryStore store;
    CountingJdbc db;
    static final long NOW=1700000400000L;
    static class CountingJdbc extends JdbcTemplate {
        int writes;
        CountingJdbc(DriverManagerDataSource ds){super(ds);}
        @Override public int update(String sql,Object... args) {
            if(sql.startsWith("INSERT INTO market_source_candle")) writes++;
            return super.update(sql,args);
        }
    }
    @BeforeEach void setup() {
        String url=System.getenv("PERF_TEST_JDBC");
        if(url!=null) assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/performance_test(?:\\?.*)?"));
        DriverManagerDataSource ds=new DriverManagerDataSource(url==null ? "jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1" : url,
            url==null?"sa":"root",url==null?"":"performance-test-only");
        db=new CountingJdbc(ds);store=new ControlHistoryStore(db,new DataSourceTransactionManager(ds));
        db.execute("CREATE TABLE IF NOT EXISTS trading_symbol(id BIGINT PRIMARY KEY)");
        for(long id:new long[]{99001,99002}) {
            if(db.queryForObject("SELECT COUNT(*) FROM trading_symbol WHERE id=?",Integer.class,id)==0) db.update("INSERT INTO trading_symbol VALUES(?)",id);
        }
        store.migrate();db.update("DELETE FROM market_source_candle WHERE symbol_id IN (99001,99002)");
    }
    List<Map<String,Object>> rows(int count) {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<count;i++) {
            Map<String,Object> r=new LinkedHashMap<>();r.put("timestamp",NOW+i*60000L);
            r.put("open_price",new java.math.BigDecimal("12.1234567890123456"));
            r.put("high_price",14);r.put("low_price",11);r.put("close_price",13);r.put("volume",i);rows.add(r);
        }
        return rows;
    }
    void before(List<Map<String,Object>> rows,long now) {
        store.locked(99001,()->{
            for(Map<String,Object> row:rows) {
                Map<String,Object> copy=new LinkedHashMap<>(row);copy.put("timestamp",ControlHistoryStore.time(row));
                db.update("INSERT INTO market_source_candle(symbol_id,period,candle_at,body,received_at) VALUES(?,?,?,?,?) "
                    +"ON DUPLICATE KEY UPDATE body=VALUES(body),received_at=VALUES(received_at)",99001,"1m",ControlHistoryStore.time(row),store.encode(copy),now);
            }return null;
        });
    }
    List<Map<String,Object>> state(long id) {return db.queryForList("SELECT period,candle_at,body,received_at FROM market_source_candle WHERE symbol_id=? ORDER BY period,candle_at",id);}
    @Test void identicalRowsDuplicatesTimestampUnitsAndConfirmationTime() {
        List<Map<String,Object>> input=rows(1001);
        input.get(0).put("timestamp",NOW/1000); // Original seconds-to-milliseconds conversion.
        input.add(new LinkedHashMap<>(input.get(0)));input.get(1001).put("close_price",12);
        before(input,NOW);int oldWrites=db.writes;db.writes=0;
        store.sourceCandles(99002,"1m",input,NOW);
        assertEquals(state(99001),state(99002));
        assertEquals(Boolean.getBoolean("performance.baseline")?1002:3,db.writes);
        System.out.println("CANDLES rows=1002 oldWrites="+oldWrites+" currentWrites="+db.writes+" exactState=true");
        before(input,NOW+60000);store.sourceCandles(99002,"1m",input,NOW+60000);
        assertEquals(state(99001),state(99002));
        assertEquals(1001,db.queryForObject("SELECT COUNT(*) FROM market_source_candle WHERE symbol_id=99002 AND received_at=?",Integer.class,NOW+60000));
    }
    @Test void emptyAndBatchBoundaries() {
        for(int count:new int[]{0,1,499,500,501}) {
            before(rows(count),NOW);store.sourceCandles(99002,"1m",rows(count),NOW);assertEquals(state(99001),state(99002));
        }
    }
    @Test void failureAfterFirstChunkRollsBackEverything() {
        before(rows(1),NOW);store.sourceCandles(99002,"1m",rows(1),NOW);
        List<Map<String,Object>> prior=state(99002),bad=rows(501);bad.get(500).put("timestamp",null);
        assertThrows(RuntimeException.class,()->before(bad,NOW+60000));
        assertThrows(RuntimeException.class,()->store.sourceCandles(99002,"1m",bad,NOW+60000));
        assertEquals(prior,state(99002));assertEquals(state(99001),state(99002));
    }
    @Test void mysqlStatementFailureAfterFirstChunkIsAtomic() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getenv("PERF_TEST_JDBC")!=null,"MySQL strict TEXT limit is not emulated by H2");
        assertTrue(db.queryForObject("SELECT @@sql_mode",String.class).contains("STRICT"));
        before(rows(1),NOW);store.sourceCandles(99002,"1m",rows(1),NOW);
        List<Map<String,Object>> prior=state(99002),bad=rows(501);
        bad.get(500).put("oversize",String.join("",Collections.nCopies(70000,"x")));
        assertThrows(org.springframework.dao.DataAccessException.class,()->before(bad,NOW+60000));
        assertThrows(org.springframework.dao.DataAccessException.class,()->store.sourceCandles(99002,"1m",bad,NOW+60000));
        assertEquals(prior,state(99002));assertEquals(state(99001),state(99002));
    }
    @Test void concurrentIdenticalRetryKeepsOneRowPerKey() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {List<Callable<Void>> jobs=new ArrayList<>();for(int i=0;i<4;i++)jobs.add(()->{store.sourceCandles(99002,"1m",rows(501),NOW);return null;});
            for(Future<Void> f:pool.invokeAll(jobs))f.get(20,TimeUnit.SECONDS);
            before(rows(501),NOW);assertEquals(state(99001),state(99002));
        } finally {pool.shutdownNow();}
    }
}
