package com.gtcfesk.exchange.activity;

import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.user.KycIdentityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit transactions, synthetic funding only; opt-in MySQL shares the original fixture guards. */
@SpringJUnitConfig(ActivityIntegrationTest.Config.class)
class TrialFundsCurrentReadRegressionTest extends ActivityFeatureFixture {
    @Autowired TrialLedgerRepository ledger;
    @Autowired KycIdentityService identity;
    @PersistenceContext EntityManager em;
    @Test void repeatedCanonicalLockKeepsEarlierUncommittedCashReservations() {
        tx.executeWithoutResult(status->{
            funds.lock(user);AssetAccount cash=assets.lockByUserId(user).stream().filter(a->"CONTRACT".equals(a.getCoin())).findFirst().orElseThrow();
            funds.reserve(user,cash,d("10"),"CONTRACT","CONTRACT","FIRST_SYNTHETIC_RESERVATION");
            funds.lock(user);funds.reserve(user,cash,d("20"),"CONTRACT","CONTRACT","SECOND_SYNTHETIC_RESERVATION");
            money("70",cash.getAvailable());money("30",cash.getFrozen());
        });
        AssetAccount current=assets.findByTenantIdAndUserIdAndCoin(1L,user,"CONTRACT").orElseThrow();money("70",current.getAvailable());money("30",current.getFrozen());
    }
    @Test void settlementAtExpiryDoesNotRelyOnAQueryOrMaintenanceJob() {
        clock("2030-01-01T00:00:00Z");claim(campaign(),user,"owned-expiry-settlement");
        tx.executeWithoutResult(status->{
            funds.lock(user);AssetAccount cash=assets.lockByUserId(user).stream().filter(a->"CONTRACT".equals(a.getCoin())).findFirst().orElseThrow();
            TrialFunds.Reservation reservation=funds.reserve(user,cash,d("50"),"TRIAL","CONTRACT","OWNED_TRIAL_RESERVATION");
            clock("2030-01-04T00:00:00Z");
            funds.settle(user,cash,d("50"),reservation.trial,reservation.allocations,"TRIAL",BigDecimal.ZERO,"OWNED_EXPIRED_SETTLEMENT");
        });
        TrialAccount account=trials.findByTenantIdAndId(1L,user).orElseThrow();money("0",account.getAvailable());money("0",account.getFrozen());money("300",account.getExpired());assertFalse(account.isTrialEligible());
        TrialGrant grant=grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).get(0);assertFalse(grant.isActive());money("300",grant.getExpired());money("100",cash(user,"CONTRACT"));
    }
    @Test void inactiveTrialAndGrantCacheAreRefreshedBeforeVersionedLockUpgrade() {
        clock("2030-01-01T00:00:00Z");claim(campaign(),user,"owned-stale-trial-grant");
        tx.executeWithoutResult(status->{
            TrialAccount oldAccount=trials.findByTenantIdAndId(1L,user).orElseThrow();TrialGrant oldGrant=grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).get(0);money("300",oldAccount.getAvailable());assertTrue(oldGrant.isActive());
            TransactionTemplate peer=new TransactionTemplate(tx.getTransactionManager());peer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            peer.executeWithoutResult(other->{TrialAccount account=trials.findByTenantIdAndId(1L,user).orElseThrow();account.setAvailable(BigDecimal.ZERO);account.setExpired(d("300"));account.setTrialEligible(false);trials.save(account);TrialGrant grant=grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).get(0);grant.setAvailable(BigDecimal.ZERO);grant.setExpired(d("300"));grant.setActive(false);grants.save(grant);});
            funds.lock(user);assertFalse(oldAccount.isTrialEligible());assertFalse(oldGrant.isActive());money("0",oldAccount.getAvailable());money("300",oldAccount.getExpired());money("300",oldGrant.getExpired());
        });
        assertFalse(trials.findByTenantIdAndId(1L,user).orElseThrow().isTrialEligible());money("100",cash(user,"CONTRACT"));
    }
    @Test void claimGrantAccountAndLedgerFailuresRemainOneTransaction() {
        for(String fault:Arrays.asList("trial-anchor","claim-grant","trial-account","trial-ledger")) {
            TrialFunds failing=new TrialFunds(trials,grants,ledger,users,assets,identity){@Override protected void checkpoint(String stage){if(stage.equals(fault))throw new IllegalStateException("injected "+stage);}};
            ReflectionTestUtils.setField(failing,"entityManager",em);ReflectionTestUtils.setField(failing,"clock",Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"),ZoneOffset.UTC));
            assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->failing.grant(user,d("100"),null,999L,"owned-claim-checkpoint",3)));
            assertFalse(trials.findByTenantIdAndId(1L,user).isPresent());assertTrue(grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).isEmpty());
            assertEquals(0L,ledger.findByTenantIdAndUserIdOrderByIdDesc(1L,user,PageRequest.of(0,20)).getTotalElements());money("100",cash(user,"CONTRACT"));
        }
    }

    @Test void cashOnlyLockDoesNotCreateTrialOrGrantRows() {
        tx.executeWithoutResult(status->{funds.lock(user);assertFalse(trials.findByTenantIdAndId(1L,user).isPresent());assertTrue(grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).isEmpty());});
        assertFalse(trials.findByTenantIdAndId(1L,user).isPresent());money("100",cash(user,"CONTRACT"));
    }
    void repeatableRead(){int isolation=em.unwrap(org.hibernate.Session.class).doReturningWork(java.sql.Connection::getTransactionIsolation);assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,isolation);}
    long connectionId(){return em.unwrap(org.hibernate.Session.class).doReturningWork(connection->{try(java.sql.Statement statement=connection.createStatement();java.sql.ResultSet result=statement.executeQuery("select connection_id()")){assertTrue(result.next());return result.getLong(1);}});}
    @Test void mysqlMissingAnchorDoesNotBlockAnotherUsersFirstGrant() throws Exception {
        Assumptions.assumeTrue(System.getProperty("activity.test.mysqlPort")!=null,"MySQL RR row/gap-lock assertion requires the original dedicated Activity fixture");
        Long other=newUser();CountDownLatch anchored=new CountDownLatch(1),release=new CountDownLatch(1);java.util.concurrent.atomic.AtomicLong heldConnection=new java.util.concurrent.atomic.AtomicLong();ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<?> held=pool.submit(scoped(()->{new TransactionTemplate(manager).executeWithoutResult(status->{repeatableRead();heldConnection.set(connectionId());funds.lockForGrant(other);assertFalse(grants.findByTenantIdAndUserIdAndRequestKey(1L,other,"owned-mysql-held-missing-key").isPresent());assertNull(funds.currentGrant(other,"owned-mysql-held-missing-key"));anchored.countDown();try{assertTrue(release.await(20,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException(error);}});return null;}));
            assertTrue(anchored.await(10,TimeUnit.SECONDS),"Second user must retain its anchor transaction before the first grant starts");
            Future<TrialAccount> first=pool.submit(scoped(()->new TransactionTemplate(manager).execute(status->{repeatableRead();assertNotEquals(heldConnection.get(),connectionId(),"The two transactions must use distinct MySQL connections");return funds.grant(user,d("100"),null,10001L,"owned-mysql-gap-first",3);} )));
            TrialAccount granted=first.get(10,TimeUnit.SECONDS);money("100",granted.getAvailable());release.countDown();held.get(10,TimeUnit.SECONDS);
            money("100",trials.findByTenantIdAndId(1L,user).orElseThrow().getAvailable());money("0",trials.findByTenantIdAndId(1L,other).orElseThrow().getAvailable());
            assertEquals(1,grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).size());assertTrue(grants.findByTenantIdAndUserIdOrderByIdAsc(1L,other).isEmpty());money("100",cash(user,"CONTRACT"));money("100",cash(other,"CONTRACT"));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void mysqlOldSnapshotCannotCreateOverAnAlreadyCommittedTrialAnchor() {
        Assumptions.assumeTrue(System.getProperty("activity.test.mysqlPort")!=null,"MySQL RR old-snapshot assertion requires the original dedicated Activity fixture");
        tx.executeWithoutResult(status->{
            repeatableRead();assertFalse(trials.findByTenantIdAndId(1L,user).isPresent());
            TransactionTemplate peer=new TransactionTemplate(manager);peer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Long committedId=peer.execute(other->{funds.grant(user,d("100"),null,10002L,"owned-mysql-hidden-anchor",3);return grants.findByTenantIdAndUserIdAndRequestKey(1L,user,"owned-mysql-hidden-anchor").orElseThrow().getId();});
            TrialGrant hiddenReference=em.getReference(TrialGrant.class,committedId);assertFalse(org.hibernate.Hibernate.isInitialized(hiddenReference),"The old RR persistence context must retain an uninitialized receipt proxy");
            assertFalse(grants.findByTenantIdAndUserIdAndRequestKey(1L,user,"owned-mysql-hidden-anchor").isPresent(),"The ordinary request lookup must remain a stale-snapshot hint");assertNotNull(funds.currentGrant(user,"owned-mysql-hidden-anchor"),"The user-locked receipt lookup must bypass that old absence snapshot");
            assertTrue(org.hibernate.Hibernate.isInitialized(hiddenReference),"Current row hydration must initialize the existing proxy without an old-snapshot load");assertEquals(committedId,hiddenReference.getId());assertEquals(user,hiddenReference.getUserId());money("100",hiddenReference.getAvailable());
            TrialAccount replay=funds.grant(user,d("100"),null,10002L,"owned-mysql-hidden-anchor",3);money("100",replay.getAvailable());money("100",replay.getGranted());
            TrialAccount repeated=funds.grant(user,d("100"),null,10002L,"owned-mysql-hidden-anchor",3);money("100",repeated.getAvailable());money("100",repeated.getGranted());
        });
        money("100",trials.findByTenantIdAndId(1L,user).orElseThrow().getAvailable());assertEquals(1,grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).size());assertEquals(1L,ledger.findByTenantIdAndUserIdOrderByIdDesc(1L,user,PageRequest.of(0,20)).getTotalElements());money("100",cash(user,"CONTRACT"));
    }
}
