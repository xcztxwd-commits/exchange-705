package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.TradeValidation;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductTermsTest {
    FinancialProduct product() {
        FinancialProduct p = new FinancialProduct(); p.setTermDays(7); p.setDailyYieldRate(BigDecimal.ZERO);
        p.setRentalFee(BigDecimal.ZERO); p.setMinPurchase(BigDecimal.ONE); p.setMaxPurchase(BigDecimal.TEN);
        p.setPenaltyRate(BigDecimal.ZERO); return p;
    }
    LoanSetting setting() {
        LoanSetting s = new LoanSetting(); s.setDays(7); s.setFreeDays(7); s.setDailyRate(BigDecimal.ZERO); return s;
    }
    @Test void selectedFinanceTermsMustBeUsableByTheUsdFundLedger() {
        FinancialService.validateProduct(product());
        List<Consumer<FinancialProduct>> invalid = Arrays.asList(p -> p.setTermDays(0), p -> p.setCurrency("EUR"),
                p -> p.setDailyYieldRate(BigDecimal.ONE.negate()), p -> p.setRentalFee(BigDecimal.ONE.negate()),
                p -> p.setPenaltyRate(new BigDecimal("101")), p -> p.setMaxPurchase(new BigDecimal("0.5")),
                p -> p.setPenaltyRate(null));
        for (Consumer<FinancialProduct> change : invalid) { FinancialProduct p = product(); change.accept(p);
            assertThrows(BusinessException.class, () -> FinancialService.validateProduct(p)); }
    }
    @Test void selectedLoanTermsCannotCreateNegativeOrImpossibleLiabilities() {
        LoanService.validateSetting(setting());
        List<Consumer<LoanSetting>> invalid = Arrays.asList(s -> s.setDays(0), s -> s.setFreeDays(-1),
                s -> s.setFreeDays(8), s -> s.setDailyRate(BigDecimal.ONE.negate()), s -> s.setDailyRate(null),
                s -> s.setOverdueRate(BigDecimal.ONE.negate()), s -> s.setMinAmount(BigDecimal.ZERO),
                s -> {s.setMinAmount(BigDecimal.TEN); s.setMaxAmount(BigDecimal.ONE);});
        for (Consumer<LoanSetting> change : invalid) { LoanSetting s = setting(); change.accept(s);
            assertThrows(BusinessException.class, () -> LoanService.validateSetting(s)); }
        assertThrows(BusinessException.class, () -> TradeValidation.nonNegative(new BigDecimal("0.00000000000000001"), "rate"));
    }
    @Test void invalidSelectedProductStopsBeforeAccountLocksOrdersOrMoney() {
        try (com.gtcfesk.exchange.tenant.TenantContext.Scope ignored = com.gtcfesk.exchange.tenant.TenantContext.open(1L)) {
            FinancialProductRepository products = mock(FinancialProductRepository.class);
            FinancialOrderRepository orders = mock(FinancialOrderRepository.class); AssetAccountRepository assets = mock(AssetAccountRepository.class);
            UserAccountRepository users = mock(UserAccountRepository.class); FinancialService service = new FinancialService(products, orders, assets);
            ReflectionTestUtils.setField(service, "users", users);
            ReflectionTestUtils.setField(service, "tenantPolicy", mock(com.gtcfesk.exchange.control.TenantPolicyService.class));
            FinancialProduct p = product(); p.setCurrency("EUR"); when(products.findByTenantIdAndId(1L, 7L)).thenReturn(Optional.of(p));
            assertThrows(BusinessException.class, () -> service.purchaseProduct(1L, 7L, BigDecimal.TEN));
            verifyNoInteractions(users, assets, orders);
            LoanRecordRepository loans = mock(LoanRecordRepository.class); LoanSettingRepository settings = mock(LoanSettingRepository.class);
            KycRecordRepository kycs = mock(KycRecordRepository.class); LoanPersonalInfoService personal = mock(LoanPersonalInfoService.class);
            LoanService loan = new LoanService(loans, settings, users, kycs, assets, personal);
            ReflectionTestUtils.setField(loan, "tenantPolicy", mock(com.gtcfesk.exchange.control.TenantPolicyService.class));
            LoanSetting s = setting(); s.setFreeDays(8); when(settings.findByTenantIdAndId(1L, 7L)).thenReturn(Optional.of(s));
            assertThrows(BusinessException.class, () -> loan.createLoan(1L, BigDecimal.TEN, 7L));
            verifyNoInteractions(users, assets, loans, kycs, personal);
        }
    }
}
