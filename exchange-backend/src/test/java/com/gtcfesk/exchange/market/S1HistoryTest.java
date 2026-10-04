package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class S1HistoryTest extends TenantMarketTestContext {
    static final String LEGACY = "SELECT symbol_id,source_time,received_at,price,event_sequence FROM market_source_event WHERE tenant_id=1 UNION ALL "
        + "SELECT t.symbol_id,t.source_time,t.received_at,t.price,0 AS event_sequence FROM market_source_tick t WHERE t.tenant_id=1 "
        + "AND NOT EXISTS (SELECT 1 FROM market_source_event e WHERE e.tenant_id=t.tenant_id AND e.symbol_id=t.symbol_id AND e.source_time=t.source_time AND e.received_at=t.received_at AND e.price=t.price)";
    @Test void legacyAndBoundedWindowsPreserveAllFieldsAndFrozenPrefix() {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:s1diff_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        JdbcTemplate db=new JdbcTemplate(ds);MarketSqlFixture.schema(db);
        ControlHistoryStore store=new ControlHistoryStore(db,new DataSourceTransactionManager(ds));
        db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1),(2,1),(3,2)");
        Random random=new Random(1701);long minute=1700000400000L;
        for(int i=0;i<400;i++) {
            long source=minute+(random.nextInt(5)-1)*15000L, received=minute+(random.nextInt(7)-1)*15000L;
            int symbol=i%7==0?2:1, tenant=i%11==0?2:1;
            BigDecimal price=new BigDecimal("100.1234567890123456").add(BigDecimal.valueOf(i));
            db.update("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(?,?,?,?,?,?)",tenant,"e"+i,symbol,source,received,price);
            if(i%2==0) db.update("INSERT INTO market_source_tick(tenant_id,symbol_id,source_time,received_at,price) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE source_time=VALUES(source_time)",tenant,symbol,source,received,price);
        }
        db.update("DELETE FROM market_source_event WHERE MOD(event_sequence,13)=0");
        for(int offset=1;offset<60;offset++) {
            long cutoff=minute+offset*1000L, received=minute-1;
            List<Map<String,Object>> before=db.queryForList("SELECT received_at,price FROM ("+LEGACY+") ticks WHERE symbol_id=? AND source_time>=? AND source_time<? AND received_at>? AND received_at<=? ORDER BY received_at,event_sequence",1,minute,minute+60000,received,cutoff);
            String bounded=ControlHistoryStore.sourceEvents("symbol_id=? AND source_time>=? AND source_time<? AND received_at>? AND received_at<=?",false);
            List<Map<String,Object>> after=db.queryForList("SELECT received_at,price FROM ("+bounded+") ticks ORDER BY received_at,event_sequence",1,minute,minute+60000,received,cutoff,1,minute,minute+60000,received,cutoff);
            assertEquals(before,after,"freeze "+offset);
            List<Map<String,Object>> oldHold=db.queryForList("SELECT price,source_time FROM ("+LEGACY+") ticks WHERE symbol_id=? AND received_at<=? AND source_time<=? ORDER BY source_time DESC,received_at DESC,event_sequence DESC LIMIT 1",1,cutoff,cutoff);
            List<Map<String,Object>> newHold=db.queryForList("SELECT price,source_time FROM ("+ControlHistoryStore.sourceEvents("symbol_id=? AND received_at<=? AND source_time<=?",true)+") ticks ORDER BY source_time DESC,received_at DESC,event_sequence DESC LIMIT 1",1,cutoff,cutoff,1,cutoff,cutoff);
            assertEquals(oldHold,newHold,"hold "+offset);
            db.update("DELETE FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=1");
            for(Map<String,Object> tick:before)store.point(1,((Number)tick.get("received_at")).longValue(),ControlHistoryStore.number(tick.get("price")),true);
            List<Map<String,Object>> expected=db.queryForList("SELECT * FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=1 ORDER BY minute_at");
            db.update("DELETE FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=1");
            store.locked(1,()->{store.freeze(1,cutoff);return null;});
            assertEquals(expected,db.queryForList("SELECT * FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=1 ORDER BY minute_at"));
        }
        System.out.println("S1_DIFFERENTIAL 59 freeze/hold windows, legacy dedup, sequence ties, late arrivals, tenants, full mixed-minute rows equal");
    }
}
