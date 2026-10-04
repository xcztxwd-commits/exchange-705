package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gtcfesk.exchange.market.MarketSqlFixture.inTenant;

/** Differential final-state and rollback checks against the pre-optimization writer. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceCandlesBatchRegressionTest extends TenantMarketTestContext {
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
    @BeforeEach void setup() throws Exception {
        boolean mysql=System.getProperty("performance.candles.mysql.fixture")!=null;
        // Reuse one writer identity in this exclusive suite DB; every method still cleans under its fence.
        if(store==null) {
            DriverManagerDataSource ds=mysql?IdentifiedMarketMysqlFixture.open("performance.candles.mysql.fixture"):
                new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
            db=new CountingJdbc(ds);store=new ControlHistoryStore(db,new DataSourceTransactionManager(ds));
            MarketSqlFixture.schema(db);
        }
        for(long id:new long[]{99001,99002}) {
            if(mysql)IdentifiedMarketMysqlFixture.symbol(db,id,"CANDLES_"+id);
            else if(db.queryForObject("SELECT COUNT(*) FROM trading_symbol WHERE id=?",Integer.class,id)==0) db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,1)",id);
        }
        store.migrate();for(long id:new long[]{99001,99002})store.locked(id,()->{db.update("DELETE FROM market_source_candle WHERE tenant_id=1 AND symbol_id=?",id);return null;});
        db.writes=0;
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
                db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES(1,?,?,?,?,?) "
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
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("performance.candles.mysql.fixture")!=null,"MySQL strict TEXT limit is not emulated by H2");
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
        try {List<Callable<Void>> jobs=new ArrayList<>();for(int i=0;i<4;i++)jobs.add(inTenant(()->{store.sourceCandles(99002,"1m",rows(501),NOW);return null;}));
            for(Future<Void> f:pool.invokeAll(jobs))f.get(20,TimeUnit.SECONDS);
            before(rows(501),NOW);assertEquals(state(99001),state(99002));
        } finally {pool.shutdownNow();}
    }
}
