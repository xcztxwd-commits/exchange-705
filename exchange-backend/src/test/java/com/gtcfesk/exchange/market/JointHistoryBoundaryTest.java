package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Joint boundary checks; the frozen strict S4 oracle remains unchanged and independently runs. */
class JointHistoryBoundaryTest extends TenantMarketTestContext {
    @Test void ambiguousLegacyFactsRejectInsteadOfChangingPublishedClose() throws Exception {
        S4KlineDifferentialTest frozen = new S4KlineDifferentialTest();
        frozen.setup();
        BusinessException rejected = assertThrows(BusinessException.class,
                frozen::frozenPrefixMultiTaskLateEventsDedupAndPublicationAreFieldEqual);
        assertTrue(rejected.getMessage().contains("HISTORY_LEGACY_ORDER_PENDING"));
        assertEquals(2, frozenRows(frozen));
        ControlHistoryStore store=(ControlHistoryStore)org.springframework.test.util.ReflectionTestUtils.getField(frozen,"store");
        ControlHistoryStore legacy=(ControlHistoryStore)org.springframework.test.util.ReflectionTestUtils.getField(frozen,"legacyStore");
        Map<String,Object> factsBefore=legacyFacts(store);
        Object observed=org.springframework.test.util.ReflectionTestUtils.getField(frozen,"source");
        @SuppressWarnings("unchecked") List<Map<String,Object>> calls=(List<Map<String,Object>>)org.springframework.test.util.ReflectionTestUtils.getField(observed,"calls");
        calls.clear(); org.springframework.test.util.ReflectionTestUtils.setField(observed,"record",true);
        try {
            // A fresh reader instance must not approve an incidental legacy tie or repair history while reading.
            ControlHistoryStore reopened=new ControlHistoryStore(store.db,
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(store.db.getDataSource()));
            for(ControlHistoryStore reader:List.of(store,reopened)) {
                BusinessException repeat=assertThrows(BusinessException.class,
                        ()->reader.readSnapshot(()->reader.visibleMixed(1,1700000400000L,1700000400000L)));
                assertTrue(repeat.getMessage().contains("HISTORY_LEGACY_ORDER_PENDING"));
            }
            assertThrows(org.springframework.security.access.AccessDeniedException.class,
                    ()->com.gtcfesk.exchange.tenant.TenantContext.open(2L));
            java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                worker.submit(()->{
                    try(com.gtcfesk.exchange.tenant.TenantContext.Scope foreign=com.gtcfesk.exchange.tenant.TenantContext.open(2L)) {
                        assertTrue(reopened.readSnapshot(()->reopened.visibleMixed(1,1700000400000L,1700000400000L)).isEmpty());
                    }
                }).get(10,java.util.concurrent.TimeUnit.SECONDS);
            } finally { worker.shutdownNow(); }
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(observed,"record",false); }
        assertFalse(calls.isEmpty());
        for(Map<String,Object> call:calls) {
            String sql=call.get("sql").toString().toUpperCase(Locale.ROOT);
            assertTrue(sql.startsWith("SELECT"),sql);
            assertFalse(sql.contains("FOR UPDATE") || sql.contains("LOCK IN SHARE MODE") || sql.contains("GET_LOCK"),sql);
        }
        assertEquals(factsBefore,legacyFacts(store),"Rejected/cross-tenant reads must preserve every captured historical field");
        Map<String,Object> evidence=new LinkedHashMap<>();
        evidence.put("scope","H2 fixed legacy facts; guard containment, not approval or MySQL legacy compatibility");
        evidence.put("legacyResponse",legacy.visibleMixed(1,1700000400000L,1700000400000L));
        evidence.put("rejection",rejected.getMessage());
        evidence.put("repeatAndFreshReaderReject",true);
        evidence.put("crossTenantRows",0);
        evidence.put("factsBeforeAndAfterEqual",true);
        evidence.put("readOnlySql",new ArrayList<>(calls));
        for(String table:List.of("market_source_tick","market_source_event","market_legacy_minute_snapshot","market_mixed_minute"))
            evidence.put(table,store.db.queryForList("SELECT * FROM "+table+" WHERE tenant_id=1"));
        assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_legacy_minute_snapshot WHERE tenant_id=1",Integer.class));
        java.nio.file.Path out=java.nio.file.Paths.get(System.getProperty("joint.history.evidence.dir","target/joint-history-boundary"));
        java.nio.file.Files.createDirectories(out);
        java.nio.file.Files.writeString(out.resolve("legacy-pending-"+UUID.randomUUID()+".json"),
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
    }
    private Map<String,Object> legacyFacts(ControlHistoryStore store) {
        Map<String,Object> facts=new TreeMap<>();
        for(String table:List.of("market_control_task","market_control_flow","market_control_publication",
                "market_control_sample","market_source_tick","market_source_event","market_source_candle",
                "market_legacy_minute_snapshot","market_mixed_minute","market_engine_runtime")) {
            List<String> rows=new ArrayList<>();
            for(Map<String,Object> row:store.db.queryForList("SELECT * FROM "+table+" WHERE tenant_id=1"))
                rows.add(store.encode(new TreeMap<>(row)));
            Collections.sort(rows); facts.put(table,rows);
        }
        return facts;
    }
    @Test void archivedMysql83And84RemainDistinctExactFullResponsesNotPublicationApproval() throws Exception {
        Map<String,Object> first=archived("old83.json","b9e5a6c0e64fd7a679e4e2578d332c0eccdf46426ccaeff63de059bde38c7e4b");
        Map<String,Object> second=archived("old84.json","68641c5893367ba787936fb9eafcc0e31f8419890ce47c40cda9000ea0002f48");
        assertEquals(200,first.get("ret")); assertEquals(200,second.get("ret"));
        Map<String,Object> a=ControlHistoryStore.rows(first).get(0),b=ControlHistoryStore.rows(second).get(0);
        assertEquals(new java.math.BigDecimal("83.1234567890123456"),a.get("close_price"));
        assertEquals(new java.math.BigDecimal("84.1234567890123456"),b.get("close_price"));
        assertEquals(new java.math.BigDecimal("80.1234567890123456"),a.get("open_price"));
        assertEquals(1700000400000L,ControlHistoryStore.time(a));
        for(String field:List.of("controlled","partial","historyReplaced")) assertEquals(true,a.get(field));
        assertNotEquals(first,second,"Neither archived legacy return can overwrite or approve the other");
        b.put("close_price",a.get("close_price"));
        assertEquals(first,second,"Every envelope/status/precision/volume field differs only at the archived close");
        try(java.io.InputStream input=getClass().getResourceAsStream("/history-original-returns/provenance.json")) {
            assertNotNull(input);
            com.fasterxml.jackson.databind.JsonNode provenance=new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            assertEquals("01a1013e-ccdc-7401-bd14-2e65818385ae",provenance.path("sourceThread").asText());
            assertFalse(provenance.path("productionPublicationReceipt").asBoolean());
            assertFalse(provenance.path("httpWsTransmissionReceipt").asBoolean());
            assertTrue(provenance.path("syntheticMysqlReaderReturns").asBoolean());
        }
    }
    private Map<String,Object> archived(String name,String expectedHash) throws Exception {
        try(java.io.InputStream input=getClass().getResourceAsStream("/history-original-returns/"+name)) {
            assertNotNull(input); byte[] bytes=input.readAllBytes();
            assertEquals(expectedHash,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(bytes,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        }
    }
    private int frozenRows(S4KlineDifferentialTest fixture) {
        ControlHistoryStore store = (ControlHistoryStore)org.springframework.test.util.ReflectionTestUtils.getField(fixture,"store");
        return store.db.queryForObject("SELECT COUNT(*) FROM market_source_tick WHERE tenant_id=1 AND symbol_id=1 AND received_at=1700000457000", Integer.class);
    }
    @Test void canonicalSourceCallbackNeverRequestsMoreThanFiveHundredMinuteSlots() {
        S2CanonicalKlineMergerTest fixture = new S2CanonicalKlineMergerTest();
        fixture.setup();
        long start=1700000400000L, end=start+999*60000L;
        List<long[]> calls = new ArrayList<>();
        Map<String,Object> result=fixture.merger.merge(1,"1m",1000,end,fixture.external(List.of(),0),null,true,(from,to)->{
            calls.add(new long[]{from,to});
            assertTrue(to-from+1<=500*60000L);
            List<Map<String,Object>> rows=new ArrayList<>();
            for(long at=Math.max(start,from);at<=Math.min(end,to);at+=60000L)rows.add(fixture.candle(at,90,95,89,91));
            return rows;
        });
        List<Map<String,Object>> rows=ControlHistoryStore.rows(result);
        assertEquals(1000,rows.size());
        assertEquals(2,calls.size());
        assertEquals(start,ControlHistoryStore.time(rows.get(0)));
        assertEquals(end,ControlHistoryStore.time(rows.get(999)));
    }
}
