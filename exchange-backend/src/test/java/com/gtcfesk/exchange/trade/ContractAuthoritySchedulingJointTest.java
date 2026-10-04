package com.gtcfesk.exchange.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.activity.TrialAccount;
import com.gtcfesk.exchange.activity.TrialGrant;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.market.ControlHistoryStore;
import com.gtcfesk.exchange.market.FundingQuoteAuthority;
import org.h2.api.Trigger;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/** Adds enabled, public-entry H2 authority proofs without changing the inherited fail-closed cases. */
class ContractAuthoritySchedulingJointTest extends ContractSchedulingS3Test {
    public static class FundingDmlTrigger implements Trigger {
        static final AtomicInteger writes = new AtomicInteger();
        @Override public void fire(Connection connection, Object[] before, Object[] after) { writes.incrementAndGet(); }
    }

    FundingQuoteAuthority enable(String price, boolean stopAfterPreparation) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("H2", connection.getMetaData().getDatabaseProductName(), "These added tests only authorize the inherited H2 fixture");
        }
        database.execute("CREATE TABLE IF NOT EXISTS market_engine_runtime(tenant_id BIGINT,symbol_id BIGINT,writer_generation BIGINT,control_revision BIGINT,snapshot_version BIGINT,quote_json VARCHAR(16000),owner_id VARCHAR(64),lease_until BIGINT,committed_at BIGINT,status_json VARCHAR(16000),PRIMARY KEY(tenant_id,symbol_id))");
        TradingSymbol config = symbols.findByTenantIdAndSymbol(1L, symbol).get();
        Map<String,Object> committed = quote(price);
        committed.put("tradeAvailable", true); committed.put("executionExpiresAt", System.currentTimeMillis() + 60000);
        committed.put("tenantId", 1L); committed.put("symbolId", config.getId()); committed.put("writerGeneration", 3L);
        committed.put("controlRevision", 4L); committed.put("quoteVersion", 5L);
        ReflectionTestUtils.invokeMethod(FundingQuoteAuthority.class, "stamp", committed, config);
        database.update("INSERT INTO market_engine_runtime VALUES(?,?,3,4,5,?,'joint-existing-engine',?,0,'{}')",
                1L, config.getId(), new ObjectMapper().writeValueAsString(committed), System.currentTimeMillis() + 15000);
        ControlHistoryStore store = new ControlHistoryStore(database, manager);
        FundingQuoteAuthority authority = stopAfterPreparation ? new FundingQuoteAuthority(store) {
            @Override public Map<String,Object> prepare(String code) {
                Map<String,Object> prepared = super.prepare(code);
                // A separately committed STOP invalidation occurs between preparation and funding locks.
                database.update("UPDATE market_engine_runtime SET control_revision=control_revision+1 WHERE tenant_id=1 AND symbol_id=?", config.getId());
                return prepared;
            }
        } : new FundingQuoteAuthority(store);
        ReflectionTestUtils.setField(service, "quoteAuthority", authority);
        ReflectionTestUtils.setField(service, "s3SchedulingEnabled", true);
        for (String table : Arrays.asList("user_account", "asset_account", "contract_order", "trial_account", "trial_grant", "trial_ledger", "s3_contract_success_audit")) {
            database.execute("CREATE TRIGGER IF NOT EXISTS joint_funding_dml_" + table + " AFTER INSERT, UPDATE, DELETE ON " + table
                    + " FOR EACH ROW CALL '" + FundingDmlTrigger.class.getName() + "'");
        }
        FundingDmlTrigger.writes.set(0);
        return authority;
    }

    @Test void enabledPublicCashUsdAutoCloseUsesRealAuthorityAndCommits() throws Exception {
        Long user = user("90", "10"); ContractOrder position = order(user, "CONTRACT"); enable("110", false);
        assertEquals("CLOSED", service.checkAndAutoCloseOrdersS3(1).get(position.getId()));
        assertEquals("CLOSED", load(position).getStatus()); money("110", load(position).getClosePrice());
        money("110", account(user).getAvailable()); money("0", account(user).getFrozen()); assertEquals(1, auditRows());
        assertTrue(FundingDmlTrigger.writes.get() > 0); verifyNoInteractions(quotes);
    }

    @Test void enabledPublicCashUsdtAutoCloseUsesFixedProtocolAndRealAuthority() throws Exception {
        TradingSymbol config = symbols.findByTenantIdAndSymbol(1L, symbol).get(); config.setQuoteCurrency("USDT"); symbols.saveAndFlush(config);
        Long user = user("90", "10"); ContractOrder position = order(user, "CONTRACT"); position.setQuoteCurrency("USDT"); orders.saveAndFlush(position);
        enable("110", false);
        assertEquals("CLOSED", service.checkAndAutoCloseOrdersS3(1).get(position.getId()));
        assertEquals("CLOSED", load(position).getStatus()); money("110", account(user).getAvailable()); money("0", account(user).getFrozen());
        assertEquals(1, auditRows()); verifyNoInteractions(quotes);
    }

    @Test void enabledPublicForcePortfolioClosesEntireCashUnitWithRealAuthority() throws Exception {
        Long user = user("0", "20"); ContractOrder first = order(user, "CONTRACT"), second = order(user, "CONTRACT"); enable("80", false);
        assertEquals(2, service.checkAndForceClosePortfolioS3(user, "CONTRACT"));
        assertEquals("CLOSED", load(first).getStatus()); assertEquals("CLOSED", load(second).getStatus());
        money("0", account(user).getAvailable()); money("0", account(user).getFrozen()); assertEquals(3, auditRows());
        verifyNoInteractions(quotes);
    }

    @Test void enabledPublicStopRevisionRejectsWithoutAnyFundingOrAuditDml() throws Exception {
        Long user = user("90", "10"); ContractOrder position = order(user, "CONTRACT"); enable("110", true);
        assertEquals("RETRY:BusinessException", service.checkAndAutoCloseOrdersS3(1).get(position.getId()));
        assertEquals(0, FundingDmlTrigger.writes.get(), "Even rolled-back funding/maintenance DML must not precede current authority");
        unchanged(user, "90", "10", position); assertEquals(0, auditRows()); verifyNoInteractions(quotes);
    }

    @Test void enabledPublicHealthySkipNeverRunsExpiredTrialMaintenance() throws Exception {
        Long user = user("0", "10"); ContractOrder position = order(user, "CONTRACT");
        TrialAccount trial = new TrialAccount(); trial.setUserId(user); trial.setAvailable(number("12")); trial.setGranted(number("12")); trial.setTrialEligible(true); trials.saveAndFlush(trial);
        TrialGrant grant = new TrialGrant(); grant.setUserId(user); grant.setRequestKey("joint-expired-" + user);
        grant.setClaimedAt(LocalDateTime.now().minusDays(2)); grant.setExpiresAt(LocalDateTime.now().minusDays(1)); grant.setAvailable(number("12")); grants.saveAndFlush(grant);
        long trialVersion = trial.getRowVersion(), grantVersion = grant.getRowVersion();
        enable("101", false);
        assertEquals("SKIPPED", service.checkAndAutoCloseOrdersS3(1).get(position.getId()));
        assertEquals(0, FundingDmlTrigger.writes.get(), "A healthy cash skip must not create LEGACY grants or expire promotional balances");
        unchanged(user, "0", "10", position); assertEquals(0, auditRows());
        TrialAccount currentTrial = trials.findByTenantIdAndId(1L, user).get(); TrialGrant currentGrant = grants.findByTenantIdAndId(1L, grant.getId()).get();
        money("12", currentTrial.getAvailable()); money("0", currentTrial.getExpired()); assertTrue(currentTrial.isTrialEligible());
        money("12", currentGrant.getAvailable()); money("0", currentGrant.getExpired()); assertTrue(currentGrant.isActive());
        assertEquals(trialVersion, currentTrial.getRowVersion()); assertEquals(grantVersion, currentGrant.getRowVersion());
        assertEquals(0L, ledger.findByTenantIdAndUserIdOrderByIdDesc(1L, user, PageRequest.of(0, 20)).getTotalElements());
        verifyNoInteractions(quotes);
    }
}
