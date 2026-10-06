package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Latest-only lookup must not search the entire mirrored ledger for an unmatched legacy tick. */
class LatestSourceLookupTest extends TenantMarketTestContext {
    static final String PREDICATE = "symbol_id=? AND received_at<=? AND source_time<=?";
    static final String ORDER = " ORDER BY source_time DESC,received_at DESC,event_sequence DESC LIMIT 1";

    @Test void latestOnlyEliminatesAntiJoinButHistoryStillDeduplicates() {
        String latest = ControlHistoryStore.sourceEvents(PREDICATE, true);
        assertFalse(latest.contains("NOT EXISTS"), "Latest duplicate is already defeated by the positive event sequence; do not scan all mirrors");
        assertTrue(latest.contains("ORDER BY t.source_time DESC LIMIT 1"), "Tick PK is unique per tenant/symbol/source time; do not filesort the full ledger");
        assertTrue(ControlHistoryStore.sourceEvents(PREDICATE, false).contains("NOT EXISTS"), "Full history still needs exact duplicate suppression");
    }

    @Test void cutoffsTiesLateArrivalsLegacyRowsAndTenantsMatchOldLatestResult() {
        JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:latest_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        MarketSqlFixture.schema(db);
        db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1),(2,1),(3,2)");
        for (int i=1; i<=100; i++) {
            db.update("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(1,?,1,?,?,?)", "mirror-"+i, i*1000, i*1000+1, i);
            db.update("INSERT INTO market_source_tick(tenant_id,symbol_id,source_time,received_at,price) VALUES(1,1,?,?,?)", i*1000, i*1000+1, i);
        }
        db.update("INSERT INTO market_source_tick VALUES(1,1,101000,101001,101)");
        db.update("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(1,'tie',1,100000,100001,102),(1,'late',1,99000,102001,103),(1,'other',2,104000,104001,999),(2,'other-tenant',3,105000,105001,999)");
        // A replaced legacy tick can have a newer received time than its matching source event.
        db.update("UPDATE market_source_tick SET received_at=103001,price=104 WHERE tenant_id=1 AND symbol_id=1 AND source_time=98000");
        for (long cutoff : new long[]{0, 1000, 1001, 50001, 100001, 101001, 102001, 103001, 200000}) {
            List<Map<String,Object>> expected = db.queryForList("SELECT price,source_time FROM ("+S1HistoryTest.LEGACY+") ticks WHERE "+PREDICATE+ORDER, 1,cutoff,cutoff);
            List<Map<String,Object>> actual = db.queryForList("SELECT price,source_time FROM ("+ControlHistoryStore.sourceEvents(PREDICATE,true)+") ticks"+ORDER, 1,cutoff,cutoff,1,cutoff,cutoff);
            assertEquals(expected, actual, "cutoff="+cutoff);
        }
    }
}
