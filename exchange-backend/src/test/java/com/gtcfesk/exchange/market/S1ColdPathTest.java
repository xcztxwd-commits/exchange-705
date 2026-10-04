package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in diagnostic on the original owned million-event snapshot; never an application boot. */
class S1ColdPathTest extends TenantMarketTestContext {
    @Test void locateEveryColdBusinessSqlAndPreserveCommittedResults() throws Exception {
        String url=System.getenv("S1_COLD_JDBC");
        Assumptions.assumeTrue(url!=null,"explicit S1 owned cold-path fixture required");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:33329/performance_test(?:\\?.*)?"));
        boolean bounded=Boolean.parseBoolean(System.getenv("S1_COLD_ASSERT_BOUND"));
        Map<String,Object> evidence=new LinkedHashMap<>();
        try(Connection raw=new DriverManagerDataSource(url,"root","performance-test-only").getConnection()) {
            JdbcTemplate metrics=new JdbcTemplate(new SingleConnectionDataSource(raw,true));
            assertEquals(System.getenv("S1_COLD_UUID"),metrics.queryForObject("SELECT @@server_uuid",String.class));
            evidence.put("server",metrics.queryForMap("SELECT VERSION() version,@@server_uuid uuid,@@tx_isolation isolation_level"));
            evidence.put("stats_before",metrics.queryForList("SELECT * FROM mysql.innodb_table_stats WHERE database_name='performance_test' AND table_name IN ('market_source_event','market_source_tick')"));
            assertEquals(1000043L,metrics.queryForObject("SELECT COUNT(*) FROM market_source_event",Long.class));
            evidence.put("indexes",metrics.queryForList("SHOW INDEX FROM market_source_event"));
            metrics.execute("SET SESSION optimizer_trace='enabled=on'");
            metrics.execute("SET SESSION optimizer_trace_max_mem_size=4194304");
            Trace trace=new Trace(raw,metrics);
            SingleConnectionDataSource ds=new SingleConnectionDataSource(trace.connection(),true);
            JdbcTemplate db=new JdbcTemplate(ds);
            ControlHistoryStore store=new ControlHistoryStore(db,new DataSourceTransactionManager(ds));
            List<Object> runs=new ArrayList<>();
            for(int i=0;i<3;i++) {
                db.update("DELETE FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=97001");
                PersistentPriceControl.Task task=new PersistentPriceControl.Task();
                task.id="s1-paired";task.symbolId=97001;task.tenantId=1;task.startPrice=BigDecimal.valueOf(100);task.targetPrice=BigDecimal.valueOf(110);
                task.sourceTime=1700000400000L;task.startedAt=task.sourceTime;task.plannedEnd=1700000430000L;
                db.update("DELETE FROM market_control_hold WHERE tenant_id=1 AND task_id=?",task.id);
                ControlHoldService holds=new ControlHoldService(store);holds.prepare(task,Collections.emptyMap());
                for(String phase:Arrays.asList("freeze","hold")) {
                    trace.phase=phase+"-"+(i+1);trace.record=true;long start=System.nanoTime();
                    try { store.locked(97001,()->{if(phase.equals("freeze"))store.freeze(97001,1700000430000L);else holds.activate(task);return null;}); }
                    finally { trace.record=false; }
                    runs.add(Map.of("phase",trace.phase,"instrumented_full_method_ms",(System.nanoTime()-start)/1e6));
                }
            }
            evidence.put("runs",runs);evidence.put("executed_sql",trace.statements);evidence.put("physical_transactions",trace.transactions);
            evidence.put("stats_after",metrics.queryForList("SELECT * FROM mysql.innodb_table_stats WHERE database_name='performance_test' AND table_name IN ('market_source_event','market_source_tick')"));
            evidence.put("freeze_rows",db.queryForList("SELECT * FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=97001 ORDER BY minute_at"));
            evidence.put("hold_rows",db.queryForList("SELECT * FROM market_control_hold WHERE tenant_id=1 AND task_id='s1-paired'"));
            Files.writeString(Paths.get(System.getenv("S1_COLD_OUTPUT")),new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
            List<Map<String,Object>> business=new ArrayList<>();
            for(Map<String,Object> row:trace.statements)if(((String)row.get("sql")).contains("FROM market_source_event"))business.add(row);
            assertEquals(6,business.size());assertEquals(6,trace.transactions.size());
            assertTrue(trace.transactions.stream().allMatch(t->t.endsWith(":commit")),"real commits, not annotations");
            if(bounded)for(Map<String,Object> row:business) {
                @SuppressWarnings("unchecked") Map<String,Long> counts=(Map<String,Long>)row.get("handlers");
                assertTrue(counts.values().stream().mapToLong(Long::longValue).sum()<1000,"all Handler_read* remain bounded: "+row);
                assertTrue(((String)row.get("sql")).contains("/*! FORCE INDEX (source_event_time) */"));
            }
        }
    }
    static class Trace {
        final Connection raw;final JdbcTemplate metrics;
        final List<Map<String,Object>> statements=new ArrayList<>();final List<String> transactions=new ArrayList<>();
        boolean record;String phase;
        Trace(Connection raw,JdbcTemplate metrics){this.raw=raw;this.metrics=metrics;}
        Connection connection(){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            Object result=invoke(m,raw,a);
            if(record&&(m.getName().equals("commit")||m.getName().equals("rollback")))transactions.add(phase+":"+m.getName());
            if(m.getName().equals("prepareStatement"))return statement((PreparedStatement)result,(String)a[0]);
            return result;
        });}
        PreparedStatement statement(PreparedStatement statement,String sql){
            Map<Integer,Object> arguments=new TreeMap<>();
            return (PreparedStatement)Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
                if(m.getName().startsWith("set")&&a!=null&&a.length>=2&&a[0] instanceof Integer)arguments.put((Integer)a[0],a[1]);
                if(!record||!m.getName().startsWith("execute"))return invoke(m,statement,a);
                Map<String,Long> before=S1PairedProbeTest.handlers(metrics);long start=System.nanoTime();
                Object result=invoke(m,statement,a);double elapsed=(System.nanoTime()-start)/1e6;
                Map<String,Object> row=new LinkedHashMap<>();row.put("phase",phase);row.put("sql",sql);row.put("arguments",new TreeMap<>(arguments));row.put("execute_ms",elapsed);
                row.put("handlers",S1PairedProbeTest.delta(before,S1PairedProbeTest.handlers(metrics)));
                if(sql.contains("FROM market_source_event"))row.put("optimizer_trace_after_execution_before_explain",metrics.queryForList("SELECT QUERY,TRACE,MISSING_BYTES_BEYOND_MAX_MEM_SIZE,INSUFFICIENT_PRIVILEGES FROM INFORMATION_SCHEMA.OPTIMIZER_TRACE"));
                statements.add(row);return result;
            });
        }
        static Object invoke(Method method,Object target,Object[] args)throws Throwable {try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    }
}
