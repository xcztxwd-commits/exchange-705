package com.gtcfesk.exchange.market;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Physical MySQL session interruption and explicitly injected post-commit acknowledgement loss.
 * Composition avoids inheriting/recounting the ordinary acceptance tests.
 */
class ControlRecoveryTransportMysqlTest {
    ControlRecoveryMysqlTest h;
    final AtomicReference<RuntimeException> commitFailure=new AtomicReference<>();
    @BeforeAll static void fixture() throws Exception { ControlRecoveryMysqlTest.ownedFixture(); }
    @BeforeEach void setup() { h=new ControlRecoveryMysqlTest();h.setup(); }
    @AfterEach void cleanup() { h.cleanup(); }

    void rewire(JdbcTemplate db,DataSource data) {
        h.store=new ControlHistoryStore(db,new DataSourceTransactionManager(data){
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){
                try{super.doCommit(status);}catch(RuntimeException failed){commitFailure.set(failed);throw failed;}
            }
        });
        h.controls=new PersistentPriceControl(h.store);
        h.market=h.newMarket(h.store,h.controls,h.symbol);h.commands=h.newQueue(h.store,h.market);
        doNothing().when(h.market).start();
        doAnswer(call->new LinkedHashMap<>(h.source.get())).when(h.market).getPrice(nullable(String.class),nullable(String.class));
    }
    JdbcTemplate killedAfter(String prefix,AtomicBoolean armed,AtomicLong killed) {
        return new JdbcTemplate(ControlRecoveryMysqlTest.data) {
            @Override public int update(String sql,Object... args) {
                int changed=super.update(sql,args);
                if(sql.startsWith(prefix) && armed.compareAndSet(true,false)) {
                    assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    long own=super.queryForObject("SELECT CONNECTION_ID()",Long.class);
                    // The fixture identity was verified before all tests. Same account may kill its own session only.
                    try(Connection separate=ControlRecoveryMysqlTest.data.getConnection();Statement kill=separate.createStatement()){kill.execute("KILL CONNECTION "+own);}
                    catch(SQLException unexpected){throw new RuntimeException(unexpected);}
                    killed.set(own);
                }
                return changed;
            }
        };
    }
    void evidence(String name,Throwable failure,Map<String,Object> facts) throws Exception {
        List<Map<String,Object>> chain=new ArrayList<>();Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable at=failure;at!=null && seen.add(at);at=at.getCause()) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("class",at.getClass().getName());
            if(at instanceof SQLException){row.put("sqlState",((SQLException)at).getSQLState());row.put("vendorCode",((SQLException)at).getErrorCode());}
            chain.add(row);
        }
        facts.put("failureChain",chain);facts.put("normalized",MarketEngineFailure.normalize(failure));
        Path target=Paths.get(System.getenv("CONTROL_RECOVERY_OUTPUT"),name+".json");
        Path temporary=target.resolveSibling(target.getFileName()+".tmp");
        Files.write(temporary,ControlRecoveryMysqlTest.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(facts));
        Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
    }
    @Test void actualConnectionKilledAfterInsertRollsBackAndResumesCommittedWatermark() throws Exception {
        AtomicBoolean armed=new AtomicBoolean();AtomicLong killed=new AtomicLong();
        rewire(killedAfter("INSERT INTO market_control_sample",armed,killed),ControlRecoveryMysqlTest.data);
        h.prime();PersistentPriceControl.Task task=h.startShort();
        Map<String,Object> snapshot=h.store.db.queryForMap("SELECT snapshot_version,committed_at,quote_json FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",h.symbol.getId());
        List<Map<String,Object>> minutes=h.store.db.queryForList("SELECT * FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=? ORDER BY minute_at",h.symbol.getId());
        long count=h.samples(task.id),watermark=h.controls.latest(h.symbol.getId()).sampledUntil;
        armed.set(true);RuntimeException failure=assertThrows(RuntimeException.class,()->h.controls.pump(h.symbol,h.raw(task.startedAt+1000,true),task.startedAt+1000,60000));
        assertTrue(killed.get()>0);assertEquals(count,h.samples(task.id));assertEquals(watermark,h.controls.latest(h.symbol.getId()).sampledUntil);
        assertEquals("RUNNING",h.controls.latest(h.symbol.getId()).status);
        assertEquals(snapshot,h.store.db.queryForMap("SELECT snapshot_version,committed_at,quote_json FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",h.symbol.getId()));
        assertEquals(minutes,h.store.db.queryForList("SELECT * FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=? ORDER BY minute_at",h.symbol.getId()));
        h.controls.pump(h.symbol,h.raw(task.plannedEnd,true),task.plannedEnd,60000);
        assertEquals("COMPLETED",h.controls.latest(h.symbol.getId()).status);assertEquals(3,h.samples(task.id));
        assertEquals(3,h.store.db.queryForObject("SELECT COUNT(DISTINCT generated_at) FROM market_control_sample WHERE tenant_id=1 AND task_id=?",Integer.class,task.id));
        Map<String,Object> facts=new LinkedHashMap<>();facts.put("fault","ACTUAL_MYSQL_KILL_CONNECTION_AFTER_SAMPLE_INSERT");facts.put("killedOwnSession",killed.get());facts.put("rollbackVerified",true);facts.put("resumedSampleCount",h.samples(task.id));
        evidence("physical-connection-interruption",failure,facts);
        assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(failure));
    }

    @Test void actualWorkerConnectionKilledBeforeClaimCommitKeepsOriginalReceiptRecoverable() throws Exception {
        AtomicBoolean armed=new AtomicBoolean();AtomicLong killed=new AtomicLong();
        rewire(killedAfter("UPDATE market_control_command SET state='PREPARING'",armed,killed),ControlRecoveryMysqlTest.data);
        h.prime();Map<String,Object> accepted=h.accept("transport_worker_disconnect");armed.set(true);h.commands.runOne();
        Map<String,Object> interrupted=h.commands.query(h.symbol.getId(),"transport_worker_disconnect");
        Map<String,Object> facts=new LinkedHashMap<>();facts.put("fault","ACTUAL_MYSQL_KILL_CONNECTION_BEFORE_CLAIM_COMMIT");facts.put("killedOwnSession",killed.get());facts.put("receipt",interrupted);
        evidence("worker-connection-interruption",commitFailure.get(),facts);
        assertTrue(killed.get()>0);assertNotNull(commitFailure.get());assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(commitFailure.get()));
        assertEquals(0,h.store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,h.symbol.getId()));
        verify(h.market,never()).prepareCommand(anyLong(),anyInt(),any(java.math.BigDecimal.class),anyInt(),anyBoolean(),any(TargetControlOptions.class),anyLong());
        assertEquals(accepted.get("commandId"),interrupted.get("commandId"));
        assertEquals("ACCEPTED",interrupted.get("state"));assertEquals("ENGINE_TRANSIENT",interrupted.get("errorCode"));assertEquals(1,((Number)interrupted.get("retryCount")).intValue());
        Thread.sleep(450);h.commands.runOne();assertEquals("RUNNING",h.commands.query(h.symbol.getId(),"transport_worker_disconnect").get("state"));
        assertEquals(1,h.store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,h.symbol.getId()));
    }

    @Test void actualMysqlQueryInterruptionDefersWorkerWithoutCancellingBusinessCommand() throws Exception {
        h.prime();h.accept("worker_query_interrupted");AtomicBoolean once=new AtomicBoolean(true),observed=new AtomicBoolean();AtomicReference<RuntimeException> error=new AtomicReference<>();AtomicReference<SQLException> queryFailure=new AtomicReference<>();AtomicBoolean killSucceeded=new AtomicBoolean();
        doAnswer(call->{
            if(once.compareAndSet(true,false)) {
                java.util.concurrent.ExecutorService killer=java.util.concurrent.Executors.newSingleThreadExecutor();
                try {
                    h.store.db.execute((Connection connection)->{
                        long id;try(Statement getId=connection.createStatement();ResultSet result=getId.executeQuery("SELECT CONNECTION_ID()")){assertTrue(result.next());id=result.getLong(1);}
                        java.util.concurrent.Future<?> stopped=killer.submit(()->{
                            JdbcTemplate separate=new JdbcTemplate(ControlRecoveryMysqlTest.data);long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                            while(System.nanoTime()<deadline) {
                                for(Map<String,Object> row:separate.queryForList("SHOW FULL PROCESSLIST"))
                                    if(((Number)row.get("Id")).longValue()==id && "SELECT /*+ MAX_EXECUTION_TIME(3000) */ COUNT(*) FROM information_schema.columns a CROSS JOIN information_schema.columns b CROSS JOIN information_schema.columns c".equals(row.get("Info"))) {
                                        observed.set(true);separate.execute("KILL QUERY "+id);killSucceeded.set(true);return;
                                    }
                                try{Thread.sleep(10);}catch(InterruptedException interrupted){throw new RuntimeException(interrupted);}
                            }
                            throw new AssertionError("Own MySQL query was not observed running");
                        });
                        try(Statement sleep=connection.createStatement()){sleep.setQueryTimeout(4);sleep.executeQuery("SELECT /*+ MAX_EXECUTION_TIME(3000) */ COUNT(*) FROM information_schema.columns a CROSS JOIN information_schema.columns b CROSS JOIN information_schema.columns c");}
                        catch(SQLException interrupted){queryFailure.set(interrupted);throw interrupted;}
                        finally{try{stopped.get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception unexpected){throw new RuntimeException(unexpected);}}
                        return null;
                    });
                }catch(RuntimeException failed){error.set(failed);throw failed;}finally{killer.shutdownNow();}
            }
            return call.callRealMethod();
        }).when(h.market).prepareCommand(anyLong(),anyInt(),any(java.math.BigDecimal.class),anyInt(),anyBoolean(),any(TargetControlOptions.class),anyLong());
        h.commands.runOne();Map<String,Object> receipt=h.commands.query(h.symbol.getId(),"worker_query_interrupted");
        Map<String,Object> facts=new LinkedHashMap<>();facts.put("fault","ACTUAL_MYSQL_KILL_QUERY_DURING_WORKER_PREPARATION");facts.put("queryObservedRunning",observed.get());facts.put("receipt",receipt);evidence("worker-query-interruption",error.get(),facts);
        assertTrue(observed.get());assertTrue(killSucceeded.get());assertNotNull(queryFailure.get());
        assertEquals("70100",queryFailure.get().getSQLState());assertEquals(1317,queryFailure.get().getErrorCode());
        assertNotNull(error.get());assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(error.get()));
        assertEquals("PREPARING",receipt.get("state"));assertEquals("ENGINE_TRANSIENT",receipt.get("errorCode"));
        Thread.sleep(450);h.commands.runOne();assertEquals("RUNNING",h.commands.query(h.symbol.getId(),"worker_query_interrupted").get("state"));
        assertEquals(1,h.store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,h.symbol.getId()));
    }

    DataSource loseCommitAcknowledgement(AtomicBoolean armed) {
        return new DelegatingDataSource(ControlRecoveryMysqlTest.data) {
            @Override public Connection getConnection() throws SQLException {return intercept(super.getConnection());}
            @Override public Connection getConnection(String user,String password) throws SQLException {return intercept(super.getConnection(user,password));}
            Connection intercept(Connection actual) {
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    try {
                        Object result=method.invoke(actual,args);
                        if("commit".equals(method.getName()) && armed.compareAndSet(true,false))throw new SQLException("Injected acknowledgement loss AFTER actual MySQL COMMIT","08007");
                        return result;
                    } catch(InvocationTargetException failure){throw failure.getCause();}
                });
            }
        };
    }
    @Test void committedAcceptWithLostAcknowledgementQueriesAndReplaysSameCommandOnlyOnce() throws Exception {
        AtomicBoolean armed=new AtomicBoolean();DataSource data=loseCommitAcknowledgement(armed);rewire(new JdbcTemplate(data),data);
        h.prime();armed.set(true);RuntimeException failure=assertThrows(RuntimeException.class,()->h.accept("accept_commit_ack_lost"));
        MarketControlCommands restarted=h.newQueue(h.store,h.market);
        Map<String,Object> query=restarted.query(h.symbol.getId(),"accept_commit_ack_lost");assertEquals("ACCEPTED",query.get("state"));
        Map<String,Object> repeated=restarted.accept(h.symbol.getId(),20,new java.math.BigDecimal("91"),1,false,"accept_commit_ack_lost",h.options());
        assertEquals(query.get("commandId"),repeated.get("commandId"));restarted.runOne();restarted.runOne();
        assertEquals("RUNNING",restarted.query(h.symbol.getId(),"accept_commit_ack_lost").get("state"));
        assertEquals(1,h.store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,h.symbol.getId()));
        Map<String,Object> facts=new LinkedHashMap<>();facts.put("fault","INJECTED_JDBC_ACK_LOSS_AFTER_ACTUAL_MYSQL_COMMIT_NOT_TCP_DROP");facts.put("commandId",query.get("commandId"));facts.put("sameKeyReplayCreatedTasks",1);evidence("accept-commit-acknowledgement-loss",failure,facts);
        assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(failure));
    }
    @Test void committedSampleWithLostAcknowledgementDoesNotDuplicateOnRetry() throws Exception {
        AtomicBoolean armed=new AtomicBoolean();DataSource data=loseCommitAcknowledgement(armed);rewire(new JdbcTemplate(data),data);
        h.prime();PersistentPriceControl.Task task=h.startShort();armed.set(true);
        RuntimeException failure=assertThrows(RuntimeException.class,()->h.controls.pump(h.symbol,h.raw(task.startedAt+1000,true),task.startedAt+1000,60000));
        assertEquals(task.startedAt+1000,h.controls.latest(h.symbol.getId()).sampledUntil);assertEquals(2,h.samples(task.id));
        h.controls.pump(h.symbol,h.raw(task.startedAt+1000,true),task.startedAt+1000,60000);assertEquals(2,h.samples(task.id));
        h.controls.pump(h.symbol,h.raw(task.plannedEnd,true),task.plannedEnd,60000);assertEquals(3,h.samples(task.id));assertEquals("COMPLETED",h.controls.latest(h.symbol.getId()).status);
        Map<String,Object> facts=new LinkedHashMap<>();facts.put("fault","INJECTED_JDBC_ACK_LOSS_AFTER_ACTUAL_MYSQL_SAMPLE_COMMIT_NOT_TCP_DROP");facts.put("replayedSameWatermark",true);facts.put("samples",h.samples(task.id));evidence("sample-commit-acknowledgement-loss",failure,facts);
        assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(failure));
    }
}
