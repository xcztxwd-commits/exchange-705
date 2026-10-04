package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiles unchanged against the frozen pre-S1 tree and S1 tree; restored input databases are identical. */
class S1PairedProbeTest extends TenantMarketTestContext {
    @Test void measureActualFreezeAndHoldOnRestoredMillionSnapshot() throws Exception {
        String url=System.getenv("S1_PAIR_JDBC"); Assumptions.assumeTrue(url!=null,"explicit paired disposable MySQL fixture required");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:3332[89]/performance_test(?:\\?.*)?"));
        DriverManagerDataSource source=new DriverManagerDataSource(url,"root","performance-test-only");
        Map<String,Object> evidence=new LinkedHashMap<>();
        try(ConnectionSource connection=new ConnectionSource(source)) {
            JdbcTemplate db=new JdbcTemplate(connection.ds);
            assertEquals(System.getenv("S1_PAIR_UUID"),db.queryForObject("SELECT @@server_uuid",String.class));
            ControlHistoryStore store=new ControlHistoryStore(db,new DataSourceTransactionManager(connection.ds));
            evidence.put("server",db.queryForMap("SELECT VERSION() version,@@server_uuid uuid,@@tx_isolation isolation_level"));
            evidence.put("events",db.queryForObject("SELECT COUNT(*) FROM market_source_event",Long.class));
            evidence.put("indexes",db.queryForList("SHOW INDEX FROM market_source_event"));
            List<Object> runs=new ArrayList<>();
            for(int i=0;i<3;i++) {
                db.update("DELETE FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=97001");
                PersistentPriceControl.Task task=new PersistentPriceControl.Task();task.id="s1-paired";task.symbolId=97001;task.tenantId=1;
                task.startPrice=BigDecimal.valueOf(100);task.targetPrice=BigDecimal.valueOf(110);task.sourceTime=1700000400000L;task.startedAt=task.sourceTime;task.plannedEnd=1700000430000L;
                db.update("DELETE FROM market_control_hold WHERE tenant_id=1 AND task_id=?",task.id);
                ControlHoldService holds=new ControlHoldService(store);holds.prepare(task,Collections.emptyMap());
                Map<String,Long> before=handlers(db); long start=System.nanoTime();
                store.locked(97001,()->{store.freeze(97001,1700000430000L);return null;});
                double freeze=(System.nanoTime()-start)/1e6;Map<String,Long> middle=handlers(db);
                start=System.nanoTime();store.locked(97001,()->{holds.activate(task);return null;});
                double hold=(System.nanoTime()-start)/1e6;Map<String,Long> end=handlers(db);
                Map<String,Object> run=new LinkedHashMap<>();run.put("freeze_full_method_ms",freeze);run.put("hold_full_method_ms",hold);
                run.put("freeze_handlers",delta(before,middle));run.put("hold_handlers",delta(middle,end));runs.add(run);
            }
            evidence.put("runs",runs);
            evidence.put("freeze_rows",db.queryForList("SELECT * FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=97001 ORDER BY minute_at"));
            evidence.put("hold_rows",db.queryForList("SELECT * FROM market_control_hold WHERE tenant_id=1 AND task_id='s1-paired'"));
            evidence.put("mvc_requests",requests(store));
            String sql=System.getenv("S1_PAIR_QUERY");
            if(sql!=null) {
                String text=Files.readString(Paths.get(sql));List<Object> queries=new ArrayList<>();
                for(String query:text.split(";"))if(!query.trim().isEmpty()) {
                    Map<String,Object> q=new LinkedHashMap<>();q.put("sql",query);q.put("explain",db.queryForList("EXPLAIN "+query));
                    List<Double> times=new ArrayList<>();for(int i=0;i<3;i++){long t=System.nanoTime();q.put("rows",db.queryForList(query));times.add((System.nanoTime()-t)/1e6);}q.put("ms",times);queries.add(q);
                }
                evidence.put("single_sql",queries);
            }
        }
        Files.writeString(Paths.get(System.getenv("S1_PAIR_OUTPUT")),new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
    // Complete DispatcherServlet -> real controller -> market -> SQL -> JSON route.
    // Provider/repository adapters are fixed fixtures; security filters and TLS are not benchmarked here.
    static List<Object> requests(ControlHistoryStore store) throws Exception {
        com.gtcfesk.exchange.entity.TradingSymbol symbol=new com.gtcfesk.exchange.entity.TradingSymbol();
        symbol.setTenantId(1L);symbol.setId(97001L);symbol.setSymbol("PAIR");symbol.setCategory("Metal");symbol.setSourceCategory("Metal");symbol.setQuoteCurrency("USD");symbol.setPricePrecision(8);symbol.setIsEnabled(true);
        com.gtcfesk.exchange.repository.TradingSymbolRepository repository=org.mockito.Mockito.mock(com.gtcfesk.exchange.repository.TradingSymbolRepository.class);
        org.mockito.Mockito.when(repository.findByTenantIdAndId(1L,97001L)).thenReturn(Optional.of(symbol));
        org.mockito.Mockito.when(repository.saveAndFlush(org.mockito.Mockito.any())).thenAnswer(i->i.getArgument(0));
        PersistentPriceControl controls=new PersistentPriceControl(store);
        ForexQuoteMarketService market=org.mockito.Mockito.spy(new ForexQuoteMarketService());
        org.springframework.test.util.ReflectionTestUtils.setField(market,"symbols",repository);
        org.springframework.test.util.ReflectionTestUtils.setField(market,"controls",controls);
        org.springframework.test.util.ReflectionTestUtils.setField(market,"controlHistory",store);
        org.springframework.test.util.ReflectionTestUtils.setField(market,"redis",org.mockito.Mockito.mock(RedisMarketService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(market,"v3Enabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(market,"v4Enabled",true);
        org.mockito.Mockito.doReturn(Collections.emptyMap()).when(market).getKline(org.mockito.Mockito.anyString(),org.mockito.Mockito.anyString(),org.mockito.Mockito.anyInt(),org.mockito.Mockito.anyString());
        com.gtcfesk.exchange.admin.AdminAiControlController controller=new com.gtcfesk.exchange.admin.AdminAiControlController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"market",market);
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"controls",controls);
        org.springframework.test.web.servlet.MockMvc mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        List<Object> results=new ArrayList<>();
        try {
            for(int i=0;i<3;i++) {
                for(String table:Arrays.asList("market_control_sample","market_control_plan","market_control_hold","market_control_flow","market_control_resume","market_control_publication"))
                    store.db.update("DELETE FROM "+table+" WHERE tenant_id=1 AND task_id IN (SELECT id FROM market_control_task WHERE tenant_id=1 AND symbol_id=97001)");
                store.db.update("DELETE FROM market_control_task WHERE tenant_id=1 AND symbol_id=97001");
                store.db.update("DELETE FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=97001");
                long now=System.currentTimeMillis();
                org.mockito.Mockito.doReturn(new HashMap<>(Map.of("price",100,"timestamp",now,"sourceTimestamp",now,"available",true))).when(market).getPrice("PAIR","Metal");
                Map<String,Object> row=new LinkedHashMap<>();Map<String,Long> before=handlers(store.db);long at=System.nanoTime();
                org.springframework.test.web.servlet.MvcResult started=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/ai-control/97001/start")
                    .contentType("application/json").content("{\"durationSeconds\":60,\"targetPrice\":100.054,\"intensity\":10,\"randomOscillation\":false,\"requestKey\":\"pair-"+i+"\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
                row.put("start_full_mvc_ms",(System.nanoTime()-at)/1e6);row.put("start_handlers",delta(before,handlers(store.db)));
                row.put("start_body",new com.fasterxml.jackson.databind.ObjectMapper().readValue(started.getResponse().getContentAsString(),Map.class));
                // Reuse the completed plan, but fixed sample cursor/end reproduces the known-at-end hold query.
                store.db.update("UPDATE market_control_task SET status='RUNNING',planned_end=1700000430000,sampled_until=1700000430000 WHERE tenant_id=1 AND symbol_id=97001");
                store.db.update("UPDATE market_control_hold SET reference_time=1700000400000 WHERE tenant_id=1 AND task_id IN (SELECT id FROM market_control_task WHERE tenant_id=1 AND symbol_id=97001)");
                org.mockito.Mockito.doReturn(Collections.emptyMap()).when(market).getPrice("PAIR","Metal");
                before=handlers(store.db);at=System.nanoTime();
                org.springframework.test.web.servlet.MvcResult status=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/ai-control/97001"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
                row.put("hold_status_full_mvc_ms",(System.nanoTime()-at)/1e6);row.put("status_handlers",delta(before,handlers(store.db)));
                row.put("status_body",new com.fasterxml.jackson.databind.ObjectMapper().readValue(status.getResponse().getContentAsString(),Map.class));results.add(row);
            }
        } finally {market.stop();}
        return results;
    }
    static Map<String,Long> handlers(JdbcTemplate db) {Map<String,Long> r=new TreeMap<>();for(Map<String,Object> row:db.queryForList("SHOW SESSION STATUS LIKE 'Handler_read%'"))r.put((String)row.get("Variable_name"),Long.parseLong((String)row.get("Value")));return r;}
    static Map<String,Long> delta(Map<String,Long>a,Map<String,Long>b){Map<String,Long> r=new TreeMap<>();b.forEach((k,v)->r.put(k,v-a.get(k)));return r;}
    static class ConnectionSource implements AutoCloseable {
        final java.sql.Connection connection;final SingleConnectionDataSource ds;
        ConnectionSource(DriverManagerDataSource source)throws Exception{connection=source.getConnection();ds=new SingleConnectionDataSource(connection,true);}
        @Override public void close()throws Exception{connection.close();}
    }
}
