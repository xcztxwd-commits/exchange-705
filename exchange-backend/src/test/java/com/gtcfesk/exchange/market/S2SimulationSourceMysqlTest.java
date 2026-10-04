package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Real producer, committed random-session cursor and DB-only consumers on this run's owned MySQL. */
class S2SimulationSourceMysqlTest {
    @BeforeAll static void ownedMysql() throws Exception { S2RuntimeMysqlTest.identity(); }
    static final class FailingJdbc extends JdbcTemplate {
        int writes, failAt, dml;
        Runnable afterCursor;
        FailingJdbc(){super(S2RuntimeMysqlTest.data);}
        @Override public int update(String sql,Object... args){
            dml++;
            if(sql.startsWith("INSERT INTO market_simulation_source_candle") && ++writes==failAt)
                throw new IllegalStateException("injected simulation second block before commit");
            return super.update(sql,args);
        }
        @Override public <T> T queryForObject(String sql,Class<T> type,Object... args){
            T result=super.queryForObject(sql,type,args);
            if(sql.startsWith("SELECT MAX(candle_at) FROM market_simulation_source_candle") && afterCursor!=null){
                Runnable hook=afterCursor;afterCursor=null;hook.run();
            }
            return result;
        }
    }
    FailingJdbc db;JdbcTemplate independent;ControlHistoryStore store;PersistentPriceControl controls;
    TradingSymbol symbol;ForexQuoteMarketService market;TenantContext.Scope scope;long now,session;
    @BeforeEach void setup(){
        scope=TenantContext.open(1L);db=new FailingJdbc();independent=new JdbcTemplate(S2RuntimeMysqlTest.data);
        store=new ControlHistoryStore(db,new DataSourceTransactionManager(S2RuntimeMysqlTest.data));controls=new PersistentPriceControl(store);
        S2CommandAcceptanceTest helper=new S2CommandAcceptanceTest();helper.store=store;symbol=helper.newSymbol(1L);
        now=System.currentTimeMillis()/60000*60000-60000+30000;session=now-86400000;
        symbol.setRandomMarketEnabled(true);symbol.setRandomMarketStartedAt(session);symbol.setRandomMarketBasePrice(new BigDecimal("90.00"));
        market=helper.newMarket(store,controls,symbol);ReflectionTestUtils.setField(market,"virtualTrading",true);
        ReflectionTestUtils.setField(market,"klineMerger",new ControlledKlineMerger(store));
    }
    @AfterEach void close(){market.stop();scope.close();}
    List<Map<String,Object>> rows(long at){return independent.query(
        "SELECT body FROM market_simulation_source_candle WHERE tenant_id=1 AND symbol_id=? AND session_at=? AND period='1m' ORDER BY candle_at",
        (r,n)->store.decode(r.getString(1)),symbol.getId(),at);}
    long cursor(long at){return independent.queryForObject("SELECT MAX(candle_at) FROM market_simulation_source_candle WHERE tenant_id=1 AND symbol_id=? AND session_at=? AND period='1m'",Long.class,symbol.getId(),at);}
    void turn(long time){db.writes=0;market.completeControls(time);assertTrue(db.writes<=512,"one physical turn must attempt at most 512 source minutes");}
    void tradingQuote(long time){
        Map<String,Object> quote=store.decode(independent.queryForObject("SELECT quote_json FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",String.class,symbol.getId()));
        assertEquals(true,quote.get("available"));assertEquals(true,quote.get("tradeAvailable"));assertEquals(false,quote.get("engineLag"));
        assertTrue(QuoteState.time(quote.get("timestamp"))<=time);assertTrue(QuoteState.time(quote.get("executionExpiresAt"))>time);
        assertEquals(0,RandomMarketPath.basePrice(symbol,time/1000*1000).compareTo(ControlHistoryStore.number(quote.get("price"))));
        Map<String,Object> read=controls.display(symbol,Collections.emptyMap(),time);
        assertEquals(true,read.get("available"));assertEquals(true,read.get("tradeAvailable"));assertEquals(false,read.get("engineLag"));
    }
    static void sameBars(List<Map<String,Object>> expected,List<Map<String,Object>> actual){
        assertEquals(expected.size(),actual.size(),"no holes, future rows or omitted source-only bars");
        for(int i=0;i<expected.size();i++){
            Map<String,Object> left=expected.get(i),right=actual.get(i);assertEquals(ControlHistoryStore.time(left),ControlHistoryStore.time(right));
            for(String field:Arrays.asList("open_price","high_price","low_price","close_price","volume"))
                assertEquals(0,ControlHistoryStore.number(left.get(field)).compareTo(ControlHistoryStore.number(right.get(field))),field+" at "+ControlHistoryStore.time(left));
        }
    }
    @Test void twentyFourHourGapCommitsThreeBoundedBlocksAndSecondRollbackRetriesExactDatabaseCursor(){
        long firstMinute=Math.floorDiv(session,60000)*60000;
        turn(now);assertEquals(512,db.writes);assertEquals(512,rows(session).size());assertEquals(firstMinute+511*60000,cursor(session));
        tradingQuote(now);
        List<Map<String,Object>> first=rows(session);Map<String,Object> snapshot=independent.queryForMap("SELECT snapshot_version,quote_json,status_json FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId());
        long sourceEvents=independent.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId());
        db.failAt=256;turn(now+1000);assertEquals(256,db.writes);assertEquals(first,rows(session));assertEquals(firstMinute+511*60000,cursor(session));
        assertEquals(snapshot,independent.queryForMap("SELECT snapshot_version,quote_json,status_json FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId()));
        assertEquals(sourceEvents,independent.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId()));
        db.failAt=0;turn(now);assertEquals(512,db.writes);assertEquals(1023,rows(session).size());assertEquals(firstMinute+1022*60000,cursor(session));
        turn(now);assertEquals(419,db.writes);assertEquals(1441,rows(session).size());assertEquals(Math.floorDiv(now,60000)*60000,cursor(session));
        tradingQuote(now);
        assertEquals(0,independent.queryForObject("SELECT COUNT(*) FROM market_simulation_source_candle WHERE tenant_id=1 AND symbol_id=? AND session_at=? AND (candle_at<? OR candle_at>?)",Integer.class,symbol.getId(),session,firstMinute,now));
        assertEquals(1441,independent.queryForObject("SELECT COUNT(DISTINCT candle_at) FROM market_simulation_source_candle WHERE tenant_id=1 AND symbol_id=? AND session_at=?",Integer.class,symbol.getId(),session));
        assertEquals(0,independent.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));
        assertEquals(0,independent.queryForObject("SELECT COUNT(*) FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));
        long pageEnd=firstMinute+1000*60000-1000;
        List<Map<String,Object>> firstPage=ControlHistoryStore.rows(RandomMarketPath.klines(symbol,"1m",1000,pageEnd,now));
        List<Map<String,Object>> lastPage=ControlHistoryStore.rows(RandomMarketPath.klines(symbol,"1m",441,now,now));
        List<Map<String,Object>> expected=new ArrayList<>(firstPage);expected.addAll(lastPage);sameBars(expected,rows(session));
        List<Map<String,Object>> committed=rows(session);turn(now);assertEquals(1,db.writes);assertEquals(committed,rows(session));
        int written=db.writes,dml=db.dml;
        sameBars(firstPage,ControlHistoryStore.rows(market.historicalKline(symbol.getSymbol(),"1m",1000,pageEnd)));
        sameBars(lastPage,ControlHistoryStore.rows(market.historicalKline(symbol.getSymbol(),"1m",441,now)));
        assertEquals(written,db.writes,"GET must not materialize source candles");assertEquals(dml,db.dml,"GET must perform zero DML");assertEquals(committed,rows(session));
        long nextSession=now-120000;symbol.setRandomMarketStartedAt(nextSession);symbol.setRowVersion(symbol.getRowVersion()+1);market.refreshSymbols();
        turn(now);assertEquals(3,rows(nextSession).size());assertEquals(committed,rows(session),"old session remains immutable");
        sameBars(ControlHistoryStore.rows(RandomMarketPath.klines(symbol,"1m",100,now,now)),
            ControlHistoryStore.rows(market.historicalKline(symbol.getSymbol(),"1m",100,now)));
        long fresh=System.currentTimeMillis();turn(fresh);tradingQuote(fresh);dml=db.dml;
        Map<String,Object> price=market.internalPrice(symbol.getSymbol());assertEquals(true,price.get("available"));assertEquals(true,price.get("tradeAvailable"));assertEquals(false,price.get("engineLag"));
        assertTrue(QuoteState.time(price.get("timestamp"))<=System.currentTimeMillis());assertEquals(dml,db.dml,"quote consumer must perform zero DML");
    }
    @Test void lateRevisionOrSessionChangeCannotPublishPreparedOldSourceWindow(){
        db.afterCursor=()->symbol.setRowVersion(symbol.getRowVersion()+1);turn(now);assertEquals(0,db.writes);assertTrue(rows(session).isEmpty());
        db.afterCursor=()->symbol.setRandomMarketStartedAt(now-120000);turn(now);assertEquals(0,db.writes);assertTrue(rows(session).isEmpty());assertTrue(rows(now-120000).isEmpty());
        assertEquals(0,independent.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));
        market.refreshSymbols();turn(now);assertEquals(3,rows(now-120000).size());
        sameBars(ControlHistoryStore.rows(RandomMarketPath.klines(symbol,"1m",100,now,now)),rows(now-120000));
    }
}
