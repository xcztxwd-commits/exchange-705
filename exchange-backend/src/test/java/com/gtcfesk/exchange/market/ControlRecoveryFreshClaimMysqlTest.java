package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.tenant.TenantContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Fresh-owner claim acknowledgement loss and a current-read legacy-key race on the owned MySQL fixture.
 * Composition deliberately does not inherit/recount the ordinary acceptance scenarios.
 */
class ControlRecoveryFreshClaimMysqlTest {
    ControlRecoveryTransportMysqlTest transport;
    ControlRecoveryMysqlTest h;
    @BeforeAll static void fixture() throws Exception {ControlRecoveryMysqlTest.ownedFixture();}
    @BeforeEach void setup(){transport=new ControlRecoveryTransportMysqlTest();transport.setup();h=transport.h;}
    @AfterEach void cleanup(){transport.cleanup();}
    JdbcTemplate facts(){return new JdbcTemplate(ControlRecoveryMysqlTest.data);}
    Map<String,Object> runtime(){return facts().queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",h.symbol.getId());}
    Map<String,Object> receipt(String key){return facts().queryForMap("SELECT * FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND request_key=?",h.symbol.getId(),key);}
    long tasks(){return facts().queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Long.class,h.symbol.getId());}
    void expireOwnedFixtureLease(ControlHistoryStore owner){
        // Only the verified, unique fixture symbol's real owner lease; never a task state mutation.
        assertEquals(1,facts().update("UPDATE market_engine_runtime SET lease_until=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)-1 WHERE tenant_id=1 AND symbol_id=? AND owner_id=?",h.symbol.getId(),owner.runtime.owner));
    }

    @Test void freshOwnerLostClaimAcknowledgementNeverReclaimsAfterThirdPartyTakeover() throws Exception {
        h.prime();ControlHistoryStore ownerA=h.store;String key="fresh_claim_ack_original";Map<String,Object> accepted=h.accept(key);expireOwnedFixtureLease(ownerA);
        AtomicBoolean armed=new AtomicBoolean();DataSource uncertain=transport.loseCommitAcknowledgement(armed);transport.rewire(new JdbcTemplate(uncertain),uncertain);
        ControlHistoryStore ownerB=h.store;MarketControlCommands oldWorker=h.commands;assertNotEquals(ownerA.runtime.owner,ownerB.runtime.owner);
        armed.set(true);oldWorker.runOne();Map<String,Object> claimed=receipt(key);assertNotNull(transport.commitFailure.get());assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(transport.commitFailure.get()));
        assertEquals("PREPARING",claimed.get("state"));assertEquals(ownerB.runtime.owner,claimed.get("owner_id"));assertEquals(1,((Number)claimed.get("retry_count")).intValue());assertEquals(0,tasks());
        assertEquals(ownerB.runtime.owner,runtime().get("owner_id"));assertEquals(claimed.get("writer_generation"),runtime().get("writer_generation"));
        expireOwnedFixtureLease(ownerB);transport.rewire(new JdbcTemplate(ControlRecoveryMysqlTest.data),ControlRecoveryMysqlTest.data);ControlHistoryStore ownerC=h.store;
        ownerC.locked(h.symbol.getId(),()->null);assertEquals(ownerC.runtime.owner,runtime().get("owner_id"));assertTrue(((Number)runtime().get("writer_generation")).longValue()>((Number)claimed.get("writer_generation")).longValue());expireOwnedFixtureLease(ownerC);
        Map<String,Object> authority=runtime(),beforeReceipt=receipt(key);Thread.sleep(Math.max(0,((Number)beforeReceipt.get("retry_at")).longValue()-ownerC.runtime.clock()+20));
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("fault","INJECTED_JDBC_ACK_LOSS_AFTER_ACTUAL_FIRST_OWNER_CLAIM_COMMIT_NOT_TCP_DROP");evidence.put("committedClaimReceipt",claimed);evidence.put("thirdPartyExpiredAuthority",authority);
        try{
            for(int round=0;round<2;round++){
                oldWorker.runOne();assertEquals(authority,runtime(),"Unacknowledged fresh owner B must never replace owner C's committed generation, even after C lease expiry");
                assertEquals(beforeReceipt,receipt(key),"Old owner must not mutate the complete PREPARING receipt or retry counters");assertEquals(0,tasks());
            }
            transport.rewire(new JdbcTemplate(ControlRecoveryMysqlTest.data),ControlRecoveryMysqlTest.data);ControlHistoryStore ownerD=h.store;MarketControlCommands fresh=h.commands;
            assertEquals(accepted.get("commandId"),h.accept(key).get("commandId"));fresh.runOne();fresh.runOne();assertEquals("RUNNING",fresh.query(h.symbol.getId(),key).get("state"));assertEquals(1,tasks());assertEquals(ownerD.runtime.owner,runtime().get("owner_id"));
            evidence.put("freshOwnerDResumedExactlyOneTask",true);
        }finally{evidence.put("finalRuntime",runtime());evidence.put("finalReceipt",receipt(key));evidence.put("finalTaskCount",tasks());transport.evidence("fresh-owner-claim-acknowledgement-loss",transport.commitFailure.get(),evidence);}
    }

    @Test void workerLegacyKeyValidationSeesSyncTaskCommittedWhileWaitingAfterOldRepeatableReadSnapshot() throws Exception {
        h.prime();String key="legacy_sync_after_accept";h.accept(key);
        CountDownLatch writerLocked=new CountDownLatch(1),snapshotRead=new CountDownLatch(1);AtomicLong workerConnection=new AtomicLong();AtomicBoolean blockedObserved=new AtomicBoolean();AtomicReference<String> legacyTask=new AtomicReference<>();
        JdbcTemplate observed=new JdbcTemplate(ControlRecoveryMysqlTest.data){
            @Override public <T> T queryForObject(String sql,Class<T> type,Object... args){
                T result=super.queryForObject(sql,type,args);
                if(Thread.currentThread().getName().equals("mysql-legacy-worker") && sql.equals("SELECT id FROM trading_symbol WHERE tenant_id=? AND id=?")){
                    workerConnection.set(super.queryForObject("SELECT CONNECTION_ID()",Long.class));snapshotRead.countDown();
                }
                return result;
            }
        };
        ReflectionTestUtils.setField(h.store,"db",observed);
        ExecutorService writer=Executors.newSingleThreadExecutor(r->new Thread(r,"mysql-legacy-sync")),worker=Executors.newSingleThreadExecutor(r->new Thread(r,"mysql-legacy-worker"));
        try{
            Future<?> sync=writer.submit(()->{try(TenantContext.Scope tenant=TenantContext.open(1L)){h.store.locked(h.symbol.getId(),()->{
                writerLocked.countDown();try{
                    assertTrue(snapshotRead.await(3,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                    while(!blockedObserved.get() && System.nanoTime()<deadline){
                        for(Map<String,Object> process:observed.queryForList("SHOW FULL PROCESSLIST"))if(((Number)process.get("Id")).longValue()==workerConnection.get() && String.valueOf(process.get("Info")).startsWith("INSERT INTO market_engine_runtime"))blockedObserved.set(true);
                        if(!blockedObserved.get())Thread.sleep(10);
                    }
                    assertTrue(blockedObserved.get(),"Worker must actually be waiting in MySQL after creating its earlier RR snapshot");
                }catch(InterruptedException interrupted){throw new RuntimeException(interrupted);}
                PersistentPriceControl.Task task=h.controls.startRealtimeRestore(h.symbol,h.raw(System.currentTimeMillis(),true),new java.math.BigDecimal("90"),2,1,false,key);legacyTask.set(task.id);return null;
            });}});
            assertTrue(writerLocked.await(3,TimeUnit.SECONDS));Future<?> attempt=worker.submit(()->{try(TenantContext.Scope tenant=TenantContext.open(1L)){h.commands.runOne();}});sync.get(10,TimeUnit.SECONDS);attempt.get(10,TimeUnit.SECONDS);
            assertTrue(blockedObserved.get());Map<String,Object> failed=h.commands.query(h.symbol.getId(),key);assertEquals("FAILED",failed.get("state"));assertEquals("LEGACY_REQUEST_KEY_REQUIRES_RECONCILIATION",failed.get("errorCode"));assertNull(failed.get("taskId"));assertEquals(1,tasks());assertEquals(legacyTask.get(),h.controls.latest(h.symbol.getId()).id);
        }finally{snapshotRead.countDown();writer.shutdownNow();worker.shutdownNow();}
    }
    Map<String,Object> committedHistory(String taskId){
        JdbcTemplate db=facts();Map<String,Object> history=new LinkedHashMap<>();
        history.put("task",db.queryForMap("SELECT * FROM market_control_task WHERE tenant_id=1 AND symbol_id=? AND id=?",h.symbol.getId(),taskId));
        history.put("samples",db.queryForList("SELECT * FROM market_control_sample WHERE tenant_id=1 AND task_id=? ORDER BY generated_at",taskId));
        history.put("flow",db.queryForList("SELECT * FROM market_control_flow WHERE tenant_id=1 AND task_id=?",taskId));
        history.put("holds",db.queryForList("SELECT * FROM market_control_hold WHERE tenant_id=1 AND task_id=?",taskId));
        history.put("minutes",db.queryForList("SELECT * FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=? ORDER BY minute_at",h.symbol.getId()));
        history.put("publications",db.queryForList("SELECT * FROM market_control_publication WHERE tenant_id=1 AND task_id=? ORDER BY from_at",taskId));
        return history;
    }

    @Test void genericFreshWriterLostFirstCommitAcknowledgementCannotReclaimAfterThirdPartyTakeover() throws Exception {
        h.prime();PersistentPriceControl.Task task=h.startShort();ControlHistoryStore ownerA=h.store;expireOwnedFixtureLease(ownerA);
        AtomicBoolean armed=new AtomicBoolean();DataSource uncertain=transport.loseCommitAcknowledgement(armed);transport.rewire(new JdbcTemplate(uncertain),uncertain);
        ControlHistoryStore ownerB=h.store;PersistentPriceControl oldWriter=h.controls;assertNotEquals(ownerA.runtime.owner,ownerB.runtime.owner);
        armed.set(true);RuntimeException acknowledgement=assertThrows(RuntimeException.class,()->oldWriter.pump(h.symbol,h.raw(task.startedAt+1000,true),task.startedAt+1000,60000));
        assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(acknowledgement));assertEquals(ownerB.runtime.owner,runtime().get("owner_id"));assertEquals(2,h.samples(task.id));assertEquals(task.startedAt+1000,h.controls.latest(h.symbol.getId()).sampledUntil);
        long committedGeneration=((Number)runtime().get("writer_generation")).longValue();expireOwnedFixtureLease(ownerB);
        transport.rewire(new JdbcTemplate(ControlRecoveryMysqlTest.data),ControlRecoveryMysqlTest.data);ControlHistoryStore ownerC=h.store;
        ownerC.locked(h.symbol.getId(),()->null);assertEquals(ownerC.runtime.owner,runtime().get("owner_id"));assertTrue(((Number)runtime().get("writer_generation")).longValue()>committedGeneration);expireOwnedFixtureLease(ownerC);
        Map<String,Object> authority=runtime(),history=committedHistory(task.id),evidence=new LinkedHashMap<>();evidence.put("fault","INJECTED_JDBC_ACK_LOSS_AFTER_ACTUAL_FIRST_GENERIC_WRITER_COMMIT_NOT_TCP_DROP");evidence.put("thirdPartyExpiredAuthority",authority);evidence.put("committedHistoryBeforeStaleWriter",history);
        try{
            for(int round=0;round<2;round++){
                RuntimeException fenced=assertThrows(RuntimeException.class,()->oldWriter.pump(h.symbol,h.raw(task.plannedEnd,true),task.plannedEnd,60000));
                assertEquals("ENGINE_FENCED",MarketEngineFailure.normalize(fenced));assertEquals(authority,runtime(),"Old generic writer must not replace owner C or modify any runtime field");assertEquals(history,committedHistory(task.id),"Old generic writer must preserve all committed task, sample, flow, hold, minute and publication facts");
            }
            transport.rewire(new JdbcTemplate(ControlRecoveryMysqlTest.data),ControlRecoveryMysqlTest.data);ControlHistoryStore ownerD=h.store;
            h.controls.pump(h.symbol,h.raw(task.plannedEnd,true),task.plannedEnd,60000);assertEquals(ownerD.runtime.owner,runtime().get("owner_id"));assertEquals(1,tasks());assertEquals(3,h.samples(task.id));assertEquals("COMPLETED",h.controls.latest(h.symbol.getId()).status);evidence.put("freshOwnerDResumedOriginalTaskExactlyOnce",true);
        }finally{evidence.put("finalRuntime",runtime());evidence.put("finalCommittedHistory",committedHistory(task.id));transport.evidence("generic-first-writer-acknowledgement-loss",acknowledgement,evidence);}
    }

    @Test void genericFreshWriterKilledFirstClaimRollsBackWithoutPoisoningSameOwnerRetry() throws Exception {
        h.prime();PersistentPriceControl.Task task=h.startShort();ControlHistoryStore ownerA=h.store;expireOwnedFixtureLease(ownerA);
        Map<String,Object> beforeAuthority=runtime(),beforeHistory=committedHistory(task.id);
        AtomicBoolean armed=new AtomicBoolean();AtomicLong killed=new AtomicLong();transport.rewire(transport.killedAfter("INSERT INTO market_control_sample",armed,killed),ControlRecoveryMysqlTest.data);
        ControlHistoryStore ownerB=h.store;PersistentPriceControl sameWriter=h.controls;assertNotEquals(ownerA.runtime.owner,ownerB.runtime.owner);
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("fault","ACTUAL_MYSQL_KILL_CONNECTION_AFTER_FIRST_FRESH_WRITER_SAMPLE_INSERT_BEFORE_COMMIT");evidence.put("authorityBeforeKilledFirstClaim",beforeAuthority);RuntimeException failure=null;
        try{
            armed.set(true);failure=assertThrows(RuntimeException.class,()->sameWriter.pump(h.symbol,h.raw(task.startedAt+1000,true),task.startedAt+1000,60000));
            assertTrue(killed.get()>0);assertEquals("ENGINE_TRANSIENT",MarketEngineFailure.normalize(failure));assertEquals(beforeAuthority,runtime(),"An actual rolled-back first claim must preserve the earlier owner runtime in full");assertEquals(beforeHistory,committedHistory(task.id));assertEquals(1,h.samples(task.id));
            sameWriter.pump(h.symbol,h.raw(task.startedAt+1000,true),task.startedAt+1000,60000);assertEquals(ownerB.runtime.owner,runtime().get("owner_id"));assertEquals(2,h.samples(task.id));assertEquals(task.startedAt+1000,h.controls.latest(h.symbol.getId()).sampledUntil);
            sameWriter.pump(h.symbol,h.raw(task.plannedEnd,true),task.plannedEnd,60000);assertEquals("COMPLETED",h.controls.latest(h.symbol.getId()).status);assertEquals(3,h.samples(task.id));assertEquals(1,tasks());evidence.put("sameOwnerRetriedWithoutFalseFence",true);
        }finally{evidence.put("killedOwnSession",killed.get());evidence.put("finalRuntime",runtime());evidence.put("finalCommittedHistory",committedHistory(task.id));transport.evidence("generic-first-writer-physical-rollback",failure,evidence);}
    }

}
