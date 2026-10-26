package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistorySourceRestoreTest extends TenantMarketTestContext {
    ControlHistoryStore store;
    HistorySourceRestore restore;
    TradingSymbol config;
    long minute=System.currentTimeMillis()/3600000*3600000-4*3600000;
    @BeforeEach void setup() {
        DriverManagerDataSource data=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        store=new ControlHistoryStore(new JdbcTemplate(data),new DataSourceTransactionManager(data)); MarketSqlFixture.schema(store.db);
        store.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1)"); store.migrate();
        for(String column:Arrays.asList("symbol VARCHAR(32) DEFAULT 'JPY=X'","alltick_symbol VARCHAR(64)","market_source VARCHAR(16) DEFAULT 'yahoo'","source_category VARCHAR(32) DEFAULT 'Forex'","random_market_enabled BOOLEAN DEFAULT FALSE")) store.db.execute("ALTER TABLE trading_symbol ADD "+column);
        restore=new HistorySourceRestore(store,mock(ControlAuditService.class));
        config=new TradingSymbol(); config.setTenantId(1L); config.setId(1L); config.setSymbol("JPY=X"); config.setSourceCategory("Forex"); config.setMarketSource("yahoo");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("9","",AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    Map<String,Object> candle(long at,String low) {
        Map<String,Object> row=new LinkedHashMap<>(); row.put("timestamp",at); row.put("open_price",new BigDecimal("158.22100000000001")); row.put("high_price",new BigDecimal("158.236")); row.put("low_price",new BigDecimal(low)); row.put("close_price",new BigDecimal("158.229")); row.put("volume",BigDecimal.ZERO); return row;
    }
    void seed(long at,boolean mixed) {
        store.db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES(1,1,'1m',?,?,?)",at,store.encode(candle(at,"158.2100067138672")),at+60000);
        if(mixed) store.db.update("INSERT INTO market_mixed_minute(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?)",at,store.encode(candle(at,"157.321")),at+59000);
    }
    String preview(long from,long to) { return (String)restore.preview(config,"Yahoo",from,to,"Asia/Singapore").get("previewToken"); }
    String accept(String token) { return (String)restore.accept(1,token,"restore_key_"+token.replace("-",""),"Yahoo:Forex:JPY=X").get("id"); }
    Map<String,Object> merged(String period,int count,long cursor) {
        Map<String,Object> data=new HashMap<>(); data.put("kline_list",Collections.emptyList()); Map<String,Object> envelope=new HashMap<>(); envelope.put("data",data);
        return new ControlledKlineMerger(store).merge(1,period,count,cursor,envelope,ignored->{},true);
    }
    @Test void singleMinuteSurvivesReadsRestartAndUndoWithoutChangingEvidence() {
        seed(minute,true); seed(minute+60000,false);
        String mixed=store.db.queryForObject("SELECT body FROM market_mixed_minute",String.class);
        String token=preview(minute,minute),id=accept(token); assertEquals(id,accept(token)); restore.runOne();
        assertEquals("COMPLETED",restore.query(1,id,null).get("state")); assertEquals(1L,store.historyRestoreRevision(1));
        Map<String,Object> bar=ControlHistoryStore.rows(merged("1m",2,minute+60000)).get(0);
        assertEquals(new BigDecimal("158.2100067138672"),bar.get("low_price")); assertFalse(Boolean.TRUE.equals(bar.get("partial")));
        assertEquals(new BigDecimal("158.22100000000001"),bar.get("open_price"));
        for(String period:Arrays.asList("5m","15m","1h")) {
            Map<String,Object> aggregate=ControlHistoryStore.rows(merged(period,2,minute+60000)).get(0);
            assertEquals(new BigDecimal("158.2100067138672"),aggregate.get("low_price"));
        }
        assertEquals(mixed,store.db.queryForObject("SELECT body FROM market_mixed_minute",String.class));
        // Recreated service uses the durable ledger; a late old producer cannot reclaim display priority.
        HistorySourceRestore restarted=new HistorySourceRestore(store,mock(ControlAuditService.class));
        store.db.update("UPDATE market_mixed_minute SET body=?",store.encode(candle(minute,"156")));
        assertEquals(new BigDecimal("158.2100067138672"),ControlHistoryStore.rows(merged("1m",2,minute+60000)).get(0).get("low_price"));
        String undo=(String)restarted.undoPreview(1,id).get("previewToken"); accept(undo); restarted.runOne();
        assertEquals(new BigDecimal("157.321"),ControlHistoryStore.rows(merged("1m",2,minute+60000)).get(0).get("low_price"));
        assertEquals(2L,store.historyRestoreRevision(1)); assertThrows(RuntimeException.class,()->restore.undoPreview(1,id));
        java.util.concurrent.CompletableFuture.runAsync(()-> { try(TenantContext.Scope ignored=TenantContext.open(2L)) { assertTrue(restore.list(1).isEmpty()); assertThrows(RuntimeException.class,()->restore.query(1,id,null)); } }).join();
    }
    @Test void missingPartialFutureAndStalePreviewNeverPublish() {
        seed(minute,true);
        assertEquals("MISSING_SOURCE",restore.preview(config,"Yahoo",minute,minute+60000,"UTC").get("state"));
        store.db.update("UPDATE market_source_candle SET received_at=?",minute+1);
        assertEquals("MISSING_SOURCE",restore.preview(config,"Yahoo",minute,minute,"UTC").get("state"));
        assertThrows(RuntimeException.class,()->restore.preview(config,"Yahoo",System.currentTimeMillis()/60000*60000,System.currentTimeMillis()/60000*60000,"UTC"));
        store.db.update("UPDATE market_source_candle SET received_at=?",minute+60000);
        String token=preview(minute,minute); store.db.update("UPDATE market_mixed_minute SET body=?",store.encode(candle(minute,"155")));
        assertEquals(token,accept(token)); assertEquals("REJECTED",restore.query(1,token,null).get("state")); assertEquals(token,accept(token)); assertEquals(0L,store.historyRestoreRevision(1));
    }
    @Test void finishedControlUsesActualEndInsteadOfLaterStopOrOriginalPlan() {
        seed(minute,true);
        long end=minute+20000,stop=minute+45000;
        store.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,ended_at,stop_at,sampled_until) VALUES(1,'finished',1,'JPY=X',2,'TARGET','COMPLETED',158,159,20,1,false,16,'LIVE',?,?,?,?,?,?)",minute,minute,end,end,stop,end);
        store.db.update("INSERT INTO market_control_flow(tenant_id,task_id,options_json,state,last_price,last_at,finished_at) VALUES(1,'finished','{}','SOURCE',159,?,?)",stop,stop);
        String token=preview(minute,minute);
        // Emergency stop deliberately discards the unsampled remainder of the original plan.
        store.db.update("UPDATE market_control_task SET status='STOPPED',planned_end=? WHERE id='finished'",minute+300000);
        accept(token); restore.runOne();
        assertEquals("COMPLETED",restore.query(1,token,null).get("state"));
        assertEquals(1L,store.historyRestoreRevision(1));
        String undo=(String)restore.undoPreview(1,token).get("previewToken"); accept(undo); restore.runOne();
        assertEquals("READY",restore.preview(config,"Yahoo",minute,minute,"UTC").get("state"));
    }
    @Test void unfinishedSamplingAndFinalizationStillBlockRestore() {
        seed(minute,true);
        store.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,sampled_until) VALUES(1,'pending',1,'JPY=X',2,'TARGET','RUNNING',158,159,300,1,false,16,'LIVE',?,?,?,?)",minute,minute,minute+300000,minute+1000);
        store.db.update("INSERT INTO market_control_flow(tenant_id,task_id,options_json,state,last_price,last_at) VALUES(1,'pending','{}','TARGET',159,?)",minute+1000);
        assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->preview(minute,minute));
        store.db.update("UPDATE market_control_task SET status='COMPLETED',ended_at=?,sampled_until=? WHERE id='pending'",minute+20000,minute+20000);
        for(String state:Arrays.asList("WAITING_SOURCE","RECOVERING")) {
            store.db.update("UPDATE market_control_flow SET state=? WHERE task_id='pending'",state);
            assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->preview(minute,minute));
        }
        store.db.update("UPDATE market_control_flow SET state='SOURCE',finished_at=?,history_pending_until=? WHERE task_id='pending'",minute+45000,minute+20000);
        assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->preview(minute,minute));
        store.db.update("UPDATE market_control_flow SET history_pending_until=NULL WHERE task_id='pending'");
        assertEquals("READY",restore.preview(config,"Yahoo",minute,minute,"UTC").get("state"));
    }
    @Test void batchesResumeAndFailedBatchKeepsExactProgress() {
        for(int i=0;i<201;i++) seed(minute+i*60000L,true);
        String token=preview(minute,minute+200*60000L),id=accept(token); restore.runOne();
        assertEquals(100L,((Number)restore.query(1,id,null).get("completed")).longValue());
        store.db.update("UPDATE market_source_candle SET body=? WHERE candle_at=?",store.encode(candle(minute+150*60000L,"150")),minute+150*60000L);
        restore.runOne(); assertEquals("FAILED",restore.query(1,id,null).get("state")); assertEquals(100L,((Number)restore.query(1,id,null).get("completed")).longValue());
        store.db.update("UPDATE market_source_candle SET body=? WHERE candle_at=?",store.encode(candle(minute+150*60000L,"158.2100067138672")),minute+150*60000L);
        restore.retry(1,id); restore.runOne(); restore.runOne(); assertEquals("COMPLETED",restore.query(1,id,null).get("state"));
        assertEquals(201L,((Number)restore.query(1,id,null).get("completed")).longValue());
    }
    @Test void filtersQuoteTimeSnapshotsBeforeSmallPageLimit() {
        for(int i=0;i<10;i++) seed(minute+i*60000L,false);
        for(int i=0;i<8;i++) { long at=minute+9*60000L+i+1; store.db.update("INSERT INTO market_source_candle VALUES(1,1,'1m',?,?,?)",at,store.encode(candle(at,"158.21")),at+60000); }
        List<Map<String,Object>> rows=ControlHistoryStore.rows(merged("1m",2,minute+9*60000L+100)); assertEquals(2,rows.size()); assertEquals(minute+8*60000L,ControlHistoryStore.time(rows.get(0)));
    }
}
