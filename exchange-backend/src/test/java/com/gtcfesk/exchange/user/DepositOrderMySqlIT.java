package com.gtcfesk.exchange.user;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
/** Executes the inherited production-service/real-transaction matrix on disposable MySQL only. */
public class DepositOrderMySqlIT extends DepositOrderServiceTest {
 @BeforeAll static void requireMySql(){assertNotNull(System.getenv("DEPOSIT_TEST_JDBC"),"Run scripts/deposit-orders/Test-MySql.ps1; missing environment is not a pass");}
 @Test void mysql57Only()throws Exception{try(java.sql.Connection c=dataSource.getConnection()){assertTrue(c.getMetaData().getDatabaseProductVersion().startsWith("5.7."));assertTrue(c.getCatalog().startsWith("deposit_order_test_"));}}

 @Test void migratedHistoricalFactsRemainUnknown(){for(com.gtcfesk.exchange.entity.DepositRecord d:records.findByUserIdOrderByCreatedAtDesc(1L)){assertEquals("LEGACY_UNKNOWN",d.getSource());assertTrue(d.getOrderNo().startsWith("LEGACY-DEP-"));assertNull(d.getReviewedAt());assertNull(d.getCreatedById());assertNull(d.getCreditedAt());assertNull(d.getFeeRate());assertFalse(credits.findByDepositRecordId(d.getId()).isPresent());}equal("25",assets.findByUserIdAndCoin(1L,"FUND").get().getAvailable());}
}
