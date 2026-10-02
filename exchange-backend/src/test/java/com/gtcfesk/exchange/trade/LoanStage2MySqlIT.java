package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.ExchangeBackendApplication;
import com.gtcfesk.exchange.admin.LoanReviewService;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.user.LoanService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real services and migrated MySQL. Test initial balances and loan snapshots are explicitly synthetic. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {ExchangeBackendApplication.class, OptionSettlementMySqlIT.Inputs.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoanStage2MySqlIT {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        OptionSettlementMySqlIT.properties(registry);
    }
    @Autowired LoanService loans;
    @Autowired LoanReviewService review;
    @Autowired LoanRecordRepository records;
    @Autowired UserAccountRepository users;
    @Autowired AssetAccountRepository assets;
    @Autowired JdbcTemplate database;
    TenantContext.Scope scope; long user;
    @BeforeEach void initialize() {
        scope = TenantContext.open(1L);
        assertEquals("MAINTENANCE", database.queryForObject("SELECT status FROM tenant WHERE id=1", String.class));
        UserAccount account = new UserAccount(); account.setEmail("stage2-loan-" + UUID.randomUUID() + "@example.invalid");
        account.setPasswordHash("not-a-login"); user = users.saveAndFlush(account).getId();
        AssetAccount balance = new AssetAccount(); balance.setUserId(user); balance.setCoin("FUND");
        balance.setAvailable(new BigDecimal("1000")); balance.setFrozen(BigDecimal.ZERO); assets.saveAndFlush(balance);
        assertFalse(org.mockito.Mockito.mockingDetails(loans).isMock());
        assertFalse(org.mockito.Mockito.mockingDetails(review).isMock());
    }
    @AfterEach void clear() { scope.close(); TenantContext.clear(); }
    LoanRecord loan(String status) {
        LoanRecord record = new LoanRecord(); record.setUserId(user); record.setAmount(new BigDecimal("100"));
        record.setDays(7); record.setDailyRate(BigDecimal.ZERO); record.setOverdueRate(BigDecimal.ZERO);
        record.setFreeDays(7); record.setTotalInterest(BigDecimal.ZERO); record.setRepaymentAmount(new BigDecimal("100"));
        record.setRealName("Stage2 synthetic initial loan"); record.setIdNumber("NOT-A-REAL-IDENTITY"); record.setStatus(status);
        if ("APPROVED".equals(status)) record.setApprovedAt(LocalDateTime.now());
        return records.saveAndFlush(record);
    }
    void balance(String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(assets.findByTenantIdAndUserIdAndCoin(1L,user,"FUND").get().getAvailable()));
    }
    @Test void maintenancePreventsSigningWithoutStateOrMoneyIncrement() {
        LoanRecord pending = loan("PENDING");
        assertThrows(AccessDeniedException.class, () -> loans.signContract(pending.getId(), user, "synthetic-test-signature"));
        assertEquals("PENDING", records.findByTenantIdAndId(1L,pending.getId()).get().getStatus()); balance("1000");
    }
    @Test void maintenanceRejectsNewDisbursementAtPolicyBoundary() {
        LoanRecord signed = loan("SIGNED");
        assertThrows(AccessDeniedException.class, () -> review.approveLoan(signed.getId()));
        assertEquals("SIGNED", records.findByTenantIdAndId(1L,signed.getId()).get().getStatus()); balance("1000");
    }
    @Test void maintenanceKeepsExistingRejectionAndRepaymentIdempotent() {
        LoanRecord pending = loan("PENDING"), funded = loan("APPROVED");
        review.rejectLoan(pending.getId(), "Stage2 controlled rejection"); review.rejectLoan(pending.getId(), "Stage2 controlled rejection");
        loans.earlyRepayment(funded.getId(), user); loans.earlyRepayment(funded.getId(), user);
        assertEquals("REJECTED", records.findByTenantIdAndId(1L,pending.getId()).get().getStatus());
        assertEquals("COMPLETED", records.findByTenantIdAndId(1L,funded.getId()).get().getStatus()); balance("900");
    }
}
