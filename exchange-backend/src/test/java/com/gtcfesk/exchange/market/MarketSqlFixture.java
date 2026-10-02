package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.tenant.TenantContext;
import java.util.concurrent.Callable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Test-only schema. Runtime code validates versioned migrations instead of creating tables. */
final class MarketSqlFixture {
    static void schema(JdbcTemplate db) {
        new ResourceDatabasePopulator(new ClassPathResource("multitenant-market-test.sql")).execute(db.getDataSource());
    }
    static <T> Callable<T> inTenant(Callable<T> work) {
        Long tenant=TenantContext.requireTenantId();
        return ()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){return work.call();}};
    }
}
