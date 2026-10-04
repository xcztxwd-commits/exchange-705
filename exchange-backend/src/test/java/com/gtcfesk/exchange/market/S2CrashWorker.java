package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Test-only child process. A real abrupt death after JDBC commit, not an exception simulation. */
public final class S2CrashWorker {
    public static void main(String[] args) throws Exception {
        S2RuntimeMysqlTest.identityForCrashWorker();
        try(TenantContext.Scope scope=TenantContext.open(1L)){
            ControlHistoryStore store=new ControlHistoryStore(new JdbcTemplate(S2RuntimeMysqlTest.data),new DataSourceTransactionManager(S2RuntimeMysqlTest.data));
            PersistentPriceControl controls=new PersistentPriceControl(store);TradingSymbol symbol=new TradingSymbol();symbol.setTenantId(1L);symbol.setId(Long.valueOf(args[0]));symbol.setSymbol("CRASH");symbol.setPricePrecision(2);
            long now=Long.parseLong(args[1]);controls.pump(symbol,new S2RuntimeMysqlTest().raw(now,false),now,60000);
            System.out.println("S2_COMMITTED_BEFORE_PROCESS_DEATH generation="+store.db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId()));System.out.flush();
            Runtime.getRuntime().halt(73);
        }
    }
}
