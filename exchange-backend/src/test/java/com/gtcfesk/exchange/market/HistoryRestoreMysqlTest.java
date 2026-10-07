package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.entity.TradingSymbol;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="HISTORY_RESTORE_MYSQL_URL",matches=".+")
class HistoryRestoreMysqlTest extends TenantMarketTestContext {
    @Test void nativeWriterCommitsImmutableSnapshotsAndTenantForeignKeys() {
        DriverManagerDataSource ds=new DriverManagerDataSource(System.getenv("HISTORY_RESTORE_MYSQL_URL"),"root",System.getenv("HISTORY_RESTORE_MYSQL_PASSWORD"));
        ControlHistoryStore store=new ControlHistoryStore(new JdbcTemplate(ds),new DataSourceTransactionManager(ds)); store.migrate();
        HistorySourceRestore restore=new HistorySourceRestore(store,mock(ControlAuditService.class));
        TradingSymbol symbol=new TradingSymbol(); symbol.setTenantId(1L); symbol.setId(1L); symbol.setSymbol("JPY=X"); symbol.setMarketSource("yahoo"); symbol.setSourceCategory("Forex");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("9","",AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        try {
            long at=System.currentTimeMillis()/60000*60000-600000;
            Map<String,Object> source=new LinkedHashMap<>(); source.put("timestamp",at); source.put("open_price",new BigDecimal("158.221")); source.put("high_price",new BigDecimal("158.236")); source.put("low_price",new BigDecimal("158.2100067138672")); source.put("close_price",new BigDecimal("158.229")); source.put("volume",BigDecimal.ZERO);
            Map<String,Object> mixed=new LinkedHashMap<>(source); mixed.put("low_price",new BigDecimal("157.321"));
            store.locked(1,()-> { store.db.update("INSERT INTO market_source_candle VALUES(1,1,'1m',?,?,?)",at,store.encode(source),at+60000); store.db.update("INSERT INTO market_mixed_minute VALUES(1,1,?,?,?)",at,store.encode(mixed),at+59000); return null; });
            String token=(String)restore.preview(symbol,"Yahoo",at,at,"Asia/Singapore").get("previewToken");
            restore.accept(1,token,"native_restore_001","Yahoo:Forex:JPY=X"); restore.runOne();
            assertEquals("COMPLETED",restore.query(1,token,null).get("state")); assertEquals(HistorySourceRestore.prices(source),HistorySourceRestore.prices(store.historyOverrides(1,at,at).get(at)));
            assertThrows(RuntimeException.class,()->store.db.update("UPDATE market_history_restore_minute SET source_json='{}' WHERE tenant_id=1 AND job_id=?",token));
            assertThrows(RuntimeException.class,()->store.db.update("DELETE FROM market_history_restore_minute WHERE tenant_id=1 AND job_id=?",token));
            assertThrows(RuntimeException.class,()->store.db.update("INSERT INTO market_history_restore_job(tenant_id,id,symbol_id,kind,state,source_identity,from_at,to_at,timezone,total,actor_id,created_at,expires_at) VALUES(2,?,1,'RESTORE','PREVIEW','Yahoo:Forex:JPY=X',?,?,'UTC',1,9,0,0)",UUID.randomUUID().toString(),at,at));
            String undo=(String)restore.undoPreview(1,token).get("previewToken");
            // A prepared minute cannot be activated through a bare JDBC update with no real writer scope.
            assertThrows(RuntimeException.class,()->store.db.update("UPDATE market_history_restore_minute SET effective_version=2 WHERE tenant_id=1 AND job_id=?",undo));
            restore.accept(1,undo,"native_undo_00101","Yahoo:Forex:JPY=X"); restore.runOne();
            assertEquals(HistorySourceRestore.prices(mixed),HistorySourceRestore.prices(store.historyOverrides(1,at,at).get(at))); assertEquals(2L,store.historyRestoreRevision(1));
            Map<String,Object> fetched=new HashMap<>(),data=new HashMap<>(),next=new LinkedHashMap<>(source); next.put("timestamp",at+60000); data.put("kline_list",Collections.singletonList(next)); fetched.put("data",data);
            assertEquals(1,restore.insertSource(symbol,"Yahoo",at+60000,at+60000,fetched,System.currentTimeMillis()));
            assertEquals(0,restore.insertSource(symbol,"Yahoo",at+60000,at+60000,fetched,System.currentTimeMillis()));
        } finally { SecurityContextHolder.clearContext(); }
    }
}
