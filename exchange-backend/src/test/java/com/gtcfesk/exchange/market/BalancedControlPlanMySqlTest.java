package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Run only against a disposable test database, never the application database. */
class BalancedControlPlanMySqlTest {
    @Test @EnabledIfEnvironmentVariable(named = "V3_MYSQL_URL", matches = ".+")
    void planAndSamplesRoundTripOnMySql() {
        DriverManagerDataSource data = new DriverManagerDataSource(System.getenv("V3_MYSQL_URL"),
                System.getenv("V3_MYSQL_USER"), System.getenv("V3_MYSQL_PASSWORD"));
        ControlHistoryStore store = new ControlHistoryStore(new JdbcTemplate(data), new DataSourceTransactionManager(data));
        store.db.execute("CREATE TABLE trading_symbol(id BIGINT PRIMARY KEY)");
        store.db.update("INSERT INTO trading_symbol(id) VALUES(1)");
        store.migrate();
        PersistentPriceControl controls = new PersistentPriceControl(store);
        TradingSymbol symbol = new TradingSymbol(); symbol.setId(1L); symbol.setSymbol("V3MYSQL"); symbol.setPricePrecision(2);
        BigDecimal start = new BigDecimal("100000.00"), target = new BigDecimal("100060.00");
        long now = System.currentTimeMillis();
        Map<String, Object> quote = new HashMap<>(); quote.put("price", start); quote.put("timestamp", now);
        quote.put("sourceTimestamp", now); quote.put("available", true);
        PersistentPriceControl.Prepared prepared = controls.prepare(symbol, quote, start, 60, target, 10, true);
        PersistentPriceControl.Task task = controls.startPrepared(symbol, quote, start, 60, target, 10, true, "mysql-v3", null, prepared);
        assertEquals(3, task.algorithmVersion);
        assertEquals(1, store.db.queryForObject("SELECT COUNT(*) FROM market_control_plan", Integer.class));
        assertEquals(1, store.db.queryForObject("SELECT COUNT(*) FROM market_control_sample", Integer.class));
        ControlHistoryStore reloaded = new ControlHistoryStore(new JdbcTemplate(data), new DataSourceTransactionManager(data));
        assertEquals(target, new PersistentPriceControl(reloaded).latest(1).price(task.plannedEnd));
    }
}
