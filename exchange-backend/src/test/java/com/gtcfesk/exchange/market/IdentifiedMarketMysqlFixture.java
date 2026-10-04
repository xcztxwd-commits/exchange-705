package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.tenant.DedicatedMysqlFixture;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only identified/restored MySQL transport. Each property must name its own exclusive database. */
final class IdentifiedMarketMysqlFixture {
    static DriverManagerDataSource open(String property)throws Exception {
        DriverManagerDataSource data=DedicatedMysqlFixture.fromProperty(property);
        JdbcTemplate db=new JdbcTemplate(data);
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("mt705_probe_"));
        return data;
    }
    static void symbol(JdbcTemplate db,long id,String code) {
        if(db.queryForObject("SELECT COUNT(*) FROM trading_symbol WHERE tenant_id=1 AND id=?",Integer.class,id)>0)return;
        boolean full=db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='trading_symbol' AND column_name='symbol'",Integer.class)>0;
        if(full)db.update("INSERT INTO trading_symbol(tenant_id,id,symbol,name,base_currency,quote_currency,market_source,source_category,category,is_enabled,control_enabled,price_precision,row_version) VALUES(1,?,?,?,'TEST','USD','yahoo','Metal','Metal',1,1,8,0)",id,code,code);
        else db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,1)",id);
    }
    static void probeTask(ControlHistoryStore store,PersistentPriceControl.Task task) {
        store.locked(task.symbolId,()->{
            store.db.update("DELETE FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=?",task.symbolId);
            store.db.update("DELETE FROM market_control_hold WHERE tenant_id=1 AND task_id=?",task.id);
            int found=store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND id=?",Integer.class,task.id);
            if(found==0)store.db.update("INSERT INTO market_control_task(tenant_id,id,symbol_id,symbol,algorithm_version,kind,status,start_price,target_price,duration_seconds,intensity,oscillation,price_precision,start_source,source_time,started_at,planned_end,sampled_until) VALUES(1,?,?,?,1,'TARGET','COMPLETED',?,?,30,10,false,8,'PROBE',?,?,?,?)",task.id,task.symbolId,"PAIR",task.startPrice,task.targetPrice,task.sourceTime,task.startedAt,task.plannedEnd,task.plannedEnd);
            new ControlHoldService(store).prepare(task,Collections.emptyMap());
            return null;
        });
    }
}
