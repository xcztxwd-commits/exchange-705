package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gtcfesk.exchange.market.MarketSqlFixture.inTenant;

@EnabledIfEnvironmentVariable(named="PERF_TEST_JDBC",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/performance_test.*")
class ReceivedIndexRegressionTest extends TenantMarketTestContext {
    @Test void sameResultsAcrossIndexesAndEquivalentPrefixSkips() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource(System.getenv("PERF_TEST_JDBC"),"root","performance-test-only");
        try(java.sql.Connection jdbcConnection=ds.getConnection()) {
            SingleConnectionDataSource connection=new SingleConnectionDataSource(jdbcConnection,true);
            JdbcTemplate db=new JdbcTemplate(connection);
            MarketSqlFixture.schema(db);
            ControlHistoryStore store=new ControlHistoryStore(db,new DataSourceTransactionManager(connection));store.migrate();
            // The test database is newly created by the dedicated perf705 container, never the application database.
            for(String table:Arrays.asList("market_source_event","market_source_tick")) assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
            db.execute("CREATE TABLE perf_digit(n INT PRIMARY KEY)");
            db.update("INSERT INTO perf_digit VALUES (0),(1),(2),(3),(4),(5),(6),(7),(8),(9)");
            String numbers="SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n AS n FROM perf_digit a CROSS JOIN perf_digit b CROSS JOIN perf_digit c CROSS JOIN perf_digit d CROSS JOIN perf_digit e";
            db.update("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) SELECT 1, CONCAT('perf-',n),61+MOD(n,2),1700000000000+n*1000,1700000000000+n*1000+MOD(n,7),100+MOD(n,100)/100 FROM ("+numbers+") numbers");
            db.update("INSERT INTO market_source_tick(tenant_id,symbol_id,source_time,received_at,price) SELECT tenant_id, symbol_id,source_time,received_at,price FROM market_source_event");
            // Legacy-only rows, out-of-order arrivals and duplicate source timestamps with distinct prices.
            db.update("DELETE FROM market_source_event WHERE MOD(event_sequence,13)=0");
            db.update("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES (1,'extra',61,1700000002000,1700000099000,120.1234567890123456)");
            String query="SELECT e.received_at,e.price,e.event_sequence FROM market_source_event e WHERE e.tenant_id=1 AND e.symbol_id=61 AND e.received_at>=1700000090000 AND e.received_at<1700000100000 UNION ALL SELECT t.received_at,t.price,0 FROM market_source_tick t WHERE t.tenant_id=1 AND t.symbol_id=61 AND t.received_at>=1700000090000 AND t.received_at<1700000100000 AND NOT EXISTS(SELECT 1 FROM market_source_event e WHERE e.tenant_id=t.tenant_id AND e.symbol_id=t.symbol_id AND e.source_time=t.source_time AND e.received_at=t.received_at AND e.price=t.price) ORDER BY received_at,event_sequence";
            System.out.println("INDEX_BEFORE "+db.queryForList("EXPLAIN "+query));
            List<Map<String,Object>> expected=db.queryForList(query);assertFalse(expected.isEmpty());
            sample(db,query,"before");
            String sql;
            try (java.io.InputStream migration = getClass().getResourceAsStream("/performance/candidate-received-indexes.sql")) {
                assertNotNull(migration, "Candidate index SQL must be included in the test resources");
                sql = org.springframework.util.StreamUtils.copyToString(migration, StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
            }
            for(String statement:sql.split(";"))if(!statement.trim().isEmpty())db.execute(statement);
            assertEquals(expected,db.queryForList(query));
            System.out.println("INDEX_AFTER "+db.queryForList("EXPLAIN "+query));sample(db,query,"after");
            // Renaming demonstrates the guard checks columns, not just our preferred name.
            db.execute("ALTER TABLE market_source_event RENAME INDEX idx_source_event_received TO perf_existing_received");
            for(String statement:sql.split(";"))if(!statement.trim().isEmpty())db.execute(statement);
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND INDEX_NAME='idx_source_event_received'",Integer.class));
            assertEquals(expected,db.queryForList(query));
            System.out.println("INDEX_EQUIVALENCE exactRows="+expected.size()+" duplicateIndexGuard=true");
        }
    }
    void sample(JdbcTemplate db,String query,String phase) {
        List<Double> ms=new ArrayList<>();
        List<Map<String,Object>> start=db.queryForList("SHOW SESSION STATUS LIKE 'Handler_read%'");
        for(int i=0;i<100;i++){long t=System.nanoTime();db.queryForList(query);ms.add((System.nanoTime()-t)/1e6);}
        System.out.println("HANDLERS_"+phase+" start="+start+" end="+db.queryForList("SHOW SESSION STATUS LIKE 'Handler_read%'"));
        System.out.println("TIMINGS_"+phase+" raw_ms="+ms);Collections.sort(ms);
        System.out.println("TIMINGS_"+phase+" n=100 p50="+ms.get(49)+" p95="+ms.get(94)+" p99="+ms.get(98)+" synthetic warm sequential, NOT production latency");
    }
}
